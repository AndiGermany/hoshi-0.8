package de.hoshi.core.dto

/**
 * Zusammengefasster Per-Turn-Kontext (PORT-Einheit aus dem Hoshi-0.5
 * brain-streaming-Ledger, dort `TurnContext`). Ersetzt das wiederholte
 * Durchreichen von `(request, text, chatId)`-Trios durch die Pipeline-Stufen.
 *
 * Bewusst klein gehalten — kein "kitchen sink", nur was über mehrere Stufen
 * gemeinsam gebraucht wird. Reines Daten-DTO, kein Spring.
 */
data class TurnPrompt(
    /** Bereinigter User-Input (`request.text.trim()`). */
    val text: String,
    /** Chat-/Session-ID, default `"default"` wenn nicht gesetzt. */
    val chatId: String,
    /** Sprich-Audio im Stream emittieren? Spiegelt [ChatRequest.speak]. */
    val speak: Boolean,
    /** Optional vom Frontend forcierter Provider (z.B. "OPENAI"). */
    val provider: String? = null,
    /**
     * Tonfall für die TTS-Prosodie. Aus der PersonaEmotion abgeleitet und in
     * [de.hoshi.core.pipeline.ProsodyShaper] angewandt. Default NORMAL = warme Stimme.
     */
    val tone: ProsodyTone = ProsodyTone.NORMAL,
    /**
     * Sprache des Turns. Fließt aus [ChatRequest.language] mit und steuert
     * sprach-bewusste Stufen (Prosody, Intent-Keywords, TTS-Stimme).
     */
    val language: Language = Language.DEFAULT,
    /**
     * Charakter-Stimme des Turns. Fließt aus [ChatRequest.persona] mit und wählt
     * den byte-stabilen Persona-Prompt-Body + die Default-Stimmung (Temperatur).
     * Per-Turn getragen (kein globaler Mood-Holder) ⇒ kollisionsfrei bei parallelen
     * Sessions. Default [Persona.STANDARD] ⇒ heutiges Verhalten unverändert.
     */
    val persona: Persona = Persona.STANDARD,
    /**
     * Per-Turn-Sprecher (Identity-Isolation): wird aus [ChatRequest.speakerContext]
     * mit dem Request getragen. `null` → Fallback auf einen globalen Holder
     * (rückwärts-kompatibel, z.B. Text-API ohne Speaker-Frame).
     */
    val speaker: SpeakerContext? = null,
    /** Pro-Request wählbares Brain ("ollama:<model>") aus [ChatRequest.model]. */
    val model: String? = null,
    /** Original-Request — wenn doch noch ein Feld fehlt. Möglichst nicht mehr nötig. */
    val request: ChatRequest,
) {
    companion object {
        fun from(request: ChatRequest): TurnPrompt = TurnPrompt(
            text = request.text.trim(),
            chatId = request.chatId ?: "default",
            speak = request.speak,
            provider = request.provider,
            language = request.language,
            persona = request.persona,
            speaker = request.speakerContext,
            model = request.model,
            request = request,
        )
    }
}
