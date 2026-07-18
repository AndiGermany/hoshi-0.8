package de.hoshi.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Ein Sprecher-Profil: Name + Profil-Embedding (512-d) + Anlege-Zeitpunkt + die ROH-Samples,
 * aus denen das Profil entstand (Multi-Sample-Enroll).
 *
 * [embedding] ist bei EINEM Sample exakt dieses Sample (Alt-Verhalten, byte-genau), ab
 * ZWEI Samples das L2-renormalisierte Mittel aller Samples — Ein-Satz-Embeddings streuen
 * zu stark (Live-Befund 06.07: same-speaker 0.27..0.58), das Mittel stabilisiert.
 */
data class SpeakerProfile(
    val name: String,
    val embedding: FloatArray,
    val enrolledAtEpochMs: Long,
    val samples: List<FloatArray> = listOf(embedding),
)

/**
 * Nach-aussen-Sicht eines Profils — bewusst OHNE Vektor. Stimm-Embeddings sind biometrische
 * Daten (Art. 9): sie verlassen den Store NIE ueber das Web (Tom-Linie). [samples] ist nur
 * die ZAHL der Roh-Aufnahmen (Diagnose/UI: „3 Saetze angelernt"), nie deren Inhalt.
 */
data class SpeakerSummary(val name: String, val enrolledAt: Long, val samples: Int = 1)

/**
 * **SpeakerProfileStore** — file-backed Registry der Sprecher-Profile, exakt das
 * [FileBackedScheduledItemStore]-Muster:
 *  - Reads ([list]/[all]/[get]) kommen billig aus einem [ConcurrentHashMap]-Cache, NIE von der
 *    Platte. Die Datei wird genau einmal beim Konstruieren gelesen und danach nur bei Mutationen
 *    atomar (Temp-File im Zielverzeichnis + Rename) neu geschrieben.
 *  - **Persist-then-commit:** [upsert]/[delete] schreiben ZUERST die gewuenschte Sicht auf die
 *    Platte und committen den Cache NUR bei bewiesenem Persist. Ein SCHREIB-Fehler wirft (kein
 *    „fake-gruen": ein Profil, das nur im RAM existiert, waere gelogen).
 *  - Ein LESE-Fehler beim Start wirft NIE: fehlende/kaputte Datei ⇒ leer starten + WARN.
 *
 * Datei-Format: JSON-Objekt
 * `{"profiles":[{"name","enrolledAtEpochMs","embedding":[…512…],"samples":[[…],[…]]}]}`.
 * `samples` ist ADDITIV (Multi-Sample-Enroll): Alt-Dateien ohne das Feld laden als
 * 1-Sample-Profil (`samples == [embedding]`) — Migration passiert implizit beim naechsten
 * [appendSample]. Legacy-tolerant: ein nacktes Array laedt ebenfalls als Profile. Einzelne
 * unbrauchbare Eintraege werden mit WARN uebersprungen, der Rest laedt.
 *
 * **Log-Disziplin (Tom):** weder Name+Vektor noch der Vektor selbst landen je in einer Log-Zeile.
 */
class SpeakerProfileStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Absolut normalisiert, damit das Temp-File IMMER im Zielverzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    private val profiles = ConcurrentHashMap<String, SpeakerProfile>()

    init {
        loadInitial()
    }

    /**
     * Legt ein Profil an oder ueberschreibt es (Schluessel = name) — persist-then-commit:
     * erst die Platte (wirft bei Fehler), dann erst der Cache. Der Vektor wird defensiv kopiert.
     *
     * Das neue Profil hat genau EIN Sample (== [embedding], byte-genau) — ein Re-Enroll
     * desselben Namens ERSETZT damit auch eine bestehende Multi-Sample-Historie (heutiges
     * Verhalten: Sample 1 = frischer Start). Weitere Samples kommen via [appendSample].
     *
     * @throws IOException wenn die Persistenz fehlschlaegt (Cache dann NICHT veraendert).
     */
    @Synchronized
    fun upsert(name: String, embedding: FloatArray, nowMs: Long = System.currentTimeMillis()): SpeakerProfile {
        val profile = SpeakerProfile(
            name = name,
            embedding = embedding.copyOf(),
            enrolledAtEpochMs = nowMs,
            samples = listOf(embedding.copyOf()),
        )
        val desired = HashMap(profiles)
        desired[name] = profile
        writeSnapshot(desired.values)
        profiles[name] = profile
        return profile
    }

    /**
     * Fuegt einem BESTEHENDEN Profil ein weiteres Roh-Sample hinzu (Multi-Sample-Enroll,
     * Satz 2..n): das Profil-Embedding wird zum **L2-renormalisierten Mittel** ALLER Samples
     * neu berechnet, [SpeakerProfile.enrolledAtEpochMs] rueckt auf jetzt (zuletzt angelernt).
     * Persist-then-commit wie [upsert].
     *
     * Unbekannter Name ⇒ `null` OHNE Datei-I/O (KEIN stilles Anlegen — der Aufrufer muss
     * mit Sample 1 via [upsert] starten; sonst entstuende ein Profil, das der Client fuer
     * vollstaendiger haelt, als es ist). Alt-Profile (1 Sample, vor Multi-Sample enrolled)
     * sind dabei voll gueltig: ihr Embedding zaehlt als Sample 1.
     *
     * @throws IllegalArgumentException wenn die Sample-Dimension nicht zum Profil passt
     *   (Cache + Platte unveraendert — kein stilles Verrechnen dimensionsfremder Vektoren).
     * @throws IOException wenn die Persistenz fehlschlaegt (Cache dann NICHT veraendert).
     */
    @Synchronized
    fun appendSample(name: String, embedding: FloatArray, nowMs: Long = System.currentTimeMillis()): SpeakerProfile? {
        val existing = profiles[name] ?: return null
        require(embedding.size == existing.embedding.size) {
            "Sample-Dimension ${embedding.size} passt nicht zum Profil (${existing.embedding.size})"
        }
        val samples = existing.samples.map { it.copyOf() } + embedding.copyOf()
        val profile = SpeakerProfile(
            name = name,
            embedding = renormalizedMean(samples),
            enrolledAtEpochMs = nowMs,
            samples = samples,
        )
        val desired = HashMap(profiles)
        desired[name] = profile
        writeSnapshot(desired.values)
        profiles[name] = profile
        return profile
    }

    /** Nach-aussen-Liste (name + enrolledAt + Sample-ZAHL), alphabetisch — NIE Vektoren (reiner Cache-Read). */
    fun list(): List<SpeakerSummary> =
        profiles.values.sortedBy { it.name }.map { SpeakerSummary(it.name, it.enrolledAtEpochMs, it.samples.size) }

    /**
     * Loescht genau ein Profil INKL. Embedding — persist-then-commit. Unbekannter Name ⇒ `false`
     * OHNE Datei-I/O.
     *
     * @throws IOException wenn die Persistenz fehlschlaegt (Cache dann NICHT veraendert).
     */
    @Synchronized
    fun delete(name: String): Boolean {
        if (!profiles.containsKey(name)) return false
        val desired = HashMap(profiles)
        desired.remove(name)
        writeSnapshot(desired.values)
        profiles.remove(name)
        return true
    }

    /**
     * Volle Profile INKL. Vektor — nur fuer die spaetere server-seitige Erkennung (S3: `/verify`
     * bekommt alle enrolled-Vektoren mit). Bewusst NICHT ueber das Web exponiert. Kopien nach
     * aussen (der interne Cache bleibt unveraenderlich).
     */
    fun all(): List<SpeakerProfile> = profiles.values.map { it.deepCopy() }

    /** Ein einzelnes Profil INKL. Vektor (interne Naht), oder `null`. Kopie nach aussen. */
    fun get(name: String): SpeakerProfile? = profiles[name]?.deepCopy()

    /** Vollkopie (Embedding + Samples) — FloatArrays sind mutabel, der Cache bleibt unantastbar. */
    private fun SpeakerProfile.deepCopy(): SpeakerProfile =
        copy(embedding = embedding.copyOf(), samples = samples.map { it.copyOf() })

    /**
     * Mittel aller [samples] (komponentenweise, double-akkumuliert), danach L2-renormalisiert —
     * das Profil bleibt damit ein Einheitsvektor wie die Sidecar-Embeddings (Cosine == Dot).
     * Degeneriert (Norm 0, Samples loeschen sich exakt aus) ⇒ das unnormierte Mittel (kein
     * Div/0; Cosine gegen den Nullvektor ist ohnehin 0.0 ⇒ Gast).
     */
    private fun renormalizedMean(samples: List<FloatArray>): FloatArray {
        val dim = samples[0].size
        val mean = DoubleArray(dim)
        for (s in samples) for (i in 0 until dim) mean[i] += s[i].toDouble()
        var normSq = 0.0
        for (i in 0 until dim) {
            mean[i] /= samples.size
            normSq += mean[i] * mean[i]
        }
        val norm = kotlin.math.sqrt(normSq)
        return FloatArray(dim) { i -> (if (norm > 0.0) mean[i] / norm else mean[i]).toFloat() }
    }

    // ── Persistenz (Muster: FileBackedScheduledItemStore) ────────────────────────

    /** Datei einmalig beim Konstruieren lesen. Fehlend ⇒ leer (still); kaputt ⇒ leer + WARN, wirft NIE. */
    private fun loadInitial() {
        if (!Files.exists(path)) return
        try {
            val root = mapper.readTree(path.toFile()) ?: return
            val arr = when {
                root.isArray -> root
                root.isObject -> root.get("profiles")
                else -> null
            }
            if (arr == null || !arr.isArray) {
                log.warn("Speaker-Profil-Datei {} hat unbekannte JSON-Form — starte leer.", path)
                return
            }
            arr.forEach { loadEntry(it) }
        } catch (e: Exception) {
            profiles.clear()
            log.warn("Speaker-Profil-Datei {} unlesbar — starte leer: {}", path, e.toString())
        }
    }

    /** Ein Profil aus dem JSON laden; unbrauchbar ⇒ WARN + ueberspringen (kein Name/Vektor im Log). */
    private fun loadEntry(node: JsonNode) {
        val name = node.get("name")?.takeIf { it.isTextual }?.textValue()
        val enrolledAt = node.get("enrolledAtEpochMs")?.takeIf { it.canConvertToLong() }?.longValue()
        val embNode = node.get("embedding")
        if (name.isNullOrBlank() || enrolledAt == null || embNode == null || !embNode.isArray || embNode.isEmpty) {
            log.warn("Ueberspringe unbrauchbaren Speaker-Profil-Eintrag in {}", path)
            return
        }
        val emb = FloatArray(embNode.size())
        for (i in 0 until embNode.size()) emb[i] = embNode.get(i).floatValue()
        profiles[name] = SpeakerProfile(
            name = name,
            embedding = emb,
            enrolledAtEpochMs = enrolledAt,
            samples = readSamples(node.get("samples"), emb),
        )
    }

    /**
     * `samples`-Feld eines Eintrags lesen — ADDITIV: fehlend (Alt-Profil, 1 Sample) oder
     * unbrauchbar ⇒ `[embedding]` (das Profil bleibt gueltig, WARN nur wenn ein kaputtes
     * Feld wirklich da war). Dimensionsfremde/kaputte Einzel-Samples fallen still raus.
     */
    private fun readSamples(node: JsonNode?, embedding: FloatArray): List<FloatArray> {
        if (node == null) return listOf(embedding)
        val fallback = listOf(embedding)
        if (!node.isArray || node.isEmpty) {
            log.warn("Unbrauchbares samples-Feld in {} — Profil laedt als 1-Sample.", path)
            return fallback
        }
        val out = ArrayList<FloatArray>(node.size())
        for (s in node) {
            if (!s.isArray || s.size() != embedding.size) continue
            out.add(FloatArray(s.size()) { i -> s.get(i).floatValue() })
        }
        if (out.isEmpty()) {
            log.warn("samples-Feld ohne brauchbares Sample in {} — Profil laedt als 1-Sample.", path)
            return fallback
        }
        return out
    }

    /**
     * Schreibt ALLE Profile als `{"profiles":[…]}`: Temp-File im Zielverzeichnis + atomarer Rename.
     * Ein SCHREIB-Fehler ist NICHT schluckbar — er WIRFT, damit die Mutation den Cache nicht
     * faelschlich committet. Aufgeraeumt wird best-effort; der urspruengliche Fehler fliegt weiter.
     * Deterministisch nach Name serialisiert (diff-freundlich).
     */
    private fun writeSnapshot(all: Collection<SpeakerProfile>) {
        val dir = path.parent ?: throw IOException("Speaker-Profil-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val root = LinkedHashMap<String, Any>()
        root["profiles"] = all.sortedBy { it.name }.map { p ->
            val entry = LinkedHashMap<String, Any>()
            entry["name"] = p.name
            entry["enrolledAtEpochMs"] = p.enrolledAtEpochMs
            entry["embedding"] = p.embedding.toList()
            // Roh-Samples IMMER mitschreiben (auch bei n=1): appendSample rechnet das Mittel
            // daraus neu; Alt-Leser ignorieren das Zusatzfeld (additiv).
            entry["samples"] = p.samples.map { it.toList() }
            entry
        }
        val tmp = Files.createTempFile(dir, ".speaker-profiles", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(root))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Atomarer Rename, mit Fallback fuer Dateisysteme ohne ATOMIC_MOVE. */
    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
