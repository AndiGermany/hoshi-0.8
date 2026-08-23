package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **NewsSourcesConfig** — das MINIMALE Wiring des Quellen-Settings (Muster
 * [WeatherLocationConfig]): der [JsonFileNewsSourcesStore], den der
 * [NewsSourcesSettingsController] (GET/PUT) und der Active-Sources-Supplier
 * des Adapters ([NewsAdapterConfig]) TEILEN — eine Store-Instanz, eine
 * Wahrheit, ein PUT greift ab dem nächsten Refresh.
 *
 * Bewusst eine EIGENE `@Configuration` OHNE [CurrentAffairsConfig.ADAPTER_PROPERTY]-
 * Gate: das Settings liest/schreibt unabhängig davon, ob der Adapter gerade
 * aktiv ist — genau wie der Wetter-Ort settings-seitig funktioniert, auch
 * bevor die Pipeline ihn nutzt.
 *
 * Pfad-Auflösung exakt das `weather-location.json`-Muster: explizit
 * (`hoshi.news-sources.path` / `HOSHI_NEWS_SOURCES_PATH`) ▷ `~/.hoshi/news-sources.json`.
 */
@Configuration
class NewsSourcesConfig {

    @Bean
    fun newsSourcesStore(
        @Value("\${hoshi.news-sources.path:\${HOSHI_NEWS_SOURCES_PATH:}}") settingsPath: String,
    ): JsonFileNewsSourcesStore = JsonFileNewsSourcesStore(resolvePath(settingsPath))

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "news-sources.json")
}
