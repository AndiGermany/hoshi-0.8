package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Persona
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Beweist das Flag-Gating des [PersonaResolver]:
 *  - Flag ON  → die gewaehlte Persona reist unveraendert (vier distinkte Charaktere).
 *  - Flag OFF → ALLE Personas kollabieren byte-neutral auf [Persona.STANDARD].
 */
class PersonaResolverTest {

    private fun onResolver() = PersonaResolver(personaEnabled = true)
    private fun offResolver() = PersonaResolver(personaEnabled = false)

    @Test
    fun `Flag ON - jede Persona bleibt distinkt`() {
        val r = onResolver()
        for (p in Persona.entries) {
            assertEquals(p, r.resolve(p))
        }
    }

    @Test
    fun `Flag OFF - ALLE Personas kollabieren auf STANDARD (byte-neutral)`() {
        val r = offResolver()
        for (p in Persona.entries) {
            assertEquals(Persona.STANDARD, r.resolve(p))
        }
    }

    @Test
    fun `resolve(ChatRequest) faedelt die Persona-Wahl durch`() {
        // Flag ON: die Body-Persona gewinnt.
        assertEquals(
            Persona.KUMPEL,
            onResolver().resolve(ChatRequest(text = "x", persona = Persona.KUMPEL)),
        )
        // Flag OFF: dieselbe Request kollabiert auf STANDARD.
        assertEquals(
            Persona.STANDARD,
            offResolver().resolve(ChatRequest(text = "x", persona = Persona.KUMPEL)),
        )
        // Default-Request (kein persona-Feld) ist STANDARD, egal welches Flag.
        assertEquals(Persona.STANDARD, onResolver().resolve(ChatRequest(text = "x")))
        assertEquals(Persona.STANDARD, offResolver().resolve(ChatRequest(text = "x")))
    }

    // ── Fallback-Kette (ws-Rand, optionales Frame-Feld): explizit > Store > STANDARD ──

    @Test
    fun `Fallback-Kette Flag ON - explizites Feld gewinnt IMMER (Store wird nie befragt)`() {
        val r = onResolver()
        // Explizit gewählt ⇒ gewinnt über einen abweichenden Store-Wert.
        assertEquals(Persona.KUMPEL, r.resolve(Persona.KUMPEL, storeDefault = Persona.RUHIG))
        // Sogar explizit STANDARD schlägt einen Store — Praezedenz „explizit zuerst".
        assertEquals(Persona.STANDARD, r.resolve(Persona.STANDARD, storeDefault = Persona.RUHIG))
    }

    @Test
    fun `Fallback-Kette Flag ON - kein Feld faellt auf den Server-Store`() {
        val r = onResolver()
        assertEquals(Persona.RUHIG, r.resolve(null, storeDefault = Persona.RUHIG))
    }

    @Test
    fun `Fallback-Kette Flag ON - kein Feld + leerer Store = STANDARD`() {
        val r = onResolver()
        assertEquals(Persona.STANDARD, r.resolve(null, storeDefault = null))
    }

    @Test
    fun `Fallback-Kette Flag OFF - byte-neutral STANDARD, egal Feld oder Store`() {
        val r = offResolver()
        assertEquals(Persona.STANDARD, r.resolve(Persona.KUMPEL, storeDefault = Persona.RUHIG))
        assertEquals(Persona.STANDARD, r.resolve(null, storeDefault = Persona.RUHIG))
        assertEquals(Persona.STANDARD, r.resolve(null, storeDefault = null))
    }
}
