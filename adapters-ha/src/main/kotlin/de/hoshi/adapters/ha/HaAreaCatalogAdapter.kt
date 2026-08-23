package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.AreaCatalogPort
import de.hoshi.core.port.AreaInfo
import de.hoshi.core.tools.ToolAreas
import org.slf4j.LoggerFactory
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * **HaAreaCatalogAdapter** — lädt die echten Areas **READ-ONLY** von Home Assistant
 * (`POST {baseUrl}/api/template`, Bearer-Token) und erfüllt den [AreaCatalogPort]
 * (Andi-Auftrag 2026-07-15: die Raum-Liste soll DYNAMISCH aus HA synchron bleiben
 * statt hart codiert in [de.hoshi.core.tools.ToolAreas] zu leben).
 *
 * **EXAKT nach dem [HaSceneCatalogAdapter]-Muster** (read-only, never-throw, Token
 * nie geloggt) — mit ZWEI bewussten Unterschieden, weil Areas LOAD-BEARING sind
 * (jeder Licht-Befehl hängt an ihnen, anders als der Szenen-Katalog, der bei leer
 * einfach auf den naiven Fallback zurückfällt):
 *
 *  1. **TTL-Cache statt Einmal-Cache:** eine in HA umbenannte/neu angelegte Area
 *     soll ohne Prozess-Neustart auftauchen — der Cache refresht alle [ttl]
 *     (Default 15 min), nicht nur beim allerersten Aufruf.
 *  2. **Nie leer bei Ausfall:** schlägt ein Refresh fehl (HA down/Timeout/Parse-
 *     Fehler), bleibt der LETZTE erfolgreiche Cache-Stand aktiv; gab es NIE einen
 *     erfolgreichen Load, fällt der Adapter auf [staticFallback] zurück (Default
 *     [AreaCatalogPort.STATIC]) — ein leerer Katalog würde JEDEN Raum-Befehl
 *     regressieren, das ist strukturell ausgeschlossen.
 *
 * **Read-only Naht:** der Template-Call rendert `area_id::Name` je Area, mit `||`
 * getrennt (bewusst KEIN JSON — dasselbe robuste Pipe-Format wie [HaToolPort]s
 * Readback-Templates, keine Abhängigkeit von einem bestimmten Jinja-JSON-Filter).
 * `area_name(a)` kann `none` liefern (Area ohne Namen) → dann der Slug als Label.
 *
 * **Aliase — die 2026-07-25-Regressions-Naht:** [parseAreas] baut die Alias-Menge
 * je Area bisher NUR aus `{id, name.lowercase()}`. Die kuratierten englischen
 * Aliase („living room"→wohnzimmer, „kitchen"→küche, …) leben ausschließlich in
 * [ToolAreas.ROOMS] — sobald DIESER Adapter gewinnt (`HOSHI_AREAS_DYNAMIC_ENABLED`,
 * heute in Prod AN), verlieren sie sonst ihr Raum-Mapping (die 0.8.2-Lehre „living
 * room traf das Wohnzimmer nur, weil wohnzimmer der Default ist" käme zurück).
 * [mergeStaticAliases] schließt die Lücke: die kuratierten Aliase aus [ToolAreas.ROOMS]
 * werden pro `area_id` NACHTRÄGLICH in die dynamisch geladenen [AreaInfo]s gemischt.
 *
 * **HA-native Aliase (Area-Registry `aliases`-Feld) werden HEUTE NICHT gelesen** —
 * geprüft und verworfen: HA's Jinja-Template-Umgebung (worauf dieser Adapter über
 * `POST /api/template` sitzt) kennt `areas()`/`area_id()`/`area_name()`/
 * `area_entities()`/`area_devices()`, aber KEINE `area_aliases()`-Funktion; das
 * native Alias-Feld der Area-Registry ist nur über die WebSocket-Config-API
 * (`config/area_registry/list`) erreichbar — ein ANDERES Protokoll als der
 * heutige REST-Template-Call. Diese Naht bewusst NICHT gewechselt (Auftrag: kein
 * Endpunkt-Wechsel ad-hoc) → **offene BE-Naht:** ein künftiger
 * `HaAreaRegistryAdapter` (oder eine WebSocket-Erweiterung dieses Adapters) müsste
 * `config/area_registry/list` lesen, um echte HA-native Aliase zu liefern; bis
 * dahin ist [mergeStaticAliases] die einzige Alias-Quelle jenseits von id/Label.
 * [mergeStaticAliases] ist bereits SO geschrieben, dass native Aliase (sobald sie
 * eines Tages als Teil von `dynamic` ankommen) die statische Brücke schlagen —
 * s. Kollisionsregel dort.
 *
 * **Stale-while-revalidate (Stabilitäts-Fix 2026-08-20):** [areas] ist synchron
 * (der Port ist es) und wird MITTEN aus dem Turn gerufen — in Prod auf einem
 * Netty-Event-Loop-Thread ([de.hoshi.core.pipeline.ToolIntentClassifier] eager im
 * Chat-Turn). Vorher lief der blockierende `HttpClient.send` (5 s) bei TTL-Ablauf
 * DIREKT dort und noch dazu unter `synchronized(this)` ⇒ ein langsames HA parkte
 * den Event-Loop und alle parallelen Turns stauten am Monitor. Jetzt gilt:
 *
 *  - **Warm:** Cache innerhalb [ttl] ⇒ sofort zurück (wie bisher).
 *  - **Warm aber abgelaufen:** der ALTE Stand geht SOFORT zurück, der Refresh
 *    läuft asynchron auf [refreshExecutor] (Reactor-`boundedElastic`). Eine
 *    15-min-alte Raumliste ist strikt besser als ein blockierter Event-Loop.
 *  - **Kalt (Boot):** auf einem Nicht-Blockier-Thread (Event-Loop) ⇒ SOFORT
 *    [staticFallback] + asynchroner Refresh; auf einem blockier-erlaubten Thread
 *    (Boot-Warmup, Tests, WS-Worker) ⇒ EINMAL bounded warten (der HTTP-Call läuft
 *    trotzdem auf [refreshExecutor], nie auf dem Event-Loop).
 *
 * Der HTTP-Call steht damit NIE unter einem Monitor: der Lock schützt nur noch
 * das Veröffentlichen des Single-Flight-Futures (Mikrosekunden, kein I/O).
 *
 * **Read-Timeout (derselbe Fix):** [timeoutMs] war vorher nur `connectTimeout` +
 * `HttpRequest.timeout`. Letzteres bindet im JDK NUR die Zeit bis zu den Response-
 * HEADERN — sobald HA die Header geschickt hat und dann beim BODY stockt, wartet
 * `send` unbegrenzt (genau die halb-tote Verbindung, die ein hängendes HA erzeugt).
 * Deshalb läuft der Call jetzt über [HttpClient.sendAsync] mit einer harten
 * Gesamt-Wanduhr von [timeoutMs] über den ganzen Austausch (Connect + Header +
 * Body); reisst sie, wird der Austausch gecancelt ⇒ `null` ⇒ Cache/Fallback.
 */
