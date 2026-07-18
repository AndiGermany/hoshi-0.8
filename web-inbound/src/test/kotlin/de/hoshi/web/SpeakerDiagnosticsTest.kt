package de.hoshi.web

import de.hoshi.core.port.SpeakerEmbedPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * **SpeakerDiagnosticsTest** — die reine Diagnose-Mathematik von [SpeakerController.diagnostics]
 * (kein Spring, kein Netz): [SpeakerProfileDiagnostics.selfCohesion] (mittlere paarweise Cosine
 * der eigenen Roh-Samples) und [SpeakerDiagnostics.crossSimilarity] (Matrix der Profil-MITTEL)
 * mathematisch festgenagelt. Der [SpeakerEmbedPort] nutzt die ECHTE
 * [SpeakerEmbedPort.similarity]-Default-Impl (reine Cosine-Mathematik); der
 * [SpeakerProfileStore] ist echt (Temp-Datei). Die "NIE Vektoren ueber das Web"-Regel wird
 * separat in [SpeakerEndpointTest] am gebooteten Context bewiesen (JSON-Shape); hier geht es
 * NUR um die Zahlen.
 */
class SpeakerDiagnosticsTest {

    /** Kein echter Sidecar-Call in diagnostics() — embed() wird nie aufgerufen, similarity() ist real. */
    private class RealSimilarityPort : SpeakerEmbedPort {
        override fun embed(audioBytes: ByteArray, mime: String): FloatArray? =
            error("diagnostics() darf den Sidecar nie aufrufen")
    }

    private fun controller(dir: Path) =
        SpeakerController(SpeakerProfileStore(dir.resolve("profiles.json")), RealSimilarityPort())

    @Test
    fun `selfCohesion - mittlere paarweise Cosine der eigenen Samples, mathematisch gepinnt`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("andi", floatArrayOf(1f, 0f)) // Sample A
        store.appendSample("andi", floatArrayOf(0f, 1f)) // Sample B (orthogonal zu A, cos=0)
        store.appendSample("andi", floatArrayOf(1f, 0f)) // Sample C == A (cos zu A == 1, zu B == 0)
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()

        // Paare: (A,B)=0.0, (A,C)=1.0, (B,C)=0.0 → Mittel = 1/3.
        val p = diag.profiles.single { it.name == "andi" }
        assertEquals(3, p.samples)
        assertEquals(1.0 / 3.0, p.selfCohesion!!, 1e-9)
    }

    @Test
    fun `selfCohesion ist null bei genau 1 Sample - nichts zu mitteln`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("bob", floatArrayOf(1f, 0f))
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()

        val p = diag.profiles.single { it.name == "bob" }
        assertEquals(1, p.samples)
        assertNull(p.selfCohesion, "1 Sample ⇒ nichts zu mitteln ⇒ null")
    }

    @Test
    fun `crossSimilarity - Matrix der Profil-MITTEL, mathematisch gepinnt inkl Diagonale`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("andi", floatArrayOf(1f, 0f))
        store.upsert("bob", floatArrayOf(0f, 1f)) // orthogonal zu andi
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()

        assertEquals(1.0, diag.crossSimilarity["andi"]!!["andi"]!!, 1e-9, "Diagonale == 1.0 (Selbst-Cosine)")
        assertEquals(1.0, diag.crossSimilarity["bob"]!!["bob"]!!, 1e-9)
        assertEquals(0.0, diag.crossSimilarity["andi"]!!["bob"]!!, 1e-9, "orthogonale Mittel ⇒ 0.0")
        assertEquals(0.0, diag.crossSimilarity["bob"]!!["andi"]!!, 1e-9, "symmetrisch")
    }

    @Test
    fun `leerer Store - diagnostics wirft nie, liefert leere Struktur`(@TempDir dir: Path) {
        val ctrl = controller(dir)

        val diag = ctrl.diagnostics()

        assertEquals(0, diag.profiles.size)
        assertEquals(0, diag.crossSimilarity.size)
    }
}
