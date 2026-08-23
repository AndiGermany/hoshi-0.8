package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.SpeakerContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationKeysTest {
    @Test
    fun `dieselbe Geraete-ID bleibt pro Kanal stabil aber nie kanal-uebergreifend`() {
        val chatA = ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, "browser-1")
        val chatB = ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, "browser-1")
        val voice = ConversationKeys.forDevice(ConversationKeys.Channel.VOICE, "browser-1")

        assertEquals(chatA, chatB)
        assertNotEquals(chatA, voice)
        assertTrue(chatA!!.startsWith("chat:device:"))
        assertTrue(voice!!.startsWith("voice:device:"))
    }

    @Test
    fun `WebSocket bindet an die Server-Session und isoliert zwei Sessions`() {
        assertEquals(ConversationKeys.forSession("session-a"), ConversationKeys.forSession("session-a"))
        assertNotEquals(ConversationKeys.forSession("session-a"), ConversationKeys.forSession("session-b"))
    }

    @Test
    fun `Sprecher-Fallback akzeptiert nur einen Claim oberhalb der Trust-Schwelle`() {
        val trusted = ChatRequest(
            text = "ja",
            source = "voice",
            speakerContext = SpeakerContext(speakerId = "andi", score = 0.91),
        )
        val untrusted = trusted.copy(speakerContext = SpeakerContext(speakerId = "andi", score = 0.79))

        assertTrue(ConversationKeys.resolve(trusted, 0.80).startsWith("voice:speaker:"))
        assertEquals("voice:local", ConversationKeys.resolve(untrusted, 0.80))
    }

    @Test
    fun `ungueltiger expliziter Claim wird nicht als Conversation-Key uebernommen`() {
        val request = ChatRequest(
            text = "ja",
            source = "chat",
            deviceId = "browser-1",
            conversationKey = "ws:session:vom-client-behauptet",
        )

        assertEquals(
            ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, "browser-1"),
            ConversationKeys.resolve(request, 0.80),
        )
    }
}
