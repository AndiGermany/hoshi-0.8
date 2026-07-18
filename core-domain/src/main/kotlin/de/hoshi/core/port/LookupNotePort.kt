package de.hoshi.core.port

import de.hoshi.core.dto.RouteCategory
import java.security.MessageDigest
import java.time.Instant

/**
 * **Eine Nachgeschlagen-Notiz** — DAS gemeinsame Datenmodell von Extended Think
 * (S3) UND der künftigen Nachtschicht (bindender Orchestrator-Entscheid #4,
 * `vault/tracks/prep/PREP-extended-think.md`: „Extended Think DEFINIERT, die
 * Nachtschicht ERBT"). Append-only JSONL-Zeile, normativ — die Nachtschicht
 * übernimmt dieses Format 1:1, NICHT das Feld-Set ändern ohne beide Seiten.
 *
 * Feld-Bedeutung (Reihenfolge normativ, s. [de.hoshi.adapters.supervision]-Adapter):
 *  - [queryHash] — SHA-256 der normalisierten Frage ([LookupNoteNormalizer.normalize]),
 *    Dedupe-Schlüssel (nicht als Primärschlüssel erzwungen — append-only).
 *  - [queryNorm] — die normalisierte Frage im KLARTEXT (nötig für deterministisches
 *    Token-Overlap-Matching beim Lesen). **Tom: erstmals User-Fragen auf Platte** —
 *    darum der Pflicht-Lösch-Pfad (`DELETE /api/v1/privacy/lookups`).
 *  - [answer] — VERBATIM, NIE umformuliert (WikiNumber-Lehre).
 *  - [source] — die Quellen-Attribution der Cloud-Antwort.
 *  - [provider] — grobe Herkunfts-Klasse, NICHT die exakte Modell-ID: `"openai-nano"`
 *    heute, später `"local-12b"` (Kai-Universalitäts-Vertrag: core-domain kennt keine
 *    OpenAI-Spezifik).
 *  - [costCents] — was DIESER eine Lookup gekostet hat (Double: Nano-Calls kosten
 *    Bruchteile von Cents).
 *  - [ts] — Schreibzeitpunkt (UTC).
 *  - [ttlDays] — wie lange die Notiz als Cache-Treffer gilt (danach: wieder Cloud).
 *  - [origin] — `"live"` (im normalen Turn eskaliert) oder `"night"` (künftig: von
 *    der Nachtschicht nachgetragen) — [ORIGIN_LIVE]/[ORIGIN_NIGHT].
 */
data class LookupNote(
    val queryHash: String,
    val queryNorm: String,
    val answer: String,
    val source: String,
    val provider: String,
    val costCents: Double,
    val ts: Instant,
    val ttlDays: Int,
    val origin: String = ORIGIN_LIVE,
) {
    companion object {
        const val ORIGIN_LIVE: String = "live"
        const val ORIGIN_NIGHT: String = "night"
    }
}

/**
 * **LookupNotePort** — die schmale Schreib-/Lese-Naht des Nachgeschlagen-Stores
 * (ZEILE-FÜR-ZEILE-Verwandtschaft zu [TurnTracePort]: EIN Verb fürs Schreiben,
 * best-effort, wirft NIE, non-blocking).
 *
 * [find] ist additiv (Default-Body, kein zweites SAM-Mitglied — `fun interface`
 * bleibt gültig) und dient vor allem dem Adapter-eigenen Rundtrip-Test; der
 * PRODUKTIVE Cache-Hit-Lese-Pfad ist [de.hoshi.adapters.knowledge]s
 * `NachgeschlagenGroundingProvider`, der DIESELBE Datei UNABHÄNGIG liest (eigener
 * Datei-Rand, damit `adapters-knowledge` NICHT von `adapters-supervision`
 * abhängen muss — „eine Datei-Wahrheit, zwei schmale Ränder").
 *
 * **Default-OFF:** [NOOP] schreibt/findet nie ⇒ ohne Wiring
 * (`HOSHI_EXTENDED_THINK_ENABLED=false`) byte-neutral.
 */
