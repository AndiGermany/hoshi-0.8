package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die robuste Extraktion des live gemessenen gemma-Tool-Call-Formats
 * `<|tool_call>call:NAME{key:<|"|>wert<|"|>,...}<tool_call|>`:
 * echtes `<|"|>`-Quote-Token wird entfernt, bare Zahlen bleiben String, kein/halber
 * Block ⇒ null, der ERSTE Block gewinnt (BFCL „1 Tool pro Turn").
 */
class ToolCallParserTest {

    @Test
    fun `echtes Format mit Quote-Token und mehreren Args`() {
        val raw = "<|tool_call>call:light_set{area:<|\"|>wohnzimmer<|\"|>,state:<|\"|>on<|\"|>}<tool_call|>"
        val parsed = ToolCallParser.parse(raw)!!
        assertEquals("light_set", parsed.name)
        assertEquals("wohnzimmer", parsed.args["area"], "Quote-Token <|\"|> wird entfernt")
        assertEquals("on", parsed.args["state"])
        assertEquals(2, parsed.args.size)
    }

    @Test
    fun `bare Zahl bleibt als String erhalten`() {
        val raw = "<|tool_call>call:climate_set{area:<|\"|>badezimmer<|\"|>,temperature:22}<tool_call|>"
        val parsed = ToolCallParser.parse(raw)!!
        assertEquals("climate_set", parsed.name)
        assertEquals("badezimmer", parsed.args["area"])
        // Bare-Zahl: kein Quote, bleibt ihr String-Literal "22" (Typisierung erst in der Registry).
        assertEquals("22", parsed.args["temperature"])
    }

    @Test
    fun `umgebender Text vor dem Block wird ignoriert`() {
        val raw = "Klar, mach ich! <|tool_call>call:scene_activate{name:<|\"|>kino<|\"|>}<tool_call|>"
        val parsed = ToolCallParser.parse(raw)!!
        assertEquals("scene_activate", parsed.name)
        assertEquals("kino", parsed.args["name"])
    }

    @Test
    fun `normaler Text ohne Tool-Call ergibt null`() {
        assertNull(ToolCallParser.parse("Im Wohnzimmer ist das Licht gerade aus."))
    }

    @Test
    fun `defekter halber Block ohne Schliesser ergibt null`() {
        assertNull(ToolCallParser.parse("<|tool_call>call:light_set{area:<|\"|>wohnzimmer"))
    }

    @Test
    fun `unterminierter String-Wert ergibt null`() {
        // QUOTE wird geöffnet, aber nie geschlossen ⇒ defekt ⇒ null (wirft nicht).
        assertNull(ToolCallParser.parse("<|tool_call>call:light_set{area:<|\"|>wohnzimmer}<tool_call|>"))
    }

    @Test
    fun `nimmt den ersten Tool-Call bei mehreren`() {
        val raw = "<|tool_call>call:light_set{area:<|\"|>flur<|\"|>,state:<|\"|>off<|\"|>}<tool_call|>" +
            "<|tool_call>call:scene_activate{name:<|\"|>kino<|\"|>}<tool_call|>"
        val parsed = ToolCallParser.parse(raw)!!
        assertEquals("light_set", parsed.name, "BFCL: der ERSTE Tool-Call gewinnt")
        assertEquals("flur", parsed.args["area"])
        assertEquals("off", parsed.args["state"])
    }
}
