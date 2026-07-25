package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import kotlin.random.Random

/**
 * Ein deterministisches Detektor-Signal für die [HonestyGate]: ob die Frage in
 * eine Gate-Klasse fällt ([matched]) und ob die Wissens-Bridge gerade tot ist
 * ([bridgeDown] → ehrlich „nicht erreichbar" statt „gibt's nicht").
 */
data class HonestySignal(val matched: Boolean, val bridgeDown: Boolean = false) {
    companion object {
        val NONE = HonestySignal(matched = false, bridgeDown = false)
    }
}

/** Weak-Domain-Naht (Rezept/How-To — lokal notorisch schwach). Pure Heuristik. */
fun interface WeakDomainSignal {
    fun isWeakDomain(text: String): Boolean
}

/** Online-Request-Naht (explizite „schau online nach"-Bitte). Pure Heuristik. */
fun interface OnlineRequestSignal {
    fun isOnlineRequest(text: String): Boolean
}

/**
 * Existenz-Claim-Naht (Zahl+Substantiv-Existenzfrage, „gibt es einen 11-Euro-
 * Schein?"). Probt die Wissens-Bridge → Infra; bleibt als Port draußen.
 */
fun interface ExistenceClaimSignal {
    fun detect(text: String): HonestySignal
}

/**
 * Named-Entity-Naht (unbekannter Eigenname, „Wer ist Neelix?"). Probt die Wiki-
 * Bridge synchron → Infra; bleibt als Port draußen.
 */
fun interface NamedEntitySignal {
    fun detect(text: String): HonestySignal
}

/**
 * Deterministisches **Ehrlichkeits-Gate VOR dem Brain** (PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger). Anti-Konfabulation: bei Klassen, in denen
 * das lokale Brain notorisch Murks liefert (Rezepte/How-Tos) oder die es nicht
 * wissen KANN (explizite Online-Recherche, fragwürdige Existenz-Claims), wird
 * **vor** dem LLM-Call entschieden — rein deterministisch, kein zweites LLM.
 *
 * Entkoppelt von Spring + Infra: die vier Bestands-Detektoren werden als schmale
 * Ports ([WeakDomainSignal]/[OnlineRequestSignal]/[ExistenceClaimSignal]/
 * [NamedEntitySignal]) injiziert; `SkillRegistry.isEnabled(...)` wird zum
 * [cloudEnabled]-Supplier (heute: per-Turn-Verfügbarkeit aus Decke, Modus, Key
 * und Tagesbudget), `HoshiProperties.routing.disambigAskBackEnabled` zum
 * [disambigAskBackEnabled]-Flag. Die infra-koppelnden Detektoren (Existence/Named-
 * Entity proben die Wissens-Bridge) bleiben als Port draußen — die reinen
 * Heuristiken ([WeakDomainDetector]/[OnlineRequestDetector]) sind mitportiert.
 * Reines Kotlin, kein `@Component` — das Wiring kommt im Orchestrator.
 *
 * Klassifikation bewusst konservativ (lieber durchlassen als fälschlich blocken).
 *
 * **Mehrsprachig seit 2026-07-25 (Andi: „Multilingualität von A-Z"):** die
 * KLASSIFIKATION bleibt bewusst das geteilte, fein kalibrierte DE+EN-Regelwerk der
 * vier Detektoren — nur der GESPROCHENE Ehrlichkeits-Satz kommt jetzt aus dem
 * [de.hoshi.core.pipeline.lang.LanguagePack] der Turn-Sprache ([assess]). Vorher
 * feuerten die Phrasen hart deutsch, auch bei `language = EN`. Der Default
 * [Language.DEFAULT] hält jeden Bestands-Aufrufer byte-identisch.
 */
