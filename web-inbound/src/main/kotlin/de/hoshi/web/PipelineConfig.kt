package de.hoshi.web

import de.hoshi.adapters.brain.MlxBrainAdapter
import de.hoshi.adapters.brain.MlxScoreAdapter
import de.hoshi.adapters.radio.HaRadioPort
import de.hoshi.adapters.radio.RadioBrowserAdapter
import de.hoshi.adapters.ha.HaAreaCatalogAdapter
import de.hoshi.adapters.ha.HaSceneCatalogAdapter
import de.hoshi.adapters.ha.HaToolPort
import de.hoshi.adapters.knowledge.CompositeGroundingPort
import de.hoshi.adapters.knowledge.Fts5GroundingAdapter
import de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider
import de.hoshi.adapters.knowledge.OpenMeteoGeocodingClient
import de.hoshi.adapters.knowledge.WeatherGroundingProvider
import de.hoshi.adapters.knowledge.WeatherLocationAskAdapter
import de.hoshi.adapters.escalation.EscalationModelCatalog
import de.hoshi.adapters.escalation.EscalationSpendStore
import de.hoshi.adapters.escalation.FileBackedEscalationSpendStore
import de.hoshi.adapters.escalation.OpenAiEscalationAdapter
import de.hoshi.adapters.memory.EntityMemoryAdapter
import de.hoshi.adapters.memory.EpisodicMemoryAdapter
import de.hoshi.adapters.memory.WorkingSessionAdapter
import de.hoshi.adapters.routing.EmbeddingRouterRefiner
import de.hoshi.adapters.routing.OllamaRouteEmbedder
import de.hoshi.adapters.speaker.CamppSpeakerAdapter
import de.hoshi.adapters.stt.WhisperSttAdapter
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.adapters.tts.PiperTtsAdapter
import de.hoshi.adapters.tts.SayTtsAdapter
import de.hoshi.adapters.tts.VoxtralTtsAdapter
import de.hoshi.adapters.tts.LoudnessNormalizingTtsPort
import de.hoshi.adapters.tts.TtsLoudnessNormalizer
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.ListPort
import de.hoshi.core.port.LookupReplayPort
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.TurnTracePort
import de.hoshi.adapters.supervision.JsonlDailyNoteAdapter
import de.hoshi.adapters.supervision.JsonlWorkshopNoteAdapter
import de.hoshi.adapters.supervision.JsonlLookupNoteAdapter
import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import de.hoshi.core.pipeline.TimerFastpath
import de.hoshi.core.pipeline.ListFastpath
import de.hoshi.core.pipeline.CalcFastpath
import de.hoshi.core.pipeline.DateFastpath
import de.hoshi.core.pipeline.FactCoverageGate
import de.hoshi.core.pipeline.SlopKillStage
import de.hoshi.web.stub.HonestyProbeAdapters
import java.time.Clock
import java.time.ZoneId
import de.hoshi.core.pipeline.AmbientMood
import de.hoshi.core.pipeline.AmbientWarmthPort
import de.hoshi.core.pipeline.ClockPort
import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.EpisodicRecallPort
import de.hoshi.core.pipeline.DailyNoteFastpath
import de.hoshi.core.pipeline.ProbeFastpath
import de.hoshi.core.pipeline.WorkshopNoteFastpath
import de.hoshi.core.pipeline.EscalationMode
import de.hoshi.core.pipeline.EscalationModeFastpath
import de.hoshi.core.pipeline.InMemoryPendingLookupStore
import de.hoshi.core.pipeline.InMemoryPendingLocationQuestionStore
import de.hoshi.core.pipeline.PendingLocationQuestionPort
import de.hoshi.core.pipeline.PendingLookupPort
import de.hoshi.core.pipeline.WeatherLocationAskPort
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.HeuristicLanguageDetector
import de.hoshi.core.pipeline.HonestyGate
import de.hoshi.core.pipeline.InMemoryLastAreaStore
import de.hoshi.core.pipeline.LanguageDetector
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.LastAreaPort
import de.hoshi.core.pipeline.MoodTemperaturePort
import de.hoshi.core.pipeline.OnlineRequestDetector
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.ResponseFormatter
import de.hoshi.core.pipeline.DeterministicToolIntentClassifier
import de.hoshi.core.pipeline.RouteRefiner
import de.hoshi.core.pipeline.RoutingPolicy
import de.hoshi.core.pipeline.ToolIntentClassifier
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.WarmthMood
import de.hoshi.core.pipeline.RadioFastpath
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.pipeline.TurnPromptAssembler
import de.hoshi.core.pipeline.WeakDomainDetector
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.CapabilityPort
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.DailyNotePort
import de.hoshi.core.port.WorkshopNotePort
import de.hoshi.core.port.LookupNotePort
import reactor.core.publisher.Mono
import de.hoshi.core.port.EscalationPort
import de.hoshi.kernel.EgressPort
import de.hoshi.core.port.WorkingSessionPort
import de.hoshi.core.port.WorkingSessionWriter
import de.hoshi.core.port.SpeakerEmbedPort
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.ToolPort
import de.hoshi.core.port.TtsPort
import de.hoshi.core.port.TtsSanitizePort
import de.hoshi.core.skills.SkillRegistry
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.AgenticToolRegistry
import de.hoshi.kernel.CapabilityKernel
import de.hoshi.kernel.KernelCapabilityAdapter
import de.hoshi.web.routing.KeywordRouterImpl
import de.hoshi.web.stub.EntityContextStubAdapter
import de.hoshi.web.stub.EpisodicRecallStubAdapter
import de.hoshi.web.stub.ExistenceClaimStubAdapter
import de.hoshi.web.stub.GroundingStubAdapter
import de.hoshi.web.stub.NamedEntityStubAdapter
import de.hoshi.web.stub.PassthroughRefinerStubAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **PipelineConfig** — das HEXAGON-Wiring der Voice-Pipeline (M2c). Hier, im
 * Inbound-Adapter, werden der echte [MlxBrainAdapter] (e4b :8041), die portierten
 * Policies und die M2c-Stub-Adapter zum [TurnOrchestrator] verdrahtet. Der Kern
 * (`:core-domain`) bleibt Spring-frei — die Konstruktion lebt bewusst HIER.
 *
 * Stubs (ersetzt durch reiche Adapter in M4): Keyword-Heuristik statt vollem
 * Router, Passthrough-Refiner, leeres Grounding/Entity-Memory, konservative
 * Existence/Named-Entity-Signale, kein Episodic-Recall.
 */
@Configuration
class PipelineConfig {

    /**
     * Multi-User-Gedächtnis (0.8, Andis #1-Wunsch) — **flag-gated, default OFF**.
     *
     * EIN gemeinsamer [EntityMemoryAdapter] (sqlite, speakerId-keyed) bedient
     * BEIDE Nähte: Recall ([EntityContextPort]) im Prompt-Assembler UND den
     * Store-Hook ([EntityMemoryWriter]) nach der Antwort — sie müssen denselben
     * Store treffen, darum memoisiert (ein Instanz pro App).
     *
     * `HOSHI_MEMORY_ENABLED=false` (Default) → der verhaltens-neutrale Stub
     * (Recall=null) + [EntityMemoryWriter.NOOP] (kein Store): das bestehende
     * Verhalten (`hoshi turn`) ändert sich NICHT. Privacy: Andi schaltet später.
     */
    @Volatile private var sharedMemory: EntityMemoryAdapter? = null

    @Synchronized
    private fun memoryAdapter(enabled: Boolean, dbPath: String): EntityMemoryAdapter? {
        if (!enabled) return null
        return sharedMemory ?: (
            if (dbPath.isBlank()) EntityMemoryAdapter() else EntityMemoryAdapter(dbPath)
        ).also { sharedMemory = it }
    }

    @Bean
    fun entityContextPort(
        @Value("\${HOSHI_MEMORY_ENABLED:false}") memoryEnabled: Boolean,
        @Value("\${hoshi.memory.db-path:}") dbPath: String,
    ): EntityContextPort =
        memoryAdapter(memoryEnabled, dbPath) ?: EntityContextStubAdapter()

    @Bean
    fun entityMemoryWriter(
        @Value("\${HOSHI_MEMORY_ENABLED:false}") memoryEnabled: Boolean,
        @Value("\${hoshi.memory.db-path:}") dbPath: String,
    ): EntityMemoryWriter =
        memoryAdapter(memoryEnabled, dbPath) ?: EntityMemoryWriter.NOOP

    /**
     * Episodisches Gedächtnis (0.8) — **flag-gated, default OFF** (Privacy).
     * Ergänzt das Entity-Gedächtnis (Fakten) um GESPRÄCHSKONTEXT: Hoshi recallt die
     * semantisch ähnlichsten früheren Turns des Sprechers (embeddinggemma, sqlite).
     *
     * `HOSHI_EPISODIC_ENABLED=false` (Default) → der verhaltens-neutrale
     * [EpisodicRecallStubAdapter] (Recall=`""`): `hoshi turn` ändert sich NICHT —
     * der Assembler schichtet keinen Episodic-Block. Ein eigener Store unter
     * `hoshi.memory.episodic-db-path` (Default `~/.hoshi/episodic-memory.db`),
     * getrennt vom Entity-Store. Andi schaltet später per Hörprobe.
     *
     * **Embed-Sidecar konfigurierbar** (`hoshi.memory.episodic-embed-url`, Env
     * `HOSHI_EPISODIC_EMBED_URL`, Default `http://localhost:11434`): auf ct-106 lebt
     * embeddinggemma auf dem Mac im LAN, nicht lokal — analog zu `hoshi.brain.base-url`/
     * `hoshi.knowledge.bridge.base-url`. Default unverändert ⇒ byte-neutral.
     */
    @Volatile private var sharedEpisodic: EpisodicMemoryAdapter? = null

    @Synchronized
    private fun episodicAdapter(enabled: Boolean, dbPath: String, embedUrl: String, sensitiveFilter: Boolean): EpisodicMemoryAdapter? {
        if (!enabled) return null
        return sharedEpisodic ?: (
            if (dbPath.isBlank()) EpisodicMemoryAdapter(embedUrl = embedUrl, sensitiveFilterEnabled = sensitiveFilter)
            else EpisodicMemoryAdapter(dbPath = dbPath, embedUrl = embedUrl, sensitiveFilterEnabled = sensitiveFilter)
        ).also { sharedEpisodic = it }
    }

    @Bean
    fun episodicRecallPort(
        @Value("\${HOSHI_EPISODIC_ENABLED:false}") episodicEnabled: Boolean,
        @Value("\${hoshi.memory.episodic-db-path:}") dbPath: String,
        @Value("\${hoshi.memory.episodic-embed-url:\${HOSHI_EPISODIC_EMBED_URL:http://localhost:11434}}") embedUrl: String,
        // Sensitive-Marker-Gate vor Episodic-Persist (0.5-Port) — default OFF, byte-neutral.
        @Value("\${HOSHI_EPISODIC_SENSITIVE_FILTER_ENABLED:false}") sensitiveFilter: Boolean,
    ): EpisodicRecallPort =
        episodicAdapter(episodicEnabled, dbPath, embedUrl, sensitiveFilter) ?: EpisodicRecallStubAdapter()

    /**
     * Store-Hook des episodischen Gedächtnisses — teilt sich DIESELBE memoisierte
     * [EpisodicMemoryAdapter]-Instanz mit [episodicRecallPort] (Recall+Store treffen
     * denselben sqlite-Store, speakerId-keyed). `HOSHI_EPISODIC_ENABLED=false`
     * (Default) → [EpisodicWriter.NOOP]: kein Store, `hoshi turn` unverändert.
     * Eigener Qualifier, damit der EpisodicMemoryAdapter (erfüllt BEIDE Ports)
     * nicht mehrdeutig wird — analog zu entityContextPort/entityMemoryWriter.
     */
    @Bean
    fun episodicWriter(
        @Value("\${HOSHI_EPISODIC_ENABLED:false}") episodicEnabled: Boolean,
        @Value("\${hoshi.memory.episodic-db-path:}") dbPath: String,
        @Value("\${hoshi.memory.episodic-embed-url:\${HOSHI_EPISODIC_EMBED_URL:http://localhost:11434}}") embedUrl: String,
        @Value("\${HOSHI_EPISODIC_SENSITIVE_FILTER_ENABLED:false}") sensitiveFilter: Boolean,
    ): EpisodicWriter =
        episodicAdapter(episodicEnabled, dbPath, embedUrl, sensitiveFilter) ?: EpisodicWriter.NOOP

    /**
     * Working-Session (räumliches Gedächtnis S1+S2) — flag-gated, default OFF
     * (byte-neutral). Recall+Writer teilen sich DIESELBE memoisierte Instanz
     * (Muster [episodicAdapter]): sonst schriebe der Hook in einen anderen Store,
     * als der Orchestrator liest. S2 (Themen-Segment: Zeit-Lücke + Reset-Phrasen)
     * wirkt nur, wenn S1 an ist. Schwellen-Startwerte = HYPOTHESE, S4 misst.
     */
    @Volatile private var sharedWorkingSession: WorkingSessionAdapter? = null

    @Synchronized
    private fun workingSessionAdapter(enabled: Boolean, capTurns: Int, topicSegment: Boolean, timeGapMinutes: Long): WorkingSessionAdapter? {
        if (!enabled) return null
        return sharedWorkingSession ?: WorkingSessionAdapter(
            capTurns = capTurns,
            topicSegmentEnabled = topicSegment,
            timeGapMinutes = timeGapMinutes,
        ).also { sharedWorkingSession = it }
    }

    @Bean
    fun workingSessionPort(
        @Value("\${HOSHI_WORKING_SESSION_ENABLED:false}") workingSessionEnabled: Boolean,
        @Value("\${hoshi.session.working.cap-turns:12}") capTurns: Int,
        @Value("\${HOSHI_SESSION_TOPIC_SEGMENT_ENABLED:false}") topicSegment: Boolean,
        @Value("\${hoshi.session.topic.time-gap-minutes:30}") timeGapMinutes: Long,
    ): WorkingSessionPort =
        workingSessionAdapter(workingSessionEnabled, capTurns, topicSegment, timeGapMinutes) ?: WorkingSessionPort.NONE

    @Bean
    fun workingSessionWriter(
        @Value("\${HOSHI_WORKING_SESSION_ENABLED:false}") workingSessionEnabled: Boolean,
        @Value("\${hoshi.session.working.cap-turns:12}") capTurns: Int,
        @Value("\${HOSHI_SESSION_TOPIC_SEGMENT_ENABLED:false}") topicSegment: Boolean,
        @Value("\${hoshi.session.topic.time-gap-minutes:30}") timeGapMinutes: Long,
    ): WorkingSessionWriter =
        workingSessionAdapter(workingSessionEnabled, capTurns, topicSegment, timeGapMinutes) ?: WorkingSessionWriter.NOOP

