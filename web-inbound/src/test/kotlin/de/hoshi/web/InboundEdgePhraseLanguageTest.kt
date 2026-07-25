package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

/**
 * **Die Absagen am Inbound-RAND sprechen die Turn-/Session-Sprache** (Andi
 * 2026-07-25: „Multilingualität von A-Z, auch alles fest Verdrahtete"):
 *
 *  - `/ws/audio`: Audio-Cap („zu lang am Stück") und Session-Guard („kein
 *    Ende-Signal") — ein englisch sprechender Satellit darf beim Abbruch nicht
 *    plötzlich deutsch abgewiesen werden.
 *  - [BrainAdmissionGate]: die Über-Kapazität-Absage. Kein Defekt, sondern
 *    „gleich wieder da" — und zwar in der Sprache des Turns.
 *
 * DE bleibt in allen drei Fällen byte-identisch (die Bestands-Konstanten sind
 * jetzt der DE-Zeiger auf die EINE Sprachpaket-Quelle und werden hier wörtlich
 * gepinnt).
 */
class InboundEdgePhraseLanguageTest {

    // ── DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Rand-Absagen sind BYTE-IDENTISCH zum bisherigen Bestand`() {
        assertEquals(
            "Das war mir zu lang am Stück — sag es bitte etwas kürzer, dann krieg ich's zuverlässig mit.",
            AudioWebSocketHandler.AUDIO_CAP_MESSAGE,
        )
        assertEquals(
            "Die Aufnahme lief mir zu lange ohne Ende-Signal — magst du es nochmal etwas kürzer versuchen?",
            AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE,
        )
        assertEquals(
            "Ich bin gerade an einer anderen Anfrage dran — gib mir einen kurzen Moment und frag gleich nochmal.",
            BrainAdmissionGate.DEFAULT_REJECT_PHRASE,
        )
        assertEquals(AudioWebSocketHandler.AUDIO_CAP_MESSAGE, AudioWebSocketHandler.audioCapMessage(Language.DE))
        assertEquals(
            AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE,
            AudioWebSocketHandler.sessionTooLongMessage(Language.DE),
        )
    }

    // ── `/ws/audio`-Absagen je Sprache ───────────────────────────────────────

    @Test
    fun `Audio-Cap-Absage kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertEquals(pack.audioCapTooLong, AudioWebSocketHandler.audioCapMessage(language), "$language")
            assertEquals(pack.audioNoEndSignal, AudioWebSocketHandler.sessionTooLongMessage(language), "$language")
            assertFalse(pack.audioCapTooLong.isBlank(), "$language: nie leer (never-silent)")
            assertFalse(pack.audioNoEndSignal.isBlank(), "$language: nie leer (never-silent)")
        }
    }

    @Test
    fun `keine Nicht-DE-Sprache bekommt mehr die deutschen Rand-Absagen`() {
        for (language in Language.entries - Language.DE) {
            assertFalse(
                AudioWebSocketHandler.audioCapMessage(language) == AudioWebSocketHandler.AUDIO_CAP_MESSAGE,
                "$language sprach am Audio-Cap noch deutsch",
            )
            assertFalse(
                AudioWebSocketHandler.sessionTooLongMessage(language) == AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE,
                "$language sprach am Session-Guard noch deutsch",
            )
        }
    }

    // ── Admission-Gate: die gesprochene Absage je Sprache ────────────────────

    @Test
    fun `Ueber-Kapazitaet-Absage kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val expected = LanguagePackRegistry.forLanguage(language).admissionBusy
            assertEquals(expected, rejectedPhrase(language), "$language")
        }
    }

    @Test
    fun `Ueber-Kapazitaet-Absage ohne Sprach-Argument bleibt deutsch (Bestands-Aufrufer byte-neutral)`() {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1)
        val blocking = gate.gate { Flux.never() }
        blocking.subscribe()
        val phrase = textOf(gate.gate { Flux.just<ChatEvent>(ChatEvent.Done(provider = "BRAIN")) })
        assertEquals(BrainAdmissionGate.DEFAULT_REJECT_PHRASE, phrase)
    }

    @Test
    fun `eine gesetzte Betreiber-Phrase gewinnt in JEDER Sprache`() {
        val custom = "Kurz Geduld, ich bin gleich wieder frei."
        for (language in Language.entries) {
            val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1, rejectPhrase = custom)
            gate.gate(language) { Flux.never<ChatEvent>() }.subscribe()
            val phrase = textOf(gate.gate(language) { Flux.just<ChatEvent>(ChatEvent.Done(provider = "BRAIN")) })
            assertEquals(custom, phrase, "$language: eine bewusste Betreiber-Wahl übersetzt niemand weg")
        }
    }

    @Test
    fun `das Gate bleibt bei OFF ein reiner Passthrough - in jeder Sprache`() {
        val gate = BrainAdmissionGate(enabled = false, maxConcurrent = 1)
        for (language in Language.entries) {
            StepVerifier.create(gate.gate(language) { Flux.just<ChatEvent>(ChatEvent.Done(provider = "BRAIN")) })
                .expectNextCount(1)
                .verifyComplete()
        }
        assertTrue(gate.rejectedCount() == 0, "OFF darf nie ablehnen")
    }

    // ── Helfer ───────────────────────────────────────────────────────────────

    /** Belegt das einzige Permit und liest die gesprochene Absage des zweiten Turns. */
    private fun rejectedPhrase(language: Language): String {
        val gate = BrainAdmissionGate(enabled = true, maxConcurrent = 1)
        gate.gate(language) { Flux.never<ChatEvent>() }.subscribe()
        return textOf(gate.gate(language) { Flux.just<ChatEvent>(ChatEvent.Done(provider = "BRAIN")) })
    }

    private fun textOf(events: Flux<ChatEvent>): String =
        events.collectList().block()!!
            .filterIsInstance<ChatEvent.TextDelta>()
            .joinToString("") { it.text }

    /** Sanity: die DE-Quelle ist wirklich das Sprachpaket (kein zweites Literal im Code). */
    @Test
    fun `die DE-Rand-Phrasen zeigen auf das DE-Sprachpaket`() {
        assertEquals(LangDe.PACK.audioCapTooLong, AudioWebSocketHandler.AUDIO_CAP_MESSAGE)
        assertEquals(LangDe.PACK.audioNoEndSignal, AudioWebSocketHandler.SESSION_TOO_LONG_MESSAGE)
        assertEquals(LangDe.PACK.admissionBusy, BrainAdmissionGate.DEFAULT_REJECT_PHRASE)
    }
}
