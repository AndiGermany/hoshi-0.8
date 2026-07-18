package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.IntentClassifierTest).
 * Entkopplung: `HoshiProperties(openclaw=…)` → direkter `complexityThreshold`-Parameter.
 */
class IntentClassifierTest {

    private val classifier = IntentClassifier(complexityThreshold = 4)

    // === isSmartHomeCandidate() Tests ===

    @Test
    fun `isSmartHomeCandidate with verb and target`() {
        assertTrue(classifier.isSmartHomeCandidate("schalte das licht an"))
    }

    @Test
    fun `isSmartHomeCandidate with only verb`() {
        assertFalse(classifier.isSmartHomeCandidate("schalte die zeit"))
    }

    @Test
    fun `isSmartHomeCandidate with only target`() {
        assertFalse(classifier.isSmartHomeCandidate("das licht ist an"))
    }

    @Test
    fun `isSmartHomeCandidate with empty string`() {
        assertFalse(classifier.isSmartHomeCandidate(""))
    }

    @Test
    fun `isSmartHomeCandidate with blank string`() {
        assertFalse(classifier.isSmartHomeCandidate("   "))
    }

    @Test
    fun `isSmartHomeCandidate case insensitive uppercase`() {
        assertTrue(classifier.isSmartHomeCandidate("SCHALTE DAS LICHT AN"))
    }

    @Test
    fun `isSmartHomeCandidate case insensitive mixed case`() {
        assertTrue(classifier.isSmartHomeCandidate("Dimme die Lampe"))
    }

    @Test
    fun `isSmartHomeCandidate with multiple verbs and targets`() {
        assertTrue(classifier.isSmartHomeCandidate("schalte und dimme alle lichter"))
    }

    @Test
    fun `isSmartHomeCandidate with surrounding text`() {
        assertTrue(classifier.isSmartHomeCandidate("bitte schalte das wohnzimmer licht aus"))
    }

    // === complexityScore() Tests ===

    @Test
    fun `complexityScore simple query`() {
        assertEquals(0, classifier.complexityScore("Wie spät ist es?"))
    }

    @Test
    fun `complexityScore length bonus long text`() {
        val longQuery = "was ist " + "x".repeat(130)
        assertTrue(classifier.complexityScore(longQuery) >= 3)
    }

    @Test
    fun `complexityScore complexity marker`() {
        assertEquals(3, classifier.complexityScore("erstelle eine routine für morgens"))
    }

    @Test
    fun `complexityScore agent marker`() {
        assertEquals(4, classifier.complexityScore("erstelle eine einkaufsliste"))
    }

    @Test
    fun `complexityScore two question marks`() {
        assertEquals(1, classifier.complexityScore("Was ist das? Und das?"))
    }

    @Test
    fun `complexityScore multiple question marks`() {
        assertEquals(1, classifier.complexityScore("Was? Was? Was? Was?"))
    }

    @Test
    fun `complexityScore one question mark`() {
        assertEquals(0, classifier.complexityScore("Was ist das?"))
    }

    @Test
    fun `complexityScore multiple haRooms`() {
        assertEquals(2, classifier.complexityScore("Mach das Licht in Wohnzimmer Schlafzimmer und Küche aus"))
    }

    @Test
    fun `complexityScore exactly two haRooms`() {
        assertEquals(0, classifier.complexityScore("Wohnzimmer und Schlafzimmer"))
    }

    @Test
    fun `complexityScore combined markers`() {
        // agentMarker(4) + haRooms(2) = 6
        assertTrue(classifier.complexityScore("erstelle eine einkaufsliste im wohnzimmer schlafzimmer und küche") >= 6)
    }

    @Test
    fun `complexityScore case insensitive`() {
        assertEquals(4, classifier.complexityScore("ERSTELLE EINE EINKAUFSLISTE"))
    }

    @Test
    fun `complexityScore empty string`() {
        assertEquals(0, classifier.complexityScore(""))
    }

    // === isOpenClawEligible() Tests ===

    @Test
    fun `isOpenClawEligible above threshold`() {
        assertTrue(classifier.isOpenClawEligible("erstelle eine einkaufsliste"))
    }

    @Test
    fun `isOpenClawEligible below threshold`() {
        assertFalse(classifier.isOpenClawEligible("Hallo"))
    }

    @Test
    fun `isOpenClawEligible configurable threshold`() {
        val strictClassifier = IntentClassifier(complexityThreshold = 10)
        assertFalse(strictClassifier.isOpenClawEligible("erstelle eine einkaufsliste"))
    }

    @Test
    fun `isOpenClawEligible strict threshold rejects simple einkaufsliste`() {
        val strictClassifier = IntentClassifier(complexityThreshold = 10)
        // "erstelle eine einkaufsliste" scores only 4, threshold 10 rejects it
        assertFalse(strictClassifier.isOpenClawEligible("erstelle eine einkaufsliste"))
    }

    @Test
    fun `isOpenClawEligible exact threshold`() {
        val classifier4 = IntentClassifier(complexityThreshold = 4)
        assertTrue(classifier4.isOpenClawEligible("erstelle eine einkaufsliste"))
    }
}
