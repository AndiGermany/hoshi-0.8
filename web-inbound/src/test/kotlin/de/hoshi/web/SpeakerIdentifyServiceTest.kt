package de.hoshi.web

import de.hoshi.core.port.SpeakerEmbedPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.sqrt

/**
 * **SpeakerIdentifyServiceTest** — die reine Erkennungs-Mathematik (kein Spring, kein Netz):
 * Probe → bester Cosine gegen die enrollten Profile → [Recognition]. Beweist die Vera-Regel
 * HART: über der Schwelle ⇒ Name; knapp drunter/leerer Store/Sidecar-null ⇒ Gast (nie geraten).
 *
 * Der [SpeakerEmbedPort] ist ein Hand-Fake (liefert eine gesetzte Probe ODER null); die
 * Cosine-Mathematik nutzt die ECHTE [SpeakerEmbedPort.similarity]-Default-Impl. Der
 * [SpeakerProfileStore] ist echt (Temp-Datei), damit `all()` wirklich die enrollten Vektoren trägt.
 */
class SpeakerIdentifyServiceTest {

    /** Fake-Embed: liefert für jede Probe [probe] (null ⇒ Sidecar-down/leeres Audio). similarity = echt. */
    private class FakeEmbed(private val probe: FloatArray?) : SpeakerEmbedPort {
        override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = probe
    }

    /** Einheitsvektor in 2D mit Cosine [c] gegen [1,0]: `[c, sqrt(1-c^2)]`. */
    private fun unit(c: Double): FloatArray = floatArrayOf(c.toFloat(), sqrt(1.0 - c * c).toFloat())

    private val audio = ByteArray(2000) { 1 } // nicht-leer ⇒ der Pfad läuft bis zum Embed

