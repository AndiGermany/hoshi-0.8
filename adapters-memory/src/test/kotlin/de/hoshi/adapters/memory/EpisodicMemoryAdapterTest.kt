package de.hoshi.adapters.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import de.hoshi.core.port.EpisodicWriter
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EpisodicMemoryAdapterTest {

    private lateinit var dbPath: Path
    private lateinit var memory: EpisodicMemoryAdapter

    /**
     * Deterministischer Bag-of-Words-Hash-Embedder (kein Netz): geteilte Wörter →
     * überlappende Dimensionen → positive Cosine; disjunkte Themen → ~0. Ersetzt im
     * Test die echte embeddinggemma-Naht, damit `verify` ohne Ollama grün bleibt.
     */
    private val fakeEmbedder = EpisodicEmbedder { text ->
        val dims = 64
        val v = DoubleArray(dims)
        text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { w ->
            v[((w.hashCode() % dims) + dims) % dims] += 1.0
        }
        v
    }

    @BeforeEach
    fun setUp() {
        dbPath = Files.createTempFile("episodic-memory-test", ".db")
        Files.deleteIfExists(dbPath) // frische DB-Datei, sqlite legt sie an
        memory = EpisodicMemoryAdapter(dbPath.toString(), embedder = fakeEmbedder, minSim = 0.5)
    }

    @AfterEach
    fun tearDown() {
        memory.close()
        Files.deleteIfExists(dbPath)
    }

    private val urlaubTurn = "Ich war letzte Woche in Italien im Urlaub am Meer"

    @Test
    fun `store dann recall — gleicher Sprecher findet vergangenen Gespraechskontext`() {
        memory.record("andi", urlaubTurn)

        val block = memory.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen").block()
        assertTrue(block!!.isNotEmpty(), "andi muss seinen früheren Turn wieder-erinnert bekommen: '$block'")
        assertTrue(block.contains("Urlaub"), "Recall-Block trägt den früheren Kontext: '$block'")
        assertTrue(block.startsWith("[Früher gesagt:"), "Recall-Block ist als solcher markiert: '$block'")
    }

    @Test
    fun `Mandanten-Trennung — anderer Sprecher findet den Kontext NICHT`() {
        memory.record("andi", urlaubTurn)

        // bob teilt sich den Store, hat aber keine eigenen Turns → kein Recall,
        // und sieht keinesfalls andis Urlaub.
        assertEquals("", memory.recallBlock("bob", "Wo war ich im Urlaub am Meer gewesen").block())
    }

    @Test
    fun `kein Treffer — unaehnliche Frage liefert keinen Recall`() {
        memory.record("andi", urlaubTurn)

        // Themenfremde Frage (Smart-Home) → Cosine unter der Schwelle → kein Block.
        assertEquals("", memory.recallBlock("andi", "Schalte bitte das Licht im Wohnzimmer aus").block())
    }

    @Test
    fun `Gast wird nicht persistiert und bekommt keinen Block`() {
        memory.record("gast", urlaubTurn)
        memory.record("unknown", urlaubTurn)
        memory.record("", urlaubTurn)

        assertEquals("", memory.recallBlock("gast", "Wo war ich im Urlaub").block())
        assertEquals("", memory.recallBlock("unknown", "Wo war ich im Urlaub").block())
        assertEquals("", memory.recallBlock("", "Wo war ich im Urlaub").block())
    }

    @Test
    fun `Persistenz — ein zweiter Adapter auf derselben Datei recallt den Turn`() {
        memory.record("andi", urlaubTurn)
        memory.close()

        val reopened = EpisodicMemoryAdapter(dbPath.toString(), embedder = fakeEmbedder, minSim = 0.5)
        try {
            val block = reopened.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen").block()
            assertTrue(block!!.contains("Urlaub"), "persistierter Turn überlebt App-Boot: '$block'")
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `Writer-Naht — Store-Hook speichert User-Turn und ist recallbar, anderer Sprecher nicht`() {
        // Genau der Pfad, den der ChatStreamController-Store-Hook NACH der Antwort
        // fährt: der Adapter als EpisodicWriter (3-arg). Der User-Turn wird gespeichert
        // (der answer geht NICHT in den Recall-Embedder), keyed by speakerId.
        val writer: EpisodicWriter = memory
        writer.record("andi", urlaubTurn, "Schön, dass du am Meer warst!")

        val block = memory.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen").block()
        assertTrue(block!!.contains("Urlaub"), "gleicher Sprecher erinnert seinen früheren Turn: '$block'")

        // Anderer Sprecher (gleicher geteilter Store) erinnert NICHT (Mandanten-Trennung).
        assertEquals("", memory.recallBlock("bob", "Wo war ich im Urlaub am Meer gewesen").block())
    }

    @Test
    fun `zu kurzer Turn wird nicht gesammelt`() {
        memory.record("andi", "Licht an") // < MIN_RECORD_LEN
        assertEquals("", memory.recallBlock("andi", "Licht an").block())
    }
}
