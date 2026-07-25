package de.hoshi.core.tools

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Sprach-Naht der Tool-Schemas** ([AgenticToolRegistry.schemas] + [ToolSchemaTexts],
 * Scheibe 2026-07-25). Die Beschreibungen gehen in denselben `/v1/chat`-Call wie der
 * System-Prompt — deutsche Tool-Texte sind in einem englischen Turn ein deutscher
 * Anker. Drei Zusagen:
 *
 *  1. Beschreibungen je DE/EN/ES/FR/IT in der Turn-Sprache.
 *  2. **DE byte-identisch** (auch ohne Argument, der Default ist DE).
 *  3. **Der HA-/Brain-Vertrag wird NICHT übersetzt**: Tool-Namen, Parameter-Namen,
 *     `enum`-Werte und die Raum-Ids sind in jeder Sprache Zeichen für Zeichen gleich.
 */
class AgenticToolSchemaLanguageTest {

    @Suppress("UNCHECKED_CAST")
    private fun function(language: Language, name: String): Map<String, Any?> =
        AgenticToolRegistry.schemas(language)
            .map { it["function"] as Map<String, Any?> }
            .single { it["name"] == name }

    @Suppress("UNCHECKED_CAST")
    private fun property(language: Language, tool: String, param: String): Map<String, Any?> {
        val params = function(language, tool)["parameters"] as Map<String, Any?>
        return (params["properties"] as Map<String, Any?>)[param] as Map<String, Any?>
    }

    private fun description(language: Language, tool: String): String =
        function(language, tool)["description"] as String

    // ── (1) Stichprobe je Sprache ────────────────────────────────────────────────

    @Test
    fun `light_set-Beschreibung kommt in jeder der fuenf Sprachen`() {
        val expected = mapOf(
            Language.DE to "Schaltet oder dimmt das Licht in einem Raum.",
            Language.EN to "Switches or dims the light in a room.",
            Language.ES to "Enciende, apaga o atenúa la luz de una habitación.",
            Language.FR to "Allume, éteint ou tamise la lumière d'une pièce.",
            Language.IT to "Accende, spegne o attenua la luce di una stanza.",
        )
        assertEquals(Language.entries.toSet(), expected.keys, "alle fünf Sprachen, keine still vergessen")
        expected.forEach { (lang, head) ->
            assertTrue(description(lang, "light_set").startsWith(head), "$lang: ${description(lang, "light_set")}")
        }
    }

    @Test
    fun `state-, climate- und scene-Texte folgen ebenfalls der Sprache`() {
        val state = mapOf(
            Language.DE to "Gewünschter Zustand des Lichts.",
            Language.EN to "Desired state of the light.",
            Language.ES to "Estado deseado de la luz.",
            Language.FR to "État souhaité de la lumière.",
            Language.IT to "Stato desiderato della luce.",
        )
        val scene = mapOf(
            Language.DE to "Name der Szene, die aktiviert werden soll.",
            Language.EN to "Name of the scene to activate.",
            Language.ES to "Nombre de la escena que se debe activar.",
            Language.FR to "Nom de la scène à activer.",
            Language.IT to "Nome della scena da attivare.",
        )
        assertEquals(Language.entries.toSet(), state.keys)
        assertEquals(Language.entries.toSet(), scene.keys)

        Language.entries.forEach { lang ->
            assertEquals(state.getValue(lang), property(lang, "light_set", "state")["description"], "$lang state")
            assertEquals(scene.getValue(lang), property(lang, "scene_activate", "name")["description"], "$lang scene")
            // Klima: nur die Sprach-Stichprobe, nicht der ganze Satz.
            assertTrue((description(lang, "climate_set")).isNotBlank(), "$lang climate")
        }
        assertTrue(description(Language.EN, "climate_set").startsWith("Sets the target temperature"))
        assertFalse(description(Language.EN, "climate_set").contains("Zieltemperatur"))
    }

