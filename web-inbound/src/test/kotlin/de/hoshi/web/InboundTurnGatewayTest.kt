package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.ConversationKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class InboundTurnGatewayTest {

    @Test
    fun `alle Inbound-Kanaele ueberschreiben source und conversationKey`() {
        val captured = mutableListOf<ChatRequest>()
        val gateway = InboundTurnGateway(
            delegate = { request ->
                captured += request
                Flux.just(ChatEvent.Done(provider = "LOCAL"))
            },
        )
        val claimed = ChatRequest(
            text = "ja",
            chatId = "chat-a",
            deviceId = "device-a",
            source = "client-claim",
            conversationKey = "client-key",
        )

        gateway.chat(claimed).run().blockLast()
        gateway.voice(claimed).run().blockLast()
        gateway.webSocket(claimed, "server-session").run().blockLast()

        assertEquals(
            listOf(
                TurnDiaryTap.SOURCE_CHAT to ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, "device-a"),
                TurnDiaryTap.SOURCE_VOICE to ConversationKeys.forDevice(ConversationKeys.Channel.VOICE, "device-a"),
                TurnDiaryTap.SOURCE_WS to ConversationKeys.forSession("server-session"),
            ),
            captured.map { it.source to it.conversationKey },
        )
    }

    @Test
    fun `Execution-Resolver kann verriegelte Rand-Claims nicht zurueckkopieren`() {
        lateinit var captured: ChatRequest
        val gateway = InboundTurnGateway(
            delegate = { request ->
                captured = request
                Flux.empty()
            },
            execution = InboundTurnGateway.Execution { request, turn ->
                turn(request.copy(source = "ws", conversationKey = "fremder-key"))
            },
        )

        gateway.chat(ChatRequest(text = "hallo", chatId = "chat-a")).run().blockLast()

        assertEquals(TurnDiaryTap.SOURCE_CHAT, captured.source)
        assertEquals(ConversationKeys.forChatId("chat-a"), captured.conversationKey)
    }
}
