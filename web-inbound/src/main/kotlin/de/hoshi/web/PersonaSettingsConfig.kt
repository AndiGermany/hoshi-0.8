package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **PersonaSettingsConfig** — das MINIMALE Wiring des Server-seitigen Persona-Settings
 * (Muster [ExtendedThinkConfig]/[WeatherLocationConfig]): genau EINE Bean, der
 * [JsonFilePersonaStore], den der [PersonaSettingsController] (GET/PUT) und die
 * ws-Resolver-Naht ([WebSocketConfig] → `personaResolver.resolve(frameFeld, store.persona())`)
 * TEILEN — eine Store-Instanz, eine Wahrheit, ein PUT greift ab dem nächsten Satelliten-Turn.
 *
 * Bewusst eine EIGENE `@Configuration` statt PipelineConfig-Anbau (dieselbe Doktrin wie die
 * anderen Settings-Nähte): PipelineConfig bleibt unangetastet. Diese Bean ist auch ohne
 * scharfe ws-Naht settings-seitig voll funktional (GET/PUT persistiert).
 *
 * Pfad-Auflösung exakt das `extended-think.json`-/`weather-location.json`-Muster: explizit
 * (`hoshi.persona.path` / `HOSHI_PERSONA_PATH`) ▷ `~/.hoshi/persona.json`.
 */
@Configuration
class PersonaSettingsConfig {

    @Bean
    fun personaStore(
        @Value("\${hoshi.persona.path:\${HOSHI_PERSONA_PATH:}}") settingsPath: String,
    ): JsonFilePersonaStore = JsonFilePersonaStore(resolvePath(settingsPath))

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "persona.json")
}
