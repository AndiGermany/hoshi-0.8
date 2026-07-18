package de.hoshi.core.supervision

/**
 * Ein Speicher-Snapshot, gegen den der RAM-Arbiter entscheidet. Bewusst REIN
 * (totalMb/availableMb als Zahlen) — der Live-Adapter füttert die aus `vm_stat`
 * gemessenen Werte, der Unit-Test füttert ausgedachte Werte. So ist die
 * 16-GB-Wand-Logik ohne echten Speicherdruck testbar.
 */
data class MemorySnapshot(val totalMb: Long, val availableMb: Long)

/** Urteil des RAM-Arbiters über genau EINEN (Neu-)Start. */
sealed class RamVerdict {
    abstract val reason: String

    data class Allow(override val reason: String) : RamVerdict()
    data class Deny(override val reason: String) : RamVerdict()

    val permitted: Boolean get() = this is Allow
}

/**
 * **RamBudgetPort** — der zentralisierte „Brain-Guard" aus 0.5. In 0.5 war die
 * 16-GB-Wand-Logik in JEDEN der 5 Watchdogs kopiert; hier ist sie EINE testbare
 * Entscheidung: darf dieses Sidecar JETZT (neu) starten?
 */
fun interface RamBudgetPort {
    /**
     * @param snapshot            der aktuelle (gemessene oder fiktive) Speicherstand.
     * @param sidecar             welches Sidecar gestartet werden soll.
     * @param brainAlreadyResident ob bereits ein brain-gegatetes Sidecar resident ist
     *        (die 16-GB-Wand: e4b ODER 12b, NIE beide gleichzeitig).
     */
    fun permit(snapshot: MemorySnapshot, sidecar: SidecarSpec, brainAlreadyResident: Boolean): RamVerdict
}

/**
 * Simple, ehrliche Default-Impl. Zwei harte Regeln, in dieser Reihenfolge:
 *
 *  1. **Brain-Slot-Invariante (16-GB-Wand):** Ist [SidecarSpec.brainGated] true UND
 *     bereits ein Brain resident → DENY, egal wie viel RAM frei ist. Das ist die in
 *     0.5 LIVE bewiesene OOM-Schutz-Semantik (e4b+12b gleichzeitig = Mac fällt um).
 *  2. **Headroom-Budget:** Nutzbar = available − [headroomMb]. Reicht das NICHT für
 *     [SidecarSpec.ramCostMb] → DENY. Default-deny bei Knappheit, kein Start auf Verdacht.
 */
class DefaultRamBudget(private val headroomMb: Long = 1024) : RamBudgetPort {
    override fun permit(
        snapshot: MemorySnapshot,
        sidecar: SidecarSpec,
        brainAlreadyResident: Boolean,
    ): RamVerdict {
        if (sidecar.brainGated && brainAlreadyResident) {
            return RamVerdict.Deny(
                "Brain-Slot belegt — 16-GB-Wand: e4b ODER 12b, nie beide (OOM-Schutz)",
            )
        }
        val usableMb = snapshot.availableMb - headroomMb
        if (usableMb < sidecar.ramCostMb) {
            return RamVerdict.Deny(
                "zu wenig RAM: nutzbar ${usableMb}MB (avail ${snapshot.availableMb} − headroom $headroomMb) " +
                    "< Bedarf ${sidecar.ramCostMb}MB",
            )
        }
        return RamVerdict.Allow(
            "ok: nutzbar ${usableMb}MB ≥ Bedarf ${sidecar.ramCostMb}MB",
        )
    }
}
