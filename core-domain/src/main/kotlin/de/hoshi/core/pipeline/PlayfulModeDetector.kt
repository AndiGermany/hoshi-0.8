package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.deOr

/**
 * **PlayfulModeDetector — der Erkenner für „hier wird GESPIELT".**
 *
 * **Der Vorfall (Andi, 2026-07-25, echtes Gespräch):**
 * ```
 * Andi : „Stell dir vor, eine Kuh. Wie zieht sie ihre Hose an? Über die vorderen
 *         Pfoten oder über die hinteren Pfoten?"
 * Hoshi: „Ich glaube, die hinteren Pfoten sind dafür besser geeignet …"   [Wissen gedeckt]
 * Andi : „Die brauchst du schon um rum zu stehen, aber die hinteren ja auch …"
 * Hoshi: „Du meinst die Beine, oder? Die brauchen wir beide, um stabil zu stehen."
 * ```
 * Andis Urteil war „sie verliert immer den Context" — **die Messung gegen Prod sagt
 * etwas anderes:** der Verlauf kam an und WURDE benutzt (`grounded=true`,
 * `category=FACT_SHORT`). Das Gedächtnis war nie das Problem.
 *
 * **Die echte Ursache: es fehlt das REGISTER, nicht das Gedächtnis.** Ein erfundenes
 * Gedankenexperiment wird vom [de.hoshi.web.routing.KeywordRouterImpl] als
 * FACT_SHORT eingestuft (der Satz trägt Inhalts-Tokens ⇒ „Wissensfrage") und damit
 * durch die volle Wissens-/Grounding-Maschinerie geschickt. Die ist bewusst
 * vorsichtig: sie erdet in belegtem Wissen und hält zurück, was nicht gedeckt ist.
 * Auf eine Quatsch-Hypothese losgelassen erzeugt sie genau die beobachteten
 * zögerlichen Sachsätze — und „Du meinst die Beine, oder?" ist KEINE Kontext-Amnesie,
 * sondern eine FAKTENKORREKTUR: Hoshi berichtigt „Pfoten" zu „Beinen", weil er im
 * Sachmodus steckt.
 *
 * **Die Richtung des Irrtums ist bewusst asymmetrisch.** Ein verpasster Spielfall
 * kostet Charme. Eine als Spiel fehl-eingestufte WISSENSFRAGE kostet Ehrlichkeit —
 * Hoshi würde erfinden statt zu erden. Darum ist dieser Erkenner streng eingezäunt:
 *
 *  - **Nur explizite Marker** ([HYPOTHETICAL_MARKERS], DE+EN) oder ein enges
 *    **Absurditäts-Paar** ([ANIMALS] × [HUMAN_ARTIFACTS] — Kuh mit Hose, Katze mit
 *    Fahrrad) öffnen das Spiel. Kein Sentiment, kein Modell, keine Heuristik auf
 *    Satzlänge.
 *  - **[SERIOUS_BLOCKERS] haben Vorrang** (Muster: die Idiom-Blocker des
 *    KeywordRouters): eine definitorische Frage bleibt eine Wissensfrage, auch wenn
 *    zufällig ein Marker-Wort darin steht („Was bedeutet ‚hypothetisch'?").
 *  - **Der Faden hält nur mit Anker** ([continuesThread]): ein Folge-Turn ohne
 *    eigenen Marker bleibt nur dann im Spiel, wenn er ein Inhalts-Token des
 *    ERÖFFNENDEN Turns wieder aufgreift („hinteren"). Kein Overlap ⇒ Spiel aus.
 *  - **OFF ist der Default** ([enabled]==false ⇒ [detect] immer `false`) ⇒
 *    byte-neutral.
 *
 * Reine, deterministische Funktion — kein I/O, kein LLM, kein Zustand. Das, was im
 * Spiel-Modus ANDERS läuft, entscheidet der [TurnOrchestrator]:
 * Route auf SMALLTALK (⇒ kein Grounding, keine Fakten-Deckungs-Prüfung, kein
 * lügender „Wissen gedeckt"-Chip) + [playfulHint] im Prompt.
 */
