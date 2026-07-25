package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language

/**
 * **Der RAHMEN des Wetter-Grounding-Blocks in allen 5 Sprachen (DE/EN/ES/FR/IT).**
 *
 * Schwester-Katalog zu [WeatherCodeTexts] (dort: die WMO-Wetterlage), heraus-
 * gelöst aus [WeatherGroundingProvider] (Sprach-Naht-Scheibe 2026-07-25). Befund,
 * der das motiviert: der Katalog war übersetzt, der RAHMEN um ihn herum aber hart
 * deutsch — ein `language = EN`-Turn bekam „HINTERGRUND (nur für dich…)", „• Wetter
 * … bis … Grad" und eine deutsche „ANWEISUNG: Nutze diese ECHTEN Wetterdaten…"
 * mitsamt englischer Wetterlage. Der Block geht WÖRTLICH in den Brain-Prompt, und
 * ein deutscher Datenblock plus deutsche Anweisung zieht die Antwort selbst bei
 * englischer Persona zuverlässig nach Deutsch.
 *
 * **Ein `when`-Block pro Baustein** (statt einer Klasse pro Sprache): jeder
 * Baustein ist kurz genug, um alle fünf Fassungen nebeneinander zu lesen und zu
 * vergleichen — genau das braucht man beim Prüfen einer Anweisung, die ein LLM
 * befolgen soll. KEIN `else`-Zweig: eine sechste [Language] bricht hier den Build,
 * statt still auf Deutsch/Englisch zu rutschen (Muster [Language]-KDoc).
 *
 * **DE ist byte-eingefroren:** die deutschen Zweige sind ZEICHENGLEICH zum Stand
 * vor dieser Scheibe (Pin-Tests in `WeatherGroundingProviderTest` hängen direkt an
 * ihnen — ON-Block, OFF-Block, Stoppwort-Block). Wer hier ein DE-Zeichen anfasst,
 * fasst den Prompt an, den Andi seit Monaten hört.
 *
 * **Zielleser ist ein LLM, kein Ohr:** die Texte werden nie vorgelesen (der Block
 * ist „nur für dich"), deshalb zählt idiomatische Klarheit vor Wohlklang.
 * EN/ES/FR/IT sind idiomatisch formuliert, aber (wie [WeatherCodeTexts] ES/FR/IT)
 * noch nicht von Muttersprachlern gegengelesen.
 *
 * **Anführungszeichen-Hygiene:** außerhalb von DE stehen „…" bzw. «…» NICHT zur
 * Verfügung — «» ist der Verbatim-Vertrags-Marker
 * ([de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers] strippt ihn aus
 * jedem Delta), also nutzen die anderen Sprachen “…” statt französischer
 * Guillemets.
 */
internal object WeatherBlockTexts {

    /** Kopfzeile des Hintergrund-Blocks (inkl. abschließendem Zeilenumbruch). */
    fun head(language: Language): String = when (language) {
        Language.DE -> "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n"
        Language.EN -> "BACKGROUND (for you only, do NOT mention it in the conversation):\n"
        Language.ES -> "CONTEXTO (solo para ti, NO lo menciones en la conversación):\n"
        Language.FR -> "CONTEXTE (pour toi uniquement, NE le mentionne PAS dans la conversation) :\n"
        Language.IT -> "CONTESTO (solo per te, NON menzionarlo nella conversazione):\n"
    }

    /**
     * Eine Tages-Zeile (inkl. abschließendem Zeilenumbruch). Die Werte kommen
     * FERTIG markiert herein ([WeatherGroundingProvider.mark] setzt «…», wenn der
     * WeatherNumberContract an ist) — dieser Katalog kennt den Vertrag nicht und
     * baut nur die Schablone drumherum.
     */
    fun line(
        language: Language,
        label: String,
        day: String,
        min: String,
        max: String,
        condition: String,
        precip: String,
    ): String = when (language) {
        Language.DE -> "• Wetter $label $day: $min bis $max Grad, $condition, $precip.\n"
        Language.EN -> "• Weather $label $day: $min to $max degrees, $condition, $precip.\n"
        Language.ES -> "• Tiempo $label $day: de $min a $max grados, $condition, $precip.\n"
        Language.FR -> "• Météo $label $day : de $min à $max degrés, $condition, $precip.\n"
        Language.IT -> "• Meteo $label $day: da $min a $max gradi, $condition, $precip.\n"
    }

    /** Niederschlags-Hinweis der Tages-Zeile: ab ~0,5 mm konkret, sonst „kaum". */
    fun precipitation(language: Language, mm: Int, measurable: Boolean): String = when (language) {
        Language.DE -> if (measurable) "etwa $mm mm Niederschlag" else "kaum Niederschlag"
        Language.EN -> if (measurable) "about $mm mm of precipitation" else "hardly any precipitation"
        Language.ES -> if (measurable) "unos $mm mm de precipitación" else "apenas precipitación"
        Language.FR -> if (measurable) "environ $mm mm de précipitations" else "quasiment pas de précipitations"
        Language.IT -> if (measurable) "circa $mm mm di precipitazioni" else "quasi nessuna precipitazione"
    }

