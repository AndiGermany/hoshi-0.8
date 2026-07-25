package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.SmartHomeAction
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.pipeline.lang.SmartHomeAckPack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **SmartHomeMultilingualTest** — der Beweis zu Andis Ansage vom 2026-07-25:
 * „Smart-Home-Bestätigungen -> Sowas soll natürlich auch auf englisch, auch beim
 * wetter und was wir sonst fest verdrahtet haben. es soll multilingual werden.
 * von A-Z".
 *
 * Drei Dinge, die zusammen gelten müssen — sonst ist die Scheibe nicht fertig:
 *  1. **DE ist byte-identisch** zum Bestand (der deutsche Pfad darf um kein Byte
 *     wackeln — er ist Andis Alltag).
 *  2. **Jede Sprache spricht sich selbst** (Stichprobe je Sprache, kein
 *     DE-Durchschlag, keine leeren/unübersetzten Reste).
 *  3. **Nutzerdaten werden NIE übersetzt** — HA-Raumnamen reisen unverändert durch
 *     JEDEN Satz in JEDER Sprache. Das ist die eiserne Projekt-Regel.
 */
class SmartHomeMultilingualTest {

    private val formatter = ResponseFormatter()

    /** Räume, wie sie aus der HA-Registry kommen — deutsche Eigennamen, teils mit Umlaut. */
    private val haRoomNames = listOf("Wohnzimmer", "Küche", "Schlafzimmer", "Arbeitszimmer")

    // ── (1) DE byte-identisch ────────────────────────────────────────────────

    /**
     * Der Byte-Anker: die deutschen Pools stehen WÖRTLICH so da wie vor der
     * Multilingualisierung. Bewusst als Volltext-Vergleich (nicht `isNotEmpty`) —
     * genau dieser Test schlägt an, wenn eine Übersetzungs-Runde versehentlich
     * einen deutschen Satz „mit verbessert".
     */
    @Test
    fun `DE-Ack-Pools sind byte-identisch zum Bestand`() {
        val de = LangDe.SMART_HOME_ACKS
        assertEquals(listOf("{room} ist an.", "{room} ist hell.", "Mach ich — {room} ist an."), de.lightOnRoom)
        assertEquals(listOf("{room} ist aus.", "{room} ist dunkel.", "Mach ich — {room} ist aus."), de.lightOffRoom)
        assertEquals(listOf("{room} auf {value} Prozent.", "Mach ich — {room} auf {value} Prozent."), de.lightDimRoom)
        assertEquals(listOf("{room} auf {value} Grad.", "Mach ich — {room} auf {value} Grad."), de.climateRoom)
        assertEquals(listOf("Mach ich.", "Ist eingestellt."), de.scene)
        assertEquals(listOf("Mach ich.", "Ist erledigt.", "Geht klar."), de.unknown)
        assertEquals(
            listOf(
                "Die Szene kenne ich in deinem Setup nicht.",
                "So eine Szene hab ich hier nicht.",
                "Die Stimmung finde ich bei dir nicht.",
            ),
            de.unsupportedScene,
        )
        // Die vier früheren Inline-Literale des Formatters — jetzt im Katalog, Wortlaut gleich.
        assertEquals(listOf("Licht ist an."), de.lightOnNoRoom)
        assertEquals(listOf("Licht ist aus."), de.lightOffNoRoom)
        assertEquals(listOf("Ist gedimmt."), de.lightDimNoValue)
        assertEquals(listOf("Ist eingestellt."), de.climateNoValue)
        assertEquals(listOf("Auf {value} Grad."), de.climateValueNoRoom)
        assertEquals(listOf("Farbe ist {color}."), de.lightColorNamed)
        assertEquals(listOf("Farbe ist geändert."), de.lightColorUnnamed)
    }

