package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die **Anti-Konfabulations-Wand** ([FactCoverageGate]): FACT_SHORT ohne
 * gedecktes Grounding ⇒ ehrlich deflekten (kein Brain); ALLES andere ⇒ proceed.
 * Reiner, deterministischer Unit-Test — kein Reactor, kein Brain, keine Infra.
 */
class FactCoverageGateTest {

    private val on = FactCoverageGate(enabled = true)

    // ── Der Rettungs-Fall: FACT_SHORT + LOCAL + kein Grounding ⇒ DEFLECT ──────────
    @Test
    fun `FACT_SHORT ohne Grounding deflektet (der Mittwoch-Rettungsfall)`() {
        val covered = FactCoverageGate.groundingCovered(RouteProvider.LOCAL, groundBlock = "")
        assertFalse(covered, "leerer LOCAL-Block = kein Treffer = nicht gedeckt")
        assertSame(
            FactCoverageGate.Decision.Deflect,
            on.decide(RouteCategory.FACT_SHORT, covered),
            "FACT_SHORT ohne Grounding muss deflekten statt den Brain freestylen zu lassen",
        )
    }

    @Test
    fun `FACT_SHORT ohne Grounding auch bei blank-only Block deflektet`() {
        val covered = FactCoverageGate.groundingCovered(RouteProvider.LOCAL, groundBlock = "   \n\t ")
        assertFalse(covered)
        assertSame(FactCoverageGate.Decision.Deflect, on.decide(RouteCategory.FACT_SHORT, covered))
    }

    // ── GEGROUNDETE Facts NICHT deflekten ────────────────────────────────────────
    @Test
    fun `FACT_SHORT MIT Grounding laeuft normal weiter`() {
        val block = "\n\n---\nHINTERGRUND: • Konrad Adenauer: erster Bundeskanzler …\n"
        val covered = FactCoverageGate.groundingCovered(RouteProvider.LOCAL, block)
        assertTrue(covered, "echter Kontext = gedeckt")
        assertSame(
            FactCoverageGate.Decision.Proceed,
            on.decide(RouteCategory.FACT_SHORT, covered),
            "ein gegroundeter Fact darf NIE deflektet werden",
        )
    }

    // ── Bridge-Down-Sentinel ist KEIN Kontext ⇒ ehrlich deflekten ────────────────
    @Test
    fun `FACT_SHORT mit Bridge-Down-Sentinel deflektet (Steuer-Marker, kein Wissen)`() {
        val covered = FactCoverageGate.groundingCovered(
            RouteProvider.LOCAL,
            TurnPromptAssembler.BRIDGE_DOWN_SENTINEL,
        )
        assertFalse(covered, "der Bridge-Down-Sentinel zählt nie als Deckung")
        assertSame(FactCoverageGate.Decision.Deflect, on.decide(RouteCategory.FACT_SHORT, covered))
    }

    // ── Cloud-Provider: Modell weiß es selbst, Grounding läuft dort nicht ⇒ proceed ─
    @Test
    fun `FACT_SHORT ueber Cloud proceeded auch bei leerem Block`() {
        for (p in listOf(RouteProvider.OPENAI, RouteProvider.ANTHROPIC, RouteProvider.HEDGE)) {
            val covered = FactCoverageGate.groundingCovered(p, groundBlock = "")
            assertTrue(covered, "$p: Cloud-Block-Leere ist kein 'kein Treffer'")
            assertSame(
                FactCoverageGate.Decision.Proceed,
                on.decide(RouteCategory.FACT_SHORT, covered),
                "$p: Cloud-Fakten dürfen nicht deflektet werden",
            )
        }
    }

    // ── Nicht-FACT-Kategorien werden NIE deflektet (auch ohne Grounding) ──────────
    @Test
    fun `Nicht-FACT-Kategorien proceeden immer, auch ohne Grounding`() {
        val notCovered = FactCoverageGate.groundingCovered(RouteProvider.LOCAL, groundBlock = "")
        assertFalse(notCovered)
        for (c in RouteCategory.entries.filter { it != RouteCategory.FACT_SHORT }) {
            assertSame(
                FactCoverageGate.Decision.Proceed,
                on.decide(c, notCovered),
                "$c darf nie deflektet werden — die Wand ist nur für FACT_SHORT",
            )
        }
    }

    // ── Wärme-Leitplanke (Live-Bug 2026-07-01): Smalltalk wird NIE kalt deflektet, ──
    //    auch wenn der Router ihn als FACT_SHORT fehl-routete und Grounding leer ist.

    @Test
    fun `Live-Regression Kurz alles ok bei dir proceeded trotz FACT_SHORT ohne Grounding`() {
        assertSame(
            FactCoverageGate.Decision.Proceed,
            on.decide(RouteCategory.FACT_SHORT, groundingCovered = false, query = "Kurz: alles ok bei dir?"),
            "Smalltalk-förmige Query darf NIE deflektet werden — Deflection wirkt dort kalt/absurd",
        )
    }

