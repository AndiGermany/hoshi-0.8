package de.hoshi.web

import de.hoshi.adapters.escalation.EscalationModelCatalog
import de.hoshi.adapters.escalation.EscalationSpendStore
import de.hoshi.adapters.escalation.OpenAiEscalationAdapter
import de.hoshi.core.port.EscalationPort
import de.hoshi.kernel.EgressPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

/**
 * **LookupModelConfig** — das MINIMALE Wiring der Lookup-Modell-Laufzeit-Wahl
 * (Andi-Video-Auftrag, Muster [TtsRuntimeConfig]/[WeatherLocationConfig]).
 *
 * Zwei Beans:
 *  - [lookupModelStore]: der [JsonFileLookupModelStore], den
 *    [LookupModelController] (GET/PUT) und [delegatingEscalationPort] TEILEN.
 *  - [delegatingEscalationPort]: der [DelegatingEscalationPort], den
 *    `PipelineConfig.turnOrchestrator` für den SCHNELL-Lookup nutzt (per
 *    `@Qualifier`, s. dortiges KDoc) und [LookupModelController] bei einem PUT
 *    umschaltet. Betrifft NUR `hoshi.escalation.model` — das Recherche-Modell
 *    (`hoshi.escalation.research-model`, [PipelineConfig.researchEscalationPort])
 *    bleibt komplett unangetastet (Andi-Vorgabe).
 *
 * **Decke bleibt die Wahrheit:** ist `HOSHI_EXTENDED_THINK_ENABLED=false`, bleibt
 * der Delegat auch bei einem abweichenden gespeicherten Modell-Wunsch bei
 * [EscalationPort.NONE] (kein Netz) — der Wunsch greift, sobald die Decke öffnet
 * (Fallback-Default-Vertrag, s. [JsonFileLookupModelStore]-KDoc). Beide
 * Eskalations-Ports teilen sich weiter DENSELBEN [EscalationSpendStore] (ein
 * Tages-Cap, [PipelineConfig.escalationSpendStore]).
 */
@Configuration
class LookupModelConfig {

    @Bean
    fun lookupModelStore(
        @Value("\${hoshi.lookup-model.path:\${HOSHI_LOOKUP_MODEL_PATH:}}") settingsPath: String,
    ): JsonFileLookupModelStore = JsonFileLookupModelStore(resolvePath(settingsPath))

    @Bean
    fun delegatingEscalationPort(
        escalationPort: EscalationPort,
        lookupModelStore: JsonFileLookupModelStore,
        escalationSpendStore: EscalationSpendStore,
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${hoshi.escalation.model:}") escalationModel: String,
        @Value("\${HOSHI_HA_BASE_URL:http://homeassistant.local:8123}") haBaseUrl: String,
        @Value("\${hoshi.escalation.web-search:\${HOSHI_ESCALATION_WEB_SEARCH:false}}") webSearchEnabled: Boolean,
    ): DelegatingEscalationPort {
        val bootDefaultId = escalationModel.ifBlank { EscalationModelCatalog.DEFAULT_MODEL_ID }
        val storedId = lookupModelStore.modelId()?.let { EscalationModelCatalog.byId(it)?.id }
        return when {
            storedId == null || storedId == bootDefaultId ->
                // Kein abweichender Laufzeit-Wunsch ⇒ EXAKT der Boot-Adapter (byte-neutral).
                DelegatingEscalationPort(initialModelId = bootDefaultId, initial = escalationPort)
            !extendedThinkEnabled ->
                // Decke zu ⇒ trotz abweichendem Wunsch bleibt es NONE (kein Netz); der
                // Wunsch greift erst, sobald die Decke öffnet.
                DelegatingEscalationPort(initialModelId = storedId, initial = EscalationPort.NONE)
            else ->
                DelegatingEscalationPort(
                    initialModelId = storedId,
                    initial = buildEscalationAdapter(storedId, escalationSpendStore, haBaseUrl, webSearchEnabled),
                )
        }
    }

    private fun resolvePath(explicit: String): Path =
        if (explicit.isNotBlank()) Paths.get(explicit.trim())
        else Paths.get(System.getProperty("user.home"), ".hoshi", "lookup-model.json")
}

/**
 * Baut EINEN frischen Nachschlag-Adapter für [modelId] — geteilt zwischen dem
 * Boot-Wiring ([LookupModelConfig.delegatingEscalationPort]) und dem
 * Runtime-Switch ([LookupModelController]), EXAKT das Konstruktions-Muster von
 * [PipelineConfig.escalationPort] (derselbe [EgressPort]-Riegel, derselbe
 * geteilte Spend-Store, dasselbe Web-Search-Flag).
 */
fun buildEscalationAdapter(
    modelId: String,
    spendStore: EscalationSpendStore,
    haBaseUrl: String,
    webSearchEnabled: Boolean,
): EscalationPort = OpenAiEscalationAdapter(
    egress = EgressPort(haBaseUrl = haBaseUrl),
    apiKey = System.getenv("OPENAI_API_KEY"),
    spendStore = spendStore,
    model = modelId,
    webSearch = webSearchEnabled,
)