    /**
     * Und die Formatter-Ausgabe selbst: die vier Fixtexte kommen ohne Argument
     * (Default-Sprache) exakt so heraus wie vor dem Umbau — dieselbe Zusicherung,
     * die [ResponseFormatterTest] für den Bestand macht, hier nochmal explizit
     * gegen die neue Katalog-Schicht.
     */
    @Test
    fun `Formatter liefert ohne Sprach-Argument weiter exakt die deutschen Fixtexte`() {
        assertEquals("Licht ist an.", formatter.lightOn(null))
        assertEquals("Licht ist aus.", formatter.lightOff(null))
        assertEquals("Ist gedimmt.", formatter.lightDim(null, null))
        assertEquals("Ist eingestellt.", formatter.climate(null, null))
        assertEquals("Auf 21 Grad.", formatter.climate(null, 21))
        assertEquals("Farbe ist warmweiß.", formatter.lightColor("warmweiß"))
        assertEquals("Farbe ist geändert.", formatter.lightColor(null))
        // Explizit DE übergeben == Default übergeben (kein zweiter Codepfad).
        assertEquals("Licht ist an.", formatter.lightOn(null, Language.DE))
        assertEquals("Ist gedimmt.", formatter.lightDim(null, null, Language.DE))
    }

    /** Der AudioBank-Prerender bleibt für DE unverändert und endlich (keine Template-Leaks). */
    @Test
    fun `prerenderAcks bleibt fuer DE unveraendert und leakt keine Platzhalter`() {
        val de = formatter.prerenderAcks()
        assertEquals(formatter.prerenderAcks(Language.DE), de, "Default-Aufruf == DE-Aufruf")
        assertTrue(de.contains("Licht ist an."), "Fixtext muss im Prerender stehen")
        assertTrue(de.contains("Licht ist aus."))
        assertTrue(de.contains("Ist gedimmt."))
        assertTrue(de.contains("Ist eingestellt."))
        assertTrue(de.contains("Wohnzimmer ist an."), "Raum-Ack muss gefüllt im Prerender stehen")
        for (a in de) assertFalse(a.contains("{"), "Template-Leak im Prerender: '$a'")
    }

    // ── (2) Jede Sprache spricht sich selbst ─────────────────────────────────

