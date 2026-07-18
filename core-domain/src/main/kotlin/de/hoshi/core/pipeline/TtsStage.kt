package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **TtsStage** — die Stelle, die den Text-Turn HÖRBAR macht (M2-Audio-Add). Sie
 * transformiert den `Flux<ChatEvent>` des [TurnOrchestrator] so, dass:
 *
 *  1. JEDES Original-Event unverändert durchgereicht wird (insb. [ChatEvent.TextDelta]) —
 *     der bestehende Text-Turn bleibt vollständig intakt.
 *  2. der durchfließende Text satzweise gepuffert wird ([SentenceBoundaryDetector]),
 *     und pro VOLLSTÄNDIGEM Satz [TtsPort.synthStream] gerufen wird.
 *  3. einmalig ein [ChatEvent.TtsAudioStart] (vor dem ersten Audio) → je Satz EIN oder
 *     MEHRERE [ChatEvent.AudioChunk] (seq, base64-WAV; Batch-Default = genau einer,
 *     Streaming-Adapter = mehrere Slices) → am Turn-Ende ein
 *     [ChatEvent.TtsAudioEnd] (vor dem terminalen [ChatEvent.Done]) emittiert wird.
 *
 * **Best-Effort am Never-Silent vorbei:** Audio ist KÜR. Jeder [TtsPort.synthStream]
 * wird in `onErrorResume` gehüllt → ein TTS-Fehler (Sidecar weg, Timeout) wird
 * verschluckt, der Satz fließt OHNE (weiteres) Audio weiter — bereits emittierte
 * Slices bleiben gültig. Der Text-Turn kann strukturell NICHT an der Audio-Schicht
 * sterben.
 *
 * **Reihenfolge/seq sauber:** die Transformation läuft über `concatMap` — die
 * inneren (asynchronen) synth-Aufrufe werden seriell und in Quell-Reihenfolge
 * verarbeitet, `seq` läuft über Satz- UND Slice-Grenzen strikt monoton hoch
 * (FE-AudioQueue-Vertrag: streng aufsteigend ab head). Spring-frei, reiner Kern.
 *
 * **Latenz-Hebel (0.5-Port, ALLE default byte-neutral):**
 *  - **Fast-First / Grouped Chunking** ([fastFirstN]/[groupedMinChars], T081): die ersten
 *    N Sätze kurz schneiden (schnelleres erstes Audio = Time-to-first-word), spätere
 *    gruppieren (konsistentere Stimme). Default `fastFirstN=0` + `groupedMinChars=minChars`
 *    ⇒ jeder Satz nutzt `minChars` ⇒ identisches Chunking wie heute.
 *  - **Idle Mid-Sentence Force-Flush** ([idleFlushMs], sentence-flush-ms): kommt für
 *    `idleFlushMs` kein neues [ChatEvent.TextDelta], wird der gepufferte Teilsatz trotzdem
 *    emittiert (Robustheit gegen stockenden LLM-Output). Default `0` = AUS ⇒ exakt der
 *    heutige `concatMap`-Pfad (kein Timer, keine Verhaltensänderung).
 *
 * Pro Turn frischer State (der Aufrufer ruft [transform] je Request) — kein
 * geteilter Puffer zwischen Sessions.
 */
