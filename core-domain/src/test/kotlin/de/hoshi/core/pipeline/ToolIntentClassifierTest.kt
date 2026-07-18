package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die konservative, deterministische Tool-Intent-Klassifikation:
 * eindeutige DE/EN-Befehle ⇒ permit-kompatibler [de.hoshi.core.tools.ToolCall],
 * Negation/Frage/Mehrdeutiges ⇒ `null` (dann Brain-Pfad).
 */
class ToolIntentClassifierTest {

    private val classifier = DeterministicToolIntentClassifier()

    @Test
    fun `DE Licht in der Kueche an mappt auf light turn_on area kuche`() {
        val call = classifier.classify("Licht in der Küche an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId, "Area-Targeting ⇒ entityId null")
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `DE mach das Licht aus mappt auf turn_off mit Default-Area wohnzimmer`() {
        val call = classifier.classify("mach das Licht aus", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertNull(call.entityId)
        // Kein Raum genannt ⇒ Default-Area wohnzimmer (so funktioniert „Licht aus" ohne Raum weiter).
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `DE dimm das Wohnzimmer auf 30 Prozent setzt brightness_pct 30 area wohnzimmer`() {
        val call = classifier.classify("dimm das Wohnzimmer auf 30 Prozent", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId)
        assertEquals("wohnzimmer", call.data["area_id"])
        assertEquals(30, call.data["brightness_pct"])
    }

    @Test
    fun `EN turn on the kitchen light mappt auf light turn_on area kuche`() {
        val call = classifier.classify("turn on the kitchen light", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId)
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `DE Heizung im Schlafzimmer auf 21 setzt climate area schlafzimmer`() {
        val call = classifier.classify("stell die Heizung im Schlafzimmer auf 21", Language.DE)!!
        assertEquals("climate", call.domain)
        assertEquals("set_temperature", call.service)
        assertNull(call.entityId)
        assertEquals("schlafzimmer", call.data["area_id"])
        assertEquals(21, call.data["temperature"])
    }

    // ── Kompositum-Erkennung (ein Token: Raum+Licht/Lampe verschmolzen) ───────

    @Test
    fun `DE Kompositum wohnzimmerlicht an mappt auf light turn_on area wohnzimmer`() {
        val call = classifier.classify("wohnzimmerlicht an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId)
        assertEquals("wohnzimmer", call.data["area_id"], "der im Kompositum steckende Raum speist area_id")
    }

    @Test
    fun `DE Kompositum kuechenlicht aus mappt auf light turn_off area kuche (Fugen-n)`() {
        // „küchenlicht" = „küche" + Fugen-n + „licht" ⇒ resolveRoomLoose schneidet das n.
        val call = classifier.classify("küchenlicht aus", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `DE Kompositum schlafzimmerlampe an mappt auf light turn_on area schlafzimmer`() {
        val call = classifier.classify("schlafzimmerlampe an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `Kompositum mit Prozent dimmt die richtige Area`() {
        val call = classifier.classify("küchenlicht auf 40 Prozent", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("kuche", call.data["area_id"])
        assertEquals(40, call.data["brightness_pct"])
    }

    @Test
    fun `Nicht-Kompositum getrennt bleibt unveraendert (Licht im Wohnzimmer)`() {
        // Regress-Schutz: der getrennte Klassiker ist byte-identisch zu vorher.
        val call = classifier.classify("Licht im Wohnzimmer an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `False-Friend tageslicht ist KEIN Raum-Kompositum (null)`() {
        // „tageslicht" endet auf „licht", aber „tages"/„tage" ist kein Raum ⇒ kein Befehl.
        assertNull(classifier.classify("tageslicht an", Language.DE))
    }

    // ── Temperatur LESEN (READ-ONLY) ─────────────────────────────────────────

    @Test
    fun `DE wie warm ist es im Wohnzimmer mappt auf read_temperature area wohnzimmer`() {
        val call = classifier.classify("Wie warm ist es im Wohnzimmer?", Language.DE)!!
        assertEquals("sensor", call.domain)
        assertEquals("read_temperature", call.service)
        assertNull(call.entityId)
        assertEquals(true, call.read, "Read-Wunsch ⇒ read=true (am Schreib-Gate vorbei)")
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `DE welche Temperatur zeigt die Heizung mappt auf read_temperature ohne Raum`() {
        // Live-Repro: keine Zahl ⇒ KEIN set_temperature; eine Lese-Frage ⇒ read_temperature.
        val call = classifier.classify("Welche Temperatur zeigt die Heizung?", Language.DE)!!
        assertEquals("sensor", call.domain)
        assertEquals("read_temperature", call.service)
        assertEquals(true, call.read)
        // Kein Raum genannt ⇒ kein area_id ⇒ Haus-Aggregat im Adapter.
        assertNull(call.data["area_id"], "ohne Raum kein area_id (Haus-Aggregat)")
    }

    @Test
    fun `EN whats the temperature in the kitchen mappt auf read_temperature area kuche`() {
        val call = classifier.classify("what's the temperature in the kitchen?", Language.EN)!!
        assertEquals("sensor", call.domain)
        assertEquals("read_temperature", call.service)
        assertEquals(true, call.read)
        assertEquals("kuche", call.data["area_id"])
    }

    @Test
    fun `Temperatur SETZEN behaelt Vorrang vor LESEN (Zahl genannt)`() {
        // „stell die Heizung auf 21" ist eine Soll-Wert-Ansage ⇒ set_temperature (write), NICHT read.
        val call = classifier.classify("stell die Heizung im Schlafzimmer auf 21", Language.DE)!!
        assertEquals("climate", call.domain)
        assertEquals("set_temperature", call.service)
        assertEquals(false, call.read, "Schreib-Call bleibt read=false")
        assertEquals(21, call.data["temperature"])
    }

    @Test
    fun `Wetter-Frage geht NICHT auf read_temperature`() {
        // „wie wird das wetter morgen" bleibt Wetter/Grounding — kein HA-State-Read.
        assertNull(classifier.classify("wie wird das wetter morgen", Language.DE))
    }

    @Test
    fun `Wetter trotz wie warm morgen geht NICHT auf read_temperature`() {
        // „wie warm wird es morgen" nennt eine Temperatur, ist aber eine Vorhersage (Marker „morgen").
        assertNull(classifier.classify("wie warm wird es morgen draußen", Language.DE))
    }

    @Test
    fun `NICHT-Temperatur-Frage geht NICHT auf read_temperature`() {
        assertNull(classifier.classify("wie spät ist es?", Language.DE))
    }

    @Test
    fun `Negation mach das Licht NICHT aus ergibt null`() {
        assertNull(classifier.classify("mach das Licht NICHT aus", Language.DE))
    }

    @Test
    fun `Frage wie hell ist das Licht ergibt null`() {
        assertNull(classifier.classify("wie hell ist das Licht?", Language.DE))
    }

    // ── Szenen-by-Name: mit gesetztem sceneCatalog matchen die realen scene_ids. ──

    /** Realer Katalog-Auszug (echte HA-scene_ids); nordlichter eindeutig wohnzimmer. */
    private val sceneCatalog = listOf(
        "wohnzimmer_nordlichter", "kuche_gedimmt", "flur_nachtlicht",
        "schlafzimmer_hell", "wohnzimmer_nachtlicht", "flur_entspannen",
    )
    private val sceneClassifier = DeterministicToolIntentClassifier(sceneCatalog = sceneCatalog)

    @Test
    fun `mit Katalog mappt mach die Nordlichter an auf scene turn_on wohnzimmer_nordlichter`() {
        val call = sceneClassifier.classify("mach die Nordlichter an", Language.DE)!!
        assertEquals("scene", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("scene.wohnzimmer_nordlichter", call.entityId)
    }

    @Test
    fun `mit Katalog mappt Szene Kueche gedimmt auf scene kuche_gedimmt`() {
        val call = sceneClassifier.classify("Szene Küche gedimmt", Language.DE)!!
        assertEquals("scene", call.domain)
        assertEquals("scene.kuche_gedimmt", call.entityId)
    }

    @Test
    fun `mit Katalog hat Licht Vorrang vor Szene`() {
        // „mach das Licht im Wohnzimmer an" bleibt LICHT, auch mit Szenen-Katalog.
        val call = sceneClassifier.classify("mach das Licht im Wohnzimmer an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `mit Katalog hat Klima Vorrang vor Szene`() {
        val call = sceneClassifier.classify("stell die Heizung im Schlafzimmer auf 21", Language.DE)!!
        assertEquals("climate", call.domain)
        assertEquals(21, call.data["temperature"])
    }

    @Test
    fun `mit Katalog ergibt mehrdeutiges Nachtlicht ohne Raum null`() {
        // zwei nachtlicht-Szenen (flur + wohnzimmer), kein Raum ⇒ kein Tool-Turn.
        assertNull(sceneClassifier.classify("nachtlicht", Language.DE))
    }

    @Test
    fun `leerer Katalog laesst Szenen-by-Name inaktiv (altes Verhalten)`() {
        // Ohne Katalog ist „die nordlichter" kein Befehl (kein "szene"-Wort) ⇒ null.
        assertNull(classifier.classify("die nordlichter", Language.DE))
    }

    @Test
    fun `naiver scene-slug bleibt als Fallback erhalten`() {
        // Explizites „szene X" greift weiter über den Slug-Pfad (auch ohne Katalog).
        val call = classifier.classify("szene kino", Language.DE)!!
        assertEquals("scene", call.domain)
        assertEquals("scene.kino", call.entityId)
    }
}
