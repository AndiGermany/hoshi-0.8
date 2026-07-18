package de.hoshi.web

import de.hoshi.core.dto.ChatEvent

/**
 * **ChatEventWsTranslator** — die WS-RAND-Übersetzung zwischen der 0.8-internen
 * Wahrheit ([ChatEvent], sealed) und dem **0.7-String-Frame-Vokabular**, das die
 * geflashte Voice-PE-Firmware (und das Browser-FE) erwartet.
 *
 * Bewusst ein **reines `object`** ohne Spring/Infra — jede Methode ist eine
 * deterministische `(…) -> String`-Funktion, damit der Frame-Vertrag thoroughly
 * unit-getestet werden kann (`ChatEventWsTranslatorTest`), ohne einen WebSocket
 * oder den Brain zu booten. Der [AudioWebSocketHandler] ist der einzige Aufrufer.
 *
 * Übersetzungstabelle (siehe `vault/tracks/STT-VOICE-INPUT.md` §2):
 * | 0.8 `ChatEvent`            | → 0.7-Wire-Frame                         |
 * |---------------------------|------------------------------------------|
 * | `Start`                   | `llm_start{provider,category,model,emotion}` |
 * | `TextDelta`               | `llm_delta{text}`                        |
 * | `TtsAudioStart`           | `tts_audio_start{provider[,estimatedMs]}` |
 * | `AudioChunk{data,seq}`    | `llm_audio{seq,data}` **(byte-1:1)**     |
 * | `TtsAudioEnd`             | `tts_audio_end{actualMs}`                |
 * | `Done{ttsHandled}`        | `llm_done{ttsHandled}`                   |
 * | `Error{message,stage}`    | `llm_error{stage,message}`               |
 * | `Step`                    | `null` (Transkript-Frame baut der Handler selbst) |
 * | `Speaker{recognizedSpeaker}` | `speaker{speakerId}` **NUR sicherer Treffer + Flag ON**, sonst `null` |
 *
 * Die STT-/Steuer-Frames (`transcribing_started`/`transcript`/`no_input`/
 * `llm_thinking`/`turn_aborted`) entstehen NICHT aus einem [ChatEvent] (sie liegen
 * vor/neben dem Turn) — der Handler baut sie über die expliziten Builder unten.
 */
object ChatEventWsTranslator {

    /**
     * Übersetzt EIN [ChatEvent] in seinen 0.7-Wire-Frame (JSON-Text). `null` ⇒ der
     * Frame wird NICHT ans Gerät geschickt — heute [ChatEvent.Step] (das Transkript
     * reicht der Handler separat als `transcript`-Frame raus) sowie [ChatEvent.Speaker]
     * ohne sicheren Treffer ODER bei [speakerFrameEnabled] == `false`.
     *
     * [speakerFrameEnabled] gated NUR den `speaker`-Downlink-Frame (Default `false` ⇒
     * exakt das Vor-Verhalten, byte-neutral) — alle anderen Zeilen der Übersetzungs-
     * tabelle bleiben davon unberührt. Der [AudioWebSocketHandler] reicht hier
     * `HOSHI_WS_SPEAKER_FRAME_ENABLED` durch (s. [WebSocketConfig]).
     */
    fun translate(event: ChatEvent, speakerFrameEnabled: Boolean = false): String? = when (event) {
        is ChatEvent.Start ->
            """{"type":"llm_start","provider":${j(event.provider)},"category":${j(event.category)},"model":${j(event.model)},"emotion":${j(event.personaEmotion)}}"""
        is ChatEvent.TextDelta ->
            """{"type":"llm_delta","text":${j(event.text)}}"""
        is ChatEvent.AudioChunk ->
            """{"type":"llm_audio","seq":${event.seq},"data":${j(event.data)}}"""
        is ChatEvent.TtsAudioStart ->
            """{"type":"tts_audio_start","provider":${j(event.provider)}${event.estimatedMs?.let { ",\"estimatedMs\":$it" } ?: ""}}"""
        is ChatEvent.TtsAudioEnd ->
            """{"type":"tts_audio_end","actualMs":${event.actualMs}}"""
        is ChatEvent.Done ->
            """{"type":"llm_done","ttsHandled":${event.ttsHandled}}"""
        is ChatEvent.Error ->
            """{"type":"llm_error","stage":${j(event.stage)},"message":${j(event.message)}}"""
        // Step trägt das Transkript als Domänen-Event; auf dem WS-Rand baut der
        // Handler den `transcript`-Frame selbst (vor dem Turn) → hier nicht doppeln.
        is ChatEvent.Step -> null
        // Speaker (S3) → LED-Erkennungs-Schimmer am Voice-PE-Gerät (Downlink-Frame,
        // vereinbart mit der Geräte-Firmware, die schon darauf lauscht). Der Frame
        // bleibt bewusst NACKT: KEIN confidence, KEIN isGuest auf dem Draht — welche
        // LED-Farbe zu welchem Sprecher gehört, entscheidet das GERÄT, nicht der
        // Server, und die Roh-Erkennungswerte sind Server-interne Details, die auf
        // dem Draht nichts verloren haben. Ein Gast (kein sicherer Treffer,
        // [ChatEvent.Speaker.recognizedSpeaker] == null) bekommt NIE einen Frame —
        // raten wäre schlimmer als Schweigen (Vera-Vertrag, s. [ChatEvent.Speaker]-
        // KDoc). Flag-gated (`speakerFrameEnabled`, Default `false`) ⇒ OFF bleibt
        // IMMER `null`, byte-identisch zum Vor-Zustand.
        is ChatEvent.Speaker ->
            event.recognizedSpeaker
                ?.takeIf { speakerFrameEnabled }
                ?.let { """{"type":"speaker","speakerId":${j(it)}}""" }
    }

