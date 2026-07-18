package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
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
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Stage-Metriken end-to-end ins Diary (Perf-Diary)** — beweist die ganze Kette
 * OHNE Spring-Boot-Context (Muster [VoiceInboundDiaryTraceTest]):
 *
 *  1. **Tap-Durchreichung** (pur): [TurnDiaryTap.traced] liest das additive
 *     [ChatEvent.Done.stageTimings] plus den sttMs-Parameter in die [TurnTrace];
 *     ohne beides bleiben ALLE fünf Felder ehrlich null.
 *  2. **Voice-Turn** (echter Controller, verzögerter Fake-STT): `sttMs > 0`;
 *     `speak=false` ⇒ `ttsFirstAudioMs=null` (keine TtsStage — nie ein 0).
 *  3. **Voice-Turn mit speak=true** (Fake-TTS + Fake-Uhr): `ttsFirstAudioMs`
 *     gepinnt; Admission-Gate AN (Fake-Uhr) ⇒ `admissionWaitMs` gepinnt.
 *  4. **Text-Turn** (echter Chat-Controller): `sttMs=null` — ein Text-Turn hat
 *     kein STT; Brain-/Grounding-Messung läuft trotzdem.
 */
class StageTimingsDiaryTraceTest {

    private class FakeNano(vararg ticks: Long) : () -> Long {
        private val queue = ArrayDeque(ticks.toList())
        private var last = ticks.last()
        override fun invoke(): Long = queue.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class FakeBrainPort(private val line: String = "Der Eiffelturm ist 330 Meter hoch.") : BrainPort {
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

    private class RecordingTrace : TurnTracePort {
        val last = AtomicReference<TurnTrace?>(null)
        val done = CountDownLatch(1)
        override fun record(trace: TurnTrace) {
            last.set(trace)
            done.countDown()
        }
    }

    /** Verzögerter Fake-STT: der Turn „hört“ messbar lange (⇒ sttMs strikt > 0). */
    private class DelayedSttPort(private val transcript: String, private val delayMs: Long = 15) : SttPort {
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> =
            Mono.delay(Duration.ofMillis(delayMs)).thenReturn(transcript)
    }

    private fun orchestrator(): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fake")
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
                grounding = { _, _ -> Mono.just("\n\nHINTERGRUND: 330 Meter.") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
        )
    }

    // ── 1) Tap-Durchreichung (pur, gepinnt) ─────────────────────────────────────

    @Test
    fun `tap liest Done-stageTimings plus sttMs-parameter in die trace`() {
        val recorder = RecordingTrace()
        TurnDiaryTap.traced(
            turnTrace = recorder,
            stream = Flux.just(
                ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
                ChatEvent.TextDelta("hi", provider = "LOCAL"),
                ChatEvent.Done(
                    provider = "LOCAL",
                    stageTimings = ChatEvent.StageTimings(
                        groundingMs = 42,
                        brainTtftMs = 250,
                        ttsFirstAudioMs = 350,
                        admissionWaitMs = 0,
                        answerEntropy = 1.25,
                    ),
                ),
            ),
            source = TurnDiaryTap.SOURCE_VOICE,
            chatId = "",
            persona = "STANDARD",
            language = "DE",
            speak = true,
            sttMs = 300,
        ).collectList().block(Duration.ofSeconds(5))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))

