package de.hoshi.adapters.supervision

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * **JSONL-Nutzungs-Diary** (Backlog #10) — die echte [TurnTracePort]-Implementierung:
 * ein Trace pro Zeile, append-only, EINE Datei pro Tag
 * (`turn-diary-YYYY-MM-DD.jsonl` unter [directory], Default
 * `/var/lib/hoshi-0.8/`). Der North-Star („Andi-Faktor ≥ 4,2 über 14 Tage")
 * und der Nachtschicht-Lücken-Sensor (`deflected=true`-Zeilen) lesen später
 * genau diese Dateien.
 *
 * **Nie den Event-Loop blockieren** (P0-Lehre aus dem TurnOrchestrator:
 * blockierendes I/O gehört NIE auf den Reactor-Netty-Event-Loop): [record]
 * legt den Trace nur in die Queue eines EIGENEN single-thread Daemon-Executors
 * und kehrt sofort zurück. Die Queue ist bewusst begrenzt (Backpressure-Schutz);
 * läuft sie über, wird der Trace VERWORFEN — ein Diary-Eintrag ist nie wichtiger
 * als der Turn.
 *
 * **Best-effort, wirft NIE:** Serialisierung/IO-Fehler (kaputter Pfad, volle
 * Platte, fehlende Rechte) werden EINMAL pro Ursache-Klasse als WARN geloggt
 * und verschluckt — der Turn läuft immer ungestört weiter (Never-Silent
 * unberührt, exakt das EpisodicWriter-Muster).
 *
 * [clock] ist injizierbar (Tests: deterministische Tages-Datei-Wahl).
 */
class JsonlTurnTraceAdapter(
    private val directory: Path = Path.of(DEFAULT_DIRECTORY),
    private val clock: Clock = Clock.systemDefaultZone(),
) : TurnTracePort, AutoCloseable {

    private val log = LoggerFactory.getLogger(JsonlTurnTraceAdapter::class.java)
    private val mapper = ObjectMapper()

    /**
     * Genau EIN Schreib-Thread (Reihenfolge = Ankunftsreihenfolge, kein File-Lock
     * nötig), Daemon (blockiert nie den JVM-Shutdown), Queue hart auf
     * [QUEUE_CAPACITY] begrenzt mit Discard-Policy: voll ⇒ Trace leise weg,
     * NIE ein Warten am Aufrufer (= am Flux-Rand).
     */
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "turn-diary-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /** Nicht-blockierend: nur einreihen; alles Weitere passiert auf dem Writer-Thread. */
    override fun record(trace: TurnTrace) {
        try {
            executor.execute { writeLine(trace) }
        } catch (e: Exception) {
            // RejectedExecution nach close() o.ä. — der Turn ist heilig, das Diary nicht.
            log.warn("turn-diary: Trace verworfen ({})", e.toString())
        }
    }

    /** Läuft AUSSCHLIESSLICH auf dem Writer-Thread — best-effort, wirft nie. */
    private fun writeLine(trace: TurnTrace) {
        try {
            val line = serialize(trace) + "\n"
            Files.createDirectories(directory)
            Files.write(
                fileForToday(),
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            log.warn("turn-diary: Schreiben fehlgeschlagen, Trace verworfen ({})", e.toString())
        }
    }

    /**
     * Eine Zeile JSON. `ts` als ISO-8601-String (lesbar, `jq`-freundlich) statt
     * Jackson-Instant-Objekt — bewusst über eine geordnete Map serialisiert,
     * damit KEIN jsr310-Modul nötig ist und die Feld-Reihenfolge stabil bleibt.
     */
    internal fun serialize(trace: TurnTrace): String = mapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "ts" to trace.ts.toString(),
            "chatId" to trace.chatId,
            "category" to trace.category,
            "provider" to trace.provider,
            "persona" to trace.persona,
            "language" to trace.language,
            "ttftMs" to trace.ttftMs,
            "totalMs" to trace.totalMs,
            "deltaChars" to trace.deltaChars,
            "audioChunks" to trace.audioChunks,
            "speak" to trace.speak,
            "deflected" to trace.deflected,
            "error" to trace.error,
            "groundingUsed" to trace.groundingUsed,
            // Additiv ans Ende (stabile Reihenfolge der Alt-Felder): der Eingangs-Rand
            // des Turns ("chat"/"voice"; "" in Alt-Zeilen) — macht den STRICT-Entscheid
            // erstmals sehend für Voice-Turns.
            "source" to trace.source,
            // S2 räumliches Gedächtnis (additiv, Muster groundingUsed): die
            // Themen-Segment-Grenz-Felder — S4 kalibriert daraus die Schwellen.
            "segmentReset" to trace.segmentReset,
            "resetReason" to trace.resetReason,
            "segmentLenTurns" to trace.segmentLenTurns,
            // Stage-Metriken (Perf-Diary, additiv ANS ZEILENENDE — Muster source):
            // null = an der Naht nicht gemessen (nie ein erfundenes 0). Alt-Zeilen
            // ohne diese Keys bleiben gültig; der Lese-Rand (DiaryController)
            // reicht rohe Maps durch, fehlende Keys sind dort einfach abwesend.
            "sttMs" to trace.sttMs,
            "groundingMs" to trace.groundingMs,
            "brainTtftMs" to trace.brainTtftMs,
            "ttsFirstAudioMs" to trace.ttsFirstAudioMs,
            "admissionWaitMs" to trace.admissionWaitMs,
            // Antwort-Entropie (S1, additiv ANS ZEILENENDE — Muster Stage-Metriken):
            // mittlerer Surprisal in nats; null = nicht gemessen (Flag OFF / Brain
            // ohne logprobs) — nie ein erfundenes 0. S2 kalibriert daraus die
            // Abstain-Schwelle.
            "answerEntropy" to trace.answerEntropy,
            // Extended Think S4 (additiv ANS ZEILENENDE — Muster answerEntropy): die
            // Eskalations-/Cache-Diary-Felder für die S4-Kalibrier-Basis. Alt-Zeilen
            // ohne diese Keys bleiben gültig (fehlender Key ≠ null-Key).
            "escalated" to trace.escalated,
            "escalationCostCents" to trace.escalationCostCents,
            "cacheHit" to trace.cacheHit,
        ),
    )

    /** Tages-Rotation: der Dateiname folgt dem [clock]-Datum (lokale Zone der Clock). */
    internal fun fileForToday(): Path =
        directory.resolve("$FILE_PREFIX-${LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl")

    /** Flush für Tests/Shutdown: wartet kurz, bis die Queue geschrieben ist. */
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        /** Default-Ablage (konfigurierbar via Konstruktor / `HOSHI_TURN_DIARY_DIR`). */
        const val DEFAULT_DIRECTORY: String = "/var/lib/hoshi-0.8"

        /** Dateinamens-Präfix der Tages-Dateien: `turn-diary-YYYY-MM-DD.jsonl`. */
        const val FILE_PREFIX: String = "turn-diary"

        /** Hartes Queue-Limit — mehr ungeschriebene Traces als das ⇒ verwerfen statt stauen. */
        const val QUEUE_CAPACITY: Int = 1024
    }
}
