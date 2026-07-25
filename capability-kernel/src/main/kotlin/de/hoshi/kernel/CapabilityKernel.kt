package de.hoshi.kernel

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry

/**
 * **CapabilityKernel** — das deterministische Tat-Gate, portiert aus Hoshi 0.5
 * `CapabilityBroker`. Das Herz von „vertrauen": **DEFAULT DENY-ALL**. Eine Tat
 * wird NUR ausgeführt, wenn sie explizit auf der Allowlist steht UND ihr
 * data-Payload Key-für-Key gegen die Policy passt.
 *
 * Bewusst Spring-frei (reine Kotlin-Funktion + DTOs). Die WebFilter-/DI-
 * Verdrahtung kommt später in `:web-inbound`; hier lebt nur die testbare
 * Entscheidung.
 *
 * Politik (in dieser Reihenfolge, ALLE müssen passen ⇒ Grant):
 *  0. `enabled=false` ⇒ Hard-DENY (NICHT „alles erlaubt"). Das Schloss lässt
 *     sich nicht per Flag aufdrücken.
 *  1. Slash-Injection-Schutz für domain/service.
 *  2. `"$domain.$service"` muss in der Allowlist stehen — sonst Deny.
 *  3. entityId/areaId müssen in den Scope passen (leerer Scope + Target ⇒ Deny).
 *  4. jeder data-Key muss in `allowKeys` stehen — sonst Deny (rekursiv für
 *     verschachtelte Maps/Listen).
 *  5. numerische Keys innerhalb [Range] — sonst Deny.
 *
 * Bei Grant liefert der Kernel die **normalisierte data** zurück — der Aufrufer
 * baut den Effekt-Body NUR daraus, nie aus dem Roh-Input.
 *
 * **Mehrsprachige Absage seit 2026-07-25** (Andi: „es soll multilingual werden.
 * von A-Z"): die GESPROCHENE Verweigerung ([Decision.Deny.phrase]) kommt aus dem
 * `CapabilityDenyPack` der übergebenen [Language], nicht mehr aus Literalen hier.
 * Der [Decision.Deny.reason] bleibt bewusst **deutschsprachig und unübersetzt** —
 * er ist Log-/Diagnose-Text für Andi, kein Produkt-Text.
 *
 * **Architektur-Hinweis:** `:capability-kernel` hängt bereits an `:core-domain`
 * (Abhängigkeit zeigt nach INNEN, s. `ArchitectureGuardTest` — verboten ist nur
 * die Gegenrichtung `core → kernel`). Der Sprach-Katalog liegt im Kern, damit ihn
 * Kernel, Adapter und Pipeline aus DERSELBEN Quelle lesen; es gibt keinen zweiten
 * i18n-Mechanismus. Die Sprache kommt als **Parameter** herein (kein ThreadLocal,
 * kein globaler Zustand — der Kernel bleibt eine reine Funktion).
 */
