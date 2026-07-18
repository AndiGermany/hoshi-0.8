package de.hoshi.core.port

/**
 * **Schreib-Naht der Working-Session** (räumliches Gedächtnis, S1) — exakt
 * symmetrisch zu [EpisodicWriter]: NACH der Antwort eines Turns wird hier —
 * OHNE zusätzlichen Brain-Call, best-effort — das Turn-Paar (User-Text +
 * Antwort) an die personen-gekeyte Server-Session gehängt. Der Aufruf lebt im
 * `rememberAfter`-Hook des ChatStreamControllers, GENAU NEBEN
 * `memoryWriter.remember` und `episodicWriter.record`.
 *
 * Bewusst getrennt von der Lese-Naht [WorkingSessionPort] (Rekonstruktion
 * lesend), damit der Orchestrator-Pfad keine Schreib-Abhängigkeit trägt —
 * dieselbe Aufteilung wie [de.hoshi.core.pipeline.EpisodicRecallPort] ↔
 * [EpisodicWriter].
 *
 * **Default-OFF:** das verdrahtete [NOOP] tut nichts — kein Store, der Turn ist
 * byte-identisch zu heute. Erst bei `HOSHI_WORKING_SESSION_ENABLED=true` wird
 * der echte in-memory Adapter (speakerId-gekeyt, per-Speaker-CAP, isGuest-Gate)
 * gebunden. Ein **Gast** hinterlässt NIE eine Session-Zeile.
 */
fun interface WorkingSessionWriter {
    /** Hängt das Turn-Paar ([userText], [answer]) an die Session von [speakerId] (best-effort). */
    fun append(speakerId: String, userText: String, answer: String)

    companion object {
        /** Verhaltens-neutraler Default (Working-Session OFF) — speichert NIE. */
        val NOOP: WorkingSessionWriter = WorkingSessionWriter { _, _, _ -> }
    }
}
