package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.AreaClarifyIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist die beiden Live-Befund-Ergänzungen vom 2026-07-15 (Andi: „Schalte das
 * Schlafzimmer ein" schaltete NICHTS, weil der Classifier ein Geräte-Wort verlangte
 * und der Turn stattdessen als Brain-Prosa ohne Tat endete, `brainTtftMs=960`):
 *
 *  - **(Teil 1) Raum-als-Ziel:** Schalt-Verb + bekannter Raum + An/Aus-Partikel OHNE
 *    Geräte-Wort ⇒ `light_set(area, on/off)` — „Raum einschalten = Licht im Raum".
 *  - **(Teil 3) Ehrliche Rückfrage:** Schalt-Verb + Partikel sicher erkannt, aber KEIN
 *    Ziel auflösbar ⇒ `AreaClarifyIntent`-ToolCall statt Brain-Prosa/Raten.
 *
 * Regressionsbeweis: alle Bestandspfade (Licht-Wort, Kompositum, Szene, Negation,
 * indirekte Komfort-Phrasen) bleiben unverändert.
 */
class ToolIntentClassifierRoomTargetTest {

    private val classifier = DeterministicToolIntentClassifier()

    /** SMART_HOME an, sonst alles aus — für Tests, die einen [AreaCatalogPort] überschreiben
     *  (der primäre Ctor verlangt [SkillStatePort] explizit, hat keinen Default). */
    private val toolsOnly: SkillStatePort =
        SkillStatePort.ofStatic(smartHome = true, scenes = false, timer = false, calculator = false)

    // ── Teil 1: Raum-als-Ziel (Pflicht-Testfall wortgenau) ───────────────────────

