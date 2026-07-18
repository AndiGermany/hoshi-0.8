package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.ha.HaRegistryWriteClient
import de.hoshi.adapters.ha.RegistryWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock

/**
 * **HomeEditConfig** — das MINIMALE Wiring von Scheibe 2 (SCHREIBEN) des
 * Geräte-Zuordnungs-Konzepts: der [RegistryWriter] (HA-WS-Schreibnaht) + der
 * [HomeEditAuditLog], die der [HomeEditController] braucht.
 *
 * Bewusst eine EIGENE `@Configuration` (Doktrin wie [HomeRegistryConfig]/
 * [WeatherLocationConfig]): **PipelineConfig bleibt UNANGETASTET**. Der READ-
 * Adapter ([de.hoshi.adapters.ha.HaHomeRegistryAdapter]) wird NICHT hier gebaut —
 * der Controller injiziert die EINE Bean aus [HomeRegistryConfig] (Singleton),
 * damit `invalidate()` nach einem Write denselben Cache trifft, den der Lese-Rand
 * liest (read-first-Verfassung).
 *
 * Der [RegistryWriter] wird UNBEDINGT gebaut (Muster [HomeRegistryConfig]): die
 * Deploy-Flags entscheidet ausschließlich der Controller (`HOSHI_HOME_EDIT_ENABLED`);
 * fehlt der Token, liefert der Client never-throw `Failed` ⇒ ehrliche 502.
 *
 * `resolveHaToken()` ist BEWUSST dupliziert (nicht aus `PipelineConfig` geteilt) —
 * exakt dieselbe Auflösung (Env `HOSHI_HA_TOKEN` ▷ `~/.hoshi/secrets.json["ha"]`,
 * Wert NIE geloggt), aber unabhängig, damit PipelineConfig unangetastet bleibt.
 */
@Configuration
class HomeEditConfig {

    @Bean
    fun registryWriter(
        // OSS-Default wie [HomeRegistryConfig]/PipelineConfig (mDNS statt LAN-IP).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") baseUrl: String,
    ): RegistryWriter = HaRegistryWriteClient(baseUrl = baseUrl, token = resolveHaToken())

    @Bean
    fun homeEditAuditLog(
        // Default unter ~/.hoshi/audit/, überschreibbar für abweichende Deploys/Tests.
        @Value("\${HOSHI_HOME_EDIT_AUDIT_PATH:}") auditPath: String,
    ): HomeEditAuditLog = HomeEditAuditLog(path = resolveAuditPath(auditPath), clock = Clock.systemUTC())

    /** Konfigurierter Pfad, sonst `~/.hoshi/audit/home-edit.jsonl` (append-only). */
    private fun resolveAuditPath(configured: String): Path =
        if (configured.isNotBlank()) {
            Paths.get(configured)
        } else {
            Paths.get(System.getProperty("user.home"), ".hoshi", "audit", "home-edit.jsonl")
        }

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
