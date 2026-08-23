package de.hoshi.web

import de.hoshi.adapters.news.MultiSourceCurrentAffairsAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import java.nio.file.Path

/**
 * Direkter Konstruktor-Test von [NewsSourcesSettingsController] (kein Spring-
 * Context, kein Netz — Muster [LanguageSettingsControllerTest]/
 * [WeatherLocationControllerTest]): beweist die BEIDEN bindenden Fälle aus dem
 * Auftrag (fehlender Datensatz ⇒ drei Defaults; explizit leer gespeichert ⇒
 * bleibt leer), PUT unbekannt ⇒ 422 kein Store-Write, Persist-Readback und
 * dass die Persistenz einen Neustart überlebt.
 */
class NewsSourcesSettingsControllerTest {

    private fun controller(
        dir: Path,
        store: JsonFileNewsSourcesStore = JsonFileNewsSourcesStore(dir.resolve("news-sources.json")),
    ) = NewsSourcesSettingsController(store)

    @Test
    fun `GET ohne Store-Wert - aktiv sind die drei Defaults, verfuegbar ebenso`(@TempDir dir: Path) {
        val view = controller(dir).newsSources()
        val expected = MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES.map { it.name }.toSet()
        assertEquals(expected, view.aktiv.toSet())
        assertEquals(expected, view.verfuegbar.toSet())
        assertEquals(3, view.aktiv.size)
    }

    @Test
    fun `PUT ein Teil-Set (nur TAGESSCHAU) - 200, Store persistiert, GET spiegelt genau das`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        val response = controller(dir, store).setNewsSources(NewsSourcesRequest(aktiv = listOf("TAGESSCHAU")))

        assertEquals(HttpStatus.OK, response.statusCode)
        val view = response.body as NewsSourcesView
        assertEquals(listOf("TAGESSCHAU"), view.aktiv)
        assertEquals(setOf(de.hoshi.core.port.CurrentAffairsSourceId.TAGESSCHAU), store.activeSources())
    }

    @Test
    fun `PUT ein explizit leeres Set - 200, bleibt leer (kein Rueckfall auf die Defaults)`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        val response = controller(dir, store).setNewsSources(NewsSourcesRequest(aktiv = emptyList()))

        assertEquals(HttpStatus.OK, response.statusCode)
        val view = response.body as NewsSourcesView
        assertTrue(view.aktiv.isEmpty(), "ein explizit leeres PUT bleibt leer, GET darf NICHT auf die Defaults zurueckfallen")
        assertEquals(emptySet<de.hoshi.core.port.CurrentAffairsSourceId>(), store.activeSources())
    }

    @Test
    fun `PUT unbekannte Quelle - 422, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        val response = controller(dir, store).setNewsSources(NewsSourcesRequest(aktiv = listOf("TAGESSCHAU", "BILD")))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-source", (response.body as SettingsError).error)
        assertNull(store.activeSources(), "eine unbekannte Quelle darf NIE mit-persistiert werden")
    }

    @Test
    fun `PUT eine Quelle ohne Adapter (DLF) - 422, weil nicht in KNOWN_SOURCES`(@TempDir dir: Path) {
        val store = JsonFileNewsSourcesStore(dir.resolve("news-sources.json"))
        val response = controller(dir, store).setNewsSources(NewsSourcesRequest(aktiv = listOf("DLF")))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertNull(store.activeSources())
    }

    @Test
    fun `Persistenz ueberlebt Reload - ein NEUER Controller ueber demselben Pfad sieht den PUT-Wunsch`(@TempDir dir: Path) {
        val path = dir.resolve("news-sources.json")
        val first = controller(dir, JsonFileNewsSourcesStore(path))
        first.setNewsSources(NewsSourcesRequest(aktiv = listOf("HEISE", "GOLEM")))

        val restarted = controller(dir, JsonFileNewsSourcesStore(path))
        assertEquals(setOf("HEISE", "GOLEM"), restarted.newsSources().aktiv.toSet())
    }
}
