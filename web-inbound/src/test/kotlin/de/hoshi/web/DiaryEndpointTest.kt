package de.hoshi.web

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
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * **DiaryEndpointTest** — beweist am GEBOOTETEN Context, dass `GET /api/v1/diary/recent`
 *
 *  (a) AUTOMATISCH hinter der [PerimeterWebFilter]-Wand liegt (401 ohne Token — kein
 *      eigener Auth-Code im [DiaryController], exakt das [FiredItemsEndpointTest]-Muster),
 *  (b) ohne Tages-Dateien ehrlich `[]` liefert (HTTP 200, KEIN Fehler),
 *  (c) die JSONL-Zeilen von heute + gestern im Datei-Vertrag durchreicht,
 *      NEUESTE ZUERST (heute-tail vor gestern), kaputte Zeilen überspringt,
 *  (d) `?limit=` als Tail respektiert.
 *  (e) `?before=<ISO-ts>` (additive Paginierung, Andi-Auftrag „Frühere laden" 2026-07-27)
 *      strikt ältere Zeilen liefert und dafür auch VOR gestern zurückwandert,
 *  (f) ein Alt-Client OHNE `before` weiterhin exakt das alte Verhalten sieht (heute+
 *      gestern, ältere Tage bleiben unsichtbar) — der additive Vertrag bricht nichts.
 *
 * Das Diary-Verzeichnis zeigt via `hoshi.diary.dir` (dieselbe Property wie die
 * `turnTracePort`-Bean) auf ein Temp-Verzeichnis; die Tests schreiben die Tages-Dateien
 * selbst im Adapter-Wire-Format (`turn-diary-YYYY-MM-DD.jsonl`, eine JSON-Zeile pro Turn).
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // HOSHI_TURN_DIARY_ENABLED bleibt Default (false) — der Lese-Rand braucht den Writer nicht.
    ],
)
@AutoConfigureWebTestClient
class DiaryEndpointTest(
    @Autowired val client: WebTestClient,
) {

    companion object {
        @JvmStatic
        val dir: Path = Files.createTempDirectory("diary-endpoint-test")

        @JvmStatic
        @DynamicPropertySource
        fun diaryDir(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.diary.dir") { dir.toString() }
        }
    }

    @BeforeEach
    fun cleanDir() {
        Files.list(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
    }

    private fun get() = client.get().uri("/api/v1/diary/recent")
        .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")

    /** Eine Wire-Zeile exakt im [de.hoshi.adapters.supervision.JsonlTurnTraceAdapter]-Format. */
    private fun line(
        ts: String,
        category: String,
        deflected: Boolean = false,
        error: String? = null,
        // Grounding-Ehrlichkeit 2026-07-02: kein hardcoded false mehr — der Wert
        // kommt live aus ChatEvent.Start.grounded und muss BEIDE Werte tragen können.
        groundingUsed: Boolean = false,
    ): String =
        """{"ts":"$ts","chatId":"c1","category":"$category","provider":"LOCAL","persona":"hoshi",""" +
            """"language":"DE","ttftMs":420,"totalMs":1500,"deltaChars":64,"audioChunks":0,""" +
            """"speak":false,"deflected":$deflected,"error":${error?.let { "\"$it\"" } ?: "null"},""" +
            """"groundingUsed":$groundingUsed}"""

    private fun writeDay(day: LocalDate, vararg lines: String) {
        Files.write(
            dir.resolve("turn-diary-$day.jsonl"),
            (lines.joinToString("\n") + "\n").toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * Mittag EINES Tages, im selben `ZoneId.systemDefault()` wie der Controller
     * (`LocalDate.ofInstant(before, ZoneId.systemDefault())`) — dadurch sind die
     * `before`-Tests unabhängig von der tatsächlichen Maschinen-Zeitzone: Konstruktion
     * und Interpretation laufen in derselben Zone, „Mittag" hält sicheren Abstand zu
     * Mitternachts-Rand-Effekten.
     */
    private fun localNoon(day: LocalDate): Instant = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    @Test
    fun `recent ohne Token - 401 (automatisch hinter der Wand)`() {
        client.get().uri("/api/v1/diary/recent")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `keine Tages-Datei - ehrlich leere Liste, kein Fehler`() {
        get().exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }

    @Test
    fun `liefert heute + gestern im Datei-Vertrag, neueste zuerst, kaputte Zeile faellt still raus`() {
        val today = LocalDate.now()
        writeDay(today.minusDays(1), line("2026-06-30T09:00:00Z", "SMART_HOME"))
        writeDay(
            today,
            line("2026-07-01T08:00:00Z", "FACT_SHORT", deflected = true),
            "{ kaputt, kein json",
            // Gedeckter FACT-Turn: groundingUsed=true muss den Datei-Vertrag
            // unverändert passieren (Grounding-Ehrlichkeit, kein hardcoded false).
            line("2026-07-01T08:05:00Z", "CHAT", error = "TTS", groundingUsed = true),
        )

        get().exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            // Neueste zuerst: letzte Zeile von HEUTE vorn, GESTERN hinten.
            .jsonPath("$[0].ts").isEqualTo("2026-07-01T08:05:00Z")
            .jsonPath("$[0].category").isEqualTo("CHAT")
            .jsonPath("$[0].persona").isEqualTo("hoshi")
            .jsonPath("$[0].ttftMs").isEqualTo(420)
            .jsonPath("$[0].deflected").isEqualTo(false)
            .jsonPath("$[0].error").isEqualTo("TTS")
            .jsonPath("$[0].groundingUsed").isEqualTo(true)
            .jsonPath("$[1].ts").isEqualTo("2026-07-01T08:00:00Z")
            .jsonPath("$[1].deflected").isEqualTo(true)
            .jsonPath("$[1].groundingUsed").isEqualTo(false)
            .jsonPath("$[2].ts").isEqualTo("2026-06-30T09:00:00Z")
            .jsonPath("$[2].category").isEqualTo("SMART_HOME")
    }

    @Test
    fun `limit wirkt als Tail - die juengsten N, neueste zuerst`() {
        writeDay(
            LocalDate.now(),
            line("2026-07-01T08:00:00Z", "A"),
            line("2026-07-01T08:01:00Z", "B"),
            line("2026-07-01T08:02:00Z", "C"),
        )

        client.get().uri("/api/v1/diary/recent?limit=2")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].category").isEqualTo("C")
            .jsonPath("$[1].category").isEqualTo("B")
    }

    // ── `before`-Paginierung (additiv, Andi-Auftrag „Frühere laden" 2026-07-27) ──

    @Test
    fun `Alt-Client ohne before sieht weiterhin NUR heute+gestern - aeltere Tage bleiben unsichtbar`() {
        val today = LocalDate.now()
        writeDay(today.minusDays(3), line(localNoon(today.minusDays(3)).toString(), "TOO_OLD"))
        writeDay(today.minusDays(1), line(localNoon(today.minusDays(1)).toString(), "YESTERDAY"))
        writeDay(today, line(localNoon(today).toString(), "TODAY"))

        get().exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].category").isEqualTo("TODAY")
            .jsonPath("$[1].category").isEqualTo("YESTERDAY")
    }

    @Test
    fun `before wandert ueber gestern hinaus zu aelteren Tages-Dateien zurueck`() {
        val today = LocalDate.now()
        writeDay(today.minusDays(3), line(localNoon(today.minusDays(3)).toString(), "THREE_DAYS_AGO"))
        writeDay(today.minusDays(2), line(localNoon(today.minusDays(2)).toString(), "TWO_DAYS_AGO"))
        // heute + gestern bewusst LEER — before darf dort nicht hängen bleiben.
        val cursor = localNoon(today.minusDays(1))

        client.get().uri("/api/v1/diary/recent?limit=2&before=$cursor")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].category").isEqualTo("TWO_DAYS_AGO")
            .jsonPath("$[1].category").isEqualTo("THREE_DAYS_AGO")
    }

    @Test
    fun `before filtert strikt - nur Zeilen VOR dem Cursor, auch innerhalb desselben Tages`() {
        val today = LocalDate.now()
        val early = localNoon(today).minusSeconds(3600)
        val late = localNoon(today)
        writeDay(today, line(early.toString(), "EARLY"), line(late.toString(), "LATE"))

        client.get().uri("/api/v1/diary/recent?limit=10&before=$late")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].category").isEqualTo("EARLY")
    }

    @Test
    fun `limit wirkt auch mit before - nur die juengsten N strikt vor dem Cursor`() {
        val today = LocalDate.now()
        writeDay(
            today.minusDays(2),
            line(localNoon(today.minusDays(2)).toString(), "A"),
            line(localNoon(today.minusDays(2)).plusSeconds(60).toString(), "B"),
            line(localNoon(today.minusDays(2)).plusSeconds(120).toString(), "C"),
        )
        val cursor = localNoon(today.minusDays(1))

        client.get().uri("/api/v1/diary/recent?limit=2&before=$cursor")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].category").isEqualTo("C")
            .jsonPath("$[1].category").isEqualTo("B")
    }

    @Test
    fun `unparsbarer before-Wert liefert ehrlich leere Liste, kein Fehler`() {
        writeDay(LocalDate.now(), line(localNoon(LocalDate.now()).toString(), "X"))

        client.get().uri("/api/v1/diary/recent?before=nicht-so-ein-datum")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")
            .exchange()
            .expectStatus().isOk
            .expectBody().json("[]")
    }
}
