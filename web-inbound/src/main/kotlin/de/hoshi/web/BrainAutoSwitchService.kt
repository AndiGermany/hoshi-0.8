package de.hoshi.web

import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * **BrainAutoSwitchService** — die echte [BrainAutoSwitchPort]-Logik: „12B für
 * Chat, e4b für Voice" (Andi-Auftrag 2026-07-26), ANKER an Sitzungs-/Medien-
 * Grenzen statt pro Turn (kein Thrashing). Nutzt AUSSCHLIESSLICH den
 * bestehenden [BrainSwitchModelPort]/[BrainHealthProbe] — KEIN zweiter HTTP-
 * Client, dieselbe Naht wie [BrainSettingsController].
 *
 * **Ablauf EINER Entscheidung** ([decide]):
 *  1. `brainAutoSwitch`-Setting AUS ([store]) ⇒ sofort raus, KEIN Health-Call
 *     (byte-neutral — geprüft NOCH VOR jeder Netz-Operation).
 *  2. Live-Probe ([healthProbe]), welches Modell GERADE geladen ist. Schon das
 *     Ziel-Modell ⇒ no-op (kein Switch-Call).
 *  3. Hysterese ([hysteresis], s. [HYSTERESIS_WINDOW]-KDoc): liegt der letzte
 *     Wechsel-VERSUCH (Erfolg oder nicht) jünger als das Fenster zurück ⇒
 *     übersprungen — verhindert Pumpen, wenn Chat-/Voice-Turns im Sekundentakt
 *     abwechseln.
 *  4. Sonst: [switchPort.switchModel] anstossen. [BrainSwitchResult.Unavailable]
 *     (404/Timeout/Fehler — der Sidecar hat seit heute eine Switch-Lock-
 *     Selbstheilung mit 120s-Timeout) wird NIE zur Exception — der Aufrufer
 *     bekommt einfach `Unit` zurück und läuft mit dem geladenen Modell weiter
 *     (Never-Silent, s. [BrainAutoSwitchPort]-KDoc).
 *
 * **Beobachtbarkeit:** GENAU EINE Log-Zeile pro Entscheidung (Grund, von→nach,
 * Dauer ODER Fehler/Skip-Grund) — der Ops-/Diary-Pfad bleibt unangetastet, das
 * ist reines Anwendungs-Log.
 */
class BrainAutoSwitchService(
    private val store: JsonFileBrainAutoSwitchStore,
    private val switchPort: BrainSwitchModelPort,
    private val healthProbe: BrainHealthProbe,
    private val clock: Clock = Clock.systemUTC(),
    private val hysteresis: Duration = HYSTERESIS_WINDOW,
) : BrainAutoSwitchPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Zeitpunkt des letzten Wechsel-VERSUCHS (nicht nur Erfolge) — die Hysterese-Uhr. */
    private val lastAttemptAt = AtomicReference<Instant?>(null)

    override fun onVoiceSessionStart() {
        if (!store.enabled()) return
        // Fire-and-forget: der WS-Handler-Aufrufer darf NIE auf den Switch warten
        // (der Nutzer spricht währenddessen weiter) — eigener Scheduler, kein
        // Netty-Event-Loop-Block, Fehler landen ausschliesslich im Log (decide() wirft nie).
        decide(reason = REASON_VOICE_START, targetRepo = BrainModelCatalog.AUTO_SWITCH_VOICE_REPO)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe()
    }

    override fun ensureChatModel(): Mono<Unit> {
        if (!store.enabled()) return Mono.just(Unit)
        return decide(reason = REASON_CHAT_TURN, targetRepo = BrainModelCatalog.AUTO_SWITCH_CHAT_REPO)
    }

    /** Wirft NIE — jeder Fehler (Health-Probe, Switch-Call) endet als geloggtes no-op. */
    private fun decide(reason: String, targetRepo: String): Mono<Unit> =
        healthProbe.check()
            .flatMap { snapshot -> react(reason, targetRepo, snapshot.model) }
            .onErrorResume { e ->
                log.warn("[brain-auto-switch] {} {}→{} Entscheidung fehlgeschlagen (Health-Probe): {}", reason, "?", targetRepo, e.toString())
                Mono.just(Unit)
            }

    private fun react(reason: String, targetRepo: String, currentRepo: String?): Mono<Unit> {
        if (currentRepo == targetRepo) {
            log.info("[brain-auto-switch] {} {}→{} bereits geladen (no-op)", reason, currentRepo ?: "?", targetRepo)
            return Mono.just(Unit)
        }
        val now = clock.instant()
        val last = lastAttemptAt.get()
        if (last != null && Duration.between(last, now) < hysteresis) {
            log.info(
                "[brain-auto-switch] {} {}→{} übersprungen (Hysterese, letzter Versuch vor {} ms)",
                reason, currentRepo ?: "?", targetRepo, Duration.between(last, now).toMillis(),
            )
            return Mono.just(Unit)
        }
        lastAttemptAt.set(now)
        val t0 = System.nanoTime()
        return switchPort.switchModel(targetRepo).map { result ->
            val ms = (System.nanoTime() - t0) / 1_000_000
            when (result) {
                is BrainSwitchResult.Accepted ->
                    log.info("[brain-auto-switch] {} {}→{} ok ({} ms)", reason, currentRepo ?: "?", targetRepo, ms)
                is BrainSwitchResult.Unavailable ->
                    log.warn(
                        "[brain-auto-switch] {} {}→{} fehlgeschlagen ({} ms): {} — Turn läuft mit dem geladenen Modell weiter (Never-Silent)",
                        reason, currentRepo ?: "?", targetRepo, ms, result.detail,
                    )
            }
            Unit
        }
    }

    companion object {
        /**
         * **Hysterese-Fenster:** nie mehr als EIN Wechsel-Versuch pro 30s, auch wenn
         * Chat- und Voice-Turns im Sekundentakt abwechseln — verhindert Pumpen
         * zwischen den beiden 16-GB-Wand-Modellen. 30s liegt grosszügig über der
         * gemessenen Wechseldauer (2,6-4,3s warm, Andi-Drei-Modell-Vergleich
         * 2026-07-26): selbst ein Nutzer, der aktiv zwischen Tippen und Sprechen
         * hin- und herspringt, braucht für einen ECHTEN Rollenwechsel selbst länger
         * als das zwischen zwei Turns.
         */
        val HYSTERESIS_WINDOW: Duration = Duration.ofSeconds(30)

        const val REASON_VOICE_START = "voice-start"
        const val REASON_CHAT_TURN = "chat-turn"
    }
}
