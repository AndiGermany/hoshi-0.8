package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language

/**
 * Single source of truth für die Keyword-Listen + heuristischen Klassifizierer
 * im Routing-Hot-Path (Smart-Home-Kandidat, Komplexitätsscore, OpenClaw-Eligibility)
 * — PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger.
 *
 * Entkoppelt von Spring: statt `HoshiProperties` nimmt der Konstruktor direkt den
 * [complexityThreshold] (Default 4) entgegen. Reines Kotlin, kein `@Service` — das
 * Wiring (Config-Injektion) kommt im Orchestrator.
 *
 * **Mehrsprachigkeit (2026-07-25):** Die Multilingualitäts-Runde hat die ANTWORTEN
 * in fünf Sprachen gebracht, die ERKENNER aber nicht — englische Befehle liefen
 * still gegen deutsche Wortlisten und degradierten zu Konversation. Seitdem wählt
 * [usesEnglishKeywords] das Wort-Set der Turn-Sprache: [Keywords] (DE, wörtlich
 * der Bestand) und [KeywordsEn] (EN). ES/FR/IT tragen NOCH keine eigenen Erkenner
 * und laufen bewusst auf dem DE-Set weiter (ehrlich benannt statt still degradiert
 * — die Antwort-Schicht ist dort fertig, die Erkenner-Schicht nicht).
 */
