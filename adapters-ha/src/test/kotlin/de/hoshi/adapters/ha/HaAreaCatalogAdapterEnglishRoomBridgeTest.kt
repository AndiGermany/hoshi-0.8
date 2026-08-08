package de.hoshi.adapters.ha

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.DeterministicToolIntentClassifier
import de.hoshi.core.skills.SkillStatePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * **End-to-end-Beweis der Regressions-Naht (2026-07-25):** ein [DeterministicToolIntentClassifier]
 * verdrahtet mit einem ECHTEN [HaAreaCatalogAdapter] (fake-HA per JDK-HttpServer, exakt
 * das Muster aus [HaAreaCatalogAdapterTest]) — also GENAU der Prod-Pfad bei
 * `HOSHI_TOOLS_ENABLED=true` + `HOSHI_AREAS_DYNAMIC_ENABLED=true` (`PipelineConfig.intentClassifier`).
 *
 * Ohne die [HaAreaCatalogAdapter.mergeStaticAliases]-Bruecke waere „turn on the light
 * in the living room" hier NUR zufaellig durch den Wohnzimmer-DEFAULT gelandet (die
 * 0.8.2-Regression) — dieser Test beweist, dass der Mehrwort-Alias „living room"
 * ECHT über den Token-Paar-Pfad ([DeterministicToolIntentClassifier.roomOrNull]) auf
 * die reale `area_id` matcht, gespeist aus dem dynamischen (nicht dem statischen)
 * Katalog.
 */
class HaAreaCatalogAdapterEnglishRoomBridgeTest {

    /** Der Live-Payload traegt NUR `id::Name` — kein "living room"/"kitchen" (s. Klassen-KDoc HaAreaCatalogAdapter). */
    private val templateBody = "wohnzimmer::Wohnzimmer||kuche::Küche||schlafzimmer::Schlafzimmer"

    private val toolsOnly: SkillStatePort =
        SkillStatePort.ofStatic(smartHome = true, scenes = false, timer = false, calculator = false)

    private fun withClassifier(block: (DeterministicToolIntentClassifier) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            val bytes = templateBody.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val adapter = HaAreaCatalogAdapter(baseUrl = "http://127.0.0.1:${server.address.port}", token = "secret-token")
            val classifier = DeterministicToolIntentClassifier(skills = toolsOnly, areaCatalog = adapter)
            block(classifier)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `dynamischer Katalog OHNE native Aliase behaelt living room ueber den Token-Paar-Pfad`() = withClassifier { classifier ->
        val call = classifier.classify("turn on the light in the living room", Language.EN)!!
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("wohnzimmer", call.data["area_id"], "die reale HA-area_id, aus dem DYNAMISCHEN Katalog gemerged")
    }

    @Test
    fun `EN turn off the light in the living room schaltet ueber den dynamischen Katalog das Wohnzimmer aus`() = withClassifier { classifier ->
        val call = classifier.classify("turn off the light in the living room", Language.EN)!!
        assertEquals("turn_off", call.service)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `Kompositum-Nachbarschaft living room lights trifft ebenfalls ueber den dynamischen Katalog`() = withClassifier { classifier ->
        val call = classifier.classify("turn on the living room lights", Language.EN)!!
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `der zuerst genannte Mehrwort-Raum gewinnt auch mit dem dynamischen Katalog`() = withClassifier { classifier ->
        assertEquals(
            "wohnzimmer",
            classifier.classify("turn on the light in the living room and the kitchen", Language.EN)!!.data["area_id"],
        )
        assertEquals(
            "kuche",
            classifier.classify("turn on the kitchen light and the living room one", Language.EN)!!.data["area_id"],
        )
    }

    @Test
    fun `EN Lese-Pfad targetet den Raum aus dem dynamischen Katalog statt des Haus-Aggregats`() = withClassifier { classifier ->
        val call = classifier.classify("how warm is it in the living room", Language.EN)!!
        assertEquals("sensor", call.domain)
        assertEquals("read_temperature", call.service)
        assertTrue(call.read)
        assertEquals("wohnzimmer", call.data["area_id"])
    }

    @Test
    fun `DE-Pfad bleibt ueber den dynamischen Katalog unveraendert`() = withClassifier { classifier ->
        assertEquals("wohnzimmer", classifier.classify("mach das Licht im Wohnzimmer an", Language.DE)!!.data["area_id"])
        assertEquals("kuche", classifier.classify("schalte die Küche an", Language.DE)!!.data["area_id"])
    }
}
