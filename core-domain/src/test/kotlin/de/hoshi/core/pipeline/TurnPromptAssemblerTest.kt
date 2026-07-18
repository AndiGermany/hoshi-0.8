package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.dto.TurnPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.TurnPromptAssemblerTest).
 * Entkopplung: statt mockk-Services schmale Ports
 * ([EntityContextPort]/[GroundingPort]/[EpisodicRecallPort]) als zählende Fakes;
 * `TurnContext`→`TurnPrompt`, `SpeakerState`→`SpeakerContext`. Geprüft:
 *  - synchrone Basis persona(+entity) — mit/ohne Entity-Block
 *  - Schichtungs-Reihenfolge base → follow → episodic → grounding (Invariante)
 *  - Provider-/Flag-Gating: Cloud bzw. wikiGrounding=false → KEIN Grounding-Call
 *  - episodic == null → inert; groundingBlock = Mono.empty() → defaultIfEmpty("")
 *  - kombinierte groundingQuery (Multi-Turn-Folge)
 */
class TurnPromptAssemblerTest {

    private val speaker = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0)
    private val localFact = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "x")
    private val cloudFact = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.OPENAI, "x")

    private fun ctx(text: String = "Was ist der Gasometer?"): TurnPrompt =
        TurnPrompt.from(ChatRequest(text = text, speak = false, chatId = "c1"))

    /** Zählender Grounding-Fake. */
    private class RecordingGrounding(private val result: () -> Mono<String>) : GroundingPort {
        val calls = mutableListOf<Pair<String, RouteCategory>>()
        override fun groundingBlock(query: String, category: RouteCategory): Mono<String> {
            calls += query to category
            return result()
        }
    }

    private fun assembler(
        entityBlock: String? = null,
        episodicBlock: String? = null,
        wikiGroundingEnabled: Boolean = true,
        grounding: RecordingGrounding = RecordingGrounding { Mono.just("") },
        persona: PersonaService = PersonaService(),
        ambient: AmbientWarmthPort = AmbientWarmthPort.NONE,
    ): Pair<TurnPromptAssembler, RecordingGrounding> {
        val entityMemory = EntityContextPort { entityBlock }
        val episodic: EpisodicRecallPort? =
            if (episodicBlock != null) EpisodicRecallPort { _, _ -> Mono.just(episodicBlock) } else null
        return TurnPromptAssembler(
            persona = persona,
            entityMemory = entityMemory,
            grounding = grounding,
            episodicMemory = episodic,
            ambient = ambient,
            availableRooms = emptyList(),
            wikiGroundingEnabled = wikiGroundingEnabled,
        ) to grounding
    }

    // ── (1) Basis-Prompt: ohne Entity-Block ──────────────────────────────────

    @Test
    fun `baseSystemPrompt ohne Entity-Block ist nur die Persona`() {
        val persona = PersonaService()
        val (asm, _) = assembler(entityBlock = null, persona = persona)

        val base = asm.baseSystemPrompt(speaker)

        assertEquals(persona.systemPrompt(displayName = "Andi", availableRooms = emptyList()), base)
        assertTrue(!base.contains("[Gedächtnis"), "kein Entity-Block erwartet")
    }

    // ── (2) Basis-Prompt: mit Entity-Block angehängt (Trenner) ───────────────

    @Test
    fun `baseSystemPrompt mit Entity-Block haengt ihn mit Doppel-Newline an`() {
        val (asm, _) = assembler(entityBlock = "[Gedächtnis: mag Tee]")

        val base = asm.baseSystemPrompt(speaker)

        assertTrue(base.endsWith("\n\n[Gedächtnis: mag Tee]"), "Entity-Block am Ende mit \\n\\n-Trenner: '$base'")
    }

    // ── (3) Schichtungs-Reihenfolge: follow → episodic → grounding ───────────

    @Test
    fun `assemble schichtet base follow episodic grounding in dieser Reihenfolge`() {
        val (asm, _) = assembler(episodicBlock = "EPISODIC", grounding = RecordingGrounding { Mono.just("GROUND") })

        val out = asm.assemble(ctx(), localFact, "BASE", "FOLLOW").block()!!

        assertEquals("BASEFOLLOW\n\nEPISODICGROUND", out.finalPrompt)
        assertEquals("GROUND", out.groundBlock)
        assertTrue(out.finalPrompt.indexOf("EPISODIC") < out.finalPrompt.indexOf("GROUND"))
    }

    // ── (4) Leerer Follow- + Episodic-Block: kein Anhang ─────────────────────

    @Test
    fun `assemble mit leerem Follow- und Episodic-Block laesst Base unveraendert`() {
        val (asm, _) = assembler(episodicBlock = null, grounding = RecordingGrounding { Mono.just("") })

        val out = asm.assemble(ctx(), localFact, "BASE", "").block()!!

        assertEquals("BASE", out.finalPrompt)
        assertEquals("", out.groundBlock)
    }

    // ── (5) Provider Cloud → KEIN Grounding-Call ─────────────────────────────

    @Test
    fun `assemble bei Cloud-Provider ruft groundingBlock NICHT auf`() {
        val (asm, grounding) = assembler(episodicBlock = null)

        val out = asm.assemble(ctx(), cloudFact, "BASE", "").block()!!

        assertEquals("BASE", out.finalPrompt)
        assertEquals(0, grounding.calls.size, "Cloud-Provider darf kein Grounding rufen")
    }

    // ── (6) wikiGroundingEnabled=false → KEIN Grounding-Call ─────────────────

    @Test
    fun `assemble bei deaktiviertem Wiki-Grounding ruft groundingBlock NICHT auf`() {
        val (asm, grounding) = assembler(episodicBlock = null, wikiGroundingEnabled = false)

        val out = asm.assemble(ctx(), localFact, "BASE", "").block()!!

        assertEquals("BASE", out.finalPrompt)
        assertEquals(0, grounding.calls.size)
    }

    // ── (7) episodicMemory == null → inert (kein Episodic-Block) ─────────────

    @Test
    fun `assemble ohne EpisodicMemory-Service haengt keinen Episodic-Block an`() {
        val (asm, _) = assembler(episodicBlock = null, grounding = RecordingGrounding { Mono.just("GROUND") })

        val out = asm.assemble(ctx(), localFact, "BASE", "FOLLOW").block()!!

        assertEquals("BASEFOLLOWGROUND", out.finalPrompt)
    }

    // ── (8) groundingBlock = Mono.empty() → defaultIfEmpty("") greift ────────

    @Test
    fun `assemble behandelt leeres Grounding-Mono als leeren Block`() {
        val (asm, _) = assembler(episodicBlock = null, grounding = RecordingGrounding { Mono.empty() })

        StepVerifier.create(asm.assemble(ctx(), localFact, "BASE", ""))
            .assertNext { out ->
                assertEquals("BASE", out.finalPrompt)
                assertEquals("", out.groundBlock)
            }
            .verifyComplete()
    }

    // ── (9) kombinierte Grounding-Query (Multi-Turn-Folge) ───────────────────

    @Test
    fun `assemble nutzt groundingQuery statt ctx-text wenn gesetzt`() {
        val (asm, grounding) = assembler(episodicBlock = null, grounding = RecordingGrounding { Mono.just("GROUND") })

        asm.assemble(ctx(text = "Welche gab es da?"), localFact, "BASE", "FOLLOW",
            groundingQuery = "Mercury Satelliten").block()!!

        assertEquals(1, grounding.calls.size)
        assertEquals("Mercury Satelliten", grounding.calls.first().first,
            "die kombinierte Query steuert das Grounding, NICHT die isolierte Folge-Frage")
    }

    @Test
    fun `assemble ohne groundingQuery groundet auf ctx-text (kein Regress)`() {
        val (asm, grounding) = assembler(episodicBlock = null, grounding = RecordingGrounding { Mono.just("GROUND") })

        asm.assemble(ctx(text = "Was ist der Gasometer?"), localFact, "BASE", "").block()!!

        assertEquals("Was ist der Gasometer?", grounding.calls.first().first)
    }

    // ── (10) Ambient OFF (NONE) ist byte-neutral gegenüber dem Baseline-Prompt ─

    @Test
    fun `baseSystemPrompt mit Ambient OFF ist byte-identisch zur Baseline`() {
        val persona = PersonaService()
        val (off, _) = assembler(persona = persona, ambient = AmbientWarmthPort.NONE)

        val out = off.baseSystemPrompt(speaker)

        // identisch zur reinen Persona (kein Entity, kein Ambient) → zero behavior change OFF.
        assertEquals(persona.systemPrompt(displayName = "Andi", availableRooms = emptyList()), out)
        assertTrue(!out.contains("Ambiente"), "OFF darf KEINEN Ambient-Hinweis schichten: $out")
    }

    // ── (11) Ambient ON: kleiner Wärme-Hinweis ans Ende (evening → wärmer) ────

    @Test
    fun `baseSystemPrompt mit Ambient ON haengt den Waerme-Hinweis ans Ende`() {
        val eveningHint = AmbientMood.warmthHint(hour = 20, language = de.hoshi.core.dto.Language.DE)
        val (on, _) = assembler(ambient = AmbientWarmthPort { eveningHint })

        val out = on.baseSystemPrompt(speaker)

        assertTrue(out.endsWith("\n\n$eveningHint"), "Ambient-Hinweis am Ende mit \\n\\n-Trenner: $out")
        assertTrue(out.contains("wärmer"), "Abend-Hinweis nudgt wärmer: $out")
    }

    // ── (12) Ambient ON + Entity: Reihenfolge persona → entity → ambient ──────

    @Test
    fun `baseSystemPrompt schichtet persona dann entity dann ambient`() {
        val (on, _) = assembler(
            entityBlock = "[Gedächtnis: mag Tee]",
            ambient = AmbientWarmthPort { "[Ambiente: Abend]" },
        )

        val out = on.baseSystemPrompt(speaker)

        assertTrue(out.indexOf("[Gedächtnis") < out.indexOf("[Ambiente"), "Entity VOR Ambient: $out")
        assertTrue(out.endsWith("\n\n[Ambiente: Abend]"), "Ambient ganz am Ende: $out")
    }
}