    private fun store(dir: Path) = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))

    @Test
    fun `Treffer ueber der Schwelle ergibt den Namen`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        // Probe cosine 1.0 zu andi, Schwelle 0.6 ⇒ sicherer Treffer.
        val svc = CosineSpeakerIdentifyService(FakeEmbed(floatArrayOf(1f, 0f)), store, threshold = 0.6)

        val rec = svc.identify(audio, "audio/wav")

        assertEquals("andi", rec.name, "über der Schwelle ⇒ Name gebunden")
        assertFalse(rec.isGuest, "Treffer ⇒ kein Gast")
        assertEquals(1.0, rec.confidence, 1e-4, "Konfidenz == bester Cosine")
    }

    @Test
    fun `knapp unter der Schwelle ergibt Gast (nie falsch zuordnen)`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        // Probe cosine 0.55 < Schwelle 0.6 ⇒ Vera-Regel: Gast, KEIN Name.
        val svc = CosineSpeakerIdentifyService(FakeEmbed(unit(0.55)), store, threshold = 0.6)

        val rec = svc.identify(audio, "audio/wav")

        assertNull(rec.name, "unter der Schwelle ⇒ NIE ein Name")
        assertTrue(rec.isGuest, "unter der Schwelle ⇒ Gast")
        assertEquals(0.55, rec.confidence, 1e-4, "Near-Miss-Score bleibt sichtbar (informativ, ohne Bindung)")
    }

    @Test
    fun `bester Treffer unter mehreren Profilen (argmax)`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        store.upsert("bob", floatArrayOf(0f, 1f))
        // Probe cosine 0.9 zu andi, 0.4359 zu bob ⇒ andi gewinnt.
        val svc = CosineSpeakerIdentifyService(FakeEmbed(unit(0.9)), store, threshold = 0.6)

        val rec = svc.identify(audio, "audio/wav")

        assertEquals("andi", rec.name, "argmax wählt das ähnlichste Profil")
        assertEquals(0.9, rec.confidence, 1e-4)
    }

    @Test
    fun `leerer Store ergibt Gast`(@TempDir dir: Path) {
        val svc = CosineSpeakerIdentifyService(FakeEmbed(floatArrayOf(1f, 0f)), store(dir), threshold = 0.6)

        val rec = svc.identify(audio, "audio/wav")

        assertNull(rec.name, "kein Profil ⇒ kein Name")
        assertTrue(rec.isGuest)
        assertEquals(0.0, rec.confidence, 1e-9, "leerer Store ⇒ Konfidenz 0")
    }

    @Test
    fun `Sidecar liefert kein Embedding ergibt Gast`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        // Embed-null (Sidecar down/leeres Audio) ⇒ best-effort Gast, kein Wurf.
        val svc = CosineSpeakerIdentifyService(FakeEmbed(null), store, threshold = 0.6)

        val rec = svc.identify(audio, "audio/wav")

        assertNull(rec.name, "kein Embedding ⇒ kein Name")
        assertTrue(rec.isGuest)
        assertEquals(0.0, rec.confidence, 1e-9)
    }

    @Test
    fun `identify-Logs nennen den BEST-Kandidaten - Treffer UND Gast-Fall`(@TempDir dir: Path) {
        val logger = org.slf4j.LoggerFactory.getLogger(CosineSpeakerIdentifyService::class.java)
            as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val store = store(dir)
            store.upsert("andi", floatArrayOf(1f, 0f))
            // Treffer (0.9 >= 0.6) und Gast (0.3 < 0.6) — beide Zeilen muessen den Kandidaten nennen,
            // sonst ist die Diagnose bei mehreren Profilen blind (Live-Befund 06.07).
            CosineSpeakerIdentifyService(FakeEmbed(unit(0.9)), store, threshold = 0.6).identify(audio, "audio/wav")
            CosineSpeakerIdentifyService(FakeEmbed(unit(0.3)), store, threshold = 0.6).identify(audio, "audio/wav")

            val lines = appender.list.map { it.formattedMessage }
            assertTrue(
                lines.any { it.contains("Treffer") && it.contains("bester Kandidat 'andi'") },
                "Treffer-Zeile nennt den Kandidaten — war: $lines",
            )
            assertTrue(
                lines.any { it.contains("Gast") && it.contains("bester Kandidat 'andi'") },
                "Gast-Zeile nennt den Near-Miss-Kandidaten — war: $lines",
            )
            assertTrue(lines.none { it.contains("[") && it.contains("1.0,") }, "NIE ein Vektor im Log")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `DISABLED-Sentinel ist aus und liefert immer Gast`() {
        val svc = SpeakerIdentifyService.DISABLED

        assertFalse(svc.enabled, "OFF ⇒ enabled=false ⇒ Controller hängt keinen Kontext an")
        val rec = svc.identify(ByteArray(2000), "audio/wav")
        assertNull(rec.name)
        assertTrue(rec.isGuest)
    }
}

/**
 * Abstands-Regel (Vera, nach Live-Fehl-Zuordnung 2026-07-07: Andis Stimme traf
 * Person Bs 1-Sample-Profil mit 0.564 und wurde FALSCH gebunden): bei mehreren
 * Profilen bindet der Service nur, wenn der beste den zweiten klar schlägt.
 */
class SpeakerIdentifyMarginTest {

    /** Fake-Port: similarity keyt auf das erste Element des Profil-Vektors. */
    private fun fakeEmbed(scores: Map<Float, Double>) = object : de.hoshi.core.port.SpeakerEmbedPort {
        override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = floatArrayOf(9f)
        override fun similarity(a: FloatArray, b: FloatArray): Double = scores[b[0]] ?: 0.0
    }

    private fun storeWith(vararg profiles: Pair<String, Float>): SpeakerProfileStore {
        val dir = java.nio.file.Files.createTempDirectory("margin-test")
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        profiles.forEach { (name, key) -> store.upsert(name, floatArrayOf(key)) }
        return store
    }

    @org.junit.jupiter.api.Test
    fun `mehrdeutiger Sieg (Paar-Stimmen im Graubereich) wird Gast, nie raten`() {
        val store = storeWith("person-b" to 2f, "andi" to 3f)
        val svc = CosineSpeakerIdentifyService(
            embedPort = fakeEmbed(mapOf(2f to 0.564, 3f to 0.52)),
            store = store, threshold = 0.45, margin = 0.10,
        )
        val r = svc.identify(byteArrayOf(1), "audio/wav")
        org.junit.jupiter.api.Assertions.assertTrue(r.isGuest, "Abstand 0.044 < 0.10 ⇒ Gast")
        org.junit.jupiter.api.Assertions.assertNull(r.name)
    }

