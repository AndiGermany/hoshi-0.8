package de.hoshi.web

import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import de.hoshi.core.supervision.SidecarSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * **SidecarHealthServiceTest** — beweist die Status-Klassifikation (OK/DEGRADED/DOWN),
 * die Consecutive-Failure-Glättung und die Mac-RAM-Druck-Ableitung aus der Brain-Health
 * OHNE Live-Infra: eine scriptbare Fake-[SidecarPort] + eine Fake-[BrainHealthSource].
 */
class SidecarHealthServiceTest {

    /**
     * Scriptbare Probe: liefert pro Sidecar-Name eine kanned [SidecarHealth] (Default OK),
     * ODER — wenn [measuredModels] für den Namen gesetzt ist UND KEIN expliziter
     * [responses]-Eintrag existiert — spiegelt sie GENAU das [de.hoshi.adapters.supervision.HttpSidecarProbe]-
     * Drift-Mapping (`model.contains(expected)`), damit die Tests unten beweisen, dass
     * SidecarHealthService die RICHTIGE `expectedModel` in die [SidecarSpec] legt, nicht
     * nur, dass irgendein Status durchgereicht wird. [specsSeen] hält die zuletzt geprobte
     * Spec pro Name fest (für direkte `expectedModel`-Assertions).
     */
    private class ScriptedProbe : SidecarPort {
        val responses = HashMap<String, SidecarHealth>()
        val measuredModels = HashMap<String, String>()
        val specsSeen = HashMap<String, SidecarSpec>()

        override fun probe(sidecar: SidecarSpec): SidecarHealth {
            specsSeen[sidecar.name] = sidecar
            responses[sidecar.name]?.let { return it }
            val measured = measuredModels[sidecar.name] ?: return SidecarHealth.ok("status=ok (fake)")
            val expected = sidecar.expectedModel
            return if (expected != null && !measured.contains(expected)) {
                SidecarHealth.degraded("model='$measured' enthält nicht soll='$expected' (Drift)", measured)
            } else {
                SidecarHealth.ok("status=ok model='$measured'", measured)
            }
        }
    }

    private fun service(
        probe: SidecarPort,
        brainBody: String? = OK_HEALTH,
        enabled: Boolean = true,
        threshold: Int = 2,
        brainExpectedModel: String = "",
        brainModelStore: JsonFileBrainModelStore? = null,
        ttsImpl: String = "",
        ttsEngineStore: JsonFileTtsEngineStore? = null,
    ) = SidecarHealthService(
        enabled = enabled,
        brainUrl = "http://localhost:8041",
        sttUrl = "http://localhost:9001",
        ttsUrl = "http://localhost:8042",
        bridgeUrl = "http://localhost:8035",
        speakerUrl = "http://localhost:9002",
        failureThreshold = threshold,
        brainExpectedModel = brainExpectedModel,
        ttsImpl = ttsImpl,
        probe = probe,
        brainHealth = { brainBody },
        brainModelStore = brainModelStore,
        ttsEngineStore = ttsEngineStore,
    )

    private fun OpsStatus.sidecar(name: String): SidecarStatus =
        sidecars.first { it.name == name }

    // ── Status-Klassifikation ──────────────────────────────────────────────────

    @Test
    fun `alle Sidecars erreichbar — overall OK`() {
        val svc = service(ScriptedProbe())
        svc.refresh()
        val status = svc.current() as OpsStatus
        assertEquals("OK", status.overall)
        assertTrue(status.sidecars.all { it.status == "OK" }, "alle Nähte OK")
        assertEquals(5, status.sidecars.size, "alle 5 bekannten Sidecars im Report")
    }

    @Test
    fun `optionale Sidecars (Voxtral, Speaker-ID) DOWN treiben overall NICHT (cry-wolf-Schutz)`() {
        val probe = ScriptedProbe().apply {
            responses["voxtral-tts"] = SidecarHealth.down("connection refused (bewusst aus)")
            responses["speaker-id"] = SidecarHealth.down("connection refused (bewusst aus)")
        }
        val svc = service(probe)
        svc.refresh() // 1. DOWN → geglättet
        svc.refresh() // 2. DOWN → roh
        val status = svc.current() as OpsStatus
        assertEquals("OK", status.overall, "off-by-design-Sidecars dürfen die RAM-Pille nicht rot färben")
        assertEquals("DOWN", status.sidecar("voxtral-tts").status, "im Report bleibt der ehrliche DOWN-Status")
        assertEquals("DOWN", status.sidecar("speaker-id").status)
    }

