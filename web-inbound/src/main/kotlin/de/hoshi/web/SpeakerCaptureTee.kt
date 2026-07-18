package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **SpeakerCaptureTee** — flag-gated Mitschnitt GENAU der Audio-Bytes, die an
 * [SpeakerIdentifyService.identify] gehen (Capture-Tee am Speaker-Identify-Rand,
 * s. [AudioWebSocketHandler]/[VoiceInboundController]).
 *
 * **WARUM:** der Offline-A/B-Runner (`tools/speaker-ab`) braucht kanal-echte Proben —
 * BYTE-IDENTISCH zum tatsächlichen Scoring-Input (Satellit `/ws/audio` vs. Browser
 * `/api/v1/voice`), nicht nachträglich rekonstruierte/rekodierte Clips. Der Tee hängt
 * sich rein LESEND an den schon fließenden identify()-Aufruf — ändert NIE die Bytes,
 * NIE das Erkennungs-Ergebnis, NIE den Turn.
 *
 * **Privacy (bewusst, Betreiber-Entscheidung):** jede Aufnahme ist ROHES biometrisches
 * Audio — inkl. jedem Gast, der zufällig mitspricht, nicht nur enrollten Personen.
 * Default OFF ([NOOP]). Nur für Testphasen scharf schalten, danach wieder leeren
 * (s. Kommentar in `tools/systemd/hoshi-0.8-backend.service`).
 */
interface SpeakerCaptureTee {
    /**
     * Best-effort/never-throw: schreibt (bei [NOOP] gar nichts) die Roh-[audioBytes] +
     * eine Meta-Zeile weg. [channel] ist die Kanal-Kennung ([CHANNEL_SATELLITE]/
     * [CHANNEL_BROWSER]), [mime] das Content-Type-Label des Aufrufers (identisch zum
     * `mime`-Argument von `identify()`).
     */
    fun capture(channel: String, audioBytes: ByteArray, mime: String)

    companion object {
        /** OFF-Sentinel: kein Dateisystem-Zugriff, byte-neutral zum heutigen Pfad. */
        val NOOP: SpeakerCaptureTee = object : SpeakerCaptureTee {
            override fun capture(channel: String, audioBytes: ByteArray, mime: String) {}
        }

        /** Kanal-Kennung: Voice-PE-Satellit über `/ws/audio`. */
        const val CHANNEL_SATELLITE = "satellit"

        /** Kanal-Kennung: Browser/FE über `/api/v1/voice`. */
        const val CHANNEL_BROWSER = "browser"
    }
}

/**
 * Die echte Ablage: EINE Roh-Datei je Aufruf (`<yyyyMMdd-HHmmss-SSS>-<kanal>.<ext>`,
 * `.wav` außer der [mime] weist erkennbar auf ein anderes Container-Format hin) +
 * EINE append-only `captures.jsonl`-Meta-Zeile (ts/kanal/mime/bytes/dateiname —
 * **KEIN Erkennungs-Ergebnis, KEIN Name**: der Tee sitzt VOR dem Scoring, kennt
 * das Ergebnis also gar nicht).
 *
 * **Fehlerverhalten (Muster [HomeEditAuditLog]):** jeder IO-Fehler (volles/nicht
 * schreibbares Verzeichnis, kaputter Pfad) wird geschluckt und als EINE WARN-Zeile
 * sichtbar gemacht — [capture] wirft NIE, die Identifikation läuft immer weiter.
 * [clock] injizierbar für deterministische Tests.
 */
class FileSpeakerCaptureTee(
    private val directory: Path,
    private val clock: Clock = Clock.systemDefaultZone(),
) : SpeakerCaptureTee {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    override fun capture(channel: String, audioBytes: ByteArray, mime: String) {
        runCatching {
            Files.createDirectories(directory)
            // Dateiname braucht ein sortier-/dateisystemsicheres Format; die Meta-Zeile
            // bekommt denselben Moment als lesbaren ISO-8601-Instant (Muster
            // [HomeEditAuditLog]/[de.hoshi.adapters.supervision.JsonlTurnTraceAdapter] — EIN
            // `now()`-Punkt, zwei Darstellungen desselben Werts).
            val now = clock.instant()
            val fileName = "${TIMESTAMP_FORMAT.format(LocalDateTime.now(clock))}-$channel.${extensionFor(mime)}"
            Files.write(
                directory.resolve(fileName),
                audioBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            appendMeta(now.toString(), channel, mime, audioBytes.size, fileName)
        }.onFailure { e ->
            log.warn(
                "[speaker-capture] Mitschnitt fehlgeschlagen (Identifikation laeuft unberuehrt weiter): {}",
                e.toString(),
            )
        }
    }

    /** EINE JSONL-Zeile — bewusst OHNE jedes Erkennungs-Feld (Tee sitzt vor dem Scoring). */
    private fun appendMeta(ts: String, channel: String, mime: String, bytes: Int, fileName: String) {
        val line = mapper.writeValueAsString(
            linkedMapOf(
                "ts" to ts,
                "kanal" to channel,
                "mime" to mime,
                "bytes" to bytes,
                "dateiname" to fileName,
            ),
        )
        Files.write(
            directory.resolve(META_FILE),
            (line + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * `.wav` ist der Default (beide heutigen Aufrufer reichen `mime="application/
     * octet-stream"` — ein bewusst inertes Transport-Label, s. [SpeakerIdentifyService]-
     * KDoc; die realen Bytes sind WAV/PCM, RIFF-Magic-erkannt). Nur wenn [mime] ein
     * ANDERES Container-Format erkennbar benennt (z.B. ein künftiger roher
     * MediaRecorder-Upload), landet die ehrliche Original-Endung auf der Platte.
     */
    private fun extensionFor(mime: String): String {
        val lower = mime.lowercase(Locale.ROOT)
        return when {
            "webm" in lower -> "webm"
            "ogg" in lower -> "ogg"
            "mpeg" in lower || "mp3" in lower -> "mp3"
            else -> "wav"
        }
    }

    companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        /** Append-only Meta-Datei, EINE Zeile pro Capture, geteilt über alle Kanäle. */
        const val META_FILE = "captures.jsonl"
    }
}
