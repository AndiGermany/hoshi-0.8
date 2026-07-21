package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.TurnTracePort
import de.hoshi.kernel.PerimeterPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * **AudioWebSocketHandler (0.8)** — der bidirektionale Sprach-Socket `/ws/audio`.
 * Der Voice-PE-Satellit (und später das Browser-FE) sprechen über EINEN Socket:
 * roh-Mic-Audio rein, Antwort-Audio raus. Dünn am Inbound-Rand (wie
 * [ChatStreamController]/[VoiceInboundController]): WS ↔ [ChatRequest]/[ChatEvent],
 * ruft den schon lebenden Turn ([runTurn]) + die [TtsStage] — KEIN Re-Port der
 * 0.5-`ChatStreamService`.
 *
 * **Wire-Vertrag (firmware-bindend, 0.7-Vokabular):** Die geflashte Firmware spricht
 * 0.7-String-Frames; 0.8 produziert intern [ChatEvent] und übersetzt am WS-Rand über
 * den reinen [ChatEventWsTranslator].
 *
 * **Inbound-Frames:**
 *  - `start{language?,room?,satelliteId?,speakerId?,turnId?}` (JSON-Text) — neuer Turn:
 *    Buffer reset, Metadaten merken.
 *  - binäre Frames — roh-Mic-Bytes, pro Session gepuffert.
 *  - `stop` — finalisieren: Buffer → [stt] → [ChatRequest] → [runTurn] → [TtsStage]
 *    → Frames. **Idempotent** (Doppel-stop verworfen, 0.5-Befund).
 *  - `abort{turnId?}` — Half-Duplex-Barge-in: den in-flight Turn disposen.
 *  - `speaker{speakerId,displayName,score?}` — per-Session-Sprecher setzen.
 *  - `timer_ack{id}` (PREP-wecker-am-satelliten) — Quittung für einen server-initiierten
 *    `timer_ring`-Downlink-Push, stoppt dessen Wiederholung ([onTimerAck]-Hook).
 *
 * **Outbound-Frames:** `transcribing_started`·`transcript{text}`·`no_input`·
 * `llm_thinking`·`llm_start`·`llm_delta`·`tts_audio_start`·`llm_audio{seq,data}`·
 * `tts_audio_end`·`llm_done{ttsHandled}`·`llm_error{stage,message}`·`turn_aborted{turnId}`·
 * `speaker{speakerId}` (LED-Erkennungs-Schimmer, nur bei sicherem Treffer,
 * [speakerFrameEnabled]).
 *
 * **Never-Silent:** Fehler (STT-Sidecar, Stream) enden in einem warmen `llm_error` +
 * `llm_done` — NIE ein stiller Socket. Ein leeres Transkript ⇒ `no_input`.
 *
 * **Auth (ANDI-1):** Der WS-Handshake prüft den Token selbst aus dem `?token=`-Query
 * (Browser/Geräte können beim Upgrade keine Header setzen) gegen denselben
 * [PerimeterPort] wie die HTTP-Wand — ungültig/fehlend (non-loopback) ⇒ Close 1008
 * (POLICY_VIOLATION). Loopback bleibt frei (lokaler Probe-Client).
 *
 * Testbarkeit: der Turn ist ein **Funktions-Seam** ([runTurn] = `orchestrator::handle`),
 * sodass der Handler-Test einen kanned `Flux<ChatEvent>` injizieren kann statt den
 * Brain zu booten (`AudioWebSocketHandlerTest`).
 */
