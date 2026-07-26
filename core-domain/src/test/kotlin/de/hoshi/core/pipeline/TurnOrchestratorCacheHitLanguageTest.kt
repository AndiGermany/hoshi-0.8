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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **Ehrlichkeits-Schuld-Fix (seit v0.8.1-rc1 in den Release-Notes): „die
 * cacheHit-Telemetrie erkennt ihren Herkunfts-Marker nur auf Deutsch."**
 *
 * Ergänzt [TurnOrchestratorCacheHitTest] (die DE-Fälle + die pure
 * [TurnOrchestrator.parseCacheHitSource]-Funktion bleiben dort und bleiben
 * byte-identisch) um die EN/ES/FR/IT-Fälle: [ChatEvent.Start.cacheHit] muss in
 * JEDER Sprache ehrlich `true` werden, wenn der assemblierte `groundBlock` den
 * Herkunfts-Marker DIESER Sprache trägt
 * ([TurnPromptAssembler.nachgeschlagenOriginMarker]) — nicht mehr nur bei DE.
 *
 * Die Block-Fragmente hier (Kopf/Quellen-Zeile/ANWEISUNG/Sprach-Hinweis) sind
 * bewusst wortgleich mit dem echten, quoteFence=false-Wortlaut aus
 * `de.hoshi.adapters.knowledge.NachgeschlagenBlockTexts` (Stand des Sprach-
 * Naht-Baus 2026-07-25) — GENAU wie [TurnOrchestratorCacheHitTest]s DE-Block
 * schon den `plainInstruction`-Zweig ohne Zitat-Zaun nachbildet, nicht den
 * `fencedInstruction`-Zweig (core-domain darf `adapters-knowledge` nicht
 * importieren, s. Modul-Graph — die Wortgleichheit ist darum eine manuell
 * gepflegte Kopie, kein Compiler-Schutz; Drift zeigt sich als roter Test hier
 * ODER in `NachgeschlagenGroundingLanguageTest` auf der Bau-Seite).
 */
class TurnOrchestratorCacheHitLanguageTest {

