package de.hoshi.core.pipeline.lang

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **FreshnessMarkerTest** — beweist [freshnessMarker] (F2-Rest, Andi-Auftrag:
 * „'Stand: vor X min' sprechbar", 5 Sprachen): die EINE Stufen-Logik (< 2 min /
 * 2–59 min / ≥ 60 min) je Sprache, plus die Grenzfälle, auf denen die Stufung
 * steht und fällt.
 */
class FreshnessMarkerTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    // ── Je Sprache mindestens EIN Phrasen-Test ───────────────────────────────

    @Test
    fun `DE - gerade eben, Minuten-Vorlage, Stunden-Auffang`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("gerade eben", p.freshnessMarker(0))
        assertEquals("vor 12 Minuten", p.freshnessMarker(12 * minute))
        assertEquals("vor über einer Stunde", p.freshnessMarker(90 * minute))
    }

    @Test
    fun `EN - just now, minutes template, hour catch-all`() {
        val p = LangEn.PACK.haExecutor
        assertEquals("just now", p.freshnessMarker(0))
        assertEquals("12 minutes ago", p.freshnessMarker(12 * minute))
        assertEquals("over an hour ago", p.freshnessMarker(90 * minute))
    }

    @Test
    fun `ES - hace un momento, hace N minutos, mas de una hora`() {
        val p = LangEs.PACK.haExecutor
        assertEquals("hace un momento", p.freshnessMarker(0))
        assertEquals("hace 12 minutos", p.freshnessMarker(12 * minute))
        assertEquals("hace más de una hora", p.freshnessMarker(90 * minute))
    }

    @Test
    fun `FR - a l'instant, il y a N minutes, plus d'une heure`() {
        val p = LangFr.PACK.haExecutor
        assertEquals("à l'instant", p.freshnessMarker(0))
        assertEquals("il y a 12 minutes", p.freshnessMarker(12 * minute))
        assertEquals("il y a plus d'une heure", p.freshnessMarker(90 * minute))
    }

    @Test
    fun `IT - poco fa, N minuti fa, piu di un'ora fa`() {
        val p = LangIt.PACK.haExecutor
        assertEquals("poco fa", p.freshnessMarker(0))
        assertEquals("12 minuti fa", p.freshnessMarker(12 * minute))
        assertEquals("più di un'ora fa", p.freshnessMarker(90 * minute))
    }

    // ── Grenzfälle: gerade eben / Minuten / Stunden ──────────────────────────

    @Test
    fun `Grenze knapp unter 2 Minuten bleibt gerade eben`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("gerade eben", p.freshnessMarker(2 * minute - 1))
    }

    @Test
    fun `Grenze bei genau 2 Minuten kippt auf die Minuten-Stufe`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("vor 2 Minuten", p.freshnessMarker(2 * minute))
    }

    @Test
    fun `Grenze knapp unter 60 Minuten bleibt Minuten-Stufe`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("vor 59 Minuten", p.freshnessMarker(hour - minute))
    }

    @Test
    fun `Grenze bei genau 60 Minuten kippt auf den Stunden-Auffang`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("vor über einer Stunde", p.freshnessMarker(hour))
    }

    @Test
    fun `Stunden-Auffang bleibt EIN Satz, keine Stunden-Zaehlung auch bei 5 Stunden`() {
        val p = LangDe.PACK.haExecutor
        assertEquals("vor über einer Stunde", p.freshnessMarker(5 * hour))
    }

    @Test
    fun `negatives Alter (Uhr-Drift) faellt ehrlich auf gerade eben statt negativ zu sprechen`() {
        val p = LangDe.PACK.haExecutor
        val phrase = p.freshnessMarker(-5000)
        assertEquals("gerade eben", phrase)
        assertTrue(!phrase.contains("-"), "keine negative Minutenzahl im gesprochenen Text: $phrase")
    }

    @Test
    fun `Minuten-Stufe ist im Deutschen nie Singular - die Stufe beginnt erst bei 2`() {
        // "vor 1 Minute" (Singular) wird NIE erzeugt: alles unter 2 Minuten faellt
        // auf freshnessJustNow, die Minuten-Stufe startet erst bei genau 2.
        val p = LangDe.PACK.haExecutor
        assertEquals("vor 2 Minuten", p.freshnessMarker(2 * minute))
        assertTrue(!p.freshnessMarker(2 * minute).contains("1 Minute"))
    }

    // ── Alle fünf Sprachen liefern eine nicht-leere, unterscheidbare Phrase je Stufe ──

    @Test
    fun `alle fuenf Sprachen haben eine echte, nicht-leere Phrase je Stufe`() {
        for (language in Language.entries) {
            val p = LanguagePackRegistry.forLanguage(language).haExecutor
            assertTrue(p.freshnessJustNow.isNotBlank(), "$language: freshnessJustNow darf nicht leer sein")
            assertTrue(p.freshnessMinutesAgo.isNotBlank(), "$language: freshnessMinutesAgo darf nicht leer sein")
            assertTrue(p.freshnessOverAnHourAgo.isNotBlank(), "$language: freshnessOverAnHourAgo darf nicht leer sein")
            assertTrue(
                p.freshnessMinutesAgo.contains(MINUTES_PLACEHOLDER),
                "$language: freshnessMinutesAgo muss den {minutes}-Platzhalter tragen",
            )
            assertTrue(
                p.temperatureInAreaStale.contains(FRESHNESS_PLACEHOLDER) && p.temperatureInAreaStale.contains("{room}") &&
                    p.temperatureInAreaStale.contains("{value}"),
                "$language: temperatureInAreaStale braucht {room}/{value}/{freshness}",
            )
            assertTrue(
                p.temperatureHouseAverageStale.contains(FRESHNESS_PLACEHOLDER) && p.temperatureHouseAverageStale.contains("{value}"),
                "$language: temperatureHouseAverageStale braucht {value}/{freshness}",
            )
        }
    }
}
