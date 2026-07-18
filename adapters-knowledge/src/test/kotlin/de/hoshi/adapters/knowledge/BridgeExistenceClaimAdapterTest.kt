package de.hoshi.adapters.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist den ECHTEN [BridgeExistenceClaimAdapter] (Zahl-Entity-Regex + Zahlwort-
 * Normalisierung + Bibliothekar-Probe) mit gefaktem [BridgeSearch] — OHNE Live-Bridge.
 * Spiegelt die 0.5-`ExistenceClaimDetectorTest`-Essenz (Andi-11-Euro-Schein-Bug).
 */
class BridgeExistenceClaimAdapterTest {

    private fun hit(title: String, classification: String, bm25: Double) = ProbeHit(title, classification, bm25)
    private val sanityHealthy = "Albert Einstein" to listOf(hit("Albert Einstein", "Physiker", -25.0))

    private fun adapter(vararg responses: Pair<String, List<ProbeHit>>): BridgeExistenceClaimAdapter {
        val map = responses.toMap()
        return BridgeExistenceClaimAdapter(BridgeKnowledgeProbe(BridgeSearch { q, _ -> map[q] ?: emptyList() }))
    }

    // ── Probe-Verdict → HonestySignal ────────────────────────────────────────

    @Test
    fun `Zahl-Entity ohne Bibliotheks-Treffer (Bridge healthy) matcht (EMPTY → ehrliche Absage)`() {
        // „euro schein" leer, Sanity healthy ⇒ EMPTY ⇒ matched, nicht bridgeDown.
        val sig = adapter(sanityHealthy).detect("Gibt es einen 11 Euro schein?")
        assertTrue(sig.matched, "11-Euro-Schein ohne Treffer soll matchen")
        assertFalse(sig.bridgeDown, "Bridge healthy ⇒ kein bridgeDown")
    }

    @Test
    fun `Zahl-Entity mit Bibliotheks-HIT passiert (NONE → Grounding-Flow)`() {
        val sig = adapter(
            "euro schein" to listOf(hit("Eurobanknoten", "Geld Zahlungsmittel", -30.0)),
            sanityHealthy,
        ).detect("Gibt es einen 11 Euro schein?")
        assertFalse(sig.matched, "Bridge kennt das Thema (HIT) ⇒ Pass an Grounding")
    }

    @Test
    fun `Zahl-Entity bei toter Bridge matcht mit bridgeDown`() {
        // alles leer (auch Sanity) ⇒ BRIDGE_DOWN ⇒ matched + bridgeDown.
        val sig = adapter().detect("Gibt es einen 11 Euro schein?")
        assertTrue(sig.matched, "tote Bridge ⇒ matched (ehrliche Absage)")
        assertTrue(sig.bridgeDown, "tote Bridge ⇒ bridgeDown=true")
    }

    // ── Zahlwort-Normalisierung (Voice-Input) ────────────────────────────────

    @Test
    fun `Voice-Zahlwort elf wird normalisiert und matcht`() {
        val sig = adapter(sanityHealthy).detect("Gibt es einen elf Euro Schein?")
        assertTrue(sig.matched, "'elf Euro Schein' (Whisper) soll wie '11' matchen")
    }

    // ── Konservativ: kein Zahl-Pattern ⇒ NONE, KEIN Bridge-Call ──────────────

    @Test
    fun `harmlose gibt-es-Frage ohne Zahl ist NONE und probt die Bridge nicht`() {
        val calls = AtomicInteger(0)
        val a = BridgeExistenceClaimAdapter(BridgeKnowledgeProbe(BridgeSearch { _, _ -> calls.incrementAndGet(); emptyList() }))
        for (q in listOf("Gibt es Honigbienen?", "Gibt es Pizza Hawaii?", "Existiert das Bermuda-Dreieck?")) {
            assertFalse(a.detect(q).matched, "'$q' soll NONE sein (kein Zahl-Pattern)")
        }
        assertEquals(0, calls.get(), "ohne Zahl-Match darf die Bridge nicht angefragt werden")
    }

    @Test
    fun `leere Query ist NONE`() {
        assertFalse(adapter().detect("").matched)
        assertFalse(adapter().detect("   ").matched)
    }

    // ── numberEntityTopic: probe-freie Regex-Sicht (Treffer / Nicht-Treffer) ──

    @Test
    fun `numberEntityTopic liefert die zahl-entkoppelte Themen-Query`() {
        val a = adapter()
        assertEquals("euro schein", a.numberEntityTopic("Gibt es einen 12 Euro Schein?"))
        assertEquals("monats-jahr", a.numberEntityTopic("Existiert ein 13-Monats-Jahr?"))
    }

    @Test
    fun `numberEntityTopic ist null ohne Zahl-Entity`() {
        val a = adapter()
        assertNull(a.numberEntityTopic("Gibt es Honigbienen?"))
        assertNull(a.numberEntityTopic("Wie spät ist es?"))
        assertNull(a.numberEntityTopic(""))
    }
}
