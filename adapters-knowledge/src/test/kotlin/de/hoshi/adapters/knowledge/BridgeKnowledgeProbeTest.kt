package de.hoshi.adapters.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Beweist die [BridgeKnowledgeProbe]-Verdict-Logik (HIT/EMPTY/BRIDGE_DOWN) mit einem
 * gefakten [BridgeSearch] — OHNE Live-Bridge. Spiegelt die 0.5-`KnowledgeProbeTest`-
 * Essenz: bm25-Strong + Coverage + Reachability-Sanity + Zahl-Präfix-Mismatch.
 */
class BridgeKnowledgeProbeTest {

    private fun hit(title: String, classification: String, bm25: Double) = ProbeHit(title, classification, bm25)

    /** Fake-Bridge: Map Query→Hits; alles Unbekannte ⇒ leer. „Albert Einstein" = Sanity-Anker. */
    private fun bridge(vararg responses: Pair<String, List<ProbeHit>>): BridgeSearch {
        val map = responses.toMap()
        return BridgeSearch { q, _ -> map[q] ?: emptyList() }
    }

    private val sanityHealthy = "Albert Einstein" to listOf(hit("Albert Einstein", "Physiker", -25.0))

    @Test
    fun `starker themen-passender Treffer ist HIT`() {
        val probe = BridgeKnowledgeProbe(
            bridge("euro schein" to listOf(hit("Eurobanknoten", "Geld Zahlungsmittel", -30.0))),
        )
        assertEquals(
            BridgeKnowledgeProbe.Verdict.HIT,
            probe.probe("euro schein", coverageTokens = listOf("euro", "schein"), askedNumber = "12"),
        )
    }

    @Test
    fun `schwacher bm25-Treffer ist EMPTY`() {
        val probe = BridgeKnowledgeProbe(
            bridge("euro schein" to listOf(hit("Eurobanknoten", "Geld", -1.0))), // > -3.0 ⇒ schwach
        )
        assertEquals(
            BridgeKnowledgeProbe.Verdict.EMPTY,
            probe.probe("euro schein", coverageTokens = listOf("euro", "schein")),
        )
    }

    @Test
    fun `themen-fremder Treffer (keine Coverage) ist EMPTY`() {
        val probe = BridgeKnowledgeProbe(
            bridge("euro schein" to listOf(hit("Schein (Philosophie)", "Erkenntnistheorie", -40.0))),
        )
        // Kopf-Token „euro" steckt nicht in „schein philosophie erkenntnistheorie" ⇒ EMPTY.
        assertEquals(
            BridgeKnowledgeProbe.Verdict.EMPTY,
            probe.probe("euro schein", coverageTokens = listOf("euro")),
        )
    }

    @Test
    fun `Zahl-Praefix-Mismatch verwirft den Treffer (EMPTY)`() {
        val probe = BridgeKnowledgeProbe(
            bridge("euro schein" to listOf(hit("0-Euro-Schein", "Souvenir Geld", -50.0))),
        )
        // Gefragt „12", Treffer-Lemma trägt „0" ⇒ andere Entität ⇒ EMPTY (nicht HIT).
        assertEquals(
            BridgeKnowledgeProbe.Verdict.EMPTY,
            probe.probe("euro schein", coverageTokens = listOf("euro", "schein"), askedNumber = "12"),
        )
    }

    @Test
    fun `leere Trefferliste bei gesunder Bridge ist EMPTY`() {
        val probe = BridgeKnowledgeProbe(bridge(sanityHealthy)) // Such-Query leer, Sanity healthy
        assertEquals(BridgeKnowledgeProbe.Verdict.EMPTY, probe.probe("neelix"))
    }

    @Test
    fun `leere Trefferliste UND leere Sanity-Probe ist BRIDGE_DOWN`() {
        val probe = BridgeKnowledgeProbe(bridge()) // alles leer, auch „Albert Einstein"
        assertEquals(BridgeKnowledgeProbe.Verdict.BRIDGE_DOWN, probe.probe("neelix"))
    }

    @Test
    fun `werfender Bridge-Client ist BRIDGE_DOWN, nie Crash`() {
        val probe = BridgeKnowledgeProbe(BridgeSearch { _, _ -> throw RuntimeException("connection refused") })
        assertEquals(BridgeKnowledgeProbe.Verdict.BRIDGE_DOWN, probe.probe("neelix"))
    }

    @Test
    fun `leere coverageTokens werten rein nach bm25 (Eigennamen-Pfad)`() {
        val probe = BridgeKnowledgeProbe(
            bridge("Marie Curie" to listOf(hit("Marie Curie", "Physikerin Chemikerin", -25.0))),
        )
        assertEquals(BridgeKnowledgeProbe.Verdict.HIT, probe.probe("Marie Curie"))
    }
}
