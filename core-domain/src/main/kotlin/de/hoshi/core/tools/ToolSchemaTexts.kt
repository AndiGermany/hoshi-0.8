package de.hoshi.core.tools

import de.hoshi.core.dto.Language

/**
 * **Die Tool-Schema-Beschreibungen in allen 5 Sprachen (DE/EN/ES/FR/IT).**
 *
 * Schwester-Katalog zu `de.hoshi.adapters.knowledge.WeatherBlockTexts` (Sprach-Naht-
 * Scheibe 2026-07-25), herausgelöst aus [AgenticToolRegistry]. Diese Texte sind der
 * unauffälligste, aber sehr wirksame Sprach-Hebel: sie werden NIE angezeigt und NIE
 * gesprochen — sie gehen als `tools`-Feld in denselben `/v1/chat`-Call wie der
 * System-Prompt und sind damit Teil dessen, was das Modell VOR seiner ersten Silbe
 * liest. Deutsche Tool-Beschreibungen in einem englischen Turn sind ein deutscher
 * Anker, der die Antwortsprache zurückzieht.
 *
 * **Beschreibung vs. WERT — die Schnittkante:** übersetzt wird ausschließlich die
 * `description`. Die `enum`-WERTE (`on`/`off`) und die Raum-Ids
 * ([ToolAreas.AREAS] — `wohnzimmer`, `kuche`, …) sind der HA-Vertrag, also
 * Nutzerdaten: sie bleiben in JEDER Sprache identisch, auch in den Beispielen der
 * Beschreibung. Ein übersetztes „kitchen" fände in Home Assistant keinen Raum.
 *
 * **Ein `when`-Block pro Baustein, KEIN `else`:** eine sechste [Language] bricht hier
 * den Build, statt still auf Deutsch zu rutschen.
 *
 * **DE ist byte-eingefroren** (`AgenticToolRegistryTest` prüft die Schema-Struktur;
 * die deutschen Zweige sind ZEICHENGLEICH zum Stand vor dieser Scheibe).
 *
 * EN/ES/FR/IT sind idiomatisch formuliert, aber noch nicht von Muttersprachlern
 * gegengelesen.
 */
internal object ToolSchemaTexts {

    // ── light_set ────────────────────────────────────────────────────────────────

    fun lightDescription(language: Language): String = when (language) {
        Language.DE ->
            "Schaltet oder dimmt das Licht in einem Raum. " +
                "state=on schaltet ein, state=off schaltet aus. Optional brightness_pct " +
                "(0–100) zum Dimmen und color_name für eine Farbe."
        Language.EN ->
            "Switches or dims the light in a room. " +
                "state=on turns it on, state=off turns it off. Optional brightness_pct " +
                "(0–100) for dimming and color_name for a colour."
        Language.ES ->
            "Enciende, apaga o atenúa la luz de una habitación. " +
                "state=on la enciende, state=off la apaga. Opcionalmente brightness_pct " +
                "(0–100) para atenuar y color_name para un color."
        Language.FR ->
            "Allume, éteint ou tamise la lumière d'une pièce. " +
                "state=on allume, state=off éteint. En option brightness_pct " +
                "(0–100) pour tamiser et color_name pour une couleur."
        Language.IT ->
            "Accende, spegne o attenua la luce di una stanza. " +
                "state=on accende, state=off spegne. Facoltativi brightness_pct " +
                "(0–100) per attenuare e color_name per un colore."
    }

    /** Raum-Parameter des Lichts. Die Beispiel-Ids sind HA-Werte und bleiben unübersetzt. */
    fun lightArea(language: Language): String = when (language) {
        Language.DE -> "Der Raum, z.B. wohnzimmer, kuche, schlafzimmer."
        Language.EN -> "The room, e.g. wohnzimmer, kuche, schlafzimmer."
        Language.ES -> "La habitación, p. ej. wohnzimmer, kuche, schlafzimmer."
        Language.FR -> "La pièce, p. ex. wohnzimmer, kuche, schlafzimmer."
        Language.IT -> "La stanza, p. es. wohnzimmer, kuche, schlafzimmer."
    }