    /**
     * Der EINZIGE Brain: live gegen den e4b-Sidecar (gemma-4-e2b/e4b, :8041).
     * **D1-Sampling-Hebel — flag-gated, default OFF** (`HOSHI_BRAIN_SAMPLING_ENABLED`):
     * bei ON gehen `min_p`/`presence_penalty` in den Body (belegte Sofort-Hebel gegen
     * Slop/Repetition); bei OFF byte-identischer Body wie heute. Wirkt erst, wenn
     * server_e4b die Felder liest (Folge-Scheibe nach dem Brain-Restart).
     */
    @Bean
    fun brainPort(
        @Value("\${hoshi.brain.base-url:http://localhost:8041}") baseUrl: String,
        @Value("\${HOSHI_BRAIN_SAMPLING_ENABLED:false}") samplingEnabled: Boolean,
        @Value("\${hoshi.brain.min-p:0.08}") minP: Double,
        @Value("\${hoshi.brain.presence-penalty:1.1}") presencePenalty: Double,
        // D1b (Stufe-0-Rest: XTC + Öffner-Bias) — eigener Rollback-Hebel, unabhängig von
        // SAMPLING. Default OFF; Flip erst nach Andis Ohr-Urteil zu D1 (ein Hebel, eine
        // Messung). Braucht D1b-Code resident im Brain — seit Cutover S4 der Repo-Brain
        // (sidecars/brain; die 0.5-Referenz ffe109e ist Historie).
        @Value("\${HOSHI_BRAIN_D1B_ENABLED:false}") d1bEnabled: Boolean,
        @Value("\${hoshi.brain.xtc-probability:0.5}") xtcProbability: Double,
        @Value("\${hoshi.brain.xtc-threshold:0.1}") xtcThreshold: Double,
        @Value("\${hoshi.brain.opener-bias:true}") openerBias: Boolean,
        // Antwort-Entropie S1 (Meta-Signal, default OFF = byte-identisch): bei ON bittet
        // der Adapter den Brain um logprobs + misst mittlere Token-Entropie (messen-only).
        // Der Brain liest das logprobs-Feld seit dem Restart 15.07 (beide Sensoren
        // entsperrt, /v1/score liefert Logprobs) — Flip bleibt ein Mess-Gate.
        @Value("\${HOSHI_ANSWER_ENTROPY_ENABLED:false}") entropyEnabled: Boolean,
    ): BrainPort = MlxBrainAdapter(
        baseUrl = baseUrl,
        samplingEnabled = samplingEnabled,
        minP = minP,
        presencePenalty = presencePenalty,
        d1bEnabled = d1bEnabled,
        entropyEnabled = entropyEnabled,
        xtcProbability = xtcProbability,
        xtcThreshold = xtcThreshold,
        openerBias = openerBias,
    )

    /**
     * Die Audio-Naht — **per Env [HOSHI_TTS] umschaltbar**, Default lokal.
     *
     *  - `HOSHI_TTS=openai` → [OpenAiTtsAdapter] (OpenAI-Cloud-TTS, `/v1/audio/speech`,
     *    Key aus der Env `OPENAI_API_KEY`). Für Tests, wenn der Voxtral-Sidecar
     *    gestoppt ist (RAM sparen). Key wird nie geloggt/konfiguriert — nur die Env.
     *  - `HOSHI_TTS=say` → [SayTtsAdapter] (macOS-`say`-Sidecar, `sidecars/say/`,
     *    Default-Base-URL `http://127.0.0.1:8044`). Dritte, rein lokale Engine ohne
     *    Modell-RAM und ohne Cloud-Egress — Bordmittel-Stimmen.
     *  - `HOSHI_TTS=piper` → [PiperTtsAdapter] (Piper-Sidecar, `sidecars/piper/`,
     *    Default-Base-URL `http://127.0.0.1:8045`). Vierte, CPU-only-lokale Engine
     *    (ONNX/Thorsten medium, GPL-3.0-Runtime hinter HTTP isoliert — s.
     *    `sidecars/piper/README.md`). Default-OFF, `Codex`-Contract 19.07: Andis
     *    Blind-Hörprobe + Lizenz-/Contest-Entscheid stehen noch aus, diese Naht macht
     *    die Engine nur anwählbar, sie flippt keinen Default.
     *  - sonst (Default, unbekannter/leerer Wert) → [VoxtralTtsAdapter] (lokaler
     *    Sidecar :8042) — UNVERÄNDERTE Semantik, weder `say` noch `piper` verschärfen
     *    die Fallback-Regel.
     *
     * Alle vier erfüllen denselben best-effort [TtsPort]: TTS-Fehler killen den
     * Text-Turn NIE (Never-Silent).
     *
     * **Cloud-Egress-Sanitize (Ticket #5, P0-Leak) — flag-gated `HOSHI_TTS_SANITIZE_ENABLED`,
     * default OFF.** Nur der CLOUD-Pfad ([OpenAiTtsAdapter]) verlaesst die Box, darum
     * bekommt NUR er den Sanitizer. Bei OFF (Default) [TtsSanitizePort.IDENTITY] ⇒ der
     * rohe Antworttext geht (wie heute) an OpenAI ⇒ byte-identisch. Bei ON maskiert der
     * [NeverSpeakTtsSanitizer] die Never-Speak-Spans (Token/URL/IP/UUID/HA-Entity-ID) VOR
     * dem Body-Bau, behaelt aber Namen/normalen Inhalt (warmes Audio). Das Scharfschalten
     * schliesst den Live-Leak. [VoxtralTtsAdapter] ist lokal (kein Egress) — unberuehrt.
     */
    @Bean
    fun ttsPort(
        @Value("\${HOSHI_TTS:}") ttsImpl: String,
        @Value("\${hoshi.tts.base-url:http://localhost:8042}") baseUrl: String,
        @Value("\${hoshi.tts.voice:de_female}") voice: String,
        @Value("\${hoshi.tts.openai.model:gpt-4o-mini-tts}") openaiModel: String,
        @Value("\${hoshi.tts.openai.voice:coral}") openaiVoice: String,
        // ── say-Engine (Auftrag 19.07, dritte TTS-Engine, sidecars/say/) ──
        // Base-URL mit ENV-Spiegel (Spring relaxed binding: HOSHI_TTS_SAY_BASE_URL →
        // hoshi.tts.say.base-url, identisches Muster zu hoshi.speaker.base-url).
        // Default 127.0.0.1:8044 (lokaler Mac-Sidecar, byte-neutral solange HOSHI_TTS != "say").
        @Value("\${hoshi.tts.say.base-url:http://127.0.0.1:8044}") sayBaseUrl: String,
        // Leer = Sidecar-Default (server.py DEFAULT_VOICE) statt eines hier hart
        // codierten macOS-Stimmnamens.
        @Value("\${hoshi.tts.say.voice:}") sayVoice: String,
        // 0 = kein Rate-Override (say/Sidecar entscheidet selbst).
        @Value("\${hoshi.tts.say.rate:0}") sayRate: Int,
        // ── piper-Engine (Codex-Sidecar-Übergabe 19.07, vierte TTS-Engine, sidecars/piper/) ──
        // Base-URL mit ENV-Spiegel (Spring relaxed binding: HOSHI_TTS_PIPER_BASE_URL →
        // hoshi.tts.piper.base-url, identisches Muster zu hoshi.tts.say.base-url).
        // Default 127.0.0.1:8045 (lokaler Mac-Sidecar, byte-neutral solange HOSHI_TTS != "piper").
        @Value("\${hoshi.tts.piper.base-url:http://127.0.0.1:8045}") piperBaseUrl: String,
        // Codex-Contract #2: IMMER die geladene Stimme mitschicken (kein optionales Feld
        // wie bei say) — der Sidecar validiert hart und antwortet mit 422 bei Mismatch.
        @Value("\${hoshi.tts.piper.voice:de_DE-thorsten-medium}") piperVoice: String,
        // ── Cloud-TTS Egress-Sanitize (Ticket #5) — default OFF (byte-neutral, IDENTITY) ──
        @Value("\${HOSHI_TTS_SANITIZE_ENABLED:false}") sanitizeEnabled: Boolean,
        // ── TTS-Loudness-Normalisierung (0.5-Port) — flag-gated, default OFF (byte-neutral) ──
        // Fixt Andis Befund 2026-06-21 „Stimme unterschiedlich laut". OFF ⇒ exakt der nackte
        // Adapter ⇒ byte-identisches Audio wie heute. Aktivierung = Andi-Hörprobe.
        @Value("\${HOSHI_TTS_LOUDNESS_ENABLED:false}") loudnessEnabled: Boolean,
        @Value("\${hoshi.tts.loudness.target-rms-db:-18.0}") loudnessTargetRmsDb: Double,
        @Value("\${hoshi.tts.loudness.peak-ceiling-db:-1.0}") loudnessPeakCeilingDb: Double,
        // ── Gain-Cap (Ravi-Messung 2026-07-03, Andi „Wetter-Antwort ungleich laut") ──
        // Live-Messung der ECHTEN coral-Stimme (streamEnabled=true, 3 Wetter-Sätze):
        // coral rendert Sätze mit 3–5 dB Roh-Pegel-Streuung (Satz-zu-Satz), und ihr
        // Roh-RMS liegt chronisch ~−28…−34 dBFS ⇒ um aufs −18-Ziel zu kommen braucht
        // JEDER Satz +9…+13 dB. Der bisherige +6-Cap SÄTTIGT damit ALLE Sätze auf
        // exakt +6 dB (Onset-Gains gemessen alle ≈+6, am Cap) ⇒ die Normalisierung
        // reicht jeden Satz identisch verschoben durch und kann die coral-Streuung
        // NICHT entfernen ⇒ Andi hört die Roh-Stufen (~4,6 dB gated). +12 dB gibt der
        // Pro-Satz-Schätzung Luft, LEISERE Sätze STÄRKER anzuheben als lautere ⇒ die
        // Satz-zu-Satz-Spanne schrumpft (gemessen 4,6 → 3,0 dB gated / 4,2 → 2,5 dB
        // full-RMS). Clip-Schutz (Peak-Guard, −1 dBFS) bleibt je Slice; die Restspanne
        // ist crest-faktor-bedingt (peakige Sätze) und bräuchte einen Limiter (Andi-Call).
        @Value("\${hoshi.tts.loudness.max-gain-db:12.0}") loudnessMaxGainDb: Double,
        @Value("\${hoshi.tts.loudness.silence-floor-db:-50.0}") loudnessSilenceFloorDb: Double,
        // ── TTS-Streaming (Lars-Befund: Batch-Roundtrip dominiert ~1,9s) — default OFF ──
        // ON ⇒ OpenAI PCM-Stream, in ~600ms-Slices (Zero-Crossing) je als eigenes WAV
        // ⇒ erstes Audio ~1,1s statt ~2,0-2,8s (gemessen). Flip erst nach Hoerprobe.
        @Value("\${HOSHI_TTS_STREAM_ENABLED:false}") ttsStreamEnabled: Boolean,
    ): TtsPort {
        val base: TtsPort =
            when {
                ttsImpl.equals("openai", ignoreCase = true) -> OpenAiTtsAdapter(
                    apiKey = System.getenv("OPENAI_API_KEY"),
                    model = openaiModel,
                    voice = openaiVoice,
                    // P0-Leak-Riegel: OFF ⇒ IDENTITY (roher Text, byte-neutral),
                    // ON ⇒ Never-Speak-Maskierung VOR dem Cloud-Call.
                    sanitizer = if (sanitizeEnabled) NeverSpeakTtsSanitizer() else TtsSanitizePort.IDENTITY,
                    streamEnabled = ttsStreamEnabled,
                )
                // Dritte Engine (Auftrag 19.07): lokaler macOS-`say`-Sidecar, kein Cloud-Egress.
                ttsImpl.equals("say", ignoreCase = true) -> SayTtsAdapter(
                    baseUrl = sayBaseUrl,
                    voice = sayVoice.ifBlank { null },
                    rate = sayRate.takeIf { it > 0 },
                )
                // Vierte Engine (Codex-Sidecar-Übergabe 19.07): lokaler Piper-Sidecar,
                // CPU-only, kein Cloud-Egress. Default-OFF bis Andis Hörprobe/Lizenz-Gate.
                ttsImpl.equals("piper", ignoreCase = true) -> PiperTtsAdapter(
                    baseUrl = piperBaseUrl,
                    voice = piperVoice,
                )
                // Default/unbekannter Wert — UNVERÄNDERTE Semantik (Voxtral, wie vor say/piper).
                else -> VoxtralTtsAdapter(baseUrl = baseUrl, voice = voice)
            }
        return if (loudnessEnabled) {
            LoudnessNormalizingTtsPort(
                delegate = base,
                normalizer = TtsLoudnessNormalizer(
                    targetRmsDb = loudnessTargetRmsDb,
                    peakCeilingDb = loudnessPeakCeilingDb,
                    maxGainDb = loudnessMaxGainDb,
                    silenceFloorDb = loudnessSilenceFloorDb,
                ),
            )
        } else {
            base
        }
    }

    /**
     * Satzweises TTS-Chunking zwischen Orchestrator-Output und SSE (best-effort, never-silent).
     *
     * **Telemetrie-Wahrheit:** der `provider`-Tag der Audio-Events
     * ([ChatEvent.TtsAudioStart]) muss die WIRKLICH aktive Engine nennen — sonst lügt
     * die Wire-Telemetrie (früher hart „voxtral", auch bei OpenAI-TTS). Wir lesen
     * dieselbe Env [HOSHI_TTS] wie [ttsPort] und reichen exakt den Namen des Adapters,
     * den [ttsPort] baut, in den [TtsStage]-Ctor.
     */
    @Bean
    fun ttsStage(
        ttsPort: TtsPort,
        @Value("\${HOSHI_TTS:}") ttsImpl: String,
        // ── Fast-First Time-to-First-Audio (Ticket #4) — MASTER-Flag, default OFF ──
        // Spiegelt HOSHI_LANG_AUTO_ENABLED. OFF (Default) = byte-identisch zu heute:
        // fastFirstN=0, groupedMinChars=minChars(12), idleFlushMs=0 ⇒ exakt der heutige
        // concatMap-Pfad (jeder Satz einzeln, kein Idle-Timer). ON = das Backlog-Preset
        // (2 / 24 / 300): das erste Audio geht nach ~2 kurzen Sätzen raus (gefühlte Latenz
        // runter, maskiert die ~2,5s Brain-TTFT perzeptiv), der Rest gruppiert (flüssige
        // Stimme). Andi flippt beim Deploy per Hörprobe.
        @Value("\${HOSHI_TTS_FAST_FIRST_ENABLED:false}") fastFirstEnabled: Boolean,
        // Die ON-Werte (greifen NUR bei aktivem Master-Flag) — Default = Backlog-Preset,
        // einzeln per Env nachschärfbar (Ravi-A/B ohne Code-Edit). Bei OFF ignoriert.
        @Value("\${hoshi.tts.fast-first-sentences:\${HOSHI_FAST_FIRST_SENTENCES:2}}") fastFirstN: Int,
        @Value("\${hoshi.tts.grouped-min-chars:\${HOSHI_GROUPED_MIN_CHARS:24}}") groupedMinChars: Int,
        @Value("\${hoshi.tts.idle-flush-ms:\${HOSHI_TTS_IDLE_FLUSH_MS:300}}") idleFlushMs: Long,
        // **Laufzeit-Engine-Wahl (Andi-Video-Auftrag, TtsRuntimeConfig)** — wenn verdrahtet
        // (Spring-Boot), geht die ECHTE Synthese durch den Delegaten, der per
        // `PUT /api/v1/settings/tts` umgeschaltet wird, statt durch den beim Boot fix
        // konstruierten [ttsPort]. Default `null` ⇒ die Konstruktor-Tests (direkter
        // Methodenaufruf ohne Spring, z.B. [PipelineConfigTtsEngineTest]) bleiben
        // byte-identisch: `ttsPort`/`ttsImpl` greifen wie bisher statisch.
        delegatingTtsPort: DelegatingTtsPort? = null,
    ): TtsStage = TtsStage(
        tts = delegatingTtsPort ?: ttsPort,
        // Telemetrie folgt der Laufzeit-Wahl: bei verdrahtetem Delegaten wird der
        // Provider-Tag PRO SATZ frisch gelesen (currentEngineId()) statt beim Boot
        // eingefroren — ein Runtime-Switch mitten im Betrieb lügt das Wire-Tag nicht.
        provider = { delegatingTtsPort?.currentEngineId() ?: ttsEngineName(ttsImpl) },
        // OFF ⇒ die byte-neutralen Defaults (0 / minChars(12) / 0). ON ⇒ das (per Env
        // nachschärfbare) Fast-First-Preset. minChars(12) bleibt unangetastet, damit auch
        // der ERSTE Chunk ein ganzer kurzer Satz/Phrase ist (>=12 Zeichen + .!?…), nicht ein
        // abgehacktes Wort — Ravi-Veto „nicht zerhackt".
        fastFirstN = if (fastFirstEnabled) fastFirstN else 0,
        groupedMinChars = if (fastFirstEnabled) groupedMinChars else 12,
        idleFlushMs = if (fastFirstEnabled) idleFlushMs else 0L,
    )

