package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.DeviceDownlinkPort
import de.hoshi.core.port.SttPort
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * **AudioWebSocketDownlinkTest** — beweist die ws-Connect-Naht des Device-
 * Downlink-Kanals (Nachtmodus-Vorstufe, Scheibe 1) am echten [AudioWebSocketHandler]
 * OHNE Socket/Brain (Muster [AudioWebSocketDiaryTraceTest]: interne Test-Seams
 * [AudioWebSocketHandler.openSession]/[AudioWebSocketHandler.onText]/
 * [AudioWebSocketHandler.closeSession] statt eines vollen FakeWebSocketSession):
 *
 *  1. `start{satelliteId}` + Flag ON ⇒ die [WsDeviceRegistry] kennt das Gerät,
 *     [DeviceDownlinkPort.pushToDevice] liefert danach ein Frame an den echten
 *     Session-Sink.
 *  2. [AudioWebSocketHandler.closeSession] ⇒ das Gerät verschwindet wieder aus
 *     der Registry — kein Leak.
 *  3. Flag OFF (Default) ⇒ trotz `satelliteId` im `start`-Frame KEINE
 *     Registrierung, der optionale Hook feuert nie, der Wire-Fluss bleibt
 *     Frame-für-Frame identisch — byte-neutral.
 */
class AudioWebSocketDownlinkTest {

    private val mapper = ObjectMapper()
    private val noAudioTts = TtsPort { _, _ -> Mono.just(ByteArray(0)) }
    private val ttsStage = TtsStage(tts = noAudioTts)
    private val perimeter = de.hoshi.kernel.PerimeterPort(enabled = true, configuredToken = "test-secret-token")
    private val cannedTurn: (ChatRequest) -> Flux<ChatEvent> = { Flux.empty() }
    private val stt = SttPort { _, _ -> Mono.just("") }

    private fun handler(
        deviceRegistry: WsDeviceRegistry? = null,
        downlinkPushEnabled: Boolean = false,
        onDeviceConnected: (String, DeviceDownlinkPort) -> Unit = { _, _ -> },
    ): AudioWebSocketHandler =
        AudioWebSocketHandler(
            stt = stt,
            ttsStage = ttsStage,
            perimeter = perimeter,
            objectMapper = mapper,
            runTurn = cannedTurn,
            deviceRegistry = deviceRegistry,
            downlinkPushEnabled = downlinkPushEnabled,
            onDeviceConnected = onDeviceConnected,
        )

    @Test
    fun `start mit satelliteId und Flag ON registriert das Geraet - pushToDevice erreicht den echten Sink`() {
        val registry = WsDeviceRegistry(mapper)
        val hookCalls = mutableListOf<String>()
        val h = handler(
            deviceRegistry = registry,
            downlinkPushEnabled = true,
            onDeviceConnected = { satelliteId, _ -> hookCalls.add(satelliteId) },
        )
        val sessionId = "sess-1"
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","satelliteId":"sat-kueche"}""")

        assertEquals(setOf("sat-kueche"), registry.connectedDevices(), "die Registry kennt das Gerät")
        assertEquals(listOf("sat-kueche"), hookCalls, "der Hook feuert genau einmal mit der satelliteId")

        val received = mutableListOf<String>()
        h.sinks[sessionId]!!.asFlux().subscribe { received.add(it) }
        val ok = registry.pushToDevice("sat-kueche", mapOf("type" to "night_mode", "active" to true))

        assertTrue(ok, "der Push erreicht den registrierten Session-Sink")
        assertEquals(1, received.size)
        assertEquals("night_mode", mapper.readTree(received.first())["type"].asText())
    }

    @Test
    fun `closeSession entfernt das Geraet wieder aus der Registry - kein Leak`() {
        val registry = WsDeviceRegistry(mapper)
        val h = handler(deviceRegistry = registry, downlinkPushEnabled = true)
        val sessionId = "sess-2"
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","satelliteId":"sat-buero"}""")
        assertEquals(setOf("sat-buero"), registry.connectedDevices())

        h.closeSession(sessionId)

        assertEquals(emptySet<String>(), registry.connectedDevices(), "kein Leak nach Session-Ende")
        assertFalse(registry.pushToDevice("sat-buero", mapOf("type" to "night_mode")), "push nach Close ⇒ false")
    }

    @Test
    fun `Flag OFF - trotz satelliteId keine Registrierung, kein Hook-Call, Wire unveraendert`() {
        val registry = WsDeviceRegistry(mapper)
        var hookCalled = false
        // downlinkPushEnabled bewusst NICHT gesetzt ⇒ Default false, obwohl ein
        // deviceRegistry UND ein Hook verdrahtet sind — der Flag muss allein entscheiden.
        val h = handler(
            deviceRegistry = registry,
            downlinkPushEnabled = false,
            onDeviceConnected = { _, _ -> hookCalled = true },
        )
        val sessionId = "sess-3"
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","satelliteId":"sat-off","turnId":"t1","language":"de"}""")
        h.onBinary(sessionId, ByteArray(400))
        h.onText(sessionId, """{"type":"stop"}""")

        assertEquals(emptySet<String>(), registry.connectedDevices(), "Flag OFF ⇒ keine Registrierung")
        assertFalse(hookCalled, "Flag OFF ⇒ der Hook feuert nie")

        val frames = mutableListOf<String>()
        h.sinks[sessionId]!!.asFlux().subscribe { frames.add(it) }
        assertEquals(
            listOf("transcribing_started", "transcript", "no_input"),
            frames.map { mapper.readTree(it)["type"].asText() },
            "der Wire-Fluss bleibt exakt der heutige — byte-neutral",
        )
    }

    @Test
    fun `Default-Konstruktion ganz ohne Downlink-Wiring bleibt byte-neutral (kein Crash bei satelliteId)`() {
        // KEIN deviceRegistry, KEIN Flag, KEIN Hook übergeben — exakt der Zustand
        // aller bestehenden Aufrufer vor dieser Scheibe.
        val h = AudioWebSocketHandler(
            stt = stt, ttsStage = ttsStage, perimeter = perimeter, objectMapper = mapper, runTurn = cannedTurn,
        )
        val sessionId = "sess-4"
        h.openSession(sessionId)
        h.onText(sessionId, """{"type":"start","satelliteId":"sat-egal"}""")
        h.closeSession(sessionId)
        // Kein Crash ist der Beweis — ohne deviceRegistry ist [registerDevice] ein reines no-op.
    }
}
