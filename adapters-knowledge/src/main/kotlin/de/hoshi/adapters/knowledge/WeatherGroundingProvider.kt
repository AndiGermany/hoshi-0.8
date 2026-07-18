package de.hoshi.adapters.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.math.roundToInt

/**
 * **WeatherGroundingProvider** — eine zweite reale Grounding-Scheibe: statt der
 * lokalen Wikipedia (siehe [Fts5GroundingAdapter]) zapft sie für Wetter-Fragen die
 * freie **Open-Meteo**-API an (`https://api.open-meteo.com/v1/forecast`, KEIN
 * API-Key) und liefert einen kompakten deutschen „HINTERGRUND…"-Block in derselben
 * Form, die der [Fts5GroundingAdapter] nutzt.
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
     * ON ⇒ [buildBlock] umschließt Ortsname, Min/Max-Temperatur und Wetterlage
     * jeder Tages-Zeile mit «…» + hängt eine kurze WETTER-VERTRAG-Instruktion an
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
     * (Nicht-Wissens-Kategorie / keine Wetter-Absicht / API weg).
     * Niemals ein Fehler nach außen — Grounding ist best-effort.
     */
    override fun groundingBlock(query: String, category: RouteCategory): Mono<String> {
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
        val place = if (geocoding != null) explicitPlace(query) else null
        return if (place != null && geocoding != null) {
            explicitPlaceBlock(place, geocoding, reference)
        } else {
            configuredLocationBlock(reference)
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
    ): Mono<String> =
        geocoder.geocode(place)
            // Ops-Sichtbarkeit: WELCHER Ort diesen Turn grounded (Diagnose-Anker,
            // falls eine Antwort nach dem falschen Ort klingt — Log statt Raten).
            .doOnNext { log.info("[weather-grounding] Turn-Ort: {} (geocodet aus '{}')", it.label, place) }
            .flatMap { location -> forecastBlock(location, reference) }
            .switchIfEmpty(
                Mono.fromSupplier {
                    log.info("[weather-grounding] Geocode '{}' ohne Treffer — ehrlicher Hinweis statt Heimat-Fallback", place)
                    honestyBlock(place)
                },
            )
            .onErrorResume { e ->
                // Geocoding weg → EHRLICHER Hinweis statt stillem Heimat-Fallback.
                log.warn(
                    "[weather-grounding] Geocode '{}' fehlgeschlagen ({}) — ehrlicher Hinweis statt Heimat-Fallback",
                    place,
                    e.message,
                )
                Mono.just(honestyBlock(place))
            }

    /** Kein expliziter Ort ⇒ byte-identisch zum bisherigen Verhalten: konfigurierter Ort. */
    private fun configuredLocationBlock(reference: DayReferenceResolver.DayReference): Mono<String> =
        Mono.fromSupplier { configuredLocation() }
            .doOnNext { log.info("[weather-grounding] Turn-Ort: {} (konfiguriert)", it.label) }
            .flatMap { location -> forecastBlock(location, reference) }
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
     * kaschieren.
     */
    private fun honestyBlock(place: String): String =
        "\n\n---\n" +
            "WETTER-HINWEIS (nur für dich, im Gespräch NICHT erwähnen): " +
            "Der Ort „$place“ wurde nicht gefunden — keine Wetterdaten dafür. " +
            "Sag das ehrlich und biete den konfigurierten Ort an."

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
     */
    fun todayForecast(): Mono<TodayForecast> =
        Mono.fromSupplier { configuredLocation() }
            .flatMap { location ->
                fetchDailyJson(location).flatMap { body ->
                    val today = parseDays(body).firstOrNull { it.offset == 0 }
                    if (today == null) {
                        Mono.empty()
                    } else {
                        Mono.just(
                            TodayForecast(
                                label = location.label,
                                todayMin = today.tMin,
                                todayMax = today.tMax,
                                codeText = weatherCodeText(today.code, Lang.DE),
                                precipMm = today.precipMm,
                            ),
                        )
                    }
                }
            }

    /** Ein Forecast-Call für [location] → Block mit GENAU den Tagen aus [reference]. */
    private fun forecastBlock(
        location: WeatherLocation,
        reference: DayReferenceResolver.DayReference,
    ): Mono<String> =
        fetchDailyJson(location)
            .map { body ->
                val requested = parseDays(body).filter { it.offset in reference.offsets }
                buildBlock(requested, location.label, reference.explicit)
            }

    /** Der EINE Open-Meteo-`/v1/forecast`-Call (rohes JSON) — geteilt von Grounding- und Lese-Pfad. */
    private fun fetchDailyJson(location: WeatherLocation): Mono<String> =
        client.get()
            .uri { b ->
                b.path("/v1/forecast")
                    .queryParam("latitude", location.lat)
                    .queryParam("longitude", location.lon)
                    .queryParam("current", "temperature_2m,weathercode")
                    .queryParam("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode")
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
    )

    /** Parst die Open-Meteo `daily`-Arrays → bis zu [FORECAST_DAYS] Tage. Leer/kaputt → leere Liste. */
    private fun parseDays(body: String): List<Day> = runCatching {
        val daily = mapper.readTree(body).path("daily")
        if (daily.isMissingNode) return emptyList()
        val tMax = daily.path("temperature_2m_max")
        val tMin = daily.path("temperature_2m_min")
        val precip = daily.path("precipitation_sum")
        val codes = daily.path("weathercode")
        if (!tMax.isArray || tMax.size() == 0) return emptyList()
        val count = minOf(tMax.size(), FORECAST_DAYS)
        (0 until count).map { i ->
            Day(
                offset = i,
                tMin = tMin.numOrZero(i).roundToInt(),
                tMax = tMax.numOrZero(i).roundToInt(),
                precipMm = precip.numOrZero(i),
                code = codes.path(i).asInt(0),
            )
        }
    }.getOrElse { emptyList() }

    private fun JsonNode.numOrZero(i: Int): Double = this.path(i).asDouble(0.0)

    /**
     * Baut den kompakten deutschen Hintergrund-Block. Leere Liste → `""`.
     * Bei EXPLIZITER Tages-Referenz kommt ein Tages-Vertrag dazu (Muster
     * ZAHLEN-VERTRAG im [Fts5GroundingAdapter]): Antworte für den gefragten Tag,
     * nenne den Tag beim Namen. OHNE Referenz bleibt die ANWEISUNG byte-gleich
     * zum bisherigen Block.
     *
     * **WeatherNumberContract** ([enableWeatherContract] ON, s. Ctor-KDoc): jede
     * Tages-Zeile umschließt Ortsname, Min/Max-Temperatur und Wetterlage mit
     * [mark] («…») + [appendWeatherContract] hängt die Zitier-Instruktion an.
     * OFF ⇒ [mark] ist die Identität ⇒ Zeile und Block bleiben byte-identisch
     * zum bisherigen Verhalten (s. Pin-Tests).
     */
    private fun buildBlock(forecastDays: List<Day>, label: String, explicitDay: Boolean): String {
        if (forecastDays.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n---\n")
        sb.append("HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n")
        forecastDays.forEach { d ->
            sb.append("• Wetter ").append(mark(label)).append(' ').append(days.dayLabel(d.offset)).append(": ")
                .append(mark(d.tMin.toString())).append(" bis ").append(mark(d.tMax.toString())).append(" Grad, ")
                .append(mark(weatherCodeText(d.code, Lang.DE))).append(", ")
                .append(precipText(d.precipMm)).append(".\n")
        }
        sb.append(
            "ANWEISUNG: Nutze diese ECHTEN Wetterdaten und antworte knapp im eigenen warmen Stil — " +
                "erfinde nichts dazu und erwähne nie „die API“, „Open-Meteo“ oder „den Text“.",
        )
        if (explicitDay) {
            sb.append(" Antworte für den gefragten Tag; nenne den Tag beim Namen.")
        }
        appendWeatherContract(sb)
        return sb.toString()
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
    private fun appendWeatherContract(sb: StringBuilder) {
        if (!enableWeatherContract) return
        sb.append("\n")
        sb.append("WETTER-VERTRAG: Die Werte in «» oben (Ort, Temperaturen, Wetterlage) sind exakt. ")
        sb.append("Nenne sie genau so weiter — gleicher Ortsname, gleiche Ziffern, gleiche Einheit — ")
        sb.append("nicht runden, nicht umformulieren, keinen anderen Ort oder Wert erfinden.")
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

    /** Niederschlags-Hinweis: ab ~0,5 mm konkret, sonst „kaum Niederschlag". */
    private fun precipText(mm: Double): String =
        if (mm >= 0.5) "etwa ${mm.roundToInt()} mm Niederschlag" else "kaum Niederschlag"

    // ── Wetter-Absichts-Erkennung ───────────────────────────────────────────────

    /**
     * Erkennt eine WETTER-Absicht in [query] (DE + EN). Bewusst substring-basiert und
     * knapp gehalten; das Kategorie-Gate (FACT_SHORT/NEEDS_WEB/AMBIG) fängt SMART_HOME-
     * Komfort-Phrasen vorher ab, sodass die Schlüsselwörter hier nicht überfeuern.
     * `internal` für die Unit-Tests.
     */
    internal fun isWeatherIntent(query: String): Boolean = weatherIntent(query)

    /** Sprache der Code→Text-Tabelle. */
    internal enum class Lang { DE, EN }

    /**
     * Wire-/Lese-Vertrag der heutigen Vorhersage ([todayForecast], Read-Endpoint
     * `GET /api/v1/weather/today`): das wirksame Orts-Label, gerundete Min/Max-
     * Temperatur (°C), der deutsche Lagen-Text ([weatherCodeText]) und die
     * Niederschlags-Summe in mm — exakt die Werte, die auch der Grounding-Block
     * dem Brain gibt (eine Wahrheit, zwei Leser).
     */
    data class TodayForecast(
        val label: String,
        val todayMin: Int,
        val todayMax: Int,
        val codeText: String,
        val precipMm: Double,
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
         * Wissens-Kategorien-Gate (FACT_SHORT/NEEDS_WEB/AMBIG) als geteilte
         * companion-Wahrheit: Grounding-Block UND Wetter-Orts-Nachfrage
         * ([WeatherLocationAskAdapter]) gaten identisch.
         */
        internal fun isKnowledgeCategory(category: RouteCategory): Boolean =
            category == RouteCategory.FACT_SHORT ||
                category == RouteCategory.NEEDS_WEB ||
                category == RouteCategory.AMBIG

        /**
         * Pure companion-Wahrheit der Wetter-Absicht (DE+EN, substring-basiert) —
         * die Instanz-Methode [isWeatherIntent] delegiert hierher, der
         * [WeatherLocationAskAdapter] liest DIESELBE Liste (nie zwei Wahrheiten).
         */
        internal fun weatherIntent(query: String): Boolean {
            val q = query.lowercase()
            return WEATHER_KEYWORDS.any { q.contains(it) }
        }

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
            "zwei", "drei", "vier", "fünf", "fuenf",
            "paar", "etwa", "circa", "ca", "ungefähr", "ungefaehr",
            "kurzem", "kürze", "kuerze", "zukunft", "urlaub",
        )

        /**
         * DE + EN Schlüssel für die Wetter-Absicht. Mehrwort-Formen für die sonst
         * mehrdeutigen „warm/kalt" (nur „wie warm/kalt wird" zählt, nicht bloßes „warm").
         */
        private val WEATHER_KEYWORDS: List<String> = listOf(
            // DE
            "wetter", "regnet", "regen", "sonne", "sonnig", "schnee", "temperatur",
            "vorhersage", "wie warm wird", "wie kalt wird", "morgen draußen", "morgen draussen",
            "grad draußen", "grad draussen", "bewölkt", "bewoelkt",
            // EN
            "weather", "rain", "forecast", "temperature", "how warm", "how cold", "sunny", "snow",
        )

        /**
         * **WMO-Wettercode → Text (DE/EN).** Essenz aus Hoshi 0.5
         * `WeatherService.weatherCodeToGerman`, hier um die englischen Pendants ergänzt.
         * Deckt die gängigen Lagen ab (klar/bewölkt/Nebel/Niesel/Regen/Schnee/Schauer/
         * Gewitter); unbekannte Codes → „wechselhaft" / „changeable". `internal` für Tests.
         */
        internal fun weatherCodeText(code: Int, lang: Lang): String = when (code) {
            0 -> if (lang == Lang.DE) "klar und sonnig" else "clear and sunny"
            1 -> if (lang == Lang.DE) "überwiegend klar" else "mostly clear"
            2 -> if (lang == Lang.DE) "teilweise bewölkt" else "partly cloudy"
            3 -> if (lang == Lang.DE) "bedeckt" else "overcast"
            45 -> if (lang == Lang.DE) "neblig" else "foggy"
            48 -> if (lang == Lang.DE) "gefrierender Nebel" else "freezing fog"
            51 -> if (lang == Lang.DE) "leichter Nieselregen" else "light drizzle"
            53 -> if (lang == Lang.DE) "mäßiger Nieselregen" else "moderate drizzle"
            55 -> if (lang == Lang.DE) "starker Nieselregen" else "dense drizzle"
            56, 57 -> if (lang == Lang.DE) "gefrierender Nieselregen" else "freezing drizzle"
            61 -> if (lang == Lang.DE) "leichter Regen" else "light rain"
            63 -> if (lang == Lang.DE) "mäßiger Regen" else "moderate rain"
            65 -> if (lang == Lang.DE) "starker Regen" else "heavy rain"
            66, 67 -> if (lang == Lang.DE) "gefrierender Regen" else "freezing rain"
            71 -> if (lang == Lang.DE) "leichter Schneefall" else "light snow"
            73 -> if (lang == Lang.DE) "mäßiger Schneefall" else "moderate snow"
            75 -> if (lang == Lang.DE) "starker Schneefall" else "heavy snow"
            77 -> if (lang == Lang.DE) "Schneekörner" else "snow grains"
            80 -> if (lang == Lang.DE) "leichte Regenschauer" else "light rain showers"
            81 -> if (lang == Lang.DE) "mäßige Regenschauer" else "moderate rain showers"
            82 -> if (lang == Lang.DE) "starke Regenschauer" else "violent rain showers"
            85 -> if (lang == Lang.DE) "leichte Schneeschauer" else "light snow showers"
            86 -> if (lang == Lang.DE) "starke Schneeschauer" else "heavy snow showers"
            95 -> if (lang == Lang.DE) "Gewitter" else "thunderstorm"
            96 -> if (lang == Lang.DE) "Gewitter mit Hagel" else "thunderstorm with hail"
            99 -> if (lang == Lang.DE) "Gewitter mit starkem Hagel" else "thunderstorm with heavy hail"
            else -> if (lang == Lang.DE) "wechselhaft" else "changeable"
        }
    }
}
