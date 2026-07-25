package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language

/**
 * **Der RAHMEN des Wiki-Grounding-Blocks in allen 5 Sprachen (DE/EN/ES/FR/IT).**
 *
 * Schwester-Katalog zu [WeatherBlockTexts], herausgelöst aus [Fts5GroundingAdapter]
 * (Sprach-Naht-Scheibe 2026-07-25). Derselbe Befund, dieselbe Wirkung: der Block geht
 * WÖRTLICH in den Brain-Prompt — ein deutscher Kopf („HINTERGRUND …") plus eine
 * deutsche ANWEISUNG zieht die Antwort selbst bei englischer Persona zuverlässig
 * nach Deutsch. Diese Texte werden nie angezeigt und nie gesprochen; Zielleser ist
 * ein Sprachmodell, deshalb zählt Klarheit vor Wohlklang.
 *
 * **Rahmen vs. zitierter Inhalt — die Schnittkante dieser Scheibe:** die Quelle
 * hinter dem Adapter ist die LOKALE DEUTSCHE Wikipedia. Der ZITIERTE Inhalt
 * (Artikel-Titel + Passage, die `• Titel: Text`-Zeilen) bleibt deshalb IMMER
 * unangetastet deutsch — Quellen/Nutzerdaten werden nie übersetzt. Nur der RAHMEN
 * (Kopfzeile, ANWEISUNG, ZAHLEN-VERTRAG) folgt der Turn-Sprache. Damit das Modell
 * die Mischung versteht, tragen die NICHT-deutschen Fassungen zusätzlich den kurzen
 * [foreignSourceNote] — genau denselben Schnitt macht der Wetter-Block.
 *
 * **Ein `when`-Block pro Baustein, KEIN `else`** (Muster [WeatherBlockTexts]): eine
 * sechste [Language] bricht hier den Build, statt still auf Deutsch zu rutschen.
 *
 * **DE ist byte-eingefroren:** die deutschen Zweige sind ZEICHENGLEICH zum Stand vor
 * dieser Scheibe (Pin-Tests in `Fts5GroundingAdapterTest` hängen direkt an ihnen —
 * Basis-ANWEISUNG und ZAHLEN-VERTRAG).
 *
 * **Anführungszeichen-Hygiene:** außerhalb von DE stehen „…“ bzw. «…» NICHT zur
 * Verfügung — «» ist der Verbatim-Vertrags-Marker
 * ([de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers] strippt ihn aus
 * jedem Delta), also nutzen die anderen Sprachen “…”. Im ZAHLEN-VERTRAG stehen in
 * KEINER Sprache Anführungszeichen: das 4B kopierte live jedes gezeigte
 * Markierungs-Muster in die Antwort (Befund 2026-07-02), darum ist der Vertragsteil
 * bewusst zeichen-nackt.
 *
 * EN/ES/FR/IT sind idiomatisch formuliert, aber (wie [WeatherCodeTexts] ES/FR/IT)
 * noch nicht von Muttersprachlern gegengelesen.
 */
internal object WikiBlockTexts {

    /**
     * Kopfzeile des Hintergrund-Blocks (inkl. abschließendem Zeilenumbruch).
     *
     * **EINE Wahrheit, mehrere Ränder:** der Satz ist ZEICHENGLEICH mit dem des
     * Wetter-Blocks — beide sagen dem Modell dasselbe („das hier ist Hintergrund,
     * erwähne ihn nicht"). Statt ihn ein zweites Mal in fünf Sprachen zu pflegen
     * (zwei Fassungen driften garantiert auseinander), delegiert dieser Katalog an
     * [WeatherBlockTexts.head]. Der Name ist historisch (dort zuerst gebraucht),
     * die Bedeutung ist grounding-übergreifend.
     */
    fun head(language: Language): String = WeatherBlockTexts.head(language)

    /**
     * Die Haupt-ANWEISUNG unter den Passagen. Bewusst kurz — jede Zusatzregel kostet
     * bei einem 4B Befolgung.
     */
    fun instruction(language: Language): String = when (language) {
        Language.DE ->
            "ANWEISUNG: Nutze diese Fakten und antworte knapp im eigenen warmen Stil — " +
                "zitiere nichts wörtlich und erwähne nie „den Text“, „den Artikel“ oder „Wikipedia“."
        Language.EN ->
            "INSTRUCTION: Use these facts and answer briefly in your own warm style — " +
                "quote nothing verbatim and never mention “the text”, “the article” or “Wikipedia”."
        Language.ES ->
            "INSTRUCCIÓN: Usa estos datos y responde brevemente con tu propio estilo cálido — " +
                "no cites nada literalmente y nunca menciones “el texto”, “el artículo” ni “Wikipedia”."
        Language.FR ->
            "INSTRUCTION : Utilise ces faits et réponds brièvement dans ton style chaleureux — " +
                "ne cite rien mot pour mot et ne mentionne jamais “le texte”, “l'article” ou “Wikipédia”."
        Language.IT ->
            "ISTRUZIONE: Usa questi fatti e rispondi in breve con il tuo stile caloroso — " +
                "non citare nulla alla lettera e non menzionare mai “il testo”, “l'articolo” o “Wikipedia”."
    }

