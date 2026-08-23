package de.hoshi.web

import de.hoshi.adapters.news.MultiSourceCurrentAffairsAdapter
import de.hoshi.core.port.CurrentAffairsSourceId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **NewsSourcesSettingsController** — der Settings-Rand der aktiven
 * Nachrichten-Quellen (Tagesschau/heise/Golem), Muster
 * [LanguageSettingsController]/[WeatherLocationController]: ein schlanker
 * `@RestController` hinter der [PerimeterWebFilter]-Wand.
 *
 * **Bekannte/wählbare Quellen** sind exakt
 * [MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES] — dieselben drei,
 * für die `adapters-news` tatsächlich einen Feed-Adapter besitzt
 * ([de.hoshi.adapters.news.FeedSourceDefinition.WAVE_1]). Weitere
 * [CurrentAffairsSourceId]-Werte (DLF, NETZPOLITIK) existieren im Enum als
 * Vorgriff auf spätere Wellen, sind hier aber NICHT wählbar — ein PUT mit
 * einem solchen Wert wird wie jede unbekannte Quelle abgelehnt.
 *
 * Endpoints:
 *  - GET /api/v1/settings/news-sources → {aktiv:[...], verfuegbar:[...]} —
 *    Store-Wert, sonst die drei Defaults (eine Wahrheit, zwei Leser: dieser
 *    Controller und [NewsAdapterConfig]s Supplier).
 *  - PUT /api/v1/settings/news-sources → Body {aktiv:[...]}. Jede unbekannte
 *    Quellen-Id ⇒ 422 (unknown-source), KEIN Store-Write; Persist
 *    fehlgeschlagen ⇒ 500 (ehrlich, KEIN fake-200); sonst Store-Write
 *    bewiesen ⇒ 200 + neuer Zustand (Readback). Ein explizit leeres `aktiv`
 *    ist gültig und bleibt leer (bindende Semantik, s. [JsonFileNewsSourcesStore]).
 */
@RestController
class NewsSourcesSettingsController(
    private val store: JsonFileNewsSourcesStore,
) {

    @GetMapping("/api/v1/settings/news-sources")
    fun newsSources(): NewsSourcesView = view()

    @PutMapping("/api/v1/settings/news-sources")
    fun setNewsSources(@RequestBody body: NewsSourcesRequest): ResponseEntity<Any> {
        val requested = body.aktiv.orEmpty()
        val resolved = requested.map { raw -> raw to (runCatching { CurrentAffairsSourceId.valueOf(raw) }.getOrNull()) }
        val unknown = resolved.filter { (_, id) -> id == null || id !in KNOWN_SOURCES }.map { it.first }
        if (unknown.isNotEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(SettingsError("unknown-source", unknown.joinToString(","), "Unbekannte Quelle(n)."))
        }

        // Persist-then-commit: setActiveSources schreibt ZUERST atomar auf die
        // Platte und wirft, wenn das fehlschlägt (Cache dann unangetastet). 200
        // NUR bei bewiesenem Persist — nie fake-grün.
        val sources = resolved.mapNotNull { it.second }.toSet()
        val persisted = runCatching { store.setActiveSources(sources) }
        if (persisted.isFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SettingsError("persist-failed", SETTING_ID, "Konnte die Quellen-Auswahl nicht dauerhaft speichern."))
        }
        return ResponseEntity.ok(view())
    }

    /** Der eine Settings-Zustand: Store-Wert (Readback), sonst die drei Defaults. */
    private fun view(): NewsSourcesView {
        val aktiv = store.activeSources() ?: MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES
        return NewsSourcesView(
            aktiv = aktiv.sortedBy(CurrentAffairsSourceId::ordinal).map { it.name },
            verfuegbar = KNOWN_SOURCES.sortedBy(CurrentAffairsSourceId::ordinal).map { it.name },
        )
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [LanguageSettingsController.SETTING_ID]). */
        const val SETTING_ID = "news-sources"

        /** Die einzigen wählbaren Quellen — s. Klassen-KDoc. */
        val KNOWN_SOURCES: Set<CurrentAffairsSourceId> = MultiSourceCurrentAffairsAdapter.DEFAULT_ACTIVE_SOURCES
    }
}

/**
 * Wire-Vertrag: die aktiven Quellen-Ids + die vollständige wählbare Liste
 * (beide sortiert nach Enum-Ordinal, für ein stabiles JSON). Ids sind die
 * rohen [CurrentAffairsSourceId]-Namen (z.B. `"TAGESSCHAU"`) — dieselbe rohe
 * Form wie `source` in [CurrentAffairsItemWire].
 */
data class NewsSourcesView(
    val aktiv: List<String>,
    val verfuegbar: List<String>,
)

/** PUT-Body: die gewünschten aktiven Quellen-Ids (z.B. `{"aktiv":["TAGESSCHAU"]}`); leer ⇒ bewusst keine Quelle aktiv. */
data class NewsSourcesRequest(val aktiv: List<String>?)
