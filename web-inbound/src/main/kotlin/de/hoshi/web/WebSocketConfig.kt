package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TurnTracePort
import de.hoshi.core.port.WorkingSessionWriter
import de.hoshi.kernel.PerimeterPort
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

/**
 * **WebSocketConfig** — verdrahtet den [AudioWebSocketHandler] an `/ws/audio`
 * (`SimpleUrlHandlerMapping` + `WebSocketHandlerAdapter`, 1:1-Muster aus 0.5).
 *
 * **Flag-gated, default OFF** (`HOSHI_WS_AUDIO_ENABLED`): ist das Flag nicht `true`,
 * greift `@ConditionalOnProperty` nicht ⇒ KEINE Beans ⇒ `/ws/audio` bleibt unmapped
 * ⇒ **byte-neutral** (kein WS-Handler, keine Handler-Mapping-Konkurrenz). Andi schaltet
 * scharf, sobald der Voice-PE-Satellit live verifiziert ist.
 *
 * Der Handler hängt am [PerimeterPort] (gleicher erwarteter Token wie die HTTP-Wand,
 * `hoshi.perimeter.token`/`HOSHI_API_TOKEN`) und am Turn-Funktions-Seam
 * ([TurnOrchestrator.handle]) — die [TtsStage] ist dieselbe Audio-Schicht wie bei
 * `/api/v1/chat/stream`.
 */
@Configuration
@ConditionalOnProperty(name = ["HOSHI_WS_AUDIO_ENABLED"], havingValue = "true")
class WebSocketConfig {

    /**
     * **Device-Downlink-Registry (Nachtmodus-Vorstufe, Scheibe 1 von 3)** — die
     * `:web-inbound`-Impl des hexagonalen `DeviceDownlinkPort` (s. dessen KDoc +
     * [WsDeviceRegistry]). IMMER als Bean vorhanden (sobald `/ws/audio` selbst
     * an ist), unabhängig vom Push-Flag: eine leere Registry ist harmlos (kein
     * Gerät registriert ⇒ `connectedDevices()` leer, `pushToDevice` immer
     * `false`) und steht künftigen Scheiben (Scheduler/Nachtmodus) schon als
     * Spring-Bean bereit, OHNE dass deren Wiring hier nochmal angefasst werden
     * muss. Ob [AudioWebSocketHandler] überhaupt etwas hier einträgt, entscheidet
     * ausschließlich `HOSHI_WS_DOWNLINK_PUSH_ENABLED` (s. unten).
     */
    @Bean
    fun wsDeviceRegistry(objectMapper: ObjectMapper): WsDeviceRegistry = WsDeviceRegistry(objectMapper)

