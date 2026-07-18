package de.hoshi.core.port

import de.hoshi.core.dto.ChatMessage

/**
 * **Lese-Naht der Working-Session** (räumliches Gedächtnis, S1) — der
 * serverseitige, **speakerId-gekeyte** Kurzzeit-Verlauf des LAUFENDEN Gesprächs.
 *
 * Bewusst getrennt von der Schreib-Naht [WorkingSessionWriter] — dieselbe
 * Aufteilung wie [de.hoshi.core.pipeline.EpisodicRecallPort] ↔ [EpisodicWriter]:
 * der Prompt-/History-Pfad trägt keine Schreib-Abhängigkeit.
 *
 * Abgrenzung zu den bestehenden Gedächtnis-Nähten:
 *  - [de.hoshi.core.pipeline.EpisodicRecallPort] = semantischer Top-K-Recall
 *    *ähnlicher* Alt-Turns (Langzeit) — trägt eine „ER"-Anapher NICHT zuverlässig.
 *  - `TurnPromptAssembler.windowHistory` = zustandsloser TRIM der Client-history,
 *    besitzt KEINE Session (Byte-Neutralitäts-Vertrag, bleibt unangetastet).
 *  - DIESE Naht = der zusammenhängende Arbeits-Verlauf, personen-gekeyt: schickt
 *    der Client keine history (Voice, zweites Gerät, Page-Reload), rekonstruiert
 *    der [de.hoshi.core.pipeline.TurnOrchestrator] sie hieraus.
 *
 * **Schlüssel-Konsistenz (Vera-Regel, bindend):** gekeyt nach `speakerId` —
 * NIE Gerät, NIE `chatId`. Ein Gast (isGuest) bekommt IMMER die leere Liste.
 *
 * **Default-OFF:** das verdrahtete [NONE] liefert immer die leere Liste — eine
 * leere Client-history bleibt leer, der [ChatRequest][de.hoshi.core.dto.ChatRequest]
 * an den Brain ist byte-identisch zu heute. Erst bei
 * `HOSHI_WORKING_SESSION_ENABLED=true` wird der echte in-memory Adapter gebunden.
 */
fun interface WorkingSessionPort {
    /**
     * Die jüngsten Turns des laufenden Gesprächs von [speakerId], chronologisch
     * als abwechselnde user/assistant-[ChatMessage]s. Leer = keine Session
     * (Gast, unbekannter Sprecher, Feature OFF).
     */
    fun recentTurns(speakerId: String): List<ChatMessage>

    /**
     * **S2 — segment-bewusste Lese-Naht:** liefert das AKTUELLE Themen-Segment
     * statt „der letzten N", plus die Diary-Felder der Grenz-Entscheidung
     * ([WorkingSessionSegment]). Die [utterance] (aktuelle Äußerung) fließt mit,
     * weil eine Reset-Phrase am Äußerungs-ANFANG („ganz was anderes: …") schon
     * DIESEN Turn frisch starten muss — nicht erst den nächsten.
     *
     * Default: delegiert auf [recentTurns] mit neutralen Diary-Feldern — jede
     * S1-Implementierung (und [NONE]) verhält sich exakt wie vorher.
     */
    fun readSegment(speakerId: String, utterance: String): WorkingSessionSegment =
        WorkingSessionSegment(turns = recentTurns(speakerId))

    companion object {
        /** Verhaltens-neutraler Default (Working-Session OFF) — erinnert NIE. */
        val NONE: WorkingSessionPort = WorkingSessionPort { emptyList() }
    }
}

/**
 * **S2-Lese-Ergebnis:** das aktuelle Themen-Segment + die Grenz-Entscheidung als
 * Diary-Felder (additive S4-Kalibrier-Basis, Muster `groundingUsed`):
 *
 *  - [segmentReset]: begann mit DIESER Äußerung ein frisches Segment (die
 *    rekonstruierte history ist dann bewusst leer)?
 *  - [resetReason]: [REASON_TIME_GAP] | [REASON_MARKER] | [REASON_NONE].
 *    [REASON_SEMANTIC] ist reserviert für die EmbeddingGemma-Zentroid-Scheibe
 *    (semantische Distanz, bewusst NICHT in S2 — braucht den Embed-Sidecar live).
 *  - [segmentLenTurns]: Länge des zurückgegebenen Segments in TURN-Paaren
 *    (0 bei Reset/leerer Session).
 *
 * **Andi-Entscheid 03.07 (bindend):** ein Raum-Wechsel ist NIE eine Themen-Grenze
 * — Raum ist nur Ausgabe-Routing. Grenzen kommen allein aus Zeit-Lücke,
 * semantischer Distanz (Folge-Scheibe) und expliziten Reset-Phrasen.
 */
data class WorkingSessionSegment(
    val turns: List<ChatMessage>,
    val segmentReset: Boolean = false,
    val resetReason: String = REASON_NONE,
    val segmentLenTurns: Int = 0,
) {
    companion object {
        const val REASON_NONE = "none"
        const val REASON_TIME_GAP = "time-gap"
        const val REASON_MARKER = "marker"

        /** Reserviert (Folge-Scheibe EmbeddingGemma-Zentroid) — wird in S2 nie emittiert. */
        const val REASON_SEMANTIC = "semantic"
    }
}
