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

    // ── Auftrag 2b (Andi-Livetest 2026-08-21): die JETZT-Zeile ────────────────
    //
    // Befund wörtlich: „Ich habe gefragt, wie grad das Wetter ist und Hoshi sagt
    // 15-irgendwas Grad und Regen. Ich will dann die aktuelle Temperatur, und es
    // regnet nicht — hat es aber heute."
    //
    // Ursache war KEIN Modell-Fehler: der Block enthielt schlicht nie einen
    // JETZT-Wert. `current.temperature_2m`/`weathercode` wurden von Open-Meteo
    // abgeholt (für die FE-Kachel) und im Grounding-Pfad komplett verworfen — das
    // Brain hatte nur die TAGES-Spanne („11 bis 19 Grad") und die TAGES-
    // Niederschlagssumme („etwa 3 mm") und hat daraus brav „15-irgendwas Grad und
    // Regen" gemacht. Beide Aussagen waren für den AUGENBLICK falsch, obwohl der
    // Block stimmte. Ein Prompt kann diese Lücke nicht schließen — der Wert fehlte.
    //
    // Diese Bausteine liefern ihn nach; [tenseInstruction] trennt die Zeitformen.

    /**
     * Die JETZT-Zeile (inkl. abschließendem Zeilenumbruch) — steht bei
     * JETZT-Fragen VOR den Tages-Zeilen. [value] kommt fertig zusammengesetzt
     * herein (Grad · Lage · Niederschlag-jetzt · Stand-Marker), weil je nach
     * Datenlage einzelne Teile fehlen dürfen: Open-Meteo liefert `current` nicht
     * garantiert, und ein fehlendes Feld wird WEGGELASSEN statt geraten.
     *
     * Der Tagesbezug ist bewusst GROSSGESCHRIEBEN („JETZT"/„RIGHT NOW"): der
     * Block wird von einem 4B gelesen, und die Unterscheidung Augenblick↔Tag ist
     * genau die, die im Livetest gekippt ist.
     */
    fun nowLine(language: Language, label: String, value: String): String = when (language) {
        Language.DE -> "• Wetter $label JETZT: $value.\n"
        Language.EN -> "• Weather $label RIGHT NOW: $value.\n"
        Language.ES -> "• Tiempo $label AHORA MISMO: $value.\n"
        Language.FR -> "• Météo $label MAINTENANT : $value.\n"
        Language.IT -> "• Meteo $label ADESSO: $value.\n"
    }

    /** Temperatur-Fragment der JETZT-Zeile („14 Grad"). */
    fun nowDegrees(language: Language, temp: String): String = when (language) {
        Language.DE -> "$temp Grad"
        Language.EN -> "$temp degrees"
        Language.ES -> "$temp grados"
        Language.FR -> "$temp degrés"
        Language.IT -> "$temp gradi"
    }

    /**
     * Niederschlag-Fragment der JETZT-Zeile — die Kern-Korrektur aus Andis
     * Livetest: `current.precipitation` ist der EINZIGE Wert, der etwas über den
     * Augenblick sagt. [measurable] `false` heißt AUSDRÜCKLICH „es regnet gerade
     * nicht" (nicht bloß „kein Wert") — der Satz muss das Brain aktiv davon
     * abhalten, die Tagessumme als Gegenwart zu lesen.
     */
    fun nowPrecipitation(language: Language, mm: Int, measurable: Boolean): String = when (language) {
        Language.DE -> if (measurable) "gerade etwa $mm mm Niederschlag" else "gerade KEIN Niederschlag"
        Language.EN -> if (measurable) "about $mm mm of precipitation right now" else "NO precipitation right now"
        Language.ES -> if (measurable) "ahora mismo unos $mm mm de precipitación" else "AHORA MISMO sin precipitación"
        Language.FR -> if (measurable) "environ $mm mm de précipitations en ce moment" else "AUCUNE précipitation en ce moment"
        Language.IT -> if (measurable) "adesso circa $mm mm di precipitazioni" else "adesso NESSUNA precipitazione"
    }

    /**
     * **Frische-/Herkunfts-Marker der JETZT-Zeile** (F2-Geist, Prompt-Block-
     * Fassung). Mirror ist bewusst [NachgeschlagenBlockTexts] („Stand
     * $dateLabel"), nicht die gesprochene `freshnessMarker`-Staffelung aus
     * `HaExecutorPack`: dort geht die Phrase direkt ins Ohr und muss deshalb
     * gestaffelt werden („gerade eben"/„vor N Minuten"), hier liest ein LLM einen
     * Hintergrund-Block und braucht den ROHEN Stand, um selbst zu entscheiden, ob
     * er ihn erwähnt. [clock] ist die lokale Beobachtungszeit („12:00").
     *
     * Ehrlichkeits-Grund: eine Open-Meteo-`current`-Ablesung kann bis zu ~15 min
     * alt sein. Ohne Stand-Marker würde ein „gerade" im Antwortsatz eine Frische
     * behaupten, die die Zahl nicht hat.
     */
    fun observedAt(language: Language, clock: String): String = when (language) {
        Language.DE -> "Stand $clock Uhr"
        Language.EN -> "as of $clock"
        Language.ES -> "a fecha de las $clock"
        Language.FR -> "relevé de $clock"
        Language.IT -> "aggiornato alle $clock"
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
     * **ZEITFORM-Regel** (Auftrag 2b) — der eigentliche Ehrlichkeits-Baustein.
     * Trennt die drei Zeitebenen, die im Livetest ineinandergerutscht sind:
     * JETZT (der `current`-Node), BEREITS GEFALLEN (die schon vergangenen Stunden
     * des Tages) und NOCH ERWARTET (die restlichen Stunden). Die Tages-Summe
     * allein erlaubt KEINE Zeitform — sie sagt nicht, ob der Regen morgens fiel
     * oder abends kommt; genau deshalb liefert [todayRainFallen]/[todayRainAhead]
     * die Aufteilung als DATEN, statt sie das Brain raten zu lassen.
     *
     * Nur angehängt, wenn es eine JETZT-Zeile GIBT — ohne sie wäre die Regel eine
     * Anweisung auf Werte, die nicht im Block stehen.
     */
    fun tenseInstruction(language: Language): String = when (language) {
        Language.DE ->
            " ZEITFORM: Die JETZT-Zeile gilt für den Augenblick, die Tages-Zeile für den ganzen Tag. " +
                "Sag „es regnet“ NUR, wenn die JETZT-Zeile Niederschlag nennt. " +
                "Niederschlag, der laut Block schon gefallen ist, heißt „heute hat es geregnet“; " +
                "noch erwarteter heißt „es soll noch regnen“ — nie beides verwechseln."
        Language.EN ->
            " TENSE: The RIGHT NOW line is about this moment, the day line about the whole day. " +
                "Say “it is raining” ONLY if the RIGHT NOW line states precipitation. " +
                "Precipitation the block says already fell is “it rained today”; " +
                "precipitation still expected is “it is going to rain” — never mix the two up."
        Language.ES ->
            " TIEMPO VERBAL: La línea AHORA MISMO vale para este instante; la del día, para todo el día. " +
                "Di “está lloviendo” SOLO si la línea AHORA MISMO indica precipitación. " +
                "La precipitación que ya cayó según el bloque es “hoy ha llovido”; " +
                "la que aún se espera es “va a llover” — nunca los confundas."
        Language.FR ->
            " TEMPS VERBAL : La ligne MAINTENANT vaut pour l'instant présent, la ligne du jour pour toute la journée. " +
                "Dis “il pleut” UNIQUEMENT si la ligne MAINTENANT indique des précipitations. " +
                "Les précipitations déjà tombées selon le bloc, c'est “il a plu aujourd'hui” ; " +
                "celles encore attendues, c'est “il va pleuvoir” — ne confonds jamais les deux."
        Language.IT ->
            " TEMPO VERBALE: La riga ADESSO vale per questo momento, la riga del giorno per l'intera giornata. " +
                "Di' “sta piovendo” SOLO se la riga ADESSO indica precipitazioni. " +
                "Le precipitazioni già cadute secondo il blocco sono “oggi ha piovuto”; " +
                "quelle ancora attese sono “pioverà” — non confonderle mai."
    }

    /**
     * Fragment der HEUTE-Zeile: wie viel Niederschlag BIS JETZT schon gefallen
     * ist (Summe der bereits vergangenen Stunden). Schwelle 0,5 mm wie bei
     * [precipitation] — eine Wahrheit, ein Grenzwert.
     */
    fun todayRainFallen(language: Language, mm: Int, measurable: Boolean): String = when (language) {
        Language.DE -> if (measurable) "bis jetzt etwa $mm mm gefallen" else "bis jetzt nichts gefallen"
        Language.EN -> if (measurable) "about $mm mm fell so far" else "nothing fell so far"
        Language.ES -> if (measurable) "hasta ahora han caído unos $mm mm" else "hasta ahora no ha caído nada"
        Language.FR -> if (measurable) "environ $mm mm sont déjà tombés" else "rien n'est encore tombé"
        Language.IT -> if (measurable) "finora sono caduti circa $mm mm" else "finora non è caduto nulla"
    }

    /** Gegenstück zu [todayRainFallen]: was für den REST des Tages erwartet wird. */
    fun todayRainAhead(language: Language, mm: Int, measurable: Boolean): String = when (language) {
        Language.DE -> if (measurable) "noch etwa $mm mm erwartet" else "für den Rest des Tages nichts mehr erwartet"
        Language.EN -> if (measurable) "about $mm mm still expected" else "nothing more expected for the rest of the day"
        Language.ES -> if (measurable) "aún se esperan unos $mm mm" else "no se espera nada más para el resto del día"
        Language.FR -> if (measurable) "environ $mm mm encore attendus" else "plus rien d'attendu pour le reste de la journée"
        Language.IT -> if (measurable) "attesi ancora circa $mm mm" else "per il resto della giornata non è atteso altro"
    }

    /**
     * Zusatz NUR bei „am Wochenende"-Fragen: Samstag und Sonntag stehen als zwei
     * Zeilen im Block, sollen aber als EIN Bild beantwortet werden („am
     * Wochenende wird's …") statt als Protokoll zweier Tage. Nennt ausdrücklich,
     * dass Unterschiede zwischen den beiden Tagen erwähnt gehören — sonst mittelt
     * ein 4B sie gern stillschweigend weg, und ein verregneter Sonntag
     * verschwindet hinter einem sonnigen Samstag.
     */
    fun weekendSuffix(language: Language): String = when (language) {
        Language.DE ->
            " Fasse Samstag und Sonntag zu EINEM Wochenend-Bild zusammen; " +
                "nenne den Unterschied zwischen beiden Tagen nur, wenn es einen deutlichen gibt."
        Language.EN ->
            " Summarise Saturday and Sunday into ONE weekend picture; " +
                "point out the difference between the two days only if there is a clear one."
        Language.ES ->
            " Resume el sábado y el domingo en UNA sola imagen del fin de semana; " +
                "menciona la diferencia entre ambos días solo si es clara."
        Language.FR ->
            " Résume samedi et dimanche en UNE seule image du week-end ; " +
                "ne signale la différence entre les deux jours que si elle est nette."
        Language.IT ->
            " Riassumi sabato e domenica in UN solo quadro del fine settimana; " +
                "segnala la differenza tra i due giorni solo se è netta."
    }

    /**
     * **Ehrliche Horizont-Grenze** — Vorbild `escalationUnavailable`
     * ([de.hoshi.core.pipeline.lang.LanguagePack]): EIN fester String je Sprache,
     * KEIN Pool (eine Grenz-/Ehrlichkeits-Kennzeichnung soll immer gleich
     * klingen), warm statt technisch, ohne Versprechen für später.
     *
     * Ausgelöst von [DayReferenceResolver.DayReference.beyondHorizon]: die Frage
     * nannte einen Tag jenseits der Sieben-Tage-Reichweite („nächsten Samstag",
     * „nächste Woche", „in zehn Tagen"). Der Block enthält dann bewusst KEINE
     * Wetterdaten — geraten wird nicht, und ein stiller Ausweich auf
     * „diesen Samstag" wäre exakt derselbe Fehler wie der stille Heimat-Fallback
     * bei einem unbekannten Ort ([placeNotFound]).
     */
    fun beyondHorizon(language: Language, days: Int): String = when (language) {
        Language.DE ->
            "WETTER-HINWEIS (nur für dich, im Gespräch NICHT erwähnen): " +
                "Der gefragte Tag liegt jenseits meiner Vorhersage — ich sehe nur $days Tage voraus. " +
                "Sag ehrlich, dass dein Ausblick so weit nicht reicht, und biete an, näher am Tag nochmal zu schauen. " +
                "Rate KEINE Werte."
        Language.EN ->
            "WEATHER NOTE (for you only, do NOT mention it in the conversation): " +
                "The day asked about is beyond my forecast — I only see $days days ahead. " +
                "Say honestly that your outlook does not reach that far, and offer to check again closer to the day. " +
                "Do NOT guess any values."
        Language.ES ->
            "AVISO METEOROLÓGICO (solo para ti, NO lo menciones en la conversación): " +
                "El día preguntado queda fuera de mi previsión — solo veo $days días por delante. " +
                "Di con honestidad que tu previsión no llega tan lejos y ofrece mirarlo de nuevo más cerca de ese día. " +
                "NO inventes valores."
        Language.FR ->
            "NOTE MÉTÉO (pour toi uniquement, NE la mentionne PAS dans la conversation) : " +
                "Le jour demandé dépasse ma prévision — je ne vois que $days jours à l'avance. " +
                "Dis honnêtement que ta prévision ne va pas si loin et propose de regarder à nouveau plus près du jour. " +
                "N'invente AUCUNE valeur."
        Language.IT ->
            "NOTA METEO (solo per te, NON menzionarla nella conversazione): " +
                "Il giorno richiesto è oltre la mia previsione — vedo solo $days giorni in avanti. " +
                "Di' onestamente che la tua previsione non arriva così lontano e offri di ricontrollare più vicino a quel giorno. " +
                "NON inventare valori."
    }

    /**
     * **JETZT gefragt, aber kein JETZT-Wert da** (Open-Meteo hat den
     * `current`-Node nicht geliefert oder er war unlesbar). Statt still die
     * Tagesspanne als Gegenwart auszugeben — das war der Livetest-Fehler — wird
     * der Ausweich SPRACHLICH gekennzeichnet: „für den Tag gilt…". Der Nutzer
     * hört dann eine Tages-Aussage und hält sie nicht für eine Messung.
     */
    fun nowUnavailable(language: Language): String = when (language) {
        Language.DE ->
            " ACHTUNG: Für den AUGENBLICK liegt kein Messwert vor — nur Tageswerte. " +
                "Sag keine aktuelle Temperatur und kein „es regnet gerade“, sondern kennzeichne es als Tagesbild " +
                "(„für heute gilt …“) und erwähne kurz, dass du den Moment gerade nicht siehst."
        Language.EN ->
            " CAUTION: There is no reading for this MOMENT — only day values. " +
                "Do not state a current temperature or “it is raining right now”; mark it as a day picture " +
                "(“for today …”) and briefly mention that you cannot see the moment itself."
        Language.ES ->
            " ATENCIÓN: No hay medición para este INSTANTE — solo valores del día. " +
                "No des una temperatura actual ni digas “está lloviendo ahora”; márcalo como imagen del día " +
                "(“para hoy …”) y menciona brevemente que ahora mismo no ves el momento."
        Language.FR ->
            " ATTENTION : Aucune mesure pour l'INSTANT présent — seulement des valeurs du jour. " +
                "N'annonce pas de température actuelle ni “il pleut en ce moment” ; présente-le comme une image de la journée " +
                "(“pour aujourd'hui …”) et mentionne brièvement que tu ne vois pas l'instant présent."
        Language.IT ->
            " ATTENZIONE: Non c'è una misura per QUESTO momento — solo valori del giorno. " +
                "Non dare una temperatura attuale né “sta piovendo adesso”; presentalo come quadro della giornata " +
                "(“per oggi …”) e accenna brevemente che il momento presente non lo vedi."
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
