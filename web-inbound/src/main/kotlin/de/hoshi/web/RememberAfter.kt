package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.EntityMemoryWriter
import de.hoshi.core.pipeline.SpeakerTrust
import de.hoshi.core.port.EpisodicWriter
import de.hoshi.core.port.WorkingSessionWriter
import reactor.core.publisher.Flux

/**
 * **RememberAfter** — der wiederverwendbare Gedächtnis-SCHREIB-Hook ans Ende eines Turns,
 * herausgezogen aus `ChatStreamController.rememberAfter` (dessen Verhalten byte-identisch
 * bleibt — [ChatStreamWorkingSessionTest]/[ChatStreamSpeakerTrustTest] beweisen es), damit
 * ihn auch der ws-Rand ([AudioWebSocketHandler]) nutzen kann.
 *
 * Sammelt den Antwort-Text (best-effort) und ruft NACH `onComplete` einmal den Entity-Store
 * ([EntityMemoryWriter.remember]), den episodischen Store ([EpisodicWriter.record]) UND die
 * Working-Session ([WorkingSessionWriter.append]) — alle speakerId-keyed, alle ohne zweiten
 * Brain-Call. Verhaltens-neutral: die [ChatEvent] fließen unverändert durch; ohne
 * Speaker-Kontext (bzw. bei einem nicht-vertrauten Claim unter Enforcement) wird gar nicht
 * erst umhüllt. Jeder Store läuft in eigenem [runCatching]: ein Fehler killt weder den Turn
 * noch die anderen Stores (best-effort, Never-Silent unberührt).
 *
 * **Vertrauens-Gate (P1-Privacy):** die tatsächlich verwendete `speakerId` kommt aus
 * [SpeakerTrust.resolve] statt direkt aus `request.speakerContext.speakerId` — bei
 * [speakerTrustEnforced]`=false` (Default) byte-neutral derselbe Wert wie vorher; bei `=true`
 * kann ein zu unsicherer/fehlender Claim auf den Gast kollabieren (kein Write unter einer
 * fremden Id). Am ws-Rand ruft [AudioWebSocketHandler] diesen Hook ohnehin NUR bei einem
 * echt ERKANNTEN Sprecher auf (nie bei einem rohen Client-Claim) — der Gast-Kollaps ist dort
 * die zweite Verteidigungslinie.
 */
class RememberAfter(
    private val memoryWriter: EntityMemoryWriter,
    private val episodicWriter: EpisodicWriter,
    private val workingSessionWriter: WorkingSessionWriter,
    private val speakerTrustEnforced: Boolean,
    private val speakerTrustThreshold: Double,
) {

    /**
     * Hängt den deterministischen Store-Hook ans Ende von [stream]: sammelt den Antwort-Text
     * und schreibt NACH `onComplete` die drei speakerId-gekeyten Stores. Ohne (vertrauten)
     * Sprecher-Kontext wird [stream] unverändert zurückgegeben (kein Umhüllen).
     */
    fun rememberAfter(request: ChatRequest, stream: Flux<ChatEvent>): Flux<ChatEvent> {
        val speakerId = SpeakerTrust.resolve(request.speakerContext, speakerTrustEnforced, speakerTrustThreshold)
            ?.speakerId ?: return stream
        val answer = StringBuilder()
        return stream
            .doOnNext { ev -> if (ev is ChatEvent.TextDelta) answer.append(ev.text) }
            .doOnComplete {
                val ans = answer.toString()
                runCatching { memoryWriter.remember(speakerId, request.text, ans) }
                runCatching { episodicWriter.record(speakerId, request.text, ans) }
                runCatching { workingSessionWriter.append(speakerId, request.text, ans) }
            }
    }
}
