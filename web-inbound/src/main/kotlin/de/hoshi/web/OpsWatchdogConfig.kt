package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.supervision.HttpSidecarProbe
import de.hoshi.core.supervision.SidecarPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * **OpsWatchdogConfig** — die selbst-enthaltene Verdrahtung des Ops-Status-Watchdogs.
 * Bewusst eine eigene `@Configuration` (analog [WarmProbeScheduling]) statt eines
 * Eingriffs in [PipelineConfig] — so bleibt das Feature komplett selbst-enthalten.
 *
 * Stellt die Live-Nähte als Beans bereit, die der [SidecarHealthService] konsumiert:
 *  - [SidecarPort] = der ehrliche [HttpSidecarProbe] (eine geteilte, zustandslose Instanz).
 *  - [BrainHealthSource] = der synchrone `:8041/health`-Leser ([HttpBrainHealthSource]).
 *  - [HaHealthSource] = the READ-ONLY Home Assistant probe ([HttpHaHealthSource], S4).
 *
 * Tests übersteuern jede davon per `@Primary`-Fake. `@EnableScheduling` liefert bereits
 * [WarmProbeScheduling] (gleicher `de.hoshi.web`-Scan) — hier nicht nötig.
 *
 * **Byte-neutral bei Flag OFF:** die Beans existieren immer, aber der
 * [SidecarHealthService] ruft sie bei `HOSHI_SIDECAR_WATCH_ENABLED=false` nie auf.
 */
@Configuration
class OpsWatchdogConfig {

    @Bean
    fun sidecarProbe(): SidecarPort = HttpSidecarProbe()

    @Bean
    fun brainHealthSource(
        @Value("\${hoshi.brain.base-url:http://localhost:8041}") brainUrl: String,
    ): BrainHealthSource = HttpBrainHealthSource(brainUrl)

    /**
     * The Home Assistant seam (S4): address + deploy flag from the SAME properties every
     * other HA adapter uses (`HOSHI_HA_BASE_URL`, `HOSHI_HA_ENABLED`, OSS default is the
     * mDNS name, not a LAN IP — see `PipelineConfig.toolPort`). Flag off or no token ⇒
     * [HttpHaHealthSource] answers `null` ⇒ no HA row at all.
     *
     * Known limit (deliberate, own slice): both are read ONCE here at bean construction,
     * exactly like [HomeRegistryConfig]/`PipelineConfig` — an HA that moves to a new
     * address (DHCP) is only picked up on restart, so this row shows the drift as DOWN
     * rather than resolving it.
     */
    @Bean
    fun haHealthSource(
        @Value("\${HOSHI_HA_ENABLED:false}") haEnabled: Boolean,
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
    ): HaHealthSource = HttpHaHealthSource(baseUrl = haBaseUrl, token = resolveHaToken(), enabled = haEnabled)

    /**
     * Duplicated from `PipelineConfig.resolveHaToken` — same resolution (env
     * `HOSHI_HA_TOKEN` ▷ `~/.hoshi/secrets.json["ha"]`, value NEVER logged), duplicated
     * on purpose so PipelineConfig stays untouched (same doctrine as
     * [HomeRegistryConfig]/[HomeEditConfig]).
     */
    private fun resolveHaToken(): String? {
        System.getenv("HOSHI_HA_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val path = Paths.get(System.getProperty("user.home"), ".hoshi", "secrets.json")
            if (!Files.exists(path)) return null
            ObjectMapper().readTree(path.toFile()).get("ha")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
