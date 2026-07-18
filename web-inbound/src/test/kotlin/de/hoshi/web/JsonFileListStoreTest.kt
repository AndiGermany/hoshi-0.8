package de.hoshi.web

import de.hoshi.core.port.InMemoryListStore
import de.hoshi.core.port.ListEntry
import de.hoshi.core.port.ListPort
import de.hoshi.core.port.addWithDedupe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist den [JsonFileListStore] (Listen-Persistenz über den Backend-Neustart —
 * Andi-JA 2026-07-08, dasselbe Vertrauens-Kriterium, mit dem der Wecker-Store
 * abgenommen wurde):
 *  - **Restart-Überlebens-Beweis (PFLICHT-Fall):** Store A schreibt Items, Store B
 *    auf demselben Pfad liest sie — inkl. `quantity`/`listId` byte-genau;
 *  - fehlende/kaputte Datei ⇒ leer starten, KEIN Crash;
 *  - atomare Semantik: keine `.tmp`-Reste; Persist-Fehler ⇒ Mutation wirft und der
 *    Cache bleibt unangetastet (persist-then-commit, kein fake-grün);
 *  - Vertrag-Parität: identische Operationsfolgen liefern exakt die Ergebnisse der
 *    [InMemoryListStore]-Referenz.
 */
class JsonFileListStoreTest {

    private fun entry(id: String, text: String, quantity: Int = 1, addedAt: Long = 1_000L, listId: String = ListPort.DEFAULT_LIST_ID) =
        ListEntry(id = id, listId = listId, text = text, quantity = quantity, addedAtEpochMs = addedAt)

    // ── Restart-Überlebens-Beweis (der Kern) ─────────────────────────────────

    @Test
    fun `Neustart - neue Instanz auf gleicher Datei sieht alle Items byte-genau (PFLICHT-Fall)`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch", quantity = 2, addedAt = 1_000L))
        store.add(entry("b", "Butter", quantity = 1, addedAt = 2_000L))
        store.add(entry("c", "500 g Hack", quantity = 1, addedAt = 3_000L))

