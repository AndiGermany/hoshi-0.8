package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **ExtendedThinkConfig** — das MINIMALE Wiring des Extended-Think-Settings (S2):
 * genau EINE Bean, der [JsonFileEscalationModeStore], den der
 * [ExtendedThinkController] (GET/PUT) und später der TurnOrchestrator-Mode-
 * Supplier (PipelineConfig) TEILEN — eine Store-Instanz, eine Wahrheit, ein PUT
 * greift ab dem nächsten Turn.
 *
 * Bewusst eine EIGENE `@Configuration` statt PipelineConfig-Anbau: die
 * Turn-Pipeline-Verdrahtung (TurnOrchestrator-Params, PendingLookupPort- und
 * EscalationPort-Beans) bleibt Sache der PipelineConfig — sie ist im
 * S2-Übergabe-Text dokumentiert und wird dort vom Integrator eingesetzt. Diese
 * Bean hier ist bis dahin settings-seitig voll funktional (GET/PUT persistiert),
 * pipeline-seitig aber inert: ohne den Mode-Supplier in PipelineConfig fährt der
 * Orchestrator seinen byte-neutralen AUS-Default.
 *
 * Pfad-Auflösung exakt das `skills.json`-Muster ([PipelineConfig.skillStateStore]):
 * explizit (`hoshi.extended-think.path` / `HOSHI_EXTENDED_THINK_PATH`) ▷
 * `~/.hoshi/extended-think.json`.
 */
@Configuration
class ExtendedThinkConfig {

    @Bean
    fun escalationModeStore(
        @Value("\${hoshi.extended-think.path:\${HOSHI_EXTENDED_THINK_PATH:}}") settingsPath: String,
    ): JsonFileEscalationModeStore = JsonFileEscalationModeStore(resolvePath(settingsPath))

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "extended-think.json")
}
