package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
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
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Voice-Diary-Ehrlichkeit (2026-07-05)** — der Befund: Voice-Turns über
 * `POST /api/v1/voice` landeten GAR NICHT im Turn-Diary (der Datenbasis des
 * STRICT-Entscheids), Text-Turns schon. Dieser Test beweist den Fix OHNE
 * Spring-Boot-Context, exakt im Muster von [ChatStreamDiaryGroundingTest]:
 * ein echter Turn (realer [TurnOrchestrator], Fake-STT/-Brain) durch den echten
 * [VoiceInboundController] mit dem geteilten [TurnDiaryTap]:
 *
 *  1. Voice-Turn ⇒ GENAU EINE Trace mit denselben ehrlichen Feldern wie am
 *     Chat-Rand (category/provider/groundingUsed/deflected/error) PLUS
 *     `source="voice"` — der Turn ist im Diary als Sprach-Turn erkennbar.
 *  2. Leeres Transkript (`no_input`) ⇒ bewusste Entscheidung: JA, auch der
 *     stumme Turn bekommt eine Trace (eigene Kategorie [TurnDiaryTap.CATEGORY_NO_INPUT],
 *     `error="STT"`) — stumme Turns sind Betriebs-Wahrheit; ohne sie rechnete
 *     sich die Voice-Verlässlichkeit im Diary schön. Der SSE-Strom selbst
 *     bleibt unverändert (dieselbe warme Never-Silent-Absage wie heute).
 *  3. NOOP-Default (kein Wiring) ⇒ der Event-Strom ist Event-für-Event identisch
 *     zum getracten Lauf und es wird NIE geschrieben — byte-neutral, OFF = heute.
 *  4. Audio-Cap-Frühabbruch (2026-07-05, die bewusst offene Lücke) ⇒ eigene
 *     Kategorie [TurnDiaryTap.CATEGORY_ABORTED] + Grund
 *     [TurnDiaryTap.ABORT_AUDIO_CAP] im error-Feld, SSE unverändert.
 */
class VoiceInboundDiaryTraceTest {

    // ── Fake-Brain: eine Zeile, kein Netz (identisch zum Chat-Diary-Test) ───────
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

    /** Recording-Diary: zählt ALLE records (beweist „genau eine") + hält die letzte Trace. */
    private class RecordingTrace : TurnTracePort {
        val count = AtomicInteger(0)
        val last = AtomicReference<TurnTrace?>(null)
        val done = CountDownLatch(1)
        override fun record(trace: TurnTrace) {
            count.incrementAndGet()
            last.set(trace)
            done.countDown()
        }
    }

    /** Fake-STT: liefert das gesetzte Transkript (`""` ⇒ no_input-Pfad, kein Brain-Call). */
    private class FakeSttPort(private val transcript: String) : SttPort {
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> =
            Mono.just(transcript)
    }

