package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.ha.HaServiceCallClient
import de.hoshi.adapters.ha.HaServiceCaller
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * **VacuumActionConfig** — das MINIMALE Wiring der Sauger-Knöpfe: EINE Bean,
 * der [HaServiceCaller], den der [VacuumActionController] braucht. Den
 * READ-Adapter injiziert der Controller aus [HomeRegistryConfig] (dieselbe
 * Singleton-Bean, damit `invalidate()` nach einer Tat DENSELBEN Cache trifft,
 * den die Kachel liest — read-first).
 *
 * Bewusst eine EIGENE `@Configuration` (Doktrin wie [HomeRegistryConfig]/
 * [HomeEditConfig]): **PipelineConfig bleibt UNANGETASTET.**
 *
 * Der Caller wird UNBEDINGT gebaut (Muster [HomeEditConfig.registryWriter]):
 * die Deploy-Decke (`HOSHI_HA_ENABLED`) entscheidet ausschließlich der
 * Controller; fehlt der Token, liefert der Client never-throw `Failed` ⇒
 * ehrliche 502 statt eines stillen Fake-Erfolgs.
 *
 * `resolveHaToken()` ist BEWUSST dupliziert (s. [HomeEditConfig]-KDoc) — exakt
 * dieselbe Auflösung (Env `HOSHI_HA_TOKEN` ▷ `~/.hoshi/secrets.json["ha"]`,
 * Wert NIE geloggt), aber unabhängig.
 */
@Configuration
class VacuumActionConfig {

    @Bean
    fun haServiceCaller(
        // OSS-Default wie [HomeRegistryConfig]/[HomeEditConfig] (mDNS statt LAN-IP).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") baseUrl: String,
    ): HaServiceCaller = HaServiceCallClient(baseUrl = baseUrl, token = resolveHaToken())

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