class CapabilityKernel(
    private val policy: CapabilityPolicy = CapabilityPolicy(),
) {

    /**
     * Entscheidung des Kernels — eine **sealed class** mit Grund, KEIN Boolean.
     * Ein Boolean verleitet zum `if (permit(...)) {}` ohne else-Zweig
     * (fail-open). Die sealed class erzwingt im `when` den Deny-Pfad.
     */
    sealed class Decision {
        /** Erlaubt — [normalizedData] enthält NUR geprüfte, erlaubte Keys. */
        data class Grant(val normalizedData: Map<String, Any?>) : Decision()

        /** Verweigert — [reason] (intern, fürs Log) + [phrase] (warm, für die Stimme). */
        data class Deny(val reason: String, val phrase: String) : Decision()
    }

    /** Bequemer Eintritt für [WriteEffectingTool]-Träger: jede schreibende Tat MUSS hier durch. */
    fun permit(tool: WriteEffectingTool, language: Language = Language.DEFAULT): Decision =
        permit(tool.domain, tool.service, tool.entityId, tool.data, language)

    /**
     * Deterministische Tat-Prüfung. Eine Methode, ein Verdict.
     *
     * @param entityId betroffene Entity (Effekt-Target); null falls keine.
     * @param data der angeforderte data-Payload (Roh-Input).
     * @param language die Sprache des Turns — bestimmt AUSSCHLIESSLICH die
     *   gesprochene [Decision.Deny.phrase], NIE die Entscheidung selbst. Default
     *   [Language.DEFAULT] (DE) ⇒ jeder bestehende Aufrufer bleibt byte-identisch.
     */
    fun permit(
        domain: String,
        service: String,
        entityId: String?,
        data: Map<String, Any?>,
        language: Language = Language.DEFAULT,
    ): Decision {
        // (0) Hard-Off ⇒ Hard-DENY, NICHT „alles erlaubt".
        if (!policy.enabled) {
            return deny("kernel disabled (enabled=false)", language)
        }
        // (1) Slash-Injection-Schutz für domain/service.
        if ('/' in domain || '/' in service) {
            return deny("ungültiger domain/service (Slash)", language, phraseOf(language).invalid)
        }
        val key = "$domain.$service"
        val permit = policy.effectivePermits().firstOrNull { it.domain == domain && it.service == service }
            ?: return deny("capability '$key' nicht freigegeben", language)   // DEFAULT DENY-ALL

        // (3) Targeting-Scopes. Leerer Scope + vorhandenes Target ⇒ Deny.
        val areaId: String? = if (AREA_KEY in data) {
            (data[AREA_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: return deny("area_id muss ein nicht-leerer String sein für '$key'", language)
        } else {
            null
        }
        if (entityId != null && permit.entityScope.none { glob(it, entityId) }) {
            return deny("entity '$entityId' außerhalb des Scopes für '$key'", language)
        }
        if (areaId != null && permit.areaScope.none { glob(it, areaId) }) {
            return deny("area '$areaId' außerhalb des Area-Scopes für '$key'", language)
        }
        if (entityId == null && areaId == null && permit.entityScope.isNotEmpty()) {
            // Targetlos wäre „alle Entities der Domain" — die breiteste mögliche Tat.
            return deny("entity_id erforderlich für '$key'", language)
        }

        // (4)+(5) data-Keys + Ranges, rekursiv (verschachtelte Maps/Listen).
        val clean = LinkedHashMap<String, Any?>()
        for ((k, v) in data) {
            if (k == AREA_KEY) {
                clean[k] = areaId
                continue
            }
            if (k !in permit.data.allowKeys) {
                return deny("data-Key '$k' nicht erlaubt für '$key'", language)
            }
            when (val err = validateValue(k, v, permit, key, language)) {
                null -> clean[k] = v
                else -> return err
            }
        }
        return Decision.Grant(clean)
    }

    /**
     * Rekursive Wert-Prüfung. Numerische Range-Verletzung ⇒ Deny. Verschachtelte
     * Maps: jeder Schlüssel muss EBENFALLS in allowKeys stehen. Listen: jedes
     * Element rekursiv. Gibt null zurück, wenn ok; sonst die [Decision.Deny].
     */
    private fun validateValue(
        k: String,
        v: Any?,
        permit: Permit,
        key: String,
        language: Language,
    ): Decision.Deny? {
        permit.data.ranges[k]?.let { r ->
            val n = (v as? Number)?.toDouble()
                ?: return deny("data-Key '$k' muss numerisch sein für '$key'", language)
            if (n < r.min || n > r.max) {
                return deny("'$k'=$n außerhalb [${r.min}..${r.max}] für '$key'", language)
            }
        }
        when (v) {
            is Map<*, *> -> for ((nk, nv) in v) {
                val nks = nk as? String
                    ?: return deny("verschachtelter data-Key nicht String für '$key'", language)
                if (nks !in permit.data.allowKeys) {
                    return deny("verschachtelter data-Key '$nks' nicht erlaubt für '$key'", language)
                }
                validateValue(nks, nv, permit, key, language)?.let { return it }
            }
            is List<*> -> for (item in v) {
                validateValue(k, item, permit, key, language)?.let { return it }
            }
        }
        return null
    }

    /**
     * [reason] bleibt DE (Log/Diagnose für Andi), [phrase] folgt der Sprache
     * (Produkt-Text, wird gesprochen).
     */
    private fun deny(
        reason: String,
        language: Language,
        phrase: String = phraseOf(language).refusals.random(),
    ): Decision.Deny = Decision.Deny(reason, phrase)

    /** Die Absage-Texte der Turn-Sprache — EINE Quelle (der Sprachpaket-Katalog im Kern). */
    private fun phraseOf(language: Language) =
        LanguagePackRegistry.forLanguage(language).capabilityDeny

    /**
     * Glob: nur `*` ist Wildcard (⇒ `.*`), jedes andere Zeichen wird einzeln
     * Regex-escaped (kein Regex-Injection).
     */
    private fun glob(pat: String, s: String): Boolean {
        val rx = buildString {
            append('^')
            for (c in pat) {
                if (c == '*') append(".*") else append(Regex.escape(c.toString()))
            }
            append('$')
        }
        return Regex(rx).matches(s)
    }

    companion object {
        /** Targeting-Key im data-Payload — gegen [Permit.areaScope] geprüft, nie gegen allowKeys. */
        private const val AREA_KEY = "area_id"

        /**
         * Der deutsche Anker für die strukturelle Absage. Die Laufzeit zieht den
         * Text seit 2026-07-25 aus `LangDe.CAPABILITY_DENY.invalid` (bzw. dem Pack
         * der aktiven Sprache) — diese Konstante bleibt als **byte-genaue
         * DE-Referenz** bestehen (s. `CapabilityKernelTest`) und dokumentiert, dass
         * der deutsche Wortlaut sich nicht verschoben hat.
         *
         * Die warmen Absage-Varianten (früher `REFUSALS` hier) leben jetzt
         * ebenfalls im Sprachpaket — mehrsprachig statt vierfach deutsch.
         */
        const val PHRASE_INVALID = "Ungültiger domain oder service"
    }
}

/**
 * Marker für **schreibwirkende** Taten — alles, das physisch einen Aktuator
 * anstößt (HA-Service-Call, Store-Mutation), nicht nur liest.
 *
 * Jeder Träger MUSS seine Tat VOR der Ausführung durch den [CapabilityKernel]
 * gaten (DEFAULT DENY-ALL). Das Interface trägt genau die Felder, die der Kernel
 * prüft — so kann der Coverage-Test jeden Träger automatisch erfassen.
 */
interface WriteEffectingTool {
    val domain: String
    val service: String
    val entityId: String?
    val data: Map<String, Any?>
}

/**
 * Manifest des [CapabilityKernel] — die **Allowlist der erlaubten Taten** plus
 * die **data-Payload-Politik** pro Eintrag.
 *
 * Default ist NICHT leer-und-offen, sondern eine kleine fest eingebaute
 * Schreib-Allowlist ([DEFAULT_PERMITS]). Eine fehlende/leere Konfig ⇒ es gilt
 * diese sichere Default-Allowlist, NICHT „alles erlaubt" (fail-closed).
 */
data class CapabilityPolicy(
    /** Hard-Off ⇒ im Kernel als Hard-Deny behandelt (nicht „alles erlaubt"). Default ON. */
    val enabled: Boolean = true,
    /** Die wirksame Allowlist. Default: [DEFAULT_PERMITS]. Leere Liste ⇒ Fallback auf Default. */
    val permits: List<Permit> = DEFAULT_PERMITS,
) {
    /** Wirksame Permits: leere Liste fällt auf [DEFAULT_PERMITS] zurück, NICHT auf „alles erlauben". */
    fun effectivePermits(): List<Permit> = permits.ifEmpty { DEFAULT_PERMITS }

    companion object {
        /**
         * Fest eingebauter, konservativer Schreib-Kern. Nur Licht-An/Aus, Szenen,
         * Einkaufsliste, Klima (mit harten Grenzen). KEIN Schloss, KEINE
         * Alarmanlage, KEIN cover — die kommen nur durch bewusste Konfig.
         */
        val DEFAULT_PERMITS: List<Permit> = listOf(
            Permit(
                domain = "light", service = "turn_on",
                entityScope = listOf("light.*"),
                areaScope = listOf("*"),
                data = DataPolicy(
                    allowKeys = listOf("brightness_pct", "color_temp_kelvin", "transition", "color_name"),
                    ranges = mapOf(
                        "brightness_pct" to Range(0.0, 100.0),
                        "color_temp_kelvin" to Range(2000.0, 6500.0),
                    ),
                ),
            ),
            Permit(
                domain = "light", service = "turn_off",
                entityScope = listOf("light.*"),
                areaScope = listOf("*"),
                data = DataPolicy(allowKeys = listOf("transition")),
            ),
            Permit(
                domain = "scene", service = "turn_on",
                entityScope = listOf("scene.*"),
                areaScope = listOf("*"),
                data = DataPolicy(allowKeys = emptyList()),
            ),
            Permit(
                domain = "todo", service = "add_item",
                entityScope = listOf("todo.einkaufsliste"),
                data = DataPolicy(allowKeys = listOf("item")),
            ),
            Permit(
                domain = "climate", service = "set_temperature",
                entityScope = listOf("climate.*"),
                areaScope = listOf("*"),
                data = DataPolicy(
                    allowKeys = listOf("temperature", "hvac_mode"),
                    ranges = mapOf("temperature" to Range(12.0, 28.0)),
                ),
            ),
        )
    }
}

/**
 * Ein erlaubtes (domain,service)-Paar mit Scope + data-Politik.
 * `entityScope`/`areaScope` LEER bedeutet bewusst „kein Target erlaubt".
 */
data class Permit(
    val domain: String = "",
    val service: String = "",
    val entityScope: List<String> = emptyList(),
    val areaScope: List<String> = emptyList(),
    val data: DataPolicy = DataPolicy(),
)

data class DataPolicy(
    /** Whitelist der erlaubten data-Keys. Alles andere ⇒ Deny (kein Smuggle). */
    val allowKeys: List<String> = emptyList(),
    /** Numerische Wertebereiche je Key (z.B. brightness_pct ⇒ 0..100). */
    val ranges: Map<String, Range> = emptyMap(),
)

data class Range(
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
)
