package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **SSE-Wire-Vertrag des additiven `stageTimings` (Perf-Diary):**
 *
 *  - OHNE Messung (`stageTimings=null`, alle Policy-/Alt-Pfade) fehlt der Key
 *    KOMPLETT ⇒ das `done`-Event ist BYTE-IDENTISCH zu heute (JsonInclude
 *    NON_NULL) — alte Clients/Firmware sehen exakt den bisherigen Draht.
 *  - MIT Messung erscheint nur das Gemessene (null-Felder des Objekts werden
 *    ebenfalls ausgelassen) — SSE-additiv, das FE ignoriert Unbekanntes.
 */
class DoneStageTimingsWireTest {

    private val mapper = ObjectMapper()

    @Test
    fun `ohne messung ist das done-event byte-identisch zu heute`() {
        val json = mapper.writeValueAsString(ChatEvent.Done(provider = "LOCAL"))
        assertFalse(json.contains("stageTimings"), "null-Timings dürfen den Draht NICHT verändern")
        assertEquals("""{"event":"done","provider":"LOCAL","totalSentences":0,"ttsHandled":false}""", json)
    }

    @Test
    fun `mit messung reist nur das gemessene - null-felder ausgelassen`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Done(
                provider = "LOCAL",
                stageTimings = ChatEvent.StageTimings(groundingMs = 42, brainTtftMs = 250),
            ),
        )
        val node = mapper.readTree(json)["stageTimings"]
        assertEquals(42, node["groundingMs"].asLong())
        assertEquals(250, node["brainTtftMs"].asLong())
        assertFalse(node.has("ttsFirstAudioMs"), "nicht gemessen ⇒ Key ausgelassen (nie null-Rauschen)")
        assertFalse(node.has("admissionWaitMs"))
        assertFalse(node.has("answerEntropy"), "Entropie nicht gemessen ⇒ Key ausgelassen (Draht wie heute)")
        assertTrue(node.has("groundingMs"))
    }

    @Test
    fun `gemessene answerEntropy reist additiv im stageTimings-objekt`() {
        val json = mapper.writeValueAsString(
            ChatEvent.Done(
                provider = "LOCAL",
                stageTimings = ChatEvent.StageTimings(brainTtftMs = 250, answerEntropy = 1.25),
            ),
        )
        val node = mapper.readTree(json)["stageTimings"]
        assertEquals(1.25, node["answerEntropy"].asDouble(), "der S1-Messwert (nats) reist am Done-Draht")
        assertEquals(250, node["brainTtftMs"].asLong())
    }
}
