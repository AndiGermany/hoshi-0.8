package de.hoshi.core.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Beweist den tolerant-case-insensitiven [LanguagePolicy.fromCode] + [concreteOrNull]. */
class LanguagePolicyTest {

    @Test
    fun `fromCode ist case-insensitiv und region-tolerant`() {
        assertEquals(LanguagePolicy.AUTO, LanguagePolicy.fromCode("AUTO"))
        assertEquals(LanguagePolicy.AUTO, LanguagePolicy.fromCode("auto"))
        assertEquals(LanguagePolicy.DE, LanguagePolicy.fromCode("de"))
        assertEquals(LanguagePolicy.EN, LanguagePolicy.fromCode("EN"))
        assertEquals(LanguagePolicy.EN, LanguagePolicy.fromCode("en-US"))
    }

    @Test
    fun `unbekannt null oder leer ist null (Legacy-Fallback, kein AUTO-Zwang)`() {
        assertNull(LanguagePolicy.fromCode(null))
        assertNull(LanguagePolicy.fromCode(""))
        assertNull(LanguagePolicy.fromCode("   "))
        assertNull(LanguagePolicy.fromCode("klingonisch"))
    }

    @Test
    fun `concreteOrNull - AUTO ist null, DE-EN konkret`() {
        assertNull(LanguagePolicy.AUTO.concreteOrNull())
        assertEquals(Language.DE, LanguagePolicy.DE.concreteOrNull())
        assertEquals(Language.EN, LanguagePolicy.EN.concreteOrNull())
    }
}
