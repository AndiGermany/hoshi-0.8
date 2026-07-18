package de.hoshi.web.offline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LanguagePolicy
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.CalcFastpath
import de.hoshi.core.pipeline.DeterministicToolIntentClassifier
import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.HeuristicLanguageDetector
import de.hoshi.core.pipeline.HonestyGate
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.LanguageResolver
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.OnlineRequestSignal
import de.hoshi.core.pipeline.PersonaResolver
import de.hoshi.core.pipeline.PersonaService
import de.hoshi.core.pipeline.ResponseFormatter
import de.hoshi.core.pipeline.RoutingPolicy
import de.hoshi.core.pipeline.ToolIntentClassifier
import de.hoshi.core.pipeline.TurnOrchestrator
import de.hoshi.core.pipeline.TurnPromptAssembler
import de.hoshi.core.pipeline.WeakDomainSignal
import de.hoshi.core.port.BrainPort
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.web.routing.KeywordRouterImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * # Offline-Replay-Harness (`verify:offline`) — Maja + Yuki, Team-Ticket #2
 *
 * Eine **reproduzierbare Regressions-Harness, die das DETERMINISTISCHE Verhalten der
 * echten Turn-Pipeline ohne einen Live-Brain beweist** (kein :8041, kein Netz, keine
 * Uhr-Abhaengigkeit ausser der Persona-Default-Stimmung).
 *
 * Jeder der ~20 Faelle faehrt durch die **ECHTE Pipeline**:
 *   Edge-Resolver ([LanguageResolver]/[PersonaResolver]) -> [RoutingPolicy] mit dem
 *   **echten** [KeywordRouterImpl] -> [DeterministicToolIntentClassifier] +
 *   [CalcFastpath] -> [HonestyGate] -> [TurnPromptAssembler] (mit Grounding-Naht) ->
 *   [TurnOrchestrator] -> ein **scripted/deterministischer** [ScriptedFakeBrain].
 *
 * ## Was bewiesen wird (deterministisch, ohne Brain)
 *  - **RouteCategory** (KeywordRouterImpl): klare Smart-Home-Phrase -> `SMART_HOME`,
 *    Smalltalk -> `SMALLTALK`, Wissensfrage -> `FACT_SHORT`.
 *  - **Fastpath/Modell** (CalcFastpath): eingebettete + reine Mathe laeuft brain-frei
 *    (`model=policy`, 0 Brain-Calls) und byte-genau (`17 x 23 = 391`).
 *  - **Grounding-Trigger**: bei `FACT_SHORT` reicht die Assembly-Naht den
 *    Grounding-Block in den System-Prompt; bei `SMALLTALK`/`SMART_HOME` NICHT.
 *  - **Sprache** (LanguageResolver, AUTO): deutscher Text -> DE-Pfad (DE-Prompt-Body
 *    bzw. DE-Quittung), englischer Text -> EN-Pfad.
 *  - **Persona** (PersonaResolver): Flag OFF -> alle Personas kollabieren auf
 *    `STANDARD` (Prompt-Tonzeile + Sampling-Temperatur), Flag ON -> die Wahl reist durch.
 *
 * ## Was die Harness NICHT abdeckt (EHRLICH markiert — gruen != lebt)
 *  - **Die tatsaechliche Antwort-QUALITAET / den Wortlaut des Brains.** Der
 *    ScriptedFakeBrain liefert eine Konstante; ob der echte 4B `391` sauber spricht
 *    (statt "dreihundertundsterzehn") oder eine Wissensfrage korrekt beantwortet, ist
 *    eine **separate Live-Probe** (siehe vault/knowledge/GOLDEN-fact-accuracy-*.md).
 *  - **TTFT/Token-Rate/Memory-Pressure** (Yuki: am laufenden Stack messen, nicht hier).
 *  - **TTS/Audio, echtes HA-I/O, echte Knowledge-Bridge** (alles Adapter-Live-Sache).
 *
 * ## Off-Hot-Path
 * Mit `@Tag("offline-replay")` markiert; das Standard-`test`/`build` schliesst diesen
 * Tag aus (siehe `web-inbound/build.gradle.kts`). Start explizit:
 *   `./gradlew verifyOffline`  (bzw. `./gradlew :web-inbound:verifyOffline`).
 */
@Tag("offline-replay")
class OfflineReplayHarnessTest {

