package de.hoshi.core.supervision

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshot-basiert: die 16-GB-Wand-Logik ohne echten Speicherdruck. Wir füttern
 * fiktive [MemorySnapshot]s rein und prüfen das Urteil.
 */
class RamBudgetTest {

    private val budget = DefaultRamBudget(headroomMb = 1024)
    private val whisper = SidecarSpec("whisper-stt", "http://x:9001", ramCostMb = 1600)
    private val brain = SidecarSpec("brain(e4b)", "http://x:8041", ramCostMb = 8000, brainGated = true)

    @Test
    fun `erlaubt Start wenn genug nutzbarer RAM`() {
        val snap = MemorySnapshot(totalMb = 16000, availableMb = 4000) // nutzbar 2976 ≥ 1600
        assertTrue(budget.permit(snap, whisper, brainAlreadyResident = false).permitted)
    }

    @Test
    fun `verweigert Start wenn RAM nach Headroom nicht reicht`() {
        val snap = MemorySnapshot(totalMb = 16000, availableMb = 2000) // nutzbar 976 < 1600
        val verdict = budget.permit(snap, whisper, brainAlreadyResident = false)
        assertFalse(verdict.permitted)
        assertTrue(verdict.reason.contains("zu wenig RAM"))
    }

    @Test
    fun `brain-gated Start ist DENY wenn schon ein Brain resident - egal wie viel RAM`() {
        val huge = MemorySnapshot(totalMb = 64000, availableMb = 60000)
        val verdict = budget.permit(huge, brain, brainAlreadyResident = true)
        assertFalse(verdict.permitted)
        assertTrue(verdict.reason.contains("Brain-Slot"))
    }

    @Test
    fun `brain-gated Start ist erlaubt wenn KEIN Brain resident und RAM reicht`() {
        val snap = MemorySnapshot(totalMb = 16000, availableMb = 10000) // nutzbar 8976 ≥ 8000
        assertTrue(budget.permit(snap, brain, brainAlreadyResident = false).permitted)
    }

    @Test
    fun `nicht-brain-Sidecar ist von der Brain-Slot-Regel unberuehrt`() {
        val snap = MemorySnapshot(totalMb = 16000, availableMb = 4000)
        // Selbst wenn ein Brain resident ist: Whisper ist nicht brain-gated → RAM entscheidet.
        assertTrue(budget.permit(snap, whisper, brainAlreadyResident = true).permitted)
    }
}
