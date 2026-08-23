package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
 *  6. Accept-Encoding-Verhandlung auf die vorkomprimierten `.br`/`.gz`-Dateien,
 *     die der Vite-Build danebenlegt (siehe [WebConfig]-KDoc).
 *
 * Der `.br`/`.gz`-Inhalt im Temp-Bundle sind bewusst MARKER-Bytes, kein echtes
 * Brotli: der Resolver dekomprimiert nichts, sein Kontrakt ist „liefere
 * `<datei>.br` statt `<datei>` aus und setze `Content-Encoding`". Genau das
 * misst der Test — byte-genau und ohne eine Brotli-Testabhaengigkeit (die JDK
 * bringt keinen Brotli-Codec mit). Dass die Build-Ausgabe echtes, vom Browser
 * dekodierbares Brotli ist, beweist der Build selbst plus der curl-Test gegen
 * einen echten Serve (RESULT.md).
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

    @Test
    fun `Accept-Encoding br liefert die vorkomprimierte br-Datei aus`() {
        client.get().uri("/assets/app.js")
            .header("Accept-Encoding", "br, gzip")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Content-Encoding", "br")
            // Ohne Vary duerfte ein geteilter Cache die br-Antwort an einen
            // Client ohne Accept-Encoding weiterreichen.
            .expectHeader().valueEquals("Vary", "Accept-Encoding")
            .expectBody().consumeWith { r ->
                assertArrayEquals(BROTLI_MARKER, r.responseBody ?: ByteArray(0))
            }
    }

    @Test
    fun `Accept-Encoding gzip liefert die vorkomprimierte gz-Datei aus`() {
        client.get().uri("/assets/app.js")
            .header("Accept-Encoding", "gzip")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Content-Encoding", "gzip")
            .expectBody().consumeWith { r ->
                assertArrayEquals(GZIP_MARKER, r.responseBody ?: ByteArray(0))
            }
    }

    @Test
    fun `ohne Accept-Encoding bleibt es das unkomprimierte Original`() {
        client.get().uri("/assets/app.js")
            .exchange()
            .expectStatus().isOk
            .expectHeader().doesNotExist("Content-Encoding")
            .expectBody().consumeWith { r ->
                assertArrayEquals(APP_JS.toByteArray(), r.responseBody ?: ByteArray(0))
            }
    }

    @Test
    fun `Content-Type bleibt javascript, nicht die br-Endung`() {
        // Der EncodedResource meldet den ORIGINALnamen zurueck — sonst raet
        // MediaTypeFactory anhand von `.br` und der Browser lehnt das Modul ab.
        client.get().uri("/assets/app.js")
            .header("Accept-Encoding", "br")
            .exchange()
            .expectStatus().isOk
            .expectHeader().value("Content-Type") { ct ->
                assertTrue(ct.contains("javascript"), "Content-Type war '$ct'")
            }
    }

    @Test
    fun `Datei ohne br-Variante wird still unkomprimiert ausgeliefert`() {
        // index.html liegt unter der 1024-B-Schwelle des Build-Plugins, hat also
        // nie ein .br daneben. Ein Deploy ohne vorkomprimierte Assets muss
        // unveraendert funktionieren.
        client.get().uri("/index.html")
            .header("Accept-Encoding", "br, gzip")
            .exchange()
            .expectStatus().isOk
            .expectHeader().doesNotExist("Content-Encoding")
    }

    @Test
    fun `SPA-Fallback verhandelt ebenfalls — unbekannte Route mit br`() {
        // Die Kette loest erst auf (index.html) und tauscht danach gegen .br;
        // hier existiert keine — 200 mit dem Original ist das richtige Ergebnis.
        client.get().uri("/uebersicht")
            .header("Accept-Encoding", "br, gzip")
            .exchange()
            .expectStatus().isOk
    }

    companion object {
        private const val APP_JS = "console.log('hoshi 0.8')"

        /**
         * Erkennbare Platzhalter-Bytes statt echtem Brotli/Gzip — siehe Klassen-KDoc.
         * Muessen sich voneinander und vom Original unterscheiden, damit der Test
         * beweist, WELCHE Datei rausging.
         */
        private val BROTLI_MARKER = "<<brotli-variante>>".toByteArray()
        private val GZIP_MARKER = "<<gzip-variante>>".toByteArray()

        /** Temp-Bundle: index.html + assets/app.js (+ .br/.gz), beim Klassen-Load erzeugt. */
        private val staticDir: Path = Files.createTempDirectory("hoshi-fe-it").also { d ->
            Files.writeString(d.resolve("index.html"), "<!doctype html><title>hoshi</title>")
            val assets = Files.createDirectory(d.resolve("assets"))
            Files.writeString(assets.resolve("app.js"), APP_JS)
            Files.write(assets.resolve("app.js.br"), BROTLI_MARKER)
            Files.write(assets.resolve("app.js.gz"), GZIP_MARKER)
        }

        @JvmStatic
        @DynamicPropertySource
        fun staticDirProperty(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.web.static-dir") { staticDir.toString() }
        }
    }
}
