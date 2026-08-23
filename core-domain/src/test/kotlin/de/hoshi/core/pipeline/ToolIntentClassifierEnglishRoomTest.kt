package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.skills.SkillStatePort
import de.hoshi.core.tools.ToolAreas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **ToolIntentClassifierEnglishRoomTest** (2026-07-25) — zwei Lücken, die der
 * englische Tat-Pfad hatte:
 *
 *  1. **Mehrwort-Raum („living room").** Der Alias fehlte komplett. „turn on the
 *     light in the living room" traf nur deshalb das Wohnzimmer, weil `wohnzimmer`
 *     zufällig der DEFAULT ist — für [ToolAreas.mentionsRoom] nannte der Satz KEINEN
 *     Raum. Folge in der Anaphern-Auflösung des `TurnOrchestrator`: der explizit
 *     gesagte Raum wurde durch die zuletzt geschaltete Area ERSETZT (falsches
 *     Zimmer) oder der Befehl fiel ganz durch. „living room off" ergab gar nichts,
 *     obwohl „Wohnzimmer aus" funktioniert. Und der Lese-Pfad („how warm is it in
 *     the living room") lieferte das Haus-Aggregat statt des Raums.
 *  2. **Übertragene Wendungen.** „turn on the charm" endete in der Raum-Rückfrage
 *     („Which room do you mean…?").
 *
 * **Eiserne Regel:** ein Alias ist eine ZUORDNUNG, keine Übersetzung — das Ergebnis
 * ist immer die reale, deutsche HA-`area_id`, der Raumname selbst bleibt unangetastet.
 */
class ToolIntentClassifierEnglishRoomTest {

    private val classifier = DeterministicToolIntentClassifier()

    private val toolsOnly: SkillStatePort =
        SkillStatePort.ofStatic(smartHome = true, scenes = false, timer = false, calculator = false)

    // ── (1) Mehrwort-Raum-Alias → echte HA-area_id ───────────────────────────────

    @Test
    fun `EN living room loest auf die echte area_id wohnzimmer auf`() {
        val call = classifier.classify("turn on the light in the living room", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"], "die reale HA-area_id, nicht übersetzt")
    }

    @Test
    fun `EN turn off the light in the living room schaltet das Wohnzimmer aus`() {
        val call = classifier.classify("turn off the light in the living room", Language.EN)!!
        assertEquals("turn_off", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `EN living room lights als Kompositum-Nachbarschaft trifft ebenfalls`() {
        val call = classifier.classify("turn on the living room lights", Language.EN)!!
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `der zuerst GESAGTE Raum gewinnt, egal ob ein- oder zweiwortig`() {
        // Zweiwortiger Raum zuerst genannt ⇒ er gewinnt (das Paar wird VOR dem
        // Einzel-Token geprüft, sonst hätte „kitchen" das Rennen gemacht).
        assertEquals(
            "wohnzimmer",
            classifier.classify("turn on the light in the living room and the kitchen", Language.EN)!!.data["area_id"],
        )
        assertEquals(
            "kuche",
            classifier.classify("turn on the kitchen light and the living room one", Language.EN)!!.data["area_id"],
        )
    }

    @Test
    fun `EN Lese-Pfad targetet den Raum statt des Haus-Aggregats`() {
        val call = classifier.classify("how warm is it in the living room", Language.EN)!!
        assertEquals("sensor", call.domain)
        assertEquals("read_temperature", call.service)
        assertTrue(call.read)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `mentionsRoom erkennt den Mehrwort-Alias (Anaphern-Schutz)`() {
        assertTrue(ToolAreas.mentionsRoom("turn on the light in the living room"))
        assertTrue(ToolAreas.mentionsRoom("living room off"))
        assertTrue(ToolAreas.mentionsRoom("turn on the kitchen light"), "Bestand: Ein-Wort-Alias")
        assertFalse(ToolAreas.mentionsRoom("turn off the lights"), "kein Raum genannt")
        assertFalse(ToolAreas.mentionsRoom("I live in a small room"), "kein Alias-Paar")
    }

    @Test
    fun `weitere EN-Aliase mappen auf ihre realen area_ids`() {
        assertEquals("wohnzimmer", ToolAreas.resolveArea("living room"))
        assertEquals("keller", ToolAreas.resolveArea("cellar"))
        assertEquals("flur", ToolAreas.resolveArea("corridor"))
        assertEquals("schlafzimmer", ToolAreas.resolveArea("bedroom"))
    }

    /**
     * Die Alias-Erweiterung verändert die realen Areas NICHT — es kommen nur
     * zusätzliche Schlüssel dazu, keine neuen Räume und keine neue Reihenfolge.
     */
    @Test
    fun `die Area-Liste selbst bleibt unveraendert`() {
        assertEquals(
            listOf("arbeitszimmer", "badezimmer", "flur", "keller", "kuche", "schlafzimmer", "wohnzimmer"),
            ToolAreas.AREAS,
        )
        assertEquals(
            listOf("wohnzimmer", "schlafzimmer", "kuche", "arbeitszimmer", "flur", "keller", "badezimmer"),
            ToolAreas.AREAS_ORDERED,
        )
        assertEquals("Wohnzimmer", ToolAreas.label("wohnzimmer"))
        assertEquals("Küche", ToolAreas.label("kuche"))
    }

    /** Ein dynamisch aus HA gelieferter Mehrwort-Alias läuft über denselben Pfad. */
    @Test
    fun `Mehrwort-Alias aus einem dynamischen AreaCatalogPort greift ebenfalls`() {
        val custom = AreaCatalogPort {
            listOf(AreaInfo(areaId = "wintergarten", label = "Wintergarten", aliases = setOf("winter garden")))
        }
        val c = DeterministicToolIntentClassifier(skills = toolsOnly, areaCatalog = custom)
        val call = c.classify("turn on the light in the winter garden", Language.EN)!!
        assertEquals("wintergarten", call.data["area_id"])
    }

    // ── (2) Übertragene Wendungen schalten NICHTS ────────────────────────────────

    @Test
    fun `uebertragene Wendungen ergeben keinen ToolCall und keine Raum-Rueckfrage`() {
        listOf(
            "turn on the charm",
            "she really turns on the charm",
            "that turns me on",
            "he turned a blind eye",
            "what a bright idea",
            "you are the light of my life",
        ).forEach { p ->
            assertNull(classifier.classify(p, Language.EN), "Satz: $p")
        }
    }

    // ── (3) Bestand: DE unveraendert ─────────────────────────────────────────────

    @Test
    fun `DE-Bestandspfade bleiben unveraendert`() {
        assertEquals("wohnzimmer", classifier.classify("mach das Licht im Wohnzimmer an", Language.DE)!!.data["area_id"])
        assertEquals("schlafzimmer", classifier.classify("schalte das schlafzimmer ein", Language.DE)!!.data["area_id"])
        assertEquals("kuche", classifier.classify("schalte die Küche an", Language.DE)!!.data["area_id"])
        // F2/S2: „licht an" ohne Raum traegt KEIN area_id mehr (kein geratener Default).
        assertFalse(
            classifier.classify("licht an", Language.DE)!!.data.containsKey("area_id"),
            "roomless ⇒ kein area_id",
        )
        assertNull(classifier.classify("mir ist kalt", Language.DE))
        assertNull(classifier.classify("schalte das schlafzimmer nicht ein", Language.DE))
    }

    @Test
    fun `DE Rueckfrage-Pfad bleibt erhalten (kein Idiom-Riegel-Fehlfeuer)`() {
        val call = classifier.classify("schalte mal was an", Language.DE)!!
        assertEquals("area_clarify", call.domain)
    }
}