    @Test
    fun `dauerhaft DEGRADED-Sidecar — overall DEGRADED nach Schwelle`() {
        val probe = ScriptedProbe().apply {
            responses["whisper-stt"] = SidecarHealth.degraded("status=loading (Warmup)")
        }
        val svc = service(probe)
        svc.refresh() // 1. Nicht-OK → noch geglättet
        svc.refresh() // 2. Nicht-OK → meldet jetzt DEGRADED
        val status = svc.current() as OpsStatus
        assertEquals("DEGRADED", status.overall)
        assertEquals("DEGRADED", status.sidecar("whisper-stt").status)
    }

    @Test
    fun `dauerhaft DOWN-Sidecar — overall DOWN nach Schwelle (DOWN dominiert)`() {
        val probe = ScriptedProbe().apply {
            responses["brain"] = SidecarHealth.down("connection refused")
            responses["bridge"] = SidecarHealth.degraded("2xx ohne status")
        }
        val svc = service(probe)
        svc.refresh()
        svc.refresh()
        val status = svc.current() as OpsStatus
        assertEquals("DOWN", status.overall, "DOWN dominiert DEGRADED")
        assertEquals("DOWN", status.sidecar("brain").status)
        assertEquals("DEGRADED", status.sidecar("bridge").status)
    }

    // ── Consecutive-Failure-Glättung ────────────────────────────────────────────

    @Test
    fun `einzelner DOWN-Blip alarmiert NICHT (geglaettet OK)`() {
        val probe = ScriptedProbe().apply {
            responses["brain"] = SidecarHealth.down("kurzer Aussetzer")
        }
        val svc = service(probe, threshold = 2)
        svc.refresh() // genau EIN DOWN
        val status = svc.current() as OpsStatus
        assertEquals("OK", status.sidecar("brain").status, "ein einzelner Blip bleibt geglättet OK")
        assertEquals("OK", status.overall)
        assertTrue(status.sidecar("brain").detail.contains("geglättet"), "Detail nennt die Glättung ehrlich")
    }

    @Test
    fun `zwei aufeinanderfolgende DOWN flippen auf DOWN, Erholung resettet`() {
        val probe = ScriptedProbe()
        val svc = service(probe, threshold = 2)

        probe.responses["brain"] = SidecarHealth.down("refused")
        svc.refresh() // 1 → geglättet OK
        assertEquals("OK", (svc.current() as OpsStatus).sidecar("brain").status)
        svc.refresh() // 2 → DOWN
        assertEquals("DOWN", (svc.current() as OpsStatus).sidecar("brain").status)

        probe.responses["brain"] = SidecarHealth.ok("status=ok")
        svc.refresh() // Erholung → Zähler zurückgesetzt
        assertEquals("OK", (svc.current() as OpsStatus).sidecar("brain").status)
    }

    // ── Mac-RAM-Druck aus der Brain-Health ──────────────────────────────────────

    @Test
    fun `memory OK bei hohem memorystatus_level`() {
        val m = BrainMemoryHeuristic.classify(health(level = 60))
        assertEquals("OK", m.level)
        assertEquals("brain-health", m.source)
    }

    @Test
    fun `memory WARN im Hysterese-Band (release ≤ level lt reapply)`() {
        assertEquals("WARN", BrainMemoryHeuristic.classify(health(level = 30)).level)
    }

    @Test
    fun `memory CRITICAL unter release-Schwelle`() {
        assertEquals("CRITICAL", BrainMemoryHeuristic.classify(health(level = 10)).level)
    }

    @Test
    fun `memory UNKNOWN bei level -1, fehlendem wired und fehlender Antwort`() {
        assertEquals("UNKNOWN", BrainMemoryHeuristic.classify(health(level = -1)).level)
        assertEquals("UNKNOWN", BrainMemoryHeuristic.classify("""{"status":"ok","model":"x"}""").level)
        assertEquals("UNKNOWN", BrainMemoryHeuristic.classify(null).level)
        assertEquals("UNKNOWN", BrainMemoryHeuristic.classify("nicht json {{{").level)
    }

    @Test
    fun `memory CRITICAL hebt overall auf DEGRADED (Andis RAM-Alarm)`() {
        val svc = service(ScriptedProbe(), brainBody = health(level = 5))
        svc.refresh()
        val status = svc.current() as OpsStatus
        assertEquals("CRITICAL", status.memory.level)
        assertEquals("DEGRADED", status.overall, "RAM-Druck CRITICAL hebt das Gesamt-Signal an")
    }