    @org.junit.jupiter.api.Test
    fun `eindeutiger Sieg bindet weiterhin`() {
        val store = storeWith("person-b" to 2f, "andi" to 3f)
        val svc = CosineSpeakerIdentifyService(
            embedPort = fakeEmbed(mapOf(2f to 0.30, 3f to 0.58)),
            store = store, threshold = 0.45, margin = 0.10,
        )
        org.junit.jupiter.api.Assertions.assertEquals("andi", svc.identify(byteArrayOf(1), "audio/wav").name)
    }

    @org.junit.jupiter.api.Test
    fun `ein einziges Profil greift die Regel nicht (kein Zweiter)`() {
        val store = storeWith("andi" to 3f)
        val svc = CosineSpeakerIdentifyService(
            embedPort = fakeEmbed(mapOf(3f to 0.50)),
            store = store, threshold = 0.45, margin = 0.10,
        )
        org.junit.jupiter.api.Assertions.assertEquals("andi", svc.identify(byteArrayOf(1), "audio/wav").name)
    }
}

/**
 * Best-of-Sample-Matching (Vera, nach Person-B-Anomalie 07.07: ihr eigener Bestscore lag nur bei
 * 0.27..0.34 gegen das renormalisierte Profil-Mittel): der Score eines Profils ist der MAX
 * Cosine ueber dessen einzelne rohe Samples, nicht der Cosine gegen das verwaschene Mittel.
 */
class SpeakerIdentifyBestOfSampleTest {

    private fun storeAt(dir: Path) = SpeakerProfileStore(dir.resolve("profiles.json"))

    @Test
    fun `Profil mit Samples A,B - Score ist cos(B), NICHT cos(Mittel)`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("andi", floatArrayOf(1f, 0f)) // Sample A
        store.appendSample("andi", floatArrayOf(0f, 1f)) // Sample B → Mittel = renorm([0.5,0.5]) = (1/√2, 1/√2)

        // Fake-Port: similarity keyt auf das erste Element des VERGLEICHS-Vektors b (Muster wie
        // SpeakerIdentifyMarginTest). Die Map kennt NUR die beiden rohen Samples (Keys 1f/0f) —
        // fragt der Code je das Profil-Mittel (Key ≈0.7071) an, wirft es SOFORT: der Test
        // beweist damit hart, dass das Mittel gar nicht mehr angefragt wird.
        val scores = mapOf(1f to 0.10, 0f to 0.95)
        val port = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = floatArrayOf(9f, 9f)
            override fun similarity(a: FloatArray, b: FloatArray): Double =
                scores[b[0]] ?: error("unerwarteter Vergleichs-Key ${b[0]} — sollte NIE das Profil-Mittel sein")
        }
        val svc = CosineSpeakerIdentifyService(port, store, threshold = 0.5)

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("andi", rec.name, "Score 0.95 >= Schwelle 0.5 ⇒ Treffer")
        assertEquals(0.95, rec.confidence, 1e-9, "Score == cos(B)=0.95 (MAX), NICHT cos(Mittel)=0.55 (Alt-Verhalten)")
    }

    @Test
    fun `Probe naeher an A als an B - Score ist cos(A)`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("andi", floatArrayOf(1f, 0f)) // Sample A
        store.appendSample("andi", floatArrayOf(0f, 1f)) // Sample B

        val scores = mapOf(1f to 0.88, 0f to 0.20)
        val port = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = floatArrayOf(9f, 9f)
            override fun similarity(a: FloatArray, b: FloatArray): Double =
                scores[b[0]] ?: error("unerwarteter Vergleichs-Key ${b[0]} — sollte NIE das Profil-Mittel sein")
        }
        val svc = CosineSpeakerIdentifyService(port, store, threshold = 0.5)

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals(0.88, rec.confidence, 1e-9, "MAX ist symmetrisch: gewinnt A, wenn A naeher liegt")
    }

    @Test
    fun `1-Sample-Profil bleibt byte-identisch zum Alt-Verhalten (Regression)`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("andi", floatArrayOf(1f, 0f)) // GENAU 1 Sample == embedding

        // Strikte Map: nur genau der eine Sample-Key ist erlaubt — jede andere Anfrage wirft.
        val port = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray? = floatArrayOf(9f, 9f)
            override fun similarity(a: FloatArray, b: FloatArray): Double =
                if (b[0] == 1f) 0.77 else error("unerwarteter Vergleichs-Key ${b[0]}")
        }
        val svc = CosineSpeakerIdentifyService(port, store, threshold = 0.5)

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("andi", rec.name)
        assertEquals(0.77, rec.confidence, 1e-9, "MAX ueber genau 1 Sample == der eine Wert (byte-identisch)")
    }
}

