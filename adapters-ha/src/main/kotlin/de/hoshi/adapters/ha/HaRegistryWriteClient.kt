package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Ergebnis EINES Registry-Schreibvorgangs. `never-throw`: der [HaRegistryWriteClient]
 * wirft nie nach außen — jeder Fehler (Auth abgelehnt, HA-Fehler, Timeout, Netz)
 * wird zu [Failed]; der [reason] ist NUR fürs Log/Audit, nie ein Token/Secret.
 */
sealed class RegistryWriteOutcome {
    /** HA hat die Zuweisung akzeptiert (`result.success == true`). */
    object Ok : RegistryWriteOutcome()

    /** HA hat abgelehnt oder die Verbindung/der Handshake schlug fehl. */
    data class Failed(val reason: String) : RegistryWriteOutcome()
}

/**
 * Der schmale Schreib-Kontrakt, den der Web-Rand ([de.hoshi.web] `HomeEditController`)
 * kennt — eine `fun interface`, damit der Controller ohne echte WebSocket-Naht
 * testbar ist (Fake-Lambda) und der [HaRegistryWriteClient] die einzige reale
 * Implementierung bleibt (hexagonale Trennung, exakt wie [de.hoshi.core.port.ToolPort]).
 */
fun interface RegistryWriter {
    /** Weist [entityId] die HA-Area [areaId] zu (Entity-Ebene, überschreibt Device-Erbe). */
    fun assignEntityArea(entityId: String, areaId: String): RegistryWriteOutcome
}

/**
 * **HaRegistryWriteClient** — die EINZIGE Schreib-Naht von Scheibe 2 des
 * Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`): „HA bleibt die eine Wahrheit, Hoshi wird ihr
 * Editor." Er fasst NUR ein Registry-Objekt über die **offizielle HA-WebSocket-API**
 * an (`/api/websocket`), nie YAML/Configs/Integrationen — Verfassungs-Regel 1.
 *
 * **Warum WebSocket (nicht REST wie [HaToolPort]):** die Registry-Mutationen
 * (`config/entity_registry/update`, `config/device_registry/update`) sind
 * ausschließlich über die WS-API erreichbar — es gibt keinen REST-Endpoint dafür
 * [EXTERN https://developers.home-assistant.io/docs/api/websocket/].
 *
 * **Kurzlebige Verbindung pro Write** (kein Pool, kein Dauer-Socket): pro
 * [assignEntityArea] wird EINE Verbindung geöffnet, der Handshake gefahren, der
 * eine Update-Befehl gesendet, das `result` abgewartet, dann sauber geschlossen.
 * Für einen seltenen manuellen UI-Klick ist das die einfachste ehrliche Naht;
 * ein Verbindungs-Pool wäre Overhead ohne Nutzen.
 *
 * **Protokoll** (HA-WS-Handshake, [EXTERN]):
 *  1. Server → `{"type":"auth_required"}`
 *  2. Client → `{"type":"auth","access_token":"<token>"}`
 *  3. Server → `{"type":"auth_ok"}` (sonst `auth_invalid` ⇒ [RegistryWriteOutcome.Failed])
 *  4. Client → `{"id":1,"type":"config/entity_registry/update","entity_id":…,"area_id":…}`
 *  5. Server → `{"id":1,"type":"result","success":true|false,…}`
 *
 * **Entity-Ebene, nicht Device-Ebene** (kleinster Weg, dokumentiert): ein
 * `entity_registry`-`area_id` überschreibt das Device-Erbe der Entity direkt —
 * das ist exakt das, was die HA-UI beim „diesem Gerät einen Raum geben" tut, und
 * braucht KEINEN zweiten `device_registry`-Write. Erst wenn ein künftiger Fall
 * echtes Device-Umhängen verlangt (mehrere Entities EINES Devices gemeinsam),
 * kommt `config/device_registry/update` dazu — bewusst NICHT auf Vorrat gebaut.
 *
 * Der Token wird nie geloggt; die Protokoll-Logik ([RegistryWriteProtocol]) ist
 * ohne echten Socket testbar (Nachrichten rein, Frames/Outcome raus).
 */
