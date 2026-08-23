package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Französisches Sprachpaket — mit Ohr übersetzt** (Andi-Auftrag 2026-07-20).
 * Alle Konversations-Pools sind echtes, warmes Französisch (Duz-Form „tu",
 * natürlicher Ton wie DE/EN, nicht wörtlich übersetzt) — gesprochen für TTS,
 * keine Roboter-Sätze.
 *
 * [PACK.intentPatterns] ist dokumentarisch (französische Signalwörter für
 * Lookup/Consent/Research notiert) — ein Folge-Pod liefert negativ-getestete
 * Recognizer-Muster, wenn Französisch im Recognizer aktiv wird (s. PREP-multilingual.md).
 * Diese Datei ist die EINZIGE, die dafür angefasst werden muss (Ein-Datei-Regel).
 *
 * [PACK.promptLanguageInstruction] + [PACK.smartHomeNotice] + [PACK.sayVoiceHint]
 * sind bereits ECHTES, einfaches Französisch.
 */
object LangFr {

    /** `by lazy` — s. [LangDe.PACK]: [PACK] referenziert weiter unten deklarierte Felder. */
    val PACK: LanguagePack by lazy {
        LanguagePack(
        language = Language.FR,
        cloudConsentAsk = listOf(
            "Hmm, ça, je devrais le vérifier en ligne, vite fait — d'accord?",
            "Là-dessus, je ne suis pas sûr. Je regarde?",
            "Ça, je ne sais vraiment pas. Je peux regarder en ligne?",
            "Laisse-moi vérifier ça en ligne, vite fait — ça te va?",
            "Pour ça, je devrais demander à internet. Je fais?",
            "En ligne, je le saurais sûrement. Tu veux que je regarde?",
        ),
        cloudConsentAskExplicit = listOf(
            "Bien sûr, je file en ligne pour ça — tu me dis oui?",
            "Je m'en occupe — je vais voir ça dehors, vite fait, ça marche?",
            "Ravi d'aller chercher ça dehors pour toi — dis-moi juste oui et je m'y mets.",
        ),
        cloudConsentAccept = listOf(
            "Bien sûr, une seconde — je cherche.",
            "Je m'en occupe, je regarde vite…",
            "J'y vais. Une seconde.",
            "D'accord, donne-moi un moment.",
        ),
        cloudConsentDecline = listOf(
            "D'accord, je te dis ce que je sais.",
            "Très bien, je reste sur ce que j'ai.",
            "D'accord — voilà ce que j'en pense.",
            "Bon, je travaille avec ce que j'ai alors.",
        ),
        abstainLookupOffer = listOf(
            " — Tu veux que je vérifie en ligne?",
            " — Je regarde en ligne pour toi?",
            " — Tu veux que je cherche en ligne?",
        ),
        // ── Ehrlichkeit: was Hoshi sagt, wenn er NICHT weiterweiß. Duz-Form,
        //    chaleureux — jamais un message d'erreur.
        honestyOnlineRequestRefusals = listOf(
            "Le grand internet, je n'y vais pas, et c'est voulu — je reste avec toi. Mais dans ce que je sais, je regarde volontiers: tu cherches quoi exactement?",
            "Sortir sur internet, ça ne me dit rien du tout — pour ça j'ai toute une réserve de savoir ici. Je regarde dedans pour toi?",
            "Je ne traîne pas en ligne, c'est exprès. Ce que j'ai, par contre, c'est mon propre savoir — dis-moi quoi, et je cherche.",
            "Internet, je le laisse fermé exprès — mais j'ai plein de choses enregistrées. Laisse-moi chercher là-dedans pour toi, d'accord?",
            "Dehors, je n'y vais pas, c'est voulu. Mais dans ce que je sais, je te trouverai volontiers quelque chose — il te faut quoi?",
        ),
        honestyRecipeRefusals = listOf(
            "La cuisine, ce n'est vraiment pas mon fort — là, je t'induirais en erreur.",
            "Pour une recette, je devinerais, et ça ne t'aiderait pas.",
        ),
        honestyExistenceRefusals = listOf(
            "Attends — je ne suis pas sûr que ça existe vraiment. Je préfère ne rien t'inventer.",
            "Bonne question — sur ce genre de chose, je n'aime pas me fier à mon instinct. Je préfère te le dire franchement: je ne sais pas.",
            "Honnêtement? Là, je cale. Ça, je préférerais le vérifier plutôt que de deviner.",
        ),
        honestyNamedEntityRefusals = listOf(
            "Hmm, ce nom ne me dit rien pour l'instant. On dirait quelqu'un d'un milieu bien précis — c'est qui exactement?",
            "Raconte-moi un peu — musique, cinéma, histoire, sport? Avec ce nom, je suis dans le noir complet.",
            "Ce nom, je ne le connais pas — tu veux m'en dire un mot?",
            "Franchement, là-dessus je n'ai rien — tu le connais d'où, ce nom?",
        ),
        honestyBridgeDownRefusals = listOf(
            "Je n'arrive pas à joindre ma réserve de savoir en ce moment — je pourrai te le dire sûrement dans un instant. Tu me redemandes tout à l'heure?",
            "Hm, mon ouvrage de référence n'est pas joignable pour l'instant. Je ne veux pas deviner — redemande-moi dans un moment et je regarde comme il faut.",
            "Là, je bloque — ma réserve de savoir ne répond pas. Laisse-moi un moment, et je pourrai te le dire honnêtement.",
        ),
        executionClaimAskBack =
            "Je n'ai pas bien compris ça comme une commande à exécuter — tu me le redis?",
        factCoverageOfflineDisclaimer = "Honnêtement, je n'ai pas de source pour ça — mais d'après ce que je sais: ",
        localLookupFoundPrefix = "J'ai regardé dans ce que j'ai ici — ",
        // ── Andi-Auftrag 2026-07-26 „les phrases de recherche en ligne sont
        //    mauvaises" — ces cinq champs tombaient jusqu'ici en anglais pour
        //    ES/FR/IT ; maintenant du vrai français, avec « chercher » plutôt
        //    qu'un vague « demander ». Complément « pas toujours la même
        //    réponse » : deflect + rahmung d'intro sont maintenant des pools de
        //    4 (AntiRepeatPicker) — chaque variante de deflect finit sur la
        //    question, chaque variante d'intro nomme en ligne/internet.
        factCoverageDeflect = listOf(
            "Franchement, ça, je ne le sais pas par cœur comme ça — je vais chercher, vite fait?",
            "Bonne question — je vais chercher?",
            "Là, je ne l'ai pas en tête — je regarde vite fait?",
            "Ça, je ne le sais pas par cœur — tu veux que j'aille chercher?",
        ),
        escalationAnswerFrame = listOf(
            "Alors, j'ai vite cherché en ligne — ",
            "J'ai vite regardé en ligne — ",
            "Petit tour sur internet — ",
            "J'ai jeté un œil en ligne — ",
        ),
        escalationSourceTemplate = "Source : {source}.",
        escalationUnavailable =
            "J'allais chercher ça en ligne, mais là je n'arrive pas à me connecter — on réessaie plus tard.",
        escalationModeErstFragen =
            "D'accord — à partir de maintenant, je te demande d'abord avant d'aller chercher quelque chose en ligne.",
        escalationModeAus = "D'accord — les recherches en ligne sont désactivées. Je reste entièrement local.",
        escalationModeAutomatisch =
            "D'accord — à partir de maintenant, je vais chercher en ligne automatiquement quand je ne sais pas quelque chose.",
        escalationModeOffline =
            "D'accord — mode hors ligne. Je ne vais rien chercher en ligne, je réponds avec ce que je sais moi-même et je te le dis.",
        // ── Never-Silent-Ränder + Fastpath-Quittungen ────────────────────────
        warmFallback = "Je t'ai entendu, mais ça coince un instant de mon côté. Tu me le redis tout de suite?",
        audioCapTooLong = "C'était un peu trop long d'un seul coup — dis-le un peu plus court et je suivrai sans faute.",
        audioNoEndSignal = "L'enregistrement a duré trop longtemps sans signal de fin — tu veux réessayer un peu plus court?",
        admissionBusy = "Je suis sur une autre demande là — laisse-moi un court instant et redemande-moi juste après.",
        dailyNoteRecorded = "Noté: un $SCORE_PLACEHOLDER pour aujourd'hui. Merci à toi!",
        dailyNoteUpdated = "Mis à jour: un $SCORE_PLACEHOLDER pour aujourd'hui. Merci à toi!",
        workshopNoteRecorded = "Noté pour l'atelier. Merci à toi!",
        probeReceipt = "Je t'entends cinq sur cinq — oreilles, fil et voix, tout est en place.",
        currentAffairsBriefingPrefix = "Mis à jour à $TIME_PLACEHOLDER: ",
        currentAffairsBriefingStalePrefix = "Mis à jour à $TIME_PLACEHOLDER, plus ancien que d'habitude: ",
        currentAffairsNone = "Je n'ai aucune information pour toi en ce moment.",
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf(
                "cherche", "regarde", "vérifie", "consulte", "cherche-moi", "recherche",
            ),
            lookupScope = listOf("en ligne", "sur internet", "internet", "web", "net"),
            consentWords = listOf(
                "oui", "oui merci", "oui volontiers", "s'il te plaît", "vas-y", "d'accord",
                "d'ac", "ouais", "bien sûr", "tu peux",
            ),
            researchMarkers = listOf(
                "recherche", "fais des recherches", "recherche en ligne", "web recherche",
            ),
            status = "dokumentiert — Recognizer bewusst DE+EN, Ausbau nach Build-Week (s. PREP-multilingual.md)",
        ),
        promptLanguageInstruction = "Réponds TOUJOURS en français.",
        // Gedreht 2026-07-25: die Bestätigungen sprechen jetzt Französisch —
        // begrenzt ist nur noch das VERSTEHEN der Befehle (Recognizer bleibt DE+EN).
        smartHomeNotice = "Les réponses domotiques parlent désormais français — les commandes ne sont comprises qu'en allemand ou en anglais.",
        sayVoiceHint = "Thomas",
        // Kein Piper-Modell fuer Franzoesisch vorhanden (nur DE+EN sind
        // lizenzgeprueft gepinnt) — bewusst null statt geraten, s.
        // LanguagePack.piperVoiceHint-KDoc.
        piperVoiceHint = null,
        smartHomeAcks = SMART_HOME_ACKS,
        haExecutor = HA_EXECUTOR,
        capabilityDeny = CAPABILITY_DENY,
        )
    }

    /**
     * **Smart-Home-Bestätigungen auf Französisch** (Andi 2026-07-25, „multilingual
     * von A-Z"). Fürs Ohr übersetzt, tutoiement, kurz.
     *
     * Übersetzer-Notiz: `{room}` trägt einen DEUTSCHEN Eigennamen aus Home Assistant
     * — die Sätze sind deshalb so gebaut, dass sie mit „dans {room}" auskommen und
     * nicht vom Genus/Artikel des Raumnamens abhängen.
     */
    val SMART_HOME_ACKS = SmartHomeAckPack(
        lightOnRoom = listOf(
            "Lumière allumée dans {room}.",
            "Il y a de la lumière dans {room}.",
            "Je m'en occupe — lumière allumée dans {room}.",
        ),
        lightOffRoom = listOf(
            "Lumière éteinte dans {room}.",
            "{room} est dans le noir.",
            "Je m'en occupe — lumière éteinte dans {room}.",
        ),
        lightDimRoom = listOf(
            "{room} à {value} pour cent.",
            "Je m'en occupe — {room} à {value} pour cent.",
        ),
        lightDimNoRoom = listOf(
            "À {value} pour cent.",
            "Je m'en occupe — {value} pour cent.",
        ),
        scene = listOf(
            "Je m'en occupe.",
            "C'est réglé.",
        ),
        coverOpen = listOf(
            "C'est ouvert.",
            "Je m'en occupe — c'est ouvert.",
        ),
        coverClose = listOf(
            "C'est fermé.",
            "Je m'en occupe — c'est fermé.",
        ),
        climateRoom = listOf(
            "{room} à {value} degrés.",
            "Je m'en occupe — {room} à {value} degrés.",
        ),
        unknown = listOf(
            "Je m'en occupe.",
            "C'est fait.",
            "Ça marche.",
        ),
        lightOffNoEffectRoom = listOf(
            "Dans {room}, il faisait déjà noir.",
            "Tout était déjà éteint dans {room}.",
            "La lumière de {room} était déjà éteinte.",
        ),
        lightOffNoEffectNoRoom = listOf(
            "C'était déjà éteint.",
            "La lumière était déjà éteinte.",
            "Tout était déjà éteint là-bas.",
        ),
        lightOnNoEffectRoom = listOf(
            "Il y avait déjà de la lumière dans {room}.",
            "C'était déjà allumé dans {room}.",
            "La lumière de {room} était déjà allumée.",
        ),
        lightOnNoEffectNoRoom = listOf(
            "C'était déjà allumé.",
            "La lumière était déjà allumée.",
            "Il y avait déjà de la lumière.",
        ),
        lightDimNoEffectRoom = listOf(
            "{room} est déjà à peu près à {value} pour cent.",
            "Dans {room}, c'est déjà à peu près cette luminosité — {value} pour cent.",
            "C'était déjà autour de {value} pour cent dans {room}.",
        ),
        lightDimNoEffectNoRoom = listOf(
            "C'est déjà à peu près à {value} pour cent.",
            "C'était déjà autour de {value} pour cent.",
        ),
        coverOpenNoEffect = listOf(
            "C'était déjà ouvert.",
            "Ça, c'était déjà ouvert.",
        ),
        coverCloseNoEffect = listOf(
            "C'était déjà fermé.",
            "Ça, c'était déjà fermé.",
        ),
        climateNoEffectRoom = listOf(
            "{room} est déjà réglé sur {value} degrés.",
            "Dans {room}, c'était déjà réglé sur {value} degrés.",
        ),
        climateNoEffectNoRoom = listOf(
            "C'était déjà à {value} degrés.",
            "C'était déjà réglé sur {value} degrés.",
        ),
        genericNoEffect = listOf(
            "Tout était déjà réglé comme ça.",
            "C'était déjà comme ça.",
            "Rien n'a changé — c'était déjà ainsi.",
        ),
        lightOnPartialOfflineOne = listOf(
            "Il y a de la lumière dans {room} — une lampe ne répond pas en ce moment, le reste est allumé.",
            "C'est fait, le reste dans {room} est allumé. Il y en a une que je n'arrive pas à joindre.",
            "{applied} sont allumées dans {room}, une reste muette.",
        ),
        lightOnPartialOfflineMany = listOf(
            "Il y a de la lumière dans {room} — {offline} lampes ne répondent pas en ce moment, le reste est allumé.",
            "Le reste dans {room} est allumé. {offline} que je n'arrive pas à joindre.",
            "{applied} sont allumées dans {room}, {offline} restent muettes.",
        ),
        lightOffPartialOfflineOne = listOf(
            "Il fait noir dans {room} — une lampe ne répond pas en ce moment, le reste est éteint.",
            "Éteint dans {room}, sauf une qui ne répond pas en ce moment.",
            "{applied} sont éteintes dans {room}, une reste muette.",
        ),
        lightOffPartialOfflineMany = listOf(
            "Il fait noir dans {room} — {offline} lampes ne répondent pas en ce moment, le reste est éteint.",
            "Éteint dans {room}, sauf {offline} qui ne répondent pas en ce moment.",
            "{applied} sont éteintes dans {room}, {offline} restent muettes.",
        ),
        partialOfflineNoRoom = listOf(
            "Quelques lampes ne répondent pas en ce moment, le reste a réagi.",
            "C'est fait — une partie ne répond pas pour l'instant.",
        ),
        unsupportedCover = listOf(
            "Un volet comme ça, je ne le trouve pas chez toi.",
            "Je n'ai pas de volet à commander ici.",
            "Il n'y a pas de store que je puisse bouger là.",
        ),
        unsupportedClimate = listOf(
            "Je n'ai pas trouvé de chauffage à régler pour ça.",
            "Il n'y a pas de chauffage que je puisse régler là.",
            "Un thermostat comme ça, je ne le trouve pas chez toi.",
        ),
        unsupportedScene = listOf(
            "Cette scène, je ne la connais pas dans ton installation.",
            "Une scène comme ça, je ne l'ai pas ici.",
            "Cette ambiance, je ne la trouve pas chez toi.",
        ),
        unsupportedGeneric = listOf(
            "Ça, je ne peux pas le commander chez toi en ce moment — un appareil comme ça, je ne le connais pas.",
            "Je n'ai rien trouvé de tel chez toi.",
            "Cet appareil, je ne le connais pas du tout ici.",
        ),
        lightOnNoRoom = listOf("La lumière est allumée."),
        lightOffNoRoom = listOf("La lumière est éteinte."),
        lightDimNoValue = listOf("C'est tamisé."),
        climateValueNoRoom = listOf("À {value} degrés."),
        climateNoValue = listOf("C'est réglé."),
        lightColorNamed = listOf("La couleur est {color}."),
        lightColorUnnamed = listOf("La couleur est changée."),
    )

    /** **HA-Executor-Quittungen auf Französisch.** „envoyé" ≠ „est allumée" ≠ „était déjà" bleibt hörbar getrennt. */
    val HA_EXECUTOR = HaExecutorPack(
        noToken = "Très honnêtement : je n'ai pas de jeton HA configuré en ce moment, donc je n'ai rien commandé.",
        noTokenTemperature =
            "Très honnêtement : je n'ai pas de jeton HA configuré en ce moment, donc je n'accède pas à la température.",
        noThermostatInArea = "Dans {room}, je ne connais aucun thermostat.",
        lightOffArea = "La lumière dans {room} est éteinte.",
        lightSomeStillOn = "Quelques lampes dans {room} sont encore allumées — elles n'ont peut-être pas réagi.",
        offlineHintCount = " — {count} lampes ne sont pas joignables en ce moment (peut-être coupées à l'interrupteur).",
        offlineHintVague = " — les lampes sont peut-être hors ligne.",
        noLightsInArea = "Dans {room}, je n'ai trouvé aucune lampe.",
        lightOnArea = "La lumière dans {room} est allumée.",
        lightAlreadyOnArea = "Dans {room}, la lumière est déjà allumée.",
        lightNothingNewOn = "Dans {room}, il y avait déjà de la lumière, mais rien de nouveau ne s'est allumé",
        lightNoneWentOn = "Je l'ai envoyé à Home Assistant, mais dans {room} aucune lumière ne s'est allumée",
        climateSetArea = "Chauffage dans {room} à {value} degrés.",
        climateNotYet = "Je l'ai envoyé, le chauffage n'a pas encore réagi.",
        sentToArea = "C'est fait — je l'ai envoyé aux appareils dans {room}.",
        sentToHome = "C'est fait — je l'ai envoyé à Home Assistant.",
        failed = "Ça n'a pas marché — Home Assistant ne répond pas en ce moment.",
        noValue = "Pour ça, je n'ai pas de valeur en ce moment.",
        temperatureInArea = "Dans {room}, il fait {value} degrés en ce moment.",
        temperatureHouseAverage = "Dans la maison, il fait {value} degrés en moyenne en ce moment.",
        temperatureUnavailable = "Je n'accède pas à la température en ce moment — réessaie dans un instant.",
        freshnessJustNow = "à l'instant",
        freshnessMinutesAgo = "il y a $MINUTES_PLACEHOLDER minutes",
        freshnessOverAnHourAgo = "il y a plus d'une heure",
        temperatureInAreaStale = "Dans {room}, il faisait {value} degrés $FRESHNESS_PLACEHOLDER.",
        temperatureHouseAverageStale = "Dans la maison, il faisait {value} degrés en moyenne $FRESHNESS_PLACEHOLDER.",
        // Additiv am Zeilenende (LL-additive-Regel), s. HaExecutorPack.roomFallbackName:
        // der ehrliche Ersatz, wenn KEIN echter HA-Anzeigename auffindbar ist —
        // nie ein kapitalisierter Slug („Kuche"), lieber vage als verstümmelt.
        // Passt zu „dans {room}"/„de {room}" — „La lumière dans la pièce
        // demandée est éteinte."
        roomFallbackName = "la pièce demandée",
        decimalSeparator = ",",
    )

    /** **Tat-Verweigerung auf Französisch** — Haltung, kein Fehler. */
    val CAPABILITY_DENY = CapabilityDenyPack(
        refusals = listOf(
            "Ça, je préfère ne pas le faire maintenant — je n'ai pas l'autorisation.",
            "Là, je me retiens : ça, je ne le commande pas comme ça.",
            "Plutôt pas — je laisse ça de côté volontairement tant que ce n'est pas autorisé.",
            "Ça, je n'y touche pas. Si c'est vraiment voulu, il faut d'abord le débloquer.",
        ),
        invalid = "Domaine ou service invalide",
    )
}
