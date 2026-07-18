package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **ChatEventWsTranslatorTest** — beweist die ChatEvent→0.7-Wire-Frame-Übersetzung
 * thoroughly + dependency-frei (reines `object`, kein Spring/WS/Brain). Jeder Frame
 * wird zusätzlich durch einen echten Jackson-[ObjectMapper] geparst ⇒ Beweis, dass
 * die hand-rollten Frames valides JSON mit dem erwarteten `type` + Feldern sind.
 */
class ChatEventWsTranslatorTest {

    private val mapper = ObjectMapper()
    private fun parse(frame: String) = mapper.readTree(frame)

    @Test
    fun `Start mappt auf llm_start mit provider category model emotion`() {
        val frame = ChatEventWsTranslator.translate(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain", personaEmotion = "warm"),
        )!!
        val node = parse(frame)
        assertEquals("llm_start", node["type"].asText())
        assertEquals("LOCAL", node["provider"].asText())
        assertEquals("SMALLTALK", node["category"].asText())
        assertEquals("brain", node["model"].asText())
        assertEquals("warm", node["emotion"].asText())
    }

    @Test
    fun `TextDelta mappt auf llm_delta mit text`() {
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.TextDelta("Hallo Andi"))!!)
        assertEquals("llm_delta", node["type"].asText())
        assertEquals("Hallo Andi", node["text"].asText())
    }

    @Test
    fun `AudioChunk mappt byte-1zu1 auf llm_audio mit seq und data`() {
        val b64 = "U29tZUJhc2U2NA==" // beliebige base64-WAV
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.AudioChunk(data = b64, seq = 7))!!)
        assertEquals("llm_audio", node["type"].asText())
        assertEquals(7, node["seq"].asInt())
        assertEquals(b64, node["data"].asText(), "data muss byte-identisch durchgereicht werden")
    }

    @Test
    fun `TtsAudioStart ohne estimatedMs hat kein estimatedMs-Feld`() {
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.TtsAudioStart(provider = "voxtral"))!!)
        assertEquals("tts_audio_start", node["type"].asText())
        assertEquals("voxtral", node["provider"].asText())
        assertNull(node["estimatedMs"], "ohne Wert kein estimatedMs-Feld")
    }

    @Test
    fun `TtsAudioStart mit estimatedMs traegt die Zahl`() {
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.TtsAudioStart(provider = "voxtral", estimatedMs = 1200))!!)
        assertEquals(1200, node["estimatedMs"].asLong())
    }

    @Test
    fun `TtsAudioEnd mappt auf tts_audio_end mit actualMs`() {
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.TtsAudioEnd(actualMs = 980))!!)
        assertEquals("tts_audio_end", node["type"].asText())
        assertEquals(980, node["actualMs"].asLong())
    }

    @Test
    fun `Done mappt auf llm_done mit ttsHandled`() {
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.Done(ttsHandled = true))!!)
        assertEquals("llm_done", node["type"].asText())
        assertTrue(node["ttsHandled"].asBoolean())
    }

    @Test
    fun `Error mappt auf llm_error mit stage und message`() {
        val node = parse(
            ChatEventWsTranslator.translate(ChatEvent.Error(message = "STT weg", stage = ChatEvent.Stage.SIDECAR))!!,
        )
        assertEquals("llm_error", node["type"].asText())
        assertEquals("SIDECAR", node["stage"].asText())
        assertEquals("STT weg", node["message"].asText())
    }

    @Test
    fun `Step wird NICHT ans Geraet geschickt (null)`() {
        assertNull(ChatEventWsTranslator.translate(ChatEvent.Step(kind = "transcript", message = "egal")))
    }

    @Test
    fun `Speaker mit sicherem Treffer und Flag ON mappt auf speaker mit speakerId`() {
        val frame = ChatEventWsTranslator.translate(
            ChatEvent.Speaker(recognizedSpeaker = "andi", confidence = 0.97, isGuest = false),
            speakerFrameEnabled = true,
        )!!
        assertEquals("""{"type":"speaker","speakerId":"andi"}""", frame, "Frame-Form ist mit der Geräteseite fest vereinbart")
        val node = parse(frame)
        assertEquals("speaker", node["type"].asText())
        assertEquals("andi", node["speakerId"].asText())
        assertNull(node["confidence"], "confidence darf NICHT auf dem Draht stehen (Farb-Mapping lebt im Gerät)")
        assertNull(node["isGuest"], "isGuest darf NICHT auf dem Draht stehen (Farb-Mapping lebt im Gerät)")
    }

    @Test
    fun `Speaker als Gast ohne sicheren Treffer bleibt auch bei Flag ON stumm`() {
        assertNull(
            ChatEventWsTranslator.translate(
                ChatEvent.Speaker(recognizedSpeaker = null, confidence = 0.2, isGuest = true),
                speakerFrameEnabled = true,
            ),
            "Gast ⇒ nie raten, kein Frame",
        )
    }

    @Test
    fun `Speaker mit sicherem Treffer aber Flag OFF bleibt byte-neutral stumm`() {
        assertNull(
            ChatEventWsTranslator.translate(
                ChatEvent.Speaker(recognizedSpeaker = "andi", confidence = 0.97, isGuest = false),
                speakerFrameEnabled = false,
            ),
        )
        // Aufruf ohne das Flag-Argument (Default) ⇒ identisch zum Vor-Zustand.
        assertNull(
            ChatEventWsTranslator.translate(ChatEvent.Speaker(recognizedSpeaker = "andi", confidence = 0.97, isGuest = false)),
        )
    }

    // ── STT-/Steuer-Frames ────────────────────────────────────────────────────

    @Test
    fun `Steuer-Frames haben den erwarteten type`() {
        assertEquals("transcribing_started", parse(ChatEventWsTranslator.transcribingStarted())["type"].asText())
        assertEquals("no_input", parse(ChatEventWsTranslator.noInput())["type"].asText())
        assertEquals("llm_thinking", parse(ChatEventWsTranslator.llmThinking())["type"].asText())
        val t = parse(ChatEventWsTranslator.transcript("Mach das Licht an"))
        assertEquals("transcript", t["type"].asText())
        assertEquals("Mach das Licht an", t["text"].asText())
    }

    @Test
    fun `turnAborted mit turnId traegt sie, ohne turnId nicht`() {
        val withId = parse(ChatEventWsTranslator.turnAborted("t1"))
        assertEquals("turn_aborted", withId["type"].asText())
        assertEquals("t1", withId["turnId"].asText())
        assertNull(parse(ChatEventWsTranslator.turnAborted(null))["turnId"])
    }

    @Test
    fun `withTurnId haengt turnId an einen Frame an, null laesst ihn unveraendert`() {
        val base = ChatEventWsTranslator.translate(ChatEvent.TextDelta("hi"))!!
        val withId = ChatEventWsTranslator.withTurnId(base, "t9")
        assertEquals("t9", parse(withId)["turnId"].asText())
        assertEquals("hi", parse(withId)["text"].asText())
        // null/leer ⇒ byte-identisch (rückwärtskompatibel zum FE ohne turnId).
        assertEquals(base, ChatEventWsTranslator.withTurnId(base, null))
        assertEquals(base, ChatEventWsTranslator.withTurnId(base, ""))
    }

    @Test
    fun `Sonderzeichen im Text werden korrekt escaped (valides JSON)`() {
        // Anführungszeichen + Newline + Backslash + Unicode dürfen das JSON nicht brechen.
        val nasty = "sagte \"hallo\"\nund \\ ende\tÄÖÜ"
        val node = parse(ChatEventWsTranslator.translate(ChatEvent.TextDelta(nasty))!!)
        assertEquals(nasty, node["text"].asText(), "round-trip durch Jackson beweist korrektes Escaping")
    }
}
