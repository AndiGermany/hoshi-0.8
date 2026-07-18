package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den **FACT-Route-Temperatur-Clamp** ([TurnOrchestrator.factLowTemp],
 * gated hinter `HOSHI_FACT_LOW_TEMP_ENABLED`): bei ON wird die an den Brain
 * gereichte Temperatur fuer [RouteCategory.FACT_SHORT] auf
 * <= [TurnOrchestrator.FACT_TEMP_CEILING] (0.30) gedeckelt; Nicht-FACT-Routen
 * UND der OFF-Default bleiben EXAKT bei der Persona-Temperatur (byte-neutral).
 *
 * Gemessen wird die WIRKLICH durchgereichte `temperature` ueber einen
 * [TempCapturingBrainPort] — die Garantie liegt query-seitig in Kotlin, nicht in
 * einer Prompt-Bitte.
 */
class TurnOrchestratorFactTempTest {

    /** Brain-Fake, der die durchgereichte `temperature` festhaelt (sonst inert). */
    private class TempCapturingBrainPort : BrainPort {
        val capturedTemperature = AtomicReference<Double?>(null)
        override fun streamChat(
            prompt: String,
            systemPrompt: String,
            history: List<ChatMessage>,
            temperature: Double?,
            sessionId: String,
            userId: String,
            tools: List<Map<String, Any?>>,
            toolGrammar: Boolean,
            onPrefill: (Long) -> Unit,
        ): Flux<LlmDelta> {
            capturedTemperature.set(temperature)
            return Flux.just(LlmDelta("ok"))
        }
    }

    private fun passHonestyGate() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    private fun routingPolicy(category: RouteCategory) = RoutingPolicy(
        keywordRouter = KeywordRouter { RouteDecision(category, RouteProvider.LOCAL, "fake") },
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    private fun assembler(persona: PersonaService) = TurnPromptAssembler(
        persona = persona,
        entityMemory = { null },
        grounding = { _, _ -> Mono.just("") },
        episodicMemory = null,
    )

    private fun orchestrator(
        brain: BrainPort,
        persona: PersonaService,
        category: RouteCategory,
        factLowTemp: Boolean,
    ) = TurnOrchestrator(
        routing = routingPolicy(category),
        honesty = passHonestyGate(),
        promptAssembler = assembler(persona),
        persona = persona,
        formatter = ResponseFormatter(),
        brain = brain,
        factLowTemp = factLowTemp,
    )

    private fun drive(o: TurnOrchestrator, text: String) {
        o.handle(ChatRequest(text = text, language = Language.DE))
            .collectList().block(Duration.ofSeconds(5))
    }

    /** Die Persona-Default-Temperatur (STANDARD) — die ungeclampte Erwartung. */
    private fun personaTemp(persona: PersonaService): Double =
        persona.temperatureFor(persona.moodFor(Persona.STANDARD))

    // ── FACT_SHORT + Clamp ON → <= 0.30 (min(personaTemp, 0.30)) ──────────────
    @Test
    fun `FACT_SHORT mit Clamp ON deckelt die Temperatur auf hoechstens 0_30`() {
        val persona = PersonaService()
        val brain = TempCapturingBrainPort()
        drive(orchestrator(brain, persona, RouteCategory.FACT_SHORT, factLowTemp = true), "In welchem Jahr fiel die Mauer?")

        val captured = brain.capturedTemperature.get()
        assertTrue(captured != null, "Brain muss gerufen worden sein (Pass-Verdict)")
        assertEquals(
            minOf(personaTemp(persona), TurnOrchestrator.FACT_TEMP_CEILING),
            captured,
            "FACT_SHORT-Temperatur muss min(personaTemp, 0.30) sein",
        )
        assertTrue(
            captured!! <= TurnOrchestrator.FACT_TEMP_CEILING,
            "FACT_SHORT-Temperatur muss <= ${TurnOrchestrator.FACT_TEMP_CEILING} sein, war $captured",
        )
    }

    // ── Nicht-FACT (SMALLTALK) + Clamp ON → unveraendert die Persona-Temperatur ──
    @Test
    fun `Nicht-FACT-Route mit Clamp ON behaelt die volle Persona-Temperatur`() {
        val persona = PersonaService()
        val brain = TempCapturingBrainPort()
        drive(orchestrator(brain, persona, RouteCategory.SMALLTALK, factLowTemp = true), "Erzähl mir was Schönes.")

        assertEquals(
            personaTemp(persona),
            brain.capturedTemperature.get(),
            "Nicht-FACT-Routen duerfen NICHT geclampt werden",
        )
    }

    // ── FACT_SHORT + Clamp OFF (Default) → unveraendert (byte-neutral) ─────────
    @Test
    fun `FACT_SHORT mit Clamp OFF bleibt byte-neutral bei der Persona-Temperatur`() {
        val persona = PersonaService()
        val brain = TempCapturingBrainPort()
        drive(orchestrator(brain, persona, RouteCategory.FACT_SHORT, factLowTemp = false), "Wer war Marie Curie?")

        assertEquals(
            personaTemp(persona),
            brain.capturedTemperature.get(),
            "Clamp OFF (Default) ⇒ identische Temperatur wie heute",
        )
    }
}
