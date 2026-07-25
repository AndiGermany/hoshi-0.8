package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.Duration

/**
 * Direkter Konstruktor-Test von [BrainSettingsController] (kein Spring-Context,
 * kein echtes Sidecar-Netz — Muster [TtsSettingsControllerTest]): beweist die
 * GET-Form (gefaktes Health-JSON via [BrainHealthProbe]-Fake), PUT
 * valide/unbekannt (422) und den 404/Nichterreichbar-⇒-502-Pfad (Andi-Auftrag:
 * "Sidecar kann noch kein Umschalten / nicht erreichbar").
 */
class BrainSettingsControllerTest {

    private class FakeHealthProbe(private val snapshot: BrainHealthSnapshot) : BrainHealthProbe {
        override fun check(): Mono<BrainHealthSnapshot> = Mono.just(snapshot)
    }

    private class FakeSwitchPort(private val result: BrainSwitchResult) : BrainSwitchModelPort {
        var lastRepo: String? = null
        override fun switchModel(repo: String): Mono<BrainSwitchResult> {
            lastRepo = repo
            return Mono.just(result)
        }
    }

    @Test
    fun `GET - aktiv ist die kurze Id des gemessenen Modells, status roh durchgereicht`() {
        val probe = FakeHealthProbe(BrainHealthSnapshot(status = "ok", model = "mlx-community/gemma-4-e4b-it-4bit"))
        val controller = BrainSettingsController(probe, FakeSwitchPort(BrainSwitchResult.Accepted))

        val view = controller.brainSettings().block(Duration.ofSeconds(2))!!

        assertEquals("e4b", view.aktiv)
        assertEquals("ok", view.status)
        assertEquals(3, view.modelle.size, "die HARTE Whitelist — seit 25.07 drei Modelle (e2b, e4b, 12b)")
        assertTrue(view.modelle.any { it.id == "e2b" && it.repo == "mlx-community/gemma-4-e2b-it-4bit" })
    }

    @Test
    fun `GET - unreachable ⇒ aktiv leer, status unreachable (kein Fake-grün)`() {
        val probe = FakeHealthProbe(BrainHealthSnapshot(status = "unreachable", model = null))
        val controller = BrainSettingsController(probe, FakeSwitchPort(BrainSwitchResult.Accepted))

        val view = controller.brainSettings().block(Duration.ofSeconds(2))!!

        assertEquals("", view.aktiv)
        assertEquals("unreachable", view.status)
    }

    @Test
    fun `GET - gemessenes Modell ausserhalb der Whitelist ⇒ aktiv leer (ehrlich, kein Raten)`() {
        val probe = FakeHealthProbe(BrainHealthSnapshot(status = "ok", model = "mlx-community/irgendein-12b"))
        val controller = BrainSettingsController(probe, FakeSwitchPort(BrainSwitchResult.Accepted))

        val view = controller.brainSettings().block(Duration.ofSeconds(2))!!
        assertEquals("", view.aktiv)
    }

