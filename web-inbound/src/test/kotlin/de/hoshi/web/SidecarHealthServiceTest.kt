package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import de.hoshi.core.supervision.SidecarSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * **SidecarHealthServiceTest** — beweist die Status-Klassifikation (OK/DEGRADED/DOWN),
 * die Consecutive-Failure-Glättung und die Mac-RAM-Druck-Ableitung aus der Brain-Health
 * OHNE Live-Infra: eine scriptbare Fake-[SidecarPort] + eine Fake-[BrainHealthSource].
 *
 * Since S4 ("HA wird sichtbar") the same holds for the Home Assistant row: a fake
 * [HaHealthSource] for the service contract, plus a loopback HTTP server standing in
 * for HA to prove [HttpHaHealthSource] itself stays a READ-ONLY `GET /api/`.
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
            // Spiegelt die ECHTE Probe seit dem Andi-Entscheid 2026-07-26: KEIN
            // Drift-Urteil mehr — ein abweichendes Modell bei status=ok ist OK,
            // die Anzeige nennt nur, was läuft (Auto-Modellwahl wechselt gewollt).
            return SidecarHealth.ok("status=ok model='$measured'", measured)
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
        // Default seam = "HA not configured" ⇒ no HA row, so every pre-S4 assertion
        // (sidecar count, overall) keeps its meaning unchanged.
        haHealth: HaHealthSource = HaHealthSource { null },
    ) = SidecarHealthService(
        enabled = enabled,
        brainUrl = "http://localhost:8041",
        sttUrl = "http://localhost:9001",
        ttsUrl = "http://localhost:8042",
        sayUrl = "http://localhost:8044",
        piperUrl = "http://localhost:8045",
        bridgeUrl = "http://localhost:8035",
        speakerUrl = "http://localhost:9002",
        failureThreshold = threshold,
        brainExpectedModel = brainExpectedModel,
        ttsImpl = ttsImpl,
        probe = probe,
        brainHealth = { brainBody },
        brainModelStore = brainModelStore,
        ttsEngineStore = ttsEngineStore,
        haHealth = haHealth,
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
        assertEquals(5, status.sidecars.size, "vier Basis-Sidecars plus gewählte say-TTS")
        assertEquals("OK", status.sidecar("say-tts").status)
    }

    @Test
    fun `optionaler Speaker-ID-Sidecar DOWN treibt overall NICHT (cry-wolf-Schutz)`() {
        val probe = ScriptedProbe().apply {
            responses["speaker-id"] = SidecarHealth.down("connection refused (bewusst aus)")
        }
        val svc = service(probe)
        svc.refresh() // 1. DOWN → geglättet
        svc.refresh() // 2. DOWN → roh
        val status = svc.current() as OpsStatus
        assertEquals("OK", status.overall, "off-by-design-Sidecars dürfen die RAM-Pille nicht rot färben")
        assertEquals("DOWN", status.sidecar("speaker-id").status)
        assertFalse(status.sidecars.any { it.name == "voxtral-tts" }, "nicht gewähltes Voxtral wird nicht als aktiver Pfad vorgetäuscht")
    }

    @Test
    fun `gewaehlte lokale TTS DOWN treibt overall DOWN und allLocal bleibt false`() {
        val probe = ScriptedProbe().apply {
            responses["say-tts"] = SidecarHealth.down("connection refused")
        }
        val svc = service(probe, threshold = 1)
        svc.refresh()

        val status = svc.current() as OpsStatus
        assertEquals("DOWN", status.overall, "die aktive Ausgabe ist Teil des kritischen Sprechpfads")
        assertEquals("DOWN", status.sidecar("say-tts").status)
        assertFalse(status.allLocal, "lokale Engine allein beweist keine erreichbare lokale Ausgabe")
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

    // ── Direktes memory-Feld (Andi-Auftrag 2026-07-25/26): IMMER aktive Primärquelle ─
    //
    // sidecars/brain/server.py misst RAM-Druck jetzt selbst (vm_stat/sysctl) und
    // liefert ein eigenes `memory`-Feld — anders als `wired.memorystatus_level` ist
    // das NICHT vom Residency-Monitor abhängig (der im Normalbetrieb aus ist, s.
    // BrainMemoryHeuristic-KDoc). Diese Quelle hat Vorrang; die wired-Ableitung
    // bleibt NUR der Fallback für ältere Sidecar-Stände ohne das neue Feld.

    @Test
    fun `memory-Feld direkt vom Sidecar hat Vorrang vor wired (OK)`() {
        val m = BrainMemoryHeuristic.classify(
            directHealth(level = "ok", detail = "RAM entspannt: 4000 MB frei+inaktiv."),
        )
        assertEquals("OK", m.level)
        assertEquals("brain-memory", m.source)
        assertEquals("RAM entspannt: 4000 MB frei+inaktiv.", m.detail)
    }

    @Test
    fun `memory-Feld direkt WARN`() {
        val m = BrainMemoryHeuristic.classify(
            directHealth(level = "warn", detail = "RAM wird knapp: 900 MB frei+inaktiv."),
        )
        assertEquals("WARN", m.level)
        assertEquals("brain-memory", m.source)
    }

    @Test
    fun `memory-Feld direkt CRITICAL`() {
        val m = BrainMemoryHeuristic.classify(
            directHealth(level = "critical", detail = "Kritischer RAM-Druck: nur 477 MB frei, Kompressor wächst weiter."),
        )
        assertEquals("CRITICAL", m.level)
        assertEquals("brain-memory", m.source)
    }

    // ── Andi-Rekalibrierung 2026-08-19: Swap allein ist kein Warngrund mehr ─────
    //
    // Andis Befund: 16 GB Mac, 5874 MB frei+inaktiv, Swap 60 % belegt löste eine
    // Warnung aus. Die Schwelle wohnte in `sidecars/brain/server.py#_classify_memory`
    // (macOS lagert Idle-Pages routinemäßig aus; Swap allein > 50 % war der
    // Fehlalarm) — dort jetzt kalibriert: warn erst bei frei+inaktiv < 1,5 GB ODER
    // Swap > 85 % UND frei+inaktiv gleichzeitig < 3 GB. Diese Tests beweisen, dass
    // der Kotlin-Service das rekalibrierte `level` unverändert durchreicht.

    @Test
    fun `Andis Fall (16GB, 5874 MB frei+inaktiv, Swap 60 Prozent) ist KEINE Warnung`() {
        val m = BrainMemoryHeuristic.classify(
            directHealth(level = "ok", detail = "RAM entspannt: 5874 MB frei+inaktiv."),
        )
        assertEquals("OK", m.level, "Swap allein bei reichlich frei+inaktiv ist kein Druck")
        assertEquals("brain-memory", m.source)
    }

    @Test
    fun `echter Druck (Swap ueber 85 Prozent UND frei+inaktiv unter 3 GB) ist eine Warnung`() {
        val m = BrainMemoryHeuristic.classify(
            directHealth(level = "warn", detail = "RAM wird knapp: 2400 MB frei+inaktiv, Swap 88% belegt."),
        )
        assertEquals("WARN", m.level, "Swap hoch UND frei knapp zusammen bleibt ein echter Alarm")
        assertEquals("brain-memory", m.source)
    }

    @Test
    fun `memory-Feld ohne detail bekommt einen ehrlichen Fallback-Text statt leerem String`() {
        val body = """{"status":"ok","model":"gemma","memory":{"level":"warn"}}"""
        val m = BrainMemoryHeuristic.classify(body)
        assertEquals("WARN", m.level)
        assertTrue(m.detail.isNotBlank(), "kein leerer Detail-Text, auch ohne geliefertes detail-Feld")
    }

    @Test
    fun `unbekannter level-Wert im memory-Feld faellt auf die wired-Ableitung zurueck`() {
        val body = """{"status":"ok","model":"gemma","memory":{"level":"banana"},"wired":{"memorystatus_level":60,"release_lvl":25,"reapply_lvl":40}}"""
        val m = BrainMemoryHeuristic.classify(body)
        assertEquals("OK", m.level)
        assertEquals("brain-health", m.source, "unbekannter Direkt-Level -> Fallback auf die alte wired-Quelle")
    }

    @Test
    fun `memory-Feld fehlt komplett (aelterer Sidecar-Stand) - Abwaertskompatibilitaet ueber wired`() {
        // OK_HEALTH/health() (companion) tragen bewusst KEIN memory-Feld — genau der
        // Alt-Sidecar-Fall, den classify() ohne Funktionsverlust auf die wired-
        // Ableitung zurückfallen lassen muss (kein Sidecar-Redeploy erzwungen).
        val m = BrainMemoryHeuristic.classify(OK_HEALTH)
        assertEquals("OK", m.level)
        assertEquals("brain-health", m.source, "alter Sidecar ohne memory-Feld -> weiterhin die wired-Quelle")
    }

    @Test
    fun `memory CRITICAL direkt vom Sidecar hebt overall auf DEGRADED (wie beim alten wired-Pfad)`() {
        val svc = service(ScriptedProbe(), brainBody = directHealth(level = "critical", detail = "Kritischer RAM-Druck."))
        svc.refresh()
        val status = svc.current() as OpsStatus
        assertEquals("CRITICAL", status.memory.level)
        assertEquals("brain-memory", status.memory.source)
        assertEquals("DEGRADED", status.overall, "RAM-Druck CRITICAL hebt das Gesamt-Signal an, unabhängig von der Quelle")
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
    fun `Boot-Default-Fall (nie umgeschaltet) - Soll wird durchgereicht, aber NICHT mehr be-urteilt`(
        @TempDir dir: Path,
    ) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")) // nie gesetzt
        val probe = ScriptedProbe().apply {
            measuredModels["brain"] = "mlx-community/gemma-4-e4b-it-4bit" // läuft e4b …
        }
        // … das Deploy-Literal nennt e2b — seit dem Andi-Entscheid 2026-07-26 ist das
        // nur noch Info (kein model-Feld-Fallback-Text), KEIN Drift-Urteil mehr:
        // die Auto-Modellwahl wechselt Modelle gewollt, ein Soll-Vergleich in der
        // Anzeige erzeugte Dauer-Warnungen für gewünschte Zustände.
        val svc = service(probe, brainExpectedModel = "gemma-4-e2b-it-4bit", brainModelStore = store, threshold = 1)
        svc.refresh()

        assertEquals("gemma-4-e2b-it-4bit", probe.specsSeen["brain"]?.expectedModel, "ohne Runtime-Switch wird weiterhin der Boot-Default durchgereicht")
        val brain = (svc.current() as OpsStatus).sidecar("brain")
        assertEquals("OK", brain.status, "abweichendes Modell bei status=ok ist OK — kein Drift-Urteil")
        assertTrue(brain.detail.contains("gemma-4-e4b"), "die Anzeige nennt das ECHTE Modell")
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
    fun `gewaehlt != laufend - KEIN Urteil mehr, die Anzeige nennt das echte Modell`(
        @TempDir dir: Path,
    ) {
        // Andi-Entscheid 2026-07-26: seit der Auto-Modellwahl wechselt das Modell
        // GEWOLLT — gewählt != laufend ist ein Normalzustand, kein Befund. Ob ein
        // WECHSEL hängt, meldet der Sidecar selbst (switch_stuck_seconds).
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")).apply {
            setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit") // gewählt: e4b …
        }
        val probe = ScriptedProbe().apply {
            measuredModels["brain"] = "mlx-community/gemma-4-e2b-it-4bit" // … es läuft e2b
        }
        val svc = service(probe, brainExpectedModel = "", brainModelStore = store, threshold = 1)
        svc.refresh()

        val status = svc.current() as OpsStatus
        assertEquals("OK", status.sidecar("brain").status, "laufendes lokales Modell bei status=ok ⇒ OK")
        assertTrue(status.sidecar("brain").detail.contains("gemma-4-e2b"), "die Anzeige nennt, was WIRKLICH läuft")
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
        assertTrue((svc.current() as OpsStatus).sidecars.any { it.name == "piper-tts" })
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
        assertTrue((svc.current() as OpsStatus).allLocal, "STT+Brain+gewählte say-TTS sind OK ⇒ Schloss")
    }

    @Test
    fun `allLocal ist false, wenn die TTS-Engine openai (Cloud) ist`() {
        val svc = service(ScriptedProbe(), ttsImpl = "openai", threshold = 1)
        svc.refresh()
        assertFalse((svc.current() as OpsStatus).allLocal, "Cloud-Engine ⇒ kein Schloss, auch wenn STT/Brain OK sind")
    }

    @Test
    fun `allLocal bleibt true bei abweichendem LOKALEM Modell - das Schloss misst Privatsphaere, nicht Modell-Identitaet`(@TempDir dir: Path) {
        // Vorher zog die Drift-Meldung das Schloss auf false — aber e2b statt e4b ist
        // BEIDES lokal. Seit dem Andi-Entscheid 2026-07-26 (kein Drift-Urteil) sagt
        // das Schloss wieder nur, was es sagen soll: läuft alles im Haus?
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json")).apply {
            setSelectedRepo("mlx-community/gemma-4-e4b-it-4bit")
        }
        val probe = ScriptedProbe().apply { measuredModels["brain"] = "mlx-community/gemma-4-e2b-it-4bit" }
        val svc = service(probe, brainModelStore = store, threshold = 1) // ttsImpl leer ⇒ say (lokal)
        svc.refresh()
        assertTrue((svc.current() as OpsStatus).allLocal, "lokales Modell + lokale Engine ⇒ Schloss grün, egal welches lokale Modell")
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

    // ── Home Assistant row (S4 "HA wird sichtbar") ──────────────────────────────
    //
    // HA has no `/health` sidecar contract, so it comes through its own seam
    // (HaHealthSource, READ-ONLY `GET /api/`). The row exists ONLY when HA is
    // configured for this install; a configured-but-unreachable HA is critical
    // (no light obeys) and must reach `overall`. Visibility only — nothing is healed.

    @Test
    fun `HA nicht konfiguriert (Flag aus oder kein Token) - KEINE HA-Zeile, overall unberuehrt`() {
        val svc = service(ScriptedProbe(), haHealth = { null })
        svc.refresh()

        val status = svc.current() as OpsStatus
        assertFalse(status.sidecars.any { it.name == HA_NAME }, "HA-lose Box zeigt keine erfundene HA-Zeile")
        assertEquals("OK", status.overall)
        assertEquals(null, svc.statusOf(HA_NAME), "kein Snapshot-Eintrag ⇒ ehrliches UNKNOWN")
    }

    @Test
    fun `HA erreichbar - eigene Zeile mit OK, overall bleibt OK`() {
        val svc = service(ScriptedProbe(), haHealth = { SidecarHealth.ok("status=ok (API running)") })
        svc.refresh()

        val status = svc.current() as OpsStatus
        assertEquals("OK", status.sidecar(HA_NAME).status)
        assertEquals("OK", status.overall)
        assertEquals(6, status.sidecars.size, "vier Basis-Sidecars, gewählte say-TTS und HA")
    }

    @Test
    fun `HA down - erst geglaettet, ab Schwelle DOWN und overall DOWN`() {
        val svc = service(ScriptedProbe(), threshold = 2, haHealth = { SidecarHealth.down("keine Antwort") })

        svc.refresh() // 1st failure → smoothed like every other seam
        assertEquals("OK", (svc.current() as OpsStatus).sidecar(HA_NAME).status, "ein Blip alarmiert nicht")

        svc.refresh() // 2nd failure → honest DOWN
        val status = svc.current() as OpsStatus
        assertEquals("DOWN", status.sidecar(HA_NAME).status)
        assertEquals("DOWN", status.overall, "ein konfiguriertes, totes HA darf nicht hinter dem stillen OK-Punkt verschwinden")
    }

    @Test
    fun `HA down laesst das lokale Schloss unberuehrt - HA ist kein Sprech-Pfad`() {
        val svc = service(ScriptedProbe(), threshold = 1, haHealth = { SidecarHealth.down("keine Antwort") })
        svc.refresh()
        assertTrue((svc.current() as OpsStatus).allLocal, "allLocal misst STT+Brain+TTS, nicht das Haus")
    }

    @Test
    fun `HA-Probe wirft - never-throw, wird zu DOWN`() {
        val svc = service(ScriptedProbe(), threshold = 1, haHealth = { error("boom") })
        svc.refresh() // must not escape the scheduler tick

        val ha = (svc.current() as OpsStatus).sidecar(HA_NAME)
        assertEquals("DOWN", ha.status)
        assertTrue(ha.detail.contains("boom"), "der ehrliche Grund steht im Detail")
    }

    // ── HttpHaHealthSource — die echte READ-ONLY-Probe gegen ein Fake-HA ─────────

    @Test
    fun `HttpHaHealthSource - GET auf api mit Bearer-Token, API running ist OK`() = withFakeHa { url, seen ->
        val health = HttpHaHealthSource(baseUrl = url, token = "secret-token", enabled = true).probe()

        assertEquals("OK", health?.state?.name)
        assertEquals("/api/", seen.path, "READ-ONLY Liveness-Pfad von HA, kein States-/Service-Call")
        assertEquals("GET", seen.method, "nie schreiben")
        assertEquals("Bearer secret-token", seen.auth)
    }

    @Test
    fun `HttpHaHealthSource - abgelehnter Token (401) ist DOWN`() = withFakeHa(status = 401, body = "nope") { url, _ ->
        val health = HttpHaHealthSource(baseUrl = url, token = "wrong", enabled = true).probe()

        assertEquals("DOWN", health?.state?.name)
        assertTrue(health!!.detail.contains("401"), "der Statuscode steht ehrlich im Detail")
        assertFalse(health.detail.contains("wrong"), "das Token darf NIE in Detail/Log landen")
    }

    @Test
    fun `HttpHaHealthSource - 2xx ohne API-running-Marker ist DEGRADED, nicht Fake-OK`() =
        withFakeHa(body = """{"message":"something else"}""") { url, _ ->
            assertEquals("DEGRADED", HttpHaHealthSource(baseUrl = url, token = "t", enabled = true).probe()?.state?.name)
        }

    @Test
    fun `HttpHaHealthSource - Flag aus oder kein Token ist null (keine Zeile, KEIN Call)`() = withFakeHa { url, seen ->
        assertEquals(null, HttpHaHealthSource(baseUrl = url, token = "t", enabled = false).probe())
        assertEquals(null, HttpHaHealthSource(baseUrl = url, token = null, enabled = true).probe())
        assertEquals(null, HttpHaHealthSource(baseUrl = url, token = "  ", enabled = true).probe())
        assertEquals(null, seen.path, "unkonfiguriertes HA wird nicht einmal angeklopft")
    }

    @Test
    fun `HttpHaHealthSource - HA unerreichbar ist DOWN (never-throw)`() {
        // Port 1 is reliably closed — the honest "connection refused" path.
        val health = HttpHaHealthSource(baseUrl = "http://127.0.0.1:1", token = "t", enabled = true).probe()
        assertEquals("DOWN", health?.state?.name)
    }

    /** What the fake HA saw — proves the probe stays READ-ONLY and sends the Bearer token. */
    private class SeenRequest {
        var path: String? = null
        var method: String? = null
        var auth: String? = null
    }

    /** Fake HA on a loopback port (JDK HttpServer, Muster [HomeRegistryControllerTest]). */
    private fun withFakeHa(
        status: Int = 200,
        body: String = """{"message":"API running."}""",
        block: (String, SeenRequest) -> Unit,
    ) {
        val seen = SeenRequest()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen.path = ex.requestURI.path
            seen.method = ex.requestMethod
            seen.auth = ex.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", seen)
        } finally {
            server.stop(0)
        }
    }

    companion object {
        /** Wire name of the HA row — same contract string the FE renders verbatim. */
        private const val HA_NAME = SidecarHealthService.HA_SIDECAR

        private const val OK_HEALTH =
            """{"status":"ok","model":"gemma","wired":{"memorystatus_level":60,"release_lvl":25,"reapply_lvl":40}}"""

        /** Brain-`/health`-Body mit gesetztem `wired.memorystatus_level`. */
        private fun health(level: Int): String =
            """{"status":"ok","model":"gemma","wired":{"memorystatus_level":$level,"release_lvl":25,"reapply_lvl":40}}"""

        /** Brain-`/health`-Body mit dem NEUEN, direkten `memory`-Feld (Primärquelle). */
        private fun directHealth(level: String, detail: String? = null): String {
            val detailPart = if (detail != null) ",\"detail\":\"$detail\"" else ""
            return """{"status":"ok","model":"gemma","memory":{"level":"$level"$detailPart}}"""
        }
    }
}
