package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Beweist den [HeuristicLanguageDetector] OHNE Infra: rein, deterministisch. Deckt
 * klare DE/EN-Saetze, die kurzen/mehrdeutigen Faelle (-> Heimsprache DE) und ein
 * paar gemischte Saetze ab.
 */
class LanguageDetectorTest {

    private val detector = HeuristicLanguageDetector()

    private fun assertDe(text: String) =
        assertEquals(Language.DE, detector.detect(text), "erwartet DE: \"$text\"")

    private fun assertEn(text: String) =
        assertEquals(Language.EN, detector.detect(text), "erwartet EN: \"$text\"")

    // ── Klar Deutsch ──────────────────────────────────────────────────────────

    @Test
    fun `klare deutsche Saetze sind DE`() {
        assertDe("Was ist die Hauptstadt von Japan?")
        assertDe("Erklär mir warum der Himmel blau ist")
        assertDe("Mach das Licht im Wohnzimmer an")
        assertDe("Wie spät ist es eigentlich gerade?")
        assertDe("Ich hätte gerne eine kurze Zusammenfassung")
        assertDe("Kannst du mir mit der Einkaufsliste helfen?")
        assertDe("Das schmeckt richtig gut, danke dir")
    }

    @Test
    fun `Umlaute allein tragen schon stark nach DE`() {
        assertDe("Schöne Grüße")
        assertDe("Tschüss")
    }

    // ── Klar Englisch ─────────────────────────────────────────────────────────

    @Test
    fun `klare englische Saetze sind EN`() {
        assertEn("In one sentence, what is a vector embedding?")
        assertEn("Got any encouragement for me?")
        assertEn("What is the capital of Japan?")
        assertEn("Can you tell me how this works?")
        assertEn("Tell me a short story about the sea")
        assertEn("Why is the sky blue and what causes it?")
    }

    // ── Kurz / mehrdeutig / leer -> Heimsprache DE ────────────────────────────

    @Test
    fun `kurze mehrdeutige oder leere Eingaben fallen auf DE`() {
        assertDe("")
        assertDe("   ")
        assertDe("ok")
        assertDe("OK")
        assertDe("123")
        assertDe("Hi")
        assertDe("Hoshi")
        assertDe("42 + 8")
        assertDe("!!!")
    }

    // ── Gemischt ──────────────────────────────────────────────────────────────

    @Test
    fun `gemischt aber ueberwiegend deutsch ist DE`() {
        // Deutsche Funktion + Umlaute dominieren, ein englisches Fachwort kippt es nicht.
        assertDe("Erzähl mir was über machine learning")
        assertDe("Was ist das beste Framework für so ein Projekt?")
    }

    @Test
    fun `gemischt aber ueberwiegend englisch ist EN`() {
        // Mehr englische Funktionswoerter als deutsche -> EN.
        assertEn("What is the best way to learn this?")
        assertEn("Is this the right answer for you?")
    }
}