class AudioWebSocketHandler(
    private val stt: de.hoshi.core.port.SttPort,
    private val ttsStage: TtsStage,
    private val perimeter: PerimeterPort,
    private val objectMapper: ObjectMapper,
    private val runTurn: (ChatRequest) -> Flux<ChatEvent>,
    /**
     * **Audio-Byte/Dauer-Cap (Ticket #9, Nils/Lars) — flag-gated, default OFF ⇒ byte-neutral.**
     * Bei OFF (Default) puffert der Handler binäre Mic-Frames EXAKT wie heute (kein Cap-Check).
     * Bei ON wird der pro-Turn akkumulierte Audio-Puffer hart auf [maxAudioBytesPerTurn]
     * gedeckelt: überschreitet ein Frame die Grenze, wird NICHT weiter gepuffert, der Puffer
     * SOFORT freigegeben (Memory-Schutz) und der Turn sauber never-silent abgebrochen
     * (`llm_error` stage=STT + `llm_done`). Schutz gegen einen entlaufenen/bösartigen Mic-
     * Strom, der den [java.io.ByteArrayOutputStream] sonst unbegrenzt füllt (16-GB-OOM).
     */
    private val audioCapEnabled: Boolean = false,
    /** Harte Obergrenze akkumulierter Audio-Bytes pro Turn (nur bei [audioCapEnabled]). */
    private val maxAudioBytesPerTurn: Int = DEFAULT_MAX_AUDIO_BYTES,
    /**
     * **Session-Guard (Zeit-Achse, Robustheits-Scheibe #1 Nils/Eda) — flag-gated,
     * default OFF ⇒ byte-neutral.** Ergänzt den Byte-Cap um die fehlende Zeit-Achse:
     * Dauer-Deckel (Aufnahme zu lang ohne `stop` ⇒ warmer never-silent-Abbruch) +
     * Silence-Timeout (keine Frames mehr ohne Ende-Signal ⇒ finalisieren statt ewig
     * warten). Enforcement über [enforceSessionGuard] (Sweep-Ticker in [handle],
     * Tests sweepen manuell mit fake Clock). Siehe [AudioSessionGuard].
     */
    private val sessionGuard: AudioSessionGuard = AudioSessionGuard(),
    /** Sweep-Intervall des Guard-Watchdogs (nur bei aktivem Guard; Tests: groß + manueller Sweep). */
    private val guardSweepInterval: java.time.Duration = java.time.Duration.ofSeconds(1),
    /**
     * **Turn-Diary (#10) am WS-Rand** — derselbe geteilte [TurnDiaryTap] wie am
     * Chat-/Voice-Rand, mit `source="ws"` ([TurnDiaryTap.SOURCE_WS]). Default
     * [TurnTracePort.NOOP] ⇒ der Tap hüllt gar nicht erst ⇒ byte-neutral ohne
     * Wiring (Muster [VoiceInboundController]); [WebSocketConfig] reicht die
     * immer existierende `turnTracePort`-Bean durch (bei
     * `HOSHI_TURN_DIARY_ENABLED=false` ist DIE selbst NOOP). **Privacy wie
     * gehabt:** KEINE Transkript-Texte und KEINE Geräte-/Session-Kennungen
     * (deviceId/satelliteId/WS-sessionId) im Diary — nur die bestehenden
     * [de.hoshi.core.port.TurnTrace]-Felder + source. Auch die Guard-Abbrüche
     * (Byte-Cap in [onBinary], Dauer-Deckel in [enforceSessionGuard]) zählen
     * ehrlich als [TurnDiaryTap.CATEGORY_ABORTED] mit Grund im error-Feld.
     */
    private val turnTrace: TurnTracePort = TurnTracePort.NOOP,
    /**
     * **Speaker-Downlink-Frame (LED-Erkennungs-Schimmer, S3) — flag-gated, default OFF
     * ⇒ byte-neutral.** Bei ON reicht [onStop] dieses Flag an
     * [ChatEventWsTranslator.translate] durch: ein [ChatEvent.Speaker] mit sicherem
     * Treffer wird zum `speaker{speakerId}`-Downlink-Frame ans Voice-PE-Gerät (Gast
     * bleibt stumm — s. Translator-KDoc, WARUM der Frame nackt bleibt).
     * [WebSocketConfig] reicht hier `HOSHI_WS_SPEAKER_FRAME_ENABLED` durch.
     */
    private val speakerFrameEnabled: Boolean = false,
    /**
     * **Device-Downlink-Registry (Nachtmodus-Vorstufe, Scheibe 1 von 3) —
     * flag-gated über [downlinkPushEnabled], default `null`/OFF ⇒ byte-neutral.**
     * Hält `satelliteId → Outbound-Sink` (s. [WsDeviceRegistry]) — sobald [onStart]
     * eine `satelliteId` kennt, hängt [registerDevice] den Session-Sink hier ein,
     * DAMIT ein Turn-FREMDER Aufrufer (Scheduler/Nachtmodus, künftige Scheiben)
     * über [DeviceDownlinkPort.pushToDevice] gezielt an dieses Gerät senden kann —
     * AUSSERHALB eines Turns. `null` ⇒ [registerDevice] ist ein no-op (nichts wird
     * gespeichert, nichts gepusht) ⇒ exakt der heutige Pfad.
     */
    private val deviceRegistry: WsDeviceRegistry? = null,
    /**
     * **Downlink-Push-Flag** (`HOSHI_WS_DOWNLINK_PUSH_ENABLED`) — schaltet die
     * GESAMTE Registrierung + den optionalen [onDeviceConnected]-Hook scharf.
     * Default `false` ⇒ [registerDevice] tut NICHTS (auch bei gesetztem
     * [deviceRegistry]) ⇒ byte-neutral: kein neuer Zustand, kein Frame. Erst bei
     * `true` beginnt [AudioWebSocketHandler] überhaupt, `satelliteId`s im
     * [deviceRegistry] nachzuhalten — [WebSocketConfig] reicht das Flag durch.
     */
    private val downlinkPushEnabled: Boolean = false,
    /**
     * **Initial-Push-Hook (ws-Connect-Naht) — Scheibe 1: NUR der Haken, KEINE
     * night_mode-Logik.** Funktions-Seam (Muster [runTurn]): wird NACH der
     * Registrierung aufgerufen, sobald [onStart] eine `satelliteId` kennt UND
     * [downlinkPushEnabled] scharf ist. Der No-op-Default (`{ _, _ -> }`) sendet
     * NICHTS — „kein Consumer = kein Frame gesendet". Künftige Scheiben (2: der
     * `night_mode`-Initialzustand) hängen sich HIER ein, ohne [onStart] selbst
     * anzufassen.
     */
    private val onDeviceConnected: (satelliteId: String, downlink: DeviceDownlinkPort) -> Unit = { _, _ -> },
    /**
     * **Persona-Resolver-Naht am ws-Rand (S-B) — Funktions-Seam, Default byte-neutral.**
     * Wandelt die OPTIONAL im `start`-Frame gewählte [Persona] (`null` = kein Feld) in die
     * effektive Persona des Turns. [WebSocketConfig] verdrahtet hier die Fallback-Kette
     * „explizites Frame-Feld > Server-Store > STANDARD" (`personaResolver.resolve(feld,
     * personaStore.persona())`, s. [de.hoshi.core.pipeline.PersonaResolver]) — flag-frei,
     * aber flag-GEGATET durch `HOSHI_PERSONA_ENABLED` im Resolver (OFF ⇒ STANDARD).
     * Der No-op-Default (`{ Persona.STANDARD }`) hält den Handler ohne Wiring byte-neutral:
     * jeder Turn läuft wie heute unter [Persona.STANDARD].
     */
    private val resolvePersona: (Persona?) -> Persona = { Persona.STANDARD },
    /**
     * **Sprecher-ERKENNUNG am ws-Rand (S-C) — flag-gated über [wsSpeakerEnabled], Default
     * `null`/OFF ⇒ byte-neutral.** Read-only-Erkennung ([SpeakerIdentifyService.identify])
     * auf den gepufferten Mic-Bytes in [onStop] (analog [VoiceInboundController]): blockierend
     * ⇒ auf `boundedElastic`, best-effort ⇒ jeder Fehler/Timeout/Gast fällt auf das heutige
     * Verhalten zurück (nie den Turn brechen). **Die Erkennung GEWINNT über einen Client-Claim**
     * (`speaker`/`start`-Frame-`speakerId`): der Claim ist ein bekanntes Audit-P1-Loch (jeder
     * Träger des einen Perimeter-Tokens kann `speakerId:"andi"` behaupten) — die CAM++-Stimme
     * ist die einzige VERIFIZIERTE Quelle. `null` (nicht verdrahtet) ODER
     * [SpeakerIdentifyService.enabled]`=false` ⇒ keine Erkennung, der Claim bleibt maßgeblich
     * wie heute.
     */
    private val speakerIdentify: SpeakerIdentifyService? = null,
    /**
     * **ws-Speaker-Flag** (`HOSHI_WS_SPEAKER_ENABLED`, Default `false`) — schaltet BEIDE
     * server-seitigen Sprecher-Nähte am ws-Rand scharf: die Erkennung ([speakerIdentify] in
     * [onStop]) UND den Gedächtnis-SCHREIB-Hook ([rememberAfter]). OFF ⇒ [onStop] läuft exakt
     * den heutigen Pfad (kein identify-Call, kein Memory-Write) ⇒ byte-neutral für
     * Bestandsgeräte. [WebSocketConfig] reicht das Flag durch.
     */
    private val wsSpeakerEnabled: Boolean = false,
    /**
     * **Gedächtnis-SCHREIB-Hook am ws-Turn-Ende (S-D) — Funktions-Seam, Default Identity ⇒
     * byte-neutral.** Dieselbe [RememberAfter]-Logik wie am Chat-Rand (Entity/Episodic/
     * Working-Session, speakerId-gekeyt, kein zweiter Brain-Call), verdrahtet in
     * [WebSocketConfig]. [onStop] ruft ihn NUR bei einem echt ERKANNTEN Sprecher auf (nie bei
     * einem rohen Client-Claim — sonst Memory-Vergiftung unter einer behaupteten fremden Id);
     * die `isGuest`-Semantik bleibt unangetastet. Der Identity-Default (`{ _, s -> s }`) hüllt
     * nicht ⇒ kein Write, exakt der heutige Pfad.
     */
    private val rememberAfter: (ChatRequest, Flux<ChatEvent>) -> Flux<ChatEvent> = { _, stream -> stream },
    /**
     * **Capture-Tee am Speaker-Identify-Rand — flag-gated über [PipelineConfig.speakerCaptureTee],
     * Default [SpeakerCaptureTee.NOOP] ⇒ byte-neutral.** Rein LESENDE Nebenwirkung DIREKT vor dem
     * [SpeakerIdentifyService.identify]-Aufruf in [onStop]: bei aktivem `HOSHI_SPEAKER_CAPTURE_DIR`
     * landen genau die Bytes, die scoren, kanal-echt (`satellit`) auf der Platte — Basis für den
     * Offline-A/B-Runner (`tools/speaker-ab`). Best-effort/never-throw (s. [SpeakerCaptureTee]-KDoc):
     * ein Capture-Fehler darf [identify] NIE beeinflussen — deshalb läuft der Tee bewusst NUR, wenn
     * [recognitionActive] ohnehin schon identify() ruft (kein Extra-IO, wenn die Erkennung selbst aus ist).
     */
    private val speakerCapture: SpeakerCaptureTee = SpeakerCaptureTee.NOOP,
    /**
     * **Sprach-Settings-Default (Andi-Auftrag 2026-07-20, Sprachpaket-Kern)** — die
     * Session-Sprache, solange das `start`-Frame kein `language`-Feld trägt (s.
     * [openSession]). Default [Language.DEFAULT] (DE) ⇒ byte-neutral ohne Wiring;
     * [WebSocketConfig] reicht hier `Language.fromCode(languageStore.languageCode())`
     * durch (Muster [resolvePersona]/`personaStore.persona()`) — ein per
     * `/api/v1/settings/language` gespeicherter Wunsch greift ab der NÄCHSTEN
     * geöffneten Session, ohne Neustart.
     */
    private val defaultLanguage: Language = Language.DEFAULT,
    /**
     * **Ring-Ack-Hook (PREP-wecker-am-satelliten, Scheibe 2) — Funktions-Seam, Default
     * No-op ⇒ byte-neutral.** Ein `timer_ack{id}`-Inbound-Frame vom Satelliten (Quittung
     * für den server-initiierten `timer_ring`-Downlink-Push) reicht [onText] hier NUR die
     * `id` durch — der Handler selbst kennt keine Ring-Retry-Logik (die lebt im
     * [PipelineConfig]-verdrahteten Ring-Downlink-Service, Muster [onDeviceConnected]).
     * Kein Wiring ⇒ ein `timer_ack`-Frame tut schlicht nichts.
     */
    private val onTimerAck: (id: String) -> Unit = {},
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(javaClass)
    private val T = ChatEventWsTranslator

    // ── Per-Session-State (sessionId-keyed) ─────────────────────────────────────
    private val buffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    internal val sinks = ConcurrentHashMap<String, Sinks.Many<String>>()
    private val turnIds = ConcurrentHashMap<String, String>()
    private val speakers = ConcurrentHashMap<String, SpeakerContext>()
    private val languages = ConcurrentHashMap<String, Language>()
    // Optional im `start`-Frame gewählte Persona je Session (S-B). Fehlt das Feld, steht hier
    // NICHTS ⇒ die Resolver-Naht ([resolvePersona]) fällt auf den Server-Store zurück. Bewusst
    // per-Session (Muster [speakers]/[languages]) — kein globaler Persona-Holder, der bei
    // parallelen Satelliten-Sessions kollidieren würde.
    private val requestedPersonas = ConcurrentHashMap<String, Persona>()
    // Bekannte Geräte-Kennung (satelliteId) je Session, aus dem `start`-Frame geparst.
    // Audit-Befund: bislang NUR geloggt, NIRGENDS gespeichert — jetzt die Grundlage
    // für die Downlink-Registrierung ([registerDevice]) und das saubere Aufräumen
    // in [closeSession] (Muster [speakers]/[languages]).
    private val satelliteIds = ConcurrentHashMap<String, String>()
    /**
     * **Gesprächsverlauf je ws-Session (Andi-Befund 2026-07-21, "Coldplay"-Bug):**
     * Folgefragen über den Satelliten verloren ihren Bezug, weil der [ChatRequest]
     * am ws-Rand OHNE [ChatRequest.history] gebaut wurde — jeder Sprach-Turn war
     * strukturell ein Einzelgespräch, unabhängig davon, was der Nutzer gerade eben
     * gesagt hatte (derselbe Zwei-Turn-Dialog über den Chat-Rand MIT History
     * funktioniert). Diese Map hält die letzten Nachrichten (abwechselnd
     * user/assistant, ältester zuerst) GENAU für die Dauer der WS-Session — Muster
     * [speakers]/[languages]: sessionId-gekeyt, in [openSession] angelegt, in
     * [closeSession] entfernt (kein Leck zwischen Sessions/Satelliten). Bewusst
     * GETRENNT von der personen-gekeyten [de.hoshi.core.port.WorkingSessionPort]
     * (S1+S2, `TurnOrchestrator.effectiveSession`): diese Map braucht KEINE
     * Sprecher-Erkennung — sie greift für JEDE Session, auch Gast/unerkannt — und
     * gewinnt ohnehin nichts über einen NICHT-leeren [ChatRequest.history] (der
     * Orchestrator nimmt Client-history immer vorrangig).
     *
     * Gefüllt NUR nach einem ERFOLGREICH abgeschlossenen Turn ([appendHistoryAfter]);
     * Deckel auf [MAX_HISTORY_MESSAGES] (Schutz gegen unbegrenztes Wachsen im
     * Speicher — die fachliche Fensterung übernimmt weiterhin
     * `HOSHI_MEMORY_WINDOW_TURNS` im `TurnPromptAssembler`). Leer ⇒ [ChatRequest]
     * trägt `history = emptyList()`, byte-identisch zum bisherigen Verhalten
     * (Regressionsgrenze: der ERSTE Turn einer Session bleibt unverändert).
     */
    private val conversationHistories = ConcurrentHashMap<String, List<ChatMessage>>()
    // internal (statt private): Test-Seam für die P1-Race-Regressionstests
    // (überlappender Turn / Terminate-Race), Muster [sinks]/[enforceSessionGuard].
    internal val activeTurns = ConcurrentHashMap<String, Disposable>()
    // Idempotenz-Guard gegen Doppel-stop (FE/Gerät schickt stop ggf. doppelt).
    private val stopHandled = ConcurrentHashMap.newKeySet<String>()
    // Audio-Cap-Guard: Turns, deren Audio-Puffer die Byte-Grenze gerissen hat (Ticket #9).
    // Solange gesetzt, werden weitere binäre Frames verworfen UND ein `stop` ignoriert
    // (der Cap-Abbruch hat den Turn bereits never-silent abgeschlossen). Reset bei `start`.
    private val cappedSessions = ConcurrentHashMap.newKeySet<String>()

    override fun handle(session: WebSocketSession): Mono<Void> {
        // ── Handshake-Auth: Token aus dem ?token=-Query gegen denselben Kernel ──
        val isLoopback = session.handshakeInfo.remoteAddress?.address?.isLoopbackAddress ?: false
        val token = tokenFromQuery(session.handshakeInfo.uri.rawQuery)
        if (perimeter.authorize(WS_AUDIO_PATH, isLoopback, token) is PerimeterPort.PerimeterDecision.Unauthorized) {
            log.warn("[audio-ws] Handshake abgelehnt (kein/ungültiger Token, session {})", session.id)
            return session.close(CloseStatus.POLICY_VIOLATION) // 1008
        }

        val sessionId = session.id
        log.info("[audio-ws] session geöffnet: {}", sessionId)
        openSession(sessionId)
        val sink = sinks[sessionId]!!

        val outbound = session.send(sink.asFlux().map { session.textMessage(it) })

        // ── Session-Guard-Watchdog (Zeit-Achse): NUR bei aktivem Guard ein Ticker,
        // OFF (Default) ⇒ kein Timer, kein Verhalten ⇒ byte-neutral. ──
        val guardTicker: Disposable? =
            if (sessionGuard.enabled)
                Flux.interval(guardSweepInterval).subscribe { enforceSessionGuard(sessionId) }
            else null

        val inbound = session.receive()
            .doOnNext { msg ->
                when (msg.type) {
                    WebSocketMessage.Type.TEXT -> onText(sessionId, msg.payloadAsText)
                    WebSocketMessage.Type.BINARY -> {
                        val buf = msg.payload
                        val bytes = ByteArray(buf.readableByteCount())
                        buf.read(bytes)
                        onBinary(sessionId, bytes)
                    }
                    else -> {} // PING/PONG: ignorieren
                }
            }
            .doOnError { e -> log.warn("[audio-ws] inbound-Fehler: {}", e.message) }
            .doFinally {
                log.info("[audio-ws] session geschlossen: {}", sessionId)
                guardTicker?.dispose()
                closeSession(sessionId)
            }
            .then()

        return Mono.zip(inbound, outbound).then()
    }

    /** Registriert Buffer + Outbound-Sink + Default-Sprache für eine neue Session. */
    internal fun openSession(sessionId: String) {
        buffers[sessionId] = ByteArrayOutputStream()
        sinks[sessionId] = Sinks.many().unicast().onBackpressureBuffer()
        languages[sessionId] = defaultLanguage
        conversationHistories[sessionId] = emptyList()
    }

    // internal (statt private): Test-Seam für die Downlink-Registry-Lifecycle-Tests
    // (beweist die Unregister-Seite ohne vollen FakeWebSocketSession-Umweg,
    // Muster [openSession]/[sinks]/[activeTurns]).
    /** Räumt allen Per-Session-State auf und schließt den Outbound-Sink. */
    internal fun closeSession(sessionId: String) {
        buffers.remove(sessionId)
        sinks.remove(sessionId)?.tryEmitComplete()
        turnIds.remove(sessionId)
        speakers.remove(sessionId)
        languages.remove(sessionId)
        requestedPersonas.remove(sessionId)
        conversationHistories.remove(sessionId)
        activeTurns.remove(sessionId)?.dispose()
        stopHandled.remove(sessionId)
        cappedSessions.remove(sessionId)
        sessionGuard.disarm(sessionId)
        // Downlink-Registrierung abräumen (kein Leak, Muster der Maps oben): NUR
        // relevant, wenn [registerDevice] überhaupt je etwas eingehängt hat
        // (downlinkPushEnabled + deviceRegistry gesetzt) — sonst no-op.
        satelliteIds.remove(sessionId)?.let { deviceRegistry?.unregister(it) }
    }

    // ── Inbound-Dispatch ────────────────────────────────────────────────────────

    /** Text-Frame parsen (Jackson, whitespace-tolerant) und dispatchen. Crash-sicher. */
    internal fun onText(sessionId: String, payload: String) {
        runCatching {
            val node = objectMapper.readTree(payload)
            when (node.path("type").asText("")) {
                "start" -> onStart(sessionId, node)
                "stop" -> onStop(sessionId)
                "abort" -> onAbort(sessionId, node.path("turnId").asText("").takeIf { it.isNotEmpty() })
                "speaker" -> onSpeaker(sessionId, node)
                // PREP-wecker-am-satelliten (Scheibe 2): Quittung für einen server-initiierten
                // `timer_ring`-Downlink-Push — stoppt dessen Wiederholung (s. [onTimerAck]-KDoc).
                "timer_ack" -> node.path("id").asText("").takeIf { it.isNotBlank() }?.let { onTimerAck(it) }
                else -> {} // unbekannter Frame: ignorieren (rückwärtskompatibel)
            }
        }.onFailure { log.warn("[audio-ws] onText fehlgeschlagen: {}", it.message) }
    }

    /**
     * Ein binärer Mic-Frame → puffern, mit hartem Byte-Cap (Ticket #9). Cap-AUS
     * (Default) ⇒ exakt der heutige Pfad (`buffers[sessionId].write(bytes)`),
     * byte-neutral. Cap-AN ⇒ würde der Frame den pro-Turn-Puffer über
     * [maxAudioBytesPerTurn] heben, wird NICHT mehr gepuffert, der Puffer SOFORT
     * freigegeben (Memory-Schutz) und der Turn sauber never-silent abgebrochen
     * (`llm_error` stage=STT + `llm_done`). Weitere Frames bis zum nächsten `start`
     * werden verworfen ([cappedSessions]).
     */
    internal fun onBinary(sessionId: String, bytes: ByteArray) {
        val buf = buffers[sessionId] ?: return
        sessionGuard.onFrame(sessionId) // Zeit-Achse: Silence-Fenster auffrischen (OFF ⇒ no-op)
        if (!audioCapEnabled) {
            buf.write(bytes)
            return
        }
        if (cappedSessions.contains(sessionId)) return // Turn schon gekappt ⇒ nichts mehr puffern
        if (buf.size().toLong() + bytes.size > maxAudioBytesPerTurn) {
            cappedSessions.add(sessionId)
            sessionGuard.disarm(sessionId) // Cap hat den Turn geschlossen ⇒ Zeit-Guard entschärfen
            buf.reset() // akkumulierte Bytes sofort freigeben (kein Memory-Blowup)
            log.warn(
                "[audio-ws] Audio-Cap überschritten (> {} Bytes) für session {} — Turn abgebrochen",
                maxAudioBytesPerTurn, sessionId,
            )
            sinks[sessionId]?.let { sink ->
                sink.tryEmitNext(withTurnId(sessionId, T.llmError(ChatEvent.Stage.STT, AUDIO_CAP_MESSAGE)))
                sink.tryEmitNext(withTurnId(sessionId, T.llmDone(false)))
            }
            // Diary-Ehrlichkeit: der Cap-Abbruch ist Betriebs-Wahrheit — GENAU EINE
            // ABORTED-Zeile mit Grund AUDIO_CAP (bewusst NICHT NO_INPUT). Direkter
            // record statt Tap/synthetischem Strom: an dieser Naht existiert KEIN
            // ChatEvent-Flux (die Frames gehen imperativ auf den Sink) — siehe
            // [TurnDiaryTap.recordAborted]. NOOP ⇒ früher Return ⇒ null Overhead.
            // Genau einmal pro Turn: [cappedSessions] verwirft alle Folge-Frames.
            TurnDiaryTap.recordAborted(
                turnTrace = turnTrace,
                source = TurnDiaryTap.SOURCE_WS,
                reason = TurnDiaryTap.ABORT_AUDIO_CAP,
                persona = Persona.STANDARD.name,
                language = (languages[sessionId] ?: Language.DEFAULT).name,
                speak = true,
            )
        } else {
            buf.write(bytes)
        }
    }

    private fun onStart(sessionId: String, node: JsonNode) {
        buffers[sessionId]?.reset()
        stopHandled.remove(sessionId) // frischer Turn ⇒ stop wieder erlauben
        cappedSessions.remove(sessionId) // frischer Turn ⇒ Audio-Cap-Abbruch zurücksetzen
        sessionGuard.armRecording(sessionId) // Zeit-Achse scharf: Dauer-Deckel + Silence-Timeout (OFF ⇒ no-op)
        // P1-Fix: ein überlappender start OHNE vorheriges abort (Doppel-Wake, Half-Duplex-
        // AEC am Gerät tot) darf einen noch laufenden Vorgänger-Turn NICHT stillschweigend
        // weiterlaufen lassen — sonst schreiben zwei Turns auf denselben Outbound-Sink
        // (Wortsalat) UND ein späteres abort{turnId=neu} findet activeTurns[sid] bereits
        // vom neuen Turn überschrieben und disposed nie den alten. Im Normalfall (sauberes
        // start→stop→done, kein Overlap) ist hier längst nichts mehr drin ⇒ no-op.
        activeTurns.remove(sessionId)?.dispose()
        val turnId = node.path("turnId").asText("").takeIf { it.isNotEmpty() }
        if (turnId != null) turnIds[sessionId] = turnId else turnIds.remove(sessionId)
        node.path("language").asText("").takeIf { it.isNotBlank() }?.let {
            languages[sessionId] = Language.fromCode(it)
        }
        val sid = node.path("speakerId").asText("").takeIf { it.isNotBlank() }
        if (sid != null) {
            val name = node.path("displayName").asText("").ifBlank { sid.replaceFirstChar { c -> c.uppercase() } }
            speakers[sessionId] = SpeakerContext(sid, name, node.path("score").asDouble(0.0))
        }
        // S-B: ADDITIV das optionale `persona`-Feld lesen (wie [speakerId] oben, tolerant über
        // [Persona.fromCode]). GESETZT ⇒ explizite Wahl (gewinnt über den Server-Store); FEHLT ⇒
        // Eintrag entfernen ⇒ die Resolver-Naht fällt auf den Server-Store zurück. Reset je Turn,
        // damit ein Frame ohne Feld nicht die Wahl eines vorherigen Turns weiterschleppt.
        val personaRaw = node.path("persona").asText("").takeIf { it.isNotBlank() }
        if (personaRaw != null) requestedPersonas[sessionId] = Persona.fromCode(personaRaw)
        else requestedPersonas.remove(sessionId)
        val room = node.path("room").asText("").takeIf { it.isNotBlank() }
        val satelliteId = node.path("satelliteId").asText("").takeIf { it.isNotBlank() }
        if (satelliteId != null) {
            satelliteIds[sessionId] = satelliteId
            registerDevice(sessionId, satelliteId)
        }
        log.info(
            "[audio-ws] start turnId={} lang={} room={} satelliteId={}",
            turnId ?: "-", languages[sessionId]?.code ?: "-", room ?: "-", satelliteId ?: "-",
        )
    }

    /**
     * **Downlink-Registrierung (Nachtmodus-Vorstufe, Scheibe 1).** Hängt den
     * Session-Outbound-Sink unter [satelliteId] im [deviceRegistry] ein, DAMIT
     * [DeviceDownlinkPort.pushToDevice] dieses Gerät später AUSSERHALB eines
     * Turns erreichen kann (Scheduler/Nachtmodus, künftige Scheiben). Danach
     * feuert der optionale [onDeviceConnected]-Hook (No-op-Default ⇒ kein Frame).
     *
     * Flag-gated über [downlinkPushEnabled] (Default `false`): OFF ⇒ diese
     * Methode tut buchstäblich nichts — kein Registry-Put, kein Hook-Call —
     * ⇒ byte-neutral. Ebenso no-op, falls kein [deviceRegistry] verdrahtet ist
     * ODER der Session-Sink (theoretisch) schon weg wäre.
     */
    private fun registerDevice(sessionId: String, satelliteId: String) {
        if (!downlinkPushEnabled) return
        val registry = deviceRegistry ?: return
        val sink = sinks[sessionId] ?: return
        registry.register(satelliteId, sink)
        onDeviceConnected(satelliteId, registry)
    }

    /**
     * `stop`: Buffer flushen → STT → (no_input ODER) Turn → TtsStage → Frames.
     * Idempotent. Never-Silent: STT-Fehler ⇒ leeres Transkript ⇒ `no_input`;
     * Stream-Fehler ⇒ warmer `llm_error` + `llm_done`. Die Subscription wird in
     * [activeTurns] gehalten ⇒ `abort` kann sie disposen (Barge-in).
     */
    private fun onStop(sessionId: String) {
        sessionGuard.disarm(sessionId) // Aufnahme-Phase vorbei ⇒ Zeit-Guard entschärfen (idempotent)
        // Audio-Cap-Abbruch hat den Turn bereits never-silent geschlossen ⇒ stop verwerfen.
        if (cappedSessions.contains(sessionId)) {
            log.debug("[audio-ws] stop nach Audio-Cap-Abbruch für {} — ignoriert", sessionId)
            return
        }
        if (!stopHandled.add(sessionId)) {
            log.debug("[audio-ws] doppelter stop für {} — ignoriert", sessionId)
            return
        }
        val sink = sinks[sessionId] ?: return
        val audio = buffers[sessionId]?.toByteArray() ?: ByteArray(0)
        buffers[sessionId]?.reset()
        val lang = languages[sessionId] ?: Language.DEFAULT

        sink.tryEmitNext(T.transcribingStarted())

        // Perf-Diary: Dauer des SttPort.transcribe-Calls (−1 = nie gemessen ⇒ null in
        // der Trace). doOnNext/doOnError feuern VOR dem downstream-flatMapMany — beim
        // Turn-Bau steht der Wert fest. Auch der Fehler-Pfad (⇒ no_input) misst ehrlich
        // die Dauer des VERSUCHS.
        val sttElapsedMs = java.util.concurrent.atomic.AtomicLong(-1)
        // P1-Fix: einen evtl. noch aktiven Vorgänger-Turn zuerst hart disposen (Gürtel
        // zum Hosenträger von [onStart] — deckt auch den Silence-Timeout-Pfad ab, der
        // onStop OHNE ein vorheriges onStart aufruft, s. [enforceSessionGuard]).
        activeTurns.remove(sessionId)?.dispose()
        // Turn-Identität VOR dem eigentlichen Subscribe im Map ablegen (Disposables.swap()
        // statt des rohen subscribe()-Rückgabewerts): eine SYNCHRON abschließende Pipeline
        // (kanned Fake-STT/-Turn in Tests, ggf. auch ein gecachter Realpfad) kann den
        // Terminate-Callback bereits INNERHALB von .subscribe(...) feuern — bevor eine
        // simple lokale Variable überhaupt zugewiesen wäre. Das Swap-Handle existiert
        // dagegen schon VORHER und ist damit sowohl im Terminate-Callback (identitäts-
        // basiertes remove(key,value) — schützt vor Cross-Turn-Stomp, wenn ein SPÄTER
        // terminierender Vorgänger-Turn den Eintrag eines längst laufenden Nachfolgers
        // träfe) als auch für ein zwischenzeitliches abort() race-frei referenzierbar.
        val turnHandle = Disposables.swap()
        activeTurns[sessionId] = turnHandle
        val transcriptMono = Mono.defer {
            val t0 = System.nanoTime()
            stt.transcribe(audio, lang)
                .doOnNext { sttElapsedMs.set((System.nanoTime() - t0) / 1_000_000) }
                .doOnError { sttElapsedMs.set((System.nanoTime() - t0) / 1_000_000) }
        }
            // Best-Effort/Never-Silent: STT-Sidecar-Fehler ⇒ leeres Transkript (no_input),
            // KEIN Crash, KEIN Brain-Call auf Leere.
            .onErrorResume { e ->
                log.warn("[audio-ws] STT-Fehler: {} — synthetisches leeres Transkript", e.message)
                Mono.just("")
            }

        // S-C: Sprecher-ERKENNUNG nur bei scharfem Flag UND verdrahtetem, aktivem Erkenner.
        // OFF/nicht verdrahtet ⇒ EXAKT der heutige Pfad (kein identify-Call; der Client-Claim
        // aus [speakers] bleibt maßgeblich) ⇒ byte-neutral.
        val recognitionActive = wsSpeakerEnabled && (speakerIdentify?.enabled == true)
        val turnFlux: Flux<ChatEvent> =
            if (!recognitionActive) {
                transcriptMono.flatMapMany { transcript ->
                    buildTurnStream(
                        sessionId, sink, transcript, lang,
                        speakerContext = speakers[sessionId],
                        sttMs = sttElapsedMs.get().takeIf { it >= 0 },
                        rememberSpeaker = null,
                    )
                }
            } else {
                // Blockierender Embed-Call ⇒ boundedElastic (nie auf dem Netty-Loop); best-effort ⇒
                // jeder Fehler fällt auf Gast (= heutiges Verhalten, Client-Claim bleibt maßgeblich).
                val recognitionMono: Mono<Recognition> =
                    Mono.fromCallable {
                        // Capture-Tee VOR identify: exakt dieselben Bytes, die scoren (s. Ctor-KDoc
                        // [speakerCapture]). Wirft nie (best-effort in [FileSpeakerCaptureTee]) ⇒
                        // beeinflusst identify() nie.
                        speakerCapture.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, audio, IDENTIFY_MIME)
                        speakerIdentify!!.identify(audio, IDENTIFY_MIME)
                    }
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorReturn(Recognition.GUEST)
                Mono.zip(transcriptMono, recognitionMono).flatMapMany { tuple ->
                    val transcript = tuple.t1
                    val rec = tuple.t2
                    // Erkennung GEWINNT über den Client-Claim (Audit-P1-Loch: der Claim ist
                    // unverifiziert). Sicherer Treffer ⇒ verifizierter SpeakerContext (Score =
                    // Konfidenz); Gast/kein Treffer ⇒ der heutige Claim bleibt maßgeblich (unerkannt
                    // ⇒ wie heute). Nur ein ECHTER Treffer triggert später den Memory-Write (S-D).
                    val recognized: SpeakerContext? =
                        rec.name?.let { SpeakerContext(it, it.replaceFirstChar { c -> c.uppercase() }, rec.confidence) }
                    buildTurnStream(
                        sessionId, sink, transcript, lang,
                        speakerContext = recognized ?: speakers[sessionId],
                        sttMs = sttElapsedMs.get().takeIf { it >= 0 },
                        rememberSpeaker = recognized,
                    )
                }
            }

        val subscription = turnFlux
            .subscribe(
                { event -> T.translate(event, speakerFrameEnabled)?.let { sink.tryEmitNext(withTurnId(sessionId, it)) } },
                { e ->
                    // Stream-Fehler nach Subscribe: never-silent abschließen.
                    log.error("[audio-ws] Turn-Stream-Fehler: {}", e.message)
                    sink.tryEmitNext(withTurnId(sessionId, T.llmError(ChatEvent.Stage.LLM, e.message ?: "stream error")))
                    sink.tryEmitNext(withTurnId(sessionId, T.llmDone(false)))
                    // Identitätsbasiert: NUR den EIGENEN Eintrag entfernen (remove(key,value)
                    // statt remove(key)). Hat inzwischen ein überlappender Nachfolge-Turn
                    // (start ohne abort) längst einen NEUEN Handle unter demselben Key
                    // hinterlegt, bleibt der unangetastet — kein Cross-Turn-Stomp (P1-Fix).
                    activeTurns.remove(sessionId, turnHandle)
                },
                { activeTurns.remove(sessionId, turnHandle) },
            )
        // Erst JETZT den echten Subscription-Handle einhängen. War [turnHandle] zwischen-
        // zeitlich schon disposed (z.B. onStart/onAbort/closeSession griff, während die
        // Pipeline oben synchron durchlief), disposed update() die Subscription sofort
        // selbst nach (Disposable.Swap-Vertrag) — kein Leck, keine zusätzliche Prüfung nötig.
        turnHandle.update(subscription)
    }

    /**
     * **Der gemeinsame Turn-Bau aus einem Transkript** (aus [onStop] herausgezogen, damit der
     * heutige Pfad UND der Erkennungs-Pfad (S-C) denselben Körper teilen). Leeres Transkript ⇒
     * `no_input` (never-silent, unverändert). Sonst: `transcript`/`llm_thinking`-Frames, der
     * [ChatRequest] (mit aufgelöster [resolvePersona]), der Turn durch [runTurn] + [TtsStage],
     * gehüllt vom Turn-Diary-Tap — exakt wie zuvor inline.
     *
     * [speakerContext] ist der für den TURN maßgebliche Sprecher (Erkennung gewinnt über Claim);
     * [rememberSpeaker] ist NUR bei einem echt ERKANNTEN Sprecher gesetzt und triggert dann den
     * Gedächtnis-SCHREIB-Hook ([rememberAfter]) — ein roher Client-Claim schreibt NIE (S-D,
     * Anti-Memory-Vergiftung).
     */
    private fun buildTurnStream(
        sessionId: String,
        sink: Sinks.Many<String>,
        transcript: String,
        lang: Language,
        speakerContext: SpeakerContext?,
        sttMs: Long?,
        rememberSpeaker: SpeakerContext?,
    ): Flux<ChatEvent> {
        if (transcript.isBlank()) {
            sink.tryEmitNext(T.transcript(""))
            sink.tryEmitNext(T.noInput())
            // Diary-Ehrlichkeit (NO_INPUT-Muster vom Voice-Rand): der stumme Turn ist
            // Betriebs-Wahrheit und bekommt GENAU EINE Trace-Zeile mit Kategorie NO_INPUT +
            // error=STT. Der Wire bleibt UNVERÄNDERT: die `transcript`/`no_input`-Frames oben
            // sind schon raus; das synthetische Error-Event existiert NUR für den Tap und wird
            // hinter ihm weggefiltert (erreicht nie den Subscriber ⇒ nie ein `llm_error`-Frame;
            // NOOP ⇒ Tap hüllt nicht, Filter leert ⇒ exakt das heutige Flux.empty(), byte-neutral).
            return TurnDiaryTap.traced(
                turnTrace = turnTrace,
                stream = Flux.just<ChatEvent>(
                    ChatEvent.Error(message = "no_input", stage = ChatEvent.Stage.STT),
                ),
                source = TurnDiaryTap.SOURCE_WS,
                chatId = "",
                persona = Persona.STANDARD.name,
                language = lang.name,
                speak = true,
                fallbackCategory = TurnDiaryTap.CATEGORY_NO_INPUT,
                // Perf-Diary: auch „nichts verstanden" hat gemessen lange gehört.
                sttMs = sttMs,
            ).filter { false }
        }
        sink.tryEmitNext(T.transcript(transcript))
        sink.tryEmitNext(T.llmThinking())
        // S-B: die effektive Persona aus der Fallback-Kette (explizites Frame-Feld > Server-Store
        // > STANDARD, flag-gegatet im Resolver). Ohne Wiring (Default-Seam) ⇒ STANDARD ⇒ byte-neutral.
        val effectivePersona = resolvePersona(requestedPersonas[sessionId])
        val request = ChatRequest(
            text = transcript,
            speak = true,
            chatId = sessionId,
            speakerContext = speakerContext,
            language = lang,
            persona = effectivePersona,
            // Eingangs-Rand (Tagesnote-Naht): dieser Turn kam über den Voice-PE-WebSocket —
            // dieselbe Kennung wie im Turn-Diary.
            source = TurnDiaryTap.SOURCE_WS,
            // PREP-wecker-am-satelliten (Scheibe 1): die im `start`-Frame geparste `satelliteId`
            // (falls das Gerät eine mitschickt) reist NUR hier mit — Chat/FE setzen dieses Feld
            // nie. `null` (kein `satelliteId`-Feld im Frame) ⇒ byte-neutraler Alt-Pfad, exakt wie
            // heute. Unabhängig vom Downlink-Push-Flag: dieselbe Session→Satellit-Kennung, die
            // [registerDevice] (falls scharf) auch in die Registry hängt.
            originSatelliteId = satelliteIds[sessionId],
            // Session-Gedächtnis (Andi-Befund 2026-07-21, "Coldplay"-Bug): der bisher lückenlos
            // leere Default hier war die WURZEL des Bugs — jeder Sprach-Turn kam ohne Bezug zum
            // vorherigen an. Leer, solange kein Vorgänger-Turn erfolgreich abgeschlossen hat ⇒
            // erster Turn einer Session ist BYTE-IDENTISCH zum bisherigen `emptyList()`-Default.
            history = conversationHistories[sessionId] ?: emptyList(),
        )
        // S-D: Gedächtnis-Write NUR bei echt erkanntem Sprecher. [rememberAfter] umhüllt den
        // ORCHESTRATOR-Strom VOR der TtsStage (wie am Chat-Rand: es sammelt die Antwort-TextDelta
        // und schreibt on-complete, kein zweiter Brain-Call). Kein erkannter Sprecher ⇒ Identity
        // ⇒ kein Write ⇒ byte-neutral.
        val turnRaw = runTurn(request)
        val remembered = if (rememberSpeaker != null) rememberAfter(request, turnRaw) else turnRaw
        // Session-Gedächtnis Fortsetzung: dieselbe Sammel-Technik wie [rememberAfter] (TextDelta
        // einsammeln, NACH `onComplete` schreiben, kein zweiter Brain-Call) — hier ins ws-Session-
        // history statt in die personen-gekeyten Stores, s. [appendHistoryAfter]-KDoc für die
        // Fehler-/Abbruch-Grenzen.
        val withHistory = appendHistoryAfter(sessionId, transcript, remembered)
        // Turn-Diary (#10): Tap um den ÄUSSERSTEN ChatEvent-Strom des WS-Turns (nach TtsStage),
        // source="ws" — dieselben Felder wie am Chat-/Voice-Rand. Privacy: chatId bewusst LEER —
        // die WS-sessionId ist eine Geräte-/Session-Kennung und gehört NICHT ins Diary. NOOP ⇒
        // Strom ungehüllt zurück ⇒ byte-neutral (exakt der heutige Pfad).
        return TurnDiaryTap.traced(
            turnTrace = turnTrace,
            stream = ttsStage.transform(withHistory, lang),
            source = TurnDiaryTap.SOURCE_WS,
            chatId = "",
            persona = request.persona.name,
            language = lang.name,
            speak = true,
            // Perf-Diary: die am Rand gemessene STT-Dauer (Parameter-Naht des Taps).
            sttMs = sttMs,
        )
    }

    /**
     * **Session-Gedächtnis-Sammel-Hook** (Andi-Befund 2026-07-21, "Coldplay"-Bug) — hängt
     * ans Ende von [stream] GENAU wie [RememberAfter.rememberAfter]: sammelt die Antwort aus
     * den [ChatEvent.TextDelta]-Events best-effort mit, KEIN zweiter Brain-Call, KEINE
     * zusätzliche Latenz (reines `doOnNext`/`doOnComplete`, der Wire-Strom fließt unverändert
     * durch jeden Subscriber).
     *
     * Schreibt NUR bei einem ERFOLGREICH abgeschlossenen Turn ins [conversationHistories]-
     * Fenster dieser Session ([appendToHistory]):
     *  - ein [ChatEvent.Error] irgendwo im Strom (never-silent-Fehlerpfad, der Turn läuft trotzdem
     *    bis `onComplete` durch) setzt [failed] ⇒ `doOnComplete` hängt nichts an.
     *  - ein echter Flux-Fehler (seltener "Stream-Fehler nach Subscribe"-Pfad in [onStop]) oder
     *    ein Abbruch/`dispose()` (Barge-in/Overlap) lösen `onComplete` GAR NICHT aus (Reactor-
     *    Vertrag: cancel/error sind eigene Terminalsignale) ⇒ ebenfalls nichts angehängt.
     *  - leeres Transkript (`no_input`) ruft diese Methode gar nicht erst (der frühe Return in
     *    [buildTurnStream] liegt VOR dem [ChatRequest]-Bau).
     */
    private fun appendHistoryAfter(sessionId: String, userText: String, stream: Flux<ChatEvent>): Flux<ChatEvent> {
        val answer = StringBuilder()
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        return stream
            .doOnNext { ev ->
                when (ev) {
                    is ChatEvent.TextDelta -> answer.append(ev.text)
                    is ChatEvent.Error -> failed.set(true)
                    else -> {}
                }
            }
            .doOnComplete {
                if (!failed.get()) appendToHistory(sessionId, userText, answer.toString())
            }
    }

    /**
     * Hängt genau ein user/assistant-Paar ans Ende des Session-Verlaufs und kappt danach auf
     * die letzten [MAX_HISTORY_MESSAGES] Nachrichten (Speicher-Schutz — die fachliche
     * Fensterung fürs Brain-Prompt macht ohnehin `TurnPromptAssembler.windowHistory`).
     * [ConcurrentHashMap.computeIfPresent]: ist der Eintrag zwischenzeitlich schon durch
     * [closeSession] entfernt (Session ging genau zwischen Turn-Ende und diesem Callback zu),
     * bleibt das Fehlen erhalten — KEIN Wiederauferstehen eines Eintrags für eine tote Session.
     */
    private fun appendToHistory(sessionId: String, userText: String, answerText: String) {
        conversationHistories.computeIfPresent(sessionId) { _, existing ->
            (existing + ChatMessage(role = "user", content = userText) + ChatMessage(role = "assistant", content = answerText))
                .takeLast(MAX_HISTORY_MESSAGES)
        }
    }

    /**
     * **Watchdog-Tick des Session-Guards (Zeit-Achse).** Prüft die laufende Aufnahme
     * der Session gegen Dauer-Deckel und Silence-Timeout; `internal` als Test-Seam
     * (fake Clock + manueller Sweep statt Echtzeit). Guard OFF ⇒ no-op (byte-neutral).
     *
     *  - [AudioSessionGuard.Expiry.TOO_LONG]: wie der Byte-Cap, nur auf der Zeit-Achse —
     *    Puffer sofort freigeben, Turn warm & never-silent schließen (`llm_error`
     *    stage=STT + `llm_done`), weitere Frames/`stop` bis zum nächsten `start`
     *    verwerfen ([cappedSessions]-Semantik wiederverwendet).
     *  - [AudioSessionGuard.Expiry.SILENCE]: ehrlicher VAD-Proxy — das Gerät sendet
     *    nicht mehr (tot / `stop` verloren) ⇒ Aufnahme finalisieren: [onStop]
     *    transkribiert, was da ist, statt ewig zu warten. Ein später doch noch
     *    eintreffender echter `stop` wird idempotent verworfen ([stopHandled]).
     *
     * [AudioSessionGuard.expire] drained beim Treffer ⇒ jeder Ablauf feuert genau einmal.
     */
    internal fun enforceSessionGuard(sessionId: String) {
        when (sessionGuard.expire(sessionId)) {
            AudioSessionGuard.Expiry.TOO_LONG -> {
                cappedSessions.add(sessionId)
                buffers[sessionId]?.reset() // akkumulierte Bytes sofort freigeben
                log.warn("[audio-ws] Session-Guard: Aufnahme über Dauer-Deckel für {} — Turn abgebrochen", sessionId)
                sinks[sessionId]?.let { sink ->
                    sink.tryEmitNext(withTurnId(sessionId, T.llmError(ChatEvent.Stage.STT, SESSION_TOO_LONG_MESSAGE)))
                    sink.tryEmitNext(withTurnId(sessionId, T.llmDone(false)))
                }
                // Diary-Ehrlichkeit: auch der Dauer-Deckel-Abbruch bekommt GENAU EINE
                // ABORTED-Zeile, Grund SESSION_GUARD. Direkter record (kein Strom an
                // dieser Naht, siehe onBinary/[TurnDiaryTap.recordAborted]); genau
                // einmal, weil [AudioSessionGuard.expire] Drain-Semantik hat. Der
                // SILENCE-Zweig ist bewusst KEIN Abbruch: er finalisiert über [onStop]
                // und traced dort als normaler Turn bzw. NO_INPUT.
                TurnDiaryTap.recordAborted(
                    turnTrace = turnTrace,
                    source = TurnDiaryTap.SOURCE_WS,
                    reason = TurnDiaryTap.ABORT_SESSION_GUARD,
                    persona = Persona.STANDARD.name,
                    language = (languages[sessionId] ?: Language.DEFAULT).name,
                    speak = true,
                )
            }
            AudioSessionGuard.Expiry.SILENCE -> {
                log.info("[audio-ws] Session-Guard: Silence-Timeout für {} — finalisiere Aufnahme", sessionId)
                onStop(sessionId)
            }
            null -> {} // nicht scharf / unter den Grenzen / Guard OFF
        }
    }

    /**
     * `abort{turnId?}`: Half-Duplex-Barge-in. Disposed den in-flight Turn (Reactor-
     * Cancel propagiert upstream bis zum TTS-Abbruch) und quittiert mit `turn_aborted`
     * + `llm_done`. Eine mitgesendete `turnId` muss auf den aktiven Turn zeigen, sonst
     * ist es ein veralteter Abort (ignoriert).
     */
    private fun onAbort(sessionId: String, reqTurnId: String?) {
        val curTurn = turnIds[sessionId]
        if (reqTurnId == null || reqTurnId == curTurn) {
            sessionGuard.disarm(sessionId) // Barge-in beendet auch eine ggf. laufende Aufnahme
            val aborted = activeTurns.remove(sessionId)?.let { it.dispose(); true } ?: false
            log.info("[audio-ws] abort turnId={} (aktiv={}) → {}", reqTurnId ?: "-", curTurn ?: "-", if (aborted) "abgebrochen" else "nichts aktiv")
            sinks[sessionId]?.tryEmitNext(T.turnAborted(curTurn))
            sinks[sessionId]?.tryEmitNext(withTurnId(sessionId, T.llmDone(false)))
        } else {
            log.debug("[audio-ws] abort für veralteten turnId={} (aktiv={}) — ignoriert", reqTurnId, curTurn)
        }
    }

    private fun onSpeaker(sessionId: String, node: JsonNode) {
        val id = node.path("speakerId").asText("").takeIf { it.isNotBlank() } ?: return
        val name = node.path("displayName").asText("").ifBlank { id.replaceFirstChar { c -> c.uppercase() } }
        val score = node.path("score").asDouble(0.0)
        speakers[sessionId] = SpeakerContext(id, name, score)
        log.debug("[audio-ws] speaker gesetzt: {} (session {})", id, sessionId)
    }

    private fun withTurnId(sessionId: String, frame: String): String =
        T.withTurnId(frame, turnIds[sessionId])

    /** `token` aus dem rohen Query-String (`token=…&x=y`), URL-dekodiert; sonst null. */
    private fun tokenFromQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "token" }
            ?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val WS_AUDIO_PATH = "/ws/audio"

        /**
         * Content-Type-Label der Identify-Probe (S-C) — 1:1 das
         * [VoiceInboundController]-Label: der CAM++-Sidecar erkennt WAV selbst am RIFF-Magic
         * und bekommt [IDENTIFY_MIME] NICHT gereicht; ein inertes, ehrliches Transport-Label.
         */
        const val IDENTIFY_MIME = "application/octet-stream"

        /**
         * Großzügiger Default-Cap für akkumulierte Audio-Bytes pro Turn (Ticket #9):
         * ~1.5 MB. Bei 16 kHz/16-bit Mono-PCM (32 kB/s) entspricht das ~46 s — weit über
         * jedem normalen Sprach-Turn, fängt aber einen entlaufenen Strom hart ab. Nur
         * wirksam bei `audioCapEnabled` (Default OFF ⇒ kein Cap, byte-neutral).
         */
        const val DEFAULT_MAX_AUDIO_BYTES = 1_500_000

        /**
         * Obergrenze des Session-Gesprächsverlaufs ([conversationHistories]): 24 Nachrichten
         * = 12 Turns (user+assistant je Turn) — deckt sich mit `HOSHI_MEMORY_WINDOW_TURNS=12`,
         * das der `TurnPromptAssembler` ohnehin fensterte. Diese Grenze ist NUR der Schutz
         * gegen unbegrenztes Wachsen im Speicher bei einer sehr langen Session, nicht die
         * fachliche Fensterung fürs Brain-Prompt.
         */
        const val MAX_HISTORY_MESSAGES = 24

        /** Warme, ehrliche Absage beim Audio-Cap-Abbruch (stage=STT, never-silent). */
        const val AUDIO_CAP_MESSAGE =
            "Das war mir zu lang am Stück — sag es bitte etwas kürzer, dann krieg ich's zuverlässig mit."

        /** Warme, ehrliche Absage beim Dauer-Deckel des Session-Guards (Zeit-Achse, never-silent). */
        const val SESSION_TOO_LONG_MESSAGE =
            "Die Aufnahme lief mir zu lange ohne Ende-Signal — magst du es nochmal etwas kürzer versuchen?"
    }
}
