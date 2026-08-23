package de.hoshi.adapters.ha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * **HaLastKnownStateStoreTest** — der Persistenz-Vertrag des „Sauger-
 * Sichtbarkeits-Lücke"-Speichers (Andi-Auftrag 2026-08-13), UNABHÄNGIG vom
 * [HaHomeRegistryAdapter] (der die Merge-/Fallback-Logik testet, s.
 * [HaHomeRegistryAdapterTest]). Deckt: Roundtrip über einen echten Neustart
 * (frischer Store, dieselbe Datei), kaputte/fehlende Datei ⇒ leer starten
 * (never-throw), und die Schreib-Drossel (nur bei echter Wertänderung, nicht
 * bei jeder `record`-Runde mit identischen Werten).
 */
class HaLastKnownStateStoreTest {

    private fun reading(state: String, seenAt: Instant, attrs: Map<String, String> = emptyMap()) =
        LastKnownState(state = state, attrs = attrs, seenAt = seenAt)

    // ── Roundtrip: ein FRISCHER Store liest, was ein VORHERIGER geschrieben hat ──
    @Test
    fun `Persistenz-Roundtrip - ein neuer Store liest den Stand des alten Stores von der Platte`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val store1 = HaLastKnownStateStore(path)
        val seenAt = Instant.parse("2026-08-13T20:03:00Z")
        store1.record(mapOf("vacuum.rob" to reading("docked", seenAt, mapOf("battery_level" to "82"))))
        assertTrue(Files.exists(path))

