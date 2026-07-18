package de.hoshi.adapters.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **Fts5GroundingAdapter** — die ERSTE reale Grounding-Scheibe (M4-Step-1). Zapft
 * die lokale deutsche Wikipedia über den Knowledge-Bridge-Sidecar an
 * (`hoshi-knowledge-bridge`, FTS5/BM25, Port 8035, articles.db ~4,98M Artikel) und
 * implementiert den hexagonalen [GroundingPort]. Ersetzt den `GroundingStubAdapter`.
 *
 * Bridge-Vertrag (verifiziert live :8035):
 *   `GET /search?q=<query>&limit=<n>&extract_max_chars=<chars>`
 *   → `{ query, totalHits, hits:[ { title, extract, bm25Score, summary, facts, … } ] }`
 *   (FTS5/BM25 — je NEGATIVER der `bm25Score`, desto besser der Treffer.)
 *
 * Spring-entkoppelt (wie [de.hoshi.adapters.brain.MlxBrainAdapter]): kein `@Service`,
 * keine `HoshiProperties`. Konfiguration über Konstruktor-Parameter; der WebClient
 * wird intern gebaut.
 *
 * **Best-effort:** Grounding darf NIE den Turn crashen. Bridge tot, Timeout, leere
 * Antwort, schwacher Treffer oder Nicht-Wissens-Kategorie → leerer Block (`""`),
 * der [de.hoshi.core.pipeline.TurnPromptAssembler] schichtet dann einfach nichts ein.
 *
 * Portierte Heuristiken aus Hoshi 0.5 `WikiGroundingService`:
 *  - **Kategorie-Gate:** nur Wissens-Kategorien (FACT_SHORT/NEEDS_WEB/AMBIG) grounden.
 *  - **Such-Query-Reduktion:** der Fragesatz wird auf Content-Tokens reduziert
 *    („Wer war Konrad Adenauer?" → „konrad adenauer"), damit der Titel-Treffer greift.
 *  - **BM25-Gate:** nur Treffer mit `bm25Score <= bm25Max` (schwache raus).
 */