/**
 * Der neue Centroid-Pfad ist opt-in. Er bewertet jedes Profil genau einmal gegen
 * das bereits normalisierte Store-Mittel; der bestehende Best-Sample-Default bleibt
 * durch die Tests oberhalb festgeschrieben.
 */
class SpeakerIdentifyCentroidTest {

    private fun storeAt(dir: Path) = SpeakerProfileStore(dir.resolve("profiles.json"))

    @Test
    fun `Centroid vergleicht gegen Profilmittel statt gegen bestes Rohsample`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        store.appendSample("andi", floatArrayOf(0f, 1f))
        val port = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray = floatArrayOf(1f, 0f)
        }
        val svc = CosineSpeakerIdentifyService(
            embedPort = port,
            store = store,
            threshold = 0.8,
            aggregation = SpeakerProfileAggregation.CENTROID,
        )

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertTrue(rec.isGuest, "Centroid-Cosine 1/sqrt(2) bleibt unter 0.8")
        assertEquals(1.0 / sqrt(2.0), rec.confidence, 1e-6)
    }

    @Test
    fun `Centroid fuehrt unabhaengig von Samplezahl genau einen Vergleich pro Profil aus`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("andi", floatArrayOf(1f, 0f))
        store.appendSample("andi", floatArrayOf(0.9f, 0.1f))
        store.appendSample("andi", floatArrayOf(0.8f, 0.2f))
        var comparisons = 0
        val port = object : SpeakerEmbedPort {
            override fun embed(audioBytes: ByteArray, mime: String): FloatArray = floatArrayOf(1f, 0f)
            override fun similarity(a: FloatArray, b: FloatArray): Double {
                comparisons++
                return 0.9
            }
        }
        val svc = CosineSpeakerIdentifyService(
            embedPort = port,
            store = store,
            threshold = 0.8,
            aggregation = SpeakerProfileAggregation.CENTROID,
        )

        assertEquals("andi", svc.identify(byteArrayOf(1), "audio/wav").name)
        assertEquals(1, comparisons, "Centroid macht genau einen Score-Versuch pro Profil")
    }

    @Test
    fun `Config-Parser akzeptiert nur dokumentierte Strategien`() {
        assertEquals(SpeakerProfileAggregation.BEST_SAMPLE, SpeakerProfileAggregation.parse("best-sample"))
        assertEquals(SpeakerProfileAggregation.BEST_SAMPLE, SpeakerProfileAggregation.parse("BEST_SAMPLE"))
        assertEquals(SpeakerProfileAggregation.TOP_TWO_MEAN, SpeakerProfileAggregation.parse("top_two_mean"))
        assertEquals(SpeakerProfileAggregation.CENTROID, SpeakerProfileAggregation.parse(" centroid "))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SpeakerProfileAggregation.parse("maximum")
        }
    }
}

/**
 * TOP_TWO_MEAN ist ein opt-in Offline-Kandidat fuer Multi-Sample-Profile: ein einzelner
 * hoher Sample-Ausreisser darf nicht allein die Identitaet bestimmen. Der Default bleibt
 * BEST_SAMPLE, bis echte Holdout-Daten die Alternative bestaetigen.
 */
class SpeakerIdentifyTopTwoMeanTest {

