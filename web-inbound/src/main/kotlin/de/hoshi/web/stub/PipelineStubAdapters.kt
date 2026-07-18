package de.hoshi.web.stub

import de.hoshi.adapters.knowledge.BridgeExistenceClaimAdapter
import de.hoshi.adapters.knowledge.BridgeKnowledgeProbe
import de.hoshi.adapters.knowledge.BridgeNamedEntityAdapter
import de.hoshi.adapters.knowledge.BridgeSearchClient
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.EpisodicRecallPort
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.IntentClassifier
import de.hoshi.core.pipeline.KeywordRouter
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.RouteRefiner
import reactor.core.publisher.Mono

/**
 * **M2c-Stub-Adapter** — ehrlich benannte Minimal-Implementierungen der noch
 * offenen Ports, damit der [de.hoshi.core.pipeline.TurnOrchestrator] HEUTE
 * end-to-end mit dem ECHTEN Brain läuft. Die reichen Adapter ersetzen diese in
 * späteren Milestones (M4: Wiki-Grounding, Entity-/Episodic-Memory, echter
 * Embedding-/LLM-Refiner).
 */

/**
 * Einfache Keyword-Heuristik als [KeywordRouter] (Hop 1). Nutzt den portierten
 * [IntentClassifier]: Smart-Home-Kandidat → SMART_HOME, sonst SMALLTALK. Immer
 * LOCAL-Provider (kein Cloud in 0.8). M4 ersetzt das durch den vollen Router.
 */
class KeywordRouterStubAdapter(
    private val intent: IntentClassifier = IntentClassifier(),
) : KeywordRouter {
    override fun decide(text: String): RouteDecision {
        val category =
            if (intent.isSmartHomeCandidate(text)) RouteCategory.SMART_HOME
            else RouteCategory.SMALLTALK
        return RouteDecision(category, RouteProvider.LOCAL, "keyword-stub")
    }
}

/**
 * Passthrough-[RouteRefiner]: reicht die Hop-1-Decision unverändert zurück (kein
 * Embedding-/LLM-Refine in 0.8). M4 ersetzt das durch Embedding-/LLM-Refiner.
 */
class PassthroughRefinerStubAdapter : RouteRefiner {
    override fun refine(text: String, fallback: RouteDecision): Mono<RouteDecision> = Mono.just(fallback)
}

/** [EntityContextPort]-Stub: kein Gedächtnis-Block (Memory-Infra kommt in M4). */
class EntityContextStubAdapter : EntityContextPort {
    override fun contextBlock(speakerId: String): String? = null
}

/** [GroundingPort]-Stub: kein Wiki-Treffer (Wiki-RAG-Bridge kommt in M4). */
class GroundingStubAdapter : GroundingPort {
    override fun groundingBlock(query: String, category: RouteCategory): Mono<String> = Mono.just("")
}

/**
 * [EpisodicRecallPort]-Stub: kein Gesprächskontext-Recall (`""`). Verhaltens-
 * neutraler Default, solange `HOSHI_EPISODIC_ENABLED=false` — identisch zum
 * bisherigen `episodicMemory = null`: der Assembler schichtet keinen Episodic-Block.
 */
class EpisodicRecallStubAdapter : EpisodicRecallPort {
    override fun recallBlock(speakerId: String, text: String): Mono<String> = Mono.just("")
}

/** [ExistenceClaimSignal]-Stub: konservativ — nie matched (probt sonst die Bridge, M4). */
class ExistenceClaimStubAdapter : ExistenceClaimSignal {
    override fun detect(text: String): HonestySignal = HonestySignal.NONE
}

/** [NamedEntitySignal]-Stub: konservativ — nie matched (probt sonst die Wiki-Bridge, M4). */
class NamedEntityStubAdapter : NamedEntitySignal {
    override fun detect(text: String): HonestySignal = HonestySignal.NONE
}

/**
 * **HonestyGate-Probe-Umschaltung — flag-gated, default OFF** (`HOSHI_HONESTY_PROBE_ENABLED`).
 *
 * Bei OFF (Default) die verhaltens-neutralen [ExistenceClaimStubAdapter]/
 * [NamedEntityStubAdapter] (immer [HonestySignal.NONE]) — EXAKT das heutige Verhalten,
 * byte-neutral. Bei ON die ECHTEN Bridge-Probe-Adapter ([BridgeExistenceClaimAdapter]/
 * [BridgeNamedEntityAdapter], Anti-Konfabulation gegen die Knowledge-Bridge `/search`):
 * Zahl-Entity-Existenzfragen + unbekannte Eigennamen werden geprobt → HIT (Pass an
 * Grounding) / EMPTY (ehrliche Absage) / BRIDGE_DOWN (Wissensspeicher nicht erreichbar).
 *
 * Beide Signale teilen sich EINE [BridgeKnowledgeProbe]-Instanz (gemeinsamer
 * Reachability-Sanity-Cache, ein Bridge-Client). Wird vom `honestyGate`-Bean in
 * [de.hoshi.web.PipelineConfig] konsumiert.
 */
object HonestyProbeAdapters {
    fun signals(enabled: Boolean, bridgeBaseUrl: String): Pair<ExistenceClaimSignal, NamedEntitySignal> =
        if (!enabled) {
            ExistenceClaimStubAdapter() to NamedEntityStubAdapter()
        } else {
            val probe = BridgeKnowledgeProbe(BridgeSearchClient(bridgeBaseUrl))
            BridgeExistenceClaimAdapter(probe) to BridgeNamedEntityAdapter(probe)
        }
}
