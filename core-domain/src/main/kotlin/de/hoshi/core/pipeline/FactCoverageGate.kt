package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.lang.deOr

/**
 * **FactCoverageGate — die Anti-Konfabulations-Wand (Kai: „Wand statt Tapete").**
 *
 * Gemessener Befund: bei einer [RouteCategory.FACT_SHORT]-Wissensfrage, deren
 * Grounding NICHTS Brauchbares fand (kein Wiki-Hit über dem BM25-Gate, leerer
 * Block), freestylet das lokale 4B-Brain trotzdem — und erfindet Falsches
 * („Mittwoch kommt von der römischen Drei" — FALSCH; der Wochentagsartikel
 * existiert nicht in der Wiki-DB). Ein 4B hält keine „sei-ehrlich"-Prompt-Regel;
 * Ehrlichkeit muss QUERY-SEITIG in Kotlin erzwungen werden, nicht als Tapete im
 * System-Prompt.
 *
 * Dieses Gate ist rein deterministisch, pure Funktion, KEIN zweites LLM. Es
 * entscheidet aus (Kategorie, „hat Grounding gedeckt?") EINES von zwei Dingen:
 *
 *  - **[Decision.Proceed]** — normal weiter (Brain rufen). Der Default für ALLES
 *    außer dem einen Rettungs-Fall.
 *  - **[Decision.Deflect]** — den Brain NICHT rufen; der Aufrufer emittiert
 *    stattdessen die deterministisch-ehrliche [deflection]-Phrase als normalen
 *    Antwort-Turn (Start/TextDelta/Done). „Ich weiß es grad nicht sicher" schlägt
 *    „ich erfinde was Falsches".
 *
 * **Streng eingezäunt (bewusst konservativ — lieber durchlassen als falsch blocken):**
 *  - Nur [RouteCategory.FACT_SHORT] wird je deflektet. SMALLTALK / SMART_HOME /
 *    calc / timer / date / AMBIG / NEEDS_WEB / AGENT ⇒ IMMER [Decision.Proceed].
 *  - **Gegroundete Facts NICHT deflekten:** liegt echter Grounding-Kontext vor
 *    (Wiki-Hit passierte das Gate), läuft der Turn normal weiter.
 *  - Bei OFF ([enabled]==false, Default) ⇒ IMMER [Decision.Proceed] ⇒ byte-neutral,
 *    exakt das heutige Verhalten. Flag `HOSHI_FACT_COVERAGE_ENABLED` (Wiring in
 *    PipelineConfig, Andi flippt beim Deploy).
 *
 * Das [HonestyGate] (Rezept/Online/Existence/Named-Entity, VOR dem Routing) bleibt
 * unberührt — dieses Gate greift eine Scheibe später, am Konsum-Punkt des
 * Grounding-Blocks, und fängt genau die FACT_SHORT-Fragen ab, für die weder der
 * HonestyGate noch das Grounding etwas hatte.
 */