class HonestyGate(
    private val weakDomain: WeakDomainSignal,
    private val onlineRequest: OnlineRequestSignal,
    private val existenceClaim: ExistenceClaimSignal,
    private val namedEntity: NamedEntitySignal,
    private val cloudEnabled: () -> Boolean,
    private val disambigAskBackEnabled: Boolean = false,
) {

    /**
     * Ergebnis der Vorschalt-Prüfung.
     *  - [Pass]: kein Gate-Fall → normaler Brain-Flow.
     *  - [Refuse]: ehrliche Absage (Cloud aus). [phrase] wird gesprochen, der Brain
     *    wird GAR NICHT aufgerufen.
     *  - [AskConsent]: Cloud verfügbar → erst Consent-Frage.
     *  - [AskConsentExplicit]: wie [AskConsent], aber für eine **explizite** Online-
     *    Bitte (greift die Bitte auf statt redundant „Soll ich?" zu fragen).
     */
    sealed class Verdict {
        object Pass : Verdict()
        data class Refuse(val phrase: String) : Verdict()
        object AskConsent : Verdict()
        object AskConsentExplicit : Verdict()
    }

    private enum class Kind { ONLINE_REQUEST, RECIPE, EXISTENCE_CLAIM, EXISTENCE_NAMED_ENTITY, BRIDGE_DOWN }

    /**
     * Die Vorschalt-Prüfung. [language] wählt NUR die gesprochene Phrase (die
     * Klassifikation ist sprach-übergreifend, s. Klassen-KDoc); Default
     * [Language.DEFAULT] ⇒ jeder Bestands-Aufrufer bleibt byte-identisch deutsch.
     */
    fun assess(text: String, language: Language = Language.DEFAULT): Verdict {
        val kind = classify(text) ?: return Verdict.Pass
        // Mehrdeutige Eigennamen-Fragen: wenn disambig-ask-back aktiv ist, an den
        // Disambig-Flow delegieren (pendingOptions setzen) statt statisch ablehnen.
        if (kind == Kind.EXISTENCE_NAMED_ENTITY && disambigAskBackEnabled) {
            return Verdict.Pass
        }
        // Bridge-tot ist ein lokaler Infrastrukturfehler, KEINE Einladung zu
        // einem externen Privacy-Wechsel. Auch bei verfügbarem Nachschlag bleibt
        // deshalb die bestehende ehrliche Reachability-Phrase maßgeblich.
        if (kind == Kind.BRIDGE_DOWN) return Verdict.Refuse(refusalPhrase(kind, language))
        val lookupAvailable = cloudEnabled()
        if (lookupAvailable) {
            // Explizite Online-Bitte → aufgreifende Consent-Frage statt redundantem „Soll ich?".
            return if (kind == Kind.ONLINE_REQUEST) Verdict.AskConsentExplicit else Verdict.AskConsent
        }
        // Cloud aus → ehrlich absagen statt den Brain raten lassen.
        return Verdict.Refuse(refusalPhrase(kind, language))
    }

    /**
     * Deterministische Klasse oder null. Reihenfolge ist load-bearing: explizite
     * Online-Bitte ZUERST (gewinnt über Existence/Recipe), dann Recipe, dann
     * Existence-Claim, dann Named-Entity. Bridge-tot wird ehrlich anders behandelt
     * als „existiert nicht".
     */
    private fun classify(text: String): Kind? {
        if (text.isBlank()) return null
        if (onlineRequest.isOnlineRequest(text)) return Kind.ONLINE_REQUEST
        if (weakDomain.isWeakDomain(text)) return Kind.RECIPE
        val existence = existenceClaim.detect(text)
        if (existence.matched) {
            return if (existence.bridgeDown) Kind.BRIDGE_DOWN else Kind.EXISTENCE_CLAIM
        }
        val named = namedEntity.detect(text)
        if (named.matched) {
            return if (named.bridgeDown) Kind.BRIDGE_DOWN else Kind.EXISTENCE_NAMED_ENTITY
        }
        return null
    }

    /**
     * Die gesprochene Ehrlichkeits-Phrase der erkannten [kind] in [language] —
     * EINE Quelle je Sprache ([de.hoshi.core.pipeline.lang.LanguagePack]), damit ein
     * Übersetzer-Pod NUR seine `LangXx.kt` anfasst. Die DE-Pools sind WORT-FÜR-WORT
     * dieselben wie vorher hier (byte-identisch, s. [de.hoshi.core.pipeline.lang.LangDe]).
     */
    private fun refusalPhrase(kind: Kind, language: Language): String {
        val pack = LanguagePackRegistry.forLanguage(language)
        return when (kind) {
            Kind.ONLINE_REQUEST -> pack.honestyOnlineRequestRefusals.random(rnd)
            Kind.RECIPE -> pack.honestyRecipeRefusals.random(rnd)
            Kind.EXISTENCE_CLAIM -> pack.honestyExistenceRefusals.random(rnd)
            Kind.EXISTENCE_NAMED_ENTITY -> pack.honestyNamedEntityRefusals.random(rnd)
            Kind.BRIDGE_DOWN -> pack.honestyBridgeDownRefusals.random(rnd)
        }
    }

    private val rnd = Random(System.nanoTime())
}