        val trace = recorder.last.get()!!
        assertEquals(300L, trace.sttMs)
        assertEquals(42L, trace.groundingMs)
        assertEquals(250L, trace.brainTtftMs)
        assertEquals(350L, trace.ttsFirstAudioMs)
        assertEquals(0L, trace.admissionWaitMs, "ein GEMESSENES 0 (tryAcquire sofort) ist erlaubt")
        assertEquals(1.25, trace.answerEntropy, "Antwort-Entropie (S1) reist am selben Done ins Diary")
    }

    @Test
    fun `ohne Done-timings und ohne sttMs bleiben alle fuenf felder ehrlich null`() {
        val recorder = RecordingTrace()
        TurnDiaryTap.traced(
            turnTrace = recorder,
            stream = Flux.just(
                ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "policy"),
                ChatEvent.TextDelta("hi", provider = "LOCAL"),
                ChatEvent.Done(provider = "LOCAL"),
            ),
            source = TurnDiaryTap.SOURCE_CHAT,
            chatId = "",
            persona = "STANDARD",
            language = "DE",
            speak = false,
        ).collectList().block(Duration.ofSeconds(5))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))

        val trace = recorder.last.get()!!
        assertNull(trace.sttMs)
        assertNull(trace.groundingMs)
        assertNull(trace.brainTtftMs)
        assertNull(trace.ttsFirstAudioMs)
        assertNull(trace.admissionWaitMs)
        assertNull(trace.answerEntropy, "nichts gemessen ⇒ ehrlich null (Server ohne logprobs / Flag OFF)")
    }

    // ── 2+3) Voice-Rand end-to-end ──────────────────────────────────────────────

    private fun voiceController(
        recorder: RecordingTrace,
        ttsStage: TtsStage,
        admissionGate: BrainAdmissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
    ) = VoiceInboundController(
        stt = DelayedSttPort("Wie hoch ist der Eiffelturm?"),
        orchestrator = orchestrator(),
        ttsStage = ttsStage,
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        admissionGate = admissionGate,
        audioCapEnabled = false,
        maxAudioBytes = 1_500_000,
        speakerIdentify = SpeakerIdentifyService.DISABLED,
        turnTrace = recorder,
    )

    private fun runVoice(recorder: RecordingTrace, controller: VoiceInboundController, speak: Boolean): TurnTrace {
        controller.voiceRaw(
            audio = Mono.just(byteArrayOf(1, 2, 3, 4)),
            language = "DE",
            languagePolicy = null,
            speak = speak,
            voice = null,
            persona = null,
            deviceId = null,
        ).collectList().block(Duration.ofSeconds(5))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "Diary-Trace muss geschrieben werden")
        return recorder.last.get()!!
    }

    @Test
    fun `voice-turn - sttMs ist gemessen und strikt groesser 0, speak=false laesst tts ehrlich null`() {
        val recorder = RecordingTrace()
        val trace = runVoice(
            recorder,
            voiceController(recorder, ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() })),
            speak = false,
        )
        assertNotNull(trace.sttMs, "der Voice-Rand misst um SttPort.transcribe")
        assertTrue(trace.sttMs!! > 0, "der verzögerte Fake-STT (15ms) macht die Messung strikt positiv")
        assertNull(trace.ttsFirstAudioMs, "speak=false ⇒ keine TtsStage ⇒ null (NIE 0 erfinden)")
        assertNotNull(trace.brainTtftMs, "der Brain-Pfad lief ⇒ TTFT gemessen")
        assertNotNull(trace.groundingMs, "das Grounding lief ⇒ Dauer gemessen")
        assertNull(trace.admissionWaitMs, "Gate OFF ⇒ null")
    }

    @Test
    fun `voice-turn mit speak=true und aktivem gate - tts und admission reisen bis ins diary`() {
        val recorder = RecordingTrace()
        val trace = runVoice(
            recorder,
            voiceController(
                recorder,
                // Fake-Uhr der TtsStage: Subscribe=1000 · erster Chunk=1350 ⇒ gepinnt 350.
                ttsStage = TtsStage(
                    tts = TtsPort { _, _ -> Mono.just(byteArrayOf(1, 2, 3)) },
                    clockMs = FakeNano(1000L, 1350L, 1500L),
                ),
                // Fake-Uhr des Gates: Eintritt=0 · Permit=3ms ⇒ gepinnt 3.
                admissionGate = BrainAdmissionGate(enabled = true, maxConcurrent = 1, nanoTime = FakeNano(0L, 3_000_000L)),
            ),
            speak = true,
        )
        assertEquals(350L, trace.ttsFirstAudioMs, "TtsStage-Start → erster AudioChunk, aus der Fake-Uhr")
        assertEquals(3L, trace.admissionWaitMs, "Gate-Eintritt → Permit, aus der Fake-Uhr")
        assertTrue(trace.sttMs!! > 0)
    }

    // ── 4) Text-Turn am Chat-Rand: sttMs bleibt ehrlich null ────────────────────

    @Test
    fun `text-turn - sttMs null (kein stt), brain- und grounding-messung laufen trotzdem`() {
        val recorder = RecordingTrace()
        val controller = ChatStreamController(
            orchestrator = orchestrator(),
            ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
            languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
            personaResolver = PersonaResolver(personaEnabled = false),
            memoryWriter = EntityMemoryWriter.NOOP,
            episodicWriter = EpisodicWriter.NOOP,
            admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
            turnTrace = recorder,
        )
        controller.stream(ChatRequest(text = "Wie hoch ist der Eiffelturm?", speak = false))
            .collectList().block(Duration.ofSeconds(5))
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))

        val trace = recorder.last.get()!!
        assertNull(trace.sttMs, "Text-Turn ⇒ kein STT ⇒ null (NIE 0 erfinden)")
        assertNotNull(trace.brainTtftMs)
        assertNotNull(trace.groundingMs)
        assertNull(trace.ttsFirstAudioMs, "speak=false ⇒ null")
    }
}