    /**
     * Die Haupt-ANWEISUNG unter den Daten. Bewusst kurz — jede Zusatzregel kostet
     * bei einem 4B Befolgung.
     */
    fun instruction(language: Language): String = when (language) {
        Language.DE ->
            "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“."
        Language.EN ->
            "INSTRUCTION: Use this REAL weather data and answer briefly in your own warm style — " +
                "add nothing you were not given and never mention “the API”, “Open-Meteo” or “the text”."
        Language.ES ->
            "INSTRUCCIÓN: Usa estos datos meteorológicos REALES y responde brevemente con tu propio estilo cálido — " +
                "no inventes nada más y nunca menciones “la API”, “Open-Meteo” ni “el texto”."
        Language.FR ->
            "INSTRUCTION : Utilise ces données météo RÉELLES et réponds brièvement dans ton style chaleureux — " +
                "n'invente rien de plus et ne mentionne jamais “l'API”, “Open-Meteo” ou “le texte”."
        Language.IT ->
            "ISTRUZIONE: Usa questi dati meteo REALI e rispondi in breve con il tuo stile caloroso — " +
                "non inventare nulla in più e non menzionare mai “l'API”, “Open-Meteo” o “il testo”."
    }

    /** Zusatz NUR bei expliziter Tages-Referenz (führendes Leerzeichen gehört dazu). */
    fun explicitDaySuffix(language: Language): String = when (language) {
        Language.DE -> " Antworte für den gefragten Tag; nenne den Tag beim Namen."
        Language.EN -> " Answer for the day that was asked about; name that day explicitly."
        Language.ES -> " Responde para el día preguntado; menciona ese día por su nombre."
        Language.FR -> " Réponds pour le jour demandé ; nomme ce jour explicitement."
        Language.IT -> " Rispondi per il giorno richiesto; nomina quel giorno per nome."
    }

    /**
     * **WeatherNumberContract**-Instruktion (nur wenn der Vertrag AN ist) —
     * erklärt bewusst NICHT, was «» bedeutet oder dass es beim Sprechen
     * verschwindet: das ist Prompt-Interna, die Marker-Hygiene erledigt
     * deterministisch [de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers].
     */
    fun contract(language: Language): String = when (language) {
        Language.DE ->
            "WETTER-VERTRAG: Die Werte in «» oben (Ort, Tag, Temperaturen, Wetterlage) sind exakt. " +
                "Nenne sie genau so weiter — gleicher Tagesbezug, gleicher Ortsname, gleiche Ziffern, gleiche Einheit — " +
                "nicht runden, nicht umformulieren, keinen anderen Ort oder Wert erfinden."
        Language.EN ->
            "WEATHER CONTRACT: The values in «» above (place, day, temperatures, conditions) are exact. " +
                "Pass them on exactly — same day reference, same place name, same digits, same unit — " +
                "do not round, do not rephrase, do not invent another place or value."
        Language.ES ->
            "CONTRATO METEOROLÓGICO: Los valores entre «» de arriba (lugar, día, temperaturas, estado del cielo) son exactos. " +
                "Repítelos tal cual — mismo día, mismo nombre de lugar, mismas cifras, misma unidad — " +
                "sin redondear, sin reformular, sin inventar otro lugar ni otro valor."
        Language.FR ->
            "CONTRAT MÉTÉO : Les valeurs entre «» ci-dessus (lieu, jour, températures, conditions) sont exactes. " +
                "Reprends-les telles quelles — même jour, même nom de lieu, mêmes chiffres, même unité — " +
                "sans arrondir, sans reformuler, sans inventer un autre lieu ni une autre valeur."
        Language.IT ->
            "CONTRATTO METEO: I valori tra «» qui sopra (luogo, giorno, temperature, condizioni) sono esatti. " +
                "Riportali identici — stesso giorno, stesso nome del luogo, stesse cifre, stessa unità — " +
                "senza arrotondare, senza riformulare, senza inventare un altro luogo o un altro valore."
    }

    /**
     * Ehrlicher Hinweis, wenn ein EXPLIZIT genannter Ort nicht geocodet werden
     * konnte — Gegenstück zur Datenzeile: der Brain soll die Lücke offen benennen
     * statt Heimat-Wetter unterzuschieben.
     */
    fun placeNotFound(language: Language, place: String): String = when (language) {
        Language.DE ->
            "WETTER-HINWEIS (nur für dich, im Gespräch NICHT erwähnen): " +
                "Der Ort „$place“ wurde nicht gefunden — keine Wetterdaten dafür. " +
                "Sag das ehrlich und biete den konfigurierten Ort an."
        Language.EN ->
            "WEATHER NOTE (for you only, do NOT mention it in the conversation): " +
                "The place “$place” could not be found — no weather data for it. " +
                "Say so honestly and offer the configured location instead."
        Language.ES ->
            "AVISO METEOROLÓGICO (solo para ti, NO lo menciones en la conversación): " +
                "No se encontró el lugar “$place” — no hay datos meteorológicos para él. " +
                "Dilo con honestidad y ofrece el lugar configurado."
        Language.FR ->
            "NOTE MÉTÉO (pour toi uniquement, NE la mentionne PAS dans la conversation) : " +
                "Le lieu “$place” est introuvable — aucune donnée météo pour lui. " +
                "Dis-le honnêtement et propose le lieu configuré."
        Language.IT ->
            "NOTA METEO (solo per te, NON menzionarla nella conversazione): " +
                "Il luogo “$place” non è stato trovato — nessun dato meteo disponibile. " +
                "Dillo onestamente e proponi il luogo configurato."
    }
}