    /**
     * Der reale Engine-Name fürs Audio-Telemetrie-Tag — DECKUNGSGLEICH mit der
     * Adapter-Wahl in [ttsPort]: `HOSHI_TTS=openai` ⇒ „openai" (OpenAiTtsAdapter),
     * `HOSHI_TTS=say` ⇒ „say" (SayTtsAdapter), `HOSHI_TTS=piper` ⇒ „piper"
     * (PiperTtsAdapter), sonst „voxtral" (VoxtralTtsAdapter, Default-Naht —
     * unbekannte Werte fallen wie bisher hierher). Delegiert an [TtsEngineIds.canonicalOf]
     * (die EINE kanonische Namens-Wahrheit, geteilt mit dem Settings-Rand) — reines
     * Umbenennen, identische Branch-Logik wie vor dieser Naht.
     */
    private fun ttsEngineName(ttsImpl: String): String = TtsEngineIds.canonicalOf(ttsImpl)

    /**
     * Die Sprach-EINGABE-Naht: live gegen den Whisper-MLX-Sidecar (:9001). Macht
     * Hoshi ANSPRECHBAR — der [VoiceInboundController] (`/api/v1/voice`) reicht
     * eingehende WAV-Bytes hier durch zu Transkript. Best-Effort/Never-Silent:
     * Stille/Fehler → leeres Transkript (warme `no_input`-Antwort, kein Crash).
     */
    @Bean
    fun sttPort(
        @Value("\${hoshi.stt.base-url:http://localhost:9001}") baseUrl: String,
        sidecarHealth: SidecarHealthService,
        // STT-Readiness-Gate (0.5-Port) — default OFF, byte-neutral. Bei ON UND whisper-stt
        // bekannt-DOWN (aus dem Ops-Watchdog-Snapshot): sofort leeres Transkript → warmer
        // no_input-Pfad, statt 30s ins Whisper-Timeout zu laufen. UNKNOWN/OK/Watchdog-aus → normal.
        @Value("\${HOSHI_VOICE_STT_READINESS_GATE_ENABLED:false}") readinessGateEnabled: Boolean,
    ): SttPort = SttReadinessGate(
        delegate = WhisperSttAdapter(baseUrl = baseUrl),
        healthStatus = sidecarHealth::statusOf,
        enabled = readinessGateEnabled,
    )

    @Bean
    fun personaService(): PersonaService = PersonaService()

    @Bean
    fun responseFormatter(): ResponseFormatter = ResponseFormatter()

    /**
     * **Bilinguale AUTO-Sprach-Naht — flag-gated, default OFF** (`HOSHI_LANG_AUTO_ENABLED`,
     * analog zu den anderen HOSHI_*-Flags). Der [HeuristicLanguageDetector] ist brain-frei
     * + deterministisch (immer gebaut); der [LanguageResolver] löst die client-seitige
     * [de.hoshi.core.dto.LanguagePolicy] (AUTO/DE/EN) am Inbound-Rand zu EINER konkreten
     * [de.hoshi.core.dto.Language] auf — VOR Orchestrator UND TTS, sodass die Pipeline nie
     * AUTO sieht. Bei OFF (Default) degradiert AUTO zu DE und Legacy/explizit bleibt
     * unverändert ⇒ byte-neutral. Beide Controller (`/api/v1/chat/stream`, `/api/v1/voice`)
     * bekommen den Resolver per Autowiring.
     */
    @Bean
    fun languageDetector(): LanguageDetector = HeuristicLanguageDetector()

    @Bean
    fun languageResolver(
        detector: LanguageDetector,
        @Value("\${HOSHI_LANG_AUTO_ENABLED:false}") langAutoEnabled: Boolean,
    ): LanguageResolver = LanguageResolver(detector = detector, autoEnabled = langAutoEnabled)

    /**
     * **Persona-Charakter-Naht — flag-gated, default OFF** (`HOSHI_PERSONA_ENABLED`,
     * analog zu `HOSHI_LANG_AUTO_ENABLED`). Der [PersonaResolver] gatet die
     * client-seitige [de.hoshi.core.dto.Persona]-Wahl am Inbound-Rand (vor dem
     * Orchestrator): bei OFF (Default) kollabieren ALLE Personas auf
     * [de.hoshi.core.dto.Persona.STANDARD] ⇒ byte-neutral (heutiger Grundton); bei ON
     * sind Standard/Kumpel/Knapp/Ruhig distinkt. Der [ChatStreamController] bekommt den
     * Resolver per Autowiring und löst pro Request auf (spiegelt den LanguageResolver).
     */
    @Bean
    fun personaResolver(
        @Value("\${HOSHI_PERSONA_ENABLED:false}") personaEnabled: Boolean,
    ): PersonaResolver = PersonaResolver(personaEnabled = personaEnabled)

    /**
     * Routing-Policy mit dem ECHTEN Keyword-Router (M4-Step-2) + dem ECHTEN
     * Embedding-Refiner (B-047, AMBIG-Resolver).
     *
     * Hop 1: [KeywordRouterImpl] (0ms) klassifiziert eine Wissensfrage als FACT_SHORT
     * → das Kategorie-Gate des [Fts5GroundingAdapter] greift und Hoshi antwortet
     * grounded mit echtem Wiki-Wissen.
     *
     * Hop 2 (AMBIG): **flag-gated `HOSHI_EMBEDDING_ROUTER`, default OFF** (geflippt
     * OSS-Audit 2026-07-11 — vorher default ON, s. „Dormant by design" unten: ein
     * „ON", das strukturell nie feuert, täuscht eine aktive Fähigkeit nur vor). Bei ON
     * ersetzt der [EmbeddingRouterRefiner] (embeddinggemma :11434, cosine gegen
     * Kategorie-Anker) den bisherigen Passthrough-Stub → schärfere AMBIG-Auflösung,
     * best-effort (Ollama down/Fehler → Route unverändert). Bei OFF (Default) bleibt
     * der [PassthroughRefinerStubAdapter].
     *
     * `softRoutingEnabled` folgt dem Flag — bei OFF reicht die Policy die Hop-1-
     * Decision direkt durch (kein Refiner-Call). **Dormant by design:**
     * [KeywordRouterImpl] emittiert NIE AMBIG (nur SMART_HOME/SMALLTALK/FACT_SHORT,
     * s. dessen `decide()` — drei Returns, keiner AMBIG) → in [RoutingPolicy.resolve]
     * ist `hop1.category == RouteCategory.AMBIG` IMMER false, der Refiner-Zweig wird
     * also UNABHÄNGIG vom Flag-Wert nie betreten. Der echte Refiner steht fertig
     * verdrahtet bereit, sobald ein Hop-1-Router tatsächlich AMBIG liefert — dann
     * macht `HOSHI_EMBEDDING_ROUTER=true` ihn mit einem reinen Config-Flip scharf.
     *
     * Der LLM-Refiner bleibt Passthrough-Stub (kein zweiter Brain-Call/Turn in 0.8).
     */
    @Bean
    fun routingPolicy(
        @Value("\${HOSHI_EMBEDDING_ROUTER:false}") embeddingRouterEnabled: Boolean,
        @Value("\${hoshi.routing.ollama.base-url:http://localhost:11434}") ollamaBaseUrl: String,
        @Value("\${hoshi.routing.embedding-model:embeddinggemma:300m}") embeddingModel: String,
    ): RoutingPolicy {
        val embeddingRefiner: RouteRefiner =
            if (embeddingRouterEnabled) {
                EmbeddingRouterRefiner(OllamaRouteEmbedder(baseUrl = ollamaBaseUrl, model = embeddingModel))
            } else {
                PassthroughRefinerStubAdapter()
            }
        return RoutingPolicy(
            keywordRouter = KeywordRouterImpl(),
            llmRefiner = PassthroughRefinerStubAdapter(),
            embeddingRefiner = embeddingRefiner,
            softRoutingEnabled = embeddingRouterEnabled,
            softRoutingMode = "embedding",
        )
    }

    /**
     * Honesty-Gate: ECHTE reine Heuristiken (Weak-Domain/Online-Request, mitportiert)
     * + konservative Stubs für die infra-koppelnden Existence/Named-Entity-Signale.
     * Cloud ist in 0.8 aus → Gate-Treffer enden in einer ehrlichen warmen Absage.
     */
    @Bean
    fun honestyGate(
        @Value("\${hoshi.cloud.enabled:false}") cloudEnabled: Boolean,
        // ── Echte Bridge-Probe-Adapter (0.5-Port) — flag-gated, default OFF (byte-neutral) ──
        // Bei OFF die inerten Stubs (HonestySignal.NONE) ⇒ Gate-Verhalten unverändert. Bei ON
        // prüfen ExistenceClaim/NamedEntity gegen die Wiki-Bridge (:8035 /search, synchron via
        // java.net.http — 0.5-Lehre: WebClient.block() wirft auf netty-Threads) → Anti-Konfabulation.
        @Value("\${HOSHI_HONESTY_PROBE_ENABLED:false}") honestyProbeEnabled: Boolean,
        @Value("\${hoshi.knowledge.bridge.base-url:http://localhost:8035}") bridgeBaseUrl: String,
    ): HonestyGate {
        val (existenceClaim, namedEntity) = HonestyProbeAdapters.signals(honestyProbeEnabled, bridgeBaseUrl)
        return HonestyGate(
            weakDomain = WeakDomainDetector(),
            onlineRequest = OnlineRequestDetector(),
            existenceClaim = existenceClaim,
            namedEntity = namedEntity,
            cloudEnabled = { cloudEnabled },
        )
    }

    /**
     * **M4-Step-1 Grounding-Naht — flag-gated, default OFF.** Bei
     * `HOSHI_GROUNDING_ENABLED=true` zapft der [Fts5GroundingAdapter] die lokale
     * deutsche Wikipedia über die Knowledge-Bridge (:8035) an; sonst bleibt der
     * verhaltens-neutrale [GroundingStubAdapter] (leerer Block) — das Default-
     * Verhalten ändert sich NICHT (das ON ist Andi-Hörprobe).
     *
     * Hinweis: der Hop-1-Router ist in 0.8 noch ein Stub (SMALLTALK/SMART_HOME),
     * das Kategorie-Gate im Adapter groundet nur Wissens-Kategorien — der volle
     * Router ist ein eigener M4-Schritt.
     *
     * **Wetter-Grounding-Naht — flag-gated, default OFF** (`HOSHI_WEATHER_ENABLED`).
     * Bei ON wird die Wiki-Scheibe (oder der Stub) in einen [CompositeGroundingPort]
     * gewickelt, der eine WETTER-Frage zuerst über den [WeatherGroundingProvider]
     * (Open-Meteo `/v1/forecast`, KEIN API-Key) beantwortet (echte Vorhersage
     * heute+morgen, geerdet) und sonst unverändert zur Wiki-Scheibe durchfällt. Der
     * Standort ist über `hoshi.weather.lat`/`hoshi.weather.lon`/`hoshi.weather.label`
     * (bzw. die Envs `HOSHI_WEATHER_LAT`/`_LON`/`_LABEL`) überschreibbar, Default
     * Berlin (52.52/13.41). Bei OFF (Default) bleibt EXAKT das bisherige Grounding-
     * Bean (Wiki bzw. Stub) — byte-neutral, kein zweiter Brain-Call.
     */
    @Bean
    fun groundingPort(
        @Value("\${HOSHI_GROUNDING_ENABLED:false}") groundingEnabled: Boolean,
        @Value("\${hoshi.knowledge.bridge.base-url:http://localhost:8035}") bridgeBaseUrl: String,
        // WikiNumberContract (0.5-Port) — default OFF, byte-neutral. Bei ON schickt der Adapter
        // fact_query an die Bridge + hängt die «»-Verbatim-Zahl-Spans + Zitier-Instruktion an
        // den Grounding-Block (verhindert, dass das Modell Jahreszahlen/Mengen verwässert).
        @Value("\${HOSHI_WIKINUMBER_CONTRACT_ENABLED:false}") numberContractEnabled: Boolean = false,
        @Value("\${HOSHI_WEATHER_ENABLED:false}") weatherEnabled: Boolean,
        @Value("\${hoshi.weather.base-url:https://api.open-meteo.com}") weatherBaseUrl: String,
        @Value("\${hoshi.weather.lat:\${HOSHI_WEATHER_LAT:52.52}}") weatherLat: Double,
        @Value("\${hoshi.weather.lon:\${HOSHI_WEATHER_LON:13.41}}") weatherLon: Double,
        @Value("\${hoshi.weather.label:\${HOSHI_WEATHER_LABEL:Berlin}}") weatherLabel: String,
        // Wetter-Verbatim-Vertrag: Ort/Min/Max/Wetterlage werden im Block «»-markiert
        // (derselbe Marker-Vertrag wie beim Wiki-Zahlen-Kontrakt) — kleine Modelle
        // paraphrasieren Grounding-Zahlen sonst hörbar falsch. Default OFF ⇒ byte-neutral.
        @Value("\${HOSHI_WEATHER_CONTRACT_ENABLED:false}") weatherContractEnabled: Boolean,
        // Wetter S1 (Laufzeit-Ort): DIESELBE Store-Instanz wie der Settings-PUT-Rand
        // (WeatherLocationController) — eine Wahrheit, zwei Leser; PUT greift ab dem
        // nächsten Turn ohne Redeploy. Kein gespeicherter Ort ⇒ null ⇒ ENV-Seeds
        // (byte-gleich zu heute). Beans aus WeatherLocationConfig.
        weatherLocationStore: JsonFileWeatherLocationStore,
        geocodingClient: OpenMeteoGeocodingClient,
        // Extended Think S3 — Cache-Hit-vor-Cloud-Schicht, NUR bei offener Decke gebunden.
        // DIESELBE Property wie LookupNoteConfig/PrivacyController (eine Datei-Wahrheit).
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${hoshi.escalation.lookup.path:\${HOSHI_ESCALATION_LOOKUP_PATH:}}") lookupPath: String,
        // H1 Zitat-Zaun (Injection-Härtung 08.07): Default AN (Security-Fix) —
        // OFF ist der byte-identische Kill-Switch, kein Feature-Flip.
        @Value("\${HOSHI_LOOKUP_QUOTE_FENCE_ENABLED:true}") quoteFenceEnabled: Boolean,
    ): GroundingPort {
        val wiki: GroundingPort =
            if (groundingEnabled) Fts5GroundingAdapter(baseUrl = bridgeBaseUrl, enableNumberContract = numberContractEnabled) else GroundingStubAdapter()
        val nachgeschlagen: GroundingPort =
            if (extendedThinkEnabled) {
                NachgeschlagenGroundingProvider(
                    path = JsonlLookupNoteAdapter.resolveDefaultPath(lookupPath.ifBlank { null }),
                    quoteFence = quoteFenceEnabled,
                )
            } else {
                GroundingPort { _, _ -> Mono.just("") }
            }
        // weatherEnabled || extendedThinkEnabled: Cache-Hit-vor-Cloud ist Architektur,
        // kein Wetter-Anhängsel — die Schicht muss auch ohne Wetter erreichbar sein.
        return if (weatherEnabled || extendedThinkEnabled) {
            CompositeGroundingPort(
                weather = if (weatherEnabled) {
                    WeatherGroundingProvider(
                        baseUrl = weatherBaseUrl,
                        lat = weatherLat,
                        lon = weatherLon,
                        locationLabel = weatherLabel,
                        locationSupplier = { weatherLocationStore.location() },
                        geocoding = geocodingClient,
                        enableWeatherContract = weatherContractEnabled,
                    )
                } else {
                    GroundingPort { _, _ -> Mono.just("") }
                },
                wiki = wiki,
                nachgeschlagen = nachgeschlagen,
            )
        } else {
            wiki
        }
    }

