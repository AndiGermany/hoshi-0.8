package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Spanisches Sprachpaket — echtes Spanisch, intentPatterns dokumentarisch** (Andi-
 * Auftrag 2026-07-20, Übersetzer-Pod „Español").
 *
 * [PACK.cloudConsentAsk]/[PACK.cloudConsentAskExplicit]/[PACK.cloudConsentAccept]/
 * [PACK.cloudConsentDecline]/[PACK.abstainLookupOffer] sind mit Ohr ins Spanische
 * übersetzt (Wärme-Anspruch wie DE/EN, tú-Form, kurz und TTS-gerecht — keine
 * wörtlich-roboterhaften 1:1-Übersetzungen). [PACK.intentPatterns] dokumentiert
 * die natürlichsten spanischen Signalwörter (z.B. lookupVerbs: "busca"/"mira"/
 * "comprueba"; lookupScope: "en línea"/"internet"; consentWords: "sí"/"vale"/"claro")
 * — konservativ, false-positive-avers. Die Recognizer bleiben absichtlich DE+EN
 * geteilt (s. LanguagePack KDoc) — Ausbau nach Build-Week (PREP-multilingual.md).
 *
 * [PACK.promptLanguageInstruction] + [PACK.smartHomeNotice] sind bereits einfaches,
 * unzweideutiges Standardspanisch (geringes Risiko, im Gegensatz zu den nuancierten
 * Konversations-Pools oben).
 */
object LangEs {

    /** `by lazy` — s. [LangDe.PACK]: [PACK] referenziert weiter unten deklarierte Felder. */
    val PACK: LanguagePack by lazy {
        LanguagePack(
        language = Language.ES,
        cloudConsentAsk = listOf(
            "Mmm, tendría que mirar eso en internet rápido — ¿vale?",
            "No estoy muy seguro de eso. ¿Me dejas que lo busque?",
            "Eso no lo sé bien. ¿Miro en línea?",
            "Deja que lo compruebe rápido en internet — ¿te va?",
            "Tengo que preguntar a internet para eso. ¿Miro?",
            "En internet probablemente lo sepa. ¿Quieres que busque?",
        ),
        cloudConsentAskExplicit = listOf(
            "Claro, voy rápido a internet — ¿me das el visto bueno?",
            "Voy a mirar rápido afuera, ¿vale?",
            "Con gusto te lo traigo de ahí fuera — solo dime que sí y me pongo.",
        ),
        cloudConsentAccept = listOf(
            "Claro, un momento — déjame preguntar.",
            "Voy, mirando rápido…",
            "Voy. Un segundo.",
            "Vale, un momento.",
        ),
        cloudConsentDecline = listOf(
            "Vale, te digo lo que sé yo.",
            "Bien, me quedo con lo que tengo.",
            "Vale — así es como lo veo.",
            "Bien, pues con lo que tengo.",
        ),
        abstainLookupOffer = listOf(
            " — ¿Quieres que lo mire en internet?",
            " — ¿Miro eso para ti en línea?",
            " — ¿Te lo compruebo en internet?",
        ),
        // ── Ehrlichkeit: was Hoshi sagt, wenn er NICHT weiterweiß. Warm und
        //    zugewandt (tú-Form), nie wie eine Fehlermeldung.
        honestyOnlineRequestRefusals = listOf(
            "A la red abierta no salgo, y es a propósito — me quedo aquí contigo. Pero en lo que sé yo miro con mucho gusto: ¿qué buscas exactamente?",
            "Salir ahí fuera a internet no me apetece nada — para eso tengo aquí todo un archivo de conocimiento. ¿Miro ahí por ti?",
            "Por internet no ando, y es adrede. Lo que sí tengo es mi propio conocimiento — dime qué buscas y lo miro.",
            "Internet lo dejo cerrado a propósito — pero tengo un montón guardado. Déjame buscarlo ahí dentro para ti, ¿vale?",
            "Afuera no salgo, es a propósito. Pero en lo que sé yo seguro que encuentro algo para ti — ¿qué necesitas?",
        ),
        honestyRecipeRefusals = listOf(
            "Cocinar no es lo mío — ahí te llevaría por mal camino.",
            "Con una receta estaría adivinando, y eso no te serviría de nada.",
        ),
        honestyExistenceRefusals = listOf(
            "Espera — no estoy seguro de que eso exista de verdad. Prefiero no inventarte nada.",
            "Buena pregunta — en algo así no me gusta fiarme de mi instinto. Mejor te lo digo con franqueza: no lo sé.",
            "¿Sinceramente? Ahí me pierdo. Eso preferiría mirarlo antes que adivinarlo.",
        ),
        honestyNamedEntityRefusals = listOf(
            "Mmm, ese nombre ahora mismo no me suena. Suena a alguien de un ambiente concreto — ¿quién es exactamente?",
            "Cuéntame más — ¿música, cine, historia, deporte? Con ese nombre estoy completamente a oscuras.",
            "Ese nombre no lo conozco — ¿me cuentas algo sobre él?",
            "Sinceramente, sobre eso no tengo nada — ¿de qué conoces tú ese nombre?",
        ),
        honestyBridgeDownRefusals = listOf(
            "Ahora mismo no llego a mi archivo de conocimiento — con seguridad te lo puedo decir dentro de un momento. ¿Me lo vuelves a preguntar enseguida?",
            "Hm, mi libro de consulta no está accesible en este momento. No quiero adivinar — pregúntamelo otra vez en un momento y lo miro como es debido.",
            "Aquí me he quedado colgado — mi archivo de conocimiento no responde. Dame un momento y te lo digo con sinceridad.",
        ),
        // ── Never-Silent-Ränder + Fastpath-Quittungen ────────────────────────
        warmFallback = "Te he oído, pero aquí se me ha atascado algo un momento. ¿Me lo repites enseguida?",
        audioCapTooLong = "Eso ha sido demasiado largo de una vez — dímelo un poco más corto y te sigo sin fallar.",
        audioNoEndSignal = "La grabación se alargó demasiado sin señal de fin — ¿lo intentas otra vez algo más corto?",
        admissionBusy = "Ahora mismo estoy con otra petición — dame un momentito y pregúntamelo enseguida otra vez.",
        dailyNoteRecorded = "Anotado: hoy un $SCORE_PLACEHOLDER. ¡Gracias!",
        dailyNoteUpdated = "Actualizado: hoy un $SCORE_PLACEHOLDER. ¡Gracias!",
        workshopNoteRecorded = "Anotado para el taller. ¡Gracias!",
        probeReceipt = "Te oigo alto y claro — oídos, cable y voz están en pie.",
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf(
                "busca", "busco", "mira", "miro", "comprueba", "compruebo",
                "echa", "investiga", "investigo", "chequea", "revisa",
            ),
            lookupScope = listOf("en línea", "internet", "en internet", "online", "web", "red"),
            consentWords = listOf(
                "sí", "sí claro", "claro", "vale", "dale", "okay", "está bien", "bueno",
                "adelante", "anda", "venga", "vamos",
            ),
            researchMarkers = listOf(
                "investiga(r)?", "busca en profundidad", "busca en internet", "busca en línea",
            ),
            status = "dokumentiert — Recognizer bewusst DE+EN, Ausbau nach Build-Week (s. PREP-multilingual.md)",
        ),
        promptLanguageInstruction = "Responde SIEMPRE en español.",
        // Gedreht 2026-07-25: die Bestätigungen sprechen jetzt Spanisch — begrenzt
        // ist nur noch das VERSTEHEN der Befehle (Recognizer bleibt DE+EN).
        smartHomeNotice = "Las respuestas de domótica ya hablan español — las órdenes se siguen entendiendo solo en alemán o inglés.",
        sayVoiceHint = "Mónica",
        // Kein Piper-Modell fuer Spanisch vorhanden (nur DE+EN sind lizenzgeprueft
        // gepinnt) — bewusst null statt geraten, s. LanguagePack.piperVoiceHint-KDoc.
        piperVoiceHint = null,
        smartHomeAcks = SMART_HOME_ACKS,
        haExecutor = HA_EXECUTOR,
        capabilityDeny = CAPABILITY_DENY,
        )
    }

    /**
     * **Smart-Home-Bestätigungen auf Spanisch** (Andi 2026-07-25, „multilingual von
     * A-Z"). Fürs Ohr übersetzt, tú-Form, kurz.
     *
     * Übersetzer-Notiz: `{room}` trägt einen DEUTSCHEN Eigennamen aus Home Assistant
     * („Wohnzimmer") — deshalb sind alle Sätze bewusst so gebaut, dass sie mit „en
     * {room}" o.ä. auskommen und NICHT vom grammatischen Geschlecht des Raumnamens
     * abhängen. „El {room} está encendido" wäre ein Genus-Glücksspiel.
     */
    val SMART_HOME_ACKS = SmartHomeAckPack(
        lightOnRoom = listOf(
            "Luz encendida en {room}.",
            "Ya hay luz en {room}.",
            "Voy — luz encendida en {room}.",
        ),
        lightOffRoom = listOf(
            "Luz apagada en {room}.",
            "{room} a oscuras.",
            "Voy — luz apagada en {room}.",
        ),
        lightDimRoom = listOf(
            "{room} al {value} por ciento.",
            "Voy — {room} al {value} por ciento.",
        ),
        lightDimNoRoom = listOf(
            "Al {value} por ciento.",
            "Voy — {value} por ciento.",
        ),
        scene = listOf(
            "Voy.",
            "Ya está puesto.",
        ),
        coverOpen = listOf(
            "Está abierta.",
            "Voy — está abierta.",
        ),
        coverClose = listOf(
            "Está cerrada.",
            "Voy — está cerrada.",
        ),
        climateRoom = listOf(
            "{room} a {value} grados.",
            "Voy — {room} a {value} grados.",
        ),
        unknown = listOf(
            "Voy.",
            "Hecho.",
            "Claro.",
        ),
        lightOffNoEffectRoom = listOf(
            "En {room} ya estaba oscuro.",
            "En {room} ya estaba todo apagado.",
            "La luz de {room} ya estaba apagada.",
        ),
        lightOffNoEffectNoRoom = listOf(
            "Ya estaba apagada.",
            "La luz ya estaba apagada.",
            "Ahí ya estaba todo apagado.",
        ),
        lightOnNoEffectRoom = listOf(
            "En {room} ya había luz.",
            "En {room} ya estaba encendido.",
            "La luz de {room} ya estaba encendida.",
        ),
        lightOnNoEffectNoRoom = listOf(
            "Ya estaba encendida.",
            "Ya había luz.",
            "Ahí ya estaba la luz encendida.",
        ),
        lightDimNoEffectRoom = listOf(
            "{room} ya está más o menos al {value} por ciento.",
            "En {room} ya hay más o menos esa luz — {value} por ciento.",
            "Ya estaba sobre el {value} por ciento en {room}.",
        ),
        lightDimNoEffectNoRoom = listOf(
            "Ya está más o menos al {value} por ciento.",
            "Ya estaba sobre el {value} por ciento.",
        ),
        coverOpenNoEffect = listOf(
            "Ya estaba abierta.",
            "Eso ya estaba abierto.",
        ),
        coverCloseNoEffect = listOf(
            "Ya estaba cerrada.",
            "Eso ya estaba cerrado.",
        ),
        climateNoEffectRoom = listOf(
            "{room} ya está a {value} grados.",
            "En {room} ya estaba puesto a {value} grados.",
        ),
        climateNoEffectNoRoom = listOf(
            "Ya estaba a {value} grados.",
            "Ya estaba puesto a {value} grados.",
        ),
        genericNoEffect = listOf(
            "Ahí ya estaba todo así.",
            "Eso ya estaba así.",
            "No ha cambiado nada — ya estaba así.",
        ),
        lightOnPartialOfflineOne = listOf(
            "Hay luz en {room} — una lámpara no contesta ahora mismo, el resto está encendido.",
            "Hecho, el resto en {room} está encendido. A una no llego ahora mismo.",
            "{applied} están encendidas en {room}, una está callada ahora.",
        ),
        lightOnPartialOfflineMany = listOf(
            "Hay luz en {room} — {offline} lámparas no contestan ahora mismo, el resto está encendido.",
            "El resto en {room} está encendido. A {offline} no llego ahora mismo.",
            "{applied} están encendidas en {room}, {offline} están calladas ahora.",
        ),
        lightOffPartialOfflineOne = listOf(
            "En {room} está oscuro — una lámpara no contesta ahora mismo, el resto está apagado.",
            "Apagado en {room}, salvo una que no contesta ahora mismo.",
            "{applied} están apagadas en {room}, una está callada ahora.",
        ),
        lightOffPartialOfflineMany = listOf(
            "En {room} está oscuro — {offline} lámparas no contestan ahora mismo, el resto está apagado.",
            "Apagado en {room}, salvo {offline} que no contestan ahora mismo.",
            "{applied} están apagadas en {room}, {offline} están calladas ahora.",
        ),
        partialOfflineNoRoom = listOf(
            "Unas cuantas lámparas no contestan ahora mismo, el resto ha reaccionado.",
            "Hecho — una parte no contesta en este momento.",
        ),
        unsupportedCover = listOf(
            "Una persiana así no la encuentro en tu casa.",
            "Aquí no tengo ninguna persiana que pueda mover.",
            "Ahí no hay ninguna persiana que yo pueda accionar.",
        ),
        unsupportedClimate = listOf(
            "No he encontrado ninguna calefacción que pueda regular para eso.",
            "Ahí no hay calefacción que yo pueda regular.",
            "Un termostato así no lo encuentro en tu casa.",
        ),
        unsupportedScene = listOf(
            "Esa escena no la conozco en tu instalación.",
            "Una escena así aquí no la tengo.",
            "Ese ambiente no lo encuentro en tu casa.",
        ),
        unsupportedGeneric = listOf(
            "Eso ahora mismo no lo puedo accionar en tu casa — un aparato así no lo conozco.",
            "Algo así no lo he encontrado en tu casa.",
            "Ese aparato aquí no lo conozco en absoluto.",
        ),
        lightOnNoRoom = listOf("Luz encendida."),
        lightOffNoRoom = listOf("Luz apagada."),
        lightDimNoValue = listOf("Ya está atenuada."),
        climateValueNoRoom = listOf("A {value} grados."),
        climateNoValue = listOf("Ya está puesto."),
        lightColorNamed = listOf("El color es {color}."),
        lightColorUnnamed = listOf("Color cambiado."),
    )

    /** **HA-Executor-Quittungen auf Spanisch.** Die Honesty-Abstufung „enviado" ≠ „encendida" ≠ „ya estaba" bleibt hörbar. */
    val HA_EXECUTOR = HaExecutorPack(
        noToken = "Con sinceridad: ahora mismo no tengo configurado un token de HA, así que no he accionado nada.",
        noTokenTemperature =
            "Con sinceridad: ahora mismo no tengo configurado un token de HA, así que no llego a la temperatura.",
        noThermostatInArea = "En {room} no conozco ningún termostato.",
        lightOffArea = "La luz en {room} está apagada.",
        lightSomeStillOn = "Unas cuantas lámparas en {room} siguen encendidas — puede que no hayan reaccionado.",
        offlineHintCount = " — {count} lámparas no están accesibles ahora mismo (quizá apagadas desde el interruptor).",
        offlineHintVague = " — quizá las lámparas estén desconectadas.",
        noLightsInArea = "En {room} no he encontrado ninguna lámpara.",
        lightOnArea = "La luz en {room} está encendida.",
        lightAlreadyOnArea = "En {room} la luz ya está encendida.",
        lightNothingNewOn = "En {room} ya había luz, pero no se ha encendido nada nuevo",
        lightNoneWentOn = "Lo he enviado a Home Assistant, pero en {room} no se ha encendido ninguna luz",
        climateSetArea = "Calefacción en {room} a {value} grados.",
        climateNotYet = "Lo he enviado, la calefacción todavía no ha reaccionado.",
        sentToArea = "Hecho — lo he enviado a los aparatos de {room}.",
        sentToHome = "Hecho — lo he enviado a Home Assistant.",
        failed = "No ha funcionado — Home Assistant no responde ahora mismo.",
        noValue = "Para eso ahora mismo no tengo ningún valor.",
        temperatureInArea = "En {room} hay ahora mismo {value} grados.",
        temperatureHouseAverage = "En casa hay ahora mismo {value} grados de media.",
        temperatureUnavailable = "Ahora mismo no llego a la temperatura — inténtalo otra vez enseguida.",
        decimalSeparator = ",",
    )

    /** **Tat-Verweigerung auf Spanisch** — Haltung, kein Fehler. */
    val CAPABILITY_DENY = CapabilityDenyPack(
        refusals = listOf(
            "Eso mejor no lo hago ahora — no tengo permiso para ello.",
            "Ahí me contengo: eso no lo acciono así sin más.",
            "Mejor no — eso lo dejo a propósito mientras no esté autorizado.",
            "Eso no lo toco. Si de verdad lo quieres, primero hay que habilitarlo.",
        ),
        invalid = "Dominio o servicio no válido",
    )
}