    private fun storeAt(dir: Path) = SpeakerProfileStore(dir.resolve("profiles.json"))

    private fun keyedPort(scores: Map<Float, Double>) = object : SpeakerEmbedPort {
        override fun embed(audioBytes: ByteArray, mime: String): FloatArray = floatArrayOf(99f)
        override fun similarity(a: FloatArray, b: FloatArray): Double =
            scores[b[0]] ?: error("unerwarteter Sample-Key ${b[0]}")
    }

    @Test
    fun `Top-Two-Mittel nutzt die zwei hoechsten Sample-Scores`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("profil-a", floatArrayOf(1f))
        store.appendSample("profil-a", floatArrayOf(2f))
        store.appendSample("profil-a", floatArrayOf(3f))
        val svc = CosineSpeakerIdentifyService(
            embedPort = keyedPort(mapOf(1f to 0.90, 2f to 0.70, 3f to 0.10)),
            store = store,
            threshold = 0.75,
            aggregation = SpeakerProfileAggregation.TOP_TWO_MEAN,
        )

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("profil-a", rec.name)
        assertEquals(0.80, rec.confidence, 1e-9, "(0.90 + 0.70) / 2; der niedrigste Score zaehlt nicht")
    }

    @Test
    fun `ein einzelner falscher Spitzenwert kann Paarentscheidung nicht allein kippen`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("profil-a", floatArrayOf(1f))
        store.appendSample("profil-a", floatArrayOf(2f))
        store.appendSample("profil-a", floatArrayOf(3f))
        store.upsert("profil-b", floatArrayOf(4f))
        store.appendSample("profil-b", floatArrayOf(5f))
        store.appendSample("profil-b", floatArrayOf(6f))
        val port = keyedPort(
            mapOf(
                1f to 0.81, 2f to 0.10, 3f to 0.05,
                4f to 0.63, 5f to 0.62, 6f to 0.20,
            ),
        )

        val bestSample = CosineSpeakerIdentifyService(
            embedPort = port,
            store = store,
            threshold = 0.45,
            margin = 0.10,
            aggregation = SpeakerProfileAggregation.BEST_SAMPLE,
        ).identify(byteArrayOf(1), "audio/wav")
        val topTwoMean = CosineSpeakerIdentifyService(
            embedPort = port,
            store = store,
            threshold = 0.45,
            margin = 0.10,
            aggregation = SpeakerProfileAggregation.TOP_TWO_MEAN,
        ).identify(byteArrayOf(1), "audio/wav")

        assertEquals("profil-a", bestSample.name, "BEST_SAMPLE folgt dem einzelnen 0.81-Ausreisser")
        assertEquals("profil-b", topTwoMean.name, "zwei konsistente Scores schlagen den Einzel-Ausreisser")
        assertEquals(0.625, topTwoMean.confidence, 1e-9)
    }

    @Test
    fun `Ein-Sample-Profil bleibt mathematisch identisch zu Best-Sample`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.upsert("profil-a", floatArrayOf(1f))
        val port = keyedPort(mapOf(1f to 0.77))

        val best = CosineSpeakerIdentifyService(
            port, store, threshold = 0.5, aggregation = SpeakerProfileAggregation.BEST_SAMPLE,
        ).identify(byteArrayOf(1), "audio/wav")
        val topTwo = CosineSpeakerIdentifyService(
            port, store, threshold = 0.5, aggregation = SpeakerProfileAggregation.TOP_TWO_MEAN,
        ).identify(byteArrayOf(1), "audio/wav")

        assertEquals(best.name, topTwo.name)
        assertEquals(best.confidence, topTwo.confidence, 1e-12)
    }
}

/**
 * Ein optionaler Profil-Scope filtert nur die Read-only-Kandidaten der Erkennung.
 * Der Store bleibt vollstaendig und der leere Default behaelt das Mehrprofil-Verhalten.
 */
class SpeakerIdentifyProfileScopeTest {

    private fun storeAt(dir: Path): SpeakerProfileStore {
        val store = SpeakerProfileStore(dir.resolve("profiles.json"))
        store.upsert("profil-a", floatArrayOf(1f))
        store.upsert("profil-b", floatArrayOf(2f))
        return store
    }