    // ── Drift-Soll folgt dem GEWÄHLTEN Brain-Modell (Andi-Befund 2026-07-20) ─────
    //
    // Vorher verglich die Drift-Prüfung IMMER gegen das per-Deploy fixierte
    // Boot-Literal (`brainExpectedModel`/`HOSHI_BRAIN_EXPECTED_MODEL`). Wählte Andi
    // über die Settings-UI bewusst e4b, während das Deploy-Literal noch e2b nannte,
    // meldete die Übersicht fälschlich „Drift" — obwohl e4b GENAU das gewünschte,
    // laufende Modell war. Jetzt gewinnt IMMER der `JsonFileBrainModelStore`-Wert
    // (das GEWÄHLTE Modell aus PUT /settings/brain), der Boot-Default gilt NUR,
    // solange nie umgeschaltet wurde.

    @Test
    fun `Boot-Default-Fall (nie umgeschaltet) - Soll bleibt das Deploy-Literal, Drift wenn Ist abweicht`(
        @TempDir dir: Path,
    ) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")) // nie gesetzt
        val probe = ScriptedProbe().apply {
            measuredModels["brain"] = "mlx-community/gemma-4-e4b-it-4bit" // läuft e4b …
        }
        // … aber das Deploy-Literal (Boot-Default) nennt weiter e2b als Soll.
        val svc = service(probe, brainExpectedModel = "gemma-4-e2b-it-4bit", brainModelStore = store, threshold = 1)
        svc.refresh()

