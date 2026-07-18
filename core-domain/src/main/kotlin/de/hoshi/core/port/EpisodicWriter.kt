package de.hoshi.core.port

/**
 * **Store-Naht des episodischen Gedächtnisses** (0.8) — analog zu
 * [de.hoshi.core.pipeline.EntityMemoryWriter], aber für GESPRÄCHSKONTEXT statt
 * strukturierte Fakten. NACH der Antwort eines Turns wird hier — OHNE zusätzlichen
 * Brain-Call (die heilige „max 1 Brain-Call/Turn"-Invariante bleibt unberührt;
 * der Embed-Roundtrip ist ein billiger Vektor-Call) — der substanzielle User-Turn
 * eingebettet+persistiert, je [speakerId].
 *
 * Bewusst getrennt von der Lese-Naht [de.hoshi.core.pipeline.EpisodicRecallPort]
 * (Recall lesend), damit der Prompt-Assembly-Pfad keine Schreib-Abhängigkeit trägt
 * — dieselbe Aufteilung wie [de.hoshi.core.pipeline.EntityContextPort] ↔
 * [de.hoshi.core.pipeline.EntityMemoryWriter].
 *
 * **Default-OFF** (Privacy): das verdrahtete [NOOP] tut nichts. Erst bei
 * `HOSHI_EPISODIC_ENABLED=true` wird der echte Adapter (sqlite, speakerId-keyed)
 * gebunden. Ein **Gast** (leer/`unknown`/`gast`/ungültig) wird vom Adapter NIE
 * gespeichert.
 */
fun interface EpisodicWriter {
    /** Bettet den [userText] des Turns ein und persistiert ihn je [speakerId] (best-effort). */
    fun record(speakerId: String, userText: String, answer: String)

    companion object {
        /** Verhaltens-neutraler Default (Episodic OFF) — speichert NIE. */
        val NOOP: EpisodicWriter = EpisodicWriter { _, _, _ -> }
    }
}
