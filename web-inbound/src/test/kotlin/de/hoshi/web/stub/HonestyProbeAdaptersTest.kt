package de.hoshi.web.stub

import de.hoshi.adapters.knowledge.BridgeExistenceClaimAdapter
import de.hoshi.adapters.knowledge.BridgeNamedEntityAdapter
import de.hoshi.core.pipeline.HonestySignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die [HonestyProbeAdapters]-Umschaltung (`HOSHI_HONESTY_PROBE_ENABLED`):
 * OFF = verhaltens-neutrale Stubs (byte-identisch heute), ON = echte Bridge-Probe-
 * Adapter. Reine Konstruktor-Verdrahtung — kein Spring-Context.
 */
class HonestyProbeAdaptersTest {

    private val bridge = "http://localhost:8035"

    @Test
    fun `OFF liefert inerte Stubs — selbst eine Gate-Triggerfrage bleibt NONE`() {
        val (existence, named) = HonestyProbeAdapters.signals(enabled = false, bridgeBaseUrl = bridge)
        assertInstanceOf(ExistenceClaimStubAdapter::class.java, existence, "OFF ⇒ Existence-Stub")
        assertInstanceOf(NamedEntityStubAdapter::class.java, named, "OFF ⇒ NamedEntity-Stub")
        // Inert: auch eine klare Trigger-Frage probt nichts und matcht nie.
        assertEquals(HonestySignal.NONE, existence.detect("Gibt es einen 11 Euro Schein?"), "OFF ⇒ Existence inert")
        assertEquals(HonestySignal.NONE, named.detect("Wer ist Neelix?"), "OFF ⇒ NamedEntity inert")
    }

    @Test
    fun `ON verdrahtet die echten Bridge-Probe-Adapter`() {
        val (existence, named) = HonestyProbeAdapters.signals(enabled = true, bridgeBaseUrl = bridge)
        assertInstanceOf(BridgeExistenceClaimAdapter::class.java, existence, "ON ⇒ echte Existence-Probe")
        assertInstanceOf(BridgeNamedEntityAdapter::class.java, named, "ON ⇒ echte NamedEntity-Probe")
    }

    @Test
    fun `ON ist best-effort — tote Bridge wirft nie, sondern ehrlich bridgeDown`() {
        // Toter Port (connection refused) ⇒ Probe + Sanity beide leer ⇒ BRIDGE_DOWN.
        val (existence, _) = HonestyProbeAdapters.signals(enabled = true, bridgeBaseUrl = "http://127.0.0.1:1")
        val sig = existence.detect("Gibt es einen 11 Euro Schein?")
        assertTrue(sig.matched, "Zahl-Entity-Match bleibt — Gate greift")
        assertTrue(sig.bridgeDown, "tote Bridge ⇒ bridgeDown (ehrlich 'nicht erreichbar', kein Crash)")
    }
}
