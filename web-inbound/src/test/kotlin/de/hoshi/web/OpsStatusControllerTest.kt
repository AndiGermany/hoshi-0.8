package de.hoshi.web

import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import de.hoshi.core.supervision.SidecarSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **OpsStatusControllerTest** — beweist die Controller-Abbildung OHNE Boot:
 *  - Flag OFF ⇒ `status()` liefert exakt `{enabled=false}` (byte-neutral).
 *  - Flag ON  ⇒ `status()` liefert den letzten [OpsStatus]-Snapshot (voller Contract).
 *
 * Der Controller delegiert nur an [SidecarHealthService.current] (kein Blocking, kein
 * Probe-Call im Request) — wir konstruieren den Service direkt mit Fakes.
 */
class OpsStatusControllerTest {

    private val okProbe = SidecarPort { sidecar: SidecarSpec -> SidecarHealth.ok("status=ok (fake) ${sidecar.name}") }

    private fun service(enabled: Boolean, ttsImpl: String = "") = SidecarHealthService(
        enabled = enabled,
        brainUrl = "http://localhost:8041",
        sttUrl = "http://localhost:9001",
        ttsUrl = "http://localhost:8042",
        bridgeUrl = "http://localhost:8035",
        speakerUrl = "http://localhost:9002",
        failureThreshold = 2,
        ttsImpl = ttsImpl,
        probe = okProbe,
        brainHealth = { """{"status":"ok","wired":{"memorystatus_level":70,"release_lvl":25,"reapply_lvl":40}}""" },
    )

    @Test
    fun `Flag OFF — Controller liefert nur enabled-false`() {
        val controller = OpsStatusController(service(enabled = false))
        assertEquals(mapOf("enabled" to false), controller.status())
    }

    @Test
    fun `Flag ON — Controller liefert den vollen Snapshot-Contract`() {
        val svc = service(enabled = true)
        svc.refresh()
        val body = OpsStatusController(svc).status()

        assertTrue(body is OpsStatus, "ON liefert das volle OpsStatus-Objekt")
        body as OpsStatus
        assertTrue(body.enabled)
        assertEquals("OK", body.overall)
        assertEquals("OK", body.memory.level)
        assertEquals("brain-health", body.memory.source)
        assertTrue(body.sidecars.any { it.name == "brain" }, "der brain-Sidecar ist im Report")
        assertTrue(body.ts > 0, "Zeitstempel gesetzt")
    }

    // ── voice-Vertrag (Toms ☁️-Cloud-Banner): Engine + Cloud-Flag aus der Boot-Config ──

    @Test
    fun `voice — HOSHI_TTS=openai ⇒ engine openai, cloud true (Egress-Pfad, Banner-Pflicht)`() {
        val svc = service(enabled = true, ttsImpl = "openai")
        svc.refresh()
        val body = OpsStatusController(svc).status() as OpsStatus
        assertEquals(VoiceStatus(engine = "openai", cloud = true), body.voice)
    }

    @Test
    fun `voice — HOSHI_TTS leer (Default) ⇒ engine voxtral, cloud false (lokal, kein Banner)`() {
        val svc = service(enabled = true)
        svc.refresh()
        val body = OpsStatusController(svc).status() as OpsStatus
        assertEquals(VoiceStatus(engine = "voxtral", cloud = false), body.voice)
    }

    @Test
    fun `voice — steht auch im Warmup-Snapshot (VOR dem ersten Probe-Lauf, Boot-Config-Wahrheit)`() {
        // Kein refresh(): der Controller liefert den Warmup-Snapshot — die Engine
        // ist boot-statisch und muss trotzdem schon ehrlich drinstehen.
        val body = OpsStatusController(service(enabled = true, ttsImpl = "OpenAI")).status() as OpsStatus
        assertEquals(VoiceStatus(engine = "openai", cloud = true), body.voice, "case-insensitive wie PipelineConfig")
    }
}
