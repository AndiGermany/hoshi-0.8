package de.hoshi.web

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **`llm_done` trägt die offene Rückfrage** (Andi-Livetest 2026-08-21) — die WS-Hälfte
 * von [ChatEvent.Done.expectsReply]. Muster K4/`timer_ring`
 * (`vault/tracks/RESULT-k4-timer-ring-2026-08-20.md`): additiv ans FRAME-ENDE, mit
 * ausdrücklicher Alt-Parser-Probe.
 *
 * Gepinnt wird:
 *  1. ohne offene Rückfrage bleibt das Frame BYTE-IDENTISCH zum bisherigen Schema;
 *  2. mit offener Rückfrage kommen `expectReply` + `pendingKind` ans Ende;
 *  3. `expectReply` steht NIE als `false` auf dem Draht (kein Key statt falscher Key);
 *  4. ein Alt-Parser des bisherigen `{type,ttsHandled}`-Schemas parst das neue Frame
 *     weiterhin — die geflashte Firmware ignoriert unbekannte Felder gefahrlos;
 *  5. die never-silent-Notausgänge des Handlers sprechen das Feld NICHT.
 */
class ChatEventWsTranslatorExpectReplyTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val T = ChatEventWsTranslator

    /** Exakt das Schema, das ein Bestandsclient vor dem 21.08. kannte. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LegacyLlmDone(val type: String = "", val ttsHandled: Boolean = false)

    // ── (1) Kein Pending ⇒ byte-identisch ─────────────────────────────────────
    @Test
    fun `ohne offene Rueckfrage bleibt llm_done byte-identisch`() {
        val frame = T.translate(ChatEvent.Done(ttsHandled = true))

        assertEquals("""{"type":"llm_done","ttsHandled":true}""", frame)
    }

    // ── (2) Mit Pending ⇒ additiv am Frame-ENDE ───────────────────────────────
    @Test
    fun `mit offener Rueckfrage haengen expectReply und pendingKind hinten an`() {
        val frame = T.translate(
            ChatEvent.Done(expectsReply = true, pendingKind = ChatEvent.PendingKind.LOOKUP),
        )

        assertEquals(
            """{"type":"llm_done","ttsHandled":false,"expectReply":true,"pendingKind":"lookup"}""",
            frame,
        )
    }

    @Test
    fun `jede Arbiter-Art erreicht den Draht`() {
        val kinds = listOf(
            ChatEvent.PendingKind.LOOKUP,
            ChatEvent.PendingKind.LOCATION,
            ChatEvent.PendingKind.AREA,
        )
        for (kind in kinds) {
            val frame = T.translate(ChatEvent.Done(expectsReply = true, pendingKind = kind))!!
            val node = mapper.readTree(frame)
            assertTrue(node["expectReply"].asBoolean(), "expectReply fehlt fuer $kind")
            assertEquals(kind, node["pendingKind"].asText())
        }
    }

    // ── (3) Nie ein falsches `false` ──────────────────────────────────────────
    @Test
    fun `expectReply steht nie als false auf dem Draht`() {
        val frame = T.translate(ChatEvent.Done(expectsReply = null, pendingKind = null))!!

        assertFalse(frame.contains("expectReply"), "kein Key ist besser als ein falscher Key: $frame")
        assertFalse(frame.contains("pendingKind"))
    }

    // ── (4) Alt-Parser-Probe ──────────────────────────────────────────────────
    @Test
    fun `ein Alt-Parser des bisherigen Schemas parst das neue Frame weiter`() {
        val frame = T.translate(
            ChatEvent.Done(ttsHandled = true, expectsReply = true, pendingKind = ChatEvent.PendingKind.AREA),
        )!!

        val legacy: LegacyLlmDone = mapper.readValue(frame)

        assertEquals("llm_done", legacy.type, "der Alt-Parser sieht denselben Frame-Typ")
        assertTrue(legacy.ttsHandled, "und dasselbe Bestandsfeld")
    }

    // ── (5) Notausgänge sprechen das Feld nicht ───────────────────────────────
    @Test
    fun `die never-silent-Notausgaenge tragen kein expectReply`() {
        // Genau die Aufrufform, die der Handler bei Audio-Cap, Session-Guard,
        // Stream-Fehler und `abort` benutzt.
        assertEquals("""{"type":"llm_done","ttsHandled":false}""", T.llmDone(false))
    }

    // ── (6) turnId haengt weiterhin GANZ hinten an ────────────────────────────
    @Test
    fun `withTurnId haengt sich hinter die neuen Felder`() {
        val frame = T.translate(ChatEvent.Done(expectsReply = true, pendingKind = ChatEvent.PendingKind.LOOKUP))!!

        val withId = T.withTurnId(frame, "t-7")

        assertEquals(
            """{"type":"llm_done","ttsHandled":false,"expectReply":true,"pendingKind":"lookup","turnId":"t-7"}""",
            withId,
        )
    }
}
