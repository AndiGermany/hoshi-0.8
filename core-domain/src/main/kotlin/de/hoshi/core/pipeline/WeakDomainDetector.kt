package de.hoshi.core.pipeline

/**
 * Erkennt **Query-Kategorien**, bei denen das lokale Brain zuverlässig schwach
 * ist (Rezepte/Kochen/Backen + mehrschrittige How-Tos) — PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger (`de.hoshi.app.cloud.WeakDomainDetector`).
 *
 * Reine Heuristik (keine Infra) → 1:1 mitportiert, damit die [HonestyGate]-Policy
 * ihre Recipe-Klasse ohne Fake testen kann. De-Spring't: kein `@Component`, kein
 * Logging. Implementiert [WeakDomainSignal].
 *
 * **Konservativ — zwei Gates:** (1) ein Domänen-Marker muss matchen; (2) bei
 * How-To-Verben muss zusätzlich ein „wie …"-/„anleitung"-/„schritt"-Kontext da
 * sein, damit „ich installiere gerade ein Update" (Aussage) NICHT triggert.
 *
 * **EN/ES/FR/IT (Andi-Befund 2026-07-25):** ~770 Antwort-Sätze waren übersetzt,
 * aber die ERKENNER blieben deutsch — dieselbe Lehre wie beim
 * [OnlineRequestDetector]. Alle vier Marker-Listen sind jetzt sprachübergreifend
 * (kein `Language`-Parameter nötig — [detect] kennt keine Turn-Sprache, prüft
 * also über alle Sprachen hinweg, wie es die Substring-Listen unten schon für
 * DE+EN tun). Spanisch bewusst NUR mit Akzent („cómo"), da das unbetonte „como"
 * die Präposition/Konjunktion „als/wie/da" ist — ein Verwechsler hier würde
 * Gate 2 an normaler spanischer Prosa aufreißen.
 */
class WeakDomainDetector : WeakDomainSignal {

    enum class Domain { RECIPE, HOWTO }

    data class Detection(
        val matched: Boolean,
        val domain: Domain?,
        val trigger: String?,
    ) {
        companion object {
            val NONE = Detection(matched = false, domain = null, trigger = null)
        }
    }

    /** Marker für Rezept-/Koch-Domäne. Treffer reicht allein (eindeutig genug). */
    private val recipeMarkers: List<String> = listOf(
        "rezept", "zutaten",
        "wie backe ich", "wie back ich", "wie koche ich", "wie mache ich",
        "wie bereite ich", "wie zubereite", "zubereiten",
        "wie brate ich", "wie grille ich",
        // EN/ES/FR/IT — direkte Rezept-Nomen sind kollisionsfrei; die Verb-Phrasen
        // NUR mit kochspezifischen Verben (cook/bake bzw. cocinar/hornear/cuisiner/
        // cucinare). BEWUSST NICHT das generische „machen/hacer/faire/fare" wie das
        // DE-Pendant „wie mache ich" — das ist ein Bestands-Fall (s. Klassen-KDoc),
        // kein Vorbild für neue Sprachen (s. RESULT-Zeile des Auftrags).
        "recipe", "ingredients", "how do i cook", "how do i bake",
        "receta", "ingredientes", "cómo cocino", "cómo horneo",
        "recette", "ingrédients", "comment je cuisine",
        "ricetta", "ingredienti", "come cucino",
    )

    /** How-To-Verben — matchen NUR mit [howToContext] (sonst Aussage-Fehltrigger). */
    private val howToVerbs: List<String> = listOf(
        "repariere", "reparier", "installiere", "installier",
        "baue", "zusammenbaue", "montiere", "montier",
        "wechsle", "wechsel", "tausche", "verlege", "entferne",
        "richte ein", "konfiguriere", "konfigurier",
        // EN/ES/FR/IT — dieselbe DIY-/Technik-Domäne, immer gegated durch
        // [howToContext] (kein Alleingang wie bei [recipeMarkers]).
        "repair", "fix", "install", "build", "assemble", "replace", "remove", "set up", "configure",
        "reparar", "reparo", "instalar", "instalo", "construir", "construyo",
        "montar", "monto", "cambiar", "cambio", "quitar", "quito", "configurar", "configuro",
        "réparer", "répare", "installer", "installe", "construire", "construis",
        "monter", "monte", "changer", "change", "enlever", "enlève", "configurer", "configure",
        "riparare", "riparo", "installare", "installo", "costruire", "costruisco",
        "montare", "cambiare", "togliere", "tolgo", "configurare",
    )

    /** Kontext-Marker, die eine How-To-Frage von einer Aussage unterscheiden. */
    private val howToContext: List<String> = listOf(
        "wie ", "anleitung", "schritt für schritt", "schritte",
        "wie kann ich", "wie geht",
        // EN/ES/FR/IT — ES bewusst NUR „cómo" MIT Akzent (s. Klassen-KDoc): das
        // unbetonte „como" ist Präposition/Konjunktion, kein Frage-Wort.
        "how ", "instructions", "step by step", "steps", "how can i", "how does it work",
        "cómo ", "instrucciones", "paso a paso", "pasos", "cómo puedo", "cómo funciona",
        "comment ", "étape par étape", "étapes", "comment puis-je", "comment ça marche",
        "come ", "istruzioni", "passo dopo passo", "passi", "come posso", "come funziona",
    )

    /** Smart-Home-Imperative — defensiver Ausschluss (sollten eh vor Route B raus). */
    private val haImperatives: List<String> = listOf(
        "mach das ", "mach die ", "mach den ", "schalte ",
        "stell die ", "stell den ", "dimm ", "spiel ",
        "starte das", "starte die",
        // EN/ES/FR/IT — rein defensiv: Gate 0 liefert bei Treffer NIE WeakDomain,
        // ein zusätzlicher Eintrag kann also nur einen Fehltrigger VERHINDERN, nie
        // einen neuen erzeugen — hier ist Breite ausdrücklich sicher.
        "turn on the", "turn off the", "switch on the", "switch off the", "set the", "dim ", "play ", "start the",
        "enciende el", "enciende la", "apaga el", "apaga la", "pon el", "pon la", "atenúa", "reproduce",
        "allume le", "allume la", "éteins le", "éteins la", "règle le", "règle la", "joue ",
        "accendi il", "accendi la", "spegni il", "spegni la", "imposta il", "imposta la", "attenua", "riproduci",
    )

    /** Scannt die **Original-Query** (nicht die Antwort). */
    fun detect(query: String): Detection {
        if (query.isBlank()) return Detection.NONE
        val q = query.lowercase()

        // Gate 0: HA-Imperative haben Vorrang → niemals WeakDomain.
        if (haImperatives.any { q.contains(it) }) return Detection.NONE

        // Recipe: Marker-Treffer reicht.
        val recipeHit = recipeMarkers.firstOrNull { q.contains(it) }
        if (recipeHit != null) {
            return Detection(matched = true, domain = Domain.RECIPE, trigger = recipeHit)
        }

        // HowTo: Verb-Treffer NUR mit Kontext.
        val howToVerb = howToVerbs.firstOrNull { q.contains(it) }
        if (howToVerb != null && howToContext.any { q.contains(it) }) {
            return Detection(matched = true, domain = Domain.HOWTO, trigger = howToVerb)
        }

        return Detection.NONE
    }

    /** Convenience: bool-only — erfüllt [WeakDomainSignal]. */
    override fun isWeakDomain(text: String): Boolean = detect(text).matched
}
