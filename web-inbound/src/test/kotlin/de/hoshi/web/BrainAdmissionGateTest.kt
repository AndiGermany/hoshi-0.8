package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * **BrainAdmissionGateTest** — beweist die Concurrent-Brain-Admission (Ticket #9):
 *
 *  - OFF (Default) ⇒ reiner Passthrough (byte-neutral): die Quelle fließt unverändert.
 *  - ON + über N gleichzeitige Turns ⇒ SAUBERE Ablehnung (warmer never-silent Mini-Turn),
 *    KEIN Aufstau, der echte Turn-Inhalt läuft NICHT.
 *  - Nach Freigabe eines Permits (auch per Cancel/Barge-in) ⇒ der nächste Turn läuft normal.
 */
class BrainAdmissionGateTest {

    private val realTurn: () -> Flux<ChatEvent> = {
        Flux.just(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            ChatEvent.TextDelta("echte Antwort"),
            ChatEvent.Done(provider = "LOCAL"),
        )
    }

    @Test
    fun `disabled ist Passthrough (byte-neutral)`() {
        val gate = BrainAdmissionGate(enabled = false, maxConcurrent = 1)
        val out = gate.gate(realTurn).collectList().block(Duration.ofSeconds(2))!!
        assertEquals(
            listOf(ChatEvent.Start::class, ChatEvent.TextDelta::class, ChatEvent.Done::class),
            out.map { it::class },
        )
        assertEquals("echte Antwort", out.filterIsInstance<ChatEvent.TextDelta>().first().text)
        assertEquals(0, gate.rejectedCount())
    }

    @Test
    fun `ueber N gleichzeitige Turns wird sauber abgelehnt statt aufgestaut`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1)
        // Erster Turn hält das einzige Permit (nie-endender Stream).
        val held = gate.gate { Flux.never<ChatEvent>() }.subscribe()
        assertEquals(0, gate.availablePermits(), "Permit ist belegt")

        // Zweiter Turn: kein Permit ⇒ warmer Rejection-Mini-Turn, der echte Turn läuft NICHT.
        val out = gate.gate(realTurn).collectList().block(Duration.ofSeconds(2))!!

        assertEquals(
            listOf(ChatEvent.Start::class, ChatEvent.TextDelta::class, ChatEvent.Done::class),
            out.map { it::class },
            "Ablehnung ist ein vollständiger never-silent Mini-Turn",
        )
        val delta = out.filterIsInstance<ChatEvent.TextDelta>().first()
        assertNotEquals("echte Antwort", delta.text, "der echte Turn-Inhalt darf NICHT geflossen sein")
        assertTrue(delta.text.isNotBlank(), "ehrliche, sprechbare Absage")
        val start = out.filterIsInstance<ChatEvent.Start>().first()
        assertEquals(BrainAdmissionGate.CATEGORY, start.category, "als ADMISSION-Absage markiert")
        assertEquals(1, gate.rejectedCount())

        held.dispose()
    }

    @Test
    fun `Cancel eines Turns gibt das Permit frei (Barge-in) und der naechste laeuft`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1)
        val held = gate.gate { Flux.never<ChatEvent>() }.subscribe()
        assertEquals(0, gate.availablePermits())

        held.dispose() // Cancel ⇒ doFinally ⇒ Permit-Freigabe
        awaitPermits(gate, 1)

        val out = gate.gate(realTurn).collectList().block(Duration.ofSeconds(2))!!
        assertEquals("echte Antwort", out.filterIsInstance<ChatEvent.TextDelta>().first().text)
        assertEquals(0, gate.rejectedCount(), "nach Freigabe keine Ablehnung")
    }

    @Test
    fun `normaler Abschluss gibt das Permit frei`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1)
        // Erster Turn läuft vollständig durch ⇒ Permit zurück.
        gate.gate(realTurn).collectList().block(Duration.ofSeconds(2))
        awaitPermits(gate, 1)
        // Zweiter Turn bekommt das Permit ⇒ echter Inhalt, keine Ablehnung.
        val out = gate.gate(realTurn).collectList().block(Duration.ofSeconds(2))!!
        assertEquals("echte Antwort", out.filterIsInstance<ChatEvent.TextDelta>().first().text)
        assertEquals(0, gate.rejectedCount())
    }

    /** Wartet (bounded) bis das erwartete Permit-Budget wieder frei ist — gegen Cancel-Race. */
    private fun awaitPermits(gate: BrainAdmissionGate, expected: Int) {
        val deadline = System.currentTimeMillis() + 1000
        while (gate.availablePermits() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(expected, gate.availablePermits())
    }
}
