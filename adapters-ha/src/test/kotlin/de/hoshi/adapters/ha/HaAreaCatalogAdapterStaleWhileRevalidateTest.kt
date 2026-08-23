package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.port.AreaCatalogPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Der Stabilitäts-Fix von 2026-08-20** am [HaAreaCatalogAdapter]: der blockierende
 * 5-s-HA-Call lief vorher bei TTL-Ablauf SYNCHRON und unter `synchronized(this)` —
 * mitten im Chat-Turn, also auf dem Netty-Event-Loop. Ein langsames HA parkte damit
 * den Event-Loop UND stellte jeden parallelen Turn am Monitor an.
 *
 * Bewiesen wird hier genau das Verhalten danach (kein echtes HA, ein winziger
 * JDK-HttpServer spielt `/api/template` wie in [HaAreaCatalogAdapterTest]):
 *
 *  1. **warm-aber-abgelaufen ⇒ sofort alte Daten + asynchroner Refresh** — während
 *     des `areas()`-Aufrufs geht KEIN HTTP raus, der Refresh liegt auf dem Executor.
 *  2. **kein Monitor um HTTP** — ein hängender Refresh blockiert weitere Aufrufer
 *     nicht (vorher: `synchronized`-Stau).
 *  3. **Read-Timeout** — ein HA, das Header schickt und dann beim BODY stockt, wird
 *     nach [HaAreaCatalogAdapter]s Budget abgeschnitten. Ohne den `sendAsync`+
 *     Deadline-Umbau hinge dieser Load UNBEGRENZT (`HttpRequest.timeout` deckt im
 *     JDK nur die Zeit bis zu den Headern, `HttpClient` kennt gar keinen Read-Timeout).
 *  4. **Event-Loop-Naht** — auf einem Nicht-Blockier-Thread wird NIE gewartet.
 */
class HaAreaCatalogAdapterStaleWhileRevalidateTest {

    private val bodyV1 = "wohnzimmer::Wohnzimmer||kuche::Küche"
    private val bodyV2 = "wohnzimmer::Wohnzimmer||kuche::Küche||buero::Büro"

    /** Frei vorspulbare Uhr (TTL-Ablauf ohne echtes Warten) — wie in [HaAreaCatalogAdapterTest]. */
    private class MutableClock(startEpochSeconds: Long) : Clock() {
        @Volatile var now: Instant = Instant.ofEpochSecond(startEpochSeconds)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = now
    }

