package de.hoshi.web

import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **FiredItemsEndpointTest** — beweist am GEBOOTETEN Context, dass
 * `GET /api/v1/scheduled/fired` + `POST …/fired/{id}/ack` (a) AUTOMATISCH hinter der
 * [PerimeterWebFilter]-Wand liegen (401 ohne Token — kein eigener Auth-Code im
 * Controller) und (b) mit Token den [FiredItemsStore] im Contract-Format liefern:
 * der GET ist **idempotent** (Ring-1-Fix: zwei parallele Poller-Tabs sehen BEIDE
 * dieselben Items — frueher schnappte der erste den Event weg), erst der Ack-POST
 * raeumt (dann fuer ALLE Tabs; zweites Ack ⇒ 404).
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich
 * (exakt das [PerimeterWallTest]-Muster). Der Fire-Service selbst ist hier NICHT
 * verdrahtet (Flag default OFF) — der Store wird direkt befuellt; der Endpoint
 * funktioniert bewusst auch ohne Fire-Service-Wiring (liefert dann `[]`).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // HOSHI_TIMER_FIRE_ENABLED bleibt Default (false) — kein Poll-Thread im Test.
    ],
)
@AutoConfigureWebTestClient
class FiredItemsEndpointTest(
    @Autowired val client: WebTestClient,
    @Autowired val fired: FiredItemsStore,
) {

    @BeforeEach
    fun cleanStore() {
        // Sauberer Start (Tests teilen sich den Context-Singleton): alles quittieren.
        fired.pending(0).forEach { fired.ack(it.id) }
    }

    @Test
    fun `fired ohne Token - 401 (automatisch hinter der Wand)`() {
        client.get().uri("/api/v1/scheduled/fired")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `ack ohne Token - 401 (auch der POST liegt hinter der Wand)`() {
        client.post().uri("/api/v1/scheduled/fired/w1/ack")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `fired mit Token - Contract-Format, IDEMPOTENT - zwei Poller sehen beide dasselbe`() {
        // FRISCH gefeuert (echte Uhr im Controller!) — sonst greift die ehrliche
        // 30-min-missed-Markierung und missed waere true statt false.
        val now = System.currentTimeMillis()
        fired.add(
            FiredItem(
                id = "w1",
                kind = ScheduledKind.ALARM,
                label = "Aufstehen",
                dueAtEpochMs = now - 1_500,
                firedAtEpochMs = now - 1_000,
            ),
        )
        fired.add(
            FiredItem(
                id = "t1",
                kind = ScheduledKind.TIMER,
                label = null,
                dueAtEpochMs = now - 600,
                firedAtEpochMs = now - 500,
                missed = true,
            ),
        )

        // "Tab 1" pollt …
        client.get().uri("/api/v1/scheduled/fired")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo("w1")
            .jsonPath("$[0].kind").isEqualTo("ALARM")
            .jsonPath("$[0].label").isEqualTo("Aufstehen")
            .jsonPath("$[0].dueAtEpochMs").isEqualTo(now - 1_500)
            .jsonPath("$[0].firedAtEpochMs").isEqualTo(now - 1_000)
            .jsonPath("$[0].missed").isEqualTo(false)
            .jsonPath("$[1].id").isEqualTo("t1")
            .jsonPath("$[1].missed").isEqualTo(true)
            // label? — bei null fehlt das Feld ganz (NON_NULL), exakt der Contract.
            .jsonPath("$[1].label").doesNotExist()

        // … und "Tab 2" pollt danach: sieht EXAKT dieselben Items (kein consume-once mehr —
        // frueher war dieser zweite Abruf leer und der Wecker verpuffte lautlos).
        client.get().uri("/api/v1/scheduled/fired")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo("w1")
            .jsonPath("$[1].id").isEqualTo("t1")
    }

    @Test
    fun `fired mit Token - origin ist im JSON, fehlt bei null (NON_NULL)`() {
        val now = System.currentTimeMillis()
        fired.add(
            FiredItem(
                id = "w1",
                kind = ScheduledKind.ALARM,
                label = "Aufstehen",
                dueAtEpochMs = now - 1_500,
                firedAtEpochMs = now - 1_000,
                origin = "voice-pe-1",
            ),
        )
        fired.add(
            FiredItem(
                id = "t1",
                kind = ScheduledKind.TIMER,
                dueAtEpochMs = now - 600,
                firedAtEpochMs = now - 500,
                // origin=null (alt-Client / kein Ursprung) ⇒ Feld fehlt im JSON.
            ),
        )

        client.get().uri("/api/v1/scheduled/fired")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // FIRED_ORDER = aelteste Feuerung zuerst ⇒ w1 (firedAt now-1000) vor t1 (now-500).
            .jsonPath("$[0].id").isEqualTo("w1")
            .jsonPath("$[0].origin").isEqualTo("voice-pe-1")
            .jsonPath("$[0].firedAtEpochMs").isEqualTo(now - 1_000)
            .jsonPath("$[1].id").isEqualTo("t1")
            .jsonPath("$[1].origin").doesNotExist() // origin=null ⇒ NON_NULL laesst das Feld weg
    }

    @Test
    fun `ack mit Token - 204, danach ist das Item fuer ALLE Tabs weg, zweites ack 404`() {
        fired.add(
            FiredItem(
                id = "w1",
                kind = ScheduledKind.ALARM,
                label = "Aufstehen",
                dueAtEpochMs = 1_000,
                firedAtEpochMs = 1_500,
            ),
        )

        client.post().uri("/api/v1/scheduled/fired/w1/ack")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNoContent

        // Beide "Tabs" sehen jetzt nichts mehr — ack raeumt zentral.
        client.get().uri("/api/v1/scheduled/fired")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")

        // Zweites ack (z.B. Race zweier Tabs): 404 — fuer das FE gleichwertig (weg ist weg).
        client.post().uri("/api/v1/scheduled/fired/w1/ack")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNotFound
    }
}
