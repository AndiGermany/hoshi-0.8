package de.hoshi.kernel

/**
 * **EgressPort** — die einzige Naht, durch die JEDER Egress (Cloud-Call, Log,
 * Telemetrie) geht. Portiert die Sanitisierungs-Essenz aus Hoshi 0.5
 * `PrivacySanitizer`: Sprecher-Name, IDs, `haBaseUrl` und URLs werden vor dem
 * Verlassen des lokalen Systems maskiert.
 *
 * Das ist der architektonische Riegel gegen **SEC-1 (OpenClaw-Leck)**: weil
 * jeder Egress hier durchläuft, kann eine private ID/URL das System nicht mehr
 * „aus Versehen" verlassen — die Naht ist die Invariante, nicht die Disziplin
 * des Aufrufers.
 *
 * Andi-Maxime: „Online darf nie Zugriff auf private Daten haben. Lokales bleibt
 * lokal." Bewusst Spring-frei + Regex-basiert (kein Modell sieht den Klartext).
 *
 * @param speakerName aktiver Sprecher-Name (Wort-genau, case-insensitiv maskiert).
 * @param haBaseUrl die Home-Assistant-Basis-URL (literal, vor generischer
 *        URL-Maske, damit sie als `[HA_URL]` und nicht als `[URL]` erscheint).
 */
