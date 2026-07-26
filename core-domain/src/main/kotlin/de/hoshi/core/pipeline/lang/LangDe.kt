package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Deutsches Sprachpaket — die Heimsprache.** Jede Zeile hier ist WORT-FÜR-WORT
 * aus [de.hoshi.core.pipeline.ResponseFormatter] VERSCHOBEN (nicht verändert) —
 * der de-Pfad bleibt byte-identisch, die bestehenden Formatter-/Orchestrator-
 * Tests sind der beweis (Andi: „der de-Pfad darf um kein Byte wackeln").
 *
 * Übersetzer-Pod-Regel: diese Datei bleibt UNANGETASTET von Sprachpaket-Arbeit an
 * anderen Sprachen (s. [LangEn]/[LangEs]/[LangFr]/[LangIt]).
 */
object LangDe {

    /**
     * `by lazy`, damit die Deklarations-Reihenfolge im Objekt egal ist: [PACK]
     * referenziert [SMART_HOME_ACKS]/[HA_EXECUTOR]/[CAPABILITY_DENY], die weiter
     * unten stehen. Eager wären die beim PACK-Bau noch `null` (Objekt-Properties
     * initialisieren in Deklarations-Reihenfolge) — `lazy` baut das Pack erst beim
     * ersten Zugriff, also garantiert nach allen Feldern.
     */
    val PACK: LanguagePack by lazy {
        LanguagePack(
        language = Language.DE,
        cloudConsentAsk = listOf(
            "Hmm, das müsste ich kurz online nachschauen — soll ich?",
            "Da bin ich mir nicht sicher. Darf ich kurz nachsehen?",
            "Genau das weiß ich nicht. Online schauen okay?",
            "Lass mich kurz online checken — passt das?",
            "Da guck ich am besten online nach. Soll ich?",
            "Online weiß ich's vermutlich. Soll ich?",
        ),
        cloudConsentAskExplicit = listOf(
            "Klar, dafür geh ich kurz online — kurz dein Okay?",
            "Mach ich, dann schau ich kurz raus für dich — passt das?",
            "Gern, das hol ich von draußen — sag kurz Ja, dann leg ich los.",
        ),
        cloudConsentAccept = listOf(
            "Klar, Moment — ich schau schnell.",
            "Geht klar, kurz schauen…",
            "Mache ich. Moment.",
            "Okay, Sekunde.",
        ),
        cloudConsentDecline = listOf(
            "Okay, dann sag ich dir, was ich selbst weiß.",
            "Alles klar, bleiben wir bei mir.",
            "Verstanden — also so wie ich's sehe.",
            "Gut, dann mit dem, was ich hab.",
        ),
        abstainLookupOffer = listOf(
            " — Soll ich kurz online nachschauen?",
            " — Willst du, dass ich das online nachschaue?",
            " — Soll ich das für dich online checken?",
        ),
        // ── Ehrlichkeits-Sätze: WORT-FÜR-WORT aus [de.hoshi.core.pipeline.HonestyGate]
        //    verschoben (nicht verändert) — der de-Pfad bleibt byte-identisch, die
        //    bestehenden HonestyGate-Tests sind der Beweis.
        honestyOnlineRequestRefusals = listOf(
            "Ins offene Netz geh ich bewusst nicht — ich bleib bei dir. Aber in meinem eigenen Wissen schau ich gern nach: was genau suchst du?",
            "Da raus ins Internet will ich gar nicht — dafür hab ich 'nen ganzen Wissensspeicher hier. Soll ich da für dich nachsehen?",
            "Online unterwegs bin ich absichtlich nicht. Was ich aber hab, ist mein eigenes Wissen — sag mir, wonach, dann schau ich nach.",
            "Das Internet lass ich bewusst zu — aber ich hab ne Menge selbst gespeichert. Lass mich da für dich nachschlagen, okay?",
            "Nach draußen geh ich nicht, das ist Absicht. In meinem eigenen Wissen werd ich aber gern für dich fündig — was brauchst du?",
        ),
        honestyRecipeRefusals = listOf(
            "Kochen ist nicht meine Stärke — da führ ich dich in die Irre.",
            "Beim Rezept würd ich raten, und das wär dir keine Hilfe.",
        ),
        honestyExistenceRefusals = listOf(
            "Halt — da bin ich nicht sicher, ob's das wirklich gibt. Ich würd dir lieber nichts erfinden.",
            "Gute Frage — bei sowas verlass ich mich ungern auf mein Bauchgefühl. Lieber sag ich's ehrlich: weiß ich nicht.",
            "Ehrlich? Da bin ich raus. Sowas würd ich gerne nachschauen statt raten.",
        ),
        honestyNamedEntityRefusals = listOf(
            "Hmm, der Name sagt mir gerade nichts. Klingt nach jemandem aus einer bestimmten Szene — wer genau ist das?",
            "Sag mir mehr — Musik, Film, Geschichte, Sport? Bei dem Namen tappe ich grade im Dunkeln.",
            "Ich kenn den Namen nicht — magst du mir was dazu sagen?",
            "Ehrlich, da hab ich nichts zu — woher kennst du den Namen?",
        ),
        honestyBridgeDownRefusals = listOf(
            "Ich komm gerade nicht an meinen Wissensspeicher — das kann ich dir verlässlich erst gleich sagen. Magst du's in einem Moment nochmal fragen?",
            "Hm, mein Nachschlagewerk ist im Moment nicht erreichbar. Ich will dir nichts raten — frag mich gleich nochmal, dann schau ich richtig nach.",
            "Da häng ich grad — mein Wissensspeicher antwortet nicht. Gib mir einen Moment, dann kann ich's dir ehrlich sagen.",
        ),
        factCoverageOfflineDisclaimer = "Ehrlich, dafür hab ich keinen Beleg — aber aus meinem eigenen Wissen: ",
        localLookupFoundPrefix = "Hab kurz bei mir nachgeschaut — ",
        // ── Andi-Auftrag 2026-07-26 „die Sprüche für Hoshi schaut online nach
        //    sind schlecht" — Deflect NEU, die übrigen vier Felder hierher
        //    verschoben (WORT-FÜR-WORT aus FactCoverageGate/TurnOrchestrator/
        //    EscalationModeFastpath übernommen, kein Zeichen geändert). Nachtrag
        //    „gestreut statt statisch": Deflect + Ergebnis-Vorspann sind jetzt
        //    4er-Pools (AntiRepeatPicker) — jede Deflect-Variante endet mit der
        //    Nachschau-Frage, jede Vorspann-Variante trägt Netz/Internet/online.
        factCoverageDeflect = listOf(
            "Puh, das weiß ich grad nicht auswendig — soll ich kurz nachschauen?",
            "Hm, das hab ich nicht im Kopf — soll ich kurz gucken?",
            "Gute Frage — soll ich kurz nachschauen?",
            "Ehrlich, da bin ich grad überfragt — soll ich schnell nachschauen?",
        ),
        escalationAnswerFrame = listOf(
            "Hab kurz im Netz geschaut — ",
            "Kurz im Internet geschaut — ",
            "Hab schnell online geschaut — ",
            "Kurz online nachgesehen — ",
        ),
        escalationSourceTemplate = "Quelle: {source}.",
        escalationUnavailable = "Ich wollt nachschauen, aber grad komm ich nicht ran — probieren wir's später nochmal.",
        escalationModeErstFragen = "Okay — ich frag dich ab jetzt erst, bevor ich online nachschaue.",
        escalationModeAus = "Okay — Online-Nachschauen ist aus. Ich bleib komplett lokal.",
        escalationModeAutomatisch = "Okay — ich schau ab jetzt automatisch online nach, wenn ich etwas nicht weiß.",
        escalationModeOffline =
            "Okay — Offline-Modus. Ich schau nichts online nach; ich antworte aus dem, was ich selbst weiß, und sag das dazu.",
        // ── Never-Silent-Ränder + Fastpath-Quittungen: ebenfalls WORT-FÜR-WORT aus
        //    TurnOrchestrator / AudioWebSocketHandler / BrainAdmissionGate /
        //    DailyNote-/WorkshopNote-/ProbeFastpath verschoben.
        warmFallback = "Hab dich gehört, aber bei mir hakt's grad kurz. Sag's gleich nochmal?",
        audioCapTooLong = "Das war mir zu lang am Stück — sag es bitte etwas kürzer, dann krieg ich's zuverlässig mit.",
        audioNoEndSignal = "Die Aufnahme lief mir zu lange ohne Ende-Signal — magst du es nochmal etwas kürzer versuchen?",
        admissionBusy = "Ich bin gerade an einer anderen Anfrage dran — gib mir einen kurzen Moment und frag gleich nochmal.",
        dailyNoteRecorded = "Notiert: heute eine $SCORE_PLACEHOLDER. Danke dir!",
        dailyNoteUpdated = "Aktualisiert: heute eine $SCORE_PLACEHOLDER. Danke dir!",
        workshopNoteRecorded = "Notiert für die Werkstatt. Danke dir!",
        probeReceipt = "Ich hör dich klar und deutlich — Ohren, Draht und Stimme stehen.",
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf(
                "schau", "guck", "sieh", "schlag", "schläg", "nachschau",
                "nachguck", "nachschlag", "such", "prüf", "pruef",
            ),
            lookupScope = listOf("online", "internet", "netz", "web"),
            consentWords = listOf(
                "ja", "ja bitte", "ja gerne", "gerne", "mach das", "mach mal",
                "bitte", "ok", "okay", "jo", "jap", "klar",
            ),
            researchMarkers = listOf(
                "recherchier(e/st/en)", "recherche dazu/hierzu/davon/darüber", "web ?suche", "internet ?suche",
            ),
            status = "aktiv (LookupIntentRecognizer/ConsentRecognizer/ResearchIntentRecognizer, DE+EN geteilt)",
        ),
        promptLanguageInstruction = "Antworte IMMER auf Deutsch.",
        smartHomeNotice = null,
        sayVoiceHint = null,
        piperVoiceHint = null,
        smartHomeAcks = SMART_HOME_ACKS,
        haExecutor = HA_EXECUTOR,
        capabilityDeny = CAPABILITY_DENY,
        )
    }

    /**
     * Die Smart-Home-Bestätigungs-Pools — WORT-FÜR-WORT aus dem bisherigen
     * [de.hoshi.core.pipeline.ResponseFormatter] verschoben, seither um kein Byte
     * verändert. Seit 2026-07-25 sind sie NICHT mehr die einzige Wahrheit für alle
     * Sprachen (s. [LangEn]/[LangEs]/[LangFr]/[LangIt]), sondern das deutsche
     * Exemplar unter vielen — hier hängt der Byte-Identitäts-Beweis dran.
     *
     * Steht bewusst VOR [PACK]: Objekt-Properties initialisieren in
     * Deklarations-Reihenfolge, und [PACK] referenziert diesen Wert.
     */
    val SMART_HOME_ACKS = SmartHomeAckPack(
        lightOnRoom = listOf(
            "{room} ist an.",
            "{room} ist hell.",
            "Mach ich — {room} ist an.",
        ),
        lightOffRoom = listOf(
            "{room} ist aus.",
            "{room} ist dunkel.",
            "Mach ich — {room} ist aus.",
        ),
        lightDimRoom = listOf(
            "{room} auf {value} Prozent.",
            "Mach ich — {room} auf {value} Prozent.",
        ),
        lightDimNoRoom = listOf(
            "Auf {value} Prozent.",
            "Mach ich — {value} Prozent.",
        ),
        scene = listOf(
            "Mach ich.",
            "Ist eingestellt.",
        ),
        coverOpen = listOf(
            "Ist offen.",
            "Mach ich — ist offen.",
        ),
        coverClose = listOf(
            "Ist zu.",
            "Mach ich — ist zu.",
        ),
        climateRoom = listOf(
            "{room} auf {value} Grad.",
            "Mach ich — {room} auf {value} Grad.",
        ),
        unknown = listOf(
            "Mach ich.",
            "Ist erledigt.",
            "Geht klar.",
        ),
        lightOffNoEffectRoom = listOf(
            "{room} war schon dunkel.",
            "Da ist schon alles aus im {room}.",
            "Das Licht im {room} war schon aus.",
        ),
        lightOffNoEffectNoRoom = listOf(
            "War schon aus.",
            "Das Licht war schon dunkel.",
            "Da war schon alles aus.",
        ),
        lightOnNoEffectRoom = listOf(
            "{room} war schon hell.",
            "Da brennt schon Licht im {room}.",
            "Das Licht im {room} war schon an.",
        ),
        lightOnNoEffectNoRoom = listOf(
            "War schon an.",
            "Das Licht war schon hell.",
            "Da war schon Licht an.",
        ),
        lightDimNoEffectRoom = listOf(
            "{room} steht schon ungefähr auf {value} Prozent.",
            "Im {room} ist's schon etwa so hell — {value} Prozent.",
            "War schon ungefähr auf {value} Prozent im {room}.",
        ),
        lightDimNoEffectNoRoom = listOf(
            "Steht schon ungefähr auf {value} Prozent.",
            "War schon etwa auf {value} Prozent.",
        ),
        coverOpenNoEffect = listOf(
            "War schon offen.",
            "Das stand schon offen.",
        ),
        coverCloseNoEffect = listOf(
            "War schon zu.",
            "Das war schon zu.",
        ),
        climateNoEffectRoom = listOf(
            "{room} steht schon auf {value} Grad.",
            "Das war im {room} schon auf {value} Grad eingestellt.",
        ),
        climateNoEffectNoRoom = listOf(
            "Das war schon auf {value} Grad.",
            "Stand schon auf {value} Grad.",
        ),
        genericNoEffect = listOf(
            "Da war schon alles so eingestellt.",
            "Das war schon so.",
            "Hat sich nichts geändert — war schon so.",
        ),
        lightOnPartialOfflineOne = listOf(
            "Im {room} ist Licht — die eine Lampe meldet sich grad nicht, der Rest leuchtet.",
            "Hab ich, der Rest im {room} ist an. Eine kriege ich gerade nicht ans Netz.",
            "{applied} sind an im {room}, eine ist gerade still.",
        ),
        lightOnPartialOfflineMany = listOf(
            "Im {room} ist Licht — {offline} Lampen melden sich grad nicht, der Rest leuchtet.",
            "Der Rest im {room} ist an. {offline} kriege ich gerade nicht ans Netz.",
            "{applied} sind an im {room}, {offline} sind gerade still.",
        ),
        lightOffPartialOfflineOne = listOf(
            "Im {room} ist's dunkel — die eine Lampe meldet sich grad nicht, der Rest ist aus.",
            "Aus im {room}, bis auf eine, die sich gerade nicht meldet.",
            "{applied} sind aus im {room}, eine ist gerade still.",
        ),
        lightOffPartialOfflineMany = listOf(
            "Im {room} ist's dunkel — {offline} Lampen melden sich grad nicht, der Rest ist aus.",
            "Aus im {room}, bis auf {offline}, die sich gerade nicht melden.",
            "{applied} sind aus im {room}, {offline} sind gerade still.",
        ),
        partialOfflineNoRoom = listOf(
            "Ein paar Lampen melden sich grad nicht, der Rest hat reagiert.",
            "Hab ich — ein Teil meldet sich gerade nicht.",
        ),
        unsupportedCover = listOf(
            "So ein Rollo finde ich bei dir gerade nicht.",
            "Ein Rollo zum Steuern hab ich da nicht.",
            "Da ist kein Rollo, das ich bewegen könnte.",
        ),
        unsupportedClimate = listOf(
            "Eine Heizung zum Steuern hab ich dafür nicht gefunden.",
            "Da ist keine Heizung, die ich regeln kann.",
            "So ein Thermostat finde ich bei dir nicht.",
        ),
        unsupportedScene = listOf(
            "Die Szene kenne ich in deinem Setup nicht.",
            "So eine Szene hab ich hier nicht.",
            "Die Stimmung finde ich bei dir nicht.",
        ),
        unsupportedGeneric = listOf(
            "Das kann ich bei dir gerade nicht schalten — so ein Gerät kenne ich nicht.",
            "Sowas hab ich in deinem Zuhause nicht gefunden.",
            "Das Gerät kenne ich hier gar nicht.",
        ),
        // Die „kein Slot"-Varianten: standen bis 2026-07-25 als nackte Literale IM
        // ResponseFormatter (`else -> "Licht ist an."`). Ein-elementig ⇒ der
        // Anti-Repeat-Ring gibt unverändert genau diesen String zurück.
        lightOnNoRoom = listOf("Licht ist an."),
        lightOffNoRoom = listOf("Licht ist aus."),
        lightDimNoValue = listOf("Ist gedimmt."),
        climateValueNoRoom = listOf("Auf {value} Grad."),
        climateNoValue = listOf("Ist eingestellt."),
        lightColorNamed = listOf("Farbe ist {color}."),
        lightColorUnnamed = listOf("Farbe ist geändert."),
    )

    /**
     * Die Quittungen des realen HA-Executors — WORT-FÜR-WORT aus
     * `de.hoshi.adapters.ha.HaToolPort` verschoben (2026-07-25), kein Zeichen
     * geändert. Die bestehenden `HaToolPortTest`-Erwartungen sind der Beweis.
     *
     * Achtung beim Lesen: `{room}` ist NICHT überall dasselbe. Der Licht-Readback
     * setzt den rohen HA-Area-**Slug** ein (`kueche`), Klima/Temperatur setzen das
     * sprechbare **Label** ein (`Küche`) — genau so war es vorher auch, und genau
     * so bleibt es (beides Nutzerdaten, beides unübersetzt).
     */
    val HA_EXECUTOR = HaExecutorPack(
        noToken = "Ganz ehrlich: ich hab gerade kein HA-Token konfiguriert, also hab ich nichts geschaltet.",
        noTokenTemperature =
            "Ganz ehrlich: ich hab gerade kein HA-Token konfiguriert, also komm ich nicht an die Temperatur ran.",
        noThermostatInArea = "Im {room} kenne ich kein Thermostat.",
        lightOffArea = "Licht im {room} ist aus.",
        lightSomeStillOn = "Ein paar Lampen im {room} sind noch an — die haben evtl. nicht reagiert.",
        offlineHintCount = " — {count} Lampen sind gerade nicht erreichbar (evtl. am Schalter aus).",
        offlineHintVague = " — vielleicht sind die Lampen offline.",
        noLightsInArea = "Im {room} hab ich gar keine Lampen gefunden.",
        lightOnArea = "Licht im {room} ist an.",
        lightAlreadyOnArea = "Im {room} ist das Licht schon an.",
        lightNothingNewOn = "Im {room} brennt zwar schon Licht, aber neu angegangen ist nichts",
        lightNoneWentOn = "Ich hab's an Home Assistant geschickt, aber im {room} ging kein Licht an",
        climateSetArea = "Heizung im {room} auf {value} Grad.",
        climateNotYet = "Hab's geschickt, die Heizung hat noch nicht reagiert.",
        sentToArea = "Ist erledigt — ich hab's an die Geräte im {room} geschickt.",
        sentToHome = "Ist erledigt — ich hab's an Home Assistant geschickt.",
        failed = "Hat nicht geklappt — Home Assistant hat gerade nicht reagiert.",
        noValue = "Dafür hab ich gerade keinen Wert.",
        temperatureInArea = "Im {room} sind es gerade {value} Grad.",
        temperatureHouseAverage = "Im Haus sind es gerade durchschnittlich {value} Grad.",
        temperatureUnavailable = "Ich komm gerade nicht an die Temperatur ran — versuch's gleich nochmal.",
        decimalSeparator = ",",
    )

    /**
     * Die gesprochene Tat-Verweigerung des Trust-Kernels — WORT-FÜR-WORT aus
     * `de.hoshi.kernel.CapabilityKernel` verschoben (2026-07-25).
     * [CapabilityDenyPack.invalid] ist byte-gleich zu `CapabilityKernel.PHRASE_INVALID`
     * (die Konstante bleibt als DE-Anker + Test-Referenz bestehen).
     */
    val CAPABILITY_DENY = CapabilityDenyPack(
        refusals = listOf(
            "Das mach ich gerade lieber nicht — dafür hab ich keine Freigabe.",
            "Da halt ich mich zurück: das schalte ich nicht einfach so.",
            "Lieber nicht — sowas lass ich bewusst, solange es nicht freigegeben ist.",
            "Das fass ich nicht an. Wenn das wirklich gewollt ist, müssen wir's erst freischalten.",
        ),
        invalid = "Ungültiger domain oder service",
    )
}
