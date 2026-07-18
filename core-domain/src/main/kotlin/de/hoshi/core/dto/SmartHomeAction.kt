package de.hoshi.core.dto

/**
 * Die strukturierte Smart-Home-Aktion eines Turns (PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger, dort `StructuredIntentService.SmartHomeAction`).
 *
 * Bewusst reine Daten — die Klassifikation (Intent → Aktion) lebt im
 * Orchestrator/Adapter; die Bestätigungs-Formulierung in
 * [de.hoshi.core.pipeline.ResponseFormatter] mappt sie auf die warme Quittung.
 */
enum class SmartHomeAction {
    LIGHT_ON, LIGHT_OFF, LIGHT_DIM, LIGHT_COLOR,
    SCENE_ACTIVATE,
    COVER_OPEN, COVER_CLOSE,
    CLIMATE_SET,
    UNKNOWN,
}
