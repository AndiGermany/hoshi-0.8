package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.Language
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFileTtsEngineStore** — der LAUFZEIT-Zustand des TTS-Engine-Settings,
 * persistiert als kleine JSON-Datei (`~/.hoshi/tts-engine.json` bzw.
 * `hoshi.tts-engine.path` / `HOSHI_TTS_ENGINE_PATH`). ZEILE FÜR ZEILE nach dem
 * [JsonFileWeatherLocationStore]-Muster: der Store selbst kennt KEINE
 * Boot-ENV-Seeds (der Aufrufer — [TtsSettingsController]/[TtsRuntimeConfig] —
 * legt `?: TtsEngineIds.canonicalOf(ttsImpl)` als Fallback davor, exakt wie
 * `stored?.label ?: seedLabel` bei `WeatherLocationController`).
 *
 * Robust per Doktrin: fehlende/kaputte/unbekannte Datei ⇒ [engineId]/[voiceFor]
 * liefern `null` (⇒ Boot-Property greift als Fallback-Default); kein LESE-Fehler
 * wirft je. Ein SCHREIB-Fehler ([setEngineId]/[setVoice]) ist NICHT schluckbar:
 * er wirft, der Cache bleibt unangetastet (persist-then-commit).
 *
 * **Stimmen-Erweiterung** (Andi-Live-Befund: „die Stimme-Sektion muss der
 * aktiven Engine folgen"): NEBEN der Engine-Wahl trägt die Datei jetzt optional
 * eine Stimme JE Engine — `{"engineId":"say","voices":{"say":"Anna"}}`. Jede
 * Engine behält ihre EIGENE gemerkte Stimme, unabhängig davon, welche Engine
 * gerade aktiv ist (ein Wechsel say→piper→say verliert die say-Stimme nicht).
 * Bestandsdateien OHNE `voices`-Feld lesen sich unverändert (leere Map, kein
 * Bruch — reines additives Feld).
 *
 * **Sprachbewusste Stimmen-Erweiterung** (Andi-Auftrag 21.07: „…dann soll das
 * TTS auch auf englisch umschwenken" — eine deutsche `say`-Stimme liest
 * englischen Text grauenhaft): die gemerkte Stimme JE Engine wird JE Sprache
 * gehalten — `{"engineId":"say","voices":{"say":{"de":"Anna","en":"Samantha"}}}`.
 * Andis Wahl für (say, DE) und (say, EN) sind komplett unabhängig; ein
 * Sprachwechsel hin und zurück verliert keine der beiden.
 *
 * **MIGRATIONS-SAUBER (Legacy-Format):** ältere Dateien tragen `voices` noch
 * als FLACHEN String je Engine (`{"say":"Anna"}`, kein Sprach-Schlüssel) — beim
 * Lesen wird so ein Eintrag als Wahl für [Language.DE] interpretiert (Andis
 * Wahl VOR dieser Naht war implizit immer Deutsch, die einzige damals gebaute
 * Sprache). Der nächste Schreibvorgang (egal auf welche Engine) persistiert
 * automatisch im neuen, verschachtelten Format — kein manueller Migrations-Schritt
 * nötig. Die 1-Parameter-Bequemlichkeits-Overloads [voiceFor]/[setVoice] bleiben
 * für Bestandsaufrufer unverändert kompilierbar und bedeuten EXAKT [Language.DE]
 * (byte-neutrale Fortsetzung des bisherigen Verhaltens).
 */
class JsonFileTtsEngineStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cachedEngineId: String? = null

    /** engineId -> (Sprach-Code -> Stimme), z.B. `"say" -> ("de" -> "Anna", "en" -> "Samantha")`. */
    @Volatile
    private var cachedVoices: Map<String, Map<String, String>> = emptyMap()

    init {
        reload()
    }

    /** Die gespeicherte Engine-Id, oder `null` wenn nie eine gesetzt wurde (⇒ Boot-Default greift). */
    fun engineId(): String? = cachedEngineId

    /** Bequemlichkeits-Overload: EXAKT [Language.DE] (Legacy-Bedeutung, byte-neutral für Bestandsaufrufer). */
    fun voiceFor(engineId: String): String? = voiceFor(engineId, Language.DE)

    /** Die gemerkte Stimme für ([engineId], [language]), oder `null` (nie gesetzt ⇒ Hint-/Factory-Default greift). */
    fun voiceFor(engineId: String, language: Language): String? = cachedVoices[engineId]?.get(language.code)

    /**
     * **Atomar setzen — persist-then-commit**: ZUERST atomar auf die Platte,
     * DANN — nur bei bewiesenem Persist — der Cache. Die gemerkten Stimmen
     * bleiben dabei unangetastet (nur das `engineId`-Feld wechselt).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setEngineId(engineId: String) {
        writeSnapshot(engineId, cachedVoices)
        cachedEngineId = engineId
    }

    /** Bequemlichkeits-Overload: merkt sich [voice] EXAKT für [Language.DE] (Legacy-Bedeutung, byte-neutral). */
    fun setVoice(engineId: String, voice: String) = setVoice(engineId, Language.DE, voice)

    /**
     * Merkt sich [voice] NUR für ([engineId], [language]) — weder die Stimmen anderer
     * Engines noch die ANDERER Sprachen DERSELBEN Engine werden angetastet (additives
     * Update, kein Ersetzen der ganzen Map). Persist-then-commit wie [setEngineId]:
     * schlägt der Schreibversuch fehl, bleibt der Cache (und damit [voiceFor]) beim
     * letzten bewiesenen Stand.
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setVoice(engineId: String, language: Language, voice: String) {
        val perEngine = (cachedVoices[engineId] ?: emptyMap()) + (language.code to voice)
        val next = cachedVoices + (engineId to perEngine)
        writeSnapshot(cachedEngineId, next)
        cachedVoices = next
    }

    private fun reload() {
        cachedEngineId = null
        cachedVoices = emptyMap()
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            val id = root.path(ENGINE_FIELD).asText("")
            if (id.isNotBlank()) cachedEngineId = id
            val voicesNode = root.path(VOICES_FIELD)
            if (voicesNode.isObject) {
                val voices = mutableMapOf<String, Map<String, String>>()
                voicesNode.fields().forEach { (engine, value) ->
                    when {
                        // LEGACY-Format: ein flacher String je Engine ⇒ Migrations-Doktrin: DE-Wahl.
                        value.isTextual -> {
                            val v = value.asText("")
                            if (v.isNotBlank()) voices[engine] = mapOf(Language.DE.code to v)
                        }
                        // NEUES Format: verschachtelt je Sprache.
                        value.isObject -> {
                            val perLang = mutableMapOf<String, String>()
                            value.fields().forEach { (langCode, langValue) ->
                                val v = langValue.asText("")
                                if (v.isNotBlank()) perLang[langCode] = v
                            }
                            if (perLang.isNotEmpty()) voices[engine] = perLang
                        }
                    }
                }
                cachedVoices = voices
            }
        }
    }

    /** [engineId] optional (`null` = noch nie gesetzt, z.B. wenn nur [setVoice] isoliert getestet wird). */
    private fun writeSnapshot(engineId: String?, voices: Map<String, Map<String, String>>) {
        val dir = path.parent ?: throw IOException("TTS-Engine-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".tts-engine", ".tmp")
        try {
            val payload = buildMap<String, Any> {
                if (engineId != null) put(ENGINE_FIELD, engineId)
                put(VOICES_FIELD, voices)
            }
            Files.write(tmp, mapper.writeValueAsBytes(payload))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Das Engine-Feld: `{"engineId":"say", …}`. */
        const val ENGINE_FIELD = "engineId"

        /** Das neue Stimmen-Feld: `{…, "voices":{"say":"Anna"}}` — je Engine EINE gemerkte Stimme. */
        const val VOICES_FIELD = "voices"
    }
}
