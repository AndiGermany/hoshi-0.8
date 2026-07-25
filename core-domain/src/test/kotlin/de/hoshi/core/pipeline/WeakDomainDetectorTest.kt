package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den [WeakDomainDetector] (Andi-Befund 2026-07-25: ~770 Antwort-Sätze
 * waren übersetzt, aber die ERKENNER blieben deutsch): die bestehende DE-Matrix
 * bleibt byte-identisch, und Rezept-/HowTo-Marker matchen jetzt auch auf
 * Englisch, Spanisch, Französisch und Italienisch — inkl. des Gegen-Belegs,
 * dass das unbetonte spanische „como" (Präposition „als/wie") NICHT als
 * How-To-Kontext zählt.
 */
class WeakDomainDetectorTest {

    private val detector = WeakDomainDetector()

    // ── DE-Bestand bleibt byte-identisch (Regressions-Anker) ─────────────────

    @Test
    fun `DE Rezept-Marker matcht unveraendert`() {
        val v = detector.detect("Wie mache ich Käsekuchen?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.RECIPE, v.domain)
        assertEquals("wie mache ich", v.trigger)
    }

    @Test
    fun `DE HowTo-Verb mit Kontext matcht unveraendert`() {
        val v = detector.detect("Wie repariere ich die Waschmaschine?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.HOWTO, v.domain)
    }

    @Test
    fun `DE HowTo-Verb OHNE Kontext matcht weiterhin nicht (Aussage)`() {
        assertFalse(detector.detect("Ich installiere gerade ein Update").matched)
    }

    @Test
    fun `DE HA-Imperativ hat weiterhin Vorrang vor WeakDomain`() {
        // Gate 0: "schalte " ist ein HA-Imperativ-Marker; obwohl "repariere"+"wie"
        // beide da sind, darf NIE WeakDomain zurückkommen.
        assertFalse(detector.detect("schalte mal, wie repariere ich das Radio").matched)
    }

    // ── RECIPE: EN/ES/FR/IT ───────────────────────────────────────────────────

    @Test
    fun `EN Rezept-Nomen matcht`() {
        val v = detector.detect("Do you have a recipe for lasagna?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.RECIPE, v.domain)
    }

    @Test
    fun `ES Rezept-Nomen matcht`() {
        val v = detector.detect("Necesito una receta para hacer pan.")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.RECIPE, v.domain)
    }

    @Test
    fun `FR Rezept-Nomen matcht`() {
        val v = detector.detect("Tu as une recette pour ce gâteau ?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.RECIPE, v.domain)
    }

    @Test
    fun `IT Rezept-Nomen matcht`() {
        val v = detector.detect("Hai una ricetta per la pasta?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.RECIPE, v.domain)
    }

    // ── HOWTO: EN/ES/FR/IT (Verb + Kontext-Gate) ──────────────────────────────

    @Test
    fun `EN HowTo-Verb mit Kontext matcht`() {
        val v = detector.detect("How do I install a new light switch?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.HOWTO, v.domain)
    }

    @Test
    fun `EN HowTo-Verb OHNE Kontext matcht nicht (Aussage)`() {
        assertFalse(detector.detect("I repair bicycles for a living").matched)
    }

    @Test
    fun `ES HowTo-Verb mit Kontext matcht`() {
        val v = detector.detect("¿Cómo instalo una lámpara nueva?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.HOWTO, v.domain)
    }

    @Test
    fun `FR HowTo-Verb mit Kontext matcht`() {
        val v = detector.detect("Comment je répare ce robinet ?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.HOWTO, v.domain)
    }

    @Test
    fun `IT HowTo-Verb mit Kontext matcht`() {
        val v = detector.detect("Come installo una nuova presa?")
        assertTrue(v.matched)
        assertEquals(WeakDomainDetector.Domain.HOWTO, v.domain)
    }

    // ── Gegen-Beispiele: keine falschen Treffer ───────────────────────────────

    @Test
    fun `ES unbetontes como als Praeposition triggert NICHT`() {
        // "como" OHNE Akzent ist "als/wie" (Vergleich), kein Frage-Wort — der
        // Kontext-Gate darf hier nicht anspringen, obwohl "instalar" (HowTo-Verb)
        // im Satz steht.
        assertFalse(detector.detect("Voy a instalar el armario, tal como el del catálogo.").matched)
    }

    @Test
    fun `Harmlose Alltagssaetze in EN ES FR IT triggern nicht`() {
        for (text in listOf(
            "How are you doing today?",
            "¿Cómo estás?",
            "Comment ça va ?",
            "Come stai oggi?",
            "I will fix a coffee for you.", // "fix" ohne HowTo-Kontext
        )) {
            assertFalse(detector.detect(text).matched, "Text war: $text")
        }
    }

    @Test
    fun `Leerer Text matcht nie`() {
        assertFalse(detector.detect("").matched)
        assertFalse(detector.detect("   ").matched)
    }
}