    private class FakeBrainPort(private val line: String = "42 Metres.") : BrainPort {
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
                entityMemory = { _, _ -> null },
                grounding = GroundingPort.fixed(groundBlock),
                episodicMemory = null,
            ),
            persona = persona,
            formatter = ResponseFormatter(),
            brain = FakeBrainPort(),
            // FactCoverageGate DISABLED (Default): dieser Test prüft NUR die
            // Cache-Hit-Erkennung, nicht die Deflect-Wand (Muster TurnOrchestratorCacheHitTest).
        )
    }

    private fun start(o: TurnOrchestrator, language: Language): ChatEvent.Start =
        o.handle(ChatRequest(text = "irrelevant", language = language)).collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Start>().first()

    private fun blockEn(dateLabel: String, source: String) =
        "\n\n---\n" +
            "BACKGROUND (for you only, do NOT mention it in the conversation):\n" +
            "• The Eiffel Tower is 330 metres tall.\n" +
            "Source: $source.\n" +
            "INSTRUCTION: You (Hoshi) already looked this up online recently (as of $dateLabel) — say so " +
            "honestly (e.g. “I ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER_EN}, as of $dateLabel”) " +
            "and answer briefly in your own warm style from this background. Add nothing you were not given." +
            " The quote may be in another language; answer in English anyway."

    private fun blockEs(dateLabel: String, source: String) =
        "\n\n---\n" +
            "CONTEXTO (solo para ti, NO lo menciones en la conversación):\n" +
            "• La Torre Eiffel mide 330 metros.\n" +
            "Fuente: $source.\n" +
            "INSTRUCCIÓN: Tú (Hoshi) ya consultaste esto en línea hace poco (a fecha de $dateLabel) — dilo " +
            "con honestidad (p. ej. “Lo ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER_ES}, a fecha de $dateLabel”) " +
            "y responde brevemente con tu propio estilo cálido a partir de este contexto. No inventes nada más." +
            " La cita puede estar en otro idioma; responde igualmente en español."

    private fun blockFr(dateLabel: String, source: String) =
        "\n\n---\n" +
            "CONTEXTE (pour toi uniquement, NE le mentionne PAS dans la conversation) :\n" +
            "• La tour Eiffel mesure 330 mètres.\n" +
            "Source : $source.\n" +
            "INSTRUCTION : Tu (Hoshi) as déjà cherché cela en ligne récemment (état du $dateLabel) — dis-le " +
            "honnêtement (p. ex. “J'ai ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER_FR}, état du $dateLabel”) " +
            "et réponds brièvement dans ton style chaleureux à partir de ce contexte. N'invente rien de plus." +
            " La citation peut être dans une autre langue ; réponds quand même en français."

    private fun blockIt(dateLabel: String, source: String) =
        "\n\n---\n" +
            "CONTESTO (solo per te, NON menzionarlo nella conversazione):\n" +
            "• La Torre Eiffel è alta 330 metri.\n" +
            "Fonte: $source.\n" +
            "ISTRUZIONE: Tu (Hoshi) hai già cercato questo online di recente (aggiornato al $dateLabel) — dillo " +
            "onestamente (p. es. “L'ho ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER_IT}, aggiornato al $dateLabel”) " +
            "e rispondi in breve con il tuo stile caloroso a partire da questo contesto. Non inventare nulla in più." +
            " La citazione può essere in un'altra lingua; rispondi comunque in italiano."

    @Test
    fun `EN Cache-Marker im groundBlock - cacheHit=true, Quelle wird gelesen`() {
        val start = start(orchestrator(blockEn("01.07.2026", "Wikipedia")), Language.EN)
        assertTrue(start.grounded)
        assertTrue(start.cacheHit, "der englische Herkunfts-Marker muss cacheHit setzen")
        assertFalse(start.escalated)
        assertEquals("Wikipedia", start.escalationSource, "H2: sprachneutrale Quellen-Zeile (Label \"Source:\")")
    }

    @Test
    fun `ES Cache-Marker im groundBlock - cacheHit=true, Quelle wird gelesen`() {
        val start = start(orchestrator(blockEs("01.07.2026", "Wikipedia")), Language.ES)
        assertTrue(start.grounded)
        assertTrue(start.cacheHit, "der spanische Herkunfts-Marker muss cacheHit setzen")
        assertFalse(start.escalated)
        assertEquals("Wikipedia", start.escalationSource, "H2: sprachneutrale Quellen-Zeile (Label \"Fuente:\")")
    }

    @Test
    fun `FR Cache-Marker im groundBlock - cacheHit=true, Quelle wird gelesen`() {
        val start = start(orchestrator(blockFr("01.07.2026", "Wikipedia")), Language.FR)
        assertTrue(start.grounded)
        assertTrue(start.cacheHit, "der französische Herkunfts-Marker muss cacheHit setzen")
        assertFalse(start.escalated)
        assertEquals(
            "Wikipedia",
            start.escalationSource,
            "H2: sprachneutrale Quellen-Zeile (Label \"Source :\", Leerzeichen vor dem Doppelpunkt)",
        )
    }

    @Test
    fun `IT Cache-Marker im groundBlock - cacheHit=true, Quelle wird gelesen`() {
        val start = start(orchestrator(blockIt("01.07.2026", "Wikipedia")), Language.IT)
        assertTrue(start.grounded)
        assertTrue(start.cacheHit, "der italienische Herkunfts-Marker muss cacheHit setzen")
        assertFalse(start.escalated)
        assertEquals("Wikipedia", start.escalationSource, "H2: sprachneutrale Quellen-Zeile (Label \"Fonte:\")")
    }

    // ── Nicht-Cache-Turns bleiben false, auch außerhalb Deutsch ──────────────

    @Test
    fun `EN Wiki-Grounding ohne Marker - grounded=true aber cacheHit bleibt false`() {
        val block = "\n\n---\nBACKGROUND: • Eiffel Tower: iron lattice tower in Paris, 330 metres.\n"
        val start = start(orchestrator(block), Language.EN)
        assertTrue(start.grounded, "non-blank Block deckt (lax-Sicht)")
        assertFalse(start.cacheHit, "wiki-Grounding trägt in KEINER Sprache den Nachgeschlagen-Marker")
        assertEquals("", start.escalationSource)
    }

    @Test
    fun `IT leeres Grounding - weder grounded noch cacheHit`() {
        val start = start(orchestrator(""), Language.IT)
        assertFalse(start.grounded)
        assertFalse(start.cacheHit)
        assertEquals("", start.escalationSource)
    }

    // ── DE bleibt byte-identisch zum bisherigen Verhalten ─────────────────────

    @Test
    fun `DE Cache-Marker im groundBlock - unveraendert cacheHit=true (Regressionsschutz des Fixes)`() {
        val block = "\n\n---\n" +
            "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
            "• Der Eiffelturm ist 330 Meter hoch.\n" +
            "Quelle: Wikipedia.\n" +
            "ANWEISUNG: Das hast du (Hoshi) neulich schon online nachgeschlagen (Stand 01.07.2026) — sag das " +
            "ehrlich dazu (z. B. \"Hab ich ${TurnPromptAssembler.NACHGESCHLAGEN_ORIGIN_MARKER}, Stand 01.07.2026\") " +
            "und antworte knapp im eigenen warmen Stil aus diesem Hintergrund. Erfinde nichts dazu."
        val start = start(orchestrator(block), Language.DE)
        assertTrue(start.grounded)
        assertTrue(start.cacheHit)
        assertFalse(start.escalated)
        assertEquals("Wikipedia", start.escalationSource)
    }

    @Test
    fun `DE Quelle mit Punkten im Wert - parseCacheHitSource unveraendert (Regex-Erweiterung bricht DE nicht)`() {
        assertEquals(
            "de.wikipedia.org",
            TurnOrchestrator.parseCacheHitSource("Quelle: de.wikipedia.org.\nANWEISUNG: …"),
        )
    }
}