    // ── STT-/Steuer-Frames (pre/neben-Turn, ohne ChatEvent-Quelle) ──────────────

    /** Heartbeat sofort nach `stop`: das Gerät bleibt im transcribing-Overlay. */
    fun transcribingStarted(): String = """{"type":"transcribing_started"}"""

    /** Das erkannte Transkript (leer ⇒ `no_input` folgt). */
    fun transcript(text: String): String = """{"type":"transcript","text":${j(text)}}"""

    /** „Nichts verstanden" — Stille / zu kurzer Input / STT-Fehler (never-silent). */
    fun noInput(): String = """{"type":"no_input"}"""

    /** Heartbeat zwischen Transkript und erstem LLM-Token (Cloud kann 300-1500ms brauchen). */
    fun llmThinking(): String = """{"type":"llm_thinking"}"""

    /** Sichtbarer warmer Fehler-Frame (never-silent statt stiller Socket). */
    fun llmError(stage: String, message: String): String =
        """{"type":"llm_error","stage":${j(stage)},"message":${j(message)}}"""

    /** Terminaler Frame eines Turns. */
    fun llmDone(ttsHandled: Boolean = false): String =
        """{"type":"llm_done","ttsHandled":$ttsHandled}"""

    /** Half-Duplex-Barge-in-Quittung; `turnId` nur gesetzt, wenn der Turn eine hatte. */
    fun turnAborted(turnId: String?): String =
        if (turnId.isNullOrEmpty()) """{"type":"turn_aborted"}"""
        else """{"type":"turn_aborted","turnId":${j(turnId)}}"""

    /**
     * Hängt `"turnId":…` an einen `{…}`-Frame an, wenn für die Session eine turnId
     * gesetzt ist (sonst unverändert ⇒ rückwärtskompatibel zum Browser-FE ohne
     * turnId). Portiert aus 0.5 `withTurnId`.
     */
    fun withTurnId(frame: String, turnId: String?): String =
        if (!turnId.isNullOrEmpty() && frame.endsWith("}"))
            frame.dropLast(1) + ""","turnId":${j(turnId)}}"""
        else frame

    /**
     * Minimaler, korrekter JSON-String-Encoder (Anführungszeichen + Escapes),
     * 1:1 aus dem 0.5-Handler portiert. Bewusst hand-rolled, damit der Translator
     * ein dependency-freies `object` bleibt (kein ObjectMapper im Frame-Vertrag).
     */
    fun j(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < '\u0020') sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
