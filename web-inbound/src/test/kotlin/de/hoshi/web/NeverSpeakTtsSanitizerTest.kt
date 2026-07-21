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
        assertFalse(out.contains("http"), "URL wird fuer Sprache ENTFERNT: $out")
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

/**
 * Markdown-Quellenangaben werden NIE gesprochen (Andi-Befund 21.07, wörtlich:
 * „wenn du es jetzt noch schaffst, dass er das nicht liest, sondern nur die
 * Referenz selbst"). Der ANGEZEIGTE Text behält die Quelle — hier geht es
 * ausschließlich um das, was aus dem Lautsprecher kommt.
 */
class NeverSpeakTtsSanitizerCitationTest {

    private val sanitizer = NeverSpeakTtsSanitizer()

    @org.junit.jupiter.api.Test
    fun `geklammerte Markdown-Quelle am Satzende verschwindet komplett`() {
        val spoken = sanitizer.sanitizeForSpeech(
            "Ein Ticket kostet 23,50 Euro. " +
                "([toureiffel.paris](https://www.toureiffel.paris/en/rates?utm_source=openai))",
        )
        org.junit.jupiter.api.Assertions.assertEquals("Ein Ticket kostet 23,50 Euro.", spoken)
    }

    @org.junit.jupiter.api.Test
    fun `Markdown-Quelle mitten im Satz hinterlaesst keine Luecke vor dem Punkt`() {
        val spoken = sanitizer.sanitizeForSpeech(
            "Laut [rockstargames.com](https://www.rockstargames.com/VI) erscheint es im November.",
        )
        org.junit.jupiter.api.Assertions.assertEquals("Laut erscheint es im November.", spoken)
    }

    @org.junit.jupiter.api.Test
    fun `nackte URL wird fuer Sprache entfernt - nichts Vorlesbares bleibt uebrig`() {
        val spoken = sanitizer.sanitizeForSpeech("Schau auf https://example.com/geheim nach.")
        org.junit.jupiter.api.Assertions.assertFalse(spoken.contains("http"), spoken)
        org.junit.jupiter.api.Assertions.assertFalse(spoken.contains("example.com"), spoken)
    }

    @org.junit.jupiter.api.Test
    fun `Quellen-Schwanz mit doppeltem Label verschwindet ganz`() {
        // Andi-Repro 21.07 woertlich — das Label kommt real doppelt und zweisprachig.
        val spoken = sanitizer.sanitizeForSpeech(
            "GTA 6 erscheint am 19. November 2026. " +
                "Source: Quellen: https://www.rockstargames.com/newswire/article/ak3ak31a49a221/x.",
        )
        org.junit.jupiter.api.Assertions.assertEquals("GTA 6 erscheint am 19. November 2026.", spoken)
    }

    @org.junit.jupiter.api.Test
    fun `Markdown-Auszeichnung wird nicht als Zeichen gesprochen`() {
        val spoken = sanitizer.sanitizeForSpeech("Ein Ticket kostet **23,50 EUR** oder ~~30~~ `netto`.")
        org.junit.jupiter.api.Assertions.assertEquals("Ein Ticket kostet 23,50 EUR oder 30 netto.", spoken)
    }

    @org.junit.jupiter.api.Test
    fun `beide Quellenformen zusammen - genau Andis Fall`() {
        val spoken = sanitizer.sanitizeForSpeech(
            "Es erscheint am 19. November 2026. " +
                "([rockstargames.com](https://www.rockstargames.com/newswire/x?utm_source=openai)) " +
                "Source: Quellen: https://www.rockstargames.com/newswire/x.",
        )
        org.junit.jupiter.api.Assertions.assertEquals("Es erscheint am 19. November 2026.", spoken)
    }

    @org.junit.jupiter.api.Test
    fun `eckige Klammern ohne Link bleiben unangetastet`() {
        val text = "Das ist [wichtig] und bleibt so."
        org.junit.jupiter.api.Assertions.assertEquals(text, sanitizer.sanitizeForSpeech(text))
    }
}
