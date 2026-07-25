package de.hoshi.adapters.escalation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiEscalationAvailabilityTest {

    private class Spend(private val cents: Double) : EscalationSpendStore {
        override fun spentTodayCents(): Double = cents
        override fun book(cents: Double): Double = this.cents + cents
    }

    @Test
    fun `Key vorhanden und Spend unter Cap erlaubt ein ehrliches Angebot`() {
        val availability = OpenAiEscalationAvailability("sk-test", Spend(49.99))
        assertTrue(availability.canOffer())
    }

    @Test
    fun `fehlender oder leerer Key verbietet das Angebot ohne Store-Abhaengigkeit`() {
        assertFalse(OpenAiEscalationAvailability(null, Spend(0.0)).canOffer())
        assertFalse(OpenAiEscalationAvailability("   ", Spend(0.0)).canOffer())
    }

    @Test
    fun `am oder ueber dem Tages-Cap wird nichts mehr versprochen`() {
        val cap = OpenAiEscalationAdapter.DEFAULT_DAILY_CAP_CENTS
        assertFalse(OpenAiEscalationAvailability("sk-test", Spend(cap)).canOffer())
        assertFalse(OpenAiEscalationAvailability("sk-test", Spend(cap + 1.0)).canOffer())
    }

    @Test
    fun `unerwarteter Store-Fehler ist konservativ kein Angebot`() {
        val broken = object : EscalationSpendStore {
            override fun spentTodayCents(): Double = error("broken")
            override fun book(cents: Double): Double = error("unused")
        }
        assertFalse(OpenAiEscalationAvailability("sk-test", broken).canOffer())
    }
}
