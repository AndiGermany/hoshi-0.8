package de.hoshi.web

import de.hoshi.core.dto.Language
import de.hoshi.core.port.SttPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * **SttReadinessGateTest** — beweist das 0.5-portierte STT-Readiness-Gate OHNE Live-Infra:
 *
 *  - Flag ON + `whisper-stt` DOWN ⇒ KEIN `transcribe`-Call, sofort leeres Transkript
 *    (der warme `no_input`-Pfad im Controller greift statt ~30 s Timeout).
 *  - Flag ON + STT OK ⇒ normal transkribiert (Delegate aufgerufen, sein Transkript fließt).
 *  - Flag OFF ⇒ IMMER normal (Pass-Through, byte-neutral — selbst bei DOWN).
 *  - STT UNKNOWN (Watchdog aus / noch kein Probe-Lauf ⇒ Status `null`) ⇒ normal probiert.
 *  - STT DEGRADED ⇒ normal (nur eindeutiges DOWN löst das Gate aus).
 *
 * Der Delegate ist ein [RecordingSttPort], der festhält OB er aufgerufen wurde — so wird
 * „kein 30 s-Warten" als „kein transcribe-Call" direkt bewiesen.
 */
class SttReadinessGateTest {

    /** Fake-STT: merkt sich, ob es aufgerufen wurde, und liefert ein kanned Transkript. */
    private class RecordingSttPort(private val result: String = "hallo hoshi") : SttPort {
        @Volatile var called: Boolean = false
        override fun transcribe(audioWav: ByteArray, language: Language?): Mono<String> {
            called = true
            return Mono.just(result)
        }
    }

    private val audio = byteArrayOf(1, 2, 3, 4)

    private fun gate(delegate: SttPort, enabled: Boolean, status: String?) =
        SttReadinessGate(delegate = delegate, healthStatus = { name ->
            if (name == SttReadinessGate.WHISPER_STT) status else null
        }, enabled = enabled)

    @Test
    fun `Flag ON + STT DOWN — kein transcribe-Call, leeres Transkript`() {
        val delegate = RecordingSttPort()
        val gate = gate(delegate, enabled = true, status = "DOWN")

        val transcript = gate.transcribe(audio, Language.DE).block()

        assertEquals("", transcript, "DOWN ⇒ sofortiges leeres Transkript")
        assertFalse(delegate.called, "der echte STT wird NICHT gerufen (kein 30s-Timeout)")
    }

    @Test
    fun `Flag ON + STT OK — normal transkribiert`() {
        val delegate = RecordingSttPort(result = "guten morgen")
        val gate = gate(delegate, enabled = true, status = "OK")

        val transcript = gate.transcribe(audio, Language.DE).block()

        assertEquals("guten morgen", transcript, "OK ⇒ das echte Transkript fließt")
        assertTrue(delegate.called, "der echte STT wird gerufen")
    }

    @Test
    fun `Flag OFF — immer normal (Pass-Through, byte-neutral), selbst bei DOWN`() {
        val delegate = RecordingSttPort(result = "egal")
        val gate = gate(delegate, enabled = false, status = "DOWN")

        val transcript = gate.transcribe(audio, Language.DE).block()

        assertEquals("egal", transcript, "Flag OFF ⇒ delegiert IMMER, auch bei DOWN")
        assertTrue(delegate.called, "Flag OFF ⇒ der echte STT wird immer gerufen")
    }

    @Test
    fun `STT UNKNOWN (Watchdog aus) — normal transkribiert`() {
        val delegate = RecordingSttPort(result = "noch da")
        val gate = gate(delegate, enabled = true, status = null) // null = UNKNOWN

        val transcript = gate.transcribe(audio, Language.DE).block()

        assertEquals("noch da", transcript, "UNKNOWN ≠ DOWN ⇒ lieber 1x normal probieren")
        assertTrue(delegate.called, "UNKNOWN ⇒ der echte STT wird gerufen")
    }

    @Test
    fun `STT DEGRADED — normal (nur DOWN loest das Gate aus)`() {
        val delegate = RecordingSttPort(result = "trotzdem")
        val gate = gate(delegate, enabled = true, status = "DEGRADED")

        val transcript = gate.transcribe(audio, Language.DE).block()

        assertEquals("trotzdem", transcript, "DEGRADED ⇒ noch erreichbar ⇒ normal probieren")
        assertTrue(delegate.called, "DEGRADED ⇒ der echte STT wird gerufen")
    }
}
