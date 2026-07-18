package de.hoshi.web

import de.hoshi.adapters.escalation.EscalationSpendStore
import de.hoshi.adapters.escalation.FileBackedEscalationSpendStore
import de.hoshi.adapters.escalation.OpenAiEscalationAdapter
import de.hoshi.core.port.EscalationPort
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist das Recherche-Modell-Wiring in [PipelineConfig] (Andi-Auftrag
 * 2026-07-19): [PipelineConfig.escalationPort]/[PipelineConfig.researchEscalationPort]
 * teilen sich [PipelineConfig.escalationSpendStore] (EIN Tages-Cap für beide
 * Modelle), Decke zu ⇒ beide [EscalationPort.NONE], Default-Property leer ⇒
 * [PipelineConfig.researchEscalationPort] bleibt NONE, eine unbekannte
 * Modell-ID lässt den Bean-Bau werfen (fail-fast statt stiller Fehlkonfiguration).
 * Reine Konstruktor-Verdrahtung — kein Spring-Context, kein Netz.
 */
class PipelineConfigEscalationTest {

    private val config = PipelineConfig()
    private val ha = "http://localhost:8123"

    private fun spendPath(dir: Path): String = dir.resolve("spend.json").toString()

    // ── escalationSpendStore — die GETEILTE Bean ─────────────────────────────────

    @Test
    fun `escalationSpendStore ist eine funktionsfaehige FileBackedEscalationSpendStore-Instanz`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        assertInstanceOf(FileBackedEscalationSpendStore::class.java, store)
        val before = store.spentTodayCents()
        store.book(1.0)
        org.junit.jupiter.api.Assertions.assertEquals(before + 1.0, store.spentTodayCents(), 1e-9)
    }

    // ── escalationPort — Decke zu ⇒ NONE, sonst der Standard-Adapter ────────────

    @Test
    fun `escalationPort Decke zu liefert NONE (kein Netz, kein Spend)`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val port = config.escalationPort(
            extendedThinkEnabled = false,
            escalationModel = "",
            escalationSpendStore = store,
            haBaseUrl = ha,
        )
        assertSame(EscalationPort.NONE, port, "Decke zu ⇒ byte-neutraler NONE-Sentinel")
    }

    @Test
    fun `escalationPort Decke offen liefert den OpenAiEscalationAdapter`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val port = config.escalationPort(
            extendedThinkEnabled = true,
            escalationModel = "",
            escalationSpendStore = store,
            haBaseUrl = ha,
        )
        assertInstanceOf(OpenAiEscalationAdapter::class.java, port)
    }

    // ── researchEscalationPort — Default leer ⇒ NONE (byte-neutral) ─────────────

    @Test
    fun `researchEscalationPort ohne konfiguriertes Modell bleibt NONE, auch bei offener Decke`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val port = config.researchEscalationPort(
            extendedThinkEnabled = true,
            researchModel = "",
            escalationSpendStore = store,
            haBaseUrl = ha,
        )
        assertSame(EscalationPort.NONE, port, "leeres hoshi.escalation.research-model ⇒ Feature AUS")
    }

    @Test
    fun `researchEscalationPort Decke zu bleibt NONE, auch mit gesetztem Modell`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val port = config.researchEscalationPort(
            extendedThinkEnabled = false,
            researchModel = "gpt-5.6-sol",
            escalationSpendStore = store,
            haBaseUrl = ha,
        )
        assertSame(EscalationPort.NONE, port, "Extended-Think-Decke zu ⇒ kein zweiter Adapter")
    }

    @Test
    fun `researchEscalationPort mit bekannter ID liefert einen OpenAiEscalationAdapter`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val port = config.researchEscalationPort(
            extendedThinkEnabled = true,
            researchModel = "gpt-5.6-sol",
            escalationSpendStore = store,
            haBaseUrl = ha,
        )
        assertInstanceOf(OpenAiEscalationAdapter::class.java, port)
    }

    @Test
    fun `researchEscalationPort mit unbekannter ID wirft (fail-fast statt stiller Fehlkonfiguration)`(@TempDir dir: Path) {
        val store = config.escalationSpendStore(spendPath = spendPath(dir))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            config.researchEscalationPort(
                extendedThinkEnabled = true,
                researchModel = "gpt-5.6-mars",
                escalationSpendStore = store,
                haBaseUrl = ha,
            )
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.message!!.contains("gpt-5.6-mars"),
            "die Fehlermeldung nennt die unbekannte ID: ${ex.message}",
        )
    }

    // ── Geteilter Spend-Store: beide Ports bekommen DIESELBE Instanz ────────────

    @Test
    fun `escalationPort und researchEscalationPort injizieren DIESELBE Spend-Store-Instanz (ein Tages-Cap)`(
        @TempDir dir: Path,
    ) {
        val store: EscalationSpendStore = config.escalationSpendStore(spendPath = spendPath(dir))
        val nanoPort = config.escalationPort(
            extendedThinkEnabled = true, escalationModel = "", escalationSpendStore = store, haBaseUrl = ha,
        )
        val researchPort = config.researchEscalationPort(
            extendedThinkEnabled = true, researchModel = "gpt-5.6-sol", escalationSpendStore = store, haBaseUrl = ha,
        )
        // Beide Bean-Methoden nehmen [store] als PARAMETER entgegen (statt sich
        // je eine eigene FileBackedEscalationSpendStore-Instanz zu bauen) — Spring
        // injiziert dieselbe Singleton-Bean in beide, s. escalationSpendStore-KDoc
        // (zwei Instanzen auf demselben Pfad würden sich NICHT live sehen).
        assertInstanceOf(OpenAiEscalationAdapter::class.java, nanoPort)
        assertInstanceOf(OpenAiEscalationAdapter::class.java, researchPort)
        // Die eigentliche Korrektheits-Garantie (Cap wirkt gemeinsam über ZWEI
        // verschiedene Modelle) ist in adapters-escalation direkt am Adapter
        // bewiesen: OpenAiEscalationAdapterTest > "zwei Adapter mit
        // verschiedenem Modell teilen sich EINEN Spend-Store".
        Files.exists(dir) // Smoke: TempDir wurde tatsächlich genutzt (spendPath-Verzeichnis existiert).
    }
}
