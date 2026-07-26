package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.DateFastpath
import de.hoshi.core.pipeline.EscalationMode
import de.hoshi.core.pipeline.EscalationModeFastpath
import de.hoshi.core.pipeline.EscalationModeSwitchPort
import de.hoshi.core.pipeline.ListFastpath
import de.hoshi.core.port.ListPort
import de.hoshi.core.tools.ListIntent
import de.hoshi.core.tools.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * **EINE Fallback-Regel statt zweier widersprüchlicher** (Scheibe 2026-07-25).
 *
 * Im Code koexistierten zwei Regeln, was bei ES/FR/IT passiert:
 *  - [deOr] ⇒ DE→Deutsch, **alles andere→Englisch** (Deflect, Consent, Prompt,
 *    Fehler-Fallbacks),
 *  - `val en = language == Language.EN` ⇒ EN→Englisch, **ES/FR/IT→DEUTSCH**
 *    (Timer, Datum, Liste, Radio, Rechner, Stufen-Fastpath, Raum-Rückfrage).
 *
 * Ein Spanier bekam also je nach Codepfad mal Englisch, mal Deutsch. Es gibt
 * jetzt genau eine Regel — [fallsBackToEnglish], die BOOLEAN-Form von [deOr]:
 * **nur [Language.DE] bekommt Deutsch, jede andere Sprache Englisch.** Begründung:
 * ein Spanier versteht eher Englisch als Deutsch, und derselbe Zwischenfallback
 * galt für die halbe Codebasis ohnehin schon.
 *
 * DE und EN bleiben dabei byte-identisch — nur ES/FR/IT wechseln.
 *
 * **Nachtrag 2026-07-26:** die vier [EscalationModeFastpath]-Stufen-QUITTUNGEN
 * (Erfolgsfall, `receipt`) sind aus dieser Fallback-Regel wieder RAUS — sie
 * leben jetzt echt fünfsprachig im [LanguagePack] (Andi: „die Sprüche für
 * Hoshi schaut online nach sind schlecht"). Der Fehlerfall (`failure`, Persist
 * schlägt fehl) bleibt unverändert bei [fallsBackToEnglish] — s. „Stufen-
 * Fehlerfall" unten.
 */
class FallbackRuleUnificationTest {

    private val zone = ZoneId.of("Europe/Berlin")

    // ── Die Regel selbst ─────────────────────────────────────────────────────

    @Test
    fun `fallsBackToEnglish ist exakt die BOOLEAN-Form von deOr`() {
        for (language in Language.entries) {
            assertEquals(
                language.deOr(de = false, en = true),
                language.fallsBackToEnglish,
                "$language: die beiden Formen derselben Regel dürfen nie auseinanderlaufen",
            )
        }
    }

    @Test
    fun `nur Deutsch bekommt Deutsch - jede andere Sprache Englisch`() {
        assertEquals(false, Language.DE.fallsBackToEnglish)
        assertEquals(true, Language.EN.fallsBackToEnglish)
        assertEquals(true, Language.ES.fallsBackToEnglish, "ein Spanier versteht eher Englisch als Deutsch")
        assertEquals(true, Language.FR.fallsBackToEnglish)
        assertEquals(true, Language.IT.fallsBackToEnglish)
    }

    // ── Datum: der reinste der betroffenen Bausteine (feste Uhr, kein Port) ──

    @Test
    fun `Datum - DE und EN bleiben byte-identisch`() {
        val fp = DateFastpath(clock = clockAt(2026, 7, 1))
        assertEquals("Heute ist Mittwoch, der 1. Juli 2026.", fp.handle("welcher Tag ist heute?", Language.DE))
        assertEquals("Today is Wednesday, 1 July 2026.", fp.handle("welcher Tag ist heute?", Language.EN))
    }

