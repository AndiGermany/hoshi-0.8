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
        // LIVE GEMESSEN 25.07 (nicht vermutet): der Wechsel scheitert heute mit
        // `ValueError: Model type gemma4_unified not supported` — das gepinnte
        // mlx-lm 0.31.2 kennt die Architektur des dichten 12B nicht. Der Sidecar
        // entlaedt (16-GB-Wand) VOR dem Laden, ein Klick kostet also das laufende
        // Brain bis `bin/hoshi heal`. Der Eintrag bleibt trotzdem sichtbar — Andis
        // System, Andis Entscheidung —, aber das Label sagt die Wahrheit, statt
        // einen Versuch zu versprechen, der heute sicher fehlschlaegt.
        // Entsperrt wird er durch das mlx-lm-Upgrade (PREP-mlx-modell-upgrade,
        // eigenes Brain-Fenster); danach hier nur diesen Text zuruecknehmen.
        ModelInfo(
            id = "12b",
            label = "Gemma-4 12B (braucht ein neueres mlx-lm — lädt heute NICHT, Brain muss danach geheilt werden)",
            repo = "mlx-community/gemma-4-12B-it-4bit",
        ),
    )

    /** Tabellen-Lookup über die kurze Settings-Id (exakt, getrimmt). Unbekannt ⇒ null. */
    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id.trim() }

    /** Tabellen-Lookup über die VOLLE HF-Repo-Id (wie sie `/health` im `model`-Feld liefert). */
    fun byRepo(repo: String?): ModelInfo? = repo?.trim()?.takeIf { it.isNotBlank() }?.let { r ->
        MODELS.firstOrNull { it.repo == r }
    }
}
