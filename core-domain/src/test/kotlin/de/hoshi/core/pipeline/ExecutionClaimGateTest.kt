package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **ExecutionClaimGate** — pattern catalogue (positive/negative), the frozen
 * state-answer negative case, the five ask-backs and the stream contract.
 *
 * The live turns behind this: `vault/knowledge/BEFUND-brain-behauptet-vollzug-2026-08-11.md`.
 */
class ExecutionClaimGateTest {

    private val gate = ExecutionClaimGate()

    // ── (1) Positive catalogue — these ARE claims ────────────────────────────

    @Test
    fun `Vollzugs-Muster positiv - DE`() {
        val claims = listOf(
            // the two live turns of 2026-08-11, verbatim
            "Flurlicht an.",
            "Mach ich. Flurlicht ist an.",
            // state form, particle closing the sentence
            "Das Licht im Flur ist an.",
            "Die Lampe ist jetzt aus.",
            "Die Lichter sind wieder an.",
            "Das Wohnzimmerlicht ist aus.",
            "Die Heizung ist an.",
            // completed act (participle), anywhere in the sentence
            "Ich habe das Licht im Flur eingeschaltet.",
            "Hab die Lampe ausgeschaltet.",
            "Das Deckenlicht habe ich angemacht.",
            "Die Heizung ist abgeschaltet.",
        )
        claims.forEach { assertTrue(ExecutionClaimGate.claimsExecution(it), "muss als Vollzug gelten: $it") }
    }

    @Test
    fun `Vollzugs-Muster positiv - EN`() {
        val claims = listOf(
            "Hallway light on.",
            "Done. The hallway light is on.",
            "The lamp is now off.",
            "The lights are on.",
            "I turned on the light in the hallway.",
            "I've switched off the heating.",
            "Turned the light on.",
        )
        claims.forEach { assertTrue(ExecutionClaimGate.claimsExecution(it), "must count as a claim: $it") }
    }

    // ── (2) Negative catalogue — frozen, must NEVER be replaced ──────────────

    @Test
    fun `Negativ-Liste - kein Vollzug`() {
        val innocent = listOf(
            // no device noun at all — DOCUMENTED GAP: a pronoun-only claim
            // ("hab ich gemacht", "turned it on") is not caught, because without a
            // device noun the ask-back would be the wrong answer.
            "Klar, mach ich.",
            "Der Wecker ist gestellt.",
            "Hab ich gemacht.",
            "Turned it on.",
            // negated — the honest sentence must survive
            "Ich habe das Licht nicht eingeschaltet.",
            "Das Licht ist nicht an.",
            "Ich kann das Licht gerade nicht schalten.",
            // hedged — an uncertain sentence claims nothing
            "Das Licht ist vermutlich an.",
            "Ich glaube, die Lampe ist aus.",
            "The light should be on.",
            // offer / infinitive / instruction — nothing happened yet
            "Soll ich das Licht im Flur einschalten?",
            "Ich kann das Licht für dich anmachen.",
            "Sag einfach: Licht im Flur an.",
            "Shall I turn on the light?",
            // question
            "Ist das Licht im Flur an?",
            "Meinst du das Licht im Flur?",
            // prepositional "an"/"on" — the reason the particle is anchored at the end
            "Das Licht ist an der Wand angebracht.",
            "Die Lampe steht auf dem Tisch an der Wand.",
            "The light on the table is a lamp.",
            // knowledge prose that merely mentions a device word (separable verb!)
            "Licht breitet sich sehr schnell aus.",
            "Eine Lampe wandelt Strom in Helligkeit um.",
            "Ein Lichtjahr ist eine Strecke, keine Zeit.",
        )
        innocent.forEach { assertFalse(ExecutionClaimGate.claimsExecution(it), "darf NIE als Vollzug gelten: $it") }
    }

    @Test
    fun `Geraetewort-Erkennung - Komposita ja, Zufalls-Endungen nein`() {
        listOf("licht", "flurlicht", "deckenlicht", "wohnzimmerlicht", "stehlampe", "fussbodenheizung")
            .forEach { assertTrue(ExecutionClaimGate.isDeviceToken(it), "muss Gerätewort sein: $it") }
        listOf("pflicht", "schlicht", "geschichte", "gewicht", "bericht", "absicht", "schicht")
            .forEach { assertFalse(ExecutionClaimGate.isDeviceToken(it), "darf KEIN Gerätewort sein: $it") }
    }

    // ── (3) The frozen negative case: state answer to a state question ───────

    @Test
    fun `Zustands-Auskunft auf Zustandsfrage - Riegel bleibt entwaffnet`() {
        // The ANSWER matches the pattern — only the input side keeps it untouched.
        assertTrue(ExecutionClaimGate.claimsExecution("Das Licht im Flur ist an."))
        listOf(
            "Ist das Licht im Flur an?",
            "ist das licht im flur an",
            "Brennt das Licht im Flur noch?",
            "Wie hell ist das Licht im Flur?",
            "Is the hallway light on?",
            "What is a lamp?",
        ).forEach {
            assertFalse(ExecutionClaimGate.armed(it, toolCallRan = false), "Zustandsfrage darf nie scharf sein: $it")
        }
    }

    @Test
    fun `Riegel ist entwaffnet - kein Geraetewort, oder ein ToolCall lief`() {
        assertFalse(ExecutionClaimGate.armed("Wie geht es dir?", toolCallRan = false))
        assertFalse(ExecutionClaimGate.armed("Erzähl mir was über Lampen", toolCallRan = false))
        // Every smart-home READ is a tool turn — that IS the category guard.
        assertFalse(ExecutionClaimGate.armed("Schalte das Licht im Flur ein.", toolCallRan = true))
    }

