package de.hoshi.adapters.tts

import de.hoshi.core.dto.Language
import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Live-Smoke gegen die ECHTE OpenAI-TTS-API (`/v1/audio/speech`). grün≠lebt:
 * beweist isoliert (OHNE Brain), dass der [OpenAiTtsAdapter] „Hallo, ich bin
 * Hoshi." in echte WAV-Bytes (RIFF-Header, Länge>0) verwandelt.
 *
 * Key-Auflösung (Wert wird NIE ausgegeben): zuerst `OPENAI_API_KEY` aus der Env,
 * sonst Fallback auf die Datei `~/.hoshi/openai.key` (robust gegen Gradle-Daemon-
 * Env-Propagation). Aufruf via `./gradlew :adapters-tts:run` (siehe pipeline/tts-openai.sh).
 */
fun main() {
    val model = System.getenv("HOSHI_TTS_OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini-tts"
    val voice = System.getenv("HOSHI_TTS_OPENAI_VOICE")?.takeIf { it.isNotBlank() } ?: "coral"

    val key = resolveKey()
    if (key.isNullOrBlank()) {
        System.err.println("[tts-openai] FAIL: OPENAI_API_KEY weder in der Env noch in ~/.hoshi/openai.key.")
        exitProcess(1)
    }
    // NIE den Wert ausgeben — nur Präsenz/Länge.
    println("[tts-openai] key vorhanden (Länge=${key.length}), model=$model voice=$voice")

    val adapter = OpenAiTtsAdapter(apiKey = key, model = model, voice = voice)
    val text = "Hallo, ich bin Hoshi."
    println("[tts-openai] synth: \"$text\" (lang=de) → OpenAI /v1/audio/speech ...")

    val started = System.nanoTime()
    val wav = adapter.synth(text, Language.DE).block(Duration.ofSeconds(40)) ?: ByteArray(0)
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    val riff = wav.size >= 4 &&
        wav[0] == 'R'.code.toByte() && wav[1] == 'I'.code.toByte() &&
        wav[2] == 'F'.code.toByte() && wav[3] == 'F'.code.toByte()

    println("[tts-openai] ----------------------------------------")
    println("[tts-openai] WAV-Bytes : ${wav.size}")
    println("[tts-openai] RIFF      : $riff")
    println("[tts-openai] Latenz    : ${elapsedMs} ms")
    println("[tts-openai] ----------------------------------------")

    if (wav.isEmpty() || !riff) {
        System.err.println("[tts-openai] FAIL: keine/ungültige WAV-Bytes (leer oder kein RIFF-Header).")
        exitProcess(1)
    }
    println("[tts-openai] OK — OpenAI lieferte ${wav.size} WAV-Bytes (RIFF).")
    exitProcess(0)
}

/** Env zuerst, dann ~/.hoshi/openai.key — der Wert verlässt diese Funktion nie als Log. */
private fun resolveKey(): String? {
    System.getenv("OPENAI_API_KEY")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val f = File(System.getProperty("user.home"), ".hoshi/openai.key")
    return runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
}
