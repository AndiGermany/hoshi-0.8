package de.hoshi.adapters.knowledge

/**
 * **WeatherLocation** — der eine aufgelöste Wetter-Standort (Anzeige-Label +
 * Koordinaten), wie ihn der [WeatherGroundingProvider] anfragt und der
 * Geocoding-Client ([OpenMeteoGeocodingClient]) liefert.
 *
 * Lebt bewusst hier im Knowledge-Adapter (nicht in `core-domain`): der Ort ist
 * ein reines Wetter-Grounding-Detail, kein Pipeline-Begriff. Der Laufzeit-Store
 * (`web-inbound`, `JsonFileWeatherLocationStore`) persistiert genau diese drei
 * Felder als JSON (`~/.hoshi/weather-location.json`).
 */
data class WeatherLocation(
    /** Anzeige-Name im HINTERGRUND-Block (z.B. „Duisburg"). */
    val label: String,
    /** Breitengrad. */
    val lat: Double,
    /** Längengrad. */
    val lon: Double,
)
