package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist den Kern-Vertrag von [DelegatingEscalationPort] (Andi-Video-Auftrag:
 * „Lookup-Sprachmodell in den Einstellungen wählbar, zur Laufzeit, ohne
 * Neustart") — Spiegel von [DelegatingTtsPortTest].
 */
class DelegatingEscalationPortTest {

    private class TaggedFakeEscalation(private val tag: String) : EscalationPort {
        override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> =
            Mono.just(EscalationResult.Answer(text = tag, source = tag, costCents = 0.0))
    }

    @Test
    fun `vor dem Switch landet der Aufruf beim initialen Delegaten`() {
        val port = DelegatingEscalationPort(initialModelId = "gpt-5.4-nano", initial = TaggedFakeEscalation("nano"))
        val result = port.lookup("Frage", "", Language.DE).block(Duration.ofSeconds(2))!!
        assertEquals("nano", (result as EscalationResult.Answer).text)
        assertEquals("gpt-5.4-nano", port.currentModelId())
    }

    @Test
    fun `switchTo wechselt sofort - der naechste Lookup landet beim neuen Fake-Adapter`() {
        val port = DelegatingEscalationPort(initialModelId = "gpt-5.4-nano", initial = TaggedFakeEscalation("nano"))

        port.switchTo("gpt-5.4-mini", TaggedFakeEscalation("mini"))

        assertEquals("gpt-5.4-mini", port.currentModelId())
        val result = port.lookup("Frage", "", Language.DE).block(Duration.ofSeconds(2))!!
        assertEquals("mini", (result as EscalationResult.Answer).text)
    }

    @Test
    fun `Decke-zu-Fall - initial NONE bleibt bis zum ersten Switch bestehen`() {
        val port = DelegatingEscalationPort(initialModelId = "gpt-5.4-nano", initial = EscalationPort.NONE)
        val result = port.lookup("Frage", "", Language.DE).block(Duration.ofSeconds(2))!!
        assertEquals(EscalationResult.Unavailable, result)
    }
}
