package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Ein OFFENES Nachschlage-Angebot (Extended Think S2): der Deflect-Zweig hat
 * „… soll ich kurz nachschauen?" gefragt — [query]/[language] sind die
 * ORIGINAL-Frage und -Sprache dieses Angebots. Sagt der Folge-Turn „ja"
 * ([AffirmationRecognizer]), wird mit GENAU dieser Frage eskaliert (nie mit „ja").
 */
data class PendingLookup(
    val query: String,
    val language: Language,
    val ts: Instant = Instant.now(),
)

/**
 * **PendingLookupPort** — das winzige Session-Gedächtnis des offenen
 * „soll ich kurz nachschauen?"-Angebots (ZEILE FÜR ZEILE nach dem
 * [LastAreaPort]-Muster: Interface + [NONE]-Default + In-Memory-Impl).
 *
 * Lebenszyklus strikt **one-shot**: [offer] setzt das Angebot am Deflect-Zweig,
 * der NÄCHSTE Turn desselben Schlüssels zieht es per [consume] — egal ob er
 * „ja" sagt oder etwas anderes (ein Fremd-Turn räumt das Angebot, es bleibt
 * nie ein alter „ja"-Köder liegen). Zusätzlich TTL [DEFAULT_TTL] (~120 s):
 * ein Angebot von vorhin ist kein Consent von jetzt.
 *
 * **Key-Entscheid (S2, größte Design-Unbekannte des PREP):**
 * `key = chatId ?: speakerId ?: "local"` — der Voice-Pfad baut den ChatRequest
 * heute OHNE `chatId` und OHNE `speakerContext` (VoiceInboundController), fällt
 * also auf den einen [LOCAL_KEY]-Slot. Im Ein-Haushalt-Betrieb ist dieser
 * Single-Slot ehrlich und sicher: es gibt maximal EIN offenes Angebot, TTL +
 * one-shot verhindern Alt-Consent. **Upgrade-Pfad (dokumentiert, nicht gebaut):**
 * sobald die Sprecher-ID-Lane (deren S3) den Voice-Pfad mit einer echten
 * `speakerId` befüllt, greift automatisch die mittlere Stufe des Schlüssels —
 * das Angebot wird pro Sprecher isoliert, ohne dass sich dieser Port ändert.
 *
 * **Wiederverwendbar geschnitten:** der Port kennt nur „Angebot gemerkt/geholt" —
 * er ist NICHT an den FactCoverage-Deflect gebunden. Der zweite Angebots-Pfad
 * (HonestyGate.AskConsent, v1 bewusst NICHT umgebaut) kann später dieselbe
 * Naht als zweite Quelle nutzen (ein Erkenner, zwei Angebots-Quellen).
 *
 * [NONE] ist der verhaltens-neutrale Default (merkt nie, liefert nie) ⇒ ohne
 * Wiring (Decke `HOSHI_EXTENDED_THINK_ENABLED=false`) bleibt jeder Pfad
 * byte-identisch.
 */
interface PendingLookupPort {
    /** Merkt [pending] als das offene Angebot für [key] (überschreibt ein älteres). */
    fun offer(key: String, pending: PendingLookup)

    /**
     * Holt das offene Angebot für [key] **one-shot** (danach ist es weg) —
     * `null` wenn keins offen ist oder die TTL abgelaufen ist.
     */
    fun consume(key: String): PendingLookup?

    companion object {
        /** Default: merkt nie, liefert nie ⇒ kein Consent-Folge-Turn (Verhalten unverändert). */
        val NONE: PendingLookupPort = object : PendingLookupPort {
            override fun offer(key: String, pending: PendingLookup) {}
            override fun consume(key: String): PendingLookup? = null
        }

        /** Der Ein-Haushalt-Single-Slot, wenn weder chatId noch speakerId da sind (Voice-Pfad heute). */
        const val LOCAL_KEY: String = "local"

        /** Angebots-TTL: ein „ja" zählt nur ~120 s — ein Angebot von vorhin ist kein Consent von jetzt. */
        val DEFAULT_TTL: Duration = Duration.ofSeconds(120)
    }
}

/**
 * In-Memory-Impl: `ConcurrentHashMap<key, PendingLookup>`. Pure, framework-frei,
 * thread-safe. One-shot über atomares `remove`; TTL wird beim [consume] gegen die
 * [clock] geprüft (abgelaufen ⇒ `null`, Eintrag ist trotzdem geräumt). [offer]
 * räumt opportunistisch abgelaufene Fremd-Einträge (die Map bleibt winzig).
 */
class InMemoryPendingLookupStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = PendingLookupPort.DEFAULT_TTL,
) : PendingLookupPort {
    private val byKey = ConcurrentHashMap<String, PendingLookup>()

    override fun offer(key: String, pending: PendingLookup) {
        if (key.isBlank() || pending.query.isBlank()) return
        // Opportunistische Hygiene: abgelaufene Angebote anderer Schlüssel räumen.
        byKey.entries.removeIf { expired(it.value) }
        byKey[key] = pending
    }

    override fun consume(key: String): PendingLookup? {
        val pending = byKey.remove(key) ?: return null
        return if (expired(pending)) null else pending
    }

    private fun expired(pending: PendingLookup): Boolean =
        Duration.between(pending.ts, clock.instant()) > ttl
}

/**
 * **AffirmationRecognizer** — der deterministische „ja"-Erkenner des
 * Consent-Folge-Turns (Extended Think S2). KEIN Modell, KEINE Heuristik über
 * freien Text: eine kurze, exakte Phrasen-Liste (DE+EN), normalisiert
 * (lowercase, Interpunktion weg) und hart auf ≤ [MAX_TOKENS] Tokens begrenzt —
 * „Ja, aber was ist mit morgen?" ist NIE eine Affirmation.
 *
 * Bewusst als eigenes, quellen-unabhängiges Bauteil geschnitten (nicht im
 * Orchestrator vergraben): das HonestyGate-AskConsent-Angebot kann später
 * DENSELBEN Erkenner nutzen (ein Erkenner, zwei Angebots-Quellen — v1 bedient
 * nur den FactCoverage-Deflect).
 */
object AffirmationRecognizer {

    /** Max. Token-Zahl einer Affirmation — längere Äußerungen sind nie ein reines „ja". */
    const val MAX_TOKENS: Int = 4

    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /** Die exakte, deterministische Affirmations-Liste (normalisierte Token-Folgen). */
    private val AFFIRMATIONS: Set<String> = setOf(
        // DE
        "ja", "ja bitte", "ja gern", "ja gerne", "gern", "gerne",
        "mach das", "mach mal", "schau nach", "schau mal nach",
        "ok", "okay", "klar", "bitte",
        // EN
        "yes", "yes please", "sure", "go ahead",
    )

    /** TRUE gdw. [text] normalisiert EXAKT eine der Affirmations-Phrasen ist (≤ [MAX_TOKENS] Tokens). */
    fun matches(text: String): Boolean {
        val tokens = text.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return false
        return tokens.joinToString(" ") in AFFIRMATIONS
    }
}
