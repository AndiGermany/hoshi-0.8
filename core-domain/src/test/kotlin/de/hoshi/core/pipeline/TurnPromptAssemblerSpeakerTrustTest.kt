package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.dto.TurnPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * **P1-Privacy: SpeakerTrust an den TurnPromptAssembler-RECALL-Nähten** — beweist, dass
 * BEIDE Recall-Aufrufe ([TurnPromptAssembler.baseSystemPrompt] für Entity-Memory,
 * [TurnPromptAssembler.assemble] für Episodic-Recall) durch [SpeakerTrust.resolve] laufen:
 * `speakerTrustEnforced=false` (Default) ist byte-neutral zu [TurnPromptAssemblerTest]
 * (unverändert grün); `=true` blockiert einen unsicheren/fremden Claim (kein Cross-User-
 * Recall unter der behaupteten Id).
 */
class TurnPromptAssemblerSpeakerTrustTest {

    private val localFact = RouteDecision(RouteCategory.FACT_SHORT, RouteProvider.LOCAL, "x")

    /** Zählender Entity-Fake: hält die zuletzt angefragte speakerId fest. */
    private class RecordingEntity(private val block: String? = "[Gedächtnis: privat]") : EntityContextPort {
        var lastSpeakerId: String? = null
        override fun contextBlock(speakerId: String): String? {
            lastSpeakerId = speakerId
            return block
        }
    }

    /** Zählender Episodic-Fake: hält die zuletzt angefragte speakerId fest. */
    private class RecordingEpisodic(private val block: String = "EPISODIC") : EpisodicRecallPort {
        var lastSpeakerId: String? = null
        override fun recallBlock(speakerId: String, text: String): Mono<String> {
            lastSpeakerId = speakerId
            return Mono.just(block)
        }
    }

    private fun ctx(speaker: SpeakerContext?): TurnPrompt =
        TurnPrompt.from(ChatRequest(text = "geheime Frage", speak = false, chatId = "c1", speakerContext = speaker))

    // ── baseSystemPrompt (Entity-Recall) ──────────────────────────────────────

    @Test
    fun `baseSystemPrompt enforced OFF recallt unter dem behaupteten Claim (byte-neutral)`() {
        val entity = RecordingEntity()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = entity,
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = null,
            speakerTrustEnforced = false,
        )

        asm.baseSystemPrompt(SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0))

        assertEquals("andi", entity.lastSpeakerId, "OFF: der rohe Claim wird ungeprüft genutzt, Score egal")
    }

    @Test
    fun `baseSystemPrompt enforced ON mit niedrigem Score recallt NICHT unter der fremden Id`() {
        val entity = RecordingEntity()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = entity,
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = null,
            speakerTrustEnforced = true,
            speakerTrustThreshold = 0.45,
        )

        asm.baseSystemPrompt(SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0))

        assertEquals("gast", entity.lastSpeakerId, "ON + Score < Schwelle: KEIN Cross-User-Recall unter 'andi'")
    }

    @Test
    fun `baseSystemPrompt enforced ON mit hohem Score vertraut dem Claim`() {
        val entity = RecordingEntity()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = entity,
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = null,
            speakerTrustEnforced = true,
            speakerTrustThreshold = 0.45,
        )

        asm.baseSystemPrompt(SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.9))

        assertEquals("andi", entity.lastSpeakerId, "ON + Score >= Schwelle: der verifizierte Claim wird genutzt")
    }

    // ── assemble (Episodic-Recall) ────────────────────────────────────────────

    @Test
    fun `assemble enforced OFF recallt episodic unter dem behaupteten Claim (byte-neutral)`() {
        val episodic = RecordingEpisodic()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = episodic,
            speakerTrustEnforced = false,
        )

        asm.assemble(ctx(SpeakerContext(speakerId = "andi", score = 0.0)), localFact, "BASE", "").block()

        assertEquals("andi", episodic.lastSpeakerId)
    }

    @Test
    fun `assemble enforced ON mit niedrigem Score recallt episodic NICHT unter der fremden Id`() {
        val episodic = RecordingEpisodic()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = episodic,
            speakerTrustEnforced = true,
            speakerTrustThreshold = 0.45,
        )

        asm.assemble(ctx(SpeakerContext(speakerId = "andi", score = 0.0)), localFact, "BASE", "").block()

        assertEquals("gast", episodic.lastSpeakerId)
    }

    @Test
    fun `assemble enforced ON mit hohem Score recallt episodic unter der verifizierten Id`() {
        val episodic = RecordingEpisodic()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = episodic,
            speakerTrustEnforced = true,
            speakerTrustThreshold = 0.45,
        )

        asm.assemble(ctx(SpeakerContext(speakerId = "andi", score = 0.9)), localFact, "BASE", "").block()

        assertEquals("andi", episodic.lastSpeakerId)
    }

    @Test
    fun `assemble enforced ON ohne Speaker faellt auf Gast zurueck und crasht nicht`() {
        val episodic = RecordingEpisodic()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = episodic,
            speakerTrustEnforced = true,
            speakerTrustThreshold = 0.45,
        )

        asm.assemble(ctx(null), localFact, "BASE", "").block()

        assertEquals("gast", episodic.lastSpeakerId)
    }

    @Test
    fun `assemble enforced OFF ohne Speaker faellt auf unknown zurueck (byte-neutral)`() {
        val episodic = RecordingEpisodic()
        val asm = TurnPromptAssembler(
            persona = PersonaService(),
            entityMemory = { null },
            grounding = { _, _ -> Mono.just("") },
            episodicMemory = episodic,
            speakerTrustEnforced = false,
        )

        asm.assemble(ctx(null), localFact, "BASE", "").block()

        assertEquals("unknown", episodic.lastSpeakerId, "exakt der bisherige Fallback-String, unverändert")
    }
}
