package de.hoshi.web

import de.hoshi.adapters.memory.EntityMemoryAdapter
import de.hoshi.adapters.memory.EpisodicEmbedder
import de.hoshi.adapters.memory.EpisodicMemoryAdapter
import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * **PrivacyEndpointTest** — die BE-Verträge des Vertrauens-Rands am GEBOOTETEN Context:
 *
 *  (a) ALLE vier Privacy-Endpoints liegen AUTOMATISCH hinter der [PerimeterWebFilter]-
 *      Wand (401 ohne Token — kein eigener Auth-Code, exakt das [DiaryEndpointTest]-Muster),
 *  (b) `GET /summary` liefert das ehrliche Wire-Format: voice/sanitize aus der Boot-Config,
 *      Stores mit exists+Größe+ECHTEM Count (fehlende Datei ⇒ `entries=null`, nie erfunden),
 *  (c) die Lösch-API löscht WIRKLICH (bewiesene Zahl) — und eine LIVE offene
 *      Adapter-Instanz überlebt den Wipe (kein Datei-Unlink, nur `DELETE FROM`),
 *  (d) Löschen ohne Datei ⇒ `deleted=0` und legt NIE einen Store an,
 *  (e) Diary-Löschung entfernt NUR `turn-diary-*.jsonl` (fremde Dateien bleiben).
 *
 * MOCK-Env (kein echter Socket) ⇒ `isLoopback=false` ⇒ die Token-Wand greift wirklich.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "hoshi.perimeter.enabled=true",
        "hoshi.perimeter.token=test-secret-token",
        // HOSHI_MEMORY/EPISODIC/DIARY bleiben Default (false) — der Privacy-Rand liest
        // die Dateien direkt; genau so ist er auch bei ausgeschalteten Flags ehrlich.
    ],
)
@AutoConfigureWebTestClient
class PrivacyEndpointTest(
    @Autowired val client: WebTestClient,
) {

    companion object {
        @JvmStatic
        val root: Path = Files.createTempDirectory("privacy-endpoint-test")

        @JvmStatic val memoryDb: Path = root.resolve("entity-memory.db")

        @JvmStatic val episodicDb: Path = root.resolve("episodic-memory.db")

        @JvmStatic val diaryDir: Path = root.resolve("diary")

        @JvmStatic val lookupsFile: Path = root.resolve("lookups").resolve("nachgeschlagen.jsonl")

        @JvmStatic
        @DynamicPropertySource
        fun paths(registry: DynamicPropertyRegistry) {
            registry.add("hoshi.memory.db-path") { memoryDb.toString() }
            registry.add("hoshi.memory.episodic-db-path") { episodicDb.toString() }
            registry.add("hoshi.diary.dir") { diaryDir.toString() }
            registry.add("hoshi.escalation.lookup.path") { lookupsFile.toString() }
        }
    }

    @BeforeEach
    fun cleanStores() {
        Files.deleteIfExists(memoryDb)
        Files.deleteIfExists(episodicDb)
        Files.deleteIfExists(lookupsFile)
        if (Files.isDirectory(diaryDir)) {
            Files.list(diaryDir).use { files -> files.forEach { Files.deleteIfExists(it) } }
        }
    }

    // Exakt das DiaryEndpointTest-Muster: authentisierte Request-Builder je Verb/Pfad.
    private fun getSummary() = client.get().uri("/api/v1/privacy/summary")
        .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")

    private fun deleteTarget(target: String) = client.delete().uri("/api/v1/privacy/$target")
        .header(HttpHeaders.AUTHORIZATION, "Bearer test-secret-token")

    // ── (a) Die Wand ─────────────────────────────────────────────────────────

    @Test
    fun `alle Privacy-Endpoints ohne Token - 401 (automatisch hinter der Wand)`() {
        client.get().uri("/api/v1/privacy/summary").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/v1/privacy/memory").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/v1/privacy/episodic").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/v1/privacy/diary").exchange().expectStatus().isUnauthorized
        client.delete().uri("/api/v1/privacy/lookups").exchange().expectStatus().isUnauthorized
    }

    // ── (b) Summary-Format, ehrlich ──────────────────────────────────────────

    @Test
    fun `summary ohne Stores - ehrliches Format, entries null statt erfunden`() {
        getSummary().exchange()
            .expectStatus().isOk
            // voice/sanitize: Boot-Config (kein HOSHI_TTS gesetzt ⇒ voxtral, lokal; Sanitize OFF).
            .expectBody()
            .jsonPath("$.voice.engine").isEqualTo("voxtral")
            .jsonPath("$.voice.cloud").isEqualTo(false)
            .jsonPath("$.sanitize.enabled").isEqualTo(false)
            // Stores: Datei fehlt ⇒ exists=false, entries=null (ehrlich „weiß nicht").
            .jsonPath("$.memory.enabled").isEqualTo(false)
            .jsonPath("$.memory.exists").isEqualTo(false)
            .jsonPath("$.memory.entries").value(nullValue())
            .jsonPath("$.episodic.exists").isEqualTo(false)
            .jsonPath("$.episodic.entries").value(nullValue())
            .jsonPath("$.diary.enabled").isEqualTo(false)
            .jsonPath("$.diary.days").isEqualTo(0)
            // Extended Think S3: Decke zu (Default) ⇒ enabled=false, Datei fehlt ⇒ entries=null.
            .jsonPath("$.lookups.enabled").isEqualTo(false)
            .jsonPath("$.lookups.exists").isEqualTo(false)
            .jsonPath("$.lookups.entries").value(nullValue())
    }

    @Test
    fun `summary mit Daten - echter COUNT und Diary-Tage`() {
        EntityMemoryAdapter(memoryDb.toString()).use { it.remember("andi", "Mein Hund heißt Bello", "ok") }
        Files.createDirectories(diaryDir)
        Files.writeString(diaryDir.resolve("turn-diary-2026-06-30.jsonl"), "{\"ts\":\"x\"}\n")
        Files.writeString(diaryDir.resolve("turn-diary-2026-07-01.jsonl"), "{\"ts\":\"y\"}\n")

        getSummary().exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.memory.exists").isEqualTo(true)
            .jsonPath("$.memory.entries").isEqualTo(1)
            .jsonPath("$.diary.days").isEqualTo(2)
    }

    @Test
    fun `summary zaehlt Nachgeschlagen-Zeilen (append-only ⇒ 1 Zeile = 1 Notiz)`() {
        Files.createDirectories(lookupsFile.parent)
        Files.writeString(lookupsFile, "{\"queryNorm\":\"a\"}\n{\"queryNorm\":\"b\"}\n")

        getSummary().exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.lookups.exists").isEqualTo(true)
            .jsonPath("$.lookups.entries").isEqualTo(2)
    }

    // ── (c) Echte Löschung + Adapter-Überleben ───────────────────────────────

    @Test
    fun `DELETE memory loescht wirklich - und die LIVE Adapter-Instanz ueberlebt`() {
        EntityMemoryAdapter(memoryDb.toString()).use { adapter ->
            adapter.remember("andi", "Mein Hund heißt Bello", "ok")

            deleteTarget("memory").exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.target").isEqualTo("memory")
                .jsonPath("$.deleted").isEqualTo(1)

            // Wirklich weg — und der offene Adapter arbeitet weiter (kein Korrupt, kein Reconnect).
            assertNull(adapter.contextBlock("andi"), "nach DELETE kein Gedächtnis-Block mehr")
            adapter.remember("andi", "Meine Katze heißt Mia", "ok")
            val block = adapter.contextBlock("andi")
            assertNotNull(block, "Adapter muss den externen Wipe überleben")
            assertTrue(block!!.contains("Mia"))
        }
    }

    @Test
    fun `DELETE episodic loescht wirklich (Fake-Embedder, kein Netz)`() {
        val embedder = EpisodicEmbedder { doubleArrayOf(1.0, 0.5, 0.25) }
        EpisodicMemoryAdapter(dbPath = episodicDb.toString(), embedder = embedder).use { adapter ->
            adapter.record("andi", "Ich mag Sternschnuppen über dem Balkon")

            deleteTarget("episodic").exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.target").isEqualTo("episodic")
                .jsonPath("$.deleted").isEqualTo(1)

            assertEquals(0, EpisodicMemoryAdapter.countTurns(episodicDb.toString()))
            // Überleben: dieselbe Instanz speichert weiter (recallNow ist modul-intern —
            // der öffentliche countTurns beweist das Überleben genauso hart).
            adapter.record("andi", "Ich mag Sternschnuppen über dem Balkon")
            assertEquals(1, EpisodicMemoryAdapter.countTurns(episodicDb.toString()))
        }
    }

    // ── (d) Löschen ohne Datei: 0, und NICHTS wird angelegt ─────────────────

    @Test
    fun `DELETE ohne Store - deleted 0 und es entsteht KEINE Datei`() {
        deleteTarget("memory").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.deleted").isEqualTo(0)
        deleteTarget("episodic").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.deleted").isEqualTo(0)

        assertFalse(Files.exists(memoryDb), "ein Wipe darf keinen Store anlegen")
        assertFalse(Files.exists(episodicDb), "ein Wipe darf keinen Store anlegen")
    }

    @Test
    fun `DELETE lookups ohne Datei - deleted 0, keine Datei entsteht`() {
        deleteTarget("lookups").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.target").isEqualTo("lookups")
            .jsonPath("$.deleted").isEqualTo(0)

        assertFalse(Files.exists(lookupsFile), "ein Wipe darf keinen Store anlegen")
    }

    // ── (e) Diary: nur die Tages-Dateien ─────────────────────────────────────

    @Test
    fun `DELETE diary entfernt NUR turn-diary-Dateien, fremde bleiben`() {
        Files.createDirectories(diaryDir)
        Files.writeString(diaryDir.resolve("turn-diary-2026-06-30.jsonl"), "{}\n")
        Files.writeString(diaryDir.resolve("turn-diary-2026-07-01.jsonl"), "{}\n")
        val foreign = diaryDir.resolve("notes.txt")
        Files.writeString(foreign, "bleibt")

        deleteTarget("diary").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.target").isEqualTo("diary")
            .jsonPath("$.deleted").isEqualTo(2)

        assertTrue(Files.exists(foreign), "fremde Dateien im Diary-Verzeichnis bleiben unangetastet")
        getSummary().exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.diary.days").isEqualTo(0)
    }

    // ── (f) Extended Think S3: die Nachgeschlagen-Notizen sind löschbar (Tom-Pflicht) ──

    @Test
    fun `DELETE lookups loescht die nachgeschlagen-jsonl wirklich`() {
        Files.createDirectories(lookupsFile.parent)
        Files.writeString(lookupsFile, "{\"queryNorm\":\"wie hoch ist der eiffelturm\",\"answer\":\"330 Meter\"}\n")

        deleteTarget("lookups").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.target").isEqualTo("lookups")
            .jsonPath("$.deleted").isEqualTo(1)

        assertFalse(Files.exists(lookupsFile), "die Notiz-Datei muss wirklich weg sein — Klartext-Fragen dürfen nicht liegen bleiben")
        getSummary().exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.lookups.exists").isEqualTo(false)
    }

    // ── (g) Voice: EINE Laufzeit-Quelle mit SidecarHealthService (e404804-Beifang-Fix) ──

    /**
     * Beweist die KDoc-Behauptung „deckungsgleich mit SidecarHealthService" DIREKT:
     * derselbe [JsonFileTtsEngineStore] (Runtime-Switch auf "say", Boot-Default wäre
     * `openai`/Cloud) gefüttert in einen eigens konstruierten [PrivacyController] UND
     * einen eigens konstruierten [SidecarHealthService] — beide müssen exakt dasselbe
     * `voice`-Ergebnis liefern, nie eine zweite, driftende Ableitung. Bewusst OHNE
     * Spring-Kontext (kein Sidecar-Netz nötig für [SidecarPort]/[BrainHealthSource]).
     */
    @Test
    fun `voice folgt dem Runtime-Switch - kongruent mit SidecarHealthService, nicht dem Boot-Fossil`() {
        val isolated = Files.createTempDirectory("privacy-voice-congruence")
        val ttsStore = JsonFileTtsEngineStore(isolated.resolve("tts-engine.json")).apply { setEngineId("say") }

        val controller = PrivacyController(
            ttsImpl = "openai", // Boot-Default wäre Cloud — der Runtime-Switch MUSS gewinnen
            sanitizeEnabled = false,
            memoryEnabled = false,
            memoryDbPath = isolated.resolve("entity-memory.db").toString(),
            episodicEnabled = false,
            episodicDbPath = isolated.resolve("episodic-memory.db").toString(),
            diaryEnabled = false,
            diaryDir = isolated.resolve("diary").toString(),
            extendedThinkEnabled = false,
            lookupNotePath = isolated.resolve("nachgeschlagen.jsonl").toString(),
            ttsEngineStore = ttsStore,
        )
        val sidecarService = SidecarHealthService(
            enabled = true,
            brainUrl = "http://localhost:8041",
            sttUrl = "http://localhost:9001",
            ttsUrl = "http://localhost:8042",
            bridgeUrl = "http://localhost:8035",
            speakerUrl = "http://localhost:9002",
            failureThreshold = 1,
            ttsImpl = "openai", // derselbe Boot-Default wie oben
            probe = SidecarPort { SidecarHealth.ok("status=ok (fake)") },
            brainHealth = BrainHealthSource { null },
            ttsEngineStore = ttsStore,
        )
        sidecarService.refresh()

        val privacyVoice = controller.buildSummary().voice
        val opsVoice = (sidecarService.current() as OpsStatus).voice

        assertEquals("say", privacyVoice.engine, "der Runtime-Switch muss die Privacy-Zeile erreichen")
        assertFalse(privacyVoice.cloud, "say ist lokal")
        assertEquals(opsVoice.engine, privacyVoice.engine, "PrivacyController und SidecarHealthService müssen DECKUNGSGLEICH sein")
        assertEquals(opsVoice.cloud, privacyVoice.cloud)
    }
}
