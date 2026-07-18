package de.hoshi.core.dto

import com.fasterxml.jackson.annotation.JsonCreator

/**
 * **Persona** — die per-Turn waehlbare Charakter-Stimme Hoshis, wie der Client sie
 * im Chat-Body schickt ("Standard"/"Kumpel"/"Knapp"/"Ruhig"). Bewusst ein
 * SEPARATER Typ neben [PersonaEmotion]: die Persona ist die vom Nutzer GEWAEHLTE
 * Grundhaltung (waehlt Prompt-Body + Default-Stimmung), [PersonaEmotion] ist der
 * abgeleitete Tonfall, der in [de.hoshi.core.pipeline.PersonaService.temperatureFor]
 * auf die Sampling-Temperatur faellt.
 *
 * **Per-Request, byte-stabil.** Die Persona reist MIT dem Request
 * ([ChatRequest.persona] -> [TurnPrompt.persona]); KEIN globaler Mood-Holder, der
 * bei parallelen Satelliten-Sessions kollidieren wuerde. Jeder Persona-Body ist
 * ueber alle Turns byte-stabil (KV-Cache-Prefix), die Default-Stimmung wird
 * per-Turn getragen statt prozessweit gesetzt.
 *
 * [defaultMood]: die per-Persona Default-Stimmung, die [PersonaService.moodFor]
 * in die Sampling-Temperatur uebersetzt.
 *  - [STANDARD] -> `null`: KEINE feste Stimmung — faellt auf die heutige
 *    Tageszeit-abgeleitete `currentEmotionEnum()` zurueck ⇒ byte-identisch zu heute.
 *  - [KUMPEL]   -> CHEERFUL (hoehere Temperatur, mehr Energie/Varietaet).
 *  - [KNAPP]    -> FOCUSED  (niedrigere Temperatur, wortkarg/vorhersagbar).
 *  - [RUHIG]    -> CALM     (niedrigere Temperatur, sanft/entschleunigt).
 *
 * Flag-gated am Inbound-Rand ([de.hoshi.core.pipeline.PersonaResolver],
 * `HOSHI_PERSONA_ENABLED`): bei OFF kollabieren ALLE Personas auf [STANDARD]
 * (byte-neutral); bei ON sind die vier distinkt.
 */
enum class Persona(
    /** Anzeige-/Wire-Code wie ihn das Frontend schickt ("Standard"/"Kumpel"/...). */
    val code: String,
    /** Per-Persona Default-Stimmung; `null` = keine feste (Tageszeit-Default). */
    val defaultMood: PersonaEmotion?,
) {
    /** Hoshis Grundton: warm, locker, kumpelhaft. BYTE-IDENTISCH zum heutigen Verhalten. */
    STANDARD("Standard", null),

    /** Noch flapsiger und spielfreudiger: duzt, witzelt, mehr Energie und Slang. */
    KUMPEL("Kumpel", PersonaEmotion.CHEERFUL),

    /** Wortkarg und sachlich: kuerzeste Antworten, kein Geplaenkel, nur das Noetige. */
    KNAPP("Knapp", PersonaEmotion.FOCUSED),

    /** Sanft und gelassen: leise, entschleunigt, gedaempfte Saetze ohne Ausrufe. */
    RUHIG("Ruhig", PersonaEmotion.CALM),
    ;

    companion object {
        /**
         * Tolerant aus einem Roh-String (Wire-Tag), case-insensitiv auf den NAMEN:
         * "Standard"/"standard"/"STANDARD" -> [STANDARD], "Kumpel" -> [KUMPEL] usw.
         *
         * **Unbekannt / null / leer -> [STANDARD]** (bewusst Grundton-Default): ein
         * fehlender oder unbekannter Persona-Wert (alter Client, neuer Research-Name)
         * spricht in Hoshis Grundstimme weiter, statt den Turn zu brechen.
         *
         * [JsonCreator]: Jackson deserialisiert den Wire-String ("Kumpel") direkt
         * ueber diese Fabrik (das FE schickt PascalCase, nicht den GROSS-Enum-Namen).
         */
        @JvmStatic
        @JsonCreator
        fun fromCode(raw: String?): Persona {
            val c = raw?.trim() ?: return STANDARD
            if (c.isBlank()) return STANDARD
            return entries.firstOrNull { it.name.equals(c, ignoreCase = true) } ?: STANDARD
        }
    }
}
