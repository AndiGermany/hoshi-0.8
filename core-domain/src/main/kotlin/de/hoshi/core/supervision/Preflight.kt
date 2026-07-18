package de.hoshi.core.supervision

/**
 * Die rohen Fakten, die VOR „healthy" über einen Sidecar bekannt sein müssen — die
 * Ehrlichkeits-Achse aus dem 0.5-e4b-run (Brief 15). Bewusst REINE Booleans, vom
 * Live-Adapter aus dem Dateisystem befüllt, im Test ausgedacht.
 *
 *  - [venvPresent]         die `.venv` des Sidecars existiert (tote venv = stiller Tod).
 *  - [importProbeOk]       `import <modul>` lief durch (z.B. `import mlx_lm`).
 *  - [modelCacheComplete]  die `.safetensors`/Gewichte sind vollständig da.
 *  - [hasIncompleteMarkers] Reste eines abgebrochenen Downloads (`*.incomplete`) —
 *                          GENAU der „6-Tage-Zombie-Download", der in 0.5 still durchrutschte.
 */
data class PreflightInput(
    val venvPresent: Boolean,
    val importProbeOk: Boolean,
    val modelCacheComplete: Boolean,
    val hasIncompleteMarkers: Boolean,
)

/**
 * Ergebnis des Start-Preflights. [ready] = grünes Licht für den Start; [blockers]
 * sind die ehrlichen Gründe, warum NICHT (leer wenn ready).
 */
data class PreflightResult(
    val sidecar: String,
    val ready: Boolean,
    val blockers: List<String>,
)