class HaAreaCatalogAdapter(
    baseUrl: String,
    private val token: String?,
    /** Cache-Frische: nach Ablauf wird beim nächsten Aufruf neu geladen (best-effort). */
    private val ttl: Duration = Duration.ofMinutes(15),
    private val timeoutMs: Long = 5000,
    /** Injizierbar für deterministische Tests (TTL-Ablauf ohne echtes Warten). */
    private val clock: Clock = Clock.systemUTC(),
    /** Greift NUR, solange NIE ein erfolgreicher HA-Load da war (s. Klassen-KDoc). */
    private val staticFallback: AreaCatalogPort = AreaCatalogPort.STATIC,
    /**
     * Wo der HTTP-Refresh läuft — NIE der Aufrufer-Thread (s. Klassen-KDoc
     * „Stale-while-revalidate"). Default: Reactors `boundedElastic` (geteilt,
     * beschränkt, Daemon — kein eigener Pool zum Aufräumen). Tests können einen
     * Direkt-Executor injizieren, um den Refresh deterministisch zu machen.
     */
    private val refreshExecutor: Executor = Executor { Schedulers.boundedElastic().schedule(it) },
) : AreaCatalogPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    @Volatile private var cached: List<AreaInfo>? = null
    @Volatile private var cachedAt: Instant = Instant.MIN

    /** Schützt NUR [inFlight] (Single-Flight-Buchhaltung) — nie einen HTTP-Call. */
    private val refreshLock = Any()
    private var inFlight: CompletableFuture<List<AreaInfo>?>? = null

    override fun areas(): List<AreaInfo> {
        val snapshot = cached
        if (snapshot != null && Duration.between(cachedAt, clock.instant()) < ttl) return snapshot
        // Ab hier: Cache kalt ODER abgelaufen ⇒ Refresh anstossen (single-flight,
        // asynchron auf refreshExecutor — nie auf dem Aufrufer-Thread).
        val refresh = triggerRefresh()
        // Stale-while-revalidate: alte Daten schlagen einen blockierten Event-Loop.
        if (snapshot != null) return snapshot
        // Kalter Cache: auf einem Nicht-Blockier-Thread (Netty-Event-Loop) NIE warten.
        if (Schedulers.isInNonBlockingThread()) return staticFallback.areas()
        // Blockier-erlaubter Thread (Boot/Tests/Worker): EINMAL bounded warten.
        awaitBounded(refresh)?.let { return it }
        // HA-Ausfall/Timeout/Parse-Fehler: letzter Cache-Stand gewinnt, sonst der
        // statische Fallback (NIE ein leerer Katalog, s. Klassen-KDoc).
        return cached ?: staticFallback.areas()
    }

    /**
     * Startet höchstens EINEN Refresh gleichzeitig und gibt dessen Future zurück.
     * Der Lock umschliesst ausschliesslich das Setzen/Lesen von [inFlight] — der
     * HTTP-Call passiert danach auf [refreshExecutor], AUSSERHALB des Monitors.
     */
    private fun triggerRefresh(): CompletableFuture<List<AreaInfo>?> {
        val started: CompletableFuture<List<AreaInfo>?>
        synchronized(refreshLock) {
            inFlight?.let { return it }
            started = CompletableFuture()
            inFlight = started
        }
        val task = Runnable {
            val fresh = try {
                loadOnce()
            } catch (e: Exception) {
                // never-throw: loadOnce faengt schon alles ab, das hier ist die Wand
                // gegen Errors aus dem Executor-Thread (nie ein toter Refresh-Slot).
                log.warn("[ha-areas] Refresh warf unerwartet: {} (Katalog unveraendert)", e.message)
                null
            }
            if (fresh != null) {
                cached = fresh
                cachedAt = clock.instant()
            }
            synchronized(refreshLock) { inFlight = null }
            started.complete(fresh)
        }
        try {
            refreshExecutor.execute(task)
        } catch (e: Exception) {
            // Executor abgelehnt (Shutdown/saturiert): Slot sofort freigeben, damit der
            // naechste Aufruf es erneut versuchen kann — nie dauerhaft „refreshing".
            synchronized(refreshLock) { inFlight = null }
            started.complete(null)
            log.warn("[ha-areas] Refresh nicht startbar: {} (Katalog unveraendert)", e.message)
        }
        return started
    }

    /** Wartet bounded auf den kalten Erst-Load; Timeout/Fehler ⇒ `null` (never-throw). */
    private fun awaitBounded(refresh: CompletableFuture<List<AreaInfo>?>): List<AreaInfo>? =
        try {
            refresh.get(timeoutMs + COLD_WAIT_SLACK_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[ha-areas] Erst-Load nicht rechtzeitig fertig: {} (Fallback greift)", e.message)
            null
        }

    /** Einmaliger READ-ONLY Load. Jeder Fehler/leeres Ergebnis ⇒ `null` (never-throw). */
    private fun loadOnce(): List<AreaInfo>? {
        if (token.isNullOrBlank()) return null
        return try {
            val payload = mapper.writeValueAsString(mapOf("template" to AREA_TEMPLATE))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = sendBounded(req)
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-areas] POST /api/template → HTTP {} (Katalog unveraendert)", resp.statusCode())
                return null
            }
            mergeStaticAliases(parseAreas(resp.body())).ifEmpty { null }
        } catch (e: Exception) {
            // never-throw: Netz/Timeout/Parse-Fehler → null (Caller faellt auf Cache/Fallback zurueck).
            log.warn("[ha-areas] POST /api/template warf: {} (Katalog unveraendert)", e.message)
            null
        }
    }

    /**
     * Der READ-Timeout, den `java.net.http` nicht kennt: [HttpClient.Builder] hat NUR
     * `connectTimeout`, und `HttpRequest.timeout` endet mit den Response-Headern.
     * `sendAsync` + [java.util.concurrent.Future.get] mit Deadline bindet dagegen den
     * GANZEN Austausch inklusive Body. Reisst die Deadline, wird der Austausch
     * gecancelt (sonst hinge die Verbindung im Client-Pool weiter) und die Exception
     * fliegt an [loadOnce]s never-throw-Wand.
     *
     * Läuft ausschliesslich auf `refreshExecutor` (s. Klassen-KDoc), also auf einem
     * blockier-erlaubten Thread — NIE auf dem Netty-Event-Loop.
     */
    private fun sendBounded(req: HttpRequest): HttpResponse<String> {
        val future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw e
        }
    }

    /** Parst die `id::Name||id::Name…`-Antwort des Templates; jede kaputte Zeile wird übersprungen. */
    private fun parseAreas(raw: String?): List<AreaInfo> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()
        return body.split("||").mapNotNull { part ->
            val idx = part.indexOf("::")
            if (idx < 0) return@mapNotNull null
            val id = part.substring(0, idx).trim()
            val name = part.substring(idx + 2).trim()
            if (id.isBlank()) return@mapNotNull null
            // Blank HA name ⇒ NEVER the raw slug as label: `label` is spoken (clarify
            // question, HaToolPort readback), and a slug is a key, not a name — HA
            // slugifies ü→u, so `kuche` would be heard as „kuche"/„Kuche" instead of
            // „Küche" (Andi 2026-08-22). [ToolAreas.label] is the curated anchor and
            // is exactly what [AreaCatalogPort.STATIC] uses for the same job.
            AreaInfo(
                areaId = id,
                label = name.ifBlank { ToolAreas.label(id) },
                aliases = setOf(id, name.lowercase()).filter { it.isNotBlank() }.toSet(),
            )
        }
    }

    /**
     * **Die statische Brücke** (s. Klassen-KDoc „Aliase"): mischt die kuratierten
     * [ToolAreas.ROOMS]-Aliase pro `area_id` in die dynamisch geladenen [dynamic]-
     * Areas — NUR für `area_id`s, die im statischen Mapping bekannt sind (unbekannte
     * dynamische Areas, z.B. eine neu in HA angelegte, bleiben unangetastet: für sie
     * gibt es keine kuratierten Wörter).
     *
     * **Kollisionsregel: dynamisch/HA-nativ gewinnt, statisch füllt nur Lücken.**
     * Ein Alias-WORT wird nur dann aus der statischen Brücke übernommen, wenn es
     * NOCH VON KEINER ANDEREN dynamischen Area beansprucht wird — beansprucht eine
     * andere Area (dynamisch/HA-nativ) dasselbe Wort bereits, gewinnt SIE, die
     * statische Brücke fügt es nicht hinzu (kein Diebstahl/Überschreiben eines vom
     * Live-Katalog vergebenen Worts). Beansprucht dieselbe Area das Wort schon
     * selbst, ist es ein No-op (Mengen-Vereinigung). So gilt dieselbe Regel bereits
     * heute (die dynamischen Aliase sind nur `{id, name.lowercase()}`) UND später,
     * sobald echte HA-native Aliase (s. Klassen-KDoc) Teil von [dynamic] werden.
     */
    internal fun mergeStaticAliases(dynamic: List<AreaInfo>): List<AreaInfo> {
        if (dynamic.isEmpty()) return dynamic
        val staticAliasesByArea: Map<String, Set<String>> =
            ToolAreas.ROOMS.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
        // Jedes Alias-Wort, das IRGENDEINE dynamische Area schon traegt -> deren area_id
        // (bei doppelter Vergabe gewinnt die zuerst gesehene Area; praktisch kollisionsfrei,
        // da jede Area-Alias-Menge heute schon disjunkt ist).
        val claimedBy = LinkedHashMap<String, String>()
        for (area in dynamic) for (alias in area.aliases) claimedBy.putIfAbsent(alias, area.areaId)
        return dynamic.map { area ->
            val bridge = staticAliasesByArea[area.areaId].orEmpty()
            val gapFillers = bridge.filter { alias -> claimedBy[alias] == null || claimedBy[alias] == area.areaId }
            if (gapFillers.isEmpty()) area else area.copy(aliases = area.aliases + gapFillers)
        }
    }

    private companion object {
        /** Puffer über [timeoutMs] fürs kalte Warten (Executor-Übergabe + Parse). */
        const val COLD_WAIT_SLACK_MS = 500L

        /**
         * READ-ONLY Jinja-Template: jede HA-Area als `area_id::Name`, mit `||`
         * verbunden. `area_name(a) | default(a)` fängt Areas ohne Namen ab (dann der
         * Slug selbst als Label) — nie ein leeres/„None"-Label.
         */
        const val AREA_TEMPLATE =
            "{% set ns = namespace(parts=[]) %}" +
                "{% for a in areas() %}" +
                "{% set ns.parts = ns.parts + [a ~ '::' ~ (area_name(a) | default(a, true))] %}" +
                "{% endfor %}" +
                "{{ ns.parts | join('||') }}"
    }
}
