package de.hoshi.core.port

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

/**
 * Vertrag des [EscalationPort]-Defaults: [EscalationPort.NONE] ist der
 * byte-neutrale OFF-Zustand — er eskaliert NIE und liefert immer
 * [EscalationResult.Unavailable] (kein Netz, kein Spend, kein Verhalten).
 */
class EscalationPortTest {

    @Test
    fun `NONE liefert immer Unavailable — nie eine Antwort, nie ein Fehler`() {
        StepVerifier.create(EscalationPort.NONE.lookup("Wie hoch ist der Eiffelturm?", "", Language.DE))
            .expectNext(EscalationResult.Unavailable)
            .verifyComplete()

        StepVerifier.create(EscalationPort.NONE.lookup("", "snippet", Language.EN))
            .expectNext(EscalationResult.Unavailable)
            .verifyComplete()
    }

    @Test
    fun `EscalationResult ist erschoepfend pattern-matchbar (sealed-Vertrag)`() {
        val results: List<EscalationResult> = listOf(
            EscalationResult.Answer(text = "330 Meter.", source = "Wikipedia", costCents = 0.05),
            EscalationResult.Unclear,
            EscalationResult.Declined(auditReason = "Memory-Referenz/-Fakt — bleibt strikt lokal"),
            EscalationResult.Unavailable,
            EscalationResult.CapExhausted,
        )
        val labels = results.map { r ->
            when (r) {
                is EscalationResult.Answer -> "answer"
                EscalationResult.Unclear -> "unclear"
                is EscalationResult.Declined -> "declined"
                EscalationResult.Unavailable -> "unavailable"
                EscalationResult.CapExhausted -> "cap_exhausted"
            }
        }
        assertEquals(listOf("answer", "unclear", "declined", "unavailable", "cap_exhausted"), labels)
    }

    @Test
    fun `Declined traegt NUR den Audit-Grund — nie Klartext-Payload-Felder`() {
        // Struktureller Vertrag: Declined hat genau EIN Feld (auditReason).
        val fields = EscalationResult.Declined::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
        assertEquals(listOf("auditReason"), fields, "Declined darf nur auditReason tragen: $fields")
    }

    @Test
    fun `Answer-costCents ist ein Double fuer Bruchteile von Cents (Nano-Klasse)`() {
        val answer = EscalationResult.Answer("x", "y", costCents = 0.0123)
        assertTrue(answer.costCents < 1.0, "Nano-Kosten sind Bruchteile von Cents")
    }
}
