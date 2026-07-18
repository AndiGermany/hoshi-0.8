package de.hoshi.web

import de.hoshi.core.port.ScheduledItem
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * **ScheduledItemsEndpointTest** — beweist am GEBOOTETEN Context, dass
 * `GET /api/v1/scheduled` (a) AUTOMATISCH hinter der [PerimeterWebFilter]-Wand liegt
 * (401 ohne Token — kein eigener Auth-Code im Controller), (b) mit Token die aktiven
 * Items im Contract-Format `[{id, kind, label?, dueAtEpochMs}]` liefert (sortiert
 * nach Faelligkeit, `label` fehlt bei null, KEIN `firedAtEpochMs`) und (c) strikt
 * READ-ONLY ist (der zweite Abruf liefert dieselben Items — kein drain).
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich
 * (exakt das [FiredItemsEndpointTest]-Muster). `HOSHI_TIMER_ENABLED=true` verdrahtet
 * den echten [de.hoshi.core.port.InMemoryScheduledItemStore] (statt NONE), damit der
 * Test Items direkt anlegen kann; der Fire-Service bleibt Default OFF — kein
 * Poll-Thread, nichts feuert waehrend des Tests.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // Echter In-Mem-Store statt ScheduledItemPort.NONE — der Test befuellt ihn direkt.
        "HOSHI_TIMER_ENABLED=true",
        // HOSHI_TIMER_FIRE_ENABLED bleibt Default (false) — kein Poll-Thread im Test.
    ],
)
@AutoConfigureWebTestClient
class ScheduledItemsEndpointTest(
    @Autowired val client: WebTestClient,
    @Autowired val store: ScheduledItemPort,
) {

    @BeforeEach
    fun cleanStore() {
        store.cancelAll() // sauberer Start (Tests teilen sich den Context-Singleton)
    }

    @Test
    fun `scheduled ohne Token - 401 (automatisch hinter der Wand)`() {
        client.get().uri("/api/v1/scheduled")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `scheduled mit Token - Contract-Format, nach Faelligkeit sortiert, read-only`() {
        // Bewusst in „falscher" Reihenfolge angelegt — query() sortiert nach dueAt.
        store.set(ScheduledItem(id = "t1", kind = ScheduledKind.TIMER, dueAtEpochMs = 2_000))
        store.set(
            ScheduledItem(id = "w1", kind = ScheduledKind.ALARM, dueAtEpochMs = 1_000, label = "Aufstehen"),
        )

        client.get().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo("w1") // frueheste Faelligkeit zuerst
            .jsonPath("$[0].kind").isEqualTo("ALARM")
            .jsonPath("$[0].label").isEqualTo("Aufstehen")
            .jsonPath("$[0].dueAtEpochMs").isEqualTo(1_000)
            .jsonPath("$[1].id").isEqualTo("t1")
            .jsonPath("$[1].kind").isEqualTo("TIMER")
            // label? — bei null fehlt das Feld ganz (NON_NULL), exakt der Contract.
            .jsonPath("$[1].label").doesNotExist()
            // Aktiv = noch nicht gefeuert — firedAtEpochMs gehoert NICHT ins Format.
            .jsonPath("$[0].firedAtEpochMs").doesNotExist()

        // READ-ONLY: der zweite Abruf liefert dieselben Items (kein consume-once-Drain).
        client.get().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo("w1")
    }

    @Test
    fun `scheduled mit Token - keine aktiven Items = leeres Array`() {
        client.get().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    // ── remainingSeconds (additive FE-Bequemlichkeit) ────────────────────────

    @Test
    fun `scheduled - remainingSeconds ist die Restzeit gegen die Server-Uhr, faellig ⇒ 0`() {
        // laengst faellig (Epoch 1970) ⇒ nie negativ ⇒ 0; ein Zukunfts-Item ⇒ ~Restsekunden.
        store.set(ScheduledItem(id = "faellig", kind = ScheduledKind.ALARM, dueAtEpochMs = 1_000, label = "alt"))
        store.set(
            ScheduledItem(id = "zukunft", kind = ScheduledKind.TIMER, dueAtEpochMs = System.currentTimeMillis() + 120_000),
        )

        client.get().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // sortiert nach Faelligkeit: „faellig" (1_000) zuerst.
            .jsonPath("$[0].id").isEqualTo("faellig")
            .jsonPath("$[0].remainingSeconds").isEqualTo(0) // laengst faellig ⇒ nie negativ
            .jsonPath("$[1].id").isEqualTo("zukunft")
            .jsonPath("$[1].remainingSeconds").value<Int> {
                assertTrue(it in 110..120, "Rest ~120 s (Toleranz fuer Test-Laufzeit), war: $it")
            }
    }

    // ── DELETE by id: 204 / 404, entfernt genau eins ─────────────────────────

    @Test
    fun `delete by id - 204 und entfernt genau eins`() {
        store.set(ScheduledItem(id = "a", kind = ScheduledKind.TIMER, dueAtEpochMs = 1_000))
        store.set(ScheduledItem(id = "b", kind = ScheduledKind.TIMER, dueAtEpochMs = 2_000))

        client.delete().uri("/api/v1/scheduled/a")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNoContent

        assertEquals(listOf("b"), store.query().map { it.id }, "nur 'a' entfernt, 'b' bleibt")
    }

    @Test
    fun `delete by id - unbekannte id ⇒ 404`() {
        client.delete().uri("/api/v1/scheduled/gibtsnicht")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNotFound
    }

    // ── DELETE all: 200 {count}, leert den Store ─────────────────────────────

    @Test
    fun `delete all - 200 mit count, leert den Store`() {
        store.set(ScheduledItem(id = "a", kind = ScheduledKind.TIMER, dueAtEpochMs = 1_000))
        store.set(ScheduledItem(id = "b", kind = ScheduledKind.TIMER, dueAtEpochMs = 2_000))

        client.delete().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.count").isEqualTo(2)

        assertTrue(store.query().isEmpty(), "cancelAll leert den Store")
    }

    @Test
    fun `delete all - leerer Store ⇒ 200 mit count 0`() {
        client.delete().uri("/api/v1/scheduled")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.count").isEqualTo(0)
    }

    @Test
    fun `delete ohne Token - 401 (beide DELETE-Routen liegen hinter der Wand)`() {
        client.delete().uri("/api/v1/scheduled/a").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/v1/scheduled").exchange().expectStatus().isUnauthorized
    }
}
