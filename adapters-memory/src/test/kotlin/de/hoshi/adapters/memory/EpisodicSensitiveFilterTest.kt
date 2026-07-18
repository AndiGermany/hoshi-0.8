package de.hoshi.adapters.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests des **Sensitive-Marker-Gates vor Episodic-Persist** (Flag
 * `HOSHI_EPISODIC_SENSITIVE_FILTER_ENABLED`). Belegt: Flag ON + sensibler Turn →
 * NICHT persistiert (kein Recall); Flag ON + harmloser Turn → normal persistiert;
 * Flag OFF → alles persistiert (byte-neutral, heutiges Verhalten). Temp-DB + fake
 * Embedder wie [EpisodicMemoryAdapterTest] (deterministisch, kein Netz).
 */
class EpisodicSensitiveFilterTest {

    private lateinit var dbPath: Path

    /** Identischer Bag-of-Words-Hash-Embedder wie in [EpisodicMemoryAdapterTest]. */
    private val fakeEmbedder = EpisodicEmbedder { text ->
        val dims = 64
        val v = DoubleArray(dims)
        text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { w ->
            v[((w.hashCode() % dims) + dims) % dims] += 1.0
        }
        v
    }

    // Sensibler Turn (Gesundheit) + naher Recall-Query (hohe Wort-Überlappung).
    private val sensitiveTurn = "Ich war heute beim Arzt wegen meiner Diagnose und hatte Schmerzen"
    private val sensitiveQuery = "Ich war heute beim Arzt wegen meiner Diagnose"

    // Harmloser Turn (kein Marker) + naher Recall-Query.
    private val harmlessTurn = "Ich war letzte Woche in Italien im Urlaub am Meer"
    private val harmlessQuery = "Wo war ich im Urlaub am Meer gewesen"

    @BeforeEach
    fun setUp() {
        dbPath = Files.createTempFile("episodic-sensitive-test", ".db")
        Files.deleteIfExists(dbPath)
    }

    @AfterEach
    fun tearDown() {
        Files.deleteIfExists(dbPath)
    }

    private fun adapter(filterEnabled: Boolean) =
        EpisodicMemoryAdapter(
            dbPath.toString(),
            embedder = fakeEmbedder,
            minSim = 0.5,
            sensitiveFilterEnabled = filterEnabled,
        )

    @Test
    fun `Flag ON — sensibler Turn wird NICHT persistiert (kein Recall danach)`() {
        adapter(filterEnabled = true).use { memory ->
            memory.record("andi", sensitiveTurn)
            assertEquals(
                "",
                memory.recallBlock("andi", sensitiveQuery).block(),
                "sensibler Turn darf nicht gespeichert + nicht recallt werden",
            )
        }
    }

    @Test
    fun `Flag ON — harmloser Turn wird normal persistiert und recallt`() {
        adapter(filterEnabled = true).use { memory ->
            memory.record("andi", harmlessTurn)
            val block = memory.recallBlock("andi", harmlessQuery).block()
            assertTrue(block!!.contains("Urlaub"), "harmloser Turn bleibt voll funktional: '$block'")
        }
    }

    @Test
    fun `Flag OFF — sensibler Turn wird persistiert (byte-neutral, heutiges Verhalten)`() {
        adapter(filterEnabled = false).use { memory ->
            memory.record("andi", sensitiveTurn)
            val block = memory.recallBlock("andi", sensitiveQuery).block()
            assertTrue(
                block!!.contains("Arzt"),
                "ohne Filter (Default) verhält sich der Store exakt wie heute: '$block'",
            )
        }
    }

    @Test
    fun `Default-Konstruktor hat Filter OFF (byte-neutral)`() {
        // Adapter ohne sensitiveFilterEnabled-Argument → Filter aus → sensibler Turn bleibt.
        EpisodicMemoryAdapter(dbPath.toString(), embedder = fakeEmbedder, minSim = 0.5).use { memory ->
            memory.record("andi", sensitiveTurn)
            val block = memory.recallBlock("andi", sensitiveQuery).block()
            assertTrue(block!!.contains("Arzt"), "Default-Konstruktor speichert wie heute: '$block'")
        }
    }
}
