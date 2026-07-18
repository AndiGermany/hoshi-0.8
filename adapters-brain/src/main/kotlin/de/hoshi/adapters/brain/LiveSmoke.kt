package de.hoshi.adapters.brain

import java.time.Duration
import kotlin.system.exitProcess

/**
 * Live-Smoke gegen den echten e4b-Brain (:8041). grün≠lebt: beweist, dass der
 * NEUE [MlxBrainAdapter] echte Tokens vom Brain streamt.
 */
fun main() {
    val adapter = MlxBrainAdapter(baseUrl = "http://localhost:8041")

    println("[smoke] health() ...")
    val healthy = adapter.health().block(Duration.ofSeconds(5)) ?: false
    val detail = adapter.healthDetail().block(Duration.ofSeconds(5))
    println("[smoke] health=$healthy model=${detail?.model}")
    if (!healthy) {
        System.err.println("[smoke] FAIL: Brain meldet nicht ok (health=false)")
        exitProcess(1)
    }

    val prefillMs = java.util.concurrent.atomic.AtomicLong(-1)
    val started = System.nanoTime()
    val deltas = adapter.streamChat(
        prompt = "Sag in genau einem kurzen, warmen deutschen Satz Hallo.",
        temperature = 0.6,
        onPrefill = { ms -> prefillMs.set(ms) },
    ).collectList().block(Duration.ofSeconds(40)) ?: emptyList()

    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    val text = deltas.joinToString("") { it.text }

    println("[smoke] ----------------------------------------")
    println("[smoke] Satz   : $text")
    println("[smoke] Latenz : ${elapsedMs} ms  (TTFT prefill=${prefillMs.get()} ms)")
    println("[smoke] Deltas : ${deltas.size}")
    println("[smoke] ----------------------------------------")

    if (text.isBlank()) {
        System.err.println("[smoke] FAIL: leerer Text")
        exitProcess(1)
    }
    println("[smoke] OK — Brain lebt durch den neuen Adapter.")
    exitProcess(0)
}
