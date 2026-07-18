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

    val PACK = LanguagePack(
        language = Language.DE,
        cloudConsentAsk = listOf(
            "Hmm, das müsste ich kurz online nachschauen — soll ich?",
            "Da bin ich mir nicht sicher. Darf ich kurz nachsehen?",
            "Genau das weiß ich nicht. Online schauen okay?",
            "Lass mich kurz online checken — passt das?",
            "Da würde ich kurz das Internet bemühen. Mach ich das?",
            "Online weiß ich's vermutlich. Soll ich?",
        ),
        cloudConsentAskExplicit = listOf(
            "Klar, dafür geh ich kurz online — kurz dein Okay?",
            "Mach ich, dann schau ich kurz raus für dich — passt das?",
            "Gern, das hol ich von draußen — sag kurz Ja, dann leg ich los.",
        ),
        cloudConsentAccept = listOf(
            "Klar, einen Moment — ich frag schnell.",
            "Geht klar, kurz schauen…",
            "Mache ich. Moment.",
            "Okay, einen Augenblick.",
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
    )

    /**
     * Die Smart-Home-/Timer-Reflex-Ack-Pools — WORT-FÜR-WORT aus dem bisherigen
     * [de.hoshi.core.pipeline.ResponseFormatter] verschoben. Bleiben IMMER Deutsch
     * (Andi-Vorgabe), unabhängig von der aktiven Konversationssprache.
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
    )
}
