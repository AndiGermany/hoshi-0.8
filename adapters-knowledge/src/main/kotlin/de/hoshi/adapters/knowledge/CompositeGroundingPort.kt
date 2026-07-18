package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import reactor.core.publisher.Mono

/**
 * **CompositeGroundingPort** — kombiniert mehrere Grounding-Scheiben hinter EINEM
 * [GroundingPort], ohne den [de.hoshi.core.pipeline.TurnPromptAssembler] oder den
 * Router zu ändern (der Assembler sieht weiter genau einen Port) und OHNE einen
 * zweiten Brain-Call.
 *
 * Strategie: **Wetter zuerst, dann Nachgeschlagen, dann Wiki.** Für eine
 * Wetter-Frage liefert [weather] einen Block → er gewinnt und die weiteren
 * Scheiben werden gar nicht erst angefragt (eine Wikipedia-Passage über „Wetter"
 * wäre nur Rauschen). Liefert [weather] nichts, prüft [nachgeschlagen] (Extended
 * Think S3 — „einmal bezahlt, für immer gewusst": deckt eine frühere,
 * bezahlte Cloud-Eskalation dieselbe Frage, deckt dieser Cache-Hit das Grounding
 * VOR jeder erneuten Eskalation). Liefert auch das nichts, fällt der Composite
 * zur unveränderten [wiki]-Scheibe durch — der bestehende Grounding-Pfad bleibt
 * damit 1:1 erhalten. **Default [nachgeschlagen] ist ein Leer-Stub** (`Mono.just("")`)
 * ⇒ ohne explizite dritte Scheibe ist die Kette byte-identisch zur alten
 * Zwei-Scheiben-Strategie — bestehende Aufrufer mit 2-Argument-Konstruktor
 * (`CompositeGroundingPort(weather, wiki)`) bleiben unverändert kompilierbar
 * UND verhaltensgleich.
 *
 * **Best-effort:** ein Fehler in einer der vorderen Scheiben darf den Turn nie
 * kippen → wir fallen dann zur Wiki-Scheibe durch. Das Kategorie-Gate liegt in
 * den einzelnen Scheiben (alle drei grounden nur Wissens-Kategorien).
 */
class CompositeGroundingPort(
    private val weather: GroundingPort,
    private val wiki: GroundingPort,
    /**
     * Extended Think S3 (Cache-Hit-vor-Cloud-Schicht). Default: verhaltens-neutraler
     * Leer-Stub — ohne explizite dritte Scheibe (z.B. Decke `HOSHI_EXTENDED_THINK_ENABLED`
     * zu) ist die Kette byte-identisch zur alten Zwei-Scheiben-Strategie.
     */
    private val nachgeschlagen: GroundingPort = GroundingPort { _, _ -> Mono.just("") },
) : GroundingPort {

    override fun groundingBlock(query: String, category: RouteCategory): Mono<String> =
        weather.groundingBlock(query, category)
            .defaultIfEmpty("")
            .flatMap { w ->
                if (w.isNotBlank()) Mono.just(w) else nachgeschlagen.groundingBlock(query, category).defaultIfEmpty("")
            }
            .flatMap { n ->
                if (n.isNotBlank()) Mono.just(n) else wiki.groundingBlock(query, category)
            }
            // Vordere Scheiben sollten selbst nie werfen (best-effort), aber doppelt
            // genäht: ein Fehler dort fällt sauber zur Wiki-Scheibe durch.
            .onErrorResume { wiki.groundingBlock(query, category) }
}