    private fun port(scores: Map<Float, Double>) = object : SpeakerEmbedPort {
        override fun embed(audioBytes: ByteArray, mime: String): FloatArray = floatArrayOf(99f)
        override fun similarity(a: FloatArray, b: FloatArray): Double =
            scores[b[0]] ?: error("unerwarteter Profil-Key ${b[0]}")
    }

    @Test
    fun `leerer Scope behaelt alle Profile und damit das Default-Verhalten`(@TempDir dir: Path) {
        val svc = CosineSpeakerIdentifyService(
            embedPort = port(mapOf(1f to 0.80, 2f to 0.60)),
            store = storeAt(dir),
            threshold = 0.45,
            margin = 0.10,
        )

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("profil-a", rec.name)
        assertEquals(0.80, rec.confidence, 1e-9)
    }

    @Test
    fun `Allowlist scored ausschliesslich das erlaubte Profil ohne Store-Mutation`(@TempDir dir: Path) {
        val store = storeAt(dir)
        val svc = CosineSpeakerIdentifyService(
            embedPort = port(mapOf(1f to 0.80, 2f to 0.60)),
            store = store,
            threshold = 0.45,
            margin = 0.10,
            allowedProfileNames = setOf("profil-b"),
        )

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("profil-b", rec.name, "profil-a ist kein Kandidat, obwohl es im Store bleibt")
        assertEquals(setOf("profil-a", "profil-b"), store.all().map { it.name }.toSet(), "Scope mutiert den Store nie")
    }

    @Test
    fun `unbekannter Allowlist-Eintrag endet fail-closed als Gast`(@TempDir dir: Path) {
        val svc = CosineSpeakerIdentifyService(
            embedPort = port(emptyMap()),
            store = storeAt(dir),
            threshold = 0.45,
            allowedProfileNames = setOf("nicht-vorhanden"),
        )

        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertTrue(rec.isGuest)
        assertNull(rec.name)
        assertEquals(0.0, rec.confidence, 1e-9)
    }

    @Test
    fun `nachtraegliche Caller-Mutation kann den Scope nicht erweitern`(@TempDir dir: Path) {
        val mutableScope = mutableSetOf("profil-b")
        val svc = CosineSpeakerIdentifyService(
            embedPort = port(mapOf(1f to 0.80, 2f to 0.60)),
            store = storeAt(dir),
            threshold = 0.45,
            allowedProfileNames = mutableScope,
        )

        mutableScope.clear()
        val rec = svc.identify(byteArrayOf(1), "audio/wav")

        assertEquals("profil-b", rec.name, "leeres Caller-Set darf nicht nachtraeglich all-profile bedeuten")
    }

    @Test
    fun `leerer oder ungetrimmter Scope-Eintrag startet nicht mit breiterer Semantik`(@TempDir dir: Path) {
        for (invalid in listOf("", " profil-a")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                CosineSpeakerIdentifyService(
                    embedPort = port(mapOf(1f to 0.80, 2f to 0.60)),
                    store = storeAt(dir),
                    threshold = 0.45,
                    allowedProfileNames = setOf(invalid),
                )
            }
        }
    }

    @Test
    fun `Scope-Readback nennt nur Anzahl und nie Profil-ID`(@TempDir dir: Path) {
        val logger = org.slf4j.LoggerFactory.getLogger(CosineSpeakerIdentifyService::class.java)
            as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            CosineSpeakerIdentifyService(
                embedPort = port(mapOf(1f to 0.80, 2f to 0.60)),
                store = storeAt(dir),
                threshold = 0.45,
                allowedProfileNames = setOf("profil-a"),
            )

            val scopeLines = appender.list.map { it.formattedMessage }.filter { it.contains("profile-scope") }
            assertTrue(scopeLines.any { it.contains("allowlist(1)") }, "Scope-Anzahl muss beweisbar sein: $scopeLines")
            assertTrue(scopeLines.none { it.contains("profil-a") }, "Readback darf keine Profil-ID tragen: $scopeLines")
        } finally {
            logger.detachAppender(appender)
        }
    }
}