class PlayfulModeDetector(
    /** Default `false` ⇒ [detect] gibt immer `false` ⇒ Identität ⇒ byte-neutral. */
    private val enabled: Boolean = false,
) {

    /**
     * „Wird in diesem Turn gespielt?" — `true` nur, wenn [enabled] UND entweder der
     * Text selbst ein Spiel ERÖFFNET ([isPlayOpener]) oder er einen bereits
     * eröffneten Faden nachweislich WEITERFÜHRT ([continuesThread]).
     *
     * [history] ist der Client-Verlauf des Turns (`ChatRequest.history`). Leer ⇒ nur
     * der Eröffnungs-Pfad greift; ein Voice-Turn ohne mitgeschickten Verlauf verliert
     * also den Faden — bewusst in Kauf genommen (lieber ein verpasster Spiel-Turn als
     * ein Spiel-Modus, der sich aus einer fremden Quelle selbst am Leben hält).
     */
    fun detect(text: String, history: List<ChatMessage> = emptyList()): Boolean {
        if (!enabled) return false
        if (text.isBlank()) return false
        val q = text.lowercase()
        // Beide Veto-Listen gelten für den GANZEN Turn, nicht nur für die Fortsetzung:
        // „Mal im Ernst, stell dir vor …" ist ein Widerspruch — und in einem Widerspruch
        // gewinnt die konservative Lesart (Sachmodus).
        if (SERIOUS_BLOCKERS.any { it.containsMatchIn(q) }) return false
        if (THREAD_EXITS.any { it.containsMatchIn(q) }) return false
        if (isPlayOpener(text)) return true
        return continuesThread(text, history)
    }

    companion object {
        /** Byte-neutraler Default: Erkenner AUS ⇒ [detect] immer `false`. */
        val DISABLED: PlayfulModeDetector = PlayfulModeDetector(enabled = false)

        /** Wie viele Verlaufs-Nachrichten rückwärts nach einem Spiel-Eröffner gesucht wird. */
        private const val HISTORY_WINDOW = 8

        /**
         * **Eröffnet [text] ein Spiel?** — pur, deterministisch, case-insensitiv.
         *
         * `true` bei (a) einem expliziten hypothetischen Marker ([HYPOTHETICAL_MARKERS])
         * ODER (b) einem Absurditäts-Paar ([isAbsurdPair]). In BEIDEN Fällen haben die
         * [SERIOUS_BLOCKERS] Vorrang: eine definitorische/Übersetzungs-Frage bleibt eine
         * Wissensfrage, egal welches Marker-Wort darin vorkommt.
         */
        fun isPlayOpener(text: String): Boolean {
            val q = text.lowercase()
            if (SERIOUS_BLOCKERS.any { it.containsMatchIn(q) }) return false
            if (HYPOTHETICAL_MARKERS.any { it.containsMatchIn(q) }) return true
            return isAbsurdPair(q)
        }

        /**
         * **Hält [text] den Faden eines schon laufenden Spiels?**
         *
         * Sucht im letzten [HISTORY_WINDOW]-Fenster die JÜNGSTE User-Nachricht, die
         * selbst ein Eröffner war, und verlangt vom aktuellen Text mindestens EIN
         * gemeinsames Inhalts-Token mit genau dieser Eröffnung ([threadTokens]).
         * Der Anker ist bewusst der ERÖFFNER (nicht der ganze Verlauf, nicht Hoshis
         * eigene Antworten): so kann sich ein Spiel-Modus nicht über generische
         * Zwischen-Sätze selbst verlängern.
         *
         * Zwei Ausstiege haben Vorrang und beenden den Faden sofort:
         *  - [SERIOUS_BLOCKERS] („Was bedeutet …") — jemand fragt wieder echt.
         *  - [THREAD_EXITS] („im Ernst", „mal was anderes", „seriously") — jemand sagt
         *    ausdrücklich, dass Schluss mit Spielen ist.
         */
        fun continuesThread(text: String, history: List<ChatMessage>): Boolean {
            if (history.isEmpty()) return false
            val q = text.lowercase()
            if (SERIOUS_BLOCKERS.any { it.containsMatchIn(q) }) return false
            if (THREAD_EXITS.any { it.containsMatchIn(q) }) return false
            val opener = history.takeLast(HISTORY_WINDOW)
                .lastOrNull { it.role.equals("user", ignoreCase = true) && isPlayOpener(it.content) }
                ?: return false
            val anchors = threadTokens(opener.content)
            if (anchors.isEmpty()) return false
            return threadTokens(text).any { it in anchors }
        }

        /**
         * **Absurditäts-Paar: Tier + menschliches Objekt.** Beide als GANZE Wörter
         * (Lookarounds statt `\b` — `\b` kennt ohne `UNICODE_CHARACTER_CLASS` keine
         * Umlaute, dieselbe Lehre wie in
         * [de.hoshi.adapters.knowledge.Fts5GroundingAdapter]).
         *
         * Das Vokabular ist ABSICHTLICH klein und hart kuratiert: Kleidungsstücke,
         * Fahrzeuge und Geräte, die ein Tier nie besitzt. Bekannte, bewusst gedrehte
         * Trade-offs: „Warum bellt der Hund, wenn ich meine Schuhe anziehe?" gilt als
         * Spiel (Schaden gering — Grounding hätte dafür ohnehin nichts) und
         * mehrdeutige EN-Wörter (`pants` als Verb bei Hunden, `tie`, `suit`, `dress`,
         * `coat`, `bike`) sind bewusst NICHT in der Liste.
         *
         * **Die Sprachen teilen sich EINE Liste — Homographen sind darum Gift.** Zwei
         * fielen im Test auf und sind bewusst RAUS: `hat` (EN Kopfbedeckung ==
         * deutsches Hilfsverb, ließ „Wie viele Zähne **hat** eine Kuh?" als Spiel
         * gelten) und `hut` (deutsche Kopfbedeckung == englische Hütte). Die
         * Kopfbedeckung deckt DE weiter über „mütze".
         */
        fun isAbsurdPair(lowercaseText: String): Boolean =
            ANIMALS.any { wholeWord(it).containsMatchIn(lowercaseText) } &&
                HUMAN_ARTIFACTS.any { wholeWord(it).containsMatchIn(lowercaseText) }

        /**
         * Inhalts-Tokens für den Faden-Anker: ≥3 Zeichen, nicht in [THREAD_STOP].
         * Bewusst ≥3 (nicht ≥4 wie beim strikten Coverage-Check), weil das Thema eines
         * Spiels oft kurz ist („Kuh", „Hut"). Die generischen Vergleichs-Wörter
         * („hoch", „viele", „tall") stehen dafür IM Stop-Set — sonst könnte eine echte
         * Folge-Wissensfrage („Wie hoch ist der Eiffelturm?") über ein zufälliges
         * Allerwelts-Token im Spiel hängenbleiben.
         */
        fun threadTokens(text: String): Set<String> =
            text.lowercase()
                .split(TOKEN_SPLIT)
                .filter { it.length >= 3 && it !in THREAD_STOP }
                .toSet()

        private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

        /** Ganzes-Wort-Matcher (umlautfest, s. [isAbsurdPair]). */
        private fun wholeWord(word: String): Regex =
            Regex("(?u)(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])")

        /** Wie [wholeWord], aber für ein Muster mit eigener Regex-Syntax (Phrasen/Alternativen). */
        private fun phrase(pattern: String): Regex =
            Regex("(?u)(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])")

        /**
         * **Explizite hypothetische/spielerische Marker (DE+EN).** Mehrwort-Phrasen,
         * wo immer möglich — genau die Lehre der [de.hoshi.web.routing.KeywordRouterImpl]-
         * Komfort-Phrasen: ein Einzel-Token triggert in normaler Konversation zu oft.
         *
         * `angenommen` ist NUR satz-initial ein Marker („Angenommen, eine Kuh …") —
         * mitten im Satz heißt es etwas völlig anderes („der Antrag wurde angenommen",
         * „HA hat den Call angenommen"), darum die Verankerung an Satz-Anfang bzw.
         * Satzzeichen.
         */
        private val HYPOTHETICAL_MARKERS: List<Regex> = listOf(
            // DE — „stell dir (das mal) vor", „stellen wir uns (mal) vor".
            // `[\p{L}\p{N}]` statt `\w`: `\w` ist in Java ohne `(?U)` ASCII-only und
            // würde an einem Umlaut-Zwischenwort zerbrechen (dieselbe Lehre wie die
            // Lookarounds unten).
            phrase("stell(?:e)?\\s+(?:dir|euch)\\s+(?:[\\p{L}\\p{N}]+\\s+){0,3}vor"),
            phrase("stellen\\s+wir\\s+uns\\s+(?:[\\p{L}\\p{N}]+\\s+){0,2}vor"),
            // DE — „was wäre, wenn", „was wäre wenn"
            phrase("was\\s+w(?:ä|ae)re[,\\s]+wenn"),
            // DE — „nehmen wir (mal) an"
            phrase("nehmen\\s+wir\\s+(?:mal\\s+)?an"),
            // DE — hypothetisch/Gedankenexperiment
            phrase("hypothetisch(?:e|es|er|en)?"),
            phrase("gedankenexperiment(?:e|s)?"),
            // EN
            phrase("imagine"),
            phrase("what\\s+if"),
            phrase("suppose|supposing"),
            phrase("let(?:'|’)?s\\s+say|let\\s+us\\s+say"),
            phrase("just\\s+for\\s+fun"),
            phrase("hypothetical(?:ly)?"),
            phrase("thought\\s+experiment"),
            phrase("pretend"),
            // DE — satz-initiales „(mal) angenommen"
            Regex("(?u)(?:^|[.!?;:]\\s*)(?:mal\\s+)?angenommen(?![\\p{L}\\p{N}])"),
        )

        /**
         * **Ernst-Blocker — Vorrang vor JEDEM Marker.** Hier fragt jemand nach einer
         * Bedeutung/Definition/Übersetzung; das ist eine echte Wissensfrage, die
         * geerdet gehört, auch wenn zufällig „hypothetisch"/„imagine"/„suppose" darin
         * vorkommt („Was bedeutet ‚hypothetisch'?"). Muster: die COMFORT_BLOCKERS des
         * KeywordRouters — der Blocker gewinnt, weil die teure Fehlrichtung
         * (Wissensfrage → Spiel) geschlossen bleiben muss.
         */
        private val SERIOUS_BLOCKERS: List<Regex> = listOf(
            phrase("was\\s+bedeutet"),
            phrase("was\\s+hei(?:ß|ss)t"),
            phrase("was\\s+ist\\s+die\\s+bedeutung"),
            phrase("definier(?:e|st|en)?"),
            phrase("erkl(?:ä|ae)r(?:e|st|en)?\\s+mir"),
            phrase("(?:ü|ue)bersetz(?:e|t|en)?"),
            // `\S+` statt `\w+`: das Zitat steht live in Anführungszeichen
            // („What does 'suppose' mean?") — genau der Fall, in dem ein Marker-Wort
            // ERWÄHNT statt BENUTZT wird und der Blocker greifen MUSS.
            Regex("what\\s+does\\s+\\S+\\s+mean"),
            phrase("what(?:'|’)?s\\s+the\\s+meaning"),
            phrase("what\\s+is\\s+the\\s+meaning"),
            phrase("define"),
            phrase("translate"),
        )

        /**
         * **Faden-Ausstiege:** der Nutzer sagt ausdrücklich, dass es wieder ernst wird
         * (oder das Thema wechselt). Beendet [continuesThread] sofort — auch wenn der
         * Satz zufällig noch ein Wort aus dem Spiel trägt.
         */
        private val THREAD_EXITS: List<Regex> = listOf(
            phrase("im\\s+ernst"),
            phrase("mal\\s+im\\s+ernst"),
            phrase("jetzt\\s+mal\\s+ernst"),
            phrase("ernsthaft"),
            phrase("ganz\\s+was\\s+anderes"),
            phrase("mal\\s+was\\s+anderes"),
            phrase("themawechsel"),
            phrase("seriously"),
            phrase("on\\s+a\\s+serious\\s+note"),
            phrase("different\\s+topic"),
            phrase("change\\s+of\\s+topic"),
        )

        /** Tiere (DE+EN, inkl. gängiger Plurale) — Hälfte 1 des Absurditäts-Paars. */
        private val ANIMALS: Set<String> = setOf(
            // DE
            "kuh", "kühe", "katze", "katzen", "hund", "hunde", "pferd", "pferde",
            "schwein", "schweine", "huhn", "hühner", "schaf", "schafe", "ziege", "ziegen",
            "ente", "enten", "elefant", "elefanten", "giraffe", "giraffen",
            "pinguin", "pinguine", "maus", "mäuse", "hamster", "kaninchen", "hase", "hasen",
            "esel", "fisch", "fische", "biene", "bienen", "ameise", "ameisen",
            "spinne", "spinnen", "krokodil", "wal", "wale", "schnecke", "schnecken",
            // EN
            "cow", "cows", "cat", "cats", "dog", "dogs", "horse", "horses",
            "pig", "pigs", "chicken", "chickens", "sheep", "goat", "goats",
            "duck", "ducks", "elephant", "elephants", "giraffe", "giraffes",
            "penguin", "penguins", "mouse", "mice", "rabbit", "rabbits",
            "donkey", "donkeys", "bee", "bees", "ant", "ants", "spider", "spiders",
            "crocodile", "whale", "whales", "snail", "snails",
        )

        /**
         * Menschliche Objekte — Hälfte 2 des Absurditäts-Paars. Kleidung, Fahrzeuge,
         * Geräte: Dinge, die ein Tier nie besitzt. Mehrdeutige EN-Wörter sind bewusst
         * NICHT drin (s. [isAbsurdPair]).
         */
        private val HUMAN_ARTIFACTS: Set<String> = setOf(
            // DE
            "hose", "hosen", "schuh", "schuhe", "socke", "socken", "krawatte", "schlips",
            "hemd", "hemden", "jacke", "jacken", "mantel", "mütze", "brille",
            "handtasche", "rucksack", "fahrrad", "fahrräder", "motorrad",
            "laptop", "smartphone", "handy", "zeitung", "aktenkoffer",
            "anzug", "kleid", "gürtel", "unterhose", "pullover",
            // EN
            "trousers", "shoe", "shoes", "sock", "socks", "necktie", "shirt", "shirts",
            "jacket", "handbag", "backpack", "bicycle", "motorcycle",
            "laptop", "smartphone", "newspaper", "briefcase", "sweater", "underpants",
        )

        /**
         * Stop-Tokens des Faden-Ankers ([threadTokens]): Funktionswörter DE+EN PLUS die
         * generischen Vergleichs-/Maß-Wörter. Letztere stehen bewusst drin, damit eine
         * echte Folge-Wissensfrage („Wie **hoch** ist der Eiffelturm?") nicht über ein
         * Allerwelts-Token im Spiel-Modus hängenbleibt.
         */
        private val THREAD_STOP: Set<String> = setOf(
            // DE — Funktionswörter/Frage-Gerüst
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
            "und", "oder", "aber", "auch", "noch", "schon", "denn", "nur", "doch", "mal", "ja", "nein",
            "ist", "sind", "war", "waren", "bin", "bist", "hat", "haben", "hatte", "hätte",
            "wird", "wurde", "kann", "kannst", "könnte", "muss", "müsste", "soll", "sollte",
            "sich", "sie", "ihr", "ihre", "ihren", "ihrem", "ihres", "man", "wir", "uns", "euch",
            "mir", "mich", "dir", "dich", "mein", "meine", "dein", "deine",
            "nicht", "kein", "keine", "sonst", "dann", "wenn", "weil", "dass", "damit", "also",
            "wie", "was", "wer", "wann", "warum", "wieso", "welche", "welcher", "welches",
            "über", "unter", "vor", "nach", "bei", "mit", "ohne", "für", "aus", "auf", "vom", "zum", "zur",
            "sehr", "ganz", "immer", "gerade", "grad", "eigentlich", "vielleicht",
            // DE — generische Vergleichs-/Maß-Wörter (Anti-Hängenbleiben)
            "hoch", "höhe", "groß", "größe", "klein", "lang", "länge", "weit", "breit",
            "alt", "viel", "viele", "wenig", "jahr", "jahre", "jahren", "meter", "grad",
            // EN — function words
            "the", "and", "but", "not", "for", "with", "without", "you", "your", "she", "her",
            "his", "him", "its", "they", "them", "their", "this", "that", "these", "those",
            "are", "was", "were", "has", "have", "had", "can", "could", "would", "should",
            "does", "did", "doing", "there", "here", "then", "than", "too", "also", "just",
            "what", "who", "when", "why", "which", "how", "because", "about", "over", "under",
            // EN — generic comparison/measure words
            "tall", "high", "big", "small", "long", "wide", "old", "many", "much", "year", "years",
        )

        /**
         * **Der Spiel-Hinweis fürs Prompt (DE/EN).** Wird vom [TurnOrchestrator] als
         * `followBlock` an [TurnPromptAssembler.assemble] gereicht und landet damit am
         * Ende des System-Prompts.
         *
         * Zwei Dinge stehen bewusst drin: „halte den Faden" (gegen die beobachtete
         * Faktenkorrektur „Du meinst die Beine, oder?") UND der Ehrlichkeits-Kern —
         * fabulieren ja, aber Erfundenes NIE als Tatsache ausgeben. Der Hinweis
         * ersetzt keine Wand: er läuft ausschließlich auf einer Route, die ohnehin
         * kein Grounding und keinen „Wissen gedeckt"-Chip trägt.
         */
        const val PLAY_HINT_DE: String =
            "\n\n[SPIELMODUS] Das hier ist ein ausgedachtes Gedankenspiel, keine Wissensfrage. " +
                "Spiel mit, denk mit und halte den Faden der Erfindung — korrigiere keine Begriffe " +
                "und liefere keine Sachbelehrung. Erfundenes bleibt erkennbar erfunden: gib nichts " +
                "davon als Tatsache aus."

        /** Englisches Gegenstück zu [PLAY_HINT_DE] (ES/FR/IT fallen darauf zurück, s. [deOr]). */
        const val PLAY_HINT_EN: String =
            "\n\n[PLAY MODE] This is a made-up thought experiment, not a knowledge question. " +
                "Play along, think along and keep the invented thread going — don't correct terms " +
                "and don't lecture with facts. Made-up stays recognisably made-up: never present " +
                "any of it as a fact."

        /** Pure, deterministische Auswahl des Spiel-Hinweises nach Turn-Sprache. */
        fun playfulHint(language: Language): String = language.deOr(PLAY_HINT_DE, PLAY_HINT_EN)
    }
}
