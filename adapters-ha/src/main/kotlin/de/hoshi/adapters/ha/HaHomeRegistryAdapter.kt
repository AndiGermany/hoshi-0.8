package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Ein einzelnes HA-Entity im Registry-Snapshot: seine `entity_id`, die daraus
 * abgeleitete Domain (Präfix vor dem ersten `.`), sein sprechbarer Name
 * (`State.name`, HA-seitig bereits auf `friendly_name` ▷ Objekt-Id gefallen)
 * und die HA-Label-Namen (leer, wenn kein Label gesetzt ist). READ-ONLY.
 */
data class HomeRegistryEntity(
    val entityId: String,
    val domain: String,
    val name: String,
    val labels: List<String> = emptyList(),
)

/**
 * Eine HA-Area mit ihren zugeordneten Entities. Bewusst AUCH ohne ein einziges
 * Gerät in der Liste (eine leere Area ist ein ehrlicher Zustand, kein Fehler —
 * z.B. ein Raum, den Andi in HA angelegt, aber noch nicht bestückt hat).
 */
data class HomeRegistryArea(
    val areaId: String,
    val label: String,
    val entities: List<HomeRegistryEntity> = emptyList(),
)

/**
 * Der ganze READ-ONLY Snapshot: alle HA-Areas (auch leere) + die Entities OHNE
 * Area-Zuordnung separat (`unassigned`) — GENAU die „tado-Lücke" ehrlich
 * sichtbar machen statt sie zu verstecken (Andi-Auftrag, Scheibe 1 des
 * Geräte-Zuordnungs-Konzepts, `.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`).
 */
data class HomeRegistrySnapshot(
    val areas: List<HomeRegistryArea>,
    val unassigned: List<HomeRegistryEntity>,
)

/**
 * **HaHomeRegistryAdapter** — lädt Areas + Entities (inkl. Area-Zuordnung und
 * Labels) **READ-ONLY** von Home Assistant (`POST {baseUrl}/api/template`,
 * EIN Call für beides). Scheibe 1 des Geräte-Zuordnungs-Konzepts: „HA bleibt
 * die eine Wahrheit, Hoshi wird ihr freundlicher Editor" — diese Scheibe ist
 * NUR LESEN, keine Schreiboperation, kein Hoshi-eigenes Parallel-Register.
 *
 * **Schwester-Adapter zu [HaAreaCatalogAdapter]** (bewusst NICHT erweitert):
 * der `AreaCatalogPort` (core-domain) speist den Tool-/Sprach-Pfad mit einer
 * schlanken `AreaInfo`-Aliasliste; dieser Adapter liefert die reichhaltigere
 * FE-Registry-Sicht (Entities + Labels je Area, plus „ohne Area") — zwei
 * Konsumenten, zwei Verträge, EIN gemeinsames HA-Adapter-Muster (TTL-Cache,
 * never-throw, Token nie geloggt).
 *
 * **Kein „nie leer"-Fallback wie beim Area-Katalog:** anders als der
 * Area-Katalog (load-bearing für den Tool-Pfad — ein leerer Katalog würde
 * Sprachbefehle regressieren) ist diese Registry eine reine FE-Anzeige. Bei
 * Ausfall/Fehler OHNE jeden Vorerfolg ist `null` die EHRLICHE Antwort (FE
 * zeigt „gerade nicht erreichbar" statt erfundener Räume/Geräte). Nach einem
 * Erfolg gilt dasselbe TTL-Cache-Muster wie beim Area-Katalog: der letzte gute
 * Stand bleibt aktiv, bis ein neuer Load gelingt.
 *
 * **Read-only Naht:** EIN Jinja-Template liefert zwei Pipe-Blöcke, getrennt
 * durch den literalen Marker [ENTITY_SEP] (bewusst kein JSON, s.
 * [HaAreaCatalogAdapter]-KDoc — dasselbe robuste Pipe-Format):
 *  - Areas: `area_id::Name||…` (identisch zum Area-Katalog-Format)
 *  - Entities: `entity_id::area_id::Name::label1,label2||…` — eine LEERE
 *    `area_id` heißt „keine Area zugeordnet" ⇒ die Zeile landet in
 *    [HomeRegistrySnapshot.unassigned].
 *
 * Der Marker [ENTITY_SEP] ist IMMER im Template-Output enthalten (er ist ein
 * literales Text-Fragment des Templates, unabhängig davon, ob Areas/Entities
 * leer sind) — sein Fehlen im Antwort-Body ist der Garbage-Detektor: eine
 * kaputte/unerwartete Antwort (falsches Template, HA-Fehlerseite als 200,
 * …) zählt dann korrekt als Fehlversuch statt als „echtes, leeres Zuhause".
 */