    // ── Scripted, deterministischer Fake-Brain (NIE :8041) ───────────────────────
    /** Liefert eine Konstante, zaehlt Calls, haelt den letzten System-Prompt + die Temperatur fest. */
    private class ScriptedFakeBrain(private val line: String = "Alles klar, kein Ding.") : BrainPort {
        val callCount = AtomicInteger(0)
        val lastSystemPrompt = AtomicReference<String?>(null)
        val lastTemperature = AtomicReference<Double?>(null)
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
            callCount.incrementAndGet()
            lastSystemPrompt.set(systemPrompt)
            lastTemperature.set(temperature)
            return Flux.just(LlmDelta(line))
        }
    }

    // ── Verdrahtung der ECHTEN Pipeline-Naehte (Spring-frei) ─────────────────────
    private val languageResolver = LanguageResolver(HeuristicLanguageDetector(), autoEnabled = true)

    /** HonestyGate, der immer PASS gibt (inerte Stubs) — der Brain-Pfad bleibt erreichbar. */
    private fun passGate() = HonestyGate(
        weakDomain = WeakDomainSignal { false },
        onlineRequest = OnlineRequestSignal { false },
        existenceClaim = ExistenceClaimSignal { HonestySignal.NONE },
        namedEntity = NamedEntitySignal { HonestySignal.NONE },
        cloudEnabled = { false },
    )

    /** RoutingPolicy mit dem ECHTEN KeywordRouterImpl (Refiner = Passthrough, kein Infra-Call). */
    private fun routingPolicy() = RoutingPolicy(
        keywordRouter = KeywordRouterImpl(),
        llmRefiner = { _, fb -> Mono.just(fb) },
        embeddingRefiner = { _, fb -> Mono.just(fb) },
        softRoutingEnabled = false,
        softRoutingMode = "embedding",
    )

    /**
     * Grounding-Naht, die den ECHTEN [de.hoshi.adapters.knowledge.Fts5GroundingAdapter]-Gate
     * spiegelt: ein Block NUR fuer die Grounding-Kategorien (FACT_SHORT/NEEDS_WEB/AMBIG),
     * sonst leer. So beweist der Marker im System-Prompt den Grounding-TRIGGER, ohne Bridge.
     */
    private fun groundingPort() = GroundingPort { query, category ->
        if (category == RouteCategory.FACT_SHORT ||
            category == RouteCategory.NEEDS_WEB ||
            category == RouteCategory.AMBIG
        ) {
            Mono.just("\n\n$GROUNDING_MARKER (q=$query)")
        } else {
            Mono.just("")
        }
    }

    private fun assembler(persona: PersonaService, grounding: GroundingPort) = TurnPromptAssembler(
        persona = persona,
        entityMemory = EntityContextPort { null },
        grounding = grounding,
        episodicMemory = null,
    )

    private fun orchestrator(
        persona: PersonaService,
        brain: BrainPort,
        grounding: GroundingPort,
        intent: ToolIntentClassifier,
        calculator: CalcFastpath,
    ) = TurnOrchestrator(
        routing = routingPolicy(),
        honesty = passGate(),
        promptAssembler = assembler(persona, grounding),
        persona = persona,
        formatter = ResponseFormatter(),
        brain = brain,
        intent = intent,
        calculator = calculator,
    )

    // ── Skill-Profile pro Fall ───────────────────────────────────────────────────
    private enum class Skills { NONE, CALC }

    private fun intentFor(skills: Skills): ToolIntentClassifier = when (skills) {
        Skills.NONE -> ToolIntentClassifier.DISABLED
        Skills.CALC -> DeterministicToolIntentClassifier(
            sceneCatalog = emptyList(),
            skills = SkillStatePort.ofStatic(smartHome = false, scenes = false, timer = false, calculator = true),
            calcEmbeddedEnabled = true,
        )
    }

    private fun calculatorFor(skills: Skills): CalcFastpath = when (skills) {
        Skills.NONE -> CalcFastpath.DISABLED
        Skills.CALC -> CalcFastpath()
    }

    // ── Fall-Definition (eine Zeile = ein deterministischer Turn) ────────────────
    private data class Case(
        val n: Int,
        val dim: String,
        val text: String,
        val policy: LanguagePolicy?,
        val persona: Persona = Persona.STANDARD,
        val personaEnabled: Boolean = false,
        val skills: Skills = Skills.NONE,
        val expectLang: Language,
        val expectResolvedPersona: Persona = Persona.STANDARD,
        /** Wenn gesetzt: assert `Start.category`. */
        val expectCategory: RouteCategory? = null,
        /** "policy" (Fastpath/Direkt) oder "brain" (Brain-Pfad). */
        val expectModel: String,
        val expectBrainCalls: Int,
        val expectTextContains: List<String> = emptyList(),
        val expectTextAbsent: List<String> = emptyList(),
        /** Grounding-Block muss (true) bzw. darf nicht (false) im System-Prompt stehen. Nur bei Brain-Pfad geprueft. */
        val expectGroundingInPrompt: Boolean = false,
        val expectPromptContains: List<String> = emptyList(),
        val expectPromptAbsent: List<String> = emptyList(),
        /** Erwartete an den Brain gereichte Temperatur (dynamisch, da STANDARD tageszeit-abhaengig). */
        val expectTemp: ((PersonaService) -> Double)? = null,
    )

    private val cases: List<Case> = listOf(
        // ── CalcFastpath: eingebettete + reine Mathe -> model=policy, brain-frei ──
        Case(
            1, "calc/embedded", "Wenn ich 17 mal 23 rechne, was kommt dabei heraus?",
            policy = LanguagePolicy.DE, skills = Skills.CALC, expectLang = Language.DE,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("391"),
        ),
        Case(
            2, "calc/pure", "Was ist 5 mal 3?",
            policy = LanguagePolicy.DE, skills = Skills.CALC, expectLang = Language.DE,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("5 mal 3 ist 15"),
        ),
        Case(
            3, "calc/EN", "what is 8 times 9",
            policy = LanguagePolicy.EN, skills = Skills.CALC, expectLang = Language.EN,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("8 times 9 is 72"),
        ),
        Case(
            4, "calc/div0", "Was ist 10 geteilt durch 0?",
            policy = LanguagePolicy.DE, skills = Skills.CALC, expectLang = Language.DE,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("null"),
        ),

        // ── RouteCategory: klare Smart-Home-Phrasen -> SMART_HOME (Brain-Pfad) ──
        Case(
            5, "route/smart_home", "Mach das Licht im Wohnzimmer an",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMART_HOME, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = false,
        ),
        Case(
            6, "route/smart_home", "Licht aus",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMART_HOME, expectModel = "brain", expectBrainCalls = 1,
        ),
        Case(
            7, "route/smart_home", "Schlafzimmer aus",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMART_HOME, expectModel = "brain", expectBrainCalls = 1,
        ),
        Case(
            8, "route/smart_home(comfort)", "Mir ist kalt",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMART_HOME, expectModel = "brain", expectBrainCalls = 1,
        ),

        // ── RouteCategory: Smalltalk -> SMALLTALK (kein Grounding) ──
        Case(
            9, "route/smalltalk", "Wie geht es dir?",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = false,
        ),
        Case(
            10, "route/smalltalk", "Erzähl mir einen Witz.",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = false,
        ),
        // Live-Bug-Regression 2026-07-01: persönliche Zustands-Frage wurde FACT_SHORT
        // (Token „bei" überlebte die Reduktion) → leeres Grounding → kalte
        // FactCoverage-Deflection. MUSS Smalltalk sein: kein Grounding, Brain-Pfad.
        Case(
            21, "route/smalltalk(live-bug)", "Kurz: alles ok bei dir?",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = false,
        ),

        // ── Wissensfrage -> FACT_SHORT + Grounding-Trigger (Block im Prompt) ──
        Case(
            11, "route/fact+grounding", "In welchem Jahr fiel die Berliner Mauer?",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.FACT_SHORT, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = true,
        ),
        Case(
            12, "route/fact+grounding", "Wer war Marie Curie?",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.FACT_SHORT, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = true,
        ),
        Case(
            13, "route/fact+grounding", "Wer hat die Relativitätstheorie entwickelt?",
            policy = LanguagePolicy.DE, expectLang = Language.DE,
            expectCategory = RouteCategory.FACT_SHORT, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = true,
        ),

        // ── Bilingual (AUTO): deutscher Text -> DE-Pfad, englischer -> EN-Pfad ──
        Case(
            14, "lang/AUTO->DE", "Erkläre mir kurz, wer Goethe war.",
            policy = LanguagePolicy.AUTO, expectLang = Language.DE,
            expectCategory = RouteCategory.FACT_SHORT, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = true, expectPromptContains = listOf("Antworte IMMER auf Deutsch."),
        ),
        Case(
            15, "lang/AUTO->EN", "Who painted the Mona Lisa?",
            policy = LanguagePolicy.AUTO, expectLang = Language.EN,
            expectCategory = RouteCategory.FACT_SHORT, expectModel = "brain", expectBrainCalls = 1,
            expectGroundingInPrompt = true, expectPromptContains = listOf("Always answer in English."),
        ),
        Case(
            16, "lang/AUTO->EN(calc)", "what is 8 times 9",
            policy = LanguagePolicy.AUTO, skills = Skills.CALC, expectLang = Language.EN,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("8 times 9 is 72"),
        ),
        Case(
            17, "lang/AUTO->DE(calc)", "Was ist 8 mal 9?",
            policy = LanguagePolicy.AUTO, skills = Skills.CALC, expectLang = Language.DE,
            expectModel = "policy", expectBrainCalls = 0, expectTextContains = listOf("8 mal 9 ist 72"),
        ),

        // ── Persona: Flag OFF -> STANDARD, Flag ON -> Wahl reist durch ──
        Case(
            18, "persona/OFF->STANDARD", "Wie geht es dir?",
            policy = LanguagePolicy.DE, persona = Persona.KUMPEL, personaEnabled = false,
            expectLang = Language.DE, expectResolvedPersona = Persona.STANDARD,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectPromptContains = listOf("Grundton: warm, locker, kumpelhaft"),
            expectPromptAbsent = listOf("Grundton: flapsig und spielfreudig"),
            expectTemp = { svc -> svc.temperatureFor(svc.moodFor(Persona.STANDARD)) },
        ),
        Case(
            19, "persona/ON->KUMPEL", "Wie geht es dir?",
            policy = LanguagePolicy.DE, persona = Persona.KUMPEL, personaEnabled = true,
            expectLang = Language.DE, expectResolvedPersona = Persona.KUMPEL,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectPromptContains = listOf("Grundton: flapsig und spielfreudig"),
            expectTemp = { _ -> 0.85 }, // CHEERFUL
        ),
        Case(
            20, "persona/OFF->STANDARD", "Wie geht es dir?",
            policy = LanguagePolicy.DE, persona = Persona.KNAPP, personaEnabled = false,
            expectLang = Language.DE, expectResolvedPersona = Persona.STANDARD,
            expectCategory = RouteCategory.SMALLTALK, expectModel = "brain", expectBrainCalls = 1,
            expectPromptContains = listOf("Grundton: warm, locker, kumpelhaft"),
            expectPromptAbsent = listOf("Grundton: wortkarg und sachlich"),
        ),
    )

    /** Faehrt EINEN Fall durch die echte Pipeline und prueft alle erwarteten Dimensionen. */
    private fun assertCase(c: Case) {
        val persona = PersonaService()
        val brain = ScriptedFakeBrain()
        val grounding = groundingPort()

        // ── Edge-Resolution (wie der echte Controller, VOR dem Orchestrator) ──
        val resolvedLang = languageResolver.resolve(c.policy, c.text, Language.DEFAULT)
        val resolvedPersona = PersonaResolver(personaEnabled = c.personaEnabled).resolve(c.persona)
        assertEquals(c.expectLang, resolvedLang, "Sprach-Aufloesung")
        assertEquals(c.expectResolvedPersona, resolvedPersona, "Persona-Aufloesung")

        val orchestrator = orchestrator(
            persona = persona,
            brain = brain,
            grounding = grounding,
            intent = intentFor(c.skills),
            calculator = calculatorFor(c.skills),
        )
        val events = orchestrator
            .handle(ChatRequest(text = c.text, language = resolvedLang, persona = resolvedPersona))
            .collectList()
            .block(Duration.ofSeconds(5))!!

        // ── Start-Event: Route-Kategorie + Modell-Naht ──
        val start = events.filterIsInstance<ChatEvent.Start>().firstOrNull()
        assertTrue(start != null, "Turn muss mit einem Start-Event beginnen")
        c.expectCategory?.let { assertEquals(it.name, start!!.category, "RouteCategory") }
        assertEquals(c.expectModel, start!!.model, "Modell-Naht (policy=Fastpath, brain=Brain-Pfad)")

        // ── Brain-Call-Zaehlung (Fastpath = 0, Brain-Pfad = 1) ──
        assertEquals(c.expectBrainCalls, brain.callCount.get(), "Brain-Call-Anzahl")

        // ── Antwort-Text (deterministische Quittung; NICHT der Brain-Wortlaut) ──
        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        c.expectTextContains.forEach { assertTrue(text.contains(it), "Text muss '$it' enthalten, war: $text") }
        c.expectTextAbsent.forEach { assertTrue(!text.contains(it), "Text darf '$it' NICHT enthalten, war: $text") }

        // ── System-Prompt-Naht (nur Brain-Pfad): Grounding/Sprache/Persona ──
        if (brain.callCount.get() > 0) {
            val sys = brain.lastSystemPrompt.get() ?: ""
            assertEquals(
                c.expectGroundingInPrompt, sys.contains(GROUNDING_MARKER),
                "Grounding-Block im System-Prompt (Trigger nur bei FACT/NEEDS_WEB/AMBIG)",
            )
            c.expectPromptContains.forEach { assertTrue(sys.contains(it), "System-Prompt muss '$it' enthalten") }
            c.expectPromptAbsent.forEach { assertTrue(!sys.contains(it), "System-Prompt darf '$it' NICHT enthalten") }
            c.expectTemp?.let { assertEquals(it(persona), brain.lastTemperature.get(), "an den Brain gereichte Temperatur") }
        }

        // ── Never-Silent: jeder Turn endet terminal in Done ──
        assertTrue(events.last() is ChatEvent.Done, "Turn muss mit Done enden")
    }

    // ── DER Lauf: 20 deterministische Turns durch die echte Pipeline ─────────────
    @Test
    fun `replays 20 deterministische Turns durch die echte Pipeline ohne Live-Brain`() {
        val failures = mutableListOf<String>()
        for (c in cases) {
            try {
                assertCase(c)
            } catch (e: Throwable) {
                failures += "C${c.n} [${c.dim}] \"${c.text}\": ${e.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Offline-Replay: ${failures.size}/${cases.size} Faelle ROT:\n" + failures.joinToString("\n"),
        )
    }

    // ── Selbst-Check: Abdeckung der geforderten Dimensionen ──────────────────────
    @Test
    fun `Assert-Set deckt alle geforderten Dimensionen ab`() {
        assertEquals(21, cases.size, "~20 repraesentative Turns (+1 Live-Bug-Regression 2026-07-01)")
        // Jede Dimension muss vertreten sein.
        val dims = cases.map { it.dim.substringBefore('/') }.toSet()
        listOf("calc", "route", "lang", "persona").forEach {
            assertTrue(dims.contains(it), "Dimension '$it' muss im Assert-Set sein")
        }
        // Beide Modell-Naehte beweisen.
        assertTrue(cases.any { it.expectModel == "policy" }, "mind. ein Fastpath-Fall (model=policy)")
        assertTrue(cases.any { it.expectModel == "brain" }, "mind. ein Brain-Pfad-Fall (model=brain)")
        // Beide Routen-Endpunkte + Grounding-Kontrast.
        assertTrue(cases.any { it.expectCategory == RouteCategory.SMART_HOME }, "SMART_HOME vertreten")
        assertTrue(cases.any { it.expectCategory == RouteCategory.SMALLTALK }, "SMALLTALK vertreten")
        assertTrue(cases.any { it.expectCategory == RouteCategory.FACT_SHORT }, "FACT_SHORT vertreten")
        assertTrue(cases.any { it.expectGroundingInPrompt }, "Grounding-Trigger vertreten")
        // Bilingual + Persona-Kollaps.
        assertTrue(cases.any { it.expectLang == Language.DE } && cases.any { it.expectLang == Language.EN }, "DE+EN vertreten")
        assertTrue(
            cases.any { it.persona != Persona.STANDARD && !it.personaEnabled && it.expectResolvedPersona == Persona.STANDARD },
            "Persona-Kollaps (Flag OFF -> STANDARD) vertreten",
        )
    }

    private companion object {
        /** Eindeutiger Marker des Grounding-Blocks (statt der Live-Bridge) — beweist den Trigger im Prompt. */
        const val GROUNDING_MARKER = "[[OFFLINE-GROUNDING]]"
    }
}