    /** Jede [Language] hat ein VOLLSTÄNDIGES Ack-Pack — kein leerer Pool, kein leerer Satz. */
    @Test
    fun `jede Sprache hat vollstaendige Ack-Pools ohne leere Eintraege`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language).smartHomeAcks
            for ((name, pool) in pools(pack)) {
                assertTrue(pool.isNotEmpty(), "$language.$name: Pool darf nicht leer sein")
                assertTrue(pool.none { it.isBlank() }, "$language.$name: keine leere Phrase")
            }
        }
    }

    /**
     * Struktur-Gleichheit gegen DE: gleiche Variantenzahl je Pool. Fängt die
     * typische halbe Übersetzung („zwei von drei Varianten übersetzt, die dritte
     * vergessen") — die würde sonst still zu einem DE-Durchschlag im Anti-Repeat.
     */
    @Test
    fun `jede Sprache traegt dieselbe Variantenzahl je Pool wie DE`() {
        val dePools = pools(LangDe.SMART_HOME_ACKS).toMap()
        for (language in Language.entries - Language.DE) {
            for ((name, pool) in pools(LanguagePackRegistry.forLanguage(language).smartHomeAcks)) {
                assertEquals(dePools.getValue(name).size, pool.size, "$language.$name: Variantenzahl wie DE erwartet")
            }
        }
    }

    /** Keine Nicht-DE-Sprache darf einen deutschen Satz durchreichen (Stichprobe über alle Pools). */
    @Test
    fun `keine Fremdsprache reicht einen deutschen Ack-Satz durch`() {
        val deAll = pools(LangDe.SMART_HOME_ACKS).flatMap { it.second }.toSet()
        for (language in Language.entries - Language.DE) {
            for ((name, pool) in pools(LanguagePackRegistry.forLanguage(language).smartHomeAcks)) {
                for (phrase in pool) {
                    assertFalse(phrase in deAll, "$language.$name: unübersetzter DE-Satz: '$phrase'")
                }
            }
        }
    }

    /** EN-Stichprobe am lebenden Formatter — echte englische Sätze, keine DE-Reste. */
    @Test
    fun `EN-Stichprobe - Licht, Klima, NoEffect und Unsupported sprechen Englisch`() {
        val en = LanguagePackRegistry.forLanguage(Language.EN).smartHomeAcks
        assertEquals("Light is on.", formatter.lightOn(null, Language.EN))
        assertEquals("Light is off.", formatter.lightOff(null, Language.EN))
        assertEquals("Set to 21 degrees.", formatter.climate(null, 21, Language.EN))
        assertEquals("Color is warm white.", formatter.lightColor("warm white", Language.EN))
        repeat(10) {
            assertTrue(en.unsupportedScene.contains(formatter.unsupported(SmartHomeAction.SCENE_ACTIVATE, Language.EN)))
            val dim = formatter.lightDim("Küche", 50, Language.EN)
            assertTrue(dim.contains("50") && dim.contains("percent"), "EN-Dim erwartet: '$dim'")
        }
    }

    /** ES-Stichprobe. */
    @Test
    fun `ES-Stichprobe - eigene Saetze, spanische Woerter`() {
        assertEquals("Luz encendida.", formatter.lightOn(null, Language.ES))
        assertEquals("A 21 grados.", formatter.climate(null, 21, Language.ES))
        repeat(10) {
            val dim = formatter.lightDim("Wohnzimmer", 40, Language.ES)
            assertTrue(dim.contains("por ciento"), "ES-Dim erwartet: '$dim'")
        }
    }

    /** FR-Stichprobe. */
    @Test
    fun `FR-Stichprobe - eigene Saetze, franzoesische Woerter`() {
        assertEquals("La lumière est allumée.", formatter.lightOn(null, Language.FR))
        assertEquals("À 21 degrés.", formatter.climate(null, 21, Language.FR))
        repeat(10) {
            val dim = formatter.lightDim("Flur", 40, Language.FR)
            assertTrue(dim.contains("pour cent"), "FR-Dim erwartet: '$dim'")
        }
    }

    /** IT-Stichprobe. */
    @Test
    fun `IT-Stichprobe - eigene Saetze, italienische Woerter`() {
        assertEquals("La luce è accesa.", formatter.lightOn(null, Language.IT))
        assertEquals("A 21 gradi.", formatter.climate(null, 21, Language.IT))
        repeat(10) {
            val dim = formatter.lightDim("Keller", 40, Language.IT)
            assertTrue(dim.contains("per cento"), "IT-Dim erwartet: '$dim'")
        }
    }

    /** Dieselbe Situation muss in fünf Sprachen fünf verschiedene Sätze ergeben. */
    @Test
    fun `dieselbe Tat klingt in jeder Sprache anders`() {
        val seen = Language.entries.map { language ->
            LanguagePackRegistry.forLanguage(language).smartHomeAcks.lightOnRoom.first()
        }
        assertEquals(seen.size, seen.toSet().size, "jede Sprache braucht einen eigenen Satz: $seen")
    }

    // ── (3) Nutzerdaten (HA-Raumnamen) werden NIE übersetzt ──────────────────

    /**
     * **Die eiserne Regel.** Ein Raumname kommt aus Home Assistant und ist ein
     * Eigenname von Andis Wohnung — „Wohnzimmer" bleibt „Wohnzimmer", auch im
     * englischen, spanischen, französischen und italienischen Satz. Geprüft über
     * ALLE raum-tragenden Formatter-Methoden und ALLE Sprachen.
     */
    @Test
    fun `HA-Raumnamen bleiben in JEDER Sprache unuebersetzt`() {
        for (language in Language.entries) {
            for (room in haRoomNames) {
                val outputs = buildList {
                    repeat(6) {
                        add(formatter.lightOn(room, language))
                        add(formatter.lightOff(room, language))
                        add(formatter.lightDim(room, 40, language))
                        add(formatter.climate(room, 21, language))
                        add(formatter.lightOnNoEffect(room, language))
                        add(formatter.lightOffNoEffect(room, language))
                        add(formatter.lightDimNoEffect(room, 40, language))
                        add(formatter.climateNoEffect(room, 21, language))
                        add(formatter.noEffect(SmartHomeAction.LIGHT_ON, room, language = language))
                        add(formatter.partialOffline(SmartHomeAction.LIGHT_ON, room, applied = 2, offline = 1, language = language))
                        add(formatter.partialOffline(SmartHomeAction.LIGHT_OFF, room, applied = 3, offline = 2, language = language))
                    }
                }
                for (out in outputs) {
                    assertTrue(
                        out.contains(room),
                        "$language: Raumname '$room' muss wörtlich im Satz stehen, war: '$out'",
                    )
                    assertFalse(out.contains("{"), "$language: ungefüllter Platzhalter in '$out'")
                }
            }
        }
    }

    /**
     * Gegenprobe zur Regel: ein Raumname, den NIEMAND übersetzen könnte (frei
     * erfunden, keine Wörterbuch-Entsprechung), überlebt jede Sprache unverändert
     * — inklusive Groß-/Kleinschreibung ab dem zweiten Zeichen und Umlauten.
     */
    @Test
    fun `frei erfundener Raumname ueberlebt jede Sprache unveraendert`() {
        val exotic = "Hobbyräumchen"
        for (language in Language.entries) {
            repeat(10) {
                val out = formatter.lightOn(exotic, language)
                assertTrue(out.contains(exotic), "$language: '$exotic' muss unverändert bleiben, war '$out'")
            }
        }
    }

    /**
     * Kleinschreibung wird — wie eh und je — nur im ERSTEN Buchstaben normalisiert
     * (`wohnzimmer` → `Wohnzimmer`), nicht übersetzt. Gilt in jeder Sprache gleich.
     */
    @Test
    fun `kleingeschriebener Raumname wird kapitalisiert, nicht uebersetzt`() {
        for (language in Language.entries) {
            repeat(6) {
                val out = formatter.lightOn("wohnzimmer", language)
                assertTrue(out.contains("Wohnzimmer"), "$language: erwartet kapitalisiert, war '$out'")
                assertFalse(out.contains("living"), "$language: darf NICHT übersetzt werden: '$out'")
            }
        }
    }

    /** Der Farbname ist ebenfalls Eingabe-Datum und wird nur eingesetzt. */
    @Test
    fun `Farbname wird in jeder Sprache eingesetzt statt uebersetzt`() {
        for (language in Language.entries) {
            val out = formatter.lightColor("türkis", language)
            assertTrue(out.contains("türkis"), "$language: Farbname muss erhalten bleiben: '$out'")
            assertNotEquals(out, formatter.lightColor(null, language), "mit/ohne Farbname unterscheiden sich")
        }
    }

    /** Alle Pools eines Packs als (Name, Pool) — eine Liste, damit kein Feld vergessen wird. */
    private fun pools(p: SmartHomeAckPack): List<Pair<String, List<String>>> = listOf(
        "lightOnRoom" to p.lightOnRoom,
        "lightOffRoom" to p.lightOffRoom,
        "lightDimRoom" to p.lightDimRoom,
        "lightDimNoRoom" to p.lightDimNoRoom,
        "scene" to p.scene,
        "coverOpen" to p.coverOpen,
        "coverClose" to p.coverClose,
        "climateRoom" to p.climateRoom,
        "unknown" to p.unknown,
        "lightOffNoEffectRoom" to p.lightOffNoEffectRoom,
        "lightOffNoEffectNoRoom" to p.lightOffNoEffectNoRoom,
        "lightOnNoEffectRoom" to p.lightOnNoEffectRoom,
        "lightOnNoEffectNoRoom" to p.lightOnNoEffectNoRoom,
        "lightDimNoEffectRoom" to p.lightDimNoEffectRoom,
        "lightDimNoEffectNoRoom" to p.lightDimNoEffectNoRoom,
        "coverOpenNoEffect" to p.coverOpenNoEffect,
        "coverCloseNoEffect" to p.coverCloseNoEffect,
        "climateNoEffectRoom" to p.climateNoEffectRoom,
        "climateNoEffectNoRoom" to p.climateNoEffectNoRoom,
        "genericNoEffect" to p.genericNoEffect,
        "lightOnPartialOfflineOne" to p.lightOnPartialOfflineOne,
        "lightOnPartialOfflineMany" to p.lightOnPartialOfflineMany,
        "lightOffPartialOfflineOne" to p.lightOffPartialOfflineOne,
        "lightOffPartialOfflineMany" to p.lightOffPartialOfflineMany,
        "partialOfflineNoRoom" to p.partialOfflineNoRoom,
        "unsupportedCover" to p.unsupportedCover,
        "unsupportedClimate" to p.unsupportedClimate,
        "unsupportedScene" to p.unsupportedScene,
        "unsupportedGeneric" to p.unsupportedGeneric,
        "lightOnNoRoom" to p.lightOnNoRoom,
        "lightOffNoRoom" to p.lightOffNoRoom,
        "lightDimNoValue" to p.lightDimNoValue,
        "climateValueNoRoom" to p.climateValueNoRoom,
        "climateNoValue" to p.climateNoValue,
        "lightColorNamed" to p.lightColorNamed,
        "lightColorUnnamed" to p.lightColorUnnamed,
    )
}
