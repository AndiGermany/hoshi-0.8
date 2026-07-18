package de.hoshi.web

import de.hoshi.adapters.memory.EntityMemoryAdapter
import de.hoshi.adapters.memory.EpisodicMemoryAdapter
import de.hoshi.adapters.supervision.JsonlLookupNoteAdapter
import de.hoshi.adapters.supervision.JsonlTurnTraceAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Path

/**
 * **PrivacyController** — der Vertrauens-Rand: EHRLICHE Privatsphäre-Übersicht +
 * die Charter-versprochene Lösch-API. Alles unter `/api/v1` ⇒ AUTOMATISCH hinter
 * der [PerimeterWebFilter]-Wand (ohne/falscher Token ⇒ 401, exakt das
 * [DiaryController]-Muster; bewiesen im PrivacyEndpointTest).
 *
 * `GET /api/v1/privacy/summary` — nur, was BILLIG UND ECHT lesbar ist, nichts behauptet:
 *  - `voice` — aktive TTS-Engine + Cloud-Flag, DECKUNGSGLEICH mit [SidecarHealthService]
 *    (`VoiceStatus`/`OpsStatus.voice`): beide rufen [TtsEngineIds.effectiveEngineId] —
 *    der GEWÄHLTE Laufzeit-Wunsch aus [JsonFileTtsEngineStore] gewinnt, NUR ohne je
 *    einen Runtime-Switch (`PUT /api/v1/settings/tts`) fällt es auf die Boot-Env
 *    `HOSHI_TTS` zurück (Andi-Befund 2026-07-20: vor diesem Fix blieb diese Zeile nach
 *    einem Wechsel auf eine andere Engine beim Boot-Stand stehen — bewiesen kongruent
 *    in PrivacyEndpointTest).
 *  - `sanitize` — ist die Cloud-Egress-Maskierung ([NeverSpeakTtsSanitizer]) aktiv?
 *    Dieselbe Env `HOSHI_TTS_SANITIZE_ENABLED`, mit der [PipelineConfig.ttsPort] den
 *    Sanitizer verdrahtet.
 *  - `memory`/`episodic` — sqlite-Store-Dateien: exists + Größe + ECHTER `COUNT(*)`
 *    über eine kurzlebige Lese-Connection ([EntityMemoryAdapter.countFacts]/
 *    [EpisodicMemoryAdapter.countTurns]). Count nicht möglich ⇒ `entries=null`
 *    (ehrlich „weiß nicht", nie eine erfundene Zahl). Pfad-Auflösung EXAKT wie die
 *    Beans in [PipelineConfig] (Property leer ⇒ Adapter-Default `~/.hoshi/…`).
 *  - `diary` — Anzahl der Tages-Dateien (`turn-diary-*.jsonl`); das Diary trägt
 *    by design KEINE Gesprächs-Inhalte (siehe [DiaryController]). Verzeichnis-
 *    Auflösung gespiegelt von [DiaryController.resolveDirectory].
 *  - `lookups` (Extended Think S3) — die EINE `nachgeschlagen.jsonl`: exists +
 *    Größe + Zeilen-Anzahl (best-effort, kaputte/fehlende Datei ⇒ `entries=null`).
 *    **Tom: erstmals User-FRAGEN im Klartext auf Platte** ([de.hoshi.core.port.LookupNote.queryNorm])
 *    — darum die Pflicht-Löschung unten, VOR dem ersten produktiven Füllen gebaut.
 *    Pfad-Auflösung DIESELBE wie [JsonlLookupNoteAdapter.resolveDefaultPath]
 *    (`hoshi.escalation.lookup.path`/`HOSHI_ESCALATION_LOOKUP_PATH`) — dieselbe
 *    Property, die die künftige `LookupNoteConfig`/`PipelineConfig`-Wiring liest.
 *
 * `DELETE /api/v1/privacy/{memory|episodic|diary|lookups}` — echte Löschung, SICHER gebaut:
 *  - memory/episodic: `DELETE FROM …` über eine kurzlebige Zweit-Connection — eine
 *    live verdrahtete Adapter-Instanz überlebt das garantiert (kein Datei-Unlink,
 *    kein Schema-Drop; bewiesen in MemoryPrivacyWipeTest). Datei fehlt ⇒ `deleted=0`,
 *    und es wird NIE ein Store angelegt.
 *  - diary: löscht die `turn-diary-*.jsonl`-Tages-Dateien (der [JsonlTurnTraceAdapter]
 *    schreibt append-per-write mit `CREATE` — die nächste Zeile legt die Datei sauber
 *    neu an; andere Dateien im Verzeichnis bleiben unangetastet).
 *  - lookups: löscht die EINE `nachgeschlagen.jsonl` (Datei-Unlink reicht — der
 *    [JsonlLookupNoteAdapter] schreibt mit `CREATE` und legt sie beim nächsten
 *    Nachschlag sauber neu an; eine LIVE offene Adapter-Instanz überlebt, weil sie
 *    NIE einen File-Handle hält, nur bei `record()` kurz öffnet/schließt).
 *  - Fehler ⇒ ehrlich 500 (nie ein fake-„gelöscht"). Antwort bei Erfolg:
 *    `{"target":"…","deleted":N}` — N ist die BEWIESENE Zahl (Zeilen/Dateien).
 *
 * Blocking-Hygiene: Datei-/sqlite-I/O via [Schedulers.boundedElastic], nie auf dem
 * Reactor-Netty-Event-Loop (dieselbe P0-Lehre wie [DiaryController]).
 */