    @Test
    fun `PUT unbekannte Modell-Id - 422, kein Sidecar-Call`() {
        val switchPort = FakeSwitchPort(BrainSwitchResult.Accepted)
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("ok", "mlx-community/gemma-4-e2b-it-4bit")),
            switchPort,
        )

        val response = controller.setModel(BrainModelRequest(id = "e12b")).block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-model", (response.body as SettingsError).error)
        assertEquals(null, switchPort.lastRepo, "eine unbekannte Id darf NIE einen Sidecar-Call ausloesen")
    }

    @Test
    fun `PUT bekannte Id - der Sidecar bekommt die VOLLE Repo-Id, Accepted ⇒ 200`() {
        val switchPort = FakeSwitchPort(BrainSwitchResult.Accepted)
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("loading", "mlx-community/gemma-4-e2b-it-4bit")),
            switchPort,
        )

        val response = controller.setModel(BrainModelRequest(id = "e4b")).block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("mlx-community/gemma-4-e4b-it-4bit", switchPort.lastRepo)
        // Readback statt Behauptung: das Body ist der ECHTE (Fake-)Health-Zustand,
        // nicht das behauptete Ziel-Modell — hier noch "loading"/e2b, wie die Probe liefert.
        assertEquals("e2b", (response.body as BrainSettingsView).aktiv)
    }

    /**
     * Andi will das dichte 12B testen (Auftrag 2026-07-25). Zwei Dinge müssen stimmen,
     * sonst läuft der Versuch ins Leere: die Id ist bekannt (kein 422), und der Sidecar
     * bekommt die Repo-Id mit **großem B** — `gemma-4-12B-it-4bit` existiert auf HF nur
     * in dieser Schreibweise, ein kleines `b` lädt nichts.
     */
    @Test
    fun `PUT 12b - die Id ist bekannt und die Repo-Id traegt das grosse B`() {
        val switchPort = FakeSwitchPort(BrainSwitchResult.Accepted)
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("ok", "mlx-community/gemma-4-e4b-it-4bit")),
            switchPort,
        )

        val response = controller.setModel(BrainModelRequest(id = "12b")).block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("mlx-community/gemma-4-12B-it-4bit", switchPort.lastRepo)
    }

    /** Die Whitelist ist die Reihenfolge, die Andi im Auswahlfeld sieht: sparsam → gründlich. */
    @Test
    fun `Katalog - drei Modelle, aufsteigend nach Bedarf`() {
        assertEquals(listOf("e2b", "e4b", "12b"), BrainModelCatalog.MODELS.map { it.id })
        assertEquals("mlx-community/gemma-4-12B-it-4bit", BrainModelCatalog.byId("12b")?.repo)
        assertNull(BrainModelCatalog.byId("e12b"), "es gibt kein E12B — die E-Reihe endet bei E4B")
    }

    @Test
    fun `PUT - Sidecar 404 oder unerreichbar ⇒ 502 mit dem ehrlichen Hinweis`() {
        val switchPort = FakeSwitchPort(BrainSwitchResult.Unavailable("404 Not Found"))
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("ok", "mlx-community/gemma-4-e2b-it-4bit")),
            switchPort,
        )

        val response = controller.setModel(BrainModelRequest(id = "e4b")).block(Duration.ofSeconds(2))!!

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        val error = response.body as SettingsError
        assertEquals("switch-unavailable", error.error)
        assertEquals("Brain-Sidecar kann noch kein Umschalten / nicht erreichbar.", error.message)
    }

    // ── Andi-Befund 2026-07-20: PUT merkt das GEWÄHLTE Modell für die Ops-Drift-Prüfung ──

    @Test
    fun `PUT Accepted - das GEWAEHLTE Modell wird im modelStore gemerkt (Ops-Drift-Soll)`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json"))
        val switchPort = FakeSwitchPort(BrainSwitchResult.Accepted)
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("loading", "mlx-community/gemma-4-e2b-it-4bit")),
            switchPort,
            store,
        )

        controller.setModel(BrainModelRequest(id = "e4b")).block(Duration.ofSeconds(2))

        assertEquals(
            "mlx-community/gemma-4-e4b-it-4bit",
            store.selectedRepo(),
            "der Runtime-Switch muss das Drift-Soll SOFORT mitziehen, unabhängig vom Swap-Fortschritt",
        )
    }

    @Test
    fun `PUT unbekannte Modell-Id - der modelStore bleibt unangetastet`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json"))
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("ok", "mlx-community/gemma-4-e2b-it-4bit")),
            FakeSwitchPort(BrainSwitchResult.Accepted),
            store,
        )

        controller.setModel(BrainModelRequest(id = "e12b")).block(Duration.ofSeconds(2))

        assertNull(store.selectedRepo(), "eine unbekannte Id darf NIE das Drift-Soll verändern")
    }

    @Test
    fun `PUT Unavailable - der modelStore bleibt unangetastet (kein Switch, kein neues Soll)`(@TempDir dir: Path) {
        val store = JsonFileBrainModelStore(dir.resolve("brain-model.json"))
        val controller = BrainSettingsController(
            FakeHealthProbe(BrainHealthSnapshot("ok", "mlx-community/gemma-4-e2b-it-4bit")),
            FakeSwitchPort(BrainSwitchResult.Unavailable("404 Not Found")),
            store,
        )

        controller.setModel(BrainModelRequest(id = "e4b")).block(Duration.ofSeconds(2))

        assertNull(store.selectedRepo(), "ein abgelehnter/nicht angenommener Switch darf das Soll nicht verändern")
    }
}
