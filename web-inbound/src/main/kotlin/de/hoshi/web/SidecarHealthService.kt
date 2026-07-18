package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.supervision.HealthState
import de.hoshi.core.supervision.SidecarHealth
import de.hoshi.core.supervision.SidecarPort
import de.hoshi.core.supervision.SidecarSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
//  Contract-DTOs — werden 1:1 zu dem von der UI erwarteten JSON serialisiert.
//  (Feld-Reihenfolge = Vertrag-Reihenfolge; Strings statt Enums, damit das Wire-
//  JSON exakt "OK"/"WARN"/… trägt.)
// ─────────────────────────────────────────────────────────────────────────────

/** Mac-RAM-Druck, abgeleitet aus der Brain-Health (`:8041/health` → `wired`). */
data class MemoryStatus(val level: String, val source: String, val detail: String)

/** Pro-Sidecar-Status nach Consecutive-Failure-Glättung. */
data class SidecarStatus(val name: String, val status: String, val detail: String)

/**
 * Aktive TTS-Engine fürs FE (Toms ☁️-Cloud-Banner: „Cloud nur mit Banner").
 * `cloud=true` NUR bei `engine="openai"` — das ist der einzige TTS-Pfad, der die
 * Box verlässt; say/piper/voxtral sind lokal.
 *
 * **Live seit dem Runtime-Switch (9edbb1d/b4844d0):** [SidecarHealthService.currentVoice]
 * liest zuerst den GEWÄHLTEN Laufzeit-Wunsch aus dem [JsonFileTtsEngineStore] — derselben
 * Wahrheit, die die Settings-Sektion nutzt — und fällt NUR ohne einen Runtime-Switch auf
 * die Boot-Config (`HOSHI_TTS`) zurück. Vorher spiegelte dieses Feld ausschließlich die
 * Boot-Config (Andi-Befund 2026-07-20: die Cloud-Zeile blieb nach einem Wechsel auf eine
 * lokale Engine stehen).
 */
data class VoiceStatus(val engine: String, val cloud: Boolean)

/** Der vollständige Ops-Status — Snapshot, den der Controller bei Flag ON ausliefert. */
data class OpsStatus(
    val enabled: Boolean,
    val overall: String,
    val memory: MemoryStatus,
    val sidecars: List<SidecarStatus>,
    val voice: VoiceStatus,
    /**
     * Andis Schloss-Wunsch (2026-07-20): `true` NUR wenn der GESAMTE Sprech-Pfad lokal
     * ist — STT (`whisper-stt` OK) UND Brain (OK, nicht DEGRADED/Drift/Loading) UND die
     * GEWÄHLTE TTS-Engine lokal (`!voice.cloud`). S. [SidecarHealthService.deriveAllLocal].
     */
    val allLocal: Boolean,
    val ts: Long,
)

/**
 * **BrainHealthSource** — die schmale Naht, die das rohe `GET {brain}/health`-JSON
 * liefert. Funktionales Interface ⇒ Tests injizieren kanned JSON ohne Live-Brain.
 */
fun interface BrainHealthSource {
    /** Roh-Body von `GET {brain}/health`, oder `null` bei Fehler/Timeout (best-effort). */
    fun fetchHealthJson(): String?
}

/**
 * Live-Impl: synchroner `java.net.http`-GET gegen `{brainBaseUrl}/health`. Bewusst
 * NICHT WebClient — der Aufruf läuft im Scheduler-Thread, und ein blockierender
 * WebClient.block() ist auf netty-Threads verboten (0.5-Lehre, siehe HonestyProbe).
 * `java.net.http` ist thread-agnostisch und best-effort: jeder Fehler → `null`.
 */
class HttpBrainHealthSource(
    brainBaseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(3),
) : BrainHealthSource {
    private val healthUri: URI = URI.create(brainBaseUrl.trimEnd('/') + "/health")
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun fetchHealthJson(): String? = runCatching {
        val req = HttpRequest.newBuilder(healthUri).timeout(timeout).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) resp.body() else null
    }.getOrNull()
}

