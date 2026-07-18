package de.hoshi.adapters.memory

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.pipeline.EpisodicRecallPort
import de.hoshi.core.port.EpisodicWriter
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/**
 * **EpisodicMemoryAdapter** — Episodisches Gedächtnis (Ergänzung zum
 * [EntityMemoryAdapter]): Hoshi erinnert sich an **Gesprächskontext** aus
 * vergangenen Turns, nicht nur an strukturierte Fakten. „Hoshi erinnert sich an
 * etwas, das ich nie wiederholt habe."
 *
 * Erfüllt die Lese-Naht [EpisodicRecallPort.recallBlock] (RECALL): vor der Antwort
 * werden die zur aktuellen Frage **semantisch ähnlichsten** früheren Turns des
 * Sprechers als „[Früher gesagt: …]" in den System-Prompt geschichtet. Der
 * Schreib-Pfad ([record]) bettet substanzielle User-Turns ein und persistiert sie.
 *
 * **Semantik via embeddinggemma (Ollama :11434) — NUR Embeddings, kein zweiter
 * Brain-Call.** Die heilige Invariante „max 1 Brain-Call/Turn" bleibt unberührt:
 * der Embed-Roundtrip ist ein billiger Vektor-Call, kein Generieren. Embeddings
 * sind über [EpisodicEmbedder] injizierbar (Tests fahren deterministisch ohne Netz).
 *
 * **Mandanten-Trennung:** jede Zeile trägt die `speaker_id`; Recall liest NUR die
 * Turns des fragenden Sprechers. Ein **Gast** (leer/`unknown`/`gast`/ungültig) wird
 * NIE persistiert und bekommt NIE einen Block (Privacy/Tom-Veto, 0.8-Vision) —
 * dieselbe [EntityMemoryAdapter.isGuest]-Härtung wie das Entity-Gedächtnis.
 *
 * **Persistenz:** simpler sqlite-Store (JDBC), Datei standardmäßig unter
 * `~/.hoshi/episodic-memory.db`. Tabelle `episodic_turns(speaker_id, ts, text,
 * embedding)` — Embedding als komma-separierte Doubles. Gedeckelt auf die
 * jüngsten [CAP] Einträge pro Sprecher (Datensparsamkeit).
 *
 * **Default OFF** (`HOSHI_EPISODIC_ENABLED`, Privacy) — verdrahtet wird der Adapter
 * nur flag-gated; sonst bleibt der verhaltens-neutrale Stub (Recall=`""`).
 *
 * Bewusst NICHT portiert aus 0.5 (Phase-1-Gerüst, kein Block für 0.8): Supersession,
 * Nacht-Verdichtung/Decay, TTL, Schatten-Log. Das Store/Recall-Fundament steht
 * sauber; diese Schichten sind eigene, additive Schritte.
 */
