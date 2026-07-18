package de.hoshi.web

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * **AudioSessionGuard (Robustheits-Scheibe #1, Nils/Eda) — die ZEIT-Achse des
 * `/ws/audio`-Schutzes, flag-gated, default OFF.**
 *
 * Satellit-Research-Befund #1: der Server macht kein VAD und wartet nach `start`
 * unbegrenzt auf ein Ende-Signal — stirbt das Gerät mitten in der Aufnahme (oder
 * geht `stop` verloren), hängt die Session ewig. Die BYTE-Achse deckt der schon
 * existierende Audio-Cap ab (Ticket #9, `HOSHI_AUDIO_CAP_ENABLED`); diese Klasse
 * ergänzt NUR die fehlende Zeit-Achse mit zwei ehrlichen Proxys statt eines
 * VAD-Modells:
 *
 *  1. **Dauer-Deckel** ([maxRecordingDuration], Default 30 s): läuft eine Aufnahme
 *     so lange ohne `stop`, ist etwas kaputt (oder ein Monolog) ⇒ [Expiry.TOO_LONG],
 *     der Handler bricht den Turn warm & never-silent ab.
 *  2. **Silence-Timeout** ([silenceTimeout], Default 5 s): kommen so lange KEINE
 *     Audio-Frames mehr ohne Ende-Signal, ist die Aufnahme faktisch vorbei ⇒
 *     [Expiry.SILENCE], der Handler finalisiert (transkribiert, was da ist).
 *
 * **Reiner Zustands-Tracker** ohne eigenen Timer: der [AudioWebSocketHandler]
 * ruft [armRecording]/[onFrame]/[disarm] an seinen Frame-Rändern und pollt
 * [expire] über einen Sweep-Ticker. Die [Clock] ist injizierbar ⇒ Tests treiben
 * die Zeit deterministisch (fake Clock + manueller Sweep, kein `sleep`).
 *
 * [expire] hat **Drain-Semantik**: ein abgelaufener Eintrag wird beim ersten
 * Treffer entfernt ⇒ jeder Ablauf feuert genau EINMAL (idempotente Enforcement-
 * Seite). Bei `enabled=false` (Default) sind alle Methoden no-ops ⇒ byte-neutral.
 */
class AudioSessionGuard(
    val enabled: Boolean = false,
    /** Harter Deckel für die Gesamt-Dauer einer Aufnahme ohne `stop`. */
    private val maxRecordingDuration: Duration = Duration.ofMillis(DEFAULT_MAX_RECORDING_MS),
    /** Ehrlicher VAD-Proxy: so lange ohne neuen Frame ⇒ Aufnahme gilt als beendet. */
    private val silenceTimeout: Duration = Duration.ofMillis(DEFAULT_SILENCE_TIMEOUT_MS),
    /** Injizierbare Uhr (Tests: fake Clock statt Echtzeit/sleep). */
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Warum eine Aufnahme abgelaufen ist — bestimmt die Enforcement-Reaktion. */
    enum class Expiry {
        /** Dauer-Deckel gerissen ⇒ Turn warm abbrechen („Aufnahme zu lang"). */
        TOO_LONG,

        /** Keine Frames mehr ⇒ Aufnahme finalisieren (transkribieren, was da ist). */
        SILENCE,
    }

    private data class Recording(val startedAt: Instant, val lastFrameAt: Instant)

    private val recordings = ConcurrentHashMap<String, Recording>()

    /** `start`-Frame: Aufnahme-Tracking (neu) scharfschalten. No-op bei OFF. */
    fun armRecording(sessionId: String) {
        if (!enabled) return
        val now = clock.instant()
        recordings[sessionId] = Recording(startedAt = now, lastFrameAt = now)
    }

    /** Binärer Mic-Frame: Silence-Fenster auffrischen (nur wenn scharf). No-op bei OFF. */
    fun onFrame(sessionId: String) {
        if (!enabled) return
        recordings.computeIfPresent(sessionId) { _, r -> r.copy(lastFrameAt = clock.instant()) }
    }

    /** `stop`/`abort`/Session-Ende/Byte-Cap: Aufnahme-Tracking beenden. Idempotent. */
    fun disarm(sessionId: String) {
        recordings.remove(sessionId)
    }

    /**
     * Sweep-Check für EINE Session: abgelaufen? Dauer-Deckel wird VOR dem
     * Silence-Timeout geprüft (härtere Grenze gewinnt). Trifft ein Ablauf, wird
     * der Eintrag entfernt (Drain) ⇒ feuert genau einmal. `null` ⇒ alles gut
     * (nicht scharf, unter den Grenzen oder Guard OFF).
     */
    fun expire(sessionId: String): Expiry? {
        if (!enabled) return null
        val r = recordings[sessionId] ?: return null
        val now = clock.instant()
        val expiry = when {
            Duration.between(r.startedAt, now) >= maxRecordingDuration -> Expiry.TOO_LONG
            Duration.between(r.lastFrameAt, now) >= silenceTimeout -> Expiry.SILENCE
            else -> null
        }
        if (expiry != null) recordings.remove(sessionId, r)
        return expiry
    }

    companion object {
        /** Default-Dauer-Deckel: 30 s Aufnahme ohne Ende-Signal ist nie ein normaler Turn. */
        const val DEFAULT_MAX_RECORDING_MS = 30_000L

        /** Default-Silence-Timeout: 5 s ohne Frames ⇒ das Gerät sendet nicht mehr. */
        const val DEFAULT_SILENCE_TIMEOUT_MS = 5_000L
    }
}
