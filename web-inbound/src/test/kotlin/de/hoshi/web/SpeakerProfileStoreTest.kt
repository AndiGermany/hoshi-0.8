package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beweist den [SpeakerProfileStore] (Enroll-Persistenz ueber den Backend-Neustart):
 *  - Roundtrip ueber „Neustart" (neue Instanz, gleiche Datei) inkl. Vektor + Zeitstempel;
 *  - [SpeakerProfileStore.list] gibt NIE Vektoren heraus (nur name + enrolledAt);
 *  - upsert desselben Namens ueberschreibt (auch persistent), delete raeumt wirklich;
 *  - fehlende/kaputte Datei ⇒ leer starten, KEIN Crash, keine Datei angelegt;
 *  - persist-then-commit: Persist-Fehler ⇒ upsert wirft, der Cache bleibt unangetastet;
 *  - Multi-Sample-Enroll: [SpeakerProfileStore.appendSample] liefert das mathematisch
 *    festgenagelte L2-renormalisierte Mittel, Alt-Profile (ohne samples-Feld) bleiben
 *    gueltig und zaehlen als 1 Sample, Re-Enroll (upsert) ersetzt die Sample-Historie.
 */
class SpeakerProfileStoreTest {

    private fun vec(vararg v: Float) = floatArrayOf(*v)

    // ── Roundtrip über „Neustart" ────────────────────────────────────────────

    @Test
    fun `Neustart - neue Instanz auf gleicher Datei sieht Profil inkl Vektor und Zeitstempel`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        store.upsert("andi", vec(0.1f, 0.2f, 0.3f), nowMs = 1_000)
        store.upsert("partnerin", vec(0.9f, 0.8f, 0.7f), nowMs = 2_000)

