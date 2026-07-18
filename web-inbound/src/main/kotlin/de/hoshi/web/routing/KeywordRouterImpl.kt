package de.hoshi.web.routing

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.IntentClassifier
import de.hoshi.core.pipeline.KeywordRouter

/**
 * **KeywordRouterImpl** — der ECHTE Hop-1-Keyword-Router (M4-Step-2). Ersetzt den
 * [de.hoshi.web.stub.KeywordRouterStubAdapter] (der nur SMART_HOME/SMALLTALK
 * konnte) und ist damit der Hebel, der eine Wissensfrage als Wissens-Kategorie
 * klassifiziert → der [de.hoshi.adapters.knowledge.Fts5GroundingAdapter] greift
 * (groundet bei FACT_SHORT/NEEDS_WEB/AMBIG) → Hoshi antwortet mit echtem Wiki-Wissen.
 *
 * Reine Keyword-Heuristik (0ms, KEIN Ollama/Embedding — die Refiner bleiben in 0.8
 * Passthrough-Stub). Portiert aus Hoshi 0.5 `RouterService.decide`:
 *
 *  1. **Smart-Home** (1:1 aus 0.5): Target-Substantiv + (klassisches Verb ODER
 *     knapper Zustands-Marker „an/aus/hell/dunkel") → SMART_HOME. Zusätzlich ein
 *     blanker bekannter Raum-Name + Zustand → SMART_HOME (0.5 `mentionsKnownRoom`,
 *     hier gegen das statische [IntentClassifier.Keywords.haRooms]-Set statt gegen
 *     den Spring-`RoomCatalog`).
 *  2. **Wissen vs. Smalltalk** — Content-Token-Reduktion (portiert aus der
 *     0.5-`WikiGroundingService`-Such-Query-Reduktion): trägt der Satz nach Abzug
 *     der Grüße/Floskeln/Frage-Gerüst-Tokens noch ein Inhalts-Token („konrad
 *     adenauer"), ist es eine **Wissensfrage → FACT_SHORT** (Grounding feuert).
 *     Bleibt nichts übrig („wie geht es dir"), ist es **Smalltalk → SMALLTALK**
 *     (kein Grounding). Das ist präziser als 0.5's reine Längen-Heuristik
 *     (kurz→AMBIG), trifft aber dieselbe Absicht — und liefert die vom M4-Schritt
 *     geforderte Trennung Wissen↔Smalltalk, die der Stub nicht konnte.
 *
 * Immer LOCAL-Provider (kein Cloud in 0.8).
 */