        val reloaded = JsonFileListStore(file) // „Backend-Neustart"
        assertEquals(listOf("a", "b", "c"), reloaded.items().map { it.id }, "sortiert nach addedAtEpochMs")
        assertEquals(entry("a", "Milch", quantity = 2, addedAt = 1_000L), reloaded.items()[0], "quantity ueberlebt byte-genau")
        assertEquals(entry("c", "500 g Hack", quantity = 1, addedAt = 3_000L), reloaded.items()[2], "Freitext-Item ueberlebt byte-genau")
    }

    @Test
    fun `Neustart - listId ueberlebt (Datenmodell-Naht fuer spaetere Zweit-Listen)`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch", listId = "einkauf"))
        store.add(entry("b", "Kino-Idee", listId = "notizen"))

        val reloaded = JsonFileListStore(file)
        val einkauf = reloaded.items("einkauf").single()
        assertEquals("a", einkauf.id)
        assertEquals("einkauf", einkauf.listId)
        assertEquals(listOf("b"), reloaded.items("notizen").map { it.id })
    }

    @Test
    fun `Neustart - remove ist persistent`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch"))
        store.add(entry("b", "Butter"))
        assertTrue(store.remove("a"))

        val reloaded = JsonFileListStore(file)
        assertEquals(listOf("b"), reloaded.items().map { it.id }, "das entfernte Item bleibt entfernt")
    }

    @Test
    fun `Neustart - clear ist persistent`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch"))
        store.add(entry("b", "Butter"))
        assertEquals(2, store.clear())

        val reloaded = JsonFileListStore(file)
        assertTrue(reloaded.items().isEmpty(), "clear ueberlebt den Neustart")
    }

    @Test
    fun `add mit gleicher id ueberschreibt - auch ueber den Neustart (Dedupe-Mechanismus)`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch", quantity = 1))
        store.add(entry("a", "Milch", quantity = 2)) // Re-add derselben id = Dedupe-Merge

        val reloaded = JsonFileListStore(file)
        assertEquals(listOf(entry("a", "Milch", quantity = 2)), reloaded.items(), "id ist Schlüssel, kein Duplikat")
    }

    @Test
    fun `addWithDedupe ueber echte Store-Instanz - zweites Ansagen mergt statt dupliziert`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.addWithDedupe(ListPort.DEFAULT_LIST_ID, "Milch", 1_000L) { "id-1" }
        val saved = store.addWithDedupe(ListPort.DEFAULT_LIST_ID, "milch", 2_000L) { "id-2" }

        assertEquals(1, store.items().size, "case-insensitiver Merge - kein Duplikat")
        assertEquals(2, saved.quantity)
        assertEquals("Milch", saved.text, "die erste Schreibweise gewinnt")
    }

    // ── Robustes Laden: fehlend/kaputt ⇒ leer, NIE crashen ───────────────────

    @Test
    fun `fehlende Datei - leer starten, kein Crash, keine Datei angelegt`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        assertTrue(store.items().isEmpty())
        assertFalse(Files.exists(file), "reines Konstruieren + items schreibt nichts")
    }

    @Test
    fun `kaputte Datei - leer starten, kein Crash`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        Files.writeString(file, "{ das ist kein gueltiges json ]]")
        val store = JsonFileListStore(file)
        assertTrue(store.items().isEmpty(), "kaputt ⇒ leer")
        // … und der Store ist danach voll benutzbar (naechster add ueberschreibt den Schrott):
        store.add(entry("a", "Milch"))
        assertEquals(listOf("a"), JsonFileListStore(file).items().map { it.id })
    }

    @Test
    fun `falsche JSON-Form (kein Array) und unbrauchbare Eintraege - tolerant`(@TempDir dir: Path) {
        val notArray = dir.resolve("not-array.json")
        Files.writeString(notArray, """{"id":"a"}""")
        assertTrue(JsonFileListStore(notArray).items().isEmpty(), "Objekt statt Array ⇒ leer")

        val partly = dir.resolve("partly.json")
        Files.writeString(
            partly,
            """[
              {"id":"ok","text":"Milch","addedAtEpochMs":1000},
              {"id":"","text":"Butter","addedAtEpochMs":2000},
              {"id":"notext","addedAtEpochMs":3000},
              {"id":"nodate","text":"Eier"},
              "kein objekt"
            ]""",
        )
        assertEquals(
            listOf("ok"),
            JsonFileListStore(partly).items().map { it.id },
            "gültige Einträge laden, unbrauchbare werden übersprungen",
        )
    }

    @Test
    fun `fehlende listId und quantity im JSON - tolerante Defaults (einkauf, 1)`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        Files.writeString(file, """[{"id":"a","text":"Milch","addedAtEpochMs":1000}]""")
        val loaded = JsonFileListStore(file).items().single()
        assertEquals(ListPort.DEFAULT_LIST_ID, loaded.listId)
        assertEquals(1, loaded.quantity)
    }

    // ── Atomare Semantik + persist-then-commit ───────────────────────────────

    @Test
    fun `Mutationen hinterlassen keine tmp-Reste, Zieldatei existiert`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        store.add(entry("a", "Milch"))
        store.add(entry("b", "Butter"))
        store.remove("a")
        store.clear()

        assertTrue(Files.exists(store.path), "Zieldatei muss nach Mutationen existieren")
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0L, leftovers, "atomarer Rename ⇒ keine .tmp-Reste")
    }

    @Test
    fun `Persist-Fehler - add wirft und committet den Cache NICHT`(@TempDir dir: Path) {
        // Parent des Pfads ist eine REGULAERE DATEI, kein Verzeichnis ⇒
        // Files.createDirectories(...) im Write wirft deterministisch (cross-platform).
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = JsonFileListStore(blocker.resolve("lists.json"))

        assertThrows(IOException::class.java) { store.add(entry("a", "Milch")) }
        // Kein fake-gruen: das Item, das nie auf der Platte ankam, existiert auch im Cache nicht.
        assertTrue(store.items().isEmpty(), "Persist-Fehler ⇒ Cache unveraendert (leer)")
        assertFalse(store.remove("a"), "nie committed ⇒ nichts zu entfernen")
    }

    @Test
    fun `no-op Mutationen schreiben nicht - remove unbekannt und clear leer`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        val store = JsonFileListStore(file)
        assertFalse(store.remove("nope"))
        assertEquals(0, store.clear())
        assertFalse(Files.exists(file), "no-op ⇒ kein Datei-Write")
    }

    // ── Privacy: Item-Text NIE geloggt (nur strukturelle Behauptung im KDoc,
    //    hier ein Rauch-Test, dass Laden/Schreiben ohne Logger-Fehler durchlaeuft) ──

    @Test
    fun `unbrauchbarer Eintrag wird uebersprungen ohne den Text zu brauchen`(@TempDir dir: Path) {
        val file = dir.resolve("lists.json")
        Files.writeString(file, """[{"id":"","text":"geheimes-item","addedAtEpochMs":1000}]""")
        assertTrue(JsonFileListStore(file).items().isEmpty())
    }

    // ── Vertrag-Paritaet zur InMemory-Referenz ───────────────────────────────

    private fun bothStores(dir: Path): List<ListPort> =
        listOf(InMemoryListStore(), JsonFileListStore(dir.resolve("parity.json")))

    @Test
    fun `Paritaet - add dann items liefert das Item`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            assertEquals(entry("a", "Milch"), store.add(entry("a", "Milch")), "add gibt das Item zurück")
            assertEquals(listOf("a"), store.items().map { it.id })
        }
    }

    @Test
    fun `Paritaet - items ist nach addedAtEpochMs sortiert`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.add(entry("late", "C", addedAt = 3_000L))
            store.add(entry("early", "A", addedAt = 1_000L))
            store.add(entry("mid", "B", addedAt = 2_000L))
            assertEquals(listOf("early", "mid", "late"), store.items().map { it.id })
        }
    }

    @Test
    fun `Paritaet - remove entfernt genau ein Item, unbekannt ist false`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.add(entry("a", "Milch"))
            store.add(entry("b", "Butter"))
            assertTrue(store.remove("a"))
            assertFalse(store.remove("a"), "zweites remove derselben id ⇒ false")
            assertFalse(store.remove("nope"))
            assertEquals(listOf("b"), store.items().map { it.id })
        }
    }

    @Test
    fun `Paritaet - clear leert und zaehlt`(@TempDir dir: Path) {
        for (store in bothStores(dir)) {
            store.add(entry("a", "Milch"))
            store.add(entry("b", "Butter"))
            assertEquals(2, store.clear())
            assertTrue(store.items().isEmpty())
            assertEquals(0, store.clear(), "leerer Store ⇒ 0")
        }
    }
}