    // ── (2) DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Beschreibungen sind BYTE-IDENTISCH zum Stand vor der Sprach-Naht`() {
        assertEquals(
            "Schaltet oder dimmt das Licht in einem Raum. " +
                "state=on schaltet ein, state=off schaltet aus. Optional brightness_pct " +
                "(0–100) zum Dimmen und color_name für eine Farbe.",
            description(Language.DE, "light_set"),
        )
        assertEquals("Der Raum, z.B. wohnzimmer, kuche, schlafzimmer.", property(Language.DE, "light_set", "area")["description"])
        assertEquals("Gewünschter Zustand des Lichts.", property(Language.DE, "light_set", "state")["description"])
        assertEquals("Helligkeit in Prozent (0–100), optional.", property(Language.DE, "light_set", "brightness_pct")["description"])
        assertEquals(
            "Farbname (englisch), z.B. red, blue, warm. Optional.",
            property(Language.DE, "light_set", "color_name")["description"],
        )
        assertEquals("Setzt die Zieltemperatur der Heizung/Klima in einem Raum.", description(Language.DE, "climate_set"))
        assertEquals(
            "Der Raum, z.B. wohnzimmer, schlafzimmer, badezimmer.",
            property(Language.DE, "climate_set", "area")["description"],
        )
        assertEquals(
            "Zieltemperatur in Grad Celsius (z.B. 21).",
            property(Language.DE, "climate_set", "temperature")["description"],
        )
        assertEquals("Aktiviert eine benannte Szene (z.B. Kino, Entspannen).", description(Language.DE, "scene_activate"))
        assertEquals("Name der Szene, die aktiviert werden soll.", property(Language.DE, "scene_activate", "name")["description"])
    }

    @Test
    fun `schemas ohne Argument ist byte-identisch zu schemas(DE) - der Bestands-Aufrufer aendert sich nicht`() {
        assertEquals(AgenticToolRegistry.schemas(Language.DE), AgenticToolRegistry.schemas())
    }

    // ── (3) Der Vertrag wird NICHT übersetzt ─────────────────────────────────────

    @Test
    fun `Tool-Namen, Parameter-Namen, enum-Werte und Raum-Ids sind in JEDER Sprache identisch`() {
        val reference = AgenticToolRegistry.schemas(Language.DE)

        Language.entries.forEach { lang ->
            val schemas = AgenticToolRegistry.schemas(lang)
            assertEquals(3, schemas.size, "$lang: drei Tools")

            @Suppress("UNCHECKED_CAST")
            fun names(list: List<Map<String, Any?>>) =
                list.map { (it["function"] as Map<String, Any?>)["name"] }
            assertEquals(names(reference), names(schemas), "$lang: Tool-Namen unverändert")

            // enum-Werte: der Brain darf NUR on/off senden — in jeder Sprache.
            assertEquals(listOf("on", "off"), property(lang, "light_set", "state")["enum"], "$lang: state-enum")
            // Raum-Ids: HA-Wahrheit (kuche, NICHT kueche/kitchen) — nie übersetzt.
            assertEquals(ToolAreas.AREAS, property(lang, "light_set", "area")["enum"], "$lang: area-enum")
            assertEquals(ToolAreas.AREAS, property(lang, "climate_set", "area")["enum"], "$lang: area-enum")
            // Auch die BEISPIELE in der Beschreibung nennen die echten Ids, nicht Übersetzungen.
            val areaDesc = property(lang, "light_set", "area")["description"] as String
            assertTrue(areaDesc.contains("wohnzimmer"), "$lang: echte Raum-Id im Beispiel — $areaDesc")
            assertTrue(areaDesc.contains("kuche"), "$lang: echte Raum-Id im Beispiel — $areaDesc")
            assertFalse(areaDesc.contains("kitchen"), "$lang: KEINE übersetzte Raum-Id — $areaDesc")
            // Farbnamen bleiben die HA-englischen Werte.
            val colorDesc = property(lang, "light_set", "color_name")["description"] as String
            assertTrue(colorDesc.contains("red, blue, warm"), "$lang: HA-Farbwerte unübersetzt — $colorDesc")
        }
    }
}