class TtsStage(
    private val tts: TtsPort,
    /** Mindest-Zeichen vor einer Satzgrenze — verhindert Mikro-Synth auf Fragmenten. */
    private val minChars: Int = 12,
    /** Satz-Endzeichen fürs TTS-Chunking (bewusst nur „echte" Enden, nicht Komma). */
    private val punctuation: String = ".!?…",
    /**
     * Provider-Tag für die Audio-Events (FE-Vertrag) — als SUPPLIER statt fixem
     * String (0.8, Laufzeit-TTS-Engine-Wahl): wird PRO SATZ (beim ersten Audio-
     * Chunk, s. [synthOne]) frisch gelesen, damit ein Runtime-Switch der Engine
     * (`DelegatingTtsPort.switchTo`) das Telemetrie-Tag NICHT belügt — ein beim
     * Konstruktor-Aufruf eingefrorener String könnte nach einem Wechsel die alte
     * Engine behaupten. Default `{ "voxtral" }` ⇒ byte-identisch zum früheren
     * fixen String-Default.
     */
    private val provider: () -> String = { "voxtral" },
    /**
     * **Fast-First** (0.5-Port T081): Anzahl der ERSTEN Sätze, die noch mit dem kleinen
     * [minChars]-Schwellwert geschnitten werden (schnelles erstes Audio). Ab Satz
     * `fastFirstN+1` greift [groupedMinChars]. Default `0` ⇒ kein Satz wird „fast"
     * behandelt; zusammen mit dem byte-neutralen [groupedMinChars]-Default bleibt das
     * Chunking exakt wie heute.
     */
    private val fastFirstN: Int = 0,
    /**
     * **Grouped** (0.5-Port T081): Mindest-Puffergröße ab Satz `fastFirstN+1`. Größer ⇒
     * mehrere kurze Sätze verschmelzen in EINEN TTS-Call (konsistentere Stimme, höhere
     * Latenz pro spätem Chunk). Default `= minChars` ⇒ späte Sätze nutzen denselben
     * Schwellwert wie die frühen ⇒ byte-neutral (keine Gruppierung).
     */
    private val groupedMinChars: Int = minChars,
    /**
     * **Idle-Flush** (0.5-Port, sentence-flush-ms): bleibt der Puffer für `idleFlushMs`
     * ohne neues [ChatEvent.TextDelta] liegen, wird der gepufferte Teilsatz (≥ [minChars])
     * trotzdem als Audio emittiert. `0` (Default) = AUS ⇒ der heutige `concatMap`-Pfad ohne
     * Timer (byte-neutral). Sinnvoll: ~300ms, wenn der LLM-Output stockt.
     */
    private val idleFlushMs: Long = 0,
    /**
     * Zeitgeber für den Idle-Timer — injizierbar, damit der Force-Flush mit Reactors
     * VirtualTimeScheduler (`StepVerifier.withVirtualTime`) deterministisch testbar bleibt
     * (keine `now()`-Logik im reinen Pfad). Nur relevant, wenn [idleFlushMs] > 0.
     */
    private val scheduler: Scheduler = Schedulers.parallel(),
    /**
     * Wall-Clock der Audio-Zeitmessung ([ChatEvent.TtsAudioEnd.actualMs] +
     * Perf-Diary [ChatEvent.StageTimings.ttsFirstAudioMs]) — injizierbar für
     * deterministische Tests (Fake-Clock), Default die echte `currentTimeMillis`-
     * Uhr. Rein additiv: kein bestehender Aufrufer ändert sich.
     */
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** Interner Token-Typ: echtes Event ODER Idle-Tick (nur im aktivierten Idle-Flush-Pfad). */
    private sealed interface Token {
        data class Real(val event: ChatEvent) : Token
        object IdleTick : Token
    }

    /**
     * Hüllt [events] um die Audio-Schicht. [language] fließt an [TtsPort.synthStream]
     * (Voxtral `lang`-Hint). Liefert Text+Audio-Events in sauberer Reihenfolge.
     *
     * [voice] (Backlog #6): der per-Turn-Stimm-Wunsch aus [de.hoshi.core.dto.ChatRequest.voice],
     * ungeprüft durchgereicht an den voice-aware [TtsPort.synthStream]-Overload — die
     * WHITELIST-Prüfung ist Sache des Adapters (OpenAI), Voxtral ignoriert ehrlich.
     * Default `null` ⇒ alle bestehenden Aufrufer kompilieren unverändert und der
     * Adapter nutzt seine Boot-Default-Stimme (byte-neutral).
     */
    fun transform(events: Flux<ChatEvent>, language: Language, voice: String? = null): Flux<ChatEvent> {
        val buffer = StringBuilder()
        val seq = AtomicInteger(0)
        val started = AtomicBoolean(false)
        val firstAudioMs = AtomicLong(0)
        // Perf-Diary: Stage-Start (Subscribe) — Referenzpunkt der ttsFirstAudioMs-
        // Messung (Stage-Start → erster AudioChunk); reist am Done-stageTimings.
        val stageT0 = AtomicLong(0)
        // Zähler erkannter Sätze (Fast-First/Grouped-Umschaltung). Bei Default-Knöpfen
        // ohne Effekt — der Schwellwert ist dann für jeden Satz [minChars].
        val sentencesDetected = AtomicInteger(0)

        // ── Idle-Flush AUS (Default) ⇒ EXAKT der heutige concatMap-Pfad (byte-neutral). ──
        // Kein Timer, kein publish/merge, keine zusätzlichen Operatoren auf dem Audio-Pfad
        // (das doOnSubscribe ist rein passiv: eine Uhr-Ablesung beim Subscribe).
        if (idleFlushMs <= 0L) {
            return events.doOnSubscribe { stageT0.set(clockMs()) }.concatMap { event ->
                handleEvent(event, buffer, language, voice, seq, started, firstAudioMs, sentencesDetected, stageT0)
            }
        }

        // ── Idle-Flush AN: Idle-Ticks injizieren. publish() teilt EINE Subscription auf
        // [events] (kein Doppel-Abo des Upstream); switchMap armiert nach jedem TextDelta
        // einen Timer und CANCELT ihn beim nächsten Event (Reset). Andere Events (Done/Start)
        // ⇒ Mono.empty ⇒ kein Trailing-Tick, der die Turn-Completion verzögert. merge führt
        // Real+Tick zusammen, concatMap verarbeitet sie seriell (Puffer bleibt thread-safe). ──
        val flush = Duration.ofMillis(idleFlushMs)
        return events.doOnSubscribe { stageT0.set(clockMs()) }.publish<Token> { shared ->
            val real: Flux<Token> = shared.map { Token.Real(it) }
            val ticks: Flux<Token> = shared.switchMap { event ->
                if (event is ChatEvent.TextDelta) {
                    Mono.delay(flush, scheduler).map<Token> { Token.IdleTick }
                } else {
                    Mono.empty<Token>()
                }
            }
            Flux.merge(real, ticks)
        }.concatMap { token ->
            when (token) {
                is Token.Real -> handleEvent(
                    token.event, buffer, language, voice, seq, started, firstAudioMs, sentencesDetected, stageT0,
                )
                Token.IdleTick -> idleFlush(buffer, language, voice, seq, started, firstAudioMs, sentencesDetected)
            }
        }
    }

    /** Ein Original-Event → Durchreichen + (bei TextDelta/Done) Audio nachschieben. */
    private fun handleEvent(
        event: ChatEvent,
        buffer: StringBuilder,
        language: Language,
        voice: String?,
        seq: AtomicInteger,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
        sentencesDetected: AtomicInteger,
        stageT0: AtomicLong,
    ): Flux<ChatEvent> = when (event) {
        // Text durchreichen UND puffern → fertige Sätze als Audio nachschieben.
        is ChatEvent.TextDelta -> {
            buffer.append(event.text)
            Flux.concat(
                Flux.just<ChatEvent>(event),
                audioForCompleteSentences(buffer, language, voice, seq, started, firstAudioMs, sentencesDetected),
            )
        }
        // Turn-Ende: Rest-Satz flushen, TtsAudioEnd (falls Audio lief), dann Done.
        // Das Done selbst wird DEFERRED emittiert: erst nach dem Rest-Flush steht
        // fest, ob (und wann) das erste Audio floss — dann trägt es die gemessene
        // ttsFirstAudioMs (Perf-Diary); ohne Audio bleibt es byte-identisch.
        is ChatEvent.Done -> Flux.concat(
            flushRemaining(buffer, language, voice, seq, started, firstAudioMs),
            audioEnd(started, firstAudioMs),
            Flux.defer { Flux.just<ChatEvent>(withTtsTiming(event, started, firstAudioMs, stageT0)) },
        )
        // Start/Step/Error unverändert durch.
        else -> Flux.just<ChatEvent>(event)
    }

    /**
     * Merged die gemessene **Stage-Start → erstes Audio**-Dauer additiv in das
     * durchfließende [ChatEvent.Done] (Perf-Diary). Lief KEIN Audio (reiner
     * Text-Turn, Best-Effort-TTS-Fehler), bleibt das Done die IDENTISCHE Instanz
     * (byte-neutral, nie ein erfundenes 0). Bereits vorhandene stageTimings
     * (Orchestrator: grounding/brainTtft · Gate: admissionWait) bleiben erhalten.
     */
    private fun withTtsTiming(
        done: ChatEvent.Done,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
        stageT0: AtomicLong,
    ): ChatEvent.Done =
        if (!started.get()) {
            done
        } else {
            val elapsed = (firstAudioMs.get() - stageT0.get()).coerceAtLeast(0)
            done.copy(
                stageTimings = (done.stageTimings ?: ChatEvent.StageTimings()).copy(ttsFirstAudioMs = elapsed),
            )
        }

    /** Zieht alle KOMPLETTEN Sätze aus dem Puffer und synthetisiert sie der Reihe nach. */
    private fun audioForCompleteSentences(
        buffer: StringBuilder,
        language: Language,
        voice: String?,
        seq: AtomicInteger,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
        sentencesDetected: AtomicInteger,
    ): Flux<ChatEvent> {
        val sentences = mutableListOf<String>()
        while (true) {
            // Fast-First/Grouped: die ersten [fastFirstN] Sätze mit [minChars] (schnell),
            // danach mit [groupedMinChars] (gruppiert). Default ⇒ immer [minChars].
            val currentMin = if (sentencesDetected.get() < fastFirstN) minChars else groupedMinChars
            val idx = SentenceBoundaryDetector.firstSentenceBoundary(
                buffer.toString(), currentMin, punctuation,
            )
            if (idx < 0) break
            val sentence = buffer.substring(0, idx + 1).trim()
            buffer.delete(0, idx + 1)
            if (sentence.isNotBlank()) {
                sentences.add(sentence)
                sentencesDetected.incrementAndGet()
            }
        }
        if (sentences.isEmpty()) return Flux.empty()
        return Flux.fromIterable(sentences)
            .concatMap { synthOne(it, language, voice, seq, started, firstAudioMs) }
    }

    /**
     * **Idle-Force-Flush:** der gepufferte Teilsatz (≥ [minChars]) wird emittiert, weil
     * für [idleFlushMs] kein neues Delta kam. Nur im aktivierten Pfad erreichbar.
     */
    private fun idleFlush(
        buffer: StringBuilder,
        language: Language,
        voice: String?,
        seq: AtomicInteger,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
        sentencesDetected: AtomicInteger,
    ): Flux<ChatEvent> {
        val partial = buffer.toString().trim()
        // Mikro-Fragmente nicht synthetisieren (0.5-Guard) — sie warten auf mehr Text bzw.
        // den Turn-Ende-Flush.
        if (partial.length < minChars) return Flux.empty()
        buffer.setLength(0)
        sentencesDetected.incrementAndGet()
        return synthOne(partial, language, voice, seq, started, firstAudioMs)
    }

    /** Synthetisiert den Rest-Puffer (letzter Satz ohne Endzeichen) am Turn-Ende. */
    private fun flushRemaining(
        buffer: StringBuilder,
        language: Language,
        voice: String?,
        seq: AtomicInteger,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
    ): Flux<ChatEvent> {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        if (rest.isBlank()) return Flux.empty()
        return synthOne(rest, language, voice, seq, started, firstAudioMs)
    }

    /**
     * EIN Satz → (einmalig [ChatEvent.TtsAudioStart], beim ERSTEN Häppchen) + ein
     * [ChatEvent.AudioChunk] je WAV-Häppchen aus [TtsPort.synthStream].
     *
     * **Byte-neutral beim Batch-Default:** [TtsPort.synthStream] delegiert per Default
     * an [TtsPort.synth] (EIN WAV-Element) ⇒ exakt ein Chunk pro Satz wie bisher. Nur
     * echte Streaming-Adapter (Flag im Adapter, nicht hier) liefern mehrere Slices.
     *
     * **seq-Vertrag (FE-AudioQueue: streng aufsteigend ab head):** der pro Turn geteilte
     * [seq]-Zähler wird ausschließlich hier inkrementiert; die Sätze laufen seriell durch
     * das äußere `concatMap` ([transform]/[audioForCompleteSentences]), die Slices eines
     * Satzes seriell durch das innere `concatMap` ⇒ `seq` ist über Satz- UND
     * Slice-Grenzen strikt monoton.
     *
     * Best-Effort: Fehler → bereits emittierte Slices bleiben gültig, der Rest wird
     * verschluckt (`Flux.empty()`), der Text läuft weiter (Never-Silent).
     */
    private fun synthOne(
        sentence: String,
        language: Language,
        voice: String?,
        seq: AtomicInteger,
        started: AtomicBoolean,
        firstAudioMs: AtomicLong,
    ): Flux<ChatEvent> =
        // Voice-aware Overload: bei voice=null identisch zum voice-losen Pfad
        // (Default-Delegation im Port) — byte-neutral ohne gesetztes Feld.
        tts.synthStream(sentence, language, voice)
            // Leere Häppchen (Best-Effort „kein Audio") werden nie zu Chunks.
            .filter { wav -> wav.isNotEmpty() }
            .concatMap<ChatEvent> { wav ->
                val out = ArrayList<ChatEvent>(2)
                if (started.compareAndSet(false, true)) {
                    firstAudioMs.set(clockMs())
                    out.add(ChatEvent.TtsAudioStart(provider = provider()))
                }
                val b64 = Base64.getEncoder().encodeToString(wav)
                out.add(ChatEvent.AudioChunk(data = b64, seq = seq.getAndIncrement()))
                Flux.fromIterable(out)
            }
            // Best-Effort: TTS-Fehler darf den Text-Turn NIE killen (Never-Silent).
            .onErrorResume { Flux.empty() }

    /** [ChatEvent.TtsAudioEnd] nur, wenn überhaupt Audio lief (sonst reiner Text-Turn). */
    private fun audioEnd(started: AtomicBoolean, firstAudioMs: AtomicLong): Flux<ChatEvent> =
        if (started.get()) {
            Flux.just(ChatEvent.TtsAudioEnd(actualMs = clockMs() - firstAudioMs.get()))
        } else {
            Flux.empty()
        }
}
