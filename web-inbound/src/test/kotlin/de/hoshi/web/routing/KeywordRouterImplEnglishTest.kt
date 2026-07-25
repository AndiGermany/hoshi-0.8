package de.hoshi.web.routing

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * **KeywordRouterImplEnglishTest** — das englische Gegenstück zum
 * [KeywordRouterImplTest] (2026-07-25).
 *
 * **Der Befund, den diese Tests festnageln:** die Multilingualitäts-Runde vom
 * 25.07. hat die ANTWORTEN in fünf Sprachen gebracht, die ERKENNER aber nicht.
 * Gemessen (vorher): **JEDER** englische Smart-Home-Satz landete in FACT_SHORT —
 * „turn on the light in the living room", „turn off the lights", „dim the light",
 * „set the temperature to 21 degrees", „turn on the kitchen light", ausnahmslos.
 * Folge: Grounding lief auf einem Befehl, die Route war nie SMART_HOME (also kein
 * agentischer Tool-Layer), und in der UI stand die falsche Kategorie.
 *
 * **Die teure Richtung bleibt geschlossen** (zweiter Block): ein falsch erkannter
 * Smart-Home-Befehl schaltet echte Geräte in einer echten Wohnung. „I feel warm"
 * ist keine Heizung, „that's a bright idea" kein Licht, „turn on the charm"
 * schaltet gar nichts. Diese Tests sind die englischen Zwillinge der deutschen
 * Idiom-Blocker-Tests („mir ist warm ums herz ist NICHT SMART_HOME").
 */
class KeywordRouterImplEnglishTest {

    private val router = KeywordRouterImpl()

    private fun assertSmartHome(text: String) =
        assertEquals(RouteCategory.SMART_HOME, router.decide(text).category, "Satz: $text")

    private fun assertNotSmartHome(text: String) =
        assertNotEquals(RouteCategory.SMART_HOME, router.decide(text).category, "Satz: $text")

    // ── Die gemessenen Fälle aus dem Auftrag ─────────────────────────────────────

    @Test
    fun `turn on the light in the living room ist SMART_HOME`() {
        val d = router.decide("turn on the light in the living room")
        assertEquals(RouteCategory.SMART_HOME, d.category)
        assertEquals(RouteProvider.LOCAL, d.provider)
        assertEquals("smart_home_en", d.reason)
    }

    @Test
    fun `die sechs Auftrags-Messfaelle routen alle nach SMART_HOME`() {
        listOf(
            "turn on the light in the living room",
            "turn off the lights",
            "dim the light",
            "set the temperature to 21 degrees",
            "is the light on?",
            "turn on the kitchen light",
        ).forEach(::assertSmartHome)
    }

    // ── Verben ───────────────────────────────────────────────────────────────────

    @Test
    fun `Schalt- und Stell-Verben greifen`() {
        listOf(
            "turn on the lamp", "turn off the lamp",
            "switch on the lights", "switch off the bedroom lamp",
            "dim the light", "brighten the lights",
            "set the light to 30 percent",
            "open the blinds", "close the blinds", "close the curtains",
            "activate the movie scene", "toggle the light",
        ).forEach(::assertSmartHome)
    }

    // ── Objekte ──────────────────────────────────────────────────────────────────

    @Test
    fun `Geraete-Substantive greifen - Licht, Heizung, Rollo, TV, Musik`() {
        listOf(
            "turn on the light", "turn off the lights", "turn on the lamp", "turn off the bulb",
            "turn the heating on", "turn on the heater", "turn on the radiator", "turn on the thermostat",
            "open the shutters", "close the curtains",
            "turn on the tv", "turn off the music",
        ).forEach(::assertSmartHome)
    }

    @Test
    fun `terse Befehle ohne Verb greifen - lights off, lamp on`() {
        listOf("lights off", "lights on", "lamp on", "light off").forEach(::assertSmartHome)
    }

    // ── Raum-Erkennung: englischer Alias UND deutscher HA-Name ───────────────────

    @Test
    fun `englischer Raum-Alias plus Zustand ist SMART_HOME (Gegenstueck zu Schlafzimmer aus)`() {
        listOf("living room off", "living room on", "kitchen off", "bedroom on", "office off")
            .forEach(::assertSmartHome)
    }

    /**
     * **Code-Switching.** Andis HA-Räume heißen deutsch — ein englischer Satz nennt
     * den Raum also entweder deutsch („in the Wohnzimmer") oder englisch („in the
     * living room"). Beides muss routen; der Raumname selbst wird NIE übersetzt,
     * nur zugeordnet (die Alias→area_id-Auflösung macht `ToolAreas`).
     */
    @Test
    fun `englischer Satz mit deutschem HA-Raumnamen routet ebenfalls`() {
        listOf(
            "turn on the light in the Wohnzimmer",
            "turn off the light in the Küche",
            "switch the Schlafzimmer light on",
        ).forEach(::assertSmartHome)
    }

    // ══ Die teure Richtung: NICHTS schalten, was nicht gemeint ist ═══════════════

    /** Der Auftrags-Gegentest: Wärme-Empfinden auf Englisch ist KEIN Heizungs-Befehl. */
    @Test
    fun `I feel warm ist KEIN Heizungsbefehl`() {
        listOf(
            "I feel warm", "it's warm in here", "I'm feeling warm", "it feels warm",
            "that was a warm welcome", "she gave me a warm smile",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `bright idea ist KEIN Licht-Befehl`() {
        listOf(
            "that's a bright idea", "what a bright idea", "look on the bright side",
            "the future is bright",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `turn on the charm schaltet nichts`() {
        listOf(
            "turn on the charm", "she really turns on the charm",
            "he turned a blind eye", "that turns me on",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `Licht- und Waerme-Woerter als THEMA sind Wissensfragen, keine Befehle`() {
        // Das englische Gegenstück zu „erzähl mir was über das licht der sonne".
        listOf(
            "tell me about the light of the sun",
            "tell me about the northern lights",
            "the light of my life",
            "how fast does light travel",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `temperature ohne konkreten Sollwert ist kein Befehl`() {
        listOf(
            "what temperature should I set the oven to?",
            "what is the ideal temperature for a wine cellar",
            "set the oven to 200 degrees",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `andere off-Bedeutungen schalten kein Licht`() {
        listOf(
            "the meeting in the office is off",
            "I'm taking the day off",
            "he was off to the basement to get some tools right away",
        ).forEach(::assertNotSmartHome)
    }

    @Test
    fun `Timer- und Listen-Saetze bleiben aus dem Smart-Home-Routing raus`() {
        listOf(
            "set an alarm for seven", "set a timer for 20 seconds",
            "set the volume to ten", "I need to set the table",
            "add milk to the shopping list",
        ).forEach(::assertNotSmartHome)
    }

    // ── Wissen vs. Smalltalk auf Englisch ────────────────────────────────────────

    @Test
    fun `englische Wissensfragen bleiben FACT_SHORT (Grounding feuert)`() {
        listOf(
            "who was Konrad Adenauer?",
            "what is love?",
            "how does photosynthesis work?",
            "what is Helgoland?",
            "tell me about the moon landing",
        ).forEach { p ->
            assertEquals(RouteCategory.FACT_SHORT, router.decide(p).category, "Satz: $p")
        }
    }

    /**
     * Englischer Smalltalk lief vorher als Wissensfrage ins Grounding (Rest-Tokens
     * „how/are/you") — dieselbe kalte Deflection wie der deutsche Live-Bug vom
     * 2026-07-01 („Kurz: alles ok bei dir?").
     */
    @Test
    fun `englischer Smalltalk ist SMALLTALK, nicht FACT_SHORT`() {
        listOf(
            "how are you?",
            "how are you doing today?",
            "hello, how are you?",
            "hi there",
            "what's up?",
            "are you awake?",
            "thanks a lot",
            "tell me a joke",
        ).forEach { p ->
            assertEquals(RouteCategory.SMALLTALK, router.decide(p).category, "Satz: $p")
        }
    }

    // ── Komfort-Phrasen: EN bleibt bei den eindeutigen Formen ────────────────────

    @Test
    fun `englische Komfort-Phrasen routen weiter nach SMART_HOME`() {
        listOf("I'm cold", "i am cold", "it's dark", "it's too bright", "it's stuffy")
            .forEach(::assertSmartHome)
    }

    @Test
    fun `uebertragene dark- und cold-Wendungen sind KEINE Komfort-Absicht`() {
        listOf(
            "it's dark humor", "it's dark matter", "it's dark times we live in",
            "he gave me the cold shoulder", "that leaves me cold",
        ).forEach(::assertNotSmartHome)
    }
}
