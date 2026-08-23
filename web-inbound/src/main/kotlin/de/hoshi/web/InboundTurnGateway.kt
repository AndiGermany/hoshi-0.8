package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.ConversationKeys
import de.hoshi.core.pipeline.TurnOrchestrator
import reactor.core.publisher.Flux

/**
 * Einziger Übergang von Web-Inbounds in den [TurnOrchestrator]. Kanal und
 * Conversation-Key werden hier unmittelbar vor dem Kernaufruf gesetzt, damit
 * weder Wire-Claims noch ein vorgeschalteter Resolver diese Autorität tragen.
 */
class InboundTurnGateway internal constructor(
    private val delegate: (ChatRequest) -> Flux<ChatEvent>,
    private val execution: Execution = Execution.DIRECT,
) {
    constructor(
        orchestrator: TurnOrchestrator,
        execution: Execution = Execution.DIRECT,
    ) : this(orchestrator::handle, execution)

    fun chat(request: ChatRequest): PreparedTurn = prepared(
        request.copy(
            source = TurnDiaryTap.SOURCE_CHAT,
            conversationKey = ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, request.deviceId)
                ?: ConversationKeys.forChatId(request.chatId),
        ),
    )

    fun voice(request: ChatRequest): PreparedTurn = prepared(
        request.copy(
            source = TurnDiaryTap.SOURCE_VOICE,
            conversationKey = ConversationKeys.forDevice(ConversationKeys.Channel.VOICE, request.deviceId),
        ),
    )

    fun webSocket(request: ChatRequest, serverSessionId: String): PreparedTurn = prepared(
        request.copy(
            source = TurnDiaryTap.SOURCE_WS,
            conversationKey = ConversationKeys.forSession(serverSessionId),
        ),
    )

    private fun prepared(request: ChatRequest): PreparedTurn = PreparedTurn(request, ::runPrepared)

    /**
     * Resolver dürfen andere Request-Felder ändern. Direkt vor dem Delegate
     * werden die zwei Rand-Claims erneut auf die vorbereiteten Werte verriegelt.
     */
    internal fun runPrepared(request: ChatRequest): Flux<ChatEvent> {
        val trustedSource = request.source
        val trustedConversationKey = request.conversationKey
        return execution.execute(request) { candidate ->
            delegate(
                candidate.copy(
                    source = trustedSource,
                    conversationKey = trustedConversationKey,
                ),
            )
        }
    }

    class PreparedTurn internal constructor(
        val request: ChatRequest,
        private val runPrepared: (ChatRequest) -> Flux<ChatEvent>,
    ) {
        fun run(): Flux<ChatEvent> = runPrepared(request)
    }

    fun interface Execution {
        fun execute(
            request: ChatRequest,
            turn: (ChatRequest) -> Flux<ChatEvent>,
        ): Flux<ChatEvent>

        companion object {
            val DIRECT = Execution { request, turn -> turn(request) }
        }
    }
}