fun interface LookupNotePort {
    /** Persistiert [note] (best-effort, non-blocking, wirft NIE). NUR bei einer echten Answer aufrufen — UNKLAR wird NIE gespeichert (Aufrufer-Pflicht, s. TurnOrchestrator). */
    fun record(note: LookupNote)

    /** Best-effort Lese-Pfad (exaktes [queryNorm]-Match). Default: nie ein Treffer. */
    fun find(queryNorm: String): LookupNote? = null

    companion object {
        /** Verhaltens-neutraler Default (Store OFF) — schreibt/findet NIE. */
        val NOOP: LookupNotePort = LookupNotePort { }
    }
}

/**
 * **LookupReplayPort — die Lese-Naht für das BRAIN-FREIE Verbatim-Replay eines
 * sicheren Nachgeschlagen-Cache-Treffers** (Andi-Befund 2026-07-16: „die gecachten
 * smart Antworten klingen echt doof").
 *
 * **Warum eine EIGENE Naht neben [LookupNotePort.find]:** [find] ist ein EXAKTES
 * `queryNorm`-Match (Adapter-Rundtrip-Test). Das PRODUKTIVE Cache-Matching lebt in
 * [de.hoshi.adapters.knowledge]s `NachgeschlagenGroundingProvider` als
 * DETERMINISTISCHER Token-Overlap (Jaccard ab `MATCH_THRESHOLD`) + TTL. Diese Naht
 * EXPONIERT genau diesen bestehenden Treffer als rohe [LookupNote] — dieselbe
 * Schwelle, dieselbe TTL, dieselbe Kategorie-Gate wie der Grounding-Block —, damit
 * der [de.hoshi.core.pipeline.TurnOrchestrator] sie bei einem SICHEREN Treffer
 * WÖRTLICH und brain-frei zurückspielt, statt sie (wie bisher als HINTERGRUND-Block)
 * vom 4B-Brain PARAPHRASIEREN zu lassen (Erst-Antwort = Nano-Qualität, Replay =
 * e2b-Nacherzählung).
 *
 * **Default-OFF/byte-neutral:** [NONE] liefert nie einen Treffer ⇒ ohne Wiring
 * (`HOSHI_LOOKUP_VERBATIM_REPLAY_ENABLED=false`) betritt der Orchestrator den
 * Replay-Zweig gar nicht erst (zusätzlicher Flag-Gate im Orchestrator). Der
 * Grounding-Injektions-Pfad (Provider als [GroundingPort]) bleibt UNVERÄNDERT der
 * Fallback UNTER der Schwelle.
 *
 * **Best-effort:** [bestNote] wirft laut Vertrag NIE (fehlende/kaputte Datei,
 * kein Treffer, jeder Fehler ⇒ `null`); der reale Provider macht Datei-I/O, DARF
 * also BLOCKIEREN — der Aufrufer ([TurnOrchestrator]) lagert auf
 * `Schedulers.boundedElastic` aus (P0-Event-Loop-Disziplin), exakt wie beim
 * Grounding-Pfad.
 */
fun interface LookupReplayPort {
    /**
     * Der beste, NICHT-abgelaufene [LookupNote]-Treffer ÜBER der Overlap-Schwelle
     * für [query] in [category], oder `null` (falsche Kategorie / kein sicherer
     * Treffer / abgelaufen / leerer Store / jeder Fehler). MAY BLOCK (Datei-I/O),
     * wirft NIE.
     */
    fun bestNote(query: String, category: RouteCategory): LookupNote?

    companion object {
        /** Verhaltens-neutraler Default (Replay OFF) — findet NIE einen Treffer. */
        val NONE: LookupReplayPort = LookupReplayPort { _, _ -> null }
    }
}

