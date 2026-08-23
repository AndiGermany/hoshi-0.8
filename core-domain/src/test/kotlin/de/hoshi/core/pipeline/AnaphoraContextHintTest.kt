package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit proof of the two bauteile behind the anaphora fix (Andi live bug
 * 2026-08-15): [AnaphoraRecognizer] decides WHETHER context must travel,
 * [ContextHint] decides WHAT travels and how much. The chain proof (offer →
 * „ja" → escalation query) lives in [TurnOrchestratorAnaphoraContextHintTest].
 */
class AnaphoraContextHintTest {

    // ── Recognizer: fires on a reference without an own noun ─────────────────

    @Test
    fun `the live bug sentence is recognized as anaphoric`() {
        assertTrue(AnaphoraRecognizer.carriesUnresolvedReference("Wozu isst man ihn denn?"))
    }

    @Test
    fun `german pronouns and pronominal adverbs fire`() {
        listOf(
            "Wozu isst man ihn denn?",
            "Was macht man damit?",
            "Wie schmeckt sie eigentlich?",
            "Wie lange kocht man es?",
            "Das kann man doch essen, oder?",
            "Wofür braucht man diese?",
        ).forEach { assertTrue(AnaphoraRecognizer.carriesUnresolvedReference(it), "should fire: '$it'") }
    }

    @Test
    fun `english pronouns and demonstratives fire`() {
        listOf(
            "What do you eat it with?",
            "How long do they live?",
            "What is this used for?",
            "That sounds odd, how does it work?",
        ).forEach { assertTrue(AnaphoraRecognizer.carriesUnresolvedReference(it), "should fire: '$it'") }
    }

    @Test
    fun `a sentence with its own noun is self-contained`() {
        listOf(
            "Wie hoch ist der Eiffelturm?",
            "Wozu isst man das Brot?",
            "Wie alt ist Ingwer als Heilpflanze?",
            "How tall is the Eiffel Tower?",
        ).forEach { assertFalse(AnaphoraRecognizer.carriesUnresolvedReference(it), "should NOT fire: '$it'") }
    }

    @Test
    fun `no reference signal at all - no hint`() {
        assertFalse(AnaphoraRecognizer.carriesUnresolvedReference("wann kommt gta 6 raus"))
        assertFalse(AnaphoraRecognizer.carriesUnresolvedReference(""))
        assertFalse(AnaphoraRecognizer.carriesUnresolvedReference("   ?!  "))
    }

    /**
     * Documented, accepted false positive: expletive „es" carries no referent, but
     * the recognizer cannot tell it from a referential one without a parser. The
     * cost is two extra lines of context on an escalation that already happens.
     */
    @Test
    fun `expletive es is an accepted false positive`() {
        assertTrue(AnaphoraRecognizer.carriesUnresolvedReference("wie spät ist es gerade"))
    }

    @Test
    fun `german polite Sie is not mistaken for a noun`() {
        // "Sie" is capitalized but a pronoun — the noun guard must not swallow the signal.
        assertTrue(AnaphoraRecognizer.carriesUnresolvedReference("Wozu essen Sie ihn?"))
    }

    // ── Hint: last exchange, labelled, capped ────────────────────────────────

    @Test
    fun `hint carries the last user sentence and the core of the last answer`() {
        val hint = ContextHint.of(
            listOf(
                ChatMessage("user", "Was ist Ingwer?"),
                ChatMessage("assistant", "Ingwer ist eine scharfe Wurzelknolle."),
            ),
            Language.DE,
        )
        assertTrue(hint!!.contains("Was ist Ingwer?"), hint)
        assertTrue(hint.contains("Ingwer ist eine scharfe Wurzelknolle."), hint)
        assertTrue(hint.startsWith("Nutzer: "), hint)
        assertTrue(hint.contains("Hoshi: "), hint)
    }

    @Test
    fun `english turn gets the english role label`() {
        val hint = ContextHint.of(listOf(ChatMessage("user", "What is ginger?")), Language.EN)
        assertTrue(hint!!.startsWith("User: "), hint)
    }

    @Test
    fun `empty history yields no hint`() {
        assertNull(ContextHint.of(emptyList(), Language.DE))
    }

    @Test
    fun `a history of bare affirmations and lookup requests yields no hint`() {
        assertNull(
            ContextHint.of(
                listOf(ChatMessage("user", "ja"), ChatMessage("user", "schau online nach")),
                Language.DE,
            ),
        )
    }

    @Test
    fun `the hint is hard-capped and newline-free`() {
        val hint = ContextHint.of(
            listOf(
                ChatMessage("user", "A".repeat(900)),
                ChatMessage("assistant", "B".repeat(900) + "\n\nnoch mehr Zeug"),
            ),
            Language.DE,
        )!!
        assertTrue(hint.length <= ContextHint.MAX_CHARS, "cap breached: ${hint.length}")
        assertFalse(hint.contains('\n'), "hint must stay one flat line")
    }

    @Test
    fun `only the last two exchanges are in reach`() {
        val hint = ContextHint.of(
            listOf(
                ChatMessage("user", "Uraltes Thema Kernfusion"),
                ChatMessage("assistant", "Uralte Antwort"),
                ChatMessage("user", "Was ist Ingwer?"),
                ChatMessage("assistant", "Eine Wurzelknolle."),
            ),
            Language.DE,
        )!!
        assertFalse(hint.contains("Kernfusion"), hint)
        assertTrue(hint.contains("Ingwer"), hint)
    }

    // ── Outbound query shape ─────────────────────────────────────────────────

    @Test
    fun `no hint leaves the query byte-identical`() {
        val q = "Wie hoch ist der Eiffelturm?"
        assertEquals(q, ContextHint.escalationQuery(q, null, Language.DE))
        assertEquals(q, ContextHint.escalationQuery(q, "   ", Language.DE))
    }

    @Test
    fun `a hint puts context before the question`() {
        assertEquals(
            "Kontext: Nutzer: Was ist Ingwer?\nFrage: Wozu isst man ihn denn?",
            ContextHint.escalationQuery("Wozu isst man ihn denn?", "Nutzer: Was ist Ingwer?", Language.DE),
        )
        assertEquals(
            "Context: User: What is ginger?\nQuestion: What do you eat it with?",
            ContextHint.escalationQuery("What do you eat it with?", "User: What is ginger?", Language.EN),
        )
    }
}
