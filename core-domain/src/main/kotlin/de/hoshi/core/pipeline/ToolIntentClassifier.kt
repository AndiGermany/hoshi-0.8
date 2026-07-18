package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.skills.SkillId
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.AreaClarifyIntent
import de.hoshi.core.tools.CalcIntent
import de.hoshi.core.tools.ListIntent
import de.hoshi.core.tools.SceneMatcher
import de.hoshi.core.tools.TimerIntent
import de.hoshi.core.tools.ToolAreas
import de.hoshi.core.tools.ToolCall

/**
 * **ToolIntentClassifier** — die deterministische, LLM-freie Token-Set-Klassifikation
 * eines eindeutigen Smart-Home-Befehls in einen [ToolCall] (Essenz aus Hoshi-0.5
 * `StructuredIntentService.classifyDeterministic`, framework- und LLM-frei portiert).
 *
 * KONSERVATIV: erkennt nur eindeutige Befehle (Licht an/aus/dimmen/Farbe, Szene
 * aktivieren, Klima setzen) in DE **und** EN. Negation, Fragen oder Mehrdeutiges
 * ⇒ `null` (dann übernimmt der normale Brain-Pfad). Die erzeugten [ToolCall]s
 * sind absichtlich permit-kompatibel zu den `CapabilityKernel.DEFAULT_PERMITS`
 * (richtige domain/service, erlaubte data-Keys, Ranges, `light.*`/`climate.*`/
 * `scene.*`-Scopes) — der Happy-Path Grantet.
 *
 * Heißt bewusst NICHT `IntentClassifier`: dieser Name ist im selben Paket bereits
 * vom Routing-Keyword-Classifier belegt — Kollision vermeiden, nichts brechen.
 *
 * [DISABLED] ist der verhaltens-neutrale Default (klassifiziert NIE) — so bleibt
 * der Orchestrator-Default brain-only.
 */
fun interface ToolIntentClassifier {
    fun classify(text: String, language: Language): ToolCall?

    companion object {
        /** Default: klassifiziert nie ⇒ kein Tool-Turn (Verhalten unverändert). */
        val DISABLED = ToolIntentClassifier { _, _ -> null }
    }
}

/**
 * Die konkrete deterministische Impl. Tokenisiert den (klein geschriebenen) Text,
 * verwirft bei Negation, und mappt eindeutige Befehle auf permit-kompatible
 * [ToolCall]s. Reines Kotlin, keine Side-Effects, kein Framework.
 *
 * [sceneCatalog] (default leer ⇒ heutiges naives Verhalten) sind die ECHTEN
 * HA-`scene_id`s (ohne `scene.`-Präfix). Ist er gesetzt, matcht der [SceneMatcher]
 * freien Text gegen die realen Szenen („mach die Nordlichter an" → den passenden
 * `scene_id`) — als Pfad NACH Licht/Klima (die haben Vorrang) und VOR dem naiven
 * `scene.<token>`-Fallback. Leerer Katalog ⇒ dieser Pfad ist inaktiv.
 */
