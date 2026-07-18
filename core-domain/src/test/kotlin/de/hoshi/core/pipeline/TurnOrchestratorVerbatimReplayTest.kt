package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.port.BrainPort
import de.hoshi.core.port.LookupNote
import de.hoshi.core.port.LookupReplayPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Brain-freies Verbatim-Replay eines sicheren Nachgeschlagen-Cache-Treffers**
 * (Andi-Befund 2026-07-16: „die gecachten smart Antworten klingen echt doof").
 *
 * Beweist auf Orchestrator-Ebene: ein sicherer Treffer (der injizierte
 * [LookupReplayPort] liefert eine [LookupNote]) wird bei `HOSHI_LOOKUP_VERBATIM_
 * REPLAY_ENABLED=true` WÖRTLICH + brain-frei zurückgespielt; ohne Flag / ohne
 * Treffer / bei Nicht-LOCAL bleibt der heutige Brain-Pfad. Die Schwellen-/TTL-/
 * Kategorie-Semantik selbst (wann [LookupReplayPort.bestNote] `null` liefert) prüft
 * der `NachgeschlagenGroundingProviderTest` (die eine Match-Wahrheit).
 */
class TurnOrchestratorVerbatimReplayTest {

    private val question = "Wie hoch ist der Eiffelturm?"

    private val note = LookupNote(
        queryHash = "h",
        queryNorm = "wie hoch ist der eiffelturm",
        answer = "Der Eiffelturm ist 330 Meter hoch.",
        source = "Wikipedia",
        provider = "openai-nano",
        costCents = 0.1,
        ts = Instant.parse("2026-07-01T12:00:00Z"),
        ttlDays = 30,
    )

    /** Zählt Brain-Calls — der Verbatim-Replay muss den Brain NIE anfassen (0 Calls). */
    private class CountingBrainPort(
        val line: String = "Der Turm ist ungefähr dreihundert Meter, glaub ich.",
    ) : BrainPort {
        val calls = AtomicInteger(0)
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
            calls.incrementAndGet()
            return Flux.just(LlmDelta(line))
        }
    }

    private fun orchestrator(
        brain: BrainPort,
        replay: LookupReplayPort,
        replayEnabled: Boolean,
        provider: RouteProvider = RouteProvider.LOCAL,
    ): TurnOrchestrator {
        val persona = PersonaService()
        return TurnOrchestrator(
            routing = RoutingPolicy(
                keywordRouter = KeywordRouter {
                    RouteDecision(RouteCategory.FACT_SHORT, provider, "fake")
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
                grounding = { _, _ -> Mono.just("") },
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = brain,
            lookupReplay = replay,
            verbatimReplayEnabled = replayEnabled,
        )
    }

    private fun run(o: TurnOrchestrator, language: Language = Language.DEFAULT): List<ChatEvent> =
        o.handle(ChatRequest(text = question, language = language)).collectList().block(Duration.ofSeconds(5))!!

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `sicherer Treffer bei Flag AN - woertliches Replay, 0 Brain-Calls, Datum + Quelle`() {
        val brain = CountingBrainPort()
        val events = run(orchestrator(brain, { _, _ -> note }, replayEnabled = true))

        assertEquals(0, brain.calls.get(), "Verbatim-Replay ruft den Brain NIE (0 Brain-Calls/Turn)")
        val start = events.filterIsInstance<ChatEvent.Start>().single()
        assertTrue(start.cacheHit, "ein Cache-Replay ist ein Cache-Hit")
        assertTrue(start.grounded, "ein Cache-Hit ist per Definition gedeckt")
        assertFalse(start.escalated, "kein Netz-Call ⇒ kein Eskalations-Turn")
        assertEquals("Wikipedia", start.escalationSource, "die Quelle reist als Turn↔Note-Verknüpfung am Start")
        assertEquals("policy", start.model, "brain-freier Pfad ⇒ model=policy")

        assertEquals(
            "Hab ich neulich nachgeschlagen, Stand 01.07.2026: Der Eiffelturm ist 330 Meter hoch. Quelle: Wikipedia.",
            text(events),
            "warme Rahmung + Datum + Answer WÖRTLICH + Quelle",
        )
        assertTrue(text(events).contains(note.answer), "die Antwort steckt BYTE-WÖRTLICH im Replay (keine Paraphrase)")
    }

    @Test
    fun `Replay auf Englisch - EN-Rahmung, Answer byte-woertlich`() {
        val brain = CountingBrainPort()
        val events = run(orchestrator(brain, { _, _ -> note }, replayEnabled = true), language = Language.EN)

        assertEquals(0, brain.calls.get())
        assertEquals(
            "I looked this up recently, as of 01.07.2026: Der Eiffelturm ist 330 Meter hoch. Source: Wikipedia.",
            text(events),
        )
    }

    @Test
    fun `Flag AUS - Brain-Pfad, Replay-Port NIE konsultiert (byte-neutral)`() {
        val brain = CountingBrainPort()
        val portTouched = AtomicInteger(0)
        val replay = LookupReplayPort { _, _ -> portTouched.incrementAndGet(); note }

        val events = run(orchestrator(brain, replay, replayEnabled = false))

        assertEquals(0, portTouched.get(), "Flag AUS ⇒ der Replay-Port wird NIE angefasst")
        assertEquals(1, brain.calls.get(), "Flag AUS ⇒ der heutige Brain-Pfad läuft normal")
        assertEquals(brain.line, text(events), "die Ausgabe ist die Brain-Antwort, NICHT das Replay")
        assertFalse(events.filterIsInstance<ChatEvent.Start>().single().cacheHit, "kein Cache-Hit ohne Feature")
    }

    @Test
    fun `kein sicherer Treffer bei Flag AN - faellt auf den Brain-Pfad`() {
        val brain = CountingBrainPort("Der ist etwa 300 Meter hoch.")
        // NONE == leerer/unter-Schwelle/abgelaufener Store aus Orchestrator-Sicht: bestNote==null.
        val events = run(orchestrator(brain, LookupReplayPort.NONE, replayEnabled = true))

        assertEquals(1, brain.calls.get(), "kein Treffer ⇒ heutiger Brain-Pfad (Grounding-Injektion/Deflect)")
        assertEquals("Der ist etwa 300 Meter hoch.", text(events))
        assertFalse(events.filterIsInstance<ChatEvent.Start>().single().cacheHit)
    }

    @Test
    fun `Nicht-LOCAL-Provider bei Flag AN - kein Replay, Port unberuehrt (Provider-Gate)`() {
        val brain = CountingBrainPort()
        val portTouched = AtomicInteger(0)
        val replay = LookupReplayPort { _, _ -> portTouched.incrementAndGet(); note }

        val events = run(orchestrator(brain, replay, replayEnabled = true, provider = RouteProvider.OPENAI))

        assertEquals(0, portTouched.get(), "der Provider-Gate spiegelt den Grounding-Gate (nur LOCAL)")
        assertEquals(1, brain.calls.get(), "Nicht-LOCAL ⇒ heutiger Brain-Pfad")
        assertFalse(events.filterIsInstance<ChatEvent.Start>().single().cacheHit)
    }
}
