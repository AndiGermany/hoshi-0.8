package de.hoshi.adapters.knowledge

import org.slf4j.LoggerFactory

/**
 * Ein knapper Bridge-Such-Treffer für die Wissens-Probe (nur die Felder, die die
 * Anti-Konfabulations-Probe braucht): Titel + Klassifikation (Themen-Coverage) +
 * BM25-Score (Treffer-Stärke). Bewusst getrennt vom Grounding-`Hit` — die Probe
 * will nur wissen, OB die Bibliothek das Thema kennt, nicht den Volltext.
 */
data class ProbeHit(
    val title: String,
    val classification: String,
    val bm25: Double,
)

/**
 * Schmale Naht „frag die Wissens-Bridge nach <query>". Real → HTTP `/search`
 * ([BridgeSearchClient]); Test → Fake. Liefert bei Bridge-tot UND echt-leer beide
 * `emptyList()` (wie der 0.5-`WikiKnowledgeSearchService.searchRemote`); die
 * Reachability-Unterscheidung macht die [BridgeKnowledgeProbe] über ihre Sanity-Probe.
 */
fun interface BridgeSearch {
    fun search(query: String, limit: Int): List<ProbeHit>
}

/**
 * **BridgeKnowledgeProbe** — die EINE generische Wissens-Probe (portiert aus Hoshi
 * 0.5 `de.hoshi.app.knowledge.KnowledgeProbe`, Kai-Design „Türsteher→Bibliothekar").
 * Beantwortet *vor* jeder Gate-Entscheidung „kennt die Bibliothek (Wiki) etwas zu
 * diesem Begriff?" — deterministisch, ein FTS-Roundtrip, kein LLM.
 *
 * Confidence (Nora-Linie): bm25-Absolutwert allein taugt nicht (ein Halbtreffer ist
 * „stärker" als mancher echte Hit). Darum zwei Signale: (a) bm25 unter dem
 * Strong-Threshold UND (b) Kopf-Token-Coverage (mind. ein inhaltlicher Token im
 * Titel/in der Klassifikation). Sonst themen-fremd → [Verdict.EMPTY].
 *
 * Reachability (B-105): `search()` liefert bei Bridge-Down UND echt-leer beide
 * `emptyList()`. Eine Sanity-Probe (fest-bekannte Entity, 30s-TTL-Cache) trennt
 * „Bridge tot" ([Verdict.BRIDGE_DOWN], Konsument darf NICHT „kennt sie nicht" werten)
 * von „echt-leer" ([Verdict.EMPTY]).
 *
 * Spring-entkoppelt (wie [Fts5GroundingAdapter]): kein `@Component`, die Bridge-Naht
 * kommt als [BridgeSearch] in den Konstruktor.
 */
class BridgeKnowledgeProbe(
    private val bridge: BridgeSearch,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * - [HIT]: Bridge kennt einen starken, themen-passenden Artikel.
     * - [EMPTY]: Bridge ist erreichbar, kennt aber nichts Passendes (echter Leertreffer).
     * - [BRIDGE_DOWN]: Bridge nicht erreichbar → Aussage unmöglich (Konsument: ehrlich
     *   „komm grad nicht an mein Wissen", nie „kenne ich nicht").
     */
    enum class Verdict { HIT, EMPTY, BRIDGE_DOWN }

    // ── Reachability-Sanity (B-105, 1:1 aus 0.5 KnowledgeProbe) ──────────────────
    @Volatile private var bridgeHealthyAt: Long = 0L
    private val bridgeHealthyTtlMs = 30_000L
    private val sanityProbe = "Albert Einstein"

    private fun bridgeIsReachable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - bridgeHealthyAt < bridgeHealthyTtlMs) return true
        return try {
            val ok = bridge.search(sanityProbe, 1).isNotEmpty()
            if (ok) bridgeHealthyAt = now
            else log.warn("[knowledge-probe] Sanity-Probe leer für '{}' — Bridge down?", sanityProbe)
            ok
        } catch (e: Exception) {
            log.warn("[knowledge-probe] Sanity-Probe wirft: {} — Bridge als down gewertet", e.message)
            false
        }
    }

    /**
     * bm25-Strong-Threshold (negativer Wert; weiter weg von 0 = besserer FTS-Match).
     * Direkt-Hit Marie Curie ~ -25, Einstein ~ -9. -3.0 ist defensiv: nur extrem
     * schwache Treffer fallen darunter.
     */
    private val bm25StrongThreshold = -3.0

    /** Führendes Zahl-Präfix eines Lemmas, z.B. „0-Euro-Schein" → „0", „2-Euro-Münze" → „2". */
    private val leadingNumberRegex = Regex("""^(\d+)""")

    /**
     * Generische Wissens-Probe. [coverageTokens] sind die inhaltlichen Kern-Tokens
     * der Anfrage (z.B. ["euro","schein"] für „12-Euro-Schein"); mindestens einer muss
     * im Treffer-Titel/-Klassifikation vorkommen (Themen-Passung), sonst EMPTY. Leere
     * coverageTokens → reine bm25-Wertung (Eigennamen-Pfad).
     *
     * [askedNumber] (Nora P1.2/T138): bei Existenz-Fragen nach einer Zahl-Entität ist
     * die gefragte Zahl von der Themen-Query abgekoppelt („12-Euro-Schein" probt „euro
     * schein"). Trägt das Treffer-Lemma ein EIGENES, ABWEICHENDES Zahl-Präfix
     * („0-Euro-Schein" für gefragte „12") → andere Entität → nicht covered → EMPTY.
     */
    fun probe(
        query: String,
        coverageTokens: List<String> = emptyList(),
        askedNumber: String? = null,
    ): Verdict {
        if (query.isBlank()) return Verdict.EMPTY
        return try {
            val hits = bridge.search(query, 1)
            if (hits.isEmpty()) {
                return if (!bridgeIsReachable()) {
                    log.info("[knowledge-probe] '{}' leer UND Sanity leer → BRIDGE_DOWN", query.take(40))
                    Verdict.BRIDGE_DOWN
                } else {
                    log.info("[knowledge-probe] '{}' leer (Bridge healthy) → EMPTY", query.take(40))
                    Verdict.EMPTY
                }
            }
            val best = hits.first()
            val strong = best.bm25 < bm25StrongThreshold
            val haystack = (best.title + " " + best.classification).lowercase()
            val tokenCovered = coverageTokens.isEmpty() ||
                coverageTokens.any { it.isNotBlank() && it.lowercase() in haystack }
            val numberPrefixMismatch = askedNumber != null &&
                leadingNumberRegex.find(best.title)?.groupValues?.get(1)
                    ?.let { it != askedNumber } == true
            val covered = tokenCovered && !numberPrefixMismatch
            val verdict = if (strong && covered) Verdict.HIT else Verdict.EMPTY
            log.info(
                "[knowledge-probe] '{}' → {} (title='{}' bm25={} strong={} covered={} numMismatch={})",
                query.take(40), verdict, best.title, "%.2f".format(best.bm25), strong, covered, numberPrefixMismatch,
            )
            verdict
        } catch (e: Exception) {
            log.warn("[knowledge-probe] '{}' wirft: {} — BRIDGE_DOWN", query.take(40), e.message)
            Verdict.BRIDGE_DOWN
        }
    }
}