class HaHomeRegistryAdapter(
    baseUrl: String,
    private val token: String?,
    /** Cache-Frische: nach Ablauf wird beim nächsten Aufruf neu geladen (best-effort). */
    private val ttl: Duration = Duration.ofMinutes(15),
    private val timeoutMs: Long = 5000,
    /** Injizierbar für deterministische Tests (TTL-Ablauf ohne echtes Warten). */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    @Volatile private var cached: HomeRegistrySnapshot? = null
    @Volatile private var cachedAt: Instant = Instant.MIN

    /** Der aktuelle Snapshot — `null` NUR wenn NIE ein erfolgreicher Load gelang. */
    fun registry(): HomeRegistrySnapshot? {
        val now = clock.instant()
        cached?.let { if (Duration.between(cachedAt, now) < ttl) return it }
        synchronized(this) {
            cached?.let { if (Duration.between(cachedAt, now) < ttl) return it }
            val fresh = loadOnce()
            if (fresh != null) {
                cached = fresh
                cachedAt = now
                return fresh
            }
            // HA-Ausfall/Timeout/Parse-Fehler: letzter Cache-Stand gewinnt, sonst `null`
            // (nie geladen ⇒ ehrlich „gerade nicht erreichbar", s. Klassen-KDoc).
            return cached
        }
    }

    /**
     * Verwirft den Cache-Stand, sodass der NÄCHSTE [registry]-Aufruf FRISCH von HA
     * lädt. Aufgerufen NACH einem erfolgreichen Registry-Write (Scheibe 2,
     * read-first-Verfassung: „nach jedem Write frisch lesen + Cache invalidieren"):
     * ohne das würde das FE bis zu [ttl] den alten Stand zeigen und die zugewiesene
     * Karte NICHT wandern sehen. Byte-neutral für den reinen Lese-Pfad (Scheibe 1).
     */
    fun invalidate() {
        synchronized(this) {
            cached = null
            cachedAt = Instant.MIN
        }
    }

    /** Einmaliger READ-ONLY Load. Jeder Fehler/kaputtes Ergebnis ⇒ `null` (never-throw). */
    private fun loadOnce(): HomeRegistrySnapshot? {
        if (token.isNullOrBlank()) return null
        return try {
            val payload = mapper.writeValueAsString(mapOf("template" to REGISTRY_TEMPLATE))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-registry] POST /api/template -> HTTP {} (Registry unveraendert)", resp.statusCode())
                return null
            }
            parseSnapshot(resp.body())
        } catch (e: Exception) {
            // never-throw: Netz/Timeout/Parse-Fehler → null (Caller faellt auf Cache zurueck).
            log.warn("[ha-registry] POST /api/template warf: {} (Registry unveraendert)", e.message)
            null
        }
    }

    /** Parst die `Areas@@ENTITIES@@Entities`-Antwort des Templates; s. Klassen-KDoc fürs Format. */
    internal fun parseSnapshot(raw: String?): HomeRegistrySnapshot? {
        val body = raw?.trim().orEmpty()
        val sep = body.indexOf(ENTITY_SEP)
        // Marker fehlt ⇒ keine echte Template-Antwort (Garbage-Detektor, s. Klassen-KDoc).
        if (sep < 0) return null
        val areasPart = body.substring(0, sep)
        val entitiesPart = body.substring(sep + ENTITY_SEP.length)

        val areaLabels = LinkedHashMap<String, String>()
        areasPart.split("||").forEach { part ->
            if (part.isBlank()) return@forEach
            val idx = part.indexOf("::")
            if (idx < 0) return@forEach
            val id = part.substring(0, idx).trim()
            val name = part.substring(idx + 2).trim()
            if (id.isBlank()) return@forEach
            areaLabels[id] = name.ifBlank { id }
        }

        val byArea = LinkedHashMap<String, MutableList<HomeRegistryEntity>>()
        val unassigned = ArrayList<HomeRegistryEntity>()
        entitiesPart.split("||").forEach { part ->
            if (part.isBlank()) return@forEach
            val fields = part.split("::", limit = 4)
            if (fields.size < 3) return@forEach // mind. entity_id::area_id::name
            val entityId = fields[0].trim()
            if (entityId.isBlank() || !entityId.contains('.')) return@forEach
            val areaId = fields[1].trim()
            val name = fields[2].trim().ifBlank { entityId }
            val labels = if (fields.size > 3) {
                fields[3].split(",").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val domain = entityId.substringBefore('.')
            val entity = HomeRegistryEntity(entityId = entityId, domain = domain, name = name, labels = labels)
            if (areaId.isBlank()) unassigned.add(entity) else byArea.getOrPut(areaId) { mutableListOf() }.add(entity)
        }

        // JEDE bekannte Area erscheint, auch ohne ein einziges Gerät (leere Area ehrlich sichtbar).
        val areas = areaLabels.map { (id, label) ->
            HomeRegistryArea(areaId = id, label = label, entities = byArea[id].orEmpty())
        }
        return HomeRegistrySnapshot(areas = areas, unassigned = unassigned)
    }

    private companion object {
        const val ENTITY_SEP = "@@ENTITIES@@"

        /**
         * READ-ONLY Jinja-Template, EIN Call für Areas + Entities:
         *  - Areas: `area_id::Name`, `||`-getrennt (identisch zu [HaAreaCatalogAdapter]).
         *  - Entities: iteriert `states` (ALLE aktiven Entities über alle Domains,
         *    dokumentiertes HA-Muster) und emittiert je Entity
         *    `entity_id::area_id::Name::label1,label2`, `||`-getrennt. `area_id(eid)`
         *    liefert `none`, wenn weder die Entity noch ihr Device eine Area hat ⇒
         *    `default('', true)` macht daraus einen LEEREN String (nicht „None").
         *    `s.name` ist HA's eigener Fallback friendly_name ▷ Objekt-Id (nie leer).
         *    `labels(eid)` liefert Label-IDs, `label_name(...)` löst sie zu Namen auf.
         *
         * Bewusst als EINE lange Zeile (keine Zeilenumbrüche zwischen den `{% %}`-Tags):
         * Jinja rendert Text ZWISCHEN Tags als literalen Output — ein Template über
         * mehrere Quellzeilen würde stille Zeilenumbrüche in die Antwort mischen
         * (exakt die Lehre aus [HaAreaCatalogAdapter.AREA_TEMPLATE]).
         */
        const val REGISTRY_TEMPLATE =
            "{% set an = namespace(parts=[]) %}" +
                "{% for a in areas() %}" +
                "{% set an.parts = an.parts + [a ~ '::' ~ (area_name(a) | default(a, true))] %}" +
                "{% endfor %}" +
                "{% set en = namespace(parts=[]) %}" +
                "{% for s in states %}" +
                "{% set eid = s.entity_id %}" +
                "{% set aid = area_id(eid) %}" +
                "{% set lblns = namespace(list=[]) %}" +
                "{% for lid in labels(eid) %}" +
                "{% set lblns.list = lblns.list + [label_name(lid)] %}" +
                "{% endfor %}" +
                "{% set en.parts = en.parts + [eid ~ '::' ~ (aid | default('', true)) ~ '::' ~ s.name ~ '::' ~ (lblns.list | join(','))] %}" +
                "{% endfor %}" +
                "{{ an.parts | join('||') }}$ENTITY_SEP{{ en.parts | join('||') }}"
    }
}
