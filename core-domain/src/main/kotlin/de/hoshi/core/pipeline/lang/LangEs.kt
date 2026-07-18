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

    val PACK = LanguagePack(
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
        smartHomeNotice = "Órdenes de domótica: por ahora solo en alemán.",
        sayVoiceHint = "Mónica",
    )
}
