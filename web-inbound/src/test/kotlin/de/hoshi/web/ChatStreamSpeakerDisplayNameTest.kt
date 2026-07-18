package de.hoshi.web

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.ExistenceClaimSignal
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * **Die erkannte Person erreicht den Brain-Prompt auch im Text-Chat** — beweist die
 * ganze Kette OHNE Spring-Boot-Context: [ChatStreamController.stream] löst
 * [ChatRequest.speakerContext] über [SpeakerDisplayNameResolver] auf, BEVOR der
 * [TurnOrchestrator] (mit dem ECHTEN [PersonaService]/[TurnPromptAssembler]) daraus
 * das System-Prompt baut. Sichtbares Signal: `PersonaService.systemPromptDe` schreibt
 * `"Du sprichst mit $nameRef."` — [FakeBrainPort] fängt den `systemPrompt`-Parameter ab.
 *
 * Die drei Auftrags-Szenarien:
 *  (a) Flag ON + enrollter Name + DTO-Default-`displayName` ⇒ Prompt nennt den Namen.
 *  (b) Flag OFF ⇒ „Unbekannt" bleibt (kollabiert auf „die Person") — byte-neutral,
 *      obwohl derselbe Store dasselbe Profil hätte.
 *  (c) Flag ON, aber unbekannte `speakerId` ⇒ unverändert („die Person"), kein Raten.
 */
class ChatStreamSpeakerDisplayNameTest {

    /** Fängt den `systemPrompt`-Parameter ab — das einzige Fenster auf den finalen Prompt-Text. */
    private class CapturingBrainPort : BrainPort {
        val capturedSystemPrompt = AtomicReference<String>()

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
            capturedSystemPrompt.set(systemPrompt)
            return Flux.just(LlmDelta("Antwort."))
        }
    }

    private fun orchestrator(brain: CapturingBrainPort): TurnOrchestrator {
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
                entityMemory = { null },
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
        )
    }

    private fun controller(
        brain: CapturingBrainPort,
        resolutionEnabled: Boolean,
        store: SpeakerProfileStore?,
    ) = ChatStreamController(
        orchestrator = orchestrator(brain),
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        memoryWriter = EntityMemoryWriter.NOOP,
        episodicWriter = EpisodicWriter.NOOP,
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
        speakerDisplayNameResolutionEnabled = resolutionEnabled,
        speakerProfileStoreProvider = SpeakerDisplayNameResolver.providerOf(store),
    )

    private fun run(controller: ChatStreamController, speakerContext: SpeakerContext?) {
        controller.stream(ChatRequest(text = "Was kochen wir heute?", speak = false, speakerContext = speakerContext))
            .collectList()
            .block(Duration.ofSeconds(5))
    }

    @Test
    fun `Flag ON + enrollter Name + Default-displayName - der Brain-Prompt nennt die Person`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        store.upsert("andi", floatArrayOf(0.1f, 0.2f, 0.3f))
        val brain = CapturingBrainPort()

        run(
            controller(brain, resolutionEnabled = true, store = store),
            SpeakerContext(speakerId = "andi"), // displayName bleibt DTO-Default "Unbekannt"
        )

        assertTrue(
            brain.capturedSystemPrompt.get().contains("Du sprichst mit Andi."),
            "der aufgelöste, groß geschriebene Name muss im System-Prompt stehen: ${brain.capturedSystemPrompt.get()}",
        )
    }

    @Test
    fun `Flag OFF - Unbekannt bleibt, obwohl derselbe Store dasselbe Profil haette`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        store.upsert("andi", floatArrayOf(0.1f, 0.2f, 0.3f))
        val brain = CapturingBrainPort()

        run(
            controller(brain, resolutionEnabled = false, store = store),
            SpeakerContext(speakerId = "andi"),
        )

        assertTrue(
            brain.capturedSystemPrompt.get().contains("Du sprichst mit die Person."),
            "Flag OFF ⇒ byte-neutral, der Default 'Unbekannt' kollabiert unveraendert: ${brain.capturedSystemPrompt.get()}",
        )
    }

    @Test
    fun `Flag ON + unbekannte speakerId - unveraendert, kein Raten`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        store.upsert("andi", floatArrayOf(0.1f, 0.2f, 0.3f))
        val brain = CapturingBrainPort()

        run(
            controller(brain, resolutionEnabled = true, store = store),
            SpeakerContext(speakerId = "gast"), // nicht enrollt
        )

        assertTrue(
            brain.capturedSystemPrompt.get().contains("Du sprichst mit die Person."),
            "unbekannte speakerId darf NIE einen Namen erfinden: ${brain.capturedSystemPrompt.get()}",
        )
    }
}
