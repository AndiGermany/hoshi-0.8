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

    val PACK = LanguagePack(
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
        smartHomeNotice = "Commandes domotiques : uniquement en allemand pour l'instant.",
        sayVoiceHint = "Thomas",
    )
}
