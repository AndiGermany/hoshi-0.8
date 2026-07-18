package de.hoshi.adapters.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist den ECHTEN [BridgeNamedEntityAdapter] (Eigenname-Trigger + Common-Wort-
 * Schutz + Bridge-Probe) mit gefaktem [BridgeSearch] — OHNE Live-Bridge. Spiegelt die
 * 0.5-`EntityClaimDetectorTest`-Essenz (Andi-Neelix-Bug, Marie-Curie-Reachability).
 */
class BridgeNamedEntityAdapterTest {

    private fun hit(title: String, classification: String, bm25: Double) = ProbeHit(title, classification, bm25)
    private val sanityHealthy = "Albert Einstein" to listOf(hit("Albert Einstein", "Physiker", -25.0))

    private fun adapter(vararg responses: Pair<String, List<ProbeHit>>): BridgeNamedEntityAdapter {
        val map = responses.toMap()
        return BridgeNamedEntityAdapter(BridgeKnowledgeProbe(BridgeSearch { q, _ -> map[q] ?: emptyList() }))
    }

    @Test
    fun `unbekannter Eigenname (Bridge healthy) matcht (EMPTY → warmer Refuse)`() {
        // „Neelix" leer, Sanity healthy ⇒ EMPTY ⇒ matched, nicht bridgeDown.
        val sig = adapter(sanityHealthy).detect("Wer ist Neelix?")
        assertTrue(sig.matched, "unbekannter Eigenname soll matchen")
        assertFalse(sig.bridgeDown, "Bridge healthy ⇒ kein bridgeDown")
    }

    @Test
    fun `bekannter Eigenname (Bridge-HIT) passiert (NONE → Wiki-Pfad)`() {
        val sig = adapter(
            "Albert Einstein" to listOf(hit("Albert Einstein", "Physiker", -25.0)),
        ).detect("Wer war Albert Einstein?")
        assertFalse(sig.matched, "Bridge kennt ihn (HIT) ⇒ Pass an Wiki-Grounding")
    }

    @Test
    fun `unbekannter Eigenname bei toter Bridge matcht mit bridgeDown`() {
        val sig = adapter().detect("Wer ist Marie Curie?") // alles leer ⇒ BRIDGE_DOWN
        assertTrue(sig.matched, "tote Bridge ⇒ matched")
        assertTrue(sig.bridgeDown, "tote Bridge ⇒ bridgeDown=true (nicht 'kenne ich nicht')")
    }

    @Test
    fun `Common-Wort mein Bruder ist NONE und probt nicht`() {
        val calls = AtomicInteger(0)
        val a = BridgeNamedEntityAdapter(BridgeKnowledgeProbe(BridgeSearch { _, _ -> calls.incrementAndGet(); emptyList() }))
        assertFalse(a.detect("Wer ist mein Bruder?").matched)
        assertFalse(a.detect("Kennst du Honigbienen?").matched)
        assertEquals(0, calls.get(), "Common-Wörter dürfen die Bridge nicht anfragen")
    }

    @Test
    fun `kein Trigger-Pattern ist NONE`() {
        val a = adapter(sanityHealthy)
        assertFalse(a.detect("Erzähl mir was über Saturn.").matched)
        assertFalse(a.detect("").matched)
    }
}
