package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **BrainRuntimeConfig** — das MINIMALE Wiring des Brain-Modell-Settings
 * (Andi-Auftrag „Brain (LLM)"-Sektion, Muster [TtsRuntimeConfig]). Anders als
 * TTS-Engine/Lookup-Modell braucht die LIVE `aktiv`-Anzeige KEINEN eigenen
 * JSON-Store: der Brain-Sidecar selbst ist die Live-Wahrheit (`GET /health`
 * trägt das gerade geladene Modell) — ein Neustart des Sidecars fällt ohnehin
 * auf sein Boot-Modell (`HOSHI_BRAIN_MODEL`) zurück, unabhängig von unserem
 * Backend.
 *
 * [brainModelStore] ist die EINE Ausnahme davon: er trägt NICHT die Live-Anzeige,
 * sondern NUR das GEWÄHLTE Soll für die Ops-Drift-Prüfung
 * ([SidecarHealthService]) — s. [JsonFileBrainModelStore]-KDoc (Andi-Befund
 * 2026-07-20: ein Runtime-Switch zog das Drift-Soll bisher nicht mit, die
 * Übersicht meldete „Drift" gegen ein per-Deploy fixiertes Literal).
 *
 * `hoshi.brain.base-url` ist DIESELBE Property wie
 * [PipelineConfig.brainPort]/[SidecarHealthService] (eine Wahrheit, mehrere Leser).
 */
@Configuration
class BrainRuntimeConfig {

    @Bean
    fun brainHealthProbe(
        @Value("\${hoshi.brain.base-url:http://localhost:8041}") baseUrl: String,
    ): BrainHealthProbe = HttpBrainHealthProbe(baseUrl = baseUrl)

    @Bean
    fun brainSwitchModelPort(
        @Value("\${hoshi.brain.base-url:http://localhost:8041}") baseUrl: String,
    ): BrainSwitchModelPort = HttpBrainSwitchModelPort(baseUrl = baseUrl)

    @Bean
    fun brainModelStore(
        @Value("\${hoshi.brain-model.path:\${HOSHI_BRAIN_MODEL_PATH:}}") settingsPath: String,
    ): JsonFileBrainModelStore = JsonFileBrainModelStore(resolvePath(settingsPath))

    /**
     * **Auto-Switch-Settings-Store** (Andi-Auftrag „12B für Chat, e4b für Voice",
     * 2026-07-26) — Muster [brainModelStore]/[JsonFileEscalationModeStore], eigene
     * Datei (`~/.hoshi/brain-auto-switch.json`), Default AUS.
     */
    @Bean
    fun brainAutoSwitchStore(
        @Value("\${hoshi.brain-auto-switch.path:\${HOSHI_BRAIN_AUTO_SWITCH_PATH:}}") settingsPath: String,
    ): JsonFileBrainAutoSwitchStore = JsonFileBrainAutoSwitchStore(resolveAutoSwitchPath(settingsPath))

    /**
     * **Der echte [BrainAutoSwitchPort]** — verdrahtet AUSSCHLIESSLICH bestehende Beans
     * ([brainSwitchModelPort]/[brainHealthProbe], oben) + den neuen Settings-Store; KEIN
     * zweiter HTTP-Client (Andi-Vorgabe: den bestehenden `switchPort` wiederverwenden).
     */
    @Bean
    fun brainAutoSwitchPort(
        brainAutoSwitchStore: JsonFileBrainAutoSwitchStore,
        brainSwitchModelPort: BrainSwitchModelPort,
        brainHealthProbe: BrainHealthProbe,
    ): BrainAutoSwitchPort = BrainAutoSwitchService(
        store = brainAutoSwitchStore,
        switchPort = brainSwitchModelPort,
        healthProbe = brainHealthProbe,
    )

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "brain-model.json")

    private fun resolveAutoSwitchPath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "brain-auto-switch.json")
}