class IntentClassifier(
    private val complexityThreshold: Int = 4,
) {
    /**
     * **DE-Wortlisten — wörtlich der Bestand.** Diese Listen sind Andis Alltag; sie
     * bleiben Byte für Byte, wie sie waren. Neue Sprachen bekommen ein EIGENES Set
     * ([KeywordsEn]) statt Einträge hier hinein zu mischen.
     */
    object Keywords {
        val smartHomeVerbs = setOf(
            "schalte", "dimme", "setze", "stelle", "starte", "stoppe", "öffne", "schließe",
        )
        val smartHomeTargets = setOf(
            "licht", "lampe", "rollo", "jalousie", "heizung", "szene", "fernseher", "musik",
        )
        val complexityMarkers = listOf(
            "routine", "wenn dann", "falls dann", "plan erstellen", "strategie",
            "mehrere räume", "überall", "gleichzeitig",
        )
        val agentMarkers = listOf(
            "erinnere dich", "merke dir", "vergiss nicht",
            "suche im internet", "google",
            "spiel mir", "spiel den", "spiel die", "nächste episode", "was lief",
            "wieviel strom", "stromverbrauch", "verbrauch",
            "wie warm", "wie kalt", "temperatur",
            "erstelle eine einkaufsliste", "füge zur einkaufsliste",
            "füge hinzu", "zur liste",
            "was steht", "kalender", "termin",
            "organisiere", "plane", "erstelle einen plan",
            "öffne", "klick auf", "browser",
        )
        val haRooms = listOf(
            "wohnzimmer", "schlafzimmer", "küche", "bad", "büro", "flur", "keller",
        )
    }

    /**
     * **EN-Wortlisten + EN-Matching.** Bewusst getrennt von [Keywords] statt
     * hineingemischt: so kann der deutsche Pfad nicht wackeln.
     *
     * **Match-Modus:** ganze WÖRTER (Token-Set), nicht `contains` wie im deutschen
     * Bestand. Englische Funktionswörter sind kurz und stecken in Dutzenden anderer
     * Wörter (`set` in „sunset"/„settings"/„asset", `on` in „only"/„conference") —
     * ein Substring-Match wäre eine False-Positive-Maschine. Ein falsch erkannter
     * Smart-Home-Befehl schaltet echte Geräte in einer echten Wohnung; diese
     * Richtung bleibt konservativ geschlossen.
     */
    object KeywordsEn {
        /** Schalt-/Stell-Verben. „dim"/„brighten" sind für sich schon Licht-Verben. */
        val smartHomeVerbs = setOf(
            "turn", "switch", "dim", "dims", "brighten", "set", "sets",
            "open", "close", "shut", "activate", "toggle", "power",
        )

        /**
         * Geräte-/Ziel-Substantive — Parität zur deutschen [Keywords.smartHomeTargets]
         * (licht·lampe·rollo·jalousie·heizung·szene·fernseher·musik), plus die üblichen
         * englischen Plurale/Synonyme. BEWUSST OHNE „temperature": das Wort steht viel
         * zu oft in harmlosen Sätzen („what temperature should I set the oven to?") —
         * es zählt nur über die engere [setpointTargets]-Regel als Ziel.
         */
        val smartHomeTargets = setOf(
            "light", "lights", "lamp", "lamps", "bulb", "bulbs",
            "blind", "blinds", "shutter", "shutters", "curtain", "curtains",
            "heating", "heater", "radiator", "thermostat",
            "scene", "tv", "television", "music",
        )

        /**
         * Ziele, die NUR MIT einem konkreten Zahlwert als Smart-Home-Ziel zählen
         * (Soll-Wert-Ansage „set the temperature to 21 degrees"). Ohne Zahl ist
         * „temperature" fast immer Konversation oder eine Lese-Frage.
         */
        val setpointTargets = setOf("temperature", "temp", "degrees")

        /**
         * Nicht-Haus-Geräte, die einen Soll-Wert tragen können — ihretwegen wird die
         * [setpointTargets]-Regel NICHT scharf („set the oven to 200 degrees" ist kein
         * Thermostat-Befehl).
         */
        val setpointBlockers = setOf("oven", "stove", "grill", "kettle", "fridge", "freezer", "car", "water")

        /** Knappe Zustands-Marker für terse Befehle ohne Verb („lights off", „lamp on"). */
        val stateWords = setOf("on", "off")

        /**
         * **Englische Raum-ALIASE — reine Zuordnung, KEINE Übersetzung.** Der Raumname
         * selbst kommt aus Home Assistant, ist deutsch und bleibt unangetastet
         * (eiserne Projektregel: Nutzerdaten werden nie übersetzt). Diese Wörter sind
         * nur ZUSÄTZLICHE Schlüssel, über die ein englischer Satz dieselbe reale
         * HA-`area_id` trifft — die echte Alias→area_id-Tabelle lebt in
         * `de.hoshi.core.tools.ToolAreas.ROOMS` bzw. hinter dem `AreaCatalogPort`.
         * Hier stehen sie nur, damit der Router „kitchen off" als Raum+Zustand erkennt.
         */
        val roomWords = setOf(
            "bedroom", "kitchen", "bathroom", "office", "hallway", "basement", "cellar", "corridor",
        )

        /** Mehrwort-Raum-Aliase (Token-Paare) — s. [roomWords] zur eisernen Regel. */
        val roomPhrases = listOf("living room", "livingroom")

        val complexityMarkers = listOf(
            "routine", "if then", "when then", "create a plan", "make a plan", "strategy",
            "multiple rooms", "every room", "all rooms", "everywhere", "at the same time",
        )

        val agentMarkers = listOf(
            "remember that", "remember this", "keep in mind", "dont forget", "don't forget",
            "search the web", "search the internet", "google",
            "play me", "play the", "next episode", "what was on",
            "how much power", "power consumption", "energy usage", "electricity usage",
            "how warm", "how cold", "temperature",
            "shopping list", "add to the list", "add to my list",
            "whats on my", "what's on my", "calendar", "appointment",
            "organize", "organise", "plan my",
            "open the browser", "click on", "browser",
        )

        /**
         * **Übertragene Wendungen — hier kommt ein Schalt-/Licht-Wort VOR, aber es ist
         * KEINE Tat gemeint.** Haben Vorrang vor jeder Erkennung. Bewusst nur
         * eindeutige MEHRWORT-Phrasen (kein Einzel-Token), damit sie nie einen echten
         * Befehl blockieren — das Gegenstück zu den deutschen Idiom-Blockern
         * („mir ist warm ums herz").
         */
        val figurativePhrases = listOf(
            "turn on the charm", "turns on the charm", "turned on the charm",
            "turn me on", "turns me on", "turned me on", "turn you on",
            "turn a blind eye", "turned a blind eye",
            "bright idea", "bright side", "brighten your day", "brightens my day",
            "light of my life", "make light of", "shed light on",
            "light at the end of the tunnel", "in a new light",
        )

        /** Ganze Wörter (lowercase, Satzzeichen/Apostrophe weg) — s. Klassen-KDoc zum Match-Modus. */
        fun tokens(query: String): Set<String> =
            query.lowercase().replace('’', '\'').replace("'", "")
                .split(TOKEN_SPLIT).filter { it.isNotBlank() }.toSet()

        /** Trägt der (lowercase) Satz eine übertragene Wendung? Dann NIE ein Befehl. */
        fun isFigurative(q: String): Boolean = figurativePhrases.any { q.contains(it) }

        /**
         * Ein Smart-Home-Ziel? Entweder ein [smartHomeTargets]-Substantiv, oder ein
         * [setpointTargets]-Wort MIT konkreter Zahl und OHNE [setpointBlockers]-Gerät.
         */
        fun hasTarget(q: String, tokens: Set<String>): Boolean {
            if (smartHomeTargets.any { it in tokens }) return true
            if (setpointTargets.none { it in tokens }) return false
            if (setpointBlockers.any { it in tokens }) return false
            return DIGIT.containsMatchIn(q)
        }

        /** Nennt der Satz einen Raum — deutsche HA-Namen ODER einen englischen Alias? */
        fun mentionsRoom(q: String, tokens: Set<String>): Boolean =
            Keywords.haRooms.any { it in tokens } ||
                roomWords.any { it in tokens } ||
                roomPhrases.any { q.contains(it) }

        private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")
        private val DIGIT = Regex("\\d")
    }

    /**
     * Smart-Home-Kandidat: Schalt-Verb **und** Geräte-Ziel. Läuft in der Wortliste
     * der Turn-[language] — EN über die ganz-Wort-Erkennung aus [KeywordsEn], alle
     * anderen über die deutschen [Keywords] (s. Klassen-KDoc). Im EN-Modus zählen
     * die deutschen Listen ZUSÄTZLICH: Andis HA-Räume und viele Geräte-Wörter sind
     * deutsch, ein englisch geführter Turn darf einen deutsch gesprochenen Befehl
     * („schalte das Licht an") nicht verlieren.
     */
    fun isSmartHomeCandidate(query: String, language: Language = Language.DEFAULT): Boolean {
        val q = query.lowercase().trim()
        if (q.isBlank()) return false
        val de = Keywords.smartHomeVerbs.any { q.contains(it) } &&
            Keywords.smartHomeTargets.any { q.contains(it) }
        if (!usesEnglishKeywords(language)) return de
        if (KeywordsEn.isFigurative(q)) return false
        val tokens = KeywordsEn.tokens(q)
        return de || (KeywordsEn.smartHomeVerbs.any { it in tokens } && KeywordsEn.hasTarget(q, tokens))
    }

    /**
     * Komplexitätsscore: höhere Werte = aufwendigere Anfrage. Über
     * [complexityThreshold] wird das OpenClaw-Routing ausgelöst. Marker + Raum-Zählung
     * folgen der Turn-[language] (s. Klassen-KDoc) — ein englischer Satz wurde vorher
     * an den deutschen Markern gemessen und dadurch systematisch zu einfach eingestuft.
     */
    fun complexityScore(query: String, language: Language = Language.DEFAULT): Int {
        val q = query.lowercase().trim()
        val english = usesEnglishKeywords(language)
        var s = 0
        if (q.length > 120) s += 3
        val complexityMarkers = if (english) Keywords.complexityMarkers + KeywordsEn.complexityMarkers else Keywords.complexityMarkers
        val agentMarkers = if (english) Keywords.agentMarkers + KeywordsEn.agentMarkers else Keywords.agentMarkers
        if (complexityMarkers.any { q.contains(it) }) s += 3
        if (agentMarkers.any { q.contains(it) }) s += 4
        if (q.count { it == '?' } >= 2) s += 1
        if (roomMentions(q, english) > 2) s += 2
        return s
    }

    fun isOpenClawEligible(query: String, language: Language = Language.DEFAULT): Boolean =
        complexityScore(query, language) >= complexityThreshold

    /**
     * Wie viele Räume nennt [q]? Deutsche HA-Namen zählen IMMER (sie sind der reale
     * Raumname und tauchen auch mitten in englischen Sätzen auf); im EN-Modus zählen
     * die englischen Aliase zusätzlich — der Name selbst wird dabei nie übersetzt,
     * nur zusätzlich zugeordnet.
     */
    private fun roomMentions(q: String, english: Boolean): Int {
        val de = Keywords.haRooms.count { q.contains(it) }
        if (!english) return de
        val tokens = KeywordsEn.tokens(q)
        return de + KeywordsEn.roomWords.count { it in tokens } + KeywordsEn.roomPhrases.count { q.contains(it) }
    }

    companion object {
        /**
         * Läuft dieser Turn auf den englischen Erkennern ([KeywordsEn])? Bewusst ein
         * exhaustives `when` OHNE `else`: eine NEUE [Language] zwingt den Compiler zu
         * einer bewussten Entscheidung, statt sie still ins Deutsche fallen zu lassen.
         * ES/FR/IT haben noch keine eigenen Erkenner (s. Klassen-KDoc).
         */
        fun usesEnglishKeywords(language: Language): Boolean = when (language) {
            Language.EN -> true
            Language.DE, Language.ES, Language.FR, Language.IT -> false
        }
    }
}
