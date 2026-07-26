package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language
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
    private val nachgeschlagen: GroundingPort = GroundingPort.EMPTY,
) : GroundingPort {

    /**
     * EIN Körper für die EINE Port-Signatur (entdoppelt 2026-07-25: vorher trug
     * diese Klasse zwei handkopierte Zwillinge, einen je Overload — der
     * 2-Arg-Zwilling war toter Prod-Code, den nur Tests noch trafen). Reicht
     * [language] 1:1 an ALLE drei Scheiben weiter (Wetter/Nachgeschlagen/Wiki) —
     * jede Scheibe entscheidet selbst, ob sie die Sprache nutzt (aktuell nur
     * [WeatherGroundingProvider]; die anderen ignorieren sie sichtbar).
     */
    override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
        weather.groundingBlock(query, category, language)
            .defaultIfEmpty("")
            .flatMap { w ->
                if (w.isNotBlank()) {
                    Mono.just(w)
                } else {
                    nachgeschlagen.groundingBlock(query, category, language).defaultIfEmpty("")
                }
            }
            .flatMap { n ->
                if (n.isNotBlank()) Mono.just(n) else wiki.groundingBlock(query, category, language)
            }
            // Vordere Scheiben sollten selbst nie werfen (best-effort), aber doppelt
            // genäht: ein Fehler dort fällt sauber zur Wiki-Scheibe durch.
            .onErrorResume { wiki.groundingBlock(query, category, language) }

    /**
     * Enger lokaler Wissenspfad: ausschließlich die Wiki-Scheibe darf liefern.
     * Wetter ist Zustandswissen, [nachgeschlagen] kann aus einer früheren
     * Cloud-Eskalation stammen — beides wäre unter dem Label „lokales Wissen"
     * unehrlich. Der default-deny-Unterport der Wiki entscheidet seinerseits,
     * ob sie als belegbar lokale Quelle optiert hat.
     */
    override fun localKnowledgeBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
        wiki.localKnowledgeBlock(query, category, language)
            .defaultIfEmpty("")
            .onErrorReturn("")
}
