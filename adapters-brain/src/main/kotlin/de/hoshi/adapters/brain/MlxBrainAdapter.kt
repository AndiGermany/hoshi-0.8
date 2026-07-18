package de.hoshi.adapters.brain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.LlmDelta
import de.hoshi.core.port.BrainPort
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Brain-Adapter für den aktiven e4b-Brain (`hoshi-llm-optiq/server_e4b.py`,
 * Port 8041, gemma-4-e4b). Spricht `POST /v1/chat`
 * (`{sessionId, userId, messages[], stream}` → Text-SSE) und implementiert den
 * hexagonalen [BrainPort].
 *
 * Portiert aus Hoshi 0.5 `MlxOmniLlmClient`, ENTKOPPELT von Spring: kein
 * `@Service`, keine `HoshiProperties`, kein `MemoryDirector`. Konfiguration über
 * Konstruktor-Parameter; der WebClient wird intern via [WebClient.builder] gebaut.
 *
 * **Schnittstellen-Parität:** `streamChat(...)` liefert einen `Flux<LlmDelta>`.
 *
 * **Empty-Stream-Retry erhalten:** auf dem 16-GB-Mac gibt der Sidecar unter
 * gleichzeitiger Last gelegentlich 0 Zeichen zurück (Memory-Druck). Ein einmaliger
 * Retry bei LEEREM Stream (`switchIfEmpty`) fängt den Aussetzer ab.
 *
 * **Sampling-Hebel (D1, flag-gated):** `min_p` + `presence_penalty` sind die
 * belegten Sofort-Hebel gegen Slop/Repetition (min-p 0.05–0.1). EHRLICH: das
 * heutige `server_e4b.py` liest diese Felder evtl. noch NICHT — ein
 * FastAPI/pydantic-Server ignoriert unbekannte JSON-Felder vermutlich stumm,
 * KANN aber (bei `extra="forbid"`) mit 422 ablehnen; prüfbar erst nach dem
 * Brain-Restart. Darum doppelt abgesichert: die Felder gehen NUR in den Body,
 * wenn [samplingEnabled] `true` ist (Wiring: Env `HOSHI_BRAIN_SAMPLING_ENABLED`)
 * UND der jeweilige Wert nicht null ist. Default `false`/null ⇒ Request-Body
 * byte-identisch zu vorher. Wirkung entfaltet sich erst, wenn server_e4b die
 * Felder in einer Folge-Scheibe an `mlx_lm` durchreicht.
 *
 * **XTC/Opener-Hebel (D1b, flag-gated):** `server_e4b.py` akzeptiert seit
 * 2026-07-02 optional `xtc_probability`/`xtc_threshold` (Anti-Slop-Sampler)
 * und `opener_bias` (Satzanfang-Varianz). Die Felder gehen NUR bei
 * [d1bEnabled] `true` in den Body; `false` (Default) ⇒ byte-identisch zu
 * heute — unabhängig von [samplingEnabled], die beiden Flags sind getrennte
 * Rollback-Hebel.
 *
 * **Antwort-Entropie-Sensor (S1, flag-gated, MESSEN-first):** bei
 * [entropyEnabled] `true` (Wiring: Env `HOSHI_ANSWER_ENTROPY_ENABLED`, Default
 * OFF) geht zusätzlich `logprobs: true` in den Body — die Bitte an den Brain,
 * pro Delta-Frame den Logprob des gesampelten Tokens mitzuliefern
 * (`{"delta":"…","logprob":-0.42}`). EHRLICH: das heutige Prod-`server_e4b.py`
 * (0.5) sendet das Feld noch NICHT (geprüft 2026-07-07, es streamt nur
 * `{"delta":…}`); der Parser ist darum vollständig null-tolerant — fehlt das
 * Feld / ist es nicht numerisch ⇒ [de.hoshi.core.dto.LlmDelta.logprob] bleibt
 * `null`, alles downstream (answerEntropy, Diary `answerEntropy`) bleibt
 * ehrlich null. Bei ON wird NUR gemessen + geloggt (mittlerer Surprisal
 * −mean(logprob) am Stream-Ende, laufende Summe statt Liste) — KEIN
 * Verhalten hängt am Wert (Abstain-Wirkung ist S2 nach echter Datenlage:
 * Token-Entropie konfundiert Wortwahl mit Nicht-Wissen ⇒ erst kalibrieren).
 * `false` (Default) ⇒ Body byte-identisch, kein Feld, keine Messung.
 */
