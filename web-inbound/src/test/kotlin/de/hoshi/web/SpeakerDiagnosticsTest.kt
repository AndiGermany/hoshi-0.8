package de.hoshi.web

import de.hoshi.core.port.SpeakerEmbedPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ── Auftrag A (25.07): Leave-one-out — EIN Wert PRO AUFNAHME ─────────────

    @Test
    fun `leaveOneOutSimilarity - mathematisch gepinnt gegen das Mittel der UEBRIGEN Samples`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("andi", floatArrayOf(1f, 0f)) // Sample A (Index 0)
        store.appendSample("andi", floatArrayOf(0f, 1f)) // Sample B (Index 1)
        store.appendSample("andi", floatArrayOf(1f, 0f)) // Sample C == A (Index 2)
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()
        val p = diag.profiles.single { it.name == "andi" }

        // Index 0 (A) vs Mittel(B,C) = Mittel((0,1),(1,0)) = (0.5,0.5) → renorm (1/√2,1/√2).
        //   cos(A, (1/√2,1/√2)) = 1/√2.
        // Index 1 (B) vs Mittel(A,C) = Mittel((1,0),(1,0)) = (1,0) → renorm (1,0). cos(B,(1,0)) = 0.
        // Index 2 (C) vs Mittel(A,B) = Mittel((1,0),(0,1)) = (0.5,0.5) → renorm (1/√2,1/√2).
        //   cos(C, (1/√2,1/√2)) = 1/√2 (C == A).
        val invSqrt2 = 1.0 / kotlin.math.sqrt(2.0)
        assertEquals(3, p.leaveOneOutSimilarity.size)
        assertEquals(invSqrt2, p.leaveOneOutSimilarity[0], 1e-9)
        assertEquals(0.0, p.leaveOneOutSimilarity[1], 1e-9)
        assertEquals(invSqrt2, p.leaveOneOutSimilarity[2], 1e-9)
    }

    @Test
    fun `leaveOneOutSimilarity ist leere Liste bei weniger als 2 Samples - nichts zu leaven`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("bob", floatArrayOf(1f, 0f))
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()

        assertTrue(diag.profiles.single { it.name == "bob" }.leaveOneOutSimilarity.isEmpty())
    }

    // ── Auftrag A (25.07): Fremd-Bestwerte — Sample-gegen-Sample, nicht Mittel-gegen-Mittel ──

    @Test
    fun `bestForeignSimilarity - hoechste Sample-gegen-Sample-Aehnlichkeit, mathematisch gepinnt`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        // andi hat 2 Samples, eines davon liegt (bewusst kontaminiert) NAH an bob.
        store.upsert("andi", floatArrayOf(1f, 0f))
        store.appendSample("andi", floatArrayOf(0.6f, 0.8f))
        store.upsert("bob", floatArrayOf(0f, 1f))

        val ctrl = SpeakerController(store, RealSimilarityPort())
        val diag = ctrl.diagnostics()

        // best(andi vs bob) = max(cos((1,0),(0,1)), cos((0.6,0.8),(0,1))) = max(0.0, 0.8) = 0.8.
        // Toleranz 1e-6 statt 1e-9: (0.6f,0.8f) ist NICHT exakt binaer darstellbar (anders als
        // die reinen 0/1-Vektoren in den anderen Tests) — Float32-Rundung liegt bei ~1e-7.
        val andi = diag.profiles.single { it.name == "andi" }
        assertEquals(0.8, andi.bestForeignSimilarity["bob"]!!, 1e-6)
        val bob = diag.profiles.single { it.name == "bob" }
        assertEquals(0.8, bob.bestForeignSimilarity["andi"]!!, 1e-6, "symmetrisch")

        // Der Punkt des Auftrags: bestForeignSimilarity (0.8) ist NICHT crossSimilarity
        // (Mittel-gegen-Mittel) — Andis Profil-Mittel liegt weiter von bob weg als sein
        // schlechtestes Einzel-Sample.
        val crossMeanVsMean = diag.crossSimilarity["andi"]!!["bob"]!!
        assertTrue(crossMeanVsMean < andi.bestForeignSimilarity["bob"]!!, "Mittel-Vergleich unterschaetzt die Fremd-Naehe")
    }

    @Test
    fun `bestForeignSimilarity ist leere Map bei nur einem Profil`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("andi", floatArrayOf(1f, 0f))
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()

        assertTrue(diag.profiles.single { it.name == "andi" }.bestForeignSimilarity.isEmpty())
    }

    // ── Auftrag B+C (25.07): Herkunft + Qualitaet je Aufnahme in der Diagnose ────────────────

    @Test
    fun `sampleOrigins - Herkunft und Qualitaet 1 zu 1 zu samples, nichts erfunden`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert(
            "andi", floatArrayOf(1f, 0f),
            nowMs = 1_000, session = 1, device = "browser", durationSeconds = 3.2, rms = 0.12,
        )
        store.appendSample(
            "andi", floatArrayOf(0f, 1f),
            nowMs = 2_000, session = 2, device = "voice-pe", durationSeconds = 4.1, rms = 0.20,
        )
        // Drittes Sample OHNE Herkunft/Qualitaet (Client hat sie nicht mitgeschickt) — bleibt null.
        store.appendSample("andi", floatArrayOf(1f, 0f), nowMs = 3_000)
        val ctrl = SpeakerController(store, RealSimilarityPort())

        val diag = ctrl.diagnostics()
        val origins = diag.profiles.single { it.name == "andi" }.sampleOrigins

        assertEquals(3, origins.size)
        assertEquals(1_000, origins[0].recordedAt)
        assertEquals(1, origins[0].session)
        assertEquals("browser", origins[0].device)
        assertEquals(3.2, origins[0].durationSeconds)
        assertEquals(0.12, origins[0].rms)
        assertEquals(2_000, origins[1].recordedAt)
        assertEquals("voice-pe", origins[1].device)
        assertEquals(3_000, origins[2].recordedAt)
        assertNull(origins[2].session, "nicht mitgeschickt ⇒ null, nicht erfunden")
        assertNull(origins[2].device)
        assertNull(origins[2].durationSeconds)
        assertNull(origins[2].rms)
    }
}
