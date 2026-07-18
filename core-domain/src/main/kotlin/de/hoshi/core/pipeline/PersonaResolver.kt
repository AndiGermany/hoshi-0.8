package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.dto.Persona

/**
 * **PersonaResolver** — gatet die client-seitige [Persona]-Wahl am Inbound-Rand
 * (analog zum [LanguageResolver] fuer die Sprache). Flag-gated ueber
 * `HOSHI_PERSONA_ENABLED` (Default OFF):
 *
 *  - Flag OFF (Default) → ALLE Personas kollabieren auf [Persona.STANDARD]
 *    (byte-neutral; der Charakter ist exakt der heutige Grundton, egal was der
 *    Client schickt).
 *  - Flag ON → die gewaehlte Persona reist unveraendert weiter; die vier
 *    Charaktere (Standard/Kumpel/Knapp/Ruhig) sind distinkt.
 *
 * Die Aufloesung am RAND (statt tief im PersonaService) spiegelt das
 * LanguageResolver-Muster: der Controller loest VOR dem Orchestrator auf, sodass
 * die ganze Downstream-Pipeline bei OFF nur STANDARD sieht.
 *
 * @param personaEnabled das Deploy-Flag `HOSHI_PERSONA_ENABLED` (Default OFF).
 */
class PersonaResolver(
    private val personaEnabled: Boolean,
) {

    /** Gatet eine konkrete [Persona]: OFF → [Persona.STANDARD], ON → unveraendert. */
    fun resolve(persona: Persona): Persona =
        if (personaEnabled) persona else Persona.STANDARD

    /** Bequemlichkeit fuer den Chat-Rand: loest direkt aus dem [ChatRequest]. */
    fun resolve(request: ChatRequest): Persona = resolve(request.persona)

    /**
     * **Fallback-Kette fuer Raender mit OPTIONALEM Persona-Feld** (der ws-Rand: das
     * `start`-Frame traegt die Persona nur, wenn das Geraet sie explizit setzt).
     * Praezedenz — exakt Andis Vorgabe „explizites Request-Feld > Server-Store > STANDARD":
     *
     *  1. [persona] (explizit gewaehlt) ⇒ gewinnt IMMER (der Chat-/WebUI-Pfad, der die
     *     Persona explizit schickt, ist damit byte-identisch — der Store wird nie befragt).
     *  2. sonst [storeDefault] (die serverseitig gespeicherte Wahl aus `JsonFilePersonaStore`,
     *     `null` = nie gesetzt) ⇒ der Satellit erbt Andis Server-Setting, ohne selbst ein
     *     Frame-Feld zu senden.
     *  3. sonst [Persona.STANDARD].
     *
     * Danach greift dieselbe Flag-Gate wie oben ([personaEnabled]): bei OFF (Default)
     * kollabiert das Ergebnis byte-neutral auf STANDARD — ein Bestandsgeraet ohne Frame-Feld
     * bei leerem Store bleibt so exakt beim heutigen STANDARD.
     *
     * Reine Wertelogik, kein I/O: den Store-Wert liest der Aufrufer am Rand (der Store-Cache),
     * `core-domain` bleibt Spring-/Adapter-frei.
     */
    fun resolve(persona: Persona?, storeDefault: Persona?): Persona =
        resolve(persona ?: storeDefault ?: Persona.STANDARD)
}
