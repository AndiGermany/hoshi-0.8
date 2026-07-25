package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language

/**
 * **Englisches Sprachpaket — Tier 1** (Andi-Auftrag 2026-07-20). Von Hand mit Ohr
 * übersetzt (Wärme-Anspruch wie DE, keine Roboter-Sätze) — kein wörtliches
 * Wort-für-Wort, sondern derselbe Ton in derselben Situation. Die bestehenden
 * EN-Abstain-/Consent-Marker (s. `LookupIntentRecognizer`/`ConsentRecognizer`/
 * `BrainAbstainRecognizer`) sind hier unter [LanguagePack.intentPatterns]
 * dokumentarisch einsortiert.
 *
 * Übersetzer-Pod-Regel: diese Datei ist NUR für Englisch — Änderungen an anderen
 * Sprachen gehören in [LangDe]/[LangEs]/[LangFr]/[LangIt].
 */
object LangEn {

    /** `by lazy` — s. [LangDe.PACK]: [PACK] referenziert weiter unten deklarierte Felder. */
    val PACK: LanguagePack by lazy {
        LanguagePack(
        language = Language.EN,
        cloudConsentAsk = listOf(
            "Hmm, I'd need to look that up online real quick — want me to?",
            "I'm not sure about that one. Mind if I check?",
            "That I genuinely don't know. Okay if I look online?",
            "Let me check online real quick — that work for you?",
            "I'd have to ask the internet for that one. Should I?",
            "Online I'd probably know. Want me to?",
        ),
        cloudConsentAskExplicit = listOf(
            "Sure, I'll hop online for that — quick okay from you?",
            "On it — I'll check outside real quick, that work?",
            "Happy to grab that from out there — just say the word and I'm on it.",
        ),
        cloudConsentAccept = listOf(
            "Sure, one sec — let me ask.",
            "On it, checking quick…",
            "Doing it. One sec.",
            "Okay, give me a moment.",
        ),
        cloudConsentDecline = listOf(
            "Okay, I'll tell you what I know myself.",
            "Alright, sticking with what I've got.",
            "Got it — here's how I see it.",
            "Fine, working with what I have then.",
        ),
        abstainLookupOffer = listOf(
            " — Want me to check that online?",
            " — Should I look that up for you online?",
            " — Want me to check that online for you?",
        ),
        // ── Ehrlichkeit: was Hoshi sagt, wenn er NICHT weiterweiß. Haltung statt
        //    Defekt — nie „I can't", nie Engine-Sprech, immer ein Angebot.
        honestyOnlineRequestRefusals = listOf(
            "I stay off the open internet on purpose — I'd rather stay right here with you. But I'll gladly dig through what I know myself: what exactly are you after?",
            "Out onto the internet? Not something I want — I've got a whole store of knowledge sitting right here. Want me to look in there for you?",
            "I'm deliberately not out on the web. What I do have is my own knowledge — tell me what you're after and I'll look.",
            "I keep the internet closed on purpose — but I've got plenty saved myself. Let me look it up in there for you, okay?",
            "I don't head outside, and that's on purpose. In what I know myself I'll gladly find something for you — what do you need?",
        ),
        honestyRecipeRefusals = listOf(
            "Cooking really isn't my strong suit — I'd only lead you astray.",
            "With a recipe I'd be guessing, and that would be no help to you.",
        ),
        honestyExistenceRefusals = listOf(
            "Hold on — I'm not sure that's actually a thing. I'd rather not make something up for you.",
            "Good question — with something like that I don't like trusting my gut. I'd rather say it straight: I don't know.",
            "Honestly? That one's beyond me. I'd want to look it up rather than guess.",
        ),
        honestyNamedEntityRefusals = listOf(
            "Hmm, that name doesn't ring a bell right now. Sounds like someone from a particular scene — who exactly is that?",
            "Tell me more — music, film, history, sport? With that name I'm completely in the dark.",
            "I don't know that name — want to tell me a bit about them?",
            "Honestly, I've got nothing on that one — where do you know the name from?",
        ),
        honestyBridgeDownRefusals = listOf(
            "I can't reach my knowledge store right now — I'll only be able to tell you reliably in a moment. Want to ask me again shortly?",
            "Hm, my reference shelf isn't reachable at the moment. I don't want to guess at you — ask me again in a bit and I'll look it up properly.",
            "I'm stuck for a second here — my knowledge store isn't answering. Give me a moment, then I can tell you honestly.",
        ),
        // ── Never-Silent-Ränder + Fastpath-Quittungen ────────────────────────
        warmFallback = "I heard you, but something's snagging on my end for a second. Say it again in a moment?",
        audioCapTooLong = "That was a bit too long in one go — say it a little shorter and I'll catch it reliably.",
        audioNoEndSignal = "The recording ran on too long without an end signal — want to try again a bit shorter?",
        admissionBusy = "I'm on another request right now — give me a short moment and ask me again.",
        dailyNoteRecorded = "Noted: a $SCORE_PLACEHOLDER for today. Thank you!",
        dailyNoteUpdated = "Updated: a $SCORE_PLACEHOLDER for today. Thank you!",
        workshopNoteRecorded = "Noted for the workshop. Thank you!",
        probeReceipt = "I hear you loud and clear — ears, wire and voice are all up.",
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf("look", "search", "check", "research"),
            lookupScope = listOf("online", "internet", "net", "web"),
            consentWords = listOf("yes", "sure", "okay", "please", "please do"),
            researchMarkers = listOf("research"),
            status = "aktiv (geteilt mit DE in denselben Recognizer-Objekten)",
        ),
        promptLanguageInstruction = "Always answer in English.",
        // Gedreht 2026-07-25: die Bestätigungen sprechen jetzt Englisch — nur das
        // VERSTEHEN der Befehle ist weiterhin auf DE+EN begrenzt. Genau das sagt
        // der Hinweis (und nur das).
        smartHomeNotice = "Smart-home replies speak English now — commands are still understood in German or English only.",
        sayVoiceHint = "Samantha",
        // Handverifiziert + lizenzgeprueft, s. sidecars/piper/artifacts.lock.json
        // (Andi-Wunsch fuers Build-Week-Video: "de -> en, piper spricht englisch mit").
        piperVoiceHint = "en_US-kristin-medium",
        smartHomeAcks = SMART_HOME_ACKS,
        haExecutor = HA_EXECUTOR,
        capabilityDeny = CAPABILITY_DENY,
        )
    }

    /**
     * **Smart-Home-Bestätigungen auf Englisch** (Andi 2026-07-25: „sowas soll
     * natürlich auch auf englisch … von A-Z"). Fürs OHR übersetzt, nicht fürs Auge:
     * kurz, warm, zustands-eindeutig — dieselbe Situation, derselbe Ton, nicht
     * dieselben Wörter.
     *
     * `{room}` bleibt IMMER unübersetzt: der Raumname kommt aus Home Assistant
     * (Nutzerdaten). „The light in Wohnzimmer is on." ist gewollt — Hoshi erfindet
     * keine englischen Raumnamen für Andis Wohnung.
     */
    val SMART_HOME_ACKS = SmartHomeAckPack(
        lightOnRoom = listOf(
            "{room} is on.",
            "{room} is bright.",
            "On it — {room} is on.",
        ),
        lightOffRoom = listOf(
            "{room} is off.",
            "{room} is dark.",
            "On it — {room} is off.",
        ),
        lightDimRoom = listOf(
            "{room} at {value} percent.",
            "On it — {room} at {value} percent.",
        ),
        lightDimNoRoom = listOf(
            "At {value} percent.",
            "On it — {value} percent.",
        ),
        scene = listOf(
            "On it.",
            "All set.",
        ),
        coverOpen = listOf(
            "It's open.",
            "On it — it's open.",
        ),
        coverClose = listOf(
            "It's closed.",
            "On it — it's closed.",
        ),
        climateRoom = listOf(
            "{room} at {value} degrees.",
            "On it — {room} at {value} degrees.",
        ),
        unknown = listOf(
            "On it.",
            "Done.",
            "You got it.",
        ),
        lightOffNoEffectRoom = listOf(
            "{room} was already dark.",
            "Everything's already off in {room}.",
            "The light in {room} was already off.",
        ),
        lightOffNoEffectNoRoom = listOf(
            "It was already off.",
            "The light was already dark.",
            "Everything was already off there.",
        ),
        lightOnNoEffectRoom = listOf(
            "{room} was already bright.",
            "There's already light on in {room}.",
            "The light in {room} was already on.",
        ),
        lightOnNoEffectNoRoom = listOf(
            "It was already on.",
            "The light was already bright.",
            "There was already light on.",
        ),
        lightDimNoEffectRoom = listOf(
            "{room} is already at about {value} percent.",
            "It's already about that bright in {room} — {value} percent.",
            "It was already around {value} percent in {room}.",
        ),
        lightDimNoEffectNoRoom = listOf(
            "It's already at about {value} percent.",
            "It was already around {value} percent.",
        ),
        coverOpenNoEffect = listOf(
            "It was already open.",
            "That was already open.",
        ),
        coverCloseNoEffect = listOf(
            "It was already closed.",
            "That was already closed.",
        ),
        climateNoEffectRoom = listOf(
            "{room} is already set to {value} degrees.",
            "That was already set to {value} degrees in {room}.",
        ),
        climateNoEffectNoRoom = listOf(
            "That was already at {value} degrees.",
            "It was already set to {value} degrees.",
        ),
        genericNoEffect = listOf(
            "Everything was already set that way.",
            "That was already the case.",
            "Nothing changed — it was already like that.",
        ),
        // „meldet sich grad nicht" statt „offline": auch im Englischen bleibt die
        // Quittung menschlich, kein Geräte-Status-Bericht.
        lightOnPartialOfflineOne = listOf(
            "There's light in {room} — one lamp isn't answering right now, the rest are lit.",
            "Got it, the rest of {room} is on. One I can't reach at the moment.",
            "{applied} are on in {room}, one is quiet right now.",
        ),
        lightOnPartialOfflineMany = listOf(
            "There's light in {room} — {offline} lamps aren't answering right now, the rest are lit.",
            "The rest of {room} is on. {offline} I can't reach at the moment.",
            "{applied} are on in {room}, {offline} are quiet right now.",
        ),
        lightOffPartialOfflineOne = listOf(
            "It's dark in {room} — one lamp isn't answering right now, the rest are off.",
            "Off in {room}, except for one that isn't answering right now.",
            "{applied} are off in {room}, one is quiet right now.",
        ),
        lightOffPartialOfflineMany = listOf(
            "It's dark in {room} — {offline} lamps aren't answering right now, the rest are off.",
            "Off in {room}, except for {offline} that aren't answering right now.",
            "{applied} are off in {room}, {offline} are quiet right now.",
        ),
        partialOfflineNoRoom = listOf(
            "A few lamps aren't answering right now, the rest reacted.",
            "Got it — some of them aren't answering at the moment.",
        ),
        unsupportedCover = listOf(
            "I can't find a blind like that at your place.",
            "I don't have a blind here I could move.",
            "There's no shade there I could work.",
        ),
        unsupportedClimate = listOf(
            "I couldn't find any heating I can control for that.",
            "There's no heating there I can adjust.",
            "I can't find a thermostat like that at your place.",
        ),
        unsupportedScene = listOf(
            "I don't know that scene in your setup.",
            "I don't have a scene like that here.",
            "I can't find that mood at your place.",
        ),
        unsupportedGeneric = listOf(
            "I can't switch that at your place right now — I don't know a device like that.",
            "I didn't find anything like that in your home.",
            "That device I don't know here at all.",
        ),
        lightOnNoRoom = listOf("Light is on."),
        lightOffNoRoom = listOf("Light is off."),
        lightDimNoValue = listOf("Dimmed."),
        climateValueNoRoom = listOf("Set to {value} degrees."),
        climateNoValue = listOf("All set."),
        lightColorNamed = listOf("Color is {color}."),
        lightColorUnnamed = listOf("Color changed."),
    )

    /**
     * **HA-Executor-Quittungen auf Englisch.** Die Abstufung des Honesty-Charters
     * ist hier die eigentliche Übersetzungsaufgabe: „sent" (angekommen) ≠ "is on"
     * (gemessen) ≠ "was already on" (kein Effekt). Wer das einebnet, macht aus
     * Ehrlichkeit eine Behauptung.
     */
    val HA_EXECUTOR = HaExecutorPack(
        noToken = "Honestly: I don't have an HA token configured right now, so I haven't switched anything.",
        noTokenTemperature =
            "Honestly: I don't have an HA token configured right now, so I can't get to the temperature.",
        noThermostatInArea = "I don't know of any thermostat in {room}.",
        lightOffArea = "The light in {room} is off.",
        lightSomeStillOn = "A few lamps in {room} are still on — they may not have reacted.",
        offlineHintCount = " — {count} lamps aren't reachable right now (maybe switched off at the wall).",
        offlineHintVague = " — maybe the lamps are offline.",
        noLightsInArea = "I didn't find any lamps at all in {room}.",
        lightOnArea = "The light in {room} is on.",
        lightAlreadyOnArea = "The light in {room} is already on.",
        lightNothingNewOn = "There was already light on in {room}, but nothing new came on",
        lightNoneWentOn = "I sent it to Home Assistant, but no light came on in {room}",
        climateSetArea = "Heating in {room} at {value} degrees.",
        climateNotYet = "I sent it, the heating hasn't reacted yet.",
        sentToArea = "Done — I sent it to the devices in {room}.",
        sentToHome = "Done — I sent it to Home Assistant.",
        failed = "That didn't work — Home Assistant isn't responding right now.",
        noValue = "I don't have a value for that right now.",
        temperatureInArea = "It's {value} degrees in {room} right now.",
        temperatureHouseAverage = "It's {value} degrees on average in the house right now.",
        temperatureUnavailable = "I can't get to the temperature right now — try again in a moment.",
        // Englisch spricht den Dezimalpunkt: „twenty-one point five degrees".
        decimalSeparator = ".",
    )

    /** **Tat-Verweigerung auf Englisch** — Haltung, kein Fehler. Nie „permission denied". */
    val CAPABILITY_DENY = CapabilityDenyPack(
        refusals = listOf(
            "I'd rather not do that right now — I don't have clearance for it.",
            "I'm holding back on that one: I don't just switch things like that.",
            "Better not — I leave that alone on purpose until it's been approved.",
            "I'm not touching that. If it's really what you want, we have to unlock it first.",
        ),
        invalid = "Invalid domain or service",
    )
}
