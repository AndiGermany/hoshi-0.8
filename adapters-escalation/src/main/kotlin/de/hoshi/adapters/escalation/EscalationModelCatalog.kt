package de.hoshi.adapters.escalation

/**
 * **Statische ca.-Preis-Tabelle der Eskalations-Modelle (Nano-/Recherche-Klasse).**
 *
 * Quelle: developers.openai.com/api/docs/pricing — die gpt-5.4-Zeilen
 * **verifiziert 2026-07-05**, die gpt-5.6-Zeilen (Recherche-Familie)
 * **verifiziert 2026-07-19** (Standard-Tier, USD, OHNE Cache-Rabatt). Alle
 * Werte sind bewusst als **„ca."-Schätzung** geführt — die Tabelle ist eine
 * ehrliche Orientierung für Settings-UI und Kosten-Buchung, KEINE
 * Abrechnungswahrheit (die hat nur OpenAIs Billing). USD≈EUR wird nicht
 * umgerechnet (Cent-Größenordnung).
 *
 * Konfigurierbar über `hoshi.escalation.model` (Standard-Lookup, ab S1) bzw.
 * `hoshi.escalation.research-model` (expliziter Recherche-Imperativ, ab
 * 2026-07-19); die Whitelist für BEIDE Properties ist genau diese Tabelle.
 */
object EscalationModelCatalog {

    /** Ein Eintrag der ca.-Preis-Tabelle. Cents je 1M Tokens (100 Cents = 1 USD/EUR-ca.). */
    data class ModelInfo(
        /** Exakte API-Modell-ID (`model`-Feld im Request). */
        val id: String,
        /** Menschlicher Anzeigename für die Settings-UI (S5). */
        val label: String,
        /** ca. Input-Preis in Cents je 1M Tokens (verifiziert, s.o.). */
        val caInputCentsPer1M: Double,
        /** ca. Output-Preis in Cents je 1M Tokens (inkl. Reasoning-Tokens!). */
        val caOutputCentsPer1M: Double,
        /**
         * ca. Kosten EINES typischen Nachschlags in Cents (grobe Schätzung:
         * ~600 Input- + ~700 Output-Tokens inkl. Reasoning, aufgerundet) —
         * die „kleine Preis-Info" für die Settings-UI.
         */
        val caPriceCentsPerLookup: Double,
    )

    /**
     * Die Whitelist. Stand 2026-07-05 führt die offizielle Preisseite die
     * gpt-5.4-Serie; Nano ist die Auftrags-Klasse (Andi-Regel „Nano, Spend
     * frei" + 0,50-€-Tages-Cap), Mini die eine dokumentierte Upgrade-Option.
     * Ältere Nano-IDs (gpt-5-nano/gpt-4.1-nano) stehen nicht mehr auf der
     * Preisseite und sind darum bewusst NICHT gelistet (keine unverifizierten
     * Preise in der Tabelle).
     *
     * **gpt-5.6-Familie (Recherche-Modelle, Andi-Auftrag 2026-07-19, Preise
     * verifiziert 2026-07-19 gegen developers.openai.com/api/docs/pricing):**
     * für den expliziten Recherche-Imperativ („recherchiere online",
     * s. [de.hoshi.core.pipeline.ResearchIntentRecognizer]) — gründlicher als
     * Nano/Mini, darum deutlich teurer (5-25× Nano); NUR gerufen, wenn
     * `hoshi.escalation.research-model` explizit einen dieser drei Einträge
     * nennt (Default leer ⇒ Feature AUS). [caPriceCentsPerLookup] rechnet mit
     * dem in dieser Datei etablierten Schätz-Muster (typischer Lookup
     * ~800 Input-/~300 Output-Tokens): `(800×caInputCentsPer1M +
     * 300×caOutputCentsPer1M) / 1_000_000` — für Sol z.B.
     * `(800×500 + 300×3000) / 1_000_000 = 1.3` Cent.
     */
    val MODELS: List<ModelInfo> = listOf(
        ModelInfo(
            id = "gpt-5.4-nano",
            label = "OpenAI Nano (Standard, günstig)",
            caInputCentsPer1M = 20.0, // ca. $0.20 / 1M
            caOutputCentsPer1M = 125.0, // ca. $1.25 / 1M
            caPriceCentsPerLookup = 0.1,
        ),
        ModelInfo(
            id = "gpt-5.4-mini",
            label = "OpenAI Mini (gründlicher, teurer)",
            caInputCentsPer1M = 75.0, // ca. $0.75 / 1M
            caOutputCentsPer1M = 450.0, // ca. $4.50 / 1M
            caPriceCentsPerLookup = 0.4,
        ),
        ModelInfo(
            id = "gpt-5.6-sol",
            label = "OpenAI 5.6 Sol (Recherche, gründlich)",
            caInputCentsPer1M = 500.0, // $5.00 / 1M
            caOutputCentsPer1M = 3000.0, // $30.00 / 1M
            // (800×500 + 300×3000) / 1_000_000 = (400_000 + 900_000) / 1_000_000 = 1.3 ct.
            caPriceCentsPerLookup = 1.3,
        ),
        ModelInfo(
            id = "gpt-5.6-terra",
            label = "OpenAI 5.6 Terra (Recherche, mittel)",
            caInputCentsPer1M = 250.0, // $2.50 / 1M
            caOutputCentsPer1M = 1500.0, // $15.00 / 1M
            // (800×250 + 300×1500) / 1_000_000 = (200_000 + 450_000) / 1_000_000 = 0.65 ct.
            caPriceCentsPerLookup = 0.65,
        ),
        ModelInfo(
            id = "gpt-5.6-luna",
            label = "OpenAI 5.6 Luna (Recherche, günstig)",
            caInputCentsPer1M = 100.0, // $1.00 / 1M
            caOutputCentsPer1M = 600.0, // $6.00 / 1M
            // (800×100 + 300×600) / 1_000_000 = (80_000 + 180_000) / 1_000_000 = 0.26 ct.
            caPriceCentsPerLookup = 0.26,
        ),
    )