/**
 * **BrainMemoryHeuristic** — die REINE Ableitung des Mac-RAM-Drucks aus der
 * Brain-Health. Auf ct-106 (Linux) ist `vm_stat`/[de.hoshi.adapters.supervision.MacMemorySnapshot]
 * blind für den Mac — der Mac-Druck kommt darum aus dem Feld, das der Brain-Sidecar
 * (`server_e4b.py`) ohnehin im `/health`-JSON exponiert:
 *
 * ```
 * "wired": { "want_mb":…, "active":bool, "memorystatus_level":int,
 *            "release_lvl":int, "reapply_lvl":int }
 * ```
 *
 * `memorystatus_level` ist `sysctl kern.memorystatus_level` (0..100, **höher = mehr
 * freier RAM**, `-1` = nicht gemessen). Die Schwellen kommen aus DEMSELBEN Health-JSON
 * (`release_lvl`/`reapply_lvl`, Default 25/40) und spiegeln exakt die Hysterese, mit
 * der der Sidecar seinen wired-Pin freigibt/re-pinnt:
 *  - `level < release_lvl`  → **CRITICAL** (akuter Druck: der Sidecar gibt gepinnte
 *    Pages frei, damit ein Voice-Peak STT+TTS nicht erstickt).
 *  - `level < reapply_lvl`  → **WARN** (Hysterese-Band: knapp, noch nicht erholt).
 *  - `level ≥ reapply_lvl`  → **OK** (reichlich frei).
 *  - `level < 0` / kein `wired`-Feld / Brain unerreichbar → **UNKNOWN** (ehrlich, kein Fake).
 *
 * **Annahme:** `memorystatus_level` ist nur LIVE, wenn der Residency-Monitor des
 * Brains läuft (`HOSHI_E4B_WIRED_MB>0`). Sonst bleibt es bei `-1` ⇒ UNKNOWN — das ist
 * der ehrliche „weiß ich nicht"-Zustand, kein falsches Grün.
 */
object BrainMemoryHeuristic {
    const val SOURCE = "brain-health"
    private const val DEFAULT_RELEASE = 25
    private const val DEFAULT_REAPPLY = 40
    private val mapper = jacksonObjectMapper()

    /** Klassifiziert aus dem rohen `/health`-Body (null/leer/unparsebar → UNKNOWN). */
    fun classify(healthBody: String?): MemoryStatus {
        if (healthBody.isNullOrBlank()) {
            return unknown("Brain-Health (:8041/health) nicht erreichbar — RAM-Druck am Mac unbekannt.")
        }
        val node = runCatching { mapper.readTree(healthBody) }.getOrNull()
            ?: return unknown("Brain-Health nicht parsebar — RAM-Druck am Mac unbekannt.")
        return classify(node)
    }

    /** Klassifiziert aus dem bereits geparsten `/health`-Knoten. */
    fun classify(node: JsonNode): MemoryStatus {
        val wired = node.path("wired")
        if (wired.isMissingNode || wired.isNull) {
            return unknown("Brain liefert kein wired-Feld — RAM-Druck am Mac unbekannt.")
        }
        val level = wired.path("memorystatus_level").asInt(-1)
        val releaseLvl = wired.path("release_lvl").asInt(DEFAULT_RELEASE)
        val reapplyLvl = wired.path("reapply_lvl").asInt(DEFAULT_REAPPLY)
        return when {
            level < 0 -> unknown("Brain misst keinen RAM-Pegel (Residency-Monitor aus) — Druck am Mac unbekannt.")
            level < releaseLvl -> MemoryStatus(
                "CRITICAL",
                SOURCE,
                "Akuter RAM-Druck am Mac (Pegel $level < $releaseLvl) — Sidecars könnten ausgebremst werden.",
            )
            level < reapplyLvl -> MemoryStatus(
                "WARN",
                SOURCE,
                "RAM wird knapp am Mac (Pegel $level, Erholung erst ab $reapplyLvl).",
            )
            else -> MemoryStatus(
                "OK",
                SOURCE,
                "RAM am Mac entspannt (Pegel $level ≥ $reapplyLvl).",
            )
        }
    }

