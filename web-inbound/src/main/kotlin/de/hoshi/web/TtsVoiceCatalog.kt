package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.core.dto.Language
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Eine wählbare Stimme der AKTIVEN Engine (Wire-Form von `GET /api/v1/settings/tts`,
 * Feld `stimmen`). [locale]/[lizenz] sind optional — nur `say`/`piper` füllen sie
 * (OpenAI hat keine Locale-Info, Voxtral kommt hier nie an).
 */
data class TtsVoiceOption(
    val id: String,
    val label: String,
    val locale: String? = null,
    val lizenz: String? = null,
)

/** Ergebnis EINER Stimmen-Abfrage: die (ggf. gekappte) Liste + ein ehrlicher Klartext-Hinweis (leer = nichts zu sagen). */
data class TtsVoiceCatalogResult(val stimmen: List<TtsVoiceOption> = emptyList(), val hinweis: String = "")

/**
 * **TtsVoiceCatalog** — die Stimmen-Naht je Engine (Andi-Live-Befund: „die
 * Stimme-Sektion zeigt den OpenAI-Cloud-Hinweis + OpenAI-Stimmen AUCH bei
 * piper/say — sie muss der aktiven Engine folgen"). Funktionales Interface
 * (Muster [TtsEngineProbe]), damit [TtsSettingsController] ohne echtes
 * Sidecar-Netz getestet werden kann.
 */
fun interface TtsVoiceCatalog {
    /** Ehrliche Stimmen-Liste für [engineId] (eine [TtsEngineIds]-Konstante). Wirft NIE. */
    fun voicesFor(engineId: String): Mono<TtsVoiceCatalogResult>

    /**
     * **Sprachbewusste Variante** (Andi-Auftrag 21.07, Punkt c: „die sprach-
     * passenden Stimmen zuerst listen, wenn die aktive Sprache nicht DE ist").
     * Additiver Default: delegiert UNVERÄNDERT an [voicesFor] (kein Sortier-
     * Vorteil, byte-identisch) — bestehende Test-Fakes (SAM-Lambdas mit EINEM
     * Parameter) bleiben unverändert kompilierbar. NUR [HttpTtsVoiceCatalog]
     * überschreibt sie wirklich (Locale-Tags aus `/voices` nutzen).
     */
    fun voicesFor(engineId: String, language: Language): Mono<TtsVoiceCatalogResult> = voicesFor(engineId)
}

/**
 * **HttpTtsVoiceCatalog** — die EINE Live-Impl von [TtsVoiceCatalog]:
 *  - `openai`: KEIN Netz-Call — die feste, doku-verifizierte Whitelist
 *    ([OpenAiTtsAdapter.SUPPORTED_VOICES]), dieselbe Quelle wie der Adapter
 *    selbst (kein zweites, driftendes Set).
 *  - `say`: `GET {sayBaseUrl}/voices` → `{"voices":[{"name","locale","sample"}]}`
 *    (der Sidecar liefert deutsche Stimmen bereits ZUERST sortiert) — auf
 *    [MAX_VOICES] gekappt, mit Hinweis „…und N weitere" bei Überlauf.
 *  - `piper`: `GET {piperBaseUrl}/voices` → `{"voices":[{"id","locale","quality",
 *    "model_license","dataset_license"}]}` — inkl. Lizenz-Klartext (Andis
 *    Blind-Hörprobe + der finale Lizenz-/Contest-Entscheid stehen noch aus,
 *    das Feld bleibt sichtbar statt verschwiegen).
 *  - `voxtral`: bewusst (noch) keine Stimmwahl ⇒ leere Liste + ehrlicher Hinweis.
 *
 * Best-effort wie [HttpTtsEngineProbe]: JEDER Fehler (Timeout, Connection-Refused,
 * kaputtes JSON) landet in einer LEEREN Liste + Hinweis, NIE in einer Exception.
 *
 * **Sprach-Sortierung** (Andi-Auftrag 21.07, Punkt c): [voicesFor] mit [Language]
 * sortiert die Liste STABIL um — Stimmen, deren [TtsVoiceOption.locale]-Präfix
 * (`"en_US"` → `"en"`) zur aktiven Sprache passt, kommen ZUERST, der Rest bleibt
 * in seiner bisherigen Reihenfolge dahinter. Für [Language.DE] (die im Sidecar
 * ohnehin schon zuerst sortierte Standard-Sprache, s. KDoc oben) ist das ein
 * No-op. `openai` (keine Locale-Tags) und `voxtral` (leer) bleiben unberührt.
 */
