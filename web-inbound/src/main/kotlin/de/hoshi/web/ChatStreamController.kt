package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.SpeakerTrust
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.TurnTracePort
import de.hoshi.core.port.WorkingSessionWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * **ChatStreamController** — der Inbound-Einstieg eines Voice-/Chat-Turns (M2c).
 *
 * `POST /api/v1/chat/stream` ist ein geschützter Pfad: die [PerimeterWebFilter]
 * -Wand (Trust-Kernel) lässt ihn nur mit gültigem Token (oder über Loopback)
 * durch. Der Body ist ein [ChatRequest] (mind. `text`, optional `language`,
 * `chatId`, `history`, …).
 *
 * Die Antwort ist ein **SSE-Stream der [ChatEvent]** (Start → TextDelta… → Done),
 * direkt vom [TurnOrchestrator] — ein echter Turn durchs neue Hexagon
 * (Routing → Honesty → Prompt → Brain(1×) → Never-Silent). Jedes Event wird via
 * Jackson serialisiert; der `@JsonTypeInfo`-Diskriminator (`"event"`) trägt den
 * Wire-Vertrag mit dem Frontend.
 *
 * **Audio (M2-Add):** Bei `speak=true` (Default) wird der Orchestrator-Stream
 * durch die [TtsStage] gehüllt — Text fließt unverändert durch UND je Satz kommt
 * ein [ChatEvent.AudioChunk] (Voxtral-WAV) dazu. Best-Effort: TTS-Fehler killen
 * den Text-Turn NIE. `speak=false` → reiner Text-Turn (unverändert).
 */
