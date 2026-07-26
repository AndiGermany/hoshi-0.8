package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Die fünf „online nachschauen"-Kategorien sprechen wirklich fünf Sprachen**
 * (Andi-Auftrag 2026-07-26: „die Sprüche für Hoshi schaut online nach sind
 * schlecht — niemand würde das so sagen"; Inventur + LanguagePack-Umzug +
 * Streuungs-Nachtrag „nicht immer die gleiche Antwort").
 *
 * Vorher fielen genau FÜNF Kategorien für ES/FR/IT auf Englisch zurück, weil
 * sie hart im [FactCoverageGate]/[TurnOrchestrator]/[EscalationModeFastpath]
 * verdrahtet waren statt im [de.hoshi.core.pipeline.lang.LanguagePack] zu
 * leben:
 *
 *  1. **Deflect** ([FactCoverageGate.deflection]) — seit dem Streuungs-Nachtrag
 *     ein 3-4er-Pool je Sprache, ausgewählt über [AntiRepeatPicker].
 *  2. **Ergebnis-Vorspann** ([TurnOrchestrator.escalationAnswerFrame]) —
 *     ebenfalls ein Pool.
 *  3. **Quellen-Zeile** ([TurnOrchestrator.escalationSourceNote]) — bewusst
 *     KEIN Pool (Kennzeichnung soll gleich klingen).
 *  4. **Fehlerfall „unavailable"** ([TurnOrchestrator.escalationUnavailable])
 *     — bewusst KEIN Pool.
 *  5. **Die vier Stufen-Quittungen** ([EscalationModeFastpath]) — bewusst KEIN
 *     Pool (Wiedererkennbarkeit ist hier ein Feature).
 *
 * Nach dem Muster von [HonestyGateLanguageTest]: jede Kategorie bekommt hier
 * einen Beweis pro Sprache — insbesondere, dass ES/FR/IT NIE mehr die
 * englische Phrase sprechen, und dass die beiden gepoolten Kategorien wirklich
 * streuen (mehrere Aufrufe ⇒ mehrere verschiedene Varianten).
 */
class EscalationLanguageTest {

    private val fastpath = EscalationModeFastpath(object : EscalationModeSwitchPort {
        override fun switchTo(mode: EscalationMode): Boolean = true
    })

    // ── (1) Deflect ───────────────────────────────────────────────────────────

    @Test
    fun `Deflect kommt in jeder Sprache aus dem eigenen Pool`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertTrue(
                FactCoverageGate.deflection(language) in pack.factCoverageDeflect,
                "$language: Deflect-Auswahl muss aus dem eigenen Pool kommen",
            )
            assertTrue(FactCoverageGate.deflection(language).isNotBlank(), "$language: Deflect darf nicht leer sein")
        }
    }

    @Test
    fun `ES-FR-IT bekommen beim Deflect nie eine englische Phrase, und der Pool streut wirklich`() {
        val enPool = LanguagePackRegistry.forLanguage(Language.EN).factCoverageDeflect
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            val seen = (1..60).map { FactCoverageGate.deflection(language) }.toSet()
            for (phrase in seen) {
                assertFalse(phrase in enPool, "$language sprach noch Englisch (Deflect): '$phrase'")
            }
            assertTrue(seen.size >= 2, "$language: Deflect-Pool streut nicht (nur ${seen.size} distinct in 60 Aufrufen)")
        }
    }

    // ── (2) Ergebnis-Vorspann ────────────────────────────────────────────────

    @Test
    fun `Ergebnis-Vorspann kommt in jeder Sprache aus dem eigenen Pool`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertTrue(
                TurnOrchestrator.escalationAnswerFrame(language) in pack.escalationAnswerFrame,
                "$language: Vorspann-Auswahl muss aus dem eigenen Pool kommen",
            )
        }
    }

    @Test
    fun `ES-FR-IT bekommen beim Ergebnis-Vorspann nie eine englische Rahmung, und der Pool streut wirklich`() {
        val enPool = LanguagePackRegistry.forLanguage(Language.EN).escalationAnswerFrame
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            val seen = (1..60).map { TurnOrchestrator.escalationAnswerFrame(language) }.toSet()
            for (phrase in seen) {
                assertFalse(phrase in enPool, "$language sprach noch Englisch (Ergebnis-Vorspann): '$phrase'")
            }
            assertTrue(
                seen.size >= 2,
                "$language: Vorspann-Pool streut nicht (nur ${seen.size} distinct in 60 Aufrufen)",
            )
        }
    }

    /** Harte Regel: JEDE Vorspann-Variante trägt das Herkunfts-Label (Netz/online/Internet). */
    @Test
    fun `jede Ergebnis-Vorspann-Variante traegt in jeder Sprache das Herkunfts-Label`() {
        val labelWords = mapOf(
            Language.DE to listOf("netz", "internet", "online"),
            Language.EN to listOf("online", "internet", "web"),
            Language.ES to listOf("internet", "línea", "online"),
            Language.FR to listOf("ligne", "internet", "online"),
            Language.IT to listOf("online", "internet"),
        )
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val words = labelWords.getValue(language)
            for (phrase in pack.escalationAnswerFrame) {
                assertTrue(
                    words.any { phrase.lowercase().contains(it) },
                    "$language: '$phrase' traegt kein Herkunfts-Label ($words)",
                )
            }
        }
    }

    // ── (3) Quellen-Zeile ────────────────────────────────────────────────────

    @Test
    fun `Quellen-Zeile ist in jeder Sprache echt uebersetzt, DE-EN bleiben wortwoertlich`() {
        assertEquals("Quelle: Wikipedia.", TurnOrchestrator.escalationSourceNote("Wikipedia", Language.DE))
        assertEquals("Source: Wikipedia.", TurnOrchestrator.escalationSourceNote("Wikipedia", Language.EN))
        assertEquals("Fuente: Wikipedia.", TurnOrchestrator.escalationSourceNote("Wikipedia", Language.ES))
        assertEquals("Source : Wikipedia.", TurnOrchestrator.escalationSourceNote("Wikipedia", Language.FR))
        assertEquals("Fonte: Wikipedia.", TurnOrchestrator.escalationSourceNote("Wikipedia", Language.IT))
    }

    @Test
    fun `ES-FR-IT bekommen bei der Quellen-Zeile nie mehr das englische Source-Template`() {
        // Praezise statt Substring-Heuristik: Franzoesisch schreibt "Source"
        // zufaellig identisch wie Englisch (echtes franzoesisches Wort) — ein
        // startsWith("Source") waere hier ein falscher Alarm. Der eigentliche
        // Alt-Bug war der DEOr-Fallback auf GENAU das EN-Template; das prueft
        // dieser Test exakt.
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            assertNotEquals(
                "Source: Wikipedia.",
                TurnOrchestrator.escalationSourceNote("Wikipedia", language),
                "$language sprach noch exakt das englische Quellen-Template",
            )
        }
    }

    // ── (4) Fehlerfall „unavailable" ─────────────────────────────────────────

    @Test
    fun `Unavailable kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertEquals(pack.escalationUnavailable, TurnOrchestrator.escalationUnavailable(language), "$language")
        }
    }

    @Test
    fun `ES-FR-IT bekommen beim Unavailable-Fehlerfall nie mehr die englische Phrase`() {
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            assertNotEquals(
                TurnOrchestrator.ESCALATION_UNAVAILABLE_EN,
                TurnOrchestrator.escalationUnavailable(language),
                "$language sprach noch Englisch (Unavailable)",
            )
        }
    }

    /**
     * Die Inventur fand ZWEI Code-Pfade für „ich komm an mein Wissen nicht ran"
     * mit unterschiedlichem Multilingual-Stand ([HonestyGate] war schon
     * fünfsprachig, [TurnOrchestrator.escalationUnavailable] nicht) — der
     * LanguagePack-Umzug gleicht nur den AUSBAU-Stand an, die Sätze selbst
     * bleiben in jeder Sprache verschieden (verschiedene Situationen: lokale
     * Wissens-Bridge tot vs. Cloud-Lookup nicht erreichbar).
     */
    @Test
    fun `Unavailable und HonestyGate Bridge-down bleiben in jeder Sprache verschiedene Saetze`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertFalse(
                pack.escalationUnavailable in pack.honestyBridgeDownRefusals,
                "$language: Unavailable darf nicht mit einer Bridge-down-Phrase zusammenfallen",
            )
        }
    }

    // ── (5) EscalationModeFastpath: die vier Stufen-Quittungen ──────────────

    /**
     * Die MUSTER-ERKENNUNG bleibt bewusst DE+EN (s. [EscalationModeFastpath]-KDoc)
     * — hier also ein deutscher Trigger je Stufe, aber die QUITTUNG kommt in der
     * angefragten [Language] zurück.
     */
    private val triggers = mapOf(
        EscalationMode.ERST_FRAGEN to "frag mich erst, bevor du online gehst",
        EscalationMode.AUS to "schalte online-nachschauen aus",
        EscalationMode.AUTOMATISCH to "geh automatisch online",
        EscalationMode.OFFLINE to "geh offline",
    )

    @Test
    fun `alle vier Stufen-Quittungen kommen in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertEquals(
                pack.escalationModeErstFragen,
                fastpath.handle(triggers.getValue(EscalationMode.ERST_FRAGEN), language),
                "$language/ERST_FRAGEN",
            )
            assertEquals(
                pack.escalationModeAus,
                fastpath.handle(triggers.getValue(EscalationMode.AUS), language),
                "$language/AUS",
            )
            assertEquals(
                pack.escalationModeAutomatisch,
                fastpath.handle(triggers.getValue(EscalationMode.AUTOMATISCH), language),
                "$language/AUTOMATISCH",
            )
            assertEquals(
                pack.escalationModeOffline,
                fastpath.handle(triggers.getValue(EscalationMode.OFFLINE), language),
                "$language/OFFLINE",
            )
        }
    }

    @Test
    fun `ES-FR-IT bekommen bei keiner Stufen-Quittung mehr die englische Phrase`() {
        val en = LanguagePackRegistry.forLanguage(Language.EN)
        val englishReceipts = setOf(
            en.escalationModeErstFragen,
            en.escalationModeAus,
            en.escalationModeAutomatisch,
            en.escalationModeOffline,
        )
        for (language in listOf(Language.ES, Language.FR, Language.IT)) {
            for ((mode, trigger) in triggers) {
                val receipt = fastpath.handle(trigger, language)
                assertFalse(receipt in englishReceipts, "$language/$mode sprach noch Englisch: '$receipt'")
            }
        }
    }
}