class HttpTtsVoiceCatalog(
    private val sayBaseUrl: String,
    private val piperBaseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : TtsVoiceCatalog {

    override fun voicesFor(engineId: String): Mono<TtsVoiceCatalogResult> = when (engineId) {
        TtsEngineIds.OPENAI -> Mono.just(openAiVoices())
        TtsEngineIds.SAY -> fetch(sayBaseUrl, ::parseSayVoices)
        TtsEngineIds.PIPER -> fetch(piperBaseUrl, ::parsePiperVoices)
        else -> Mono.just(TtsVoiceCatalogResult(hinweis = "Stimmwahl für diese Engine kommt noch."))
    }

    override fun voicesFor(engineId: String, language: Language): Mono<TtsVoiceCatalogResult> =
        voicesFor(engineId).map { result -> prioritizeLocale(result, language) }

    /** Verschiebt Locale-passende Stimmen an den Anfang (stabil) — DE oder keine Locale-Tags ⇒ No-op. */
    private fun prioritizeLocale(result: TtsVoiceCatalogResult, language: Language): TtsVoiceCatalogResult {
        if (language == Language.DE || result.stimmen.isEmpty()) return result
        val (matching, rest) = result.stimmen.partition { voice ->
            voice.locale?.substringBefore('_')?.equals(language.code, ignoreCase = true) == true
        }
        return if (matching.isEmpty()) result else result.copy(stimmen = matching + rest)
    }

    private fun openAiVoices(): TtsVoiceCatalogResult =
        TtsVoiceCatalogResult(
            stimmen = OpenAiTtsAdapter.SUPPORTED_VOICES.sorted().map { id ->
                TtsVoiceOption(id = id, label = id.replaceFirstChar { c -> c.uppercase() })
            },
        )

    private fun fetch(baseUrl: String, parse: (JsonNode) -> List<TtsVoiceOption>): Mono<TtsVoiceCatalogResult> =
        WebClient.builder().baseUrl(baseUrl).build()
            .get().uri("/voices")
            .retrieve()
            .bodyToMono(String::class.java)
            .timeout(timeout)
            .map { body -> cap(runCatching { parse(mapper.readTree(body)) }.getOrDefault(emptyList())) }
            .onErrorResume { Mono.just(TtsVoiceCatalogResult(hinweis = "Stimmen-Liste grad nicht lesbar.")) }

    private fun cap(all: List<TtsVoiceOption>): TtsVoiceCatalogResult =
        if (all.size <= MAX_VOICES) TtsVoiceCatalogResult(stimmen = all)
        else TtsVoiceCatalogResult(stimmen = all.take(MAX_VOICES), hinweis = "…und ${all.size - MAX_VOICES} weitere")

    private fun parseSayVoices(root: JsonNode): List<TtsVoiceOption> =
        root.path("voices").mapNotNull { v ->
            val name = v.path("name").asText("")
            if (name.isBlank()) {
                null
            } else {
                TtsVoiceOption(id = name, label = name, locale = v.path("locale").asText("").ifBlank { null })
            }
        }

    private fun parsePiperVoices(root: JsonNode): List<TtsVoiceOption> =
        root.path("voices").mapNotNull { v ->
            val id = v.path("id").asText("")
            if (id.isBlank()) {
                null
            } else {
                val quality = v.path("quality").asText("")
                val modelLicense = v.path("model_license").asText("")
                val datasetLicense = v.path("dataset_license").asText("")
                TtsVoiceOption(
                    id = id,
                    label = if (quality.isBlank()) id else "$id ($quality)",
                    locale = v.path("locale").asText("").ifBlank { null },
                    lizenz = listOf(modelLicense, datasetLicense).filter { it.isNotBlank() }
                        .joinToString(" / ").ifBlank { null },
                )
            }
        }

    companion object {
        /** Kappung großer Stimmen-Kataloge (Andi-Auftrag: „auf ~25 kappen"). */
        const val MAX_VOICES = 25
    }
}