    @Test
    fun `Datum - ES FR IT bekommen jetzt Englisch statt Deutsch`() {
        val fp = DateFastpath(clock = clockAt(2026, 7, 1))
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            val phrase = fp.handle("welcher Tag ist heute?", language)
            assertEquals("Today is Wednesday, 1 July 2026.", phrase, "$language")
            assertNotEquals("Heute ist Mittwoch, der 1. Juli 2026.", phrase, "$language bekam noch Deutsch")
        }
    }

    @Test
    fun `Uhrzeit - ES FR IT bekommen jetzt die englische 12-Stunden-Form`() {
        val fp = DateFastpath(clock = clockAtTime(2026, 7, 1, 20, 15))
        assertEquals("Es ist 20 Uhr 15.", fp.handle("wie spät ist es?", Language.DE))
        for (language in listOf(Language.EN, Language.ES, Language.FR, Language.IT)) {
            assertEquals("It's 8:15 pm.", fp.handle("wie spät ist es?", language), "$language")
        }
    }

    // ── Einkaufsliste: derselbe Regel-Wechsel über einen Port hinweg ─────────

    @Test
    fun `Liste - DE bleibt byte-identisch, ES FR IT bekommen jetzt Englisch`() {
        val read = ToolCall(domain = ListIntent.DOMAIN, service = ListIntent.READ)
        assertEquals("Die Liste ist leer.", listFastpath().handle(read, Language.DE))
        for (language in listOf(Language.EN, Language.ES, Language.FR, Language.IT)) {
            assertEquals("The list is empty.", listFastpath().handle(read, language), "$language")
        }
    }

    // ── Stufen-Fastpath: die Quittung MIT Stufen-Echo ────────────────────────
    //
    // **Andi-Auftrag 2026-07-26** („die Sprüche für Hoshi schaut online nach sind
    // schlecht"): die vier Stufen-Quittungen sind aus dem [deOr]/[fallsBackToEnglish]-
    // Fallback RAUS und leben jetzt echt fünfsprachig im [LanguagePack] — ES/FR/IT
    // bekommen seither KEIN Englisch mehr, sondern ihre eigene Übersetzung.

    @Test
    fun `Stufen-Quittung - DE und EN bleiben byte-identisch, ES FR IT sprechen jetzt ihre eigene Sprache`() {
        val de = EscalationModeFastpath(AcceptingSwitch()).handle("Geh nicht mehr online.", Language.DE)
        assertEquals("Okay — Online-Nachschauen ist aus. Ich bleib komplett lokal.", de)
        val en = EscalationModeFastpath(AcceptingSwitch()).handle("Geh nicht mehr online.", Language.EN)
        assertEquals("Okay — online lookups are off. I'll stay fully local.", en)
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = EscalationModeFastpath(AcceptingSwitch()).handle("Geh nicht mehr online.", language)
            assertEquals(pack.escalationModeAus, phrase, "$language")
            assertNotEquals("Okay — online lookups are off. I'll stay fully local.", phrase, "$language bekam noch Englisch")
        }
    }

    @Test
    fun `Stufen-Fehlerfall - ES FR IT bekommen jetzt die englische ehrliche Absage`() {
        for (language in listOf(Language.EN, Language.ES, Language.FR, Language.IT)) {
            val phrase = EscalationModeFastpath(RefusingSwitch()).handle("Geh nicht mehr online.", language)
            assertTrue(
                phrase!!.startsWith("I tried to switch that"),
                "$language soll die englische Fehler-Antwort bekommen, war: $phrase",
            )
        }
        assertTrue(
            EscalationModeFastpath(RefusingSwitch())
                .handle("Geh nicht mehr online.", Language.DE)!!
                .startsWith("Das wollte ich gerade umstellen"),
            "DE bleibt byte-identisch",
        )
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private fun clockAt(year: Int, month: Int, day: Int): Clock =
        Clock.fixed(LocalDate.of(year, month, day).atStartOfDay(zone).toInstant(), zone)

    private fun clockAtTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Clock =
        Clock.fixed(LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant(), zone)

    /** Leerer Listen-Store ([ListPort.NONE]) — der Read-Zweig ist der kürzeste Weg an die Sprach-Naht. */
    private fun listFastpath(): ListFastpath =
        ListFastpath(store = ListPort.NONE, clock = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), zone))

    private class AcceptingSwitch : EscalationModeSwitchPort {
        override fun switchTo(mode: EscalationMode): Boolean = true
    }

    private class RefusingSwitch : EscalationModeSwitchPort {
        override fun switchTo(mode: EscalationMode): Boolean = false
    }
}