    /** Default-Modell (`hoshi.escalation.model`, wenn nichts konfiguriert ist). NIE die 5.6-Familie (Andi-Vorgabe). */
    const val DEFAULT_MODEL_ID: String = "gpt-5.4-nano"

    /** Tabellen-Lookup (exakte ID); unbekannt ⇒ null. */
    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id.trim() }

    /**
     * **Strikte Config-Grenze** (Muster [de.hoshi.web.SpeakerProfileAggregation.parse]):
     * eine EXPLIZIT konfigurierte, aber unbekannte Modell-ID lässt den Aufrufer
     * (typischerweise ein Spring-`@Bean`-Bau) werfen, statt mit stiller
     * Fehl-Semantik zu starten — Kosten-/Urteils-Config darf nicht raten.
     * NUR für explizit gesetzte IDs gedacht; ein leerer Wert (Feature AUS)
     * bleibt Sache des Aufrufers (der ruft [requireKnown] dann gar nicht erst).
     */
    fun requireKnown(id: String): ModelInfo = byId(id) ?: throw IllegalArgumentException(
        "Unbekannte Eskalations-Modell-ID '$id' — nicht in EscalationModelCatalog.MODELS " +
            "(bekannt: ${MODELS.joinToString(", ") { it.id }})",
    )

    /**
     * Kurzes Anzeige-Label im Stil der Bestandskonstante
     * `TurnOrchestrator.LOOKUP_NOTE_PROVIDER` (`"openai-nano"` für
     * `gpt-5.4-nano`) — der Teil der Modell-ID NACH dem letzten Bindestrich,
     * `"openai-"` vorangestellt (`gpt-5.6-sol` → `"openai-sol"`). Ehrliche
     * Diary-/Event-Beschriftung je nach TATSÄCHLICH gerufenem Modell
     * (Andi-Auftrag 2026-07-19: „keine Nano-Beschriftung auf einer
     * Sol-Antwort").
     */
    fun providerLabel(modelId: String): String = "openai-" + modelId.trim().substringAfterLast('-')

    /**
     * ca.-Kosten eines Calls in Cents aus echten Token-Counts. Unbekanntes
     * Modell ⇒ KONSERVATIV mit dem teuersten Tabellen-Eintrag gerechnet
     * (lieber über- als unterbuchen — der Cap ist ein Geld-Riegel).
     */
    fun costCents(modelId: String, promptTokens: Int, completionTokens: Int): Double {
        val info = byId(modelId) ?: MODELS.maxBy { it.caOutputCentsPer1M }
        val prompt = promptTokens.coerceAtLeast(0)
        val completion = completionTokens.coerceAtLeast(0)
        return (prompt * info.caInputCentsPer1M + completion * info.caOutputCentsPer1M) / 1_000_000.0
    }
}
