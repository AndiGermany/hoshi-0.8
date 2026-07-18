package de.hoshi.core.pipeline

import de.hoshi.core.dto.RouteCategory
import reactor.core.publisher.Mono

/**
 * **WeatherLocationAskPort** — die Domänen-Naht der Wetter-Orts-Nachfrage
 * (Wetter S3, „hoshi soll nachfragen, wenn kein ort hinterlegt ist"):
 *
 *  - [needsLocation] entscheidet pro Turn, ob eine WETTER-Frage OHNE
 *    konfigurierten Ort vorliegt (Wetter-Absicht + Wissens-Kategorie + kein
 *    expliziter Ort in der Frage + Store leer + Deploy-Seeds auf den
 *    Code-Defaults). Das Kriterium selbst lebt beim Implementor
 *    (`adapters-knowledge`, `WeatherLocationAskAdapter`) — der Orchestrator
 *    kennt nur die Frage „nachfragen ja/nein?".
 *  - [resolveAndStore] löst die Orts-ANTWORT des Folge-Turns auf: Geocode →
 *    persistenter Store (wie der Settings-PUT) → das aufgelöste Label. Leeres
 *    Mono = kein Treffer/nicht erreichbar (der Aufrufer läuft dann als normaler
 *    Turn weiter — best-effort, nie Crash).
 *
 * [NONE] ist der verhaltens-neutrale Default (fragt nie nach, löst nie auf) ⇒
 * ohne Wiring bleibt jeder Pfad byte-identisch — exakt das Muster der anderen
 * Orchestrator-Nähte ([PendingLookupPort.NONE], [LastAreaPort.NONE]).
 */
interface WeatherLocationAskPort {

    /**
     * TRUE gdw. [query] eine Wetter-Frage ist, für die KEIN echter Ort bekannt
     * ist (weder Laufzeit-Store noch Deploy-Seed noch explizit in der Frage) —
     * dann fragt der Orchestrator deterministisch nach, statt einen
     * Wetter-Block mit falschem Default-Ort zu injizieren.
     */
    fun needsLocation(query: String, category: RouteCategory): Boolean

    /**
     * Geocodet [place] und SPEICHERT den Treffer persistent (dieselbe
     * Store-Wahrheit wie der Settings-PUT) — liefert das aufgelöste Label.
     * Leer = kein Treffer / Geocoding nicht erreichbar / Persist fehlgeschlagen
     * (der Implementor schluckt Fehler best-effort und loggt sie).
     */
    fun resolveAndStore(place: String): Mono<String>

    companion object {
        /** Default: fragt nie nach, löst nie auf ⇒ byte-neutral ohne Wiring. */
        val NONE: WeatherLocationAskPort = object : WeatherLocationAskPort {
            override fun needsLocation(query: String, category: RouteCategory): Boolean = false
            override fun resolveAndStore(place: String): Mono<String> = Mono.empty()
        }
    }
}
