package de.hoshi.web

import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Beweist den [ScheduledItemFireService] + [FiredItemsStore] (die Klingel-Mechanik,
 * deterministisch mit fixer [Clock] ueber [ScheduledItemFireService.pollOnce] — kein
 * Scheduler, kein Sleep im Kernpfad):
 *  - Fire bei Faelligkeit: raus aus dem Store, rein in den Fired-Store (mit firedAtEpochMs);
 *  - noch-nicht-faellig bleibt unangetastet;
 *  - Ueberfaellig-Politik v2: > 5 min ueber-faellig ⇒ MIT `missed=true` gefeuert
 *    (ehrlich statt still verwerfen); exakt an der Grenze klingelt es normal;
 *  - Fired-Store: pending ist IDEMPOTENT (zwei parallele Poller sehen BEIDE dasselbe),
 *    erst ack raeumt (fuer beide); > 30 min unbestaetigt ⇒ beim Lesen `missed=true`;
 *  - Ring: mehr als 20 unbestaetigte ⇒ das aelteste faellt raus;
 *  - Flag OFF ⇒ [ScheduledItemFireService.start] startet KEINEN Scheduler;
 *  - Flag ON ⇒ der echte Scheduler feuert wirklich (einziger async Test).
 */
class ScheduledItemFireServiceTest {

    private val now = 1_750_000_000_000L
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private fun item(id: String, dueAt: Long, kind: ScheduledKind = ScheduledKind.TIMER, label: String? = null) =
        ScheduledItem(id = id, kind = kind, dueAtEpochMs = dueAt, label = label)

    private fun service(
        store: InMemoryScheduledItemStore,
        fired: FiredItemsStore,
        enabled: Boolean = true,
        clock: Clock = fixedClock,
        pollIntervalMs: Long = 10,
        onFired: (id: String, label: String?, originSatelliteId: String?) -> Unit = { _, _, _ -> },
    ) = ScheduledItemFireService(
        store = store,
        fired = fired,
        enabled = enabled,
        clock = clock,
        pollIntervalMs = pollIntervalMs,
        onFired = onFired,
    )

    // ── Fire bei Faelligkeit (fixe Clock, pollOnce direkt) ───────────────────

