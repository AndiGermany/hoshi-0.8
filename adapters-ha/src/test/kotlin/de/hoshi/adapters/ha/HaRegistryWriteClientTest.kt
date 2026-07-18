package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **HaRegistryWriteClientTest** — der HA-WebSocket-Schreib-Vertrag AUF
 * PROTOKOLL-EBENE, ohne echten Socket: [RegistryWriteProtocol] wird Nachricht für
 * Nachricht gefahren (auth_required → auth, auth_ok → update, result/auth_invalid
 * → Outcome). So ist der Handshake deterministisch prüfbar; die dünne Socket-Glue
 * ([HaRegistryWriteClient.ProtoListener]) trägt keine Logik, die hier fehlen würde.
 *
 * Der no-token-Kurzschluss des äußeren Clients (kein Connect, ehrlich `Failed`)
 * ist zusätzlich direkt geprüft.
 */
class HaRegistryWriteClientTest {

    private val mapper = ObjectMapper()

    private fun proto() = RegistryWriteProtocol(
        token = "secret-token",
        entityId = "light.wohnzimmer_decke",
        areaId = "wohnzimmer",
        mapper = mapper,
    )

    @Test
    fun `auth_required - Client sendet den auth-Frame mit dem Token`() {
        val p = proto()
        val frames = p.onMessage("""{"type":"auth_required","ha_version":"2026.7"}""")

        assertEquals(1, frames.size)
        val sent = mapper.readTree(frames[0])
        assertEquals("auth", sent.get("type").asText())
        assertEquals("secret-token", sent.get("access_token").asText())
        assertFalse(p.result.isDone) // noch kein Ergebnis — der Handshake läuft
    }

    @Test
    fun `auth_ok - Client sendet den entity_registry-update mit entity_id + area_id`() {
        val p = proto()
        val frames = p.onMessage("""{"type":"auth_ok","ha_version":"2026.7"}""")

        assertEquals(1, frames.size)
        val cmd = mapper.readTree(frames[0])
        assertEquals("config/entity_registry/update", cmd.get("type").asText())
        assertEquals("light.wohnzimmer_decke", cmd.get("entity_id").asText())
        assertEquals("wohnzimmer", cmd.get("area_id").asText())
        assertEquals(1, cmd.get("id").asInt())
    }

    @Test
    fun `voller Handshake mit result success true - Outcome Ok`() {
        val p = proto()
        p.onMessage("""{"type":"auth_required"}""")
        p.onMessage("""{"type":"auth_ok"}""")
        p.onMessage("""{"id":1,"type":"result","success":true,"result":{}}""")

        assertTrue(p.result.isDone)
        assertEquals(RegistryWriteOutcome.Ok, p.result.get())
    }

    @Test
    fun `result success false - Outcome Failed mit HA-Fehlerklasse`() {
        val p = proto()
        p.onMessage("""{"type":"auth_ok"}""")
        p.onMessage("""{"id":1,"type":"result","success":false,"error":{"code":"not_found","message":"x"}}""")

        val outcome = p.result.get()
        assertTrue(outcome is RegistryWriteOutcome.Failed)
        assertEquals("ha-error:not_found", (outcome as RegistryWriteOutcome.Failed).reason)
    }

    @Test
    fun `auth_invalid - Outcome Failed, kein update gesendet`() {
        val p = proto()
        val frames = p.onMessage("""{"type":"auth_invalid","message":"Invalid access token"}""")

        assertTrue(frames.isEmpty())
        assertTrue(p.result.isDone)
        assertEquals(RegistryWriteOutcome.Failed("auth_invalid"), p.result.get())
    }

    @Test
    fun `kaputte JSON-Nachricht - Outcome Failed (bad-json), kein Hänger`() {
        val p = proto()
        p.onMessage("nicht-json <html>")

        assertTrue(p.result.isDone)
        assertEquals(RegistryWriteOutcome.Failed("bad-json"), p.result.get())
    }

    @Test
    fun `fremde result-id wird ignoriert - kein vorzeitiges Ergebnis`() {
        val p = proto()
        p.onMessage("""{"type":"auth_ok"}""")
        p.onMessage("""{"id":99,"type":"result","success":true}""") // gehört nicht zu unserem Kommando

        assertFalse(p.result.isDone)
    }

    @Test
    fun `kein Token - assignEntityArea gibt Failed(no-token) ohne Connect zurueck`() {
        val client = HaRegistryWriteClient(baseUrl = "http://127.0.0.1:1", token = null)
        val outcome = client.assignEntityArea("light.x", "wohnzimmer")

        assertEquals(RegistryWriteOutcome.Failed("no-token"), outcome)
    }

    @Test
    fun `leerer Token - ebenfalls Failed(no-token)`() {
        val client = HaRegistryWriteClient(baseUrl = "http://127.0.0.1:1", token = "   ")
        assertEquals(RegistryWriteOutcome.Failed("no-token"), client.assignEntityArea("light.x", "kueche"))
    }
}
