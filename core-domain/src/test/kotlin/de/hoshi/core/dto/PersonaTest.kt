package de.hoshi.core.dto

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Beweist den tolerant-case-insensitiven [Persona.fromCode] (Wire-Vertrag des
 * Frontends: PascalCase "Standard"/"Kumpel"/...), den STANDARD-Fallback bei
 * Unbekannt/null/leer UND die Jackson-Deserialisierung ueber den [Persona.fromCode]-
 * `@JsonCreator` (das FE schickt "Kumpel", NICHT den GROSS-Enum-Namen "KUMPEL").
 */
class PersonaTest {

    @Test
    fun `fromCode ist case-insensitiv auf den Namen und trimmt`() {
        assertEquals(Persona.STANDARD, Persona.fromCode("Standard"))
        assertEquals(Persona.KUMPEL, Persona.fromCode("Kumpel"))
        assertEquals(Persona.KNAPP, Persona.fromCode("Knapp"))
        assertEquals(Persona.RUHIG, Persona.fromCode("Ruhig"))
        // case-insensitiv + trim:
        assertEquals(Persona.KUMPEL, Persona.fromCode("kumpel"))
        assertEquals(Persona.KNAPP, Persona.fromCode("KNAPP"))
        assertEquals(Persona.RUHIG, Persona.fromCode("  Ruhig  "))
    }

    @Test
    fun `unbekannt null oder leer faellt auf STANDARD (Grundton-Default)`() {
        assertEquals(Persona.STANDARD, Persona.fromCode(null))
        assertEquals(Persona.STANDARD, Persona.fromCode(""))
        assertEquals(Persona.STANDARD, Persona.fromCode("   "))
        // alter/neuer Research-Name → kein Bruch, spricht im Grundton weiter:
        assertEquals(Persona.STANDARD, Persona.fromCode("Forscherin"))
    }

    @Test
    fun `Default-Stimmungen treiben die Temperatur (STANDARD ohne feste Stimmung)`() {
        assertEquals(PersonaEmotion.CHEERFUL, Persona.KUMPEL.defaultMood)
        assertEquals(PersonaEmotion.FOCUSED, Persona.KNAPP.defaultMood)
        assertEquals(PersonaEmotion.CALM, Persona.RUHIG.defaultMood)
        assertEquals(null, Persona.STANDARD.defaultMood)
    }

    @Test
    fun `Jackson deserialisiert den Wire-String tolerant ueber fromCode`() {
        val mapper = ObjectMapper()
        assertEquals(Persona.KUMPEL, mapper.readValue("\"Kumpel\"", Persona::class.java))
        assertEquals(Persona.KNAPP, mapper.readValue("\"Knapp\"", Persona::class.java))
        assertEquals(Persona.RUHIG, mapper.readValue("\"Ruhig\"", Persona::class.java))
        assertEquals(Persona.STANDARD, mapper.readValue("\"Standard\"", Persona::class.java))
        // unbekannter Wert auf der Leitung → STANDARD statt Deserialisierungs-Fehler:
        assertEquals(Persona.STANDARD, mapper.readValue("\"Forscherin\"", Persona::class.java))
    }
}
