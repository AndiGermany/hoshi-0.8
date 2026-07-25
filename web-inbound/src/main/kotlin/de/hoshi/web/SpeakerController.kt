package de.hoshi.web

import de.hoshi.core.port.SpeakerEmbedPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Wire-Antwort auf ein erfolgreiches Enroll — bewusst OHNE Vektor. [samples] = Stand nach
 * diesem Call. [durationSeconds]/[rms] beschreiben NUR das GERADE hochgeladene Sample (Auftrag
 * C, 25.07) — `null` wenn das WAV nicht sicher geparst werden konnte (Parser-Zweifel blockiert
 * nie, s. [SpeakerController.analyzeWav]).
 */
data class EnrollResponse(
    val name: String,
    val enrolledAt: Long,
    val samples: Int,
    val durationSeconds: Double? = null,
    val rms: Double? = null,
)

/**
 * Diagnose-Antwort — NUR ZAHLEN, NIE Vektoren (Vera-Regel: Biometrie verlaesst den Store nie,
 * auch nicht ueber diesen Rand). [crossSimilarity] ist die Cosine-Matrix zwischen allen
 * Profil-MITTELN, aussen UND innen nach Name sortiert (`name -> (andererName -> score)`,
 * inkl. Diagonale == 1.0).
 */
data class SpeakerDiagnostics(
    val profiles: List<SpeakerProfileDiagnostics>,
    val crossSimilarity: Map<String, Map<String, Double>>,
)

/**
 * Herkunft + Qualitaet EINES Roh-Samples fuer die Diagnose — Spiegel von [SpeakerSampleMeta],
 * aber ausdruecklich der Wire-Vertrag (kein Vektor, nur Zahlen/Text). Jedes Feld einzeln
 * `null`, wenn unbekannt (Alt-Datei, Client hat es nicht mitgeschickt, WAV-Parsen fehlgeschlagen)
 * — NIE erfunden.
 */
data class SpeakerSampleOrigin(
    val recordedAt: Long?,
    val session: Int?,
    val device: String?,
    val durationSeconds: Double?,
    val rms: Double?,
)

/**
 * [selfCohesion] = mittlere paarweise Cosine der EIGENEN Roh-Samples eines Profils
 * (Streuung der eigenen Aufnahmen untereinander) — `null` bei genau 1 Sample (nichts zu
 * mitteln). Niedrige Werte sind ein Warnsignal: ein Anlern-Sample koennte kontaminiert/
 * verrutscht sein (Live-Befund 07.07: Person B scorte nur 0.27..0.34 gegen ihr eigenes Profil).
 *
 * [leaveOneOutSimilarity] — Diagnose-Auftrag 25.07, DER wichtigste Teil: EIN Wert PRO AUFNAHME
 * — die Cosine dieser einen Aufnahme zum L2-renormalisierten Mittel der UEBRIGEN Aufnahmen
 * desselben Profils. Macht ein einzelnes verkorkstes Sample sichtbar, statt im Profil-Mittel
 * zu verschwinden (Index-Position == Index in [sampleOrigins]). Bei <2 Samples: leere Liste
 * (nichts zu leaven, nicht geraten).
 *
 * [bestForeignSimilarity] — je ANDEREM Profil die HOECHSTE Sample-gegen-Sample-Aehnlichkeit
 * (nicht Mittel-gegen-Mittel wie [SpeakerDiagnostics.crossSimilarity]). Das ist exakt der
 * Live-Befund vom 25.07: die hoechste Fremd-Aehnlichkeit (0.873) uebertraf jede Eigen-
 * Aehnlichkeit (max 0.722) — diese Zahl muss ohne Skript ablesbar sein.
 *
 * [sampleOrigins] — Herkunft (Sitzung/Geraet/Zeitpunkt) + Qualitaet (Dauer/RMS) je Aufnahme,
 * IMMER so lang wie [samples] (1:1 nach Index).
 */
data class SpeakerProfileDiagnostics(
    val name: String,
    val samples: Int,
    val selfCohesion: Double?,
    val leaveOneOutSimilarity: List<Double> = emptyList(),
    val bestForeignSimilarity: Map<String, Double> = emptyMap(),
    val sampleOrigins: List<SpeakerSampleOrigin> = emptyList(),
)