    @Test
    fun `Smalltalk-Varianten proceeden trotz leerem Grounding`() {
        val smalltalk = listOf(
            "na, wie läufts?", "bist du wach?", "alles klar bei dir?",
            "wie geht es dir?", "wie geht es dir heute?", "wie heißt du?",
            "how are you doing?", "alles gut bei euch?",
        )
        smalltalk.forEach { q ->
            assertFalse(FactCoverageGate.looksLikeKnowledgeQuery(q), "keine Wissensfrage: $q")
            assertSame(
                FactCoverageGate.Decision.Proceed,
                on.decide(RouteCategory.FACT_SHORT, groundingCovered = false, query = q),
                "Smalltalk darf nie deflektet werden: $q",
            )
        }
    }

    // ── Anti-Regression: echte Wissensfragen deflekten WEITER (die Wand steht) ────

    @Test
    fun `Mittwoch-Etymologie deflektet weiter (Fragewort + Substanz)`() {
        listOf(
            "Warum heißt der Mittwoch Mittwoch?",
            "Woher kommt der Name Mittwoch?",
        ).forEach { q ->
            assertTrue(FactCoverageGate.looksLikeKnowledgeQuery(q), "echte Wissensfrage: $q")
            assertSame(
                FactCoverageGate.Decision.Deflect,
                on.decide(RouteCategory.FACT_SHORT, groundingCovered = false, query = q),
                "ungegroundete Wissensfrage MUSS weiter deflekten: $q",
            )
        }
    }

    @Test
    fun `gegroundeter Helgoland-Fact proceeded weiter (Grounding schlaegt Text-Check)`() {
        val block = "\n\n---\nHINTERGRUND: • Helgoland: Insel in der Nordsee …\n"
        val covered = FactCoverageGate.groundingCovered(RouteProvider.LOCAL, block)
        assertTrue(covered)
        assertSame(
            FactCoverageGate.Decision.Proceed,
            on.decide(RouteCategory.FACT_SHORT, covered, query = "Was ist Helgoland?"),
        )
    }

    @Test
    fun `ohne Query-Text bleibt das alte Deflect-Verhalten (Wand nicht blind offen)`() {
        assertSame(
            FactCoverageGate.Decision.Deflect,
            on.decide(RouteCategory.FACT_SHORT, groundingCovered = false),
            "query=null (Legacy-Aufrufer) ⇒ Text-Check entfällt ⇒ deflect-berechtigt wie zuvor",
        )
    }

    @Test
    fun `looksLikeKnowledgeQuery braucht Fragewort UND Substanz`() {
        // Fragewort + Substanz ⇒ Wissensfrage.
        assertTrue(FactCoverageGate.looksLikeKnowledgeQuery("Wer war Konrad Adenauer?"))
        assertTrue(FactCoverageGate.looksLikeKnowledgeQuery("Wie viele Monde hat der Jupiter?"))
        // Wissens-Imperativ ohne Fragewort ⇒ Wissensfrage.
        assertTrue(FactCoverageGate.looksLikeKnowledgeQuery("Erklär mir Photosynthese"))
        // Fragewort OHNE Substanz ⇒ Smalltalk-förmig.
        assertFalse(FactCoverageGate.looksLikeKnowledgeQuery("was geht?"))
        // Substanz OHNE Fragewort (Nominal-Query) ⇒ bewusst KEINE Wissensfrage
        // (Wärme-Trade-off: proceeded zum Brain statt Deflect-Risiko).
        assertFalse(FactCoverageGate.looksLikeKnowledgeQuery("Hauptstadt von Australien"))
    }

    // ── Strict-Modus (RCA „Grounding-Ehrlichkeit" 2026-07-02): tangentiale ────────
    //    BM25-Treffer zählen NICHT mehr als Deckung — lax bleibt byte-identisch.

    private val strictOn = FactCoverageGate(enabled = true, strict = true)
    private val laxOn = FactCoverageGate(enabled = true, strict = false)

    /** Live-Beweis-Fall: Block non-blank, aber OHNE „eiffelturm" (tangentialer Treffer). */
    private val tangentialBlock =
        "\n\n---\nHINTERGRUND: • Paris: Metropole an der Seine, bekannt für Museen und Türme …\n"

    @Test
    fun `Eiffelturm-Fall strict - tangentialer Block ohne Query-Substanz ist NICHT gedeckt (Deflect)`() {
        val q = "Wie hoch ist der Eiffelturm?"
        val covered = strictOn.groundingCovered(RouteProvider.LOCAL, tangentialBlock, q)
        assertFalse(covered, "off-target Block darf im strict-Modus NICHT als Deckung zählen")
        assertSame(
            FactCoverageGate.Decision.Deflect,
            strictOn.decide(RouteCategory.FACT_SHORT, covered, query = q),
            "strict ⇒ Deflect-Pfad: lieber ehrlich nachschauen als faktenfrei schwafeln",
        )
    }