    /**
     * Wetter S3 (Orts-Nachfrage): Pending-Store fürs offene „Für welchen Ort denn?"
     * (eigener Store, damit Orts- und Extended-Think-Kette sich strukturell nie
     * vermischen). Decke zu (Default) ⇒ NONE ⇒ byte-neutral.
     */
    @Bean
    fun pendingLocationQuestionPort(
        @Value("\${HOSHI_WEATHER_ENABLED:false}") weatherEnabled: Boolean,
    ): PendingLocationQuestionPort =
        if (weatherEnabled) InMemoryPendingLocationQuestionStore() else PendingLocationQuestionPort.NONE

    /**
     * Wetter S3: die Frage-Naht — needsLocation nur, wenn Store leer UND die Seeds
     * exakt die Code-Defaults sind (Prod hat echte Seeds ⇒ feuert dort NIE).
     * DIESELBEN Store-/Geocoding-Instanzen wie groundingPort/WeatherLocationController
     * (eine Wahrheit, drei Leser; GET zeigt nach der Turn-Nachfrage fromStore=true).
     */
    @Bean
    fun weatherLocationAskPort(
        @Value("\${HOSHI_WEATHER_ENABLED:false}") weatherEnabled: Boolean,
        @Value("\${hoshi.weather.lat:\${HOSHI_WEATHER_LAT:52.52}}") weatherLat: Double,
        @Value("\${hoshi.weather.lon:\${HOSHI_WEATHER_LON:13.41}}") weatherLon: Double,
        @Value("\${hoshi.weather.label:\${HOSHI_WEATHER_LABEL:Berlin}}") weatherLabel: String,
        weatherLocationStore: JsonFileWeatherLocationStore,
        geocodingClient: OpenMeteoGeocodingClient,
    ): WeatherLocationAskPort =
        if (!weatherEnabled) {
            WeatherLocationAskPort.NONE
        } else {
            WeatherLocationAskAdapter(
                seedsAreCodeDefaults = (
                    weatherLat == WeatherGroundingProvider.DEFAULT_LAT &&
                        weatherLon == WeatherGroundingProvider.DEFAULT_LON &&
                        weatherLabel == WeatherGroundingProvider.DEFAULT_LABEL
                    ),
                storedLocation = { weatherLocationStore.location() },
                storeLocation = { weatherLocationStore.setLocation(it) },
                geocoding = geocodingClient,
            )
        }

    /**
     * **Ambient/Mood-Wärme-Naht — flag-gated, default OFF** (`HOSHI_AMBIENT_ENABLED`,
     * analog zu den anderen Naht-Flags). Bei OFF (Default) der verhaltens-neutrale
     * [AmbientWarmthPort.NONE] (gibt nie einen Hinweis) ⇒ `baseSystemPrompt` bleibt
     * byte-identisch, das bestehende Verhalten ändert sich NICHT.
     *
     * Bei ON liest der clock-gebundene Adapter die echte Stunde ([ClockPort.SYSTEM] —
     * der EINZIGE `now()`-Punkt) und reicht sie der REINEN [AmbientMood]-Logik
     * (Stunde → Tageszeit → kleiner, sprach-getunter Wärme-Hinweis). Kein zweiter
     * Brain-Call, keine Infra — nur ein String ans Prompt-Ende.
     */
    @Bean
    fun ambientWarmthPort(
        @Value("\${HOSHI_AMBIENT_ENABLED:false}") ambientEnabled: Boolean,
    ): AmbientWarmthPort =
        if (ambientEnabled) {
            val clock = ClockPort.SYSTEM
            AmbientWarmthPort { language -> AmbientMood.warmthHint(clock.hour(), language) }
        } else {
            AmbientWarmthPort.NONE
        }

    /**
     * **Warmth v2 — passiver Mood-Temperatur-Hebel. Flag-gated, default OFF**
     * (`HOSHI_WARMTH_V2_ENABLED`, analog zu `HOSHI_AMBIENT_ENABLED`). Bei OFF (Default)
     * der verhaltens-neutrale [MoodTemperaturePort.NONE] (Identität) ⇒ die an den Brain
     * gereichte Temperatur ist EXAKT `persona.temperatureFor()`, byte-neutral.
     *
     * Bei ON liest der clock-gebundene Adapter die echte Stunde ([ClockPort.SYSTEM] —
     * der EINZIGE `now()`-Punkt, identisch zur Ambient-Naht) und reicht sie der REINEN
     * [WarmthMood.temperatureFor]-Logik (Zeit-Bias + Müdigkeits-Marker → kleiner
     * Temperatur-Nudge: CALM tiefer, WAKE höher). Kein zweiter Brain-Call, keine Infra.
     * Die statische Ambient-v1-Naht bleibt unberührt (eigenes Flag).
     */
    @Bean
    fun moodTemperaturePort(
        @Value("\${HOSHI_WARMTH_V2_ENABLED:false}") warmthV2Enabled: Boolean,
    ): MoodTemperaturePort =
        if (warmthV2Enabled) {
            val clock = ClockPort.SYSTEM
            MoodTemperaturePort { base, userText -> WarmthMood.temperatureFor(base, clock.hour(), userText) }
        } else {
            MoodTemperaturePort.NONE
        }

    @Bean
    fun turnPromptAssembler(
        persona: PersonaService,
        grounding: GroundingPort,
        ambientWarmthPort: AmbientWarmthPort,
        // Explizit qualifizieren: bei aktivem Memory ist der EntityMemoryAdapter
        // EINE Instanz, die BEIDE Ports erfüllt → ohne Qualifier wäre EntityContextPort
        // mehrdeutig (entityContextPort + entityMemoryWriter). Recall ⇒ entityContextPort.
        @Qualifier("entityContextPort") entityContextPort: EntityContextPort,
        // Episodic-Recall: bei OFF der verhaltens-neutrale Stub (`""`) → der
        // Assembler schichtet keinen Episodic-Block (Default-Verhalten unverändert).
        // Qualifizieren: der EpisodicMemoryAdapter erfüllt BEIDE Ports (Recall+Store)
        // → ohne Qualifier wäre EpisodicRecallPort mehrdeutig (episodicRecallPort +
        // episodicWriter). Recall ⇒ episodicRecallPort.
        @Qualifier("episodicRecallPort") episodicRecallPort: EpisodicRecallPort,
        // Server-side Working-Memory-Window (0.5-Port) — 0 = kein Cap (byte-neutral),
        // >0 kappt die an den Brain gereichte History auf die letzten N Turns (16-GB-KV-Schutz).
        @Value("\${hoshi.memory.window-turns:\${HOSHI_MEMORY_WINDOW_TURNS:0}}") memoryWindowTurns: Int,
        // Sprecher-Vertrauens-Gate (P1-Privacy, SpeakerTrust) — DASSELBE Flag/DIESELBE
        // Schwelle wie das Write-Gate in ChatStreamController.rememberAfter: EINE
        // Entscheidung, zwei Nähte (Recall hier, Write dort). Default OFF ⇒ byte-neutral.
        @Value("\${HOSHI_SPEAKER_TRUST_ENFORCED:false}") speakerTrustEnforced: Boolean,
        @Value("\${hoshi.speaker.recognition.threshold:0.80}") speakerTrustThreshold: Double,
    ): TurnPromptAssembler = TurnPromptAssembler(
        persona = persona,
        entityMemory = entityContextPort,
        grounding = grounding,
        episodicMemory = episodicRecallPort,
        ambient = ambientWarmthPort,
        historyWindowTurns = memoryWindowTurns,
        speakerTrustEnforced = speakerTrustEnforced,
        speakerTrustThreshold = speakerTrustThreshold,
    )

    /**
     * **Tat-Gate** — verdrahtet den bisher VERWAISTEN [CapabilityKernel] über den
     * [KernelCapabilityAdapter] in den [CapabilityPort]. DEFAULT DENY-ALL: jede Tat
     * MUSS hier durch, bevor der Executor sie sieht.
     */
    @Bean
    fun capabilityPort(): CapabilityPort = KernelCapabilityAdapter(CapabilityKernel())

    /**
     * **Tat-Executor — flag-gated, default OFF** (`HOSHI_HA_ENABLED`, analog zu den
     * anderen Naht-Beans). Bei ON UND vorhandenem Token ([resolveHaToken]: Env
     * `HOSHI_HA_TOKEN` oder `~/.hoshi/secrets.json[ha]`, NIE geloggt) der echte
     * [HaToolPort] (HA REST /api/services,
     * area_id-Targeting); sonst der ehrliche [ToolPort.HONEST_PLACEHOLDER] (🔵, nie
     * Fake). Default OFF ⇒ Platzhalter ⇒ Verhalten unverändert.
     *
     * Fehlt das Token trotz `HOSHI_HA_ENABLED=true`, bleibt es bewusst beim
     * Platzhalter (sauberer Fallback statt eines Adapters, der eh nur NoEffect liefert).
     *
     * `HOSHI_HA_BASE_URL` Default (OSS-Audit 2026-07-11): `http://homeassistant.local:8123`
     * (mDNS-Standard-Host von Home Assistant) statt einer hartkodierten privaten LAN-IP —
     * ein Fremd-Clone soll nicht Andis Heimnetz erben. Andis reale HA-Adresse lebt NUR in
     * der systemd-Unit (`tools/systemd/hoshi-0.8-backend.service`, `Environment=`), NIE im
     * Code. Bei OFF (Default) unbenutzt (kein Bean baut [HaToolPort]); bei ON ohne Override
     * degradiert [HaToolPort] never-throw (Timeout/DNS-Fehler ⇒ [ToolResult.Failed], warm).
     */
    @Bean
    fun toolPort(
        @Value("\${HOSHI_HA_ENABLED:false}") haEnabled: Boolean,
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") baseUrl: String,
    ): ToolPort {
        val token = resolveHaToken()
        return if (haEnabled && !token.isNullOrBlank()) {
            HaToolPort(baseUrl = baseUrl, token = token)
        } else {
            ToolPort.HONEST_PLACEHOLDER
        }
    }