    /** Realer Orchestrator (FACT_SHORT/LOCAL-Route) mit injizierbarem Grounding-Block. */
    private fun orchestrator(groundBlock: String): TurnOrchestrator {
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
                grounding = { _, _ -> Mono.just(groundBlock) },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
        )
    }

    /** Echter Controller mit Fake-Nähten; [turnTrace] pro Test (Recorder ODER NOOP-Default). */
    private fun controller(
        transcript: String,
        turnTrace: TurnTracePort,
        audioCapEnabled: Boolean = false,
        maxAudioBytes: Int = 1_500_000,
    ) = VoiceInboundController(
        stt = FakeSttPort(transcript),
        orchestrator = orchestrator(
            groundBlock = "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter …\n",
        ),
        ttsStage = TtsStage(tts = TtsPort { _, _ -> Mono.empty() }),
        languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = false),
        personaResolver = PersonaResolver(personaEnabled = false),
        admissionGate = BrainAdmissionGate(enabled = false, maxConcurrent = 1),
        audioCapEnabled = audioCapEnabled,
        maxAudioBytes = maxAudioBytes,
        speakerIdentify = SpeakerIdentifyService.DISABLED,
        turnTrace = turnTrace,
    )

    /** Ein voller Voice-Turn über den Roh-Pfad (`voiceRaw`), Events eingesammelt. */
    private fun runVoiceTurn(
        transcript: String,
        turnTrace: TurnTracePort,
        audioCapEnabled: Boolean = false,
        maxAudioBytes: Int = 1_500_000,
    ): List<ChatEvent> =
        controller(transcript, turnTrace, audioCapEnabled, maxAudioBytes)
            .voiceRaw(
                audio = Mono.just(byteArrayOf(1, 2, 3, 4)),
                language = "DE",
                languagePolicy = null,
                speak = false,
                voice = null,
                persona = null,
                deviceId = null,
            )
            .collectList()
            .block(Duration.ofSeconds(5))!!

    @Test
    fun `voice-turn schreibt genau EINE Trace - source=voice mit den Chat-Feldern`() {
        val recorder = RecordingTrace()
        val events = runVoiceTurn(transcript = "Wie hoch ist der Eiffelturm?", turnTrace = recorder)
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "Diary-Trace muss geschrieben werden")

        // Der Strom selbst bleibt der heutige: Transkript-Step voran, dann der Turn.
        val step = events.filterIsInstance<ChatEvent.Step>().first()
        assertEquals("transcript", step.kind)
        assertEquals("Wie hoch ist der Eiffelturm?", step.message)
        assertTrue(events.filterIsInstance<ChatEvent.Start>().isNotEmpty(), "echter Turn lief")

        assertEquals(1, recorder.count.get(), "GENAU eine Trace pro Voice-Turn")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.SOURCE_VOICE, trace.source, "die Trace muss den Voice-Weg erkennbar machen")
        assertEquals("FACT_SHORT", trace.category, "Routing-Kategorie wie am Chat-Rand aus dem Start-Event")
        assertEquals("LOCAL", trace.provider)
        assertTrue(trace.groundingUsed, "grounded reist ehrlich aus dem Start-Event in die Voice-Trace")
        assertFalse(trace.deflected, "gedeckter Turn ist keine Deflection")
        assertNull(trace.error, "fehlerfreier Turn ⇒ error=null")
        assertEquals("STANDARD", trace.persona)
        assertEquals("DE", trace.language)
        assertFalse(trace.speak, "speak=false des Requests steht in der Trace")
        assertEquals("", trace.chatId, "der Voice-Rand trägt keine chatId — ehrlich leer")
        assertTrue(trace.deltaChars > 0, "die Antwortlänge wurde gemessen (Transkript-Step zählt NICHT)")
    }

    @Test
    fun `leeres Transkript (no_input) - ehrliche Trace mit Kategorie NO_INPUT und error=STT`() {
        // BEWUSSTE ENTSCHEIDUNG (dokumentiert): JA, no_input bekommt eine Trace.
        // Stumme Turns sind Betriebs-Wahrheit — jede unverstandene Aufnahme ist ein
        // realer (fehlgeschlagener) Nutzungsversuch; ohne diese Zeilen würde das
        // Diary die Voice-Verlässlichkeit systematisch überschätzen. Erkennbar an
        // der EIGENEN Kategorie NO_INPUT (kein Start-Event ⇒ keine Routing-Kategorie)
        // statt einer leeren.
        val recorder = RecordingTrace()
        val events = runVoiceTurn(transcript = "", turnTrace = recorder)
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "auch der stumme Turn muss ins Diary")

        // Never-Silent-Verhalten UNVERÄNDERT: genau die eine warme STT-Absage.
        assertEquals(1, events.size)
        val error = events.single() as ChatEvent.Error
        assertEquals(ChatEvent.Stage.STT, error.stage)
        assertEquals("Ich habe leider nichts verstanden — magst du es noch einmal sagen?", error.message)

        assertEquals(1, recorder.count.get(), "GENAU eine Trace auch für no_input")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.CATEGORY_NO_INPUT, trace.category, "eigene Kategorie statt leerer — filterbar")
        assertEquals("STT", trace.error, "die Fehler-Stage der Absage steht ehrlich in der Trace")
        assertEquals(TurnDiaryTap.SOURCE_VOICE, trace.source)
        assertEquals(0, trace.deltaChars, "keine Antwort ⇒ 0 Zeichen")
        assertNull(trace.ttftMs, "nie eine TextDelta gesehen ⇒ ttftMs=null")
        assertFalse(trace.deflected, "no_input ist keine FactCoverage-Deflection")
        assertFalse(trace.groundingUsed)
        assertEquals("DE", trace.language, "ohne Transkript zählt ehrlich nur die ANGEFRAGTE Sprache")
    }

    @Test
    fun `NOOP-Default (kein Wiring) - Event-Strom identisch, nie geschrieben - byte-neutral`() {
        val recorder = RecordingTrace()
        val traced = runVoiceTurn(transcript = "Wie hoch ist der Eiffelturm?", turnTrace = recorder)
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))
        // Default-Konstruktion OHNE turnTrace-Wiring == NOOP == exakt heutiges Verhalten.
        val untraced = runVoiceTurn(transcript = "Wie hoch ist der Eiffelturm?", turnTrace = TurnTracePort.NOOP)

        assertEquals(traced, untraced, "der Tap ist rein passiv: Event-für-Event identischer Strom")
        assertEquals(1, recorder.count.get(), "nur der getracte Lauf schrieb — der NOOP-Lauf nie")
    }

    // ── Cap-Abbruch (2026-07-05): die bewusst offene Diary-Lücke geschlossen ────

    @Test
    fun `audio-cap-abbruch - genau EINE Trace ABORTED mit Grund AUDIO_CAP, SSE unveraendert`() {
        // BEWUSSTE ABGRENZUNG (dokumentiert an TurnDiaryTap.CATEGORY_ABORTED):
        // der Cap-Frühabbruch (VOR dem STT-Call) ist KEIN NO_INPUT — der Guard
        // brach ab, bevor gehört wurde. error trägt den GRUND (AUDIO_CAP), nicht
        // die uniforme Wire-Stage STT.
        val recorder = RecordingTrace()
        // Der 4-Byte-Test-Body reißt den 2-Byte-Cap ⇒ Frühabbruch vor STT/Brain.
        val events = runVoiceTurn(
            transcript = "darf nie transkribiert werden",
            turnTrace = recorder,
            audioCapEnabled = true,
            maxAudioBytes = 2,
        )
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS), "auch der Cap-Abbruch muss ins Diary")

        // Never-Silent-SSE UNVERÄNDERT: genau die eine warme Cap-Absage, kein Turn.
        assertEquals(1, events.size, "genau ein Event — kein STT-Step, kein Brain-Turn")
        val error = events.single() as ChatEvent.Error
        assertEquals(ChatEvent.Stage.STT, error.stage)
        assertEquals(AudioWebSocketHandler.AUDIO_CAP_MESSAGE, error.message)

        assertEquals(1, recorder.count.get(), "GENAU eine Trace pro Cap-Abbruch")
        val trace = recorder.last.get()!!
        assertEquals(TurnDiaryTap.CATEGORY_ABORTED, trace.category, "eigene Kategorie — NICHT NO_INPUT")
        assertEquals(TurnDiaryTap.ABORT_AUDIO_CAP, trace.error, "das error-Feld trägt den Abbruch-GRUND")
        assertEquals(TurnDiaryTap.SOURCE_VOICE, trace.source)
        assertEquals("STANDARD", trace.persona)
        assertEquals("DE", trace.language, "ohne Turn zählt ehrlich nur die ANGEFRAGTE Sprache")
        assertEquals(0, trace.deltaChars, "keine Antwort ⇒ 0 Zeichen")
        assertNull(trace.ttftMs, "nie eine TextDelta gesehen ⇒ ttftMs=null")
        assertFalse(trace.deflected)
        assertEquals("", trace.chatId)
    }

    @Test
    fun `audio-cap-abbruch unter NOOP - SSE identisch, nie geschrieben - byte-neutral`() {
        val recorder = RecordingTrace()
        val traced = runVoiceTurn(
            transcript = "egal",
            turnTrace = recorder,
            audioCapEnabled = true,
            maxAudioBytes = 2,
        )
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS))
        val untraced = runVoiceTurn(
            transcript = "egal",
            turnTrace = TurnTracePort.NOOP,
            audioCapEnabled = true,
            maxAudioBytes = 2,
        )

        assertEquals(traced, untraced, "Cap-Absage Event-für-Event identisch mit und ohne Diary")
        assertEquals(1, recorder.count.get(), "nur der getracte Lauf schrieb — der NOOP-Lauf nie")
    }
}
