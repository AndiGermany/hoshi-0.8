package de.hoshi.adapters.escalation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.deOr
import de.hoshi.core.port.EscalationPort
import de.hoshi.core.port.EscalationResult
import de.hoshi.core.port.EscalationSourceRef
import de.hoshi.kernel.EgressDecision
import de.hoshi.kernel.EgressPort
import de.hoshi.kernel.SanitizedPayload
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

/**
 * **OpenAiEscalationAdapter** — die Cloud-Implementierung des [EscalationPort]
 * (Extended Think S1): `POST /v1/chat/completions`, Nano-Klasse, konservativer
 * Nachschlage-Prompt mit UNKLAR-Ausweg.
 *
 * **Web-Search-Modus ([webSearch], Andi-Auftrag 2026-07-19, video-kritisch):**
 * Default `false` = EXAKT dieser bisherige Pfad, byte-neutral. Bei `true` läuft
 * der Call stattdessen über die OpenAI **Responses API**
 * (`POST /v1/responses`, `tools: [{"type":"web_search"}]`,
 * s. [responsesApiCall]) — das Modell darf ECHT im Web suchen (statt nur sein
 * Trainingswissen wiederzugeben) und liefert echte Quellen-URLs aus den
 * `url_citation`-Annotations ([extractResponsesOutput]) statt einer
 * selbstgeschriebenen `Quelle:`-Zeile. Grund: der LiveSmoke-Beweis „Wann kommt
 * GTA 6 raus?" zeigte, dass reines Modellwissen (auch beim gründlicheren
 * gpt-5.6-Modell) ehrlich UNKLAR bleibt, statt eine aktuelle Antwort zu liefern
 * — für die Video-Szene „ich weiß es nicht — soll ich online schauen?" muss die
 * Eskalation tatsächlich nachschauen können. Schlägt der Web-Search-Pfad fehl
 * (4xx/5xx/Timeout/Parse), fällt der Call EINMAL best-effort auf den
 * `/v1/chat/completions`-Pfad zurück (Never-Silent: lieber Modellwissen als
 * Stille) — s. [responsesApiCall]-KDoc.
 *
 * **Egress-Riegel BY CONSTRUCTION (Tom, bindend):** der Konstruktor VERLANGT
 * einen [EgressPort] — es gibt keinen Weg, diesen Adapter ohne Riegel zu bauen.
 * Der komplette dynamische Request-Inhalt (Frage + optionale Schnipsel) läuft
 * als EIN Payload durch [EgressPort.guard] — UNABHÄNGIG von [webSearch]:
 *  - [EgressDecision.Blocked] ⇒ [EscalationResult.Declined] mit dem
 *    klartext-freien `auditReason` — es geht KEIN HTTP-Call raus.
 *  - [EgressDecision.Allowed] ⇒ NUR der maskierte `sanitizedText` verlässt die
 *    Box; [EgressPort.reconstruct] setzt die Masken-Token in der ANTWORT zurück.
 * Der System-Prompt ist eine statische Konstante ohne User-Daten und braucht
 * darum keinen Guard.
 *
 * **Tages-Cap (Entscheid #2, Restart-fest):** VOR jedem Call wird
 * [EscalationSpendStore.spentTodayCents] gegen [dailyCapCents] geprüft
 * (≥ Cap ⇒ [EscalationResult.CapExhausted] — H3, EHRLICH unterscheidbar von
 * [EscalationResult.Unavailable] statt derselben Netzfehler-Phrase, kein Call);
 * NACH jedem Call werden
 * die echten Kosten (Token-Counts × ca.-Preis-Tabelle) gebucht — auch bei
 * UNKLAR (bezahlt ist bezahlt).
 *
 * **Key:** [apiKey] kommt vom Aufrufer aus der Env (`OPENAI_API_KEY`, exakt der
 * [de.hoshi.adapters.tts.OpenAiTtsAdapter]-Mechanismus) und wird ausschließlich
 * als `Authorization: Bearer …`-Header verwendet. Kein Key ⇒ best-effort
 * [EscalationResult.Unavailable] + WARN, der Turn läuft weiter. Der Key-Wert
 * wird NIE geloggt; ebenso wird NIE Frage-/Antwort-Klartext geloggt (nur
 * Längen, Kategorien, Kosten).
 *
 * **Verbatim-Vertrag:** [EscalationResult.Answer.text] ist die (rekonstruierte)
 * Modell-Antwort ohne die `Quelle:`-Zeile — der Aufrufer spricht sie VERBATIM
 * (nie durch den Brain umformuliert — WikiNumber-Lehre).
 *
 * Best-Effort (Never-Silent): jeder Fehler (401, Netz, Timeout, kaputtes JSON)
 * ⇒ [EscalationResult.Unavailable], NIE eine Exception zum Aufrufer.
 */
