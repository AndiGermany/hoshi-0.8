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
import de.hoshi.core.port.WorkingSessionWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Working-Session-Schreib-Hook am Rand (räumliches Gedächtnis, S1)** — beweist
 * OHNE Spring-Boot-Context, dass der [ChatStreamController] das Turn-Paar NACH
 * `onComplete` GENAU EINMAL an den [WorkingSessionWriter] hängt (neben den zwei
 * bestehenden Writes, kein zweiter Brain-Call):
 *
 *  - Mit Speaker-Kontext: append(speakerId, request.text, gesammelte Antwort).
 *  - OHNE Speaker-Kontext: kein append (rememberAfter umhüllt gar nicht erst).
 *  - Default [WorkingSessionWriter.NOOP] = Flag-OFF-Pfad (andere Tests bauen den
 *    Controller ohne das Argument — byte-neutral per Konstruktion).
 */
class ChatStreamWorkingSessionTest {

    private class FakeBrainPort(private val line: String = "Der Skytree ist 634 Meter hoch.") : BrainPort {
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

    /** Recording-Writer: hält den EINEN append fest und signalisiert den Abschluss. */
    private class RecordingWriter : WorkingSessionWriter {
        val appended = AtomicReference<Triple<String, String, String>?>(null)
        val done = CountDownLatch(1)
        override fun append(speakerId: String, userText: String, answer: String) {
            appended.set(Triple(speakerId, userText, answer))
            done.countDown()
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
                entityMemory = { null },
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
        )
    }

    private fun controller(writer: WorkingSessionWriter) = ChatStreamController(
        orchestrator = orchestrator(),
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        memoryWriter = EntityMemoryWriter.NOOP,
        episodicWriter = EpisodicWriter.NOOP,
        workingSessionWriter = writer,
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
    )

    @Test
    fun `nach onComplete wird das turn-paar speakerId-gekeyt an die session gehaengt`() {
        val writer = RecordingWriter()
        controller(writer)
            .stream(
                ChatRequest(
                    text = "Wie hoch ist der Tokyo Skytree?",
                    speak = false,
                    speakerContext = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 1.0),
                ),
            )
            .collectList()
            .block(Duration.ofSeconds(5))!!

        // doOnComplete feuert ggf. asynchron — deterministisch auf append warten.
        assertTrue(writer.done.await(5, TimeUnit.SECONDS), "append muss nach onComplete kommen")
        val (speakerId, userText, answer) = writer.appended.get()!!
        assertEquals("andi", speakerId, "Schlüssel ist die speakerId")
        assertEquals("Wie hoch ist der Tokyo Skytree?", userText)
        assertEquals("Der Skytree ist 634 Meter hoch.", answer, "die GESAMMELTE Antwort, kein zweiter Brain-Call")
    }

    @Test
    fun `ohne speaker-kontext wird die session nie beschrieben`() {
        val writer = RecordingWriter()
        controller(writer)
            .stream(ChatRequest(text = "Wie hoch ist der Tokyo Skytree?", speak = false))
            .collectList()
            .block(Duration.ofSeconds(5))!!

        // Kein Latch-Warten nötig: rememberAfter umhüllt ohne Speaker gar nicht erst.
        assertEquals(null, writer.appended.get(), "ohne speakerId kein append")
    }
}
