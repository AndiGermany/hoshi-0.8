package de.hoshi.web.routing

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * **KeywordRouterImplTest** — beweist die Trennung, auf der der M4-Step-2-Meilenstein
 * steht: eine Wissensfrage landet in einer Grounding-Kategorie (FACT_SHORT), Smalltalk
 * nicht (SMALLTALK), Smart-Home in SMART_HOME. Test-Fälle aus 0.5 `RouterServiceTest`
 * portiert (Smart-Home-Matrix) + die neue Wissen↔Smalltalk-Trennung.
 */
class KeywordRouterImplTest {

    private val router = KeywordRouterImpl()

    // ── Der Meilenstein-Kern: Wissensfrage → Grounding-Kategorie ─────────────────

    @Test
    fun `Wissensfrage Konrad Adenauer ist FACT_SHORT (Grounding feuert)`() {
        val d = router.decide("Wer war Konrad Adenauer?")
        assertEquals(RouteCategory.FACT_SHORT, d.category)
        assertEquals(RouteProvider.LOCAL, d.provider)
    }

    @Test
    fun `Wissensfrage Was ist Liebe ist FACT_SHORT`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Was ist Liebe?").category)
    }

    @Test
    fun `lange Wissensfrage ist FACT_SHORT`() {
        val long = "Erkläre mir bitte ausführlich und detailliert wie Photosynthese in " +
            "Pflanzen abläuft und welche Enzyme daran beteiligt sind"
        assertEquals(RouteCategory.FACT_SHORT, router.decide(long).category)
    }

    // ── Smalltalk → SMALLTALK (kein Grounding) ───────────────────────────────────

    @Test
    fun `Smalltalk wie geht es dir ist SMALLTALK`() {
        assertEquals(RouteCategory.SMALLTALK, router.decide("Wie geht es dir?").category)
    }

    @Test
    fun `Gruss mit Smalltalk ist SMALLTALK`() {
        assertEquals(RouteCategory.SMALLTALK, router.decide("Hallo, wie geht's dir?").category)
    }

    @Test
    fun `leerer String ist SMALLTALK`() {
        assertEquals(RouteCategory.SMALLTALK, router.decide("").category)
    }

    // ── Smart-Home → SMART_HOME (port 0.5 RouterServiceTest-Matrix) ──────────────

    @Test
    fun `schalte Licht im Wohnzimmer an ist SMART_HOME`() {
        val d = router.decide("schalte das licht im wohnzimmer an")
        assertEquals(RouteCategory.SMART_HOME, d.category)
        assertEquals(RouteProvider.LOCAL, d.provider)
    }

    @Test
    fun `terse Licht im Schlafzimmer aus ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("Licht im Schlafzimmer aus").category)
    }

    @Test
    fun `terse Lampe an ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("Lampe an").category)
    }

    @Test
    fun `licht ausmachen ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("kannst du das licht ausmachen").category)
    }

    @Test
    fun `blanker Raum plus Zustand Schlafzimmer aus ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("Schlafzimmer aus").category)
    }

    @Test
    fun `blanker Raum plus an Wohnzimmer an ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("Wohnzimmer an").category)
    }

    // ── Kein Fehl-Routing: Target als Thema (kein Befehl) ist NICHT SMART_HOME ────

    @Test
    fun `Target ohne Befehl ist kein SMART_HOME sondern Wissensfrage`() {
        // „licht der sonne" — Target als Thema, kein Steuer-Marker → darf NICHT als
        // Aktion routen; es ist eine Wissensfrage (Inhalts-Tokens vorhanden).
        val d = router.decide("erzähl mir was über das licht der sonne")
        assertNotEquals(RouteCategory.SMART_HOME, d.category)
        assertEquals(RouteCategory.FACT_SHORT, d.category)
    }

    // ── Komfort-/Ambiente-Phrasen → SMART_HOME (0.8, INDIREKTE Absicht) ──────────

    @Test
    fun `mir ist kalt ist SMART_HOME`() {
        val d = router.decide("mir ist kalt")
        assertEquals(RouteCategory.SMART_HOME, d.category)
        assertEquals(RouteProvider.LOCAL, d.provider)
    }

    @Test
    fun `es ist dunkel ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("es ist dunkel").category)
    }

    @Test
    fun `englische Komfortphrase i'm cold ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("I'm cold").category)
    }

    @Test
    fun `alle Komfort-Phrasen routen nach SMART_HOME`() {
        val phrases = listOf(
            // DE — Klima
            "mir ist kalt", "mir ist kühl", "mir ist warm", "mir ist heiß", "mir ist heiss",
            "es ist kühl", "es ist stickig", "es zieht",
            // DE — Licht
            "es ist dunkel", "es ist hell", "es ist zu hell",
            // EN
            "i'm cold", "i am cold", "i'm hot", "i am hot", "it's stuffy",
            "it's dark", "it's bright", "it's too bright",
        )
        phrases.forEach { p ->
            assertEquals(RouteCategory.SMART_HOME, router.decide(p).category, "Phrase: $p")
        }
    }

    @Test
    fun `Komfortphrase mit Kontext mir ist kalt hier drin ist SMART_HOME`() {
        assertEquals(RouteCategory.SMART_HOME, router.decide("mir ist kalt hier drin").category)
    }

    // ── Komfort-Layer kapert WEDER Wissen NOCH Smalltalk ─────────────────────────

    @Test
    fun `Wissensfrage Wer war Adenauer bleibt FACT_SHORT (Komfort-Layer feuert nicht)`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Wer war Adenauer?").category)
    }

    @Test
    fun `Wissensfrage Was ist Helligkeit bleibt FACT_SHORT (kein es-ist-hell-Treffer)`() {
        val d = router.decide("Was ist Helligkeit?")
        assertNotEquals(RouteCategory.SMART_HOME, d.category)
        assertEquals(RouteCategory.FACT_SHORT, d.category)
    }

    @Test
    fun `Chit-Chat wie geht's dir bleibt NICHT SMART_HOME`() {
        assertNotEquals(RouteCategory.SMART_HOME, router.decide("wie geht's dir?").category)
    }

    // ── Live-Bug 2026-07-01: persönliche Zustands-Fragen sind SMALLTALK, nie FACT ──
    // („Kurz: alles ok bei dir?" wurde FACT_SHORT → leeres Grounding → kalte
    // FactCoverage-Deflection auf Smalltalk. Wurzel: „bei"/„wach"/„läufts"/„heute"
    // überlebten die Content-Token-Reduktion.)

    @Test
    fun `Live-Regression Kurz alles ok bei dir ist SMALLTALK`() {
        val d = router.decide("Kurz: alles ok bei dir?")
        assertEquals(RouteCategory.SMALLTALK, d.category)
    }

    @Test
    fun `Zustands-Smalltalk-Varianten routen alle SMALLTALK`() {
        val phrases = listOf(
            "na, wie läufts?",
            "wie läuft's?",
            "bist du wach?",
            "alles klar bei dir?",
            "alles gut bei euch?",
            "wie geht es dir?",
            "wie geht es dir heute?",
            "alles ok?",
            "wie heißt du?",
            "bist du gut drauf?",
        )
        phrases.forEach { p ->
            assertEquals(RouteCategory.SMALLTALK, router.decide(p).category, "Phrase: $p")
        }
    }

    // ── Anti-Regression: die neuen Filler kapern KEINE echten Wissensfragen ──────
    // (echte Fragen tragen immer mind. ein weiteres Inhalts-Token)

    @Test
    fun `Wissensfrage mit bei bleibt FACT_SHORT`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Wer war bei der Mondlandung dabei?").category)
    }

    @Test
    fun `Mittwoch-Etymologie bleibt FACT_SHORT (Deflect-Pfad erreichbar)`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Warum heißt der Mittwoch Mittwoch?").category)
    }

    @Test
    fun `Was ist Helgoland bleibt FACT_SHORT (Grounding feuert)`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Was ist Helgoland?").category)
    }

    @Test
    fun `Wie läuft eine Herztransplantation ab bleibt FACT_SHORT`() {
        assertEquals(RouteCategory.FACT_SHORT, router.decide("Wie läuft eine Herztransplantation ab?").category)
    }

    // ── Idiom-False-Positive-Guards: Komfort-Wort übertragen → NICHT SMART_HOME ──

    @Test
    fun `Idiom das lässt mich kalt ist NICHT SMART_HOME`() {
        assertNotEquals(RouteCategory.SMART_HOME, router.decide("das lässt mich kalt").category)
    }

    @Test
    fun `Idiom mir ist warm ums herz ist NICHT SMART_HOME (Blocker)`() {
        assertNotEquals(RouteCategory.SMART_HOME, router.decide("mir ist warm ums herz").category)
    }

    @Test
    fun `Idiom es zieht sich hin ist NICHT SMART_HOME (Blocker)`() {
        assertNotEquals(RouteCategory.SMART_HOME, router.decide("die besprechung es zieht sich hin").category)
    }
}