    /**
     * Der „virtuelle Scheduler" für den Refresh: solange [capture] `false` ist, läuft
     * die Aufgabe INLINE (deterministisches Aufwärmen des Caches, kein Thread-Rennen);
     * ab `capture = true` wird sie nur noch eingesammelt und läuft erst, wenn der Test
     * [runCaptured] ruft. So ist „der Refresh ist ausgelagert" beobachtbar statt
     * erhofft — ohne `Thread.sleep` im Test.
     */
    private class LatchedExecutor : Executor {
        @Volatile var capture = false
        private val captured = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) {
            if (capture) captured.add(command) else command.run()
        }
        fun pending(): Int = captured.size
        fun runCaptured(): Int {
            var n = 0
            while (true) {
                val task = captured.poll() ?: return n
                task.run()
                n++
            }
        }
    }

    private fun withHa(block: (url: String, calls: AtomicInteger, body: AtomicReference<String>) -> Unit) {
        val calls = AtomicInteger(0)
        val body = AtomicReference(bodyV1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            calls.incrementAndGet()
            ex.requestBody.readBytes()
            val bytes = body.get().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", calls, body)
        } finally {
            server.stop(0)
        }
    }

    // ── (1) Der Kern des Fixes ───────────────────────────────────────────────
    @Test
    fun `warm aber abgelaufen liefert SOFORT die alten Daten und refresht asynchron`() = withHa { url, calls, body ->
        val exec = LatchedExecutor()
        val clock = MutableClock(startEpochSeconds = 1_000)
        val adapter = HaAreaCatalogAdapter(
            baseUrl = url,
            token = "secret-token",
            ttl = Duration.ofMinutes(15),
            clock = clock,
            refreshExecutor = exec,
        )

        val warm = adapter.areas()
        assertEquals(2, warm.size)
        assertEquals(1, calls.get(), "Erst-Load")

        // Ab jetzt landet jeder Refresh auf dem Executor statt inline zu laufen.
        exec.capture = true
        body.set(bodyV2)
        clock.now = clock.now.plus(Duration.ofMinutes(16)) // TTL abgelaufen

        val stale = adapter.areas()

        assertEquals(warm, stale, "abgelaufener Cache wird SOFORT mit den ALTEN Daten bedient")
        assertEquals(1, calls.get(), "waehrend areas() darf KEIN HTTP-Call passieren (kein Blockieren im Aufrufer)")
        assertEquals(1, exec.pending(), "der Refresh muss auf dem Executor liegen, nicht im Aufrufer gelaufen sein")

        // Erst wenn der ausgelagerte Refresh laeuft, kommt der neue Stand an.
        assertEquals(1, exec.runCaptured())
        assertEquals(2, calls.get(), "der asynchrone Refresh holt die frischen Daten nach")

        val fresh = adapter.areas()
        assertEquals(3, fresh.size, "nach dem async Refresh gilt der neue Stand")
        assertNotEquals(warm, fresh)
        assertEquals(2, calls.get(), "der frische Stand ist wieder innerhalb der TTL — kein weiterer Call")
    }

    @Test
    fun `mehrere abgelaufene Aufrufe loesen nur EINEN Refresh aus (single-flight)`() = withHa { url, calls, _ ->
        val exec = LatchedExecutor()
        val clock = MutableClock(startEpochSeconds = 1_000)
        val adapter = HaAreaCatalogAdapter(
            baseUrl = url,
            token = "secret-token",
            ttl = Duration.ofMinutes(15),
            clock = clock,
            refreshExecutor = exec,
        )
        adapter.areas()
        exec.capture = true
        clock.now = clock.now.plus(Duration.ofMinutes(16))

        repeat(5) { adapter.areas() }

        assertEquals(1, exec.pending(), "fuenf abgelaufene Aufrufe duerfen HA nicht fuenfmal fragen")
        exec.runCaptured()
        assertEquals(2, calls.get())
    }

    // ── (2) Kein Monitor um den HTTP-Call ────────────────────────────────────
    @Test
    fun `ein haengender Refresh blockiert weitere Aufrufer nicht (kein synchronized um HTTP)`() {
        val calls = AtomicInteger(0)
        val hold = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            val n = calls.incrementAndGet()
            ex.requestBody.readBytes()
            if (n > 1) hold.await(10, TimeUnit.SECONDS) // der Refresh-Call haengt
            val bytes = bodyV1.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val clock = MutableClock(startEpochSeconds = 1_000)
            val adapter = HaAreaCatalogAdapter(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                timeoutMs = 2_000,
                clock = clock,
            )
            val warm = adapter.areas()
            assertEquals(2, warm.size)

            clock.now = clock.now.plus(Duration.ofMinutes(16))
            val startedAt = System.nanoTime()
            // Der erste Aufruf startet den (haengenden) Refresh, die weiteren treffen
            // auf dieselbe Baustelle — KEINER darf am Monitor anstehen.
            repeat(3) { assertEquals(warm, adapter.areas()) }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                elapsedMs < 1_000,
                "abgelaufene Aufrufe muessen sofort zurueckkommen, auch waehrend HA haengt (waren ${elapsedMs}ms)",
            )
        } finally {
            hold.countDown()
            server.stop(0)
        }
    }

    // ── (3) Read-Timeout: Header da, Body stockt ─────────────────────────────
    @Test
    fun `ein beim Body stockendes HA wird vom Read-Timeout abgeschnitten statt ewig zu haengen`() {
        val calls = AtomicInteger(0)
        val hold = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            val n = calls.incrementAndGet()
            ex.requestBody.readBytes()
            val bytes = bodyV1.toByteArray()
            if (n > 1) {
                // Header + Content-Length raus, dann beim BODY stocken: genau die
                // halbtote Verbindung, gegen die `HttpRequest.timeout` NICHT schuetzt.
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.write(bytes, 0, 1)
                ex.responseBody.flush()
                hold.await(20, TimeUnit.SECONDS)
                ex.responseBody.close()
            } else {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val exec = LatchedExecutor()
            val clock = MutableClock(startEpochSeconds = 1_000)
            val adapter = HaAreaCatalogAdapter(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "secret-token",
                ttl = Duration.ofMinutes(15),
                timeoutMs = 700,
                clock = clock,
                refreshExecutor = exec,
            )
            val warm = adapter.areas() // Erst-Load laeuft sauber durch
            assertEquals(2, warm.size)

            exec.capture = true
            clock.now = clock.now.plus(Duration.ofMinutes(16))
            assertEquals(warm, adapter.areas(), "stale-while-revalidate bedient weiter aus dem Cache")

            // Den ausgelagerten Refresh HIER laufen lassen und stoppen: ohne den
            // Read-Timeout kaeme dieser Aufruf nie zurueck.
            val startedAt = System.nanoTime()
            exec.runCaptured()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                elapsedMs in 500..5_000,
                "der stockende Body muss nach ~700ms abgeschnitten werden (waren ${elapsedMs}ms)",
            )
            assertEquals(warm, adapter.areas(), "gescheiterter Refresh laesst den letzten guten Stand stehen")
        } finally {
            hold.countDown()
            server.stop(0)
        }
    }

    // ── (4) Event-Loop-Naht: dort wird NIE gewartet ──────────────────────────
    @Test
    fun `auf einem Nicht-Blockier-Thread wartet der kalte Cache nicht sondern liefert den Fallback`() {
        // baseUrl zeigt ins Leere: ein wartender Aufruf wuerde hier messbar haengen.
        val exec = LatchedExecutor().apply { capture = true }
        val adapter = HaAreaCatalogAdapter(
            baseUrl = "http://127.0.0.1:1",
            token = "secret-token",
            timeoutMs = 5_000,
            refreshExecutor = exec,
        )
        val result = AtomicReference<List<de.hoshi.core.port.AreaInfo>>()
        val elapsed = AtomicReference<Long>(0)
        // reactor-core markiert seine parallel-Threads als „non-blocking" — dieselbe
        // Markierung tragen Nettys Event-Loop-Threads in Prod.
        val scheduler = reactor.core.scheduler.Schedulers.newParallel("evloop-probe", 1)
        try {
            val done = CountDownLatch(1)
            scheduler.schedule {
                val t0 = System.nanoTime()
                result.set(adapter.areas())
                elapsed.set((System.nanoTime() - t0) / 1_000_000)
                done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "areas() haette auf dem Event-Loop nie warten duerfen")
            assertTrue(elapsed.get() < 1_000, "kein Warten auf dem Event-Loop (waren ${elapsed.get()}ms)")
            assertEquals(AreaCatalogPort.STATIC.areas(), result.get(), "kalt + Event-Loop ⇒ statischer Fallback")
            assertEquals(1, exec.pending(), "der Load wurde trotzdem angestossen (naechster Turn sieht echte Areas)")
        } finally {
            scheduler.dispose()
        }
    }
}
