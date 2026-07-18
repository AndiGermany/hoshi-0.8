package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.LanguagePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die [LanguageResolver]-Aufloesungs-Matrix UND die Byte-Neutralitaet bei
 * Flag OFF (AUTO -> DE, explizit/Legacy unveraendert). Der Detector ist hier ein
 * fixer Stub (immer EN), damit AUTO-bei-Flag-AN eindeutig sichtbar wird.
 */
class LanguageResolverTest {

    private val detectEn = LanguageDetector { Language.EN }

    private fun onResolver() = LanguageResolver(detector = detectEn, autoEnabled = true)
    private fun offResolver() = LanguageResolver(detector = detectEn, autoEnabled = false)

    @Test
    fun `Flag ON - AUTO erkennt aus dem Text`() {
        val r = onResolver()
        assertEquals(Language.EN, r.resolve(LanguagePolicy.AUTO, "egal", Language.DE))
        assertTrue(r.isAutoDetect(LanguagePolicy.AUTO))
    }

    @Test
    fun `Flag OFF - AUTO degradiert byte-neutral zu DE`() {
        val r = offResolver()
        assertEquals(Language.DE, r.resolve(LanguagePolicy.AUTO, "this is clearly english", Language.DE))
        assertFalse(r.isAutoDetect(LanguagePolicy.AUTO))
    }

    @Test
    fun `explizit DE-EN ist ein harter Override, egal welches Flag`() {
        for (r in listOf(onResolver(), offResolver())) {
            assertEquals(Language.DE, r.resolve(LanguagePolicy.DE, "this is english", Language.EN))
            assertEquals(Language.EN, r.resolve(LanguagePolicy.EN, "das ist deutsch", Language.DE))
        }
    }

    @Test
    fun `Legacy ohne Policy nutzt das explizite language-Feld (kein Override)`() {
        // Der kritische Fall: alter Client schickt nur language=EN, keine Policy.
        for (r in listOf(onResolver(), offResolver())) {
            assertEquals(Language.EN, r.resolve(null, "egal", Language.EN))
            assertEquals(Language.DE, r.resolve(null, "egal", Language.DE))
        }
    }

    @Test
    fun `resolve(ChatRequest) faedelt Policy plus language-Feld korrekt durch`() {
        val on = onResolver()
        // Legacy-Request: nur language gesetzt, keine Policy.
        assertEquals(Language.EN, on.resolve(ChatRequest(text = "x", language = Language.EN)))
        // AUTO-Request bei Flag ON -> Detector (EN-Stub).
        assertEquals(
            Language.EN,
            on.resolve(ChatRequest(text = "x", language = Language.DE, languagePolicy = LanguagePolicy.AUTO)),
        )
        // AUTO-Request bei Flag OFF -> DE.
        assertEquals(
            Language.DE,
            offResolver().resolve(ChatRequest(text = "x", language = Language.DE, languagePolicy = LanguagePolicy.AUTO)),
        )
    }
}