    /**
     * **Sprach-Naht zwischen Rahmen und zitiertem Inhalt** (führendes Leerzeichen
     * gehört dazu, hängt direkt an [instruction]): der Wiki-Index IST deutsch, die
     * Passage oben bleibt deutsch. Ohne diesen Satz sieht ein EN-Turn einen deutschen
     * Fakten-Block und antwortet mit hoher Wahrscheinlichkeit deutsch — genau der
     * Effekt, den diese Scheibe abstellt.
     *
     * **DE liefert bewusst `""`** — nicht weil „nichts zu sagen wäre", sondern weil
     * der deutsche Block byte-eingefroren ist UND die Aussage für einen DE-Turn
     * ohnehin leer wäre (Passage und Antwort sind dieselbe Sprache).
     */
    fun foreignSourceNote(language: Language): String = when (language) {
        Language.DE -> ""
        Language.EN -> " The facts above are in German; answer in English anyway."
        Language.ES -> " Los datos de arriba están en alemán; responde igualmente en español."
        Language.FR -> " Les faits ci-dessus sont en allemand ; réponds quand même en français."
        Language.IT -> " I fatti qui sopra sono in tedesco; rispondi comunque in italiano."
    }

    /**
     * **WikiNumberContract**-Instruktion (nur wenn das Flag AN ist UND die Bridge
     * Zahl-Spans markiert hat). [marked] kommt FERTIG in «…» herein — dieser Katalog
     * kennt den Vertrag nicht und baut nur die Schablone drumherum.
     *
     * Die Fakt-DIREKT-Formel (Wert als direkte Eigenschaft im ERSTEN Satz, kein
     * inhaltsleerer Vorsatz, nicht relativieren) und das Beispiel ohne
     * Anführungszeichen sind in JEDER Sprache erhalten — sie sind der eigentliche
     * Wirkstoff (Live-Befund 2026-07-02), nicht Zierrat.
     */
    fun numberContract(language: Language, marked: String): String = when (language) {
        Language.DE ->
            "ZAHLEN-VERTRAG: Der exakte Wert zur Frage ist $marked. " +
                "Nenne genau diesen Wert — gleiche Ziffern, gleiche Einheit — als direkte Eigenschaft im ERSTEN Satz, " +
                "zum Beispiel: Der Eiffelturm ist 330 Meter hoch — ganz schön was. " +
                "Nicht relativieren (reicht über etwas hinaus) und kein inhaltsleerer Vorsatz. " +
                "Passt kein Wert zur Frage, erfinde KEINEN — sag dann ehrlich, dass du die genaue Zahl grad nicht parat hast."
        Language.EN ->
            "NUMBER CONTRACT: The exact value for the question is $marked. " +
                "State exactly this value — same digits, same unit — as a direct property in the FIRST sentence, " +
                "for example: The Eiffel Tower is 330 metres tall — quite something. " +
                "Do not hedge (goes beyond something) and no empty run-up. " +
                "If no value fits the question, invent NONE — then say honestly that you do not have the exact number at hand."
        Language.ES ->
            "CONTRATO DE CIFRAS: El valor exacto para la pregunta es $marked. " +
                "Di exactamente ese valor — mismas cifras, misma unidad — como propiedad directa en la PRIMERA frase, " +
                "por ejemplo: La Torre Eiffel mide 330 metros de alto — nada mal. " +
                "Sin relativizar (supera algo) y sin preámbulo vacío. " +
                "Si ningún valor encaja con la pregunta, no inventes NINGUNO — di con honestidad que ahora mismo no tienes la cifra exacta."
        Language.FR ->
            "CONTRAT DES CHIFFRES : La valeur exacte pour la question est $marked. " +
                "Donne exactement cette valeur — mêmes chiffres, même unité — comme propriété directe dans la PREMIÈRE phrase, " +
                "par exemple : La tour Eiffel fait 330 mètres de haut — pas mal du tout. " +
                "Ne relativise pas (dépasse quelque chose) et pas de préambule vide. " +
                "Si aucune valeur ne correspond à la question, n'en invente AUCUNE — dis alors honnêtement que tu n'as pas le chiffre exact sous la main."
        Language.IT ->
            "CONTRATTO DELLE CIFRE: Il valore esatto per la domanda è $marked. " +
                "Indica esattamente questo valore — stesse cifre, stessa unità — come proprietà diretta nella PRIMA frase, " +
                "per esempio: La Torre Eiffel è alta 330 metri — niente male. " +
                "Non relativizzare (supera qualcosa) e nessuna premessa vuota. " +
                "Se nessun valore corrisponde alla domanda, non inventarne NESSUNO — di' allora onestamente che non hai il numero esatto a portata di mano."
    }
}
