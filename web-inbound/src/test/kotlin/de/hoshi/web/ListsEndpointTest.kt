package de.hoshi.web

import de.hoshi.core.port.ListPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
 * **ListsEndpointTest** — beweist am GEBOOTETEN Context, dass `GET /api/v1/lists`
 * (a) AUTOMATISCH hinter der [PerimeterWebFilter]-Wand liegt (401 ohne Token —
 * kein eigener Auth-Code im Controller), (b) mit Token die Items im
 * Contract-Format `[{id, text, quantity, addedAtEpochMs}]` liefert (sortiert
 * nach Anlage-Zeit) und (c) strikt READ-ONLY ist; dass `POST /api/v1/lists/items`
 * anlegt/mergt (Dedupe-Zähler) und `DELETE /api/v1/lists/items/{id}` entfernt.
 *
 * `HOSHI_LIST_ENABLED=true` verdrahtet den ECHTEN [JsonFileListStore] (Andi-JA
 * 2026-07-08: „Persistenz IST das Feature" — anders als beim Timer gibt es KEINEN
 * separaten In-Mem-Zwischenschalter). Damit dieser Test NICHT die echte
 * `~/.hoshi/lists.json` des Rechners anfasst, zeigt `hoshi.list.store.path` via
 * [DynamicPropertySource] auf ein isoliertes Test-Temp-Verzeichnis.
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift
 * wirklich (exakt das [ScheduledItemsEndpointTest]-Muster).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_LIST_ENABLED=true",
    ],
)
@AutoConfigureWebTestClient
class ListsEndpointTest(
    @Autowired val client: WebTestClient,
    @Autowired val store: ListPort,
) {

    companion object {
        private val tempDir: Path = Files.createTempDirectory("hoshi-lists-endpoint-test")

        @DynamicPropertySource
        @JvmStatic
        fun listStorePath(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.list.store.path") { tempDir.resolve("lists.json").toString() }
        }
    }

    @BeforeEach
    fun cleanStore() {
        store.clear(ListPort.DEFAULT_LIST_ID) // sauberer Start (Tests teilen sich den Context-Singleton)
    }

    // ── Perimeter ─────────────────────────────────────────────────────────────

    @Test
    fun `lists ohne Token - 401 (automatisch hinter der Wand)`() {
        client.get().uri("/api/v1/lists")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `post ohne Token - 401`() {
        client.post().uri("/api/v1/lists/items")
            .bodyValue(AddListItemRequest("Milch"))
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `delete ohne Token - 401`() {
        client.delete().uri("/api/v1/lists/items/irgendeine-id")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ── GET (Contract-Format, sortiert, read-only) ───────────────────────────

    @Test
    fun `lists mit Token - keine Items = leeres Array`() {
        client.get().uri("/api/v1/lists")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    @Test
    fun `lists mit Token - Contract-Format, sortiert nach Anlage, read-only`() {
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("Milch"))
            .exchange()
            .expectStatus().isOk
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("Butter"))
            .exchange()
            .expectStatus().isOk

        client.get().uri("/api/v1/lists")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].text").isEqualTo("Milch")
            .jsonPath("$[0].quantity").isEqualTo(1)
            .jsonPath("$[1].text").isEqualTo("Butter")

        // READ-ONLY: der zweite Abruf liefert dieselben Items (kein consume-once-Drain).
        client.get().uri("/api/v1/lists")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
    }

    // ── POST (anlegen + Dedupe-Merge) ────────────────────────────────────────

    @Test
    fun `post legt ein neues Item an - 200 mit dem Item`() {
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("Milch"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.text").isEqualTo("Milch")
            .jsonPath("$.quantity").isEqualTo(1)
            .jsonPath("$.id").isNotEmpty
    }

    @Test
    fun `post mit gleichem Text zweimal mergt - quantity 2, kein Duplikat`() {
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("Milch"))
            .exchange().expectStatus().isOk

        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("milch"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.text").isEqualTo("Milch")
            .jsonPath("$.quantity").isEqualTo(2)

        assertEquals(1, store.items(ListPort.DEFAULT_LIST_ID).size, "kein Duplikat im Store")
    }

    @Test
    fun `post mit leerem Text - 400`() {
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("   "))
            .exchange()
            .expectStatus().isBadRequest
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    fun `delete by id - 204 und entfernt genau eins`() {
        val idA = postAndGetId("Milch")
        postAndGetId("Butter")

        client.delete().uri("/api/v1/lists/items/$idA")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNoContent

        assertEquals(listOf("Butter"), store.items(ListPort.DEFAULT_LIST_ID).map { it.text }, "nur Milch entfernt, Butter bleibt")
    }

    @Test
    fun `delete unbekannte id - 404`() {
        client.delete().uri("/api/v1/lists/items/gibtsnicht")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isNotFound
    }

    private fun postAndGetId(text: String): String {
        var id: String? = null
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest(text))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").value<String> { id = it }
        return id ?: error("keine id in der POST-Antwort")
    }

    // ── Restart-Beweis auf REST-Ebene: Store-Referenz teilt sich mit einer
    //    zweiten Instanz auf demselben Pfad (der eigentliche Neustart-Beweis
    //    lebt in JsonFileListStoreTest; hier nur der Kontext-Beweis, dass DIESER
    //    Context tatsaechlich file-backed ist und nicht ListPort.NONE). ────────

    @Test
    fun `Store ist file-backed (JsonFileListStore), nicht NONE - Datei entsteht nach POST`() {
        assertTrue(store is JsonFileListStore, "HOSHI_LIST_ENABLED=true muss JsonFileListStore verdrahten")
        client.post().uri("/api/v1/lists/items")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .bodyValue(AddListItemRequest("Milch"))
            .exchange()
            .expectStatus().isOk
        assertTrue(Files.exists((store as JsonFileListStore).path), "die Listen-Datei muss nach einem POST existieren")
    }
}
