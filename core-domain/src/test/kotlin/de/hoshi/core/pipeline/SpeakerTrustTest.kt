package de.hoshi.core.pipeline

import de.hoshi.core.dto.SpeakerContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **SpeakerTrust.resolve** — die zentrale P1-Vertrauens-Entscheidung, isoliert getestet
 * (kein Reactor, keine Adapter, keine Spring-Beans). Deckt exakt die 4 Auftrags-Szenarien:
 *  (a) enforced=OFF ⇒ byte-neutraler Pass-Through (Claim UNABHÄNGIG vom Score).
 *  (b) enforced=ON + Score unter Schwelle ⇒ Gast (kein Zugriff unter der fremden Claim-Id).
 *  (c) enforced=ON + Score über/auf der Schwelle ⇒ Claim vertraut.
 *  (d) enforced=ON + kein Kontext ⇒ Gast, kein Crash (keine NPE).
 */
class SpeakerTrustTest {

    private val threshold = 0.45

    // ── (a) enforced=OFF: byte-neutraler Pass-Through ────────────────────────

    @Test
    fun `enforced OFF nutzt den behaupteten Claim unabhaengig vom Score`() {
        val lowScoreClaim = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0)

        val result = SpeakerTrust.resolve(lowScoreClaim, enforced = false, threshold = threshold)

        assertEquals(SpeakerTrust.TrustedSpeaker("andi", trusted = true), result)
    }

    @Test
    fun `enforced OFF ohne Kontext liefert null, damit der Aufrufer seinen Kurzschluss behaelt`() {
        val result = SpeakerTrust.resolve(null, enforced = false, threshold = threshold)

        assertNull(result, "kein Kontext + Gate OFF ⇒ null (byte-neutral zum bisherigen `?:`-Fallback)")
    }

    // ── (b) enforced=ON + Score unter Schwelle + fremde Id ⇒ Gast ────────────

    @Test
    fun `enforced ON mit Score unter Schwelle faellt auf Gast zurueck`() {
        val unsichererClaim = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.20)

        val result = SpeakerTrust.resolve(unsichererClaim, enforced = true, threshold = threshold)

        assertEquals(SpeakerTrust.GUEST, result)
        assertEquals("gast", result?.speakerId)
        assertFalse(result?.trusted ?: true, "ein unsicherer Claim darf nie als vertraut gelten")
    }

    @Test
    fun `enforced ON mit Score knapp unter der Schwelle faellt auf Gast zurueck`() {
        val result = SpeakerTrust.resolve(
            SpeakerContext(speakerId = "andi", score = threshold - 0.001),
            enforced = true,
            threshold = threshold,
        )

        assertEquals(SpeakerTrust.GUEST, result)
    }

    @Test
    fun `ein fremder Traeger der Andis Id behauptet wird bei Default-Score NICHT vertraut`() {
        // score=0.0 ist der SpeakerContext-Default — exakt das, was ein roher, unbewiesener
        // FE-/API-Claim (kein CAM++-Treffer) mitschickt.
        val fremderClaim = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.0)

        val result = SpeakerTrust.resolve(fremderClaim, enforced = true, threshold = threshold)

        assertEquals("gast", result?.speakerId, "der Claim 'andi' darf NIE ungeprueft durchgereicht werden")
    }

    // ── (c) enforced=ON + Score über/auf der Schwelle ⇒ Claim vertraut ───────

    @Test
    fun `enforced ON mit Score ueber der Schwelle vertraut dem Claim`() {
        val sichererClaim = SpeakerContext(speakerId = "andi", displayName = "Andi", score = 0.90)

        val result = SpeakerTrust.resolve(sichererClaim, enforced = true, threshold = threshold)

        assertEquals(SpeakerTrust.TrustedSpeaker("andi", trusted = true), result)
    }

    @Test
    fun `enforced ON mit Score GENAU auf der Schwelle vertraut dem Claim (inklusive Grenze)`() {
        val result = SpeakerTrust.resolve(
            SpeakerContext(speakerId = "andi", score = threshold),
            enforced = true,
            threshold = threshold,
        )

        assertEquals(SpeakerTrust.TrustedSpeaker("andi", trusted = true), result)
    }

    // ── (d) enforced=ON + kein Kontext ⇒ Gast, kein Crash ─────────────────────

    @Test
    fun `enforced ON ohne Kontext liefert Gast ohne zu crashen`() {
        val result = SpeakerTrust.resolve(null, enforced = true, threshold = threshold)

        assertEquals(SpeakerTrust.GUEST, result)
    }
}
