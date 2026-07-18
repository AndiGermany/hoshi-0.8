package de.hoshi.web

import de.hoshi.core.port.SpeakerEmbedPort
import org.slf4j.LoggerFactory

/**
 * Ergebnis einer Sprecher-ERKENNUNG (nach-aussen-Sicht, OHNE Vektor — Biometrie
 * verlaesst den Store nie). Vera-Vertrag: [name] ist NUR bei einem sicheren Treffer
 * ueber der Konfidenz-Schwelle gesetzt; im Zweifel bleibt [name]==null und
 * [isGuest]==true. Es wird NIE eine Person geraten (Fehl-Zuordnung == 0).
 *
 * [confidence] ist der beste Cosine-Score (∈[-1,1]) — auch im Gast-Fall gefuellt
 * (informativer Near-Miss fuers FE/Diagnose), aber ohne [name] ⇒ keine Bindung.
 */
data class Recognition(
    val name: String?,
    val confidence: Double,
    val isGuest: Boolean,
) {
    companion object {
        /** Der sichere Default: Gast, kein Name, keine Konfidenz. */
        val GUEST = Recognition(name = null, confidence = 0.0, isGuest = true)

        /** Gast MIT Near-Miss-Score (unter der Schwelle) — informativ, ohne Bindung. */
        fun guest(confidence: Double) = Recognition(name = null, confidence = confidence, isGuest = true)
    }
}

/**
 * **SpeakerIdentifyService** — die Sprecher-ERKENNUNGS-Naht (S3): aus einer Audio-Probe
 * die enrollte Person wiedererkennen. Schwester des Enroll-Rands ([SpeakerController]),
 * aber READ-ONLY: sie schreibt NIE in den [SpeakerProfileStore], sie vergleicht nur.
 *
 * **Flag-gated ([enabled]):** bei OFF (Default) der verhaltens-neutrale [DISABLED]
 * (liefert immer Gast, ruehrt den Sidecar nicht an) ⇒ der [VoiceInboundController]
 * haengt gar keinen `speakerContext` an ⇒ byte-neutral zum heutigen Voice-Pfad.
 *
 * **Vera-Regel HART:** unter der Schwelle NIE eine Person zuordnen. Jeder Zweifel
 * (Sidecar down, leeres Embedding, leerer Store, bester Score < Schwelle) endet in
 * [Recognition.GUEST] — Fehl-Zuordnung == 0. Der Gast-Pfad nutzt die fertige
 * `isGuest`-Haertung im Memory (kein Load/Write).
 */
interface SpeakerIdentifyService {
    /** Ist die Erkennung scharf? Bei false haengt der Controller keinen Kontext an (byte-neutral). */
    val enabled: Boolean

    /**
     * Probe → [Recognition]. [mime] ist der Content-Type der Probe (Ehrlichkeit ueber das
     * Empfangene; der CAM++-Sidecar erkennt WAV selbst am RIFF-Magic, [mime] wird NICHT
     * weitergereicht). Best-Effort/never-throw: jeder Fehler ⇒ Gast.
     *
     * **Blockierend** (java.net.http im Embed-Adapter) ⇒ der Aufrufer MUSS das auf
     * `Schedulers.boundedElastic()` auslagern, nie auf dem Netty-Event-Loop.
     */
    fun identify(audioBytes: ByteArray, mime: String): Recognition

    companion object {
        /** OFF-Sentinel: liefert immer Gast, kein Sidecar-Call. */
        val DISABLED: SpeakerIdentifyService = object : SpeakerIdentifyService {
            override val enabled: Boolean = false
            override fun identify(audioBytes: ByteArray, mime: String): Recognition = Recognition.GUEST
        }
    }
}

