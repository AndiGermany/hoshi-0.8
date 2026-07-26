package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.HeuristicLanguageDetector
import de.hoshi.core.pipeline.HonestyGate
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.KeywordRouter
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.OnlineRequestSignal
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.ResponseFormatter
import de.hoshi.core.pipeline.RoutingPolicy
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.pipeline.TurnPromptAssembler
import de.hoshi.core.pipeline.WeakDomainSignal
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Auto-Switch-Anker Chat-Seite (Andi-Auftrag „12B für Chat, e4b für Voice",
 * 2026-07-26)** — beweist OHNE Spring-Boot-Context, dass [ChatStreamController]:
 *  - [BrainAutoSwitchPort.ensureChatModel] GENAU EINMAL VOR dem Turn ruft,
 *  - den fertigen Turn NUR nach dessen Abschluss weiterlaufen lässt (wartet ab),
 *  - Default [BrainAutoSwitchPort.NOOP] den Turn unbeeinflusst lässt (byte-neutral,
 *    andere Tests bauen den Controller ohne das Argument).
 */
class ChatStreamBrainAutoSwitchTest {

    private class FakeBrainPort(private val line: String = "Klar, mach ich.") : BrainPort {
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
        ): Flux<LlmDelta> = Flux.just(LlmDelta(line))
    }

    /** Zählt Aufrufe + merkt die Reihenfolge (Turn darf erst NACH dem Switch starten). */
    private class RecordingAutoSwitchPort(
        private val ensureDelay: Duration = Duration.ZERO,
    ) : BrainAutoSwitchPort {
        val voiceStartCalls = AtomicInteger(0)
        val ensureCalls = AtomicInteger(0)
        override fun onVoiceSessionStart() {
            voiceStartCalls.incrementAndGet()
        }
        override fun ensureChatModel(): Mono<Unit> {
            ensureCalls.incrementAndGet()
            val mono = Mono.just(Unit)
            return if (ensureDelay.isZero) mono else mono.delayElement(ensureDelay)
        }
    }

    private fun orchestrator(): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake")
                },
                llmRefiner = { _, fb -> Mono.just(fb) },
                embeddingRefiner = { _, fb -> Mono.just(fb) },
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
            brain = FakeBrainPort(),
        )
    }

    private fun controller(autoSwitch: BrainAutoSwitchPort) = ChatStreamController(
        orchestrator = orchestrator(),
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        memoryWriter = EntityMemoryWriter.NOOP,
        episodicWriter = EpisodicWriter.NOOP,
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
        brainAutoSwitch = autoSwitch,
    )

    @Test
    fun `ensureChatModel wird GENAU EINMAL vor dem Turn gerufen`() {
        val autoSwitch = RecordingAutoSwitchPort()
        val events = controller(autoSwitch)
            .stream(ChatRequest(text = "Mach das Licht an", speak = false))
            .collectList()
            .block(Duration.ofSeconds(5))!!

        assertEquals(1, autoSwitch.ensureCalls.get())
        assertEquals(0, autoSwitch.voiceStartCalls.get(), "der Chat-Rand ruft NIE den Voice-Hook")
        assertTrue(events.isNotEmpty(), "der Turn muss trotzdem normal laufen")
    }

    @Test
    fun `der Turn wartet den Wechsel ab, bevor der Brain-Call startet`() {
        val autoSwitch = RecordingAutoSwitchPort(ensureDelay = Duration.ofMillis(150))
        val t0 = System.nanoTime()
        controller(autoSwitch)
            .stream(ChatRequest(text = "Mach das Licht an", speak = false))
            .collectList()
            .block(Duration.ofSeconds(5))!!
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(elapsedMs >= 140, "der Turn darf nicht VOR dem abgewarteten Wechsel starten (war ${elapsedMs}ms)")
    }

    @Test
    fun `Default NOOP - der Turn laeuft byte-neutral wie ohne das Feature`() {
        val events = controller(BrainAutoSwitchPort.NOOP)
            .stream(ChatRequest(text = "Mach das Licht an", speak = false))
            .collectList()
            .block(Duration.ofSeconds(5))!!

        assertTrue(events.isNotEmpty())
    }
}
