package de.hoshi.core.supervision

/**
 * Der GEPLANTE Restart eines Sidecars. Nur Daten — der Effekt (Prozess killen/starten)
 * passiert hinter dem [RestartPort], NICHT hier.
 */
data class RestartPlan(
    val sidecar: String,
    val reason: String,
    val command: String,
)

/**
 * Ergebnis einer Restart-ANFORDERUNG. [executed] sagt ehrlich, ob wirklich ein Prozess
 * angefasst wurde. Im Default ([GatedRestartPort]) ist das IMMER false.
 */
data class RestartOutcome(
    val executed: Boolean,
    val plannedCommand: String,
    val note: String,
)

/**
 * **RestartPort** — die Effekt-Naht für „Sidecar neu starten". Der Supervisor ENTSCHEIDET,
 * dass ein Restart nötig ist; das AUSFÜHREN ist ein separater Port, damit der gefährliche
 * Teil (Fremd-Prozess-Start/-Kill) gegated bleibt.
 */
fun interface RestartPort {
    fun restart(plan: RestartPlan): RestartOutcome
}

/**
 * Default-Impl: **GATED / dry-run**. Führt NIE einen echten Prozess-Kill/-Start aus —
 * gibt nur den Plan zurück. Fremd-Prozess-Lifecycle ist Andi-Gate (analog `deploy.sh`):
 * der Supervisor darf planen und melden, aber nicht selbst-granten zu killen/starten.
 */
class GatedRestartPort : RestartPort {
    override fun restart(plan: RestartPlan): RestartOutcome = RestartOutcome(
        executed = false,
        plannedCommand = plan.command,
        note = "GATED (dry-run) — Prozess-Start/-Kill ist Andi-Gate, nicht selbst-granten",
    )
}
