package de.hoshi.core.pipeline

/**
 * **EscalationMode — das Vier-Stufen-Setting von Extended Think (S2).**
 *
 * Steuert, was am [FactCoverageGate.Decision.Deflect]-Zweig des
 * [TurnOrchestrator] passiert, wenn die lokale Wissensdecke nicht reicht:
 *
 *  - **[AUS]** — exakt heutiges Verhalten: die ehrliche Deflection-Phrase,
 *    kein Cloud-Call. Ein „ja" auf das Angebot bekommt einen ehrlich-warmen
 *    Hinweis aufs Setting (nie einen stillen Call).
 *  - **[ERST_FRAGEN]** — die Deflection-Phrase wird echtes Gesprächs-Consent
 *    (bindender Orchestrator-Entscheid #3, zugleich der Laufzeit-Default bei
 *    offener Decke): erst wenn der Folge-Turn deterministisch „ja" sagt
 *    ([AffirmationRecognizer]), wird mit der GESPEICHERTEN Frage eskaliert.
 *  - **[AUTOMATISCH]** — der Deflect-Zweig eskaliert direkt über den
 *    [de.hoshi.core.port.EscalationPort], ohne Rückfrage.
 *  - **[OFFLINE]** (Andi-Auftrag 2026-07-26, „Warum brauchen wir die Wärme
 *    der Sonne?" blieb ohne Cloud unbeantwortet) — das Gegenstück zu [AUS]:
 *    ebenfalls KEIN Cloud-Call, aber statt der Ausweich-Phrase antwortet das
 *    LOKALE Brain aus eigenem Wissen, ehrlich als unbelegt gekennzeichnet
 *    (die Kennzeichnung reist im [de.hoshi.core.pipeline.lang.LanguagePack],
 *    s. [FactCoverageGate.offlineDisclaimer]). Kein zweiter Brain-Call: der
 *    Deflect-Zweig lässt für [OFFLINE] denselben LOCAL-Brain-Call laufen, der
 *    sonst für [FactCoverageGate.Decision.Proceed] ohnehin passiert wäre.
 *
 * Zwei Stufen der Wahrheit (SettingsController-Muster): die DECKE
 * `HOSHI_EXTENDED_THINK_ENABLED` (Deploy-Zeit, default false ⇒ byte-neutral)
 * muss offen sein, damit der Laufzeit-Mode überhaupt greift — Decke zu ⇒
 * effektiv immer [AUS] (die Wiring-Kaskade in `PipelineConfig` kollabiert
 * generisch auf den konstanten AUS-Supplier, unabhängig vom gespeicherten Wert).
 */
enum class EscalationMode {
    AUS,
    ERST_FRAGEN,
    AUTOMATISCH,
    OFFLINE,
    ;

    companion object {
        /**
         * Laufzeit-Default bei OFFENER Decke (bindender Entscheid #3):
         * Hoshi fragt erst — Default „Erst fragen", nie still „Automatisch".
         */
        val RUNTIME_DEFAULT: EscalationMode = ERST_FRAGEN

        /** Tolerantes Wire-Parsing („erst_fragen", " AUTOMATISCH " …); unbekannt ⇒ null. */
        fun fromWire(raw: String?): EscalationMode? {
            val normalized = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}
