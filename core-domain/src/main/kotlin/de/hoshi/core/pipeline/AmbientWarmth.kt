package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.TimeOfDay
import java.time.LocalTime

/**
 * **Uhr-Naht** für die Ambient-Schicht — der EINZIGE Ort, an dem die Wanduhr
 * gelesen wird. Die reine Logik ([AmbientMood]) bekommt die Stunde INJiziert und
 * ruft NIE selbst `now()` (Determinismus-Invariante des Codebases: kein Wanduhr-
 * Nichtdeterminismus in reiner Logik). Tests setzen eine feste Stunde über einen
 * Fake-[ClockPort]; produktiv liefert [SYSTEM] die echte lokale Stunde.
 *
 * Reines Kotlin/JDK (`java.time`), kein Spring.
 */
fun interface ClockPort {
    /** Lokale Stunde 0..23. */
    fun hour(): Int

    companion object {
        /** Impurer System-Default (Wanduhr). NUR hier — nie in reiner Logik. */
        val SYSTEM: ClockPort = ClockPort { LocalTime.now().hour }
    }
}

/**
 * **Ambient/Mood-Wärme-Naht (0.8) — flag-gated, default OFF.** Liefert (optional)
 * einen kleinen, ehrlichen Wärme-Hinweis, den der [TurnPromptAssembler] ans Ende
 * des System-Prompts schichtet. `null` = kein Hinweis ⇒ byte-neutral (Default):
 * der verdrahtete [NONE] schweigt, das bestehende Verhalten ändert sich NICHT.
 *
 * Bewusst KEIN zweiter Brain-Call und keine schwere Infra: der Hinweis ist ein
 * reiner String aus [AmbientMood] (Tageszeit-Bucket → kleiner Ton-Nudge), getunt
 * über die Turn-Sprache. Erst bei `HOSHI_AMBIENT_ENABLED=true` (PipelineConfig)
 * wird der clock-gebundene Adapter gebunden.
 */
fun interface AmbientWarmthPort {
    /** `null` = kein Hinweis (OFF / byte-neutral). */
    fun warmthHint(language: Language): String?

    companion object {
        /** Verhaltens-neutraler Default (Ambient OFF) — gibt NIE einen Hinweis. */
        val NONE: AmbientWarmthPort = AmbientWarmthPort { _ -> null }
    }
}

/**
 * Reine, deterministische Ambient-Logik: Stunde → Tageszeit-Bucket → kleiner
 * Wärme-Hinweis. KEINE Wanduhr hier — die Stunde kommt als Parameter ([hour]),
 * damit die Logik testbar/deterministisch bleibt.
 *
 * Der Hinweis ist EHRLICH und MINIMAL: er nudgt nur den Ton (abends/nachts wärmer
 * und ruhiger) und weist das Modell an, die Uhrzeit NICHT auszuplaudern. Er steht
 * am ENDE des Prompts (nach Persona+Entity), damit der byte-feste Persona-Prefix
 * (KV-Cache) unberührt bleibt — analog zu den bestehenden Entity-/Episodic-Blöcken.
 */
object AmbientMood {

    /** Stunde (0..23, robust gegen Über-/Unterlauf) → Tageszeit-Bucket. Spiegelt [PersonaService]. */
    fun bucket(hour: Int): TimeOfDay = when (((hour % 24) + 24) % 24) {
        in 5..11  -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..21 -> TimeOfDay.EVENING
        else      -> TimeOfDay.NIGHT
    }

    /** Wärme-Hinweis für die [hour] in der [language] (reine Komposition über [bucket]). */
    fun warmthHint(hour: Int, language: Language): String = warmthHintFor(bucket(hour), language)

    /**
     * Kleiner Wärme-Hinweis pro Tageszeit. Abend/Nacht nudgen wärmer/ruhiger; die
     * Anweisung „erwähne die Uhrzeit nicht" verhindert, dass das Modell die Tageszeit
     * ausplaudert. Sprach-getunt (DE/EN), exhaustiv über [TimeOfDay]. NUR [Language.DE]
     * bekommt den deutschen Hinweis — jede andere Sprache (ES/FR/IT eingeschlossen)
     * fällt auf EN zurück, bis ein Übersetzer-Pod eigene Strings liefert.
     */
    fun warmthHintFor(timeOfDay: TimeOfDay, language: Language): String =
        if (language == Language.DE) {
            when (timeOfDay) {
                TimeOfDay.MORNING   -> DE_MORNING
                TimeOfDay.AFTERNOON -> DE_DAY
                TimeOfDay.EVENING   -> DE_EVENING
                TimeOfDay.NIGHT     -> DE_NIGHT
            }
        } else {
            when (timeOfDay) {
                TimeOfDay.MORNING   -> EN_MORNING
                TimeOfDay.AFTERNOON -> EN_DAY
                TimeOfDay.EVENING   -> EN_EVENING
                TimeOfDay.NIGHT     -> EN_NIGHT
            }
        }

    const val DE_MORNING = "[Ambiente: Morgen — wach und freundlich, leichter Ton. Erwähne die Uhrzeit nicht.]"
    const val DE_DAY = "[Ambiente: Tag — normal locker und warm. Erwähne die Uhrzeit nicht.]"
    const val DE_EVENING = "[Ambiente: Abend — einen Tick wärmer und ruhiger im Ton. Erwähne die Uhrzeit nicht.]"
    const val DE_NIGHT = "[Ambiente: Spät — leise, warm und kurz. Erwähne die Uhrzeit nicht.]"

    const val EN_MORNING = "[Ambient: morning — awake and friendly, light tone. Don't mention the time.]"
    const val EN_DAY = "[Ambient: day — easy and warm as usual. Don't mention the time.]"
    const val EN_EVENING = "[Ambient: evening — a touch warmer and calmer in tone. Don't mention the time.]"
    const val EN_NIGHT = "[Ambient: late — quiet, warm and brief. Don't mention the time.]"
}
