package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/** End-to-end: ein Pending wird nur von derselben Kanal-/Geräte-Kette eingelöst. */
class TurnOrchestratorConversationKeyTest {
    private class Brain : BrainPort {
        var calls = 0
        override fun streamChat(
            prompt: String,
            systemPrompt: String,
            history: List<ChatMessage>,
            temperature: Double?,
            sessionId: String,
            userId: String,
            tools: List<Map<String, Any?>>,
            toolGrammar: Boolean,
            onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            calls++
            return Flux.just(LlmDelta("lokal"))
        }
    }

    private fun orchestrator(
        pending: PendingLookupPort,
        cloudQueries: MutableList<String>,
        brain: Brain,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "test")
                },
                llmRefiner = { _, fallback -> Mono.just(fallback) },
                embeddingRefiner = { _, fallback -> Mono.just(fallback) },
                softRoutingEnabled = false,
                softRoutingMode = "embedding",
            ),
            honesty = HonestyGate(
                weakDomain = WeakDomainSignal { false },
                onlineRequest = OnlineRequestSignal { false },
                existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
                namedEntity = NamedEntitySignal { HonestySignal.NONE },
                cloudEnabled = { false },
            ),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { _, _ -> null },
                grounding = GroundingPort.EMPTY,
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            pendingLookup = pending,
            escalationMode = { EscalationMode.ERST_FRAGEN },
            escalation = EscalationPort { query, _, _ ->
                cloudQueries += query
                Mono.just(EscalationResult.Answer("gefunden", "test", 0.0))
            },
        )
    }

    private fun turn(orchestrator: TurnOrchestrator, request: ChatRequest) {
        orchestrator.handle(request).collectList().block(Duration.ofSeconds(5))!!
    }

    @Test
    fun `gleiches Geraet kann Chat-Pending weder per Voice noch von fremdem Browser einloesen`() {
        val store = InMemoryPendingLookupStore()
        val cloudQueries = mutableListOf<String>()
        val brain = Brain()
        val orchestrator = orchestrator(store, cloudQueries, brain)
        val question = "Wann kommt GTA 6?"
        val chatKey = ConversationKeys.forDevice(ConversationKeys.Channel.CHAT, "browser-a")!!
        store.offer(chatKey, PendingLookup(question, Language.DE))

        turn(orchestrator, ChatRequest(text = "ja", source = "voice", deviceId = "browser-a"))
        turn(orchestrator, ChatRequest(text = "ja", source = "chat", deviceId = "browser-b"))
        assertEquals(emptyList<String>(), cloudQueries)

        turn(orchestrator, ChatRequest(text = "ja", source = "chat", deviceId = "browser-a"))
        assertEquals(listOf(question), cloudQueries)
        assertEquals(2, brain.calls, "die zwei fremden Zustimmungen laufen normal; die eigene ist brain-frei")
    }
}
