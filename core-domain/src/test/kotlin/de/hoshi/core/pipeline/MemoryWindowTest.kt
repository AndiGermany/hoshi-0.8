package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * **Server-side Working-Memory-Window** ([TurnPromptAssembler.windowHistory]).
 *
 * Reine, deterministische Logik (kein Reactor/I/O nötig): kappt die an den Brain
 * gereichte Konversations-History auf die letzten `windowTurns * 2` Nachrichten
 * (N User + N Assistant). Portiert aus Hoshi 0.5 `ChatMemoryService` (dort
 * `windowTurns * 2` + `removeFirst()`-Trim). Geprüft:
 *  - windowTurns=2 + lange History → nur die letzten 4 Nachrichten
 *  - windowTurns=0 → unverändert (Default = KEIN Cap, byte-neutral)
 *  - History kürzer als das Fenster → unverändert
 */
class MemoryWindowTest {

    /** Minimaler Assembler — `windowHistory` berührt weder Persona noch Grounding/Episodic. */
    private fun assembler(windowTurns: Int): TurnPromptAssembler =
        TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = EntityContextPort { null },
            grounding = GroundingPort { _, _ -> Mono.just("") },
            episodicMemory = null,
            historyWindowTurns = windowTurns,
        )

    /** Baut `count` abwechselnde user/assistant-Nachrichten (m0, m1, …). */
    private fun history(count: Int): List<ChatMessage> =
        (0 until count).map { i ->
            ChatMessage(role = if (i % 2 == 0) "user" else "assistant", content = "m$i")
        }

    @Test
    fun `windowTurns 2 kappt lange History auf die letzten 4 Nachrichten`() {
        val long = history(10) // m0..m9
        val out = assembler(windowTurns = 2).windowHistory(long)

        assertEquals(4, out.size, "windowTurns*2 = 4 Nachrichten")
        assertEquals(listOf("m6", "m7", "m8", "m9"), out.map { it.content }, "nur die letzten 4")
    }

    @Test
    fun `windowTurns 0 laesst die History unveraendert (byte-neutral)`() {
        val long = history(10)
        val out = assembler(windowTurns = 0).windowHistory(long)

        // Default-Pfad gibt die EXAKTE Eingabe-Liste zurueck (keine Kopie, kein Cap).
        assertSame(long, out, "Default 0 = kein Cap: identische Liste zurueck")
        assertEquals(10, out.size)
    }

    @Test
    fun `History kuerzer als das Fenster bleibt unveraendert`() {
        val short = history(3) // 3 < windowTurns*2 (=6)
        val out = assembler(windowTurns = 3).windowHistory(short)

        assertEquals(listOf("m0", "m1", "m2"), out.map { it.content }, "nichts zu kappen")
        assertEquals(3, out.size)
    }

    @Test
    fun `leere History bleibt leer`() {
        val out = assembler(windowTurns = 2).windowHistory(emptyList())
        assertEquals(0, out.size)
    }
}