    @Test
    fun `faellig - pollOnce entfernt aus dem Store und legt in den Fired-Store`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("due-genau", dueAt = now, kind = ScheduledKind.ALARM, label = "Aufstehen"))
        store.set(item("due-knapp", dueAt = now - 1_000))
        store.set(item("zukunft", dueAt = now + 1_000))

        service(store, fired).pollOnce()

        assertEquals(listOf("zukunft"), store.query().map { it.id }, "nur das nicht-faellige bleibt im Store")
        val pending = fired.pending(now)
        assertEquals(listOf("due-knapp", "due-genau"), pending.map { it.id }, "beide faelligen gefeuert, aelteste Faelligkeit zuerst")
        val alarm = pending.single { it.id == "due-genau" }
        assertEquals(ScheduledKind.ALARM, alarm.kind)
        assertEquals("Aufstehen", alarm.label)
        assertEquals(now, alarm.dueAtEpochMs)
        assertEquals(now, alarm.firedAtEpochMs, "firedAtEpochMs = Poll-Zeitpunkt der fixen Clock")
        assertFalse(alarm.missed, "puenktlich gefeuert ⇒ nicht verpasst")
    }

    @Test
    fun `origin des Items wandert beim Feuern ins FiredItem, null bleibt null`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(
            ScheduledItem(id = "mit-origin", kind = ScheduledKind.ALARM, dueAtEpochMs = now, label = "Aufstehen", origin = "voice-pe-1"),
        )
        store.set(ScheduledItem(id = "ohne-origin", kind = ScheduledKind.TIMER, dueAtEpochMs = now))

        service(store, fired).pollOnce()

        val pending = fired.pending(now)
        assertEquals("voice-pe-1", pending.single { it.id == "mit-origin" }.origin, "origin wird beim Feuern durchgereicht")
        assertNull(pending.single { it.id == "ohne-origin" }.origin, "kein origin ⇒ null (FE klingelt ueberall)")
    }

    // ── onFired-Hook (PREP-wecker-am-satelliten, Scheibe 2) ──────────────────

    @Test
    fun `onFired-Hook feuert genau einmal je gefeuertem Item mit id, label und originSatelliteId`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(
            ScheduledItem(
                id = "am-satelliten", kind = ScheduledKind.ALARM, dueAtEpochMs = now,
                label = "Aufstehen", originSatelliteId = "sat-kueche",
            ),
        )
        val calls = mutableListOf<Triple<String, String?, String?>>()

        service(store, fired, onFired = { id, label, originSatelliteId -> calls.add(Triple(id, label, originSatelliteId)) })
            .pollOnce()

        assertEquals(listOf(Triple("am-satelliten", "Aufstehen", "sat-kueche")), calls, "genau ein Aufruf mit den richtigen Feldern")
    }

    @Test
    fun `onFired-Hook feuert auch mit originSatelliteId=null - der Aufrufer entscheidet, ob er reagiert`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(ScheduledItem(id = "chat-timer", kind = ScheduledKind.TIMER, dueAtEpochMs = now))
        val calls = mutableListOf<String?>()

        service(store, fired, onFired = { _, _, originSatelliteId -> calls.add(originSatelliteId) }).pollOnce()

        assertEquals(listOf<String?>(null), calls, "Hook wird trotzdem gerufen, originSatelliteId ist ehrlich null")
    }

    @Test
    fun `ohne Wiring (No-op-Default) bleibt pollOnce unveraendert - kein Crash`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("a", dueAt = now - 1))

        service(store, fired).pollOnce() // Default onFired = No-op

        assertEquals(1, fired.size(), "Fire-Mechanik selbst bleibt unveraendert")
    }

    @Test
    fun `noch nicht faellig - pollOnce ist ein No-op`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("zukunft", dueAt = now + 1))

        service(store, fired).pollOnce()

        assertEquals(listOf("zukunft"), store.query().map { it.id }, "Item bleibt im Store")
        assertEquals(0, fired.size(), "nichts gefeuert")
    }

    // ── Ueberfaellig-Politik v2: ehrlich statt still verwerfen ───────────────

    @Test
    fun `ueber 5min ueber-faellig - feuert MIT missed=true statt still verwerfen`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        val grace = ScheduledItemFireService.FIRED_LATE_AFTER_MS
        store.set(item("uralt", dueAt = now - grace - 1, kind = ScheduledKind.ALARM, label = "verschlafen"))

        service(store, fired).pollOnce()

        assertTrue(store.query().isEmpty(), "gefeuert heisst auch: raus aus dem Store (kein ewiges Re-Poll)")
        val pending = fired.pending(now)
        assertEquals(listOf("uralt"), pending.map { it.id }, "NICHT verworfen - der User erfaehrt es")
        assertTrue(pending.single().missed, "Downtime-Nachzuegler ⇒ missed=true (kein Geister-Klingeln, ehrliche Meldung)")
        assertEquals("verschlafen", pending.single().label)
    }

    @Test
    fun `exakt an der 5min-Grenze - feuert ohne missed`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        val grace = ScheduledItemFireService.FIRED_LATE_AFTER_MS
        store.set(item("grenzfall", dueAt = now - grace))

        service(store, fired).pollOnce()

        val pending = fired.pending(now)
        assertEquals(listOf("grenzfall"), pending.map { it.id }, "<= Gnadenfrist ⇒ normal feuern")
        assertFalse(pending.single().missed)
    }

    // ── Fired-Store: idempotent lesen, ack raeumt, 30-min-missed ─────────────

    @Test
    fun `pending ist idempotent - zwei parallele Poller sehen BEIDE das Item`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("a", dueAt = now - 1))
        service(store, fired).pollOnce()

        val tab1 = fired.pending(now)
        val tab2 = fired.pending(now)
        assertEquals(listOf("a"), tab1.map { it.id }, "erster Poller sieht das Klingeln")
        assertEquals(listOf("a"), tab2.map { it.id }, "zweiter Poller sieht es AUCH (kein consume-once mehr)")
        assertEquals(1, fired.size(), "lesen konsumiert nichts")
    }

    @Test
    fun `ack raeumt das Item - fuer alle Poller, zweites ack ist false`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("a", dueAt = now - 1))
        service(store, fired).pollOnce()

        assertTrue(fired.ack("a"), "erstes ack quittiert")
        assertTrue(fired.pending(now).isEmpty(), "nach ack sieht KEIN Poller mehr etwas")
        assertFalse(fired.ack("a"), "zweites ack derselben id ⇒ false (schon weg)")
        assertFalse(fired.ack("nope"), "unbekannte id ⇒ false")
    }

    @Test
    fun `laenger als 30min unbestaetigt - pending liefert missed=true, Item bleibt abholbar`() {
        val fired = InMemoryFiredItemsStore()
        fired.add(FiredItem(id = "lang", kind = ScheduledKind.TIMER, dueAtEpochMs = now, firedAtEpochMs = now))

        val justInside = fired.pending(now + FiredItemsStore.MISSED_AFTER_MS)
        assertFalse(justInside.single().missed, "exakt 30 min ⇒ noch normal")

        val after = fired.pending(now + FiredItemsStore.MISSED_AFTER_MS + 1)
        assertEquals(listOf("lang"), after.map { it.id }, "bleibt abholbar - NICHT still verworfen")
        assertTrue(after.single().missed, "> 30 min unbestaetigt ⇒ missed=true (ehrliche Verpasst-Meldung)")
        assertEquals(1, fired.size(), "die missed-Markierung konsumiert nichts")
    }

    @Test
    fun `Fired-Store ist ein Ring - bei mehr als 20 faellt das aelteste raus`() {
        val fired = InMemoryFiredItemsStore()
        for (i in 1..25) {
            fired.add(FiredItem(id = "f$i", kind = ScheduledKind.TIMER, dueAtEpochMs = i.toLong(), firedAtEpochMs = now + i))
        }
        val pending = fired.pending(now)
        assertEquals(FiredItemsStore.CAPACITY, pending.size, "Ring haelt genau die letzten ${FiredItemsStore.CAPACITY}")
        assertEquals("f6", pending.first().id, "f1..f5 sind rausgefallen")
        assertEquals("f25", pending.last().id)
    }

    // ── Flag-Gate + echter Scheduler ─────────────────────────────────────────

    @Test
    fun `Flag OFF - start startet keinen Scheduler`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("faellig-aber-aus", dueAt = now - 1))
        val svc = service(store, fired, enabled = false)

        svc.start()

        assertFalse(svc.isRunning, "enabled=false ⇒ kein Poll-Thread")
        assertEquals(listOf("faellig-aber-aus"), store.query().map { it.id }, "Store unangetastet")
        assertEquals(0, fired.size(), "nichts gefeuert")
        svc.close() // idempotent, auch ohne Start sicher
    }

    @Test
    fun `Flag ON - der echte Scheduler feuert ein faelliges Item`() {
        val store = InMemoryScheduledItemStore()
        val fired = InMemoryFiredItemsStore()
        store.set(item("echt", dueAt = now - 1_000, label = "Pizza"))
        val svc = service(store, fired, enabled = true, pollIntervalMs = 10)

        svc.start()
        try {
            assertTrue(svc.isRunning, "enabled=true ⇒ Scheduler laeuft")
            // Auf den async Fire warten (max ~2s, Poll laeuft alle 10ms).
            val deadline = System.currentTimeMillis() + 2_000
            while (fired.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(listOf("echt"), fired.pending(now).map { it.id }, "der Scheduler-Poll hat gefeuert")
            assertTrue(store.query().isEmpty(), "und aus dem Store entfernt")
        } finally {
            svc.close()
        }
        assertFalse(svc.isRunning, "close faehrt den Scheduler herunter")
    }
}