    @Test
    fun `DE schalte das licht im schlafzimmer ein — getrenntes ein zaehlt im klassischen Licht-Pfad mit Schalt-Verb als An-Partikel`() {
        val call = classifier.classify("schalte das licht im schlafzimmer ein", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `DE brennt da ein licht im flur — ein ohne Schalt-Verb ist Artikel, kein Befehl`() {
        assertNull(classifier.classify("brennt da ein licht im flur", Language.DE))
    }

    @Test
    fun `DE schalte ein licht im keller aus — Artikel-ein stoert den Aus-Befehl nicht`() {
        val call = classifier.classify("schalte ein licht im keller aus", Language.DE)!!
        assertEquals("turn_off", call.service)
        assertEquals("keller", call.data["area_id"])
    }

    @Test
    fun `DE schalte das schlafzimmer ein (lowercase) mappt auf light turn_on area schlafzimmer`() {
        val call = classifier.classify("schalte das schlafzimmer ein", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertNull(call.entityId, "Area-Targeting ⇒ entityId null")
        assertEquals("schlafzimmer", call.data["area_id"])
        assertFalse(call.read)
    }

    @Test
    fun `DE mach das schlafzimmer aus mappt auf light turn_off area schlafzimmer`() {
        val call = classifier.classify("mach das schlafzimmer aus", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `DE schalte die kueche an mappt auf light turn_on area kuche (ue-zu-u Slug)`() {
        val call = classifier.classify("schalte die Küche an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("kuche", call.data["area_id"], "HA slugifiziert ü→u, nicht ue")
    }

    @Test
    fun `EN turn the bedroom on mappt auf light turn_on area schlafzimmer`() {
        val call = classifier.classify("turn the bedroom on", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `EN turn the bedroom off mappt auf light turn_off area schlafzimmer`() {
        val call = classifier.classify("turn the office off", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("arbeitszimmer", call.data["area_id"])
    }

    @Test
    fun `Groschreibung und Satzzeichen sind egal (STT-tolerant)`() {
        val call = classifier.classify("Schalte das Schlafzimmer ein!", Language.DE)!!
        assertEquals("turn_on", call.service)
        assertEquals("schlafzimmer", call.data["area_id"])
    }

    @Test
    fun `Negation blockt auch den Raum-als-Ziel-Pfad`() {
        assertNull(classifier.classify("schalte das schlafzimmer nicht ein", Language.DE))
    }

    // ── Teil 1 (Fortsetzung): die restlichen Raeume aus ToolAreas.ROOMS, DE+EN ──
    // (schlafzimmer/kuche/arbeitszimmer sind oben bereits abgedeckt; hier der Rest,
    // damit JEDE reale HA-area_id mindestens einmal ueber den Raum-als-Ziel-Pfad läuft.)

    @Test
    fun `DE schalte das wohnzimmer an mappt auf light turn_on area wohnzimmer`() {
        val call = classifier.classify("schalte das wohnzimmer an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `DE schalte den flur aus mappt auf light turn_off area flur`() {
        val call = classifier.classify("schalte den flur aus", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    @Test
    fun `EN turn the hallway on mappt auf light turn_on area flur`() {
        val call = classifier.classify("turn the hallway on", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("flur", call.data["area_id"])
    }

    @Test
    fun `DE schalte den keller an mappt auf light turn_on area keller`() {
        val call = classifier.classify("schalte den keller an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("keller", call.data["area_id"])
    }

    @Test
    fun `EN turn the basement off mappt auf light turn_off area keller`() {
        val call = classifier.classify("turn the basement off", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("keller", call.data["area_id"])
    }

    @Test
    fun `DE schalte das bad an mappt auf light turn_on area badezimmer (Kurzform bad)`() {
        val call = classifier.classify("schalte das bad an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("badezimmer", call.data["area_id"])
    }

    @Test
    fun `EN turn the bathroom off mappt auf light turn_off area badezimmer`() {
        val call = classifier.classify("turn the bathroom off", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("badezimmer", call.data["area_id"])
    }

    @Test
    fun `DE schalte das buero aus (Alias buero) mappt auf light turn_off area arbeitszimmer`() {
        val call = classifier.classify("schalte das büro aus", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertEquals("arbeitszimmer", call.data["area_id"])
    }

    // ── Teil 3: Ehrliche Rueckfrage statt Brain-Prosa ────────────────────────────

    @Test
    fun `DE Schalt-Verb plus Partikel ohne Ziel ergibt eine Rueckfrage statt null`() {
        val call = classifier.classify("schalte mal was an", Language.DE)!!
        assertEquals(AreaClarifyIntent.DOMAIN, call.domain)
        assertEquals(AreaClarifyIntent.ASK, call.service)
        val phrase = call.data[AreaClarifyIntent.PHRASE] as String
        assertTrue(phrase.isNotBlank())
        assertTrue(phrase.contains("Raum"), "Phrase war: $phrase")
    }

    @Test
    fun `EN Schalt-Verb plus Partikel ohne Ziel ergibt eine englische Rueckfrage`() {
        val call = classifier.classify("turn something on", Language.EN)!!
        assertEquals(AreaClarifyIntent.DOMAIN, call.domain)
        val phrase = call.data[AreaClarifyIntent.PHRASE] as String
        assertTrue(phrase.contains("room"), "Phrase war: $phrase")
    }

    @Test
    fun `Rueckfrage nennt hoechstens vier Raeume aus dem Katalog`() {
        val call = classifier.classify("mach mal an", Language.DE)!!
        val phrase = call!!.data[AreaClarifyIntent.PHRASE] as String
        // Default-Katalog kennt 7 Areas — die Rueckfrage darf nicht alle aufzaehlen.
        val namedRooms = listOf("Wohnzimmer", "Schlafzimmer", "Küche", "Arbeitszimmer", "Flur", "Keller", "Badezimmer")
        val mentioned = namedRooms.count { phrase.contains(it) }
        assertTrue(mentioned in 1..4, "Rueckfrage nannte $mentioned Raeume: $phrase")
    }

    @Test
    fun `Rueckfrage mit leerem Katalog bleibt ehrlich und nie leer`() {
        val c = DeterministicToolIntentClassifier(skills = toolsOnly, areaCatalog = AreaCatalogPort { emptyList() })
        val call = c.classify("schalte mal was an", Language.DE)!!
        assertEquals(AreaClarifyIntent.DOMAIN, call.domain)
        val phrase = call.data[AreaClarifyIntent.PHRASE] as String
        assertTrue(phrase.isNotBlank(), "auch ohne Katalog nie eine leere Frage")
    }

    @Test
    fun `Schalt-Verb mit Klimawort loest KEINE Rueckfrage aus (kein Raum-Fall)`() {
        // "schalte die heizung ein" ist ein (nicht unterstuetztes) Heizungs-Kommando,
        // keine Raum-Mehrdeutigkeit ⇒ soll NICHT nach dem Raum gefragt werden.
        assertNull(classifier.classify("schalte die heizung ein", Language.DE))
    }

    // ── Regression: bestehende Pfade + Nicht-Trigger bleiben unveraendert ────────

    @Test
    fun `Indirekte Komfort-Phrase mir ist kalt bleibt unberuehrt (kein Schalt-Verb-Partikel-Paar)`() {
        assertNull(classifier.classify("mir ist kalt", Language.DE))
    }

    @Test
    fun `Getrennter Klassiker Licht im Wohnzimmer an bleibt unveraendert (isLight-Vorrang)`() {
        val call = classifier.classify("mach das Licht im Wohnzimmer an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `Szene-Text ohne bekannten Raum bleibt beim Szenen-Pfad (mach die Nordlichter an)`() {
        val sceneCatalog = listOf("wohnzimmer_nordlichter", "kuche_gedimmt")
        val sceneClassifier = DeterministicToolIntentClassifier(sceneCatalog = sceneCatalog)
        val call = sceneClassifier.classify("mach die Nordlichter an", Language.DE)!!
        assertEquals("scene", call.domain)
        assertEquals("scene.wohnzimmer_nordlichter", call.entityId)
    }

    @Test
    fun `Liste-Satz mit mach bleibt unveraendert null (kein Partikel)`() {
        assertNull(classifier.classify("Mach mir eine Liste von Ideen für Papas Geburtstag.", Language.DE))
    }

    @Test
    fun `toolsEnabled false schaltet auch den neuen Raum-als-Ziel-Pfad aus`() {
        val off = DeterministicToolIntentClassifier(toolsEnabled = false)
        assertNull(off.classify("schalte das schlafzimmer ein", Language.DE))
    }

    // ── Custom AreaCatalogPort (Teil 2 wird hier NUR aus Classifier-Sicht geprüft) ──

    @Test
    fun `Custom AreaCatalogPort mit neuer Area (nicht in ToolAreas) wird erkannt`() {
        val garten = AreaInfo(areaId = "garten", label = "Garten", aliases = setOf("garten"))
        val custom = AreaCatalogPort { listOf(garten) }
        val c = DeterministicToolIntentClassifier(skills = toolsOnly, areaCatalog = custom)
        val call = c.classify("schalte den garten an", Language.DE)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("garten", call.data["area_id"])
    }
}
