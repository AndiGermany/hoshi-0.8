package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * **SpeakerEndpointTest** — beweist am GEBOOTETEN Context (MOCK, echte [PerimeterWebFilter]-Wand)
 * den Enroll-Rand end-to-end gegen einen Fake-CAM++-Sidecar (JDK-HttpServer, kanned `/embed`)
 * und einen Temp-Store:
 *  - die `/api/v1/speakers`-Pfade liegen AUTOMATISCH hinter der 401-Wand (kein eigener Auth-Code);
 *  - enroll → 200 `{name, enrolledAt}`, list zeigt das Profil OHNE Vektor, delete → 204, dann leer;
 *  - leeres/zu kurzes Audio ⇒ 422 (kein stilles Speichern); boeser Name ⇒ 400; DELETE unbekannt ⇒ 404.
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich
 * (exakt das [ScheduledItemsEndpointTest]-Muster). `HOSHI_SPEAKER_ENROLL_ENABLED=true` erzeugt
 * Controller + Store + Embed-Adapter; `@DynamicPropertySource` biegt `hoshi.speaker.base-url` auf
 * den Fake-Sidecar und `hoshi.speaker.store.path` auf eine Temp-Datei.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        "HOSHI_SPEAKER_ENROLL_ENABLED=true",
    ],
)
@AutoConfigureWebTestClient
class SpeakerEndpointTest(
    @Autowired val client: WebTestClient,
    @Autowired val store: SpeakerProfileStore,
) {

    @BeforeEach
    fun cleanStore() {
        // Context-Singleton wird zwischen Tests geteilt ⇒ sauberer Start.
        store.list().forEach { store.delete(it.name) }
    }

    private fun bearer() = HttpHeaders.AUTHORIZATION to "Bearer test-secret-token"

    private fun multipart(audio: ByteArray) = BodyInserters.fromMultipartData(
        MultipartBodyBuilder().apply {
            part("audio", object : ByteArrayResource(audio) {
                override fun getFilename() = "enroll.wav"
            }).contentType(MediaType.parseMediaType("audio/wav"))
        }.build(),
    )

    // ── Auth-Wand (automatisch, kein Controller-Code) ────────────────────────

    @Test
    fun `enroll ohne Token - 401`() {
        client.post().uri("/api/v1/speakers/enroll?name=andi")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `list ohne Token - 401`() {
        client.get().uri("/api/v1/speakers")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `diagnostics ohne Token - 401`() {
        client.get().uri("/api/v1/speakers/diagnostics")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ── enroll → list → delete (Vollzyklus, NIE Vektoren) ────────────────────

    @Test
    fun `enroll dann list dann delete - Contract, list ohne Vektor`() {
        // enroll → 200 {name, enrolledAt}
        client.post().uri("/api/v1/speakers/enroll?name=andi")
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("andi")
            .jsonPath("$.enrolledAt").isNumber

        // list → [{name, enrolledAt}] — NIE embedding
        client.get().uri("/api/v1/speakers")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].name").isEqualTo("andi")
            .jsonPath("$[0].enrolledAt").isNumber
            .jsonPath("$[0].embedding").doesNotExist()

        // delete → 204
        client.delete().uri("/api/v1/speakers/andi")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isNoContent

        // list → leer
        client.get().uri("/api/v1/speakers")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    // ── Multi-Sample-Enroll: 3-Satz-Kette ueber den Controller ───────────────

    @Test
    fun `enroll mit sample 1-2-3 - Kette baut EIN Profil mit 3 Samples und renormalisiertem Mittel`() {
        for (i in 1..3) {
            client.post().uri("/api/v1/speakers/enroll?name=andi&sample=$i")
                .header(bearer().first, bearer().second)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(ByteArray(2000)))
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.name").isEqualTo("andi")
                .jsonPath("$.samples").isEqualTo(i) // Zwischenstand ehrlich: 1, 2, 3
        }

        // list: EIN Profil, Sample-Zahl 3, NIE Vektoren.
        client.get().uri("/api/v1/speakers")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].samples").isEqualTo(3)
            .jsonPath("$[0].embedding").doesNotExist()

        // Store-Wahrheit: der Fake-Sidecar liefert konstant [0.1,0.2,0.3,0.4] ⇒ Mittel == Sample
        // ⇒ Profil == renormalisiertes Sample: v/‖v‖ mit ‖v‖=√0.30 (mathematisch festgenagelt).
        val p = store.get("andi")!!
        org.junit.jupiter.api.Assertions.assertEquals(3, p.samples.size)
        val norm = kotlin.math.sqrt(0.1 * 0.1 + 0.2 * 0.2 + 0.3 * 0.3 + 0.4 * 0.4)
        val expected = floatArrayOf(
            (0.1 / norm).toFloat(),
            (0.2 / norm).toFloat(),
            (0.3 / norm).toFloat(),
            (0.4 / norm).toFloat(),
        )
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, p.embedding, 1e-5f)
    }

    // ── Diagnostics: NUR ZAHLEN, NIE Vektoren ────────────────────────────────

    @Test
    fun `diagnostics - Shape traegt Zahlen fuer alle Profile, NIE Vektoren`() {
        // andi: 2 Samples (selfCohesion gesetzt); bob: 1 Sample (selfCohesion null).
        for (i in 1..2) {
            client.post().uri("/api/v1/speakers/enroll?name=andi&sample=$i")
                .header(bearer().first, bearer().second)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(ByteArray(2000)))
                .exchange()
                .expectStatus().isOk
        }
        client.post().uri("/api/v1/speakers/enroll?name=bob")
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isOk

        client.get().uri("/api/v1/speakers/diagnostics")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.profiles.length()").isEqualTo(2)
            // Alphabetisch sortiert (diagnostics(): sortedBy { it.name }) ⇒ profiles[0]=andi, [1]=bob.
            .jsonPath("$.profiles[0].name").isEqualTo("andi")
            .jsonPath("$.profiles[0].samples").isEqualTo(2)
            // Fake-Sidecar liefert IMMER dasselbe Embedding ⇒ beide andi-Samples nahezu identisch
            // (Float32-Rundung durch die Sidecar-JSON-Rundreise ⇒ ~1.0, nicht exakt — die exakte
            // Mathe ist in SpeakerDiagnosticsTest mit sauberen Vektoren festgenagelt).
            .jsonPath("$.profiles[0].selfCohesion").isNumber
            .jsonPath("$.profiles[1].name").isEqualTo("bob")
            .jsonPath("$.profiles[1].samples").isEqualTo(1)
            .jsonPath("$.profiles[1].selfCohesion").isEqualTo(null as Any?) // 1 Sample ⇒ nichts zu mitteln ⇒ null
            .jsonPath("$.crossSimilarity.andi.bob").isNumber
            .jsonPath("$.crossSimilarity.andi.andi").isNumber
            // NIE Vektoren, weder auf Profil- noch auf Wurzel-Ebene.
            .jsonPath("$.profiles[0].embedding").doesNotExist()
            .jsonPath("$.embedding").doesNotExist()
            .jsonPath("$.crossSimilarity.andi.embedding").doesNotExist()
    }

    @Test
    fun `sample 2 ohne bestehendes Profil - 409, nichts angelegt`() {
        client.post().uri("/api/v1/speakers/enroll?name=neu&sample=2")
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isEqualTo(409)

        client.get().uri("/api/v1/speakers")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    @Test
    fun `sample ausserhalb 1 bis 9 - 400`() {
        client.post().uri("/api/v1/speakers/enroll?name=andi&sample=0")
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isBadRequest
    }

    // ── Fehlerpfade (ehrliche 4xx, kein stilles Speichern) ───────────────────

    @Test
    fun `leeres bzw zu kurzes Audio - 422, nichts gespeichert`() {
        client.post().uri("/api/v1/speakers/enroll?name=andi")
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(10)))
            .exchange()
            .expectStatus().isEqualTo(422)

        client.get().uri("/api/v1/speakers")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    @Test
    fun `ungueltiger Name - 400`() {
        client.post().uri("/api/v1/speakers/enroll?name=%2E%2E%2Fx") // "../x"
            .header(bearer().first, bearer().second)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart(ByteArray(2000)))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `delete unbekannter Name - 404`() {
        client.delete().uri("/api/v1/speakers/gibtsnicht")
            .header(bearer().first, bearer().second)
            .exchange()
            .expectStatus().isNotFound
    }

    companion object {
        /** Fake-CAM++-Sidecar: liefert fuer jedes /embed ein kleines kanned Embedding. */
        private val fakeSidecar: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/embed") { ex ->
                    ex.requestBody.readBytes() // Body verwerfen — der Inhalt ist hier egal
                    val body = """{"embedding":[0.1,0.2,0.3,0.4],"dim":4}""".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }

        private val storeFile =
            Files.createTempDirectory("speaker-endpoint-test").resolve("speaker-profiles.json")

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("hoshi.speaker.base-url") { "http://127.0.0.1:${fakeSidecar.address.port}" }
            reg.add("hoshi.speaker.store.path") { storeFile.toString() }
        }

        @JvmStatic
        @AfterAll
        fun stopSidecar() {
            fakeSidecar.stop(0)
        }
    }
}
