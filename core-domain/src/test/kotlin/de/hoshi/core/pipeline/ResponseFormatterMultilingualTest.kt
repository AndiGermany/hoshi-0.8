package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.SmartHomeAction
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LangEn
import de.hoshi.core.pipeline.lang.LangEs
import de.hoshi.core.pipeline.lang.LangFr
import de.hoshi.core.pipeline.lang.LangIt
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **ResponseFormatterMultilingualTest** — NEUE, additive Tests (Andi-Auftrag
 * 2026-07-20, Sprachpaket-Kern). Rührt [ResponseFormatterTest] NICHT an (der
 * bleibt der Byte-Neutralitäts-Beweis für DE); dieser Datei-Beweis:
 *
 *  - die Konversations-Schicht (Cloud-Consent/Abstain-Angebot) folgt der aktiven
 *    [Language] — EN liefert EN-Text, nie DE.
 *  - die Smart-Home-/Timer-Reflexe bleiben IMMER Deutsch, UNABHÄNGIG von der
 *    übergebenen [Language] (Andi-Vorgabe „Reflexe NICHT anfassen").
 */
class ResponseFormatterMultilingualTest {

    private val formatter = ResponseFormatter()

    // ── Konversations-Schicht folgt der Sprache ──────────────────────────────

    @Test
    fun `cloudConsentAsk auf Englisch liefert NUR EN-Pool-Phrasen, nie DE`() {
        repeat(30) {
            val msg = formatter.cloudConsentAsk(Language.EN)
            assertTrue(LangEn.PACK.cloudConsentAsk.contains(msg), "erwartet EN-Pool-Phrase, war: '$msg'")
            assertFalse(LangDe.PACK.cloudConsentAsk.contains(msg), "darf NICHT aus dem DE-Pool kommen: '$msg'")
        }
    }

    @Test
    fun `cloudConsentAccept-Decline-abstainLookupOffer auf Englisch bleiben im EN-Pool`() {
        repeat(20) {
            assertTrue(LangEn.PACK.cloudConsentAccept.contains(formatter.cloudConsentAccept(Language.EN)))
            assertTrue(LangEn.PACK.cloudConsentDecline.contains(formatter.cloudConsentDecline(Language.EN)))
            assertTrue(LangEn.PACK.abstainLookupOffer.contains(formatter.abstainLookupOffer(Language.EN)))
        }
    }

    @Test
    fun `cloudConsentAskExplicit auf Englisch unterscheidet sich vom Deutschen Pool`() {
        repeat(20) {
            val msg = formatter.cloudConsentAskExplicit(Language.EN)
            assertTrue(LangEn.PACK.cloudConsentAskExplicit.contains(msg))
            assertFalse(LangDe.PACK.cloudConsentAskExplicit.contains(msg))
        }
    }

    @Test
    fun `Default-Sprache (kein Argument) bleibt Deutsch - byte-neutral`() {
        repeat(20) {
            assertTrue(LangDe.PACK.cloudConsentAsk.contains(formatter.cloudConsentAsk()))
        }
    }

    @Test
    fun `ES-FR-IT Konversations-Pools liefern die eigene Sprache, nie mehr den EN-Fallback`() {
        for ((language, pack) in mapOf(
            Language.ES to LangEs.PACK,
            Language.FR to LangFr.PACK,
            Language.IT to LangIt.PACK,
        )) {
            repeat(10) {
                val msg = formatter.cloudConsentAsk(language)
                assertTrue(msg.isNotBlank(), "$language darf nie eine leere Phrase liefern")
                assertTrue(pack.cloudConsentAsk.contains(msg), "$language: eigene Pool-Phrase erwartet, war '$msg'")
                assertFalse(LangEn.PACK.cloudConsentAsk.contains(msg), "$language: darf kein EN-Fallback mehr sein: '$msg'")
            }
        }
    }

    // ── Smart-Home-/Timer-Reflexe bleiben IMMER Deutsch ──────────────────────

    @Test
    fun `Smart-Home-Acks bleiben Deutsch, egal welche Sprache uebergeben wird`() {
        for (language in Language.entries) {
            val on = formatter.lightOn("Wohnzimmer", language)
            assertTrue(
                on.contains("Wohnzimmer") && (on.contains("an") || on.contains("hell")),
                "lightOn muss für $language weiter Deutsch sein: '$on'",
            )
            val off = formatter.lightOff("Küche", language)
            assertTrue(off.contains("Küche"), "lightOff muss für $language weiter Deutsch sein: '$off'")
        }
    }

    @Test
    fun `unsupported und noEffect bleiben Deutsch unabhaengig von der Sprache`() {
        for (language in Language.entries) {
            val unsupported = formatter.unsupported(SmartHomeAction.COVER_OPEN, language)
            assertTrue(unsupported.contains("Rollo"), "unsupported muss für $language weiter Deutsch sein: '$unsupported'")
            val noEffect = formatter.noEffect(SmartHomeAction.LIGHT_ON, "Bad", language = language)
            assertTrue(noEffect.lowercase().contains("schon"), "noEffect muss für $language weiter Deutsch sein: '$noEffect'")
        }
    }
}
