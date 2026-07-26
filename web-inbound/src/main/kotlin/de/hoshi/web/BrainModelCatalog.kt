package de.hoshi.web

/**
 * **BrainModelCatalog** — die HARTE Drei-Modell-Whitelist des Brain-Sidecars
 * (16-GB-RAM-Wand: e2b, e4b und 12b laufen NIE gleichzeitig,
 * s. `pipeline/stack-lib.sh brain_guard_blocks`). Spiegelt die
 * `role: "brain"`/`"brain-alt"`-Einträge aus `models.json` (die Manifest-
 * Wahrheit für Sidecar-Modelle) — bewusst eine eigene, kleine Kotlin-Tabelle
 * statt eines Datei-Parsers zur Laufzeit (Muster
 * [de.hoshi.adapters.escalation.EscalationModelCatalog]): ändert sich
 * `models.json`, muss diese Tabelle von Hand nachgezogen werden (gleiche
 * Abwägung wie die Preis-Tabelle dort — kein neuer JSON-Parser/keine neue
 * Dependency für drei feste Zeilen).
 *
 * **12b (Andi-Test-Auftrag 2026-07-25):** `mlx-community/gemma-4-12B-it-4bit`
 * — dieselbe Repo-Groß-/Kleinschreibung wie in `pipeline/stack-lib.sh
 * resolve_brain_model` (Fall `12b|12B`), case-sensitiv gegen HF. Die
 * Drift-Prüfung ([SidecarHealthService.currentSpecs]) folgt dem per
 * `PUT /settings/brain` GEWÄHLTEN Modell dynamisch (volle Repo-Id, nicht
 * dieser kurzen Id) — ein Wechsel auf 12b braucht darum KEINE eigene
 * Whitelist-Zeile in der Drift-Logik.
 */
object BrainModelCatalog {

    /** Ein Eintrag der Whitelist: kurze Settings-Id, Anzeige-Label, volle HF-Repo-Id (für `/switch-model`). */
    data class ModelInfo(val id: String, val label: String, val repo: String)

    /** Aufsteigend nach RAM-/Denk-Bedarf: e2b (schnell) → e4b (gründlicher) → 12b (am gründlichsten). */
    val MODELS: List<ModelInfo> = listOf(
        ModelInfo(
            id = "e2b",
            label = "Gemma-4 E2B (Default, schnell)",
            repo = "mlx-community/gemma-4-e2b-it-4bit",
        ),
        ModelInfo(
            id = "e4b",
            label = "Gemma-4 E4B (gründlicher, mehr RAM)",
            repo = "mlx-community/gemma-4-e4b-it-4bit",
        ),
        // Das dichte 12B — laeuft seit dem 26.07, aber nur MIT unserem eigenen
        // mlx-lm-Patch (`sidecars/brain/mlx_patches/gemma4_unified.py`): seine
        // config.json deklariert `model_type: gemma4_unified`, und dieses
        // Architektur-Modul bringt KEINE veroeffentlichte mlx-lm-Version mit
        // (geprueft: 0.31.2 lokal, 0.31.3 auf PyPI, Upstream-Baum). Ohne den
        // Patch endet jeder Wechsel im Ladefehler — und weil der Sidecar wegen
        // der 16-GB-Wand VOR dem Laden entlaedt, steht Hoshi dann ohne Brain da.
        //
        // LIVE GEMESSEN (gleiche Frage, gleicher Sidecar): 12B 2619/1407 ms,
        // e4b 828/696 ms — rund doppelt so langsam, Ladezeit 4,3 s. Deshalb
        // nennt das Label die Kosten, statt nur "gruendlicher" zu versprechen.
        ModelInfo(
            id = "12b",
            label = "Gemma-4 12B (gründlicher, aber rund doppelt so langsam — braucht den mlx-Patch)",
            repo = "mlx-community/gemma-4-12B-it-4bit",
        ),
    )

    /** Tabellen-Lookup über die kurze Settings-Id (exakt, getrimmt). Unbekannt ⇒ null. */
    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id.trim() }

    /** Tabellen-Lookup über die VOLLE HF-Repo-Id (wie sie `/health` im `model`-Feld liefert). */
    fun byRepo(repo: String?): ModelInfo? = repo?.trim()?.takeIf { it.isNotBlank() }?.let { r ->
        MODELS.firstOrNull { it.repo == r }
    }

    /**
     * **Das Auto-Switch-Paar (Andi-Auftrag „12B für Chat, e4b für Voice", 2026-07-26)**
     * — Grundlage eines gemessenen Drei-Modell-Vergleichs: ein Modellwechsel kostet nur
     * 2,6-4,3s (warm), aber 12B generiert 2-4x langsamer als e4b (11-15s lange Antworten)
     * — fürs SPRECHEN inakzeptabel, beim TIPPEN egal. [BrainAutoSwitchService] verdrahtet
     * NUR dieses eine Paar (keine eigene UI dafür in v1, s. [BrainAutoSwitchController]).
     */
    val AUTO_SWITCH_VOICE_REPO: String = byId("e4b")!!.repo

    /** Chat-Hälfte des Auto-Switch-Paars — s. [AUTO_SWITCH_VOICE_REPO]-KDoc. */
    val AUTO_SWITCH_CHAT_REPO: String = byId("12b")!!.repo
}