    /**
     * HA-Token-Auflösung (Wert wird NIE geloggt). Reihenfolge: Env `HOSHI_HA_TOKEN`
     * gewinnt (Bench/CI), sonst der `"ha"`-Key aus `~/.hoshi/secrets.json` — DIESELBE
     * Quelle, die Hoshi 0.5 (`SecretsStore`, chmod 600) nutzt. So „übernimmt" 0.8 den
     * Token, sobald Andi ihn EINMAL dort ablegt (z.B. vom ct-106-`/etc/hoshi.env`).
     * Fehlende Datei / fehlender Key / Parse-Fehler → null (sauberer Platzhalter-Fallback).
     */
    private fun resolveHaToken(): String? {
        System.getenv("HOSHI_HA_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val path = Paths.get(System.getProperty("user.home"), ".hoshi", "secrets.json")
            if (!Files.exists(path)) return null
            ObjectMapper().readTree(path.toFile()).get("ha")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * **Tool-Intent-Classifier — flag-gated, default OFF** (`HOSHI_TOOLS_ENABLED`,
     * analog zu `HOSHI_MEMORY_ENABLED`). Bei OFF der verhaltens-neutrale
     * [ToolIntentClassifier.DISABLED] (klassifiziert nie ⇒ kein Tool-Turn, der
     * bestehende Brain-Pfad bleibt unverändert). Bei ON der deterministische
     * [DeterministicToolIntentClassifier] (single-turn Tool-Calling, brain-frei).
     *
     * **Szenen-by-Name — flag-gated, default OFF** (`HOSHI_SCENES_ENABLED`). Bei ON
     * (und vorhandenem Token + erreichbarem HA) lädt der [HaSceneCatalogAdapter] die
     * echten `scene_id`s READ-ONLY (`GET /api/states`, einmal + gecacht, best-effort)
     * und gibt sie dem Classifier ⇒ „mach die Nordlichter an" matcht die reale Szene.
     * Bei OFF (oder HA unerreichbar / kein Token) ist der Katalog leer ⇒ heutiges
     * naives `scene.<token>`-Verhalten unverändert. Greift nur wenn `HOSHI_TOOLS_ENABLED`
     * (sonst klassifiziert der Pfad ohnehin nie).
     */
    @Bean
    fun intentClassifier(
        @Value("\${HOSHI_TOOLS_ENABLED:false}") toolsEnabled: Boolean,
        @Value("\${HOSHI_SCENES_ENABLED:false}") scenesEnabled: Boolean,
        // ── TimerFastpath (0.5-Port) — flag-gated, default OFF. Bei ON erkennt der
        // Classifier deterministische Timer/Wecker/Erinnerungen (brain-frei); der
        // Timer-Zweig ist unabhängig von HOSHI_TOOLS_ENABLED (Timer ≠ HA-Aktuator).
        @Value("\${HOSHI_TIMER_ENABLED:false}") timerEnabled: Boolean,
        // CalculatorFastpath (0.5-Port-Geist) — det. Rechnen DE+EN, brain-frei, unabhängig von HOSHI_TOOLS_ENABLED.
        @Value("\${HOSHI_CALCULATOR_ENABLED:false}") calculatorEnabled: Boolean,
        // Embedded-Arithmetik-Fang — flag-gated, default OFF (byte-neutral). Bei ON fängt der
        // Calc-Zweig auch satz-eingebettete einfache Rechnungen („wie viel ist 17 mal 23" im Satz)
        // in die Fastpath statt ins Brain. Greift NUR wenn HOSHI_CALCULATOR_ENABLED ohnehin an ist.
        @Value("\${HOSHI_CALC_EMBEDDED_ENABLED:false}") calcEmbeddedEnabled: Boolean,
        // ListFastpath (Andi-JA 2026-07-08, „Listen auf die Ring-1-Karte") — det.
        // Einkaufsliste DE+EN, brain-frei, unabhängig von HOSHI_TOOLS_ENABLED.
        @Value("\${HOSHI_LIST_ENABLED:false}") listEnabled: Boolean,
        // Dynamischer HA-Area-Katalog — Räume synchron zur echten HA-Registry statt
        // statischer Liste. Flag-gated, default OFF ⇒ statische ToolAreas-Liste, byte-neutral.
        @Value("\${HOSHI_AREAS_DYNAMIC_ENABLED:false}") areasDynamicEnabled: Boolean,
        // OSS-Default s. [toolPort]-KDoc (mDNS statt hartkodierter LAN-IP; Andis realer
        // Prod-Wert kommt ausschließlich aus der systemd-Unit-Environment).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
        // Die EINE pro-Turn lesbare Toggle-Wahrheit (S2.2): Decke AND Laufzeit-Store
        // (siehe [skillState]). Ersetzt das frühere inline-ofStatic — der Classifier
        // liest jetzt den effektiven Laufzeit-Zustand, der OHNE Redeploy togglen kann.
        @Qualifier("skillState") skillState: SkillStatePort,
    ): ToolIntentClassifier {
        // DISABLED-Guard bleibt an der DECKE (ENV-Flags): sind ALLE Decken zu, gibt es nie
        // einen klassifizierten Call ⇒ verhaltens-neutraler DISABLED-Pfad, byte-neutral.
        // Liegt mindestens eine Decke offen, baut der Classifier — der per-Skill-Laufzeit-
        // Toggle wirkt dann allein über skillState.isEnabled(...) zur classify-Zeit.
        if (!toolsEnabled && !timerEnabled && !calculatorEnabled && !listEnabled) return ToolIntentClassifier.DISABLED
        // Szene-Katalog wird AB OFFENER DECKE gebaut (nicht erst bei Laufzeit-ON), damit ein
        // späteres Settings-ON sofort greift, nicht erst nach Neustart.
        val sceneCatalog = if (toolsEnabled && scenesEnabled) loadSceneCatalog(haBaseUrl) else emptyList()
        // Area-Katalog wie der Szenen-Katalog AB OFFENER DECKE: dynamisch NUR wenn Tools
        // an UND Flag gezogen; sonst [AreaCatalogPort.STATIC] (= ToolAreas.ROOMS,
        // byte-identisch zum Verhalten vor dieser Naht). Adapter ist gecacht (TTL 15min)
        // + never-throw mit Fallback auf den letzten guten Stand — HA-Ausfall macht
        // Raum-Befehle nie kaputt, nur ggf. veraltet.
        val areaCatalog =
            if (toolsEnabled && areasDynamicEnabled) HaAreaCatalogAdapter(baseUrl = haBaseUrl, token = resolveHaToken())
            else AreaCatalogPort.STATIC
        return DeterministicToolIntentClassifier(
            sceneCatalog = sceneCatalog,
            skills = skillState,
            calcEmbeddedEnabled = calcEmbeddedEnabled,
            listEnabled = listEnabled,
            areaCatalog = areaCatalog,
        )
    }

    /**
     * **Die Zwei-Stufen-Toggle-Wahrheit (S2.2)** — `effektiv = Decke AND Laufzeit-Store`.
     *
     * Stufe 1 (DECKE, Deploy-Zeit): die heutigen ENV-Flags pro Skill, gefaltet in einen
     * konstanten [SkillStatePort.ofStatic] — SMART_HOME=`HOSHI_TOOLS_ENABLED`,
     * SCENES=`HOSHI_TOOLS_ENABLED && HOSHI_SCENES_ENABLED`, TIMER=`HOSHI_TIMER_ENABLED`,
     * CALCULATOR=`HOSHI_CALCULATOR_ENABLED`. Ist die Decke für einen Skill zu, ist er hart
     * aus (Store-Toggle wirkungslos — das bewahrt das Egress-Deploy-Gate).
     *
     * Stufe 2 (STORE, Laufzeit): [JsonFileSkillStateStore] aus `hoshi.settings.path` /
     * `HOSHI_SETTINGS_PATH` (Default `~/.hoshi/skills.json`). Decke offen ⇒ der Store
     * entscheidet zur Laufzeit (Settings-PUT, S2.3, ohne Redeploy).
     *
     * **Byte-Neutralität:** der `runtimeDefault` ist für die heutigen (lokalen) Skills ON.
     * Ohne `skills.json` ist der Store leer ⇒ `effektiv == Decke == das frühere ofStatic`.
     * Der Classifier verhält sich exakt wie vor S2.2.
     */
    @Bean
    fun skillState(
        @Value("\${HOSHI_TOOLS_ENABLED:false}") toolsEnabled: Boolean,
        @Value("\${HOSHI_SCENES_ENABLED:false}") scenesEnabled: Boolean,
        @Value("\${HOSHI_TIMER_ENABLED:false}") timerEnabled: Boolean,
        @Value("\${HOSHI_CALCULATOR_ENABLED:false}") calculatorEnabled: Boolean,
        @Value("\${hoshi.settings.path:\${HOSHI_SETTINGS_PATH:}}") settingsPath: String,
    ): SkillStatePort {
        val ceiling = SkillStatePort.ofStatic(
            smartHome = toolsEnabled,
            scenes = toolsEnabled && scenesEnabled,
            timer = timerEnabled,
            calculator = calculatorEnabled,
        )
        // runtimeDefault tier-abhängig (Codex fail-closed): LOCAL = ON (byte-neutral, alle 4 Skills lokal),
        // EGRESS/CLOUD = OFF. Schließt die Egress-Naht am Classifier-Default vor dem ersten Egress-Skill.
        return CeilingAndStoreSkillState(ceiling, skillStore(settingsPath)) { id -> SkillRegistry.defaultEnabledFor(id) }
    }

    /**
     * **Der Laufzeit-Store als eigene Bean (S2.3)** — DIESELBE [JsonFileSkillStateStore]-
     * Instanz, die [skillState] in die Zwei-Stufen-Wahrheit faltet. Der [SettingsController]
     * injiziert genau diese Bean: ein Settings-PUT schreibt in den Store, dessen Cache der
     * Classifier pro Turn liest ⇒ der Toggle greift ab dem nächsten Turn OHNE Redeploy.
     *
     * Memoisiert (ein Store pro Pfad, analog zu [memoryAdapter] und [episodicAdapter]), damit
     * Controller und Classifier garantiert dieselbe [java.util.concurrent.ConcurrentHashMap]
     * sehen — NICHT zwei Instanzen mit getrennten Caches.
     */
    @Bean
    fun skillStateStore(
        @Value("\${hoshi.settings.path:\${HOSHI_SETTINGS_PATH:}}") settingsPath: String,
    ): JsonFileSkillStateStore = skillStore(settingsPath)

    @Volatile private var sharedSkillStore: JsonFileSkillStateStore? = null

    /**
     * Memoisierte [JsonFileSkillStateStore]-Konstruktion (ein Store pro absolutem Pfad).
     * [skillState] und [skillStateStore] rufen DIESE Methode mit demselben `settingsPath`
     * und teilen sich so EINE Instanz (gemeinsamer Cache, gemeinsame Schreib-Sicht).
     */
    @Synchronized
    private fun skillStore(settingsPath: String): JsonFileSkillStateStore {
        val path = resolveSettingsPath(settingsPath).toAbsolutePath()
        sharedSkillStore?.let { if (it.path == path) return it }
        return JsonFileSkillStateStore(path).also { sharedSkillStore = it }
    }

    /**
     * Pfad des Laufzeit-Skill-Stores: explizit konfiguriert (`hoshi.settings.path` /
     * `HOSHI_SETTINGS_PATH`) oder der Default `~/.hoshi/skills.json` — exakt das
     * `secrets.json`-Muster ([resolveHaToken]).
     */
    private fun resolveSettingsPath(configured: String): Path =
        if (configured.isNotBlank()) Paths.get(configured)
        else Paths.get(System.getProperty("user.home"), ".hoshi", "skills.json")

    /**
     * Lädt die echten `scene_id`s READ-ONLY von HA (best-effort, leer wenn Token fehlt
     * oder HA unerreichbar — der Adapter wirft NIE). Token aus [resolveHaToken] (nie geloggt).
     */
    private fun loadSceneCatalog(baseUrl: String): List<String> =
        HaSceneCatalogAdapter(baseUrl = baseUrl, token = resolveHaToken()).sceneIds()

    /**
     * **Pro-Sprecher Last-Area-Gedächtnis — gated auf `HOSHI_TOOLS_ENABLED`** (es
     * speist NUR die deterministische Anaphern-Auflösung des Tool-Pfads; ohne Tools
     * gibt es nie einen klassifizierten Call). Bei ON der in-memory
     * [InMemoryLastAreaStore] (`ConcurrentHashMap<speakerId, areaId>`), sonst der
     * verhaltens-neutrale [LastAreaPort.NONE] ⇒ byte-neutral.
     *
     * Nicht-persistent (nur Laufzeit): ein neu gestarteter Prozess „vergisst" die
     * zuletzt geschaltete Area — bewusst, denn ein Raum-Bezug ohne frische Konversation
     * raten wäre falscher als ehrlich nachzufragen.
     */
    @Bean
    fun lastAreaPort(
        @Value("\${HOSHI_TOOLS_ENABLED:false}") toolsEnabled: Boolean,
    ): LastAreaPort = if (toolsEnabled) InMemoryLastAreaStore() else LastAreaPort.NONE

    /**
     * **TimerFastpath-Store (0.5-Port) — flag-gated, default OFF** (`HOSHI_TIMER_ENABLED`).
     * Bei ON ein thread-sicherer In-Memory-Store (uhrfrei, hält absolute `dueAtEpochMs`),
     * sonst [ScheduledItemPort.NONE]. In-Mem ⇒ Items überleben keinen Prozess-Neustart
     * (sqlite optional später); die Klingel-Ansage braucht die Audio-Naht.
     */
    @Bean
    fun scheduledItemPort(
        @Value("\${HOSHI_TIMER_ENABLED:false}") timerEnabled: Boolean,
        // Wecker-Persistenz (Cowork-Befund, von uns verifiziert: InMemory stirbt beim
        // Restart — der taegliche Vertrauens-Anker braucht eine Datei). Default OFF ⇒
        // byte-neutral (InMemory wie bisher). Pfad: konfiguriert ▷ /var/lib/hoshi-0.8 ▷ ~/.hoshi.
        @Value("\${HOSHI_TIMER_PERSISTENCE_ENABLED:false}") timerPersistence: Boolean,
        @Value("\${hoshi.timer.store.path:\${HOSHI_TIMER_STORE_PATH:}}") timerStorePath: String,
    ): ScheduledItemPort = when {
        !timerEnabled -> ScheduledItemPort.NONE
        timerPersistence -> FileBackedScheduledItemStore(resolveScheduledStorePath(timerStorePath))
        else -> InMemoryScheduledItemStore()
    }

    /**
     * **Fired-Store (Klingel-Zustand) — die Ablage der gefeuerten, unbestaetigten Items**
     * (`GET /api/v1/scheduled/fired` idempotent + `POST …/fired/{id}/ack`). Ist der
     * [ScheduledItemPort] file-backed, IST er zugleich der [FiredItemsStore] — fired/ack
     * leben dann Restart-fest im SELBEN JSON-Store wie die aktiven Items. Sonst (In-Mem/
     * NONE) die fluechtige [InMemoryFiredItemsStore]-Variante; der [FiredItemsController]
     * bootet damit auch OHNE Fire-Service-Wiring (liefert dann schlicht `[]`).
     */
    @Bean
    fun firedItemsStore(scheduledItemPort: ScheduledItemPort): FiredItemsStore =
        scheduledItemPort as? FiredItemsStore ?: InMemoryFiredItemsStore()

    /**
     * **Wecker-Klingel-Naht (Fire-Service) — flag-gated, default OFF** (`HOSHI_TIMER_FIRE_ENABLED`).
     * Pollt faellige Items (~1s, eigener Daemon-Thread), cancelt sie persistent (kein
     * Doppel-Klingeln nach Restart) und legt sie in den [FiredItemsStore]
     * (`GET /api/v1/scheduled/fired` — idempotent, weg erst per Ack; > 5 min ueber-faellig
     * ⇒ `missed=true` statt still verwerfen). Das FE pollt + klingelt lokal.
     * OFF ⇒ Service startet nicht ⇒ byte-neutral.
     */
    @Bean(destroyMethod = "close")
    fun scheduledItemFireService(
        @Value("\${HOSHI_TIMER_FIRE_ENABLED:false}") fireEnabled: Boolean,
        scheduledItemPort: ScheduledItemPort,
        firedItemsStore: FiredItemsStore,
        timerRingDownlinkService: TimerRingDownlinkService,
    ): ScheduledItemFireService = ScheduledItemFireService(
        store = scheduledItemPort,
        fired = firedItemsStore,
        enabled = fireEnabled,
        onFired = { id, label, originSatelliteId -> timerRingDownlinkService.onFired(id, label, originSatelliteId) },
    ).also { it.start() }

    /**
     * Daten-Store-Pfad, EIN Muster für alle vier JSON-Stores (Audit C1, 21.07):
     * explizit ▷ Prod-Datenverzeichnis (falls beschreibbar) ▷ ~/.hoshi (Dev).
     * [resolveSettingsPath] bleibt bewusst eigenständig (anderes Fallback-Muster).
     */
    private fun resolveDataStorePath(configured: String, fileName: String): java.nio.file.Path {
        if (configured.isNotBlank()) return java.nio.file.Path.of(configured)
        val prod = java.nio.file.Path.of("/var/lib/hoshi-0.8", fileName)
        return if (java.nio.file.Files.isWritable(prod.parent)) prod
        else java.nio.file.Path.of(System.getProperty("user.home"), ".hoshi", fileName)
    }

    /** Wecker-Store-Pfad — s. [resolveDataStorePath]. */
    private fun resolveScheduledStorePath(configured: String): java.nio.file.Path =
        resolveDataStorePath(configured, "scheduled-items.json")

    /**
     * **ListPort-Store (Andi-JA 2026-07-08 „Listen auf die Ring-1-Karte") — flag-gated,
     * default OFF** (`HOSHI_LIST_ENABLED`). ANDERS als beim Timer gibt es HIER KEINEN
     * separaten Persistenz-Schalter: bei ON direkt [JsonFileListStore] — „Persistenz
     * IST das Feature" (PREP-Entwurf 2026-07-02, OFFEN(d) so entschieden: eine
     * Einkaufsliste, die den Neustart nicht überlebt, wäre für Andi wertlos; keine
     * In-Mem-Zwischenstufe in Prod). Bei OFF [ListPort.NONE].
     */
    @Bean
    fun listPort(
        @Value("\${HOSHI_LIST_ENABLED:false}") listEnabled: Boolean,
        @Value("\${hoshi.list.store.path:\${HOSHI_LIST_STORE_PATH:}}") listStorePath: String,
    ): ListPort = if (listEnabled) JsonFileListStore(resolveListStorePath(listStorePath)) else ListPort.NONE

    /** Listen-Store-Pfad — s. [resolveDataStorePath]. */
    private fun resolveListStorePath(configured: String): java.nio.file.Path =
        resolveDataStorePath(configured, "lists.json")

    /**
     * **ListFastpath-Vollzug — flag-gated, default OFF.** Brain-freie Anlage/Lese/
     * Entfernen über den deterministischen Tool-Turn, mit Read-back-Quittung
     * (Andi-JA 2026-07-08). Bei OFF [ListFastpath.DISABLED] ⇒ toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun listFastpath(
        @Value("\${HOSHI_LIST_ENABLED:false}") listEnabled: Boolean,
        listPort: ListPort,
    ): ListFastpath = if (listEnabled) ListFastpath(store = listPort) else ListFastpath.DISABLED

    // ── Nachtmodus (Scheibe 2 von 3) — flag-gated, default OFF (HOSHI_NIGHT_MODE_ENABLED) ──

    /**
     * **NightModeStore — pro-Gerät Nachtmodus-Einstellung, IMMER file-backed** (Muster
     * [listPort]/[JsonFileListStore]-Pfadauflösung). ANDERS als beim Flag selbst gibt es
     * hier KEINEN In-Mem-Zwischenschalter: der Store ist harmlos, solange nichts ihn
     * liest/pusht (das gated ausschließlich [nightModeService] + [NightModeController]
     * über die Decke) — so bleiben Einstellungen über ein Flag-Toggle hinweg erhalten,
     * exakt das [JsonFileSkillStateStore]-Muster (Decke gated den EFFEKT, nicht den Store).
     */
    @Bean
    fun nightModeStore(
        @Value("\${hoshi.night-mode.store.path:\${HOSHI_NIGHT_MODE_STORE_PATH:}}") storePath: String,
    ): JsonFileNightModeStore = JsonFileNightModeStore(resolveNightModeStorePath(storePath))

    /** Nachtmodus-Store-Pfad — s. [resolveDataStorePath]. */
    private fun resolveNightModeStorePath(configured: String): java.nio.file.Path =
        resolveDataStorePath(configured, "night-mode.json")

    /**
     * **NightModeService — Push-Wahrheit + ~60s-Tick, flag-gated, default OFF**
     * (`HOSHI_NIGHT_MODE_ENABLED`). Bei OFF startet [NightModeService.start] KEINEN
     * Poll-Thread und [NightModeService.pushNow]/[NightModeService.tickOnce] tun NICHTS
     * ⇒ byte-neutral. Der [DeviceDownlinkPort] ist [WebSocketConfig.wsDeviceRegistry] —
     * die existiert NUR wenn `/ws/audio` selbst an ist (`@ConditionalOnProperty`), daher
     * hier optional via [ObjectProvider] mit [DeviceDownlinkPort.NOOP]-Fallback (harmlos:
     * eine leere/NOOP-Registry verbindet nie etwas, [NightModeService] bleibt trotzdem
     * IMMER als Bean vorhanden, damit [WebSocketConfig] sie fest injizieren kann, sobald
     * `/ws/audio` an ist).
     */
    @Bean(destroyMethod = "close")
    fun nightModeService(
        @Value("\${HOSHI_NIGHT_MODE_ENABLED:false}") nightModeEnabled: Boolean,
        nightModeStore: JsonFileNightModeStore,
        downlinkProvider: ObjectProvider<DeviceDownlinkPort>,
    ): NightModeService = NightModeService(
        store = nightModeStore,
        downlink = downlinkProvider.getIfAvailable { DeviceDownlinkPort.NOOP },
        enabled = nightModeEnabled,
    ).also { it.start() }

    /**
     * **Timer-Ring-Downlink (Wecker bimmelt AM Ursprungs-Satelliten) — flag-gated,
     * default OFF** (`HOSHI_TIMER_RING_DOWNLINK_ENABLED`; Andi-Gate 2026-07-20,
     * PREP-wecker-am-satelliten). Feuert je [ScheduledItemFireService.onFired] ein
     * `timer_ring`-Frame an die WS-Session des Ursprungs-Satelliten (Retry bis
     * `timer_ack`/Timeout); Satellit nicht verbunden ⇒ ehrlich nur FE-Pfad, eine
     * Log-Zeile. [DeviceDownlinkPort]-Bezug exakt wie [nightModeService] (optional
     * via [ObjectProvider] + NOOP-Fallback). OFF ⇒ byte-neutral (Test-bewiesen).
     */
    @Bean(destroyMethod = "close")
    fun timerRingDownlinkService(
        @Value("\${HOSHI_TIMER_RING_DOWNLINK_ENABLED:false}") ringDownlinkEnabled: Boolean,
        downlinkProvider: ObjectProvider<DeviceDownlinkPort>,
    ): TimerRingDownlinkService = TimerRingDownlinkService(
        downlink = downlinkProvider.getIfAvailable { DeviceDownlinkPort.NOOP },
        enabled = ringDownlinkEnabled,
    ).also { it.start() }

    // ── Sprecher-ID / Stimm-Anlernen (S2) — flag-gated, default OFF ──────────────
    // HOSHI_SPEAKER_ENROLL_ENABLED=false ⇒ WEDER SpeakerEmbedPort NOCH SpeakerProfileStore
    // existieren als Bean, und der (ebenfalls @ConditionalOnProperty) SpeakerController wird
    // nie erzeugt ⇒ keine Mappings, kein Store-File ⇒ byte-neutral. Beide Beans lesen dieselbe
    // URL-Wahrheit `hoshi.speaker.base-url` wie SidecarHealthService (auf ct-106 = Mac-IP:9002).

    /**
     * **SpeakerEmbedPort** — der Adapter gegen den CAM++-Sidecar (:9002, `POST /embed`).
     * `hoshi.speaker.base-url` (Env `HOSHI_SPEAKER_BASE_URL`, relaxed binding) ist DIESELBE
     * Property wie in [SidecarHealthService] — eine URL-Wahrheit, kein zweiter Config-Pfad.
     */
    @Bean
    @ConditionalOnProperty(name = ["HOSHI_SPEAKER_ENROLL_ENABLED"], havingValue = "true")
    fun speakerEmbedPort(
        @Value("\${hoshi.speaker.base-url:http://localhost:9002}") baseUrl: String,
    ): SpeakerEmbedPort = CamppSpeakerAdapter(baseUrl)

    /**
     * **SpeakerProfileStore** — file-backed Registry der Enroll-Profile (Muster:
     * [FileBackedScheduledItemStore]). Pfad: explizit (`hoshi.speaker.store.path` /
     * `HOSHI_SPEAKER_STORE_PATH`) ▷ Prod `/var/lib/hoshi-0.8/speaker-profiles.json`
     * (falls beschreibbar) ▷ Dev `~/.hoshi/speaker-profiles.json`.
     */
    @Bean
    @ConditionalOnProperty(name = ["HOSHI_SPEAKER_ENROLL_ENABLED"], havingValue = "true")
    fun speakerProfileStore(
        @Value("\${hoshi.speaker.store.path:\${HOSHI_SPEAKER_STORE_PATH:}}") storePath: String,
    ): SpeakerProfileStore = SpeakerProfileStore(resolveSpeakerStorePath(storePath))

    /** Speaker-Store-Pfad — s. [resolveDataStorePath]. */
    private fun resolveSpeakerStorePath(configured: String): java.nio.file.Path =
        resolveDataStorePath(configured, "speaker-profiles.json")

    /**
     * **SpeakerIdentifyService (S3) — Stimm-ERKENNUNG, flag-gated `HOSHI_SPEAKER_RECOGNITION_ENABLED`,
     * default OFF ⇒ byte-neutral.** Diese Bean existiert IMMER (der [VoiceInboundController] injiziert
     * sie fest), aber bei OFF ist sie der inerte [SpeakerIdentifyService.DISABLED]-Sentinel: der
     * Controller überspringt jede Erkennung ⇒ kein `speakerContext`, kein Speaker-Event ⇒ heutiger Pfad.
     *
     * Bei ON braucht die Erkennung die READ-ONLY-Wiederverwendung der ENROLL-Beans (denselben
     * [CamppSpeakerAdapter] :9002 + denselben [SpeakerProfileStore], damit frisch enrollte Profile
     * SOFORT erkannt werden — kein zweiter Store, kein Cache-Drift). Die sind
     * `@ConditionalOnProperty(HOSHI_SPEAKER_ENROLL_ENABLED)` ⇒ optional via [ObjectProvider]. Fehlen
     * sie (Enroll OFF), gibt es KEINE Profile zum Vergleichen ⇒ ehrlich DISABLED (immer Gast), statt
     * einen halb-verdrahteten Erkenner zu bauen.
     *
     * **Schwelle** (`hoshi.speaker.recognition.threshold`, Default 0.80): der `known`-Tau des
     * 0.5-`hoshi-speaker-id`-Sidecars (`/verify._decide`), das EINZIGE Band, in dem die Referenz eine
     * ID gebunden hat. Konservativ am `known`-Tau statt im `uncertain`-Band (0.50..0.80) ⇒ Vera-Regel
     * „Fehl-Zuordnung == 0"; per Env nach echter Enroll-ROC nachkalibrierbar.
     */
    @Bean
    fun speakerIdentifyService(
        @Value("\${HOSHI_SPEAKER_RECOGNITION_ENABLED:false}") recognitionEnabled: Boolean,
        @Value("\${hoshi.speaker.recognition.threshold:0.80}") threshold: Double,
        // Abstands-Regel (Live-Fehl-Zuordnung 07.07: Person A→Person B 0.564) — Sieg muss eindeutig sein.
        @Value("\${hoshi.speaker.recognition.margin:\${HOSHI_SPEAKER_RECOGNITION_MARGIN:0.10}}") margin: Double,
        // Score-Aggregation je Profil: best-sample = Bestand; top-two-mean verlangt
        // Unterstuetzung durch zwei Roh-Samples; centroid scored gegen das Mittel-Embedding.
        // Unbekannter Wert ⇒ fail-fast beim Boot statt stillem Default — Urteils-Config darf nicht raten.
        @Value("\${hoshi.speaker.recognition.aggregation:\${HOSHI_SPEAKER_RECOGNITION_AGGREGATION:best-sample}}") aggregation: String,
        // Reversibler Profil-Scope (Codex d66f62b, P0-Folge 21.07): leer = alle Profile (Bestand),
        // "id" = Owner-Verifikation gegen genau dieses Profil, Store bleibt unangetastet. Leere
        // Tokens ("a,,b") NICHT wegfiltern — der Service lehnt sie fail-fast ab (nie still breiter).
        // Echte Profil-IDs nur im privaten Runtime-Override, NIE im Repo/Unit-Template.
        @Value("\${hoshi.speaker.recognition.profiles:\${HOSHI_SPEAKER_RECOGNITION_PROFILES:}}") recognitionProfiles: String,
        embedPortProvider: ObjectProvider<SpeakerEmbedPort>,
        storeProvider: ObjectProvider<SpeakerProfileStore>,
    ): SpeakerIdentifyService {
        if (!recognitionEnabled) return SpeakerIdentifyService.DISABLED
        val embed = embedPortProvider.getIfAvailable()
        val store = storeProvider.getIfAvailable()
        return if (embed != null && store != null) {
            CosineSpeakerIdentifyService(
                embedPort = embed,
                store = store,
                threshold = threshold,
                margin = margin,
                aggregation = SpeakerProfileAggregation.parse(aggregation),
                allowedProfileNames = if (recognitionProfiles.isBlank()) {
                    emptySet()
                } else {
                    recognitionProfiles.split(',').map { it.trim() }.toSet()
                },
            )
        } else {
            SpeakerIdentifyService.DISABLED
        }
    }

    /**
     * **SpeakerCaptureTee (Capture-Tee am Speaker-Identify-Rand) — flag-gated über die BLOSSE
     * Anwesenheit eines Pfads (`hoshi.speaker.capture.dir`/`HOSHI_SPEAKER_CAPTURE_DIR`), Default
     * LEER = AUS ⇒ byte-neutral (kein Objekt im Pfad — [SpeakerCaptureTee.NOOP] tut buchstäblich
     * nichts).** Anders als [speakerIdentifyService] braucht dieser Flip KEIN eigenes Boolean-Flag:
     * ein leerer Pfad IST das AUS, ein gesetzter Pfad IST das AN (Muster
     * [de.hoshi.adapters.escalation.FileBackedEscalationSpendStore] — Pfad-Präsenz als Schalter).
     *
     * **Zweck:** kanal-echte Rohproben (Satellit `/ws/audio` vs. Browser `/api/v1/voice`) für den
     * Offline-A/B-Runner (`tools/speaker-ab`) — BYTE-IDENTISCH zum tatsächlichen Scoring-Input.
     *
     * **Privacy (Betreiber-Entscheidung, s. Klassen-KDoc):** rohes biometrisches Audio inkl. jedem
     * mitsprechenden Gast — bewusst nur für Testphasen scharf schalten (s. Kommentar im
     * systemd-Unit-Template).
     */
    @Bean
    fun speakerCaptureTee(
        @Value("\${hoshi.speaker.capture.dir:\${HOSHI_SPEAKER_CAPTURE_DIR:}}") captureDir: String,
    ): SpeakerCaptureTee =
        if (captureDir.isBlank()) SpeakerCaptureTee.NOOP else FileSpeakerCaptureTee(Paths.get(captureDir))

    /**
     * **Turn-Diary (#10) — flag-gated, default OFF** (`HOSHI_TURN_DIARY_ENABLED`). Bei ON
     * schreibt der JSONL-Adapter je Turn EINE Zeile (Tages-Datei, async, non-throwing);
     * bei OFF [TurnTracePort.NOOP] ⇒ der Controller-Tap huellt gar nicht erst ⇒ byte-neutral.
     * Fuettert North-Star-Laengsschnitt + Nachtschicht-Luecken-Rate.
     */
    @Bean
    fun turnTracePort(
        @Value("\${HOSHI_TURN_DIARY_ENABLED:false}") diaryEnabled: Boolean,
        @Value("\${hoshi.diary.dir:\${HOSHI_TURN_DIARY_DIR:}}") diaryDir: String,
    ): TurnTracePort =
        if (!diaryEnabled) {
            TurnTracePort.NOOP
        } else {
            val dir = when {
                diaryDir.isNotBlank() -> java.nio.file.Path.of(diaryDir)
                java.nio.file.Files.isWritable(java.nio.file.Path.of("/var/lib/hoshi-0.8")) ->
                    java.nio.file.Path.of("/var/lib/hoshi-0.8/diary")
                else -> java.nio.file.Path.of(System.getProperty("user.home"), ".hoshi", "diary")
            }
            JsonlTurnTraceAdapter(directory = dir)
        }

    /**
     * **TimerFastpath-Vollzug (0.5-Port) — flag-gated, default OFF.** Brain-freie Anlage +
     * warme Quittung (Query/Cancel) über den deterministischen Tool-Turn. Die [Clock] ist
     * der EINZIGE `now()`-Punkt (Zone via `hoshi.timer.zone`/`HOSHI_TIMER_ZONE`, Default
     * Europe/Berlin). Bei OFF [TimerFastpath.DISABLED] ⇒ toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun timerFastpath(
        @Value("\${HOSHI_TIMER_ENABLED:false}") timerEnabled: Boolean,
        scheduledItemPort: ScheduledItemPort,
        firedItemsStore: FiredItemsStore,
        @Value("\${hoshi.timer.zone:\${HOSHI_TIMER_ZONE:Europe/Berlin}}") zone: String,
    ): TimerFastpath =
        if (timerEnabled) {
            val zoneId = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("Europe/Berlin"))
            TimerFastpath(
                store = scheduledItemPort,
                clock = Clock.system(zoneId),
                // Klingelnde Timer leben im FiredItemsStore, nicht mehr im Planungs-Store —
                // ohne diese Naht wäre „Stoppe den Timer" beim Klingeln strukturell blind.
                ringingPort = FiredItemsRingingAdapter(firedItemsStore, Clock.system(zoneId)),
            )
        } else {
            TimerFastpath.DISABLED
        }

    /**
     * **RadioFastpath (Musik Stufe A) — flag-gated, default OFF** (`HOSHI_RADIO_ENABLED`).
     * Andi-GO 2026-07-03: risikofreies Internetradio statt Spotify. Bei ON: radio-browser-
     * Suche (Aehnlichkeits-Schwelle — Match nur bei echt aehnlichem Namen, sonst warmes
     * NOT_FOUND) + HA `play_media` auf `HOSHI_RADIO_TARGET` (media_player-Entity des
     * Yamaha RX-V6A, sobald die MusicCast-Integration existiert). OFF ⇒ DISABLED ⇒
     * toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun radioFastpath(
        @Value("\${HOSHI_RADIO_ENABLED:false}") radioEnabled: Boolean,
        @Value("\${HOSHI_RADIO_TARGET:}") radioTarget: String,
        // OSS-Default s. [toolPort]-KDoc (mDNS statt hartkodierter LAN-IP; Andis realer
        // Prod-Wert kommt ausschließlich aus der systemd-Unit-Environment).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
    ): RadioFastpath =
        run {
            // Ohne Token/Ziel ehrlich DISABLED (kein stiller Halb-Zustand).
            val token = resolveHaToken()
            if (radioEnabled && radioTarget.isNotBlank() && token != null) {
                RadioFastpath(
                    radio = HaRadioPort(RadioBrowserAdapter(), haBaseUrl, token),
                    target = radioTarget,
                    enabled = true,
                )
            } else {
                RadioFastpath.DISABLED
            }
        }

    /**
     * **CalculatorFastpath — flag-gated, default OFF** (`HOSHI_CALCULATOR_ENABLED`). Bei ON
     * rechnet Hoshi deterministisch (sicherer Parser, KEIN eval), brain-frei; bei OFF
     * [CalcFastpath.DISABLED] ⇒ toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun calcFastpath(
        @Value("\${HOSHI_CALCULATOR_ENABLED:false}") calculatorEnabled: Boolean,
    ): CalcFastpath = if (calculatorEnabled) CalcFastpath() else CalcFastpath.DISABLED

    /**
     * **DateFastpath — flag-gated, default OFF** (`HOSHI_DATE_FASTPATH_ENABLED`). Bei ON
     * beantwortet Hoshi „welcher Tag ist heute?" deterministisch aus der Code-Uhr (der
     * Brain kennt kein Datum); bei OFF [DateFastpath.DISABLED] ⇒ toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun dateFastpath(
        @Value("\${HOSHI_DATE_FASTPATH_ENABLED:false}") dateEnabled: Boolean,
        @Value("\${hoshi.date.zone:\${HOSHI_DATE_ZONE:Europe/Berlin}}") zone: String,
    ): DateFastpath =
        if (dateEnabled) {
            val zoneId = runCatching { ZoneId.of(zone) }.getOrDefault(DateFastpath.BERLIN)
            DateFastpath(clock = Clock.system(zoneId))
        } else {
            DateFastpath.DISABLED
        }

    /**
     * Extended-Think-Stufe per Sprache/Chat (Andi-Entscheid 06.07) — deterministischer
     * Fastpath, schreibt DENSELBEN Store wie PUT /api/v1/settings/extended-think.
     * Decke zu ⇒ DISABLED ⇒ byte-neutral.
     */
    @Bean
    fun escalationModeFastpath(
        escalationModeStore: JsonFileEscalationModeStore,
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
    ): EscalationModeFastpath =
        if (extendedThinkEnabled) EscalationModeFastpath(StoreEscalationModeSwitch(escalationModeStore))
        else EscalationModeFastpath.DISABLED

    /**
     * Andi-Faktor-Tagesnote (K5, Andi-Entscheid 06.07: per Chat UND Stimme) — eine
     * JSONL-Zeile pro Berlin-Kalendertag, zweite Note überschreibt ehrlich.
     */
    @Bean
    fun dailyNotePort(
        @Value("\${hoshi.andi-faktor.path:\${HOSHI_ANDI_FAKTOR_PATH:}}") path: String,
    ): DailyNotePort = JsonlDailyNoteAdapter(
        if (path.isNotBlank()) java.nio.file.Paths.get(path)
        else java.nio.file.Paths.get(System.getProperty("user.home"), ".hoshi", "andi-faktor.jsonl"),
    )

    @Bean
    fun dailyNoteFastpath(
        dailyNotePort: DailyNotePort,
        @Value("\${HOSHI_ANDI_FAKTOR_ENABLED:false}") enabled: Boolean,
    ): DailyNoteFastpath = if (enabled) DailyNoteFastpath(dailyNotePort) else DailyNoteFastpath.DISABLED

    /**
     * Werkstatt-Notiz (Cowork-Idee 08.07, Hand-adoptiert): „Notiz an die Werkstatt: …"
     * → APPEND-only-Briefkasten (`werkstatt-notizen.jsonl`), den der Orchestrator
     * morgens liest. Bewusst EIGENER Store, KEIN Bus-Generator — Eingabe-Stores
     * tragen nur echte Andi-Eingaben (Tagesnote-Lehre 08.07). Default OFF ⇒
     * DISABLED ⇒ toter Zweig ⇒ byte-neutral.
     */
    @Bean
    fun workshopNotePort(
        @Value("\${hoshi.workshop-note.path:\${HOSHI_WORKSHOP_NOTE_PATH:}}") path: String,
    ): WorkshopNotePort = JsonlWorkshopNoteAdapter(
        if (path.isNotBlank()) java.nio.file.Paths.get(path) else JsonlWorkshopNoteAdapter.defaultPath(),
    )

    /**
     * „Probe."-Fastpath (Golden #20, Satellit-Testtag): binärer Ketten-Beweis
     * Ohren→Draht→Server→Stimme, brain-frei, exakter Treffer. Fürs Testtag-Bündel
     * 08.07 im Unit AN (deterministisch, kein Geschmacks-Gate; Rollback = Flag).
     */
    @Bean
    fun probeFastpath(
        @Value("\${HOSHI_PROBE_ENABLED:false}") enabled: Boolean,
    ): ProbeFastpath = if (enabled) ProbeFastpath() else ProbeFastpath.DISABLED

    @Bean
    fun workshopNoteFastpath(
        workshopNotePort: WorkshopNotePort,
        @Value("\${HOSHI_WORKSHOP_NOTE_ENABLED:false}") enabled: Boolean,
    ): WorkshopNoteFastpath = if (enabled) WorkshopNoteFastpath(workshopNotePort) else WorkshopNoteFastpath.DISABLED

    /**
     * **FactCoverageGate — die Anti-Konfabulations-Wand, flag-gated, default OFF**
     * (`HOSHI_FACT_COVERAGE_ENABLED`). Bei ON deflektet der Orchestrator eine
     * FACT_SHORT-Frage ohne gedecktes Grounding ehrlich, statt den Brain freestylen
     * zu lassen; bei OFF [FactCoverageGate.DISABLED] ⇒ immer Proceed ⇒ byte-neutral.
     */
    /**
     * **D7 Slop-Kill-Stage — flag-gated, default OFF** (`HOSHI_SLOP_KILL_ENABLED`).
     * Deterministischer satz-initialer Phrasen-Filter (Prefix-Hold, DE+EN) + Slop-
     * Rate-Messung. Sitzt VOR der TtsStage (Slop wird nie gesprochen). OFF ⇒
     * [SlopKillStage.DISABLED] (Identity) ⇒ byte-neutral.
     */
    @Bean
    fun slopKillStage(
        @Value("\${HOSHI_SLOP_KILL_ENABLED:false}") slopKillEnabled: Boolean,
    ): SlopKillStage = if (slopKillEnabled) SlopKillStage() else SlopKillStage.DISABLED

    @Bean
    fun factCoverageGate(
        @Value("\${HOSHI_FACT_COVERAGE_ENABLED:false}") factCoverageEnabled: Boolean,
        // Strikte Deckungs-Prüfung (RCA 2026-07-02): tangentiale BM25-Treffer zählen
        // sonst als „gedeckt" und das Brain schwafelt Wissensfragen faktenfrei durch.
        // Default OFF ⇒ byte-identisch; Flip nach Messung (Andi). Ein Konstruktor statt
        // if/else: strict instrumentiert das Diary auch bei Wand-OFF ehrlich strenger.
        @Value("\${HOSHI_FACT_COVERAGE_STRICT_ENABLED:false}") strictEnabled: Boolean,
    ): FactCoverageGate = FactCoverageGate(enabled = factCoverageEnabled, strict = strictEnabled)

    /**
     * Extended Think (S2): Pending-Angebot-Store — merkt das offene „soll ich kurz
     * nachschauen?"-Angebot (Key chatId ?: speakerId ?: "local", TTL 120 s, one-shot).
     * Decke zu (Default) ⇒ NONE ⇒ byte-neutral.
     */
    @Bean
    fun pendingLookupPort(
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
    ): PendingLookupPort =
        if (extendedThinkEnabled) InMemoryPendingLookupStore() else PendingLookupPort.NONE

    /**
     * **Extended Think — der GETEILTE Tages-Spend-Store** (Andi-Auftrag 2026-07-19,
     * herausgelöst aus [escalationPort]): NUR EINE [FileBackedEscalationSpendStore]-
     * INSTANZ für BEIDE Eskalations-Ports ([escalationPort] + [researchEscalationPort])
     * — der 0,50-€-Tages-Cap muss für Nano UND das Recherche-Modell GEMEINSAM gelten
     * (Andi-Vorgabe: „der bestehende Tages-Kosten-Cap bleibt unangetastet und greift
     * weiter"). **Wichtig:** [FileBackedEscalationSpendStore] hält seinen Zähler im
     * RAM (Datei nur beim Konstruieren gelesen, s. dessen KDoc) — ZWEI Instanzen auf
     * demselben Pfad würden sich NICHT live sehen und könnten sich beim Schreiben
     * gegenseitig überschreiben. Eine Spring-Singleton-Bean, in beide Ports injiziert,
     * ist darum keine Stil-Frage, sondern Korrektheits-Voraussetzung. Immer konstruiert
     * (auch bei Decke zu) — reiner, seiteneffektfreier Best-Effort-Dateilese, s.
     * [FileBackedEscalationSpendStore.loadInitial]; ungenutzt, solange kein Port
     * darauf `book()`/`spentTodayCents()` ruft.
     */
    @Bean
    fun escalationSpendStore(
        @Value("\${hoshi.escalation.spend.path:\${HOSHI_ESCALATION_SPEND_PATH:}}") spendPath: String,
    ): EscalationSpendStore = FileBackedEscalationSpendStore(FileBackedEscalationSpendStore.resolveDefaultPath(spendPath))

    /**
     * Extended Think (S1): der OpenAI-Nano-EscalationPort — Egress-Riegel (guard) +
     * Tages-Cap 0,50 €/Tag leben BY CONSTRUCTION im Adapter. Decke zu (Default) ⇒
     * [EscalationPort.NONE] ⇒ kein Netz, kein Spend. Key: DERSELBE OPENAI_API_KEY-
     * Env-Mechanismus wie der OpenAiTtsAdapter; Spend-Store: die GETEILTE
     * [escalationSpendStore]-Bean (s. dortiges KDoc — DERSELBE Cap wie [researchEscalationPort]).
     *
     * **Web-Search-Naht (`hoshi.escalation.web-search`/`HOSHI_ESCALATION_WEB_SEARCH`,
     * Andi-Auftrag 2026-07-19) — default OFF, byte-neutral.** Bei OFF (Default) läuft
     * der Nano-Lookup UNVERÄNDERT über `/v1/chat/completions` (reines Modellwissen).
     * Bei ON darf auch der normale Nano-Pfad echt im Web suchen (Responses API,
     * s. [OpenAiEscalationAdapter.webSearch]-KDoc) — unabhängig vom
     * [researchEscalationPort], der das Web-Search IMMER nutzt (s. dortiges KDoc).
     */
    @Bean
    fun escalationPort(
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${hoshi.escalation.model:}") escalationModel: String,
        escalationSpendStore: EscalationSpendStore,
        // OSS-Default s. [toolPort]-KDoc (mDNS statt hartkodierter LAN-IP; Andis realer
        // Prod-Wert kommt ausschließlich aus der systemd-Unit-Environment).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
        @Value("\${hoshi.escalation.web-search:\${HOSHI_ESCALATION_WEB_SEARCH:false}}") webSearchEnabled: Boolean = false,
    ): EscalationPort =
        if (!extendedThinkEnabled) {
            EscalationPort.NONE
        } else {
            OpenAiEscalationAdapter(
                egress = EgressPort(haBaseUrl = haBaseUrl),
                apiKey = System.getenv("OPENAI_API_KEY"),
                spendStore = escalationSpendStore,
                model = escalationModel.ifBlank { EscalationModelCatalog.DEFAULT_MODEL_ID },
                webSearch = webSearchEnabled,
            )
        }

    /**
     * **Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19)** — eine ZWEITE,
     * unabhängige [EscalationPort]-Instanz NUR für explizite Recherche-Imperative
     * ([de.hoshi.core.pipeline.ResearchIntentRecognizer], z.B. „recherchiere online"),
     * die — wenn konfiguriert — das gründlichere gpt-5.6-Modell statt des
     * Nano-Defaults ruft. Property `hoshi.escalation.research-model` /
     * `HOSHI_ESCALATION_RESEARCH_MODEL`, Default LEER = Feature AUS = EXAKT
     * heutiges Verhalten (Recherche-Phrasen laufen dann unverändert über
     * [escalationPort], s. [de.hoshi.core.pipeline.TurnOrchestrator.escalationChoice]
     * — byte-neutral, per Test bewiesen).
     *
     * **Design-Entscheid — zweite Adapter-Instanz statt Modell-Parameter am
     * [EscalationPort] (wie im Auftrag verlangt begründet):** [EscalationPort] ist
     * bewusst „universal by design" (Kai-Leitplanke, s. dessen KDoc) — KEIN
     * Cloud-/Modell-Wissen im Interface, damit ein künftiger lokaler Adapter
     * (PREP-Vokabular „local-12b") denselben Port unverändert erfüllen kann. Ein
     * `model`-Override-Parameter an `lookup()` würde diese Abstraktion
     * durchlöchern (JEDER Adapter, auch ein lokaler ohne Modell-Auswahl, müsste
     * ihn dann beantworten). Eine zweite, eigenständige [OpenAiEscalationAdapter]-
     * Instanz — EXAKT dasselbe Wiring-Muster wie [escalationPort], nur mit einem
     * zweiten Modell + DEMSELBEN Egress-Riegel + DEMSELBEN [escalationSpendStore]
     * (EIN Tages-Budget für BEIDE Modelle, kein zweites, umgehbares) — passt
     * idiomatisch zu JEDER anderen Naht dieser Datei ([pendingLookupPort],
     * [lookupReplayPort], `weatherLocationAskPort`: je ein eigener Port/eine
     * eigene Instanz mit NONE/Default-Sentinel statt Verzweigung IM Adapter).
     *
     * **Fail-fast** (Muster [SpeakerProfileAggregation.parse]): eine gesetzte,
     * aber unbekannte Modell-ID (nicht in [EscalationModelCatalog.MODELS]) lässt
     * den Bean-Bau werfen — der Prozess startet NICHT mit einer stillen
     * Fehlkonfiguration (Kosten-Config darf nicht raten).
     *
     * Decke [extendedThinkEnabled] zu ⇒ ebenfalls [EscalationPort.NONE] (dieselbe
     * Deploy-Zeit-Wand wie [escalationPort] — kein zweiter Adapter ohne die
     * Extended-Think-Decke).
     *
     * **Video-kritisch (Andi-Auftrag 2026-07-19): `webSearch = true` fest.** Der
     * Recherche-Port ist GENAU für den expliziten „recherchiere online"-Imperativ
     * gedacht — reines Modellwissen (auch beim gründlicheren gpt-5.6-Modell) blieb im
     * LiveSmoke-Beweis bei aktuellen Fragen ehrlich UNKLAR, s.
     * [OpenAiEscalationAdapter.webSearch]-KDoc. Anders als [escalationPort] (Web-Search
     * dort per eigenem Flag optional) MUSS dieser Port also echt im Web suchen können.
     */
    @Bean
    fun researchEscalationPort(
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${hoshi.escalation.research-model:\${HOSHI_ESCALATION_RESEARCH_MODEL:}}") researchModel: String,
        escalationSpendStore: EscalationSpendStore,
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
    ): EscalationPort {
        val model = researchModel.trim()
        if (!extendedThinkEnabled || model.isEmpty()) return EscalationPort.NONE
        // Fail-fast: eine gesetzte, aber unbekannte ID startet NICHT mit stiller Fehl-Semantik.
        EscalationModelCatalog.requireKnown(model)
        return OpenAiEscalationAdapter(
            egress = EgressPort(haBaseUrl = haBaseUrl),
            apiKey = System.getenv("OPENAI_API_KEY"),
            spendStore = escalationSpendStore,
            model = model,
            webSearch = true,
        )
    }

    /**
     * Der Keystone: verdrahtet Policies + den echten Brain (max 1 Call/Turn + Never-Silent).
     *
     * **Agentischer Tool-Pfad — flag-gated, default OFF** (`HOSHI_AGENTIC_TOOLS_ENABLED`,
     * analog zu den anderen Naht-Flags). Bei ON wird die [AgenticToolRegistry] in den
     * Orchestrator gereicht ⇒ der Pass-Pfad läuft über den sicheren `agenticBrainTurn`
     * (Brain darf ein Tool vorschlagen, der Kernel gatet ALLES). Bei OFF (Default) `null`
     * ⇒ der unveränderte `brainTurn`, byte-neutral. Schaltet NUR den Brain-Tool-Pfad —
     * `HOSHI_TOOLS_ENABLED` (Intent-Fast-Path) und `HOSHI_HA_ENABLED` (Executor) bleiben
     * unabhängig.
     */
    @Bean
    fun turnOrchestrator(
        routingPolicy: RoutingPolicy,
        honestyGate: HonestyGate,
        turnPromptAssembler: TurnPromptAssembler,
        personaService: PersonaService,
        responseFormatter: ResponseFormatter,
        brainPort: BrainPort,
        intentClassifier: ToolIntentClassifier,
        capabilityPort: CapabilityPort,
        toolPort: ToolPort,
        lastAreaPort: LastAreaPort,
        // Working-Session (räumliches Gedächtnis S1) — Recall-Seite; Qualifier nötig,
        // weil der WorkingSessionAdapter BEIDE Ports erfüllt (analog episodicRecallPort).
        @Qualifier("workingSessionPort") workingSessionPort: WorkingSessionPort,
        moodTemperaturePort: MoodTemperaturePort,
        timerFastpath: TimerFastpath,
        radioFastpath: RadioFastpath,
        calcFastpath: CalcFastpath,
        listFastpath: ListFastpath,
        dateFastpath: DateFastpath,
        factCoverageGate: FactCoverageGate,
        pendingLookupPort: PendingLookupPort,
        escalationModeFastpath: EscalationModeFastpath,
        dailyNoteFastpath: DailyNoteFastpath,
        workshopNoteFastpath: WorkshopNoteFastpath,
        probeFastpath: ProbeFastpath,
        // Laufzeit-Modell-Wahl (Andi-Video-Auftrag, LookupModelConfig): der SCHNELL-
        // Lookup läuft über den [DelegatingEscalationPort] statt direkt über die
        // [escalationPort]-Bean — `PUT /api/v1/settings/lookup-model` schaltet das
        // Modell um, ohne Neustart. Ohne den `@Qualifier` würde Spring den Parameter-
        // Namen "escalationPort" gegen die GLEICHNAMIGE Bean matchen (der rohe,
        // Boot-fixe Adapter) — der Qualifier erzwingt explizit den Delegaten.
        @Qualifier("delegatingEscalationPort") escalationPort: EscalationPort,
        lookupNotePort: LookupNotePort,
        // Bean aus ExtendedThinkConfig.kt — DIESELBE Instanz wie der Settings-PUT-Rand.
        escalationModeStore: JsonFileEscalationModeStore,
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        // Wetter S3 (Orts-Nachfrage) — Defaults NONE ⇒ ohne Wiring byte-identisch.
        weatherLocationAskPort: WeatherLocationAskPort,
        pendingLocationQuestionPort: PendingLocationQuestionPort,
        @Value("\${HOSHI_AGENTIC_TOOLS_ENABLED:false}") agenticToolsEnabled: Boolean,
        // FACT-Route-Temperatur-Clamp (default OFF, byte-neutral) — spiegelt die anderen
        // HOSHI_*-Flags. Bei ON deckelt der Orchestrator die FACT_SHORT-Temperatur auf
        // <= 0.30 (min(personaTemp, 0.30)); bei OFF Identitaet ⇒ identische Temperatur.
        @Value("\${HOSHI_FACT_LOW_TEMP_ENABLED:false}") factLowTempEnabled: Boolean,
        // Verhör-Detektor S1 (STT-Surprisal, default OFF ⇒ null-Port ⇒ byte-neutral,
        // KEIN Call). Der Score-Endpoint lebt am SELBEN Brain-Sidecar wie MlxBrainAdapter.
        @Value("\${HOSHI_STT_SURPRISAL_ENABLED:false}") sttSurprisalEnabled: Boolean,
        @Value("\${hoshi.brain.base-url:http://localhost:8041}") sttScoreBaseUrl: String,
        // Sprecher-Vertrauens-Gate (P1-Privacy, SpeakerTrust) — schließt die WorkingSession-
        // Recall-Lücke (TurnOrchestrator.effectiveSession, s. dessen KDoc): DASSELBE Flag/
        // DIESELBE Schwelle wie turnPromptAssembler (Entity-/Episodic-Recall) UND
        // ChatStreamController.rememberAfter (Write) — eine Entscheidung, drei Nähte.
        // Default OFF ⇒ byte-neutral.
        @Value("\${HOSHI_SPEAKER_TRUST_ENFORCED:false}") speakerTrustEnforced: Boolean,
        @Value("\${hoshi.speaker.recognition.threshold:0.80}") speakerTrustThreshold: Double,
        // Verbatim-Replay: ein Cache-Hit spricht die gespeicherte Nachschlag-Antwort
        // WÖRTLICH (brain-frei) statt sie vom kleinen Modell paraphrasieren zu lassen —
        // Paraphrase degradiert die Erst-Antwort-Qualität hörbar. Default OFF ⇒ byte-neutral.
        lookupReplayPort: LookupReplayPort,
        @Value("\${HOSHI_LOOKUP_VERBATIM_REPLAY_ENABLED:false}") verbatimReplayEnabled: Boolean,
        // Nachschlag-Intent: die explizite Bitte („schau online nach") wird ein
        // deterministischer Pfad — Bitte = Consent, vorige Frage = Query; ehrliches
        // Brain-Passen registriert ein einlösbares Pending. Default OFF ⇒ byte-neutral.
        @Value("\${HOSHI_LOOKUP_INTENT_ENABLED:false}") lookupIntentEnabled: Boolean,
        // Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19): DIESELBE Property,
        // die [researchEscalationPort] schon fail-fast gegen den Katalog geprüft hat
        // (Spring baut Beans in Abhängigkeits-Reihenfolge — der Fehler wirft dort,
        // BEVOR diese Bean-Methode je läuft). Hier NUR für das ehrliche Anzeige-Label
        // nochmal gelesen — [EscalationModelCatalog.providerLabel] braucht die rohe ID.
        researchEscalationPort: EscalationPort,
        @Value("\${hoshi.escalation.research-model:\${HOSHI_ESCALATION_RESEARCH_MODEL:}}") researchModel: String,
    ): TurnOrchestrator = TurnOrchestrator(
        routing = routingPolicy,
        honesty = honestyGate,
        promptAssembler = turnPromptAssembler,
        persona = personaService,
        formatter = responseFormatter,
        brain = brainPort,
        intent = intentClassifier,
        capability = capabilityPort,
        tools = toolPort,
        lastArea = lastAreaPort,
        // Working-Session (räumliches Gedächtnis S1, default OFF ⇒ NONE ⇒ byte-neutral).
        workingSession = workingSessionPort,
        agenticTools = if (agenticToolsEnabled) AgenticToolRegistry else null,
        // Warmth v2 (default OFF ⇒ NONE ⇒ byte-neutral, identische Temperatur).
        mood = moodTemperaturePort,
        // TimerFastpath (default OFF ⇒ DISABLED ⇒ toter Zweig ⇒ byte-neutral).
        timer = timerFastpath,
        // RadioFastpath (Musik Stufe A, default OFF ⇒ DISABLED ⇒ byte-neutral).
        radio = radioFastpath,
        // CalculatorFastpath (default OFF ⇒ DISABLED ⇒ toter Zweig ⇒ byte-neutral).
        calculator = calcFastpath,
        // ListFastpath (Andi-JA 2026-07-08, default OFF ⇒ DISABLED ⇒ toter Zweig ⇒ byte-neutral).
        list = listFastpath,
        // DateFastpath (default OFF ⇒ DISABLED ⇒ toter Zweig ⇒ byte-neutral).
        date = dateFastpath,
        // FactCoverageGate — Anti-Konfabulation (default OFF ⇒ Proceed ⇒ byte-neutral).
        factCoverage = factCoverageGate,
        // Voice-Intents (Andi 06.07): Stufen-Wechsel + Tagesnote als Fastpaths (DISABLED-Default).
        escalationModeSwitch = escalationModeFastpath,
        dailyNote = dailyNoteFastpath,
        // Werkstatt-Notiz (default OFF => DISABLED => byte-neutral).
        workshopNote = workshopNoteFastpath,
        // Probe-Fastpath (Golden #20; Testtag-Bündel 08.07).
        probe = probeFastpath,
        // Extended Think (S2, default OFF ⇒ NONE/AUS ⇒ byte-neutral).
        escalation = escalationPort,
        lookupNotes = lookupNotePort,
        pendingLookup = pendingLookupPort,
        // Drei-Stufen-Mode als SUPPLIER (pro Turn gelesen): Decke zu ⇒ konstant AUS;
        // offen ⇒ Laufzeit-Store — ein PUT /api/v1/settings/extended-think greift ab
        // dem nächsten Turn, ohne Redeploy (Cache-Read, kein Datei-I/O pro Turn).
        escalationMode = if (extendedThinkEnabled) {
            { escalationModeStore.mode() }
        } else {
            { EscalationMode.AUS }
        },
        // Wetter S3 (Orts-Nachfrage, hinter HOSHI_WEATHER_ENABLED + Seeds-Kriterium).
        weatherAsk = weatherLocationAskPort,
        pendingLocation = pendingLocationQuestionPort,
        // FACT-Route-Temperatur-Clamp (default OFF ⇒ Identitaet ⇒ byte-neutrale Temperatur).
        factLowTemp = factLowTempEnabled,
        // Verhör-Detektor S1 (default OFF ⇒ null ⇒ byte-neutral). Bis der /v1/score-Patch
        // im Prod-Brain lebt (Andi-Restart-Gate), liefert der Adapter ehrlich leer.
        sttSurprisal = if (sttSurprisalEnabled) MlxScoreAdapter(baseUrl = sttScoreBaseUrl) else null,
        // Sprecher-Vertrauens-Gate (P1-Privacy) — schließt die WorkingSession-Recall-Lücke
        // (siehe SpeakerTrust-KDoc + TurnOrchestrator.effectiveSession). Default OFF ⇒
        // byte-neutral, identisch zu turnPromptAssembler/ChatStreamController.
        speakerTrustEnforced = speakerTrustEnforced,
        speakerTrustThreshold = speakerTrustThreshold,
        lookupReplay = lookupReplayPort,
        verbatimReplayEnabled = verbatimReplayEnabled,
        lookupIntentEnabled = lookupIntentEnabled,
        // Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19, default OFF ⇒
        // byte-neutral): das Label ist zugleich der Feature-Schalter im
        // TurnOrchestrator (s. dessen escalationChoice-KDoc) — leer ⇒ NIE
        // researchEscalationPort gewählt, unabhängig davon, ob die Bean selbst
        // NONE oder ein echter Adapter ist.
        researchEscalation = researchEscalationPort,
        researchEscalationProvider = researchModel.trim().let {
            if (it.isEmpty()) "" else EscalationModelCatalog.providerLabel(it)
        },
    )

    /**
     * **Verbatim-Replay-Naht** — exponiert das produktive
     * Overlap-Matching des [NachgeschlagenGroundingProvider] als [LookupReplayPort],
     * damit ein sicherer Cache-Hit Nanos gespeicherte Antwort WÖRTLICH sprechen
     * kann (brain-frei) statt sie vom 4B paraphrasieren zu lassen. Beide Flags zu
     * ⇒ [LookupReplayPort.NONE] ⇒ byte-neutral (der Orchestrator konsultiert den
     * Port dann nie — Test beweist es). Billige stateless Zweit-Instanz desselben
     * Providers (gleiche Datei, gleiche Schwelle, gleiche TTL wie der Grounding-Weg).
     */
    @Bean
    fun lookupReplayPort(
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${HOSHI_LOOKUP_VERBATIM_REPLAY_ENABLED:false}") verbatimReplayEnabled: Boolean,
        @Value("\${hoshi.escalation.lookup.path:\${HOSHI_ESCALATION_LOOKUP_PATH:}}") lookupPath: String,
        @Value("\${HOSHI_LOOKUP_QUOTE_FENCE_ENABLED:true}") quoteFenceEnabled: Boolean,
    ): LookupReplayPort =
        if (extendedThinkEnabled && verbatimReplayEnabled) {
            NachgeschlagenGroundingProvider(
                path = JsonlLookupNoteAdapter.resolveDefaultPath(lookupPath.ifBlank { null }),
                quoteFence = quoteFenceEnabled,
            )
        } else {
            LookupReplayPort.NONE
        }
}