class MlxBrainAdapter(
    baseUrl: String,
    private val maxTokens: Int = 512,
    private val temperature: Double = 0.6,
    private val chatTimeoutSeconds: Long = 30,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    // FLAG-GATE für die Sampling-Felder (D1). `false` (Default) ⇒ min_p/presence_penalty
    // werden NIE gesendet, egal was konfiguriert ist — Body bleibt byte-identisch.
    private val samplingEnabled: Boolean = false,
    // min-p-Sampling (0.05–0.1 empfohlen). null ⇒ Feld fehlt im Body.
    private val minP: Double? = null,
    // Presence-Penalty gegen Wiederholungs-Slop. null ⇒ Feld fehlt im Body.
    private val presencePenalty: Double? = null,
    // FLAG-GATE für XTC/Opener (D1b), UNABHÄNGIG von samplingEnabled. `false`
    // (Default) ⇒ die drei Felder werden NIE gesendet — Body byte-identisch.
    private val d1bEnabled: Boolean = false,
    // XTC-Sampler: Wahrscheinlichkeit, Top-Tokens zu kappen (Anti-Slop).
    private val xtcProbability: Double = 0.5,
    // XTC-Sampler: Mindest-Wahrscheinlichkeit, ab der ein Token kappbar ist.
    private val xtcThreshold: Double = 0.1,
    // Satzanfang-Varianz im Brain (server_e4b `opener_bias`).
    private val openerBias: Boolean = true,
    // FLAG-GATE für den Antwort-Entropie-Sensor (S1). `false` (Default) ⇒ das
    // `logprobs`-Feld wird NIE gesendet und nichts gemessen/geloggt — Body
    // byte-identisch. `true` ⇒ `logprobs:true` im Body + mittlerer Surprisal
    // wird am Stream-Ende geloggt (NUR messen, kein Verhalten — s. Klassen-KDoc).
    private val entropyEnabled: Boolean = false,
) : BrainPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
        .build()

    /**
     * Streamt eine Chat-Antwort vom e4b-Sidecar. SSE-Format `data: {...}\n\n`,
     * jeder Frame trägt `{"delta": "..."}` (flaches delta-Feld), defensiv
     * ZUSÄTZLICH die OpenAI-Form (`choices[0].delta.content`) und ein flaches
     * `{text}`. `[DONE]` beendet den Stream.
     */
    override fun streamChat(
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double?,
        sessionId: String,
        userId: String,
        // Agentische gemma-Tool-Schemas. Nicht-leer ⇒ als `tools` in den Body; leer
        // ⇒ Body byte-identisch zu vorher (kein `tools`-Feld).
        tools: List<Map<String, Any?>>,
        // PATH B: `true` ⇒ `tool_grammar:true` in den Body (Brain erzwingt {tool,args}).
        // `false` ⇒ Body byte-identisch zu vorher (kein `tool_grammar`-Feld).
        toolGrammar: Boolean,
        // Echtes TTFT-Callback. Wird EINMAL pro Turn (First-Wins via Guard) mit der
        // Dauer (ms) vom LLM-POST bis zum ersten NICHT-leeren Delta gerufen.
        onPrefill: (Long) -> Unit,
    ): Flux<LlmDelta> {
        val messages = mutableListOf<Map<String, String>>()
        if (systemPrompt.isNotBlank()) messages += mapOf("role" to "system", "content" to systemPrompt)
        history.forEach { messages += mapOf("role" to it.role, "content" to it.content) }
        messages += mapOf("role" to "user", "content" to prompt)

        val body = buildMap<String, Any> {
            put("sessionId", sessionId)
            put("userId", userId)
            put("messages", messages)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature ?: this@MlxBrainAdapter.temperature)
            // NUR setzen, wenn Tools übergeben wurden ⇒ ohne Tools byte-identisch zu vorher.
            if (tools.isNotEmpty()) put("tools", tools)
            // NUR setzen, wenn Tool-Grammar angefragt wurde ⇒ sonst byte-identisch (kein Feld).
            if (toolGrammar) put("tool_grammar", true)
            // Sampling-Hebel (D1): NUR bei Flag AN und konfiguriertem Wert ⇒ sonst
            // fehlt das Feld und der Body ist byte-identisch zu heute (s. Klassen-KDoc).
            if (samplingEnabled) {
                minP?.let { put("min_p", it) }
                presencePenalty?.let { put("presence_penalty", it) }
            }
            // XTC/Opener (D1b): NUR bei Flag AN ⇒ sonst fehlen die Felder und der
            // Body ist byte-identisch zu heute (Rollback = Flag aus, s. Klassen-KDoc).
            if (d1bEnabled) {
                put("xtc_probability", xtcProbability)
                put("xtc_threshold", xtcThreshold)
                put("opener_bias", openerBias)
            }
            // Antwort-Entropie (S1): NUR bei Flag AN ⇒ sonst fehlt das Feld und der
            // Body ist byte-identisch zu heute. Ein ungepatchter server_e4b (pydantic
            // ohne extra="forbid") ignoriert das unbekannte Feld stumm — und der
            // Parser unten toleriert fehlende logprobs ohnehin (alles bleibt null).
            if (entropyEnabled) put("logprobs", true)
        }

        log.debug("[mlx-brain] /v1/chat session={} user={} msgs={}", sessionId, userId, messages.size)

        // First-Wins-Guard fürs TTFT. Spannt über BEIDE Versuche (Original + Retry) —
        // das erste nicht-leere Delta gewinnt, egal aus welchem Versuch.
        val prefillReported = java.util.concurrent.atomic.AtomicBoolean(false)
        val stream = callBrain(body, prefillReported, onPrefill)
            .switchIfEmpty(
                Flux.defer {
                    log.warn("[mlx-brain] Stream lieferte 0 Zeichen — Retry 1x (vermutl. 16GB-Memory-Druck)")
                    callBrain(body, prefillReported, onPrefill).delaySubscription(Duration.ofMillis(200))
                },
            )
        if (!entropyEnabled) return stream // Flag OFF ⇒ exakt der heutige Stream (keine Messung)
        // Antwort-Entropie (S1, NUR messen+loggen): laufende Summe + Zähler statt
        // Liste (kein Speicher-Wachstum, egal wie lang der Turn wird). Am Stream-
        // Ende EIN Log mit dem mittleren Surprisal −mean(logprob) in nats — die
        // Kalibrier-Datenbasis für den S2-Abstain-Entscheid. Kein Verhalten hängt
        // am Wert; Server ohne logprobs ⇒ count==0 ⇒ gar kein Log (ehrlich).
        val surprisalSum = java.util.concurrent.atomic.DoubleAdder()
        val surprisalCount = java.util.concurrent.atomic.AtomicLong()
        return stream
            .doOnNext { delta ->
                delta.logprob?.let { lp ->
                    surprisalSum.add(-lp)
                    surprisalCount.incrementAndGet()
                }
            }
            .doFinally {
                val n = surprisalCount.get()
                if (n > 0) {
                    log.info(
                        "[mlx-brain] answerEntropy: mittlerer Surprisal {} nats ueber {} Tokens (S1 nur Messung)",
                        String.format(java.util.Locale.ROOT, "%.4f", surprisalSum.sum() / n),
                        n,
                    )
                }
            }
    }

    /** Ein einzelner /v1/chat-Aufruf gegen den Sidecar → Flux der Text-Deltas. */
    private fun callBrain(
        body: Map<String, Any>,
        prefillReported: java.util.concurrent.atomic.AtomicBoolean,
        onPrefill: (Long) -> Unit,
    ): Flux<LlmDelta> = Flux.defer {
        // Request-Start pro Versuch beim Subscribe erfassen (Defer) — so zählt
        // beim Retry NICHT der 200ms-delaySubscription mit.
        val reqStartNanos = System.nanoTime()
        client.post().uri("/v1/chat")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String::class.java)
            .timeout(Duration.ofSeconds(chatTimeoutSeconds))
            .concatMap { chunk ->
                Flux.fromIterable(chunk.split("\n").filter { it.isNotBlank() })
            }
            .flatMap { line ->
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]" || data.isBlank()) return@flatMap Flux.empty()
                runCatching {
                    val node = mapper.readTree(data)
                    val openAiDelta = node.path("choices").path(0).path("delta").path("content").asText("")
                    val flatDelta = node.path("delta").asText("")
                    val flatText = node.path("text").asText("")
                    val text = when {
                        openAiDelta.isNotEmpty() -> openAiDelta
                        flatDelta.isNotEmpty() -> flatDelta
                        else -> flatText
                    }
                    if (text.isNotEmpty()) {
                        // prefillMs (echtes TTFT): erstes nicht-leeres Delta. First-Wins.
                        if (prefillReported.compareAndSet(false, true)) {
                            val ms = (System.nanoTime() - reqStartNanos) / 1_000_000
                            runCatching { onPrefill(ms) }
                        }
                        // Antwort-Entropie (S1): optionaler Logprob des gesampelten
                        // Tokens am Frame — VOLL null-tolerant: Feld fehlt / nicht
                        // numerisch (heutiges Prod-server_e4b ohne Patch) ⇒ null,
                        // der Frame fließt byte-identisch wie bisher.
                        val lp = node.path("logprob").takeIf { it.isNumber }?.asDouble()
                        Flux.just(LlmDelta(text, logprob = lp))
                    } else {
                        Flux.empty()
                    }
                }.getOrElse { Flux.empty() }
            }
            .onErrorResume { e ->
                log.error("[mlx-brain] Stream error: {}", e.message)
                Flux.error(RuntimeException("MLX-Brain (/v1/chat) nicht erreichbar: ${e.message}", e))
            }
    }

    /**
     * Health-Check gegen den Sidecar (`GET /health`). Liefert NUR true bei
     * `{"status":"ok"}`. Während des Warmup-Fensters meldet der Brain
     * `{"status":"loading"}` mit 2xx — das ist bewusst „noch nicht bereit".
     * Bei Parse-Fehler / fehlendem Feld defensiv true (alter 2xx-Default).
     */
    fun health(): Mono<Boolean> =
        client.get().uri("/health")
            .retrieve().bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(3))
            .map { body ->
                runCatching {
                    val status = mapper.readTree(body).path("status").asText("")
                    when {
                        status.equals("ok", ignoreCase = true) -> true
                        status.equals("loading", ignoreCase = true) -> false
                        else -> true
                    }
                }.getOrDefault(true)
            }
            .defaultIfEmpty(true)
            .onErrorReturn(false)

    /**
     * EHRLICHE Modell-Messung: das `:8041/health`-JSON trägt neben `status` ein
     * `model`-Feld. `healthDetail()` exponiert ZUSÄTZLICH den GEMESSENEN
     * Modell-Namen, damit gezeigt werden kann, was wirklich geladen ist.
     */
    fun healthDetail(): Mono<MlxHealth> =
        client.get().uri("/health")
            .retrieve().bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(3))
            .map { body ->
                runCatching {
                    val node = mapper.readTree(body)
                    val status = node.path("status").asText("")
                    val ok = when {
                        status.equals("ok", ignoreCase = true) -> true
                        status.equals("loading", ignoreCase = true) -> false
                        else -> true
                    }
                    val model = node.path("model").asText("").takeIf { it.isNotBlank() }
                    if (model != null) lastMeasuredModel = model
                    MlxHealth(ok, model)
                }.getOrDefault(MlxHealth(true, null))
            }
            .defaultIfEmpty(MlxHealth(true, null))
            .onErrorReturn(MlxHealth(false, null))

    /** Zuletzt am `:8041/health` GEMESSENER Modellname, oder null bis zur ersten Antwort. */
    @Volatile
    private var lastMeasuredModel: String? = null

    /** Synchroner Zugriff auf das zuletzt gemessene MLX-Modell ([healthDetail]-Cache). */
    fun measuredModel(): String? = lastMeasuredModel
}

/**
 * Health-Detail des MLX-Sidecars — ok-Flag + GEMESSENES Modell. `model` ist der
 * rohe Name aus `:8041/health`, null wenn nicht messbar.
 */
data class MlxHealth(val ok: Boolean, val model: String?)
