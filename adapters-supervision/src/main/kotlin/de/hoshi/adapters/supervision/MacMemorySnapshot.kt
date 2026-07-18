package de.hoshi.adapters.supervision

import de.hoshi.core.supervision.MemorySnapshot

/**
 * Liest einen ehrlichen [MemorySnapshot] vom macOS-Host über `vm_stat` + `sysctl`.
 * Das ist der Live-Futter-Lieferant des reinen RAM-Arbiters — die ENTSCHEIDUNG bleibt
 * im Kern testbar, nur die MESSUNG lebt hier im Adapter.
 *
 * `availableMb` ist eine bewusst konservative Schätzung des für einen Sidecar-Start
 * realistisch nutzbaren Speichers: free + inactive + speculative + purgeable (alles in
 * Pages × page-size). Das ist absichtlich vorsichtig — der Arbiter soll lieber zu früh
 * DENY sagen als einen OOM auf der 16-GB-Wand riskieren.
 */
object MacMemorySnapshot {

    fun read(): MemorySnapshot {
        val totalBytes = sysctlLong("hw.memsize") ?: 0L
        val totalMb = totalBytes / (1024 * 1024)

        val vm = runCommand(listOf("vm_stat")) ?: return MemorySnapshot(totalMb, 0)
        val pageSize = Regex("page size of (\\d+) bytes").find(vm)?.groupValues?.get(1)?.toLongOrNull() ?: 16384L

        fun pages(label: String): Long =
            Regex("$label:\\s+(\\d+)").find(vm)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val free = pages("Pages free")
        val inactive = pages("Pages inactive")
        val speculative = pages("Pages speculative")
        val purgeable = pages("Pages purgeable")

        val availableBytes = (free + inactive + speculative + purgeable) * pageSize
        val availableMb = availableBytes / (1024 * 1024)
        return MemorySnapshot(totalMb = totalMb, availableMb = availableMb)
    }

    private fun sysctlLong(key: String): Long? =
        runCommand(listOf("sysctl", "-n", key))?.trim()?.toLongOrNull()

    private fun runCommand(cmd: List<String>): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrNull()
}
