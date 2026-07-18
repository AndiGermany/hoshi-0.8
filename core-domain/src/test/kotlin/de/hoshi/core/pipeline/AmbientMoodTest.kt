package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.TimeOfDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reine Ambient-Logik (Task B): Stunde → Tageszeit-Bucket → kleiner, sprach-
 * getunter Wärme-Hinweis. Deterministisch — die Stunde wird INJiziert, nie `now()`
 * in der reinen Logik (Determinismus-Invariante).
 */
class AmbientMoodTest {

    // ── Bucket-Mapping (deterministisch, injizierte Stunde) ──────────────────

    @Test
    fun `bucket bildet Stunden auf die vier Tageszeiten ab`() {
        assertEquals(TimeOfDay.MORNING, AmbientMood.bucket(8))
        assertEquals(TimeOfDay.AFTERNOON, AmbientMood.bucket(14))
        assertEquals(TimeOfDay.EVENING, AmbientMood.bucket(20))
        assertEquals(TimeOfDay.NIGHT, AmbientMood.bucket(2))
    }

    @Test
    fun `bucket ist robust gegen Ueber- und Unterlauf`() {
        assertEquals(TimeOfDay.NIGHT, AmbientMood.bucket(24))   // 24 → 0 → NIGHT
        assertEquals(AmbientMood.bucket(20), AmbientMood.bucket(44)) // 44 → 20 → EVENING
        assertEquals(AmbientMood.bucket(23), AmbientMood.bucket(-1)) // -1 → 23 → NIGHT
    }

    // ── ON-Pfad: abends wird wärmer genudged (DE+EN) ─────────────────────────

    @Test
    fun `Abend-Hinweis nudgt waermer (DE)`() {
        val hint = AmbientMood.warmthHint(hour = 20, language = Language.DE)
        assertTrue(hint.contains("Ambiente"), hint)
        assertTrue(hint.contains("wärmer"), "Abend (DE) nudgt wärmer: $hint")
    }

    @Test
    fun `Abend-Hinweis nudgt waermer (EN)`() {
        val hint = AmbientMood.warmthHint(hour = 20, language = Language.EN)
        assertTrue(hint.contains("Ambient"), hint)
        assertTrue(hint.contains("warmer"), "evening (EN) nudges warmer: $hint")
    }

    @Test
    fun `Hinweise unterscheiden sich je Tageszeit (Abend nicht gleich Morgen)`() {
        val morning = AmbientMood.warmthHint(hour = 8, language = Language.DE)
        val evening = AmbientMood.warmthHint(hour = 20, language = Language.DE)
        assertNotEquals(morning, evening, "Morgen- und Abend-Hinweis dürfen nicht identisch sein")
    }

    @Test
    fun `DEFAULT-Sprache faellt auf DE`() {
        assertEquals(
            AmbientMood.warmthHint(hour = 20, language = Language.DE),
            AmbientMood.warmthHint(hour = 20, language = Language.DEFAULT),
        )
    }

    // ── ClockPort-Naht: Stunde wird injiziert (Fake-Clock, kein now()) ───────

    @Test
    fun `Port mit Fake-Clock liefert den getunten Hinweis der gesetzten Stunde`() {
        val fakeNight: ClockPort = ClockPort { 2 }
        val port = AmbientWarmthPort { lang -> AmbientMood.warmthHint(fakeNight.hour(), lang) }

        assertEquals(AmbientMood.DE_NIGHT, port.warmthHint(Language.DE))
    }

    @Test
    fun `NONE-Port schweigt (OFF byte-neutral)`() {
        assertEquals(null, AmbientWarmthPort.NONE.warmthHint(Language.DE))
        assertEquals(null, AmbientWarmthPort.NONE.warmthHint(Language.EN))
    }
}
