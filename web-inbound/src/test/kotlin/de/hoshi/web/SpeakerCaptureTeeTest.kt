package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * **SpeakerCaptureTeeTest** — die reine Ablage-Naht des Speaker-Capture-Tees
 * (kein Spring, kein Netz): [SpeakerCaptureTee.NOOP] rührt das Dateisystem nie an;
 * [FileSpeakerCaptureTee] legt bei aktivem Verzeichnis GENAU eine Roh-Datei +
 * eine ergebnis-freie Meta-Zeile ab; jeder IO-Fehler wird geschluckt (kein Throw).
 */
class SpeakerCaptureTeeTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-07-19T10:15:30.123Z"), ZoneOffset.UTC)
    private val mapper = ObjectMapper()

    @Test
    fun `NOOP fasst das Dateisystem nie an`(@TempDir dir: Path) {
        assertDoesNotThrow { SpeakerCaptureTee.NOOP.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, ByteArray(10), "application/octet-stream") }
        // NOOP kennt gar kein Zielverzeichnis — der leere TempDir bleibt leer als
        // Nebenbeweis, dass an dieser Naht (Flag leer ⇒ PipelineConfig liefert NOOP)
        // strukturell nichts geschrieben werden KANN.
        assertTrue(Files.list(dir).use { it.findAny().isEmpty }, "TempDir bleibt leer")
    }

    @Test
    fun `aktives Verzeichnis erhaelt WAV-Datei und Meta-Zeile mit korrektem Kanal`(@TempDir dir: Path) {
        val tee = FileSpeakerCaptureTee(dir, clock = fixedClock)
        val audio = ByteArray(1234) { (it % 7).toByte() }

        tee.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, audio, "application/octet-stream")

        val files = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        val wavFile = files.single { it != FileSpeakerCaptureTee.META_FILE }
        assertEquals("20260719-101530-123-satellit.wav", wavFile, "Dateiname folgt <yyyyMMdd-HHmmss-SSS>-<kanal>.wav")
        assertTrue(files.contains(FileSpeakerCaptureTee.META_FILE), "captures.jsonl existiert")

        // Byte-identisch zum Scoring-Input — kein Re-Encode, keine Veränderung.
        assertTrue(audio.contentEquals(Files.readAllBytes(dir.resolve(wavFile))), "geschriebene Bytes == identify-Input")

        val metaLines = Files.readAllLines(dir.resolve(FileSpeakerCaptureTee.META_FILE))
        assertEquals(1, metaLines.size, "genau eine Meta-Zeile pro Aufruf")
        val meta = mapper.readTree(metaLines[0])
        assertEquals("2026-07-19T10:15:30.123Z", meta["ts"].asText())
        assertEquals("satellit", meta["kanal"].asText())
        assertEquals("application/octet-stream", meta["mime"].asText())
        assertEquals(1234, meta["bytes"].asInt())
        assertEquals(wavFile, meta["dateiname"].asText())
        // Datenschutz-Vertrag: KEIN Erkennungs-Ergebnis, KEIN Name in der Meta-Zeile.
        val keys = meta.fieldNames().asSequence().toSet()
        assertEquals(setOf("ts", "kanal", "mime", "bytes", "dateiname"), keys, "Meta-Zeile traegt NUR die erlaubten Felder")
    }

    @Test
    fun `zweiter Kanal und zweiter Aufruf haengt an dieselbe Meta-Datei an`(@TempDir dir: Path) {
        val tee = FileSpeakerCaptureTee(dir, clock = fixedClock)
        tee.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, ByteArray(10), "application/octet-stream")
        tee.capture(SpeakerCaptureTee.CHANNEL_BROWSER, ByteArray(20), "application/octet-stream")

        val metaLines = Files.readAllLines(dir.resolve(FileSpeakerCaptureTee.META_FILE))
        assertEquals(2, metaLines.size, "append-only ueber mehrere Aufrufe")
        assertEquals("satellit", mapper.readTree(metaLines[0])["kanal"].asText())
        assertEquals("browser", mapper.readTree(metaLines[1])["kanal"].asText())
    }

    @Test
    fun `nicht-WAV-mime ergibt die ehrliche Original-Endung`(@TempDir dir: Path) {
        val tee = FileSpeakerCaptureTee(dir, clock = fixedClock)

        tee.capture(SpeakerCaptureTee.CHANNEL_BROWSER, ByteArray(5), "audio/webm")

        val wavFile = Files.list(dir).use { it.toList() }
            .map { it.fileName.toString() }
            .single { it != FileSpeakerCaptureTee.META_FILE }
        assertTrue(wavFile.endsWith("-browser.webm"), "webm-mime ⇒ .webm statt .wav: $wavFile")
    }

    @Test
    fun `unbekanntes verschachteltes Verzeichnis wird bei Bedarf angelegt`(@TempDir dir: Path) {
        val nested = dir.resolve("noch/nicht/da")
        val tee = FileSpeakerCaptureTee(nested, clock = fixedClock)

        tee.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, ByteArray(3), "application/octet-stream")

        assertTrue(Files.isDirectory(nested), "Verzeichnis wurde angelegt")
        assertTrue(Files.list(nested).use { it.count() } >= 2, "WAV + Meta liegen im neu angelegten Verzeichnis")
    }

    @Test
    fun `IO-Fehler (Zielpfad ist eine Datei, kein Verzeichnis) wird geschluckt - kein Throw`(@TempDir dir: Path) {
        // Files.createDirectories() wirft FileAlreadyExistsException, wenn am Zielpfad
        // schon eine REGULÄRE Datei liegt — realistisches "nicht schreibbar"-Analogon.
        val blocked = dir.resolve("blocked")
        Files.write(blocked, byteArrayOf(1, 2, 3))
        val tee = FileSpeakerCaptureTee(blocked, clock = fixedClock)

        assertDoesNotThrow {
            tee.capture(SpeakerCaptureTee.CHANNEL_SATELLITE, ByteArray(10), "application/octet-stream")
        }
        // Der Fehlversuch hat die ursprüngliche Datei nicht angetastet (best-effort, kein Write).
        assertEquals(3, Files.size(blocked))
    }

    @Test
    fun `leerer HOSHI_SPEAKER_CAPTURE_DIR ergibt in PipelineConfig den NOOP-Sentinel`() {
        val bean = PipelineConfig().speakerCaptureTee(captureDir = "")
        assertTrue(bean === SpeakerCaptureTee.NOOP, "leerer Pfad ⇒ referenzgleich NOOP (kein Objekt im Pfad)")
    }

    @Test
    fun `gesetzter HOSHI_SPEAKER_CAPTURE_DIR ergibt in PipelineConfig einen echten FileSpeakerCaptureTee`(@TempDir dir: Path) {
        val bean = PipelineConfig().speakerCaptureTee(captureDir = dir.resolve("captures").toString())
        assertTrue(bean is FileSpeakerCaptureTee, "gesetzter Pfad ⇒ echte Ablage")
        assertFalse(bean === SpeakerCaptureTee.NOOP)
    }
}
