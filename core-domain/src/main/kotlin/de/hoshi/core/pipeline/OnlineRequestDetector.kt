package de.hoshi.core.pipeline

/**
 * Erkennt eine **explizite Aufforderung, etwas online / im Internet
 * nachzuschlagen** — PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger
 * (`de.hoshi.app.cloud.OnlineRequestDetector`).
 *
 * Reine Heuristik (keine Infra) → 1:1 mitportiert, damit die [HonestyGate]-Policy
 * ihre Online-Request-Klasse ohne Fake testen kann. De-Spring't: kein `@Component`,
 * kein Logging. Implementiert [OnlineRequestSignal].
 *
 * **Konservativ:** eine reine Wissensfrage ÜBER das Internet („Wie funktioniert
 * das Internet?", „Was ist Google?") darf NICHT triggern. Externe Scope-Marker
 * („online", „im internet" …) zählen nur in Kombination mit einem Nachschau-Verb;
 * nur unzweideutige Aktionswörter („recherchier", „google das") triggern allein.
 *
 * **Englisch gleichberechtigt** (Andi-Vorfall 2026-07-25, englische Oberfläche:
 * „Take a look online for a recept of pizza." wurde NICHT als Online-Wunsch
 * erkannt, weil beide Wortlisten rein deutsch waren): jede Kategorie hat jetzt
 * ihre EN-Entsprechung — nach EXAKT derselben Regel-Geometrie. Insbesondere
 * bleiben die häufigen Alltagswörter „look"/„check"/„search" reine
 * [lookupVerbs] — sie triggern NUR mit einem Netz-Marker („I'll check the
 * oven", „look at this picture" ⇒ NIE). Die EN-Scope-Marker tragen — wie ihre
 * DE-Vorbilder („**im** internet") — bewusst die PRÄPOSITION („**on the**
 * internet"), damit „I find the internet fascinating" kein Online-Wunsch wird.
 */
class OnlineRequestDetector : OnlineRequestSignal {

    /** Externe Scope-Marker: „geh über dein eigenes Wissen hinaus, ins offene Netz". */
    private val externalScope = listOf(
        "online", "im internet", "ins internet", "im netz", "ins netz",
        "im web", "im world wide web", "übers internet", "uebers internet",
        // EN — mit Präposition (s. Klassen-KDoc): das blosse „the internet"/„the web"
        // wäre ein Substantiv-Vorkommen und würde „I find the internet fascinating"
        // fangen; „on/in/over the …" ist dagegen die ORTS-Angabe einer Nachschau.
        "on the internet", "in the internet", "over the internet",
        "on the web", "in the web", "on the net",
    )

    /** Unzweideutige Aktionswörter — triggern allein (kein Scope-Marker nötig). */
    private val standaloneAction = listOf(
        "recherchier", "websuche", "web-suche", "internet-suche", "internetsuche",
        "googeln", "google mal", "googel mal", "google das", "googel das",
        "google bitte", "googel bitte", "google für mich", "googel für mich",
        // EN — dieselbe Messlatte wie oben: NUR Wendungen, die ohne jeden Zusatz
        // eine Nachschau-Bitte SIND. Ein blosses „google" fehlt bewusst („What is
        // Google?", „Google is a big company" dürfen nie triggern) — es zählt nur
        // mit Objekt/Modal ("google it", "can you google").
        "search the web", "search the internet", "search the net",
        "browse the web", "browse the internet",
        "look it up", "look that up", "look this up", "look them up",
        "google it", "google that", "google this", "google for me",
        "can you google", "could you google", "please google", "just google",
    )

    /** Nachschau-Verben — nur in Kombination mit einem externen Scope-Marker. */
    private val lookupVerbs = listOf(
        "schau", "guck", "seh", "sieh", "nachschau", "nachseh", "nachguck",
        "such", "find", "check", "prüf", "pruef", "recherch", "informier",
        "wie viele", "wieviele", "wie viel ", "gibt es", "gibt's", "rausfind",
        "raus find", "herausfind", "heraus find", "ermittl",
        // EN — „check" ist oben schon dabei (DE-Lehnwort, identische Schreibung);
        // „find out"/„find" ebenso (DE „finde"). Neu: die häufigen Alltagsverben,
        // die NUR mit Netz-Marker zählen dürfen (s. Klassen-KDoc).
        // („research" fehlt bewusst: als Substring bereits von „search" abgedeckt.
        //  „google" ebenfalls — als VERB stünde es sonst auch in „Was macht Google
        //  online?"/„Is Google online?", einer Aussage ÜBER die Firma. Die
        //  Google-IMPERATIVE stehen oben bei den Standalone-Aktionen.)
        "look", "search", "browse", "how many", "how much",
    )

    override fun isOnlineRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val q = text.lowercase()

        if (standaloneAction.any { q.contains(it) }) return true
        val hasScope = externalScope.any { q.contains(it) }
        if (!hasScope) return false
        return lookupVerbs.any { q.contains(it) }
    }
}
