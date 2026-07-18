package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * **WebConfigServeFrontendIT** — beweist das FE-Serving am GEBOOTETEN Context
 * mit `hoshi.web.serve-frontend=true` (die anderen Web-Tests booten mit dem
 * Default OFF; dieser schliesst die ON-Luecke). Static-Dir ist ein Temp-Verzeichnis
 * mit echtem `index.html` + `assets/app.js`.
 *
 * Bewiesen:
 *  1. `/` liefert das index.html-Bundle (200) — der Root-Router greift.
 *  2. `/assets/app.js` wird statisch ausgeliefert (200) — token-frei (public).
 *  3. Eine unbekannte SPA-Route faellt auf index.html (200) — Client-Routing.
 *  4. `/api/v1/ping` bleibt token-geschuetzt (401) — FE-Serving schwaecht die
 *     Auth-Wand NICHT.
 *  5. `/api/health` bleibt oeffentlich (200).
 *
 * MOCK-WebEnvironment ⇒ kein remoteAddress ⇒ nicht-loopback ⇒ die Token-Wand
 * ist scharf (gespiegelt zu [PerimeterWallTest]).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "hoshi.web.serve-frontend=true",
    ],
)
@AutoConfigureWebTestClient
class WebConfigServeFrontendIT(@Autowired val client: WebTestClient) {

    @Test
    fun `root liefert das index html Bundle — 200`() {
        client.get().uri("/")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `statisches Asset wird token-frei ausgeliefert — 200`() {
        client.get().uri("/assets/app.js")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `unbekannte SPA-Route faellt auf index html — 200`() {
        client.get().uri("/uebersicht")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `api bleibt token-geschuetzt trotz FE-Serving — 401`() {
        client.get().uri("/api/v1/ping")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `health bleibt oeffentlich — 200`() {
        client.get().uri("/api/health")
            .exchange()
            .expectStatus().isOk
    }

    companion object {
        /** Temp-Bundle: index.html + assets/app.js, einmalig beim Klassen-Load erzeugt. */
        private val staticDir: Path = Files.createTempDirectory("hoshi-fe-it").also { d ->
            Files.writeString(d.resolve("index.html"), "<!doctype html><title>hoshi</title>")
            val assets = Files.createDirectory(d.resolve("assets"))
            Files.writeString(assets.resolve("app.js"), "console.log('hoshi 0.8')")
        }

        @JvmStatic
        @DynamicPropertySource
        fun staticDirProperty(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.web.static-dir") { staticDir.toString() }
        }
    }
}
