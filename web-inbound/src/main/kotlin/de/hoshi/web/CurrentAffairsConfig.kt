package de.hoshi.web

import de.hoshi.core.pipeline.CurrentAffairsFastpath
import de.hoshi.core.port.CivicAlertPort
import de.hoshi.core.port.CurrentAffairsPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * **CurrentAffairsConfig** — the minimal wiring of the two Lagebild read seams
 * ([CurrentAffairsPort], [CivicAlertPort]), deliberately its own
 * `@Configuration` instead of a PipelineConfig extension (pattern
 * [WeatherLocationConfig]): the news slice must be landable and revertible on
 * its own.
 *
 * **Default is the honest NONE port** — an unavailable snapshot without any
 * network call. So `GET /api/v1/currentaffairs/today` answers correctly from
 * day one (`freshness: "UNAVAILABLE"`, empty items) and the voice fastpath says
 * "no reports right now" rather than pretending.
 *
 * ## Docking expectation for the real adapter
 *
 * `adapters-news` already carries the hardened Tagesschau adapter
 * (`TagesschauCurrentAffairsAdapter`), but `web-inbound` does NOT depend on that
 * module yet and this config deliberately does not wire it. Landing it means
 * exactly three steps:
 *
 * 1. add `implementation(project(":adapters-news"))` to `web-inbound`,
 * 2. declare the real beans in an adapter-side `@Configuration` guarded by
 *    `@ConditionalOnProperty(name = [ADAPTER_PROPERTY], havingValue = "true")`,
 * 3. set [ADAPTER_PROPERTY]`=true` (an owner gate — a flag flip, not a code change).
 *
 * The one property switches BOTH beans here off at once, so the adapter config
 * owns both ports from that moment. Until a civic-alert adapter exists it should
 * simply hand back [CivicAlertPort.NONE] — "unknown" is an honest answer, an
 * absent bean is a boot break.
 */
@Configuration
class CurrentAffairsConfig {

    /**
     * Honest default seam for the news read edge. Disappears as soon as
     * [ADAPTER_PROPERTY] is `true`, leaving the field to the real adapter bean.
     */
    @Bean
    @ConditionalOnProperty(name = [ADAPTER_PROPERTY], havingValue = "false", matchIfMissing = true)
    fun currentAffairsPort(): CurrentAffairsPort = CurrentAffairsPort.NONE

    /**
     * Honest default seam for civic alerts. NONE reports
     * [de.hoshi.core.port.CivicAlertState.UNKNOWN] — never an all-clear.
     */
    @Bean
    @ConditionalOnProperty(name = [ADAPTER_PROPERTY], havingValue = "false", matchIfMissing = true)
    fun civicAlertPort(): CivicAlertPort = CivicAlertPort.NONE

    /**
     * The voice fastpath is ALWAYS armed (hand decision, 15.08.): with the NONE
     * port it answers the "what's new?" question honestly ("no reports right
     * now") instead of letting the brain guess — the PORT decides the truth,
     * not a second flag.
     */
    @Bean
    fun currentAffairsFastpath(port: CurrentAffairsPort): CurrentAffairsFastpath =
        CurrentAffairsFastpath(port = port, enabled = true)

    companion object {
        /**
         * The ONE docking property of the Lagebild slice. Absent/`false` (default)
         * ⇒ the NONE beans above ⇒ byte-neutral, no network. `true` ⇒ this config
         * steps aside and the adapter configuration must supply both ports.
         */
        const val ADAPTER_PROPERTY = "HOSHI_NEWS_ADAPTER_ENABLED"
    }
}