/**
 * **SpeakerController** — der Stimm-Anlern-Rand (S2, „Consent by Design"). Liegt AUTOMATISCH
 * hinter der [PerimeterWebFilter]-Wand (`/api/`-Pfad ⇒ 401 ohne Token — Biometrie NIE LAN-offen,
 * ANDI-1-Lehre). Existiert NUR bei `HOSHI_SPEAKER_ENROLL_ENABLED=true` (byte-neutral OFF: keine
 * Mappings, kein Store-File).
 *
 *  - `POST /api/v1/speakers/enroll?name=<name>[&sample=<1..9>][&session=<1..3>][&device=<text>]`
 *    — multipart, Feld `audio` = WAV (PCM16 mono) → embed (:9002) → Store →
 *    `200 {name, enrolledAt, samples, durationSeconds, rms}`.
 *    **Multi-Sample-Enroll (additiv am bestehenden Vertrag):** `sample` fehlt oder `=1`
 *    ⇒ [SpeakerProfileStore.upsert] — ERSETZT das Profil (byte-identisch zum heutigen
 *    Verhalten, Alt-Clients bleiben gueltig). `sample>=2` ⇒ [SpeakerProfileStore.appendSample]
 *    — haengt das Sample an, das Profil-Embedding wird zum L2-renormalisierten Mittel.
 *    Append ohne bestehendes Profil ⇒ `409` (kein stilles Anlegen — Satz 1 zuerst).
 *    **Herkunft (Auftrag B, 25.07):** `session` (1..3) und `device` (frei, `[A-Za-z0-9 _-]`,
 *    max 32 Zeichen) sind OPTIONAL und werden je Aufnahme persistiert; fehlen sie, bleibt es
 *    `null` (nie erfunden). Ungueltiges `session`/`device` ⇒ `400`.
 *    **Qualitaets-Riegel (Auftrag C, 25.07):** Netto-Dauer < [MIN_AUDIO_SECONDS] s ODER RMS
 *    unter [SILENCE_RMS_FLOOR] (praktisch stumm) ⇒ `422` — verhindert, dass eine verkorkste
 *    Aufnahme das Profil vergiftet. Scheitert das WAV-Parsen ([analyzeWav] ⇒ `null`,
 *    unerwartetes Format), greift der Riegel NICHT — ein Parser-Zweifel blockiert Andi nie,
 *    die Aufnahme geht durch wie bisher.
 *    Leeres/zu kurzes Audio (Rohbytes) ⇒ `422` (kein stilles Speichern); dimensionsfremdes
 *    Embedding ⇒ `422`; Sidecar liefert kein Embedding ⇒ `502` (ehrlich, kein Fake-200).
 *    Ungueltiger Name oder `sample` ausserhalb 1..9 ⇒ `400`.
 *  - `GET /api/v1/speakers` → `[{name, enrolledAt, samples}]` — **NIE Vektoren**.
 *  - `GET /api/v1/speakers/diagnostics` → [SpeakerDiagnostics] — je Profil Sample-Zahl,
 *    `selfCohesion` (eigene Samples untereinander), `leaveOneOutSimilarity` (EIN Wert PRO
 *    AUFNAHME gegen das Mittel der uebrigen, Auftrag A), `bestForeignSimilarity` (hoechste
 *    Sample-gegen-Sample-Aehnlichkeit je anderem Profil — der Live-Befund-Vergleich in EINER
 *    Zahl) und `sampleOrigins` (Sitzung/Geraet/Zeitpunkt/Dauer/RMS je Aufnahme) sowie die
 *    `crossSimilarity`-Matrix aller Profil-MITTEL. **NUR ZAHLEN, NIE Vektoren** — macht
 *    Anomalien wie „Person Bs Abdruck aehnelt Andi mehr als ihr selbst" (Live-Befund 07.07)
 *    sofort ablesbar und jedes Re-Enroll direkt bewertbar.
 *  - `DELETE /api/v1/speakers/{name}` → `204` (Profil + Embedding wirklich weg) / `404` / `400`.
 *
 * **Log-Disziplin (Tom):** nur `name` + Vektor-Groesse, nie Namen zusammen mit Werten, nie der
 * Vektor. Der `embed`-Aufruf ist blockierend (java.net.http) ⇒ auf [Schedulers.boundedElastic]
 * ausgelagert, nie auf dem Netty-Event-Loop.
 */
