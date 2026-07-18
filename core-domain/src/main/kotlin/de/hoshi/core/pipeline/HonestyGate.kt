package de.hoshi.core.pipeline

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
 * [cloudEnabled]-Supplier, `HoshiProperties.routing.disambigAskBackEnabled` zum
 * [disambigAskBackEnabled]-Flag. Die infra-koppelnden Detektoren (Existence/Named-
 * Entity proben die Wissens-Bridge) bleiben als Port draußen — die reinen
 * Heuristiken ([WeakDomainDetector]/[OnlineRequestDetector]) sind mitportiert.
 * Reines Kotlin, kein `@Component` — das Wiring kommt im Orchestrator.
 *
 * Klassifikation bewusst konservativ (lieber durchlassen als fälschlich blocken).
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

    fun assess(text: String): Verdict {
        val kind = classify(text) ?: return Verdict.Pass
        // Mehrdeutige Eigennamen-Fragen: wenn disambig-ask-back aktiv ist, an den
        // Disambig-Flow delegieren (pendingOptions setzen) statt statisch ablehnen.
        if (kind == Kind.EXISTENCE_NAMED_ENTITY && disambigAskBackEnabled) {
            return Verdict.Pass
        }
        val cloudOn = cloudEnabled()
        if (cloudOn) {
            // Explizite Online-Bitte → aufgreifende Consent-Frage statt redundantem „Soll ich?".
            return if (kind == Kind.ONLINE_REQUEST) Verdict.AskConsentExplicit else Verdict.AskConsent
        }
        // Cloud aus → ehrlich absagen statt den Brain raten lassen.
        return Verdict.Refuse(refusalPhrase(kind))
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

    private fun refusalPhrase(kind: Kind): String = when (kind) {
        Kind.ONLINE_REQUEST -> ONLINE_REQUEST_REFUSALS.random(rnd)
        Kind.RECIPE -> RECIPE_REFUSALS.random(rnd)
        Kind.EXISTENCE_CLAIM -> EXISTENCE_REFUSALS.random(rnd)
        Kind.EXISTENCE_NAMED_ENTITY -> NAMED_ENTITY_REFUSALS.random(rnd)
        Kind.BRIDGE_DOWN -> BRIDGE_DOWN_REFUSALS.random(rnd)
    }

    private val rnd = Random(System.nanoTime())

    companion object {
        // Explizite „schau online nach"-Bitte bei Cloud-AUS. Haltung, KEIN Defekt —
        // nie „ich kann nicht". Bietet sofort das eigene Wissen an. Die interne
        // Vokabel „lokal" bleibt DRAUSSEN (gehört nie in Hoshis Stimme).
        private val ONLINE_REQUEST_REFUSALS = listOf(
            "Ins offene Netz geh ich bewusst nicht — ich bleib bei dir. Aber in meinem eigenen Wissen schau ich gern nach: was genau suchst du?",
            "Da raus ins Internet will ich gar nicht — dafür hab ich 'nen ganzen Wissensspeicher hier. Soll ich da für dich nachsehen?",
            "Online unterwegs bin ich absichtlich nicht. Was ich aber hab, ist mein eigenes Wissen — sag mir, wonach, dann schau ich nach.",
            "Das Internet lass ich bewusst zu — aber ich hab ne Menge selbst gespeichert. Lass mich da für dich nachschlagen, okay?",
            "Nach draußen geh ich nicht, das ist Absicht. In meinem eigenen Wissen werd ich aber gern für dich fündig — was brauchst du?",
        )

        // Sara-Refusals: ehrlich, warm, KEIN Engine-Sprech.
        private val RECIPE_REFUSALS = listOf(
            "Kochen ist nicht meine Stärke — da führ ich dich in die Irre.",
            "Beim Rezept würd ich raten, und das wär dir keine Hilfe.",
        )

        // Existenz-Claims mit Zahl-Entity: ehrlich-zweifelnd statt erfunden-bestätigend.
        private val EXISTENCE_REFUSALS = listOf(
            "Halt — da bin ich nicht sicher, ob's das wirklich gibt. Ich würd dir lieber nichts erfinden.",
            "Gute Frage — bei sowas verlass ich mich ungern auf mein Bauchgefühl. Lieber sag ich's ehrlich: weiß ich nicht.",
            "Ehrlich? Da bin ich raus. Sowas würd ich gerne nachschauen statt raten.",
        )

        // Warme, neugierige Repair-Phrasen bei unbekanntem Eigennamen. Kurz, zugewandt, fragend.
        private val NAMED_ENTITY_REFUSALS = listOf(
            "Hmm, der Name sagt mir gerade nichts. Klingt nach jemandem aus einer bestimmten Szene — wer genau ist das?",
            "Sag mir mehr — Musik, Film, Geschichte, Sport? Bei dem Namen tappe ich grade im Dunkeln.",
            "Ich kenn den Namen nicht — magst du mir was dazu sagen?",
            "Ehrlich, da hab ich nichts zu — woher kennst du den Namen?",
        )

        // NICHT „gibt's nicht", sondern ehrlich „komm grad nicht an mein Wissen" (Bridge tot).
        private val BRIDGE_DOWN_REFUSALS = listOf(
            "Ich komm gerade nicht an meinen Wissensspeicher — das kann ich dir verlässlich erst gleich sagen. Magst du's in einem Moment nochmal fragen?",
            "Hm, mein Nachschlagewerk ist im Moment nicht erreichbar. Ich will dir nichts raten — frag mich gleich nochmal, dann schau ich richtig nach.",
            "Da häng ich grad — mein Wissensspeicher antwortet nicht. Gib mir einen Moment, dann kann ich's dir ehrlich sagen.",
        )
    }
}
