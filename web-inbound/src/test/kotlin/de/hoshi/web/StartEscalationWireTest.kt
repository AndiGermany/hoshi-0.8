package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **SSE-Wire-Vertrag der additiven Extended-Think-S4-Felder** — `escalated`/
 * `escalationProvider`/`cacheHit` an [ChatEvent.Start] (Muster
 * [ChatEvent.Start.grounded]/[ChatEvent.Start.segmentReset]: IMMER serialisiert,
 * KEINE NON_NULL/NON_DEFAULT-Hide-Klausel — dieselbe Wahl wie bei den Segment-
 * Diary-Feldern) und `escalationCostCents` an [ChatEvent.Done] (Muster
 * `stageTimings`: [com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL],
 * NUR ein bezahlter Eskalations-Turn fügt den Key hinzu).
 *
 * **„Wire-Byte-Gleichheit ohne Eskalation"**: ein Start OHNE Eskalations-/Cache-
 * Angabe pinnt IMMER dasselbe, deterministische JSON — die drei neuen Keys
 * `escalated:false`/`escalationProvider:""`/`cacheHit:false` hängen additiv ans
 * Ende (hinter `segmentLenTurns`), kein bestehendes Feld/keine Reihenfolge ändert
 * sich. Ein Client, der die drei neuen Keys nicht kennt, ignoriert sie (additiv/
 * optional — Muster [ChatEvent.Speaker]).
 */
class StartEscalationWireTest {

    private val mapper = ObjectMapper()

    @Test
    fun `ohne Eskalation - das start-event pinnt escalated, escalationProvider, cacheHit und escalationSource auf ihre Defaults`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
        )
        assertEquals(
            """{"event":"start","provider":"LOCAL","category":"FACT_SHORT","model":"brain",""" +
                """"personaEmotion":"neutral","grounded":false,"segmentReset":false,"resetReason":"none",""" +
                """"segmentLenTurns":0,"escalated":false,"escalationProvider":"","cacheHit":false,""" +
                """"escalationSource":""}""",
            json,
            "additiv ans Ende (H2: escalationSource NACH cacheHit), honeste Defaults — " +
                "deterministisch, keine Reihenfolge-Drift der Alt-Felder",
        )
    }

    @Test
    fun `Cache-Hit-Turn - escalationSource (H2) reist additiv am Start`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Start(
                provider = "LOCAL",
                category = "FACT_SHORT",
                model = "brain",
                grounded = true,
                cacheHit = true,
                escalationSource = "Wikipedia",
            ),
        )
        val node = mapper.readTree(json)
        assertEquals("Wikipedia", node["escalationSource"].asText())
    }

    @Test
    fun `eskalierter Turn - escalated und escalationProvider reisen am Start`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Start(
                provider = "LOCAL",
                category = "FACT_SHORT",
                model = "policy",
                escalated = true,
                escalationProvider = "openai-nano",
            ),
        )
        val node = mapper.readTree(json)
        assertEquals(true, node["escalated"].asBoolean())
        assertEquals("openai-nano", node["escalationProvider"].asText())
    }

    @Test
    fun `Cache-Hit-Turn - cacheHit reist additiv, escalated bleibt false`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain", grounded = true, cacheHit = true),
        )
        val node = mapper.readTree(json)
        assertEquals(true, node["cacheHit"].asBoolean())
        assertEquals(false, node["escalated"].asBoolean())
    }

    @Test
    fun `done ohne eskalation - escalationCostCents fehlt komplett (NON_NULL, byte-neutral)`() {
        val json = mapper.writeValueAsString(ChatEvent.Done(provider = "LOCAL"))
        assertEquals(
            """{"event":"done","provider":"LOCAL","totalSentences":0,"ttsHandled":false}""",
            json,
            "null-Kosten dürfen den Draht NICHT verändern — exakt das stageTimings-Muster",
        )
    }

    @Test
    fun `done mit eskalation - escalationCostCents reist additiv`() {
        val json = mapper.writeValueAsString(ChatEvent.Done(provider = "LOCAL", escalationCostCents = 0.05))
        val node = mapper.readTree(json)
        assertEquals(0.05, node["escalationCostCents"].asDouble())
    }

    @Test
    fun `done mit Answer - escalationQueryHash und escalationSource (H2) reisen additiv`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Done(
                provider = "LOCAL",
                escalationCostCents = 0.05,
                escalationQueryHash = "abc123",
                escalationSource = "Wikipedia",
            ),
        )
        val node = mapper.readTree(json)
        assertEquals("abc123", node["escalationQueryHash"].asText())
        assertEquals("Wikipedia", node["escalationSource"].asText())
        assertEquals(false, node.has("escalationCapExhausted"), "kein Cap-Fall ⇒ Key fehlt (NON_NULL)")
    }

    @Test
    fun `done mit CapExhausted (H3) - eigenes Feld, escalationCostCents fehlt`() {
        val json = mapper.writeValueAsString(ChatEvent.Done(provider = "LOCAL", escalationCapExhausted = true))
        val node = mapper.readTree(json)
        assertEquals(true, node["escalationCapExhausted"].asBoolean())
        assertEquals(false, node.has("escalationCostCents"), "CapExhausted lieferte keine Answer ⇒ keine Kosten")
    }
}
