package de.hoshi.core.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **LanguageTest** — beweist die Sprachpaket-Erweiterung (Andi-Auftrag
 * 2026-07-20): ES/FR/IT sind wählbare [Language]-Werte, [Language.fromCodeOrNull]
 * liefert für unbekannte Codes ehrlich `null` (Settings-422-Vertrag), während
 * [Language.fromCode] wie bisher konservativ auf [Language.DEFAULT] fällt.
 */
class LanguageTest {

    @Test
    fun `fuenf Sprachen sind definiert - DE bleibt DEFAULT`() {
        assertEquals(5, Language.entries.size)
        assertEquals(Language.DE, Language.DEFAULT)
    }

    @Test
    fun `fromCode erkennt ES-FR-IT case-insensitiv mit Region-Tag`() {
        assertEquals(Language.ES, Language.fromCode("es"))
        assertEquals(Language.ES, Language.fromCode("ES"))
        assertEquals(Language.ES, Language.fromCode("es-ES"))
        assertEquals(Language.FR, Language.fromCode("fr-FR"))
        assertEquals(Language.IT, Language.fromCode("IT"))
    }

    @Test
    fun `fromCode faellt bei unbekanntem Code auf DEFAULT (bestehendes Verhalten)`() {
        assertEquals(Language.DEFAULT, Language.fromCode("xx"))
        assertEquals(Language.DEFAULT, Language.fromCode(null))
        assertEquals(Language.DEFAULT, Language.fromCode(""))
    }

    @Test
    fun `fromCodeOrNull liefert bei unbekanntem-leerem Code ehrlich null (Settings-422-Vertrag)`() {
        assertNull(Language.fromCodeOrNull("xx"))
        assertNull(Language.fromCodeOrNull(null))
        assertNull(Language.fromCodeOrNull(""))
        assertNull(Language.fromCodeOrNull("   "))
    }

    @Test
    fun `fromCodeOrNull erkennt alle fuenf gueltigen Codes`() {
        assertEquals(Language.DE, Language.fromCodeOrNull("de"))
        assertEquals(Language.EN, Language.fromCodeOrNull("EN"))
        assertEquals(Language.ES, Language.fromCodeOrNull("es"))
        assertEquals(Language.FR, Language.fromCodeOrNull("fr"))
        assertEquals(Language.IT, Language.fromCodeOrNull("it"))
    }

    @Test
    fun `jede Sprache traegt einen eigenen Endonym`() {
        val endonyms = Language.entries.map { it.endonym }
        assertEquals(endonyms.toSet().size, endonyms.size, "keine doppelten Endonyme")
    }
}
