package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.SmartHomeAction
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LangEn
import de.hoshi.core.pipeline.lang.LangEs
import de.hoshi.core.pipeline.lang.LangFr
import de.hoshi.core.pipeline.lang.LangIt
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
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
 *  - **seit 2026-07-25 auch die Smart-Home-Bestätigungen** (Andis Ansage:
 *    „Smart-Home-Bestätigungen -> sowas soll natürlich auch auf englisch […] es
 *    soll multilingual werden. von A-Z"). Die frühere Ausnahme („Reflexe bleiben
 *    IMMER Deutsch") ist AUFGEHOBEN — die zwei Tests, die sie festgeschrieben
 *    hatten, prüfen jetzt das Gegenteil. Der DE-Bestand selbst ist davon
 *    unberührt (s. [ResponseFormatterTest] + [SmartHomeMultilingualTest]).
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

    // ── Smart-Home-Acks folgen der Sprache (Ausnahme aufgehoben, 2026-07-25) ──

    @Test
    fun `Smart-Home-Acks folgen der Sprache - DE bleibt DE, jede andere verlaesst den DE-Pool`() {
        for (language in Language.entries) {
            val dePool = LangDe.SMART_HOME_ACKS.lightOnRoom
            val ownPool = LanguagePackRegistry.forLanguage(language).smartHomeAcks.lightOnRoom
            repeat(10) {
                val on = formatter.lightOn("Wohnzimmer", language)
                // Der Raumname reist IMMER mit — auch im fremdsprachigen Satz.
                assertTrue(on.contains("Wohnzimmer"), "$language: Raumname muss erhalten bleiben: '$on'")
                val template = on.replace("Wohnzimmer", "{room}")
                assertTrue(ownPool.contains(template), "$language: eigene Pool-Phrase erwartet, war '$on'")
                if (language != Language.DE) {
                    assertFalse(dePool.contains(template), "$language: darf nicht mehr aus dem DE-Pool kommen: '$on'")
                }
            }
        }
    }

    @Test
    fun `unsupported und noEffect folgen der Sprache statt immer Deutsch zu sein`() {
        // DE: der Bestandswortlaut steht unverändert.
        assertTrue(formatter.unsupported(SmartHomeAction.COVER_OPEN, Language.DE).contains("Rollo"))
        assertTrue(formatter.noEffect(SmartHomeAction.LIGHT_ON, "Bad", language = Language.DE).lowercase().contains("schon"))

        for (language in Language.entries - Language.DE) {
            val pack = LanguagePackRegistry.forLanguage(language).smartHomeAcks
            repeat(10) {
                val unsupported = formatter.unsupported(SmartHomeAction.COVER_OPEN, language)
                assertTrue(
                    pack.unsupportedCover.contains(unsupported),
                    "$language: unsupported muss aus dem eigenen Pool kommen, war '$unsupported'",
                )
                assertFalse(
                    LangDe.SMART_HOME_ACKS.unsupportedCover.contains(unsupported),
                    "$language: kein DE-Text mehr: '$unsupported'",
                )
                val noEffect = formatter.noEffect(SmartHomeAction.LIGHT_ON, "Bad", language = language)
                assertTrue(noEffect.contains("Bad"), "$language: Raumname muss erhalten bleiben: '$noEffect'")
                assertFalse(
                    noEffect.lowercase().contains("schon"),
                    "$language: 'schon' ist deutsch — der Satz darf nicht mehr aus dem DE-Pool kommen: '$noEffect'",
                )
            }
        }
    }
}
