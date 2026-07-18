package de.hoshi.core.port

import de.hoshi.core.dto.Language
import reactor.core.publisher.Mono

/**
 * **EscalationPort — das „dritte Ohr" der Ehrlichkeit (Extended Think, S1).**
 *
 * Wenn die lokale Wissensdecke nicht reicht (FactCoverage-Deflect), kann ein
 * Turn — gegated und mit Consent — eine externe Nachschlage-Instanz fragen.
 * Dieser Port ist die EINZIGE Naht dafür.
 *
 * **Universell by design (Kai-Leitplanke):** KEINE Cloud-/OpenAI-Typen hier —
 * ein späteres lokales 12B („das größere Ich") implementiert exakt denselben
 * Port. Egress-Riegel ([de.hoshi.kernel.EgressPort.guard]) und Tages-Cap leben
 * bewusst NUR im Cloud-ADAPTER (ein lokaler Adapter braucht beides nicht).
 *
 * **Egress-Gesetz (Tom, bindend):** an diesen Port geht NUR die Frage plus
 * höchstens die unzureichenden Grounding-Schnipsel — NIE Memory, NIE Namen aus
 * dem Kontext, NIE History, NIE der `finalPrompt` (der trägt Persona+Memory!).
 *
 * Historie: ersetzt die ältere, nie konsumierte CloudEscalationPort-Seam-Skizze (entfernt 2026-07).
 *
 * Bewusst ein `fun interface` (genau EINE Methode), damit Tests einen
 * Lambda-Fake injizieren können — wie [TtsPort]/[ToolPort].
 */
fun interface EscalationPort {

    /**
     * Schlägt [query] extern nach. [groundingSnippets] dürfen die (per
     * Definition unzureichenden) lokalen Schnipsel tragen — v1-Default ist
     * leer (nur die Frage geht raus, Tom-freundlichste Auslegung).
     * Liefert IMMER ein [EscalationResult], wirft NIE (best-effort:
     * Fehler/Timeout ⇒ [EscalationResult.Unavailable]).
     */
    fun lookup(query: String, groundingSnippets: String, language: Language): Mono<EscalationResult>

    companion object {
        /**
         * Byte-neutraler Default (Extended Think OFF / nicht verdrahtet):
         * eskaliert NIE, antwortet immer [EscalationResult.Unavailable] —
         * kein Netz, kein Spend, kein Verhalten.
         */
        val NONE: EscalationPort = EscalationPort { _, _, _ -> Mono.just(EscalationResult.Unavailable) }
    }
}

/**
 * Ergebnis einer Eskalation — sealed, damit der Aufrufer erschöpfend
 * pattern-matched und keinen Zustand „vergessen" kann.
 */
sealed interface EscalationResult {

    /**
     * Die externe Instanz hat belegbar geantwortet.
     *
     * @param text die Antwort — wird VERBATIM gesprochen/angezeigt (nie durch
     *        den Brain umformuliert — WikiNumber-Lehre: Umformulierung ist ein
     *        Konfabulations-Einfallstor). Masken-Token sind bereits zurückgesetzt.
     *        Trägt SEIT dem Quellen-Struktur-Auftrag (2026-07-21, Andi-Befund
     *        „Quelle: Quellen: https://…?utm_source=openai" im Sprach-Output)
     *        NIE mehr eine angehängte Quellen-/URL-Zeile — die Attribution
     *        reist getrennt über [source]/[sources].
     * @param source Quellen-Angabe aus der Antwort (bzw. Modell-Attribution,
     *        wenn die Antwort keine trug) — EIN lesbarer String fürs Diary/
     *        die 30-Tage-Notiz, NIE an den Aufrufer-Text angehängt.
     * @param costCents echte Kosten dieses Calls in ca.-Cents (Nano-Calls sind
     *        Bruchteile von Cents ⇒ Double; 0.0 bei einem lokalen Adapter).
     * @param sources **strukturierte ECHTE Quellen** (additiv, Default leer —
     *        Quellen-Struktur-Auftrag 2026-07-21): nur bei belegbaren
     *        `url_citation`-Treffern des Web-Search-Pfads gefüllt (s.
     *        [de.hoshi.adapters.escalation.OpenAiEscalationAdapter]), URLs
     *        bereits von Tracking-Query-Parametern (`utm_source` u.ä.)
     *        bereinigt. Leer = KEINE verifizierbare Quelle (reiner
     *        Modellwissen-Fallback wie [source]s „ohne Quellenangabe"/eine
     *        bloße Selbstauskunft) — der Aufrufer zeigt dann ehrlich KEIN
     *        Quellen-Icon (Vera-Regel: kein Beleg, kein Beleg-Icon).
     */
    data class Answer(
        val text: String,
        val source: String,
        val costCents: Double,
        val sources: List<EscalationSourceRef> = emptyList(),
    ) : EscalationResult

