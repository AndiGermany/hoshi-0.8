package de.hoshi.web

import de.hoshi.core.port.InMemoryScheduledItemStore
import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist den [FileBackedScheduledItemStore] (Timer-Persistenz über den Backend-Neustart):
 *  - Roundtrip über „Neustart" (neue Instanz, gleiche Datei) inkl. kind/label/Sortierung;
 *  - fehlende/kaputte Datei ⇒ leer starten, KEIN Crash;
 *  - atomare Semantik: keine `.tmp`-Reste; Persist-Fehler ⇒ Mutation wirft und der Cache
 *    bleibt unangetastet (persist-then-commit, kein fake-grün);
 *  - Vertrag-Parität: identische Operationsfolgen liefern exakt die Ergebnisse der
 *    [InMemoryScheduledItemStore]-Referenz (deren Tests hier gespiegelt mitlaufen).
 */
class FileBackedScheduledItemStoreTest {

    private fun item(id: String, dueAt: Long, kind: ScheduledKind = ScheduledKind.TIMER, label: String? = null) =
        ScheduledItem(id = id, kind = kind, dueAtEpochMs = dueAt, label = label)

    // ── Roundtrip über „Neustart" ────────────────────────────────────────────

    @Test
    fun `Neustart - neue Instanz auf gleicher Datei sieht alle Items inkl kind und label`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("wecker", 7_000, kind = ScheduledKind.ALARM, label = "Aufstehen"))
        store.set(item("pizza", 3_000, kind = ScheduledKind.REMINDER, label = "Pizza"))
        store.set(item("timer", 5_000))

        val reloaded = FileBackedScheduledItemStore(file) // „Backend-Neustart"
        assertEquals(listOf("pizza", "timer", "wecker"), reloaded.query().map { it.id }, "sortiert nach Fälligkeit")
        assertEquals(
            item("wecker", 7_000, kind = ScheduledKind.ALARM, label = "Aufstehen"),
            reloaded.query().last(),
            "kind + label überleben den Neustart byte-genau",
        )
        assertEquals(item("timer", 5_000), reloaded.query()[1], "label=null überlebt als null")
    }

    @Test
    fun `Neustart - origin (Wecker-Ursprung) ueberlebt aktiv und gefeuert, byte-genau`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(ScheduledItem(id = "aktiv", kind = ScheduledKind.ALARM, dueAtEpochMs = 9_000, label = "Aufstehen", origin = "voice-pe-1"))
        store.set(ScheduledItem(id = "ohne", kind = ScheduledKind.TIMER, dueAtEpochMs = 8_000)) // origin=null
        store.add(
            FiredItem(id = "gefeuert", kind = ScheduledKind.TIMER, label = "Pizza", dueAtEpochMs = 4_500, firedAtEpochMs = 5_000, origin = "kueche-tab"),
        )

        val reloaded = FileBackedScheduledItemStore(file) // „Backend-Neustart"
        assertEquals("voice-pe-1", reloaded.query().first { it.id == "aktiv" }.origin, "aktiver origin ueberlebt")
        assertNull(reloaded.query().first { it.id == "ohne" }.origin, "fehlender origin bleibt null")
        assertEquals("kueche-tab", reloaded.pending(6_000).single { it.id == "gefeuert" }.origin, "gefeuerter origin ueberlebt")
    }

    // ── originSatelliteId (PREP-wecker-am-satelliten) — additiv, NUR bei aktiven Items ──

    @Test
    fun `Neustart - originSatelliteId ueberlebt bei aktiven Items, byte-genau`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(
            ScheduledItem(
                id = "am-satelliten", kind = ScheduledKind.TIMER, dueAtEpochMs = 9_000,
                label = "Nudeln", originSatelliteId = "sat-kueche",
            ),
        )
        store.set(ScheduledItem(id = "ohne", kind = ScheduledKind.TIMER, dueAtEpochMs = 8_000)) // originSatelliteId=null

        val reloaded = FileBackedScheduledItemStore(file) // „Backend-Neustart"
        assertEquals(
            "sat-kueche", reloaded.query().first { it.id == "am-satelliten" }.originSatelliteId,
            "originSatelliteId ueberlebt den Neustart",
        )
        assertNull(reloaded.query().first { it.id == "ohne" }.originSatelliteId, "fehlende Satelliten-Id bleibt null")
    }

    @Test
    fun `origin und originSatelliteId ueberleben gleichzeitig und unabhaengig`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(
            ScheduledItem(
                id = "beides", kind = ScheduledKind.ALARM, dueAtEpochMs = 9_000,
                origin = "kueche-tab", originSatelliteId = "sat-kueche",
            ),
        )

        val reloaded = FileBackedScheduledItemStore(file).query().single()
        assertEquals("kueche-tab", reloaded.origin)
        assertEquals("sat-kueche", reloaded.originSatelliteId)
    }

    @Test
    fun `Legacy-Item ohne originSatelliteId-Feld im JSON deserialisiert sauber mit null`(@TempDir dir: Path) {
        val file = dir.resolve("legacy-active.json")
        Files.writeString(
            file,
            """{"items":[{"id":"alt","kind":"TIMER","dueAtEpochMs":1000,"origin":"kueche-tab"}],"fired":[]}""",
        )
        val store = FileBackedScheduledItemStore(file)
        val item = store.query().single()
        assertEquals("kueche-tab", item.origin, "vorhandenes origin laedt weiterhin")
        assertNull(item.originSatelliteId, "fehlendes originSatelliteId-Feld ⇒ null, kein Crash")
    }

    @Test
    fun `Neustart - cancel ist persistent`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("a", 1_000))
        store.set(item("b", 2_000))
        assertTrue(store.cancel("a"))

        val reloaded = FileBackedScheduledItemStore(file)
        assertEquals(listOf("b"), reloaded.query().map { it.id }, "der stornierte Wecker bleibt storniert")
    }

    @Test
    fun `Neustart - cancelAll ist persistent`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("a", 1_000))
        store.set(item("b", 2_000))
        assertEquals(2, store.cancelAll())

        val reloaded = FileBackedScheduledItemStore(file)
        assertTrue(reloaded.query().isEmpty(), "cancelAll überlebt den Neustart")
    }

    @Test
    fun `set mit gleicher id ueberschreibt - auch ueber den Neustart`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("a", 1_000))
        store.set(item("a", 9_000, label = "verschoben"))

        val reloaded = FileBackedScheduledItemStore(file)
        assertEquals(listOf(item("a", 9_000, label = "verschoben")), reloaded.query(), "id ist Schlüssel, kein Duplikat")
    }

    // ── Robustes Laden: fehlend/kaputt ⇒ leer, NIE crashen ───────────────────

    @Test
    fun `fehlende Datei - leer starten, kein Crash, keine Datei angelegt`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        assertTrue(store.query().isEmpty())
        assertFalse(Files.exists(file), "reines Konstruieren + query schreibt nichts")
    }

    @Test
    fun `kaputte Datei - leer starten, kein Crash`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        Files.writeString(file, "{ das ist kein gueltiges json ]]")
        val store = FileBackedScheduledItemStore(file)
        assertTrue(store.query().isEmpty(), "kaputt ⇒ leer")
        // … und der Store ist danach voll benutzbar (nächster set überschreibt den Schrott):
        store.set(item("a", 1_000))
        assertEquals(listOf("a"), FileBackedScheduledItemStore(file).query().map { it.id })
    }

    @Test
    fun `falsche JSON-Form (kein Array) und unbrauchbare Eintraege - tolerant`(@TempDir dir: Path) {
        val notArray = dir.resolve("not-array.json")
        Files.writeString(notArray, """{"id":"a"}""")
        assertTrue(FileBackedScheduledItemStore(notArray).query().isEmpty(), "Objekt statt Array ⇒ leer")

        val partly = dir.resolve("partly.json")
        Files.writeString(
            partly,
            """[
              {"id":"ok","kind":"TIMER","dueAtEpochMs":1000},
              {"id":"","kind":"TIMER","dueAtEpochMs":2000},
              {"id":"badkind","kind":"GONG","dueAtEpochMs":3000},
              {"id":"nodue","kind":"TIMER"},
              "kein objekt"
            ]""",
        )
        assertEquals(
            listOf("ok"),
            FileBackedScheduledItemStore(partly).query().map { it.id },
            "gültige Einträge laden, unbrauchbare werden übersprungen",
        )
    }

    // ── Atomare Semantik + persist-then-commit ───────────────────────────────

    @Test
    fun `Mutationen hinterlassen keine tmp-Reste, Zieldatei existiert`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("a", 1_000))
        store.set(item("b", 2_000))
        store.cancel("a")
        store.cancelAll()

        assertTrue(Files.exists(store.path), "Zieldatei muss nach Mutationen existieren")
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0L, leftovers, "atomarer Rename ⇒ keine .tmp-Reste")
    }

    @Test
    fun `Persist-Fehler - set wirft und committet den Cache NICHT`(@TempDir dir: Path) {
        // Parent des Pfads ist eine REGULÄRE DATEI, kein Verzeichnis ⇒
        // Files.createDirectories(...) im Write wirft deterministisch (cross-platform).
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = FileBackedScheduledItemStore(blocker.resolve("scheduled-items.json"))

        assertThrows(IOException::class.java) { store.set(item("a", 1_000)) }
        // Kein fake-grün: der Wecker, der nie auf der Platte ankam, existiert auch im Cache nicht.
        assertTrue(store.query().isEmpty(), "Persist-Fehler ⇒ Cache unverändert (leer)")
        assertFalse(store.cancel("a"), "nie committed ⇒ nichts zu stornieren")
    }

    @Test
    fun `no-op Mutationen schreiben nicht - cancel unbekannt und cancelAll leer`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        assertFalse(store.cancel("nope"))
        assertEquals(0, store.cancelAll())
        assertFalse(Files.exists(file), "no-op ⇒ kein Datei-Write")
    }

    // ── Fired-Zustand: Restart-fest im SELBEN JSON-Store (Ring-1-Fix) ────────

    private fun firedItem(id: String, firedAt: Long, missed: Boolean = false) = FiredItem(
        id = id, kind = ScheduledKind.ALARM, label = "Aufstehen",
        dueAtEpochMs = firedAt - 500, firedAtEpochMs = firedAt, missed = missed,
    )

    @Test
    fun `Neustart - unbestaetigtes gefeuertes Item ueberlebt byte-genau`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.set(item("aktiv", 9_000))
        store.add(firedItem("w1", firedAt = 5_000))
        store.add(firedItem("w2", firedAt = 6_000, missed = true))

        val reloaded = FileBackedScheduledItemStore(file) // „Backend-Neustart"
        assertEquals(listOf("aktiv"), reloaded.query().map { it.id }, "aktive Items bleiben getrennt")
        val pending = reloaded.pending(nowMs = 6_500)
        assertEquals(listOf("w1", "w2"), pending.map { it.id }, "unbestaetigt gefeuerte ueberleben, aelteste zuerst")
        assertEquals(firedItem("w1", firedAt = 5_000), pending.first(), "alle Felder inkl. missed byte-genau")
        assertTrue(pending.last().missed, "persistiertes missed=true ueberlebt")
    }

    @Test
    fun `Neustart - ack ist persistent (quittiert bleibt quittiert)`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        store.add(firedItem("w1", firedAt = 5_000))
        store.add(firedItem("w2", firedAt = 6_000))
        assertTrue(store.ack("w1"))

        val reloaded = FileBackedScheduledItemStore(file)
        assertEquals(listOf("w2"), reloaded.pending(6_500).map { it.id }, "das quittierte Klingeln kommt nie wieder")
    }

    @Test
    fun `pending nach 30min unbestaetigt - missed=true, aber weiter abholbar`(@TempDir dir: Path) {
        val store = FileBackedScheduledItemStore(dir.resolve("scheduled-items.json"))
        store.add(firedItem("w1", firedAt = 5_000))

        assertFalse(store.pending(5_000 + FiredItemsStore.MISSED_AFTER_MS).single().missed, "exakt 30 min ⇒ normal")
        val late = store.pending(5_000 + FiredItemsStore.MISSED_AFTER_MS + 1)
        assertTrue(late.single().missed, "> 30 min unbestaetigt ⇒ missed=true (ehrlich statt still)")
        assertEquals(1, store.size(), "lesen konsumiert nichts")
    }

    @Test
    fun `ack unbekannter id - false ohne Datei-Write`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        assertFalse(store.ack("nope"))
        assertFalse(Files.exists(file), "no-op ⇒ kein Datei-Write")
    }

    @Test
    fun `Legacy-Format v1 (nacktes Array) laedt als aktive Items - fired leer`(@TempDir dir: Path) {
        val legacy = dir.resolve("legacy.json")
        Files.writeString(legacy, """[{"id":"alt","kind":"TIMER","dueAtEpochMs":1000}]""")
        val store = FileBackedScheduledItemStore(legacy)
        assertEquals(listOf("alt"), store.query().map { it.id }, "v1-Array = aktive Items (Migration ohne Verlust)")
        assertTrue(store.pending(0).isEmpty(), "kein fired-Abschnitt ⇒ nichts unbestaetigt")
    }

    @Test
    fun `unbrauchbare fired-Eintraege werden uebersprungen - der Rest laedt`(@TempDir dir: Path) {
        val file = dir.resolve("partly-fired.json")
        Files.writeString(
            file,
            """{
              "items": [],
              "fired": [
                {"id":"ok","kind":"TIMER","dueAtEpochMs":1000,"firedAtEpochMs":1500,"missed":false},
                {"id":"ohne-firedAt","kind":"TIMER","dueAtEpochMs":2000},
                {"id":"","kind":"TIMER","dueAtEpochMs":3000,"firedAtEpochMs":3500},
                "kein objekt"
              ]
            }""",
        )
        assertEquals(
            listOf("ok"),
            FileBackedScheduledItemStore(file).pending(1_600).map { it.id },
            "gueltige fired-Eintraege laden, unbrauchbare werden uebersprungen",
        )
    }

    @Test
    fun `fired-Ring - bei mehr als 20 unbestaetigten faellt das aelteste raus, auch persistent`(@TempDir dir: Path) {
        val file = dir.resolve("scheduled-items.json")
        val store = FileBackedScheduledItemStore(file)
        for (i in 1..25) store.add(firedItem("f$i", firedAt = 1_000L + i))

        assertEquals(FiredItemsStore.CAPACITY, store.size(), "Ring haelt genau ${FiredItemsStore.CAPACITY}")
        val reloaded = FileBackedScheduledItemStore(file)
        assertEquals("f6", reloaded.pending(2_000).first().id, "f1..f5 sind rausgefallen - auch auf der Platte")
        assertEquals("f25", reloaded.pending(2_000).last().id)
    }

    // ── Vertrag-Parität zur InMemory-Referenz ────────────────────────────────
    // Spiegelt ScheduledItemStoreTest: jede Operation muss auf beiden Impls dasselbe liefern.

    private fun bothStores(dir: Path): List<ScheduledItemPort> =
        listOf(InMemoryScheduledItemStore(), FileBackedScheduledItemStore(dir.resolve("parity.json")))

    @Test
    fun `Paritaet - set dann query liefert das Item`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            assertEquals(item("a", 1_000), store.set(item("a", 1_000)), "set gibt das Item zurück")
            assertEquals(listOf("a"), store.query().map { it.id })
        }
    }

    @Test
    fun `Paritaet - query ist nach Faelligkeit sortiert`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.set(item("late", 3_000))
            store.set(item("early", 1_000))
            store.set(item("mid", 2_000))
            assertEquals(listOf("early", "mid", "late"), store.query().map { it.id })
        }
    }

    @Test
    fun `Paritaet - cancel entfernt genau ein Item, unbekannt ist false`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.set(item("a", 1_000))
            store.set(item("b", 2_000))
            assertTrue(store.cancel("a"))
            assertFalse(store.cancel("a"), "zweites cancel derselben id ⇒ false")
            assertFalse(store.cancel("nope"))
            assertEquals(listOf("b"), store.query().map { it.id })
        }
    }

    @Test
    fun `Paritaet - cancelAll leert und zaehlt`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.set(item("a", 1_000))
            store.set(item("b", 2_000))
            assertEquals(2, store.cancelAll())
            assertTrue(store.query().isEmpty())
            assertEquals(0, store.cancelAll(), "leerer Store ⇒ 0")
        }
    }

    @Test
    fun `Paritaet fired - add, pending idempotent, ack`(@TempDir dir: Path) {
        val firedStores: List<FiredItemsStore> =
            listOf(InMemoryFiredItemsStore(), FileBackedScheduledItemStore(dir.resolve("fired-parity.json")))
        for (store in firedStores) {
            store.add(firedItem("w1", firedAt = 5_000))
            store.add(firedItem("w2", firedAt = 6_000))
            assertEquals(listOf("w1", "w2"), store.pending(6_500).map { it.id })
            assertEquals(listOf("w1", "w2"), store.pending(6_500).map { it.id }, "idempotent - zweiter Poller sieht dasselbe")
            assertTrue(store.ack("w1"))
            assertFalse(store.ack("w1"), "zweites ack derselben id ⇒ false")
            assertEquals(listOf("w2"), store.pending(6_500).map { it.id })
        }
    }
}
