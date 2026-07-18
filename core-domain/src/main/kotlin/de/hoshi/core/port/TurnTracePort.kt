package de.hoshi.core.port

import java.time.Instant

/**
 * **Ein Turn im Nutzungs-Diary** — die Längsschnitt-Messung hinter dem
 * North-Star („Andi-Faktor ≥ 4,2 über 14 Tage"): ohne Diary können wir
 * *benutzbar* nicht von *Demo* unterscheiden. Bewusst nur primitive Felder
 * (Strings statt Domain-Enums), damit der Trace-Vertrag stabil bleibt, auch
 * wenn Provider/Persona-Typen sich weiterentwickeln.
 *
 * Alle Felder sind vom Rand des Event-Streams ablesbar (Start/TextDelta/
 * AudioChunk/Done/Error + Wall-Clock) — der Trace zwingt NIE einen Eingriff
 * in den [de.hoshi.core.pipeline.TurnOrchestrator].
 */
data class TurnTrace(
    /** Zeitpunkt des Turn-Starts (Subscribe des Event-Streams). */
    val ts: Instant,
    /** Chat-/Session-Kennung (leer, wenn der Request keine trägt). */
    val chatId: String = "",
    /** Routing-Kategorie aus [de.hoshi.core.dto.ChatEvent.Start] (z.B. FACT_SHORT, SMART_HOME). */
    val category: String = "",
    /** Provider aus Start/Done (LOCAL/CLOUD/…). */
    val provider: String = "",
    /** Effektive Persona des Turns (nach PersonaResolver). */
    val persona: String = "",
    /** Effektive Turn-Sprache (DE/EN, nach LanguageResolver). */
    val language: String = "",
    /** Time-to-first-token: ms bis zur ERSTEN TextDelta (null = nie eine gesehen). */
    val ttftMs: Long? = null,
    /** Gesamtdauer des Turns in ms (Subscribe → Done/Error/Cancel). */
    val totalMs: Long = 0,
    /** Summe der TextDelta-Zeichen (Antwortlänge ohne den Text selbst zu loggen — Privacy). */
    val deltaChars: Int = 0,
    /** Anzahl der AudioChunk-Events (0 bei reinen Text-Turns). */
    val audioChunks: Int = 0,
    /** War es ein Sprach-Turn (TtsStage aktiv)? */
    val speak: Boolean = false,
    /**
     * Ehrlichkeits-Deflect des [de.hoshi.core.pipeline.FactCoverageGate]: die
     * deterministische Deflection-Phrase kam statt einer Brain-Antwort. DER
     * Lücken-Sensor für die Nachtschicht — jedes `deflected=true` ist eine
     * Wissens-Lücke, die nachts gestopft werden kann.
     */
    val deflected: Boolean = false,
    /**
     * Fehler-Stage (STT/LLM/SIDECAR/TTS) aus [de.hoshi.core.dto.ChatEvent.Error], null = fehlerfrei.
     * Ausnahme `category=ABORTED`: dort trägt das Feld den Abbruch-GRUND
     * (AUDIO_CAP/SESSION_GUARD) statt einer Stage — die Wire-Stage ist bei
     * Guard-Abbrüchen uniform STT und würde nichts unterscheiden.
     */
    val error: String? = null,
    /** Trug der Turn echtes Grounding (Wissens-Bridge-Kontext im Prompt)? */
    val groundingUsed: Boolean = false,
    /**
     * **Eingangs-Rand des Turns** (additiv, Default `""` — bestehende Aufrufer
     * und Alt-Zeilen unverändert): `"chat"` = `POST /api/v1/chat/stream`,
     * `"voice"` = `POST /api/v1/voice`. Ohne dieses Feld war das Diary blind
     * für den Weg, über den ein Turn hereinkam — und damit blind für Andis
     * Hauptnutzungsweg (Sprache) im STRICT-Entscheid.
     */
    val source: String = "",
    /**
     * **Themen-Segment-Diary (S2 räumliches Gedächtnis, additiv — Muster
     * [groundingUsed]):** begann mit diesem Turn ein frisches Themen-Segment?
     * Aus [de.hoshi.core.dto.ChatEvent.Start.segmentReset] gelesen; Defaults für
     * alle Nicht-Brain-Pfade. Die S4-Kalibrier-Basis (Reset-Rate, Segment-Längen,
     * Zeit-Lücke-vs-Marker-Beitrag).
     */
    val segmentReset: Boolean = false,
    /** Grenz-Grund: "time-gap" | "marker" | "none" ("semantic" reserviert für die Embed-Folge-Scheibe). */
    val resetReason: String = "none",
    /** Länge des rekonstruierten Themen-Segments in TURN-Paaren (0 = Reset/keine Session). */
    val segmentLenTurns: Int = 0,
    // ── Stage-Metriken (Perf-Diary, additiv — Muster source/segmentReset): alle
    // Defaults null ⇒ Alt-Zeilen/Alt-Aufrufer unverändert. null heißt IMMER
    // „an dieser Naht wurde nicht gemessen" (Text-Turn ⇒ sttMs=null, speak=false
    // ⇒ ttsFirstAudioMs=null, Gate OFF ⇒ admissionWaitMs=null) — nie ein
    // erfundenes 0. Quelle: [de.hoshi.core.dto.ChatEvent.StageTimings] am
    // Done-Event bzw. der sttMs-Messwert des Inbound-Rands. ──
    /** Dauer des SttPort.transcribe-Calls am Voice-/WS-Rand (null = kein STT, z.B. Text-Turn). */
    val sttMs: Long? = null,
    /** Dauer des echten GroundingPort-Calls im TurnPromptAssembler (null = kein Grounding-Call). */
    val groundingMs: Long? = null,
    /** Brain-Call-Start → erste TextDelta (null = kein Brain-Call / nie eine Delta). */
    val brainTtftMs: Long? = null,
    /** TtsStage-Start → erster AudioChunk (null = kein Audio, z.B. speak=false). */
    val ttsFirstAudioMs: Long? = null,
    /** Wartezeit im BrainAdmissionGate bis Permit (null = Gate OFF / kein Permit). */
    val admissionWaitMs: Long? = null,
    /**
     * **Antwort-Entropie des Turns** (S1, additiv — Muster der Stage-Metriken,
     * aber KEINE ms-Latenz): mittlerer Surprisal −mean(logprob) der Antwort-
     * Tokens in nats (≥ 0; höher = der Brain hat eher „geraten"). Quelle:
     * [de.hoshi.core.dto.ChatEvent.StageTimings.answerEntropy] am Done-Event.
     * `null` = nicht gemessen (Flag OFF / Brain-Server ohne logprobs) — nie ein
     * erfundenes 0. Die S2-Kalibrier-Basis für den Abstain-/Extended-Think-Trigger.
     */
    val answerEntropy: Double? = null,
    /**
     * **STT-Surprisal des Whisper-Transkripts** (Verhör-Detektor S1, additiv —
     * Muster [answerEntropy], aber am EINGANG statt am Ausgang des Turns):
     * mittlerer Token-Surprisal (`-mean(logprob)`, nats) des rohen Transkripts,
     * NUR bei Voice-/WS-Turns gemessen. Quelle:
     * [de.hoshi.core.dto.ChatEvent.StageTimings.sttSurprisal] am Done-Event.
     * `null` = nicht gemessen (Flag OFF / kein Voice-Transkript / Score-Endpoint
     * fehlt/Timeout) — nie ein erfundenes 0. Die S2-Kalibrier-Basis für einen
     * künftigen Rückfrage-Trigger.
     */
    val sttSurprisal: Double? = null,
    /**
     * Höchster Einzel-Token-Surprisal des Transkripts (additiv — Muster
     * [sttSurprisal]). Quelle: [de.hoshi.core.dto.ChatEvent.StageTimings.sttSurprisalMax].
     */
    val sttSurprisalMax: Double? = null,
    // ── Extended-Think S4 (additiv ANS ZEILENENDE — Muster answerEntropy): der
    // Eskalations-/Cache-Diary für die S4-Kalibrier-Basis (Eskalations-Rate, echte
    // Kosten, ROI der S3-Cache-Scheibe). Quelle: [de.hoshi.core.dto.ChatEvent.Start]
    // (escalated/cacheHit) bzw. [de.hoshi.core.dto.ChatEvent.Done] (escalationCostCents,
    // erst nach dem asynchronen Lookup bekannt). ──
    /**
     * Lief dieser Turn über den bezahlten Eskalations-Pfad
     * ([de.hoshi.core.pipeline.TurnOrchestrator.escalationTurn], S2 Online-
     * Nachschau)? Aus [de.hoshi.core.dto.ChatEvent.Start.escalated] gelesen;
     * Default `false` (Alt-Aufrufer/Nicht-Eskalations-Turns unverändert).
     */
    val escalated: Boolean = false,
    /**
     * Echte Kosten (ca.-Cents) einer bezahlten
     * [de.hoshi.core.port.EscalationResult.Answer], aus
     * [de.hoshi.core.dto.ChatEvent.Done.escalationCostCents] gelesen. `null` =
     * kein Eskalations-Turn ODER die Eskalation lieferte keine Answer
     * (Unclear/Unavailable/Declined) — nie eine erfundene 0.0.
     */
    val escalationCostCents: Double? = null,
    /**
     * Deckte die S3-Cache-Scheibe
     * ([de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider], „einmal
     * bezahlt, für immer gewusst") den Grounding-Block dieses Turns? Aus
     * [de.hoshi.core.dto.ChatEvent.Start.cacheHit] gelesen. `true` ⇒ diese Frage
     * kostete NICHTS — der ROI-Sensor der S3-Cache-Scheibe. Default `false`.
     */
    val cacheHit: Boolean = false,
    // ── H2: Turn↔Note-Verknüpfung (additiv ANS ZEILENENDE — Muster escalated/
    // cacheHit/escalationCostCents): bislang trug die Trace NUR, DASS ein Turn
    // eskalierte/einen Cache-Hit hatte — nicht, WELCHE [LookupNote] beteiligt war.
    // Quelle: [de.hoshi.core.dto.ChatEvent.Start.escalationSource] (Cache-Hit-Fall,
    // vor dem Brain-Call bekannt) bzw. [de.hoshi.core.dto.ChatEvent.Done.escalationQueryHash]/
    // [de.hoshi.core.dto.ChatEvent.Done.escalationSource] (Eskalations-Fall, erst nach
    // dem asynchronen Lookup bekannt). ──
    /**
     * Der [de.hoshi.core.port.LookupNote.queryHash] der Notiz, die DIESER Turn
     * geschrieben hat — NUR am Eskalations-Pfad gesetzt, NUR bei einer echten
     * [de.hoshi.core.port.EscalationResult.Answer] (sonst wurde nie eine Notiz
     * geschrieben, Nora-Veto). `null` = kein Eskalations-Turn ODER kein
     * Answer-Ausgang ODER ein Cache-Hit-Turn (dort ist die GELESENE Notiz an
     * dieser Naht nicht mehr identifizierbar, s. [escalationSource]) — nie ein
     * erfundener Hash.
     */
    val escalationQueryHash: String? = null,
    /**
     * Die Quellen-Angabe der beteiligten [de.hoshi.core.port.LookupNote] —
     * entweder frisch geschrieben (Eskalations-Fall,
     * [de.hoshi.core.port.EscalationResult.Answer.source]) oder wiederverwendet
     * (Cache-Hit-Fall, aus dem bereits assemblierten groundBlock geparst). `null`
     * = weder eskaliert noch Cache-Hit. **Ehrlich dokumentiert:** dies ist die
     * vom Modell/der Notiz BEHAUPTETE Quelle, UNVERIFIZIERT — kein Beleg, dass
     * sie stimmt, nur dass sie so genannt wurde.
     */
    val escalationSource: String? = null,
    /**
     * **H3 — Tages-Cap erreicht, KEIN Netzfehler:** `true` GENAU DANN, wenn der
     * Eskalations-Pfad [de.hoshi.core.port.EscalationResult.CapExhausted]
     * lieferte (kein HTTP-Call ging raus, das Tages-Budget war leer) — der
     * Diary-Sensor, der Cap-Erschöpfung von einem echten Netz-/Key-Fehler
     * ([de.hoshi.core.port.EscalationResult.Unavailable], Feld bleibt `false`)
     * unterscheidbar macht. Default `false` ⇒ byte-neutral ohne H3-Fall.
     */
    val escalationCapExhausted: Boolean = false,
)

/**
 * **Auslass-Naht des Nutzungs-Diaries** (Backlog #10) — genau EIN Verb, exakt
 * das Muster von [EpisodicWriter]: read-only fürs Verhalten, off-hot-path,
 * best-effort. Der Aufrufer (Tap am Flux-Rand) ruft [record] NACH dem Turn;
 * eine Implementierung darf NIE werfen und NIE blockieren (der Event-Loop ist
 * heilig — siehe die P0-Lehren im TurnOrchestrator zu boundedElastic).
 *
 * **Default-OFF:** das verdrahtete [NOOP] tut nichts. Erst bei
 * `HOSHI_TURN_DIARY_ENABLED=true` wird der echte JSONL-Adapter gebunden.
 */
fun interface TurnTracePort {
    /** Persistiert einen Turn-Trace (best-effort, non-blocking, wirft NIE). */
    fun record(trace: TurnTrace)

    companion object {
        /** Verhaltens-neutraler Default (Diary OFF) — schreibt NIE. */
        val NOOP: TurnTracePort = TurnTracePort { }
    }
}
