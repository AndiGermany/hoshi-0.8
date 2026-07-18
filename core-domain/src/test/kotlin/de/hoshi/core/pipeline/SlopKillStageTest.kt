package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * Beweist die [SlopKillStage] (D7) auf dem rohen [ChatEvent]-Strom:
 *  (a) satz-initiale Kills DE+EN (geschlossen „Gerne!"/„Certainly!" und offen
 *      „Zusammenfassend…"/„Great question") inkl. Großschreibung des Restsatzes,
 *  (b) Delta-Grenzen: eine über MEHRERE Deltas gesplittete Phrase wird EHRLICH
 *      gekillt (Prefix-Hold-Design),
 *  (c) Laras Veto: kein Mid-Satz-Matschen — Slop-Wörter mitten im Satz und
 *      Nicht-Slop-Satzanfänge („Gerne erkläre…", „Great questions…") überleben
 *      byte-identisch,
 *  (d) Zähl-Register je Phrase ([SlopKillStage.SlopMetrics]),
 *  (e) [SlopKillStage.DISABLED] = Identity, Turn-Ende-Flush + Never-Silent-Guard.
 */
class SlopKillStageTest {

    private fun events(vararg texts: String): List<ChatEvent> = buildList {
        add(ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"))
        texts.forEach { add(ChatEvent.TextDelta(it, provider = "LOCAL")) }
        add(ChatEvent.Done(provider = "LOCAL"))
    }

    private fun run(stage: SlopKillStage, input: List<ChatEvent>): List<ChatEvent> =
        stage.transform(Flux.fromIterable(input)).collectList().block(Duration.ofSeconds(5))!!

    private fun textOf(out: List<ChatEvent>): String =
        out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // ── (a) satz-initiale Kills DE ───────────────────────────────────────────────

    @Test
    fun `geschlossene DE-Klassiker werden satz-initial gekillt und gezaehlt`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Gerne! Ich mache das Licht an."))

        assertEquals("Ich mache das Licht an.", textOf(out))
        assertEquals(mapOf("Gerne!" to 1L), stage.metrics.getSnapshot())
        // Event-Hülle intakt: Start zuerst, Done terminal.
        assertTrue(out.first() is ChatEvent.Start, "Turn beginnt mit Start")
        assertTrue(out.last() is ChatEvent.Done, "Turn endet mit Done")
    }

    @Test
    fun `offene DE-Phrase wird gestrichen und der Restsatz grossgeschrieben`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Es ist wichtig zu beachten, dass es regnet."))

