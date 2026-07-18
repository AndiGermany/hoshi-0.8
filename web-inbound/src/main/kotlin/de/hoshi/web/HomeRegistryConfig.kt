package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * **HomeRegistryConfig** — das MINIMALE Wiring von Scheibe 1 (READ-ONLY) des
 * Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`): EINE Bean, der [HaHomeRegistryAdapter],
 * den der [HomeRegistryController] liest.
 *
 * Bewusst eine EIGENE `@Configuration` statt PipelineConfig-Anbau (dieselbe
 * Doktrin wie [WeatherLocationConfig]/[PersonaSettingsConfig]): PipelineConfig
 * bleibt UNANGETASTET (Andi-Auftrag Scheibe 1).
 *
 * Der Adapter wird UNBEDINGT gebaut (Muster [WeatherLocationConfig.weatherTodayReader]):
 * die Deploy-Decke (`HOSHI_HA_ENABLED`) entscheidet ausschließlich der
 * [HomeRegistryController] selbst — fehlt das Token oder ist HA aus, liefert
 * der Adapter never-throw `null`, der Controller macht daraus ehrlich 502.
 *
 * `resolveHaToken()` ist BEWUSST dupliziert (nicht aus `PipelineConfig`
 * importiert/geteilt) — exakt dieselbe Auflösung (Env `HOSHI_HA_TOKEN` ▷
 * `~/.hoshi/secrets.json["ha"]`, Wert NIE geloggt), aber unabhängig, damit
 * PipelineConfig unangetastet bleibt.
 */
@Configuration
class HomeRegistryConfig {

    @Bean
    fun haHomeRegistryAdapter(
        // OSS-Default s. PipelineConfig.toolPort-KDoc (mDNS statt hartkodierter LAN-IP).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") baseUrl: String,
    ): HaHomeRegistryAdapter = HaHomeRegistryAdapter(baseUrl = baseUrl, token = resolveHaToken())

    /** Dupliziert aus `PipelineConfig.resolveHaToken` (s. Klassen-KDoc). */
    private fun resolveHaToken(): String? {
        System.getenv("HOSHI_HA_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val path = Paths.get(System.getProperty("user.home"), ".hoshi", "secrets.json")
            if (!Files.exists(path)) return null
            ObjectMapper().readTree(path.toFile()).get("ha")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
