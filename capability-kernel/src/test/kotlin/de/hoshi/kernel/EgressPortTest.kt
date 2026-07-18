package de.hoshi.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EgressPortTest {

    // ── Helfer ──────────────────────────────────────────────────────────────

    private fun blockedCategory(decision: EgressDecision): BlockCategory {
        assertTrue(decision is EgressDecision.Blocked, "erwartet Blocked, war: $decision")
        return (decision as EgressDecision.Blocked).category
    }

    @Test
    fun `Name und haBaseUrl werden beide maskiert`() {
        val port = EgressPort(speakerName = "Andi", haBaseUrl = "http://192.168.178.106:8123")
        val payload = "Andi fragt, ob das Haus unter http://192.168.178.106:8123 erreichbar ist."

        val result = port.sanitize(payload)

        // Klartext darf das System NICHT verlassen.
        assertFalse(result.sanitizedText.contains("Andi"), "Name leakt: ${result.sanitizedText}")
        assertFalse(result.sanitizedText.contains("192.168.178.106"), "haBaseUrl leakt: ${result.sanitizedText}")
        // Maskierungs-Token sind drin.
        assertTrue(result.sanitizedText.contains("[NAME_1]"), result.sanitizedText)
        assertTrue(result.sanitizedText.contains("[HA_URL_1]"), result.sanitizedText)
        // Audit-Map bildet Token → Original ab (rein lokal).
        assertTrue(result.redactions.containsValue("Andi"))
        assertTrue(result.redactions.containsValue("http://192.168.178.106:8123"))
    }

    @Test
    fun `generische URL und Email werden maskiert`() {
        val port = EgressPort()
        val result = port.sanitize("Mehr unter https://example.com/x oder a@b.de.")

        assertFalse(result.sanitizedText.contains("example.com"), result.sanitizedText)
        assertFalse(result.sanitizedText.contains("a@b.de"), result.sanitizedText)
        assertTrue(result.redactionCount >= 2)
    }

    @Test
    fun `leere Payload bleibt unveraendert`() {
        val result = EgressPort(speakerName = "Andi").sanitize("")
        assertTrue(result.sanitizedText.isEmpty())
        assertTrue(result.redactions.isEmpty())
    }

    // ── reconstruct: macht sanitize rueckgaengig (Cloud-Antwort → User) ──────

    @Test
    fun `reconstruct macht sanitize rueckgaengig (round-trip)`() {
        val port = EgressPort(speakerName = "Andi", haBaseUrl = "http://192.168.178.106:8123")
        val original = "Andi fragt, ob das Haus unter http://192.168.178.106:8123 erreichbar ist."

        val sanitized = port.sanitize(original)
        // Sicherstellen, dass ueberhaupt maskiert wurde.
        assertTrue(sanitized.redactionCount >= 2, sanitized.sanitizedText)

        val restored = port.reconstruct(sanitized.sanitizedText, sanitized.redactions)
        assertEquals(original, restored, "round-trip muss die maskierten Spans exakt zuruckholen")
    }

    @Test
    fun `reconstruct laesst unbekanntes Token unveraendert und wirft nie`() {
        val port = EgressPort()
        // [NAME_9] ist NICHT in der Map (Cloud-Halluzination) → bleibt stehen.
        val out = port.reconstruct("Hallo [NAME_9] und [NAME_1].", mapOf("[NAME_1]" to "Andi"))

        assertTrue(out.contains("[NAME_9]"), "unbekanntes Token muss stehen bleiben: $out")
        assertTrue(out.contains("Andi"), out)
        assertFalse(out.contains("[NAME_1]"), out)
    }

    @Test
    fun `reconstruct matcht Token case-insensitiv (Cloud-Lowercasing)`() {
        val port = EgressPort()
        val out = port.reconstruct("Antwort: [name_1]", mapOf("[NAME_1]" to "Andi"))
        assertEquals("Antwort: Andi", out)
    }

    @Test
    fun `reconstruct mit leerer Map ist no-op`() {
        assertEquals("nichts zu tun", EgressPort().reconstruct("nichts zu tun", emptyMap()))
    }

    // ── guard: Hard-Block feuert (Egress KOMPLETT blocken) ──────────────────

    @Test
    fun `guard blockt Memory-Referenz`() {
        val decision = EgressPort().guard("Erinnerst du dich an gestern Abend?")
        assertEquals(BlockCategory.MEMORY_REFERENCE, blockedCategory(decision))
    }

    @Test
    fun `guard blockt Sprecher-ID`() {
        val decision = EgressPort().guard("Sprecher spk-19f5da56-37d0-4af3-ad56-c1600d9ca7b1 hat gefragt.")
        assertEquals(BlockCategory.SPEAKER_ID, blockedCategory(decision))
    }

    @Test
    fun `guard blockt internen Token (JWT)`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        val decision = EgressPort().guard("Mein HA-Token ist $jwt bitte merken.")
        assertEquals(BlockCategory.INTERNAL_TOKEN, blockedCategory(decision))
    }

    @Test
    fun `guard blockt konfigurierte HA-Base-URL`() {
        // Hostname-basiert, damit die Kategorie eindeutig HA_BASE_URL ist
        // (eine IP-basierte HA-URL traegt zusaetzlich eine LAN-IP).
        val port = EgressPort(haBaseUrl = "http://homeassistant.local:8123")
        val decision = port.guard("Frag mal http://homeassistant.local:8123 nach dem Status.")
        assertEquals(BlockCategory.HA_BASE_URL, blockedCategory(decision))
    }

    @Test
    fun `guard blockt private LAN-IP`() {
        val decision = EgressPort().guard("Der Server laeuft auf 192.168.178.106 im Heimnetz.")
        assertEquals(BlockCategory.LAN_IP, blockedCategory(decision))
    }

    // ── guard: harmlose Payload → Allowed (ggf. maskiert) ───────────────────

    @Test
    fun `guard laesst harmlose Payload durch (Allowed)`() {
        val decision = EgressPort(speakerName = "Andi").guard("Wie warm wird es morgen draussen?")

        assertTrue(decision.isAllowed, "harmlose Payload muss durch: $decision")
        assertFalse(decision.isBlocked)
        val payload = (decision as EgressDecision.Allowed).payload
        assertEquals("Wie warm wird es morgen draussen?", payload.sanitizedText)
    }

    @Test
    fun `guard erlaubt aber maskiert (Allowed-Pfad nutzt sanitize)`() {
        val port = EgressPort()
        val decision = port.guard("Schreib eine Mail an gast@example.org bitte.")

        assertTrue(decision.isAllowed, decision.toString())
        val payload = (decision as EgressDecision.Allowed).payload
        assertFalse(payload.sanitizedText.contains("gast@example.org"), payload.sanitizedText)
        assertTrue(payload.redactionCount >= 1)
        // Allowed-Pfad ist byte-identisch zu direktem sanitize (kein Sonderweg).
        assertEquals(
            port.sanitize("Schreib eine Mail an gast@example.org bitte.").sanitizedText,
            payload.sanitizedText,
        )
    }
}
