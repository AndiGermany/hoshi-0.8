package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.pipeline.lang.SCORE_PLACEHOLDER
import de.hoshi.core.port.DailyNote
import de.hoshi.core.port.DailyNotePort
import de.hoshi.core.port.WorkshopNote
import de.hoshi.core.port.WorkshopNotePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * **Die Never-Silent-Ränder und die brain-freien Quittungen sprechen die
 * Turn-Sprache** (Andi 2026-07-25: „Multilingualität von A-Z"). Diese Sätze
 * fallen genau dann, wenn ohnehin schon etwas schiefging (leere Eingabe, Fehler
 * vor dem ersten Text) — sie dürfen erst recht nicht auch noch in der falschen
 * Sprache fallen.
 *
 * DE bleibt überall byte-identisch; die Bestands-Konstanten sind jetzt der
 * DE-Zeiger auf die EINE Sprachpaket-Quelle und werden hier wörtlich gepinnt.
 */
class NeverSilentLanguageTest {

    // ── DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Never-Silent-Phrase ist BYTE-IDENTISCH zum bisherigen Bestand`() {
        assertEquals(
            "Hab dich gehört, aber bei mir hakt's grad kurz. Sag's gleich nochmal?",
            TurnOrchestrator.DEFAULT_FALLBACK,
        )
        assertEquals(TurnOrchestrator.DEFAULT_FALLBACK, TurnOrchestrator.defaultFallback(Language.DE))
    }

    @Test
    fun `DE-Fastpath-Quittungen sind BYTE-IDENTISCH zum bisherigen Bestand`() {
        assertEquals("Notiert: heute eine $SCORE_PLACEHOLDER. Danke dir!", LangDe.PACK.dailyNoteRecorded)
        assertEquals("Aktualisiert: heute eine $SCORE_PLACEHOLDER. Danke dir!", LangDe.PACK.dailyNoteUpdated)
        assertEquals("Notiert für die Werkstatt. Danke dir!", WorkshopNoteFastpath.RECEIPT)
        assertEquals("Ich hör dich klar und deutlich — Ohren, Draht und Stimme stehen.", ProbeFastpath.RECEIPT)
        assertEquals(
            "Notiert: heute eine 4. Danke dir!",
            DailyNoteFastpath(RecordingNotes(), clock = fixedClock()).handle("Tagesnote 4", "chat"),
        )
    }

    // ── Never-Silent-Phrase je Sprache ───────────────────────────────────────

    @Test
    fun `Never-Silent-Phrase kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).warmFallback
            assertEquals(expected, TurnOrchestrator.defaultFallback(language), "$language")
            assertFalse(expected.isBlank(), "$language: nie leer (Never-Silent-Vertrag)")
        }
    }

    @Test
    fun `keine Nicht-DE-Sprache bekommt mehr die deutsche Never-Silent-Phrase`() {
        for (language in Language.entries - Language.DE) {
            assertFalse(
                TurnOrchestrator.defaultFallback(language) == TurnOrchestrator.DEFAULT_FALLBACK,
                "$language sprach noch deutsch",
            )
        }
    }

    // ── Probe-Selbsttest je Sprache ──────────────────────────────────────────

    @Test
    fun `Probe-Quittung kommt in jeder Sprache aus dem eigenen Pack`() {
        val fp = ProbeFastpath()
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).probeReceipt
            assertEquals(expected, fp.handle("Hoshi, Probe.", language), "$language")
        }
        assertEquals(ProbeFastpath.RECEIPT, fp.handle("Probe"), "ohne Sprach-Argument bleibt es deutsch")
    }

    // ── Werkstatt-Notiz je Sprache ───────────────────────────────────────────

    @Test
    fun `Werkstatt-Quittung kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val store = RecordingWorkshop()
            val fp = WorkshopNoteFastpath(store, clock = fixedClock())
            val expected = LanguagePackRegistry.forLanguage(language).workshopNoteRecorded
            assertEquals(expected, fp.handle("Notiz an die Werkstatt: Timer zu laut", "andi", language), "$language")
            assertEquals(1, store.notes.size, "$language: die Notiz landet trotzdem im Briefkasten")
        }
    }

    // ── Tagesnote je Sprache — die ZAHL bleibt unübersetzt ───────────────────

    @Test
    fun `Tagesnoten-Quittung kommt in jeder Sprache aus dem eigenen Pack, die Note bleibt eine Zahl`() {
        for (language in Language.entries) {
            val fp = DailyNoteFastpath(RecordingNotes(), clock = fixedClock())
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = fp.handle("Tagesnote 4", "chat", language)
            assertEquals(pack.dailyNoteRecorded.replace(SCORE_PLACEHOLDER, "4"), phrase, "$language")
            assertTrue(phrase!!.contains("4"), "$language: die Note ist eine Zahl und wird nie übersetzt")
            assertFalse(phrase.contains(SCORE_PLACEHOLDER), "$language: der Platzhalter muss ersetzt sein")
        }
    }

    @Test
    fun `zweite Tagesnote am selben Tag quittiert in jeder Sprache ehrlich Aktualisiert`() {
        for (language in Language.entries) {
            val fp = DailyNoteFastpath(RecordingNotes(), clock = fixedClock())
            val pack = LanguagePackRegistry.forLanguage(language)
            fp.handle("Tagesnote 4", "chat", language)
            val second = fp.handle("Tagesnote 3", "chat", language)
            assertEquals(pack.dailyNoteUpdated.replace(SCORE_PLACEHOLDER, "3"), second, "$language")
        }
    }

    @Test
    fun `jede Sprache traegt einen eigenen Platzhalter-Satz fuer die Tagesnote`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertTrue(pack.dailyNoteRecorded.contains(SCORE_PLACEHOLDER), "$language: recorded ohne Platzhalter")
            assertTrue(pack.dailyNoteUpdated.contains(SCORE_PLACEHOLDER), "$language: updated ohne Platzhalter")
        }
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-25T10:15:00Z"), ZoneId.of("Europe/Berlin"))

    /** Erste Note des Tages ⇒ false, jede weitere ⇒ true (Überschreib-Vertrag des Ports). */
    private class RecordingNotes : DailyNotePort {
        private var seen = false
        override fun record(note: DailyNote): Boolean {
            val replaced = seen
            seen = true
            return replaced
        }
    }

    private class RecordingWorkshop : WorkshopNotePort {
        val notes = mutableListOf<WorkshopNote>()
        override fun record(note: WorkshopNote) {
            notes += note
        }
    }
}