    /** Die Instanz weiß es ehrlich nicht (Konservativ-Prompt: wörtlich UNKLAR). */
    data object Unclear : EscalationResult

    /**
     * Der Egress-Riegel hat den Request GEBLOCKT — es ging NICHTS raus.
     *
     * @param auditReason klartext-freier Audit-Grund (nur die Block-Kategorie,
     *        NIE der geblockte Inhalt).
     */
    data class Declined(val auditReason: String) : EscalationResult

    /**
     * Nachschlagen gerade nicht möglich (kein Key, Netz/Timeout, Port nicht
     * verdrahtet — irgendetwas ist gerade KAPUTT oder nicht erreichbar). Der
     * Aufrufer antwortet ehrlich lokal.
     *
     * **Abgrenzung zu [CapExhausted] (H3, bindend):** dieser Fall ist NICHT
     * für „Tages-Cap erreicht" gedacht — das ist strukturell etwas anderes
     * (nichts ist kaputt, das Budget für heute ist nur leer) und bekommt
     * seit H3 einen eigenen, ehrlichen Ausgang statt in derselben warmen
     * Netzfehler-Phrase unterzugehen (Andi kann sonst „kein Internet" nicht
     * von „Budget alle" unterscheiden).
     */
    data object Unavailable : EscalationResult

    /**
     * **Tages-Cap erreicht (H3, additiv)** — der Adapter hat VOR jedem Call
     * geprüft (`spentTodayCents() >= dailyCapCents`, s.
     * [de.hoshi.adapters.escalation.OpenAiEscalationAdapter]) und bewusst
     * KEINEN HTTP-Call ausgelöst. Strukturell wie [Unavailable] (der Turn
     * antwortet ehrlich lokal), aber semantisch UNTERSCHEIDBAR — der
     * Aufrufer spricht eine eigene, ehrliche Phrase („Budget für heute alle",
     * NICHT „gerade nicht erreichbar") und das Diary trägt den Unterschied
     * (`TurnTrace.escalationCapExhausted`), statt Cap und Netzfehler in
     * derselben Zeile zu verschmelzen.
     */
    data object CapExhausted : EscalationResult
}

/**
 * **Eine strukturierte Quellen-Referenz** (additiv, Quellen-Struktur-Auftrag
 * 2026-07-21) — universell wie [EscalationPort] selbst (keine Cloud-/OpenAI-
 * Typen): ein späterer lokaler Adapter füllt [EscalationResult.Answer.sources]
 * genauso.
 *
 * @param title optionale Kurz-Attribution (z.B. Seitentitel) — oft nicht
 *        vorhanden, dann zeigt der Aufrufer den Host aus [url].
 * @param url die Quellen-URL, bereits von Tracking-Query-Parametern
 *        (`utm_source`/`utm_medium`/… u.ä.) bereinigt — s.
 *        [de.hoshi.adapters.escalation.OpenAiEscalationAdapter] fürs Strip.
 */
data class EscalationSourceRef(val title: String? = null, val url: String)
