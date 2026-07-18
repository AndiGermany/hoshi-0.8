package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **S4 Diary — Grounding-Cache-Hit** (Extended Think): beweist, dass der
 * [TurnOrchestrator] [ChatEvent.Start.cacheHit] EHRLICH aus dem Herkunfts-Marker
 * ([TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER]) im assemblierten
 * `groundBlock` liest — GENAU am selben Konsum-Punkt wie [ChatEvent.Start.grounded]
 * (Muster [TurnOrchestratorFactCoverageChainTest]), aber ZUSÄTZLICH spezifisch für
 * die S3-Cache-Scheibe: wiki-/weather-Grounding DECKT (grounded=true), ist aber
 * KEIN Cache-Hit (cacheHit bleibt false), weil es den Marker nicht trägt.
 */
class TurnOrchestratorCacheHitTest {

    private val question = "Wie hoch ist der Eiffelturm?"

    private class FakeBrainPort(private val line: String = "Der Eiffelturm ist 330 Meter hoch.") : BrainPort {
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
        ): Flux<LlmDelta> = Flux.just(LlmDelta(line))
    }

    private fun orchestrator(groundBlock: String): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "fake")
                },
                llmRefiner = { _, fb -> Mono.just(fb) },
                embeddingRefiner = { _, fb -> Mono.just(fb) },
                softRoutingEnabled = false,
                softRoutingMode = "embedding",
            ),
            honesty = HonestyGate(
                weakDomain = WeakDomainSignal { false },
                onlineRequest = OnlineRequestSignal { false },
                existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
                namedEntity = NamedEntitySignal { HonestySignal.NONE },
                cloudEnabled = { false },
            ),
            promptAssembler = TurnPromptAssembler(
                persona = persona,
                entityMemory = { null },
                grounding = { _, _ -> Mono.just(groundBlock) },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
            // FactCoverageGate DISABLED (Default): dieser Test prüft NUR die
            // Cache-Hit-Erkennung, nicht die Deflect-Wand.
        )
    }

    private fun start(o: TurnOrchestrator): ChatEvent.Start =
        o.handle(ChatRequest(text = question)).collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Start>().first()

    @Test
    fun `Nachgeschlagen-Cache-Marker im groundBlock - cacheHit=true, escalated bleibt false`() {
        val block = "\n\n---\n" +
            "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
            "• Der Eiffelturm ist 330 Meter hoch.\n" +
            "Quelle: Wikipedia.\n" +
            "ANWEISUNG: Das hast du (Hoshi) neulich schon online nachgeschlagen (Stand 01.07.2026) — sag das " +
            "ehrlich dazu (z. B. \"Hab ich ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER}, Stand 01.07.2026\") " +
            "und antworte knapp im eigenen warmen Stil aus diesem Hintergrund. Erfinde nichts dazu."
        val start = start(orchestrator(block))
        assertTrue(start.grounded, "ein Cache-Hit ist per Definition gedeckt")
        assertTrue(start.cacheHit, "der Herkunfts-Marker im Block muss cacheHit setzen")
        assertFalse(start.escalated, "ein Cache-Hit ist KEIN Eskalations-Turn (0 Netz-Calls)")
        // H2: Turn↔Note-Verknüpfung — die Quelle wird aus dem "Quelle: …"-Nachsatz
        // des bereits assemblierten groundBlock gelesen (kein zweiter Lese-Pfad).
        assertEquals("Wikipedia", start.escalationSource, "H2: die Cache-Hit-Quelle reist am Start")
    }

    @Test
    fun `Wiki-Grounding ohne Marker - grounded=true aber cacheHit bleibt false`() {
        val block = "\n\n---\nHINTERGRUND: • Eiffelturm: Eisenfachwerkturm in Paris, 330 Meter.\n"
        val start = start(orchestrator(block))
        assertTrue(start.grounded, "non-blank Block deckt (lax-Sicht)")
        assertFalse(start.cacheHit, "wiki-Grounding trägt den Nachgeschlagen-Marker NICHT")
        assertEquals("", start.escalationSource, "H2: ohne Cache-Hit bleibt die Quelle leer (Wire-Sentinel)")
    }

    @Test
    fun `leeres Grounding - weder grounded noch cacheHit`() {
        val start = start(orchestrator(""))
        assertFalse(start.grounded)
        assertFalse(start.cacheHit)
        assertEquals("", start.escalationSource)
    }

    // ── H2: die pure Parse-Funktion direkt (Companion, ohne Pipeline) ────────────
    @Test
    fun `parseCacheHitSource - liest die Quelle, auch wenn sie selbst Punkte traegt`() {
        assertEquals("Wikipedia", TurnOrchestrator.parseCacheHitSource("Quelle: Wikipedia.\nANWEISUNG: …"))
        assertEquals(
            "de.wikipedia.org",
            TurnOrchestrator.parseCacheHitSource("Quelle: de.wikipedia.org.\nANWEISUNG: …"),
            "greedy Match laesst nur den letzten Punkt fuer das Zeilenende uebrig",
        )
    }

    @Test
    fun `parseCacheHitSource - null ohne Quelle-Zeile`() {
        assertEquals(null, TurnOrchestrator.parseCacheHitSource("HINTERGRUND: kein Quellen-Nachsatz hier.\n"))
        assertEquals(null, TurnOrchestrator.parseCacheHitSource(""))
    }
}
