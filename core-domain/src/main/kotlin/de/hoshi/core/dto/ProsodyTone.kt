package de.hoshi.core.dto

/**
 * Tonfall für die TTS-Prosodie (PORT-Einheit aus dem Hoshi-0.5
 * brain-streaming-Ledger). Aus der [PersonaEmotion] abgeleitet und in
 * [de.hoshi.core.pipeline.ProsodyShaper] auf die Satzzeichen des gesprochenen
 * Texts angewandt.
 *
 * Sara-Brief — drei Tonfälle, nicht zwanzig:
 *  - **CALM** (müde/spät): dämpft Ausrufe → Punkte. Hoshi „brüllt" nicht spät abends.
 *  - **NORMAL** (~90 % aller Turns): unverändert. Die warme Default-Stimme.
 *  - **ENERGETIC** (Erfolg): unverändert durchgereicht — die Energie steckt schon
 *    im LLM-Output. Wir dämpfen nur, wir hypen nicht.
 */
enum class ProsodyTone { CALM, NORMAL, ENERGETIC }

/**
 * Persona-Emotionszustand. Wird über [de.hoshi.core.pipeline.ProsodyShaper.toneFor]
 * auf einen der drei [ProsodyTone] gemappt.
 */
enum class PersonaEmotion { NEUTRAL, CALM, FOCUSED, WARM, CHEERFUL }
