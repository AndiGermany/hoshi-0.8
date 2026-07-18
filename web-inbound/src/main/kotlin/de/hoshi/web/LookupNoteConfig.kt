package de.hoshi.web

import de.hoshi.adapters.supervision.JsonlLookupNoteAdapter
import de.hoshi.core.port.LookupNotePort
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * **LookupNoteConfig** — das MINIMALE Wiring des Nachgeschlagen-Store-WRITE
 * (Extended Think S3): genau EINE Bean, der [LookupNotePort]. Bewusst eine
 * EIGENE `@Configuration` statt `PipelineConfig`-Anbau (Muster
 * [ExtendedThinkConfig]): die eigentliche Turn-Pipeline-Verdrahtung
 * (`TurnOrchestrator.lookupNotes`-Param, `groundingPort`-Composite-Erweiterung
 * um `NachgeschlagenGroundingProvider`) ist Sache der `PipelineConfig` — sie ist
 * in der S3-Übergabe (finale Pod-Antwort) als EXAKTER Text dokumentiert und wird
 * dort vom Integrator eingesetzt. Diese Bean hier ist bis dahin funktional
 * fertig (schreibt bei Decke offen echte Notizen), aber pipeline-seitig NICHT
 * verdrahtet ⇒ ohne den `PipelineConfig`-Anschluss bleibt der Turn-Pfad
 * byte-neutral (der Orchestrator sieht [LookupNotePort.NOOP] als Default).
 *
 * Pfad-Auflösung EXAKT [JsonlLookupNoteAdapter.resolveDefaultPath] — DIESELBE
 * Property (`hoshi.escalation.lookup.path`/`HOSHI_ESCALATION_LOOKUP_PATH`) liest
 * auch [PrivacyController] (Lösch-Pfad) und (nach dem PipelineConfig-Anschluss)
 * der `NachgeschlagenGroundingProvider` — „eine Datei-Wahrheit, mehrere schmale
 * Ränder", ohne dass die Module voneinander abhängen.
 */
@Configuration
class LookupNoteConfig {

    @Bean
    fun lookupNotePort(
        @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") extendedThinkEnabled: Boolean,
        @Value("\${hoshi.escalation.lookup.path:\${HOSHI_ESCALATION_LOOKUP_PATH:}}") lookupPath: String,
    ): LookupNotePort =
        if (!extendedThinkEnabled) {
            LookupNotePort.NOOP
        } else {
            JsonlLookupNoteAdapter(JsonlLookupNoteAdapter.resolveDefaultPath(lookupPath.ifBlank { null }))
        }
}
