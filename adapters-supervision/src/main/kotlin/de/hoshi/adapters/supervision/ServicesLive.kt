package de.hoshi.adapters.supervision

import de.hoshi.core.supervision.DefaultRamBudget
import de.hoshi.core.supervision.HealthState
import de.hoshi.core.supervision.SidecarRegistry
import de.hoshi.core.supervision.SidecarSupervisor
import kotlin.system.exitProcess

/**
 * Live-Runner hinter `hoshi services`. Läuft den [SidecarSupervisor] mit der echten
 * [HttpSidecarProbe] gegen die LIVE-Infra (read-only inspect) und dem aus `vm_stat`
 * gemessenen [MacMemorySnapshot]. Gibt einen EHRLICHEN Report aus — KEIN Restart wird
 * ausgeführt (gegated), die geplanten Restarts werden nur als Plan gezeigt.
 *
 * Ehrlicher Exit-Code (wie seam-watch): 0 = alle OK · 2 = mind. DEGRADED · 3 = mind. DOWN.
 */
fun main() {
    // Kosmetik: localhost-Proben brauchen kein DNS. Die netty-macOS-DNS-Warnung würde
    // sonst den ehrlichen Report verschmutzen — VOR jedem netty-Load stummschalten.
    System.setProperty("org.slf4j.simpleLogger.log.io.netty", "off")

    val registry = SidecarRegistry.mac()
    val supervisor = SidecarSupervisor(
        registry = registry,
        probe = HttpSidecarProbe(),
        ramBudget = DefaultRamBudget(),
        // Default = GatedRestartPort (dry-run). KEIN echter Prozess-Eingriff.
    )

    val snapshot = MacMemorySnapshot.read()
    val report = supervisor.inspect(snapshot)

    println("Hoshi Sidecar-Supervision  ·  read-only inspect  ·  ${java.time.LocalDateTime.now().withNano(0)}")
    println("RAM: total ${snapshot.totalMb} MB · nutzbar(geschätzt) ${snapshot.availableMb} MB  (16-GB-Wand-Arbiter)")
    println("-".repeat(78))

    report.sidecars.forEach { r ->
        val tag = when (r.health.state) {
            HealthState.OK -> "OK        "
            HealthState.DEGRADED -> "DEGRADED  "
            HealthState.DOWN -> "DOWN      "
        }
        val restart = if (r.restartNeeded) "  → restart-nötig (GATED)" else ""
        val ram = if (r.ramVerdict.permitted) "" else "  [RAM-Arbiter: START-DENY — ${r.ramVerdict.reason}]"
        println(String.format("  %-3s %-16s %s%s%s", tag.trim(), r.name, r.health.detail, restart, ram))
    }

    println("-".repeat(78))

    // Geplante (gegatete) Restarts sichtbar machen — Plan, kein Effekt.
    val outcomes = supervisor.requestRestarts(report)
    if (outcomes.isEmpty()) {
        println("Restart-Pläne: keine (kein DOWN-Sidecar).")
    } else {
        println("Restart-Pläne (GATED / dry-run, NICHT ausgeführt):")
        report.sidecars.mapNotNull { it.restartPlan }.forEach { plan ->
            println("  • ${plan.sidecar}: ${plan.command}")
        }
    }

    println("-".repeat(78))
    val verdict = when (report.exitCode) {
        0 -> "ALL-OK"
        2 -> "DEGRADED"
        else -> "DOWN"
    }
    println(
        "Summary: $verdict — ${report.okCount} OK · ${report.degradedCount} DEGRADED · " +
            "${report.downCount} DOWN  (exit ${report.exitCode})",
    )
    exitProcess(report.exitCode)
}
