package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.pipeline.FactCoverageGate
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import reactor.core.publisher.Flux
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * **TurnDiaryTap** — der EINE gemeinsame Diary-Tap (#10) für ALLE Inbound-Ränder
 * (Chat [ChatStreamController], Voice [VoiceInboundController] UND der WS-Rand
 * [AudioWebSocketHandler]) — extrahiert
 * aus dem Chat-Rand, damit der Voice-Pfad dieselbe Wahrheit schreibt statt einer
 * Kopie (der Befund 2026-07-05: Voice-Turns landeten GAR NICHT im Diary — das
 * Diary war blind für Andis Hauptnutzungsweg, und damit der STRICT-Entscheid).
 *
 * Hüllt den ÄUSSERSTEN Event-Strom (nach TTS/Concat): zählt/misst rein passiv
 * (Start→Kategorie/Provider/Model/Grounded, erste TextDelta→TTFT, AudioChunks,
 * Error) und schreibt in `doFinally` GENAU EINE [TurnTrace]-Zeile.
 * [TurnTracePort.NOOP] ⇒ Strom ungehüllt zurück (null Overhead, byte-neutral —
 * exakt heutiges Verhalten). `deflected` = policy-Start + erste Delta == der
 * exakten FactCoverage-Deflection (DE/EN) — der Lücken-Sensor der Nachtschicht.
 *
 * Nicht-[ChatEvent.Start]/[ChatEvent.TextDelta]/[ChatEvent.AudioChunk]/
 * [ChatEvent.Error]-Events (z.B. das Voice-`transcript`-[ChatEvent.Step] oder
 * [ChatEvent.Speaker]) fließen unverändert durch und zählen in KEINE Metrik —
 * insbesondere landet nie Transkript-Text im Diary (Privacy, wie am Chat-Rand).
 */
internal object TurnDiaryTap {

    /** [TurnTrace.source] eines Text-Turns über `POST /api/v1/chat/stream`. */
    const val SOURCE_CHAT = "chat"

    /** [TurnTrace.source] eines Sprach-Turns über `POST /api/v1/voice`. */
    const val SOURCE_VOICE = "voice"

    /**
     * [TurnTrace.source] eines Sprach-Turns über den WebSocket `/ws/audio`
     * ([AudioWebSocketHandler]) — der Rand, über den der Voice-PE-Satellit spricht.
     */
    const val SOURCE_WS = "ws"

    /**
     * Eigene Diary-Kategorie eines stummen Voice-Turns (leeres Transkript, kein
     * [ChatEvent.Start] ⇒ keine Routing-Kategorie). Stumme Turns sind
     * Betriebs-Wahrheit: ohne diese Zeile sähe das Diary nur die verstandenen
     * Turns und rechnete sich die Voice-Verlässlichkeit schön.
     */
    const val CATEGORY_NO_INPUT = "NO_INPUT"

    /**
     * Eigene Diary-Kategorie eines **Guard-Abbruchs** (Audio-Byte-Cap am Voice-/
     * WS-Rand, Session-Guard-Dauer-Deckel am WS-Rand): der Schutz hat den Turn
     * VOR dem STT-Call abgebrochen — bewusst NICHT mit [CATEGORY_NO_INPUT]
     * verschmolzen (Abgrenzung 2026-07-05): NO_INPUT = „aufgenommen, nichts
     * verstanden" (Verstehens-Wahrheit), ABORTED = „Guard hat abgebrochen,
     * bevor gehört wurde" (Schutz-Entscheidung). Verschmolzen sähe eine
     * steigende Cap-Rate im Diary wie schlechtes STT aus.
     *
     * **Grund-Wahl (dokumentiert):** das `error`-Feld trägt den maschinen-
     * lesbaren ABBRUCH-GRUND ([ABORT_AUDIO_CAP]/[ABORT_SESSION_GUARD]) statt
     * der Wire-Stage — auf dem Draht sind ALLE Guard-Abbrüche per
     * Never-Silent-Konvention uniform `stage=STT`, die Stage unterscheidet
     * also nichts; der Grund ist die eigentliche Betriebs-Wahrheit.
     */
    const val CATEGORY_ABORTED = "ABORTED"

    /** Abbruch-Grund im `error`-Feld: Audio-Byte-Cap gerissen (`HOSHI_AUDIO_CAP_ENABLED`). */
    const val ABORT_AUDIO_CAP = "AUDIO_CAP"

    /** Abbruch-Grund im `error`-Feld: Session-Guard-Dauer-Deckel (Zeit-Achse, TOO_LONG). */
    const val ABORT_SESSION_GUARD = "SESSION_GUARD"

    /**
     * Hüllt [stream] in den Diary-Tap. [fallbackCategory] greift NUR, wenn der
     * Strom kein [ChatEvent.Start] trägt (z.B. der `no_input`-Pfad am Voice-Rand);
     * Default `""` = exakt das bisherige Chat-Verhalten. [errorOverride] (Default
     * `null` = bisheriges Verhalten) gewinnt in der Trace über die Stage aus
     * [ChatEvent.Error] — für Guard-Abbrüche ([CATEGORY_ABORTED]), deren
     * Wire-Stage uniform STT ist und nichts unterscheidet; der GRUND
     * (z.B. [ABORT_AUDIO_CAP]) ist die informativere Wahrheit.
     *
     * [sttMs] (Perf-Diary, additiv — Default `null` = bisheriges Verhalten):
     * die am AUFRUFENDEN Rand gemessene Dauer des `SttPort.transcribe`-Calls.
     * Sie reist als Parameter statt im Event-Strom, weil sie an derselben Naht
     * entsteht, an der [traced] gerufen wird (Text-Turns: kein STT ⇒ null).
     * Die übrigen Stage-Latenzen liest der Tap aus dem additiven
     * [ChatEvent.Done.stageTimings] (Orchestrator/Gate/TtsStage mergen dort).
     */
    fun traced(
        turnTrace: TurnTracePort,
        stream: Flux<ChatEvent>,
        source: String,
        chatId: String,
        persona: String,
        language: String,
        speak: Boolean,
        fallbackCategory: String = "",
        errorOverride: String? = null,
        sttMs: Long? = null,
    ): Flux<ChatEvent> {
        if (turnTrace === TurnTracePort.NOOP) return stream
        val t0 = AtomicLong(0)
        val ttftMs = AtomicLong(-1)
        val totalChars = AtomicInteger(0)
        val audioChunks = AtomicInteger(0)
        val category = AtomicReference(fallbackCategory)
        val provider = AtomicReference("")
        val model = AtomicReference("")
        val grounded = AtomicBoolean(false)
        val segmentReset = AtomicBoolean(false)
        val resetReason = AtomicReference("none")
        val segmentLenTurns = AtomicInteger(0)
        val firstDelta = AtomicReference<String?>(null)
        val errorStage = AtomicReference<String?>(null)
        // Perf-Diary: die tief innen gemessenen Stage-Latenzen reisen am terminalen
        // Done-Event an diesen Rand (additives stageTimings, Muster grounded).
        val stageTimings = AtomicReference<ChatEvent.StageTimings?>(null)
        // Extended Think S4 (additiv — Muster grounded/segmentReset): escalated/
        // cacheHit reisen am Start (TurnTrace trägt sie 1:1 — escalationProvider
        // bleibt bewusst NUR ein Wire-Feld, s. ChatEvent.Start.escalationProvider-KDoc;
        // seit dem Recherche-Modell-Auftrag 2026-07-19 nicht mehr IMMER "openai-nano",
        // aber weiterhin redundant zur Diary-Frage "eskaliert?" — WELCHES Modell lief,
        // steht schon ehrlich in escalationSource, s.u.); die Kosten (erst NACH dem
        // asynchronen Lookup bekannt) reisen am terminalen Done.
        val escalated = AtomicBoolean(false)
        val cacheHit = AtomicBoolean(false)
        val escalationCostCents = AtomicReference<Double?>(null)
        // H2 (Turn↔Note-Verknüpfung, additiv — Muster escalationCostCents): ZWEI
        // mögliche Schreiber derselben Diary-Spalte, NIE beide für denselben Turn —
        // Start (Cache-Hit-Fall, VOR dem Brain-Call bekannt) ODER Done
        // (Eskalations-Fall, ERST NACH dem asynchronen Lookup bekannt). Das Done
        // überschreibt NUR mit einem echten (non-blank/non-null) Wert, sonst bleibt
        // der am Start gesetzte Cache-Hit-Wert stehen (Coalesce statt Clobber).
        val escalationQueryHash = AtomicReference<String?>(null)
        val escalationSource = AtomicReference<String?>(null)
        // H3 (Cap-Erschöpfung EHRLICH von Netzfehler unterscheidbar): einziger
        // Schreiber ist das terminale Done (Muster escalationCostCents).
        val escalationCapExhausted = AtomicBoolean(false)
        return stream
            .doOnSubscribe { t0.set(System.nanoTime()) }
            .doOnNext { ev ->
                when (ev) {
                    is ChatEvent.Start -> {
                        category.set(ev.category)
                        provider.set(ev.provider)
                        model.set(ev.model)
                        grounded.set(ev.grounded)
                        // S2 räumliches Gedächtnis: Segment-Diary additiv (Muster grounded).
                        segmentReset.set(ev.segmentReset)
                        resetReason.set(ev.resetReason)
                        segmentLenTurns.set(ev.segmentLenTurns)
                        // Extended Think S4 (additiv, Muster grounded): Eskalation/Cache-Hit.
                        escalated.set(ev.escalated)
                        cacheHit.set(ev.cacheHit)
                        // H2: Cache-Hit-Quelle ist VOR dem Brain-Call bekannt (aus dem
                        // bereits assemblierten groundBlock geparst) ⇒ reist am Start.
                        // "" = kein Cache-Hit-Fall (Start-Default) — dann NICHT überschreiben,
                        // damit ein späteres Done (Eskalations-Fall) nicht durch dieses
                        // Start hier schon auf null zurückgesetzt wird (die beiden Fälle
                        // sind ohnehin exklusiv, aber diese Reihenfolge ist die robustere).
                        if (ev.escalationSource.isNotEmpty()) escalationSource.set(ev.escalationSource)
                    }
                    is ChatEvent.TextDelta -> {
                        if (ttftMs.get() < 0) {
                            ttftMs.set((System.nanoTime() - t0.get()) / 1_000_000)
                            firstDelta.set(ev.text)
                        }
                        totalChars.addAndGet(ev.text.length)
                    }
                    is ChatEvent.AudioChunk -> audioChunks.incrementAndGet()
                    is ChatEvent.Error -> errorStage.set(ev.stage)
                    is ChatEvent.Done -> {
                        stageTimings.set(ev.stageTimings)
                        // Extended Think S4: die Eskalations-Kosten sind erst am terminalen
                        // Done bekannt (async Lookup) — Muster stageTimings.
                        escalationCostCents.set(ev.escalationCostCents)
                        // H2: nur bei einem ECHTEN Wert überschreiben (Eskalations-Fall) —
                        // sonst bliebe ein am Start gesetzter Cache-Hit-Wert unangetastet
                        // (Coalesce, s. Deklarations-Kommentar oben).
                        ev.escalationQueryHash?.let { escalationQueryHash.set(it) }
                        ev.escalationSource?.let { escalationSource.set(it) }
                        // H3: das Wire-Feld ist nullable (nur bei true gesetzt, s.
                        // ChatEvent.Done.escalationCapExhausted-KDoc) — nur bei einem
                        // echten true überschreiben, der ehrliche Default bleibt false.
                        if (ev.escalationCapExhausted == true) escalationCapExhausted.set(true)
                    }
                    else -> {}
                }
            }
            .doFinally {
                // best-effort: der Adapter ist non-throwing + async; ein Diary-Fehler
                // darf den Turn nie berühren (zusätzlich defensiv gefangen).
                runCatching {
                    val deflected = model.get() == "policy" &&
                        (firstDelta.get() == FactCoverageGate.DEFLECT_DE || firstDelta.get() == FactCoverageGate.DEFLECT_EN)
                    turnTrace.record(
                        TurnTrace(
                            ts = Instant.now(),
                            chatId = chatId,
                            category = category.get(),
                            provider = provider.get(),
                            persona = persona,
                            language = language,
                            ttftMs = ttftMs.get().takeIf { it >= 0 },
                            totalMs = (System.nanoTime() - t0.get()) / 1_000_000,
                            deltaChars = totalChars.get(),
                            audioChunks = audioChunks.get(),
                            speak = speak,
                            deflected = deflected,
                            error = errorOverride ?: errorStage.get(),
                            // Ehrlich aus dem Start-Event (additives `grounded`-Feld):
                            // der Orchestrator setzt es am Brain-Pfad aus
                            // FactCoverageGate.groundingCovered.
                            groundingUsed = grounded.get(),
                            source = source,
                            // S2 räumliches Gedächtnis (additiv): Segment-Grenz-Diary
                            // aus dem Start-Event — die S4-Kalibrier-Basis.
                            segmentReset = segmentReset.get(),
                            resetReason = resetReason.get(),
                            segmentLenTurns = segmentLenTurns.get(),
                            // Perf-Diary (additiv): sttMs vom Rand-Parameter, der Rest
                            // aus dem Done-stageTimings. Kein Done / nichts gemessen ⇒
                            // ehrlich null (nie ein erfundenes 0).
                            sttMs = sttMs,
                            groundingMs = stageTimings.get()?.groundingMs,
                            brainTtftMs = stageTimings.get()?.brainTtftMs,
                            ttsFirstAudioMs = stageTimings.get()?.ttsFirstAudioMs,
                            admissionWaitMs = stageTimings.get()?.admissionWaitMs,
                            // Antwort-Entropie (S1, additiv): nur Messwert — null,
                            // wenn Flag OFF / der Brain keine logprobs liefert.
                            answerEntropy = stageTimings.get()?.answerEntropy,
                            // Verhör-Detektor (S1, additiv): STT-Surprisal des
                            // Whisper-Transkripts — null, wenn Flag OFF / kein
                            // Voice-Turn / der Brain kein /v1/score kann.
                            sttSurprisal = stageTimings.get()?.sttSurprisal,
                            sttSurprisalMax = stageTimings.get()?.sttSurprisalMax,
                            // Extended Think S4 (additiv): escalated/escalationCostCents/
                            // cacheHit aus Start (escalated/cacheHit) bzw. Done
                            // (escalationCostCents, erst nach dem Lookup bekannt).
                            escalated = escalated.get(),
                            escalationCostCents = escalationCostCents.get(),
                            cacheHit = cacheHit.get(),
                            // H2 (Turn↔Note-Verknüpfung) / H3 (Cap-Erschöpfung EHRLICH
                            // unterscheidbar von Netzfehler) — additiv ans Zeilenende.
                            escalationQueryHash = escalationQueryHash.get(),
                            escalationSource = escalationSource.get(),
                            escalationCapExhausted = escalationCapExhausted.get(),
                        ),
                    )
                }
            }
    }

    /**
     * **Direkte ABORTED-Zeile für Nähte OHNE [ChatEvent]-Strom** — die Cap-/
     * Session-Guard-Abbrüche im [AudioWebSocketHandler] (`onBinary`-Byte-Cap,
     * `enforceSessionGuard`-Dauer-Deckel) schreiben ihre `llm_error`/`llm_done`-
     * Frames IMPERATIV direkt auf den Session-Sink — dort existiert kein Flux,
     * den [traced] hüllen könnte. Ein synthetischer Strom müsste an diesen
     * Nähten nur erfunden und selbst subscribed werden, um `doFinally` zu
     * triggern (Plumbing-Theater auf dem heißen Inbound-Pfad) — der direkte,
     * defensive [TurnTracePort.record]-Call ist hier die ehrlichere Form.
     *
     * Verhalten exakt wie der Tap: [TurnTracePort.NOOP] ⇒ früher Return (null
     * Overhead, kein Trace-Objekt gebaut), `runCatching` ⇒ ein Diary-Fehler
     * berührt NIE den Turn. Kategorie fest [CATEGORY_ABORTED], [reason] landet
     * im `error`-Feld (siehe Grund-Wahl-Doku an [CATEGORY_ABORTED]). Kein
     * Turn lief ⇒ ttft/total/chars bleiben ehrlich auf ihren Null-Defaults.
     */
    fun recordAborted(
        turnTrace: TurnTracePort,
        source: String,
        reason: String,
        persona: String,
        language: String,
        speak: Boolean,
    ) {
        if (turnTrace === TurnTracePort.NOOP) return
        runCatching {
            turnTrace.record(
                TurnTrace(
                    ts = Instant.now(),
                    category = CATEGORY_ABORTED,
                    persona = persona,
                    language = language,
                    speak = speak,
                    error = reason,
                    source = source,
                ),
            )
        }
    }
}
