package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Italienisches Sprachpaket — echtes Italienisch, intentPatterns dokumentarisch**
 * (Übersetzer-Pod „Italiano", 2026-07-20).
 *
 * [PACK.cloudConsentAsk]/[PACK.cloudConsentAskExplicit]/[PACK.cloudConsentAccept]/
 * [PACK.cloudConsentDecline]/[PACK.abstainLookupOffer] sind mit Ohr ins Italienische
 * übersetzt (Wärme-Anspruch wie DE/EN, tu-Form, kurz und TTS-gerecht — keine
 * wörtlich-roboterhaften 1:1-Übersetzungen). [PACK.intentPatterns] dokumentiert
 * die naheliegendsten italienischen Signalwörter (z.B. lookupVerbs: "cerca"/"guarda"/
 * "controlla"; lookupScope: "online"/"su internet"; consentWords: "sì"/"va bene"/
 * "certo"/"dai") — konservativ, false-positive-avers. Die Recognizer bleiben
 * absichtlich DE+EN geteilt (s. LanguagePack KDoc) — Ausbau nach Build-Week
 * (s. PREP-multilingual.md).
 *
 * [PACK.promptLanguageInstruction] + [PACK.smartHomeNotice] + [PACK.sayVoiceHint]
 * sind bereits echtes, einfaches Italienisch (geringes Risiko, im Gegensatz zu den
 * nuancierten Konversations-Pools oben).
 */
object LangIt {

    /** `by lazy` — s. [LangDe.PACK]: [PACK] referenziert weiter unten deklarierte Felder. */
    val PACK: LanguagePack by lazy {
        LanguagePack(
        language = Language.IT,
        cloudConsentAsk = listOf(
            "Mmm, questa dovrei controllarla online al volo — ti va?",
            "Su questo non sono sicuro. Ti spiace se controllo?",
            "Questo proprio non lo so. Va bene se guardo online?",
            "Fammi controllare online, un attimo — ti sta bene?",
            "Per questo dovrei chiedere a internet. Lo faccio?",
            "Online probabilmente lo saprei. Vuoi che guardi?",
        ),
        cloudConsentAskExplicit = listOf(
            "Certo, vado subito online per questo — mi dai l'okay?",
            "Ci penso io — do un'occhiata fuori al volo, ti va bene?",
            "Volentieri vado a recuperarlo là fuori — dimmi solo di sì e parto.",
        ),
        cloudConsentAccept = listOf(
            "Certo, un attimo — lascia che chieda.",
            "Ci penso io, controllo veloce…",
            "Vado. Un secondo.",
            "Okay, dammi un momento.",
        ),
        cloudConsentDecline = listOf(
            "Va bene, ti dico quello che so io.",
            "Okay, mi tengo quello che ho.",
            "Capito — ecco come la vedo io.",
            "Va bene, allora lavoro con quello che ho.",
        ),
        abstainLookupOffer = listOf(
            " — Vuoi che lo controlli online?",
            " — Vuoi che te lo cerchi online?",
            " — Vuoi che te lo controlli online?",
        ),
        // ── Ehrlichkeit: was Hoshi sagt, wenn er NICHT weiterweiß. Tu-Form,
        //    caldo e sincero — mai un messaggio di errore.
        honestyOnlineRequestRefusals = listOf(
            "Sulla rete aperta non ci vado, ed è voluto — resto qui con te. Ma in quello che so io guardo molto volentieri: cosa cerchi di preciso?",
            "Uscire là fuori su internet non mi va proprio — per questo ho qui tutto un archivio di sapere. Ci guardo dentro per te?",
            "In giro per internet non ci sto, è di proposito. Quello che ho, però, è il mio sapere — dimmi cosa cerchi e guardo.",
            "Internet lo lascio chiuso apposta — ma ho parecchia roba salvata. Fammi cercare lì dentro per te, va bene?",
            "Fuori non ci vado, è voluto. Ma in quello che so io qualcosa per te lo trovo di sicuro — cosa ti serve?",
        ),
        honestyRecipeRefusals = listOf(
            "Cucinare non è il mio forte — lì ti porterei fuori strada.",
            "Su una ricetta tirerei a indovinare, e non ti sarebbe d'aiuto.",
        ),
        honestyExistenceRefusals = listOf(
            "Aspetta — non sono sicuro che esista davvero. Preferisco non inventarti niente.",
            "Bella domanda — su una cosa così non mi piace fidarmi dell'istinto. Preferisco dirtelo onestamente: non lo so.",
            "Sinceramente? Qui mi fermo. Una cosa così la guarderei, invece di tirare a indovinare.",
        ),
        honestyNamedEntityRefusals = listOf(
            "Mmm, questo nome adesso non mi dice niente. Sembra qualcuno di un certo ambiente — chi è di preciso?",
            "Raccontami di più — musica, cinema, storia, sport? Con quel nome sono completamente al buio.",
            "Questo nome non lo conosco — mi racconti qualcosa?",
            "Sinceramente, su questo non ho nulla — tu il nome da dove lo conosci?",
        ),
        honestyBridgeDownRefusals = listOf(
            "In questo momento non riesco ad arrivare al mio archivio di sapere — potrò dirtelo con certezza tra un attimo. Me lo richiedi tra poco?",
            "Hm, il mio libro di consultazione adesso non è raggiungibile. Non voglio tirare a indovinare — richiedimelo tra un attimo e guardo per bene.",
            "Qui mi sono bloccato — il mio archivio di sapere non risponde. Dammi un momento e poi te lo dico onestamente.",
        ),
        factCoverageOfflineDisclaimer = "Onestamente, non ho una fonte per questo — ma per quel che so io: ",
        localLookupFoundPrefix = "Ho guardato in quello che ho qui — ",
        // ── Andi-Auftrag 2026-07-26 „le frasi per Hoshi guarda online sono
        //    brutte" — questi cinque campi finivano finora in inglese per
        //    ES/FR/IT; adesso italiano vero, con „guardare" invece di un vago
        //    „chiedere". Aggiunta „non sempre la stessa risposta": deflect +
        //    introduzione al risultato sono ora pool da 4 (AntiRepeatPicker) —
        //    ogni variante di deflect finisce con la domanda, ogni variante
        //    dell'introduzione nomina online/internet.
        factCoverageDeflect = listOf(
            "Uff, questo adesso non lo so a memoria — lo guardo un attimo?",
            "Bella domanda — lo guardo un attimo?",
            "Questo non ce l'ho a mente — do un'occhiata?",
            "Non lo so a memoria adesso — vuoi che controlli?",
        ),
        escalationAnswerFrame = listOf(
            "Allora ho dato un'occhiata veloce online — ",
            "Ho controllato veloce online — ",
            "Ho dato un'occhiata su internet — ",
            "Ho guardato veloce online — ",
        ),
        escalationSourceTemplate = "Fonte: {source}.",
        escalationUnavailable = "Volevo darci un'occhiata online, ma adesso non riesco a collegarmi — riproviamo più tardi.",
        escalationModeErstFragen = "Va bene — da adesso in poi ti chiedo prima di guardare qualcosa online.",
        escalationModeAus = "Va bene — le ricerche online sono disattivate. Resto completamente locale.",
        escalationModeAutomatisch = "Va bene — da adesso in poi guardo online automaticamente quando non so qualcosa.",
        escalationModeOffline =
            "Va bene — modalità offline. Non guardo niente online, rispondo con quello che so io e te lo dico.",
        // ── Never-Silent-Ränder + Fastpath-Quittungen ────────────────────────
        warmFallback = "Ti ho sentito, ma qui si è inceppato qualcosa per un attimo. Me lo ridici tra un secondo?",
        audioCapTooLong = "Era un po' troppo lungo tutto d'un fiato — dimmelo un po' più corto e ti seguo senza perdermi.",
        audioNoEndSignal = "La registrazione è andata troppo a lungo senza segnale di fine — vuoi riprovare un po' più corto?",
        admissionBusy = "In questo momento sto seguendo un'altra richiesta — dammi un attimo e richiedimelo subito dopo.",
        dailyNoteRecorded = "Annotato: oggi un $SCORE_PLACEHOLDER. Grazie!",
        dailyNoteUpdated = "Aggiornato: oggi un $SCORE_PLACEHOLDER. Grazie!",
        workshopNoteRecorded = "Annotato per l'officina. Grazie!",
        probeReceipt = "Ti sento forte e chiaro — orecchie, filo e voce sono in piedi.",
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf(
                "cerca", "guarda", "controlla", "verifica", "cercami", "controllami",
            ),
            lookupScope = listOf("online", "su internet", "in rete", "internet", "web"),
            consentWords = listOf(
                "sì", "va bene", "certo", "dai", "okay", "d'accordo", "ok", "vai",
            ),
            researchMarkers = listOf(
                "fai una ricerca", "ricerca(re)?", "cerca a fondo", "ricerca online", "ricerca su internet",
            ),
            status = "dokumentiert — Recognizer bewusst DE+EN, Ausbau nach Build-Week (s. PREP-multilingual.md)",
        ),
        promptLanguageInstruction = "Rispondi SEMPRE in italiano.",
        // Gedreht 2026-07-25: die Bestätigungen sprechen jetzt Italienisch —
        // begrenzt ist nur noch das VERSTEHEN der Befehle (Recognizer bleibt DE+EN).
        smartHomeNotice = "Le risposte smart-home ora parlano italiano — i comandi si capiscono ancora solo in tedesco o inglese.",
        sayVoiceHint = "Alice",
        // Kein Piper-Modell fuer Italienisch vorhanden (nur DE+EN sind
        // lizenzgeprueft gepinnt) — bewusst null statt geraten, s.
        // LanguagePack.piperVoiceHint-KDoc.
        piperVoiceHint = null,
        smartHomeAcks = SMART_HOME_ACKS,
        haExecutor = HA_EXECUTOR,
        capabilityDeny = CAPABILITY_DENY,
        )
    }

    /**
     * **Smart-Home-Bestätigungen auf Italienisch** (Andi 2026-07-25, „multilingual
     * von A-Z"). Fürs Ohr übersetzt, du-Form, kurz.
     *
     * Übersetzer-Notiz: `{room}` trägt einen DEUTSCHEN Eigennamen aus Home Assistant
     * — die Sätze kommen deshalb mit „in {room}" aus und hängen nicht am Genus des
     * Raumnamens.
     */
    val SMART_HOME_ACKS = SmartHomeAckPack(
        lightOnRoom = listOf(
            "Luce accesa in {room}.",
            "C'è luce in {room}.",
            "Ci penso io — luce accesa in {room}.",
        ),
        lightOffRoom = listOf(
            "Luce spenta in {room}.",
            "{room} è al buio.",
            "Ci penso io — luce spenta in {room}.",
        ),
        lightDimRoom = listOf(
            "{room} al {value} per cento.",
            "Ci penso io — {room} al {value} per cento.",
        ),
        lightDimNoRoom = listOf(
            "Al {value} per cento.",
            "Ci penso io — {value} per cento.",
        ),
        scene = listOf(
            "Ci penso io.",
            "È impostata.",
        ),
        coverOpen = listOf(
            "È aperta.",
            "Ci penso io — è aperta.",
        ),
        coverClose = listOf(
            "È chiusa.",
            "Ci penso io — è chiusa.",
        ),
        climateRoom = listOf(
            "{room} a {value} gradi.",
            "Ci penso io — {room} a {value} gradi.",
        ),
        unknown = listOf(
            "Ci penso io.",
            "Fatto.",
            "Va bene.",
        ),
        lightOffNoEffectRoom = listOf(
            "In {room} era già buio.",
            "In {room} era già tutto spento.",
            "La luce in {room} era già spenta.",
        ),
        lightOffNoEffectNoRoom = listOf(
            "Era già spenta.",
            "La luce era già spenta.",
            "Lì era già tutto spento.",
        ),
        lightOnNoEffectRoom = listOf(
            "In {room} c'era già luce.",
            "In {room} era già acceso.",
            "La luce in {room} era già accesa.",
        ),
        lightOnNoEffectNoRoom = listOf(
            "Era già accesa.",
            "La luce era già accesa.",
            "Lì c'era già luce.",
        ),
        lightDimNoEffectRoom = listOf(
            "{room} è già più o meno al {value} per cento.",
            "In {room} c'è già più o meno questa luce — {value} per cento.",
            "Era già intorno al {value} per cento in {room}.",
        ),
        lightDimNoEffectNoRoom = listOf(
            "È già più o meno al {value} per cento.",
            "Era già intorno al {value} per cento.",
        ),
        coverOpenNoEffect = listOf(
            "Era già aperta.",
            "Quella era già aperta.",
        ),
        coverCloseNoEffect = listOf(
            "Era già chiusa.",
            "Quella era già chiusa.",
        ),
        climateNoEffectRoom = listOf(
            "{room} è già a {value} gradi.",
            "In {room} era già impostato su {value} gradi.",
        ),
        climateNoEffectNoRoom = listOf(
            "Era già a {value} gradi.",
            "Era già impostato su {value} gradi.",
        ),
        genericNoEffect = listOf(
            "Lì era già tutto così.",
            "Era già così.",
            "Non è cambiato niente — era già così.",
        ),
        lightOnPartialOfflineOne = listOf(
            "C'è luce in {room} — una lampada non risponde in questo momento, il resto è acceso.",
            "Fatto, il resto in {room} è acceso. Una non riesco a raggiungerla.",
            "{applied} sono accese in {room}, una è muta al momento.",
        ),
        lightOnPartialOfflineMany = listOf(
            "C'è luce in {room} — {offline} lampade non rispondono in questo momento, il resto è acceso.",
            "Il resto in {room} è acceso. {offline} non riesco a raggiungerle.",
            "{applied} sono accese in {room}, {offline} sono mute al momento.",
        ),
        lightOffPartialOfflineOne = listOf(
            "In {room} è buio — una lampada non risponde in questo momento, il resto è spento.",
            "Spento in {room}, tranne una che non risponde in questo momento.",
            "{applied} sono spente in {room}, una è muta al momento.",
        ),
        lightOffPartialOfflineMany = listOf(
            "In {room} è buio — {offline} lampade non rispondono in questo momento, il resto è spento.",
            "Spento in {room}, tranne {offline} che non rispondono in questo momento.",
            "{applied} sono spente in {room}, {offline} sono mute al momento.",
        ),
        partialOfflineNoRoom = listOf(
            "Qualche lampada non risponde in questo momento, il resto ha reagito.",
            "Fatto — una parte non risponde al momento.",
        ),
        unsupportedCover = listOf(
            "Una tapparella così da te non la trovo.",
            "Qui non ho una tapparella da comandare.",
            "Lì non c'è nessuna tenda che io possa muovere.",
        ),
        unsupportedClimate = listOf(
            "Non ho trovato nessun riscaldamento da regolare per questo.",
            "Lì non c'è un riscaldamento che io possa regolare.",
            "Un termostato così da te non lo trovo.",
        ),
        unsupportedScene = listOf(
            "Questa scena non la conosco nel tuo impianto.",
            "Una scena così qui non ce l'ho.",
            "Questa atmosfera da te non la trovo.",
        ),
        unsupportedGeneric = listOf(
            "Questo da te in questo momento non riesco a comandarlo — un apparecchio così non lo conosco.",
            "Una cosa del genere non l'ho trovata in casa tua.",
            "Questo apparecchio qui non lo conosco proprio.",
        ),
        lightOnNoRoom = listOf("La luce è accesa."),
        lightOffNoRoom = listOf("La luce è spenta."),
        lightDimNoValue = listOf("È soffusa."),
        climateValueNoRoom = listOf("A {value} gradi."),
        climateNoValue = listOf("È impostato."),
        lightColorNamed = listOf("Il colore è {color}."),
        lightColorUnnamed = listOf("Il colore è cambiato."),
    )

    /** **HA-Executor-Quittungen auf Italienisch.** „mandato" ≠ „è accesa" ≠ „era già" bleibt hörbar getrennt. */
    val HA_EXECUTOR = HaExecutorPack(
        noToken = "Ti dico la verità: al momento non ho un token HA configurato, quindi non ho comandato niente.",
        noTokenTemperature =
            "Ti dico la verità: al momento non ho un token HA configurato, quindi non arrivo alla temperatura.",
        noThermostatInArea = "In {room} non conosco nessun termostato.",
        lightOffArea = "La luce in {room} è spenta.",
        lightSomeStillOn = "Qualche lampada in {room} è ancora accesa — forse non ha reagito.",
        offlineHintCount = " — {count} lampade non sono raggiungibili al momento (forse spente dall'interruttore).",
        offlineHintVague = " — forse le lampade sono offline.",
        noLightsInArea = "In {room} non ho trovato nessuna lampada.",
        lightOnArea = "La luce in {room} è accesa.",
        lightAlreadyOnArea = "In {room} la luce è già accesa.",
        lightNothingNewOn = "In {room} c'era già luce, ma non si è acceso niente di nuovo",
        lightNoneWentOn = "L'ho mandato a Home Assistant, ma in {room} non si è accesa nessuna luce",
        climateSetArea = "Riscaldamento in {room} a {value} gradi.",
        climateNotYet = "L'ho mandato, il riscaldamento non ha ancora reagito.",
        sentToArea = "Fatto — l'ho mandato agli apparecchi in {room}.",
        sentToHome = "Fatto — l'ho mandato a Home Assistant.",
        failed = "Non ha funzionato — Home Assistant non risponde in questo momento.",
        noValue = "Per questo al momento non ho un valore.",
        temperatureInArea = "In {room} ci sono {value} gradi in questo momento.",
        temperatureHouseAverage = "In casa ci sono in media {value} gradi in questo momento.",
        temperatureUnavailable = "Al momento non arrivo alla temperatura — riprova tra un attimo.",
        decimalSeparator = ",",
    )

    /** **Tat-Verweigerung auf Italienisch** — Haltung, kein Fehler. */
    val CAPABILITY_DENY = CapabilityDenyPack(
        refusals = listOf(
            "Questo preferisco non farlo adesso — non ho l'autorizzazione.",
            "Qui mi trattengo: una cosa così non la comando e basta.",
            "Meglio di no — questo lo lascio stare finché non è autorizzato.",
            "Questo non lo tocco. Se lo vuoi davvero, prima dobbiamo abilitarlo.",
        ),
        invalid = "Dominio o servizio non valido",
    )
}
