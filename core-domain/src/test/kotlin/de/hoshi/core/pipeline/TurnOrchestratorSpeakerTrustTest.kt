package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.WorkingSessionPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **P1-Privacy: SpeakerTrust an der WorkingSession-RECALL-Naht** (`TurnOrchestrator.
 * effectiveSession` → `WorkingSessionPort.readSegment`) — schließt die vom Bau-Pod ehrlich
 * gemeldete Restlücke des P1-Fixes (Commit `bc64190`, [SpeakerTrust]): Entity-/Episodic-
 * Recall ([TurnPromptAssemblerSpeakerTrustTest]) und der Write-Pfad
 * ([de.hoshi.web.ChatStreamSpeakerTrustTest]) laufen bereits durch [SpeakerTrust.resolve];
 * die WorkingSession-Rekonstruktion (räumliches Gedächtnis S1) las bislang UNGEGATET die
 * bloß behauptete `ctx.speaker?.speakerId`. Beweist dieselben vier Szenarien wie die
 * Geschwister-Tests, mit DERSELBEN Trust-Funktion + Gast-Kollaps-Semantik:
 *
 *  (a) enforced=OFF ⇒ die behauptete Id wird ungeprüft genutzt, Score egal (byte-neutral —
 *      [TurnOrchestratorWorkingSessionTest] bleibt unverändert grün).
 *  (b) enforced=ON + Score unter Schwelle + fremde Id ⇒ Recall unter "gast", NIE unter der
 *      behaupteten Id (kein Cross-User-Leak über die Working-Session).
 *  (c) enforced=ON + Score über Schwelle ⇒ Recall unter der verifizierten Id.
 *  (d) enforced=ON + kein speakerContext ⇒ kein Crash — [SpeakerTrust.resolve] liefert
 *      Gast statt eines Wurfs (never-throw).
 */
class TurnOrchestratorSpeakerTrustTest {

    private class FakeBrain(private val line: String = "Antwort.") : BrainPort {
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

    /** Zählender Fake-Session-Port — hält die zuletzt angefragte speakerId fest (Muster [TurnOrchestratorWorkingSessionTest]). */
    private class FakeWorkingSession(private val turns: List<ChatMessage>) : WorkingSessionPort {
        val reads = AtomicInteger(0)
        val lastSpeaker = AtomicReference<String?>(null)
        override fun recentTurns(speakerId: String): List<ChatMessage> {
            reads.incrementAndGet()
            lastSpeaker.set(speakerId)
            return turns
        }
    }

    private val sessionTurns = listOf(
        ChatMessage("user", "Wie hoch ist der Tokyo Skytree?"),
        ChatMessage("assistant", "634 Meter."),
    )

    private fun orchestrator(
        session: WorkingSessionPort,
        speakerTrustEnforced: Boolean,
        speakerTrustThreshold: Double = 0.45,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter { RouteDecision(RouteCategory.SMALLTALK, RouteProvider.LOCAL, "fake") },
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
            brain = FakeBrain(),
            workingSession = session,
            speakerTrustEnforced = speakerTrustEnforced,
            speakerTrustThreshold = speakerTrustThreshold,
        )
    }

    private fun run(o: TurnOrchestrator, speakerContext: SpeakerContext?) {
        o.handle(ChatRequest(text = "Und wie hoch ist ER?", speak = false, speakerContext = speakerContext, language = Language.DE))
            .collectList()
            .block(Duration.ofSeconds(5))
    }

    // ── (a) enforced=OFF: byte-neutraler Pass-Through, Score egal ────────────

    @Test
    fun `enforced OFF recallt WorkingSession unter dem behaupteten Claim, egal welcher Score`() {
        val session = FakeWorkingSession(sessionTurns)

        run(
            orchestrator(session, speakerTrustEnforced = false),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0),
        )

        assertEquals("andi", session.lastSpeaker.get(), "OFF: der rohe Claim wird ungeprüft genutzt, Score egal")
        assertEquals(1, session.reads.get())
    }

    @Test
    fun `enforced OFF ohne speakerContext liest die Session gar nicht (byte-neutraler Kurzschluss)`() {
        val session = FakeWorkingSession(sessionTurns)

        run(orchestrator(session, speakerTrustEnforced = false), null)

        assertEquals(0, session.reads.get(), "ohne Claim + OFF: exakt der bisherige Kurzschluss, kein Load")
    }

    // ── (b) enforced=ON + Score unter Schwelle + fremde Id ⇒ Gast ────────────

    @Test
    fun `enforced ON mit Score unter Schwelle recallt NIE unter der fremden Id`() {
        val session = FakeWorkingSession(sessionTurns)

        run(
            orchestrator(session, speakerTrustEnforced = true, speakerTrustThreshold = 0.45),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.20),
        )

        assertEquals("gast", session.lastSpeaker.get(), "ON + Score < Schwelle: KEIN Cross-User-Recall unter 'andi'")
    }

    // ── (c) enforced=ON + Score über Schwelle ⇒ verifizierte Id ───────────────

    @Test
    fun `enforced ON mit Score ueber Schwelle recallt unter der verifizierten Id`() {
        val session = FakeWorkingSession(sessionTurns)

        run(
            orchestrator(session, speakerTrustEnforced = true, speakerTrustThreshold = 0.45),
            SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.90),
        )

        assertEquals("andi", session.lastSpeaker.get(), "ON + Score >= Schwelle: der verifizierte Claim wird genutzt")
    }

    // ── (d) enforced=ON + kein speakerContext ⇒ Gast, kein Crash ──────────────

    @Test
    fun `enforced ON ohne speakerContext faellt auf Gast zurueck und crasht nicht`() {
        val session = FakeWorkingSession(sessionTurns)

        run(orchestrator(session, speakerTrustEnforced = true, speakerTrustThreshold = 0.45), null)

        assertEquals("gast", session.lastSpeaker.get(), "kein Crash: SpeakerTrust liefert Gast statt eines Wurfs")
    }
}