class Fts5GroundingAdapter(
    baseUrl: String,
    /** Wie viele Top-Passagen maximal in den Block (knapp halten — kein Roh-Dump). */
    private val topN: Int = 1,
    /** Bridge-seitige Extract-Länge pro Treffer (Zeichen). */
    private val extractMaxChars: Int = 600,
    /** Qualitäts-Schwelle: Treffer zählt nur bei `bm25Score <= bm25Max` (0.5-Default −3.0). */
    private val bm25Max: Double = -3.0,
    /**
     * **WikiNumberContract** (`HOSHI_WIKINUMBER_CONTRACT_ENABLED`, T140-Port aus 0.5) —
     * default OFF, byte-neutral. Verhindert, dass das Modell eine konkrete Zahl aus
     * dem Grounding (z.B. `1721`) zu `irgendwann` verwässert. ON ⇒ (a) der
     * `/search`-Request schickt zusätzlich `fact_query=<volle Frage>`, womit die
     * Bridge die Zahl-Spans der Frage als `facts`-Array markiert; (b) der
     * Grounding-Block verankert diese Spans «verbatim» + eine Zitier-Instruktion.
     * OFF ⇒ kein `fact_query`-Param, kein Zusatz-Text — exakt der heutige Block.
     * Liefert die Bridge kein/leeres `facts` (alter Bridge-Stand, kein Zahl-Treffer),
     * bleibt es ebenfalls beim heutigen Block (defensiv, nie Crash).
     */
    private val enableNumberContract: Boolean = false,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : GroundingPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
        .build()

    /**
     * Holt für [query] einen kompakten Grounding-Block aus der Wiki-Bridge, oder
     * `""` (kein Treffer / disabled-Kategorie / Bridge weg). Niemals ein Fehler
     * nach außen — Grounding ist best-effort.
     */
    override fun groundingBlock(query: String, category: RouteCategory): Mono<String> {
        // Kategorie-Gate (1:1 aus 0.5): Smalltalk/Smart-Home/Agent NICHT grounden.
        if (category != RouteCategory.FACT_SHORT &&
            category != RouteCategory.NEEDS_WEB &&
            category != RouteCategory.AMBIG
        ) {
            return Mono.just("")
        }
        // Frage-Frame strippen ⇒ Head-Noun bleibt übrig ("woher kommt der Name
        // Mittwoch" → "mittwoch"). `definitional` = ein Frame hat gegriffen; nur dann
        // greift die geschärfte Abstain-Regel (exakter Titel Pflicht, sonst leer).
        val (frameStripped, definitional) = questionFrameStrip(query)
        val headNoun = reduceToHeadNoun(frameStripped, query)
        if (headNoun.isBlank()) return Mono.just("")

        return client.get()
            .uri { b ->
                b.path("/search")
                    .queryParam("q", headNoun)
                    // Kandidaten-Pool (≥CANDIDATE_POOL), damit der Exact-Title-Boost
                    // einen exakten Titel-Treffer VOR einen stärkeren BM25-Treffer
                    // ziehen kann; emittiert wird danach nur topN.
                    .queryParam("limit", maxOf(topN, CANDIDATE_POOL))
                    .queryParam("extract_max_chars", extractMaxChars)
                // WikiNumberContract: NUR bei Flag ON die VOLLE Frage als `fact_query`
                // (nicht searchQ — dort sind die Trigger-Wörter „wie viele/wann/…" als
                // Füller schon raus). Das triggert die Zahl-Span-Markierung der Bridge
                // (facts-Array). OFF ⇒ Param fehlt ⇒ byte-identische URL wie bisher.
                if (enableNumberContract) {
                    b.queryParam("fact_query", query)
                }
                b.build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)
            .map { body -> buildBlock(parseHits(body, headNoun, definitional)) }
            .defaultIfEmpty("")
            .onErrorResume { e ->
                // Bridge tot / Timeout / Parse → best-effort leerer Block, nie Crash.
                log.warn("[fts5-grounding] Bridge nicht erreichbar/Fehler ({}) — leerer Grounding-Block", e.message)
                Mono.just("")
            }
    }

    /** Ein gefilterter, knapper Treffer (Titel + grounding-tauglicher Text + Zahl-Spans). */
    private data class Hit(
        val title: String,
        val text: String,
        val bm25: Double,
        /** Zahl-Fakt-Spans der Bridge (z.B. ["40.000 Zähnchen", "1921"]); leer = keine. */
        val facts: List<String> = emptyList(),
    )

    /**
     * Parst die Bridge-Antwort → BM25-gegatete Kandidaten, re-rankt sie und liefert
     * die Top-N. Zwei Nora-Regeln greifen hier:
     *  - **Exact-Title-Boost:** ein Treffer, dessen Titel (case-insensitiv) == [headNoun]
     *    ist, wird VOR den reinen BM25-Score gezogen (deterministischer Tie-Break) —
     *    so schlägt der exakte Konzept-Artikel eine mehrdeutige Gruppe/Film mit zufällig
     *    stärkerem BM25.
     *  - **Geschärfte Abstain (nur [definitional]):** kam die Query aus einem Frage-Frame
     *    ("was bedeutet X" / "woher kommt der Name X") UND ist unter den gegateten
     *    Kandidaten KEIN exakter Titel-Treffer, dann leere Liste → Lane A deflektet
     *    ehrlich, statt einen tangentialen Treffer ("Mittwochsfazit" für „Mittwoch") als
     *    Fakt durchzureichen. Freitext-Fragen (kein Frame) bleiben unberührt — „einstein"
     *    groundet weiter über „Albert Einstein" (kein exakter Titel-Treffer nötig).
     */
    private fun parseHits(body: String, headNoun: String, definitional: Boolean): List<Hit> = runCatching {
        val hitsNode = mapper.readTree(body).path("hits")
        if (!hitsNode.isArray) return emptyList()
        val gated = hitsNode.asSequence()
            .mapNotNull { n ->
                val bm25 = n.path("bm25Score").asDouble(0.0)
                if (bm25 > bm25Max) return@mapNotNull null // schwacher Treffer raus
                val title = n.path("title").asText("").trim()
                // Summary (Tier-1-Kompression) bevorzugen, sonst der volle Extract.
                val summary = n.path("summary").asText("").trim()
                val extract = n.path("extract").asText("").trim()
                val text = summary.ifBlank { extract }
                if (title.isBlank() || text.isBlank()) return@mapNotNull null
                // WikiNumberContract: die von der Bridge markierten Zahl-Spans
                // (Feld `facts`). Defensiv: fehlt/leer/kein Array → leere Liste (alter
                // Bridge-Stand, kein fact_query gesendet) — nie ein Crash.
                val factsNode = n.path("facts")
                val facts = if (factsNode.isArray) {
                    factsNode.mapNotNull { f -> f.asText("").trim().ifBlank { null } }
                } else {
                    emptyList()
                }
                Hit(title, normalize(text), bm25, facts)
            }
            .toList()
        if (gated.isEmpty()) return emptyList()
        fun Hit.isExactTitle() = title.equals(headNoun, ignoreCase = true)
        // Geschärfte Abstain: definitional + kein exakter Titel-Treffer ⇒ nichts durchreichen.
        if (definitional && gated.none { it.isExactTitle() }) return emptyList()
        gated.sortedWith(
            // Exakter Titel zuerst, dann der beste (negativste) BM25 — deterministisch.
            compareByDescending<Hit> { it.isExactTitle() }.thenBy { it.bm25 },
        ).take(topN)
    }.getOrElse { emptyList() }

    /** Baut den kompakten Prompt-Block. Leer-Liste → "" (kein Block). */
    private fun buildBlock(hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n---\n")
        sb.append("HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n")
        hits.forEach { h ->
            sb.append("• ").append(h.title).append(": ").append(h.text).append("\n")
        }
        sb.append(
            "ANWEISUNG: Nutze diese Fakten und antworte knapp im eigenen warmen Stil — " +
                "zitiere nichts wörtlich und erwähne nie „den Text“, „den Artikel“ oder „Wikipedia“.",
        )
        appendNumberContract(sb, hits)
        return sb.toString()
    }

    /**
     * **WikiNumberContract** (T140-Port aus 0.5 `WikiGroundingService.buildGroundingBlock`):
     * hängt — NUR bei Flag ON UND von der Bridge markierten Zahl-Spans ([Hit.facts]) —
     * die Spans «verbatim» + eine Zitier-Instruktion an den Block. Ein 4B kopiert eine
     * konkret markierte Zahl zuverlässiger als es eine abstrakte Regel hält: nur die
     * Zahl zwischen «…» ist heilig, der Satz drumherum bleibt warm.
     *
     * Flag OFF ODER keine facts ⇒ NICHTS angehängt ⇒ der Block ist byte-identisch zum
     * bisherigen (defensiv: leere/fehlende facts sind der ehrliche Normalfall, kein Crash).
     */
    private fun appendNumberContract(sb: StringBuilder, hits: List<Hit>) {
        if (!enableNumberContract) return
        val facts = hits.flatMap { it.facts }.filter { it.isNotBlank() }.distinct()
        if (facts.isEmpty()) return
        val marked = facts.joinToString(", ") { "«$it»" }
        sb.append("\n")
        // Fakt-DIREKT-Formel (Live-Befund 2026-07-02: „Der Turm ist ziemlich hoch. Er
        // reicht über die 330 Meter…" — inhaltsleerer Vorsatz + Relativierung statt
        // direkter Eigenschaft). KURZ gehalten: jede Zusatzregel kostet bei einem 4B
        // Befolgung. Die frühere „Zeichen «» NICHT mitschreiben"-Zeile ist bewusst RAUS —
        // das erledigt jetzt deterministisch der Guillemet-Strip an der Delta-Naht
        // (TurnOrchestrator.stripContractMarkers, Wand statt Tapete).
        // Kein «…»-Meta-Literal und KEINE Anführungszeichen im Beispiel: das 4B kopiert
        // jedes gezeigte Markierungs-Muster in die Antwort (Live 2026-07-02: mit „…“
        // „368 Metern“). Nur die Wert-Anker «…» selbst bleiben — die strippt die Wand.
        sb.append("ZAHLEN-VERTRAG: Der exakte Wert zur Frage ist ").append(marked).append(". ")
        sb.append("Nenne genau diesen Wert — gleiche Ziffern, gleiche Einheit — als direkte Eigenschaft im ERSTEN Satz, ")
        sb.append("zum Beispiel: Der Eiffelturm ist 330 Meter hoch — ganz schön was. ")
        sb.append("Nicht relativieren (reicht über etwas hinaus) und kein inhaltsleerer Vorsatz. ")
        sb.append("Passt kein Wert zur Frage, erfinde KEINEN — sag dann ehrlich, dass du die genaue Zahl grad nicht parat hast.")
    }

    /** Glättet Whitespace, damit der Block eine knappe Passage statt eines Roh-Dumps ist. */
    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    // ── Such-Query-Reduktion (portiert aus 0.5 WikiGroundingService) ─────────────

    /**
     * Reduziert den Fragesatz auf sein Head-Noun. Zwei Stufen:
     *  1. **Frage-Frame strippen** (DE+EN, [questionFrameStrip]): „woher kommt der Name
     *     Mittwoch" → „Mittwoch", „was bedeutet Photosynthese" → „Photosynthese". Ohne
     *     das verwässert das Frage-Gerüst („name", „wort") das eigentliche Subjekt und
     *     die Bridge trifft „Name"/„Wort" statt des Konzepts.
     *  2. **Content-Tokens** ([contentTokens]): Rest-Füller raus („Wer war Konrad
     *     Adenauer?" → „konrad adenauer"). Bleibt nichts übrig (reine Füll-Query),
     *     bleibt die Original-Query.
     */
    internal fun searchQuery(query: String): String =
        reduceToHeadNoun(questionFrameStrip(query).first, query)

    /** Content-Token-Reduktion mit Fallback auf die Original-Query (nie leer, wenn Query nicht leer). */
    private fun reduceToHeadNoun(text: String, original: String): String {
        val toks = contentTokens(text)
        return if (toks.isEmpty()) original.trim() else toks.joinToString(" ")
    }

    /**
     * Strippt ein führendes (DE+EN) Frage-Frame und liefert `(rest, matched)`. `matched`
     * markiert eine definitorische Frage („was bedeutet X" / „woher kommt der Name X"),
     * für die [parseHits] die geschärfte Abstain-Regel anwendet. Kein Frame ⇒ Query
     * unverändert, `matched=false` (Freitext-Fragen bleiben tolerant). Vorab fliegen
     * modale Füllpartikel raus ([MODAL_FILLERS], ganze Wörter) — sie zählen nie als
     * Frame, würden aber Frame-Matching und FTS-Query verwässern.
     */
    private fun questionFrameStrip(query: String): Pair<String, Boolean> {
        // Modale Füllpartikel VORAB strippen ([MODAL_FILLERS], nur GANZE Wörter,
        // case-insensitiv) — sie setzen nie definitional, stehen aber live mitten im
        // Satz oder Frame und verwässern sonst die FTS-Query. Live-Befund 2026-07-05:
        // „Warum heißt der Mittwoch eigentlich Mittwoch?" ließ „eigentlich" in der
        // Query → BM25 traf „Eigentliche Eulen"/„Eigentliche Enten" → das laxe
        // Coverage-Gate wertete „gedeckt" → Konfabulation. Setzt selbst NICHT matched
        // und wirkt NUR auf die FTS-Query (fact_query/Brain-Prompt bleiben unberührt).
        var q = MODAL_FILLERS.replace(query, " ").trim()
        // Ein satz-initial gestrippter Füller kann ein Komma freilegen („Eigentlich,
        // warum …" → ", warum …") — weg damit, sonst greifen die ^-verankerten
        // Präambel-/Frame-Regexe nicht mehr.
        q = q.trimStart(',', ' ')
        // Höflichkeits-/Meta-Präambel ZUERST strippen („Kannst du mir erklären, …",
        // „Weißt du, …") — setzt selbst NICHT matched, legt aber das eigentliche Frame
        // an den Satzanfang frei. Live-Befund 2026-07-01: Andis exakte Formulierung
        // „Kannst du mir erklären, woher der Name Mittwoch kommt?" ließ Füllwörter in
        // der Query → Junk-Artikel passierte das Gate → Wand sah „gedeckt".
        for (re in POLITENESS_PREFIXES) {
            val stripped = re.replaceFirst(q, "")
            if (stripped != q) { q = stripped.trim(); break }
        }
        var matched = false
        for (re in LEADING_FRAMES) {
            val stripped = re.replaceFirst(q, "")
            if (stripped != q) { q = stripped.trim(); matched = true; break }
        }
        // DE-Nebensatz-Stellung: „woher der Name Mittwoch KOMMT" — das Verb steht am
        // Ende. Nur im matched-Fall strippen (sonst würde „Wer kommt heute?" verstümmelt).
        if (matched) {
            val afterDe = DE_TRAILING_VERB.replace(q, "")
            if (afterDe != q) q = afterDe.trim()
        }
        val afterTrail = TRAILING_FRAME.replace(q, "") // EN: „… come from"
        if (afterTrail != q) { q = afterTrail.trim(); matched = true }
        return q to matched
    }

    /** Inhaltliche Tokens: ≥3 Zeichen (oder reine Zahl), nicht in der Füller-Liste. */
    internal fun contentTokens(query: String): List<String> =
        query.lowercase()
            .map { ch -> if (ch.isLetterOrDigit() || ch in CONTENT_KEEP_CHARS) ch else ' ' }
            .joinToString("")
            .split(' ')
            .map { it.trim() }
            .filter { tok -> tok.isNotEmpty() && tok !in FILLER_TOKENS && (tok.length >= 3 || tok.all { it.isDigit() }) }
            .distinct()

    companion object {
        private val CONTENT_KEEP_CHARS = setOf('ä', 'ö', 'ü', 'ß', ' ')

        /** Kandidaten-Pool für den Exact-Title-Boost (fetch ≥ N, emit topN). */
        private const val CANDIDATE_POOL = 5

        /**
         * Führende Frage-Frames (DE+EN), die das Head-Noun umschließen — case-insensitiv,
         * am Anfang verankert. Trifft eines, gilt die Query als definitorisch (geschärfte
         * Abstain in [parseHits]). Der optionale Qualifier-Teil („der name "/„das wort "/…)
         * fällt mit weg, damit nur das Subjekt übrig bleibt.
         */
        /**
         * Höflichkeits-/Meta-Präambeln VOR dem eigentlichen Frame (DE+EN). Werden
         * gestrippt, ohne selbst als definitorisch zu zählen — sie legen nur das
         * satz-verankerte Frame frei. Verb ist Pflicht (kein Over-Eating).
         */
        private val POLITENESS_PREFIXES: List<Regex> = listOf(
            Regex(
                "^\\s*(?:kannst|könntest|würdest|magst)\\s+du\\s+(?:mir\\s+)?(?:bitte\\s+)?(?:mal\\s+)?(?:kurz\\s+)?(?:erklären|sagen|verraten|erzählen)\\s*,?\\s*",
                RegexOption.IGNORE_CASE,
            ),
            // Füllwörter (mir/bitte/mal/kurz) dürfen direkt VOR dem Komma stehen
            // („Erklär mir mal, woher …") — daher [\s,]+ statt \s+: erlaubt „mal,"
            // ohne in echte Wörter zu fressen („Malaria" bleibt unangetastet).
            Regex(
                "^\\s*(?:erklär|erkläre|verrat|verrate|erzähl|erzähle)\\s+(?:mir[\\s,]+)?(?:bitte[\\s,]+)?(?:mal[\\s,]+)?(?:kurz[\\s,]+)?,?\\s*",
                RegexOption.IGNORE_CASE,
            ),
            Regex("^\\s*wei(?:ß|ss)t\\s+du\\s*,?\\s*", RegexOption.IGNORE_CASE),
            Regex(
                "^\\s*(?:can|could|would)\\s+you\\s+(?:please\\s+)?(?:tell\\s+me|explain(?:\\s+to\\s+me)?)\\s*,?\\s*",
                RegexOption.IGNORE_CASE,
            ),
        )

        /** DE-Nebensatz-Verb am Ende („… woher der Name X KOMMT?") — nur bei matched-Frame. */
        private val DE_TRAILING_VERB = Regex("\\s+(?:kommt|stammt|herkommt)\\s*\\??\\s*$", RegexOption.IGNORE_CASE)

        /**
         * Modale DE-Füllpartikel, die nie definitional setzen („eigentlich", „denn",
         * „überhaupt", „nochmal/noch mal", „eben", „halt") — werden als GANZE Wörter
         * case-insensitiv aus der FTS-Query gestrippt (Live-Befund 2026-07-05:
         * „eigentlich" in der Query traf „Eigentliche Eulen/Enten" per BM25).
         * Lookarounds statt `\b`, weil `\b` ohne UNICODE_CHARACTER_CLASS Umlaute
         * nicht als Wortzeichen kennt; Bindestrich/Apostroph zählen als Wort-
         * Fortsetzung („Eben-Emael" bleibt ganz). Flektierte Formen überleben per
         * Konstruktion („Eigentliche Eulen" ≠ „eigentlich"). Bewusst KEINE
         * EN-Pendants — die Token-Normalisierung ([FILLER_TOKENS]) behandelt auch
         * nur DE. Bekannter Trade-off (testfest im Adapter-Test): fragt jemand nach
         * dem WORT selbst („Was bedeutet das Wort eigentlich?"), bleibt nach
         * Strip+Frame nichts übrig → Fallback auf die Original-Query + die
         * definitorische Abstain-Regel deflektiert ehrlich statt zu konfabulieren.
         */
        private val MODAL_FILLERS = Regex(
            "(?u)(?<![\\p{L}\\p{N}'’-])(?:eigentlich|denn|überhaupt|noch\\s*mals?|eben|halt)(?![\\p{L}\\p{N}'’-])",
            RegexOption.IGNORE_CASE,
        )

        private val LEADING_FRAMES: List<Regex> = listOf(
            Regex(
                "^\\s*woher\\s+(?:kommt|stammt)\\s+(?:der\\s+name\\s+|das\\s+wort\\s+|der\\s+begriff\\s+)?",
                RegexOption.IGNORE_CASE,
            ),
            // Nebensatz-Stellung: „woher der Name Mittwoch kommt" — Verb am Ende
            // (strippt DE_TRAILING_VERB im matched-Zweig).
            Regex(
                "^\\s*woher\\s+(?:der\\s+name\\s+|das\\s+wort\\s+|der\\s+begriff\\s+)",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "^\\s*was\\s+bedeutet\\s+(?:das\\s+wort\\s+|der\\s+begriff\\s+|die\\s+abkürzung\\s+|der\\s+name\\s+)?",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "^\\s*was\\s+hei(?:ß|ss)t\\s+(?:das\\s+wort\\s+|der\\s+begriff\\s+|der\\s+name\\s+)?",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "^\\s*where\\s+does\\s+(?:the\\s+)?(?:name\\s+|word\\s+)?",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                "^\\s*what(?:'?s|\\s+is)\\s+the\\s+(?:origin|meaning)\\s+of\\s+(?:the\\s+)?(?:name\\s+|word\\s+)?",
                RegexOption.IGNORE_CASE,
            ),
        )

        /** Nachgestelltes EN-Frame („… come from?"). */
        private val TRAILING_FRAME = Regex("\\s+come\\s+from\\s*\\??\\s*$", RegexOption.IGNORE_CASE)

        /** Füll-/Frage-Gerüst-Tokens (1:1 aus 0.5 `wikiGroundingFillerTokens`). */
        private val FILLER_TOKENS: Set<String> = setOf(
            "hallo", "hi", "hey", "moin", "servus", "tach", "guten", "tag", "morgen", "abend",
            "danke", "bitte", "tschüss", "ciao", "hallöchen",
            "sag", "sags", "sage", "kurz", "mal", "doch", "bitteschön",
            "wie", "geht", "gehts", "gehs", "dir", "euch", "ihnen", "uns", "mir", "mich", "dich",
            "wer", "war", "ist", "sind", "bist", "bin", "warst", "waren", "wars",
            "was", "wann", "wo", "wieso", "warum", "weshalb", "welche", "welcher", "welches",
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "eines", "einer",
            "und", "oder", "aber", "auch", "noch", "schon", "denn", "nur",
            "magst", "mag", "kannst", "kann", "willst", "will", "möchtest", "möchte", "darfst",
            "erzähl", "erzähle", "erzaehl", "erzaehle", "witz", "witze", "spaß", "spass",
            "alles", "fit", "klar", "okay", "gut", "schön", "toll",
            "machst", "macht", "tust", "tut",
        )
    }
}
