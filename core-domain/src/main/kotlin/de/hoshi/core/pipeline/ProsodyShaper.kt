package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.PersonaEmotion
import de.hoshi.core.dto.ProsodyTone

/**
 * Text-Prosodie (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger).
 *
 * Voxtral (mlx-audio) hat KEINE Emotions-/Prosodie-API — der einzige ehrliche
 * Hebel ist der TEXT selbst: Voxtral reagiert stark auf Satzzeichen. Also formen
 * wir die Satzzeichen je nach Tonfall, BEVOR der Text an die TTS geht.
 *
 * Bewusst rein + deterministisch (kein ML): testbar, latenzfrei (String-Op).
 */
object ProsodyShaper {

    /** PersonaEmotion → Tonfall. WARM/FOCUSED/NEUTRAL bleiben NORMAL (Default). */
    fun toneFor(emotion: PersonaEmotion): ProsodyTone = when (emotion) {
        PersonaEmotion.CALM -> ProsodyTone.CALM
        PersonaEmotion.CHEERFUL -> ProsodyTone.ENERGETIC
        PersonaEmotion.WARM,
        PersonaEmotion.FOCUSED,
        PersonaEmotion.NEUTRAL,
        -> ProsodyTone.NORMAL
    }

    /**
     * Formt [text] für den gesprochenen Klang. Nur CALM verändert aktiv (dämpft);
     * NORMAL und ENERGETIC reichen unverändert durch. Blank-Text → unverändert.
     *
     * CALM-Regeln (sprach-neutral, da rein satzzeichen-basiert):
     *  - „!" → „." (kein Ausruf-Klang)
     *  - Gruppen mit „?" bleiben Frage („?!" / „?." → „?")
     *  - „..." aus kollabierten Ausrufen → ein „."
     *
     * [language] (0.8): heute DE-/satzzeichen-Logik für alle Sprachen identisch.
     * Der Parameter trägt die Sprache durch, damit per-Sprache-Muster später hier
     * andocken können, ohne die Signatur zu brechen. Default [Language.DEFAULT].
     */
    fun shape(text: String, tone: ProsodyTone, language: Language = Language.DEFAULT): String {
        if (tone != ProsodyTone.CALM || text.isBlank()) return text
        return text
            .replace('!', '.')
            .replace(Regex("""\.*\?\.*"""), "?")
            .replace(Regex("""\.{2,}"""), ".")
    }

    /**
     * Tonbucket → Voxtral-Sampling-Temperatur. Voxtral reagiert messbar auf
     * `temperature` (F0-Std steigt mit der Temperatur):
     *  - ENERGETIC → 0.75 (mehr Lächeln)
     *  - NORMAL → 0.70 (Modell-Default)
     *  - CALM → 0.55 (Ruhe; konservativ, NICHT tiefer — sonst kippt „flach/roboterhaft")
     */
    fun temperatureFor(tone: ProsodyTone): Double = when (tone) {
        ProsodyTone.ENERGETIC -> 0.75
        ProsodyTone.NORMAL -> 0.70
        ProsodyTone.CALM -> 0.55
    }
}