        assertEquals("Dass es regnet.", textOf(out))
        assertEquals(1L, stage.metrics.getSnapshot()["Es ist wichtig zu beachten"])
    }

    @Test
    fun `offene DE-Phrase mit Doppelpunkt wird inklusive Separator geschluckt`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Zusammenfassend lässt sich sagen: das Wetter bleibt gut."))

        assertEquals("Das Wetter bleibt gut.", textOf(out))
        assertEquals(1L, stage.metrics.getSnapshot()["Zusammenfassend lässt sich sagen"])
    }

    // ── (a) satz-initiale Kills EN ───────────────────────────────────────────────

    @Test
    fun `EN-Klassiker werden satz-initial gekillt`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Certainly! Here we go."))
        assertEquals("Here we go.", textOf(out))

        val out2 = run(stage, events("Great question! The timer is set."))
        assertEquals("The timer is set.", textOf(out2))

        val snapshot = stage.metrics.getSnapshot()
        assertEquals(1L, snapshot["Certainly!"])
        assertEquals(1L, snapshot["Great question"])
    }

    @Test
    fun `Kill wirkt auch am Anfang eines SPAETEREN Satzes`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Das Licht ist an. In conclusion, everything works."))

        assertEquals("Das Licht ist an. Everything works.", textOf(out))
        assertEquals(1L, stage.metrics.getSnapshot()["In conclusion"])
    }

    // ── (b) Delta-Grenzen: gesplittete Phrase wird gekillt ──────────────────────

    @Test
    fun `Phrase ueber mehrere Deltas gesplittet wird ehrlich gekillt`() {
        val stage = SlopKillStage()
        // „Gerne!" liegt über zwei Deltas verteilt — der Prefix-Hold killt trotzdem.
        val out = run(stage, events("Ger", "ne! Hal", "lo."))

        assertEquals("Hallo.", textOf(out))
        assertEquals(mapOf("Gerne!" to 1L), stage.metrics.getSnapshot())
    }

    @Test
    fun `offene Phrase ueber Delta-Grenze inklusive Wortgrenze im Folgedelta`() {
        val stage = SlopKillStage()
        val out = run(stage, events("I hope this ", "helps! See you."))

        assertEquals("See you.", textOf(out))
        assertEquals(1L, stage.metrics.getSnapshot()["I hope this helps"])
    }

    // ── (c) Laras Veto: kein Mid-Satz-Matschen ───────────────────────────────────

    @Test
    fun `Slop-Woerter mitten im Satz bleiben byte-identisch erhalten`() {
        val stage = SlopKillStage()
        val text = "Ich helfe dir gerne! Das klappt natürlich gut."
        val out = run(stage, events(text))

        assertEquals(text, textOf(out))
        assertTrue(stage.metrics.getSnapshot().isEmpty(), "kein Kill, kein Zähler")
    }

    @Test
    fun `Gerne ohne Ausrufezeichen ueberlebt satz-initial`() {
        val stage = SlopKillStage()
        val text = "Gerne erkläre ich dir das genauer."
        assertEquals(text, textOf(run(stage, events(text))))
        assertTrue(stage.metrics.getSnapshot().isEmpty())
    }

    @Test
    fun `Natuerlich als echte Antwort ohne Ausrufezeichen ueberlebt`() {
        val stage = SlopKillStage()
        val text = "Natürlich mag ich Musik."
        assertEquals(text, textOf(run(stage, events(text))))
    }

    @Test
    fun `Wortgrenzen-Guard - Great questions are rare ueberlebt`() {
        val stage = SlopKillStage()
        val text = "Great questions are rare."
        assertEquals(text, textOf(run(stage, events(text))))
        assertTrue(stage.metrics.getSnapshot().isEmpty())
    }

    @Test
    fun `zitierter Slop ueberlebt - Anfuehrungszeichen belegt den Satzanfang`() {
        val stage = SlopKillStage()
        val text = "\"Gerne!\" sagte er dann."
        assertEquals(text, textOf(run(stage, events(text))))
        assertTrue(stage.metrics.getSnapshot().isEmpty())
    }

    // ── (d) Zähl-Register ────────────────────────────────────────────────────────

    @Test
    fun `Register zaehlt je Phrase ueber mehrere Turns derselben Stage`() {
        val stage = SlopKillStage()
        run(stage, events("Gerne! Licht ist an."))
        run(stage, events("Gerne! Timer läuft."))
        run(stage, events("In conclusion, done."))

        val snapshot = stage.metrics.getSnapshot()
        assertEquals(2L, snapshot["Gerne!"])
        assertEquals(1L, snapshot["In conclusion"])
        assertEquals(3L, stage.metrics.totalKills())
    }

    // ── (e) Flag, Turn-Ende-Flush, Never-Silent ─────────────────────────────────

    @Test
    fun `DISABLED ist Identity - Text unveraendert und nichts gezaehlt`() {
        val text = "Gerne! Certainly! Es ist wichtig zu beachten, dass nichts passiert."
        val input = events(text)
        val out = run(SlopKillStage.DISABLED, input)

        assertEquals(input.size, out.size, "keine Events verschluckt oder hinzugefügt")
        assertEquals(text, textOf(out))
        assertTrue(SlopKillStage.DISABLED.metrics.getSnapshot().isEmpty(), "OFF zählt nicht")
    }

    @Test
    fun `unvollstaendiger Phrasen-Praefix wird am Done geflusht statt verschluckt`() {
        val stage = SlopKillStage()
        // „Ich hoffe" ist Präfix von „Ich hoffe, das hilft" — bleibt im Hold liegen
        // und MUSS am Turn-Ende unverändert emittiert werden (kein Textverlust).
        val out = run(stage, events("Ich hoffe"))

        assertEquals("Ich hoffe", textOf(out))
        assertTrue(out.last() is ChatEvent.Done)
        assertTrue(stage.metrics.getSnapshot().isEmpty())
    }

    @Test
    fun `vollstaendige offene Phrase am Stream-Ende wird gekillt wenn schon Text floss`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Der Timer läuft. ", "Ich hoffe, das hilft"))

        assertEquals("Der Timer läuft. ", textOf(out))
        assertEquals(1L, stage.metrics.getSnapshot()["Ich hoffe, das hilft"])
    }

    @Test
    fun `Never-Silent-Guard - Antwort die NUR aus der Phrase besteht wird nicht zur Stille gekillt`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Ich hoffe, das hilft"))

        assertEquals("Ich hoffe, das hilft", textOf(out))
        assertTrue(stage.metrics.getSnapshot().isEmpty(), "lieber Slop als Schweigen")
    }

    @Test
    fun `Doppel-Slop hintereinander wird komplett gekillt`() {
        val stage = SlopKillStage()
        val out = run(stage, events("Gerne! Natürlich! Das Licht ist an."))

        assertEquals("Das Licht ist an.", textOf(out))
        val snapshot = stage.metrics.getSnapshot()
        assertEquals(1L, snapshot["Gerne!"])
        assertEquals(1L, snapshot["Natürlich!"])
    }

    @Test
    fun `Step-Events fliessen unveraendert durch`() {
        val stage = SlopKillStage()
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            ChatEvent.Step(kind = "route", message = "LOCAL"),
            ChatEvent.TextDelta("Gerne! Alles klar.", provider = "LOCAL"),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage, input)

        assertEquals(1, out.count { it is ChatEvent.Step }, "Step bleibt erhalten")
        assertEquals("Alles klar.", textOf(out))
    }
}
