package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
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
 * Store + recognizer of the room ask (F1-4), isolated — [PendingLocationQuestionPortTest]
 * shape. Contract under test:
 *  - [InMemoryPendingAreaClarifyStore]: one-shot consume, key isolation, TTL
 *    (~120 s) reported as [PendingAreaClarifyPort.Consumed.expired] (behaviourally
 *    absent, diary-only visibility).
 *  - [AreaAnswerRecognizer]: whole-utterance match against the real catalog —
 *    bare/prepositional/politeness forms resolve, everything else is null.
 */
class PendingAreaClarifyPortTest {

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(s: Long) { now = now.plusSeconds(s) }
    }

    private fun pending(service: String = "turn_on") =
        PendingAreaClarify(domain = "light", service = service, language = Language.DE)

    // ── Store: one-shot + isolation ──────────────────────────────────────────────
    @Test
    fun `consume ist one-shot - das zweite consume liefert null`() {
        val store = InMemoryPendingAreaClarifyStore()
        store.offer("local", pending())
        assertEquals("turn_on", store.consume("local")?.pending?.service)
        assertNull(store.consume("local"), "one-shot: nach dem Ziehen ist die Rueckfrage weg")
    }

    @Test
    fun `fremder Schluessel sieht das Pending nie - Session-Isolation`() {
        val store = InMemoryPendingAreaClarifyStore()
        store.offer("chat-1", pending())
        assertNull(store.consume("chat-2"), "fremde Session darf NIE ein fremdes Pending einloesen")
        assertEquals("turn_on", store.consume("chat-1")?.pending?.service)
    }

    @Test
    fun `blank key oder blank intent werden nie gemerkt`() {
        val store = InMemoryPendingAreaClarifyStore()
        store.offer("", pending())
        store.offer("local", PendingAreaClarify(domain = "", service = "turn_on"))
        assertNull(store.consume(""))
        assertNull(store.consume("local"))
    }

    @Test
    fun `neues offer ueberschreibt ein aelteres desselben Schluessels`() {
        val store = InMemoryPendingAreaClarifyStore()
        store.offer("local", pending(service = "turn_on"))
        store.offer("local", pending(service = "turn_off"))
        assertEquals("turn_off", store.consume("local")?.pending?.service)
    }

    // ── Store: TTL ───────────────────────────────────────────────────────────────
    @Test
    fun `TTL abgelaufen - consume meldet expired und raeumt`() {
        val clock = MutableClock(Instant.now())
        val store = InMemoryPendingAreaClarifyStore(clock = clock)
        store.offer("local", pending())
        clock.advanceSeconds(121)
        val consumed = store.consume("local")
        assertTrue(consumed!!.expired, "TTL 120 s: abgelaufen wird gemeldet (Diary), nie eingeloest")
        assertNull(store.consume("local"), "geraeumt bleibt geraeumt")
    }

    @Test
    fun `innerhalb der TTL bleibt das Pending einloesbar`() {
        val clock = MutableClock(Instant.now())
        val store = InMemoryPendingAreaClarifyStore(clock = clock)
        store.offer("local", pending())
        clock.advanceSeconds(119)
        val consumed = store.consume("local")
        assertFalse(consumed!!.expired)
        assertEquals("light", consumed.pending.domain)
    }

    // ── Recognizer: matching forms against the REAL static catalog ───────────────
    private val areas = AreaCatalogPort.STATIC.areas()

    @Test
    fun `nackter Raumname matcht gegen den Katalog`() {
        assertEquals("wohnzimmer", AreaAnswerRecognizer.areaId("Wohnzimmer", areas))
        assertEquals("kuche", AreaAnswerRecognizer.areaId("Küche", areas))
        assertEquals("kuche", AreaAnswerRecognizer.areaId("kueche", areas))
    }

    @Test
    fun `Praepositions-Form matcht - im Wohnzimmer, in der Kueche`() {
        assertEquals("wohnzimmer", AreaAnswerRecognizer.areaId("im Wohnzimmer", areas))
        assertEquals("kuche", AreaAnswerRecognizer.areaId("in der Küche", areas))
        assertEquals("wohnzimmer", AreaAnswerRecognizer.areaId("in the living room", areas))
    }

    @Test
    fun `Hoeflichkeits-Nachsatz stoert nicht - Wohnzimmer bitte`() {
        assertEquals("wohnzimmer", AreaAnswerRecognizer.areaId("Wohnzimmer bitte", areas))
        assertEquals("badezimmer", AreaAnswerRecognizer.areaId("im Bad bitte", areas))
    }

    @Test
    fun `Ablehnung, Floskeln und fremde Saetze sind NIE ein Raum`() {
        assertNull(AreaAnswerRecognizer.areaId("egal", areas))
        assertNull(AreaAnswerRecognizer.areaId("vergiss es", areas))
        assertNull(AreaAnswerRecognizer.areaId("nein danke", areas))
        assertNull(AreaAnswerRecognizer.areaId("wie wird das Wetter morgen?", areas))
        assertNull(AreaAnswerRecognizer.areaId("mach das Licht in der Küche an", areas))
        assertNull(AreaAnswerRecognizer.areaId("", areas))
    }
}
