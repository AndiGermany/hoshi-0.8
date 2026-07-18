package de.hoshi.web

/**
 * **BrainModelCatalog** — die HARTE Zwei-Modell-Whitelist des Brain-Sidecars
 * (16-GB-RAM-Wand: e2b und e4b laufen NIE gleichzeitig,
 * s. `pipeline/stack-lib.sh brain_guard_blocks`). Spiegelt die
 * `role: "brain"`/`"brain-alt"`-Einträge aus `models.json` (die Manifest-
 * Wahrheit für Sidecar-Modelle) — bewusst eine eigene, kleine Kotlin-Tabelle
 * statt eines Datei-Parsers zur Laufzeit (Muster
 * [de.hoshi.adapters.escalation.EscalationModelCatalog]): ändert sich
 * `models.json`, muss diese Tabelle von Hand nachgezogen werden (gleiche
 * Abwägung wie die Preis-Tabelle dort — kein neuer JSON-Parser/keine neue
 * Dependency für zwei feste Zeilen).
 */
object BrainModelCatalog {

    /** Ein Eintrag der Whitelist: kurze Settings-Id, Anzeige-Label, volle HF-Repo-Id (für `/switch-model`). */
    data class ModelInfo(val id: String, val label: String, val repo: String)

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
    )

    /** Tabellen-Lookup über die kurze Settings-Id (exakt, getrimmt). Unbekannt ⇒ null. */
    fun byId(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id.trim() }

    /** Tabellen-Lookup über die VOLLE HF-Repo-Id (wie sie `/health` im `model`-Feld liefert). */
    fun byRepo(repo: String?): ModelInfo? = repo?.trim()?.takeIf { it.isNotBlank() }?.let { r ->
        MODELS.firstOrNull { it.repo == r }
    }
}
