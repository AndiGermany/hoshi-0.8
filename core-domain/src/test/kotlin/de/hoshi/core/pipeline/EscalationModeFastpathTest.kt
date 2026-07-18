package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist den Stufen-Fastpath [EscalationModeFastpath] (Extended Think per
 * Sprache/Chat): die konservative Matching-Matrix (inkl. der GEGEN-Beispiele —
 * „warum bist du nicht online?" darf NIE matchen), die Store-Wirkung über die
 * [EscalationModeSwitchPort]-Naht, die EXAKT gepinnten Quittungen mit
 * Stufen-Echo, die ehrliche Fehler-Antwort und das Flag-OFF (byte-neutral).
 */
class EscalationModeFastpathTest {

    /** Nimmt jeden Persist an und protokolliert ihn (Store-Wirkung sichtbar). */
    private class RecordingSwitch(private val ok: Boolean = true) : EscalationModeSwitchPort {
        val switched = mutableListOf<EscalationMode>()
        override fun switchTo(mode: EscalationMode): Boolean {
            switched += mode
            return ok
        }
    }

    private fun fastpath(port: RecordingSwitch = RecordingSwitch()) = EscalationModeFastpath(port)

    // ── Matching-Matrix: ERST_FRAGEN ─────────────────────────────────────────

    @Test
    fun `ERST_FRAGEN DE-Formulierungen matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("Frag mich erst, bevor du online gehst."))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("frag erst bevor du online gehst"))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("frag mich, bevor du online gehst"))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("Frag mich erst, bevor du nachschaust!"))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("Geh nur nach Rückfrage online."))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("geh nach rückfrage online"))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("bitte nur nach Rückfrage online"))
    }

    @Test
    fun `ERST_FRAGEN EN-Pendants matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("Ask me first before you go online."))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("ask before going online"))
        assertEquals(EscalationMode.ERST_FRAGEN, fp.match("only go online after asking me"))
    }

    // ── Matching-Matrix: AUS ─────────────────────────────────────────────────

    @Test
    fun `AUS DE-Formulierungen matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.AUS, fp.match("Schalte Online-Nachschauen aus."))
        assertEquals(EscalationMode.AUS, fp.match("schalt das Online-Nachschauen bitte aus"))
        assertEquals(EscalationMode.AUS, fp.match("mach die Online-Suche aus"))
        assertEquals(EscalationMode.AUS, fp.match("Geh nicht online."))
        assertEquals(EscalationMode.AUS, fp.match("geh nicht mehr online"))
        assertEquals(EscalationMode.AUS, fp.match("Geh nie online!"))
    }

    @Test
    fun `AUS EN-Pendants matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.AUS, fp.match("Turn off online lookups."))
        assertEquals(EscalationMode.AUS, fp.match("turn the online search off"))
        assertEquals(EscalationMode.AUS, fp.match("Don't go online."))
        assertEquals(EscalationMode.AUS, fp.match("never go online"))
        assertEquals(EscalationMode.AUS, fp.match("stop going online"))
        assertEquals(EscalationMode.AUS, fp.match("disable online lookups"))
    }

    // ── Matching-Matrix: AUTOMATISCH ─────────────────────────────────────────

    @Test
    fun `AUTOMATISCH DE-Formulierungen matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("Geh automatisch online."))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("geh ab jetzt automatisch online"))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("Schau selbstständig nach."))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("schau selbständig online nach"))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("schau automatisch nach"))
    }

    @Test
    fun `AUTOMATISCH EN-Pendants matchen`() {
        val fp = fastpath()
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("Go online automatically."))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("automatically go online"))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("look things up automatically"))
        assertEquals(EscalationMode.AUTOMATISCH, fp.match("go online on your own"))
    }

    // ── GEGEN-Beispiele: Status-Fragen und Beiläufiges matchen NIE ──────────

    @Test
    fun `Gegen-Beispiele matchen nicht`() {
        val fp = fastpath()
        assertNull(fp.match("Warum bist du nicht online?"), "Status-Frage ist KEIN Settings-Wunsch")
        assertNull(fp.match("bist du online?"))
        assertNull(fp.match("warum gehst du nicht online?"))
        assertNull(fp.match("warum gehst du automatisch online?"))
        assertNull(fp.match("geh online"), "ohne Stufen-Wort keine Stufe")
        assertNull(fp.match("ich war heute online"))
        assertNull(fp.match("schau mal nach"), "bloßes Nachschauen ist kein AUTOMATISCH")
        assertNull(fp.match("kannst du das nachschauen?"))
        assertNull(fp.match("geh nicht auf die Nerven"), "ohne Online-/Nachschauen-Wort nie")
        assertNull(fp.match("why are you not online?"))
        assertNull(fp.match(""))
    }

    // ── Store-Wirkung + exakte Quittungen ────────────────────────────────────

    @Test
    fun `handle persistiert die Stufe und quittiert mit Stufen-Echo DE`() {
        val port = RecordingSwitch()
        val fp = fastpath(port)

        assertEquals(
            "Okay — ich frag dich ab jetzt erst, bevor ich online nachschaue.",
            fp.handle("Frag mich erst, bevor du online gehst.", Language.DE),
        )
        assertEquals(
            "Okay — Online-Nachschauen ist aus. Ich bleib komplett lokal.",
            fp.handle("Schalte Online-Nachschauen aus.", Language.DE),
        )
        assertEquals(
            "Okay — ich schau ab jetzt automatisch online nach, wenn ich etwas nicht weiß.",
            fp.handle("Geh automatisch online.", Language.DE),
        )
        assertEquals(
            listOf(EscalationMode.ERST_FRAGEN, EscalationMode.AUS, EscalationMode.AUTOMATISCH),
            port.switched,
            "jede Quittung hat GENAU EINEN bewiesenen Persist dahinter",
        )
    }

    @Test
    fun `handle quittiert englisch mit Stufen-Echo`() {
        val fp = fastpath()
        assertEquals(
            "Okay — from now on I'll ask you first before I look anything up online.",
            fp.handle("ask me first before you go online", Language.EN),
        )
        assertEquals(
            "Okay — online lookups are off. I'll stay fully local.",
            fp.handle("turn off online lookups", Language.EN),
        )
        assertEquals(
            "Okay — from now on I'll look things up online automatically when I don't know something.",
            fp.handle("go online automatically", Language.EN),
        )
    }

    @Test
    fun `Persist-Fehler wird ehrlich beantwortet nie fake-bestaetigt`() {
        val fp = fastpath(RecordingSwitch(ok = false))
        assertEquals(
            "Das wollte ich gerade umstellen, aber das Speichern hat nicht geklappt — die Stufe bleibt unverändert.",
            fp.handle("geh automatisch online", Language.DE),
        )
        assertEquals(
            "I tried to switch that, but saving failed — the setting stays unchanged.",
            fp.handle("go online automatically", Language.EN),
        )
    }

    // ── Flag-OFF / Kein Treffer ⇒ null (normaler Turn, byte-neutral) ────────

    @Test
    fun `DISABLED liefert immer null`() {
        assertNull(EscalationModeFastpath.DISABLED.handle("geh automatisch online", Language.DE))
        assertNull(EscalationModeFastpath.DISABLED.handle("schalte online-nachschauen aus", Language.DE))
    }

    @Test
    fun `kein Treffer beruehrt den Store nicht`() {
        val port = RecordingSwitch()
        assertNull(fastpath(port).handle("Warum bist du nicht online?", Language.DE))
        assertEquals(emptyList<EscalationMode>(), port.switched, "Gegen-Beispiel darf NIE persistieren")
    }
}