/**
 * Legt fest, wie mehrere Enrollment-Aufnahmen zu genau einem Profil-Score werden.
 *
 * [BEST_SAMPLE] erhaelt das bestehende Verhalten. [CENTROID] vergleicht genau einmal
 * gegen das im Store bereits L2-normalisiert gemittelte Profil und haelt dadurch die
 * Anzahl der Score-Versuche pro Profil konstant. Ein Wechsel der Strategie braucht
 * immer eine neue Kalibrierung; deshalb bleibt der Bestand der Default.
 */
enum class SpeakerProfileAggregation {
    BEST_SAMPLE,
    CENTROID;

    companion object {
        /** Strikte Config-Grenze: unbekannte Werte starten nicht mit still falscher Semantik. */
        fun parse(value: String): SpeakerProfileAggregation = when (value.trim().lowercase().replace('_', '-')) {
            "best-sample" -> BEST_SAMPLE
            "centroid" -> CENTROID
            else -> throw IllegalArgumentException(
                "Unbekannte Speaker-Profil-Aggregation '$value'; erwartet: best-sample|centroid",
            )
        }
    }
}

/**
 * Cosine-Erkennung gegen ALLE enrollten Profile: Probe embedden ([SpeakerEmbedPort.embed]),
 * bester Cosine ([SpeakerEmbedPort.similarity]) ueber [SpeakerProfileStore.all]. Ist der
 * beste Score >= [threshold], wird der Name gebunden; sonst Gast.
 *
 * **Schwellen-Wahl ([threshold], Default 0.80):** der 0.5-`hoshi-speaker-id`-Sidecar
 * (`/verify`, `_decide`) entscheidet 2-schwellig — `known` (>= 0.80, bindet eine
 * speakerId), `uncertain` (0.50..0.80, bindet BEWUSST KEINE), `guest` (< 0.50). Da
 * [Recognition] binaer ist (Name ODER Gast), kollabiert die `uncertain`-Zone in Gast —
 * die maximal Vera-sichere Lesart: eine ID wird nur im `known`-Band gebunden, exakt wie
 * das Referenz-Verhalten (`match` nur bei `known`). Konfigurierbar
 * (`hoshi.speaker.recognition.threshold`), damit die Schwelle nach echter Enroll-ROC
 * kalibriert werden kann — aber der Default liegt bewusst am `known`-Tau, NICHT im
 * `uncertain`-Band (0.6 laege dort ⇒ Fehl-Zuordnungs-Risiko).
 *
 * **Log-Disziplin (Tom, justiert 06.07):** kein Vektor im Log — hart. Der NAME des besten
 * Kandidaten steht aber bewusst in der identify-Zeile (Treffer UND Gast-Fall): die Namen
 * liegen im Store ohnehin im Klartext (kein Privacy-Delta), und ohne den Kandidaten war
 * die 2-Profil-Diagnose blind (Live-Befund 06.07: Gast-Scores 0.27/0.34 — gegen WEN?).
 *
 * **Profil-Aggregation:** [aggregation] ist absichtlich explizit und steht standardmaessig
 * auf [SpeakerProfileAggregation.BEST_SAMPLE], damit das Feature im Default-Pfad neutral
 * bleibt. [SpeakerProfileAggregation.CENTROID] nutzt das vom Store bereits berechnete,
 * L2-normalisierte Mittel-Embedding und vermeidet die mit der Samplezahl wachsende Zahl
 * an Impostor-Score-Versuchen. Ein Strategie-Wechsel erfordert eine neue Kalibrierung.
 */
