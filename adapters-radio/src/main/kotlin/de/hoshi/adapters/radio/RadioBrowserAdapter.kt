package de.hoshi.adapters.radio

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.RadioStation
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * **RadioBrowserAdapter** — löst einen Stationsnamen über die keyless
 * community-API **radio-browser.info** zu einer abspielbaren [RadioStation] auf:
 *
 *   `GET {base}/json/stations/byname/{name}?limit=…&hidebroken=true&order=votes&reverse=true`
 *
 * Die API verlangt einen aussagekräftigen **User-Agent** (Community-Etikette,
 * sonst drohen Blocks) — wird immer gesetzt. Kein Key, kein Konto, kein Egress
 * von irgendwas außer dem Stationsnamen ⇒ die risikofreie Stufe A.
 *
 * **Andi-Schwelle (kein stilles Falsch-Matching):** ein Kandidat zählt NUR bei
 * echter Namensähnlichkeit zum gesuchten Namen — normalisiert (lowercase,
 * Umlaute gefaltet), dann **enthält-oder-Levenshtein-nah** ([isSimilar]).
 * Unter der Schwelle: `null` ⇒ der Fastpath antwortet warm NOT_FOUND, statt
 * irgendeinen „besten Treffer trotzdem" zu starten. Unter den ähnlichen
 * Kandidaten gewinnt der mit den meisten **votes** (Community-Signal).
 *
 * **Honesty-Charter (never-throw):** jeder Fehler (Netz/Timeout/Non-2xx/
 * kaputtes JSON) ⇒ `null` + warn-Log — nie ein Throw nach außen. Kandidaten
 * ohne Stream-URL (`url_resolved` bevorzugt, sonst `url`) werden übersprungen.
 */
class RadioBrowserAdapter(
    baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Long = 5000,
    /** Community-Etikette der radio-browser-API: immer einen ehrlichen UA senden. */
    private val userAgent: String = "Hoshi/0.8 (open-source home assistant; radio search)",
    /** Wie viele Top-Kandidaten (nach votes) geprüft werden. */
    private val limit: Int = 20,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Name → beste ähnliche Station (votes-stärkste über der Schwelle) oder `null`. */
    fun search(name: String): RadioStation? {
        val query = name.trim()
        if (query.isEmpty()) return null
        val stations = fetchByName(query) ?: return null
        return stations
            .filter { it.streamUrl.isNotBlank() && isSimilar(query, it.name) }
            .maxByOrNull { it.votes }
            ?.let { RadioStation(name = it.name, streamUrl = it.streamUrl) }
    }

    // ── HTTP + Parsing (never-throw) ─────────────────────────────────────────

    private data class Candidate(val name: String, val streamUrl: String, val votes: Long)

    private fun fetchByName(query: String): List<Candidate>? {
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val uri = URI.create(
                "$base/json/stations/byname/$encoded?limit=$limit&hidebroken=true&order=votes&reverse=true",
            )
            val req = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[radio-browser] byname('{}') → HTTP {}", query, resp.statusCode())
                return null
            }
            parse(resp.body())
        } catch (e: Exception) {
            log.warn("[radio-browser] byname('{}') warf: {}", query, e.message)
            null
        }
    }

    /** Parst das Stations-Array; kaputte/leere Antwort ⇒ `null`/leer (nie Throw). */
    private fun parse(body: String?): List<Candidate>? {
        val root = try {
            mapper.readTree(body ?: return null)
        } catch (e: Exception) {
            log.warn("[radio-browser] Antwort kein JSON: {}", e.message)
            return null
        }
        if (!root.isArray) return null
        return root.mapNotNull { node ->
            val stationName = node.text("name") ?: return@mapNotNull null
            // Defensive zusätzlich zu hidebroken=true: tote Streams überspringen.
            if (node.path("lastcheckok").asInt(1) == 0) return@mapNotNull null
            val stream = node.text("url_resolved") ?: node.text("url") ?: ""
            Candidate(name = stationName, streamUrl = stream, votes = node.path("votes").asLong(0))
        }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).asText("").takeIf { it.isNotBlank() }

    // ── Andi-Schwelle: echte Namensähnlichkeit ───────────────────────────────

    /**
     * Ähnlich genug? Normalisiert beide Namen und akzeptiert NUR
     * **enthält** (in beide Richtungen: „wdr 2" ⊂ „WDR 2 Rhein und Ruhr")
     * **oder Levenshtein-nah** (Distanz ≤ max(1, ¼ der kürzeren Länge) —
     * fängt Tippfehler/Hörer wie „antenne bayer" → „Antenne Bayern",
     * lässt „xyzzy" ↛ „Radio Paradise" NICHT durch).
     */
    internal fun isSimilar(query: String, stationName: String): Boolean {
        val q = normalize(query)
        val s = normalize(stationName)
        if (q.isEmpty() || s.isEmpty()) return false
        if (s.contains(q) || q.contains(s)) return true
        return levenshtein(q, s) <= max(1, min(q.length, s.length) / 4)
    }

    /** Lowercase, Umlaute gefaltet (ä→ae … ß→ss), Rest auf `[a-z0-9 ]` reduziert. */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Klassische Levenshtein-Distanz (klein genug für Stationsnamen, O(n·m)). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        /** Deutscher Community-Mirror; per Ctor überschreibbar (Tests: Fake-Server). */
        const val DEFAULT_BASE_URL = "https://de1.api.radio-browser.info"
    }
}
