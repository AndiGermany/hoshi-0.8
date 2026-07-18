package de.hoshi.web

import de.hoshi.adapters.supervision.HttpSidecarProbe
import de.hoshi.core.supervision.SidecarPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * **OpsWatchdogConfig** — die selbst-enthaltene Verdrahtung des Ops-Status-Watchdogs.
 * Bewusst eine eigene `@Configuration` (analog [WarmProbeScheduling]) statt eines
 * Eingriffs in [PipelineConfig] — so bleibt das Feature komplett selbst-enthalten.
 *
 * Stellt die zwei Live-Nähte als Beans bereit, die der [SidecarHealthService] konsumiert:
 *  - [SidecarPort] = der ehrliche [HttpSidecarProbe] (eine geteilte, zustandslose Instanz).
 *  - [BrainHealthSource] = der synchrone `:8041/health`-Leser ([HttpBrainHealthSource]).
 *
 * Tests übersteuern beide per `@Primary`-Fake. `@EnableScheduling` liefert bereits
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
}