        val store2 = HaLastKnownStateStore(path)
        val got = store2.get("vacuum.rob")
        assertEquals("docked", got?.state)
        assertEquals("82", got?.attrs?.get("battery_level"))
        assertEquals(seenAt, got?.seenAt)
    }

    @Test
    fun `Roundtrip ueber mehrere Entities gleichzeitig`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val seenAt = Instant.parse("2026-08-13T20:03:00Z")
        val store1 = HaLastKnownStateStore(path)
        store1.record(
            mapOf(
                "vacuum.rob" to reading("docked", seenAt),
                "climate.wz" to reading("heat", seenAt, mapOf("current_temperature" to "21.5", "temperature" to "22")),
            ),
        )

        val store2 = HaLastKnownStateStore(path)
        assertEquals("docked", store2.get("vacuum.rob")?.state)
        assertEquals("heat", store2.get("climate.wz")?.state)
        assertEquals("21.5", store2.get("climate.wz")?.attrs?.get("current_temperature"))
        assertNull(store2.get("light.unbekannt"), "nie gemerkte Entity ⇒ null")
    }

    // ── kaputte/fehlende Datei ⇒ leer starten, NIE werfen ─────────────────────
    @Test
    fun `fehlende Datei - Store startet leer, kein Fehler`(@TempDir tmp: Path) {
        val path = tmp.resolve("nie-angelegt.json")
        val store = HaLastKnownStateStore(path)
        assertNull(store.get("vacuum.rob"))
    }

    @Test
    fun `kaputtes JSON in der Datei - Store startet leer statt zu werfen`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "das ist kein JSON {{{")
        val store = HaLastKnownStateStore(path)
        assertNull(store.get("vacuum.rob"))
    }

    @Test
    fun `JSON-Array statt Objekt in der Datei - Store startet leer statt zu werfen`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "[1, 2, 3]")
        val store = HaLastKnownStateStore(path)
        assertNull(store.get("vacuum.rob"))
    }

    @Test
    fun `einzelner kaputter Eintrag (state fehlt) wird uebersprungen, Nachbar-Eintrag bleibt gueltig`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """{"vacuum.kaputt":{"seenAt":"2026-08-13T20:03:00Z"},"vacuum.rob":{"state":"docked","seenAt":"2026-08-13T20:03:00Z","attrs":{}}}""",
        )
        val store = HaLastKnownStateStore(path)
        assertNull(store.get("vacuum.kaputt"), "state fehlt ⇒ dieser Eintrag ist ehrlich weg")
        assertEquals("docked", store.get("vacuum.rob")?.state, "der gueltige Nachbar-Eintrag bleibt unberuehrt")
    }

    // ── Drossel: nur bei ECHTER Wertaenderung wird geschrieben ────────────────
    @Test
    fun `Drossel - identische Werte in Folge-Runden schreiben die Datei NUR einmal`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val store = HaLastKnownStateStore(path)
        val t1 = Instant.parse("2026-08-13T20:03:00Z")
        val t2 = t1.plusSeconds(60)
        val t3 = t1.plusSeconds(120)

        store.record(mapOf("vacuum.rob" to reading("docked", t1)))
        assertEquals(1, store.writeCount, "erste Ablesung ⇒ erster Schreibzugriff")

        store.record(mapOf("vacuum.rob" to reading("docked", t2))) // gleicher state/attrs, nur neueres seenAt
        assertEquals(1, store.writeCount, "unveraenderter Wert ⇒ KEIN zweiter Schreibzugriff (Drossel)")

        store.record(mapOf("vacuum.rob" to reading("docked", t3)))
        assertEquals(1, store.writeCount, "auch eine dritte identische Runde bleibt gedrosselt")

        // RAM bleibt trotzdem IMMER frisch (die Drossel betrifft NUR die Platte).
        assertEquals(t3, store.get("vacuum.rob")?.seenAt, "der RAM-Cache traegt das NEUESTE seenAt, auch ohne Schreibzugriff")
    }

    @Test
    fun `Drossel - ein echter Wertwechsel loest sofort wieder einen Schreibzugriff aus`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val store = HaLastKnownStateStore(path)
        val t1 = Instant.parse("2026-08-13T20:03:00Z")
        val t2 = t1.plusSeconds(60)

        store.record(mapOf("vacuum.rob" to reading("docked", t1)))
        assertEquals(1, store.writeCount)

        store.record(mapOf("vacuum.rob" to reading("cleaning", t2))) // state aendert sich
        assertEquals(2, store.writeCount, "ein echter Wertwechsel muss die Drossel durchbrechen")

        // ...und die geaenderte Datei ist auch tatsaechlich das, was ein neuer Store lesen wuerde.
        val restarted = HaLastKnownStateStore(path)
        assertEquals("cleaning", restarted.get("vacuum.rob")?.state)
    }

    @Test
    fun `Drossel - ein neues Attribut zaehlt ebenfalls als Aenderung`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val store = HaLastKnownStateStore(path)
        val t1 = Instant.parse("2026-08-13T20:03:00Z")

        store.record(mapOf("vacuum.rob" to reading("docked", t1, mapOf("battery_level" to "82"))))
        assertEquals(1, store.writeCount)

        store.record(mapOf("vacuum.rob" to reading("docked", t1.plusSeconds(60), mapOf("battery_level" to "83"))))
        assertEquals(2, store.writeCount, "ein geaendertes Attribut zaehlt als Aenderung, auch bei gleichem state")
    }

    @Test
    fun `leere readings-Map ist ein No-Op - kein Schreibzugriff, kein Fehler`(@TempDir tmp: Path) {
        val path = tmp.resolve("last-known.json")
        val store = HaLastKnownStateStore(path)
        store.record(emptyMap())
        assertEquals(0, store.writeCount)
        assertTrue(!Files.exists(path), "eine leere Runde darf keine Datei anlegen")
    }

    // ── InMemoryLastKnownStateStore (Adapter-Default) — reines RAM-Verhalten ──
    @Test
    fun `InMemoryLastKnownStateStore merkt innerhalb des Prozesses, ohne jede Datei`() {
        val store = InMemoryLastKnownStateStore()
        val t1 = Instant.parse("2026-08-13T20:03:00Z")
        assertNull(store.get("vacuum.rob"))
        store.record(mapOf("vacuum.rob" to reading("docked", t1)))
        assertEquals("docked", store.get("vacuum.rob")?.state)
    }
}
