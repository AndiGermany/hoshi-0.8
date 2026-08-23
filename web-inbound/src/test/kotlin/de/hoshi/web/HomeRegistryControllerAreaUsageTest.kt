package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.HomeRegistrySnapshot
import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **HomeRegistryControllerAreaUsageTest** — die Räume-Nutzungs-Naht am
 * `GET /api/v1/home/registry`-Rand (kartiert in Commit f049965): beweist,
 * dass [HomeRegistryController.registry] die 14-Tage-Zählung aus
 * [AreaUsageReader] additiv in [de.hoshi.adapters.ha.HomeRegistryArea.recentCommands]
 * mischt — UND den byte-neutralen Default ([AreaUsageReader.NONE], keine
 * Bean verdrahtet ⇒ jede Area bleibt bei `0`), exakt das Muster von
 * [HomeRegistryControllerTest].
 */
class HomeRegistryControllerAreaUsageTest {

    private val templateBody =
        "wohnzimmer::Wohnzimmer||kueche::Kueche" +
            "@@ENTITIES@@" +
            "light.wohnzimmer_deckenlampe::wohnzimmer::Deckenlampe::||" +
            "light.kueche_spot::kueche::Spot::"

    private fun withHa(block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            val bytes = templateBody.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun writeDiaryDay(dir: Path, day: java.time.LocalDate, areaIds: List<String>) {
        Files.createDirectories(dir)
        val file = dir.resolve("${JsonlTurnTraceAdapter.FILE_PREFIX}-${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl")
        val lines = areaIds.joinToString("\n") { areaId -> """{"ts":"${day}T09:00:00Z","targetAreaId":"$areaId"}""" }
        Files.write(file, lines.toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `recentCommands wird aus der Area-Zaehlung gemischt`(@TempDir dir: Path) = withHa { url ->
        val fixedNow = Instant.parse("2026-08-11T12:00:00Z")
        val today = java.time.LocalDate.ofInstant(fixedNow, ZoneOffset.UTC)
        writeDiaryDay(dir, today, listOf("kueche", "kueche"))
        val reader = AreaUsageReader(directory = dir, clock = Clock.fixed(fixedNow, ZoneOffset.UTC))

        val controller = HomeRegistryController(
            adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token"),
            haEnabled = true,
            areaUsage = reader,
        )
        val res = controller.registry()

        assertEquals(200, res.statusCode.value())
        val body = res.body as HomeRegistrySnapshot
        val kueche = body.areas.first { it.areaId == "kueche" }
        val wohnzimmer = body.areas.first { it.areaId == "wohnzimmer" }
        assertEquals(2, kueche.recentCommands, "zwei Diary-Treffer fuer kueche")
        assertEquals(0, wohnzimmer.recentCommands, "kein Treffer fuer wohnzimmer ⇒ ehrlich 0")
    }

    @Test
    fun `ohne verdrahtete Bean - AreaUsageReader NONE Default haelt recentCommands bei 0`() = withHa { url ->
        val controller = HomeRegistryController(
            adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token"),
            haEnabled = true,
            // areaUsage NICHT gesetzt ⇒ AreaUsageReader.NONE-Default
        )
        val res = controller.registry()

        assertEquals(200, res.statusCode.value())
        val body = res.body as HomeRegistrySnapshot
        assertEquals(0, body.areas.first { it.areaId == "kueche" }.recentCommands)
        assertEquals(0, body.areas.first { it.areaId == "wohnzimmer" }.recentCommands)
    }
}
