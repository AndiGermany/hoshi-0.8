package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * **SlopKillStage (D7, Cowork-Zielbild)** — deterministische Anti-Slop-Nachbearbeitung
 * auf dem [ChatEvent.TextDelta]-Strom. Lara (Conversation-Design, Anti-Lexikon) +
 * Runa (Stimm-Authentizität): LLM-Floskeln („Gerne!", „Certainly!", „Zusammenfassend
 * lässt sich sagen…") klingen nach Assistent, nicht nach Hoshi — sie werden satz-initial
 * ersatzlos gestrichen UND je Phrase GEZÄHLT (Slop-Rate als Metrik, [metrics]).
 *
 * ## Design-Entscheid: Live-Filterung per „Prefix-Hold" am Satzanfang
 *
 * Streaming-Problem: Kill-Phrasen kommen über Delta-Grenzen verteilt („Ger" + „ne! …").
 * Statt global zu puffern (Latenz!) oder Splits durchzulassen (unehrlich), hält die
 * Stage NUR am Satzanfang Zeichen zurück — und nur solange sie noch Präfix einer
 * Kill-Phrase sein KÖNNEN (worst case: längste Phrase + 1 ≈ 33 Zeichen, typisch 1–3).
 * Beim ersten Nicht-Treffer wird sofort geflusht. Damit werden Delta-Splits EHRLICH
 * gekillt, und die TTFT-Kosten bleiben minimal (die meisten Sätze flushen nach dem
 * ersten Zeichen, weil keine Phrase so beginnt).
 *
 * **Laras Veto eingehalten — nie mitten im Satz matschen:** gematcht wird AUSSCHLIESSLICH
 * am Satzanfang (Turn-Start bzw. nach `.!?…\n` + Whitespace). Mid-Satz-Slop
 * („…und ich hoffe, das hilft") wird bewusst weder gekillt noch gezählt — ehrliche
 * Messlücke, dafür strukturell null Matsch-Risiko. Zitate überleben ebenfalls:
 * in `„Gerne!" sagte er.` belegt das Anführungszeichen den Satzanfang, die Phrase
 * dahinter ist Mid-Satz.
 *
 * ## Kill-Semantik (konservativ)
 *
 *  - **Geschlossene Phrasen** (enden auf `!`: „Gerne!", „Natürlich!", …) matchen nur
 *    MIT dem Ausrufezeichen — „Gerne erkläre ich das." und „Natürlich mag ich Musik."
 *    überleben unangetastet.
 *  - **Offene Phrasen** („Great question", „Es ist wichtig zu beachten", …) brauchen
 *    eine Wortgrenze (Nicht-Buchstabe) NACH der Phrase — „Great questions are rare."
 *    überlebt. Nach dem Kill werden anschließende Separatoren (`,:;.!…` + Whitespace)
 *    mitgeschluckt und der neue Satzanfang großgeschrieben
 *    („Es ist wichtig zu beachten, dass es regnet." → „Dass es regnet.").
 *    Ehrlicher Kompromiss: der Reststrich kann grammatisch ein Fragment sein —
 *    bewusst akzeptiert, die Liste ist klein und das Register zeigt die Trefferraten.
 *  - **Never-Silent-Guard:** besteht die GESAMTE Antwort nur aus einer Kill-Phrase,
 *    die erst am Turn-Ende bestätigt würde, wird sie NICHT zur Stille gekillt —
 *    lieber Slop als Schweigen.
 *  - Nicht-Text-Events (Start/Step/Audio/…) fließen unverändert durch; Done/Error
 *    flushen einen offenen Hold-Rest, bevor sie selbst emittiert werden.
 *
 * ## Wiring (Folge-Scheibe — TurnOrchestrator/PipelineConfig hier NICHT angefasst)
 *
 * Die Stage sitzt im `ChatStreamController.stream` **VOR** der [TtsStage] (Slop darf
 * nie gesprochen werden) und vor `rememberAfter` (das Gedächtnis speichert die
 * entschlackte Antwort). Flag `HOSHI_SLOP_KILL_ENABLED`, Default **false**:
 * ```kotlin
 * // PipelineConfig:
 * @Bean
 * fun slopKillStage(@Value("\${HOSHI_SLOP_KILL_ENABLED:false}") enabled: Boolean): SlopKillStage =
 *     if (enabled) SlopKillStage() else SlopKillStage.DISABLED
 *
 * // ChatStreamController.stream:
 * val gated = admissionGate.gate { orchestrator.handle(resolved) }
 * val deslopped = slopKillStage.transform(gated)          // NEU: vor Memory + TTS
 * val turn = rememberAfter(resolved, deslopped)
 * val out = if (resolved.speak) ttsStage.transform(turn, effective, resolved.voice) else turn
 * ```
 *
 * Pro Turn frischer State (der Aufrufer ruft [transform] je Request); das
 * [metrics]-Register ist bewusst Stage-Instanz-global (Slop-Rate über alle Turns,
 * Diary/Ops-Anschluss ist Folge-Scheibe). Spring-frei, reiner Kern.
 */
class SlopKillStage(
    /** `false` ⇒ Identity-Transformation (byte-neutral, [DISABLED]). */
    private val enabled: Boolean = true,
    /** Zähl-Register (getroffene Phrase je Kategorie) — exposierbar via [SlopMetrics.getSnapshot]. */
    val metrics: SlopMetrics = SlopMetrics(),
) {

    companion object {
        /**
         * Identity-Instanz fürs Flag-OFF-Wiring (`HOSHI_SLOP_KILL_ENABLED=false`):
         * [transform] gibt den Strom UNGEHÜLLT zurück — null Operatoren, null Zählung.
         */
        @JvmField
        val DISABLED: SlopKillStage = SlopKillStage(enabled = false)

        /** Satz-Endzeichen, nach denen (plus Whitespace) ein neuer Satzanfang beginnt. */
        private const val BOUNDARY = ".!?…\n"

        /** Nach einem Kill mitgeschluckte Trenn-Zeichen (plus Whitespace). */
        private const val SEPARATORS = ",:;.!…"

        /**
         * Kill-Liste DE+EN (satz-initial, konservativ — Andis D7-Liste).
         * `closed=true` ⇒ Phrase matcht nur inkl. Schluss-`!`;
         * `closed=false` ⇒ Phrase braucht eine Wortgrenze (Nicht-Buchstabe) dahinter.
         * Matching case-insensitiv (Satzanfänge variieren nicht riskant).
         */
        private val PHRASES: List<Phrase> = listOf(
            // DE — geschlossene Klassiker
            Phrase("Gerne!", closed = true),
            Phrase("Natürlich!", closed = true),
            Phrase("Selbstverständlich!", closed = true),
            // DE — offene Satz-Einleiter
            Phrase("Als KI", closed = false),
            Phrase("Als hilfreicher Assistent", closed = false),
            Phrase("Ich hoffe, das hilft", closed = false),
            Phrase("Zusammenfassend lässt sich sagen", closed = false),
            Phrase("Es ist wichtig zu beachten", closed = false),
            // EN — geschlossen
            Phrase("Certainly!", closed = true),
            // EN — offen
            Phrase("Great question", closed = false),
            Phrase("I hope this helps", closed = false),
            Phrase("In conclusion", closed = false),
            Phrase("It's important to note", closed = false),
            // Typographischer Apostroph (LLMs emittieren gern U+2019).
            Phrase("It’s important to note", closed = false),
        )
    }

    private data class Phrase(val text: String, val closed: Boolean)

    /**
     * Thread-sicheres Zähl-Register: getroffene Kill-Phrase → Anzahl Kills.
     * `ConcurrentHashMap` + [AtomicLong], reine In-Memory-Metrik — der
     * Diary/Ops-Anschluss (Exposition nach außen) ist die Folge-Scheibe.
     */
    class SlopMetrics {
        private val hits = ConcurrentHashMap<String, AtomicLong>()

        internal fun record(phrase: String) {
            hits.computeIfAbsent(phrase) { AtomicLong() }.incrementAndGet()
        }

        /** Momentaufnahme: Phrase → Kill-Zähler (nur Phrasen mit ≥1 Treffer). */
        fun getSnapshot(): Map<String, Long> = hits.mapValues { it.value.get() }

        /** Summe aller Kills (die „Slop-Rate"-Zählerseite). */
        fun totalKills(): Long = hits.values.sumOf { it.get() }
    }

    // ── Per-Turn-Zustandsmaschine ────────────────────────────────────────────────

    private enum class Mode {
        /** Satzanfang: Zeichen laufen in den Hold, solange sie Phrasen-Präfix sein können. */
        HOLDING,

        /** Mitten im Satz: 1:1 durchreichen, auf Satz-Endzeichen achten. */
        PASSING,

        /** Nach Satz-Endzeichen: Whitespace durchreichen, erstes Nicht-Whitespace ⇒ [HOLDING]. */
        AFTER_BOUNDARY,

        /** Direkt nach einem Kill: Separatoren + Whitespace schlucken, dann [HOLDING]. */
        SWALLOWING,
    }

    private class TurnState {
        var mode = Mode.HOLDING // Turn-Start IST ein Satzanfang.
        val hold = StringBuilder()
        var capitalizeNext = false
        var emittedAny = false // Never-Silent-Guard: schon echter (Nicht-WS-)Text raus?
        var provider = ""
    }

    private sealed interface Match {
        /** Voll-Treffer; [refeed] = das Wortgrenzen-Zeichen offener Phrasen (gehört dem Folgetext). */
        data class Complete(val phrase: Phrase, val refeed: Char?) : Match
        object Prefix : Match
        object None : Match
    }

    /**
     * Hüllt [events] um die Slop-Kill-Schicht. Text-Deltas werden Zeichen für Zeichen
     * durch die Zustandsmaschine geführt; je Input-Delta entsteht höchstens EIN
     * Output-Delta (ggf. leer ⇒ gar keins, wenn alles im Hold wartet oder gekillt
     * wurde). `concatMap` hält die Verarbeitung seriell ⇒ der per-Turn-State ist
     * ohne Locks sicher (gleicher Vertrag wie [TtsStage]).
     */
    fun transform(events: Flux<ChatEvent>): Flux<ChatEvent> {
        if (!enabled) return events
        val st = TurnState()
        return events.concatMap { event -> handle(event, st) }
    }

    private fun handle(event: ChatEvent, st: TurnState): Flux<ChatEvent> = when (event) {
        is ChatEvent.TextDelta -> {
            st.provider = event.provider
            val out = StringBuilder()
            for (c in event.text) feed(c, st, out)
            if (out.isEmpty()) {
                Flux.empty()
            } else {
                Flux.just<ChatEvent>(ChatEvent.TextDelta(out.toString(), event.provider))
            }
        }
        // Turn-Ende: offenen Hold-Rest flushen (bzw. am Stream-Ende bestätigten
        // Voll-Treffer killen), DANN das terminale Event.
        is ChatEvent.Done -> Flux.concat(flushEnd(st), Flux.just<ChatEvent>(event))
        is ChatEvent.Error -> Flux.concat(flushEnd(st), Flux.just<ChatEvent>(event))
        // Start/Step/Audio/… unverändert durch (ein nicht-leerer Hold bleibt liegen;
        // gehaltener Text erscheint dann NACH dem Zwischen-Event — akzeptiert).
        else -> Flux.just<ChatEvent>(event)
    }

    /** EIN Zeichen durch die Zustandsmaschine — Output landet in [out]. */
    private fun feed(c: Char, st: TurnState, out: StringBuilder) {
        when (st.mode) {
            Mode.PASSING -> {
                emit(c, st, out)
                if (c in BOUNDARY) st.mode = Mode.AFTER_BOUNDARY
            }

            Mode.AFTER_BOUNDARY -> {
                if (c.isWhitespace()) {
                    emit(c, st, out)
                } else {
                    st.mode = Mode.HOLDING
                    feed(c, st, out)
                }
            }

            Mode.SWALLOWING -> {
                if (c.isWhitespace() || c in SEPARATORS) {
                    // geschluckt — gehörte zur gekillten Phrase.
                } else {
                    st.mode = Mode.HOLDING
                    feed(c, st, out)
                }
            }

            Mode.HOLDING -> {
                // Führendes Whitespace am Satzanfang direkt durchreichen (kein Phrasen-Start).
                if (st.hold.isEmpty() && c.isWhitespace()) {
                    emit(c, st, out)
                    return
                }
                st.hold.append(c)
                when (val m = evaluate(st.hold.toString())) {
                    is Match.Complete -> {
                        metrics.record(m.phrase.text)
                        st.hold.setLength(0)
                        st.capitalizeNext = true
                        st.mode = Mode.SWALLOWING
                        // Das Wortgrenzen-Zeichen offener Phrasen gehört dem Folgetext.
                        if (m.refeed != null) feed(m.refeed, st, out)
                    }
                    Match.Prefix -> Unit // weiter halten (max. längste Phrase + 1 Zeichen).
                    Match.None -> {
                        // Kein Slop: Hold SOFORT flushen — die Zeichen laufen regulär
                        // durch PASSING (Boundary-Erkennung inklusive, kein Matsch).
                        val flushed = st.hold.toString()
                        st.hold.setLength(0)
                        st.mode = Mode.PASSING
                        for (f in flushed) feed(f, st, out)
                    }
                }
            }
        }
    }

    /**
     * Bewertet den Hold gegen die Kill-Liste (case-insensitiv). Voll-Treffer schlägt
     * Präfix. Geschlossene Phrasen matchen bei exakter Länge; offene brauchen GENAU
     * ein Zeichen mehr, das KEIN Buchstabe ist (Wortgrenzen-Guard:
     * „Great questions…" ist kein Treffer).
     */
    private fun evaluate(h: String): Match {
        var anyPrefix = false
        for (p in PHRASES) {
            if (p.closed) {
                if (h.length == p.text.length && h.equals(p.text, ignoreCase = true)) {
                    return Match.Complete(p, refeed = null)
                }
                if (h.length < p.text.length && p.text.startsWith(h, ignoreCase = true)) anyPrefix = true
            } else {
                if (h.length == p.text.length + 1 &&
                    h.regionMatches(0, p.text, 0, p.text.length, ignoreCase = true) &&
                    !h.last().isLetter()
                ) {
                    return Match.Complete(p, refeed = h.last())
                }
                if (h.length <= p.text.length && p.text.startsWith(h, ignoreCase = true)) anyPrefix = true
            }
        }
        return if (anyPrefix) Match.Prefix else Match.None
    }

    /** Zeichen emittieren; nach einem Kill wird das erste Nicht-Whitespace großgeschrieben. */
    private fun emit(c: Char, st: TurnState, out: StringBuilder) {
        if (st.capitalizeNext && !c.isWhitespace()) {
            out.append(c.uppercaseChar())
            st.capitalizeNext = false
        } else {
            out.append(c)
        }
        if (!c.isWhitespace()) st.emittedAny = true
    }

    /**
     * Turn-Ende-Flush: ein offener Hold ist entweder (a) eine EXAKT vollständige
     * offene Phrase (Stream-Ende = Wortgrenze ⇒ Kill bestätigt — außer die Antwort
     * bestünde dann NUR aus Stille, Never-Silent-Guard via [TurnState.emittedAny])
     * oder (b) ein harmloser Präfix-Rest ⇒ unverändert emittieren.
     */
    private fun flushEnd(st: TurnState): Flux<ChatEvent> {
        if (st.hold.isEmpty()) return Flux.empty()
        val h = st.hold.toString()
        st.hold.setLength(0)
        val exact = PHRASES.firstOrNull { !it.closed && h.equals(it.text, ignoreCase = true) }
        if (exact != null && st.emittedAny) {
            metrics.record(exact.text)
            return Flux.empty()
        }
        val out = StringBuilder()
        for (c in h) {
            if (st.capitalizeNext && !c.isWhitespace()) {
                out.append(c.uppercaseChar())
                st.capitalizeNext = false
            } else {
                out.append(c)
            }
        }
        return Flux.just<ChatEvent>(ChatEvent.TextDelta(out.toString(), st.provider))
    }
}
