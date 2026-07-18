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

    val PACK = LanguagePack(
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
        intentPatterns = IntentPatternNotes(
            lookupVerbs = listOf("look", "search", "check", "research"),
            lookupScope = listOf("online", "internet", "net", "web"),
            consentWords = listOf("yes", "sure", "okay", "please", "please do"),
            researchMarkers = listOf("research"),
            status = "aktiv (geteilt mit DE in denselben Recognizer-Objekten)",
        ),
        promptLanguageInstruction = "Always answer in English.",
        smartHomeNotice = "Smart-home commands: German only for now.",
        sayVoiceHint = "Samantha",
    )
}
