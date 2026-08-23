package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.HaLastKnownStateStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

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
 *
 * **Räume-Nutzungs-Naht (additiv, kartiert in Commit f049965):** dieselbe
 * Dupliktions-Doktrin gilt für [areaUsageReader] — `resolveDiaryDirectory()`
 * spiegelt [DiaryController.resolveDirectory] (dieselbe Wahrheit, wo der
 * echte [de.hoshi.adapters.supervision.JsonlTurnTraceAdapter] schreibt),
 * unabhängig aufgelöst statt geteilt, damit PipelineConfig (wo die
 * `turnTracePort`-Bean lebt) unangetastet bleibt.
 *
 * **States-Frische + Last-known-good (additiv, Andi-Auftrag 2026-08-13,
 * „Sauger-Sichtbarkeits-Lücke"):** `HOSHI_HA_STATES_TTL_MS` (Default 60 000)
 * steuert die vom Template-TTL GETRENNTE States-Cache-Frische (s.
 * [HaHomeRegistryAdapter]-KDoc). Der Adapter bekommt außerdem einen ECHTEN
 * [HaLastKnownStateStore] (Prod-Datenverzeichnis-Muster wie
 * [de.hoshi.adapters.escalation.FileBackedEscalationSpendStore]) statt des
 * reinen RAM-Adapter-Defaults — NUR hier, in der Prod-Wiring-Schicht, damit
 * der Adapter selbst in Tests weiterhin ohne jede Datei konstruierbar bleibt.
 *
 * **Sauger-Cache-Carry (additiv, Andi 2026-08-21, „Energiesparmodus"):**
 * `HOSHI_VACUUM_CACHE_MAX_AGE_HOURS` (Default 24) ist die Obergrenze, bis zu
 * der die zuletzt live gesehenen Sauger-Werte MARKIERT weitergeliefert werden
 * (s. [de.hoshi.adapters.ha.VacuumFamily]-KDoc). `0` schaltet den Carry ab.
 * SCREAMING_SNAKE wie die übrigen Ops-Knöpfe: der Wert ist ohne Rebuild aus
 * der systemd-Unit drehbar, falls sich 24 h im Betrieb als falsch erweist.
 */
@Configuration
class HomeRegistryConfig {

    @Bean
    fun haHomeRegistryAdapter(
        // OSS-Default s. PipelineConfig.toolPort-KDoc (mDNS statt hartkodierter LAN-IP).
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") baseUrl: String,
        @Value("\${HOSHI_HA_STATES_TTL_MS:60000}") statesTtlMs: Long,
        @Value("\${HOSHI_HA_LAST_KNOWN_PATH:}") lastKnownPath: String,
        @Value("\${HOSHI_VACUUM_CACHE_MAX_AGE_HOURS:24}") vacuumCacheMaxAgeHours: Long,
    ): HaHomeRegistryAdapter = HaHomeRegistryAdapter(
        baseUrl = baseUrl,
        token = resolveHaToken(),
        statesTtl = Duration.ofMillis(statesTtlMs),
        lastKnownStore = HaLastKnownStateStore(HaLastKnownStateStore.resolveDefaultPath(lastKnownPath.ifBlank { null })),
        vacuumCacheMaxAge = Duration.ofHours(vacuumCacheMaxAgeHours),
    )

    /** Dupliziert aus `PipelineConfig.resolveHaToken` (s. Klassen-KDoc). */
    private fun resolveHaToken(): String? {
        System.getenv("HOSHI_HA_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val path = Paths.get(System.getProperty("user.home"), ".hoshi", "secrets.json")
            if (!Files.exists(path)) return null
            ObjectMapper().readTree(path.toFile()).get("ha")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Die Räume-Nutzungs-Lese-Naht (s. Klassen-KDoc): liest die 14-Tage-Zählung
     * je Area aus DENSELBEN Tages-Dateien wie [DiaryController]. Wird das
     * Verzeichnis nicht auflösbar (kann bei dieser Auflösung nicht passieren,
     * s. `resolveDiaryDirectory`), bliebe [AreaUsageReader.NONE] der sichere
     * Fallback — hier immer ein echtes [Path], die Datei muss nur nicht existieren.
     */
    @Bean
    fun areaUsageReader(
        @Value("\${hoshi.diary.dir:\${HOSHI_TURN_DIARY_DIR:}}") diaryDir: String,
    ): AreaUsageReader = AreaUsageReader(directory = resolveDiaryDirectory(diaryDir))

    /** Spiegel von [DiaryController.resolveDirectory] (s. Klassen-KDoc: bewusste Duplikation). */
    private fun resolveDiaryDirectory(diaryDir: String): java.nio.file.Path = when {
        diaryDir.isNotBlank() -> Paths.get(diaryDir)
        Files.isWritable(Paths.get("/var/lib/hoshi-0.8")) -> Paths.get("/var/lib/hoshi-0.8/diary")
        else -> Paths.get(System.getProperty("user.home"), ".hoshi", "diary")
    }
}
