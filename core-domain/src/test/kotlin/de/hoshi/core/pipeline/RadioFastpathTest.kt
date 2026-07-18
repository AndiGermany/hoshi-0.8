package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.RadioCallOutcome
import de.hoshi.core.port.RadioPort
import de.hoshi.core.port.RadioStation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Beweist den brain-freien [RadioFastpath] mit Fake-[RadioPort]: die kuratierten
 * DE-Intents („spiel radio <name>" / „spiel <name> radio" / „radio aus|stopp"),
 * das warme NOT_FOUND unter der Andi-Schwelle (Port liefert `null`), die
 * ehrlichen Fehler-/Hedge-Antworten aus [RadioCallOutcome] (P2-Bug-Fix
 * 2026-07-11: die Phrase kommt aus dem gelesenen State, nicht der bloßen
 * HTTP-Absicht) und den nie-antwortenden Flag-OFF-Default.
 */
class RadioFastpathTest {

    private val wdr2 = RadioStation(name = "WDR 2", streamUrl = "https://wdr2.example/stream")

    /** Fake-Port: kennt nur „wdr 2"; protokolliert alle Aufrufe. */
    private class FakeRadioPort(
        private val station: RadioStation?,
        private val playOutcome: RadioCallOutcome = RadioCallOutcome.VERIFIED,
        private val stopOutcome: RadioCallOutcome = RadioCallOutcome.VERIFIED,
    ) : RadioPort {
        val searches = mutableListOf<String>()
        val plays = mutableListOf<Pair<RadioStation, String>>()
        val stops = mutableListOf<String>()

        override fun search(name: String): RadioStation? {
            searches += name
            return station?.takeIf { name == "wdr 2" }
        }

        override fun play(station: RadioStation, target: String): RadioCallOutcome {
            plays += station to target
            return playOutcome
        }

        override fun stop(target: String): RadioCallOutcome {
            stops += target
            return stopOutcome
        }
    }

    private fun fastpath(port: RadioPort, target: String = "media_player.rx_v6a") =
        RadioFastpath(radio = port, target = target, enabled = true)

    // ── Play-Intents ─────────────────────────────────────────────────────────

    @Test
    fun `spiel radio name startet die Station mit warmer Quittung`() {
        val port = FakeRadioPort(wdr2)
        val phrase = fastpath(port).handle("Spiel Radio WDR 2", Language.DE)
        assertEquals("WDR 2 läuft — auf dem Receiver.", phrase)
        assertEquals(listOf("wdr 2"), port.searches)
        assertEquals(listOf(wdr2 to "media_player.rx_v6a"), port.plays)
    }

    @Test
    fun `spiele-Variante und Satzzeichen matchen ebenfalls`() {
        val port = FakeRadioPort(wdr2)
        assertEquals("WDR 2 läuft — auf dem Receiver.", fastpath(port).handle("spiele radio wdr 2!", Language.DE))
    }

    @Test
    fun `spiel name radio matcht die Suffix-Form`() {
        val port = FakeRadioPort(wdr2)
        val phrase = fastpath(port).handle("spiel WDR 2 radio", Language.DE)
        assertEquals("WDR 2 läuft — auf dem Receiver.", phrase)
        assertEquals(listOf("wdr 2"), port.searches)
    }

    // ── NOT_FOUND (Andi-Schwelle) ────────────────────────────────────────────

    @Test
    fun `unter der Schwelle kommt das warme NOT_FOUND und KEIN play`() {
        val port = FakeRadioPort(station = null)
        val phrase = fastpath(port).handle("spiel radio xyzzy", Language.DE)
        assertEquals("xyzzy kenn ich nicht sicher — meinst du was Ähnliches?", phrase)
        assertTrue(port.plays.isEmpty(), "unter der Schwelle darf NICHTS starten")
    }

    // ── Stop-Intents ─────────────────────────────────────────────────────────

    @Test
    fun `radio aus und stopp-Varianten stoppen das Ziel`() {
        for (text in listOf("Radio aus", "radio stopp", "radio stop", "mach das Radio aus", "stopp das radio")) {
            val port = FakeRadioPort(wdr2)
            assertEquals("Radio ist aus.", fastpath(port).handle(text, Language.DE), "Text war: $text")
            assertEquals(listOf("media_player.rx_v6a"), port.stops, "Text war: $text")
        }
    }

    // ── Ehrliche Fehler-/Hedge-Antworten (RadioCallOutcome) ──────────────────

    @Test
    fun `play-Fehlschlag endet ehrlich statt jubelnd`() {
        val port = FakeRadioPort(wdr2, playOutcome = RadioCallOutcome.NOT_ACCEPTED)
        val phrase = fastpath(port).handle("spiel radio wdr 2", Language.DE)
        assertEquals("WDR 2 hab ich gefunden, aber der Receiver reagiert gerade nicht.", phrase)
    }

    @Test
    fun `play akzeptiert aber State nie playing liefert ehrliche Hedge-Phrase`() {
        // P2-Bug-Fix 2026-07-11: HTTP-2xx allein bewies nie den echten Zustand — der
        // Receiver kann offline/auf falschem Eingang/mit totem Stream sein.
        val port = FakeRadioPort(wdr2, playOutcome = RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED)
        val phrase = fastpath(port).handle("spiel radio wdr 2", Language.DE)
        assertEquals(
            "Ich hab WDR 2 an den Receiver geschickt, aber er spielt (noch) nicht — " +
                "evtl. ist er aus oder auf einem anderen Eingang.",
            phrase,
        )
    }

    @Test
    fun `ohne Abspielziel gibt es die ehrliche Ansage OHNE Suche`() {
        val port = FakeRadioPort(wdr2)
        val phrase = fastpath(port, target = "").handle("spiel radio wdr 2", Language.DE)
        assertEquals("Radio kann ich noch nicht abspielen — mir fehlt das Abspielziel.", phrase)
        assertTrue(port.searches.isEmpty(), "ohne Ziel keine leere Versprechung + keine Suche")
        assertTrue(port.plays.isEmpty())
    }

    @Test
    fun `stop-Fehlschlag endet ehrlich`() {
        val port = FakeRadioPort(wdr2, stopOutcome = RadioCallOutcome.NOT_ACCEPTED)
        assertEquals("Der Receiver reagiert gerade nicht.", fastpath(port).handle("radio aus", Language.DE))
    }

    @Test
    fun `stop akzeptiert aber State nie idle liefert ehrliche Hedge-Phrase`() {
        val port = FakeRadioPort(wdr2, stopOutcome = RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED)
        val phrase = fastpath(port).handle("radio aus", Language.DE)
        assertEquals(
            "Ich hab dem Receiver Stopp gesagt, aber er scheint noch zu spielen — vielleicht hat er nicht reagiert.",
            phrase,
        )
    }

    // ── Konservative Erkennung: kein False-Positive ──────────────────────────

    @Test
    fun `Nicht-Radio-Texte liefern null`() {
        val port = FakeRadioPort(wdr2)
        val fp = fastpath(port)
        for (text in listOf(
            "mach das Licht an",
            "wie spät ist es",
            "im radio lief gestern ein guter song",
            "spiel radio", // kein Name ⇒ kein Wunsch
            "spiel radio aus", // Stop-Wort als „Name" ⇒ kein Play
            "",
        )) {
            assertNull(fp.handle(text, Language.DE), "Text war: $text")
            assertFalse(fp.matches(text), "matches, Text war: $text")
        }
        assertTrue(port.searches.isEmpty())
        assertTrue(port.plays.isEmpty())
        assertTrue(port.stops.isEmpty())
    }

    // ── Flag-OFF: der Kotlin-Default ist AUS ─────────────────────────────────

    @Test
    fun `DISABLED und der Kotlin-Default antworten NIE`() {
        assertNull(RadioFastpath.DISABLED.handle("spiel radio wdr 2", Language.DE))
        assertFalse(RadioFastpath.DISABLED.matches("spiel radio wdr 2"))
        // enabled hat Kotlin-Default false: auch ein „vergessenes" Flag bleibt stumm.
        val port = FakeRadioPort(wdr2)
        val defaultOff = RadioFastpath(radio = port, target = "media_player.rx_v6a")
        assertNull(defaultOff.handle("spiel radio wdr 2", Language.DE))
        assertTrue(port.searches.isEmpty())
    }

    // ── EN-Zweig (die Intents sind DE, die Quittung folgt der Turn-Sprache) ──

    @Test
    fun `EN-Turn bekommt die englische Quittung`() {
        val port = FakeRadioPort(wdr2)
        assertEquals("WDR 2 is playing — on the receiver.", fastpath(port).handle("spiel radio wdr 2", Language.EN))
    }
}
