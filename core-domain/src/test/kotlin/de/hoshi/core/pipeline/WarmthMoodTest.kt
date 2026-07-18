package de.hoshi.core.pipeline

import de.hoshi.core.dto.ProsodyTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Warmth v2 — REINE, deterministische Mood-Logik (Stunde injiziert, kein `now()`).
 * Deckt: Bucket-Mapping (Zeit-Bias), Müdigkeits-Marker → CALM, Temperatur-Nudge,
 * Prosody-Mapping und die byte-neutrale OFF-Naht ([MoodTemperaturePort.NONE]).
 */
class WarmthMoodTest {

    // ── (a) Bucket-Mapping über den Zeit-Bias (neutraler Text) ────────────────
    @Test
    fun `Zeit-Bias morgens WAKE, abends-nachts CALM, nachmittags NORMAL`() {
        assertEquals(WarmthMood.Energy.WAKE, WarmthMood.bucket(hour = 8, text = "moin"))    // Morgen
        assertEquals(WarmthMood.Energy.NORMAL, WarmthMood.bucket(hour = 14, text = "hi"))   // Nachmittag
        assertEquals(WarmthMood.Energy.CALM, WarmthMood.bucket(hour = 20, text = "na"))     // Abend
        assertEquals(WarmthMood.Energy.CALM, WarmthMood.bucket(hour = 2, text = "hey"))     // Nacht
    }

    // ── (b) Müdigkeits-Marker dominiert den Zeit-Bias → CALM (DE + EN) ────────
    @Test
    fun `Muedigkeits-Marker erzwingt CALM auch zur WAKE-Zeit`() {
        // 8 Uhr wäre normalerweise WAKE — „müde" zieht auf CALM.
        assertEquals(WarmthMood.Energy.CALM, WarmthMood.bucket(hour = 8, text = "ich bin so müde heute"))
        assertEquals(WarmthMood.Energy.CALM, WarmthMood.bucket(hour = 8, text = "bin total kaputt"))
        assertEquals(WarmthMood.Energy.CALM, WarmthMood.bucket(hour = 8, text = "I'm exhausted"))
        assertTrue(WarmthMood.hasTiredMarker("völlig platt"))
        assertTrue(WarmthMood.hasTiredMarker("totally wiped"))
        assertTrue(!WarmthMood.hasTiredMarker("alles bestens, top fit"))
    }

    // ── (c) Temperatur-Nudge über den bestehenden Temperatur-Pfad ─────────────
    @Test
    fun `Temperatur-Nudge CALM senkt, WAKE hebt, NORMAL unveraendert`() {
        val base = 0.55
        // CALM (nachts) → tiefer
        assertEquals(base + WarmthMood.CALM_NUDGE, WarmthMood.temperatureFor(base, hour = 2, text = ""), 1e-9)
        assertTrue(WarmthMood.temperatureFor(base, hour = 2, text = "") < base)
        // WAKE (morgens) → leicht höher
        assertEquals(base + WarmthMood.WAKE_NUDGE, WarmthMood.temperatureFor(base, hour = 8, text = ""), 1e-9)
        assertTrue(WarmthMood.temperatureFor(base, hour = 8, text = "") > base)
        // NORMAL (nachmittags) → unverändert
        assertEquals(base, WarmthMood.temperatureFor(base, hour = 14, text = ""), 1e-9)
        // Müdigkeits-Marker → CALM-Nudge selbst morgens
        assertEquals(base + WarmthMood.CALM_NUDGE, WarmthMood.temperatureFor(base, hour = 8, text = "bin müde"), 1e-9)
    }

    @Test
    fun `Temperatur bleibt in den sicheren Grenzen geklemmt`() {
        assertEquals(WarmthMood.MIN_TEMP, WarmthMood.temperatureFor(0.30, hour = 2, text = ""), 1e-9)  // CALM nicht unter MIN
        assertEquals(WarmthMood.MAX_TEMP, WarmthMood.temperatureFor(0.90, hour = 8, text = ""), 1e-9)  // WAKE nicht über MAX
    }

    // ── (d) Prosody-Mapping (bereit, noch nicht verdrahtet) ───────────────────
    @Test
    fun `Energie auf ProsodyTone — CALM daempft, WAKE energetisch, NORMAL neutral`() {
        assertEquals(ProsodyTone.CALM, WarmthMood.prosodyTone(WarmthMood.Energy.CALM))
        assertEquals(ProsodyTone.ENERGETIC, WarmthMood.prosodyTone(WarmthMood.Energy.WAKE))
        assertEquals(ProsodyTone.NORMAL, WarmthMood.prosodyTone(WarmthMood.Energy.NORMAL))
    }

    // ── (e) OFF ist byte-neutral: NONE gibt die Basis-Temperatur UNVERÄNDERT ──
    @Test
    fun `MoodTemperaturePort NONE ist Identitaet (byte-neutral)`() {
        // Selbst mit Müdigkeits-Marker im Text: OFF ändert die Temperatur NICHT.
        assertEquals(0.55, MoodTemperaturePort.NONE.adjust(0.55, "ich bin müde"), 0.0)
        assertEquals(0.70, MoodTemperaturePort.NONE.adjust(0.70, "moin, top fit"), 0.0)
    }
}
