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

/**
 * **P1-Privacy: SpeakerTrust an der WRITE-Naht** (`ChatStreamController.rememberAfter`) —
 * beweist OHNE Spring-Boot-Context die vier Auftrags-Szenarien (Muster
 * [ChatStreamWorkingSessionTest], hier zusätzlich mit variablem `speakerTrustEnforced`):
 *  (a) enforced=OFF ⇒ der behauptete Claim wird geschrieben, UNABHÄNGIG vom Score
 *      (byte-neutral — [ChatStreamWorkingSessionTest] bleibt unverändert grün).
 *  (b) enforced=ON + Score unter Schwelle + fremde Id ⇒ Schreiben unter "gast", NIE unter
 *      der behaupteten Id.
 *  (c) enforced=ON + Score über Schwelle ⇒ Schreiben unter der verifizierten Id.
 *  (d) enforced=ON + kein speakerContext ⇒ kein Crash — [SpeakerTrust.resolve] liefert Gast,
 *      die drei Stores werden unter "gast" gerufen (keine Sonderbehandlung nötig, weil die
 *      echten Memory-Adapter Gast-Ids ohnehin nie persistieren).
 */
class ChatStreamSpeakerTrustTest {

    private class FakeBrainPort(private val line: String = "Antwort.") : BrainPort {
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

    /** Ein Recording-Fake für alle drei Store-Hooks zugleich — hält jede benutzte speakerId fest. */
    private class RecordingWriter : EntityMemoryWriter, EpisodicWriter, WorkingSessionWriter {
        val speakerIds = mutableListOf<String>()
        val done = CountDownLatch(3) // Entity + Episodic + WorkingSession

        override fun remember(speakerId: String, turnText: String, answer: String) = record(speakerId)
        override fun record(speakerId: String, userText: String, answer: String) = record(speakerId)
        override fun append(speakerId: String, userText: String, answer: String) = record(speakerId)

        @Synchronized
        private fun record(speakerId: String) {
            speakerIds.add(speakerId)
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

    private fun controller(
        writer: RecordingWriter,
        speakerTrustEnforced: Boolean,
        speakerTrustThreshold: Double = 0.45,
    ) = ChatStreamController(
        orchestrator = orchestrator(),
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        memoryWriter = writer,
        episodicWriter = writer,
        workingSessionWriter = writer,
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
        speakerTrustEnforced = speakerTrustEnforced,
        speakerTrustThreshold = speakerTrustThreshold,
    )

    private fun run(controller: ChatStreamController, speakerContext: SpeakerContext?) {
        controller.stream(ChatRequest(text = "geheime Frage", speak = false, speakerContext = speakerContext))
            .collectList()
            .block(Duration.ofSeconds(5))
    }

    // ── (a) enforced=OFF: byte-neutraler Pass-Through, Score egal ────────────

    @Test
    fun `enforced OFF schreibt unter dem behaupteten Claim, egal welcher Score`() {
        val writer = RecordingWriter()

        run(
            controller(writer, speakerTrustEnforced = false),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0),
        )

        assertTrue(writer.done.await(5, TimeUnit.SECONDS), "alle drei Stores müssen aufgerufen werden")
        assertEquals(listOf("andi", "andi", "andi"), writer.speakerIds)
    }

    @Test
    fun `enforced OFF ohne speakerContext schreibt gar nicht (byte-neutraler Kurzschluss)`() {
        val writer = RecordingWriter()

        run(controller(writer, speakerTrustEnforced = false), null)

        // Kein Latch-Warten: rememberAfter umhüllt ohne Claim gar nicht erst (Muster
        // ChatStreamWorkingSessionTest — dieselbe Garantie, hier zusätzlich bewiesen).
        assertEquals(emptyList<String>(), writer.speakerIds)
    }

    // ── (b) enforced=ON + Score unter Schwelle + fremde Id ⇒ Gast ────────────

    @Test
    fun `enforced ON mit Score unter Schwelle schreibt NIE unter der fremden Id`() {
        val writer = RecordingWriter()

        run(
            controller(writer, speakerTrustEnforced = true, speakerTrustThreshold = 0.45),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.20),
        )

        assertTrue(writer.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("gast", "gast", "gast"), writer.speakerIds)
    }

    // ── (c) enforced=ON + Score über Schwelle ⇒ verifizierte Id ───────────────

    @Test
    fun `enforced ON mit Score ueber Schwelle schreibt unter der verifizierten Id`() {
        val writer = RecordingWriter()

        run(
            controller(writer, speakerTrustEnforced = true, speakerTrustThreshold = 0.45),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.90),
        )

        assertTrue(writer.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("andi", "andi", "andi"), writer.speakerIds)
    }

    // ── (d) enforced=ON + kein speakerContext ⇒ Gast, kein Crash ──────────────

    @Test
    fun `enforced ON ohne speakerContext schreibt unter Gast und crasht nicht`() {
        val writer = RecordingWriter()

        run(controller(writer, speakerTrustEnforced = true, speakerTrustThreshold = 0.45), null)

        assertTrue(writer.done.await(5, TimeUnit.SECONDS), "kein Crash: SpeakerTrust liefert Gast statt eines Wurfs")
        assertEquals(listOf("gast", "gast", "gast"), writer.speakerIds)
    }
}