class HaRegistryWriteClient(
    baseUrl: String,
    private val token: String?,
    /** Budget für Connect UND fürs Warten aufs `result` (je einzeln). */
    private val timeoutMs: Long = 5000,
    /** Injizierbar für Tests; der Default unterstützt WebSocket out-of-the-box. */
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : RegistryWriter {
    private val log = LoggerFactory.getLogger(javaClass)

    /** `http(s)://host:port` → `ws(s)://host:port/api/websocket` (Scheme-Mapping, ein Ort). */
    private val wsUri: URI = run {
        val trimmed = baseUrl.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "ws://$trimmed"
        }
        URI.create("$ws/api/websocket")
    }

    override fun assignEntityArea(entityId: String, areaId: String): RegistryWriteOutcome {
        val t = token
        if (t.isNullOrBlank()) {
            // Ehrlich: ohne Token gibt es keinen Write — kein Connect, kein Fake-Erfolg.
            return RegistryWriteOutcome.Failed("no-token")
        }
        val proto = RegistryWriteProtocol(t, entityId, areaId, mapper)
        var socket: WebSocket? = null
        return try {
            socket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .buildAsync(wsUri, ProtoListener(proto))
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            proto.result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // never-throw: Connect-/Handshake-/Warte-Fehler enden warm als Failed.
            log.warn("[ha-write] Registry-Write warf: {} (Zuweisung NICHT bestätigt)", e.message)
            RegistryWriteOutcome.Failed("exception:${e.javaClass.simpleName}")
        } finally {
            // Kurzlebige Verbindung: immer schließen (best-effort, ein Close-Fehler ändert nichts).
            socket?.let { s -> runCatching { s.sendClose(WebSocket.NORMAL_CLOSURE, "done") } }
        }
    }

    /**
     * WS-Listener: akkumuliert Text-Fragmente bis zur kompletten Nachricht und
     * gibt sie an [RegistryWriteProtocol]; die zurückgegebenen Frames werden gesendet.
     * Jeder Transport-Fehler ⇒ [RegistryWriteOutcome.Failed] (kein Hänger).
     */
    private inner class ProtoListener(private val proto: RegistryWriteProtocol) : WebSocket.Listener {
        private val buf = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val message = buf.toString()
                buf.setLength(0)
                runCatching { proto.onMessage(message).forEach { webSocket.sendText(it, true) } }
                    .onFailure { proto.result.complete(RegistryWriteOutcome.Failed("send:${it.message}")) }
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            proto.result.complete(RegistryWriteOutcome.Failed("ws-error:${error.message}"))
        }
    }
}

/**
 * Die REINE HA-WS-Protokoll-Logik von [HaRegistryWriteClient], ohne echten Socket —
 * `internal`, damit Modul-Tests sie Nachricht-für-Nachricht fahren können
 * (auth_required → auth, auth_ok → update, result/auth_invalid → Outcome). Sie
 * kennt weder Netz noch Threads: [onMessage] nimmt EINE HA-Nachricht und liefert
 * die zu sendenden Frames; das [result]-Future wird gesetzt, sobald das Ergebnis
 * feststeht. So ist der Handshake deterministisch prüfbar, ganz ohne WS-Server.
 */
internal class RegistryWriteProtocol(
    private val token: String,
    private val entityId: String,
    private val areaId: String,
    private val mapper: ObjectMapper,
    private val commandId: Int = 1,
) {
    /** Wird genau EINMAL gesetzt, sobald HA das `result` (oder `auth_invalid`) liefert. */
    val result = CompletableFuture<RegistryWriteOutcome>()

    /** Verarbeitet EINE eingehende HA-Nachricht; Rückgabe = zu sendende Frames (0..1). */
    fun onMessage(raw: String): List<String> {
        val node = try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            result.complete(RegistryWriteOutcome.Failed("bad-json"))
            return emptyList()
        }
        return when (node.path("type").asText()) {
            "auth_required" -> listOf(authFrame())
            "auth_ok" -> listOf(updateFrame())
            "auth_invalid" -> {
                result.complete(RegistryWriteOutcome.Failed("auth_invalid"))
                emptyList()
            }
            "result" -> {
                handleResult(node)
                emptyList()
            }
            // pong/event/andere Typen ignorieren (kurzlebige Verbindung, ein Kommando).
            else -> emptyList()
        }
    }

    private fun authFrame(): String =
        mapper.writeValueAsString(linkedMapOf("type" to "auth", "access_token" to token))

    private fun updateFrame(): String =
        mapper.writeValueAsString(
            linkedMapOf(
                "id" to commandId,
                "type" to "config/entity_registry/update",
                "entity_id" to entityId,
                "area_id" to areaId,
            ),
        )

    private fun handleResult(node: JsonNode) {
        // Nur die Antwort AUF UNSEREN Befehl zählt (fremde ids ignorieren).
        if (node.path("id").asInt(-1) != commandId) return
        if (node.path("success").asBoolean(false)) {
            result.complete(RegistryWriteOutcome.Ok)
        } else {
            val code = node.path("error").path("code").asText("unknown")
            result.complete(RegistryWriteOutcome.Failed("ha-error:$code"))
        }
    }
}