    private fun unknown(detail: String) = MemoryStatus("UNKNOWN", SOURCE, detail)
}

/**
 * **SidecarHealthService** — der Ops-Status-Watchdog. Hält einen Snapshot (Sidecar-
 * Gesundheit + Mac-RAM-Druck), den der [OpsStatusController] OHNE Blocking ausliefert.
 *
 * **Flag-gated, default OFF** (`HOSHI_SIDECAR_WATCH_ENABLED`): bei OFF feuert der
 * Scheduler zwar, kehrt aber VOR jedem Probe-Call zurück (keine Netz-Calls, kein
 * Snapshot-Update) — byte-neutral. Der Controller antwortet dann `{"enabled":false}`.
 *
 * **Split-Topologie:** das Backend läuft auf ct-106 (Linux), die Sidecars auf dem Mac.
 * Die Probe-URLs kommen aus DENSELBEN `*.base-url`-Properties, die der Rest des Backends
 * nutzt (`hoshi.brain.base-url` etc.) — auf ct-106 zeigen die auf die Mac-IP, nicht auf
 * localhost. So klopft der Watchdog exakt die Hosts ab, die die Pipeline auch nutzt.
 *
 * **Glättung:** ein einzelner Blip (eine fehlgeschlagene Probe) alarmiert NICHT —
 * ein Sidecar wird erst nach [failureThreshold] aufeinanderfolgenden Nicht-OK-Proben
 * als DEGRADED/DOWN gemeldet; davor bleibt er (geglättet) OK mit Hinweis im Detail.
 *
 * **Best-effort:** jede Probe ist in `runCatching` gekapselt; ein Fehler wird zu DOWN/
 * UNKNOWN, nie zu einer Exception — der Scheduler darf den Betrieb nie stören.
 */