    @Test
    fun `Eiffelturm-Fall lax - tangentialer Block zaehlt (heutiges Verhalten als Regression festgehalten)`() {
        val q = "Wie hoch ist der Eiffelturm?"
        val covered = laxOn.groundingCovered(RouteProvider.LOCAL, tangentialBlock, q)
        assertTrue(covered, "lax (heute): isNotBlank reicht — der dokumentierte Schwafel-Pfad")
        assertSame(
            FactCoverageGate.Decision.Proceed,
            laxOn.decide(RouteCategory.FACT_SHORT, covered, query = q),
            "lax ⇒ Proceed: exakt das heutige Verhalten (byte-identisch bei strict=false)",
        )
    }

    @Test
    fun `Positiv-Fall - Block traegt die Query-Substanz - strict UND lax gedeckt`() {
        val block = "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter hoch …\n"
        val q = "Wie hoch ist der Eiffelturm?"
        assertTrue(strictOn.groundingCovered(RouteProvider.LOCAL, block, q), "on-target Block deckt auch strict")
        assertTrue(laxOn.groundingCovered(RouteProvider.LOCAL, block, q))
        assertSame(FactCoverageGate.Decision.Proceed, strictOn.decide(RouteCategory.FACT_SHORT, true, query = q))
    }

    @Test
    fun `leerer Block ist in BEIDEN Modi nicht gedeckt`() {
        val q = "Wie hoch ist der Eiffelturm?"
        assertFalse(strictOn.groundingCovered(RouteProvider.LOCAL, "", q))
        assertFalse(laxOn.groundingCovered(RouteProvider.LOCAL, "", q))
    }

    @Test
    fun `strict laesst Nicht-LOCAL-Provider und query=null unangetastet (lax-Verhalten)`() {
        // Cloud: Grounding läuft dort nicht — leerer/fremder Block ist kein „kein Treffer".
        assertTrue(strictOn.groundingCovered(RouteProvider.OPENAI, "", "Wie hoch ist der Eiffelturm?"))
        // Legacy-Aufrufer ohne Query-Text: Substanz-Check entfällt ⇒ lax.
        assertTrue(strictOn.groundingCovered(RouteProvider.LOCAL, tangentialBlock, query = null))
    }

    @Test
    fun `queryOnTarget filtert Frage-Geruest und matcht case-insensitiv`() {
        // Content-Token „eiffelturm" (Frageworte + Filler wie hoch/ist/der fallen raus).
        assertTrue(FactCoverageGate.queryOnTarget("… der EIFFELTURM ist 330 m …", "Wie hoch ist der Eiffelturm?"))
        assertFalse(FactCoverageGate.queryOnTarget("Der Turm ist ziemlich hoch.", "Wie hoch ist der Eiffelturm?"))
        // Query ohne Content-Token (nur Gerüst): nichts zu prüfen ⇒ konservativ true.
        assertTrue(FactCoverageGate.queryOnTarget("irgendein Block", "Wie ist das denn?"))
    }

    // ── OFF (Default) ⇒ IMMER proceed ⇒ byte-neutral ─────────────────────────────
    @Test
    fun `Gate OFF ist byte-neutral (immer proceed)`() {
        assertSame(
            FactCoverageGate.Decision.Proceed,
            FactCoverageGate.DISABLED.decide(RouteCategory.FACT_SHORT, groundingCovered = false),
            "DISABLED-Default darf nie deflekten",
        )
        assertSame(
            FactCoverageGate.Decision.Proceed,
            FactCoverageGate(enabled = false).decide(RouteCategory.FACT_SHORT, groundingCovered = false),
        )
    }

    // ── Deflection-Phrasen: sprach-korrekt, nicht leer, DE != EN ─────────────────
    @Test
    fun `Deflection-Phrasen sind sprach-korrekt und nicht leer`() {
        assertEquals(FactCoverageGate.DEFLECT_DE, FactCoverageGate.deflection(Language.DE))
        assertEquals(FactCoverageGate.DEFLECT_EN, FactCoverageGate.deflection(Language.EN))
        assertTrue(FactCoverageGate.deflection(Language.DE).isNotBlank())
        assertTrue(FactCoverageGate.deflection(Language.EN).isNotBlank())
        assertNotEquals(FactCoverageGate.DEFLECT_DE, FactCoverageGate.DEFLECT_EN)
        // Die ehrliche Haltung: bietet Nachschauen an, kein hartes "kann ich nicht".
        assertTrue(FactCoverageGate.DEFLECT_DE.contains("nachschauen"))
    }
}