class CosineSpeakerIdentifyService(
    private val embedPort: SpeakerEmbedPort,
    private val store: SpeakerProfileStore,
    private val threshold: Double,
    /**
     * **Abstands-Regel (Vera, nach Live-Fehl-Zuordnung 2026-07-07):** Andis Stimme traf
     * Cindys 1-Sample-Profil mit 0.564 — über der Schwelle, FALSCH gebunden. Bei ≥2
     * Profilen wird nur noch gebunden, wenn der beste Kandidat den zweiten um
     * [margin] schlägt; Paar-Stimmen im Graubereich ⇒ ehrlich Gast (Gast ist sicher,
     * die falsche Person nie). Konfigurierbar: `hoshi.speaker.recognition.margin`.
     */
    private val margin: Double = 0.10,
    private val aggregation: SpeakerProfileAggregation = SpeakerProfileAggregation.BEST_SAMPLE,
) : SpeakerIdentifyService {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // Ops-Readback (Test-Gate Punkt 6): der aktive Aggregationsmodus muss beim
        // Testen BEWEISBAR sein — eine eindeutige Boot-Zeile statt Config-Glauben.
        log.info("[speaker-identify] aktiv: aggregation={} threshold={} margin={}", aggregation, threshold, margin)
    }

    override val enabled: Boolean = true

    override fun identify(audioBytes: ByteArray, mime: String): Recognition {
        if (audioBytes.isEmpty()) return Recognition.GUEST
        // Best-Effort: Sidecar down/Fehler/leeres Audio ⇒ null ⇒ Gast (nie werfen).
        val probe = runCatching { embedPort.embed(audioBytes, mime) }.getOrElse { e ->
            log.warn("[speaker-identify] embed fehlgeschlagen (best-effort Gast): {}", e.message)
            null
        }
        if (probe == null || probe.isEmpty()) return Recognition.GUEST

        val profiles = store.all()
        if (profiles.isEmpty()) return Recognition.GUEST // leerer Store ⇒ Gast

        var bestName: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        for (p in profiles) {
            val s = scoreProfile(probe, p)
            if (s > bestScore) {
                secondScore = bestScore
                bestScore = s
                bestName = p.name
            } else if (s > secondScore) {
                secondScore = s
            }
        }
        val score = if (bestScore.isFinite()) bestScore else 0.0
        val runnerUp = if (secondScore.isFinite()) secondScore else null

        // Abstands-Regel: bei mehreren Profilen muss der Sieg EINDEUTIG sein.
        if (runnerUp != null && score >= threshold && (score - runnerUp) < margin) {
            log.info(
                "[speaker-identify] MEHRDEUTIG: bester Kandidat '{}' score={} vs. Zweiter score={} (Abstand < {}) ⇒ Gast (Vera-Regel: nie raten)",
                bestName, "%.3f".format(score), "%.3f".format(runnerUp), margin,
            )
            return Recognition.guest(score)
        }

        return if (bestName != null && score >= threshold) {
            log.info(
                "[speaker-identify] Treffer: bester Kandidat '{}' score={} (Schwelle {}) — Kontext gebunden",
                bestName,
                "%.3f".format(score),
                threshold,
            )
            Recognition(name = bestName, confidence = score, isGuest = false)
        } else {
            // Vera-Regel: unter der Schwelle NIE zuordnen ⇒ Gast (mit Near-Miss-Score).
            // INFO statt debug (Andi-Live-Befund 05.07: „nur Gast" ohne sichtbaren Score
            // war undiagnostizierbar); seit 06.07 MIT Kandidaten-Namen — sonst ist bei
            // mehreren Profilen unentscheidbar, gegen wen der Near-Miss lief.
            log.info(
                "[speaker-identify] kein sicherer Treffer: bester Kandidat '{}' score={} < Schwelle {} ⇒ Gast",
                bestName ?: "-",
                "%.3f".format(score),
                threshold,
            )
            Recognition.guest(score)
        }
    }

    private fun scoreProfile(probe: FloatArray, profile: SpeakerProfile): Double = when (aggregation) {
        SpeakerProfileAggregation.BEST_SAMPLE -> {
            // Der Fallback schuetzt Legacy-Profile; der Store liefert regulaer mindestens ein Sample.
            val samples = profile.samples.ifEmpty { listOf(profile.embedding) }
            samples.maxOf { sample -> embedPort.similarity(probe, sample) }
        }

        SpeakerProfileAggregation.CENTROID -> embedPort.similarity(probe, profile.embedding)
    }
}