class KeywordRouterImpl(
    private val intent: IntentClassifier = IntentClassifier(),
) : KeywordRouter {

    // Knappe Zustands-Marker für terse Befehle ohne klassisches Verb (port 0.5
    // RouterService): „Licht aus", „Lampe an". Die kurzen Wörter NUR als ganze
    // Wörter, damit „an" in „Adenauer"/„danke" nicht falsch matcht.
    private val smartHomeStateWords = listOf("anmachen", "ausmachen", "angehen", "ausgehen")
    private val smartHomeShortState = Regex("(?:^|\\W)(an|aus|hell|dunkel)(?:\\W|$)")

    override fun decide(text: String): RouteDecision {
        val q = text.lowercase().trim()
        if (q.isBlank()) return RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "empty")

        // ── Hop-1a: Smart-Home (1:1 aus 0.5 RouterService.decide) ────────────────
        val hasTarget = IntentClassifier.Keywords.smartHomeTargets.any { q.contains(it) }
        val hasVerb = IntentClassifier.Keywords.smartHomeVerbs.any { q.contains(it) }
        val hasState = smartHomeStateWords.any { q.contains(it) } || smartHomeShortState.containsMatchIn(q)
        if (hasTarget && (hasVerb || hasState)) {
            return RouteDecision(RouteCategory.SMART_HOME, RouteProvider.LOCAL, "smart_home")
        }
        // Blanker Raum + Zustand („Schlafzimmer aus") — kein Target-Substantiv, aber
        // eindeutig ein Befehl (port 0.5 `mentionsKnownRoom`).
        if (hasState && mentionsKnownRoom(q)) {
            return RouteDecision(RouteCategory.SMART_HOME, RouteProvider.LOCAL, "smart_home_room_state")
        }

        // ── Hop-1c: Komfort-/Ambiente-Phrasen (0.8) — INDIREKTE Smart-Home-Absicht ──
        // „mir ist kalt", „es ist dunkel" nennen weder Gerät noch Raum, implizieren
        // aber eine Licht-/Klima-Aktion. KONSERVATIV: nur klare Mehrwort-Phrasen
        // (kein Einzel-Token), und Idiom-Blocker („lässt mich kalt", „warm ums herz")
        // haben Vorrang, damit übertragene Bedeutung NICHT als Befehl hijackt. Steht
        // NACH dem Raum/Target-Routing (das bleibt erste Instanz) und VOR der
        // Wissen↔Smalltalk-Trennung, damit Grounding-Fragen unberührt bleiben.
        if (isComfortIntent(q)) {
            return RouteDecision(RouteCategory.SMART_HOME, RouteProvider.LOCAL, "smart_home_comfort")
        }

        // ── Hop-1b: Wissen vs. Smalltalk (Content-Token-Reduktion, port 0.5) ─────
        return if (contentTokens(q).isEmpty()) {
            RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "smalltalk")
        } else {
            RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fact")
        }
    }

    /**
     * Prüft, ob [q] (lowercase) einen bekannten Raum-Namen als ganzes Wort enthält.
     * Whole-word-Match (Split an Nicht-Wort-Zeichen) verhindert, dass ein
     * Raum-Fragment in einem längeren Wort falsch matcht.
     */
    private fun mentionsKnownRoom(q: String): Boolean {
        val tokens = q.split(Regex("[^a-zäöüß0-9]+")).filter { it.isNotBlank() }.toSet()
        return IntentClassifier.Keywords.haRooms.any { it in tokens }
    }

    /**
     * Komfort-/Ambiente-Absicht: enthält [q] (lowercase) eine klare Mehrwort-Komfort-
     * phrase („mir ist kalt", „es ist dunkel") und KEINEN Idiom-Blocker? Bewusst
     * Mehrwort-`contains` statt Einzel-Token: „kalt"/„hell" allein triggern in normaler
     * Konversation zu oft (False-Positives), die volle Phrase „mir ist kalt" praktisch
     * nie. Die [COMFORT_BLOCKERS] haben Vorrang und fangen die übertragene Bedeutung ab
     * („das lässt mich kalt", „mir ist warm ums herz", „es zieht sich hin"), in der ein
     * Komfort-Wort vorkommt, aber KEINE Aktion gemeint ist.
     */
    private fun isComfortIntent(q: String): Boolean {
        if (COMFORT_BLOCKERS.any { q.contains(it) }) return false
        return COMFORT_PHRASES.any { q.contains(it) }
    }

    /**
     * Inhaltliche Tokens: ≥3 Zeichen (oder reine Zahl), nicht in der
     * Füller-/Smalltalk-Liste. Portiert aus der 0.5 `WikiGroundingService`-
     * Such-Query-Reduktion — derselbe Begriff von „trägt der Satz Inhalt?", der
     * auch im [de.hoshi.adapters.knowledge.Fts5GroundingAdapter] die Bridge-Query
     * speist.
     */
    private fun contentTokens(query: String): List<String> =
        query.lowercase()
            .map { ch -> if (ch.isLetterOrDigit() || ch in CONTENT_KEEP_CHARS) ch else ' ' }
            .joinToString("")
            .split(' ')
            .map { it.trim() }
            .filter { tok -> tok.isNotEmpty() && tok !in FILLER_TOKENS && (tok.length >= 3 || tok.all { it.isDigit() }) }
            .distinct()

    companion object {
        private val CONTENT_KEEP_CHARS = setOf('ä', 'ö', 'ü', 'ß', ' ')

        /**
         * Komfort-/Ambiente-Phrasen (0.8): INDIREKTE Smart-Home-Absicht ohne Gerät- oder
         * Raum-Nennung. KONSERVATIV als ganze Mehrwort-Phrasen gehalten (kein Einzel-Token),
         * damit sie normalen Smalltalk/Wissensfragen nicht kapern. DE + EN. EN bewusst nur
         * die eindeutigen Apostroph-/„i am"-Formen — die apostrophlosen „im"/„its" kollidieren
         * mit der dt. Präposition „im" („im cold war") bzw. dem engl. Possessiv („its dark side").
         */
        private val COMFORT_PHRASES: List<String> = listOf(
            // DE — Temperatur/Klima
            "mir ist kalt", "mir ist kühl", "mir ist warm", "mir ist heiß", "mir ist heiss",
            "es ist kühl", "es ist stickig", "es zieht",
            // DE — Licht
            "es ist dunkel", "es ist hell", "es ist zu hell",
            // EN — climate
            "i'm cold", "i am cold", "i'm hot", "i am hot", "it's stuffy",
            // EN — light
            "it's dark", "it's bright", "it's too bright",
        )

        /**
         * Idiom-Blocker: hier kommt ein Komfort-Wort VOR, ist aber übertragen gemeint und
         * impliziert KEINE Aktion. Hat Vorrang vor [COMFORT_PHRASES] (z.B. „mir ist warm
         * ums herz" enthält die Phrase „mir ist warm", meint aber Rührung, nicht Klima).
         */
        private val COMFORT_BLOCKERS: List<String> = listOf(
            "lässt mich kalt", "lässt dich kalt", "lässt ihn kalt", "lässt sie kalt",
            "lässt uns kalt", "lässt euch kalt", "kalt erwischt", "warm ums herz",
            "zieht sich",
        )

        /**
         * Füll-/Frage-Gerüst-/Smalltalk-Tokens (1:1 aus 0.5 `wikiGroundingFillerTokens`):
         * Grüße, Wohlbefinden-Floskeln, Frage-Wörter, Artikel/Konjunktionen. Ein Satz
         * NUR aus diesen → Smalltalk; bleibt ein Inhalts-Token übrig → Wissensfrage.
         */
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
            // Live-Bug 2026-07-01 („Kurz: alles ok bei dir?" → FACT_SHORT → kalte
            // Deflection): persönliche Zustands-/Befindlichkeits-Wörter, die die
            // Reduktion überlebten und Smalltalk als Wissensfrage fehl-routeten.
            // Jedes davon ist als EINZIGES Rest-Token nie eine echte Wissensfrage —
            // echte Fragen („Wer war BEI der Mondlandung dabei?", „Wie LÄUFT eine
            // Herztransplantation ab?") tragen immer mind. ein weiteres Inhalts-Token.
            "bei", "wach", "läuft", "läufts", "heißt", "heisst", "heute", "drauf", "soweit",
        )
    }
}
