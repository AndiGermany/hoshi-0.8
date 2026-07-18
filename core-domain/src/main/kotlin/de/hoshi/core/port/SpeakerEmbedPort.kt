package de.hoshi.core.port

import kotlin.math.sqrt

/**
 * **SpeakerEmbedPort** — die Sprecher-EMBEDDING-Naht (hexagonaler Port). Roh-Audio
 * (WAV/PCM16) rein → 512-d Sprecher-Embedding raus (CAM++-Sidecar :9002). Schwester des
 * [SttPort]: dieselbe „Bytes rein"-Philosophie, aber statt Text kommt ein L2-normalisierter
 * Vektor raus, mit dem sich Sprecher wiedererkennen lassen (Enroll heute, Verify spaeter).
 *
 * **Best-Effort (never-throw):** Sidecar-Fehler/Timeout/leeres Audio ⇒ `null` statt Exception.
 * Biometrie ist KÜR, nie ein Turn-Killer. Der Aufrufer (Enroll-Rand) uebersetzt `null` in eine
 * ehrliche Absage — es wird NIE ein Nicht-Embedding still gespeichert.
 *
 * **[similarity] ist reine Mathematik (kein I/O):** Cosine zweier Embeddings. Der Sidecar
 * liefert L2-normalisierte Vektoren ⇒ Cosine == Dot-Product; die volle Cosine-Formel
 * re-normalisiert defensiv (falls doch ein un-normalisierter Vektor kommt, verzerrt die Norm
 * das Ergebnis nicht). Ungleiche/leere Groessen ⇒ `0.0` (kein Treffer, kein Wurf).
 */
interface SpeakerEmbedPort {
    /**
     * Roh-Audio → L2-normalisiertes Sprecher-Embedding (512-d), oder `null` bei
     * Sidecar-Fehler/Timeout/leerem Audio. [mime] ist der Content-Type des Audios
     * (Ehrlichkeit ueber das Empfangene) — der Sidecar erkennt WAV selbst am RIFF-Magic.
     */
    fun embed(audioBytes: ByteArray, mime: String): FloatArray?

    /** Cosine-Similarity zweier Embeddings (bei L2-normalisiert == Dot). Defensiv re-normalisiert. */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    companion object {
        /** Ehrliche „nichts"-Impl: liefert nie ein Embedding (Flag OFF / kein Sidecar). */
        val NONE: SpeakerEmbedPort = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = null
        }
    }
}