        val reloaded = SpeakerProfileStore(file) // „Backend-Neustart"
        assertEquals(listOf("andi", "partnerin"), reloaded.list().map { it.name }, "alphabetisch")
        val andi = reloaded.get("andi")!!
        assertArrayEquals(vec(0.1f, 0.2f, 0.3f), andi.embedding, 1e-6f, "Vektor ueberlebt byte-genau")
        assertEquals(1_000, andi.enrolledAtEpochMs, "Zeitstempel ueberlebt")
    }

    @Test
    fun `list gibt NIE Vektoren heraus - nur name und enrolledAt`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        store.upsert("andi", vec(0.1f, 0.2f), nowMs = 5_000)
        val summaries = store.list()
        assertEquals(1, summaries.size)
        assertEquals("andi", summaries[0].name)
        assertEquals(5_000, summaries[0].enrolledAt)
        // SpeakerSummary hat strukturell KEIN embedding-Feld — der Vektor kann nicht rauslecken.
        assertTrue(summaries[0] is SpeakerSummary)
    }

    @Test
    fun `upsert mit gleichem Namen ueberschreibt Vektor - auch ueber den Neustart`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        store.upsert("andi", vec(0.1f, 0.1f), nowMs = 1_000)
        store.upsert("andi", vec(0.5f, 0.6f), nowMs = 9_000)

        val reloaded = SpeakerProfileStore(file)
        assertEquals(1, reloaded.list().size, "name ist Schluessel, kein Duplikat")
        assertArrayEquals(vec(0.5f, 0.6f), reloaded.get("andi")!!.embedding, 1e-6f)
        assertEquals(9_000, reloaded.get("andi")!!.enrolledAtEpochMs)
    }

    @Test
    fun `delete entfernt Profil und Embedding wirklich - auch persistent`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        store.upsert("andi", vec(0.1f, 0.2f))
        store.upsert("partnerin", vec(0.3f, 0.4f))
        assertTrue(store.delete("andi"))
        assertFalse(store.delete("andi"), "zweites delete derselben id ⇒ false")
        assertNull(store.get("andi"), "Profil + Vektor sind aus dem Cache weg")

        val reloaded = SpeakerProfileStore(file)
        assertEquals(listOf("partnerin"), reloaded.list().map { it.name }, "delete ueberlebt den Neustart")
        assertNull(reloaded.get("andi"), "der geloeschte Vektor ist auch auf der Platte weg")
    }

    @Test
    fun `delete unbekannter Name - false ohne Datei-Write`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        assertFalse(store.delete("gibt-es-nicht"))
        assertFalse(Files.exists(file), "no-op ⇒ kein Datei-Write")
    }

    // ── Robustes Laden: fehlend/kaputt ⇒ leer, NIE crashen ───────────────────

    @Test
    fun `fehlende Datei - leer starten, kein Crash, keine Datei angelegt`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        assertTrue(store.list().isEmpty())
        assertFalse(Files.exists(file), "reines Konstruieren + list schreibt nichts")
    }

    @Test
    fun `kaputte Datei - leer starten, kein Crash, danach voll benutzbar`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        Files.writeString(file, "{ kein gueltiges json ]]")
        val store = SpeakerProfileStore(file)
        assertTrue(store.list().isEmpty(), "kaputt ⇒ leer")
        store.upsert("andi", vec(0.1f))
        assertEquals(listOf("andi"), SpeakerProfileStore(file).list().map { it.name })
    }

    @Test
    fun `unbrauchbare Eintraege werden uebersprungen - der Rest laedt`(@TempDir dir: Path) {
        val file = dir.resolve("partly.json")
        Files.writeString(
            file,
            """{
              "profiles": [
                {"name":"ok","enrolledAtEpochMs":1000,"embedding":[0.1,0.2]},
                {"name":"","enrolledAtEpochMs":2000,"embedding":[0.3]},
                {"name":"noEmb","enrolledAtEpochMs":3000},
                {"name":"emptyEmb","enrolledAtEpochMs":4000,"embedding":[]},
                "kein objekt"
              ]
            }""",
        )
        assertEquals(listOf("ok"), SpeakerProfileStore(file).list().map { it.name })
    }

    @Test
    fun `Legacy nacktes Array laedt als Profile`(@TempDir dir: Path) {
        val file = dir.resolve("legacy.json")
        Files.writeString(file, """[{"name":"alt","enrolledAtEpochMs":1000,"embedding":[0.1,0.2]}]""")
        assertEquals(listOf("alt"), SpeakerProfileStore(file).list().map { it.name })
    }

    // ── Multi-Sample-Enroll: L2-renormalisiertes Mittel, festgenagelt ────────

    @Test
    fun `appendSample - Profil-Embedding ist das EXAKTE L2-renormalisierte Mittel`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", vec(1f, 0f), nowMs = 1_000)

        // 2 Samples: (1,0) + (0,1) → Mittel (0.5, 0.5) → Norm √0.5 → (1/√2, 1/√2).
        val two = store.appendSample("andi", vec(0f, 1f), nowMs = 2_000)!!
        val invSqrt2 = (1.0 / kotlin.math.sqrt(2.0)).toFloat()
        assertArrayEquals(vec(invSqrt2, invSqrt2), two.embedding, 1e-6f, "Mittel zweier Einheitsvektoren, renormiert")
        assertEquals(2, two.samples.size)

        // 3 Samples: (1,0), (0,1), (1,0) → Mittel (2/3, 1/3) → Norm √5/3 → (2/√5, 1/√5).
        val three = store.appendSample("andi", vec(1f, 0f), nowMs = 3_000)!!
        val sqrt5 = kotlin.math.sqrt(5.0)
        assertArrayEquals(vec((2.0 / sqrt5).toFloat(), (1.0 / sqrt5).toFloat()), three.embedding, 1e-6f)
        assertEquals(3, three.samples.size)
        assertEquals(3_000, three.enrolledAtEpochMs, "zuletzt angelernt = Zeit des letzten Samples")

        // Und `get` sieht denselben Stand (Cache-Wahrheit, nicht nur der Rueckgabewert).
        assertArrayEquals(three.embedding, store.get("andi")!!.embedding, 1e-6f)
    }

    @Test
    fun `appendSample ueberlebt den Neustart - Samples UND Mittel byte-genau`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        store.upsert("andi", vec(1f, 0f))
        store.appendSample("andi", vec(0f, 1f))

        val reloaded = SpeakerProfileStore(file) // „Backend-Neustart"
        val p = reloaded.get("andi")!!
        assertEquals(2, p.samples.size, "beide Roh-Samples ueberleben")
        assertArrayEquals(vec(1f, 0f), p.samples[0], 1e-6f)
        assertArrayEquals(vec(0f, 1f), p.samples[1], 1e-6f)
        val invSqrt2 = (1.0 / kotlin.math.sqrt(2.0)).toFloat()
        assertArrayEquals(vec(invSqrt2, invSqrt2), p.embedding, 1e-6f, "renormalisiertes Mittel ueberlebt")

        // Und das naechste append rechnet auf den GELADENEN Samples weiter (kein Drift).
        val three = reloaded.appendSample("andi", vec(1f, 0f))!!
        val sqrt5 = kotlin.math.sqrt(5.0)
        assertArrayEquals(vec((2.0 / sqrt5).toFloat(), (1.0 / sqrt5).toFloat()), three.embedding, 1e-6f)
    }

    @Test
    fun `Alt-Profil ohne samples-Feld bleibt gueltig und zaehlt als 1 Sample`(@TempDir dir: Path) {
        // Datei EXAKT im Alt-Format (vor Multi-Sample): kein samples-Feld.
        val file = dir.resolve("alt.json")
        Files.writeString(file, """{"profiles":[{"name":"andi","enrolledAtEpochMs":1000,"embedding":[1.0,0.0]}]}""")
        val store = SpeakerProfileStore(file)

        val p = store.get("andi")!!
        assertArrayEquals(vec(1f, 0f), p.embedding, 1e-6f, "Alt-Embedding unveraendert")
        assertEquals(1, p.samples.size, "Alt-Profil == 1 Sample (Migration additiv)")
        assertEquals(1, store.list()[0].samples)

        // Und es ist voll append-faehig: das Alt-Embedding zaehlt als Sample 1.
        val two = store.appendSample("andi", vec(0f, 1f))!!
        val invSqrt2 = (1.0 / kotlin.math.sqrt(2.0)).toFloat()
        assertArrayEquals(vec(invSqrt2, invSqrt2), two.embedding, 1e-6f)
        assertEquals(2, two.samples.size)
    }

    @Test
    fun `appendSample auf unbekannten Namen - null ohne Datei-Write (kein stilles Anlegen)`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        assertNull(store.appendSample("gibt-es-nicht", vec(1f, 0f)))
        assertFalse(Files.exists(file), "no-op ⇒ kein Datei-Write")
    }

    @Test
    fun `Re-Enroll via upsert ersetzt die Sample-Historie (frischer Start)`(@TempDir dir: Path) {
        val file = dir.resolve("speaker-profiles.json")
        val store = SpeakerProfileStore(file)
        store.upsert("andi", vec(1f, 0f))
        store.appendSample("andi", vec(0f, 1f))
        store.upsert("andi", vec(0.5f, 0.5f), nowMs = 9_000) // Re-Enroll = ersetzen (heutiges Verhalten)

        val p = SpeakerProfileStore(file).get("andi")!!
        assertEquals(1, p.samples.size, "Historie ersetzt, nicht angehaengt")
        assertArrayEquals(vec(0.5f, 0.5f), p.embedding, 1e-6f, "1-Sample-Embedding bleibt byte-genau wie gegeben")
    }

    @Test
    fun `appendSample mit falscher Dimension wirft und laesst alles unveraendert`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", vec(1f, 0f))
        assertThrows(IllegalArgumentException::class.java) { store.appendSample("andi", vec(1f, 0f, 0f)) }
        val p = store.get("andi")!!
        assertEquals(1, p.samples.size, "Cache unveraendert")
        assertArrayEquals(vec(1f, 0f), p.embedding, 1e-6f)
    }

    @Test
    fun `list traegt die Sample-Zahl aber NIE Vektoren`(@TempDir dir: Path) {
        val store = store(dir)
        store.upsert("andi", vec(1f, 0f))
        store.appendSample("andi", vec(0f, 1f))
        val s = store.list().single()
        assertEquals(2, s.samples, "Sample-ZAHL ist sichtbar (Diagnose/UI)")
        assertTrue(s is SpeakerSummary) // strukturell weiterhin ohne Vektor-Feld
    }

    private fun store(dir: Path) = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))

    // ── Atomare Semantik + persist-then-commit ───────────────────────────────

    @Test
    fun `Mutationen hinterlassen keine tmp-Reste`(@TempDir dir: Path) {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        store.upsert("a", vec(0.1f))
        store.upsert("b", vec(0.2f))
        store.delete("a")
        assertTrue(Files.exists(store.path))
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        assertEquals(0L, leftovers, "atomarer Rename ⇒ keine .tmp-Reste")
    }

    @Test
    fun `Persist-Fehler - upsert wirft und committet den Cache NICHT`(@TempDir dir: Path) {
        // Parent des Pfads ist eine REGULÄRE DATEI ⇒ createDirectories(...) im Write wirft.
        val blocker = Files.createFile(dir.resolve("blocker"))
        val store = SpeakerProfileStore(blocker.resolve("speaker-profiles.json"))
        assertThrows(IOException::class.java) { store.upsert("a", vec(0.1f)) }
        assertTrue(store.list().isEmpty(), "Persist-Fehler ⇒ Cache unveraendert (leer)")
        assertNull(store.get("a"), "nie committed ⇒ nichts abrufbar")
    }
}
