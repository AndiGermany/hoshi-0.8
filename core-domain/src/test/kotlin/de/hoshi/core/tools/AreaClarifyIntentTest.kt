package de.hoshi.core.tools

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist [AreaClarifyIntent.phrase] isoliert (ohne den Classifier-Umweg, den
 * [de.hoshi.core.pipeline.ToolIntentClassifierRoomTargetTest] bereits über den
 * vollen `classify()`-Pfad abdeckt) — die einzige eigenständige Logik dieses
 * Objekts (s. Klassen-KDoc: KEIN eigener `classify(text)`, nur die Phrasen-
 * Konstruktion aus einem [AreaInfo]-Katalog).
 *
 * Fälle: DE/EN-Text, MAX_ROOMS_NAMED-Deckelung (nicht die ganze Liste vorlesen),
 * leerer Katalog (nie leer/still), blanke Labels werden gefiltert, Reihenfolge
 * des Katalogs bestimmt die Aufzählungsreihenfolge (Katalog-Kontrakt, nicht
 * alphabetisch neu sortiert).
 */
class AreaClarifyIntentTest {

    private fun area(id: String, label: String) = AreaInfo(areaId = id, label = label, aliases = setOf(id))

    @Test
    fun `DE Phrase nennt die Domain-Konstanten korrekt und ist nie leer`() {
        val phrase = AreaClarifyIntent.phrase(AreaCatalogPort.STATIC.areas(), Language.DE)
        assertTrue(phrase.isNotBlank())
        assertTrue(phrase.contains("Raum"), "Phrase war: $phrase")
        assertEquals("area_clarify", AreaClarifyIntent.DOMAIN)
        assertEquals("ask", AreaClarifyIntent.ASK)
        assertEquals("phrase", AreaClarifyIntent.PHRASE)
    }

    @Test
    fun `EN Phrase ist auf Englisch und nie leer`() {
        val phrase = AreaClarifyIntent.phrase(AreaCatalogPort.STATIC.areas(), Language.EN)
        assertTrue(phrase.isNotBlank())
        assertTrue(phrase.contains("room"), "Phrase war: $phrase")
        assertFalse(phrase.contains("Raum"), "EN-Phrase darf kein deutsches Wort tragen: $phrase")
    }

    @Test
    fun `Deckelt auf hoechstens MAX_ROOMS_NAMED Raeume auch bei einem groesseren Katalog`() {
        val areas = (1..10).map { area("raum$it", "Raum$it") }
        val phrase = AreaClarifyIntent.phrase(areas, Language.DE)
        val mentioned = areas.count { phrase.contains(it.label) }
        assertEquals(AreaClarifyIntent.MAX_ROOMS_NAMED, mentioned, "Phrase war: $phrase")
        // Die ERSTEN vier (Katalog-Reihenfolge) muessen genannt sein, nicht irgendwelche vier.
        for (i in 1..AreaClarifyIntent.MAX_ROOMS_NAMED) assertTrue(phrase.contains("Raum$i"), "Phrase war: $phrase")
        for (i in (AreaClarifyIntent.MAX_ROOMS_NAMED + 1)..10) assertFalse(phrase.contains("Raum$i"), "Phrase war: $phrase")
    }

    @Test
    fun `Leerer Katalog liefert die Frage OHNE Aufzaehlung, nie leer`() {
        val phrase = AreaClarifyIntent.phrase(emptyList(), Language.DE)
        assertEquals("Welchen Raum meinst du?", phrase)
    }

    @Test
    fun `Leerer Katalog EN liefert die Frage ohne Aufzaehlung, nie leer`() {
        val phrase = AreaClarifyIntent.phrase(emptyList(), Language.EN)
        assertEquals("Which room do you mean?", phrase)
    }

    @Test
    fun `Blanke Labels werden aus der Aufzaehlung gefiltert`() {
        val areas = listOf(area("a", ""), area("b", "Küche"), area("c", "  "))
        val phrase = AreaClarifyIntent.phrase(areas, Language.DE)
        assertTrue(phrase.contains("Küche"), "Phrase war: $phrase")
        // nur EIN Name genannt (die zwei blanken sind rausgefiltert) -> kein Doppel-Komma-Artefakt.
        assertFalse(phrase.contains(",,"), "Phrase war: $phrase")
    }

    @Test
    fun `Katalog-Reihenfolge bestimmt die Aufzaehlungsreihenfolge (kein Re-Sort)`() {
        val areas = listOf(area("z", "Zimmer-Z"), area("a", "Zimmer-A"))
        val phrase = AreaClarifyIntent.phrase(areas, Language.DE)
        assertTrue(phrase.indexOf("Zimmer-Z") < phrase.indexOf("Zimmer-A"), "Phrase war: $phrase")
    }

    @Test
    fun `Einzelner Raum wird ohne Komma sauber genannt`() {
        val phrase = AreaClarifyIntent.phrase(listOf(area("kuche", "Küche")), Language.DE)
        assertEquals("Welchen Raum meinst du — Küche…?", phrase)
    }
}
