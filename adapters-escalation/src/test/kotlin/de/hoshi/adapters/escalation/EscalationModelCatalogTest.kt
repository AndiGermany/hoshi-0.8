package de.hoshi.adapters.escalation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Vertrag der statischen ca.-Preis-Tabelle (verifiziert 2026-07-05 gegen
 * developers.openai.com/api/docs/pricing): Default-Modell gelistet, Kosten-
 * Mathe stimmt, unbekannte Modelle werden KONSERVATIV (teuerster Eintrag)
 * gebucht — der Cap ist ein Geld-Riegel, Unterbuchung wäre ein Leck.
 */
class EscalationModelCatalogTest {

    @Test
    fun `das Default-Modell steht in der Tabelle (Whitelist-Anker fuer S5)`() {
        val info = EscalationModelCatalog.byId(EscalationModelCatalog.DEFAULT_MODEL_ID)
        assertNotNull(info, "Default-Modell muss gelistet sein")
        assertEquals("gpt-5.4-nano", info!!.id)
    }

    @Test
    fun `Kosten-Mathe — Cents je 1M Tokens, anteilig`() {
        // gpt-5.4-nano: ca. 20 ct/1M Input, 125 ct/1M Output.
        val cost = EscalationModelCatalog.costCents("gpt-5.4-nano", promptTokens = 1_000_000, completionTokens = 0)
        assertEquals(20.0, cost, 1e-9)
        val mixed = EscalationModelCatalog.costCents("gpt-5.4-nano", promptTokens = 100, completionTokens = 50)
        assertEquals(0.00825, mixed, 1e-9)
    }

    @Test
    fun `unbekanntes Modell wird konservativ mit dem teuersten Eintrag gerechnet`() {
        val unknown = EscalationModelCatalog.costCents("mystery-model", 1000, 1000)
        val nano = EscalationModelCatalog.costCents("gpt-5.4-nano", 1000, 1000)
        assertTrue(unknown >= nano, "unbekannt darf NIE billiger gebucht werden als Nano")
    }

    @Test
    fun `negative Token-Counts werden auf 0 geklemmt`() {
        assertEquals(0.0, EscalationModelCatalog.costCents("gpt-5.4-nano", -5, -7), 1e-9)
    }

    @Test
    fun `ein typischer Nano-Nachschlag kostet Bruchteile eines Cents (Preis-Info der Settings-UI)`() {
        val nano = EscalationModelCatalog.byId("gpt-5.4-nano")!!
        assertTrue(nano.caPriceCentsPerLookup < 1.0, "Nano-Lookup muss unter 1 Cent liegen")
        assertTrue(nano.caPriceCentsPerLookup > 0.0)
    }

    // ── gpt-5.6-Recherche-Familie (Andi-Auftrag 2026-07-19, Preise verifiziert
    //    2026-07-19 gegen developers.openai.com/api/docs/pricing) ────────────────

    @Test
    fun `gpt-5_6-Familie steht mit den EXAKTEN Preisen von der Preisseite in der Tabelle`() {
        val sol = EscalationModelCatalog.byId("gpt-5.6-sol")
        assertNotNull(sol, "Sol muss gelistet sein")
        assertEquals(500.0, sol!!.caInputCentsPer1M, 1e-9, "Sol: \$5.00/1M Input")
        assertEquals(3000.0, sol.caOutputCentsPer1M, 1e-9, "Sol: \$30.00/1M Output")

        val terra = EscalationModelCatalog.byId("gpt-5.6-terra")
        assertNotNull(terra, "Terra muss gelistet sein")
        assertEquals(250.0, terra!!.caInputCentsPer1M, 1e-9, "Terra: \$2.50/1M Input")
        assertEquals(1500.0, terra.caOutputCentsPer1M, 1e-9, "Terra: \$15.00/1M Output")

        val luna = EscalationModelCatalog.byId("gpt-5.6-luna")
        assertNotNull(luna, "Luna muss gelistet sein")
        assertEquals(100.0, luna!!.caInputCentsPer1M, 1e-9, "Luna: \$1.00/1M Input")
        assertEquals(600.0, luna.caOutputCentsPer1M, 1e-9, "Luna: \$6.00/1M Output")
    }

    @Test
    fun `caPriceCentsPerLookup der 5_6-Familie folgt der 800in-300out-Schaetzung (eigene Rechnung)`() {
        val sol = EscalationModelCatalog.byId("gpt-5.6-sol")!!
        val terra = EscalationModelCatalog.byId("gpt-5.6-terra")!!
        val luna = EscalationModelCatalog.byId("gpt-5.6-luna")!!
        // (800×caIn + 300×caOut) / 1_000_000 — dasselbe Muster wie die Nano-/
        // Mini-Einträge dieser Tabelle, nur mit den 5.6-Preisen.
        assertEquals(1.3, sol.caPriceCentsPerLookup, 1e-9, "Sol ≈ 1,3 ct/Lookup")
        assertEquals(0.65, terra.caPriceCentsPerLookup, 1e-9, "Terra ≈ 0,65 ct/Lookup")
        assertEquals(0.26, luna.caPriceCentsPerLookup, 1e-9, "Luna ≈ 0,26 ct/Lookup")
        assertTrue(sol.caPriceCentsPerLookup > terra.caPriceCentsPerLookup)
        assertTrue(terra.caPriceCentsPerLookup > luna.caPriceCentsPerLookup)
    }

    @Test
    fun `Default-Modell bleibt gpt-5_4-nano, NIE die 5_6-Recherche-Familie`() {
        assertEquals("gpt-5.4-nano", EscalationModelCatalog.DEFAULT_MODEL_ID)
    }

    // ── requireKnown — Fail-fast (Muster SpeakerProfileAggregation.parse) ────────

    @Test
    fun `requireKnown liefert das ModelInfo einer bekannten ID`() {
        val info = EscalationModelCatalog.requireKnown("gpt-5.6-sol")
        assertEquals("gpt-5.6-sol", info.id)
    }

    @Test
    fun `requireKnown wirft bei einer unbekannten ID (fail-fast statt stiller Fehl-Konfiguration)`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            EscalationModelCatalog.requireKnown("gpt-5.6-mars")
        }
        assertTrue(ex.message!!.contains("gpt-5.6-mars"), "Fehlermeldung nennt die unbekannte ID: ${ex.message}")
    }

    // ── providerLabel — ehrliche Diary-/Event-Beschriftung ───────────────────────

    @Test
    fun `providerLabel leitet das kurze Anzeige-Label aus der Modell-ID ab (Muster openai-nano)`() {
        assertEquals("openai-nano", EscalationModelCatalog.providerLabel("gpt-5.4-nano"), "identisch zur Bestandskonstante")
        assertEquals("openai-sol", EscalationModelCatalog.providerLabel("gpt-5.6-sol"))
        assertEquals("openai-terra", EscalationModelCatalog.providerLabel("gpt-5.6-terra"))
        assertEquals("openai-luna", EscalationModelCatalog.providerLabel("gpt-5.6-luna"))
    }
}