    fun lightState(language: Language): String = when (language) {
        Language.DE -> "Gewünschter Zustand des Lichts."
        Language.EN -> "Desired state of the light."
        Language.ES -> "Estado deseado de la luz."
        Language.FR -> "État souhaité de la lumière."
        Language.IT -> "Stato desiderato della luce."
    }

    fun lightBrightness(language: Language): String = when (language) {
        Language.DE -> "Helligkeit in Prozent (0–100), optional."
        Language.EN -> "Brightness in percent (0–100), optional."
        Language.ES -> "Brillo en porcentaje (0–100), opcional."
        Language.FR -> "Luminosité en pourcentage (0–100), facultatif."
        Language.IT -> "Luminosità in percentuale (0–100), facoltativo."
    }

    /** Der Farbname selbst MUSS englisch bleiben — HA kennt nur `red`/`blue`/`warm`. */
    fun lightColorName(language: Language): String = when (language) {
        Language.DE -> "Farbname (englisch), z.B. red, blue, warm. Optional."
        Language.EN -> "Colour name (English), e.g. red, blue, warm. Optional."
        Language.ES -> "Nombre del color (en inglés), p. ej. red, blue, warm. Opcional."
        Language.FR -> "Nom de la couleur (en anglais), p. ex. red, blue, warm. Facultatif."
        Language.IT -> "Nome del colore (in inglese), p. es. red, blue, warm. Facoltativo."
    }

    // ── climate_set ──────────────────────────────────────────────────────────────

    fun climateDescription(language: Language): String = when (language) {
        Language.DE -> "Setzt die Zieltemperatur der Heizung/Klima in einem Raum."
        Language.EN -> "Sets the target temperature of the heating/air conditioning in a room."
        Language.ES -> "Ajusta la temperatura objetivo de la calefacción o el aire acondicionado de una habitación."
        Language.FR -> "Règle la température cible du chauffage ou de la climatisation d'une pièce."
        Language.IT -> "Imposta la temperatura desiderata del riscaldamento o del climatizzatore di una stanza."
    }

    /** Raum-Parameter der Klima-Steuerung (andere Beispiel-Ids als beim Licht — HA-Werte). */
    fun climateArea(language: Language): String = when (language) {
        Language.DE -> "Der Raum, z.B. wohnzimmer, schlafzimmer, badezimmer."
        Language.EN -> "The room, e.g. wohnzimmer, schlafzimmer, badezimmer."
        Language.ES -> "La habitación, p. ej. wohnzimmer, schlafzimmer, badezimmer."
        Language.FR -> "La pièce, p. ex. wohnzimmer, schlafzimmer, badezimmer."
        Language.IT -> "La stanza, p. es. wohnzimmer, schlafzimmer, badezimmer."
    }

    fun climateTemperature(language: Language): String = when (language) {
        Language.DE -> "Zieltemperatur in Grad Celsius (z.B. 21)."
        Language.EN -> "Target temperature in degrees Celsius (e.g. 21)."
        Language.ES -> "Temperatura objetivo en grados Celsius (p. ej. 21)."
        Language.FR -> "Température cible en degrés Celsius (p. ex. 21)."
        Language.IT -> "Temperatura desiderata in gradi Celsius (p. es. 21)."
    }

    // ── scene_activate ───────────────────────────────────────────────────────────

    /** Die Beispiel-Szenennamen sind HA-Szenen (Nutzerdaten) und bleiben unübersetzt. */
    fun sceneDescription(language: Language): String = when (language) {
        Language.DE -> "Aktiviert eine benannte Szene (z.B. Kino, Entspannen)."
        Language.EN -> "Activates a named scene (e.g. Kino, Entspannen)."
        Language.ES -> "Activa una escena con nombre (p. ej. Kino, Entspannen)."
        Language.FR -> "Active une scène nommée (p. ex. Kino, Entspannen)."
        Language.IT -> "Attiva una scena con nome (p. es. Kino, Entspannen)."
    }

    fun sceneName(language: Language): String = when (language) {
        Language.DE -> "Name der Szene, die aktiviert werden soll."
        Language.EN -> "Name of the scene to activate."
        Language.ES -> "Nombre de la escena que se debe activar."
        Language.FR -> "Nom de la scène à activer."
        Language.IT -> "Nome della scena da attivare."
    }
}