/**
 * **LookupNoteNormalizer** — die EINE Normalisierungs-Wahrheit, die Schreib-
 * ([TurnOrchestrator]) und Lese-Seite ([de.hoshi.adapters.knowledge]s
 * `NachgeschlagenGroundingProvider`) TEILEN (core-domain, damit beide Adapter-
 * Module dieselbe Funktion importieren können, ohne voneinander abzuhängen).
 *
 * Deterministisch, KEIN Fuzzy-Matching per Modell/Embedding (Nora-Linie, v1):
 * lowercase, Satzzeichen weg, Whitespace kollabiert. [tokens] filtert auf
 * Content-Tokens (>3 Zeichen — dieselbe Längen-Schwelle wie der
 * [de.hoshi.core.pipeline.FactCoverageGate]-Substanz-Check) für den
 * Token-Overlap-Match.
 */
object LookupNoteNormalizer {

    private val PUNCTUATION = Regex("[^\\p{L}\\p{Nd}\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Normalisiert [text]: lowercase, Satzzeichen entfernt, Whitespace kollabiert, getrimmt. */
    fun normalize(text: String): String =
        WHITESPACE.replace(PUNCTUATION.replace(text.lowercase(), " "), " ").trim()

    /** Content-Token-Menge einer BEREITS normalisierten Frage (>3 Zeichen). */
    fun tokens(normalized: String): Set<String> =
        normalized.split(' ').filter { it.length > 3 }.toSet()

    /** SHA-256 (hex) einer BEREITS normalisierten Frage — der Dedupe-Schlüssel. */
    fun sha256Hex(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

/**
 * **LookupNoteFenceGuard — H1b Schreib-Hygiene (Security-Fix, Pod Tom/Jonas
 * 2026-07-08, Defense in depth zu H1).** [de.hoshi.adapters.knowledge]s
 * `NachgeschlagenGroundingProvider` zäunt [LookupNote.answer]/[LookupNote.source]
 * beim LESEN in einen Zitat-Zaun (`⟦ZITAT-ANFANG⟧…⟦ZITAT-ENDE⟧`) und neutralisiert
 * dafür die beiden Zaun-Klammerzeichen (`neutralizeFence`, damit die Notiz den
 * Zaun nicht von innen selbst öffnen kann). [neutralize] hier ist DIESELBE
 * Ersetzung, EINMAL geteilt in core-domain (kein Modul-Zyklus: core-domain hängt
 * von keinem Adapter ab), angewendet BEIM PERSISTIEREN
 * ([de.hoshi.core.pipeline.TurnOrchestrator.recordLookupNote]).
 *
 * **Defense in depth, kein Ersatz für den Lese-Zaun:** ist die Notiz bereits
 * beim Schreiben zaunfrei, kann [de.hoshi.adapters.knowledge]s Lese-Zaun gar
 * nicht mehr unterlaufen werden — UND ein später hinzukommender zweiter
 * Lese-Pfad erbt denselben Schutz automatisch, ohne dass er selbst an eine
 * Neutralisierung denken müsste (P2, Cowork-Präzisierung). Der bestehende
 * Lese-Zaun in `NachgeschlagenGroundingProvider` bleibt UNVERÄNDERT als zweite,
 * unabhängige Wand bestehen (u.a. für Alt-Notizen von vor H1b).
 *
 * **EHRLICH:** neutralisiert NUR die beiden Zaun-Klammerzeichen — KEINE
 * „Instruktions-Muster-Erkennung" (zu heuristisch für S1/Nora-Linie). Deckt die
 * Zaun-ÖFFNUNG ab, nicht die Semantik des Textes.
 */
object LookupNoteFenceGuard {
    /** Identisch zu `NachgeschlagenGroundingProvider.FENCE_OPEN`/`FENCE_CLOSE` (U+27E6/U+27E7) — NICHT unabhängig ändern. */
    private const val FENCE_OPEN: Char = '⟦'
    private const val FENCE_CLOSE: Char = '⟧'

    /** Ersetzt beide Zaun-Klammerzeichen durch ASCII-Pendants — identische Ersetzung wie der Lese-Zaun. */
    fun neutralize(text: String): String = text.replace(FENCE_OPEN, '[').replace(FENCE_CLOSE, ']')
}
