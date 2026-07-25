package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * **BrainAdmissionGate (Ticket #9, Nils/Lars)** — die globale Concurrent-Brain-
 * Admission-Control. Auf dem 16-GB-Mac ist der Brain (e4b, :8041) seriell und teuer
 * (KV-Cache, MLX-Inferenz); unter PARALLELER Voice-Last (mehrere Satelliten / FE +
 * Satellit gleichzeitig) staut sich sonst beliebig viel gleichzeitige Brain-Arbeit
 * auf → Memory-Druck → OOM. Dieses Gate deckelt die Zahl GLEICHZEITIGER Brain-Turns
 * auf [maxConcurrent] und lehnt Überzählige SAUBER ab, statt sie aufzustauen.
 *
 * **Ein einziges Semaphore, EINE Instanz (Singleton-Bean):** dieselbe Gate-Instanz
 * wird an JEDEN Inbound-Turn-Seam gehängt (`/ws/audio`, `/api/v1/voice`,
 * `/api/v1/chat/stream`) — so ist das Permit-Budget WIRKLICH global, nicht pro
 * Endpoint. Das Permit wird **lazy beim Subscribe** geholt (Flux.defer) und auf
 * JEDEM terminalen Signal (complete, error, CANCEL) genau einmal freigegeben
 * (doFinally + Einmal-Guard). Cancel-Freigabe ist wichtig fürs Barge-in (ein
 * abgebrochener Turn gibt sein Permit sofort zurück).
 *
 * **Never-Silent statt Aufstau:** ist kein Permit frei, gibt es KEIN Warten auf dem
 * Netty-Event-Loop (0.5-Lehre: blockierendes acquire auf Reactor-Threads ist
 * verboten) — der Turn wird mit einer warmen, ehrlichen, SPRECHBAREN Absage
 * (Start + TextDelta + Done, wie eine Direkt-Antwort) abgeschlossen. Die TextDelta
 * läuft durch die TtsStage ⇒ der Nutzer HÖRT „gleich wieder da", statt in einen
 * stillen Socket zu laufen.
 *
 * **Flag-gated, default OFF ⇒ byte-neutral:** bei [enabled]=false ist [gate] ein
 * reiner Passthrough (`source()` direkt, kein defer, kein Semaphore) ⇒ exakt der
 * heutige Pfad. Andi schaltet beim Deploy scharf ([HOSHI_BRAIN_ADMISSION_ENABLED]).
 */
class BrainAdmissionGate(
    private val enabled: Boolean,
    maxConcurrent: Int,
    /**
     * **Explizite** Betreiber-Absage (`HOSHI_BRAIN_ADMISSION_REJECT_PHRASE`) — wenn
     * gesetzt, gewinnt sie in JEDER Sprache (eine bewusste Betreiber-Wahl übersetzt
     * niemand weg). `null` (Default) ⇒ die Absage kommt sprach-bewusst aus dem
     * [de.hoshi.core.pipeline.lang.LanguagePack] der Turn-Sprache, s. [gate].
     */
    private val rejectPhrase: String? = null,
    /**
     * Zeitquelle der Admission-Wartezeit-Messung (Perf-Diary
     * [ChatEvent.StageTimings.admissionWaitMs]) — injizierbar für deterministische
     * Tests (Fake-Clock), Default die echte Nano-Uhr. Rein additiv.
     */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Permit-Budget — mind. 1 (eine Fehlkonfiguration 0/negativ darf das Brain nicht totlegen). */
    private val permits: Int = maxConcurrent.coerceAtLeast(1)

    /** Fair (FIFO) — kein Turn verhungert, falls je „kurz warten" nachgerüstet wird. */
    private val semaphore = Semaphore(permits, true)

    /** Mess-Haken (Lars): wie viele Turns das Gate insgesamt abgelehnt hat. */
    private val rejected = AtomicInteger(0)

    /** Anzahl abgelehnter Turns (Admission-Rejection-Rate-Zähler). */
    fun rejectedCount(): Int = rejected.get()

    /** Aktuell freie Permits (Test-/Mess-Sicht). */
    fun availablePermits(): Int = semaphore.availablePermits()

    /**
     * Schleust den Turn-erzeugenden [source] durchs Gate. [source] ist eine **kalte**
     * `() -> Flux<ChatEvent>`-Lambda (z.B. `{ orchestrator.handle(req) }`); sie wird
     * erst beim Subscribe ausgewertet, NACHDEM ein Permit feststeht — so zählt nur ein
     * wirklich laufender Brain-Turn gegen das Budget.
     *
     * OFF ⇒ `source()` direkt (byte-neutral). ON ⇒ Permit holen (non-blocking
     * tryAcquire); frei ⇒ Turn laufen lassen + bei Terminierung freigeben; belegt ⇒
     * warme Absage ([rejection]), KEIN Aufstau.
     */
    fun gate(source: () -> Flux<ChatEvent>): Flux<ChatEvent> = gate(Language.DEFAULT, source)

    /**
     * Wie [gate], aber mit der TURN-SPRACHE: die warme Absage bei Über-Kapazität
     * spricht sie (Andi 2026-07-25: „Multilingualität von A-Z"). Bewusst eine
     * Überladung statt eines Default-Arguments — so bleibt `gate { … }` mit
     * nachgestelltem Lambda an JEDEM Bestands-Aufrufer unverändert gültig.
     */
    fun gate(language: Language, source: () -> Flux<ChatEvent>): Flux<ChatEvent> {
        if (!enabled) return source()
        return Flux.defer {
            // Perf-Diary: Gate-Eintritt → Permit. tryAcquire ist non-blocking, der
            // Wert ist heute also ehrlich ~0 ms — die Mess-Naht steht, falls je ein
            // bounded-wait nachgerüstet wird. OFF/Rejection ⇒ nie gemessen ⇒ null.
            val t0 = nanoTime()
            if (!semaphore.tryAcquire()) {
                rejected.incrementAndGet()
                log.warn(
                    "[brain-admission] über Kapazität ({} Permits belegt) — Turn sauber abgelehnt (never-silent)",
                    permits,
                )
                return@defer rejection(language)
            }
            val admissionWaitMs = (nanoTime() - t0) / 1_000_000
            // Genau-einmal-Freigabe auf JEDEM terminalen Signal (complete/error/cancel).
            val released = AtomicBoolean(false)
            val release = { if (released.compareAndSet(false, true)) semaphore.release() }
            try {
                source()
                    // Die GEMESSENE Wartezeit reist additiv am terminalen Done mit
                    // (Perf-Diary-Muster: jede Schicht merged nur ihr eigenes Feld).
                    .map { ev ->
                        if (ev is ChatEvent.Done) {
                            ev.copy(
                                stageTimings = (ev.stageTimings ?: ChatEvent.StageTimings())
                                    .copy(admissionWaitMs = admissionWaitMs),
                            )
                        } else {
                            ev
                        }
                    }
                    .doFinally { release() }
            } catch (t: Throwable) {
                // Defensiv: wirft die (rein reaktive) source()-Assembly doch synchron,
                // darf das Permit nicht lecken.
                release()
                throw t
            }
        }
    }

    /**
     * Warme, ehrliche Über-Kapazität-Absage als vollständiger Mini-Turn
     * (Start + TextDelta + Done) — strukturell identisch zu einer Direkt-Antwort des
     * Orchestrators, damit die Wire-/Audio-Schicht sie wie einen normalen kurzen Turn
     * behandelt (TextDelta wird gesprochen).
     */
    private fun rejection(language: Language): Flux<ChatEvent> = Flux.just(
        ChatEvent.Start(provider = PROVIDER, category = CATEGORY, model = "policy"),
        ChatEvent.TextDelta(rejectPhraseFor(language), provider = PROVIDER),
        ChatEvent.Done(provider = PROVIDER),
    )

    /**
     * Die gesprochene Absage: explizite Betreiber-Phrase, sonst die des Sprachpakets
     * der Turn-Sprache. Für [Language.DE] ohne Override byte-identisch zu
     * [DEFAULT_REJECT_PHRASE].
     */
    private fun rejectPhraseFor(language: Language): String =
        rejectPhrase ?: LanguagePackRegistry.forLanguage(language).admissionBusy

    companion object {
        const val PROVIDER = "LOCAL"
        const val CATEGORY = "ADMISSION"

        /**
         * Ehrliche Default-Absage bei Über-Kapazität auf DEUTSCH. Seit der
         * Mehrsprachigkeit (2026-07-25) nur noch der DE-Zeiger auf die EINE Quelle
         * ([LangDe]) — der byte-identische Beweis, dass der de-Pfad nicht wackelt.
         */
        val DEFAULT_REJECT_PHRASE: String = LangDe.PACK.admissionBusy
    }
}
