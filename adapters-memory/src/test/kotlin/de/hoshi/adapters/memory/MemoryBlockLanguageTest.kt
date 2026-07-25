package de.hoshi.adapters.memory

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * **Sprach-Naht der beiden Gedächtnis-Blöcke** ([EntityMemoryAdapter]/
 * [EpisodicMemoryAdapter] + [MemoryBlockTexts], Scheibe 2026-07-25). Drei Zusagen:
 *
 *  1. Rahmen je DE/EN/ES/FR/IT in der Turn-Sprache.
 *  2. **DE byte-identisch** — beide Blöcke voll als `assertEquals`.
 *  3. **Gespeicherte Nutzerdaten werden NIE übersetzt**: Fakt-Zeilen und frühere
 *     Turn-Texte stehen in einem EN-Turn wörtlich im Block; nur Kopf/Fuß wechseln.
 */
class MemoryBlockLanguageTest {

    private lateinit var entityDb: Path
    private lateinit var episodicDb: Path
    private lateinit var entity: EntityMemoryAdapter
    private lateinit var episodic: EpisodicMemoryAdapter

    /** Deterministischer Bag-of-Words-Embedder (kein Netz) — Muster [EpisodicMemoryAdapterTest]. */
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
        entityDb = Files.createTempFile("entity-lang-test", ".db").also { Files.deleteIfExists(it) }
        episodicDb = Files.createTempFile("episodic-lang-test", ".db").also { Files.deleteIfExists(it) }
        entity = EntityMemoryAdapter(entityDb.toString())
        episodic = EpisodicMemoryAdapter(episodicDb.toString(), embedder = fakeEmbedder, minSim = 0.5)
    }

    @AfterEach
    fun tearDown() {
        entity.close()
        episodic.close()
        Files.deleteIfExists(entityDb)
        Files.deleteIfExists(episodicDb)
    }

    private val urlaubTurn = "Ich war letzte Woche in Italien im Urlaub am Meer"

    // ── (1) Stichprobe je Sprache ────────────────────────────────────────────────

    @Test
    fun `Entity-Block traegt Kopf und Anweisung in jeder der fuenf Sprachen`() {
        entity.remember("andi", "Mein Hund heißt Bello.", "Schöner Name!")

        val expected = mapOf(
            Language.DE to Pair("[Gedächtnis — was du über den aktuellen Sprecher", "antworte damit.]"),
            Language.EN to Pair("[Memory — what you know about the current speaker", "answer with it.]"),
            Language.ES to Pair("[Memoria — lo que sabes sobre la persona que habla", "responde con él.]"),
            Language.FR to Pair("[Mémoire — ce que tu sais de la personne qui parle", "réponds avec celle-ci.]"),
            Language.IT to Pair("[Memoria — ciò che sai sulla persona che parla", "rispondi con quello.]"),
        )
        assertEquals(Language.entries.toSet(), expected.keys, "alle fünf Sprachen, keine still vergessen")

        expected.forEach { (lang, parts) ->
            val block = entity.contextBlock("andi", lang)!!
            assertTrue(block.startsWith(parts.first), "$lang: Kopf — $block")
            assertTrue(block.endsWith(parts.second), "$lang: Anweisung/Fuß — $block")
        }
    }

    @Test
    fun `Episodic-Block traegt die Klammer in jeder der fuenf Sprachen`() {
        episodic.record("andi", urlaubTurn)

        val expected = mapOf(
            Language.DE to "[Früher gesagt: ",
            Language.EN to "[Said earlier: ",
            Language.ES to "[Dicho antes: ",
            Language.FR to "[Dit plus tôt : ",
            Language.IT to "[Detto in precedenza: ",
        )
        assertEquals(Language.entries.toSet(), expected.keys, "alle fünf Sprachen, keine still vergessen")

        expected.forEach { (lang, head) ->
            val block = episodic.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen", lang).block()!!
            assertTrue(block.startsWith(head), "$lang: Klammer-Kopf — $block")
        }
    }

    // ── (2) DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Entity-Block ist BYTE-IDENTISCH zum Stand vor der Sprach-Naht`() {
        entity.remember("andi", "Mein Hund heißt Bello.", "Schöner Name!")

        val expected = "[Gedächtnis — was du über den aktuellen Sprecher aus früheren Gesprächen weißt:\n" +
            "- hund: Bello" +
            "\nWenn er nach einer dieser Angaben fragt, antworte damit.]"
        assertEquals(expected, entity.contextBlock("andi", Language.DE), "DE ist eingefroren: kein Byte anders")
    }

    @Test
    fun `DE-Episodic-Block ist BYTE-IDENTISCH zum Stand vor der Sprach-Naht`() {
        episodic.record("andi", urlaubTurn)

        assertEquals(
            "[Früher gesagt: $urlaubTurn]",
            episodic.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen", Language.DE).block(),
            "DE ist eingefroren: kein Byte anders",
        )
    }

    // ── (3) Rahmen übersetzt, Nutzerdaten nicht ─────────────────────────────────

    @Test
    fun `EN-Turn uebersetzt NUR den Rahmen - gespeicherte Fakten und Turns bleiben woertlich`() {
        entity.remember("andi", "Mein Hund heißt Bello.", "Schöner Name!")
        episodic.record("andi", urlaubTurn)

        val entityBlock = entity.contextBlock("andi", Language.EN)!!
        val episodicBlock = episodic.recallBlock("andi", "Wo war ich im Urlaub am Meer gewesen", Language.EN).block()!!

        // Nutzerdaten: WÖRTLICH — eine Übersetzung wäre eine Fälschung des Gedächtnisses
        // (und bei HA-Raumnamen zusätzlich ein kaputter Smart-Home-Bezug).
        assertTrue(entityBlock.contains("- hund: Bello"), "Fakt-Zeile unverändert: $entityBlock")
        assertTrue(episodicBlock.contains(urlaubTurn), "früherer Turn unverändert: $episodicBlock")
        // Rahmen: englisch, kein deutscher Rest.
        assertTrue(entityBlock.startsWith("[Memory — "), entityBlock)
        assertFalse(entityBlock.contains("[Gedächtnis"), "kein deutscher Kopf: $entityBlock")
        assertFalse(entityBlock.contains("antworte damit"), "kein deutscher Fuß: $entityBlock")
        assertTrue(episodicBlock.startsWith("[Said earlier: "), episodicBlock)
        assertFalse(episodicBlock.startsWith("[Früher gesagt"), "kein deutscher Kopf: $episodicBlock")
    }
}