class EgressPort(
    private val speakerName: String? = null,
    private val haBaseUrl: String? = null,
) {

    /**
     * Maskiert eine Egress-Payload. Reihenfolge ist bewusst: spezifischste
     * Geheimnisse zuerst (haBaseUrl, dann Sprecher-Name), dann generische
     * Muster (URLs, E-Mail, UUIDs, HA-Entity-IDs). [SanitizedPayload.redactions]
     * bildet Token → Original für Audit ab — NIE wird Klartext ge-egress-t.
     */
    fun sanitize(payload: String): SanitizedPayload {
        if (payload.isBlank()) {
            return SanitizedPayload(sanitizedText = payload, redactions = emptyMap())
        }

        var current = payload
        val redactions = LinkedHashMap<String, String>()

        // 1. haBaseUrl literal (spezifischer als generische URL).
        haBaseUrl?.takeIf { it.isNotBlank() }?.let { base ->
            current = replaceLiteral(current, base, "HA_URL", redactions)
        }
        // 2. Sprecher-Name, Wort-genau (kein „Andi" in „Andinger").
        speakerName?.takeIf { it.isNotBlank() }?.let { name ->
            current = replaceWord(current, name, "NAME", redactions)
        }
        // 3. Generische strukturierte PII.
        current = replacePattern(current, URL_PATTERN, "URL", redactions)
        current = replacePattern(current, EMAIL_PATTERN, "EMAIL", redactions)
        current = replacePattern(current, UUID_PATTERN, "ID", redactions)
        current = replacePattern(current, ENTITY_ID_PATTERN, "ID", redactions)

        return SanitizedPayload(sanitizedText = current, redactions = redactions.toMap())
    }

    /**
     * Der zweite Riegel: entscheidet, ob eine Payload die Box überhaupt
     * verlassen darf. Manche Inhalte werden NICHT nur maskiert, sondern
     * **komplett geblockt** ([EgressDecision.Blocked]) — sie dürfen NIE in
     * einen Cloud-Call gelangen (gespeicherte Memory-Fakten, Sprecher-IDs,
     * interne Tokens/Secrets, die HA-Base-URL, private LAN-IPs).
     *
     * Konservativ by design: lieber einmal zu viel blocken (User bleibt
     * lokal) als ein einziges Mal leaken — die privacy-sichere Richtung.
     * Harmlose Payloads laufen durch [sanitize] und werden als
     * [EgressDecision.Allowed] mit maskierter Payload zurückgegeben.
     */
    fun guard(payload: String): EgressDecision {
        val blocked = hardBlockCategory(payload)
        if (blocked != null) return EgressDecision.Blocked(blocked)
        return EgressDecision.Allowed(sanitize(payload))
    }

    /**
     * Die UMKEHRUNG von [sanitize]: ersetzt die Masken-Token in einem Text
     * (typisch einer Cloud-ANTWORT) wieder durch die Originalwerte aus
     * [redactions] — BEVOR der User sie sieht. So bleibt die maskierte
     * Anfrage cloud-tauglich, der User bekommt aber die echten Werte zurück.
     *
     * Robust: unbekannte Token (nicht in [redactions]) bleiben unverändert
     * stehen; case-insensitiv, falls die Cloud `[name_1]` statt `[NAME_1]`
     * liefert. Token + Original werden escaped → wirft NIE.
     */
    fun reconstruct(text: String, redactions: Map<String, String>): String {
        if (text.isEmpty() || redactions.isEmpty()) return text
        var result = text
        for ((token, original) in redactions) {
            if (token.isEmpty()) continue
            val pattern = Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(original))
        }
        return result
    }

    /**
     * Liefert die erste zutreffende Hard-Block-Kategorie oder `null`
     * (= darf, ggf. maskiert, raus). Reihenfolge: spezifischste/konfigurierte
     * Geheimnisse zuerst, generischste Muster (LAN-IP) zuletzt — so trägt der
     * Block-Reason die aussagekräftigste Kategorie (z.B. HA_BASE_URL statt
     * LAN_IP für eine HA-URL, die ja auch eine LAN-IP enthält). Es wird NIE
     * Klartext zurückgegeben, nur die Kategorie.
     */
    private fun hardBlockCategory(payload: String): BlockCategory? {
        if (payload.isBlank()) return null
        // 1. Konfigurierte HA-Base-URL literal (spezifischstes Geheimnis).
        haBaseUrl?.takeIf { it.isNotBlank() }?.let { base ->
            if (payload.contains(base, ignoreCase = true)) return BlockCategory.HA_BASE_URL
        }
        // 2. Sprecher-ID (spk-UUID, 0.5 Retro-#12-Format).
        if (SPEAKER_ID_PATTERN.containsMatchIn(payload)) return BlockCategory.SPEAKER_ID
        // 3. Interne Tokens / Secrets (JWT/Bearer/API-Key/langer Hex-Secret).
        if (INTERNAL_TOKEN_PATTERNS.any { it.containsMatchIn(payload) }) return BlockCategory.INTERNAL_TOKEN
        // 4. Memory-Referenzen / gespeicherte persönliche Fakten.
        if (MEMORY_REFERENCE_PATTERNS.any { it.containsMatchIn(payload) }) return BlockCategory.MEMORY_REFERENCE
        // 5. Private LAN-IP (generischste Kategorie zuletzt).
        if (LAN_IP_PATTERN.containsMatchIn(payload)) return BlockCategory.LAN_IP
        return null
    }

    private fun replaceLiteral(
        text: String,
        literal: String,
        type: String,
        redactions: MutableMap<String, String>,
    ): String {
        if (!text.contains(literal)) return text
        val token = nextToken(type, redactions)
        redactions[token] = literal
        return text.replace(literal, token)
    }

    private fun replaceWord(
        text: String,
        word: String,
        type: String,
        redactions: MutableMap<String, String>,
    ): String {
        // \b ist für Umlaute unzuverlässig → explizite Look-Around auf Nicht-Wort-Zeichen.
        val pattern = Regex(
            "(?<![\\p{L}\\d])${Regex.escape(word)}(?![\\p{L}\\d])",
            RegexOption.IGNORE_CASE,
        )
        return replacePattern(text, pattern, type, redactions, originalOverride = word)
    }

    private fun replacePattern(
        text: String,
        pattern: Regex,
        type: String,
        redactions: MutableMap<String, String>,
        originalOverride: String? = null,
    ): String {
        var result = text
        var matched = pattern.find(result)
        while (matched != null) {
            val original = originalOverride ?: matched.value
            val token = nextToken(type, redactions)
            redactions[token] = original
            result = result.replaceRange(matched.range, token)
            matched = pattern.find(result, matched.range.first + token.length)
        }
        return result
    }

    private fun nextToken(type: String, redactions: Map<String, *>): String {
        val n = redactions.keys.count { it.startsWith("[${type}_") } + 1
        return "[${type}_$n]"
    }

    companion object {
        private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val EMAIL_PATTERN = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
        private val UUID_PATTERN =
            Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
        // HA-Entity-ID: domain.objekt (z.B. light.wohnzimmer) — eindeutig genug für Egress-Maskierung.
        private val ENTITY_ID_PATTERN =
            Regex("""\b[a-z_]+\.[a-z0-9_]{2,}\b""")

        // ── Hard-Block-Muster (NICHT maskieren — komplett blocken) ──────────
        //
        // Diese Inhalte verlassen die Box NIE (auch nicht maskiert). Konservativ
        // gehalten: lieber zu oft blocken als ein Leak — die privacy-sichere
        // Richtung. Reines Regex (kein Modell sieht den Klartext).

        // Sprecher-ID: das LIVE-gemessene Format spk-<UUID> (0.5 Retro-#12,
        // lowercase + Bindestrich). Lenient genug, um auch kurze Test-ids zu
        // fangen — eine spk-ID hat in einem Cloud-Call NICHTS zu suchen.
        private val SPEAKER_ID_PATTERN =
            Regex("""\bspk-[0-9a-f-]{6,}\b""", RegexOption.IGNORE_CASE)

        // Interne Tokens / Secrets. HA-Long-Lived-Access-Tokens sind JWTs
        // (eyJ…header.payload.signature). Plus generische Bearer-Header,
        // sk-API-Keys und lange Hex-Secrets/Hashes.
        private val INTERNAL_TOKEN_PATTERNS = listOf(
            Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}"""),
            Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]{12,}""", RegexOption.IGNORE_CASE),
            Regex("""\bsk-[A-Za-z0-9]{16,}\b"""),
            Regex("""\b[0-9a-f]{32,}\b""", RegexOption.IGNORE_CASE),
        )

        // Memory-Referenzen / gespeicherte persönliche Fakten — lokale
        // Kontext-Fragen, die die Cloud nicht beantworten kann/soll (0.5
        // PrivacySanitizer.memoryReferencePatterns). \b vor ASCII-Wortanfang
        // ist hier zuverlässig (anders als bei Umlaut-Wörtern).
        private val MEMORY_REFERENCE_PATTERNS = listOf(
            Regex("""\berinnerst du dich\b""", RegexOption.IGNORE_CASE),
            Regex("""\bweißt du noch\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwas haben wir besprochen\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwas hatten wir\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:was|wovon) (?:habe|hatte) ich (?:dir|gesagt|erzählt)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bgestern haben wir\b""", RegexOption.IGNORE_CASE),
            Regex("""\bletztes mal\b""", RegexOption.IGNORE_CASE),
        )

        // Private LAN-IPs (RFC-1918 + Loopback): dürfen nie zur Cloud.
        private val LAN_IP_PATTERN = Regex(
            """\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}""" +
                """|172\.(?:1[6-9]|2[0-9]|3[01])\.\d{1,3}\.\d{1,3}""" +
                """|192\.168\.\d{1,3}\.\d{1,3}""" +
                """|127\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""",
        )
    }
}

