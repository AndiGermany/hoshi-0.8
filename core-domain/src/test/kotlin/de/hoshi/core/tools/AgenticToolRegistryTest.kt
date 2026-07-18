package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die Brücke gemma-Tool ↔ Kernel-[ToolCall]: name+args → permit-kompatibler
 * ToolCall (Räume auf die echten 7 HA-area_ids aufgelöst, Küche → kuche), unbekanntes
 * Tool/Raum ⇒ null, und dass [AgenticToolRegistry.schemas] valide gemma-Strukturen liefert.
 */
class AgenticToolRegistryTest {

    private fun parsed(name: String, vararg args: Pair<String, String>) =
        ParsedToolCall(name, mapOf(*args))

    @Test
    fun `light_set on mappt auf light turn_on mit area_id wohnzimmer`() {
        val call = AgenticToolRegistry.resolve(parsed("light_set", "area" to "wohnzimmer", "state" to "on"))!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId, "Area-Targeting ⇒ entityId null")
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `light_set off in der Kueche mappt auf turn_off mit area_id kuche`() {
        val call = AgenticToolRegistry.resolve(parsed("light_set", "area" to "küche", "state" to "off"))!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("kuche", call.data["area_id"], "küche → echte area_id kuche (NICHT kueche)")
    }

    @Test
    fun `light_set mit brightness_pct erzwingt turn_on und setzt Int-Helligkeit`() {
        val call = AgenticToolRegistry.resolve(
            parsed("light_set", "area" to "wohnzimmer", "state" to "off", "brightness_pct" to "30"),
        )!!
        // Helligkeit gesetzt ⇒ turn_on (auch wenn state=off mitkam).
        assertEquals("turn_on", call.service)
        assertEquals(30, call.data["brightness_pct"])
        assertTrue(call.data["brightness_pct"] is Int, "brightness_pct ist Int")
    }

    @Test
    fun `climate_set mappt auf set_temperature mit Int-temperature`() {
        val call = AgenticToolRegistry.resolve(parsed("climate_set", "area" to "bad", "temperature" to "22"))!!
        assertEquals("climate", call.domain)
        assertEquals("set_temperature", call.service)
        assertEquals("badezimmer", call.data["area_id"], "bad → badezimmer")
        assertEquals(22, call.data["temperature"])
        assertTrue(call.data["temperature"] is Int, "temperature ist Int")
    }

    @Test
    fun `scene_activate mappt auf scene turn_on mit gesluggter entityId`() {
        val call = AgenticToolRegistry.resolve(parsed("scene_activate", "name" to "Guten Morgen"))!!
        assertEquals("scene", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("scene.guten_morgen", call.entityId)
    }

    @Test
    fun `unbekanntes Tool ergibt null`() {
        assertNull(AgenticToolRegistry.resolve(parsed("lock_unlock", "entity" to "lock.haustuer")))
    }

    @Test
    fun `unbekannter Raum ergibt null`() {
        assertNull(AgenticToolRegistry.resolve(parsed("light_set", "area" to "dachboden", "state" to "on")))
    }

    // ── (H2) light_set OHNE area-Arg ⇒ Pflicht-Arg fehlt ⇒ null (nichts ausführbar). ──
    @Test
    fun `light_set ohne area ergibt null`() {
        assertNull(
            AgenticToolRegistry.resolve(parsed("light_set", "state" to "on")),
            "fehlendes Pflicht-Arg area ⇒ resolve null",
        )
    }

    // ── (H2) Alle Küche-Aliase (DE/EN, mit/ohne Umlaut) lösen auf die echte area_id `kuche` auf. ──
    @Test
    fun `Raum-Aliase kueche kueche-umlaut kitchen kuche mappen alle auf kuche`() {
        for (alias in listOf("kueche", "küche", "kitchen", "kuche", "KÜCHE", " Kitchen ")) {
            val call = AgenticToolRegistry.resolve(parsed("light_set", "area" to alias, "state" to "on"))!!
            assertEquals("kuche", call.data["area_id"], "$alias → kuche")
        }
    }

    // ── (H2) climate_set mit nicht-numerischer temperature ⇒ null (kein Müll an HA). ──
    @Test
    fun `climate_set mit nicht-numerischer temperature ergibt null`() {
        assertNull(
            AgenticToolRegistry.resolve(parsed("climate_set", "area" to "wohnzimmer", "temperature" to "abc")),
            "temperature 'abc' ist keine Zahl ⇒ null",
        )
    }

    // ── (H2) scene_activate mit Umlauten im Namen ⇒ korrekt slugified (ä→ae …). ──
    @Test
    fun `scene_activate mit Umlauten im Namen wird korrekt slugified`() {
        val call = AgenticToolRegistry.resolve(parsed("scene_activate", "name" to "Grün & Gemütlich"))!!
        assertEquals("scene", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("scene.gruen_gemuetlich", call.entityId, "Umlaute → ae/oe/ue, Rest → _")
    }

    // ── (N2) Extrem-Temperaturen werden defensiv in die HA-Range 5–35 °C geklemmt. ──
    @Test
    fun `climate_set klemmt Extremwerte in die HA-Range 5 bis 35`() {
        val hot = AgenticToolRegistry.resolve(parsed("climate_set", "area" to "wohnzimmer", "temperature" to "99"))!!
        assertEquals(35, hot.data["temperature"], "99 → 35 (oberes Limit)")
        val cold = AgenticToolRegistry.resolve(parsed("climate_set", "area" to "wohnzimmer", "temperature" to "-5"))!!
        assertEquals(5, cold.data["temperature"], "-5 → 5 (unteres Limit)")
    }

    @Test
    fun `schemas liefert valide gemma-Struktur fuer jedes Tool`() {
        val schemas = AgenticToolRegistry.schemas()
        assertEquals(3, schemas.size, "light_set, climate_set, scene_activate")

        val names = schemas.map { schema ->
            assertEquals("function", schema["type"])
            @Suppress("UNCHECKED_CAST")
            val fn = schema["function"] as Map<String, Any?>
            assertTrue((fn["description"] as String).isNotBlank(), "deutsche Beschreibung vorhanden")
            @Suppress("UNCHECKED_CAST")
            val params = fn["parameters"] as Map<String, Any?>
            assertEquals("object", params["type"])
            assertTrue(params["properties"] is Map<*, *>, "properties ist ein Objekt")
            assertTrue(params["required"] is List<*>, "required ist eine Liste")
            fn["name"] as String
        }
        assertEquals(setOf("light_set", "climate_set", "scene_activate"), names.toSet())
    }

    @Test
    fun `Parser plus Registry End-to-End am Live-Beispiel`() {
        // „licht im wohnzimmer an" → live emittiert vom Brain:
        val raw = "<|tool_call>call:light_set{area:<|\"|>wohnzimmer<|\"|>,state:<|\"|>on<|\"|>}<tool_call|>"
        val call = AgenticToolRegistry.resolve(ToolCallParser.parse(raw)!!)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }
}
