package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LanguagePolicy
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TurnTracePort
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * **VoiceInboundController** — der Sprach-EINGABE-Rand: Andi spricht Hoshi an,
 * statt zu tippen. `POST /api/v1/voice` nimmt eine WAV-Probe (roh als
 * `application/octet-stream` ODER multipart-Feld `audio`), schickt sie durch den
 * [SttPort] (Whisper :9001) und füttert das Transkript als [ChatRequest] in den
 * schon lebenden [TurnOrchestrator] — Antwort ist derselbe SSE-[ChatEvent]-Stream
 * wie bei [ChatStreamController.stream], plus ein vorangestelltes Transkript-Event.
 *
 * Auth: `/api/v1/voice` liegt hinter der [PerimeterWebFilter]-Wand (geschützter
 * `/api/`-Pfad) — nur mit gültigem Token oder über Loopback erreichbar.
 *
 * **Never-Silent:** Liefert der STT-Sidecar ein leeres Transkript (Stille, zu
 * kurzer Input, Sidecar down → best-effort `""`), endet der Turn in einem warmen,
 * sichtbaren `no_input`-[ChatEvent.Error] (Stage STT) — nie in einer stillen
 * Sackgasse, nie in einem Brain-Call auf Leere.
 */
@RestController
class VoiceInboundController(
    private val stt: SttPort,
    private val orchestrator: TurnOrchestrator,
    private val ttsStage: TtsStage,
    /**
     * Löst die optionale [LanguagePolicy] (AUTO/DE/EN) auf. Bei AUTO + aktivem Flag
     * lässt der Voice-Rand Whisper SELBST auto-detecten (Sprach-Hint weglassen) und
     * leitet die Antwortsprache DANACH aus dem Transkript ab. Flag-gated
     * (`HOSHI_LANG_AUTO_ENABLED`): AUTO degradiert bei OFF zu DE; ohne Policy bleibt
     * der explizite `language`-Param maßgeblich (byte-neutral).
     */
    private val languageResolver: LanguageResolver,
    /**
     * Gatet die per-Turn-Persona-Wahl (Query-Param `persona`) — exakt derselbe
     * Resolver-Fluss wie am Chat-Rand ([ChatStreamController]): der rohe Wire-String
     * wird tolerant über [Persona.fromCode] gelesen (unbekannt/leer → STANDARD) und
     * VOR dem Orchestrator flag-gated aufgelöst (`HOSHI_PERSONA_ENABLED=false` ⇒
     * ALLE → STANDARD ⇒ byte-neutral).
     */
    private val personaResolver: PersonaResolver,
    /**
     * **Concurrent-Brain-Admission (Ticket #9)** — dieselbe globale Singleton-Gate-Bean
     * wie am WS-Rand. Der Brain-Turn läuft durchs Gate (OFF ⇒ Passthrough ⇒ byte-neutral);
     * bei Über-Kapazität gibt es eine warme, sprechbare Absage statt Aufstau.
     */
    private val admissionGate: BrainAdmissionGate,
    /**
     * **Audio-Byte-Cap (Ticket #9) — flag-gated, default OFF/großzügig ⇒ byte-neutral.**
     * Greift VOR dem STT/Brain: ist der (von Spring schon gepufferte) Audio-Body größer als
     * [maxAudioBytes], wird er nicht weiterverarbeitet, sondern endet in einer warmen,
     * sichtbaren STT-Absage. (Die HARTE Decode-Zeit-Grenze für den HTTP-Body ist Spring-
     * Codec-`max-in-memory-size`; siehe Bericht.) Default-Werte = dieselben Flags wie der
     * WS-Cap: `HOSHI_AUDIO_CAP_ENABLED` / `HOSHI_AUDIO_CAP_MAX_BYTES`.
     */
    @Value("\${hoshi.audio.cap.enabled:\${HOSHI_AUDIO_CAP_ENABLED:false}}")
    private val audioCapEnabled: Boolean,
    @Value("\${hoshi.audio.cap.max-bytes:\${HOSHI_AUDIO_CAP_MAX_BYTES:1500000}}")
    private val maxAudioBytes: Int,
    /**
     * **Sprecher-ERKENNUNG (S3) — flag-gated über den Bean-Bau in [PipelineConfig].**
     * Bei `HOSHI_SPEAKER_RECOGNITION_ENABLED=false` (Default) ist dies der
     * [SpeakerIdentifyService.DISABLED]-Sentinel (`enabled=false`): der Controller
     * überspringt jede Erkennung, hängt KEINEN `speakerContext` an und emittiert KEIN
     * [ChatEvent.Speaker] ⇒ byte-neutral zum heutigen Voice-Pfad. Bei ON identifiziert er
     * die Probe VOR dem Turn und trägt den Sprecher als [SpeakerContext] mit dem Request
     * (Gast ⇒ „gast" ⇒ fertige `isGuest`-Härtung ⇒ kein Memory-Load/Write).
     */
    private val speakerIdentify: SpeakerIdentifyService,
    /**
     * **Turn-Diary (#10) am Voice-Rand** — derselbe [TurnDiaryTap] wie am Chat-Rand
     * ([ChatStreamController]), mit `source="voice"`. Default [TurnTracePort.NOOP]
     * ⇒ kein Verhaltensunterschied, kein Overhead-Pfad (byte-neutral ohne Wiring);
     * mit der `turnTracePort`-Bean (bei `HOSHI_TURN_DIARY_ENABLED=true` der
     * JSONL-Adapter) schreibt jeder Voice-Turn GENAU EINE Zeile — vorher war das
     * Diary (Datenbasis des STRICT-Entscheids) blind für Andis Hauptnutzungsweg.
     * Auch der `no_input`-Pfad (leeres Transkript) wird als eigene Kategorie
     * [TurnDiaryTap.CATEGORY_NO_INPUT] ehrlich gezählt — und der Audio-Cap-
     * Frühabbruch (VOR dem STT-Call) als [TurnDiaryTap.CATEGORY_ABORTED] mit
     * Grund [TurnDiaryTap.ABORT_AUDIO_CAP] im error-Feld.
     */
    private val turnTrace: TurnTracePort = TurnTracePort.NOOP,
    /**
     * **Capture-Tee am Speaker-Identify-Rand — flag-gated über [PipelineConfig.speakerCaptureTee],
     * Default [SpeakerCaptureTee.NOOP] ⇒ byte-neutral.** Rein LESENDE Nebenwirkung DIREKT vor dem
     * [SpeakerIdentifyService.identify]-Aufruf in [handle]: bei aktivem `HOSHI_SPEAKER_CAPTURE_DIR`
     * landen genau die Bytes, die scoren, kanal-echt (`browser`) auf der Platte — Basis für den
     * Offline-A/B-Runner (`tools/speaker-ab`). Best-effort/never-throw, kann [identify] nie
     * beeinflussen (s. [SpeakerCaptureTee]-KDoc); läuft nur, wenn [speakerIdentify] ohnehin scharf ist.
     */
    private val speakerCapture: SpeakerCaptureTee = SpeakerCaptureTee.NOOP,
    /**
     * **Sprach-Settings-Default (Andi-Auftrag 2026-07-20, Sprachpaket-Kern)** — greift
     * NUR, wenn der Request KEIN eigenes `language`-Query-Feld trägt (s. [effectiveLanguage]).
     * Dieselbe Bean wie am ws-Rand ([WebSocketConfig]) und am Settings-Rand
     * ([LanguageSettingsController]) — EIN gespeicherter Wunsch, drei Leser.
     */
    private val languageStore: JsonFileLanguageStore = JsonFileLanguageStore(
        java.nio.file.Paths.get(System.getProperty("user.home"), ".hoshi", "language.json"),
    ),
) {

    /**
     * Store-Fallback (Andi-Auftrag 2026-07-20): ein expliziter `language`-Query-Param
     * gewinnt IMMER; fehlt er, greift der gespeicherte Settings-Wunsch; ist auch der
     * leer/nie gesetzt, bleibt es „DE" — byte-neutral zum bisherigen
     * `@RequestParam(defaultValue = "DE")`-Verhalten.
     */
    private fun effectiveLanguage(raw: String?): String = raw ?: languageStore.languageCode() ?: "DE"

    /**
     * Roh-Pfad: der Audio-Body kommt als roher Byte-Strom — `application/octet-stream`,
     * WAV (`audio/wav`/`audio/x-wav`/`audio/wave`) ODER ein vom Browser-`MediaRecorder`
     * geliefertes Container-Format (`audio/webm`/`audio/ogg`, Opus). Der Body wird NICHT
     * server-seitig transkodiert — die Bytes wandern roh in den [SttPort], dessen Whisper-
     * Sidecar (`/asr?encode=true`) per ffmpeg selbst dekodiert. Wir müssen den Content-Type
     * hier nur AKZEPTIEREN (sonst 415), nicht verstehen.
     */
    @PostMapping(
        "/api/v1/voice",
        consumes = [
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/webm",
            "audio/ogg",
        ],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun voiceRaw(
        @RequestBody audio: Mono<ByteArray>,
        @RequestParam(name = "language", required = false) language: String?,
        @RequestParam(name = "languagePolicy", required = false) languagePolicy: String?,
        @RequestParam(name = "speak", defaultValue = "true") speak: Boolean,
        @RequestParam(name = "voice", required = false) voice: String?,
        @RequestParam(name = "persona", required = false) persona: String?,
        @RequestParam(name = "deviceId", required = false) deviceId: String?,
    ): Flux<ChatEvent> =
        audio.flatMapMany { bytes -> handle(bytes, effectiveLanguage(language), languagePolicy, speak, voice, persona, deviceId) }

    /** Multipart-Pfad: die WAV-Datei kommt als Feld `audio` (Browser/Tooling-freundlich). */
    @PostMapping(
        "/api/v1/voice",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun voiceMultipart(
        @RequestPart("audio") audioPart: FilePart,
        @RequestParam(name = "language", required = false) language: String?,
        @RequestParam(name = "languagePolicy", required = false) languagePolicy: String?,
        @RequestParam(name = "speak", defaultValue = "true") speak: Boolean,
        @RequestParam(name = "voice", required = false) voice: String?,
        @RequestParam(name = "persona", required = false) persona: String?,
        @RequestParam(name = "deviceId", required = false) deviceId: String?,
    ): Flux<ChatEvent> =
        DataBufferUtils.join(audioPart.content())
            .map { buffer -> buffer.toBytes() }
            .flatMapMany { bytes -> handle(bytes, effectiveLanguage(language), languagePolicy, speak, voice, persona, deviceId) }

    /**
     * Der gemeinsame Pfad: WAV → STT → (no_input ODER) Turn. Das Transkript wird
     * als [ChatEvent.Step] (`kind="transcript"`) vorangestellt, damit der Client
     * (und der `hoshi voicein`-Beweis) sieht, WAS verstanden wurde.
     *
     * **Bilinguales AUTO:** Bei [LanguagePolicy.AUTO] + aktivem Flag bekommt Whisper
     * KEINEN Sprach-Hint (`null`) → der Sidecar erkennt selbst; die Antwortsprache
     * leitet der [LanguageResolver] DANACH aus dem Transkript ab. Sonst (explizit
     * DE/EN oder Legacy ohne Policy) wird der STT-Hint wie bisher gepinnt.
     */
    private fun handle(
        audioWav: ByteArray,
        languageRaw: String,
        languagePolicyRaw: String?,
        speak: Boolean,
        voice: String? = null,
        personaRaw: String? = null,
        deviceId: String? = null,
    ): Flux<ChatEvent> {
        // ── Audio-Byte-Cap (Ticket #9): zu großer Body ⇒ never-silent STT-Absage, KEIN
        //    STT-/Brain-Call. OFF (Default) ⇒ übersprungen ⇒ byte-neutral. ──
        if (audioCapEnabled && audioWav.size > maxAudioBytes) {
            val aborted = Flux.just<ChatEvent>(
                ChatEvent.Error(
                    message = AudioWebSocketHandler.AUDIO_CAP_MESSAGE,
                    stage = ChatEvent.Stage.STT,
                ),
            )
            // Diary-Ehrlichkeit: auch der Cap-Abbruch ist Betriebs-Wahrheit — GENAU
            // EINE Trace mit eigener Kategorie ABORTED + Grund AUDIO_CAP im error-Feld
            // (bewusst NICHT NO_INPUT: der Guard brach ab, BEVOR gehört wurde). Hier
            // existiert der Strom wirklich (die Error-Absage IST der Wire) ⇒ derselbe
            // Tap-Hüll-Mechanismus wie jeder andere Voice-Turn, KEIN synthetisches
            // Event, kein Filter. errorOverride trägt den Grund statt der uniformen
            // Wire-Stage STT. Kein Start-Event ⇒ persona/language ehrlich nur die
            // ANGEFRAGTEN Werte (wie am no_input-Pfad). NOOP ⇒ Strom ungehüllt
            // zurück ⇒ exakt heutiges Verhalten, byte-neutral.
            return TurnDiaryTap.traced(
                turnTrace = turnTrace,
                stream = aborted,
                source = TurnDiaryTap.SOURCE_VOICE,
                chatId = "",
                persona = personaResolver.resolve(Persona.fromCode(personaRaw)).name,
                language = Language.fromCode(languageRaw).name,
                speak = speak,
                fallbackCategory = TurnDiaryTap.CATEGORY_ABORTED,
                errorOverride = TurnDiaryTap.ABORT_AUDIO_CAP,
            )
        }
        val policy = LanguagePolicy.fromCode(languagePolicyRaw)
        val explicit = Language.fromCode(languageRaw)
        // AUTO + Flag an ⇒ Whisper auto-detectet (Hint weglassen); sonst feste Sprache pinnen.
        val sttHint: Language? =
            if (languageResolver.isAutoDetect(policy)) null else languageResolver.resolve(policy, "", explicit)
        // Perf-Diary: Dauer des SttPort.transcribe-Calls (−1 = nie gemessen ⇒ null
        // in der Trace). doOnNext feuert VOR dem downstream-flatMapMany — beim
        // Turn-Bau steht der Wert also fest. Der Tap bekommt ihn als Parameter
        // (er entsteht an derselben Naht); Text-Turns am Chat-Rand bleiben null.
        val sttElapsedMs = java.util.concurrent.atomic.AtomicLong(-1)
        val transcriptMono = Mono.defer {
            val t0 = System.nanoTime()
            stt.transcribe(audioWav, sttHint)
                .doOnNext { sttElapsedMs.set((System.nanoTime() - t0) / 1_000_000) }
        }

        // ── Sprecher-Erkennung OFF (Default): EXAKT der heutige Pfad — kein speakerContext,
        //    kein Speaker-Event ⇒ byte-neutral. ──
        if (!speakerIdentify.enabled) {
            return transcriptMono.flatMapMany { transcript ->
                turnFrom(
                    transcript, policy, explicit, speak, voice, personaRaw,
                    speakerContext = null, speakerEvent = null, deviceId = deviceId,
                    sttMs = sttElapsedMs.get().takeIf { it >= 0 },
                )
            }
        }

        // ── Sprecher-Erkennung ON: identify PARALLEL zum STT (blockierender Embed-Call auf
        //    boundedElastic, nie auf dem Netty-Loop), best-effort ⇒ Gast (nie crashen, nie
        //    falsch zuordnen). Dann Turn MIT speakerContext + vorangestelltem Speaker-Event. ──
        val recognitionMono: Mono<Recognition> =
            Mono.fromCallable {
                // Capture-Tee VOR identify: exakt dieselben Bytes, die scoren (s. Ctor-KDoc
                // [speakerCapture]). Wirft nie (best-effort in [FileSpeakerCaptureTee]) ⇒
                // beeinflusst identify() nie.
                speakerCapture.capture(SpeakerCaptureTee.CHANNEL_BROWSER, audioWav, IDENTIFY_MIME)
                speakerIdentify.identify(audioWav, IDENTIFY_MIME)
            }
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(Recognition.GUEST)

        return Mono.zip(transcriptMono, recognitionMono).flatMapMany { tuple ->
            val transcript = tuple.t1
            val rec = tuple.t2
            // Vera-Regel: name nur bei sicherem Treffer; sonst „gast" ⇒ fertige isGuest-
            // Härtung im Memory (kein Load/Write). Bekannt ⇒ displayName wie am WS-Rand.
            val speakerContext =
                if (rec.name != null) {
                    SpeakerContext(
                        speakerId = rec.name,
                        displayName = rec.name.replaceFirstChar { it.uppercase() },
                        score = rec.confidence,
                    )
                } else {
                    SpeakerContext(speakerId = GUEST_SPEAKER_ID, score = rec.confidence)
                }
            val speakerEvent = ChatEvent.Speaker(
                recognizedSpeaker = rec.name,
                confidence = rec.confidence,
                isGuest = rec.isGuest,
            )
            turnFrom(
                transcript, policy, explicit, speak, voice, personaRaw, speakerContext, speakerEvent,
                deviceId = deviceId,
                sttMs = sttElapsedMs.get().takeIf { it >= 0 },
            )
        }
    }

    /**
     * Der gemeinsame Turn-Bau aus einem Transkript. [speakerContext]==null + [speakerEvent]==null
     * ⇒ exakt der heutige Voice-Pfad (byte-neutral, Recognition OFF). Bei gesetztem
     * [speakerContext] fließt der Sprecher in den [ChatRequest] (Identity-Isolation) und das
     * [ChatEvent.Speaker] wird dem `transcript`-[ChatEvent.Step] VORANGESTELLT (FE: „wer sprach").
     */
    private fun turnFrom(
        transcript: String,
        policy: LanguagePolicy?,
        explicit: Language,
        speak: Boolean,
        voice: String?,
        personaRaw: String?,
        speakerContext: SpeakerContext?,
        speakerEvent: ChatEvent.Speaker?,
        deviceId: String? = null,
        /** Perf-Diary: gemessene STT-Dauer des Rands (null = nie gemessen). */
        sttMs: Long? = null,
    ): Flux<ChatEvent> {
        if (transcript.isBlank()) {
            // Never-Silent: leeres Transkript → warme, sichtbare STT-Absage (unverändert).
            // Diary-Ehrlichkeit: der stumme Turn ist Betriebs-Wahrheit und bekommt EINE
            // Trace-Zeile mit eigener Kategorie NO_INPUT + error=STT — sonst sähe das
            // Diary nur die verstandenen Voice-Turns. Kein Start-Event ⇒ language ist
            // ehrlich nur die ANGEFRAGTE Sprache (explicit), nie eine erkannte.
            val noInput = Flux.just<ChatEvent>(
                ChatEvent.Error(
                    message = "Ich habe leider nichts verstanden — magst du es noch einmal sagen?",
                    stage = ChatEvent.Stage.STT,
                ),
            )
            return TurnDiaryTap.traced(
                turnTrace = turnTrace,
                stream = noInput,
                source = TurnDiaryTap.SOURCE_VOICE,
                chatId = "",
                persona = personaResolver.resolve(Persona.fromCode(personaRaw)).name,
                language = explicit.name,
                speak = speak,
                fallbackCategory = TurnDiaryTap.CATEGORY_NO_INPUT,
                // Auch der stumme Turn hat GEHÖRT: die gemessene STT-Dauer ist
                // Betriebs-Wahrheit (wie lange dauerte „nichts verstanden").
                sttMs = sttMs,
            )
        }
        // Antwortsprache aus dem Transkript (AUTO) bzw. dem Override/Legacy auflösen.
        val responseLanguage = languageResolver.resolve(policy, transcript, explicit)
        // Persona: roher Query-Param → Persona.fromCode (unbekannt/leer → STANDARD),
        // dann derselbe flag-gated Resolver wie am Chat-Rand (OFF ⇒ STANDARD ⇒ byte-neutral).
        val effectivePersona = personaResolver.resolve(Persona.fromCode(personaRaw))
        val request = ChatRequest(
            text = transcript,
            language = responseLanguage,
            speak = speak,
            persona = effectivePersona,
            // Per-Turn-Voice-Wunsch: nie roh an die Cloud — der TTS-Adapter whitelistet
            // (unbekannt ⇒ Boot-Default). null (kein Param) ⇒ heutiges Verhalten.
            voice = voice,
            // Sprecher-Kontext (Recognition ON) ⇒ Identity-Isolation; null (OFF) ⇒ heutiger Pfad.
            speakerContext = speakerContext,
            // Wecker-Ursprung: die Gerät-Id des Sprech-Clients, damit ein per STIMME
            // gestellter Wecker sein Ursprungs-Gerät kennt (ohne sie bimmelt er überall).
            deviceId = deviceId,
            // Eingangs-Rand (Tagesnote-Naht): dieser Turn kam per Stimme —
            // dieselbe Kennung wie im Turn-Diary.
            source = TurnDiaryTap.SOURCE_VOICE,
        )
        // Brain-Turn durchs globale Admission-Gate (OFF ⇒ Passthrough ⇒ byte-neutral).
        val turn = admissionGate.gate { orchestrator.handle(request) }
        val streamed = if (speak) ttsStage.transform(turn, responseLanguage, request.voice) else turn
        val out = Flux.concat(
            // Speaker-Event nur bei Recognition ON (sonst leer ⇒ byte-neutral, keine Extra-Emission).
            if (speakerEvent != null) Flux.just<ChatEvent>(speakerEvent) else Flux.empty<ChatEvent>(),
            Flux.just<ChatEvent>(ChatEvent.Step(kind = "transcript", message = transcript)),
            streamed,
        )
        // Diary-Tap um den ÄUSSERSTEN Strom (inkl. Transkript-Step/Speaker — beide
        // zählen in KEINE Metrik, kein Transkript-Text im Diary): dieselben Felder
        // wie am Chat-Rand, plus source="voice". NOOP ⇒ ungehüllt ⇒ byte-neutral.
        return TurnDiaryTap.traced(
            turnTrace = turnTrace,
            stream = out,
            source = TurnDiaryTap.SOURCE_VOICE,
            chatId = "",
            persona = effectivePersona.name,
            language = responseLanguage.name,
            speak = speak,
            // Perf-Diary: die am Rand gemessene STT-Dauer (Parameter-Naht des Taps).
            sttMs = sttMs,
        )
    }

    /** Liest einen (zusammengefügten) [DataBuffer] in ein ByteArray und gibt ihn frei. */
    private fun DataBuffer.toBytes(): ByteArray {
        val bytes = ByteArray(readableByteCount())
        read(bytes)
        DataBufferUtils.release(this)
        return bytes
    }

    private companion object {
        /**
         * Gast-speakerId der Erkennung — 1:1 die `isGuest`-Härtung des Memory
         * (`de.hoshi.adapters.memory.EntityMemoryAdapter.GUEST`): kein Load, kein Write.
         */
        const val GUEST_SPEAKER_ID = "gast"

        /**
         * Content-Type-Label der Identify-Probe. Der CAM++-Sidecar (:9002 `/embed`)
         * erkennt WAV selbst am RIFF-Magic und bekommt [mime] NICHT gereicht
         * (siehe [de.hoshi.adapters.speaker.CamppSpeakerAdapter]) — daher ein inertes,
         * ehrliches Transport-Label statt eines geratenen Formats.
         */
        const val IDENTIFY_MIME = "application/octet-stream"
    }
}
