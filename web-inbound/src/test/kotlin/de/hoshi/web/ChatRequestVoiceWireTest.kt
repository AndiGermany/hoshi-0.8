package de.hoshi.web

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.hoshi.core.dto.ChatRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wire-Vertrag des [ChatRequest.voice]-Felds (Backlog #6 „Stimme wählen"):
 *  - Legacy-JSON OHNE `voice` ⇒ null ⇒ Boot-Default-Stimme (byte-neutral),
 *  - gesetztes `voice` überlebt den Roundtrip unverändert,
 *  - das DTO reicht den Wert ROH durch — die Whitelist-Prüfung ist bewusst
 *    Adapter-Sache ([de.hoshi.adapters.tts]-OpenAI-Adapter), nicht Wire-Sache.
 *
 * Jackson-Kotlin wie im echten Spring-Rand (jacksonObjectMapper).
 */
class ChatRequestVoiceWireTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `Legacy-JSON ohne voice-Feld deserialisiert zu null (byte-neutral)`() {
        val req = mapper.readValue<ChatRequest>("""{"text":"Hallo"}""")
        assertNull(req.voice, "fehlendes Feld muss null bleiben (Boot-Default-Stimme)")
    }

    @Test
    fun `gesetztes voice-Feld kommt roh im DTO an (Whitelist ist Adapter-Sache)`() {
        val req = mapper.readValue<ChatRequest>("""{"text":"Hallo","voice":"nova"}""")
        assertEquals("nova", req.voice)
        assertEquals("Hallo", req.text)
    }

    @Test
    fun `voice ueberlebt den Wire-Roundtrip serialize-deserialize`() {
        val original = ChatRequest(text = "Hallo Hoshi", voice = "coral")
        val back = mapper.readValue<ChatRequest>(mapper.writeValueAsString(original))
        assertEquals("coral", back.voice)
        assertEquals(original, back, "der ganze Request muss den Roundtrip überleben")
    }

    @Test
    fun `auch unbekannte voice-Namen passieren das DTO unveraendert (Adapter faellt zurueck)`() {
        val req = mapper.readValue<ChatRequest>("""{"text":"Hallo","voice":"eddie-der-adler"}""")
        assertEquals("eddie-der-adler", req.voice)
    }
}
