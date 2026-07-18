package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **WsDeviceRegistryTest** — beweist den Device-Downlink-Kanal (Nachtmodus-
 * Vorstufe, Scheibe 1 von 3) isoliert vom [AudioWebSocketHandler]: [pushToDevice]
 * liefert ein Frame an den registrierten Sink; ein unbekanntes Gerät liefert
 * `false` (kein Crash); [unregister] räumt sauber ab (kein Leak, bewiesen über
 * [connectedDevices]); und ein Push NEBENLÄUFIG zu einem simulierten
 * Turn-Schreiber (echte Threads, kein Mock-Race) korrumpiert den Stream nicht.
 */
class WsDeviceRegistryTest {

    private val mapper = ObjectMapper()
    private fun registry() = WsDeviceRegistry(mapper)

    @Test
    fun `pushToDevice liefert ein Frame an den registrierten Session-Sink`() {
        val reg = registry()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        val received = mutableListOf<String>()
        sink.asFlux().subscribe { received.add(it) }

        reg.register("sat-kueche", sink)
        val ok = reg.pushToDevice("sat-kueche", mapOf("type" to "night_mode", "active" to true))

        assertTrue(ok, "bekanntes Gerät ⇒ Zustellung meldet true")
        assertEquals(1, received.size)
        val node = mapper.readTree(received.first())
        assertEquals("night_mode", node["type"].asText())
        assertTrue(node["active"].asBoolean())
    }

    @Test
    fun `unbekannte satelliteId liefert false, kein Crash`() {
        val reg = registry()
        val ok = reg.pushToDevice("nie-registriert", mapOf("type" to "night_mode"))
        assertFalse(ok, "unbekanntes Gerät ⇒ false, kein Frame")
    }

    @Test
    fun `unregister entfernt die Session aus der Registry - kein Leak`() {
        val reg = registry()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sink.asFlux().subscribe { }

        reg.register("sat-buero", sink)
        assertEquals(setOf("sat-buero"), reg.connectedDevices(), "nach register bekannt")

        reg.unregister("sat-buero")
        assertEquals(emptySet<String>(), reg.connectedDevices(), "nach unregister weg — kein Leak")
        assertFalse(reg.pushToDevice("sat-buero", mapOf("type" to "night_mode")), "push nach unregister ⇒ false")
    }

    @Test
    fun `connectedDevices spiegelt mehrere registrierte Geraete wider`() {
        val reg = registry()
        val sinkA = Sinks.many().unicast().onBackpressureBuffer<String>().also { it.asFlux().subscribe { } }
        val sinkB = Sinks.many().unicast().onBackpressureBuffer<String>().also { it.asFlux().subscribe { } }
        reg.register("sat-a", sinkA)
        reg.register("sat-b", sinkB)
        assertEquals(setOf("sat-a", "sat-b"), reg.connectedDevices())
    }

    // ── Nebenläufigkeit: echte Threads statt Mock-Race ──────────────────────
    // Ein Turn-Schreiber (mimt AudioWebSocketHandler.onStop: sink.tryEmitNext,
    // KEIN Retry, exakt wie im Handler) und der Downlink-Push (emitNext +
    // busyLooping) schreiben GLEICHZEITIG auf DENSELBEN Sink. Bewiesen wird:
    // (1) JEDER empfangene Frame ist gültiges, unkorrumpiertes JSON (nie
    // interleaved/abgeschnitten), (2) JEDER Push kommt an (busyLooping verliert
    // unter Kontention nichts) — die zentrale Thread-Sicherheits-Invariante
    // dieser Scheibe.
    @Test
    fun `Push nebenlaeufig zu einem laufenden Turn korrumpiert den Stream nicht`() {
        val reg = registry()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        val received = CopyOnWriteArrayList<String>()
        sink.asFlux().subscribe { received.add(it) }
        reg.register("sat-race", sink)

        val turnFrames = 500
        val pushFrames = 500
        val startLatch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val turnWriter = pool.submit {
            startLatch.await()
            repeat(turnFrames) { i ->
                // Exakt das bestehende Handler-Muster (tryEmitNext, Ergebnis ignoriert,
                // s. AudioWebSocketHandler.onStop/onBinary/enforceSessionGuard).
                sink.tryEmitNext("""{"type":"llm_delta","seq":$i}""")
            }
        }
        val pushResults = CopyOnWriteArrayList<Boolean>()
        val pusher = pool.submit {
            startLatch.await()
            repeat(pushFrames) { i ->
                pushResults.add(reg.pushToDevice("sat-race", mapOf("type" to "night_mode", "seq" to i)))
            }
        }
        startLatch.countDown()
        turnWriter.get(15, TimeUnit.SECONDS)
        pusher.get(15, TimeUnit.SECONDS)
        pool.shutdown()

        // Invariante 1: JEDER angekommene Frame ist valides, vollständiges JSON —
        // ein korrupter/interleaved Schreibzugriff würde hier beim Parsen auffliegen.
        received.forEach { frame ->
            val node = mapper.readTree(frame)
            assertTrue(node.has("type"), "jeder Frame ist vollständiges, parsebares JSON: $frame")
        }

        // Invariante 2: emitNext+busyLooping verliert unter Kontention KEINEN Push.
        assertEquals(pushFrames, pushResults.count { it }, "jeder Push kam durch (busyLooping)")
        assertEquals(
            pushFrames,
            received.count { mapper.readTree(it)["type"].asText() == "night_mode" },
            "alle night_mode-Pushes kamen unkorrumpiert am Sink an",
        )
    }
}
