package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * **SettingsSkillsEndpointTest** — beweist am GEBOOTETEN Context den S2.3-Vertrag des
 * [SettingsController]: die Skills-Settings-API liegt hinter der [PerimeterWebFilter]-Wand,
 * GET spiegelt Decke/Store/Effektiv korrekt, PUT schreibt bei offener Decke (und der nächste
 * GET sieht es), bei zu Decke kommt ein ehrliches 409 und eine unbekannte id ist 404.
 *
 * Decken-Belegung dieses Tests: TOOLS=an, SCENES=aus, TIMER=an, CALCULATOR=aus ⇒
 *  - SMART_HOME offen, SCENES zu (tools UND scenes = an UND aus), TIMER offen, CALCULATOR zu.
 *
 * Der Laufzeit-Store zeigt auf eine frische Temp-Datei (`hoshi.settings.path`), startet also
 * leer ⇒ die lokalen Skills sind per runtimeDefault an. MOCK-Env ⇒ kein Loopback ⇒ die
 * Token-Wand greift wirklich.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_TOOLS_ENABLED=true",
        "HOSHI_SCENES_ENABLED=false",
        "HOSHI_TIMER_ENABLED=true",
        "HOSHI_CALCULATOR_ENABLED=false",
    ],
)
@AutoConfigureWebTestClient
class SettingsSkillsEndpointTest(@Autowired val client: WebTestClient) {

    private fun bearer() = HttpHeaders.AUTHORIZATION to "Bearer test-secret-token"

    @Test
    fun `GET ohne Token — 401 (hinter der Wand)`() {
        client.get().uri("/api/v1/settings/skills")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `PUT ohne Token — 401 (hinter der Wand)`() {
        client.put().uri("/api/v1/settings/skills/TIMER")
            .bodyValue(mapOf("enabled" to false))
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GET liefert alle Skills mit korrektem ceilingOpen und effective`() {
        client.get().uri("/api/v1/settings/skills")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(4)
            // Registry-Reihenfolge: SMART_HOME, SCENES, TIMER, CALCULATOR.
            .jsonPath("$[0].id").isEqualTo("SMART_HOME")
            .jsonPath("$[0].labelDe").isEqualTo("Smart-Home")
            .jsonPath("$[0].tier").isEqualTo("LOCAL")
            .jsonPath("$[0].ceilingOpen").isEqualTo(true)
            .jsonPath("$[0].effective").isEqualTo(true)
            .jsonPath("$[0].locked").isEqualTo(false)
            // SCENES: Decke zu (TOOLS UND SCENES = an UND aus) ⇒ effektiv aus, gesperrt.
            .jsonPath("$[1].id").isEqualTo("SCENES")
            .jsonPath("$[1].ceilingOpen").isEqualTo(false)
            .jsonPath("$[1].effective").isEqualTo(false)
            .jsonPath("$[1].locked").isEqualTo(true)
            .jsonPath("$[2].id").isEqualTo("TIMER")
            .jsonPath("$[2].ceilingOpen").isEqualTo(true)
            .jsonPath("$[2].effective").isEqualTo(true)
            .jsonPath("$[2].locked").isEqualTo(false)
            // CALCULATOR: Decke zu ⇒ effektiv aus, gesperrt.
            .jsonPath("$[3].id").isEqualTo("CALCULATOR")
            .jsonPath("$[3].ceilingOpen").isEqualTo(false)
            .jsonPath("$[3].effective").isEqualTo(false)
            .jsonPath("$[3].locked").isEqualTo(true)
    }

    @Test
    fun `PUT bei offener Decke — 200, persistiert und greift beim naechsten GET`() {
        // TIMER-Decke ist offen ⇒ der Store-Toggle greift.
        client.put().uri("/api/v1/settings/skills/TIMER")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("enabled" to false))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo("TIMER")
            .jsonPath("$.enabled").isEqualTo(false)
            .jsonPath("$.effective").isEqualTo(false)
            .jsonPath("$.locked").isEqualTo(false)

        // Der nächste GET sieht denselben Store-Zustand (gemeinsame Instanz).
        client.get().uri("/api/v1/settings/skills")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[2].id").isEqualTo("TIMER")
            .jsonPath("$[2].enabled").isEqualTo(false)
            .jsonPath("$[2].effective").isEqualTo(false)

        // Wiederherstellen, damit andere Testmethoden den Default-Zustand sehen.
        client.put().uri("/api/v1/settings/skills/TIMER")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("enabled" to true))
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `PUT bei zu Decke — 409 (beim Deploy deaktiviert)`() {
        // CALCULATOR-Decke ist zu ⇒ der Toggle greift nicht.
        client.put().uri("/api/v1/settings/skills/CALCULATOR")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("enabled" to true))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("deploy-disabled")
    }

    @Test
    fun `PUT unbekannte id — 404`() {
        client.put().uri("/api/v1/settings/skills/NOPE")
            .header(bearer().first, bearer().second)
            .bodyValue(mapOf("enabled" to true))
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error").isEqualTo("unknown-skill")
    }

    companion object {
        /** Frische Temp-Datei für den Laufzeit-Store (existiert anfangs nicht ⇒ leerer Store). */
        private val settingsFile: Path =
            Files.createTempDirectory("hoshi-settings-it").resolve("skills.json")

        @JvmStatic
        @DynamicPropertySource
        fun settingsPathProperty(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.settings.path") { settingsFile.toString() }
        }
    }
}
