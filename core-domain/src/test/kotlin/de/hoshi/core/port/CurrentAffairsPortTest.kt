package de.hoshi.core.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CurrentAffairsPortTest {
    @Test
    fun `unwired current-affairs port is honest and never throws`() {
        val snapshot = CurrentAffairsPort.NONE.latest(CurrentAffairsQuery())
        assertEquals(CurrentAffairsFreshness.UNAVAILABLE, snapshot.freshness)
        assertEquals(emptyList<CurrentAffairsItem>(), snapshot.items)
        assertNotNull(snapshot.observedAt)
        assertEquals(null, snapshot.lastSuccessfulRefreshAt)
    }

    @Test
    fun `unwired civic-alert port says unknown, never clear`() {
        val snapshot = CivicAlertPort.NONE.current(CivicAlertQuery(CivicArea(setOf("household-default"))))
        assertEquals(CivicAlertState.UNKNOWN, snapshot.state)
        assertEquals(emptyList<CivicAlert>(), snapshot.alerts)
    }
}
