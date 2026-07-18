package de.hoshi.core.pipeline

import de.hoshi.core.dto.ProsodyTone
import de.hoshi.core.dto.TimeOfDay

/**
 * **Warmth v2 — passiver Mood-Hebel (0.8, default OFF).** Portiert die ESSENZ des
 * 0.5-`AndiMoodDetector` (siehe `vault/knowledge/0.5-SEELE-warmth-agentik.md`):
 * passives Mood-Sensing → **drei Energien CALM/NORMAL/WAKE** (Mira: „drei Töne,
 * nicht zwanzig") → ENTKOPPELTE Hebel (Temperatur + Prosodie). **NIE kommentieren —
 * nur anders sprechen** (kein „du klingst müde").
 *
 * Bewusst MINIMAL, REIN und DETERMINISTISCH (kein Sentiment-ML, kein Brain-Call):
 *  - **Zeit-Bias:** Stunde → [AmbientMood.bucket] → Abend/Nacht ⇒ CALM-Bias,
 *    Morgen ⇒ WAKE-Bias, Nachmittag ⇒ NORMAL. Die Stunde wird INJiziert ([hour]),
 *    NIE `now()` in dieser reinen Logik (Determinismus-Invariante des Codebases).
 *  - **Müdigkeits-Marker** im AKTUELLEN User-Input (DE: müde/kaputt/erschöpft/platt;
 *    EN: tired/exhausted/wiped) ⇒ CALM (dominiert den Zeit-Bias).
 *
 * Der einzige verdrahtete Hebel ist heute die **Temperatur** ([temperatureFor],
 * via den bestehenden `persona.temperatureFor()`-Pfad im [TurnOrchestrator], gegated
 * über [MoodTemperaturePort]). [prosodyTone] liefert den passenden [ProsodyTone] schon
 * mit (CALM dämpft), ist aber bewusst NOCH NICHT verdrahtet — der Text-Turn in 0.8 hat
 * (noch) keine Prosody-Naht (TTS kommt später). TODO: Prosody-Hebel andocken, sobald
 * der ProsodyShaper im Turn-Pfad sitzt.
 */
object WarmthMood {

    /** Die DREI Energien (Mira-Regel: nicht zwanzig). */
    enum class Energy { CALM, NORMAL, WAKE }

    /** Müdigkeits-Marker (DE + EN), lowercase-substring-Scan auf dem aktuellen Input. */
    val TIRED_MARKERS: List<String> = listOf(
        "müde", "kaputt", "erschöpft", "erschoepft", "platt",
        "tired", "exhausted", "wiped",
    )

    /** Trägt der AKTUELLE Input einen Müdigkeits-Marker? (rein, lowercase-Substring). */
    fun hasTiredMarker(text: String): Boolean {
        val t = text.lowercase()
        return TIRED_MARKERS.any { t.contains(it) }
    }

    /**
     * Energie-Bucket aus dem, was billig verfügbar ist: Müdigkeits-Marker (dominiert)
     * ODER Zeit-Bias der injizierten [hour]. Rein + deterministisch.
     */
    fun bucket(hour: Int, text: String): Energy {
        if (hasTiredMarker(text)) return Energy.CALM
        return when (AmbientMood.bucket(hour)) {
            TimeOfDay.NIGHT, TimeOfDay.EVENING -> Energy.CALM   // spät/abends → ruhiger
            TimeOfDay.MORNING                  -> Energy.WAKE   // morgens → wacher
            TimeOfDay.AFTERNOON                -> Energy.NORMAL // tagsüber → Default
        }
    }

    /**
     * Kleiner Temperatur-Nudge auf die [base]-Temperatur je Energie: CALM senkt
     * (ruhiger/vorhersehbarer), WAKE hebt leicht (wacher), NORMAL unverändert.
     * Auf [MIN_TEMP]..[MAX_TEMP] geklemmt (nie ins Roboterhafte/Chaotische kippen).
     * REIN über [bucket] — Stunde injiziert.
     */
    fun temperatureFor(base: Double, hour: Int, text: String): Double {
        val nudged = base + when (bucket(hour, text)) {
            Energy.CALM   -> CALM_NUDGE
            Energy.WAKE   -> WAKE_NUDGE
            Energy.NORMAL -> 0.0
        }
        return nudged.coerceIn(MIN_TEMP, MAX_TEMP)
    }

    /**
     * Energie → [ProsodyTone] (CALM dämpft Satzzeichen, WAKE = ENERGETIC durchgereicht,
     * NORMAL unverändert). Bereit für den ProsodyShaper, **noch nicht verdrahtet** (TODO,
     * s. Klassen-Doku) — der 0.8-Text-Turn hat keine Prosody-Naht.
     */
    fun prosodyTone(energy: Energy): ProsodyTone = when (energy) {
        Energy.CALM   -> ProsodyTone.CALM
        Energy.WAKE   -> ProsodyTone.ENERGETIC
        Energy.NORMAL -> ProsodyTone.NORMAL
    }

    /** CALM senkt die Temperatur (ruhiger), WAKE hebt sie leicht (wacher). Klein gehalten. */
    const val CALM_NUDGE = -0.10
    const val WAKE_NUDGE = 0.05

    /** Sichere Grenzen — nie unter ruhig-aber-lebendig, nie über chaotisch. */
    const val MIN_TEMP = 0.30
    const val MAX_TEMP = 0.90
}

/**
 * **Temperatur-Hebel-Naht für Warmth v2 — flag-gated, default OFF** (analog zu
 * [AmbientWarmthPort]). Wickelt die im [TurnOrchestrator] schon vorhandene
 * `persona.temperatureFor()` ein: bei OFF gibt [NONE] die Basis-Temperatur
 * UNVERÄNDERT zurück (Identität ⇒ byte-neutral, exakt dieselbe Temperatur an den
 * Brain). Erst bei `HOSHI_WARMTH_V2_ENABLED=true` bindet PipelineConfig den
 * clock-gebundenen Adapter, der die Stunde ([ClockPort.SYSTEM] — der EINZIGE
 * `now()`-Punkt) der reinen [WarmthMood.temperatureFor]-Logik reicht.
 */
fun interface MoodTemperaturePort {
    /** Passt die [baseTemperature] anhand des aktuellen [userText] an (OFF = identisch). */
    fun adjust(baseTemperature: Double, userText: String): Double

    companion object {
        /** Verhaltens-neutraler Default (Warmth v2 OFF) — gibt die Basis UNVERÄNDERT zurück. */
        val NONE: MoodTemperaturePort = MoodTemperaturePort { base, _ -> base }
    }
}