@Component
class SidecarHealthService(
    @Value("\${HOSHI_SIDECAR_WATCH_ENABLED:false}") private val enabled: Boolean,
    @Value("\${hoshi.brain.base-url:http://localhost:8041}") brainUrl: String,
    @Value("\${hoshi.stt.base-url:http://localhost:9001}") sttUrl: String,
    @Value("\${hoshi.tts.base-url:http://localhost:8042}") ttsUrl: String,
    @Value("\${hoshi.knowledge.bridge.base-url:http://localhost:8035}") bridgeUrl: String,
    @Value("\${hoshi.speaker.base-url:http://localhost:9002}") speakerUrl: String,
    @Value("\${hoshi.sidecar.watch.failure-threshold:2}") private val failureThreshold: Int,
    // BOOT-Default des Brain-Solls — kommt vom DEPLOY aus derselben Modell-Wahl wie der
    // Brain-Start selbst (`pipeline/deploy.sh#resolve_brain_expected`, EINE Quelle).
    // Leer = keine Erwartung ⇒ jedes geladene Modell ist ok. Gilt NUR, solange NIE per
    // PUT /settings/brain zur Laufzeit umgeschaltet wurde — ein Runtime-Switch
    // überschreibt dieses Literal (s. [brainModelStore] unten, [currentSpecs]).
    // Warum so: ein hartes Default-Literal hier wurde zur Geister-Drift-Meldung, sobald
    // die Wahl per Settings-UI anderswo wechselte (Andi-Befund 2026-07-20) —
    // Literal-Zwillinge driften, GEWÄHLT gewinnt IMMER gegen ein Boot-Fossil.
    @Value("\${hoshi.brain.expected-model:}") private val brainExpectedModel: String = "",
    // BOOT-Default der TTS-Engine — dieselbe Env wie die Adapter-Wahl in
    // PipelineConfig.ttsPort. Gilt NUR, solange NIE per PUT /settings/tts umgeschaltet
    // wurde — [ttsEngineStore] (falls gesetzt) gewinnt (s. [currentVoice]).
    @Value("\${HOSHI_TTS:}") private val ttsImpl: String = "",
    private val probe: SidecarPort,
    private val brainHealth: BrainHealthSource,
    // Additiv: das GEWÄHLTE Brain-Modell aus `PUT /settings/brain`
    // ([BrainSettingsController]/[JsonFileBrainModelStore]) — `null` NUR in älteren
    // Tests, die den Store nicht mitgeben; Prod bekommt ihn per Spring-Autowiring
    // (Bean aus [BrainRuntimeConfig]).
    private val brainModelStore: JsonFileBrainModelStore? = null,
    // Additiv: die GEWÄHLTE TTS-Engine aus `PUT /settings/tts`
    // ([TtsSettingsController]/[JsonFileTtsEngineStore]) — dieselbe Wahrheit, die die
    // Settings-Sektion nutzt (b4844d0), NIE eine zweite, driftende Cloud/Lokal-Ableitung.
    private val ttsEngineStore: JsonFileTtsEngineStore? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Die bekannten Sidecars mit Contract-Namen + den verifizierten BOOT-Soll-Modellen.
     * URLs aus den injizierten Properties (Mac-IP auf ct-106). `ramCostMb`/
     * `restartCommand` sind hier irrelevant (der Watchdog SENST nur — kein Restart,
     * kein RAM-Arbiter). Das Brain-`expectedModel` hier ist NUR der Boot-Default —
     * [currentSpecs] überschreibt es dynamisch, sobald ein Runtime-Switch vorliegt.
     */
    private val staticSpecs: List<SidecarSpec> = listOf(
        SidecarSpec(name = "brain", url = brainUrl, ramCostMb = 0, expectedModel = brainExpectedModel.ifBlank { null }),
        SidecarSpec(name = "whisper-stt", url = sttUrl, ramCostMb = 0, expectedModel = "whisper-large-v3-turbo"),
        SidecarSpec(name = "speaker-id", url = speakerUrl, ramCostMb = 0, expectedModel = "CAM++"),
        SidecarSpec(name = "bridge", url = bridgeUrl, ramCostMb = 0),
        SidecarSpec(name = "voxtral-tts", url = ttsUrl, ramCostMb = 0, expectedModel = "Voxtral"),
    )

    /** Pro-Sidecar Zähler aufeinanderfolgender Nicht-OK-Proben (für die Glättung). */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /**
     * Die Sidecar-Specs FÜR DIESEN Tick: identisch zu [staticSpecs], außer dass das
     * Brain-Soll dynamisch überschrieben wird, WENN je ein Runtime-Switch passiert ist
     * ([brainModelStore]) — sonst bleibt der Boot-Default ([brainExpectedModel]) die
     * Wahrheit (Erst-Boot-Fall, nie umgeschaltet). Drift ist damit IMMER „laufendes
     * Modell ≠ GEWÄHLTES Modell", nie gegen ein statisches Deploy-Literal.
     */
    private fun currentSpecs(): List<SidecarSpec> {
        val chosen = brainModelStore?.selectedRepo()?.takeIf { it.isNotBlank() } ?: return staticSpecs
        return staticSpecs.map { if (it.name == "brain") it.copy(expectedModel = chosen) else it }
    }

    /**
     * Aktive TTS-Engine (s. [VoiceStatus]): DELEGIERT an [TtsEngineIds.effectiveEngineId]
     * (b4844d0 + e404804) — dieselbe Naht, die auch [PrivacyController.buildSummary] nutzt,
     * damit beide Ränder NIE auseinanderdriften. `cloud=true` NUR bei `engine="openai"`
     * (der einzige TTS-Pfad, der die Box verlässt).
     */
    private fun currentVoice(): VoiceStatus {
        val effective = TtsEngineIds.effectiveEngineId(ttsEngineStore, ttsImpl)
        return VoiceStatus(engine = effective, cloud = effective == TtsEngineIds.OPENAI)
    }

    /** Letzter Snapshot — vom Scheduler aktualisiert, vom Controller blocking-frei gelesen. */
    @Volatile
    private var snapshot: OpsStatus = OpsStatus(
        enabled = true,
        overall = "DEGRADED",
        memory = MemoryStatus("UNKNOWN", BrainMemoryHeuristic.SOURCE, "Watchdog wärmt auf — noch kein Probe-Lauf."),
        sidecars = emptyList(),
        voice = currentVoice(),
        allLocal = false,
        ts = System.currentTimeMillis(),
    )

    /**
     * Was der Controller ausliefert: bei Flag OFF NUR `{"enabled":false}` (byte-neutral),
     * sonst der letzte [OpsStatus]-Snapshot. KEIN Blocking, kein Probe-Call im Request.
     */
    fun current(): Any = if (!enabled) mapOf("enabled" to false) else snapshot

    /**
     * **Additive Lese-Naht für Gates** (z.B. das STT-Readiness-Gate, 0.5-Port):
     * liefert den geglätteten Status-String (`"OK"`/`"DEGRADED"`/`"DOWN"`) des
     * benannten Sidecars aus dem letzten Snapshot — oder `null`, wenn der Status
     * UNBEKANNT ist:
     *  - Watchdog AUS (`HOSHI_SIDECAR_WATCH_ENABLED=false`) ⇒ kein verlässlicher
     *    Snapshot ⇒ `null` (ein Gate lässt dann durch — byte-neutral).
     *  - der Sidecar steht (noch) nicht im Snapshot (Warmup vor dem ersten Probe-
     *    Lauf, oder unbekannter Name) ⇒ `null`.
     *
     * `null` ist ehrliches „weiß ich nicht" (UNKNOWN) — nie ein Fake-Status.
     * Blocking-frei: liest nur den `@Volatile`-Snapshot, kein Probe-Call.
     */
    fun statusOf(name: String): String? =
        if (!enabled) null else snapshot.sidecars.firstOrNull { it.name == name }?.status

    /**
     * Der Scheduler-Tick (~45 s): probt alle Sidecars über die Mac-IP, leitet den
     * Mac-RAM-Druck aus der Brain-Health ab, baut den geglätteten Snapshot. Bei Flag OFF
     * sofortiger Return (byte-neutral). `@EnableScheduling` liefert [WarmProbeScheduling].
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = REFRESH_INTERVAL_MS)
    fun refresh() {
        if (!enabled) return
        val sidecars = currentSpecs().map { spec ->
            val raw = runCatching { probe.probe(spec) }
                .getOrElse { SidecarHealth.down("Probe warf: ${it.message?.take(80)}") }
            smooth(spec.name, raw)
        }
        val memory = runCatching { BrainMemoryHeuristic.classify(brainHealth.fetchHealthJson()) }
            .getOrElse {
                MemoryStatus("UNKNOWN", BrainMemoryHeuristic.SOURCE, "Brain-Health-Abfrage warf — RAM-Druck unbekannt.")
            }
        val voice = currentVoice()
        snapshot = OpsStatus(
            enabled = true,
            overall = deriveOverall(sidecars, memory),
            memory = memory,
            sidecars = sidecars,
            voice = voice,
            allLocal = deriveAllLocal(sidecars, voice),
            ts = System.currentTimeMillis(),
        )
        log.debug("[ops-watch] overall={} mem={} sidecars={}", snapshot.overall, memory.level, sidecars)
    }

    /**
     * Consecutive-Failure-Glättung: OK setzt den Zähler zurück und meldet OK. Eine
     * Nicht-OK-Probe erhöht den Zähler; erst ab [failureThreshold] wird der rohe
     * (DEGRADED/DOWN-)Zustand gemeldet — davor bleibt es geglättet OK (Blip-Schutz),
     * mit dem rohen Detail im Text, damit nichts verschwiegen wird.
     */
    private fun smooth(name: String, raw: SidecarHealth): SidecarStatus {
        if (raw.state == HealthState.OK) {
            failureCounts[name] = 0
            return SidecarStatus(name, "OK", raw.detail)
        }
        val n = failureCounts.merge(name, 1) { a, b -> a + b } ?: 1
        return if (n >= failureThreshold) {
            SidecarStatus(name, raw.state.name, raw.detail)
        } else {
            SidecarStatus(name, "OK", "geglättet ($n/$failureThreshold Fehlversuch): ${raw.detail}")
        }
    }

    /**
     * Gesamt-Status: DOWN dominiert (mind. ein KRITISCHER DOWN-Sidecar). Sonst DEGRADED, wenn ein
     * kritischer Sidecar DEGRADED ist ODER der Mac-RAM-Druck CRITICAL ist (das ist Andis eigentlicher
     * Alarm-Wunsch). Memory WARN trägt die UI separat über das `memory`-Feld.
     *
     * **Optionale Sidecars** ([OPTIONAL_SIDECARS]: Voxtral-TTS, Speaker-ID) sind bewusst aus (RAM,
     * 16-GB-Wand) — ihr DOWN treibt `overall` NICHT (sonst dauerhaft rote Pille = cry-wolf); sie
     * erscheinen weiter in der Liste, damit nichts verschwiegen wird.
     */
    private fun deriveOverall(sidecars: List<SidecarStatus>, memory: MemoryStatus): String {
        val critical = sidecars.filterNot { it.name in OPTIONAL_SIDECARS }
        return when {
            critical.any { it.status == "DOWN" } -> "DOWN"
            critical.any { it.status == "DEGRADED" } || memory.level == "CRITICAL" -> "DEGRADED"
            else -> "OK"
        }
    }

    /**
     * Andis Schloss-Wunsch (2026-07-20): GRÜN nur, wenn der GESAMTE Sprech-Pfad lokal
     * ist — STT (`whisper-stt` OK) UND Brain (OK, nicht DEGRADED — kein Drift/Loading)
     * UND die GEWÄHLTE TTS-Engine lokal (`!voice.cloud`, s. [currentVoice]: say/piper/
     * voxtral). Fehlt einer der beiden Sidecar-Status (Warmup, unbekannter Name) ⇒
     * `false` — ehrlich „noch nicht bewiesen", nie ein optimistisches Grün.
     */
    private fun deriveAllLocal(sidecars: List<SidecarStatus>, voice: VoiceStatus): Boolean {
        val stt = sidecars.firstOrNull { it.name == "whisper-stt" }?.status
        val brain = sidecars.firstOrNull { it.name == "brain" }?.status
        return stt == "OK" && brain == "OK" && !voice.cloud
    }

    companion object {
        /**
         * Bewusst-aus-Sidecars (16-GB-Wand): ihr DOWN treibt `overall` nicht (cry-wolf-Schutz).
         * Voxtral-TTS (:8042) ist aus, weil Andi OpenAI-TTS nutzt; Speaker-ID (CAM++ :9002) ist
         * aus, bis Multi-User-Bedarf besteht. Werden weiter geprobt + angezeigt, nur nicht alarmierend.
         */
        val OPTIONAL_SIDECARS = setOf("voxtral-tts", "speaker-id")

        /** Erster Tick kurz nach Boot — vorher liefert der Controller die Warmup-Zeile. */
        const val INITIAL_DELAY_MS = 5_000L

        /** Re-Probe-Intervall (~45 s) — leichtgewichtig, kein Live-Last-Treiber. */
        const val REFRESH_INTERVAL_MS = 45_000L
    }
}