@RestController
class ChatStreamController(
    private val orchestrator: TurnOrchestrator,
    private val ttsStage: TtsStage,
    /**
     * Löst die [ChatRequest.languagePolicy] (AUTO/DE/EN) zu genau EINER konkreten
     * [de.hoshi.core.dto.Language] auf — VOR Orchestrator UND TTS, damit die ganze
     * Pipeline nur DE/EN sieht. Flag-gated (`HOSHI_LANG_AUTO_ENABLED`): AUTO degradiert
     * bei OFF zu DE; ein Legacy-Request ohne Policy nutzt unverändert `language`.
     */
    private val languageResolver: LanguageResolver,
    /**
     * Gatet die [ChatRequest.persona]-Wahl zu genau EINER effektiven [de.hoshi.core.dto.Persona]
     * — VOR dem Orchestrator, exakt wie [languageResolver] für die Sprache. Flag-gated
     * (`HOSHI_PERSONA_ENABLED`): bei OFF kollabieren ALLE Personas auf STANDARD (byte-neutral),
     * bei ON sind die vier Charaktere distinkt.
     */
    private val personaResolver: PersonaResolver,
    /**
     * Store-Hook des Multi-User-Gedächtnisses (default [EntityMemoryWriter.NOOP],
     * d.h. OFF → kein Verhaltensunterschied). Bei `HOSHI_MEMORY_ENABLED=true` der
     * echte sqlite-Adapter. Wird NACH der Antwort gerufen — KEIN Brain-Call.
     */
    @Qualifier("entityMemoryWriter") private val memoryWriter: EntityMemoryWriter,
    /**
     * Store-Hook des episodischen Gedächtnisses (default [EpisodicWriter.NOOP] → OFF,
     * kein Verhaltensunterschied). Bei `HOSHI_EPISODIC_ENABLED=true` der echte
     * sqlite-Adapter. Wird NACH der Antwort gerufen — KEIN Brain-Call, best-effort.
     */
    @Qualifier("episodicWriter") private val episodicWriter: EpisodicWriter,
    /**
     * Store-Hook der Working-Session (räumliches Gedächtnis S1; default
     * [WorkingSessionWriter.NOOP] → OFF, kein Verhaltensunterschied). Bei
     * `HOSHI_WORKING_SESSION_ENABLED=true` der echte in-memory Adapter
     * (speakerId-gekeyt, Gast ⇒ No-op). Wird NACH der Antwort gerufen — KEIN
     * Brain-Call, best-effort, exakt neben den zwei bestehenden Writes.
     */
    @Qualifier("workingSessionWriter") private val workingSessionWriter: WorkingSessionWriter = WorkingSessionWriter.NOOP,
    /**
     * **Concurrent-Brain-Admission (Ticket #9)** — dieselbe globale Singleton-Gate-Bean
     * wie an den Voice-Rändern. Auch der Text-Turn teilt sich den EINEN seriellen Brain;
     * das Gate hält das gemeinsame Permit-Budget global. OFF (Default) ⇒ Passthrough ⇒
     * byte-neutral.
     */
    private val admissionGate: BrainAdmissionGate,
    /**
     * **Turn-Diary (#10)** — der Längsschnitt-Tap am ÄUSSERSTEN Rand des Turns
     * (default [TurnTracePort.NOOP] → kein Verhaltensunterschied, kein Overhead-Pfad).
     * Bei `HOSHI_TURN_DIARY_ENABLED=true` schreibt der JSONL-Adapter je Turn EINE
     * Zeile (Kategorie/Persona/TTFT/Deflect/…) — off-hot-path, best-effort, nie
     * den Turn störend. Füttert den North-Star (Andi-Faktor-Längsschnitt) UND die
     * Lücken-Rate für die beschlossene Nachtschicht.
     */
    private val turnTrace: TurnTracePort = TurnTracePort.NOOP,
    /** D7 Slop-Kill (default DISABLED = Identity, byte-neutral) — sitzt VOR TtsStage. */
    private val slopKill: de.hoshi.core.pipeline.SlopKillStage = de.hoshi.core.pipeline.SlopKillStage.DISABLED,
    /**
     * **Sprecher-Vertrauens-Gate (P1-Privacy) — flag-gated, default OFF ⇒ byte-neutral.**
     * Bei `HOSHI_SPEAKER_TRUST_ENFORCED=false` (Default) verhält sich [rememberAfter] EXAKT
     * wie heute: die von [ChatRequest.speakerContext] BEHAUPTETE `speakerId` wird ungeprüft
     * für den Store-Write genutzt (byte-neutral, testbewiesen). Bei `true` entscheidet
     * [SpeakerTrust.resolve] — dieselbe zentrale Funktion wie im [de.hoshi.core.pipeline.TurnPromptAssembler]-
     * Recall — ob der Claim vertraut wird (Score >= [speakerTrustThreshold]) oder auf einen
     * neutralen Gast kollabiert (kein Write unter einer fremden Id). Bedrohungsmodell +
     * Design-Entscheidungen: [SpeakerTrust]-KDoc.
     */
    @Value("\${HOSHI_SPEAKER_TRUST_ENFORCED:false}") private val speakerTrustEnforced: Boolean = false,
    /**
     * Score-Schwelle des Gates — DIESELBE Property wie die Stimm-Erkennung
     * (`SpeakerIdentifyService`, `hoshi.speaker.recognition.threshold`, Default 0.80) statt
     * eines zweiten, unabhängig driftenden Schwellwerts: EINE Messlatte für „ist das
     * wirklich diese Person", von Recall UND Write geteilt.
     */
    @Value("\${hoshi.speaker.recognition.threshold:0.80}") private val speakerTrustThreshold: Double = 0.80,
    /**
     * **Text-Chat-Namens-Lücke (die erkannte Person soll auch beim TIPPEN erreichbar
     * sein) — flag-gated über dieselbe BESTEHENDE Property wie [SpeakerIdentifyService]**
     * (`HOSHI_SPEAKER_RECOGNITION_ENABLED`, Default OFF ⇒ byte-neutral). Anders als der
     * Voice-/WS-Rand kennt der Text-Chat-Rand nie einen `displayName` (nur eine
     * behauptete `speakerId`) — [SpeakerDisplayNameResolver] löst ihn best-effort aus
     * dem ohnehin vorhandenen enrollten [SpeakerProfileStore] auf (s. dessen KDoc).
     */
    @Value("\${HOSHI_SPEAKER_RECOGNITION_ENABLED:false}") private val speakerDisplayNameResolutionEnabled: Boolean = false,
    /**
     * READ-ONLY-Wiederverwendung derselben [SpeakerProfileStore]-Bean wie Enroll/
     * [SpeakerIdentifyService] (`@ConditionalOnProperty(HOSHI_SPEAKER_ENROLL_ENABLED)`)
     * — via [ObjectProvider], weil die Bean bei OFF gar nicht existiert (Muster
     * `PipelineConfig.speakerIdentifyService`). Fehlt sie, bleibt der Resolver ein
     * Passthrough (kein zweiter Store, kein Crash). Default [SpeakerDisplayNameResolver.providerOf]
     * (statischer `null`) — nur für die direkt konstruierten Tests dieser Klasse;
     * im echten Betrieb liefert Spring den lebenden Provider.
     */
    private val speakerProfileStoreProvider: ObjectProvider<SpeakerProfileStore> =
        SpeakerDisplayNameResolver.providerOf(null),
) {
    /** Baut [SpeakerContext.displayName] best-effort aus dem Enroll-Store nach — s. Ctor-KDoc. */
    private val speakerDisplayNameResolver = SpeakerDisplayNameResolver(
        enabled = speakerDisplayNameResolutionEnabled,
        storeProvider = speakerProfileStoreProvider,
    )

    @PostMapping(
        "/api/v1/chat/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun stream(@RequestBody request: ChatRequest): Flux<ChatEvent> {
        // Policy -> EINE konkrete Sprache (AUTO/DE/EN/Legacy), VOR Orchestrator UND TTS.
        // Flag OFF: AUTO->DE, Legacy/explizit unverändert ⇒ byte-identisch zu heute.
        val effective = languageResolver.resolve(request)
        // Persona -> effektiver Charakter (Flag OFF: ALLE -> STANDARD, byte-neutral), ebenfalls VOR dem Orchestrator.
        val effectivePersona = personaResolver.resolve(request)
        // Text-Chat kennt nie einen displayName (nur die behauptete speakerId) — best-effort
        // aus dem enrollten Profil auflösen (Flag OFF/kein Treffer/schon gesetzt ⇒ unverändert).
        val resolvedSpeaker = speakerDisplayNameResolver.resolve(request.speakerContext)
        val resolved = request.copy(language = effective, persona = effectivePersona, speakerContext = resolvedSpeaker)
        // Brain-Turn durchs globale Admission-Gate (OFF ⇒ Passthrough ⇒ byte-neutral).
        val gated = admissionGate.gate { orchestrator.handle(resolved) }
        // D7: Slop-Filter VOR Memory-Store + TTS (Slop wird weder gespeichert noch gesprochen).
        val deslopped = slopKill.transform(gated)
        val turn = rememberAfter(resolved, deslopped)
        val out = if (resolved.speak) ttsStage.transform(turn, effective, resolved.voice) else turn
        // Diary-Tap um den ÄUSSERSTEN Event-Strom (nach TTS) — die geteilte Logik
        // lebt in [TurnDiaryTap] (derselbe Tap wie am Voice-Rand); NOOP ⇒ ungehüllt
        // zurück (null Overhead, byte-neutral).
        return TurnDiaryTap.traced(
            turnTrace = turnTrace,
            stream = out,
            source = TurnDiaryTap.SOURCE_CHAT,
            chatId = resolved.chatId ?: "",
            persona = resolved.persona.name,
            language = resolved.language.name,
            speak = resolved.speak,
        )
    }

    /**
     * Der Gedächtnis-SCHREIB-Hook ans Ende des Turns — die Logik lebt seit dem ws-Rand-
     * Port in der wiederverwendbaren [RememberAfter]-Klasse (byte-identisch zum vorher hier
     * inline stehenden Code; [ChatStreamWorkingSessionTest]/[ChatStreamSpeakerTrustTest]
     * beweisen es). Der Chat-Rand baut sie aus seinen eigenen injizierten Writer-Beans +
     * Trust-Flags; derselbe Hook hängt am ws-Turn-Ende (nur bei echt erkanntem Sprecher).
     */
    private val remember = RememberAfter(
        memoryWriter = memoryWriter,
        episodicWriter = episodicWriter,
        workingSessionWriter = workingSessionWriter,
        speakerTrustEnforced = speakerTrustEnforced,
        speakerTrustThreshold = speakerTrustThreshold,
    )

    private fun rememberAfter(request: ChatRequest, stream: Flux<ChatEvent>): Flux<ChatEvent> =
        remember.rememberAfter(request, stream)
}