@RestController
class PrivacyController(
    // Boot-Default der TTS-Engine — dieselbe Env wie SidecarHealthService/PipelineConfig.
    // Gilt NUR, solange NIE per PUT /settings/tts umgeschaltet wurde (s. [ttsEngineStore]
    // unten, [buildSummary] ruft dieselbe [TtsEngineIds.effectiveEngineId]-Naht).
    @Value("\${HOSHI_TTS:}") private val ttsImpl: String,
    @Value("\${HOSHI_TTS_SANITIZE_ENABLED:false}") private val sanitizeEnabled: Boolean,
    @Value("\${HOSHI_MEMORY_ENABLED:false}") private val memoryEnabled: Boolean,
    @Value("\${hoshi.memory.db-path:}") private val memoryDbPath: String,
    @Value("\${HOSHI_EPISODIC_ENABLED:false}") private val episodicEnabled: Boolean,
    @Value("\${hoshi.memory.episodic-db-path:}") private val episodicDbPath: String,
    @Value("\${HOSHI_TURN_DIARY_ENABLED:false}") private val diaryEnabled: Boolean,
    @Value("\${hoshi.diary.dir:\${HOSHI_TURN_DIARY_DIR:}}") private val diaryDir: String,
    // Extended Think S3 (Nachgeschlagen-Store) — DIESELBE Property wie die
    // JsonlLookupNoteAdapter-Wiring (LookupNoteConfig/PipelineConfig).
    @Value("\${HOSHI_EXTENDED_THINK_ENABLED:false}") private val extendedThinkEnabled: Boolean,
    @Value("\${hoshi.escalation.lookup.path:\${HOSHI_ESCALATION_LOOKUP_PATH:}}") private val lookupNotePath: String,
    // Additiv: die GEWÄHLTE TTS-Engine aus `PUT /settings/tts`
    // ([TtsSettingsController]/[JsonFileTtsEngineStore]) — DIESELBE Store-Instanz, die
    // auch [SidecarHealthService] injiziert bekommt (EIN Spring-Bean, [TtsRuntimeConfig]),
    // NIE eine zweite, driftende Cloud/Lokal-Ableitung. `null` NUR in älteren Tests, die
    // den Store nicht mitgeben.
    private val ttsEngineStore: JsonFileTtsEngineStore? = null,
) {

    private val log = LoggerFactory.getLogger(PrivacyController::class.java)

    // ── Übersicht ────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/privacy/summary")
    fun summary(): Mono<PrivacySummary> =
        Mono.fromCallable { buildSummary() }.subscribeOn(Schedulers.boundedElastic())

    internal fun buildSummary(): PrivacySummary = PrivacySummary(
        voice = currentVoice(),
        sanitize = PrivacySanitize(enabled = sanitizeEnabled),
        memory = storeInfo(memoryEnabled, memoryPath()) { EntityMemoryAdapter.countFacts(memoryPath().toString()) },
        episodic = storeInfo(episodicEnabled, episodicPath()) { EpisodicMemoryAdapter.countTurns(episodicPath().toString()) },
        diary = PrivacyDiaryInfo(
            enabled = diaryEnabled,
            dir = resolveDiaryDir().toAbsolutePath().toString(),
            days = diaryFiles().size,
        ),
        lookups = storeInfo(extendedThinkEnabled, lookupsPath()) { lookupLineCount() },
    )

    /**
     * Aktive TTS-Engine + Cloud-Flag — RUFT DIESELBE Naht wie
     * [SidecarHealthService.currentVoice] ([TtsEngineIds.effectiveEngineId]): der
     * GEWÄHLTE Laufzeit-Wunsch aus [ttsEngineStore] gewinnt, NUR ohne je einen
     * Runtime-Switch fällt es auf den Boot-Default ([ttsImpl]) zurück. `cloud=true`
     * NUR bei `engine="openai"` (der einzige TTS-Pfad, der die Box verlässt).
     */
    private fun currentVoice(): PrivacyVoice {
        val effective = TtsEngineIds.effectiveEngineId(ttsEngineStore, ttsImpl)
        return PrivacyVoice(engine = effective, cloud = effective == TtsEngineIds.OPENAI)
    }

    /** Eine ehrliche Store-Zeile: exists + Größe + echter COUNT (oder `null` = weiß nicht). */
    private fun storeInfo(enabled: Boolean, path: Path, count: () -> Int?): PrivacyStoreInfo {
        val exists = Files.isRegularFile(path)
        return PrivacyStoreInfo(
            enabled = enabled,
            path = path.toAbsolutePath().toString(),
            exists = exists,
            sizeBytes = if (exists) runCatching { Files.size(path) }.getOrDefault(0L) else 0L,
            entries = if (exists) count() else null,
        )
    }

    // ── Lösch-API ────────────────────────────────────────────────────────────

    @DeleteMapping("/api/v1/privacy/memory")
    fun deleteMemory(): Mono<ResponseEntity<Any>> =
        deleteOp("memory") { EntityMemoryAdapter.deleteAllFacts(memoryPath().toString()) }

    @DeleteMapping("/api/v1/privacy/episodic")
    fun deleteEpisodic(): Mono<ResponseEntity<Any>> =
        deleteOp("episodic") { EpisodicMemoryAdapter.deleteAllTurns(episodicPath().toString()) }

    /** Löscht die Diary-Tages-Dateien (NUR `turn-diary-*.jsonl` — sonst nichts im Verzeichnis). */
    @DeleteMapping("/api/v1/privacy/diary")
    fun deleteDiary(): Mono<ResponseEntity<Any>> =
        deleteOp("diary") { diaryFiles().count { runCatching { Files.deleteIfExists(it) }.getOrDefault(false) } }

    /**
     * **Extended Think S3 — Pflicht-Löschung** (Tom: was im Klartext liegt, muss
     * löschbar sein, BEVOR es sich füllt). Löscht die EINE `nachgeschlagen.jsonl`
     * (Datei-Unlink reicht, kein `DELETE FROM` nötig — kein sqlite-Store).
     */
    @DeleteMapping("/api/v1/privacy/lookups")
    fun deleteLookups(): Mono<ResponseEntity<Any>> =
        deleteOp("lookups") { if (Files.deleteIfExists(lookupsPath())) 1 else 0 }

    /**
     * Gemeinsamer Lösch-Rahmen: Erfolg ⇒ 200 + bewiesene Zahl; Fehler ⇒ ehrlich 500
     * (nie fake-„gelöscht"; Grund nur ins Log, nie Pfad-/Fehler-Details über den Draht).
     */
    private fun deleteOp(target: String, op: () -> Int): Mono<ResponseEntity<Any>> =
        Mono.fromCallable {
            runCatching(op).fold(
                onSuccess = { n ->
                    log.info("[privacy] {} gelöscht: {} Einheit(en)", target, n)
                    ResponseEntity.ok(PrivacyDeleteResult(target = target, deleted = n) as Any)
                },
                onFailure = { e ->
                    log.warn("[privacy] Löschen von {} fehlgeschlagen: {}", target, e.toString())
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                        PrivacyError(
                            error = "delete-failed",
                            message = "Löschen fehlgeschlagen — die Daten liegen unverändert; bitte erneut versuchen.",
                        ) as Any,
                    )
                },
            )
        }.subscribeOn(Schedulers.boundedElastic())

    // ── Pfad-Auflösung (Spiegel der PipelineConfig-Beans — bitte synchron halten) ──

    internal fun memoryPath(): Path =
        if (memoryDbPath.isNotBlank()) Path.of(memoryDbPath) else Path.of(EntityMemoryAdapter.defaultDbPath())

    internal fun episodicPath(): Path =
        if (episodicDbPath.isNotBlank()) Path.of(episodicDbPath) else Path.of(EpisodicMemoryAdapter.defaultDbPath())

    /** Exakt die [DiaryController.resolveDirectory]-Auflösung (eine Wahrheit, gespiegelt). */
    internal fun resolveDiaryDir(): Path = when {
        diaryDir.isNotBlank() -> Path.of(diaryDir)
        Files.isWritable(Path.of("/var/lib/hoshi-0.8")) -> Path.of("/var/lib/hoshi-0.8/diary")
        else -> Path.of(System.getProperty("user.home"), ".hoshi", "diary")
    }

    /** Alle Tages-Dateien (`turn-diary-*.jsonl`) — fehlendes Verzeichnis ⇒ leer (ehrlich). */
    private fun diaryFiles(): List<Path> {
        val dir = resolveDiaryDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.filter { DIARY_FILE.matches(it.fileName.toString()) }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** EXAKT [JsonlLookupNoteAdapter.resolveDefaultPath] — eine Datei-Wahrheit, gespiegelt. */
    internal fun lookupsPath(): Path = JsonlLookupNoteAdapter.resolveDefaultPath(lookupNotePath.ifBlank { null })

    /** Zeilen-Anzahl der Nachgeschlagen-Datei (append-only ⇒ 1 Zeile = 1 Notiz). `null` = unlesbar. */
    private fun lookupLineCount(): Int? =
        runCatching { Files.readAllLines(lookupsPath()).size }.getOrNull()

    companion object {
        /** Tages-Datei-Muster des [JsonlTurnTraceAdapter] — nur DIESE Dateien zählen/löschen. */
        val DIARY_FILE = Regex("^${JsonlTurnTraceAdapter.FILE_PREFIX}-\\d{4}-\\d{2}-\\d{2}\\.jsonl$")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Wire-Vertrag (FE: api/privacy.ts rendert exakt dagegen)
// ─────────────────────────────────────────────────────────────────────────────

/** Aktive TTS-Engine + Cloud-Flag (deckungsgleich mit dem OpsStatus-`voice`-Feld). */
data class PrivacyVoice(val engine: String, val cloud: Boolean)

/** Ist die Never-Speak-Maskierung vor dem Cloud-TTS-Call aktiv? */
data class PrivacySanitize(val enabled: Boolean)

/**
 * Eine ehrliche Store-Zeile (Entity-/Episodic-Memory):
 *  - [enabled] — schreibt die Pipeline gerade hinein (Boot-Flag)?
 *  - [exists]/[sizeBytes] — liegt die sqlite-Datei da, wie groß?
 *  - [entries] — ECHTER `COUNT(*)`; `null` = ehrlich „weiß nicht" (Datei fehlt/nicht lesbar).
 */
data class PrivacyStoreInfo(
    val enabled: Boolean,
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val entries: Int?,
)

/** Diary-Zeile: Anzahl Tages-Dateien (das Diary trägt KEINE Gesprächs-Inhalte). */
data class PrivacyDiaryInfo(val enabled: Boolean, val dir: String, val days: Int)

/** Die volle Privatsphäre-Übersicht (`GET /api/v1/privacy/summary`). */
data class PrivacySummary(
    val voice: PrivacyVoice,
    val sanitize: PrivacySanitize,
    val memory: PrivacyStoreInfo,
    val episodic: PrivacyStoreInfo,
    val diary: PrivacyDiaryInfo,
    /** Extended Think S3 — Nachgeschlagen-Store (`nachgeschlagen.jsonl`, additiv). */
    val lookups: PrivacyStoreInfo,
)

/** Erfolgs-Antwort der Lösch-API: die BEWIESENE Zahl gelöschter Zeilen/Dateien. */
data class PrivacyDeleteResult(val target: String, val deleted: Int)

/** Fehler-Body (500) — ehrlich, ohne Pfad-/Stacktrace-Leak über den Draht. */
data class PrivacyError(val error: String, val message: String)
