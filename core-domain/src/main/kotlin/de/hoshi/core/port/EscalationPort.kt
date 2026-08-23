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
        val NONE: EscalationPort = EscalationPort {
            _, _, _ -> Mono.just(EscalationResult.Unavailable(EscalationUnavailableReason.DISABLED))
        }
    }
}

/**
 * Ergebnis einer Eskalation — sealed, damit der Aufrufer erschöpfend
 * pattern-matched und keinen Zustand „vergessen" kann.
 */
sealed interface EscalationResult {

    /**
     * A completed lookup response.
     *
     * @property text restored, display-ready answer, kept verbatim. It never
     *   contains an appended source line. LL-2026-07-21-wikinumber-umformulierung.
     * @property source readable but unverified attribution for diary/notes;
     *   never append it to [text].
     * @property costCents actual approximate-cent cost; local adapters use 0.0.
     * @property sources verified structured citations only. ADR-003-quellen-struktur-der-eskalations-antwort:
     *   empty means no evidence icon; URLs are already stripped of tracking parameters.
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
    data class Unavailable(
        /** Klartext-freie, stabile Ursache fuer Betrieb/Tests — nie Query, Key oder Antworttext. */
        val reason: EscalationUnavailableReason = EscalationUnavailableReason.UNKNOWN,
        /** Nur bei [EscalationUnavailableReason.HTTP_STATUS], sonst `null`. */
        val httpStatus: Int? = null,
    ) : EscalationResult

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
 * Klartext-freie Ursachen eines [EscalationResult.Unavailable]. Diese Werte sind
 * bewusst provider-neutral: auch ein spaeterer lokaler Escalation-Adapter kann
 * denselben Vertrag erfuellen. Sie enthalten NIE Query-, Antwort- oder Key-Daten.
 */
enum class EscalationUnavailableReason(val logValue: String) {
    DISABLED("disabled"),
    EMPTY_QUERY("empty_query"),
    MISSING_KEY("missing_key"),
    TIMEOUT("timeout"),
    HTTP_STATUS("http_status"),
    NETWORK("network"),
    PARSE("parse"),
    EMPTY_RESPONSE("empty_response"),
    EMPTY_RESULT("empty_result"),
    PORT_ERROR("port_error"),
    UNKNOWN("unknown"),
}

/** Ein finaler, warmer Unavailable-Ausgang an der Orchestrationsgrenze. */
data class EscalationUnavailableEvent(
    val provider: String,
    val reason: EscalationUnavailableReason,
    val elapsedMs: Long,
    val timeoutMs: Long,
    val httpStatus: Int? = null,
)

/**
 * Beobachtungs-Port fuer den finalen Unavailable-Ausgang. Der reine Kern kennt
 * keinen Logger; der Inbound-Adapter schreibt daraus genau eine sichere Zeile.
 */
fun interface EscalationDiagnosticsPort {
    fun unavailable(event: EscalationUnavailableEvent)

    companion object {
        val NONE: EscalationDiagnosticsPort = EscalationDiagnosticsPort { }
    }
}

/**
 * Provider-neutral citation. ADR-003-quellen-struktur-der-eskalations-antwort.
 *
 * @property title optional short attribution; callers may fall back to the URL host.
 * @property url citation URL already stripped of tracking query parameters.
 */
data class EscalationSourceRef(val title: String? = null, val url: String)
