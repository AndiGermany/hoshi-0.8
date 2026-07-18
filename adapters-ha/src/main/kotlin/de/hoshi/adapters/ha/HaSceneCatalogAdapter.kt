package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.SceneCatalogPort
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * **HaSceneCatalogAdapter** — lädt die echten `scene_id`s **READ-ONLY** von Home
 * Assistant (`GET {baseUrl}/api/states`, Bearer-Token), filtert die Entities mit
 * Präfix `scene.` und strippt es → `List<String>` (z.B. `wohnzimmer_nordlichter`).
 *
 * Erfüllt den [SceneCatalogPort] für den [de.hoshi.core.tools.SceneMatcher]. KEIN
 * Schalten — der einzige HA-Call ist das lesende `/api/states`.
 *
 * **Synchroner JDK-[HttpClient]** (wie [HaToolPort], kein WebClient) — der Katalog
 * wird beim ersten [sceneIds]-Aufruf EINMAL geladen und **gecacht** (best-effort).
 * HA unerreichbar / Fehler / kein Token / Non-2xx ⇒ **leere Liste** (never-throw):
 * dann fällt der Classifier sauber auf sein naives `scene.<token>`-Verhalten zurück.
 * Das Token wird NIE geloggt.
 */
class HaSceneCatalogAdapter(
    baseUrl: String,
    private val token: String?,
    private val timeoutMs: Long = 5000,
) : SceneCatalogPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    @Volatile private var cached: List<String>? = null

    override fun sceneIds(): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return loadOnce().also { cached = it }
        }
    }

    /** Einmaliger READ-ONLY Load. Jeder Fehler endet warm in einer leeren Liste. */
    private fun loadOnce(): List<String> {
        if (token.isNullOrBlank()) return emptyList()
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/states"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-scenes] GET /api/states → HTTP {} (leerer Szenen-Katalog)", resp.statusCode())
                return emptyList()
            }
            parseScenes(resp.body())
        } catch (e: Exception) {
            // never-throw: Netz/Timeout/Parse-Fehler → leerer Katalog (Token nie loggen).
            log.warn("[ha-scenes] GET /api/states warf: {} (leerer Szenen-Katalog)", e.message)
            emptyList()
        }
    }

    /** Filtert `entity_id` mit Präfix `scene.` aus dem States-Array und strippt das Präfix. */
    private fun parseScenes(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val root = mapper.readTree(body)
        if (!root.isArray) return emptyList()
        val out = ArrayList<String>()
        for (node in root) {
            val id = node.get("entity_id")?.asText() ?: continue
            if (id.startsWith(PREFIX)) out.add(id.removePrefix(PREFIX))
        }
        return out
    }

    private companion object {
        const val PREFIX = "scene."
    }
}
