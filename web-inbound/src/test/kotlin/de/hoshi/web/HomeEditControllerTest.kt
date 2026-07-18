package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.RegistryWriteOutcome
import de.hoshi.adapters.ha.RegistryWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * **HomeEditControllerTest** — der Schreib-Vertrag von Scheibe 2, OHNE Spring-Context
 * und OHNE Live-HA: der Controller wird direkt konstruiert (Muster
 * [HomeRegistryControllerTest]). Ein JDK-HttpServer spielt den READ-Katalog
 * (`/api/template`, via [HaHomeRegistryAdapter]) für die Area-Validierung; der
 * Schreib-Client ist ein Fake-[RegistryWriter] (kein echter WebSocket).
 *
 * Abgedeckt: 200 (Ok + Audit + read-first-Invalidate, MIT from/to) · 404
 * (unbekannte entityId, KEIN Write) · 409 (Flag zu) · 400 (leere/unbekannte
 * Area) · 502 (Katalog unerreichbar bzw. Write nicht bestätigt). Die 401-Wand
 * deckt [PerimeterWallTest] generisch für alle `/api/v1`-Pfade ab.
 *
 * `assignArea` liefert seit dem Netty-Loop-Offload ein `Mono<ResponseEntity<…>>`
 * (Blocking-I/O auf [reactor.core.scheduler.Schedulers.boundedElastic]) — die
 * Tests blocken synchron über [assign] (Muster `WeatherLocationControllerTest.put`).
 */
class HomeEditControllerTest {

    private val templateBody =
        "wohnzimmer::Wohnzimmer||kueche::Küche" +
            "@@ENTITIES@@" +
            "light.wohnzimmer_decke::wohnzimmer::Deckenlicht::"

