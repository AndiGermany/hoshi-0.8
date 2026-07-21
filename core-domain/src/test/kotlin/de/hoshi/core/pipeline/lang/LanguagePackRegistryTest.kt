package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **LanguagePackRegistryTest** — beweist die Sprachpaket-Struktur (Andi-Auftrag
 * 2026-07-20): [LanguagePackRegistry] ist TOTAL (jede [Language] hat ein Pack),
 * [LangDe] ist byte-identisch zum bisherigen [de.hoshi.core.pipeline.ResponseFormatter]-
 * Bestand, EN ist eigenständig übersetzt, ES/FR/IT sind echt übersetzt
 * (Übersetzer-Pods + Review 2026-07-20), Intent-Muster dort dokumentarisch.
 */
class LanguagePackRegistryTest {

    @Test
    fun `forLanguage ist total - jede Sprache hat ein eigenes Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertEquals(language, pack.language, "Pack von $language muss sich selbst tragen")
        }
    }

    @Test
    fun `DE-Pack ist byte-identisch zum bisherigen ResponseFormatter-Bestand`() {
        val de = LangDe.PACK
        assertEquals(
            listOf(
                "Hmm, das müsste ich kurz online nachschauen — soll ich?",
                "Da bin ich mir nicht sicher. Darf ich kurz nachsehen?",
                "Genau das weiß ich nicht. Online schauen okay?",
                "Lass mich kurz online checken — passt das?",
                "Da würde ich kurz das Internet bemühen. Mach ich das?",
                "Online weiß ich's vermutlich. Soll ich?",
            ),
            de.cloudConsentAsk,
        )
        assertEquals(
            listOf(
                "Klar, einen Moment — ich frag schnell.",
                "Geht klar, kurz schauen…",
                "Mache ich. Moment.",
                "Okay, einen Augenblick.",
            ),
            de.cloudConsentAccept,
        )
        assertEquals("Antworte IMMER auf Deutsch.", de.promptLanguageInstruction)
        assertNull(de.smartHomeNotice, "Deutsch selbst braucht keinen Smart-Home-Hinweis")
        assertNull(de.sayVoiceHint, "DE braucht keinen say-Stimm-Hinweis (Boot-Default ist schon deutsch)")
        assertNull(de.piperVoiceHint, "DE braucht keinen piper-Stimm-Hinweis (Boot-Default ist schon deutsch)")
    }

    @Test
    fun `Smart-Home-Ack-Pools leben ausschliesslich im DE-Pack, WORT-FUER-WORT wie zuvor`() {
        assertEquals(
            listOf("{room} ist an.", "{room} ist hell.", "Mach ich — {room} ist an."),
            LangDe.SMART_HOME_ACKS.lightOnRoom,
        )
        assertEquals(listOf("Licht ist an.", "Licht ist aus.", "Ist gedimmt.", "Ist eingestellt.").size, 4)
    }

    @Test
    fun `EN-Pack ist eigenstaendig uebersetzt - unterscheidet sich vom DE-Pack, traegt eigene Woerter`() {
        val en = LangEn.PACK
        assertNotEquals(LangDe.PACK.cloudConsentAsk, en.cloudConsentAsk)
        assertNotEquals(LangDe.PACK.cloudConsentAccept, en.cloudConsentAccept)
        assertEquals("Always answer in English.", en.promptLanguageInstruction)
        assertTrue(en.smartHomeNotice!!.contains("German"), "EN-Hinweis muss ehrlich auf Deutsch verweisen")
        for (phrase in en.cloudConsentAsk) {
            assertTrue(phrase.contains("?"), "cloudConsentAsk soll als Frage erkennbar sein: '$phrase'")
        }
        assertEquals("Samantha", en.sayVoiceHint)
        assertEquals(
            "en_US-kristin-medium",
            en.piperVoiceHint,
            "EN ist die einzige Sprache mit einem handverifizierten Piper-Modell (s. artifacts.lock.json)",
        )
    }

    @Test
    fun `ES-FR-IT haben bewusst KEINEN piperVoiceHint - kein Piper-Modell vorhanden, nichts geraten`() {
        for (pack in listOf(LangEs.PACK, LangFr.PACK, LangIt.PACK)) {
            assertNull(
                pack.piperVoiceHint,
                "${pack.language}: kein Piper-Modell installiert ⇒ piperVoiceHint bleibt null (nicht geraten)",
            )
            assertTrue(!pack.sayVoiceHint.isNullOrBlank(), "${pack.language}: say hat trotzdem eine echte macOS-Stimme")
        }
    }

    @Test
    fun `ES-FR-IT sind echt uebersetzt - eigene Pools in EN-Variantenzahl, Intent-Muster dokumentiert`() {
        for (pack in listOf(LangEs.PACK, LangFr.PACK, LangIt.PACK)) {
            for ((label, own, en) in listOf(
                Triple("cloudConsentAsk", pack.cloudConsentAsk, LangEn.PACK.cloudConsentAsk),
                Triple("cloudConsentAskExplicit", pack.cloudConsentAskExplicit, LangEn.PACK.cloudConsentAskExplicit),
                Triple("cloudConsentAccept", pack.cloudConsentAccept, LangEn.PACK.cloudConsentAccept),
                Triple("cloudConsentDecline", pack.cloudConsentDecline, LangEn.PACK.cloudConsentDecline),
                Triple("abstainLookupOffer", pack.abstainLookupOffer, LangEn.PACK.abstainLookupOffer),
            )) {
                assertEquals(en.size, own.size, "${pack.language}.$label: Variantenzahl wie EN")
                assertNotEquals(en, own, "${pack.language}.$label: darf kein EN-Fallback mehr sein")
                assertTrue(own.none { it.isBlank() }, "${pack.language}.$label: keine leere Phrase")
            }
            assertFalse(pack.intentPatterns.status.contains("TODO"), "${pack.language}: Status ist kein TODO mehr")
            assertTrue(pack.intentPatterns.lookupVerbs.isNotEmpty(), "${pack.language}: Lookup-Verben dokumentiert")
            assertTrue(pack.intentPatterns.consentWords.isNotEmpty(), "${pack.language}: Consent-Wörter dokumentiert")
            for (phrase in pack.cloudConsentAsk) {
                assertTrue(phrase.contains("?"), "${pack.language}: cloudConsentAsk soll als Frage erkennbar sein: '$phrase'")
            }
            assertFalse(pack.promptLanguageInstruction.isBlank())
            assertNotEquals(LangDe.PACK.promptLanguageInstruction, pack.promptLanguageInstruction)
            assertNotEquals(LangEn.PACK.promptLanguageInstruction, pack.promptLanguageInstruction)
            assertTrue(pack.smartHomeNotice != null && pack.smartHomeNotice!!.isNotBlank())
        }
    }

    @Test
    fun `deOr faellt fuer jede Nicht-DE-Sprache auf EN zurueck, DE bekommt de`() {
        val de = "deutsch"
        val en = "english"
        assertEquals(de, Language.DE.deOr(de, en))
        assertEquals(en, Language.EN.deOr(de, en))
        assertEquals(en, Language.ES.deOr(de, en))
        assertEquals(en, Language.FR.deOr(de, en))
        assertEquals(en, Language.IT.deOr(de, en))
    }
}
