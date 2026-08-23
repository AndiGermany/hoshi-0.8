package de.hoshi.web

import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * **DeletedAlarmNeverRingsTest** — der Riegel gegen Andis Live-Befund 23.08.2026:
 * „Ich habe einen Wecker gestellt, gelöscht — und heute morgen beim Ausklappen ging
 * der Wecker trotzdem."
 *
 * **Die bewiesene Lücke:** die Wecker-Lane hat ZWEI Wahrheiten — den geplanten Wecker
 * ([ScheduledItemPort], „aktiv") und das bereits ausgelöste Klingeln ([FiredItemsStore],
 * „gefeuert, unbestätigt"). Der VOICE-Löschweg kennt seit dem 15.07-Fix beide
 * ([de.hoshi.core.pipeline.TimerFastpath] + [de.hoshi.core.port.RingingItemPort]:
 * „stoppe den Timer" beendet auch ein laufendes Klingeln). Der **FE-/HTTP-Löschweg**
 * (`DELETE /api/v1/scheduled/{id}`, das ✕ im ScheduledPanel) bekam diese Vereinheitlichung
 * NIE: er storniert nur den aktiven Eintrag. Hat der Fire-Service den Wecker in der
 * Zwischenzeit gefeuert (der FE-Poll ist bis zu 15 s alt — bei dunklem Display pausiert
 * sein Intervall sogar ganz, die Zeile steht also noch da), dann
 *  - antwortet der Löschweg **404** („kenne ich nicht"), und das FE wertet 404 laut
 *    eigenem Contract als „weg is weg" (`deleteScheduledItem`: `res.ok || 404`),
 *  - das Klingeln lebt aber weiter: es steht unbestätigt im [FiredItemsStore], überlebt
 *    dort JEDEN Neustart (Datei-Persistenz) und wird von jedem FE-Poll erneut ausgeliefert
 *    — bis es jemand quittiert.
 *
 * Genau das ist „gelöscht und klingelt trotzdem": der Mensch hat gelöscht, die Liste ist
 * leer, und trotzdem klingelt es (bei Andi in dem Moment, in dem das Display wieder
 * aufgeklappt/entsperrt wurde — der erste Poll bzw. die erste Geste liefert das Klingeln
 * dann ab).
 *
 * Der Riegel: **Löschen heißt weg — in JEDER Wahrheit.** Ein gelöschter Wecker klingelt nie.
 */
class DeletedAlarmNeverRingsTest {

    /** Fixe „Weckzeit" (Epoch-ms) — alle Tests rechnen relativ dazu, nichts hängt an der Wanduhr. */
    private val weckzeit = 1_750_000_000_000L

    private fun clockAt(ms: Long): Clock = Clock.fixed(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

    /** Der ECHTE Prod-Store (file-backed ⇒ zugleich [FiredItemsStore], exakt das Wiring in PipelineConfig). */
    private fun store(dir: Path, name: String = "scheduled-items.json") =
        FileBackedScheduledItemStore(dir.resolve(name))

    /** Der echte Fire-Service auf fixer Uhr — [ScheduledItemFireService.pollOnce] statt Poll-Thread. */
    private fun fireAt(store: FileBackedScheduledItemStore, ms: Long) =
        ScheduledItemFireService(store = store, fired = store, enabled = true, clock = clockAt(ms))

    /** Der FE-Löschweg: derselbe Controller, den `DELETE /api/v1/scheduled/{id}` bedient. */
    private fun feLoeschweg(store: FileBackedScheduledItemStore) = ScheduledItemsController(store, store)

    private fun wecker(id: String, dueAt: Long) =
        ScheduledItem(id = id, kind = ScheduledKind.ALARM, dueAtEpochMs = dueAt, label = "Aufstehen")

    // ── Der Bestand: löschen VOR der Fälligkeit (war schon immer dicht) ───────

    @Test
    fun `geloescht vor der Faelligkeit - der Fire-Service feuert nie`(@TempDir dir: Path) {
        val store = store(dir)
        store.set(wecker("a1", weckzeit))

        assertEquals(204, feLoeschweg(store).cancel("a1").statusCode.value(), "204: entfernt")

        fireAt(store, weckzeit).pollOnce() // die Weckzeit kommt trotzdem …
        assertTrue(store.query().isEmpty(), "nichts Geplantes mehr")
        assertTrue(store.pending(weckzeit).isEmpty(), "… und nichts klingelt")
    }

    // ── Der Live-Bug: löschen, während es schon gefeuert hat ──────────────────

    @Test
    fun `geloescht nachdem es gefeuert hat - das Klingeln ist mit weg`(@TempDir dir: Path) {
        val store = store(dir)
        store.set(wecker("a1", weckzeit))
        // Der Fire-Service ist bei der Weckzeit da (Poll ~1 s); das FE-Panel zeigt die Zeile
        // noch (sein Poll ist bis zu 15 s alt, bei dunklem Display länger) — Andi tippt ✕.
        fireAt(store, weckzeit).pollOnce()
        assertEquals(listOf("a1"), store.pending(weckzeit).map { it.id }, "Vorbedingung: es klingelt")

        val antwort = feLoeschweg(store).cancel("a1")

        assertTrue(store.pending(weckzeit).isEmpty(), "GELÖSCHT HEISST WEG — auch das schon laufende Klingeln")
        assertEquals(204, antwort.statusCode.value(), "und der Löschweg sagt ehrlich 'entfernt' statt 404")
    }

    @Test
    fun `alle loeschen - auch das laufende Klingeln ist weg`(@TempDir dir: Path) {
        val store = store(dir)
        store.set(wecker("a1", weckzeit))
        store.set(wecker("a2", weckzeit + 60_000))
        fireAt(store, weckzeit).pollOnce() // a1 klingelt, a2 ist noch geplant

        val entfernt = feLoeschweg(store).cancelAll().count

        assertTrue(store.query().isEmpty(), "keine geplanten mehr")
        assertTrue(store.pending(weckzeit).isEmpty(), "und kein Klingeln mehr")
        assertEquals(2, entfernt, "gezählt wird, was der Mensch losgeworden ist: geplant UND klingelnd")
    }

    @Test
    fun `nach dem Loeschen ueberlebt kein Klingeln den Neustart`(@TempDir dir: Path) {
        val datei = dir.resolve("scheduled-items.json")
        FileBackedScheduledItemStore(datei).let { store ->
            store.set(wecker("a1", weckzeit))
            ScheduledItemFireService(store = store, fired = store, enabled = true, clock = clockAt(weckzeit)).pollOnce()
            feLoeschweg(store).cancel("a1")
        }

        val nachNeustart = FileBackedScheduledItemStore(datei) // „Backend-Neustart"
        assertTrue(nachNeustart.query().isEmpty(), "nichts Geplantes überlebt")
        assertTrue(nachNeustart.pending(weckzeit).isEmpty(), "und das gelöschte Klingeln auch nicht")
    }

    // ── Gegenprobe: der NICHT gelöschte Wecker klingelt unverändert weiter ────

    @Test
    fun `nicht geloescht - der Wecker klingelt weiter (Grundverhalten unangetastet)`(@TempDir dir: Path) {
        val store = store(dir)
        store.set(wecker("a1", weckzeit))
        fireAt(store, weckzeit).pollOnce()

        // Ein FREMDES Löschen (andere id) darf das Klingeln NICHT wegräumen.
        assertEquals(404, feLoeschweg(store).cancel("ein-anderer").statusCode.value(), "unbekannte id ⇒ 404")
        assertEquals(listOf("a1"), store.pending(weckzeit).map { it.id }, "der echte Wecker klingelt weiter")
    }

    @Test
    fun `ein zweiter Wecker bleibt geplant, wenn nur der erste geloescht wird`(@TempDir dir: Path) {
        val store = store(dir)
        store.set(wecker("a1", weckzeit))
        store.set(wecker("a2", weckzeit + 3_600_000))
        fireAt(store, weckzeit).pollOnce()

        feLoeschweg(store).cancel("a1")

        assertEquals(listOf("a2"), store.query().map { it.id }, "der zweite Wecker bleibt unangetastet")
        assertTrue(store.pending(weckzeit).isEmpty())
    }
}
