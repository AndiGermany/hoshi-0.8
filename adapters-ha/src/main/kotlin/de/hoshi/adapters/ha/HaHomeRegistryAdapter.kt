package de.hoshi.adapters.ha

import com.fasterxml.jackson.annotation.JsonInclude
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
 *
 * **Live-Zustands-Naht (additiv, Draht-Vertrag Andi 2026-08-11, „Zuhause-
 * Kacheln"):** [state] ist der ROHE HA-Zustand (`"docked"`, `"heat"`,
 * `"21.5"`, `"unavailable"` — HA's eigener String, unverändert durchgereicht)
 * oder `null`, wenn kein Zustand bekannt ist (Entity nicht im States-Call
 * gefunden ODER der States-Call selbst ist gescheitert). [attrs] trägt NUR die
 * fixierte Attribut-Allowlist ([HaHomeRegistryAdapter.ATTR_ALLOWLIST]),
 * stringifiziert, und NUR die Schlüssel, die HA tatsächlich lieferte — ein
 * leeres Objekt ist der ehrliche Normalfall für die meisten Domains (z.B.
 * `light`, `switch`). Beide Felder werden NACHTRÄGLICH von
 * [HaHomeRegistryAdapter.loadFull] aus einem separaten `GET /api/states`-Call
 * in den vom Template geparsten Snapshot gemischt — [HaHomeRegistryAdapter.parseSnapshot]
 * selbst kennt sie nicht, baut Entities weiterhin mit den Defaults (`null`/leer).
 *
 * **Last-known-good-Fallback (additiv, Andi-Auftrag 2026-08-13, „Sauger-
 * Sichtbarkeits-Lücke"):** [lastKnown] ist NUR gesetzt, wenn [state] GERADE
 * unbrauchbar ist (`null`/`unavailable`/`unknown`) UND für diese Entity
 * irgendwann zuvor ein brauchbarer Zustand gemerkt wurde (s.
 * [HaHomeRegistryAdapter.applyStates]/[HaLastKnownStateStore]) — NIE
 * gleichzeitig mit einem brauchbaren [state]. Der Live-Zustand selbst bleibt
 * dadurch unverfälscht; das FE entscheidet, ob/wie es den alten Stand zeigt.
 */
data class HomeRegistryEntity(
    val entityId: String,
    val domain: String,
    val name: String,
    val labels: List<String> = emptyList(),
    val state: String? = null,
    val attrs: Map<String, String> = emptyMap(),
    val lastKnown: LastKnownEntityState? = null,
    /**
     * **Cache-Carry-Marker (additiv AM ZEILENENDE, Andi 2026-08-21: „dann müssen
     * wir die Daten cachen und verwenden. Meistens ist er einfach im
     * Energiesparmodus"):** GESETZT heißt „[state]/[attrs] dieser Entity kommen
     * NICHT live von HA, sondern aus dem gemerkten Stand" — der Wert ist der
     * Zeitpunkt der letzten LIVE-Sichtung in Epoch-Millisekunden, aus dem ein
     * Leser direkt „Stand HH:MM" formatieren kann (Epoch statt Alter-in-ms
     * bewusst: ein Alter würde über die States-TTL hinweg driften, ein
     * Zeitstempel nie). ABWESEND heißt „live" — und nur das.
     *
     * Gesetzt ausschließlich von [VacuumFamily.carryCache], also NUR für die
     * Sauger-Familie: jede andere Domain behält ihr bisheriges Verhalten
     * unverändert (Licht/Klima zeigen weiter ehrlich `unavailable`, s. dortige
     * Ehrlichkeits-Regeln). [lastKnown] bleibt beim Carry zusätzlich gesetzt —
     * kein bestehender Leser verliert Information.
     *
     * `NON_NULL`: bei `null` fehlt das Feld im JSON komplett (Muster
     * [de.hoshi.core.dto.ChatEvent.Done.toolCallRan]) — der Draht bleibt für
     * jede nicht-gecachte Entity byte-identisch zu vorher, und ein alter Parser
     * sieht exakt die Payload, die er kennt.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val fromCacheSinceMs: Long? = null,
)

/** Wire-Form von [LastKnownState]: `seenAt` als ISO-8601-String (`Instant.toString()`), explizit statt via Default-Jackson-Zeitformat. */
data class LastKnownEntityState(val state: String, val attrs: Map<String, String>, val seenAt: String)

/**
 * Eine HA-Area mit ihren zugeordneten Entities. Bewusst AUCH ohne ein einziges
 * Gerät in der Liste (eine leere Area ist ein ehrlicher Zustand, kein Fehler —
 * z.B. ein Raum, den Andi in HA angelegt, aber noch nicht bestückt hat).
 */
data class HomeRegistryArea(
    val areaId: String,
    val label: String,
    val entities: List<HomeRegistryEntity> = emptyList(),
    /**
     * **Räume-Nutzungs-Naht (additiv, Default `0` — KEIN Breaking Change,
     * kartiert in Commit f049965 / `frontend/src/components/roomsSort.ts`-KDoc
     * „Konzept 1a mangels Datengrundlage NICHT gebaut"):** Anzahl der SMART_HOME-
     * Turns der letzten 14 Tage, deren [de.hoshi.core.port.TurnTrace.targetAreaId]
     * genau [areaId] traf — von [de.hoshi.web.HomeRegistryController] aus dem
     * Turn-Diary NACHTRÄGLICH in den Snapshot gemischt (dieser Adapter selbst
     * bleibt READ-ONLY-Spiegel von HA, kennt kein Diary). `0` heißt EHRLICH
     * ENTWEDER „wirklich nie angesteuert" ODER „Diary/Feature noch AUS"
     * ([de.hoshi.core.port.TurnTracePort.NOOP] schreibt nie) — beides
     * ununterscheidbar, aber NIE ein erfundener positiver Wert. Additiv/
     * optional: bestehende Konstruktions-Stellen ohne diesen Parameter (z.B.
     * [HaHomeRegistryAdapter.parseSnapshot]) bleiben unverändert bei `0`.
     */
    val recentCommands: Int = 0,
)

/**
 * Der ganze READ-ONLY Snapshot: alle HA-Areas (auch leere) + die Entities OHNE
 * Area-Zuordnung separat (`unassigned`) — GENAU die „tado-Lücke" ehrlich
 * sichtbar machen statt sie zu verstecken (Andi-Auftrag, Scheibe 1 des
 * Geräte-Zuordnungs-Konzepts, `.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`).
 *
 * [statesFetchedAt] (additiv, Andi-Auftrag 2026-08-13): ISO-8601-Zeitpunkt
 * des letzten ERFOLGREICHEN `GET /api/states`-Merges (auch eine echte leere
 * Antwort `[]` zählt als Erfolg), `null` heißt „noch nie erfolgreich" — s.
 * [HaHomeRegistryAdapter.applyStates].
 */
data class HomeRegistrySnapshot(
    val areas: List<HomeRegistryArea>,
    val unassigned: List<HomeRegistryEntity>,
    val statesFetchedAt: String? = null,
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
 *
 * **Live-Zustands-Naht (additiv, Draht-Vertrag Andi 2026-08-11):** NACH einem
 * erfolgreichen Template-Load folgt EIN zusätzlicher `GET {baseUrl}/api/states`
 * (derselbe [client]/Token wie oben, derselbe [timeoutMs]) — liefert ALLE
 * States+Attribute in einem Rutsch, wird per `entity_id` in den geparsten
 * Snapshot gemischt ([mergeStates]). Dieser zweite Call ist BEST EFFORT UND
 * UNABHÄNGIG vom Template-Erfolg: scheitert er (Timeout/Non-2xx/kaputtes
 * JSON), liefert [loadStates] never-throw `null`, und der Snapshot geht
 * trotzdem raus — jede Entity bleibt dann bei ihrem Default (`state=null`,
 * `attrs={}`) ODER bei ihrem [lastKnownStore]-Fallback, EHRLICH statt der
 * Rand tot. Der Rand bleibt also bei einem Ausfall NUR des States-Calls
 * verfügbar (anders als beim Template-Call, dessen Ausfall den ganzen Load
 * scheitern lässt, s.o.).
 *
 * **States-Frische, GETRENNT vom Template-TTL (Andi-Auftrag 2026-08-13,
 * „Sauger-Sichtbarkeits-Lücke"):** der Roborock hängt ~23 h/Tag im WLAN-
 * Tiefschlaf, sein Wach-Fenster ist oft nur ~60 s/Tag lang. Der Template-Load
 * bleibt teuer (Jinja-Render über ALLE States) und behält seine 15-min-[ttl];
 * der States-Call ist dagegen EIN billiges `GET` — [statesTtl] (Default 60 s,
 * ENV-Knopf `HOSHI_HA_STATES_TTL_MS` via [de.hoshi.web.HomeRegistryConfig])
 * refresht ihn UNABHÄNGIG und HÄUFIGER, OHNE den Template-Snapshot neu zu
 * laden: [registry] prüft beide TTLs getrennt — ist nur die States-TTL
 * abgelaufen, holt [refreshStatesIfDue] NUR `GET /api/states` und mischt in
 * den WEITERHIN gültigen Template-Snapshot, ein zweiter `POST /api/template`
 * bleibt aus. Erst wenn die Template-TTL selbst abläuft, folgt wieder ein
 * voller [loadFull]-Zyklus (der die States-TTL gleich mit auffrischt).
 *
 * **Last-known-good-Fallback (additiv, s. [LastKnownStateStore]):** jede
 * Entity, deren gemergter Zustand USABLE ist (state weder `null` noch
 * `unavailable`/`unknown`), wird bei [applyStates] im [lastKnownStore]
 * gemerkt. Ist der LIVE-Zustand einer Entity dagegen unbrauchbar UND
 * existiert ein gemerkter Stand, hängt [HomeRegistryEntity.lastKnown] ihn an
 * — DAS macht die Roborock-Sichtbarkeits-Lücke erträglich, ohne den
 * ehrlichen Live-Zustand (`state`) selbst zu verfälschen.
 *
 * **Sauger-Cache-Carry (additiv, Andi 2026-08-21: „dann müssen wir die Daten
 * cachen und verwenden. Meistens ist er einfach im Energiesparmodus"):** der
 * Last-known-Fallback allein reichte nicht — er ließ die Kachel im NORMALFALL
 * (schlafender Sauger) auf „zuletzt gesehen vor X" degradieren, also den
 * Regelzustand wie einen Ausfall aussehen. [applyStates] lässt darum ZULETZT
 * [VacuumFamily.carryCache] laufen: NUR für die Sauger-Familie (Stamm-Regel,
 * s. dort) werden `state`/`attrs` aus dem gemerkten Stand weitergeliefert und
 * mit [HomeRegistryEntity.fromCacheSinceMs] als Cache MARKIERT. Jenseits von
 * [vacuumCacheMaxAge] (Default 24 h, ENV-Knopf
 * `HOSHI_VACUUM_CACHE_MAX_AGE_HOURS`) passiert nichts — dann bleibt es beim
 * heutigen Unavailable-Bild. Jede andere Domain ist unberührt.
 */
class HaHomeRegistryAdapter(
    baseUrl: String,
    private val token: String?,
    /** Cache-Frische des TEMPLATE-Loads: nach Ablauf wird beim nächsten Aufruf neu geladen (best-effort). */
    private val ttl: Duration = Duration.ofMinutes(15),
    private val timeoutMs: Long = 5000,
    /** Injizierbar für deterministische Tests (TTL-Ablauf ohne echtes Warten). */
    private val clock: Clock = Clock.systemUTC(),
    /** EIGENE, kurze Cache-Frische NUR für den States-Merge (s. Klassen-KDoc „States-Frische"). */
    private val statesTtl: Duration = Duration.ofSeconds(60),
    /** Last-known-good-Speicher (s. Klassen-KDoc) — Default: reiner RAM-Fallback OHNE Persistenz (Prod-Wiring in [de.hoshi.web.HomeRegistryConfig]). */
    private val lastKnownStore: LastKnownStateStore = InMemoryLastKnownStateStore(),
    /**
     * Obergrenze für den Sauger-Cache-Carry ([VacuumFamily.carryCache], s.
     * Klassen-KDoc „Sauger-Cache-Carry"). `Duration.ZERO` schaltet ihn ab.
     */
    private val vacuumCacheMaxAge: Duration = Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    @Volatile private var cached: HomeRegistrySnapshot? = null
    @Volatile private var cachedAt: Instant = Instant.MIN

    /** Letzter (versuchter) States-Refresh — eigene Uhr, getrennt von [cachedAt] (s. Klassen-KDoc). */
    @Volatile private var statesRefreshedAt: Instant = Instant.MIN

    /** Der aktuelle Snapshot — `null` NUR wenn NIE ein erfolgreicher Load gelang. */
    fun registry(): HomeRegistrySnapshot? {
        val now = clock.instant()
        cached?.let { if (Duration.between(cachedAt, now) < ttl) return refreshStatesIfDue(now) ?: cached }
        synchronized(this) {
            cached?.let { if (Duration.between(cachedAt, now) < ttl) return refreshStatesIfDue(now) ?: cached }
            val fresh = loadFull(now)
            if (fresh != null) {
                cached = fresh
                cachedAt = now
                statesRefreshedAt = now
                return fresh
            }
            // HA-Ausfall/Timeout/Parse-Fehler: letzter Cache-Stand gewinnt, sonst `null`
            // (nie geladen ⇒ ehrlich „gerade nicht erreichbar", s. Klassen-KDoc).
            return cached
        }
    }

    /**
     * States-Frische, GETRENNT vom Template-TTL (s. Klassen-KDoc): innerhalb
     * eines noch gültigen Template-Snapshots (der Aufrufer hat das bereits
     * geprüft) refresht diese Methode NUR `GET /api/states`, sobald
     * [statesTtl] abgelaufen ist — der Template-Snapshot selbst bleibt
     * unangetastet. `null` heißt „nicht dran / ein anderer Thread war
     * schneller" — der Aufrufer fällt dann auf den weiterhin gültigen
     * [cached]-Stand zurück.
     */
    private fun refreshStatesIfDue(now: Instant): HomeRegistrySnapshot? {
        if (Duration.between(statesRefreshedAt, now) < statesTtl) return null
        synchronized(this) {
            if (Duration.between(statesRefreshedAt, now) < statesTtl) return null // ein anderer Thread war schneller
            val templateSnapshot = cached ?: return null // defensiv: müsste unter diesem Lock-Pfad noch stehen
            statesRefreshedAt = now
            val states = loadStates()
            val updated = applyStates(templateSnapshot, states, now)
            cached = updated
            return updated
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

    /** Einmaliger READ-ONLY Voll-Load (Template + States, beide TTL-Uhren werden vom Aufrufer gesetzt). Jeder Fehler/kaputtes Ergebnis ⇒ `null` (never-throw). */
    private fun loadFull(now: Instant): HomeRegistrySnapshot? {
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
            val snapshot = parseSnapshot(resp.body()) ?: return null
            // Live-Zustands-Naht (s. Klassen-KDoc): best effort, unabhaengig vom
            // Template-Erfolg — scheitert dieser zweite Call, bleibt der Snapshot
            // trotzdem gueltig (jede Entity dann bei state=null/attrs={} oder ihrem lastKnown-Fallback).
            applyStates(snapshot, loadStates(), now)
        } catch (e: Exception) {
            // never-throw: Netz/Timeout/Parse-Fehler → null (Caller faellt auf Cache zurueck).
            log.warn("[ha-registry] POST /api/template warf: {} (Registry unveraendert)", e.message)
            null
        }
    }

    /**
     * Einmaliger READ-ONLY Load von `GET /api/states` (s. Klassen-KDoc). NIE
     * wirft diese Methode — jeder Fehler (kein Token, Non-2xx, Netz/Timeout,
     * kaputtes JSON) endet in `null` (FEHLSCHLAG, unterscheidbar von einer
     * echten leeren Antwort `[]`), damit [applyStates] `statesFetchedAt` NUR
     * bei einem tatsaechlichen Erfolg fortschreibt.
     */
    private fun loadStates(): Map<String, HaStateInfo>? {
        if (token.isNullOrBlank()) return null
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
                log.warn("[ha-registry] GET /api/states -> HTTP {} (Zustaende bleiben null)", resp.statusCode())
                return null
            }
            parseStates(resp.body())
        } catch (e: Exception) {
            // never-throw: der States-Call ist best effort, s. Klassen-KDoc.
            log.warn("[ha-registry] GET /api/states warf: {} (Zustaende bleiben null)", e.message)
            null
        }
    }

    /**
     * Parst die `GET /api/states`-Antwort (JSON-Array von `{entity_id, state,
     * attributes}`, HA's Standardformat) zu einer `entity_id -> `[HaStateInfo]-Map.
     * Kaputtes/kein Array ⇒ leere Map (never-throw, Aufrufer [loadStates] faengt
     * jede Exception). `state` wird ROH durchgereicht (auch `"unavailable"`/
     * `"unknown"` sind gueltige HA-Zustaende, keine Sonderbehandlung); JSON
     * `null`/fehlend ⇒ `null`. `attrs` filtert strikt auf [ATTR_ALLOWLIST] —
     * fremde Attribut-Keys werden NIE durchgereicht.
     */
    private fun parseStates(raw: String?): Map<String, HaStateInfo> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = mapper.readTree(raw)
        if (!root.isArray) return emptyMap()
        val out = LinkedHashMap<String, HaStateInfo>()
        for (node in root) {
            val entityId = node.get("entity_id")?.takeIf { !it.isNull }?.asText() ?: continue
            val stateNode = node.get("state")
            val state = if (stateNode != null && !stateNode.isNull) stateNode.asText() else null
            val attrsNode = node.get("attributes")
            val attrs = LinkedHashMap<String, String>()
            if (attrsNode != null && attrsNode.isObject) {
                for (key in ATTR_ALLOWLIST) {
                    val value = attrsNode.get(key)
                    if (value != null && !value.isNull) attrs[key] = value.asText()
                }
            }
            out[entityId] = HaStateInfo(state = state, attrs = attrs)
        }
        return out
    }

    /** Mischt [states] (per `entity_id`) additiv in [snapshot]; unbekannte Entities bleiben beim Default (`null`/leer). */
    private fun mergeStates(snapshot: HomeRegistrySnapshot, states: Map<String, HaStateInfo>): HomeRegistrySnapshot {
        if (states.isEmpty()) return snapshot
        fun enrich(entity: HomeRegistryEntity): HomeRegistryEntity {
            val info = states[entity.entityId] ?: return entity
            return entity.copy(state = info.state, attrs = info.attrs)
        }
        return snapshot.copy(
            areas = snapshot.areas.map { area -> area.copy(entities = area.entities.map(::enrich)) },
            unassigned = snapshot.unassigned.map(::enrich),
        )
    }

    /**
     * Mischt [states] in [snapshot] ([mergeStates]), merkt danach jede Entity
     * mit USABLE Live-Zustand im [lastKnownStore] und hängt umgekehrt jeder
     * Entity mit UNBRAUCHBAREM Live-Zustand ihren gemerkten Stand an, falls
     * einer existiert (s. Klassen-KDoc „Last-known-good-Fallback"). `states`
     * `null` (States-Call gescheitert) ⇒ [mergeStates] bleibt aus (Snapshot
     * unveraendert), `statesFetchedAt` bleibt beim vorherigen Wert stehen —
     * NUR ein tatsaechlicher Erfolg (auch eine leere Liste `[]`) ruumt ihn auf `now`.
     */
    private fun applyStates(snapshot: HomeRegistrySnapshot, states: Map<String, HaStateInfo>?, now: Instant): HomeRegistrySnapshot {
        val merged = if (states != null) mergeStates(snapshot, states) else snapshot
        val allEntities = merged.areas.asSequence().flatMap { it.entities } + merged.unassigned
        val usableNow = allEntities
            .filter { isUsableState(it.state) }
            .associate { it.entityId to LastKnownState(state = it.state!!, attrs = it.attrs, seenAt = now) }
        if (usableNow.isNotEmpty()) lastKnownStore.record(usableNow) // never-throw, s. LastKnownStateStore-Vertrag

        fun attachLastKnown(entity: HomeRegistryEntity): HomeRegistryEntity {
            if (isUsableState(entity.state)) return entity
            val lk = lastKnownStore.get(entity.entityId) ?: return entity
            return entity.copy(lastKnown = LastKnownEntityState(state = lk.state, attrs = lk.attrs, seenAt = lk.seenAt.toString()))
        }
        val withLastKnown = merged.copy(
            areas = merged.areas.map { area -> area.copy(entities = area.entities.map(::attachLastKnown)) },
            unassigned = merged.unassigned.map(::attachLastKnown),
            statesFetchedAt = if (states != null) now.toString() else merged.statesFetchedAt,
        )
        // Sauger-Cache-Carry ZULETZT (s. Klassen-KDoc): er baut genau auf dem eben
        // angehängten lastKnown auf und fasst NUR die Sauger-Familie an.
        return VacuumFamily.carryCache(withLastKnown, now, vacuumCacheMaxAge)
    }

    /** `state` weder `null` noch `unavailable`/`unknown` — dieselbe Regel, die das FE in `homeTiles.ts#isEntityAvailable` spiegelt. */
    private fun isUsableState(state: String?): Boolean = state != null && state != "unavailable" && state != "unknown"

    /** Ergebnis eines `/api/states`-Eintrags, s. [parseStates]. */
    private data class HaStateInfo(val state: String?, val attrs: Map<String, String>)

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
         * Fixierter Draht-Vertrag (Andi 2026-08-11, „Zuhause-Kacheln"): NUR diese
         * HA-Attribut-Keys duerfen in [HomeRegistryEntity.attrs] landen, egal was
         * HA sonst noch an Attributen mitschickt (z.B. `friendly_name`,
         * `supported_features`, `icon`, …) — bewusst eine Allowlist, keine
         * Blockliste, damit neue/unbekannte HA-Attribute NIE ungeprüft durchsickern.
         *
         * **Additiv erweitert (Andi 2026-08-13, „Sauger-Metrik-Familie"):**
         * `unit_of_measurement` dazu — dieselbe Regel wie die anderen vier: NUR
         * stringifiziert durchgereicht, NIE interpretiert/umgerechnet. Das FE zeigt
         * die Einheit ausschliesslich, wenn HA sie mitliefert (nie geraten), s.
         * `frontend/src/components/homeTiles.ts#vacuumMaintenanceValue`.
         */
        val ATTR_ALLOWLIST = listOf(
            "battery_level",
            "current_temperature",
            "temperature",
            "hvac_action",
            "unit_of_measurement",
        )

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
