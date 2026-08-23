package de.hoshi.web.stub

import de.hoshi.adapters.knowledge.BridgeExistenceClaimAdapter
import de.hoshi.adapters.knowledge.BridgeKnowledgeProbe
import de.hoshi.adapters.knowledge.BridgeNamedEntityAdapter
import de.hoshi.adapters.knowledge.BridgeSearchClient
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.EpisodicRecallPort
import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.GroundingPort
import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.NamedEntitySignal
import de.hoshi.core.pipeline.RouteRefiner
import reactor.core.publisher.Mono

/**
 * **M2c-Stub-Adapter** — ehrlich benannte Minimal-Implementierungen der noch
 * offenen Ports, damit der [de.hoshi.core.pipeline.TurnOrchestrator] HEUTE
 * end-to-end mit dem ECHTEN Brain läuft. Die reichen Adapter (Wiki-Grounding,
 * Entity-/Episodic-Memory, Embedding-Refiner) existieren inzwischen bereits —
 * s. [de.hoshi.web.PipelineConfig] — sind aber alle flag-gated default OFF, darum
 * bleiben diese Stubs der AKTIVE Default-Pfad, kein Zukunftsversprechen. Ein
 * echter LLM-Refiner wurde nie gebaut: `llmRefiner` bleibt unconditional
 * [PassthroughRefinerStubAdapter]. Der frühere Keyword-Router-Stub ist seit 0.9
 * gelöscht — der ECHTE Router ([de.hoshi.web.routing.KeywordRouterImpl]) läuft
 * seit M4 produktiv.
 */

/**
 * Passthrough-[RouteRefiner]: reicht die Hop-1-Decision unverändert zurück. Bedient
 * IMMER den `llmRefiner` (kein echter LLM-Refiner existiert) und — bei
 * `HOSHI_EMBEDDING_ROUTER=false` (Default) — den `embeddingRefiner`; der echte
 * [de.hoshi.adapters.routing.EmbeddingRouterRefiner] existiert zwar bereits
 * (flag-gated), bleibt aber laut [de.hoshi.web.PipelineConfig.routingPolicy]-KDoc
 * „dormant by design", weil [de.hoshi.web.routing.KeywordRouterImpl] nie AMBIG liefert.
 */
class PassthroughRefinerStubAdapter : RouteRefiner {
    override fun refine(text: String, fallback: RouteDecision): Mono<RouteDecision> = Mono.just(fallback)
}

/**
 * [EntityContextPort]-Stub: kein Gedächtnis-Block. Der echte
 * [de.hoshi.adapters.memory.EntityMemoryAdapter] existiert bereits (flag-gated
 * `HOSHI_MEMORY_ENABLED`, default OFF) — dieser Stub ist der Default-Pfad, kein
 * Zukunftsversprechen.
 * [language] ist gegenstandslos — ein fehlender Block hat keine Sprache.
 */
class EntityContextStubAdapter : EntityContextPort {
    override fun contextBlock(speakerId: String, language: Language): String? = null
}

/**
 * [GroundingPort]-Stub: kein Wiki-Treffer. Der echte
 * [de.hoshi.adapters.knowledge.Fts5GroundingAdapter] existiert bereits (flag-gated
 * `HOSHI_GROUNDING_ENABLED`, default OFF) — dieser Stub ist der Default-Pfad, kein
 * Zukunftsversprechen.
 * [language] ist gegenstandslos — ein leerer Block hat keine Sprache.
 */
class GroundingStubAdapter : GroundingPort {
    override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
        Mono.just("")
}

/**
 * [EpisodicRecallPort]-Stub: kein Gesprächskontext-Recall (`""`). Verhaltens-
 * neutraler Default, solange `HOSHI_EPISODIC_ENABLED=false` — identisch zum
 * bisherigen `episodicMemory = null`: der Assembler schichtet keinen Episodic-Block.
 * [language] ist gegenstandslos — ein leerer Block hat keine Sprache.
 */
class EpisodicRecallStubAdapter : EpisodicRecallPort {
    override fun recallBlock(speakerId: String, text: String, language: Language): Mono<String> = Mono.just("")
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
    /**
     * [sidecarToken] reicht die opt-in Sidecar-Token-Wand an den [BridgeSearchClient]
     * durch (Default `""` ⇒ byte-neutral, s. `de.hoshi.core.security.SidecarTokenHeader`-KDoc).
     */
    fun signals(
        enabled: Boolean,
        bridgeBaseUrl: String,
        sidecarToken: String = "",
    ): Pair<ExistenceClaimSignal, NamedEntitySignal> =
        if (!enabled) {
            ExistenceClaimStubAdapter() to NamedEntityStubAdapter()
        } else {
            val probe = BridgeKnowledgeProbe(BridgeSearchClient(bridgeBaseUrl, token = sidecarToken))
            BridgeExistenceClaimAdapter(probe) to BridgeNamedEntityAdapter(probe)
        }
}
