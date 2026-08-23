package de.hoshi.web

import de.hoshi.adapters.news.MultiSourceCurrentAffairsAdapter
import de.hoshi.core.port.CivicAlertPort
import de.hoshi.core.port.CurrentAffairsPort
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * **NewsAdapterConfig** — the real Lagebild docking (step 2 of the
 * [CurrentAffairsConfig] docking contract). Active ONLY behind
 * `HOSHI_NEWS_ADAPTER_ENABLED=true`; absent/false keeps the honest NONE beans.
 *
 * Exactly ONE adapter instance and ONE scheduler thread (adapter contract:
 * refresh synchronization is per-instance, no cross-process lease). `latest()`
 * never does network I/O — all fetching happens on this daemon thread via
 * `refresh()`, which never throws. First refresh ~30s after boot (backend
 * health first), then every 30 minutes (agreed poll default; night reduction
 * is a later slice).
 *
 * Civic alerts: no adapter exists yet — hands back [CivicAlertPort.NONE]
 * (UNKNOWN, never an all-clear), as the docking contract requires.
 */
@Configuration
@ConditionalOnProperty(name = [CurrentAffairsConfig.ADAPTER_PROPERTY], havingValue = "true")
class NewsAdapterConfig(
    private val newsSourcesStore: JsonFileNewsSourcesStore,
) {

    private val log = LoggerFactory.getLogger(NewsAdapterConfig::class.java)

    // Wave 1b: multi-source (Tagesschau + heise + Golem, Andi's order). The
    // active set is read from the settings store on every refresh/read; an
    // absent record (never explicitly set) falls back to the three defaults —
    // see JsonFileNewsSourcesStore's KDoc for the full null-vs-empty contract.
    private val adapter = MultiSourceCurrentAffairsAdapter(
        activeSources = { newsSourcesStore.activeSources() ?: MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES },
    )

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "news-refresh").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(
                {
                    val report = adapter.refresh()
                    log.info("[news] refresh: {}", report)
                },
                INITIAL_DELAY_S,
                PERIOD_S,
                TimeUnit.SECONDS,
            )
        }

    @Bean
    fun tagesschauCurrentAffairsPort(): CurrentAffairsPort = adapter

    @Bean
    fun noneCivicAlertPort(): CivicAlertPort = CivicAlertPort.NONE

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        adapter.close()
    }

    companion object {
        private const val INITIAL_DELAY_S = 30L
        private const val PERIOD_S = 30L * 60L
    }
}
