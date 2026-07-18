package de.hoshi.core.supervision

/** Pro-Sidecar-Zeile des [SupervisionReport] — ehrlich, ohne Fake-grün. */
data class SidecarReport(
    val spec: SidecarSpec,
    val health: SidecarHealth,
    /** true NUR wenn die Naht DOWN ist (loading/DEGRADED triggert keinen Restart — Warmup nicht abwürgen). */
    val restartNeeded: Boolean,
    /** Würde der RAM-Arbiter einen (Neu-)Start JETZT erlauben? */
    val ramVerdict: RamVerdict,
    /** Der GEPLANTE (gegatete) Restart — nur gesetzt wenn [restartNeeded]. */
    val restartPlan: RestartPlan?,
) {
    val name: String get() = spec.name
}

/** Gesamt-Report eines [SidecarSupervisor.inspect]-Laufs. */
data class SupervisionReport(
    val sidecars: List<SidecarReport>,
    val snapshot: MemorySnapshot,
) {
    val okCount: Int get() = sidecars.count { it.health.state == HealthState.OK }
    val degradedCount: Int get() = sidecars.count { it.health.state == HealthState.DEGRADED }
    val downCount: Int get() = sidecars.count { it.health.state == HealthState.DOWN }
    val restartNeededCount: Int get() = sidecars.count { it.restartNeeded }

    /**
     * Ehrlicher Gesamt-Exit-Code (Muster wie seam-watch):
     *   0 = alles OK · 2 = mind. ein DEGRADED · 3 = mind. ein DOWN.
     * DOWN dominiert DEGRADED (3 vor 2), weil tot schlimmer ist als nicht-bereit.
     */
    val exitCode: Int get() = when {
        downCount > 0 -> 3
        degradedCount > 0 -> 2
        else -> 0
    }
}

/**
 * **SidecarSupervisor** — der EINE ehrliche Supervisor, der in 0.8 die 5 copy-paste-
 * Watchdogs + das lügende `/health` aus 0.5 ersetzt.
 *
 * Verantwortung (reine Entscheidung, KEIN Effekt):
 *  - [inspect]   probt jede Naht der [SidecarRegistry] EINMAL, baut den ehrlichen
 *                [SupervisionReport]: je Sidecar Health + ob Restart nötig + ob der
 *                RAM-Arbiter einen Start erlaubt + den (gegateten) Restart-Plan.
 *  - [preflight] entscheidet VOR „healthy", ob ein Sidecar überhaupt starten DARF
 *                (venv/Import/Model-Cache/keine `*.incomplete`-Reste).
 *
 * Der Restart-EFFEKT läuft über den [RestartPort] (Default gegated/dry-run). Die
 * Brain-Slot-Invariante (16-GB-Wand) lebt im [RamBudgetPort].
 */
class SidecarSupervisor(
    private val registry: SidecarRegistry,
    private val probe: SidecarPort,
    private val ramBudget: RamBudgetPort,
    private val restartPort: RestartPort = GatedRestartPort(),
) {
    /**
     * Probt alle Sidecars gegen [snapshot] und baut den ehrlichen Report. „Brain bereits
     * resident" wird LIVE aus den Proben abgeleitet: jede brain-gegatete Naht, die
     * erreichbar ist (OK ODER DEGRADED), belegt den Brain-Slot — daraufhin verweigert der
     * Arbiter einem ANDEREN brain-gegateten Start den Platz (e4b ODER 12b, nie beide).
     */
    fun inspect(snapshot: MemorySnapshot): SupervisionReport {
        val probed: List<Pair<SidecarSpec, SidecarHealth>> =
            registry.sidecars.map { it to probe.probe(it) }

        val residentBrains = probed.count { (spec, health) ->
            spec.brainGated && health.state != HealthState.DOWN
        }

        val reports = probed.map { (spec, health) ->
            // Belegt ein ANDERES Brain bereits den Slot?
            val brainResidentElsewhere = residentBrains - (
                if (spec.brainGated && health.state != HealthState.DOWN) 1 else 0
            ) > 0
            val verdict = ramBudget.permit(snapshot, spec, brainResidentElsewhere)
            val restartNeeded = health.state == HealthState.DOWN
            val plan = if (restartNeeded) {
                RestartPlan(
                    sidecar = spec.name,
                    reason = "DOWN: ${health.detail}",
                    command = spec.restartCommand,
                )
            } else {
                null
            }
            SidecarReport(spec, health, restartNeeded, verdict, plan)
        }
        return SupervisionReport(reports, snapshot)
    }

    /**
     * Start-Preflight — die Ehrlichkeits-Achse aus dem e4b-run. Sammelt ALLE Blocker
     * (nicht nur den ersten), damit ein Bild der Vollständigkeit entsteht statt nur
     * „erster Fehler".
     */
    fun preflight(sidecar: SidecarSpec, input: PreflightInput): PreflightResult {
        val blockers = buildList {
            if (!input.venvPresent) add("venv fehlt (tote venv → stiller Tod)")
            if (!input.importProbeOk) add("Import-Probe schlug fehl (Modul nicht importierbar)")
            if (!input.modelCacheComplete) add("Model-Cache unvollständig (.safetensors fehlen)")
            if (input.hasIncompleteMarkers) add("`*.incomplete`-Reste vorhanden (Zombie-Download)")
        }
        return PreflightResult(sidecar.name, blockers.isEmpty(), blockers)
    }

    /**
     * Fordert die (gegateten) Restarts für alle DOWN-Sidecars eines Reports an. Im Default
     * passiert KEIN echter Prozess-Eingriff — es kommt nur der Plan zurück (Andi-Gate).
     */
    fun requestRestarts(report: SupervisionReport): List<RestartOutcome> =
        report.sidecars.mapNotNull { it.restartPlan }.map { restartPort.restart(it) }
}
