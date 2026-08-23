package de.hoshi.adapters.brain

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.LlmDelta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Das Brain-GESAMT-Budget** (Stabilitäts-Fix 2026-08-20): `chatTimeoutSeconds`
 * ist ein INAKTIVITÄTS-Timeout PRO VERSUCH — er misst die Lücke zwischen zwei
 * Deltas und bindet damit nie einen ganzen Turn. Zwei Löcher, die er offen liess:
 *
 *  - ein tröpfelnder Brain (alle 19 s ein Zeichen) läuft unbegrenzt weiter;
 *  - der Empty-Retry hängt einen ZWEITEN vollen Versuch an ⇒ 20 + 0,2 + 20 s.
 *
 * `totalTimeoutSeconds` (Default 25, Ops-Knopf `HOSHI_BRAIN_TOTAL_TIMEOUT_SECONDS`)
 * legt eine harte Wanduhr über BEIDE Versuche. Bei Riss fliegt eine
 * [TimeoutException] — genau die Sorte, die `TurnOrchestrator.isTimeout` erkennt
 * und in die warme Fehler-Phrase übersetzt (never-silent statt stillem Abschneiden).
 */
class MlxBrainAdapterTotalBudgetTest {

    /**
     * Ein Fake-`/v1/chat`, dessen Antwort pro Versuch vom [handler] bestimmt wird:
     * `null` ⇒ Header raus und HÄNGEN (stummer Brain), sonst der SSE-Text.
     */
    private fun withBrain(
        handler: (attempt: Int) -> String?,
        block: (url: String, attempts: AtomicInteger) -> Unit,
    ) {
        val attempts = AtomicInteger(0)
        val hold = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r).also { it.isDaemon = true }
        }
        server.createContext("/v1/chat") { ex ->
            val n = attempts.incrementAndGet()
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            val sse = handler(n)
            if (sse == null) {
                // Header raus, dann Stille: der Stream steht offen und liefert nie.
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.flush()
                hold.await(30, TimeUnit.SECONDS)
                ex.responseBody.close()
            } else {
                val bytes = sse.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", attempts)
        } finally {
            hold.countDown()
            server.stop(0)
        }
    }

    // ── (1) Der Riss selbst — mit VIRTUELLER Zeit, kein echtes Warten ────────
    @Test
    fun `stummer Brain reisst nach dem Gesamt-Budget mit TimeoutException`() =
        withBrain(handler = { null }) { url, _ ->
            val adapter = MlxBrainAdapter(
                baseUrl = url,
                // Der Pro-Versuch-Timeout wird bewusst aus dem Weg geraeumt, damit
                // NUR das Gesamt-Budget reissen kann (Isolation des neuen Verhaltens).
                chatTimeoutSeconds = 600,
                totalTimeoutSeconds = 25,
            )
            // Mono.delay laeuft auf Schedulers.parallel() — withVirtualTime ersetzt
            // genau den, die 25s vergehen also in Mikrosekunden.
            StepVerifier.withVirtualTime { adapter.streamChat(prompt = "hi") }
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(24))
                .thenAwait(Duration.ofSeconds(2))
                .expectError(TimeoutException::class.java)
                .verify(Duration.ofSeconds(10))
        }

    // ── (2) Das Budget spannt ueber BEIDE Versuche (Original + Empty-Retry) ──
    @Test
    fun `das Budget spannt ueber den Empty-Retry hinweg`() =
        withBrain(handler = { attempt -> if (attempt == 1) "data: [DONE]\n\n" else null }) { url, attempts ->
            // Echte (kurze) Zeit statt virtueller: der Empty-Retry braucht eine echte
            // HTTP-Runde, bevor Versuch 2 ueberhaupt startet — die laesst sich nicht
            // vorspulen. 1s Budget haelt den Test trotzdem schnell.
            val adapter = MlxBrainAdapter(
                baseUrl = url,
                chatTimeoutSeconds = 600,
                totalTimeoutSeconds = 1,
            )
            StepVerifier.create(adapter.streamChat(prompt = "hi"))
                .expectError(TimeoutException::class.java)
                .verify(Duration.ofSeconds(15))

            assertEquals(2, attempts.get(), "Versuch 1 lief leer durch, der Retry muss gestartet sein")
        }

    // ── (3) Ohne Riss bleibt alles byte-identisch (der Draht ist inert) ──────
    @Test
    fun `ein normaler Turn bleibt unveraendert - kein Extra-Signal aus dem Budget-Draht`() =
        withBrain(handler = { "data: {\"delta\":\"ok\"}\n\ndata: [DONE]\n\n" }) { url, attempts ->
            val adapter = MlxBrainAdapter(baseUrl = url, totalTimeoutSeconds = 25)
            val deltas: List<LlmDelta>? =
                adapter.streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(10))

            assertEquals(listOf("ok"), deltas?.map { it.text }, "Deltas muessen unveraendert durchlaufen")
            assertEquals(1, attempts.get(), "kein zusaetzlicher Versuch durch den Budget-Draht")
        }

    @Test
    fun `ein schneller Turn wartet NICHT auf das Budget-Ende`() =
        withBrain(handler = { "data: {\"delta\":\"ok\"}\n\ndata: [DONE]\n\n" }) { url, _ ->
            // Regression gegen die naheliegende Fehl-Implementierung (merge statt
            // takeUntilOther): dort haette der Companion-Delay den Stream bis zum
            // Budget-Ende offen gehalten — jeder Turn wuerde 25s dauern.
            val adapter = MlxBrainAdapter(baseUrl = url, totalTimeoutSeconds = 25)
            val startedAt = System.nanoTime()
            adapter.streamChat(prompt = "hi").collectList().block(Duration.ofSeconds(10))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(elapsedMs < 5_000, "der Turn muss sofort abschliessen (waren ${elapsedMs}ms)")
        }
}