@RestController
@ConditionalOnProperty(name = ["HOSHI_SPEAKER_ENROLL_ENABLED"], havingValue = "true")
class SpeakerController(
    private val store: SpeakerProfileStore,
    private val embedPort: SpeakerEmbedPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping(
        "/api/v1/speakers/enroll",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun enroll(
        @RequestParam("name") name: String,
        @RequestParam(value = "sample", required = false) sample: Int?,
        @RequestParam(value = "session", required = false) session: Int?,
        @RequestParam(value = "device", required = false) device: String?,
        @RequestPart("audio") audioPart: FilePart,
    ): Mono<ResponseEntity<Any>> {
        val cleanName = name.trim()
        val cleanDevice = device?.trim()
        val mime = audioPart.headers().contentType?.toString() ?: "audio/wav"
        // Body IMMER erst joinen + freigeben (kein DataBuffer-Leak), DANN validieren.
        return DataBufferUtils.join(audioPart.content())
            .map { it.toBytes() }
            .flatMap { bytes ->
                // Best-effort — parst nicht ⇒ null ("unbekannt"), blockiert NIE (Auftrag C, Satz 2).
                val quality = analyzeWav(bytes)
                when {
                    !VALID_NAME.matches(cleanName) ->
                        Mono.just(badRequest("name ungueltig (erlaubt: [A-Za-z0-9_-], 1..64)"))
                    sample != null && sample !in 1..MAX_SAMPLES ->
                        Mono.just(badRequest("sample ungueltig (erlaubt: 1..$MAX_SAMPLES)"))
                    session != null && session !in 1..3 ->
                        Mono.just(badRequest("session ungueltig (erlaubt: 1..3)"))
                    cleanDevice != null && !VALID_DEVICE.matches(cleanDevice) ->
                        Mono.just(badRequest("device ungueltig (erlaubt: [A-Za-z0-9 _-], 1..32 Zeichen)"))
                    bytes.size < MIN_AUDIO_BYTES ->
                        Mono.just(unprocessable("audio fehlt oder ist zu kurz (< $MIN_AUDIO_BYTES bytes)"))
                    quality != null && quality.durationSeconds < MIN_AUDIO_SECONDS ->
                        Mono.just(
                            unprocessable(
                                "Aufnahme zu kurz (${"%.2f".format(quality.durationSeconds)}s, " +
                                    "mindestens ${MIN_AUDIO_SECONDS}s) — bitte nochmal sprechen, " +
                                    "ruhig ein paar Sekunden laenger.",
                            ),
                        )
                    quality != null && quality.rms < SILENCE_RMS_FLOOR ->
                        Mono.just(
                            unprocessable(
                                "Aufnahme fast stumm (kaum Pegel) — bitte naeher ans Mikrofon gehen " +
                                    "und nochmal deutlich hoerbar sprechen.",
                            ),
                        )
                    else -> Mono.fromCallable { embedPort.embed(bytes, mime) }
                        .subscribeOn(Schedulers.boundedElastic())
                        .map<ResponseEntity<Any>> { emb ->
                            if (emb == null || emb.isEmpty()) {
                                log.warn("[speaker-enroll] kein Embedding fuer '{}' — Sidecar down/abgelehnt", cleanName)
                                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .body<Any>(mapOf("error" to "speaker-sidecar lieferte kein Embedding"))
                            } else {
                                persist(cleanName, sample ?: 1, emb, session, cleanDevice, quality)
                            }
                        }
                }
            }
    }

    /**
     * Sample 1 (oder ohne `sample`-Param) ERSETZT das Profil — exakt das heutige Verhalten,
     * auch als frischer Start eines Multi-Sample-Enrolls (Re-Enroll ersetzt). `sample>=2`
     * fuegt an; der Index selbst wird bewusst NICHT gegen den Server-Stand abgeglichen
     * (ein Retry nach Netz-Flake soll nicht an einer Index-Pedanterie scheitern —
     * er unterscheidet nur ersetzen vs. anfuegen).
     *
     * [session]/[device]/[quality] gelten NUR fuer dieses eine Sample (Auftraege B+C, 25.07)
     * und werden 1:1 an den Store durchgereicht — [quality] `null` heisst „WAV nicht sicher
     * geparst", nicht „schlecht".
     */
    private fun persist(
        name: String,
        sample: Int,
        emb: FloatArray,
        session: Int?,
        device: String?,
        quality: WavQuality?,
    ): ResponseEntity<Any> {
        if (sample <= 1) {
            val p = store.upsert(
                name, emb,
                session = session, device = device,
                durationSeconds = quality?.durationSeconds, rms = quality?.rms,
            )
            log.info("[speaker-enroll] Profil '{}' angelegt/ersetzt (dim={}, samples={})", p.name, emb.size, p.samples.size)
            return ResponseEntity.ok<Any>(EnrollResponse(p.name, p.enrolledAtEpochMs, p.samples.size, quality?.durationSeconds, quality?.rms))
        }
        val p = try {
            store.appendSample(
                name, emb,
                session = session, device = device,
                durationSeconds = quality?.durationSeconds, rms = quality?.rms,
            )
        } catch (e: IllegalArgumentException) {
            // Dimensionsfremdes Embedding (Sidecar-Modellwechsel mitten im Enroll o.ae.) — ehrlich 422.
            log.warn("[speaker-enroll] Sample passt nicht zum Profil '{}': {}", name, e.message)
            return unprocessable("sample passt nicht zum bestehenden Profil: ${e.message}")
        }
        if (p == null) {
            log.warn("[speaker-enroll] sample={} fuer '{}' ohne bestehendes Profil — Satz 1 fehlt", sample, name)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body<Any>(mapOf("error" to "kein Profil zum Anfuegen — Satz 1 (sample=1) zuerst senden"))
        }
        log.info("[speaker-enroll] Profil '{}' erweitert (dim={}, samples={})", p.name, emb.size, p.samples.size)
        return ResponseEntity.ok<Any>(EnrollResponse(p.name, p.enrolledAtEpochMs, p.samples.size, quality?.durationSeconds, quality?.rms))
    }

    /** Liste — NIE Vektoren (der Store gibt bewusst nur [SpeakerSummary] heraus). */
    @GetMapping("/api/v1/speakers", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<SpeakerSummary> = store.list()

    /**
     * Diagnose — liest [SpeakerProfileStore.all] (INKL. Vektor) NUR intern, gibt aber
     * ausschliesslich Zahlen heraus: [SpeakerProfileDiagnostics.selfCohesion],
     * `leaveOneOutSimilarity`, `bestForeignSimilarity` und `sampleOrigins` je Profil + die
     * [SpeakerDiagnostics.crossSimilarity]-Matrix der Profil-MITTEL. Alles alphabetisch nach
     * Name sortiert (deterministisch, diff-freundlich). `similarity()` ist reine Mathematik
     * (kein I/O) ⇒ kein `boundedElastic`-Offload noetig.
     */
    @GetMapping("/api/v1/speakers/diagnostics", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun diagnostics(): SpeakerDiagnostics {
        val profiles = store.all().sortedBy { it.name }
        val summaries = profiles.map { p ->
            SpeakerProfileDiagnostics(
                name = p.name,
                samples = p.samples.size,
                selfCohesion = selfCohesion(p.samples),
                leaveOneOutSimilarity = leaveOneOut(p.samples),
                bestForeignSimilarity = bestForeignSimilarity(p, profiles),
                sampleOrigins = p.sampleMeta.map { m ->
                    SpeakerSampleOrigin(m.recordedAtEpochMs, m.session, m.device, m.durationSeconds, m.rms)
                },
            )
        }
        val cross = profiles.associate { a ->
            a.name to profiles.associate { b -> b.name to embedPort.similarity(a.embedding, b.embedding) }
        }
        return SpeakerDiagnostics(profiles = summaries, crossSimilarity = cross)
    }

    /** Mittlere paarweise Cosine der Roh-Samples EINES Profils — `null` bei 1 Sample (nichts zu mitteln). */
    private fun selfCohesion(samples: List<FloatArray>): Double? {
        if (samples.size < 2) return null
        var sum = 0.0
        var pairs = 0
        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                sum += embedPort.similarity(samples[i], samples[j])
                pairs++
            }
        }
        return sum / pairs
    }

    /**
     * Leave-one-out je Sample (Auftrag A, 25.07, DER wichtigste Teil): fuer jede Aufnahme die
     * Cosine zum L2-renormalisierten Mittel der UEBRIGEN Aufnahmen desselben Profils — macht
     * ein einzelnes verkorkstes Sample sichtbar, statt im Profil-Mittel zu verschwinden.
     * <2 Samples ⇒ leere Liste (nichts zu leaven, nicht geraten). Gleiche Formel wie
     * [SpeakerProfileStore]s renormalizedMean, hier lokal nachgebaut ([renormalizedMeanOfOthers])
     * statt den Store fuer eine reine Lese-Diagnose zu oeffnen.
     */
    private fun leaveOneOut(samples: List<FloatArray>): List<Double> {
        if (samples.size < 2) return emptyList()
        return samples.indices.map { i ->
            val others = samples.filterIndexed { j, _ -> j != i }
            embedPort.similarity(samples[i], renormalizedMeanOfOthers(others))
        }
    }

    /**
     * Mittel + L2-Renormalisierung — dieselbe Formel wie [SpeakerProfileStore]s private
     * `renormalizedMean` (bewusst hier dupliziert statt den Store zu oeffnen: reine
     * Vektor-Arithmetik, kein neues Verfahren, kein I/O). Degeneriert (Norm 0) ⇒ das
     * unnormierte Mittel (kein Div/0).
     */
    private fun renormalizedMeanOfOthers(samples: List<FloatArray>): FloatArray {
        val dim = samples[0].size
        val mean = DoubleArray(dim)
        for (s in samples) for (i in 0 until dim) mean[i] += s[i].toDouble()
        var normSq = 0.0
        for (i in 0 until dim) {
            mean[i] /= samples.size
            normSq += mean[i] * mean[i]
        }
        val norm = sqrt(normSq)
        return FloatArray(dim) { i -> (if (norm > 0.0) mean[i] / norm else mean[i]).toFloat() }
    }

    /**
     * Je ANDEREM Profil die HOECHSTE Sample-gegen-Sample-Aehnlichkeit (nicht Mittel-gegen-
     * Mittel) — der Live-Befund vom 25.07 in einer Zahl: die hoechste Fremd-Aehnlichkeit
     * uebertraf die schwaechste Eigen-Aehnlichkeit. `bestForeignSimilarity` neben
     * `leaveOneOutSimilarity` macht genau diesen Vergleich ablesbar, ohne dass jemand ein
     * Skript schreibt.
     */
    private fun bestForeignSimilarity(a: SpeakerProfile, all: List<SpeakerProfile>): Map<String, Double> =
        all.filter { it.name != a.name }.associate { b ->
            var best = Double.NEGATIVE_INFINITY
            for (sa in a.samples) {
                for (sb in b.samples) {
                    val sim = embedPort.similarity(sa, sb)
                    if (sim > best) best = sim
                }
            }
            b.name to best
        }

    @DeleteMapping("/api/v1/speakers/{name}")
    fun delete(@PathVariable("name") name: String): ResponseEntity<Void> = when {
        !VALID_NAME.matches(name) -> ResponseEntity.badRequest().build()
        store.delete(name) -> ResponseEntity.noContent().build()
        else -> ResponseEntity.notFound().build()
    }

    private fun badRequest(msg: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body<Any>(mapOf("error" to msg))

    private fun unprocessable(msg: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body<Any>(mapOf("error" to msg))

    /** Liest einen (zusammengefuegten) [DataBuffer] in ein ByteArray und gibt ihn frei. */
    private fun DataBuffer.toBytes(): ByteArray {
        val bytes = ByteArray(readableByteCount())
        read(bytes)
        DataBufferUtils.release(this)
        return bytes
    }

    /** Netto-Dauer (Sekunden) + RMS-Pegel (0..1, Vollausschlag=1.0) EINES Samples (Auftrag C). */
    private data class WavQuality(val durationSeconds: Double, val rms: Double)

    /**
     * Best-effort PCM16-WAV-Analyse: Netto-Dauer (s, Laenge der `data`-Chunk ueber alle Kanaele)
     * und RMS-Pegel (0..1, Int16-Vollausschlag = 1.0). **Reines Parsen, wirft NIE** — jedes
     * unerwartete Format (kein RIFF/WAVE, kein PCM, kein 16-bit, kaputte/fehlende Chunks,
     * sampleRate/channels <= 0) liefert `null` ("unbekannt"). Auftrag C, Satz 2: ein
     * Parser-Zweifel darf das Enroll nie blockieren — bei `null` greift KEIN Qualitaets-Riegel,
     * die Aufnahme geht durch wie vor diesem Auftrag.
     */
    private fun analyzeWav(bytes: ByteArray): WavQuality? {
        try {
            if (bytes.size < 44) return null
            fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
            if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            var offset = 12
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var dataOffset = -1
            var dataSize = 0
            while (offset + 8 <= bytes.size) {
                val chunkId = tag(offset)
                val chunkSize = buf.getInt(offset + 4)
                if (chunkSize < 0) break // korrupt/riesig — lieber abbrechen als Endlos-/OOB-Risiko
                val bodyOffset = offset + 8
                when (chunkId) {
                    "fmt " -> if (bodyOffset + 16 <= bytes.size) {
                        audioFormat = buf.getShort(bodyOffset).toInt() and 0xFFFF
                        channels = buf.getShort(bodyOffset + 2).toInt() and 0xFFFF
                        sampleRate = buf.getInt(bodyOffset + 4)
                        bitsPerSample = buf.getShort(bodyOffset + 14).toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = bodyOffset
                        dataSize = chunkSize
                    }
                }
                offset = bodyOffset + chunkSize + (chunkSize % 2) // Chunks sind wortweise (gerade) gepolstert
            }
            if (dataOffset < 0 || sampleRate <= 0 || channels <= 0 || bitsPerSample != 16 || audioFormat != 1) {
                return null
            }

            val safeSize = minOf(dataSize, bytes.size - dataOffset).let { it - (it % 2) }
            if (safeSize <= 0) return null
            val sampleCount = safeSize / 2
            var sumSq = 0.0
            for (i in 0 until sampleCount) {
                val norm = buf.getShort(dataOffset + i * 2) / 32768.0
                sumSq += norm * norm
            }
            val rms = sqrt(sumSq / sampleCount)
            val frames = sampleCount / channels
            val duration = frames.toDouble() / sampleRate.toDouble()
            return WavQuality(duration, rms)
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        /** id-/Namens-Whitelist — exakt [de.hoshi.adapters.memory.EntityMemoryAdapter]s VALID_ID. */
        val VALID_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** `device`-Whitelist (Auftrag B, 25.07) — frei lesbarer Text, kein Pfad-/Injection-Risiko. */
        val VALID_DEVICE = Regex("^[A-Za-z0-9 _-]{1,32}$")

        /** Untergrenze fuer sinnvolles Enroll-Audio (leer/Header-only ⇒ 422, kein stilles Speichern). */
        const val MIN_AUDIO_BYTES = 1000

        /** Obergrenze fuer den `sample`-Index (FE nutzt 3; Luft fuer Experimente, aber kein Abuse). */
        const val MAX_SAMPLES = 9

        /**
         * Mindest-Nettodauer eines Enroll-Samples in Sekunden (Auftrag C). Unter dieser Schwelle
         * ist ein 512-d-Sprecher-Embedding statistisch nicht tragfaehig — lieber ehrlich `422`
         * mit klarer Ansage als ein verkorkstes Sample im Profil (genau das war der Live-Befund
         * vom 25.07: eine kontaminierte Aufnahme, nicht auseinandergehalten von einer echten).
         */
        const val MIN_AUDIO_SECONDS = 1.0

        /**
         * RMS-Boden fuer „praktisch stumm" (0..1, Vollausschlag=1.0; -50 dBFS). Begruendung:
         * typische Sprachaufnahmen liegen RMS-seitig zwischen ca. -35 und -15 dBFS (auch leise,
         * aber hoerbare Sprache bleibt darueber); reines digitales Grundrauschen/Stille liegt
         * ueblicherweise unter -60 dBFS. -50 dBFS (RMS ≈0.003) liegt sicher dazwischen: sicher
         * unter jeder hoerbaren Sprache, sicher ueber reiner Stille — kein willkuerlicher Wert,
         * aber bewusst KEIN Ersatz fuer eine echte VAD (nur ein Riegel gegen leere/kaputte Takes).
         */
        const val SILENCE_RMS_FLOOR = 0.003
    }
}