    @Bean
    fun audioWebSocketHandler(
        stt: SttPort,
        orchestrator: TurnOrchestrator,
        ttsStage: TtsStage,
        objectMapper: ObjectMapper,
        // Globale Concurrent-Brain-Admission (Ticket #9) — dieselbe Singleton-Bean wie an
        // den HTTP-Rändern, damit das Permit-Budget über ALLE Endpoints geteilt wird.
        admissionGate: BrainAdmissionGate,
        // Turn-Diary (#10): dieselbe IMMER existierende Bean wie am Chat-/Voice-Rand
        // (PipelineConfig.turnTracePort — bei HOSHI_TURN_DIARY_ENABLED=false ist sie
        // NOOP ⇒ der Tap im Handler hüllt gar nicht erst ⇒ byte-neutral).
        turnTrace: TurnTracePort,
        // Device-Downlink-Registry (s. [wsDeviceRegistry]-KDoc) — immer verdrahtet,
        // scharf wird sie erst über [downlinkPushEnabled].
        wsDeviceRegistry: WsDeviceRegistry,
        // Nachtmodus (Scheibe 2 von 3) — IMMER als Bean vorhanden (PipelineConfig.nightModeService),
        // flag-gated auf HOSHI_NIGHT_MODE_ENABLED; bei OFF ist pushNow ein reines No-op.
        nightModeService: NightModeService,
        // PREP-wecker-am-satelliten (Scheibe 2) — IMMER als Bean vorhanden
        // (PipelineConfig.timerRingDownlinkService), flag-gated auf
        // HOSHI_TIMER_RING_DOWNLINK_ENABLED; bei OFF ist onAck ein reines No-op (ringing
        // bleibt immer leer, da onFired bei OFF nie einen Zustand anlegt).
        timerRingDownlinkService: TimerRingDownlinkService,
        // ── Resolver-Naht am ws-Rand (S-B) — dieselben Beans wie an den anderen Rändern ──
        // (PipelineConfig.personaResolver/languageResolver, flag-gated HOSHI_PERSONA_ENABLED/
        // HOSHI_LANG_AUTO_ENABLED). Der Persona-Store liefert Andis Server-seitige Wahl als
        // Fallback, wenn das `start`-Frame kein persona-Feld trägt (PersonaSettingsConfig).
        personaResolver: PersonaResolver,
        languageResolver: LanguageResolver,
        personaStore: JsonFilePersonaStore,
        // Sprach-Settings-Default (Andi-Auftrag 2026-07-20, Sprachpaket-Kern): dieselbe
        // Bean wie am Settings-Rand (LanguageSettingsController) — ein per
        // /api/v1/settings/language gespeicherter Wunsch wird zum ws-Session-Default,
        // solange das start-Frame kein eigenes language-Feld trägt.
        languageStore: JsonFileLanguageStore,
        // ── Sprecher-Erkennung (S-C) + Gedächtnis-Write (S-D) am ws-Rand ──
        // SpeakerIdentifyService: dieselbe Bean wie am Voice-Rand (PipelineConfig, bei
        // HOSHI_SPEAKER_RECOGNITION_ENABLED=false der inerte DISABLED-Sentinel). Die drei
        // Writer + Trust-Flags bauen dieselbe RememberAfter-Logik wie der Chat-Rand.
        speakerIdentify: SpeakerIdentifyService,
        // Capture-Tee (s. PipelineConfig.speakerCaptureTee): IMMER als Bean vorhanden, bei leerem
        // HOSHI_SPEAKER_CAPTURE_DIR der inerte NOOP-Sentinel ⇒ byte-neutral ohne eigenes Flag hier.
        speakerCapture: SpeakerCaptureTee,
        @Qualifier("entityMemoryWriter") entityMemoryWriter: EntityMemoryWriter,
        @Qualifier("episodicWriter") episodicWriter: EpisodicWriter,
        @Qualifier("workingSessionWriter") workingSessionWriter: WorkingSessionWriter,
        @Value("\${hoshi.perimeter.enabled:true}") perimeterEnabled: Boolean,
        @Value("\${hoshi.perimeter.token:\${HOSHI_API_TOKEN:}}") perimeterToken: String,
        // ── Audio-Byte/Dauer-Cap (Ticket #9) — flag-gated, default OFF/großzügig ⇒ byte-neutral ──
        @Value("\${hoshi.audio.cap.enabled:\${HOSHI_AUDIO_CAP_ENABLED:false}}") audioCapEnabled: Boolean,
        @Value("\${hoshi.audio.cap.max-bytes:\${HOSHI_AUDIO_CAP_MAX_BYTES:1500000}}") audioCapMaxBytes: Int,
        // ── Session-Guard (Zeit-Achse: Dauer-Deckel + Silence-Timeout, Robustheits-
        // Scheibe #1) — flag-gated, default OFF ⇒ byte-neutral (kein Ticker, kein Verhalten) ──
        @Value("\${hoshi.audio.session-guard.enabled:\${HOSHI_AUDIO_SESSION_GUARD_ENABLED:false}}") sessionGuardEnabled: Boolean,
        @Value("\${hoshi.audio.session-guard.max-recording-ms:\${HOSHI_AUDIO_SESSION_GUARD_MAX_RECORDING_MS:30000}}") guardMaxRecordingMs: Long,
        @Value("\${hoshi.audio.session-guard.silence-ms:\${HOSHI_AUDIO_SESSION_GUARD_SILENCE_MS:5000}}") guardSilenceMs: Long,
        // ── Speaker-Downlink-Frame (LED-Erkennungs-Schimmer, S3) — flag-gated, default OFF ⇒ byte-neutral ──
        @Value("\${hoshi.ws.speaker-frame.enabled:\${HOSHI_WS_SPEAKER_FRAME_ENABLED:false}}") speakerFrameEnabled: Boolean,
        // ── Device-Downlink-Push (Nachtmodus-Vorstufe, Scheibe 1) — flag-gated, default
        // OFF ⇒ byte-neutral: kein Registry-Eintrag, kein Hook-Call, kein Frame ──
        @Value("\${hoshi.ws.downlink-push.enabled:\${HOSHI_WS_DOWNLINK_PUSH_ENABLED:false}}") downlinkPushEnabled: Boolean,
        // ── ws-Sprecher-Nähte (S-C Erkennung + S-D Memory-Write) — EIN Flag, default OFF ⇒
        // byte-neutral (kein identify-Call, kein Memory-Write) ──
        @Value("\${hoshi.ws.speaker.enabled:\${HOSHI_WS_SPEAKER_ENABLED:false}}") wsSpeakerEnabled: Boolean,
        // Vertrauens-Gate der Memory-Writes — DIESELBEN Properties wie am Chat-Rand
        // (ChatStreamController): eine Messlatte, kein zweiter driftender Schwellwert.
        @Value("\${HOSHI_SPEAKER_TRUST_ENFORCED:false}") speakerTrustEnforced: Boolean,
        @Value("\${hoshi.speaker.recognition.threshold:0.80}") speakerTrustThreshold: Double,
    ): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = PerimeterPort(enabled = perimeterEnabled, configuredToken = perimeterToken),
            objectMapper = objectMapper,
            // Der Turn-Seam läuft durchs Admission-Gate (OFF ⇒ gate{ } == orchestrator.handle
            // direkt ⇒ byte-neutral). Cancel/Barge-in gibt das Permit über doFinally frei.
            // S-B: languageResolver VORGESCHALTET (Muster der anderen Ränder) — der ws-Request
            // trägt keine languagePolicy ⇒ resolve() gibt die Frame-Sprache unverändert zurück
            // ⇒ byte-neutral; ein künftiges policy-Feld griffe automatisch.
            runTurn = { req ->
                val resolved = req.copy(language = languageResolver.resolve(req))
                admissionGate.gate { orchestrator.handle(resolved) }
            },
            audioCapEnabled = audioCapEnabled,
            maxAudioBytesPerTurn = audioCapMaxBytes,
            sessionGuard = AudioSessionGuard(
                enabled = sessionGuardEnabled,
                maxRecordingDuration = java.time.Duration.ofMillis(guardMaxRecordingMs),
                silenceTimeout = java.time.Duration.ofMillis(guardSilenceMs),
            ),
            turnTrace = turnTrace,
            speakerFrameEnabled = speakerFrameEnabled,
            deviceRegistry = wsDeviceRegistry,
            downlinkPushEnabled = downlinkPushEnabled,
            // Scheibe 2: der Nachtmodus-Initialzustand — KRITISCH, weil das Gerät keine
            // eigene Uhr hat (der Server berechnet `active`, s. NightModeCompute). Bei
            // HOSHI_NIGHT_MODE_ENABLED=false ist pushNow ein reines No-op (byte-neutral);
            // dieser Hook selbst feuert ohnehin nur bei downlinkPushEnabled=true (s.
            // AudioWebSocketHandler.registerDevice).
            onDeviceConnected = { satelliteId, _ ->
                nightModeService.pushNow(satelliteId)
            },
            // PREP-wecker-am-satelliten (Scheibe 2): `timer_ack{id}` vom Satelliten stoppt
            // die Retry-Wiederholung des Ring-Downlinks. Bei HOSHI_TIMER_RING_DOWNLINK_ENABLED
            // =false ist [TimerRingDownlinkService.onAck] ein reines No-op (byte-neutral).
            onTimerAck = { id -> timerRingDownlinkService.onAck(id) },
            // S-B: Persona-Fallback-Kette am ws-Rand — explizites Frame-Feld (parsed im Handler)
            // > Server-Store (Andis Wahl) > STANDARD, flag-gegatet im Resolver (HOSHI_PERSONA_ENABLED
            // OFF ⇒ STANDARD). Leerer Store + kein Feld ⇒ STANDARD wie heute (byte-neutral).
            resolvePersona = { requested -> personaResolver.resolve(requested, personaStore.persona()) },
            // Sprach-Settings-Default (Andi-Auftrag 2026-07-20): Store-Wert (Readback),
            // sonst Language.DEFAULT (DE) — byte-neutral, solange nie ein Wunsch
            // gespeichert wurde (Muster personaStore.persona()).
            defaultLanguage = Language.fromCode(languageStore.languageCode()),
            // S-C: Sprecher-Erkennung am ws-Rand, flag-gated über HOSHI_WS_SPEAKER_ENABLED. Bei OFF
            // (Default) ruft der Handler identify NIE ⇒ byte-neutral; der DISABLED-Sentinel (Bean bei
            // HOSHI_SPEAKER_RECOGNITION_ENABLED=false) wäre ohnehin ein No-op.
            speakerIdentify = speakerIdentify,
            wsSpeakerEnabled = wsSpeakerEnabled,
            speakerCapture = speakerCapture,
            // S-D: derselbe Gedächtnis-SCHREIB-Hook wie am Chat-Rand (RememberAfter), aus den
            // geteilten Writer-Beans + Trust-Flags gebaut. Der Handler ruft ihn NUR bei einem echt
            // erkannten Sprecher (nie bei einem rohen Claim). Identity-Default ⇒ byte-neutral, wenn
            // wsSpeakerEnabled=OFF (dann setzt der Handler nie einen rememberSpeaker).
            rememberAfter = RememberAfter(
                memoryWriter = entityMemoryWriter,
                episodicWriter = episodicWriter,
                workingSessionWriter = workingSessionWriter,
                speakerTrustEnforced = speakerTrustEnforced,
                speakerTrustThreshold = speakerTrustThreshold,
            )::rememberAfter,
        )

    /** Mappt `/ws/audio` auf den Handler (Order -1 = vor dem RequestMappingHandlerMapping). */
    @Bean
    fun wsHandlerMapping(audioWebSocketHandler: AudioWebSocketHandler): HandlerMapping =
        SimpleUrlHandlerMapping(mapOf(AudioWebSocketHandler.WS_AUDIO_PATH to audioWebSocketHandler), -1)

    @Bean
    fun wsHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
