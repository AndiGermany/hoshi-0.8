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

/** Wire-Antwort auf ein erfolgreiches Enroll — bewusst OHNE Vektor. [samples] = Stand nach diesem Call. */
data class EnrollResponse(val name: String, val enrolledAt: Long, val samples: Int)

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
 * [selfCohesion] = mittlere paarweise Cosine der EIGENEN Roh-Samples eines Profils
 * (Streuung der eigenen Aufnahmen untereinander) — `null` bei genau 1 Sample (nichts zu
 * mitteln). Niedrige Werte sind ein Warnsignal: ein Anlern-Sample koennte kontaminiert/
 * verrutscht sein (Live-Befund 07.07: Cindy scorte nur 0.27..0.34 gegen ihr eigenes Profil).
 */
data class SpeakerProfileDiagnostics(val name: String, val samples: Int, val selfCohesion: Double?)

/**
 * **SpeakerController** — der Stimm-Anlern-Rand (S2, „Consent by Design"). Liegt AUTOMATISCH
 * hinter der [PerimeterWebFilter]-Wand (`/api/`-Pfad ⇒ 401 ohne Token — Biometrie NIE LAN-offen,
 * ANDI-1-Lehre). Existiert NUR bei `HOSHI_SPEAKER_ENROLL_ENABLED=true` (byte-neutral OFF: keine
 * Mappings, kein Store-File).
 *
 *  - `POST /api/v1/speakers/enroll?name=<name>[&sample=<1..9>]` — multipart, Feld `audio` =
 *    WAV (PCM16 mono) → embed (:9002) → Store → `200 {name, enrolledAt, samples}`.
 *    **Multi-Sample-Enroll (additiv am bestehenden Vertrag):** `sample` fehlt oder `=1`
 *    ⇒ [SpeakerProfileStore.upsert] — ERSETZT das Profil (byte-identisch zum heutigen
 *    Verhalten, Alt-Clients bleiben gueltig). `sample>=2` ⇒ [SpeakerProfileStore.appendSample]
 *    — haengt das Sample an, das Profil-Embedding wird zum L2-renormalisierten Mittel.
 *    Append ohne bestehendes Profil ⇒ `409` (kein stilles Anlegen — Satz 1 zuerst).
 *    Leeres/zu kurzes Audio ⇒ `422` (kein stilles Speichern); dimensionsfremdes Embedding
 *    ⇒ `422`; Sidecar liefert kein Embedding ⇒ `502` (ehrlich, kein Fake-200).
 *    Ungueltiger Name oder `sample` ausserhalb 1..9 ⇒ `400`.
 *  - `GET /api/v1/speakers` → `[{name, enrolledAt, samples}]` — **NIE Vektoren**.
 *  - `GET /api/v1/speakers/diagnostics` → [SpeakerDiagnostics] — je Profil Sample-Zahl +
 *    `selfCohesion` (eigene Samples untereinander) sowie die `crossSimilarity`-Matrix aller
 *    Profil-MITTEL. **NUR ZAHLEN, NIE Vektoren** — macht Anomalien wie „Cindys Abdruck
 *    aehnelt Andi mehr als ihr selbst" (Live-Befund 07.07) sofort ablesbar und jedes
 *    Re-Enroll direkt bewertbar.
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
        @RequestPart("audio") audioPart: FilePart,
    ): Mono<ResponseEntity<Any>> {
        val cleanName = name.trim()
        val mime = audioPart.headers().contentType?.toString() ?: "audio/wav"
        // Body IMMER erst joinen + freigeben (kein DataBuffer-Leak), DANN validieren.
        return DataBufferUtils.join(audioPart.content())
            .map { it.toBytes() }
            .flatMap { bytes ->
                when {
                    !VALID_NAME.matches(cleanName) ->
                        Mono.just(badRequest("name ungueltig (erlaubt: [A-Za-z0-9_-], 1..64)"))
                    sample != null && sample !in 1..MAX_SAMPLES ->
                        Mono.just(badRequest("sample ungueltig (erlaubt: 1..$MAX_SAMPLES)"))
                    bytes.size < MIN_AUDIO_BYTES ->
                        Mono.just(unprocessable("audio fehlt oder ist zu kurz (< $MIN_AUDIO_BYTES bytes)"))
                    else -> Mono.fromCallable { embedPort.embed(bytes, mime) }
                        .subscribeOn(Schedulers.boundedElastic())
                        .map<ResponseEntity<Any>> { emb ->
                            if (emb == null || emb.isEmpty()) {
                                log.warn("[speaker-enroll] kein Embedding fuer '{}' — Sidecar down/abgelehnt", cleanName)
                                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .body<Any>(mapOf("error" to "speaker-sidecar lieferte kein Embedding"))
                            } else {
                                persist(cleanName, sample ?: 1, emb)
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
     */
    private fun persist(name: String, sample: Int, emb: FloatArray): ResponseEntity<Any> {
        if (sample <= 1) {
            val p = store.upsert(name, emb)
            log.info("[speaker-enroll] Profil '{}' angelegt/ersetzt (dim={}, samples={})", p.name, emb.size, p.samples.size)
            return ResponseEntity.ok<Any>(EnrollResponse(p.name, p.enrolledAtEpochMs, p.samples.size))
        }
        val p = try {
            store.appendSample(name, emb)
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
        return ResponseEntity.ok<Any>(EnrollResponse(p.name, p.enrolledAtEpochMs, p.samples.size))
    }

    /** Liste — NIE Vektoren (der Store gibt bewusst nur [SpeakerSummary] heraus). */
    @GetMapping("/api/v1/speakers", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): List<SpeakerSummary> = store.list()

    /**
     * Diagnose — liest [SpeakerProfileStore.all] (INKL. Vektor) NUR intern, gibt aber
     * ausschliesslich Zahlen heraus: [SpeakerProfileDiagnostics.selfCohesion] je Profil +
     * die [SpeakerDiagnostics.crossSimilarity]-Matrix der Profil-MITTEL. Beides alphabetisch
     * nach Name sortiert (deterministisch, diff-freundlich). `similarity()` ist reine
     * Mathematik (kein I/O) ⇒ kein `boundedElastic`-Offload noetig.
     */
    @GetMapping("/api/v1/speakers/diagnostics", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun diagnostics(): SpeakerDiagnostics {
        val profiles = store.all().sortedBy { it.name }
        val summaries = profiles.map { p ->
            SpeakerProfileDiagnostics(name = p.name, samples = p.samples.size, selfCohesion = selfCohesion(p.samples))
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

    companion object {
        /** id-/Namens-Whitelist — exakt [de.hoshi.adapters.memory.EntityMemoryAdapter]s VALID_ID. */
        val VALID_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** Untergrenze fuer sinnvolles Enroll-Audio (leer/Header-only ⇒ 422, kein stilles Speichern). */
        const val MIN_AUDIO_BYTES = 1000

        /** Obergrenze fuer den `sample`-Index (FE nutzt 3; Luft fuer Experimente, aber kein Abuse). */
        const val MAX_SAMPLES = 9
    }
}
