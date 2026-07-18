package de.hoshi.core.dto

/**
 * Tageszeit-Bucket (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger, dort
 * lokal in `PersonaService`). Steuert den Default-Emotionszustand der Persona:
 * NIGHT → CALM, sonst NEUTRAL (siehe [de.hoshi.core.pipeline.PersonaService]).
 *
 * Reines Daten-Enum, kein Spring.
 */
enum class TimeOfDay { MORNING, AFTERNOON, EVENING, NIGHT }
