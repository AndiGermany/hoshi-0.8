package de.hoshi.adapters.memory

import de.hoshi.core.dto.Language

/**
 * **Der RAHMEN der beiden Gedächtnis-Blöcke in allen 5 Sprachen (DE/EN/ES/FR/IT).**
 *
 * Schwester-Katalog zu `de.hoshi.adapters.knowledge.WeatherBlockTexts` (Sprach-Naht-
 * Scheibe 2026-07-25), herausgelöst aus [EntityMemoryAdapter] (Entity-Recall) und
 * [EpisodicMemoryAdapter] (Episodic-Recall). Beide Blöcke werden NIE angezeigt und NIE
 * gesprochen — der `TurnPromptAssembler` schichtet sie WÖRTLICH in den System-Prompt.
 * Genau deshalb sind sie sprach-relevant: ein deutscher Gedächtnis-Rahmen samt
 * deutscher Handlungs-Anweisung zieht die Antwort selbst bei englischer Persona
 * zuverlässig nach Deutsch.
 *
 * **Rahmen vs. gespeicherter Inhalt — die Schnittkante:** die Fakt-Zeilen
 * (`- hund: Bello`) und die früheren Turn-Texte sind NUTZERDATEN. Sie bleiben
 * IMMER unangetastet in der Sprache, in der sie gesagt/gespeichert wurden — dieser
 * Katalog liefert ausschließlich Kopf, Fuß und Label drumherum. Übersetzte
 * Nutzerdaten wären eine Fälschung des Gedächtnisses (und, bei HA-Raumnamen, ein
 * kaputter Smart-Home-Bezug).
 *
 * **Ein `when`-Block pro Baustein, KEIN `else`:** eine sechste [Language] bricht hier
 * den Build, statt still auf Deutsch zu rutschen.
 *
 * **DE ist byte-eingefroren:** die deutschen Zweige sind ZEICHENGLEICH zum Stand vor
 * dieser Scheibe (`EntityMemoryAdapterTest`/`EpisodicMemoryAdapterTest` hängen an
 * ihnen).
 *
 * EN/ES/FR/IT sind idiomatisch formuliert, aber noch nicht von Muttersprachlern
 * gegengelesen.
 */
internal object MemoryBlockTexts {

    /**
     * Kopfzeile des Entity-Gedächtnis-Blocks (inkl. abschließendem Zeilenumbruch);
     * die Fakt-Zeilen des Sprechers folgen unverändert darunter.
     */
    fun entityHead(language: Language): String = when (language) {
        Language.DE -> "[Gedächtnis — was du über den aktuellen Sprecher aus früheren Gesprächen weißt:\n"
        Language.EN -> "[Memory — what you know about the current speaker from earlier conversations:\n"
        Language.ES -> "[Memoria — lo que sabes sobre la persona que habla, de conversaciones anteriores:\n"
        Language.FR -> "[Mémoire — ce que tu sais de la personne qui parle, d'après des conversations précédentes :\n"
        Language.IT -> "[Memoria — ciò che sai sulla persona che parla da conversazioni precedenti:\n"
    }

    /**
     * Fußzeile des Entity-Blocks (inkl. führendem Zeilenumbruch und der schließenden
     * Klammer): die eigentliche Handlungs-Anweisung an das Modell. Bewusst EIN Satz —
     * jede Zusatzregel kostet bei einem 4B Befolgung.
     */
    fun entityTail(language: Language): String = when (language) {
        Language.DE -> "\nWenn er nach einer dieser Angaben fragt, antworte damit.]"
        Language.EN -> "\nIf they ask for one of these details, answer with it.]"
        Language.ES -> "\nSi pregunta por alguno de estos datos, responde con él.]"
        Language.FR -> "\nS'il demande l'une de ces informations, réponds avec celle-ci.]"
        Language.IT -> "\nSe chiede uno di questi dati, rispondi con quello.]"
    }

    /**
     * Der komplette Episodic-Recall-Block. [joined] sind die WÖRTLICHEN früheren
     * Turn-Texte des Sprechers (mit `; ` verbunden) — reine Nutzerdaten, die dieser
     * Katalog nur einrahmt und nie anfasst.
     */
    fun episodicRecall(language: Language, joined: String): String = when (language) {
        Language.DE -> "[Früher gesagt: $joined]"
        Language.EN -> "[Said earlier: $joined]"
        Language.ES -> "[Dicho antes: $joined]"
        Language.FR -> "[Dit plus tôt : $joined]"
        Language.IT -> "[Detto in precedenza: $joined]"
    }
}
