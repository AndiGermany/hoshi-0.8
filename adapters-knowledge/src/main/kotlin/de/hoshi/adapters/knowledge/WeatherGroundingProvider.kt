package de.hoshi.adapters.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * **WeatherGroundingProvider** — eine zweite reale Grounding-Scheibe: statt der
 * lokalen Wikipedia (siehe [Fts5GroundingAdapter]) zapft sie für Wetter-Fragen die
 * freie **Open-Meteo**-API an (`https://api.open-meteo.com/v1/forecast`, KEIN
 * API-Key) und liefert einen kompakten „HINTERGRUND…"-Block in derselben Form,
 * die der [Fts5GroundingAdapter] nutzt — seit 2026-07-25 KOMPLETT in der
 * Turn-Sprache (Rahmen + Daten, s. [WeatherBlockTexts]), nicht mehr nur mit
 * übersetzter Wetterlage in einem deutschen Rahmen.
 *
 * Ziel: „Wie wird das Wetter morgen?" → Hoshi antwortet mit einer ECHTEN, geerdeten
 * Vorhersage (nicht halluziniert). Die Essenz (Open-Meteo `daily`-Forecast +
 * WMO-Code→Text) ist aus Hoshi 0.5 `WeatherService`/`WeatherProvider` portiert — die
 * Sprach-Templates des 0.5-Dienstes bleiben dort: hier geben wir nur die FAKTEN als
 * Hintergrund, die warme Formulierung macht der Brain (wie beim Wiki-Grounding).
 *
 * **Tages-Szenarien (smarte Injection):** der [DayReferenceResolver] erkennt die
 * gefragten Tage („morgen", „übermorgen", ein Wochentag, „am Wochenende") und der
 * Block enthält NUR diese Tage — jede Zeile mit präzisem Label („Wetter Duisburg am
 * Donnerstag (in 4 Tagen): …"). OHNE Tages-Referenz bleibt es beim bisherigen
 * heute+morgen-Block (byte-gleich, inkl. unveränderter ANWEISUNG).
 *
 * **Laufzeit-Ort:** der optionale [locationSupplier] (Settings-Store, pro Turn
 * billig aus dem Cache gelesen) GEWINNT gegen die Ctor-Seeds (lat/lon/Label aus dem
 * Deploy-ENV); liefert er `null` (nie ein Ort gespeichert), greifen EXAKT die Seeds
 * — heutiges Verhalten. Ein EXPLIZITER Ort in der Frage („Wetter morgen in
 * Duisburg?") wird — nur bei Wetter-Absicht und nur wenn ein [geocoding]-Client
 * verdrahtet ist — EINMALIG für diesen Turn geocodet (NICHT gespeichert).
 *
 * **Ehrlichkeit statt stillem Heimat-Fallback:** schlägt der Geocode für einen
 * EXPLIZITEN Ort fehl oder bleibt ergebnislos, wird NICHT mehr still der
 * konfigurierte Heimat-Ort verwendet (Bug: „Wie ist das Wetter in Kairo?" hätte
 * wortgleich das Ruhrgebiet-Wetter geliefert — der Brain hätte unwissentlich
 * gelogen). Stattdessen liefert [explicitPlaceBlock] einen ehrlichen
 * WETTER-HINWEIS-Block: der Brain erfährt, dass der Ort nicht gefunden wurde,
 * und soll das offen sagen statt Heimat-Daten unterzuschieben. KEIN expliziter
 * Ort ⇒ [configuredLocationBlock] bleibt byte-identisch zum bisherigen Verhalten.
 *
 * Spring-entkoppelt wie [Fts5GroundingAdapter] (kein `@Service`): Konfiguration über
 * Konstruktor (lat/lon/Label), WebClient intern gebaut.
 *
 * **Best-effort** (1:1 zur Grounding-Doktrin): Nicht-Wissens-Kategorie, keine
 * Wetter-Absicht, Timeout, Netzfehler oder kaputtes JSON → leerer Block (`""`). Der
 * [de.hoshi.core.pipeline.TurnPromptAssembler] schichtet dann nichts ein, der Turn
 * läuft NIE in einen Crash und wird nie blockiert.
 *
 * **Default-OFF / byte-neutral:** der Adapter wird nur gebaut, wenn
 * `HOSHI_WEATHER_ENABLED=true` (Wiring in `PipelineConfig`). Bei OFF existiert er gar
 * nicht — das bestehende `hoshi turn`-Verhalten ändert sich NICHT.
 */
class WeatherGroundingProvider(
    /** Open-Meteo-Basis-URL (überschreibbar für Tests/Mirror). */
    baseUrl: String = "https://api.open-meteo.com",
    /** Standort-Breitengrad (Default Berlin) — über `hoshi.weather.lat`/`HOSHI_WEATHER_LAT` setzbar. */
    private val lat: Double = DEFAULT_LAT,
    /** Standort-Längengrad (Default Berlin) — über `hoshi.weather.lon`/`HOSHI_WEATHER_LON` setzbar. */
    private val lon: Double = DEFAULT_LON,
    /** Anzeige-Name des Standorts im Block (Default „Berlin"). */
    private val locationLabel: String = DEFAULT_LABEL,
    private val timeout: Duration = Duration.ofSeconds(4),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    /**
     * Laufzeit-Ort (Settings-Store): liefert den GESPEICHERTEN Ort oder `null`
     * (nie einer gesetzt ⇒ die Ctor-Seeds lat/lon/[locationLabel] greifen —
     * heutiges Verhalten byte-gleich). `null`-Supplier ⇒ reine Ctor-Werte.
     */
    private val locationSupplier: (() -> WeatherLocation?)? = null,
    /**
     * Einmal-Geocode für einen EXPLIZITEN Ort in der Frage („… in Duisburg?").
     * `null` (Default) ⇒ kein Geocode, exakt das bisherige Verhalten.
     */
    private val geocoding: OpenMeteoGeocodingClient? = null,
    /**
     * **WeatherNumberContract** (geplant als `HOSHI_WEATHER_CONTRACT_ENABLED`,
     * Wiring in `web-inbound PipelineConfig.groundingPort` NICHT Teil dieser
     * Scheibe) — default OFF, byte-neutral. Reused den BESTEHENDEN «»-Marker-Vertrag
     * ([Fts5GroundingAdapter.appendNumberContract] /
     * `TurnOrchestrator.CONTRACT_MARKERS`), erfindet KEINEN zweiten Marker-
     * Dialekt: [de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers]
     * strippt JEDES «»‹›-Zeichen aus dem finalen Delta bereits generisch,
     * unabhängig davon, WER es gesetzt hat (Wiki-Zahl oder Wetter-Wert) — die
     * Wand steht schon, dieser Adapter muss nur noch markieren.
     *
     * ON ⇒ [buildBlock] umschließt Ortsname, Tagesbezug, Min/Max-Temperatur
     * und Wetterlage jeder Tages-Zeile mit «…» + hängt eine kurze
     * WETTER-VERTRAG-Instruktion an
     * (Muster ZAHLEN-VERTRAG). Live-Befunde, die das motivieren: „Wetter
     * morgen?" trug im Block 17,1–22,7°, gesprochen wurde „17–20" (Obergrenze
     * verstümmelt); „Wetter in Kairo?" grounded nachweislich Kairo, die
     * Antwort klang trotzdem nach Heimat-Werten — das 4B paraphrasiert den
     * HINTERGRUND-Block frei statt Zahlen/Ort wörtlich zu übernehmen.
     *
     * OFF ⇒ [buildBlock] byte-identisch zum bisherigen Block (kein Marker,
     * keine Zusatz-Instruktion) — exakt die WikiNumberContract-Garantie.
     */
    private val enableWeatherContract: Boolean = false,
    /** Tages-Referenzen der Frage (Clock-injiziert, Europe/Berlin). */
    private val days: DayReferenceResolver = DayReferenceResolver(),
) : GroundingPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .build()

    /**
     * Holt für eine WETTER-Frage einen kompakten Hintergrund-Block (NUR die
     * referenzierten Tage; ohne Referenz heute+morgen) aus Open-Meteo, oder `""`
     * (Nicht-Wissens-Kategorie / keine Wetter-Absicht / API weg). Niemals ein
     * Fehler nach außen — Grounding ist best-effort.
     *
     * **[language] ist die TURN-Sprache** (der Assembler reicht `ctx.language`
     * durch) und bestimmt den KOMPLETTEN Block: Kopfzeile, Tages-Zeilen inkl.
     * Tagesbezug, Wetterlage, Niederschlag, ANWEISUNG und (falls an) den
     * Wetter-Vertrag. Es gab hier bis 2026-07-25 einen zweiten, sprachlosen
     * Overload plus ein Ctor-Sprach-Feld als Fallback — beides ist weg: die
     * Sprache kommt jetzt ausschließlich pro Turn herein und kann deshalb weder
     * still verloren gehen noch von einer Adapter-Konfiguration überstimmt
     * werden. (Die Anzeigesprache von [todayForecast]/`GET /api/v1/weather/today`
     * ist eine ANDERE Frage — bis 2026-07-25 bewusst unberührt gelassen, seitdem
     * ebenfalls geschlossen: [todayForecast] nimmt jetzt ein eigenes
     * `displayLanguage`-Argument, s. dessen KDoc und
     * `vault/tracks/prep/PREP-i18n-backend-restklassen.md`.)
     */
    override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> {
        // Kategorie-Gate (identisch zum Fts5GroundingAdapter): nur Wissens-Kategorien
        // grounden. „mir ist kalt" (SMART_HOME-Komfort) o.ä. erreicht uns so nie.
        // (Companion-Wahrheit [isKnowledgeCategory] — geteilt mit der Wetter-Orts-
        // Nachfrage im WeatherLocationAskAdapter; Verhalten byte-identisch.)
        if (!isKnowledgeCategory(category)) {
            return Mono.just("")
        }
        // Absichts-Gate: nur echte Wetter-Fragen anstoßen — sonst kein API-Call,
        // damit der Composite zur Wiki-Scheibe durchfallen kann. WICHTIG: an
        // dieser Stelle steht die Wetter-Absicht schon fest — [explicitPlace]
        // darf deshalb auch kleingeschriebene Orts-Kandidaten akzeptieren
        // (Voice/STT-Transkripte sind oft komplett kleingeschrieben), ohne dass
        // harmlose Substrings anderswo überfeuern.
        if (!isWeatherIntent(query)) return Mono.just("")

        val reference = days.resolve(query)
        // Jenseits der Reichweite: ehrlich sagen — und zwar OHNE Open-Meteo zu
        // fragen. Es gibt für den Tag keine Daten, also gibt es auch nichts zu
        // holen; ein Call wäre reine Netz-Last für eine Antwort, die schon
        // feststeht. Gilt auch mit explizitem Ort: der Horizont bindet zuerst.
        if (reference.beyondHorizon && reference.offsets.isEmpty()) {
            return Mono.just(horizonBlock(language))
        }
        val place = if (geocoding != null) explicitPlace(query) else null
        return if (place != null && geocoding != null) {
            explicitPlaceBlock(place, geocoding, reference, language)
        } else {
            configuredLocationBlock(reference, language)
        }
    }

    /**
     * Pfad für einen EXPLIZITEN Ort in der Frage („… in Kairo?"): Treffer ⇒
     * Forecast-Block für den geocodeten Ort (NICHT gespeichert, nur dieser
     * Turn). Kein Treffer ODER Geocode-Fehler ⇒ **kein stiller Fallback** auf
     * den Heimat-Ort mehr — stattdessen ein ehrlicher [honestyBlock], der dem
     * Brain sagt, dass der Ort nicht gefunden wurde (statt ihm Heimat-Daten
     * unterzuschieben, siehe Klassen-Doc „Ehrlichkeit statt stillem…").
     */
    private fun explicitPlaceBlock(
        place: String,
        geocoder: OpenMeteoGeocodingClient,
        reference: DayReferenceResolver.DayReference,
        language: Language,
    ): Mono<String> =
        geocoder.geocode(place)
            // Ops-Sichtbarkeit: WELCHER Ort diesen Turn grounded (Diagnose-Anker,
            // falls eine Antwort nach dem falschen Ort klingt — Log statt Raten).
            .doOnNext { log.info("[weather-grounding] Turn-Ort: {} (geocodet aus '{}')", it.label, place) }
            .flatMap { location -> forecastBlock(location, reference, language) }
            .switchIfEmpty(
                Mono.fromSupplier {
                    log.info("[weather-grounding] Geocode '{}' ohne Treffer — ehrlicher Hinweis statt Heimat-Fallback", place)
                    honestyBlock(place, language)
                },
            )
            .onErrorResume { e ->
                // Geocoding weg → EHRLICHER Hinweis statt stillem Heimat-Fallback.
                log.warn(
                    "[weather-grounding] Geocode '{}' fehlgeschlagen ({}) — ehrlicher Hinweis statt Heimat-Fallback",
                    place,
                    e.message,
                )
                Mono.just(honestyBlock(place, language))
            }

    /** Kein expliziter Ort ⇒ byte-identisch zum bisherigen Verhalten: konfigurierter Ort. */
    private fun configuredLocationBlock(reference: DayReferenceResolver.DayReference, language: Language): Mono<String> =
        Mono.fromSupplier { configuredLocation() }
            .doOnNext { log.info("[weather-grounding] Turn-Ort: {} (konfiguriert)", it.label) }
            .flatMap { location -> forecastBlock(location, reference, language) }
            .defaultIfEmpty("")
            .onErrorResume { e ->
                // API tot / Timeout / Parse → best-effort leerer Block, nie Crash.
                log.warn("[weather-grounding] Open-Meteo nicht erreichbar/Fehler ({}) — leerer Block", e.message)
                Mono.just("")
            }

    /**
     * Der ehrliche Hinweis-Block für einen EXPLIZITEN Ort, den Open-Meteo nicht
     * auflösen konnte — Gegenstück zu [buildBlock] im selben Block-Baustil
     * (Trenner + „…, im Gespräch NICHT erwähnen"-Marker), aber ohne Fakten:
     * der Brain soll die Lücke offen benennen statt sie mit Heimat-Wetter zu
     * kaschieren. Folgt der Turn-Sprache ([WeatherBlockTexts.placeNotFound]) —
     * eine deutsche Ehrlichkeits-Anweisung in einem englischen Turn wäre genau
     * derselbe Sprachbruch wie beim Datenblock. Der ORTSNAME selbst wird nie
     * übersetzt (Nutzer-/Weltdatum).
     */
    private fun honestyBlock(place: String, language: Language): String =
        "\n\n---\n" + WeatherBlockTexts.placeNotFound(language, place)

    /**
     * Schwester zu [honestyBlock] für die ZEIT-Grenze statt der Orts-Grenze: die
     * Frage zielte über die [FORECAST_DAYS]-Reichweite hinaus („nächsten
     * Samstag", „nächste Woche"). Derselbe Baustil, dieselbe Doktrin — die Lücke
     * offen benennen statt sie mit dem nächstbesten Tag zu füllen.
     */
    private fun horizonBlock(language: Language): String =
        "\n\n---\n" + WeatherBlockTexts.beyondHorizon(language, FORECAST_DAYS)

    /** Store-Wert gewinnt; nie gespeichert (`null`) ⇒ ENV-Seed aus dem Ctor. */
    private fun configuredLocation(): WeatherLocation =
        locationSupplier?.invoke() ?: WeatherLocation(label = locationLabel, lat = lat, lon = lon)

    /**
     * **Kleiner Lese-Pfad** für den Read-Endpoint `GET /api/v1/weather/today`
     * (Idle-Gesicht): die HEUTIGE Vorhersage am KONFIGURIERTEN Ort (Store-Wert
     * gewinnt, sonst die Ctor-Seeds) — EXAKT der Datenpfad des Grounding-Blocks,
     * nichts dupliziert: derselbe Forecast-Call ([fetchDailyJson]), dasselbe
     * [parseDays], dieselbe [weatherCodeText]-Tabelle. [groundingBlock] bleibt
     * byte-gleich unverändert.
     *
     * Ehrlichkeit ANDERS als beim Grounding: der Turn-Pfad schluckt Fehler
     * (best-effort leerer Block, der Turn darf nie brechen) — ein READ-Endpoint
     * darf das nicht. Hier PROPAGIEREN Fehler (Open-Meteo weg/Timeout ⇒
     * Error-Mono) und „keine heutigen Daten" (kaputtes/leeres JSON) ist ein
     * LEERES Mono; der Controller macht daraus einen ehrlichen HTTP-Fehler
     * statt Fake-Werten.
     *
     * **[displayLanguage] (Fix 2026-07-25, PREP-i18n-backend-restklassen.md):**
     * bis heute war dies hart `Language.DE`, UNABHÄNGIG von der UI-Sprache — im
     * englischen Modus zeigte die Wetter-Kachel „17–31° · bedeckt" (deutscher
     * Zustandstext in einer englischen Kachel). Der Aufrufer ([WeatherTodayReader]
     * via [de.hoshi.web.WeatherTodayController]) liest jetzt die AKTIVE
     * Anzeigesprache aus dem [de.hoshi.web.JsonFileLanguageStore] (dasselbe Muster
     * wie `TtsSettingsController.activeLanguage`/`TtsRuntimeConfig`) und reicht sie
     * hier durch. Default bleibt [Language.DE] — byte-neutral für jeden Aufrufer,
     * der (wie die Bestands-Tests) keine Sprache übergibt.
     *
     * **Flur-Fertigstellung 2026-07-27 (additive Felder, KEIN neuer API-Call):**
     * derselbe [fetchDailyJson]-Body trägt jetzt auch `current` (Jetzt-Temperatur/
     * -Lage, [parseCurrent]), den morgigen Tag ([parseDays]-Offset 1),
     * `daily.sunrise`/`sunset` ([parseSunTimes]) und den neuen `hourly`-Block
     * ([parseHourly]) — bisher wurde alles außer heute verworfen. Jedes neue Feld
     * ist EINZELN best-effort: fehlt/kaputt ⇒ `null` (bzw. leere Liste bei
     * [TodayForecast.hourly]), NIE ein erfundener Wert. Alt-Clients, die die neuen
     * Felder nicht kennen, ignorieren sie (additiver Wire-Vertrag).
     */
    fun todayForecast(displayLanguage: Language = Language.DE): Mono<TodayForecast> =
        Mono.fromSupplier { configuredLocation() }
            .flatMap { location ->
                fetchDailyJson(location).flatMap { body ->
                    val days = parseDays(body)
                    val today = days.firstOrNull { it.offset == 0 }
                    if (today == null) {
                        Mono.empty()
                    } else {
                        val tomorrow = days.firstOrNull { it.offset == 1 }
                        val (nowTemp, nowCodeText) = parseCurrent(body, displayLanguage)
                        val (sunrise, sunset) = parseSunTimes(body)
                        Mono.just(
                            TodayForecast(
                                label = location.label,
                                todayMin = today.tMin,
                                todayMax = today.tMax,
                                codeText = weatherCodeText(today.code, displayLanguage),
                                precipMm = today.precipMm,
                                nowTemp = nowTemp,
                                nowCodeText = nowCodeText,
                                tomorrowMin = tomorrow?.tMin,
                                tomorrowMax = tomorrow?.tMax,
                                tomorrowCodeText = tomorrow?.let { weatherCodeText(it.code, displayLanguage) },
                                sunriseEpochMs = sunrise,
                                sunsetEpochMs = sunset,
                                hourly = parseHourly(body),
                                // Derselbe [parseDays]-Aufruf, der oben schon
                                // heute+morgen lieferte — nur werden die übrigen
                                // fünf Tage jetzt nicht mehr weggeworfen.
                                outlook = days.map { d ->
                                    DayOutlook(
                                        offset = d.offset,
                                        dateIso = d.dateIso,
                                        tempMin = d.tMin,
                                        tempMax = d.tMax,
                                        codeText = weatherCodeText(d.code, displayLanguage),
                                        precipMm = d.precipMm,
                                        precipProbability = d.precipProbability,
                                    )
                                },
                            ),
                        )
                    }
                }
            }

    /** Ein Forecast-Call für [location] → Block mit GENAU den Tagen aus [reference]. */
    private fun forecastBlock(
        location: WeatherLocation,
        reference: DayReferenceResolver.DayReference,
        language: Language,
    ): Mono<String> =
        fetchDailyJson(location)
            .map { body ->
                val requested = parseDays(body).filter { it.offset in reference.offsets }
                buildBlock(
                    forecastDays = requested,
                    label = location.label,
                    reference = reference,
                    // JETZT-Werte nur holen, wenn HEUTE überhaupt im Bild ist —
                    // bei „Wie wird's am Donnerstag?" hat die aktuelle Temperatur
                    // im Block nichts verloren (sie würde den Tagesbezug nur
                    // verwässern, den [WeatherBlockTexts.explicitDaySuffix] gerade
                    // schärft).
                    now = if (0 in reference.offsets) parseNow(body, language) else NowSnapshot(),
                    todayRain = if (0 in reference.offsets) parseTodayRain(body) else null,
                    language = language,
                )
            }

    /**
     * Der EINE Open-Meteo-`/v1/forecast`-Call (rohes JSON) — geteilt von Grounding- und
     * Lese-Pfad. **Flur-Fertigstellung 2026-07-27** (KEIN neuer Call, nur zwei ZUSÄTZLICHE
     * Parameter am BESTEHENDEN Request, additiv geparst in [todayForecast]):
     *  - `daily` bekommt `sunrise,sunset` dazu (heute-Zeile „hell bis …"/„hell ab …").
     *  - `hourly=temperature_2m,precipitation_probability` ist der EINZIGE wirklich neue
     *    Datenpunkt (Stunden-Verlauf + Regenwahrscheinlichkeit fürs Jetzt-Band).
     * **Mehrtage + JETZT 2026-08-21 (wieder KEIN neuer Call — drei zusätzliche FELDER
     * am BESTEHENDEN Request, deshalb unverändert EIN Fetch pro Cache-Rhythmus):**
     *  - `current` bekommt `precipitation` dazu — der EINZIGE Wert, der ehrlich sagt,
     *    ob es GERADE regnet (Andis Livetest: Tages-Summe ≠ Gegenwart, s. [buildBlock]).
     *  - `daily` bekommt `precipitation_probability_max` dazu (Regenwahrscheinlichkeit
     *    je Tag für den Sieben-Tage-Ausblick [TodayForecast.outlook]).
     *  - `hourly` bekommt `precipitation` dazu — erlaubt [parseTodayRain], den Tages-
     *    Niederschlag in „schon gefallen" und „noch erwartet" zu TRENNEN, statt das
     *    Brain die Zeitform raten zu lassen.
     */
    private fun fetchDailyJson(location: WeatherLocation): Mono<String> =
        client.get()
            .uri { b ->
                b.path("/v1/forecast")
                    .queryParam("latitude", location.lat)
                    .queryParam("longitude", location.lon)
                    .queryParam("current", "temperature_2m,weathercode,precipitation")
                    .queryParam(
                        "daily",
                        "temperature_2m_max,temperature_2m_min,precipitation_sum," +
                            "precipitation_probability_max,weathercode,sunrise,sunset",
                    )
                    .queryParam("hourly", "temperature_2m,precipitation_probability,precipitation")
                    .queryParam("forecast_days", FORECAST_DAYS)
                    .queryParam("timezone", "Europe/Berlin")
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)

    /** Eine geparste Tages-Vorhersage (0 = heute, 1 = morgen, … bis 6). */
    private data class Day(
        val offset: Int,
        val tMin: Int,
        val tMax: Int,
        val precipMm: Double,
        val code: Int,
        /** `daily.precipitation_probability_max` in % — `null`, wenn Open-Meteo sie nicht liefert. */
        val precipProbability: Int? = null,
        /** `daily.time[i]` („2026-06-28") — leer, wenn das Array fehlt (Wire-Feld [DayOutlook.dateIso]). */
        val dateIso: String = "",
    )

    /**
     * **Der AUGENBLICK** (`current`-Node) — Auftrag 2b. Jedes Feld einzeln
     * best-effort `null`: der Node ist bei Open-Meteo nicht garantiert, und ein
     * fehlender Wert wird WEGGELASSEN, nie geraten (ein geratener Jetzt-Wert war
     * genau der Livetest-Fehler, nur andersherum).
     *
     * [precipMm] ist der Schlüsselwert: `current.weathercode` kann „leichter
     * Regen" sagen, während `current.precipitation` 0,0 mm meldet (Schauerlage,
     * gerade Pause). Beide Fakten gehen so, wie sie sind, in den Block — das
     * Brain bekommt die Wahrheit, nicht unsere Interpretation.
     */
    private data class NowSnapshot(
        val tempC: Int? = null,
        val codeText: String? = null,
        val precipMm: Double? = null,
        val observedClock: String? = null,
    ) {
        /** Nichts Brauchbares da ⇒ der Block muss den Ausweich kennzeichnen ([WeatherBlockTexts.nowUnavailable]). */
        val isEmpty: Boolean get() = tempC == null && codeText == null && precipMm == null
    }

    /**
     * Der HEUTIGE Niederschlag, in „schon gefallen" und „noch erwartet" getrennt
     * (Summen der `hourly.precipitation`-Stunden vor bzw. ab `current.time`).
     *
     * WARUM: die Tages-Summe allein trägt KEINE Zeitform. „3,4 mm heute" kann
     * heißen, dass es morgens geschüttet hat und jetzt trocken ist — oder dass
     * der Regen erst abends kommt. Andis Fall war der erste, und ohne diese
     * Aufteilung bleibt dem Brain nur Raten. Mit ihr steht die Zeitform als
     * FAKT im Block ([WeatherBlockTexts.todayRainFallen]/[todayRainAhead]).
     */
    private data class TodayRain(val fallenMm: Double, val aheadMm: Double)

    /** Parst die Open-Meteo `daily`-Arrays → bis zu [FORECAST_DAYS] Tage. Leer/kaputt → leere Liste. */
    private fun parseDays(body: String): List<Day> = runCatching {
        val daily = mapper.readTree(body).path("daily")
        if (daily.isMissingNode) return emptyList()
        val tMax = daily.path("temperature_2m_max")
        val tMin = daily.path("temperature_2m_min")
        val precip = daily.path("precipitation_sum")
        val codes = daily.path("weathercode")
        if (!tMax.isArray || tMax.size() == 0) return emptyList()
        val probs = daily.path("precipitation_probability_max")
        val count = minOf(tMax.size(), FORECAST_DAYS)
        (0 until count).map { i ->
            Day(
                offset = i,
                tMin = tMin.numOrZero(i).roundToInt(),
                tMax = tMax.numOrZero(i).roundToInt(),
                precipMm = precip.numOrZero(i),
                code = codes.path(i).asInt(0),
                // Fehlt das Feld (Alt-Mirror, kanned Test-JSON) ⇒ `null`, NICHT 0 %:
                // „0 % Regenwahrscheinlichkeit" wäre eine erfundene Aussage,
                // „keine Angabe" ist die Wahrheit.
                precipProbability = probs.path(i).takeIf { it.isNumber }?.asInt(),
                dateIso = daily.path("time").path(i).asText(""),
            )
        }
    }.getOrElse { emptyList() }

    private fun JsonNode.numOrZero(i: Int): Double = this.path(i).asDouble(0.0)

    // ── Flur-Fertigstellung 2026-07-27: Jetzt/Morgen/Stunden/Sonne — ALLE aus dem
    // EINEN [fetchDailyJson]-Body, den der Provider ohnehin schon abruft. Jeder
    // Parser ist EINZELN best-effort (eigenes `runCatching`): ein kaputtes/
    // fehlendes Feld darf die anderen nicht mitreißen — [todayForecast] reicht
    // pro Feld `null`/leere Liste durch statt den ganzen Read-Pfad zu brechen. ──

    /**
     * `current.temperature_2m`/`current.weathercode` → (Jetzt-Temperatur gerundet,
     * Jetzt-Lage-Text in [language]). Der `current`-Node wurde SCHON IMMER
     * mitgeschickt ([fetchDailyJson]: `current=temperature_2m,weathercode`) und bis
     * heute komplett verworfen. Fehlt der Node oder ein einzelnes Feld darin (Open-
     * Meteo liefert `current` z.B. nicht mehr, oder ein Feld ist kein Zahl-Wert) ⇒
     * das jeweilige Feld wird `null` — NIE ein erfundener Wert, NIE ein Crash.
     */
    private fun parseCurrent(body: String, language: Language): Pair<Int?, String?> = runCatching {
        val current = mapper.readTree(body).path("current")
        val temp = current.path("temperature_2m").takeIf { it.isNumber }?.asDouble()?.roundToInt()
        val code = current.path("weathercode").takeIf { it.isIntegralNumber }?.asInt()
        temp to code?.let { weatherCodeText(it, language) }
    }.getOrElse { null to null }

    /**
     * Wie [parseCurrent], aber für den GROUNDING-Pfad: zusätzlich
     * `current.precipitation` (regnet es GERADE?) und `current.time` als
     * Frische-/Herkunfts-Marker ([WeatherBlockTexts.observedAt]).
     *
     * Bewusst NICHT in [parseCurrent] hineingebaut: dessen `Pair`-Rückgabe ist die
     * Wire-Naht von [todayForecast] (Kachel), diese hier ist die Prompt-Naht.
     * Beide lesen denselben Node aus demselben Body — kein zweiter Call, aber
     * auch keine Signatur, die zwei Aufgaben gleichzeitig trägt.
     */
    private fun parseNow(body: String, language: Language): NowSnapshot = runCatching {
        val current = mapper.readTree(body).path("current")
        if (current.isMissingNode) return NowSnapshot()
        val code = current.path("weathercode").takeIf { it.isIntegralNumber }?.asInt()
        NowSnapshot(
            tempC = current.path("temperature_2m").takeIf { it.isNumber }?.asDouble()?.roundToInt(),
            codeText = code?.let { weatherCodeText(it, language) },
            precipMm = current.path("precipitation").takeIf { it.isNumber }?.asDouble(),
            // „2026-06-28T12:00" → „12:00". Kein Zeit-Parsing nötig und keins
            // gewollt: der Block braucht die lokale Uhrzeit als TEXT, und ein
            // unerwartetes Format soll `null` ergeben statt zu werfen.
            observedClock = current.path("time").asText("")
                .substringAfter('T', "")
                .takeIf { it.matches(CLOCK_PATTERN) },
        )
    }.getOrElse { NowSnapshot() }

    /**
     * Teilt den HEUTIGEN Niederschlag an `current.time` in „schon gefallen" und
     * „noch erwartet" (Summen über `hourly.precipitation`). `null`, wenn der
     * `hourly`-Block oder `current.time` fehlt — dann bleibt es bei der reinen
     * Tages-Summe und der Block trifft KEINE Zeitform-Aussage (ehrlicher als eine
     * geratene).
     *
     * Nur Stunden DESSELBEN Kalendertags wie `current.time` zählen (Präfix-
     * Vergleich auf „yyyy-MM-dd"): der `hourly`-Block reicht über alle sieben
     * Tage, und morgiger Regen gehört nicht in die heutige Bilanz.
     */
    private fun parseTodayRain(body: String): TodayRain? = runCatching {
        val root = mapper.readTree(body)
        val hourly = root.path("hourly")
        val times = hourly.path("time")
        val precip = hourly.path("precipitation")
        if (!times.isArray || !precip.isArray || times.size() == 0) return null
        val currentTime = root.path("current").path("time").asText("")
        if (currentTime.isBlank()) return null
        val today = currentTime.substringBefore('T')
        var fallen = 0.0
        var ahead = 0.0
        for (i in 0 until minOf(times.size(), precip.size())) {
            val t = times.path(i).asText("")
            if (!t.startsWith(today)) continue
            val mm = precip.path(i).asDouble(0.0)
            // Die laufende Stunde zählt als „noch erwartet": sie ist nicht vorbei.
            if (t < currentTime) fallen += mm else ahead += mm
        }
        TodayRain(fallenMm = fallen, aheadMm = ahead)
    }.getOrNull()

    /**
     * `daily.sunrise[0]`/`daily.sunset[0]` (HEUTE, Index 0) → Epoch-Millisekunden.
     * Open-Meteo liefert diese als LOKALE ISO-Zeit ohne Zonen-Suffix (Muster
     * „2026-06-28T05:32", weil `timezone=Europe/Berlin` bereits im Request steht) —
     * [parseBerlinLocalTime] verankert sie explizit in [DayReferenceResolver.BERLIN]
     * statt UTC anzunehmen. Fehlend/kaputt ⇒ `null` (die Zeile verschwindet im FE).
     */
    private fun parseSunTimes(body: String): Pair<Long?, Long?> = runCatching {
        val daily = mapper.readTree(body).path("daily")
        val sunrise = parseBerlinLocalTime(daily.path("sunrise").path(0).asText(""))
        val sunset = parseBerlinLocalTime(daily.path("sunset").path(0).asText(""))
        sunrise to sunset
    }.getOrElse { null to null }

    /** Lokale (Europe/Berlin) ISO-Zeit ohne Zone (z.B. „2026-06-28T05:32") → Epoch-ms, oder `null`. */
    private fun parseBerlinLocalTime(iso: String): Long? = runCatching {
        LocalDateTime.parse(iso).atZone(DayReferenceResolver.BERLIN).toInstant().toEpochMilli()
    }.getOrNull()

    /**
     * Der NEUE `hourly`-Block (`temperature_2m`, `precipitation_probability"), auf die
     * nächsten [HOURLY_WINDOW] Stunden KOMPAKTIERT (Open-Meteo liefert `forecast_days
     * × 24` = bis zu 168 Punkte — das Jetzt-Band braucht nur einen kurzen Ausblick,
     * kein Sieben-Tage-Diagramm).
     *
     * **Start-Index:** `current.time` (dieselbe Zeitbasis wie [parseCurrent], vom
     * selben `current`-Node) markiert „jetzt" — der erste `hourly.time`-Eintrag, der
     * NICHT davor liegt (String-Vergleich reicht: ISO-`yyyy-MM-ddTHH:mm` sortiert
     * lexikalisch identisch zur echten Reihenfolge). Fehlt `current.time` (z.B.
     * kanned Test-JSON ohne dieses Feld) ⇒ Start bei Index 0 — konservativ, nie ein
     * Crash. Fehlt der `hourly`-Block ganz ⇒ leere Liste (die Sparkline/Regen-Zeile
     * verschwindet im FE, statt erfundene Stunden zu zeigen).
     */
    private fun parseHourly(body: String): List<HourPoint> = runCatching {
        val root = mapper.readTree(body)
        val hourly = root.path("hourly")
        val times = hourly.path("time")
        val temps = hourly.path("temperature_2m")
        val probs = hourly.path("precipitation_probability")
        if (!times.isArray || times.size() == 0) return emptyList()
        val currentTime = root.path("current").path("time").asText("")
        val startIndex = if (currentTime.isBlank()) {
            0
        } else {
            (0 until times.size()).firstOrNull { times.path(it).asText("") >= currentTime } ?: 0
        }
        val endIndex = minOf(startIndex + HOURLY_WINDOW, times.size())
        (startIndex until endIndex).mapNotNull { i ->
            val epochMs = parseBerlinLocalTime(times.path(i).asText("")) ?: return@mapNotNull null
            HourPoint(
                epochMs = epochMs,
                tempC = temps.numOrZero(i).roundToInt(),
                precipProbability = probs.path(i).asInt(0),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Baut den kompakten Hintergrund-Block. Leere Liste → `""`.
     *
     * **Der GANZE Rahmen folgt [language]** (Sprach-Naht 2026-07-25, vorher nur
     * die Wetterlage): Kopfzeile, Zeilen-Schablone, Tagesbezug
     * ([DayReferenceResolver.dayLabel]), Wetterlage ([weatherCodeText]),
     * Niederschlag, ANWEISUNG und Wetter-Vertrag kommen aus EINEM Katalog
     * ([WeatherBlockTexts]). Vorher war ein englischer Turn ein deutscher
     * Rahmen um einen englischen Katalog — das Brain bekam eine DEUTSCHE
     * Anweisung und antwortete entsprechend deutsch. **NICHT übersetzt wird das
     * Orts-Label** ([label]): Orts-/Nutzerdaten bleiben, wie sie sind.
     *
     * [language] = [Language.DE] hält den Block ZEICHENGLEICH zum bisherigen
     * Verhalten (Pin-Test-Garantie: die DE-Zweige des Katalogs sind eingefroren).
     *
     * Bei EXPLIZITER Tages-Referenz kommt ein Tages-Vertrag dazu (Muster
     * ZAHLEN-VERTRAG im [Fts5GroundingAdapter]): Antworte für den gefragten Tag,
     * nenne den Tag beim Namen. OHNE Referenz bleibt die ANWEISUNG byte-gleich
     * zum bisherigen Block.
     *
     * **WeatherNumberContract** ([enableWeatherContract] ON, s. Ctor-KDoc): jede
     * Tages-Zeile umschließt Ortsname, Tagesbezug, Min/Max-Temperatur und
     * Wetterlage mit [mark] («…») + [appendWeatherContract] hängt die
     * Zitier-Instruktion an. Der Tagesbezug gehört zum Vertrag, obwohl er kein
     * Messwert ist: er bindet die Messwerte an den vom Resolver bestimmten Tag
     * und ist damit Teil desselben geerdeten Fakts.
     * OFF ⇒ [mark] ist die Identität ⇒ Zeile und Block bleiben byte-identisch
     * zum bisherigen Verhalten (s. Pin-Tests).
     */
    private fun buildBlock(
        forecastDays: List<Day>,
        label: String,
        reference: DayReferenceResolver.DayReference,
        now: NowSnapshot,
        todayRain: TodayRain?,
        language: Language,
    ): String {
        if (forecastDays.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n---\n")
        sb.append(WeatherBlockTexts.head(language))

        // JETZT-Zeile ZUERST, wenn die Frage auf den Augenblick zielt: der Block
        // wird von oben nach unten gelesen, und was oben steht, prägt die Antwort.
        val nowLine = nowLine(now, label, language)
        if (reference.nowFocus && nowLine != null) sb.append(nowLine)

        forecastDays.forEach { d ->
            sb.append(
                WeatherBlockTexts.line(
                    language = language,
                    label = mark(label),
                    day = mark(days.dayLabel(d.offset, language)),
                    min = mark(d.tMin.toString()),
                    max = mark(d.tMax.toString()),
                    condition = mark(weatherCodeText(d.code, language)),
                    precip = precipText(d.precipMm, language),
                ),
            )
            // Die Zeitform-Aufteilung gehört DIREKT unter die heutige Zeile, auf
            // die sie sich bezieht — und nur, wenn heute überhaupt Niederschlag
            // vorkommt (sonst ist „bis jetzt nichts gefallen, nichts mehr
            // erwartet" eine Zeile ohne Aussage).
            if (d.offset == 0 && todayRain != null && d.precipMm >= PRECIP_THRESHOLD_MM) {
                sb.append(todayRainLine(todayRain, language))
            }
        }
        // Ohne nowFocus steht die JETZT-Zeile UNTER den Tagen: der Wert ist dann
        // Zusatz-Kontext, nicht die Antwort („Regnet es heute?" will das Tagesbild,
        // profitiert aber davon, dass Hoshi den Moment danebenlegen kann).
        if (!reference.nowFocus && nowLine != null) sb.append(nowLine)

        sb.append(WeatherBlockTexts.instruction(language))
        if (reference.explicit) {
            sb.append(WeatherBlockTexts.explicitDaySuffix(language))
        }
        if (reference.weekend) {
            sb.append(WeatherBlockTexts.weekendSuffix(language))
        }
        // Zeitform-Regel nur, wenn es eine JETZT-Zeile GIBT; fehlt sie, obwohl
        // nach dem Augenblick gefragt war, wird der Ausweich ehrlich markiert
        // (statt still die Tagesspanne als Gegenwart durchgehen zu lassen —
        // exakt Andis Livetest-Fehler).
        if (nowLine != null) {
            sb.append(WeatherBlockTexts.tenseInstruction(language))
        } else if (reference.nowFocus) {
            sb.append(WeatherBlockTexts.nowUnavailable(language))
        }
        // Teil-Antwort ehrlich kennzeichnen: „heute und nächste Woche" liefert
        // heute — und sagt dazu, dass der Rest jenseits der Reichweite liegt.
        if (reference.beyondHorizon) {
            sb.append("\n").append(WeatherBlockTexts.beyondHorizon(language, FORECAST_DAYS))
        }
        appendWeatherContract(sb, language)
        return sb.toString()
    }

    /**
     * Die fertige JETZT-Zeile, oder `null` wenn Open-Meteo keinen brauchbaren
     * `current`-Node lieferte. Fehlende EINZEL-Felder werden weggelassen, nicht
     * ersetzt — eine Zeile „14 Grad" ohne Lage ist ehrlich, „14 Grad, unbekannt"
     * wäre Füllmaterial.
     *
     * Die Werte tragen [mark] wie die Tages-Zeilen: sie sind exakt derselbe Sorte
     * geerdeter Fakt und gehören unter denselben Zitier-Vertrag (der Livetest
     * zeigte ja gerade, dass das Brain Zahlen gern frei paraphrasiert).
     */
    private fun nowLine(now: NowSnapshot, label: String, language: Language): String? {
        if (now.isEmpty) return null
        val parts = mutableListOf<String>()
        now.tempC?.let { parts += mark(WeatherBlockTexts.nowDegrees(language, it.toString())) }
        now.codeText?.let { parts += mark(it) }
        now.precipMm?.let {
            parts += mark(
                WeatherBlockTexts.nowPrecipitation(
                    language = language,
                    mm = it.roundToInt(),
                    measurable = it >= PRECIP_THRESHOLD_MM,
                ),
            )
        }
        now.observedClock?.let { parts += WeatherBlockTexts.observedAt(language, it) }
        return WeatherBlockTexts.nowLine(language, mark(label), parts.joinToString(", "))
    }

    /** Die Zeitform-Aufteilung des heutigen Niederschlags als eigene Unterzeile. */
    private fun todayRainLine(rain: TodayRain, language: Language): String {
        val fallen = WeatherBlockTexts.todayRainFallen(
            language = language,
            mm = rain.fallenMm.roundToInt(),
            measurable = rain.fallenMm >= PRECIP_THRESHOLD_MM,
        )
        val ahead = WeatherBlockTexts.todayRainAhead(
            language = language,
            mm = rain.aheadMm.roundToInt(),
            measurable = rain.aheadMm >= PRECIP_THRESHOLD_MM,
        )
        return "  ${mark(fallen)}, ${mark(ahead)}.\n"
    }

    /**
     * Umschließt [value] mit den BESTEHENDEN «»-Vertrags-Marken — dieselben vier
     * Zeichen, die [de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers]
     * unconditional aus jedem Brain-Delta strippt (kein neuer Marker-Dialekt).
     * [enableWeatherContract] OFF ⇒ Identität, [value] geht unverändert durch.
     */
    private fun mark(value: String): String = if (enableWeatherContract) "«$value»" else value

    /**
     * **WeatherNumberContract**-Instruktion (nur [enableWeatherContract] ON) —
     * Muster [Fts5GroundingAdapter.appendNumberContract]: kurz gehalten, jede
     * Zusatzregel kostet bei einem 4B Befolgung. Erklärt bewusst NICHT, was «»
     * bedeutet oder dass es beim Sprechen verschwindet — das ist Prompt-Interna;
     * die Marker-Hygiene selbst erledigt deterministisch die Wand
     * ([de.hoshi.core.pipeline.TurnOrchestrator.stripContractMarkers]), egal ob
     * diese Instruktion befolgt wird oder nicht.
     */
    private fun appendWeatherContract(sb: StringBuilder, language: Language) {
        if (!enableWeatherContract) return
        sb.append("\n")
        sb.append(WeatherBlockTexts.contract(language))
    }

    // ── Expliziter Ort in der Frage ─────────────────────────────────────────────

    /**
     * Erkenner für einen EXPLIZITEN Ort in der Frage: „in <Wort>" (optional
     * Bigram, z.B. „in Bad Homburg"), GROSS- **oder** kleingeschrieben (Voice-/
     * STT-Transkripte kommen oft komplett kleingeschrieben rein — „wetter in
     * kairo" muss denselben Ort finden wie „Wetter in Kairo"). Eng gehalten
     * durch [PLACE_STOPWORDS] (zeitliche/artikelhafte Wörter nach „in" wie
     * „der/zwei/paar/zukunft" ⇒ kein Ort). Kein Treffer → `null` → konfigurierter
     * Ort. Rest-False-positives („in Strömen") sind ungefährlich: die
     * Geocode-Validierung fängt sie — DANK der Ehrlichkeits-Fix (siehe Klassen-
     * Doc) jetzt mit einem ehrlichen Hinweis statt einem stillen Heimat-
     * Fallback. `internal` für die Unit-Tests.
     */
    internal fun explicitPlace(query: String): String? = placeInQuery(query)

    /**
     * Niederschlags-Hinweis in der Turn-Sprache: ab ~0,5 mm konkret, sonst
     * „kaum Niederschlag" (Schwelle + Rundung unverändert; nur der TEXT folgt
     * jetzt [WeatherBlockTexts.precipitation]).
     */
    private fun precipText(mm: Double, language: Language): String =
        WeatherBlockTexts.precipitation(language, mm.roundToInt(), measurable = mm >= PRECIP_THRESHOLD_MM)

    // ── Wetter-Absichts-Erkennung ───────────────────────────────────────────────

    /**
     * Erkennt eine WETTER-Absicht in [query] (DE + EN). Jedes Signal muss als
     * vollständiges Lexem vorkommen; sichere Wetter-Komposita/Beugungen stehen
     * ausdrücklich in der Positivliste. So bleibt „Sonnenschein" Wetter, während
     * „Sonnensystem"/„Sonnenfinsternis" nicht länger am Teilstring `sonne`
     * hängenbleiben (englisch entsprechend `rain` vs. `train`).
     * `internal` für die Unit-Tests.
     */
    internal fun isWeatherIntent(query: String): Boolean = weatherIntent(query)

    /**
     * Wire-/Lese-Vertrag der heutigen Vorhersage ([todayForecast], Read-Endpoint
     * `GET /api/v1/weather/today`): das wirksame Orts-Label, gerundete Min/Max-
     * Temperatur (°C), der deutsche Lagen-Text ([weatherCodeText]) und die
     * Niederschlags-Summe in mm — exakt die Werte, die auch der Grounding-Block
     * dem Brain gibt (eine Wahrheit, zwei Leser).
     *
     * **Additive Felder (Flur-Fertigstellung 2026-07-27)** — Alt-Clients (FE, das
     * die neuen Keys noch nicht kennt) ignorieren sie einfach, kein Breaking
     * Change: [nowTemp]/[nowCodeText] (der `current`-Node, JETZT endlich
     * ausgeliefert statt nur intern verworfen), [tomorrowMin]/[tomorrowMax]/
     * [tomorrowCodeText] (Offset 1 aus [parseDays]), [sunriseEpochMs]/
     * [sunsetEpochMs] (`daily.sunrise`/`sunset[0]`) und [hourly] (der neue
     * `hourly`-Parameter, auf [HOURLY_WINDOW] Stunden kompaktiert). JEDES Feld ist
     * EINZELN best-effort: fehlt/kaputt ⇒ `null` bzw. leere Liste — nie ein
     * erfundener Wert (das FE lässt die jeweilige Zeile dann ehrlich weg).
     */
    data class TodayForecast(
        val label: String,
        val todayMin: Int,
        val todayMax: Int,
        val codeText: String,
        val precipMm: Double,
        val nowTemp: Int? = null,
        val nowCodeText: String? = null,
        val tomorrowMin: Int? = null,
        val tomorrowMax: Int? = null,
        val tomorrowCodeText: String? = null,
        val sunriseEpochMs: Long? = null,
        val sunsetEpochMs: Long? = null,
        val hourly: List<HourPoint> = emptyList(),
        /**
         * **Der Sieben-Tage-Ausblick (Andi 2026-08-21: „warum nicht für mehr?").**
         * ADDITIV ANS ENDE (K4-Muster): jeder Bestandsleser — die Wetter-Kachel,
         * `weathertoday.test.ts`, jeder Alt-Client — sieht exakt die Felder, die er
         * vorher sah. Wer den Ausblick nicht kennt, ignoriert ihn.
         *
         * **Kein zusätzlicher Fetch:** die Tage stammen aus DEMSELBEN
         * [fetchDailyJson]-Body, aus dem schon heute+morgen kommen — Open-Meteo
         * liefert seit jeher [FORECAST_DAYS] Tage, sechs davon wurden bisher
         * geparst und weggeworfen. Der Poll-Rhythmus des FE (~10 min,
         * `useWeatherToday`) bleibt damit unangetastet.
         *
         * Enthält HEUTE als `offset = 0` — die Zeile ist damit selbsttragend und
         * ein Client muss sie nicht aus [todayMin]/[todayMax] zusammenstückeln.
         * Leere Liste = Open-Meteo lieferte keine lesbaren `daily`-Arrays (dann ist
         * aber auch der Rest leer und der Controller antwortet ehrlich 502).
         */
        val outlook: List<DayOutlook> = emptyList(),
    )

    /**
     * Ein Tag des Sieben-Tage-Ausblicks ([TodayForecast.outlook]) — Wire-Teil.
     *
     * [dateIso] („2026-06-28") statt Epoch-ms, anders als [HourPoint.epochMs]:
     * ein TAG ist ein Kalenderdatum, kein Zeitpunkt. Über Epoch-ms müsste jeder
     * Client wieder eine Zeitzone raten, um „welcher Tag ist das?" zu beantworten
     * — genau die Sorte Drift, die bei Sonnenauf-/-untergang schon einmal
     * korrigiert werden musste. [offset] (0 = heute) liegt daneben, damit das FE
     * „heute/morgen" beschriften kann, ohne selbst zu rechnen.
     *
     * [precipProbability] ist als EINZIGES Feld nullable: Open-Meteo liefert
     * `precipitation_probability_max` nicht überall (und ältere Mirrors gar
     * nicht). `null` heißt „keine Angabe" — das FE lässt das Regen-Prozent dann
     * weg, statt „0 %" zu behaupten.
     */
    data class DayOutlook(
        val offset: Int,
        val dateIso: String,
        /**
         * **`tempMin`/`tempMax`, NICHT `tMin`/`tMax`** — der Name ist hier
         * Wire-Vertrag, nicht Geschmack: Jackson leitet den JSON-Schlüssel aus dem
         * Java-Getter ab, und `tMin` wird über `getTMin()` zu **`tmin`**
         * (Bean-Konvention: nach einem einbuchstabigen Präfix wird
         * dekapitalisiert). Das FE hätte `outlook[].tmin` lesen müssen, während
         * daneben `todayMin` steht — eine Falle, die genau einmal beim
         * Serialisierungs-Test auffällt und danach nie wieder. Die internen
         * Parser-Felder heißen unverändert `tMin`/`tMax`; nur das Wire-Objekt
         * trägt die ausgeschriebenen Namen.
         */
        val tempMin: Int,
        val tempMax: Int,
        val codeText: String,
        val precipMm: Double,
        val precipProbability: Int? = null,
    )

    /**
     * Ein Stunden-Punkt des kompakten `hourly`-Verlaufs ([parseHourly]) — Wire-Teil
     * von [TodayForecast.hourly]. [epochMs] statt eines rohen ISO-Strings, damit das
     * FE dieselbe locale-bewusste Uhrzeit-Formatierung nutzen kann wie überall sonst
     * (Muster `dueClock` in `useScheduledItems.ts`), statt eine zweite Zeit-
     * Formatierung zu erfinden.
     */
    data class HourPoint(
        val epochMs: Long,
        val tempC: Int,
        val precipProbability: Int,
    )

    companion object {
        /**
         * **Code-Default-Seeds (Berlin)** — EINE Wahrheit für die Ctor-Defaults UND
         * das „kein Ort konfiguriert"-Kriterium der Wetter-Orts-Nachfrage
         * ([WeatherLocationAskAdapter]): stehen die Deploy-Seeds EXAKT auf diesen
         * Werten, hat das Deploy-ENV keinen echten Ort gesetzt.
         */
        const val DEFAULT_LAT: Double = 52.52
        const val DEFAULT_LON: Double = 13.41
        const val DEFAULT_LABEL: String = "Berlin"

        /**
         * Vorhersage-Horizont: 7 Tage, damit auch „am Donnerstag"/„am Wochenende"
         * (Offsets bis 6) beantwortbar sind. Injiziert werden trotzdem NUR die
         * referenzierten Tage — ohne Referenz bleibt der Block heute+morgen.
         */
        internal const val FORECAST_DAYS = 7

        /**
         * Kompaktierungs-Fenster von [parseHourly]: die nächsten 12 Stunden ab
         * `current.time` — genug für „Regen ab ~17 Uhr" oder eine Mini-Sparkline,
         * ohne die vollen bis zu 168 `hourly`-Punkte (7 Tage × 24 h) durchzureichen.
         */
        internal const val HOURLY_WINDOW = 12

        /**
         * **Die EINE Niederschlags-Schwelle** (mm): darunter heißt es „kaum"/„kein"
         * Niederschlag, darüber wird die Zahl genannt. Stand als nackte `0.5` in
         * [precipText] und wird jetzt von drei Stellen geteilt (Tages-Zeile,
         * JETZT-Zeile, Zeitform-Aufteilung) — eine Wahrheit, ein Grenzwert, sonst
         * behauptet der Block irgendwann gleichzeitig „kaum Niederschlag" und
         * „gerade Niederschlag" für denselben Messwert.
         */
        internal const val PRECIP_THRESHOLD_MM: Double = 0.5

        /** „HH:mm" — akzeptiertes Format des `current.time`-Uhrzeit-Teils. */
        private val CLOCK_PATTERN = Regex("""\d{2}:\d{2}""")

        /**
         * Wissens-Kategorien-Gate (FACT_SHORT/NEEDS_WEB/AMBIG) als geteilte
         * companion-Wahrheit: Grounding-Block UND Wetter-Orts-Nachfrage
         * ([WeatherLocationAskAdapter]) gaten identisch.
         */
        internal fun isKnowledgeCategory(category: RouteCategory): Boolean =
            category == RouteCategory.FACT_SHORT ||
                category == RouteCategory.NEEDS_WEB ||
                category == RouteCategory.AMBIG

        /**
         * Pure companion-Wahrheit der Wetter-Absicht (DE+EN, lexem-basiert) —
         * die Instanz-Methode [isWeatherIntent] delegiert hierher, der
         * [WeatherLocationAskAdapter] liest DIESELBE Regex (nie zwei Wahrheiten).
         *
         * Die selbst definierten Grenzen prüfen Unicode-Buchstaben/-Marken/
         * Ziffern/Unterstrich statt Java-`\b`: ein Bindestrich trennt deshalb
         * korrekt „Sonnencreme-Wetter", ein anschließender Buchstabe schützt aber
         * „Sonnenfinsternis". Keine Negativliste — neue `sonnen*`-Wörter werden
         * sicher nicht automatisch zu Wetter.
         */
        internal fun weatherIntent(query: String): Boolean = WEATHER_INTENT_PATTERN.containsMatchIn(query)

        /**
         * Pure companion-Wahrheit des EXPLIZITEN Orts in der Frage („in <Wort>",
         * [PLACE_PATTERN], GROSS- oder kleingeschrieben) — Instanz-Methode
         * [explicitPlace] delegiert hierher, der [WeatherLocationAskAdapter] nutzt
         * dasselbe Muster. Beide Aufrufer prüfen die Wetter-Absicht VOR dem Ort
         * (hier: [isWeatherIntent]/[weatherIntent] im Gate davor; im Adapter:
         * `weatherIntent(query) && placeInQuery(query) == null`) — deshalb ist es
         * sicher, hier großzügig auch kleingeschriebene Kandidaten zuzulassen und
         * nur per [PLACE_STOPWORDS] gegen Fehltreffer wie „in der Zukunft" oder
         * „in zwei Tagen" abzusichern (das erste Wort nach „in" entscheidet).
         */
        internal fun placeInQuery(query: String): String? {
            val candidate = PLACE_PATTERN.find(query)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: return null
            val firstWord = candidate.substringBefore(' ').lowercase()
            return candidate.takeUnless { firstWord in PLACE_STOPWORDS }
        }

        /**
         * „in <Wort/Bigram>" — ein „in" gefolgt von einem Wort aus Buchstaben +
         * Bindestrich (GROSS **oder** klein, [PLACE_STOPWORDS] filtert Fehltreffer
         * nach), maximal ein zweites Wort dazu (Bigram, z.B. „Bad Homburg"/„bad
         * homburg"). Ziffern („in 3 Tagen") matchen nicht — kein Buchstabe.
         */
        private val PLACE_PATTERN =
            Regex("""\bin\s+([A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß-]+(?:\s+[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß-]+)?)""")

        /**
         * Stoppwörter direkt nach „in", die NIE ein Ort sind (artikelhaft/
         * zeitlich/mengenmäßig) — verhindert, dass die kleingeschrieben-tolerante
         * Erweiterung von [PLACE_PATTERN] Phrasen wie „in der Zukunft", „in zwei
         * Tagen" oder „in einem Urlaub" fälschlich als Ortsnamen liest. Nur GROSS-
         * geschriebene Kandidaten waren vorher schon geschützt (echte Ortsnamen
         * werden großgeschrieben) — die Liste trägt jetzt die klein-tolerante
         * Erweiterung.
         */
        private val PLACE_STOPWORDS: Set<String> = setOf(
            "der", "die", "das", "den", "dem", "einer", "einem", "eine", "einen",
            "mein", "meine", "meinen", "meinem", "meiner", "meines",
            "dein", "deine", "deinen", "deinem", "deiner", "deines",
            "sein", "seine", "seinen", "seinem", "seiner", "seines",
            "ihr", "ihre", "ihren", "ihrem", "ihrer", "ihres",
            "unser", "unsere", "unseren", "unserem", "unserer", "unseres",
            "euer", "eure", "euren", "eurem", "eurer", "eures",
            "dessen", "deren",
            "zwei", "drei", "vier", "fünf", "fuenf",
            "paar", "etwa", "circa", "ca", "ungefähr", "ungefaehr",
            "kurzem", "kürze", "kuerze", "zukunft", "urlaub",
            "my", "your", "his", "her", "its", "our", "their",
        )

        /**
         * DE + EN Lexeme für die Wetter-Absicht. Echte, vormals über Teilstrings
         * mitgetroffene Beugungen/Komposita stehen einzeln hier; dadurch bleibt
         * die Positivfläche reviewbar. Mehrwort-Formen für die sonst mehrdeutigen
         * „warm/kalt" (nur „wie warm/kalt wird" zählt, nicht bloßes „warm").
         */
        private val WEATHER_KEYWORDS: List<String> = listOf(
            // DE
            "wetter", "wetterbericht", "wettervorhersage", "wetterlage", "wetterlagen",
            "regnet", "regen", "regenwetter", "regenwahrscheinlichkeit", "regenschauer",
            "regenfall", "regenfälle", "regenfaelle",
            "sonne", "sonnig", "sonnige", "sonnigen", "sonniger", "sonniges", "sonnigem",
            "sonnenschein",
            "schnee", "schneit", "schneefall", "schneeschauer", "schneesturm",
            "temperatur", "temperaturen", "vorhersage", "vorhersagen",
            "wie warm wird", "wie kalt wird", "morgen draußen", "morgen draussen",
            "grad draußen", "grad draussen",
            "bewölkt", "bewölkte", "bewölkten", "bewölkter", "bewölktes", "bewölktem",
            "bewoelkt", "bewoelkte", "bewoelkten", "bewoelkter", "bewoelktes", "bewoelktem",
            // EN
            "weather", "rain", "raining", "rainy", "rainfall", "rainfalls", "rainstorm",
            "rainstorms", "forecast", "forecasts", "forecasting",
            "temperature", "temperatures", "how warm", "how cold",
            "sunny", "sunshine", "snow", "snowing", "snowy", "snowfall", "snowstorm",
        )

        private val WEATHER_INTENT_PATTERN: Regex = Regex(
            pattern = "(?<![\\p{L}\\p{M}\\p{N}_])(?:" +
                WEATHER_KEYWORDS.joinToString("|") { Regex.escape(it) } +
                ")(?![\\p{L}\\p{M}\\p{N}_])",
            option = RegexOption.IGNORE_CASE,
        )

        /**
         * **WMO-Wettercode → Text, alle 5 Sprachen (DE/EN/ES/FR/IT).** Essenz aus
         * Hoshi 0.5 `WeatherService.weatherCodeToGerman`; die eigentliche Tabelle
         * lebt seit der Multilingual-Welle (2026-07-24) strukturiert in
         * [WeatherCodeTexts] (ein `when`-Block je Sprache statt code-für-code
         * interleaved) — diese Funktion bleibt nur als STABILE Signatur/API
         * erhalten (schon vor der Welle `internal` in Tests referenziert:
         * `WeatherGroundingProvider.weatherCodeText(code, language)`).
         * Deckt die gängigen Lagen ab (klar/bewölkt/Nebel/Niesel/Regen/Schnee/Schauer/
         * Gewitter); unbekannte Codes → „wechselhaft"/„changeable"/… (siehe
         * [WeatherCodeTexts] für die anderssprachigen Pendants). `internal` für Tests.
         *
         * **[language] ist die zentrale [Language]** (core-domain) — der bis
         * 2026-07-25 hier lebende modul-interne `Lang`-Enum samt `Language.toLang()`
         * ist entfallen: er war seit der Multilingual-Welle exakt isomorph zu
         * [Language] und damit eine zweite Sprach-Wahrheit ohne Modul- oder
         * Semantikgrenze (dieser Adapter kennt [Language] ohnehin — sie steht in
         * der [GroundingPort]-Signatur direkt darüber).
         */
        internal fun weatherCodeText(code: Int, language: Language): String =
            WeatherCodeTexts.text(code, language)
    }
}