class EpisodicMemoryAdapter(
    dbPath: String = defaultDbPath(),
    /**
     * Basis-URL des Embed-Sidecars (embeddinggemma via Ollama). Default
     * [DEFAULT_EMBED_URL] (`http://localhost:11434`) ⇒ Verhalten unverändert. Auf
     * dem Produktiv-Host (ct-106) lebt Ollama auf dem Mac im LAN — darum injizierbar
     * (PipelineConfig: `hoshi.memory.episodic-embed-url` / Env `HOSHI_EPISODIC_EMBED_URL`).
     * Speist NUR den Default-[OllamaEpisodicEmbedder]; ein explizit gesetzter
     * [embedder] (Tests, ohne Netz) ignoriert die URL.
     */
    embedUrl: String = DEFAULT_EMBED_URL,
    private val embedder: EpisodicEmbedder = OllamaEpisodicEmbedder(baseUrl = embedUrl),
    private val minSim: Double = MIN_SIM,
    private val topK: Int = TOP_K,
    /**
     * **Sensitive-Marker-Gate vor dem Persist** (`HOSHI_EPISODIC_SENSITIVE_FILTER_ENABLED`,
     * default **OFF** ⇒ byte-neutral). OFF → exakt das heutige Persist-Verhalten, der
     * Gate-Block wird übersprungen. ON → ein Turn mit Gesundheits-/Finanz-/Adress-/
     * Politik-/Religions-/Sexualitäts-Marker ([SensitiveMarker]) wird NICHT gespeichert;
     * geloggt wird NUR die Kategorie, nie der Klartext. Konservativ: im Zweifel speichern.
     */
    private val sensitiveFilterEnabled: Boolean = false,
) : EpisodicRecallPort, EpisodicWriter, AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private val conn: Connection

    init {
        val path = Paths.get(dbPath).toAbsolutePath()
        path.parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS episodic_turns (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    speaker_id TEXT NOT NULL,
                    ts         TEXT NOT NULL,
                    text       TEXT NOT NULL,
                    embedding  TEXT NOT NULL
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_episodic_speaker ON episodic_turns(speaker_id)")
        }
        log.info("[episodic-memory] sqlite-Store bereit: {}", path)
    }

    // ── RECALL (EpisodicRecallPort, lesend) ──────────────────────────────────
    /**
     * Liefert den Recall-Block für [text] keyed by [speakerId] (konsistent mit der
     * Store-Naht + Entity-Gedächtnis). Immer ein nicht-leerer Mono — `""` = kein
     * Block (Gast, leere Frage, zu wenig Ähnliches). Memory ist additiv: ein Fehler
     * kippt den Turn NIE, er wird zu `""` herunter-degradiert.
     */
    override fun recallBlock(speakerId: String, text: String): Mono<String> {
        if (EntityMemoryAdapter.isGuest(speakerId) || text.isBlank()) return Mono.just("")
        return Mono.fromCallable { recallNow(speakerId, text) }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { e ->
                log.warn("[episodic-memory] recall fehlgeschlagen ({}) — überspringe", e.message)
                Mono.just("")
            }
    }

    /** Synchroner Recall-Kern (Embed + Cosine-Scan). `internal` für deterministische Tests. */
    internal fun recallNow(speakerId: String, query: String): String {
        val qv = embedder.embed(query)
        if (qv.isEmpty()) return ""
        val entries = synchronized(lock) { loadEntries(speakerId) }
        if (entries.isEmpty()) return ""
        val hits = entries.asSequence()
            .map { it to cosine(qv, it.vec) }
            .filter { it.second >= minSim }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
        if (hits.isEmpty()) return ""
        log.info(
            "[episodic-memory] recall speaker={} → {} Treffer (top-sim={})",
            speakerId, hits.size, "%.3f".format(hits.first().second),
        )
        val joined = hits.joinToString("; ") { it.first.text.trim() }
        return "[Früher gesagt: $joined]"
    }

    // ── STORE (EpisodicWriter, schreibend) ───────────────────────────────────
    /**
     * Store-Naht ([EpisodicWriter]): NACH der Antwort gerufen. Persistiert den
     * **User-Turn** als Gesprächskontext (der [answer] geht NICHT in den Recall-
     * Embedder — Recall sucht „was hat der Sprecher gesagt"). Delegiert an den
     * Kern [record].
     */
    override fun record(speakerId: String, userText: String, answer: String) =
        record(speakerId, userText)

    /**
     * Bettet einen substanziellen User-Turn ein und persistiert ihn je [speakerId].
     * Gast/leer/ungültig → NICHT gespeichert. Triviale/kurze Turns (Smart-Home-
     * Kurzbefehle „Licht an") werden übersprungen — nur konversationelle Substanz
     * lohnt den Recall. Exakte Text-Duplikate werden nicht doppelt gesammelt.
     */
    fun record(speakerId: String, text: String) {
        if (EntityMemoryAdapter.isGuest(speakerId)) {
            log.debug("[episodic-memory] skip store für Gast/ungültige id '{}'", speakerId)
            return
        }
        val t = text.trim()
        if (t.length < MIN_RECORD_LEN) return
        // Sensitive-Marker-Gate (flag-gated, default OFF ⇒ übersprungen, byte-neutral):
        // Turns mit sensiblen Markern werden NICHT persistiert — sie landen sonst dauerhaft
        // auf Disk und tauchen über den Recall-Block wieder auf. Nur die Kategorie wird
        // geloggt (kein Klartext-Leak); im Zweifel konservativ nicht speichern.
        if (sensitiveFilterEnabled) {
            val category = SensitiveMarker.detect(t)
            if (category != null) {
                log.info("[episodic-memory] Turn NICHT gespeichert — sensitiver Marker ({}) erkannt", category)
                return
            }
        }
        val vec = embedder.embed(t)
        if (vec.isEmpty()) {
            log.warn("[episodic-memory] kein Embedding für '{}' — Turn nicht gespeichert", speakerId)
            return
        }
        synchronized(lock) {
            if (existsText(speakerId, t)) return
            conn.prepareStatement(
                "INSERT INTO episodic_turns (speaker_id, ts, text, embedding) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, speakerId)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, t)
                ps.setString(4, encode(vec))
                ps.executeUpdate()
            }
            pruneCap(speakerId)
        }
        log.info("[episodic-memory] Turn gemerkt für '{}' ({} Zeichen)", speakerId, t.length)
    }

    private fun existsText(speakerId: String, text: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM episodic_turns WHERE speaker_id = ? AND text = ? LIMIT 1").use { ps ->
            ps.setString(1, speakerId)
            ps.setString(2, text)
            ps.executeQuery().use { it.next() }
        }

    /** Hält je Sprecher nur die jüngsten [CAP] Einträge (Datensparsamkeit). */
    private fun pruneCap(speakerId: String) {
        conn.prepareStatement(
            """
            DELETE FROM episodic_turns
            WHERE speaker_id = ?
              AND id NOT IN (
                SELECT id FROM episodic_turns WHERE speaker_id = ? ORDER BY id DESC LIMIT ?
              )
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, speakerId)
            ps.setString(2, speakerId)
            ps.setInt(3, CAP)
            ps.executeUpdate()
        }
    }

    private fun loadEntries(speakerId: String): List<Entry> {
        val out = ArrayList<Entry>()
        conn.prepareStatement(
            "SELECT text, embedding FROM episodic_turns WHERE speaker_id = ? ORDER BY id",
        ).use { ps ->
            ps.setString(1, speakerId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(Entry(rs.getString(1), decode(rs.getString(2))))
            }
        }
        return out
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private class Entry(val text: String, val vec: DoubleArray)

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    private fun encode(vec: DoubleArray): String = vec.joinToString(",")

    private fun decode(s: String): DoubleArray =
        if (s.isBlank()) DoubleArray(0) else s.split(",").map { it.toDouble() }.toDoubleArray()

    companion object {
        const val CAP = 300            // max gespeicherte Episoden pro Sprecher
        const val TOP_K = 2            // wie viele frühere Turns ins Prompt
        const val MIN_SIM = 0.72       // Cosine-Schwelle (embeddinggemma) für „relevant"
        const val MIN_RECORD_LEN = 15  // Smart-Home-Kurzbefehle nicht sammeln

        /** Default-Embed-Sidecar (lokales Ollama). Überschreibbar via Konstruktor/PipelineConfig. */
        const val DEFAULT_EMBED_URL = "http://localhost:11434"

        /** Default-Store-Datei: `~/.hoshi/episodic-memory.db` (persistent über App-Boots). */
        fun defaultDbPath(): String =
            Paths.get(System.getProperty("user.home"), ".hoshi", "episodic-memory.db").toString()

        // ── Privacy-Rand (PrivacyController) — Spiegel von [EntityMemoryAdapter.deleteAllFacts]:
        // kurzlebige Zweit-Connection, NUR Inhalt (`DELETE FROM`), nie Datei-Unlink/Schema-Drop
        // ⇒ eine live laufende Adapter-Instanz überlebt das garantiert (leere Tabelle, kein Reconnect).

        /**
         * Zählt ALLE gespeicherten Episoden (über alle Sprecher). `null` = ehrliches
         * „weiß nicht" (Datei fehlt/nicht lesbar/kein Schema). Legt NIE eine Datei an.
         */
        fun countTurns(dbPath: String = defaultDbPath()): Int? {
            val path = Paths.get(dbPath).toAbsolutePath()
            if (!Files.isRegularFile(path)) return null
            return runCatching {
                DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM episodic_turns").use { rs ->
                            if (rs.next()) rs.getInt(1) else 0
                        }
                    }
                }
            }.getOrNull()
        }

        /**
         * Löscht ALLE Episoden (`DELETE FROM episodic_turns`). Datei/Tabelle fehlt ⇒ 0
         * (nichts angelegt). Rückgabe: Anzahl wirklich gelöschter Zeilen; echte Fehler
         * werfen (der Aufrufer antwortet ehrlich 5xx, nie fake-„gelöscht").
         */
        fun deleteAllTurns(dbPath: String = defaultDbPath()): Int {
            val path = Paths.get(dbPath).toAbsolutePath()
            if (!Files.isRegularFile(path)) return 0
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                return try {
                    c.createStatement().use { st -> st.executeUpdate("DELETE FROM episodic_turns") }
                } catch (e: java.sql.SQLException) {
                    if (e.message?.contains("no such table") == true) 0 else throw e
                }
            }
        }
    }
}

/**
 * Embed-Naht des episodischen Gedächtnisses: Text → Vektor (leer = nicht
 * verfügbar). Injizierbar, damit Tests deterministisch ohne Netz fahren; live
 * bindet [OllamaEpisodicEmbedder] (embeddinggemma).
 */
fun interface EpisodicEmbedder {
    fun embed(text: String): DoubleArray
}

/**
 * Live-Embedder gegen Ollama (:11434, `embeddinggemma:300m`) — **NUR Embeddings,
 * kein Brain-Call**. Reiner JDK-`HttpClient` + jackson (keine Spring-/WebClient-
 * Abhängigkeit im Adapter). `keep_alive=-1` hält das Embed-Modell resident (0.5-
 * Lehre: ein kaltes Embedding liefert leer → Recall fiele aus). Jeder Fehler →
 * leerer Vektor (Memory ist additiv, nie blockierend).
 */
class OllamaEpisodicEmbedder(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "embeddinggemma:300m",
    private val timeout: Duration = Duration.ofSeconds(5),
    private val mapper: ObjectMapper = ObjectMapper(),
) : EpisodicEmbedder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun embed(text: String): DoubleArray {
        return try {
            val body = mapper.writeValueAsString(
                mapOf("model" to model, "prompt" to text, "keep_alive" to -1),
            )
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/embeddings"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[episodic-memory] Ollama-embed HTTP {} — leerer Vektor", resp.statusCode())
                return DoubleArray(0)
            }
            val node = mapper.readTree(resp.body()).path("embedding")
            if (!node.isArray) DoubleArray(0) else DoubleArray(node.size()) { node.get(it).asDouble() }
        } catch (e: Exception) {
            log.warn("[episodic-memory] Ollama-embed fehlgeschlagen: {}", e.message)
            DoubleArray(0)
        }
    }
}
