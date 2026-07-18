package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Eine OFFENE Orts-Nachfrage (Wetter S3): der Wetter-Zweig hat „Für welchen Ort
 * denn? Ich merk's mir." gefragt — [query]/[language] sind die ORIGINAL-Wetter-
 * Frage und -Sprache dieses Turns. Nennt der Folge-Turn einen Ort
 * ([LocationAnswerRecognizer]), wird der Ort gespeichert und GENAU diese Frage
 * beantwortet (nie der bloße Orts-Name als Frage).
 */
data class PendingLocationQuestion(
    val query: String,
    val language: Language,
    val ts: Instant = Instant.now(),
)

/**
 * **PendingLocationQuestionPort** — das winzige Session-Gedächtnis der offenen
 * „Für welchen Ort denn?"-Nachfrage, ZEILE FÜR ZEILE nach dem
 * [PendingLookupPort]-Muster (Interface + [NONE]-Default + In-Memory-Impl,
 * one-shot [consume], TTL [DEFAULT_TTL], Key `chatId ?: speakerId ?: "local"`).
 *
 * **Eigener Port statt Generalisierung (begründeter Entscheid):** die beiden
 * Pendings tragen zwar dieselbe Payload-Form (query+language+ts), aber
 * GRUNDVERSCHIEDENE Einlöse-Verträge — das Nachschau-Angebot löst NUR eine
 * deterministische Affirmation ([AffirmationRecognizer]) ein, die Orts-Nachfrage
 * NUR ein konservativer Orts-Erkenner ([LocationAnswerRecognizer]). Getrennte
 * Stores machen eine Ketten-Vermischung STRUKTURELL unmöglich (ein „ja" kann nie
 * eine Orts-Nachfrage einlösen, ein „Duisburg" nie ein Cloud-Angebot — selbst
 * bei einem künftigen Refactor-Fehler am Orchestrator). Die ~20 duplizierten
 * Impl-Zeilen sind billiger als eine generische Abstraktion über zwei
 * Ein-Feld-Payloads (Muster [LastAreaPort]-Familie: lieber zwei ehrliche kleine
 * Ports als ein cleverer großer).
 *
 * Lebenszyklus strikt **one-shot** (wie [PendingLookupPort]): [offer] setzt die
 * Nachfrage am Wetter-Zweig, der NÄCHSTE Turn desselben Schlüssels zieht sie per
 * [consume] — egal ob er einen Ort nennt oder etwas anderes (ein Fremd-Turn räumt
 * die Nachfrage, es bleibt nie ein alter Orts-Köder liegen). Zusätzlich TTL
 * [DEFAULT_TTL] (~120 s): eine Nachfrage von vorhin fängt keine zufällige
 * Orts-Nennung von später.
 *
 * [NONE] ist der verhaltens-neutrale Default (merkt nie, liefert nie) ⇒ ohne
 * Wiring bleibt jeder Pfad byte-identisch.
 */
interface PendingLocationQuestionPort {
    /** Merkt [pending] als die offene Orts-Nachfrage für [key] (überschreibt eine ältere). */
    fun offer(key: String, pending: PendingLocationQuestion)

    /**
     * Holt die offene Nachfrage für [key] **one-shot** (danach ist sie weg) —
     * `null` wenn keine offen ist oder die TTL abgelaufen ist.
     */
    fun consume(key: String): PendingLocationQuestion?

    companion object {
        /** Default: merkt nie, liefert nie ⇒ kein Orts-Folge-Turn (Verhalten unverändert). */
        val NONE: PendingLocationQuestionPort = object : PendingLocationQuestionPort {
            override fun offer(key: String, pending: PendingLocationQuestion) {}
            override fun consume(key: String): PendingLocationQuestion? = null
        }

        /** Nachfrage-TTL: ein Orts-Name zählt nur ~120 s — identisch zum [PendingLookupPort]. */
        val DEFAULT_TTL: Duration = Duration.ofSeconds(120)
    }
}

/**
 * In-Memory-Impl: `ConcurrentHashMap<key, PendingLocationQuestion>` — ZEILE FÜR
 * ZEILE nach [InMemoryPendingLookupStore] (pure, framework-frei, thread-safe;
 * one-shot über atomares `remove`, TTL beim [consume] gegen die [clock], [offer]
 * räumt opportunistisch abgelaufene Fremd-Einträge).
 */
class InMemoryPendingLocationQuestionStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = PendingLocationQuestionPort.DEFAULT_TTL,
) : PendingLocationQuestionPort {
    private val byKey = ConcurrentHashMap<String, PendingLocationQuestion>()

    override fun offer(key: String, pending: PendingLocationQuestion) {
        if (key.isBlank() || pending.query.isBlank()) return
        byKey.entries.removeIf { expired(it.value) }
        byKey[key] = pending
    }

    override fun consume(key: String): PendingLocationQuestion? {
        val pending = byKey.remove(key) ?: return null
        return if (expired(pending)) null else pending
    }

    private fun expired(pending: PendingLocationQuestion): Boolean =
        Duration.between(pending.ts, clock.instant()) > ttl
}

/**
 * **LocationAnswerRecognizer** — der KONSERVATIVE Orts-Erkenner des
 * Nachfrage-Folge-Turns (Wetter S3, Pendant zum [AffirmationRecognizer]).
 * KEIN Modell, KEINE freie Heuristik — zwei enge, deterministische Formen:
 *
 *  1. **Bare Form:** 1–[MAX_TOKENS] Tokens, JEDES großgeschrieben („Duisburg",
 *     „Bad Homburg"). Großschreibung trägt das Signal (STT/Chat schreiben
 *     Eigennamen groß) — „wie ist das wetter" oder „mach das licht an" matcht nie.
 *  2. **„in X"-Form:** führendes „in"/„In" + 1–2 Wort-Tokens („in Duisburg",
 *     „in duisburg") — hier trägt die Präposition das Signal, X darf auch
 *     kleingeschrieben sein (STT-Kleinschreibung).
 *
 * Dazu die [STOPWORDS]-Wand: Affirmationen/Negationen/Floskeln („Nein", „Ja",
 * „Okay", „in Ordnung") sind NIE ein Ort — sonst würde ein großgeschriebenes
 * „Nein" geocodet. Tokens mit Ziffern/Interpunktion matchen strukturell nicht
 * (reine Buchstaben-Muster). False-negatives sind billig (der Turn läuft normal
 * weiter, die Nachfrage verfällt); false-positives wären teuer (falscher Ort
 * gespeichert) — darum bewusst eng. Bekannte Grenze (dokumentiert): Mehrwort-Orte
 * mit Kleinwort („Frankfurt am Main") matchen in der bare Form nicht — „Frankfurt"
 * reicht, der Geocoder löst den besten Treffer auf.
 */
object LocationAnswerRecognizer {

    /** Max. Token-Zahl einer Orts-Antwort — längere Äußerungen sind nie ein reiner Orts-Name. */
    const val MAX_TOKENS: Int = 3

    /** Großgeschriebenes Wort-Token (Buchstaben + Bindestrich, min. 2 Zeichen). */
    private val CAPITALIZED = Regex("""[A-ZÄÖÜ][A-Za-zÄÖÜäöüß-]+""")

    /** Wort-Token beliebiger Schreibung (für die „in X"-Form; min. 2 Zeichen). */
    private val WORD = Regex("""[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß-]+""")

    /**
     * Floskel-Wand: normalisierte Tokens/Phrasen, die NIE ein Ort sind — sonst
     * würde ein großgeschriebenes „Nein"/„Okay" (oder „in Ordnung") geocodet.
     */
    private val STOPWORDS: Set<String> = setOf(
        // DE
        "ja", "nein", "ok", "okay", "danke", "klar", "gern", "gerne", "bitte",
        "doch", "nee", "nö", "stopp", "stop", "egal", "nichts", "nix",
        "ordnung", "moment", "warte", "vielleicht", "später", "spaeter",
        // EN
        "yes", "no", "nope", "thanks", "sure", "nothing", "maybe", "later",
    )

    /**
     * Extrahiert den Orts-Kandidaten aus [text] oder `null` (keine Orts-Antwort).
     * Liefert den Namen wie gesagt (Mehrwort mit Leerzeichen gejoint) — der
     * Geocoder ist case-tolerant, es wird NICHTS umgeschrieben.
     */
    fun place(text: String): String? {
        val cleaned = text.trim().trimEnd('.', '!', '?', ',', ';', ':')
        if (cleaned.isEmpty()) return null
        val tokens = cleaned.split(Regex("\\s+"))
        if (tokens.size > MAX_TOKENS) return null

        val inForm = tokens.first().equals("in", ignoreCase = true)
        val candidate = if (inForm) tokens.drop(1) else tokens
        if (candidate.isEmpty()) return null

        val pattern = if (inForm) WORD else CAPITALIZED
        if (!candidate.all { pattern.matches(it) }) return null
        if (candidate.any { it.lowercase() in STOPWORDS }) return null
        if (candidate.joinToString(" ").lowercase() in STOPWORDS) return null
        return candidate.joinToString(" ")
    }
}
