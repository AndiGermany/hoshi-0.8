package de.hoshi.adapters.memory

import de.hoshi.core.pipeline.EntityContextPort
import de.hoshi.core.pipeline.EntityMemoryWriter
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * **EntityMemoryAdapter** — Multi-User-Gedächtnis (Andis #1 0.8-Wunsch): Hoshi
 * merkt sich Fakten ÜBER DEN SPRECHER über Turns hinweg, keyed by `speakerId`.
 *
 * Erfüllt zwei Nähte aus dem Kern:
 *  - [EntityContextPort.contextBlock] (RECALL, lesend): liefert den Gedächtnis-
 *    Block, den der [de.hoshi.core.pipeline.TurnPromptAssembler] VOR die Antwort
 *    in den System-Prompt schichtet.
 *  - [EntityMemoryWriter.remember] (STORE, schreibend): NACH der Antwort wird
 *    aus dem User-Text **deterministisch/heuristisch** extrahiert und persistiert.
 *
 * **Die heilige Invariante bleibt unberührt:** die Extraktion ist eine reine
 * Regex-Heuristik — KEIN zweiter Brain-Call pro Turn (0.5 nutzte dafür das LLM;
 * hier bewusst NICHT, um „max 1 Brain-Call/Turn" + Never-Silent nicht zu brechen).
 *
 * **Mandanten-Trennung:** jede Zeile trägt die `speaker_id`; Recall liest NUR die
 * Fakten des fragenden Sprechers. Ein **Gast** (leer/`unknown`/`gast`/ungültig)
 * wird NIE persistiert und bekommt NIE einen Block — kein Memory-Leak über
 * Personen hinweg (Privacy/Tom-Veto, 0.8-Vision).
 *
 * **Persistenz:** simpler sqlite-Store (JDBC), Datei standardmäßig unter
 * `~/.hoshi/entity-memory.db`. Tabelle `entity_facts(speaker_id, fact_key,
 * fact_value, updated_at)` mit PK `(speaker_id, fact_key)` (Upsert = jüngster
 * Fakt gewinnt).
 */
class EntityMemoryAdapter(
    dbPath: String = defaultDbPath(),
) : EntityContextPort, EntityMemoryWriter, AutoCloseable {

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
                CREATE TABLE IF NOT EXISTS entity_facts (
                    speaker_id TEXT NOT NULL,
                    fact_key   TEXT NOT NULL,
                    fact_value TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (speaker_id, fact_key)
                )
                """.trimIndent(),
            )
        }
        log.info("[entity-memory] sqlite-Store bereit: {}", path)
    }

    // ── RECALL (EntityContextPort, lesend) ───────────────────────────────────
    override fun contextBlock(speakerId: String): String? {
        if (isGuest(speakerId)) return null
        val facts = synchronized(lock) { loadFacts(speakerId) }
        if (facts.isEmpty()) return null
        val lines = facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" }
        return buildString {
            append("[Gedächtnis — was du über den aktuellen Sprecher aus früheren Gesprächen weißt:\n")
            append(lines)
            append("\nWenn er nach einer dieser Angaben fragt, antworte damit.]")
        }
    }

    // ── STORE (EntityMemoryWriter, schreibend) ───────────────────────────────
    override fun remember(speakerId: String, turnText: String, answer: String) {
        // Gast/leer/ungültig → NICHT persistieren (kein fremder Kontext im Store).
        if (isGuest(speakerId)) {
            log.debug("[entity-memory] skip store für Gast/ungültige id '{}'", speakerId)
            return
        }
        val facts = extractFacts(turnText)
        if (facts.isEmpty()) return
        synchronized(lock) {
            val now = Instant.now().toString()
            conn.prepareStatement(
                """
                INSERT INTO entity_facts (speaker_id, fact_key, fact_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(speaker_id, fact_key)
                DO UPDATE SET fact_value = excluded.fact_value, updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { ps ->
                for ((k, v) in facts) {
                    ps.setString(1, speakerId)
                    ps.setString(2, k)
                    ps.setString(3, v)
                    ps.setString(4, now)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        log.info("[entity-memory] {} Fakt(en) gemerkt für '{}': {}", facts.size, speakerId, facts)
    }

    private fun loadFacts(speakerId: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        conn.prepareStatement(
            "SELECT fact_key, fact_value FROM entity_facts WHERE speaker_id = ? ORDER BY updated_at",
        ).use { ps ->
            ps.setString(1, speakerId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out[rs.getString(1)] = rs.getString(2)
            }
        }
        return out
    }

    override fun close() {
        runCatching { conn.close() }
    }

    companion object {
        /** Default-Store-Datei: `~/.hoshi/entity-memory.db` (persistent über App-Boots). */
        fun defaultDbPath(): String =
            Paths.get(System.getProperty("user.home"), ".hoshi", "entity-memory.db").toString()

        // ── Privacy-Rand (PrivacyController, „Charter-Lösch-API") ────────────────
        //
        // Beide Helfer öffnen eine KURZLEBIGE Zweit-Connection gegen dieselbe Datei.
        // Das ist bewusst SICHER für eine live laufende Adapter-Instanz: es wird nur
        // der INHALT gelöscht (`DELETE FROM`), NIE die Datei unlinkt oder das Schema
        // gedroppt — sqlite koordiniert die zwei Connections per File-Lock, die
        // bestehende Adapter-Connection bleibt gültig und sieht danach schlicht eine
        // leere Tabelle (kein Reconnect nötig, keine Korruption).

        /**
         * Zählt ALLE gespeicherten Fakten (über alle Sprecher). `null` = ehrliches
         * „weiß nicht" (Datei fehlt, nicht lesbar oder kein Fakten-Schema). Legt
         * NIE eine Datei an — ein Count erschafft keinen Store.
         */
        fun countFacts(dbPath: String = defaultDbPath()): Int? {
            val path = Paths.get(dbPath).toAbsolutePath()
            if (!Files.isRegularFile(path)) return null
            return runCatching {
                DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM entity_facts").use { rs ->
                            if (rs.next()) rs.getInt(1) else 0
                        }
                    }
                }
            }.getOrNull()
        }

        /**
         * Löscht ALLE Fakten (`DELETE FROM entity_facts`) — die versprochene
         * Lösch-API. Datei fehlt / Tabelle fehlt ⇒ 0 (nichts zu löschen, nichts
         * wird angelegt). Rückgabe: Anzahl wirklich gelöschter Zeilen. Wirft bei
         * echten I/O-/Lock-Fehlern (der Aufrufer antwortet dann ehrlich 5xx —
         * nie ein fake-„gelöscht"). Hinweis: die Dateigröße kann gleich bleiben
         * (sqlite recycelt Seiten) — die DATEN sind weg, bewiesen per COUNT.
         */
        fun deleteAllFacts(dbPath: String = defaultDbPath()): Int {
            val path = Paths.get(dbPath).toAbsolutePath()
            if (!Files.isRegularFile(path)) return 0
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                return try {
                    c.createStatement().use { st -> st.executeUpdate("DELETE FROM entity_facts") }
                } catch (e: java.sql.SQLException) {
                    if (e.message?.contains("no such table") == true) 0 else throw e
                }
            }
        }

        /** Kanonische Gast-id (nicht-identifizierter Sprecher) — wird nie gespeichert. */
        const val GUEST = "gast"

        // Whitelist (1:1-Härtung aus 0.5 SpeakerId): erlaubt nur [A-Za-z0-9_-], 1..64.
        // Eine id, die nicht matched, kann keinen echten Sprecher bezeichnen → Gast.
        private val VALID_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** true für leer/`unknown`/`gast` ODER eine id, die nicht der Whitelist entspricht. */
        fun isGuest(id: String): Boolean =
            id.isBlank() || id == "unknown" || id == GUEST || !VALID_ID.matches(id)

        // ── Deterministische Fakt-Extraktion (heuristisch, KEIN Brain-Call) ──────
        //
        // Speicherform (entityKey, attribut→wert): der `fact_key` trägt die ENTITÄT
        // (z.B. "hund", "frau") bzw. "name" beim Selbst-Namen; der `fact_value` den
        // Wert (z.B. "Bello"). Der Recall-Block rendert „- hund: Bello" → damit wird
        // „Wie heißt mein Hund?" aus entity_facts beantwortbar (nicht nur episodisch).
        //
        // KONSERVATIV: NUR klare Besitz-/Namens-Muster (DE+EN). Fragen (`…?`) tragen
        // nie Fakten. Bloßes „X ist Y" OHNE Possessiv wird bewusst NICHT gefangen
        // (Über-Fang vermeiden — nur Possessiv/Naming, nie generische Aussagen).

        // DE — Possessiv + „heißt" (mehrwortige Sache erlaubt): „mein <X> heißt <Name>".
        private val P_DE_NAME = Regex(
            """\b(?:mein|meine|unser|unsere|dein|deine)\s+([\p{L}][\p{L}\s-]{1,38}?)\s+heißt\s+([^.,;!?]{1,60})""",
            RegexOption.IGNORE_CASE,
        )
        // DE — „ich heiße <Name>" → Selbst-Name.
        private val P_DE_ICH_HEISSE = Regex(
            """\bich\s+heiße\s+([^.,;!?]{1,40})""", RegexOption.IGNORE_CASE,
        )
        // DE — Possessiv + „ist/sind/wäre" (EIN-Wort-Sache, konservativ): „mein <X> ist <Wert>".
        private val P_DE_POSS_IST = Regex(
            """\b(?:mein|meine|unser|unsere|dein|deine)\s+([\p{L}][\p{L}-]{1,38})\s+(?:ist|sind|wäre)\s+([^.,;!?]{1,60})""",
            RegexOption.IGNORE_CASE,
        )
        // DE — „ich mag/liebe/bevorzuge <Wert>" (Bestand) → mag.
        private val P_DE_MAG = Regex(
            """\bich\s+(?:mag|liebe|bevorzuge)\s+([^.,;!?]{1,60})""", RegexOption.IGNORE_CASE,
        )

        // EN — „my <X>'s name is <Name>" → (X → Name). Apostroph optional ('/'/keiner).
        private val P_EN_POSS_NAME = Regex(
            """\bmy\s+([\p{L}][\p{L}-]{1,30})['’]?s\s+name\s+is\s+([^.,;!?]{1,60})""",
            RegexOption.IGNORE_CASE,
        )
        // EN — „my <X> is <Wert>" (Possessiv) → (X → Wert). „my name is …" ⇒ key „name".
        private val P_EN_MY_IS = Regex(
            """\bmy\s+([\p{L}][\p{L}-]{1,30})\s+is\s+([^.,;!?]{1,60})""", RegexOption.IGNORE_CASE,
        )
        // EN — „I am <Name>" (Name MUSS groß, sonst Über-Fang „I am tired"). (?i:) hält
        // nur das Schlüsselwort case-insensitiv; der Name bleibt groß-erzwungen.
        private val P_EN_I_AM = Regex("""(?i:\bi\s+am)\s+([\p{Lu}][\p{L}-]{1,30})""")
        // EN — „call me <Name>" (Name groß, sonst Über-Fang „call me later/back").
        private val P_EN_CALL_ME = Regex("""(?i:\bcall\s+me)\s+([\p{Lu}][\p{L}-]{1,30})""")

        /**
         * Heuristische Extraktion aus dem **User-Text** eines Turns. Fragen (`…?`)
         * tragen keine Fakten → leer. Reihenfolge: Possessiv-Sach-Muster zuerst,
         * dann Namens-Muster (überschreiben gleichlautende Sach-keys, „Name gewinnt"),
         * dann Selbst-Name (`name`) und `mag`.
         */
        internal fun extractFacts(text: String): List<Pair<String, String>> {
            val t = text.trim()
            if (t.isEmpty() || t.endsWith("?")) return emptyList()
            val facts = LinkedHashMap<String, String>()

            // (1) Possessiv-Sach-Fakten (mehrfach pro Satz möglich).
            P_DE_POSS_IST.findAll(t).forEach { put(facts, it.groupValues[1], it.groupValues[2]) }
            P_EN_MY_IS.findAll(t).forEach { put(facts, it.groupValues[1], it.groupValues[2]) }
            // (2) Namens-Fakten überschreiben gleichlautende Sach-keys (Name gewinnt).
            P_DE_NAME.findAll(t).forEach { put(facts, it.groupValues[1], it.groupValues[2]) }
            P_EN_POSS_NAME.findAll(t).forEach { put(facts, it.groupValues[1], it.groupValues[2]) }
            // (3) Selbst-Name → key „name".
            P_DE_ICH_HEISSE.find(t)?.let { put(facts, "name", it.groupValues[1]) }
            P_EN_I_AM.find(t)?.let { put(facts, "name", it.groupValues[1]) }
            P_EN_CALL_ME.find(t)?.let { put(facts, "name", it.groupValues[1]) }
            // (4) Vorliebe.
            P_DE_MAG.find(t)?.let { put(facts, "mag", it.groupValues[1]) }

            return facts.entries.map { it.key to it.value }.filter { it.second.isNotBlank() }
        }

        /** Normalisiert key (lowercase/getrimmt) + value ([cleanup]) und schreibt nur, wenn beide nicht-leer. */
        private fun put(into: LinkedHashMap<String, String>, rawKey: String, rawValue: String) {
            val key = rawKey.lowercase().trim().trim('-').trim()
            val value = cleanup(rawValue)
            if (key.isNotBlank() && value.isNotBlank()) into[key] = value
        }

        private fun cleanup(raw: String): String =
            raw.trim().trimEnd('.', '!', '?', ',', ';', ':', ' ').trim()
    }
}