class FactCoverageGate(
    /**
     * Default `false` ⇒ [decide] gibt immer [Decision.Proceed] ⇒ Identität ⇒
     * byte-neutral. Erst `HOSHI_FACT_COVERAGE_ENABLED=true` schaltet die Wand scharf.
     */
    private val enabled: Boolean = false,
    /**
     * **Striktes Coverage-Gate (RCA „Grounding-Ehrlichkeit", 2026-07-02).** Der laxe
     * [Companion.groundingCovered]-Check ist nur `isNotBlank` — ein tangentialer
     * BM25-Treffer („Der Turm ist ziemlich hoch") zählt als „gedeckt", und das Brain
     * schwafelt Wissensfragen faktenfrei durch (Live-Beweis: „Wie hoch ist der
     * Eiffelturm?"). Bei `strict=true` prüft [groundingCovered] ZUSÄTZLICH
     * Query-Substanz: mindestens ein Content-Token der Frage (Tokens >3 Zeichen,
     * keine Frageworte/Filler wie wie/was/jahr/wurde/ist/der/kommt/name) muss
     * case-insensitiv im groundBlock vorkommen — sonst ist der Block off-target und
     * zählt NICHT als Deckung (⇒ Deflect-Pfad). Default `false` ⇒ byte-identisches
     * Verhalten zu heute. Flag `HOSHI_FACT_COVERAGE_STRICT_ENABLED` (Wiring in
     * PipelineConfig).
     */
    private val strict: Boolean = false,
) {

    /**
     * **Instanz-Sicht von [Companion.groundingCovered]**, die den [strict]-Modus trägt:
     *
     *  - `strict=false` (Default): byte-identisch zur laxen Companion-Funktion —
     *    non-blank + kein Bridge-Down-Sentinel (bzw. Nicht-LOCAL) ⇒ gedeckt.
     *  - `strict=true`: der laxe Check MUSS gelten UND der [groundBlock] muss
     *    mindestens ein Content-Token der [query] tragen ([queryOnTarget]) — ein
     *    tangentialer Treffer ohne Bezug zur Frage zählt nicht als Deckung.
     *
     * Nicht-LOCAL-Provider bleiben in BEIDEN Modi gedeckt (Grounding läuft für Cloud
     * gar nicht — der leere/fremde Block ist dort kein „kein Treffer").
     * [query]==null (Legacy-Aufrufer ohne Text) ⇒ Substanz-Check entfällt ⇒ lax.
     */
    fun groundingCovered(provider: RouteProvider, groundBlock: String, query: String? = null): Boolean {
        val lax = Companion.groundingCovered(provider, groundBlock)
        if (!strict || provider != RouteProvider.LOCAL || query == null) return lax
        return lax && queryOnTarget(groundBlock, query)
    }

    /** Zweiwertiges Urteil: normal weiter zum Brain, oder ehrlich deflekten (kein Brain). */
    sealed interface Decision {
        /** Normal weiter — den Brain rufen (der Default für alles außer dem Rettungs-Fall). */
        data object Proceed : Decision

        /** Brain NICHT rufen — der Aufrufer spricht die ehrliche [deflection]-Phrase. */
        data object Deflect : Decision
    }

    /**
     * Entscheidet aus [category], [groundingCovered] (siehe [Companion.groundingCovered])
     * und optional der [query] selbst.
     *
     * Deflect PASSIERT NUR, wenn ALLE gelten: Gate an ([enabled]),
     * [category]==[RouteCategory.FACT_SHORT], Grounding hat NICHT gedeckt, UND die
     * [query] sieht wie eine echte Wissensfrage aus ([looksLikeKnowledgeQuery]).
     * Jeder andere Fall ⇒ [Decision.Proceed] (konservativ, byte-neutral bei OFF).
     *
     * **Wärme-Leitplanke (Live-Bug 2026-07-01, Mira-Prinzip):** „Kurz: alles ok bei
     * dir?" wurde vom Keyword-Router als FACT_SHORT fehl-geroutet → Grounding leer →
     * kalte Deflection auf Smalltalk. Der Router ist präzisiert (Wurzel-Fix), aber
     * Filler-Blacklists lecken prinzipbedingt — darum prüft die WAND selbst am
     * Emissions-Punkt der Kälte: nur deflekten, wenn die Query ein Fragewort
     * (wer/was/wann/wo/warum/woher/…) PLUS ein Substanz-Token trägt. Bewusst
     * konservativ Richtung Wärme: lieber ein paar echte FACTs zum Brain durchlassen
     * (Prompt + Never-Silent fangen die) als EINEN Smalltalk-Turn kalt deflekten.
     *
     * [query]==null (Legacy-Aufrufer ohne Text) ⇒ Text-Check entfällt ⇒ das alte
     * Verhalten (deflect-berechtigt) — so bleibt die Wand ohne Text nicht blind offen.
     */
    fun decide(category: RouteCategory, groundingCovered: Boolean, query: String? = null): Decision {
        if (!enabled) return Decision.Proceed
        if (category != RouteCategory.FACT_SHORT) return Decision.Proceed
        if (groundingCovered) return Decision.Proceed
        if (query != null && !looksLikeKnowledgeQuery(query)) return Decision.Proceed
        return Decision.Deflect
    }

    companion object {
        /** Byte-neutraler Default: Gate AUS ⇒ immer [Decision.Proceed]. */
        val DISABLED: FactCoverageGate = FactCoverageGate(enabled = false)

        /**
         * **„Hat Grounding gedeckt?"** — der Erkenner für „Grounding war leer" am
         * Konsum-Punkt (TurnOrchestrator.brainTurn, nach `assemble` ⇒ [groundBlock] =
         * [TurnPromptAssembler.AssembledPrompt.groundBlock]).
         *
         * Liefert TRUE (⇒ NICHT deflekten) wenn:
         *  - der [provider] NICHT [RouteProvider.LOCAL] ist — ein Cloud-Modell weiß
         *    Fakten selbst, und Grounding läuft für Cloud gar nicht (der leere Block
         *    ist dort KEIN „kein Treffer", nur „nicht gebraucht"); ODER
         *  - der LOCAL-[groundBlock] echten Kontext trägt: nicht blank UND nicht der
         *    [TurnPromptAssembler.BRIDGE_DOWN_SENTINEL] (der ist ein STEUER-Marker,
         *    kein Wissen — bei tot-hängender Bridge ist ehrliches Deflekten korrekt).
         *
         * FALSE ⇒ „Grounding war leer" ⇒ FACT_SHORT wird deflektet (der Rettungs-Fall).
         */
        fun groundingCovered(provider: RouteProvider, groundBlock: String): Boolean =
            provider != RouteProvider.LOCAL ||
                (groundBlock.isNotBlank() && groundBlock != TurnPromptAssembler.BRIDGE_DOWN_SENTINEL)

        /**
         * **„Trifft der [groundBlock] die [query]?"** — der Substanz-Check des
         * [strict]-Modus (pure Funktion, deterministisch, case-insensitiv).
         *
         * Content-Tokens der Query = Tokens mit >3 Zeichen, die WEDER Interrogativ
         * ([INTERROGATIVES]) noch Frage-Gerüst/Filler ([STRICT_FILLER]) sind
         * („eiffelturm", „relativitätstheorie", „mauer"). Mindestens EINES davon muss
         * im Block vorkommen, sonst ist der Treffer tangential (off-target).
         *
         * Konservativ Richtung Durchlassen: trägt die Query KEIN Content-Token
         * (reine Fragewort-/Filler-Query), gibt es nichts zu prüfen ⇒ `true`
         * (die Wärme-Leitplanke [looksLikeKnowledgeQuery] fängt solche Fälle ohnehin).
         */
        fun queryOnTarget(groundBlock: String, query: String): Boolean {
            val block = groundBlock.lowercase()
            val contentTokens = query.lowercase().split(TOKEN_SPLIT).filter { tok ->
                tok.length > 3 && tok !in INTERROGATIVES && tok !in STRICT_FILLER
            }
            if (contentTokens.isEmpty()) return true
            return contentTokens.any { block.contains(it) }
        }

        /**
         * Frage-Gerüst-/Filler-Tokens des [strict]-Substanz-Checks (>3 Zeichen, sonst
         * filtert die Längen-Regel sie ohnehin) — dieselbe Filler-Reduktions-Idee wie
         * beim Router: Wörter, die in JEDER Wissensfrage stehen können, ohne das Thema
         * zu tragen („in welchem **Jahr** **wurde** …", „woher **kommt** der **Name** …").
         */
        private val STRICT_FILLER: Set<String> = setOf(
            // DE — Frage-Gerüst
            "jahr", "jahre", "jahren", "wurde", "wurden", "worden", "heißt", "heisst",
            "kommt", "kommen", "name", "namen", "viele", "vielen", "hoch", "groß", "gross",
            "lang", "alt", "weit", "gibt", "haben", "hatte", "hatten", "sind", "eine",
            "einem", "einen", "einer", "eines", "dieser", "diese", "dieses", "denn",
            "eigentlich", "genau", "bitte", "mal",
            // EN — question scaffold
            "year", "does", "did", "was", "were", "many", "much", "tall", "high", "long",
            "old", "name", "named", "called", "come", "from", "there", "have", "has",
            "please", "exactly",
        )

        /**
         * **„Sieht [query] wie eine echte WISSENS-Frage aus?"** — der Wärme-Filter der
         * Wand (pure Funktion, deterministisch). TRUE nur, wenn BEIDES gilt:
         *
         *  1. **Fragewort/Wissens-Marker**: ein Interrogativ als ganzes Token
         *     (wer/was/wann/wo/wie/warum/woher/… bzw. who/what/why/how/…) ODER ein
         *     Wissens-Imperativ als Präfix-Phrase („erklär…", „definier…", „explain…").
         *  2. **Substanz-Token**: mind. ein Token ≥3 Zeichen (oder reine Zahl), das
         *     WEDER Interrogativ NOCH in [SMALLTALK_STOP] ist („mittwoch", „helgoland").
         *
         * FALSE ⇒ die Query ist Smalltalk-förmig („Kurz: alles ok bei dir?", „na, wie
         * läufts?") ⇒ NIE deflekten, auch wenn sie als FACT_SHORT fehl-geroutet ankam.
         * Richtung des Irrtums bewusst: eine fragewortlose Nominal-Query („Hauptstadt
         * von Australien") gilt NICHT als Wissensfrage und proceeded zum Brain —
         * akzeptierter Trade-off (Wärme > Wand-Lückenlosigkeit; Prompt fängt das).
         */
        fun looksLikeKnowledgeQuery(query: String): Boolean {
            val q = query.lowercase()
            val tokens = q.split(TOKEN_SPLIT).filter { it.isNotBlank() }
            val hasQuestionMarker = tokens.any { it in INTERROGATIVES } ||
                KNOWLEDGE_IMPERATIVES.any { q.contains(it) }
            if (!hasQuestionMarker) return false
            return tokens.any { tok ->
                tok !in INTERROGATIVES && tok !in SMALLTALK_STOP &&
                    (tok.length >= 3 || tok.all { it.isDigit() })
            }
        }

        private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

        /** Frageworte (DE+EN), als ganze Tokens gematcht — Bedingung 1 des Wärme-Filters. */
        private val INTERROGATIVES: Set<String> = setOf(
            "wer", "wen", "wem", "wessen", "was", "wann", "wo", "wie", "wieso", "warum",
            "weshalb", "woher", "wohin", "wozu", "wodurch", "womit", "wofür", "wofuer",
            "welche", "welcher", "welches", "welchen", "welchem",
            "who", "whom", "whose", "what", "when", "where", "why", "which", "how",
        )

        /** Wissens-Imperative ohne Fragewort („Erklär mir Photosynthese") — Substring-Match. */
        private val KNOWLEDGE_IMPERATIVES: List<String> = listOf(
            "erklär", "erklaer", "definier", "explain", "define ",
        )

        /**
         * Persönliche/Zustands-/Floskel-Tokens, die NICHT als Substanz zählen (DE+EN,
         * bewusst kompakt — Pendant zur Router-Filler-Liste, hier aber nur als
         * Deflect-Bremse: ein unbekanntes Token zählt als Substanz). Deckt die
         * Smalltalk-Formen „wie geht es dir (heute)", „alles ok/klar bei dir",
         * „bist du wach", „na, wie läufts", „wie heißt du", „how are you doing" ab.
         */
        private val SMALLTALK_STOP: Set<String> = setOf(
            // DE — Personal-/Befindlichkeits-Gerüst
            "dir", "dich", "dein", "deine", "mir", "mich", "mein", "meine", "uns", "euch", "ihnen",
            "geht", "gehts", "läuft", "läufts", "laufts", "isses", "machst", "macht", "tust", "tut",
            "bist", "bin", "sind", "seid", "ist", "war", "warst", "waren",
            "alles", "gut", "klar", "okay", "schön", "toll", "fit", "wach", "drauf", "soweit",
            "sonst", "selbst", "gerade", "grad", "grade", "jetzt", "heute", "momentan",
            "name", "heißt", "heisst",
            "hallo", "hey", "moin", "servus", "danke", "bitte", "kurz", "mal", "doch",
            "und", "oder", "aber", "auch", "noch", "schon", "denn", "nur", "eigentlich",
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
            // EN — personal/state scaffold
            "are", "you", "your", "yours", "doing", "going", "feeling", "been",
            "today", "now", "well", "fine", "good", "alright", "there", "here", "whats", "hows",
        )

        /**
         * Ehrliche Deflection-Phrase (DE): kein „ich kann nicht", sondern zugewandt +
         * bietet das Nachschauen an. Deterministisch, keine Zufalls-Streuung nötig.
         */
        const val DEFLECT_DE: String =
            "Ehrlich, das hab ich grad nicht sicher parat — soll ich kurz nachschauen?"

        /** Ehrliche Deflection-Phrase (EN), analog. */
        const val DEFLECT_EN: String =
            "Honestly, I'm not sure I've got that right now — want me to look it up quickly?"

        /**
         * Pure, deterministische Auswahl der Deflection nach Turn-Sprache. ES/FR/IT
         * fallen auf EN zurück (s. [de.hoshi.core.pipeline.lang.deOr]), bis ein
         * Übersetzer-Pod eigene Strings liefert.
         */
        fun deflection(language: Language): String = language.deOr(DEFLECT_DE, DEFLECT_EN)
    }
}
