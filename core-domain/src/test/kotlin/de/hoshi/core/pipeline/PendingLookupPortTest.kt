package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **PendingLookupPortTest** — der Vertrag des offenen „soll ich kurz
 * nachschauen?"-Angebots (Extended Think S2): one-shot, TTL, Key-Isolation,
 * byte-neutraler [PendingLookupPort.NONE]-Default — plus der deterministische
 * [AffirmationRecognizer] (exakte Liste, ≤4 Tokens, nie Volltext-Raten).
 */
class PendingLookupPortTest {

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    private val query = "Wie hoch ist der Eiffelturm?"

    // ── one-shot: consume liefert genau einmal ─────────────────────────────────
    @Test
    fun `offer dann consume liefert das Angebot - der zweite consume ist leer (one-shot)`() {
        val store = InMemoryPendingLookupStore()
        store.offer("local", PendingLookup(query, Language.DE))

        val pending = store.consume("local")
        assertEquals(query, pending?.query, "die GESPEICHERTE Original-Query kommt zurück")
        assertEquals(Language.DE, pending?.language)
        assertNull(store.consume("local"), "one-shot: das Angebot ist nach dem ersten consume weg")
    }

    // ── TTL: ein Angebot von vorhin ist kein Consent von jetzt ─────────────────
    @Test
    fun `TTL abgelaufen - consume liefert null und der Eintrag ist geraeumt`() {
        val clock = MutableClock(Instant.parse("2026-07-05T12:00:00Z"))
        val store = InMemoryPendingLookupStore(clock = clock)
        store.offer("local", PendingLookup(query, Language.DE, ts = clock.instant()))

        clock.advanceSeconds(121)
        assertNull(store.consume("local"), "nach 121 s ist das Angebot abgelaufen")
        clock.advanceSeconds(-121)
        assertNull(store.consume("local"), "der abgelaufene Eintrag wurde beim consume geräumt")
    }

    @Test
    fun `innerhalb der TTL bleibt das Angebot gueltig`() {
        val clock = MutableClock(Instant.parse("2026-07-05T12:00:00Z"))
        val store = InMemoryPendingLookupStore(clock = clock)
        store.offer("local", PendingLookup(query, Language.DE, ts = clock.instant()))

        clock.advanceSeconds(119)
        assertEquals(query, store.consume("local")?.query, "119 s < TTL ⇒ Angebot gilt")
    }

    /**
     * REGRESSION (25.07): [PendingLookup.ts] stempelt sich per Default aus
     * `Instant.now()` — die Verfallsprüfung misst aber gegen die injizierte Uhr.
     * Wer beides mischt, hat eine TTL, die nur zufällig stimmt: der TTL-Test der
     * Themen-Kette wurde exakt in der Sekunde dauerhaft rot, in der die Wanduhr
     * seinen fest verdrahteten Zeitpunkt überholte. Hier steht der Vertrag:
     * **der Store stempelt**, deshalb verfällt auch ein Angebot ohne eigenes `ts`.
     */
    @Test
    fun `der Store stempelt - ein Angebot ohne eigenes ts verfaellt nach der Store-Uhr`() {
        val clock = MutableClock(Instant.parse("2026-07-05T12:00:00Z"))
        val store = InMemoryPendingLookupStore(clock = clock)
        // Bewusst OHNE ts ⇒ der Default aus Instant.now() liegt Jahre nach der Store-Uhr.
        store.offer("local", PendingLookup(query, Language.DE))

        clock.advanceSeconds(121)
        assertNull(store.consume("local"), "die Store-Uhr entscheidet, nicht die Wanduhr")
    }

    // ── Key-Isolation: chatId-A konsumiert nie das Angebot von chatId-B ────────
    @Test
    fun `Keys sind isoliert - fremder Key konsumiert nichts`() {
        val store = InMemoryPendingLookupStore()
        store.offer("chat-a", PendingLookup(query, Language.DE))

        assertNull(store.consume("chat-b"), "fremder Key sieht das Angebot nicht")
        assertEquals(query, store.consume("chat-a")?.query, "der eigene Key löst weiterhin ein")
    }

    // ── NONE: byte-neutraler Default ───────────────────────────────────────────
    @Test
    fun `NONE merkt nie und liefert nie`() {
        PendingLookupPort.NONE.offer("local", PendingLookup(query, Language.DE))
        assertNull(PendingLookupPort.NONE.consume("local"), "NONE ⇒ kein Consent-Folge-Turn, Verhalten unverändert")
    }

    // ── AffirmationRecognizer: exakte Liste, deterministisch ───────────────────
    @Test
    fun `Affirmationen werden erkannt - inklusive Interpunktion und Grossschreibung`() {
        listOf(
            "ja", "Ja!", "Ja, bitte.", "ja gerne", "gern", "Gerne",
            "mach das", "Mach mal", "schau nach", "Schau mal nach",
            "ok", "Okay", "klar", "Bitte",
            "yes", "Yes, please", "sure", "Go ahead",
        ).forEach { text ->
            assertTrue(AffirmationRecognizer.matches(text), "»$text« ist eine Affirmation")
        }
    }

    @Test
    fun `Nicht-Affirmationen fallen durch - nein, Fragen, lange Saetze, Teilwoerter`() {
        listOf(
            "nein", "Nee, lass mal", "jawohl", "was ist mit morgen",
            "ja aber was ist mit dem Wetter", // >4 Tokens ⇒ nie eine Affirmation
            "ja was", // exakte Liste: „ja was" ist keine Zustimmung
            "", "   ",
        ).forEach { text ->
            assertFalse(AffirmationRecognizer.matches(text), "»$text« ist KEINE Affirmation")
        }
    }
}
