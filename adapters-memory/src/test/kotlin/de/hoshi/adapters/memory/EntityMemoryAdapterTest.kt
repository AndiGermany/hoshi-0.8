package de.hoshi.adapters.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EntityMemoryAdapterTest {

    private lateinit var dbPath: Path
    private lateinit var memory: EntityMemoryAdapter

    @BeforeEach
    fun setUp() {
        dbPath = Files.createTempFile("entity-memory-test", ".db")
        Files.deleteIfExists(dbPath) // frische DB-Datei, sqlite legt sie an
        memory = EntityMemoryAdapter(dbPath.toString())
    }

    @AfterEach
    fun tearDown() {
        memory.close()
        Files.deleteIfExists(dbPath)
    }

    @Test
    fun `store dann recall — gleicher Sprecher findet den Fakt`() {
        memory.remember("andi", "Mein Lieblingsessen ist Pizza.", "Lecker!")

        val block = memory.contextBlock("andi")
        assertNotNull(block, "andi muss einen Gedächtnis-Block bekommen")
        assertTrue(block!!.contains("lieblingsessen"), "Block trägt den Schlüssel: $block")
        assertTrue(block.contains("Pizza"), "Block erinnert Pizza: $block")
    }

    @Test
    fun `Mandanten-Trennung — anderer Sprecher findet den Fakt NICHT`() {
        memory.remember("andi", "Mein Lieblingsessen ist Pizza.", "Lecker!")

        // Bob teilt sich den Store, hat aber keine eigenen Fakten → kein Block,
        // und sieht keinesfalls andis Pizza.
        assertNull(memory.contextBlock("bob"), "bob darf andis Fakt NICHT sehen")
    }

    @Test
    fun `Gast wird nicht gespeichert und bekommt keinen Block`() {
        memory.remember("gast", "Mein Lieblingsessen ist Sushi.", "Mhm.")
        memory.remember("unknown", "Mein Lieblingsessen ist Pasta.", "Ok.")
        memory.remember("", "Mein Lieblingsessen ist Reis.", "Ok.")

        assertNull(memory.contextBlock("gast"))
        assertNull(memory.contextBlock("unknown"))
        assertNull(memory.contextBlock(""))
    }

    @Test
    fun `Persistenz — ein zweiter Adapter auf derselben Datei liest den Fakt`() {
        memory.remember("andi", "Mein Lieblingsessen ist Pizza.", "")
        memory.close()

        val reopened = EntityMemoryAdapter(dbPath.toString())
        try {
            val block = reopened.contextBlock("andi")
            assertNotNull(block)
            assertTrue(block!!.contains("Pizza"), "persistierter Fakt überlebt App-Boot: $block")
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `Extraktion ist deterministisch — Fragen tragen keine Fakten`() {
        assertEquals(
            listOf("lieblingsessen" to "Pizza"),
            EntityMemoryAdapter.extractFacts("Mein Lieblingsessen ist Pizza."),
        )
        assertEquals("name" to "Andi", EntityMemoryAdapter.extractFacts("Ich heiße Andi.").single())
        assertEquals("mag" to "Kaffee", EntityMemoryAdapter.extractFacts("Ich mag Kaffee.").single())
        // Eine Frage darf NICHT als Fakt durchgehen (sonst Müll im Store).
        assertTrue(EntityMemoryAdapter.extractFacts("Was ist mein Lieblingsessen?").isEmpty())
    }

    // ── DE „heißt"-Namensmuster (der ehemals fehlende ENTITY-Pfad) ───────────

    @Test
    fun `DE — mein Hund heißt Bello extrahiert (hund Bello)`() {
        assertEquals(
            "hund" to "Bello",
            EntityMemoryAdapter.extractFacts("Mein Hund heißt Bello.").single(),
        )
    }

    @Test
    fun `DE — einwortige Sache vor heißt (frau Anna)`() {
        assertEquals("frau" to "Anna", EntityMemoryAdapter.extractFacts("Meine Frau heißt Anna.").single())
    }

    @Test
    fun `DE — mehrwortige Sache vor heißt wird gefangen (bester freund Tom)`() {
        assertEquals(
            "bester freund" to "Tom",
            EntityMemoryAdapter.extractFacts("Mein bester Freund heißt Tom.").single(),
        )
    }

    @Test
    fun `Wie heißt mein Hund — als Frage extrahiert NICHTS`() {
        assertTrue(EntityMemoryAdapter.extractFacts("Wie heißt mein Hund?").isEmpty())
    }

    @Test
    fun `store dann recall — Wie heißt mein Hund liefert Bello via entity_facts`() {
        memory.remember("andi", "Mein Hund heißt Bello.", "Süßer Name!")

        val block = memory.contextBlock("andi")
        assertNotNull(block, "andi muss einen Gedächtnis-Block bekommen")
        assertTrue(block!!.contains("hund"), "Block trägt den Entity-key: $block")
        assertTrue(block.contains("Bello"), "Block erinnert Bello: $block")
    }

    // ── EN-Muster (DE+EN gefordert) ──────────────────────────────────────────

    @Test
    fun `EN — my name is Andi extrahiert (name Andi)`() {
        assertEquals("name" to "Andi", EntityMemoryAdapter.extractFacts("My name is Andi.").single())
    }

    @Test
    fun `EN — my dog's name is Bello extrahiert (dog Bello)`() {
        assertEquals("dog" to "Bello", EntityMemoryAdapter.extractFacts("My dog's name is Bello.").single())
    }

    @Test
    fun `EN — my dog is Bello extrahiert (dog Bello)`() {
        assertEquals("dog" to "Bello", EntityMemoryAdapter.extractFacts("My dog is Bello.").single())
    }

    @Test
    fun `EN — I am Andi extrahiert (name Andi)`() {
        assertEquals("name" to "Andi", EntityMemoryAdapter.extractFacts("I am Andi.").single())
    }

    @Test
    fun `EN — call me Andi extrahiert (name Andi)`() {
        assertEquals("name" to "Andi", EntityMemoryAdapter.extractFacts("Call me Andi.").single())
    }

    // ── Negative: kein Über-Fang aus Zuständen/Fragen ────────────────────────

    @Test
    fun `EN — I am tired extrahiert NICHTS (klein → kein Name)`() {
        assertTrue(EntityMemoryAdapter.extractFacts("I am tired.").isEmpty())
        assertTrue(EntityMemoryAdapter.extractFacts("I am happy today.").isEmpty())
    }

    @Test
    fun `EN — call me later extrahiert NICHTS (klein → kein Name)`() {
        assertTrue(EntityMemoryAdapter.extractFacts("Call me later.").isEmpty())
        assertTrue(EntityMemoryAdapter.extractFacts("Call me back.").isEmpty())
    }

    @Test
    fun `EN — Frage What's my dog's name extrahiert NICHTS`() {
        assertTrue(EntityMemoryAdapter.extractFacts("What's my dog's name?").isEmpty())
    }
}