    /** Startet einen Fake-HA (READ-Katalog) und zählt die `/api/template`-Treffer. */
    private fun withHa(block: (baseUrl: String, hits: AtomicInteger) -> Unit) {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            hits.incrementAndGet()
            val bytes = templateBody.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", hits)
        } finally {
            server.stop(0)
        }
    }

    private fun auditLog(): Pair<HomeEditAuditLog, java.nio.file.Path> {
        val path = Files.createTempFile("home-edit-audit", ".jsonl")
        Files.deleteIfExists(path) // append-only soll die Datei selbst anlegen
        return HomeEditAuditLog(path, Clock.systemUTC()) to path
    }

    private fun controller(
        baseUrl: String,
        editEnabled: Boolean,
        writer: RegistryWriter,
        audit: HomeEditAuditLog,
    ) = HomeEditController(
        registryAdapter = HaHomeRegistryAdapter(baseUrl = baseUrl, token = "secret-token"),
        writer = writer,
        auditLog = audit,
        editEnabled = editEnabled,
    )

    /** Blockt den `Mono<ResponseEntity<Any>>` synchron (Muster `WeatherLocationControllerTest.put`). */
    private fun assign(c: HomeEditController, entityId: String, areaId: String?): ResponseEntity<Any> =
        c.assignArea(entityId, AreaAssignmentRequest(areaId)).block(Duration.ofSeconds(5))!!

    @Test
    fun `GET status spiegelt HOSHI_HOME_EDIT_ENABLED`() = withHa { url, _ ->
        val (audit, _) = auditLog()
        val off = controller(url, editEnabled = false, writer = { _, _ -> RegistryWriteOutcome.Ok }, audit = audit)
        val on = controller(url, editEnabled = true, writer = { _, _ -> RegistryWriteOutcome.Ok }, audit = audit)

        assertFalse(off.status().editEnabled)
        assertTrue(on.status().editEnabled)
    }

    @Test
    fun `Flag zu - 409 home-edit-off, KEIN Write, KEINE Audit-Zeile`() = withHa { url, _ ->
        val (audit, auditPath) = auditLog()
        var writes = 0
        val res = assign(
            controller(url, editEnabled = false, writer = { _, _ -> writes++; RegistryWriteOutcome.Ok }, audit = audit),
            "light.wohnzimmer_decke",
            "wohnzimmer",
        )

        assertEquals(409, res.statusCode.value())
        assertEquals("home-edit-off", (res.body as SettingsError).error)
        assertEquals(0, writes)
        assertFalse(Files.exists(auditPath)) // kein Write ⇒ keine Audit-Datei
    }

    @Test
    fun `leere areaId - 400 invalid-area`() = withHa { url, _ ->
        val (audit, _) = auditLog()
        val res = assign(
            controller(url, editEnabled = true, writer = { _, _ -> RegistryWriteOutcome.Ok }, audit = audit),
            "light.wohnzimmer_decke",
            "   ",
        )

        assertEquals(400, res.statusCode.value())
        assertEquals("invalid-area", (res.body as SettingsError).error)
    }

    @Test
    fun `unbekannte Area (nicht im Katalog) - 400 unknown-area`() = withHa { url, _ ->
        val (audit, _) = auditLog()
        val res = assign(
            controller(url, editEnabled = true, writer = { _, _ -> RegistryWriteOutcome.Ok }, audit = audit),
            "light.wohnzimmer_decke",
            "keller",
        )

        assertEquals(400, res.statusCode.value())
        assertEquals("unknown-area", (res.body as SettingsError).error)
    }

    @Test
    fun `unbekannte entityId (nicht im Snapshot) - 404 unknown-entity, KEIN Write, Audit rejected_unknown_entity`() =
        withHa { url, _ ->
            val (audit, auditPath) = auditLog()
            var writes = 0
            val res = assign(
                controller(url, editEnabled = true, writer = { _, _ -> writes++; RegistryWriteOutcome.Ok }, audit = audit),
                "light.unbekannt",
                "wohnzimmer",
            )

            assertEquals(404, res.statusCode.value())
            assertEquals("unknown-entity", (res.body as SettingsError).error)
            assertEquals(0, writes) // read-first: unbekannte Entity ⇒ NIE ein Write-Aufruf

            // Trotz Ablehnung: EINE Audit-Zeile (Verfassung — jede Op geloggt).
            val audited = Files.readString(auditPath)
            assertTrue(audited.contains("\"outcome\":\"rejected_unknown_entity\""))
            assertTrue(audited.contains("light.unbekannt"))
        }

    @Test
    fun `Katalog unerreichbar - 502 home-edit-unreachable, kein Write`() {
        val (audit, _) = auditLog()
        var writes = 0
        // Dead port ⇒ registry() == null ⇒ keine Validierung möglich ⇒ ehrlich 502.
        val res = assign(
            HomeEditController(
                registryAdapter = HaHomeRegistryAdapter(baseUrl = "http://127.0.0.1:1", token = "secret-token", timeoutMs = 500),
                writer = { _, _ -> writes++; RegistryWriteOutcome.Ok },
                auditLog = audit,
                editEnabled = true,
            ),
            "light.x",
            "wohnzimmer",
        )

        assertEquals(502, res.statusCode.value())
        assertEquals("home-edit-unreachable", (res.body as SettingsError).error)
        assertEquals(0, writes)
    }

    @Test
    fun `bekannte Area + Writer Ok - 200, Audit ok, read-first Invalidate (frischer Read danach)`() =
        withHa { url, hits ->
            val (audit, auditPath) = auditLog()
            val calls = mutableListOf<Pair<String, String>>()
            // Adapter direkt halten, um die read-first-Invalidierung zu beobachten.
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            val ctrl = HomeEditController(
                registryAdapter = adapter,
                writer = { e, a -> calls.add(e to a); RegistryWriteOutcome.Ok },
                auditLog = audit,
                editEnabled = true,
            )

            val res = assign(ctrl, "light.wohnzimmer_decke", "wohnzimmer")

            assertEquals(200, res.statusCode.value())
            val result = res.body as AreaAssignmentResult
            assertEquals("light.wohnzimmer_decke", result.entityId)
            assertEquals("wohnzimmer", result.areaId)
            // Writer wurde mit genau (entityId, areaId) gerufen.
            assertEquals(listOf("light.wohnzimmer_decke" to "wohnzimmer"), calls)
            // Der Validierungs-Read hat den Katalog EINMAL geholt (dann gecacht).
            val hitsAfterAssign = hits.get()
            // read-first: nach Ok ist der Cache invalidiert ⇒ der nächste Read trifft HA erneut.
            adapter.registry()
            assertTrue(hits.get() > hitsAfterAssign, "invalidate() muss den Cache verwerfen (frischer Read)")

            // Audit: eine ok-Zeile, mit entityId/areaId, OHNE Token.
            val audited = Files.readString(auditPath)
            assertTrue(audited.contains("\"outcome\":\"ok\""))
            assertTrue(audited.contains("light.wohnzimmer_decke"))
            assertTrue(audited.contains("wohnzimmer"))
            assertFalse(audited.contains("secret-token"))
        }

    @Test
    fun `Reassign (Entity war bereits in wohnzimmer) - Audit traegt fromAreaId UND toAreaId`() =
        withHa { url, _ ->
            val (audit, auditPath) = auditLog()
            val res = assign(
                controller(url, editEnabled = true, writer = { _, _ -> RegistryWriteOutcome.Ok }, audit = audit),
                "light.wohnzimmer_decke",
                "kueche",
            )

            assertEquals(200, res.statusCode.value())
            val result = res.body as AreaAssignmentResult
            assertEquals("kueche", result.areaId)

            // Reconstruierbar: die Zeile traegt SOWOHL die alte (wohnzimmer) ALS AUCH
            // die neue (kueche) Area — nicht nur die neue.
            val audited = Files.readString(auditPath)
            assertTrue(audited.contains("\"fromAreaId\":\"wohnzimmer\""), audited)
            assertTrue(audited.contains("\"toAreaId\":\"kueche\""), audited)
        }

    @Test
    fun `bekannte Area + Writer Failed - 502 home-edit-write-failed, Audit failed`() = withHa { url, _ ->
        val (audit, auditPath) = auditLog()
        val res = assign(
            controller(
                url,
                editEnabled = true,
                writer = { _, _ -> RegistryWriteOutcome.Failed("ha-error:not_found") },
                audit = audit,
            ),
            "light.wohnzimmer_decke",
            "wohnzimmer",
        )

        assertEquals(502, res.statusCode.value())
        assertEquals("home-edit-write-failed", (res.body as SettingsError).error)
        val audited = Files.readString(auditPath)
        assertTrue(audited.contains("\"outcome\":\"failed\""))
        assertTrue(audited.contains("ha-error:not_found"))
    }
}
