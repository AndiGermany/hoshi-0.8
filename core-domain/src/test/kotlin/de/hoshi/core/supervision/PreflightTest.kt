package de.hoshi.core.supervision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Preflight = die Ehrlichkeits-Achse aus dem 0.5-e4b-run: VOR „healthy" prüfen, ob
 * der Start überhaupt sauber sein KANN. Genau das hätte den 6-Tage-Zombie-Download
 * und die toten venvs abgefangen.
 */
class PreflightTest {

    private val sup = SidecarSupervisor(
        SidecarRegistry(emptyList()),
        SidecarPort { SidecarHealth.down("n/a") },
        DefaultRamBudget(),
    )
    private val spec = SidecarSpec("brain(e4b)", "http://x:8041", ramCostMb = 8000)

    @Test
    fun `alles vollstaendig ist ready ohne Blocker`() {
        val r = sup.preflight(
            spec,
            PreflightInput(venvPresent = true, importProbeOk = true, modelCacheComplete = true, hasIncompleteMarkers = false),
        )
        assertTrue(r.ready)
        assertTrue(r.blockers.isEmpty())
    }

    @Test
    fun `incomplete-Reste blockieren - der 6-Tage-Zombie-Download`() {
        val r = sup.preflight(
            spec,
            PreflightInput(venvPresent = true, importProbeOk = true, modelCacheComplete = true, hasIncompleteMarkers = true),
        )
        assertFalse(r.ready)
        assertTrue(r.blockers.any { it.contains("Zombie-Download") })
    }

    @Test
    fun `tote venv blockiert`() {
        val r = sup.preflight(
            spec,
            PreflightInput(venvPresent = false, importProbeOk = false, modelCacheComplete = true, hasIncompleteMarkers = false),
        )
        assertFalse(r.ready)
        assertTrue(r.blockers.any { it.contains("venv fehlt") })
    }

    @Test
    fun `mehrere Defekte sammeln ALLE Blocker, nicht nur den ersten`() {
        val r = sup.preflight(
            spec,
            PreflightInput(venvPresent = false, importProbeOk = false, modelCacheComplete = false, hasIncompleteMarkers = true),
        )
        assertFalse(r.ready)
        assertEquals(4, r.blockers.size)
    }
}
