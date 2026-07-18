package de.hoshi.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **PerimeterWallTest** — beweist die LIVE-401-Wand am GEBOOTETEN Spring-Context.
 *
 * Bewusst `webEnvironment = MOCK` (kein echter Socket): `WebTestClient` ist an
 * den Application-Context gebunden und läuft durch die ECHTE [PerimeterWebFilter]
 * -Kette. Mock-Requests haben KEINE `remoteAddress` → `isLoopback=false`
 * (fail-closed) → die Token-Wand greift wirklich.
 *
 * **Loopback-Einschränkung (dokumentiert):** Ein `RANDOM_PORT`-Server würde
 * jeden Test-Request über `localhost` (= loopback) schicken; der PerimeterPort
 * ließe dann ALLES durch und die Wand wäre nicht beweisbar. Der MOCK-Pfad
 * (null remote = nicht-loopback) ist daher der Weg, die Token-Wand am echten
 * Context scharf zu testen.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
    ],
)
@AutoConfigureWebTestClient
class PerimeterWallTest(@Autowired val client: WebTestClient) {

    @Test
    fun `health ist oeffentlich — 200 ohne Token`() {
        client.get().uri("/api/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("up")
    }

    @Test
    fun `geschuetzter ping ohne Token — 401`() {
        client.get().uri("/api/v1/ping")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `geschuetzter ping mit korrektem Bearer-Token — 200`() {
        client.get().uri("/api/v1/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.pong").isEqualTo(true)
    }

    @Test
    fun `geschuetzter ping mit korrektem X-Hoshi-Token-Header — 200`() {
        client.get().uri("/api/v1/ping")
            .header(PerimeterWebFilter.X_HOSHI_TOKEN, "test-secret-token")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `geschuetzter ping mit falschem Token — 401`() {
        client.get().uri("/api/v1/ping")
            .header(HttpHeaders.AUTHORIZATION, "Bearer falsch")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `home registry ohne Token — 401`() {
        // Deckt die Auth-Seite von HomeRegistryController generisch ab (die
        // 200/404/502-Fallunterscheidung testet HomeRegistryControllerTest direkt).
        client.get().uri("/api/v1/home/registry")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ── WS-Handshake-Sonderfall: ?token=… nur für /ws/-Pfade (Geräte/Browser) ──
    // (HOSHI_WS_AUDIO_ENABLED ist im Test OFF ⇒ /ws/audio unmapped ⇒ ein durchgelassener
    //  Handshake landet bei 404, ein abgewiesener bei 401 — so ist die Token-Wand prüfbar.)

    @Test
    fun `ws-Pfad mit gueltigem Query-Token passiert die Wand (nicht 401)`() {
        client.get().uri("/ws/audio?token=test-secret-token")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `ws-Pfad mit falschem Query-Token — 401`() {
        client.get().uri("/ws/audio?token=falsch")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `ws-Pfad ohne Token — 401`() {
        client.get().uri("/ws/audio")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `api-Pfad ignoriert Query-Token (scoped auf ws) — 401`() {
        // Der ?token=-Pfad ist bewusst NUR für /ws/ — /api/ bleibt header-only.
        client.get().uri("/api/v1/ping?token=test-secret-token")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
