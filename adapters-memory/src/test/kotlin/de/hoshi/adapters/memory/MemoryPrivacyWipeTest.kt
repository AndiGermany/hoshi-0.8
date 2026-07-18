package de.hoshi.adapters.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * **MemoryPrivacyWipeTest** — beweist die Privacy-Lösch-Naht (`deleteAllFacts`/
 * `deleteAllTurns` + `countFacts`/`countTurns`) am Adapter selbst:
 *
 *  1. Fehlende Datei ⇒ count=`null` (ehrlich „weiß nicht"), delete=0 — und es wird
 *     NIE eine Datei angelegt (ein Wipe/Count erschafft keinen Store).
 *  2. Der Wipe löscht WIRKLICH (COUNT danach 0, Recall leer).
 *  3. **Die heilige Sicherheits-Zusage:** eine LIVE laufende Adapter-Instanz
 *     überlebt den externen Wipe (Zweit-Connection, nur `DELETE FROM`) — sie kann
 *     danach weiter speichern und recallen, keine Korruption, kein Reconnect.
 */
class MemoryPrivacyWipeTest {

    // ── Entity-Memory ────────────────────────────────────────────────────────

    @Test
    fun `entity - fehlende Datei - count null, delete 0, NICHTS angelegt`(@TempDir dir: Path) {
        val db = dir.resolve("entity-memory.db")

        assertNull(EntityMemoryAdapter.countFacts(db.toString()), "fehlende Datei ⇒ ehrlich null")
        assertEquals(0, EntityMemoryAdapter.deleteAllFacts(db.toString()))
        assertFalse(Files.exists(db), "Wipe/Count darf keinen Store anlegen")
    }

    @Test
    fun `entity - live Adapter ueberlebt externen Wipe und speichert weiter`(@TempDir dir: Path) {
        val db = dir.resolve("entity-memory.db")
        EntityMemoryAdapter(db.toString()).use { adapter ->
            adapter.remember("andi", "Mein Hund heißt Bello", "ok")
            assertEquals(1, EntityMemoryAdapter.countFacts(db.toString()))

            // Externer Wipe über die Zweit-Connection (exakt der PrivacyController-Pfad).
            assertEquals(1, EntityMemoryAdapter.deleteAllFacts(db.toString()))
            assertEquals(0, EntityMemoryAdapter.countFacts(db.toString()))
            assertNull(adapter.contextBlock("andi"), "nach Wipe kein Gedächtnis-Block mehr")

            // Überleben bewiesen: dieselbe Instanz speichert + recallt weiter.
            adapter.remember("andi", "Meine Katze heißt Mia", "ok")
            val block = adapter.contextBlock("andi")
            assertNotNull(block, "Adapter muss nach dem Wipe weiter funktionieren")
            assertTrue(block!!.contains("Mia"))
        }
    }

    // ── Episodic-Memory ──────────────────────────────────────────────────────

    @Test
    fun `episodic - fehlende Datei - count null, delete 0, NICHTS angelegt`(@TempDir dir: Path) {
        val db = dir.resolve("episodic-memory.db")

        assertNull(EpisodicMemoryAdapter.countTurns(db.toString()))
        assertEquals(0, EpisodicMemoryAdapter.deleteAllTurns(db.toString()))
        assertFalse(Files.exists(db), "Wipe/Count darf keinen Store anlegen")
    }

    @Test
    fun `episodic - live Adapter ueberlebt externen Wipe und speichert weiter`(@TempDir dir: Path) {
        val db = dir.resolve("episodic-memory.db")
        // Deterministischer Fake-Embedder — kein Netz, kein Ollama.
        val embedder = EpisodicEmbedder { doubleArrayOf(1.0, 0.5, 0.25) }
        EpisodicMemoryAdapter(dbPath = db.toString(), embedder = embedder).use { adapter ->
            adapter.record("andi", "Ich mag Sternschnuppen über dem Balkon")
            assertEquals(1, EpisodicMemoryAdapter.countTurns(db.toString()))

            assertEquals(1, EpisodicMemoryAdapter.deleteAllTurns(db.toString()))
            assertEquals(0, EpisodicMemoryAdapter.countTurns(db.toString()))
            assertEquals("", adapter.recallNow("andi", "Sternschnuppen"), "nach Wipe kein Recall mehr")

            // Überleben bewiesen: derselbe Text darf wieder rein (Dedupe-Zeile ist weg).
            adapter.record("andi", "Ich mag Sternschnuppen über dem Balkon")
            assertEquals(1, EpisodicMemoryAdapter.countTurns(db.toString()))
            assertTrue(adapter.recallNow("andi", "Sternschnuppen").contains("Sternschnuppen"))
        }
    }
}
