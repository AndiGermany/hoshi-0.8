package de.hoshi.web

import de.hoshi.adapters.knowledge.CompositeGroundingPort
import de.hoshi.adapters.knowledge.OpenMeteoGeocodingClient
import de.hoshi.adapters.knowledge.Fts5GroundingAdapter
import de.hoshi.web.stub.GroundingStubAdapter
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist das Wiring der Wetter-Grounding-Naht in [PipelineConfig.groundingPort]:
 * `HOSHI_WEATHER_ENABLED` default OFF ist byte-neutral (exakt das bisherige Bean),
 * ON wickelt die Wiki-Scheibe in den [CompositeGroundingPort]. Reine
 * Konstruktor-Verdrahtung — kein Spring-Context, kein Netz.
 */
class PipelineConfigWeatherTest {

    private val config = PipelineConfig()
    private val bridge = "http://localhost:8035"
    private val meteo = "https://api.open-meteo.com"

    // Wetter-S1-Nahtparameter (Laufzeit-Ort): leerer Store (kein gespeicherter Ort ⇒
    // ENV-Seed-Verhalten) + Geocoding-Client — reine Konstruktion, kein Netz/IO.
    private val store = JsonFileWeatherLocationStore(
        java.nio.file.Files.createTempDirectory("weather-test").resolve("weather-location.json"),
    )
    private val geocoding = OpenMeteoGeocodingClient(baseUrl = "https://geocoding-api.open-meteo.com")

    @Test
    fun `Wetter OFF + Grounding OFF ist byte-neutral (reiner Stub, kein Composite)`() {
        val port = config.groundingPort(
            groundingEnabled = false, bridgeBaseUrl = bridge,
            weatherEnabled = false, weatherBaseUrl = meteo,
            weatherLat = 52.52, weatherLon = 13.41, weatherContractEnabled = false, weatherLabel = "Berlin",
            weatherLocationStore = store, geocodingClient = geocoding,
            extendedThinkEnabled = false, lookupPath = "", quoteFenceEnabled = true,
        )
        assertInstanceOf(GroundingStubAdapter::class.java, port, "OFF ⇒ unveränderter Stub")
        assertTrue(port !is CompositeGroundingPort, "OFF ⇒ kein Composite")
    }

    @Test
    fun `Wetter OFF + Grounding ON ist byte-neutral (reine Wiki-Scheibe, kein Composite)`() {
        val port = config.groundingPort(
            groundingEnabled = true, bridgeBaseUrl = bridge,
            weatherEnabled = false, weatherBaseUrl = meteo,
            weatherLat = 52.52, weatherLon = 13.41, weatherContractEnabled = false, weatherLabel = "Berlin",
            weatherLocationStore = store, geocodingClient = geocoding,
            extendedThinkEnabled = false, lookupPath = "", quoteFenceEnabled = true,
        )
        assertInstanceOf(Fts5GroundingAdapter::class.java, port, "Wetter OFF ⇒ unveränderte Wiki-Scheibe")
        assertTrue(port !is CompositeGroundingPort, "Wetter OFF ⇒ kein Composite")
    }

    @Test
    fun `Wetter ON wickelt die Grounding-Scheibe in den Composite`() {
        val port = config.groundingPort(
            groundingEnabled = true, bridgeBaseUrl = bridge,
            weatherEnabled = true, weatherBaseUrl = meteo,
            weatherLat = 52.52, weatherLon = 13.41, weatherContractEnabled = false, weatherLabel = "Berlin",
            weatherLocationStore = store, geocodingClient = geocoding,
            extendedThinkEnabled = false, lookupPath = "", quoteFenceEnabled = true,
        )
        assertInstanceOf(CompositeGroundingPort::class.java, port, "Wetter ON ⇒ Composite")
    }
}