/**
 * Ergebnis der Egress-Sanitisierung. [sanitizedText] ist die maskierte Payload,
 * die das System verlassen darf; [redactions] bildet Token → Original ab (rein
 * lokal, für Audit/Rückbau — verlässt das System NIE).
 */
data class SanitizedPayload(
    val sanitizedText: String,
    val redactions: Map<String, String>,
) {
    val redactionCount: Int get() = redactions.size
}

/**
 * Ergebnis von [EgressPort.guard]: entweder darf die (maskierte) Payload raus
 * ([Allowed]) oder der Egress wird komplett geblockt ([Blocked]). Bewusst ein
 * sealed-Modell, damit der Aufrufer den Block NICHT versehentlich ignorieren
 * kann — die Naht erzwingt die Entscheidung.
 */
sealed interface EgressDecision {

    val isAllowed: Boolean get() = this is Allowed
    val isBlocked: Boolean get() = this is Blocked

    /** Payload darf raus — als maskierte [SanitizedPayload]. */
    data class Allowed(val payload: SanitizedPayload) : EgressDecision

    /**
     * Egress komplett geblockt. Trägt NUR die [category] (für Audit/ehrlichen
     * Hedge) — NIE den Klartext, der ja gerade nicht raus soll.
     */
    data class Blocked(val category: BlockCategory) : EgressDecision
}

/**
 * Hard-Block-Kategorien. [auditReason] ist ein klartext-freier Audit-Grund
 * (nennt nur die Kategorie, nie den geblockten Inhalt).
 */
enum class BlockCategory(val auditReason: String) {
    MEMORY_REFERENCE("Memory-Referenz/-Fakt — bleibt strikt lokal"),
    SPEAKER_ID("Sprecher-ID — bleibt strikt lokal"),
    INTERNAL_TOKEN("Interner Token/Secret — bleibt strikt lokal"),
    HA_BASE_URL("HA-Base-URL — bleibt strikt lokal"),
    LAN_IP("Private LAN-IP — bleibt strikt lokal"),
}
