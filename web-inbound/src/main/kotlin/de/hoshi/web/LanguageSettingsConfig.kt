package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **LanguageSettingsConfig** — das MINIMALE Wiring der Sprach-Laufzeit-Wahl
 * (Andi-Auftrag 2026-07-20, Muster [LookupModelConfig]/[WeatherLocationConfig]).
 * EINE Bean: der [JsonFileLanguageStore], den [LanguageSettingsController] (GET/PUT),
 * [WebSocketConfig] (ws-Session-Default) und [VoiceInboundController] (Query-Param-
 * Fallback) TEILEN — anders als TTS/Lookup-Modell gibt es hier KEINEN Live-Adapter
 * zum Umschalten: die Sprache reist schon heute PRO TURN mit ([de.hoshi.core.dto.ChatRequest.language]),
 * der Store liefert nur den STANDARD, wenn ein Rand KEINE explizite Wahl bekommt.
 */
@Configuration
class LanguageSettingsConfig {

    @Bean
    fun languageStore(
        @Value("\${hoshi.language.path:\${HOSHI_LANGUAGE_PATH:}}") settingsPath: String,
    ): JsonFileLanguageStore = JsonFileLanguageStore(resolvePath(settingsPath))

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "language.json")
}
