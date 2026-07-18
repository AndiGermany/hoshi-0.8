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

    val PACK = LanguagePack(
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
        smartHomeNotice = "Comandi smart-home: per ora solo in tedesco.",
        sayVoiceHint = "Alice",
    )
}
