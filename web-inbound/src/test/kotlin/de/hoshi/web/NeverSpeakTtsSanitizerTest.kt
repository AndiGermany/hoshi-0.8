package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den [NeverSpeakTtsSanitizer]: die Never-Speak-Spans (Token/URL/IP/UUID/
 * HA-Entity-ID) werden maskiert, der NAME und normaler Inhalt BLEIBEN — das ist
 * der P0-Leak-Riegel vor dem Cloud-TTS-Egress. Pure, keine Infra.
 */
class NeverSpeakTtsSanitizerTest {

    private val sanitizer = NeverSpeakTtsSanitizer()

    @Test
    fun `Satz mit Token URL IP UUID und Entity-ID plus NAME — Spans maskiert, Name bleibt`() {
        val input = "Andi, dein Key sk-ABCDEFGHIJKLMNOP1234 oeffnet https://ha.local/api, " +
            "HA laeuft auf 192.168.178.56, session 123e4567-e89b-12d3-a456-426614174000, " +
            "schalte light.wohnzimmer ein."
        val out = sanitizer.sanitizeForSpeech(input)

        // Name + normaler Inhalt bleiben (warmes Audio, kein guard/Stummschalten).
        assertTrue(out.contains("Andi"), "Name muss erhalten bleiben: $out")
        assertTrue(out.contains("schalte"), "normaler Inhalt muss bleiben: $out")

        // Never-Speak-Spans sind weg.
        assertFalse(out.contains("sk-ABCDEFGHIJKLMNOP1234"), "Token-Leak: $out")
        assertFalse(out.contains("https://ha.local/api"), "URL-Leak: $out")
        assertFalse(out.contains("192.168.178.56"), "IP-Leak: $out")
        assertFalse(out.contains("123e4567-e89b-12d3-a456-426614174000"), "UUID-Leak: $out")
        assertFalse(out.contains("light.wohnzimmer"), "Entity-ID-Leak: $out")

        // ...und durch Masken ersetzt.
        assertTrue(out.contains("[TOKEN]"), "Token-Maske fehlt: $out")
        assertTrue(out.contains("[URL]"), "URL-Maske fehlt: $out")
        assertTrue(out.contains("[IP]"), "IP-Maske fehlt: $out")
        assertTrue(out.contains("[ID]"), "ID-Maske (UUID/Entity) fehlt: $out")
    }

    @Test
    fun `Bearer-Header und JWT und langer Hex-Secret werden maskiert`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        assertFalse(sanitizer.sanitizeForSpeech("token $jwt fertig").contains(jwt), "JWT-Leak")
        assertFalse(
            sanitizer.sanitizeForSpeech("Authorization Bearer abcDEF1234567890xyz next").contains("abcDEF1234567890xyz"),
            "Bearer-Leak",
        )
        assertFalse(
            sanitizer.sanitizeForSpeech("hash 0123456789abcdef0123456789abcdef done").contains("0123456789abcdef0123456789abcdef"),
            "Hex-Secret-Leak",
        )
    }

    @Test
    fun `harmloser Satz bleibt komplett unveraendert`() {
        val warm = "Klar, Andi — ich mach das Licht im Wohnzimmer gleich an. Schoenen Abend dir."
        assertEquals(warm, sanitizer.sanitizeForSpeech(warm), "harmloser Satz darf NICHT maskiert werden")
    }

    @Test
    fun `leerer Text bleibt leer`() {
        assertEquals("", sanitizer.sanitizeForSpeech(""))
        assertEquals("   ", sanitizer.sanitizeForSpeech("   "))
    }
}