class DeterministicToolIntentClassifier(
    private val sceneCatalog: List<String> = emptyList(),
    /**
     * Die EINE pro-Turn lesbare Toggle-Wahrheit: pro [classify] wird je betroffenem
     * Zweig gefragt, ob sein Skill an ist (Smart-Home/Szenen/Timer/Calc). Ersetzt die
     * früheren vier Ctor-Booleans durch eine Naht, an die der Laufzeit-Store (S2)
     * andocken kann. Ein aus-Skill verhält sich byte-identisch zu seinem Flag-OFF von
     * heute; [SkillStatePort.NONE] (alles aus) ist der verhaltens-neutrale all-OFF.
     */
    private val skills: SkillStatePort,
    /**
     * **Embedded-Arithmetik-Fang — flag-gated, default `false` ⇒ byte-neutral.** Bei
     * `true` reicht der Calc-Zweig `allowEmbedded=true` an [CalcIntent.classify] —
     * dann fängt der konservative Embedded-Pfad auch satz-EINGEBETTETE einfache
     * Rechnungen („wie viel ist 17 mal 23" mitten im Satz) in die deterministische
     * Fastpath statt ins Brain. Default `false` reproduziert exakt das bisherige
     * Verhalten (nur reine Ausdrücke). Greift NUR wenn der Calc-Skill an ist.
     */
    private val calcEmbeddedEnabled: Boolean = false,
    /**
     * **Listen-Fastpath — flag-gated, default `false` ⇒ byte-neutral.** Bei `true`
     * reicht der List-Zweig den Text an `ListIntent.classify` — ein eigenständiger,
     * von `HOSHI_TOOLS_ENABLED` UNABHÄNGIGER Pfad (eine Einkaufsliste ist kein
     * HA-Aktuator), exakt wie Timer/Calc vor ihm. Bewusst NICHT über [skills]/
     * [SkillStatePort] geführt (kein neuer `SkillId` für diese Scheibe — Andi-
     * Entscheidung 2026-07-08 hält den Rechte-/Skill-Rahmen unverändert, „sonst
     * keine Sonderrechte-Logik erfinden").
     */
    private val listEnabled: Boolean = false,
    /**
     * **Raum-Wissen — Konstruktor-Param mit statischem Default ⇒ byte-neutral.**
     * Der Classifier kennt Räume NICHT mehr direkt über [ToolAreas.ROOMS], sondern
     * über diese Naht (Andi-Auftrag 2026-07-15: „Raum-Liste dynamisch aus HA
     * synchron halten" statt hart codiert). Default [AreaCatalogPort.STATIC] ist
     * WORT-für-Wort dieselbe [ToolAreas.ROOMS]-Aliastabelle wie bisher — ohne
     * Wiring ist [roomIndex] identisch zur alten Direkt-Map, jedes bestehende
     * Match-Verhalten bleibt byte-neutral. Erst ein echter, HA-gespeister Port
     * (später flag-gated verdrahtet) lässt neue/umbenannte HA-Areas OHNE Redeploy
     * auftauchen — der Classifier-Code ändert sich dafür nicht mehr.
     */
    private val areaCatalog: AreaCatalogPort = AreaCatalogPort.STATIC,
) : ToolIntentClassifier {

    /**
     * **Bestands-API-Kompat (sekundär).** Faltet die historischen vier Booleans in
     * einen konstanten [SkillStatePort.ofStatic] — so bleibt jede bisherige
     * Konstruktion (Tests + ältere Call-Sites) byte-identisch, ohne sie anzufassen.
     * Die Default-Werte spiegeln das alte Verhalten exakt: Geräte-Zweige an
     * (toolsEnabled=true), Timer/Calc/List aus. `scenes` folgt `toolsEnabled` (genau
     * wie früher, als es keinen eigenen Szenen-Schalter gab — der Szenen-Zweig lief,
     * wann immer die Geräte-Zweige liefen).
     */
    constructor(
        sceneCatalog: List<String> = emptyList(),
        toolsEnabled: Boolean = true,
        timerEnabled: Boolean = false,
        calculatorEnabled: Boolean = false,
        listEnabled: Boolean = false,
    ) : this(
        sceneCatalog,
        SkillStatePort.ofStatic(
            smartHome = toolsEnabled,
            scenes = toolsEnabled,
            timer = timerEnabled,
            calculator = calculatorEnabled,
        ),
        calcEmbeddedEnabled = false,
        listEnabled = listEnabled,
    )

    override fun classify(text: String, language: Language): ToolCall? {
        if (text.isBlank()) return null
        val raw = text.lowercase()
        // Apostrophe normalisieren, damit "don't" → "dont" als ein Token greift.
        val normalized = raw.replace('’', '\'').replace("'", "")
        val tokens = normalized.split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        // (0) Negation ⇒ KEIN Befehl. Token-basiert (kein Substring-Fehlfeuer wie "klein").
        if (tokens.any { it in NEGATION || it.startsWith("kein") }) return null

        // (0b) Timer-Fastpath (NUR bei HOSHI_TIMER_ENABLED). VOR den Geräte-Zweigen,
        //      damit ein expliziter Timer-/Wecker-/Erinnerungs-Befehl gewinnt. OFF
        //      (Default) ⇒ Zeile übersprungen ⇒ Timer wird gar nicht erkannt (byte-neutral).
        if (skills.isEnabled(SkillId.TIMER)) {
            TimerIntent.classify(text)?.let { return it }
        }
        // (0c) Calculator-Fastpath (NUR bei HOSHI_CALCULATOR_ENABLED). Wie der Timer
        //      ein eigenständiger brain-freier Pfad, unabhängig von HOSHI_TOOLS_ENABLED.
        //      OFF (Default) ⇒ Zeile übersprungen ⇒ keine Rechnung wird erkannt (byte-neutral).
        if (skills.isEnabled(SkillId.CALCULATOR)) {
            CalcIntent.classify(text, allowEmbedded = calcEmbeddedEnabled)?.let { return it }
        }
        // (0d) Listen-Fastpath (NUR bei listEnabled/HOSHI_LIST_ENABLED). Wie Timer/Calc
        //      ein eigenständiger brain-freier Pfad, unabhängig von HOSHI_TOOLS_ENABLED
        //      (eine Einkaufsliste ist kein HA-Aktuator). OFF (Default) ⇒ Zeile
        //      übersprungen ⇒ kein Listen-Intent wird erkannt (byte-neutral).
        if (listEnabled) {
            ListIntent.classify(text)?.let { return it }
        }
        // Die Geräte-Zweige (Licht/Klima/Szene/Temperatur-Read) bleiben an
        // HOSHI_TOOLS_ENABLED (Skill SMART_HOME) gebunden — unabhängig vom Timer-/Calc-/List-Flag.
        if (!skills.isEnabled(SkillId.SMART_HOME)) return null

        // Raum-Alias→area_id-Tabelle für DIESEN Turn — frisch aus [areaCatalog]
        // gebaut (s. [roomIndex]-KDoc: bewusst NICHT über den Konstruktor hinweg
        // gecacht, damit ein künftiger TTL-refreshender HA-Katalog live bleibt).
        val rooms = roomIndex()

        // (1) Klima SETZEN: Klima-Keyword + Zahl ⇒ set_temperature (area-getargetet).
        //     Läuft VOR dem Lese-Pfad (1b) — eine Soll-Wert-Ansage ist die stärkere,
        //     explizite (schreibende) Absicht und gewinnt über die Frage.
        if (tokens.any { it in CLIMATE_WORDS }) {
            val temp = firstInt(raw)
            if (temp != null) {
                return ToolCall(
                    domain = "climate",
                    service = "set_temperature",
                    entityId = null,
                    data = mapOf("area_id" to areaOf(tokens, rooms), "temperature" to temp),
                )
            }
        }

        // (1b) Temperatur LESEN (READ-ONLY): eine Frage nach der Ist-Temperatur im Haus
        //      ⇒ HA-State lesen statt Brain-Deflection. Konservativ: NUR bei eindeutigen
        //      Temperatur-Fragephrasen, und NIE bei einer Wetter-Frage (die bleibt beim
        //      Grounding/Wetter-Pfad). Reiner Lese-Wunsch ⇒ `read=true` (am Schreib-Gate
        //      vorbei). Raum genannt ⇒ getargetet; sonst kein area_id ⇒ Haus-Aggregat.
        if (isTemperatureRead(normalized, tokens)) {
            val room = roomOrNull(tokens, rooms)
            return ToolCall(
                domain = "sensor",
                service = "read_temperature",
                entityId = null,
                data = if (room != null) mapOf("area_id" to room) else emptyMap(),
                read = true,
            )
        }

        // (2) Licht-Befehl: braucht einen Licht-Hinweis (Licht-Wort ODER Dimm-Verb).
        //     Area-getargetet (entityId=null, data[area_id]) — der Kernel grantet
        //     area_id gegen Permit.areaScope=["*"] (light.turn_on/off). VORRANG vor
        //     Szene: „mach das Licht im Wohnzimmer an" bleibt LICHT, nicht Szene.
        //
        // (2c) Kompositum-Erkennung: ein EINZELNES Token wie „wohnzimmerlicht",
        //      „küchenlicht", „schlafzimmerlampe" verschmilzt Raum + Licht/Lampe.
        //      0.5/0.7 fingen nur das getrennte „Licht im Wohnzimmer" — der Satellit
        //      sagt aber oft das Kompositum. Konservativ: nur klare Raum+Licht/Lampe-
        //      Komposita lösen auf (sonst null), und der eingebettete Raum speist area_id.
        val compoundArea: String? = tokens.firstNotNullOfOrNull { compoundLightArea(it, rooms) }

        val isDim = tokens.any { it.startsWith("dimm") || it == "dim" }
        val isLight = tokens.any { it in LIGHT_WORDS } || isDim || compoundArea != null
        if (isLight) {
            // Explizit genannter Raum gewinnt; sonst der im Kompositum steckende Raum;
            // sonst Default (wie bisher). Byte-neutral, wenn kein Kompositum (compoundArea=null
            // ⇒ roomOrNull(tokens) ?: "wohnzimmer" == areaOf(tokens)).
            val area = roomOrNull(tokens, rooms) ?: compoundArea ?: "wohnzimmer"
            val pct = percent(raw)
            if (pct != null) {
                return ToolCall(
                    domain = "light", service = "turn_on", entityId = null,
                    data = mapOf("area_id" to area, "brightness_pct" to pct.coerceIn(0, 100)),
                )
            }
            colorName(tokens)?.let { color ->
                return ToolCall(
                    domain = "light", service = "turn_on", entityId = null,
                    data = mapOf("area_id" to area, "color_name" to color),
                )
            }
            if (tokens.any { it in OFF_WORDS }) {
                return ToolCall(
                    domain = "light", service = "turn_off", entityId = null,
                    data = mapOf("area_id" to area),
                )
            }
            // Das nackte „ein" (getrenntes Verb „schalte … ein") zählt NUR mit
            // Schalt-Verb als An-Partikel — ohne Verb ist „ein" meist Artikel
            // („brennt da ein Licht?") und darf nie ein Befehl werden.
            if (tokens.any { it in ON_WORDS } || (tokens.any { it in SWITCH_VERBS } && tokens.contains("ein"))) {
                return ToolCall(
                    domain = "light", service = "turn_on", entityId = null,
                    data = mapOf("area_id" to area),
                )
            }
            // Licht erwähnt, aber keine klare Aktion (z.B. eine Frage) ⇒ kein Befehl.
            return null
        }

        // (2b) Raum-als-Ziel OHNE Geräte-Wort ("schalte das Schlafzimmer ein"):
        //      Schalt-Verb + bekannter Raum + An/Aus-Partikel ⇒ Licht im Raum
        //      (Konvention „Raum einschalten = Licht im Raum"). Gesprochene Befehle
        //      nennen oft NUR den Raum, ohne "Licht" — eine Geräte-Wort-Pflicht
        //      ließe den Turn in Brain-Prosa OHNE Tat durchfallen. `isLight` ist
        //      an dieser Stelle IMMER `false` (der Licht-Zweig oben returned in jedem
        //      Fall) — kein doppeltes Prüfen von Kompositum/Geräte-Wort nötig.
        //      Climate-Wörter blocken bewusst (z.B. „schalte die Heizung ein"): das
        //      ist keine Raum-Mehrdeutigkeit, sondern ein (heute nicht unterstütztes)
        //      Heizungs-Kommando — dafür soll NICHT nach dem Raum gefragt werden.
        val switchRoom = roomOrNull(tokens, rooms)
        val hasSwitchVerb = tokens.any { it in SWITCH_VERBS }
        val hasOnParticle = tokens.any { it in ON_WORDS } || tokens.contains("ein")
        val hasOffParticle = tokens.any { it in OFF_WORDS }
        val climateFree = tokens.none { it in CLIMATE_WORDS }
        if (switchRoom != null && hasSwitchVerb && climateFree) {
            if (hasOffParticle) {
                return ToolCall(
                    domain = "light", service = "turn_off", entityId = null,
                    data = mapOf("area_id" to switchRoom),
                )
            }
            if (hasOnParticle) {
                return ToolCall(
                    domain = "light", service = "turn_on", entityId = null,
                    data = mapOf("area_id" to switchRoom),
                )
            }
        }

        // (3) Szene-by-Name gegen die ECHTEN scene_ids — nur wenn ein Katalog da ist UND
        //     der Szenen-Skill an ist. Konservativ (false-positive-avers): nur bei
        //     distinktivem Treffer. Byte-neutral: in der Praxis ist der Katalog nur dann
        //     nicht-leer, wenn der Szenen-Skill an ist (Wiring), das SCENES-Gate ist also
        //     deckungsgleich mit der bisherigen Katalog-Bedingung.
        if (sceneCatalog.isNotEmpty() && skills.isEnabled(SkillId.SCENES)) {
            SceneMatcher.match(text, sceneCatalog)?.let { sceneId ->
                return ToolCall(domain = "scene", service = "turn_on", entityId = "scene.$sceneId")
            }
        }

        // (4) Naiver Fallback: "szene"/"scene" + Name dahinter ⇒ scene.<slug> (wie bisher,
        //     greift wenn der Matcher null lieferte ODER kein Katalog da ist).
        sceneSlug(tokens)?.let { slug ->
            return ToolCall(domain = "scene", service = "turn_on", entityId = "scene.$slug")
        }

        // (5) Ehrliche Rückfrage statt Brain-Prosa (Live-Befund 2026-07-15): ein
        //     SICHER erkanntes Schalt-Verb + An/Aus-Partikel, aber KEIN auflösbares
        //     Ziel — kein bekannter Raum ([switchRoom]==null, sonst hätte (2b) schon
        //     zurückgegeben), kein Geräte-Wort/Kompositum (sonst wäre `isLight` oben
        //     `true` gewesen und die Funktion hätte längst returned) und keine Szene
        //     traf (Schritte 3/4 liefen bereits erfolglos durch). NICHT zum Brain
        //     (der liefert dann Prosa OHNE Tat — genau der Live-Befund: „Schalte das
        //     Schlafzimmer ein" ohne Geräte-Wort ⇒ brainTtftMs=960, Licht blieb aus)
        //     und NICHT geraten (ein falscher Raum wäre schlimmer als eine kurze
        //     Rückfrage) — stattdessen die ehrliche [AreaClarifyIntent]-Rückfrage
        //     (Räume aus [areaCatalog], max [AreaClarifyIntent.MAX_ROOMS_NAMED], DE/EN).
        //     Indirekte Komfort-Phrasen („mir ist kalt") haben KEIN Schalt-Verb ⇒
        //     dieser Zweig bleibt für sie tot (kein Verhaltenswechsel, s. Tests).
        if (hasSwitchVerb && climateFree && (hasOnParticle || hasOffParticle)) {
            return ToolCall(
                domain = AreaClarifyIntent.DOMAIN,
                service = AreaClarifyIntent.ASK,
                entityId = null,
                data = mapOf(AreaClarifyIntent.PHRASE to AreaClarifyIntent.phrase(areaCatalog.areas(), language)),
            )
        }

        return null
    }

    /**
     * **Baut die Alias→area_id-Tabelle FRISCH aus [areaCatalog]** (bei jedem
     * [classify]-Aufruf neu — NICHT einmalig gecacht: der Port selbst cached/
     * TTL-refresht bei Bedarf, s. [AreaCatalogPort]-KDoc; ein Classifier-seitiger
     * Cache würde einen künftigen dynamischen HA-Sync nie sehen). Für den Default
     * [AreaCatalogPort.STATIC] ist das Ergebnis byte-identisch zur alten
     * `ToolAreas.ROOMS`-Map: jede [de.hoshi.core.port.AreaInfo] trägt ihre
     * historischen Aliase (die bei STATIC bereits area_id + Label enthalten) —
     * `putIfAbsent` für area_id/Label ist dort ein No-op, ändert also nichts.
     */
    private fun roomIndex(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (area in areaCatalog.areas()) {
            for (alias in area.aliases) map.putIfAbsent(alias, area.areaId)
            map.putIfAbsent(area.areaId, area.areaId)
            map.putIfAbsent(area.label.lowercase(), area.areaId)
        }
        return map
    }

    /**
     * Erstes vorkommendes Raum-Wort ⇒ echte HA-`area_id`; Default `wohnzimmer`
     * (wie bisher — so funktioniert „Licht an" ohne genannten Raum weiter). KEIN
     * Raten von entity_ids mehr: der Kernel grantet area_id gegen areaScope=["*"].
     * [rooms] ist die pro-Turn aus [areaCatalog] gebaute Alias-Tabelle ([roomIndex]).
     */
    private fun areaOf(tokens: List<String>, rooms: Map<String, String>): String {
        for (t in tokens) rooms[t]?.let { return it }
        return "wohnzimmer"
    }

    /**
     * Wie [areaOf], aber OHNE Default: erstes genanntes Raum-Wort ⇒ echte `area_id`,
     * sonst `null`. Für den Lese-Pfad: kein genannter Raum ⇒ kein Target ⇒ das
     * Haus-Aggregat (statt fälschlich „Wohnzimmer" zu raten).
     */
    private fun roomOrNull(tokens: List<String>, rooms: Map<String, String>): String? {
        for (t in tokens) rooms[t]?.let { return it }
        return null
    }

    /**
     * **Kompositum-Auflösung** (konservativ): ein einzelnes Token, das mit einem
     * Licht-/Lampe-Suffix ENDET und dessen Präfix einen bekannten Raum nennt, ⇒
     * dessen echte `area_id`. Beispiele:
     *  - „wohnzimmerlicht" = „wohnzimmer" + „licht" ⇒ `wohnzimmer`
     *  - „küchenlicht"     = „küche"(+Fugen-n) + „licht" ⇒ `kuche`
     *  - „schlafzimmerlampe" = „schlafzimmer" + „lampe" ⇒ `schlafzimmer`
     *
     * Gibt `null` für jedes Token, das KEIN klares Raum+Licht/Lampe-Kompositum ist
     * (z.B. „tageslicht": Präfix „tages" ist kein Raum) ⇒ keine False-Positives.
     * Das Token selbst darf kein reines Licht-Wort sein (Länge > Suffix-Länge).
     */
    private fun compoundLightArea(token: String, rooms: Map<String, String>): String? {
        for (suffix in LIGHT_SUFFIXES) {
            if (token.length > suffix.length && token.endsWith(suffix)) {
                resolveRoomLoose(token.removeSuffix(suffix), rooms)?.let { return it }
            }
        }
        return null
    }

    /**
     * Raum-Präfix → echte `area_id`, tolerant gegen ein deutsches Fugen-Morphem
     * (Fugen-n/-s: „küchen" → „küche"). Direkter Treffer hat Vorrang; sonst ein
     * abgeschnittenes trailing `n`/`s` versuchen. Unbekannt ⇒ `null`.
     */
    private fun resolveRoomLoose(prefix: String, rooms: Map<String, String>): String? {
        rooms[prefix]?.let { return it }
        if (prefix.length > 1 && (prefix.endsWith("n") || prefix.endsWith("s"))) {
            rooms[prefix.dropLast(1)]?.let { return it }
        }
        return null
    }

    /**
     * Erkennt eine **Ist-Temperatur-Frage** (READ) — konservativ + false-positive-avers:
     *
     *  - **Wetter-Guard:** enthält der Satz einen Wetter-Marker (`wetter`, `morgen`,
     *    `draußen`, `outside`, `forecast`, `vorhersage`…), ist es eine Wetter-/
     *    Vorhersage-Frage → KEIN HA-State-Read (die bleibt beim Grounding/Wetter-Pfad).
     *  - sonst triggert nur eine eindeutige Temperatur-Fragephrase (DE „wie warm/kalt",
     *    „welche temperatur", „wie viel grad"; EN „how warm/cold", „what's the temperature")
     *    ODER `temperatur`/`temperature`/`heizung` zusammen mit einem Frage-/Lese-Cue
     *    (`wie`/`welche`/`zeig`/`sag`/`what`/`show`/`?`).
     *
     * [phrase] ist der bereits klein-geschriebene, apostroph-normalisierte Text.
     */
    private fun isTemperatureRead(phrase: String, tokens: List<String>): Boolean {
        if (WEATHER_MARKERS.any { phrase.contains(it) }) return false
        // Eindeutige, eigenständige Fragephrasen.
        if (TEMP_READ_PHRASES.any { phrase.contains(it) }) return true
        // Temperatur-/Heizungs-Nomen + ein Frage-/Lese-Cue (sonst zu greedy).
        val hasCue = QUESTION_CUES.any { phrase.contains(it) }
        val hasTempNoun = tokens.any { it in TEMP_READ_NOUNS }
        return hasTempNoun && hasCue
    }

    /** Token direkt nach "szene"/"scene" als slug; null falls kein Name folgt. */
    private fun sceneSlug(tokens: List<String>): String? {
        val i = tokens.indexOfFirst { it == "szene" || it == "scene" }
        if (i < 0 || i + 1 >= tokens.size) return null
        return ToolAreas.slug(tokens[i + 1]).takeIf { it.isNotBlank() }
    }

    private fun colorName(tokens: List<String>): String? {
        for (t in tokens) COLORS[t]?.let { return it }
        return null
    }

    private fun percent(raw: String): Int? =
        PERCENT_RX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun firstInt(raw: String): Int? =
        INT_RX.find(raw)?.value?.toIntOrNull()

    private companion object {
        val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")
        val PERCENT_RX = Regex("(\\d+)\\s*(?:%|prozent|percent)")
        val INT_RX = Regex("\\d+")

        val NEGATION = setOf("nicht", "nie", "niemals", "not", "no", "dont")

        val LIGHT_WORDS = setOf("licht", "lichter", "lampe", "lampen", "light", "lights", "lamp")

        /**
         * Licht-/Lampe-Suffixe für die Kompositum-Erkennung („wohnzimmerlicht").
         * **Längere zuerst** (greedy), damit „…lichter" vor „…licht" und „…lampen"
         * vor „…lampe" matcht (sonst bliebe ein „er"/„n" im Raum-Präfix hängen).
         */
        val LIGHT_SUFFIXES = listOf("lichter", "lampen", "lights", "licht", "lampe", "light", "lamp")
        val ON_WORDS = setOf("an", "anschalten", "einschalten", "anmachen", "on")
        val OFF_WORDS = setOf("aus", "ausschalten", "ausmachen", "abschalten", "off")

        /**
         * Schalt-Verben für den Raum-als-Ziel-Pfad ((2b)/(5), Live-Befund 2026-07-15):
         * DE „schalte/schalt/mach/dreh…" + EN „switch/turn". BEWUSST ein eigenes,
         * ENGERES Set statt der breiten [LIGHT_WORDS] — ein Schalt-Verb allein sagt
         * noch nichts über ein Gerät, erst zusammen mit einem bekannten Raum UND
         * einem An/Aus-Partikel ((2b)) bzw. NUR mit dem Partikel ((5), Rückfrage)
         * wird daraus ein Treffer. „mach"/"dreh" sind absichtlich mit drin (0.5-taugliche
         * Umgangssprache „mach das Schlafzimmer an" / „dreh das Licht auf") — sie sind
         * zwar generische Wörter, aber NUR in Kombination mit Raum+Partikel (2b) bzw.
         * Partikel (5) scharf, s. dortige Tests gegen False-Positives (z.B. Listen-Sätze).
         */
        val SWITCH_VERBS = setOf(
            "schalte", "schalt", "schalten", "mach", "mache", "machst",
            "dreh", "drehe", "drehst", "switch", "turn",
        )
        val CLIMATE_WORDS = setOf(
            "heizung", "temperatur", "thermostat", "klima",
            "heating", "temperature", "climate",
        )

        /**
         * Eindeutige Ist-Temperatur-Fragephrasen (DE+EN) — schon allein ein Read-Trigger.
         * Bewusst Substring-Phrasen (gegen den normalisierten Text), nicht Einzel-Tokens.
         */
        val TEMP_READ_PHRASES = setOf(
            "wie warm", "wie kalt", "welche temperatur", "wie viel grad", "wieviel grad",
            "how warm", "how cold", "how many degrees",
            "whats the temperature", "what is the temperature", "what s the temperature",
        )

        /** Nomen, die NUR zusammen mit einem [QUESTION_CUES]-Cue lesen (sonst zu greedy). */
        val TEMP_READ_NOUNS = setOf("temperatur", "temperature", "heizung")

        /** Frage-/Lese-Cues: machen aus einem Temperatur-Nomen eine LESE-Frage. */
        val QUESTION_CUES = setOf("wie", "welche", "zeig", "sag", "what", "show", "?")

        /**
         * Wetter-Marker — bei Treffer KEIN HA-State-Read (Wetter/Vorhersage bleibt beim
         * Grounding/Wetter-Pfad, auch wenn „wie warm wird es morgen" eine Temperatur nennt).
         */
        val WEATHER_MARKERS = setOf(
            "wetter", "morgen", "draußen", "draussen", "vorhersage",
            "weather", "forecast", "outside", "tomorrow",
        )

        // Raum-Aliase + echte HA-`area_id`s leben jetzt zentral in [ToolAreas]
        // (eine Quelle der Wahrheit, geteilt mit der AgenticToolRegistry).

        /** Farb-Wort → HA-color_name (englisch). */
        val COLORS = mapOf(
            "rot" to "red", "red" to "red",
            "blau" to "blue", "blue" to "blue",
            "grün" to "green", "gruen" to "green", "green" to "green",
            "gelb" to "yellow", "yellow" to "yellow",
            "weiß" to "white", "weiss" to "white", "white" to "white",
            "orange" to "orange",
            "lila" to "purple", "violett" to "purple", "purple" to "purple",
            "rosa" to "pink", "pink" to "pink",
        )
    }
}