        assertEquals("gemma-4-e2b-it-4bit", probe.specsSeen["brain"]?.expectedModel, "ohne Runtime-Switch gilt der Boot-Default")
        assertEquals("DEGRADED", (svc.current() as OpsStatus).sidecar("brain").status)
        assertTrue(
            (svc.current() as OpsStatus).sidecar("brain").detail.contains("Drift"),
            "Ist (e4b) weicht vom Boot-Default-Soll (e2b) ab ⇒ ehrlich Drift",
        )
    }

    @Test
    fun `gewaehlt = laufend (Runtime-Switch auf e4b, Brain laeuft e4b) - KEIN Drift`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")).apply {
            setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit") // Andi hat bewusst e4b gewählt
        }
        val probe = ScriptedProbe().apply {
            measuredModels["brain"] = "mlx-community/gemma-4-e4b-it-4bit" // … und e4b läuft auch wirklich
        }
        // Das Deploy-Literal nennt weiterhin e2b — das darf NICHT mehr zählen.
        val svc = service(probe, brainExpectedModel = "gemma-4-e2b-it-4bit", brainModelStore = store, threshold = 1)
        svc.refresh()

        assertEquals(
            "mlx-community/gemma-4-e4b-it-4bit",
            probe.specsSeen["brain"]?.expectedModel,
            "das GEWÄHLTE Modell überschreibt das statische Boot-Literal",
        )
        val status = svc.current() as OpsStatus
        assertEquals("OK", status.sidecar("brain").status, "gewählt == laufend ⇒ kein Drift-Befund")
        assertFalse(status.sidecar("brain").detail.contains("Drift"), "die Geister-Drift-Meldung darf nicht mehr auftauchen")
    }

    @Test
    fun `gewaehlt != laufend (Runtime-Switch auf e4b, Brain haengt noch auf e2b) - Drift ehrlich gemeldet`(
        @TempDir dir: Path,
    ) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")).apply {
            setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit") // Andi will e4b …
        }
        val probe = ScriptedProbe().apply {
            measuredModels["brain"] = "mlx-community/gemma-4-e2b-it-4bit" // … aber es läuft noch e2b
        }
        val svc = service(probe, brainExpectedModel = "", brainModelStore = store, threshold = 1)
        svc.refresh()

        val status = svc.current() as OpsStatus
        assertEquals("DEGRADED", status.sidecar("brain").status, "gewählt != laufend bleibt ein ehrlicher Drift-Befund")
        assertTrue(status.sidecar("brain").detail.contains("Drift"))
    }

    // ── Voice folgt der GEWÄHLTEN TTS-Engine (dieselbe Wahrheit wie Settings, b4844d0) ──

    @Test
    fun `Runtime-Switch auf eine lokale Engine ueberschreibt den Cloud-Boot-Default`(@TempDir dir: Path) {
        val ttsStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).apply { setEngineId("piper") }
        // Boot-Default war openai (Cloud) — der Runtime-Switch auf piper muss gewinnen.
        val svc = service(ScriptedProbe(), ttsImpl = "openai", ttsEngineStore = ttsStore, threshold = 1)
        svc.refresh()

        val voice = (svc.current() as OpsStatus).voice
        assertEquals(VoiceStatus(engine = "piper", cloud = false), voice, "die gewählte lokale Engine gewinnt gegen den Cloud-Boot-Default")
    }

    @Test
    fun `ohne Runtime-Switch bleibt die Boot-Engine die Wahrheit`() {
        val svc = service(ScriptedProbe(), ttsImpl = "openai", threshold = 1) // kein ttsEngineStore-Override
        svc.refresh()
        assertEquals(VoiceStatus(engine = "openai", cloud = true), (svc.current() as OpsStatus).voice)
    }

    // ── allLocal — das grüne Schloss (Andi-Wunsch 2026-07-20) ────────────────────

    @Test
    fun `allLocal ist true, wenn STT OK, Brain OK und die gewaehlte TTS-Engine lokal ist`(@TempDir dir: Path) {
        val ttsStore = JsonFileTtsEngineStore(dir.resolve("tts-engine.json")).apply { setEngineId("say") }
        val svc = service(ScriptedProbe(), ttsImpl = "openai", ttsEngineStore = ttsStore, threshold = 1)
        svc.refresh()
        assertTrue((svc.current() as OpsStatus).allLocal, "STT+Brain OK und Engine 'say' ist lokal ⇒ Schloss")
    }

    @Test
    fun `allLocal ist false, wenn die TTS-Engine openai (Cloud) ist`() {
        val svc = service(ScriptedProbe(), ttsImpl = "openai", threshold = 1)
        svc.refresh()
        assertFalse((svc.current() as OpsStatus).allLocal, "Cloud-Engine ⇒ kein Schloss, auch wenn STT/Brain OK sind")
    }

    @Test
    fun `allLocal ist false, wenn Brain DEGRADED ist (Drift), selbst bei lokaler Engine`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")).apply {
            setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit")
        }
        val probe = ScriptedProbe().apply { measuredModels["brain"] = "mlx-community/gemma-4-e2b-it-4bit" }
        val svc = service(probe, brainModelStore = store, threshold = 1) // ttsImpl leer ⇒ voxtral (lokal)
        svc.refresh()
        assertFalse((svc.current() as OpsStatus).allLocal, "Brain-Drift darf das Schloss NICHT grün lassen")
    }

    @Test
    fun `allLocal ist false vor dem ersten Probe-Lauf (Warmup, nichts bewiesen)`() {
        val svc = service(ScriptedProbe()) // kein refresh()
        assertFalse((svc.current() as OpsStatus).allLocal, "ehrliches 'noch nicht bewiesen', kein optimistisches Gruen")
    }

    // ── Flag OFF / Warmup ───────────────────────────────────────────────────────

    @Test
    fun `Flag OFF — current ist byte-neutral {enabled false}`() {
        val svc = service(ScriptedProbe(), enabled = false)
        svc.refresh() // darf nichts tun
        assertEquals(mapOf("enabled" to false), svc.current())
    }

    // ── statusOf — die additive Lese-Naht fürs STT-Readiness-Gate ────────────────

    @Test
    fun `statusOf liest den geglaetteten Sidecar-Status (DOWN nach Schwelle)`() {
        val probe = ScriptedProbe().apply {
            responses["whisper-stt"] = SidecarHealth.down("connection refused")
        }
        val svc = service(probe, threshold = 2)
        svc.refresh() // 1. DOWN → noch geglättet OK
        assertEquals("OK", svc.statusOf("whisper-stt"), "ein Blip bleibt geglättet OK")
        svc.refresh() // 2. DOWN → meldet jetzt DOWN
        assertEquals("DOWN", svc.statusOf("whisper-stt"), "ab Schwelle meldet statusOf DOWN")
    }

    @Test
    fun `statusOf ist null bei Watchdog OFF (UNKNOWN, Gate laesst durch)`() {
        val probe = ScriptedProbe().apply {
            responses["whisper-stt"] = SidecarHealth.down("egal")
        }
        val svc = service(probe, enabled = false)
        svc.refresh() // darf nichts proben
        assertEquals(null, svc.statusOf("whisper-stt"), "Watchdog aus ⇒ kein Snapshot ⇒ UNKNOWN")
    }

    @Test
    fun `statusOf ist null vor dem ersten Probe-Lauf und fuer unbekannte Namen`() {
        val svc = service(ScriptedProbe()) // enabled, aber noch kein refresh()
        assertEquals(null, svc.statusOf("whisper-stt"), "Warmup vor erstem Probe-Lauf ⇒ UNKNOWN")
        svc.refresh()
        assertEquals(null, svc.statusOf("gibt-es-nicht"), "unbekannter Sidecar-Name ⇒ UNKNOWN")
    }

    companion object {
        private const val OK_HEALTH =
            """{"status":"ok","model":"gemma","wired":{"memorystatus_level":60,"release_lvl":25,"reapply_lvl":40}}"""

        /** Brain-`/health`-Body mit gesetztem `wired.memorystatus_level`. */
        private fun health(level: Int): String =
            """{"status":"ok","model":"gemma","wired":{"memorystatus_level":$level,"release_lvl":25,"reapply_lvl":40}}"""
    }
}