    @Test
    fun `Riegel ist scharf - die zwei verstuemmelten Live-Befehle`() {
        assertTrue(ExecutionClaimGate.armed("Jetzt drübe das Licht im Flur ein.", toolCallRan = false))
        assertTrue(ExecutionClaimGate.armed("das Licht im Flur ein.", toolCallRan = false))
        // A modal request is a command, not an information question — stays armed.
        assertTrue(ExecutionClaimGate.armed("Kannst du das Licht im Flur anmachen?", toolCallRan = false))
    }

    // ── (4) Ask-back in all five languages ──────────────────────────────────

    @Test
    fun `Rueckfrage existiert in allen fuenf Sprachen - warm, knapp, ohne Zustands-Behauptung`() {
        Language.entries.forEach { lang ->
            val phrase = gate.askBack(lang)
            assertEquals(LanguagePackRegistry.forLanguage(lang).executionClaimAskBack, phrase)
            assertTrue(phrase.isNotBlank(), "$lang: keine leere Rückfrage")
            assertTrue(phrase.trim().endsWith("?"), "$lang: die Rückfrage muss fragen — $phrase")
            // A latch phrase must never claim or deny a device state itself.
            assertFalse(
                ExecutionClaimGate.claimsExecution(phrase),
                "$lang: die Rückfrage darf selbst nie wie ein Vollzug klingen",
            )
        }
        assertEquals(
            "Das habe ich nicht sicher als Schaltbefehl verstanden — magst du es nochmal sagen?",
            gate.askBack(Language.DE),
        )
    }

    // ── (5) Stream contract ─────────────────────────────────────────────────

    private fun deltas(vararg parts: String): Flux<ChatEvent> =
        Flux.fromIterable(parts.map { ChatEvent.TextDelta(it, provider = "LOCAL") })

    private fun run(stream: Flux<ChatEvent>): List<ChatEvent> =
        stream.collectList().block(Duration.ofSeconds(5))!!

    @Test
    fun `entwaffnet - der Strom kommt IDENTISCH zurueck, kein Puffern`() {
        val source = deltas("Alles ", "gut!")
        val out = gate.transform(source, userText = "Wie geht's dir?", language = Language.DE, toolCallRan = false)
        assertSame(source, out, "unarmed ⇒ Identität, kein Operator auf dem Strom")
    }

    @Test
    fun `scharf ohne Vollzug - Deltas laufen unveraendert durch`() {
        val fired = AtomicBoolean(false)
        val out = run(
            gate.transform(
                deltas("Ich ", "kann das Licht ", "gerade nicht schalten."),
                userText = "das Licht im Flur ein.",
                language = Language.DE,
                toolCallRan = false,
                onFired = { fired.set(true) },
            ),
        )
        assertEquals(
            listOf("Ich ", "kann das Licht ", "gerade nicht schalten."),
            out.filterIsInstance<ChatEvent.TextDelta>().map { it.text },
        )
        assertFalse(fired.get(), "kein Vollzug ⇒ der Riegel feuert nicht")
    }

    @Test
    fun `scharf mit Vollzug - die Antwort wird durch die Rueckfrage ersetzt`() {
        val fired = AtomicBoolean(false)
        val out = run(
            gate.transform(
                // split across delta boundaries — the claim only exists in the sum
                deltas("Mach ich. ", "Flurlicht ", "ist an."),
                userText = "das Licht im Flur ein.",
                language = Language.DE,
                toolCallRan = false,
                onFired = { fired.set(true) },
            ),
        )
        val texts = out.filterIsInstance<ChatEvent.TextDelta>().map { it.text }
        assertEquals(listOf(gate.askBack(Language.DE)), texts)
        assertEquals("LOCAL", out.filterIsInstance<ChatEvent.TextDelta>().first().provider)
        assertTrue(fired.get(), "der Riegel muss sichtbar gefeuert haben")
    }

    @Test
    fun `Puffer-Deckel - lange Prosa laeuft durch, auch mit spaetem Vollzugs-Satz`() {
        val fired = AtomicBoolean(false)
        val long = "Licht ist ein faszinierendes Thema. ".repeat(20) // > MAX_BUFFERED_CHARS
        val out = run(
            gate.transform(
                deltas(long, "Flurlicht ist an."),
                userText = "das Licht im Flur ein.",
                language = Language.DE,
                toolCallRan = false,
                onFired = { fired.set(true) },
            ),
        )
        assertEquals(long + "Flurlicht ist an.", out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
        assertFalse(fired.get(), "über dem Deckel gibt der Riegel ehrlich auf, statt unbegrenzt zu puffern")
    }

    @Test
    fun `Fehler im Strom - gepufferte Deltas werden geflusht, der Fehler propagiert`() {
        val fired = AtomicBoolean(false)
        val broken = Flux.concat(
            deltas("Flurlicht "),
            Flux.error<ChatEvent>(IllegalStateException("brain weg")),
        )
        val out = gate.transform(
            broken,
            userText = "das Licht im Flur ein.",
            language = Language.DE,
            toolCallRan = false,
            onFired = { fired.set(true) },
        ).onErrorResume { Flux.empty() }.collectList().block(Duration.ofSeconds(5))!!
        assertEquals(listOf("Flurlicht "), out.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        assertFalse(fired.get(), "ein abgebrochener Strom hat keine finale Antwort — never-silent schlägt Riegel")
    }
}