class OpenAiEscalationAdapter(
    /** PFLICHT-Parameter ohne Default — sanitize/guard by construction. */
    private val egress: EgressPort,
    private val apiKey: String?,
    private val spendStore: EscalationSpendStore,
    /** Modell-ID (`hoshi.escalation.model`); Whitelist/Preise: [EscalationModelCatalog]. */
    private val model: String = EscalationModelCatalog.DEFAULT_MODEL_ID,
    /**
     * Web-Search-Modus — s. Klassen-KDoc. Default `false` (byte-neutraler
     * `/v1/chat/completions`-Pfad); `true` ⇒ Responses API + `web_search`-Tool
     * mit Fallback auf denselben chat/completions-Pfad bei Fehlern.
     */
    private val webSearch: Boolean = false,
    /** Tages-Cap in Cents (0,50 €/Tag, bindender Entscheid #2). */
    private val dailyCapCents: Double = DEFAULT_DAILY_CAP_CENTS,
    private val timeoutSeconds: Long = 8,
    baseUrl: String = "https://api.openai.com",
    /**
     * Obergrenze der Completion-Tokens (inkl. Reasoning bei der gpt-5-Familie).
     * Bewusst großzügig: zu knapp ⇒ Reasoning frisst das Budget und die Antwort
     * bleibt leer; die Kosten deckelt ohnehin der Tages-Cap. Gilt für BEIDE
     * Pfade (`max_completion_tokens` bzw. `max_output_tokens`).
     */
    private val maxCompletionTokens: Int = 2000,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : EscalationPort {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
        .build()

    override fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult> {
        if (query.isBlank()) return Mono.just(EscalationResult.Unavailable)
        val key = apiKey?.trim()
        if (key.isNullOrBlank()) {
            log.warn("[escalation] kein OPENAI_API_KEY — best-effort Unavailable (Turn läuft lokal weiter)")
            return Mono.just(EscalationResult.Unavailable)
        }
        val spent = spendStore.spentTodayCents()
        if (spent >= dailyCapCents) {
            log.info(
                "[escalation] Tages-Cap erreicht ({} ct von {} ct) — kein Call, CapExhausted",
                "%.2f".format(spent), "%.2f".format(dailyCapCents),
            )
            // H3: EIGENER Ausgang statt Unavailable — der Aufrufer soll Cap-Erschöpfung
            // ehrlich von einem echten Netz-/Key-Fehler unterscheiden können.
            return Mono.just(EscalationResult.CapExhausted)
        }

        // ── Egress-Riegel: der GESAMTE dynamische Request-Inhalt, EIN guard() ──
        val outbound = buildOutboundContent(query, groundingSnippets)
        val sanitized = when (val decision = egress.guard(outbound)) {
            is EgressDecision.Blocked -> {
                // Nur die Kategorie loggen — NIE den Inhalt (der bleibt ja gerade lokal).
                log.info("[escalation] Egress geblockt ({}) — kein Call", decision.category.name)
                return Mono.just(EscalationResult.Declined(decision.category.auditReason))
            }
            is EgressDecision.Allowed -> decision.payload
        }

        // webSearch=false (Default) ⇒ EXAKT der bisherige Pfad, byte-neutral. webSearch=true
        // ⇒ Responses API + echtes Web, mit best-effort Fallback auf denselben
        // chat/completions-Pfad, falls die Web-Search-Runde scheitert (Never-Silent).
        return if (webSearch) {
            responsesApiCall(key, sanitized, language)
        } else {
            chatCompletionsCall(key, sanitized, language)
        }
    }

    /**
     * Baut den EINEN dynamischen Outbound-Inhalt: die Frage, plus (nur wenn
     * vorhanden) die unzureichenden Grounding-Schnipsel. v1-Default der
     * Aufrufer: Schnipsel leer — dann geht wirklich NUR die Frage raus.
     */
    private fun buildOutboundContent(query: String, groundingSnippets: String): String {
        val q = query.trim()
        val snippets = groundingSnippets.trim()
        if (snippets.isEmpty()) return q
        return "$q\n\nUnzureichender lokaler Kontext (nur falls hilfreich):\n$snippets"
    }

    /**
     * Der v1-Nachschlage-Pfad: `POST /v1/chat/completions`, reines
     * Trainings-/Modellwissen. UNVERÄNDERT gegenüber v1 — bei `webSearch=false`
     * (Default) der EINZIGE Pfad; bei `webSearch=true` das Fallback-Ziel, falls
     * [responsesApiCall] scheitert. Best-Effort/terminal: jeder Fehler (401,
     * Netz, Timeout, kaputtes JSON) ⇒ [EscalationResult.Unavailable], wirft NIE.
     */
    private fun chatCompletionsCall(key: String, sanitized: SanitizedPayload, language: Language): Mono<EscalationResult> {
        val body = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt(language)),
                mapOf("role" to "user", "content" to sanitized.sanitizedText),
            ),
            "max_completion_tokens" to maxCompletionTokens,
        )
        return client.post().uri("/v1/chat/completions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // Buchung + Parse machen Datei-I/O — weg vom Netty-Event-Loop (P0-Lehre).
            .publishOn(Schedulers.boundedElastic())
            .map { raw -> settleAndParse(raw, sanitized.redactions) }
            // Best-Effort: jeder Fehler (401, Netz, Timeout) ⇒ Unavailable, NIE Crash.
            // Kein Klartext, kein Key in der Log-Zeile (nur e.message = Status/Ursache).
            .doOnError { e -> log.warn("[escalation] /v1/chat/completions fehlgeschlagen (best-effort): {}", e.message) }
            .onErrorReturn(EscalationResult.Unavailable)
    }

    /**
     * **Web-Search-Pfad** (s. Klassen-KDoc): `POST /v1/responses` mit
     * `tools: [{"type":"web_search"}]` — das Modell darf ECHT im Web suchen statt
     * nur sein Trainingswissen wiederzugeben. `input` trägt sinngemäß denselben
     * System-/User-Zweiklang wie [chatCompletionsCall] (nur der Transport
     * wechselt — Struktur laut
     * developers.openai.com/api/docs/guides/tools-web-search); Quellen kommen
     * aus den `url_citation`-Annotations der Antwort ([extractResponsesOutput])
     * statt aus einer selbstgeschriebenen `Quelle:`-Zeile.
     *
     * **Fallback (Never-Silent):** JEDER Fehler dieses Pfads — 4xx/5xx, Timeout,
     * kaputtes JSON (s. [settleAndParseResponses], die bei unparsbarem Body
     * bewusst wirft statt still [EscalationResult.Unavailable] zurückzugeben) —
     * fällt GENAU EINMAL auf [chatCompletionsCall] zurück (WARN-Zeile): lieber
     * reines Modellwissen als Stille. [chatCompletionsCall] ist selbst bereits
     * best-effort/terminal (eigenes `onErrorReturn`) — keine zweite
     * Fallback-Runde, keine Endlosschleife.
     */
    private fun responsesApiCall(key: String, sanitized: SanitizedPayload, language: Language): Mono<EscalationResult> {
        val body = mapOf(
            "model" to model,
            "input" to listOf(
                mapOf("role" to "system", "content" to systemPrompt(language)),
                mapOf("role" to "user", "content" to sanitized.sanitizedText),
            ),
            "tools" to listOf(mapOf("type" to "web_search")),
            "max_output_tokens" to maxCompletionTokens,
        )
        return client.post().uri("/v1/responses")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .publishOn(Schedulers.boundedElastic())
            .map { raw -> settleAndParseResponses(raw, sanitized.redactions) }
            .onErrorResume { e ->
                log.warn(
                    "[escalation] /v1/responses (web_search) fehlgeschlagen — EIN Fallback auf " +
                        "/v1/chat/completions (best-effort): {}",
                    e.message,
                )
                chatCompletionsCall(key, sanitized, language)
            }
    }

    /**
     * Bucht die echten Kosten des Calls (IMMER — auch UNKLAR ist bezahlt) und
     * mappt den Response-Body auf das [EscalationResult]. Läuft auf
     * boundedElastic; wirft nach außen nie (der umgebende onErrorReturn fängt
     * Parser-Überraschungen zusätzlich ab).
     */
    private fun settleAndParse(raw: String, redactions: Map<String, String>): EscalationResult {
        val root = runCatching { mapper.readTree(raw) }.getOrNull()
        val usage = root?.path("usage")
        val promptTokens = usage?.path("prompt_tokens")?.asInt(-1) ?: -1
        val completionTokens = usage?.path("completion_tokens")?.asInt(-1) ?: -1
        val cost = if (promptTokens >= 0 && completionTokens >= 0) {
            EscalationModelCatalog.costCents(model, promptTokens, completionTokens)
        } else {
            // Kein/kaputtes usage ⇒ ehrlich-konservativ die ca.-Lookup-Schätzung buchen
            // (der Call ist passiert und hat Geld gekostet — buchen IMMER).
            log.warn("[escalation] Antwort ohne verwertbares usage-Feld — buche ca.-Schätzung statt echter Token-Kosten")
            EscalationModelCatalog.byId(model)?.caPriceCentsPerLookup
                ?: EscalationModelCatalog.MODELS.maxOf { it.caPriceCentsPerLookup }
        }
        val spentNow = spendStore.book(cost)
        log.info(
            "[escalation] Call gebucht: {} ct (heute gesamt {} ct von {} ct)",
            "%.4f".format(cost), "%.2f".format(spentNow), "%.2f".format(dailyCapCents),
        )

        if (root == null) {
            log.warn("[escalation] Response-Body kein JSON — Unavailable (Kosten sind gebucht)")
            return EscalationResult.Unavailable
        }
        val content = root.path("choices").path(0).path("message").path("content").asText("").trim()
        if (content.isEmpty()) {
            log.warn("[escalation] leere Antwort vom Modell — Unavailable")
            return EscalationResult.Unavailable
        }
        if (content.uppercase().startsWith(UNCLEAR_MARKER)) {
            return EscalationResult.Unclear
        }

        // Masken-Token der Anfrage in der ANTWORT zurücksetzen (rein lokal).
        val reconstructed = egress.reconstruct(content, redactions)
        val (answerText, source) = splitSource(reconstructed)
        if (answerText.isBlank()) {
            log.warn("[escalation] Antwort bestand nur aus einer Quellen-Zeile — Unavailable")
            return EscalationResult.Unavailable
        }
        return EscalationResult.Answer(text = answerText, source = source, costCents = cost)
    }

    /**
     * Bucht die echten Kosten des Responses-API-Calls — Feldnamen unterscheiden
     * sich von `/v1/chat/completions` (`usage.input_tokens`/`usage.output_tokens`
     * statt `prompt_tokens`/`completion_tokens`) — und mappt auf
     * [EscalationResult]. Läuft auf boundedElastic wie [settleAndParse].
     *
     * **Web-Search-Tool-Kosten:** die Responses-API weist in `usage` (Stand
     * developers.openai.com/api/docs/guides/tools-web-search) NUR Token-Zahlen
     * aus, KEIN separates Kosten-Feld für den `web_search`-Tool-Call selbst —
     * darum bucht dieser Pfad wie der Nano-Pfad ausschließlich Token-Kosten über
     * dieselbe [EscalationModelCatalog.costCents]-Tabelle (Auftrags-Fallback
     * „sonst Token-Kosten wie bisher"). Taucht künftig ein explizites
     * Tool-Kosten-Feld im `usage`-Objekt auf, ist HIER die Erweiterungsstelle.
     *
     * **Parse-Fehler wirft bewusst** (anders als [settleAndParse], die bei
     * kaputtem JSON still [EscalationResult.Unavailable] zurückgibt): der
     * Aufrufer ([responsesApiCall]) fängt das über `onErrorResume` ab und fällt
     * auf [chatCompletionsCall] zurück (Auftrag: „Parse-Fehler ⇒ Fallback").
     */
    private fun settleAndParseResponses(raw: String, redactions: Map<String, String>): EscalationResult {
        val root = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: throw IllegalStateException("[escalation] /v1/responses: Antwort kein JSON")

        val usage = root.path("usage")
        val inputTokens = usage.path("input_tokens").asInt(-1)
        val outputTokens = usage.path("output_tokens").asInt(-1)
        val cost = if (inputTokens >= 0 && outputTokens >= 0) {
            EscalationModelCatalog.costCents(model, inputTokens, outputTokens)
        } else {
            log.warn("[escalation] Responses-Antwort ohne verwertbares usage-Feld — buche ca.-Schätzung statt echter Token-Kosten")
            EscalationModelCatalog.byId(model)?.caPriceCentsPerLookup
                ?: EscalationModelCatalog.MODELS.maxOf { it.caPriceCentsPerLookup }
        }
        val spentNow = spendStore.book(cost)
        log.info(
            "[escalation] Call gebucht (web_search): {} ct (heute gesamt {} ct von {} ct)",
            "%.4f".format(cost), "%.2f".format(spentNow), "%.2f".format(dailyCapCents),
        )

        val output = extractResponsesOutput(root)
        if (output.text.isEmpty()) {
            log.warn("[escalation] leere Responses-Antwort (web_search) — Unavailable (Kosten sind gebucht)")
            return EscalationResult.Unavailable
        }
        if (output.text.uppercase().startsWith(UNCLEAR_MARKER)) {
            return EscalationResult.Unclear
        }

        // Masken-Token der Anfrage in der ANTWORT zurücksetzen (rein lokal).
        val reconstructed = egress.reconstruct(output.text, redactions)
        val (answerText, modelSource) = splitSource(reconstructed)
        if (answerText.isBlank()) {
            log.warn("[escalation] Antwort bestand nur aus einer Quellen-Zeile — Unavailable")
            return EscalationResult.Unavailable
        }
        // Echte Quellen VOR der Modell-Selbstauskunft: url_citation-Annotations sind
        // belegbar, eine selbstgeschriebene `Quelle:`-Zeile ist es nicht (Auftrags-
        // Vorgabe „Quellen: url1, url2" statt „ohne Quellenangabe", wenn Citations da sind).
        // Quellen-Struktur-Auftrag 2026-07-21: dieser String bleibt NUR Diary-/Notiz-
        // Metadatum — er wird NIE (mehr) an [answerText] angehängt (s. TurnOrchestrator.
        // escalationOutcomeDeltas). Die belegbaren Citations reisen ZUSÄTZLICH
        // strukturiert in [EscalationResult.Answer.sources] fürs FE-„i"-Icon.
        val source = if (output.sourceRefs.isNotEmpty()) {
            "$SOURCES_PREFIX${output.sourceRefs.joinToString(", ") { it.url }}"
        } else {
            modelSource
        }
        return EscalationResult.Answer(text = answerText, source = source, costCents = cost, sources = output.sourceRefs)
    }

    /** Rückgabe von [extractResponsesOutput]: sichtbarer Text + strukturierte Quellen. */
    private data class ResponsesOutput(val text: String, val sourceRefs: List<EscalationSourceRef>)

    /**
     * Extrahiert den sichtbaren Antwort-Text UND die echten Quellen (`url_citation`-
     * Annotations, dedupliziert per URL, Reihenfolge der ersten Nennung) aus dem
     * Responses-API-`output`-Array (Struktur laut
     * developers.openai.com/api/docs/guides/tools-web-search):
     * `output: [{type:"web_search_call",...}, {type:"message", content:[{type:
     * "output_text", text, annotations:[{type:"url_citation", url, title?, ...}]}]}]`.
     * Jede URL läuft VOR dem Ablegen durch [stripTrackingParams] (Andi-Befund
     * 2026-07-20: `?utm_source=openai` u.ä. im gesprochenen/angezeigten Anhang
     * war unbrauchbar — jetzt reist die URL ohnehin nur noch strukturiert, bleibt
     * aber auch DORT sauber). Robust gegen fehlende/unerwartete Knoten
     * (übersprungen, NIE geworfen) — nur [settleAndParseResponses]s eigener
     * JSON-Parse darf werfen (Fallback-Signal für [responsesApiCall]), diese
     * Funktion nie zusätzlich.
     */
    private fun extractResponsesOutput(root: JsonNode): ResponsesOutput {
        val textBuilder = StringBuilder()
        val refs = LinkedHashMap<String, EscalationSourceRef>() // key = bereinigte URL, Insertion-Order-Dedup
        val output = root.path("output")
        if (output.isArray) {
            for (item in output) {
                if (item.path("type").asText("") != "message") continue
                val content = item.path("content")
                if (!content.isArray) continue
                for (c in content) {
                    if (c.path("type").asText("") != "output_text") continue
                    textBuilder.append(c.path("text").asText(""))
                    val annotations = c.path("annotations")
                    if (annotations.isArray) {
                        for (ann in annotations) {
                            if (ann.path("type").asText("") == "url_citation") {
                                val rawUrl = ann.path("url").asText("").trim()
                                if (rawUrl.isEmpty()) continue
                                val cleanUrl = stripTrackingParams(rawUrl)
                                val title = ann.path("title").asText("").trim().ifBlank { null }
                                refs.putIfAbsent(cleanUrl, EscalationSourceRef(title = title, url = cleanUrl))
                            }
                        }
                    }
                }
            }
        }
        return ResponsesOutput(textBuilder.toString().trim(), refs.values.toList())
    }

    /**
     * Strippt bekannte Tracking-Query-Parameter (`utm_*`, `fbclid`, `gclid`, …,
     * s. [TRACKING_PARAM_KEYS]) aus einer URL — best-effort über [java.net.URI]:
     * unparsbare URLs kommen UNVERÄNDERT zurück (nie werfen, nie eine kaputte
     * URL erzeugen). Bleiben keine Query-Parameter übrig, verschwindet das `?`
     * komplett statt einer leeren Restzeile.
     */
    private fun stripTrackingParams(rawUrl: String): String {
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return rawUrl
        val rawQuery = uri.rawQuery ?: return rawUrl
        val kept = rawQuery.split("&")
            .filter { it.isNotBlank() }
            .filterNot { param ->
                val key = param.substringBefore("=").lowercase()
                key.startsWith("utm_") || key in TRACKING_PARAM_KEYS
            }
        val newQuery = kept.joinToString("&").ifEmpty { null }
        return runCatching {
            java.net.URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, newQuery, uri.fragment).toString()
        }.getOrDefault(rawUrl)
    }

    /**
     * Trennt die letzte `Quelle:`/`Source:`-Zeile ab. Fehlt sie, trägt [source]
     * die ehrliche Modell-Attribution (Konservativ-Prompt verlangt sie zwar,
     * aber der Adapter verlässt sich nicht darauf).
     */
    private fun splitSource(text: String): Pair<String, String> {
        val lines = text.lines()
        val idx = lines.indexOfLast { SOURCE_LINE.matches(it.trim()) }
        if (idx < 0) return text.trim() to "openai/$model (ohne Quellenangabe)"
        val source = SOURCE_LINE.matchEntire(lines[idx].trim())!!.groupValues[1].trim()
        val remaining = (lines.subList(0, idx) + lines.subList(idx + 1, lines.size))
            .joinToString("\n").trim()
        return remaining to source.ifBlank { "openai/$model (ohne Quellenangabe)" }
    }

    /**
     * Konservativ-Prompt (Nachtschicht-Linie): kurze belegbare Antwort +
     * Quellen-Zeile, sonst wörtlich UNKLAR. Masken-Token bleiben unangetastet,
     * damit [EgressPort.reconstruct] sie in der Antwort zurücksetzen kann.
     * Gilt UNVERÄNDERT für BEIDE Pfade ([chatCompletionsCall]/[responsesApiCall])
     * — nur der Transport wechselt, der Prompt-Inhalt bleibt sinngemäß gleich.
     */
    private fun systemPrompt(language: Language): String = language.deOr(
        de = "Du bist ein knapper, ehrlicher Nachschlage-Dienst. Beantworte die Frage in 1 bis 3 kurzen " +
            "Sätzen auf Deutsch — nur mit Fakten, die du sicher belegen kannst. Schließe mit einer " +
            "eigenen letzten Zeile ab: \"Quelle: <knappe Quellenangabe>\". Platzhalter wie [NAME_1] " +
            "oder [URL_1] übernimmst du unverändert. Wenn du die Antwort nicht sicher weißt, " +
            "antworte ausschließlich mit dem einen Wort: UNKLAR",
        // EN + ES/FR/IT-Fallback (s. de.hoshi.core.pipeline.lang.deOr) — bis ein Übersetzer-
        // Pod eigene Sprach-Prompts liefert, bekommt jede Nicht-DE-Sprache den EN-Prompt.
        en = "You are a terse, honest lookup service. Answer the question in 1 to 3 short sentences in " +
            "English — only with facts you can reliably substantiate. End with a final line of its " +
            "own: \"Source: <brief attribution>\". Keep placeholders like [NAME_1] or [URL_1] " +
            "unchanged. If you do not reliably know the answer, reply with exactly one word: UNKLAR",
    )

    companion object {
        /** 0,50 €/Tag (bindender Orchestrator-Entscheid #2), in Cents. */
        const val DEFAULT_DAILY_CAP_CENTS: Double = 50.0

        /** Der wörtliche Ich-weiß-es-nicht-Ausweg des Konservativ-Prompts. */
        const val UNCLEAR_MARKER: String = "UNKLAR"

        /** `Quelle: …` / `Source: …` — die Quellen-Zeile der Antwort. */
        private val SOURCE_LINE = Regex("""^(?:Quelle|Source)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

        /** Präfix der echten Web-Search-Quellenliste (Auftrags-Format „Quellen: url1, url2"). */
        private const val SOURCES_PREFIX = "Quellen: "

        /**
         * Bekannte Tracking-Query-Parameter-Namen (neben dem generischen `utm_*`-
         * Präfix, s. [stripTrackingParams]) — Andi-Befund 2026-07-21: eine
         * Web-Search-Antwort trug `?utm_source=openai` im (damals noch
         * angehängten) Quellen-Text. Best-effort/nicht erschöpfend: unbekannte
         * künftige Tracker-Parameter bleiben stehen, es wird nie geraten.
         */
        private val TRACKING_PARAM_KEYS = setOf("fbclid", "gclid", "msclkid", "ref", "ref_src", "igshid")
    }
}
