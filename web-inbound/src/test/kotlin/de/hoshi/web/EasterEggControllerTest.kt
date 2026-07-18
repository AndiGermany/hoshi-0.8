package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **EasterEggControllerTest** — beweist, dass das Crew-Ritual am gebooteten
 * Context lebt UND oeffentlich ist.
 *
 * Bewusst `webEnvironment = MOCK` mit scharfer Wand (`enabled=true` + Token):
 * Mock-Requests haben KEINE `remoteAddress` ⇒ `isLoopback=false` (fail-closed).
 * Trotzdem muessen `/api/v1/crew|fortune|about` ohne Token 200 liefern — genau
 * wie `/api/health` (die [PerimeterWebFilter]-Ausnahme). Ein geschuetzter Pfad
 * (`/api/v1/ping`) bliebe hier 401 — Kontrast belegt das Scoping.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
    ],
)
@AutoConfigureWebTestClient
class EasterEggControllerTest(@Autowired val client: WebTestClient) {

    @Test
    fun `crew ist oeffentlich — 200 ohne Token, Roster als Array`() {
        client.get().uri("/api/v1/crew")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].name").isEqualTo("mira")
            .jsonPath("$[0].role").isEqualTo("PO + Persona-Wärme")
            .jsonPath("$[0].mantra").isEqualTo("Andi-Faktor schlägt Latenz.")
            // Captain steht zuletzt; fuenf Spezialisten + jules (2026-07-16) sind ergaenzt.
            .jsonPath("$[19].name").isEqualTo("jules")
            .jsonPath("$[19].mantra").isEqualTo("Ein Karton, ein Abend, eine Stimme.")
            .jsonPath("$[20].name").isEqualTo("kuhkuh")
            .jsonPath("$[20].mantra").isEqualTo(":)")
            .jsonPath("$[21].name").isEqualTo("andi")
    }

    @Test
    fun `fortune ist oeffentlich — 200 ohne Token, traegt einen Spruch`() {
        client.get().uri("/api/v1/fortune")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.text").value<String> { assert(it.startsWith("★ Hoshi sagt:")) }
            .jsonPath("$.totalAvailable").isEqualTo(34)
    }

    @Test
    fun `about ist oeffentlich — 200 ohne Token, traegt Team und Motto`() {
        client.get().uri("/api/v1/about")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.team").isEqualTo("Stellar Bloom")
            .jsonPath("$.motto").isEqualTo("warm. lokal. wach.")
            .jsonPath("$.captain").isEqualTo("andi")
            .jsonPath("$.crewSize").isEqualTo(22)
    }

    @Test
    fun `geschuetzter Pfad bleibt 401 — Crew-Ausnahme ist scoped`() {
        client.get().uri("/api/v1/ping")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
