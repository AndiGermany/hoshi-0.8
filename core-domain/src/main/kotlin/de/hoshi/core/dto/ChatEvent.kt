package de.hoshi.core.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import de.hoshi.core.port.EscalationSourceRef

/**
 * Der sealed SSE/WS-Wire-Vertrag der Voice-Pipeline (PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger). Jeder Voice-Turn streamt eine Sequenz
 * dieser Events ans Frontend.
 *
 * Bewusst nur mit `jackson-annotations` dekoriert (KEIN jackson-databind im
 * reinen Kern) — die eigentliche (De-)Serialisierung lebt im Inbound-Adapter,
 * der den `ObjectMapper` hält. Die Annotations sind nur der Vertrag.
 *
 * `ttsHandled=true` (in [Done]) → das Backend hat TTS lokal abgespielt
 * (macOS `say`), das Frontend soll NICHT zusätzlich sprechen.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "event")
@JsonSubTypes(
    JsonSubTypes.Type(value = ChatEvent.Start::class, name = "start"),
    JsonSubTypes.Type(value = ChatEvent.TextDelta::class, name = "delta"),
    JsonSubTypes.Type(value = ChatEvent.AudioChunk::class, name = "audio"),
    JsonSubTypes.Type(value = ChatEvent.TtsAudioStart::class, name = "tts_audio_start"),
    JsonSubTypes.Type(value = ChatEvent.TtsAudioEnd::class, name = "tts_audio_end"),
    JsonSubTypes.Type(value = ChatEvent.Step::class, name = "step"),
    JsonSubTypes.Type(value = ChatEvent.Speaker::class, name = "speaker"),
    JsonSubTypes.Type(value = ChatEvent.Done::class, name = "done"),
    JsonSubTypes.Type(value = ChatEvent.Error::class, name = "error"),
)
sealed class ChatEvent {

    data class Start(
        val provider: String,
        val category: String,
        val model: String,
        val personaEmotion: String = "neutral",
        /**
         * **Ehrliche Grounding-Sichtbarkeit am Rand** (additiv, Default `false` —
         * bestehende Aufrufer kompilieren/serialisieren unverändert): trug dieser
         * Turn gedecktes Grounding im Sinne von
         * [de.hoshi.core.pipeline.FactCoverageGate]`.groundingCovered`? Gesetzt vom
         * TurnOrchestrator am Brain-Pfad; Policy-/Fastpath-Starts bleiben ehrlich
         * `false`. Füttert `TurnTrace.groundingUsed` (Diary) statt hardcoded false.
         */
        val grounded: Boolean = false,
        /**
         * **Themen-Segment-Diary (S2 räumliches Gedächtnis, additiv — Muster
         * [grounded]):** begann mit dieser Äußerung ein frisches Themen-Segment
         * (die Server-Session-history wurde bewusst NICHT rekonstruiert)?
         * Gesetzt vom TurnOrchestrator am Brain-Pfad aus der
         * [de.hoshi.core.port.WorkingSessionSegment]-Entscheidung; Policy-/
         * Fastpath-/Agentic-Starts und Turns mit Client-history bleiben auf den
         * Defaults. Füttert `TurnTrace.segmentReset/resetReason/segmentLenTurns`
         * — die S4-Kalibrier-Basis.
         */
        val segmentReset: Boolean = false,
        /** Grenz-Grund: "time-gap" | "marker" | "none" ("semantic" reserviert für die Embed-Folge-Scheibe). */
        val resetReason: String = "none",
        /** Länge des rekonstruierten Themen-Segments in TURN-Paaren (0 = Reset/keine Session). */
        val segmentLenTurns: Int = 0,
        /**
         * **Extended-Think-Eskalation dieses Turns** (S4 Diary, additiv — Muster
         * [grounded]/[segmentReset]): lief dieser Turn über
         * [de.hoshi.core.pipeline.TurnOrchestrator]s bezahlten Eskalations-Pfad
         * (`escalationTurn`, S2 Online-Nachschau)? Gesetzt NUR dort, zusammen mit
         * [escalationProvider]; alle anderen Starts (Brain-/Tool-/Policy-Pfade)
         * bleiben ehrlich `false`. Füttert `TurnTrace.escalated` — die S4-Kosten-/
         * Rate-Kalibrier-Basis.
         */
        val escalated: Boolean = false,
        /**
         * **Eskalations-Provider** (additiv, Default `""` — Muster [escalated]): der
         * [de.hoshi.core.port.EscalationPort]-Adapter, der diesen Turn bediente —
         * i.d.R. `"openai-nano"`, s.
         * [de.hoshi.core.pipeline.TurnOrchestrator.LOOKUP_NOTE_PROVIDER]. Seit dem
         * Recherche-Modell-Auftrag (2026-07-19) trägt ein Recherche-Imperativ mit
         * konfiguriertem Recherche-Modell stattdessen dessen eigenes Label (z.B.
         * `"openai-sol"`, s. [de.hoshi.core.pipeline.TurnOrchestrator.escalationChoice]) —
         * NIE fälschlich `"openai-nano"` auf einer Antwort eines anderen Modells.
         * Leer = kein Eskalations-Turn. Gesetzt zusammen mit [escalated].
         */
        val escalationProvider: String = "",
        /**
         * **Grounding-Cache-Hit** (S4 Diary, additiv — Muster [grounded]): deckte
         * [de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider] (die S3-
         * Cache-Scheibe „einmal bezahlt, für immer gewusst") den Grounding-Block
         * dieses Turns? Gesetzt am Brain-Pfad ZUSÄTZLICH zu [grounded] — ein
         * Cache-Hit IST gedeckt, aber nicht jedes gedeckte Grounding ist ein
         * Cache-Hit (wiki/weather zählen NICHT). `true` ⇒ diese Frage kostete
         * NICHTS (kein zweiter Eskalations-Call nötig). Füttert
         * `TurnTrace.cacheHit` — der ROI-Sensor der S3-Cache-Scheibe.
         */
        val cacheHit: Boolean = false,
        /**
         * **Cache-Hit-Quelle (H2, additiv, Default `""` — Muster [escalationProvider]):**
         * NUR bei [cacheHit]=`true` gesetzt — die im bereits assemblierten
         * `groundBlock` behauptete `Quelle:`-Zeile der getroffenen
         * [de.hoshi.core.port.LookupNote] (String-Extraktion, s.
         * [de.hoshi.core.pipeline.TurnOrchestrator.parseCacheHitSource]; die Note
         * selbst reist an dieser Naht nicht mehr mit). Bekannt VOR dem
         * Brain-Call ⇒ reist wie [cacheHit] am Start, NICHT am Done.
         * **Ehrlich:** wie [de.hoshi.core.port.EscalationResult.Answer.source]
         * die vom Modell/der Notiz BEHAUPTETE Quelle, unverifiziert. Füttert
         * `TurnTrace.escalationSource` (Turn↔Note-Verknüpfung).
         */
        val escalationSource: String = "",
        /**
         * **Angesteuerte HA-Area dieses Turns** (Räume-Nutzungs-Naht, additiv,
         * Default `null` — Muster [grounded]/[segmentReset], kartiert in Commit
         * f049965 / `frontend/src/components/roomsSort.ts`-KDoc „Konzept 1a
         * mangels Datengrundlage NICHT gebaut"): die `area_id`, die der
         * [de.hoshi.core.tools.ToolCall] dieses Turns bereits in seiner `data`
         * trägt (SMART_HOME-Lese-/Schreib-Turns mit aufgelöstem Raum-Ziel,
         * GRANT wie DENY — s. [de.hoshi.core.pipeline.TurnOrchestrator.writeToolTurn]/
         * [de.hoshi.core.pipeline.TurnOrchestrator.toolReadTurn]/`dispatchAgentic`).
         * `null` = kein Tool-Turn ODER ein Tool-Turn OHNE aufgelöste Area (Szene/
         * entity-getargeteter Call/Rückfrage) — nie eine erfundene Area. Füttert
         * `TurnTrace.targetAreaId` (die neue Räume-Nutzungs-Messung statt der
         * bisherigen Nicht-Existenz dieser Datengrundlage).
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val targetAreaId: String? = null,

        /**
         * **Der ECHTE HA-Anzeigename zu [targetAreaId]** (additiv ANS ZEILENENDE,
         * Andi 2026-08-22) — `kuche` ⇒ `Küche`. Aufgelöst über
         * [de.hoshi.core.port.displayNameOrNull]; `null` = keine Area ODER kein
         * vertrauenswürdiger Name (nie ein kapitalisierter Slug, s. dortige KDoc).
         *
         * **Warum BEIDE Felder:** [targetAreaId] bleibt die Matching-Wahrheit (Slug,
         * stabil über Umbenennungen — darauf zählen `AreaUsageReader` & Co.); dieses
         * Feld ist der LESBARE Name für Menschen, die ins Diary schauen. Der Slug
         * wird dadurch nicht ersetzt, nur begleitet.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val targetAreaName: String? = null,
    ) : ChatEvent()

    data class TextDelta(
        val text: String,
        val provider: String = "",
    ) : ChatEvent()

    data class AudioChunk(
        val data: String,   // base64-encoded WAV bytes
        val seq: Int,
    ) : ChatEvent()

    /**
     * Markiert den **Beginn** der TTS-Audio-Wiedergabe — genau EINMAL pro Turn,
     * direkt vor dem ersten [AudioChunk]. Das FE setzt `state==='responding'`
     * zwischen [TtsAudioStart] und [TtsAudioEnd], statt es per Timer-Failsafe zu
     * raten. Turns ohne Client-Audio (macOS-say `ttsHandled=true`, reine
     * Text-Turns) emittieren KEIN Start/End. Backward-compatible: alte Clients
     * ignorieren das unbekannte Event.
     */
    data class TtsAudioStart(
        val provider: String,
        val estimatedMs: Long? = null,
    ) : ChatEvent()

    /**
     * Markiert das **Ende** der TTS-Audio-Wiedergabe — direkt vor dem
     * abschließenden [Done] (auch im Fehler→Done-Pfad), sofern zuvor ein
     * [TtsAudioStart] lief. [actualMs] = Wall-Clock zwischen erstem Chunk und
     * Stream-Ende.
     */
    data class TtsAudioEnd(
        val actualMs: Long,
    ) : ChatEvent()

    data class Step(
        val kind: String,
        val message: String,
    ) : ChatEvent()

    /**
     * **Erkannter Sprecher** (S3, additiv) — surface das Ergebnis der Stimm-Erkennung
     * ans FE, damit es „wer sprach" zeigen kann. Der Voice-Rand
     * ([de.hoshi.web.VoiceInboundController]) stellt dieses Event dem Turn VORAN (vor
     * dem `transcript`-[Step]), NUR wenn `HOSHI_SPEAKER_RECOGNITION_ENABLED` scharf ist
     * — bei OFF wird es gar nicht emittiert (byte-neutral, alte Clients sehen es nie).
     *
     * **Vera-Vertrag:** [recognizedSpeaker] ist NUR bei einem sicheren Treffer über der
     * Konfidenz-Schwelle gesetzt; im Zweifel `null` + [isGuest]==true (nie geraten).
     * Additiv/optional: ein Client, der das `speaker`-Event nicht kennt, ignoriert es.
     */
    data class Speaker(
        val recognizedSpeaker: String?,
        val confidence: Double,
        val isGuest: Boolean,
    ) : ChatEvent()

    data class Done(
        val provider: String = "",
        val totalSentences: Int = 0,
        val ttsHandled: Boolean = false,
        /**
         * **Stage-Zerlegung des Turns** (Perf-Diary, additiv — Muster
         * [Start.grounded]/[Start.segmentReset]): die tief innen gemessenen
         * Latenz-Anteile reisen am terminalen Done an den Rand, wo der
         * TurnDiaryTap sie in die TurnTrace liest. Jede Schicht, die einen
         * Wert WIRKLICH gemessen hat, merged ihn beim Durchfluss additiv
         * hinein (Orchestrator: grounding/brainTtft · AdmissionGate:
         * admissionWait · TtsStage: ttsFirstAudio); nie gemessen ⇒ `null`
         * ⇒ Feld fehlt im JSON ([JsonInclude.Include.NON_NULL]) ⇒ Alt-Clients
         * und Nicht-Brain-Pfade sehen ein byte-identisches Done.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val stageTimings: StageTimings? = null,
        /**
         * **Eskalations-Kosten dieses Turns** (S4 Diary, additiv — Muster
         * [stageTimings]): die ECHTEN Kosten (ca.-Cents) einer bezahlten
         * [de.hoshi.core.port.EscalationResult.Answer] — NUR am Eskalations-Pfad
         * ([de.hoshi.core.pipeline.TurnOrchestrator.escalationTurn]) gesetzt, NACH
         * Abschluss des [de.hoshi.core.port.EscalationPort.lookup]-Calls (darum
         * hier am terminalen Done, NICHT am [Start] — die Kosten sind bei
         * Start-Emission noch nicht bekannt, der Lookup läuft asynchron danach).
         * `null` = kein Eskalations-Turn ODER die Eskalation lieferte keine
         * [de.hoshi.core.port.EscalationResult.Answer] (Unclear/Unavailable/Declined kosten laut
         * [de.hoshi.core.port.EscalationResult] nichts Messbares) ⇒ Feld fehlt im
         * JSON ([JsonInclude.Include.NON_NULL]) ⇒ Alt-Clients sehen ein
         * byte-identisches Done. Füttert `TurnTrace.escalationCostCents`.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val escalationCostCents: Double? = null,
        /**
         * **Turn↔Note-Verknüpfung (H2, additiv — Muster [escalationCostCents]):**
         * der [de.hoshi.core.port.LookupNote.queryHash], MIT DEM
         * [de.hoshi.core.pipeline.TurnOrchestrator.recordLookupNote] die Notiz
         * DIESES Turns geschrieben hat — NUR gesetzt, wenn der Eskalations-Pfad
         * ([de.hoshi.core.pipeline.TurnOrchestrator.escalationTurn]) tatsächlich
         * eine [de.hoshi.core.port.EscalationResult.Answer] lieferte (sonst wurde
         * NIE eine Notiz geschrieben — Nora-Veto). `null` = kein Eskalations-Turn
         * ODER kein Answer-Ausgang ⇒ Feld fehlt im JSON ⇒ Alt-Clients sehen ein
         * byte-identisches Done. Füttert `TurnTrace.escalationQueryHash`.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val escalationQueryHash: String? = null,
        /**
         * **Eskalations-Quelle (H2, additiv — Muster [escalationCostCents]):** die
         * Quellen-Angabe der frisch geschriebenen [de.hoshi.core.port.LookupNote]
         * ([de.hoshi.core.port.EscalationResult.Answer.source]) — erst NACH dem
         * asynchronen Lookup bekannt, darum hier am Done statt am [Start] (Gegenstück
         * zu [Start.escalationSource], das dieselbe Diary-Spalte für den Cache-Hit-
         * Fall aus dem [Start] füttert). `null` = kein Answer-Ausgang. **Ehrlich:**
         * die vom Modell BEHAUPTETE Quelle, unverifiziert. Füttert
         * `TurnTrace.escalationSource`.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val escalationSource: String? = null,
        /**
         * **H3 — Tages-Cap erreicht statt Netzfehler (additiv — Muster
         * [escalationCostCents], NULLABLE statt eines für JEDES Done sichtbaren
         * Booleans — sonst würde JEDER Turn im ganzen Programm, egal ob
         * Eskalation je im Spiel war, ab sofort einen zusätzlichen Wire-Key
         * tragen):** `true` GENAU DANN, wenn der Eskalations-Pfad
         * [de.hoshi.core.port.EscalationResult.CapExhausted] lieferte (kein HTTP-
         * Call, Budget für heute leer) — EHRLICH unterscheidbar von einem echten
         * Netz-/Key-Fehler ([de.hoshi.core.port.EscalationResult.Unavailable]).
         * `null` = kein Eskalations-Turn ODER kein Cap-Fall ⇒ Feld fehlt im JSON
         * ⇒ Alt-Clients sehen ein byte-identisches Done. Füttert
         * `TurnTrace.escalationCapExhausted` (dort weiterhin non-null Boolean,
         * Default `false` — die Diary-Zeile selbst braucht keine Tri-State-Optik).
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val escalationCapExhausted: Boolean? = null,
        /**
         * **Strukturierte Quellen (additiv, Quellen-Struktur-Auftrag 2026-07-21 —
         * Andi-Befund: „…Quelle: Quellen: https://…?utm_source=openai." in der
         * SPRACH-Ausgabe war unbrauchbar):** die echten `url_citation`-Treffer
         * dieses Turns ([de.hoshi.core.port.EscalationResult.Answer.sources]),
         * URLs bereits utm-bereinigt. Der Antwort-TEXT ([TextDelta]) trägt seit
         * diesem Auftrag KEINE angehängte Quellen-/URL-Zeile mehr (TTS spricht
         * damit automatisch quellenfrei) — die Quelle reist NUR NOCH hier,
         * strukturiert, fürs FE-„i"-Icon. `null` = kein Eskalations-Turn ODER
         * keine belegbaren Citations (reiner Modellwissen-Fallback — dann zeigt
         * das FE ehrlich KEIN Icon) ⇒ Feld fehlt im JSON
         * ([JsonInclude.Include.NON_NULL]) ⇒ Alt-Clients (Satellit!) sehen ein
         * byte-identisches Done und ignorieren das unbekannte Feld gefahrlos.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val escalationSources: List<EscalationSourceRef>? = null,
        /**
         * **Execution-claim latch fired** ([de.hoshi.core.pipeline.ExecutionClaimGate]):
         * `true` exactly when this turn ran WITHOUT a tool call, claimed a switching
         * act anyway, and the answer was replaced by the honest ask-back. Known only
         * after the last delta, hence here and not at [Start].
         *
         * Nullable like [escalationCapExhausted] and for the same reason: a non-null
         * Boolean would add a wire key to EVERY Done in the program. `null` = latch
         * did not fire ⇒ key absent ⇒ old clients see a byte-identical Done. Feeds
         * `TurnTrace.claimGateFired` — without it the latch would be invisible.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val claimGateFired: Boolean? = null,
        /**
         * Room-clarify cycle marker (F1-4, additive — pattern [claimGateFired]):
         * "asked" | "resolved" | "expired" | "abandoned" (constants on
         * [de.hoshi.core.pipeline.PendingAreaClarifyPort]). `null` = no clarify
         * involvement ⇒ key absent ⇒ old clients see a byte-identical Done.
         * Feeds `TurnTrace.pendingClarify`.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val pendingClarify: String? = null,
        /**
         * **The tool executor really ran in this turn** (additive AT STRUCT END —
         * LL-2026-08-11-additive-line-end, pattern [claimGateFired]): `true` exactly
         * when [de.hoshi.core.port.ToolPort.execute] was INVOKED for this turn — the
         * deterministic write path after a kernel GRANT
         * ([de.hoshi.core.pipeline.TurnOrchestrator.writeToolTurn]), the gate-free
         * smart-home READ (`toolReadTurn`) and the agentic path after its GRANT
         * (`dispatchAgentic`). A kernel DENY, a room ask and every brain-only turn
         * leave it absent — the executor never ran there.
         *
         * **Boundary (deliberate):** this records the CALL, not its outcome. A
         * [de.hoshi.core.tools.ToolResult.Failed] still counts as `true`; whether the
         * act was VERIFIED is a later, larger slice and must not be read into this field.
         *
         * Nullable like [claimGateFired] and for the same reason: a non-null Boolean
         * would add a wire key to EVERY Done in the program. `null` = no executor ran
         * ⇒ key absent ⇒ old clients see a byte-identical Done. Feeds
         * `TurnTrace.toolCallRan` — the evidence half of FALSE_EXECUTION_CLAIM
         * ("answer claims completion ∧ no tool ran"), which [claimGateFired] alone
         * cannot carry (it only proves the PREVENTED claim on the tool-free path).
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val toolCallRan: Boolean? = null,
        /**
         * **Dieser Turn endet mit einer OFFENEN Rückfrage** (additiv AT STRUCT END —
         * LL-2026-08-11-additive-line-end, Muster [toolCallRan]): `true` genau dann,
         * wenn der Turn eine Frage gestellt hat, deren Antwort der NÄCHSTE Turn
         * einlösen soll — der Online-Nachschau-Deflect („soll ich kurz nachschauen?"),
         * die Orts-Rückfrage und die Raum-Rückfrage.
         *
         * **Warum das ein WIRE-Feld sein MUSS (Andi-Livetest 2026-08-21):** „Wenn
         * Hoshi mich fragt, ob sie online schauen soll, soll sie erstmal zuhören, ob
         * ich okay oder ja sage." Der Satellit weiß heute NICHT, dass eine Frage offen
         * ist, und fällt nach dem Sprechen zurück ins Wake-Word-Warten — der Nutzer
         * muss „Hey Hoshi" sagen, um „ja" zu sagen. Mit diesem Feld kann die Firmware
         * das Mikro OHNE neues Wake-Word wieder öffnen. Es ist damit — anders als alle
         * bisherigen additiven Done-Felder — **kein reines Diary-/Messfeld, sondern
         * das erste, das GERÄTE-VERHALTEN steuern soll** (die Firmware-Hälfte baut die
         * Satelliten-Lane, s. `vault/knowledge/WIRE-PROTOCOL-0.8.md`).
         *
         * Nullable wie [toolCallRan] und aus demselben Grund: ein non-null Boolean
         * würde JEDEM Done im ganzen Programm einen Wire-Key anhängen. `null` = keine
         * offene Rückfrage ⇒ Key fehlt im JSON ⇒ Alt-Clients sehen ein byte-identisches
         * Done. Quelle der Wahrheit ist der [de.hoshi.core.pipeline.PendingTurnArbiter]:
         * gesetzt wird NUR dort, wo wirklich ein Pending-Zustand geschrieben wurde —
         * nie geraten.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val expectsReply: Boolean? = null,
        /**
         * **WELCHE Art Rückfrage offen ist** (additiv, immer gemeinsam mit
         * [expectsReply] gesetzt): `"lookup"` (Online-Nachschau-Consent bzw. die
         * Themen-Rückfrage), `"location"` (Ort für die Wetterfrage), `"area"`
         * (Raum-Klärung). Die drei Werte sind 1:1 die Arten des
         * [de.hoshi.core.pipeline.PendingTurnArbiter] (s. [PendingKind]) — bewusst
         * KEINE zweite, erfundene Taxonomie: was der Arbiter nicht kennt, kann hier
         * nicht stehen.
         *
         * Die Firmware darf den konkreten Wert IGNORIEREN und allein auf
         * [expectsReply] reagieren (Mikro auf); er existiert, damit sie später
         * differenzieren kann (z.B. kürzeres Lausch-Fenster für ein reines
         * „ja/nein" als für eine Raumnennung). `null` ⇔ [expectsReply] `null`.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val pendingKind: String? = null,
    ) : ChatEvent()

    /**
     * **Gemessene Stage-Latenzen eines Turns** in ms — alle optional (`null` =
     * an dieser Naht wurde NICHT gemessen; ein gemessenes 0 ist erlaubt, ein
     * erfundenes 0 nie). `sttMs` reist bewusst NICHT hier: es entsteht am
     * selben Inbound-Rand, an dem der TurnDiaryTap gerufen wird, und geht dort
     * als Parameter direkt in die Trace (kein Event-Umbau nötig).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class StageTimings(
        /** [de.hoshi.core.pipeline.TurnPromptAssembler]: Dauer des echten GroundingPort-Calls. */
        val groundingMs: Long? = null,
        /** [de.hoshi.core.pipeline.TurnOrchestrator]: Brain-Call-Start → erste TextDelta. */
        val brainTtftMs: Long? = null,
        /** [de.hoshi.core.pipeline.TtsStage]: Stage-Start (Subscribe) → erster [AudioChunk]. */
        val ttsFirstAudioMs: Long? = null,
        /** BrainAdmissionGate (web-inbound): Gate-Eintritt → Permit (nur bei aktivem Gate). */
        val admissionWaitMs: Long? = null,
        /**
         * **Antwort-Entropie des Turns** (S1, additiv — bewusst KEINE ms-Latenz,
         * sondern der dritte Ehrlichkeits-Sensor neben FactCoverage/Grounding-leer:
         * „hat der Brain gerade geraten?"): mittlerer Surprisal −mean(logprob)
         * der gesampelten Antwort-Tokens in **nats** (≥ 0; höher = unsicherer).
         * Der [de.hoshi.core.pipeline.TurnOrchestrator] mittelt laufend (Summe+
         * Zähler, kein Speicher-Wachstum) über [de.hoshi.core.dto.LlmDelta.logprob].
         * `null` = nicht gemessen (Flag `HOSHI_ANSWER_ENTROPY_ENABLED` OFF, oder
         * der Brain-Server liefert keine logprobs — heutiges Prod-server_e4b) ⇒
         * Feld fehlt im JSON ⇒ Done byte-identisch. NUR Messwert: kein Verhalten
         * hängt daran (Abstain-Wirkung = S2 nach Kalibrierung an echter Datenlage).
         */
        val answerEntropy: Double? = null,
        /**
         * **STT-Surprisal des Whisper-Transkripts** (Verhör-Detektor S1, additiv —
         * Muster [answerEntropy], aber am EINGANG des Turns statt am Ausgang):
         * mittlerer Token-Surprisal (`-mean(logprob)`, nats, ≥ 0) des rohen
         * Transkripts laut [de.hoshi.core.port.SttSurprisalPort]. `null` = nicht
         * gemessen (Flag `HOSHI_STT_SURPRISAL_ENABLED` OFF, kein Voice-Transkript,
         * Score-Endpoint fehlt/Timeout/kaputtes JSON) ⇒ Feld fehlt im JSON ⇒ Done
         * byte-identisch. NUR Messwert: kein Verhalten hängt am Wert (Rückfrage-
         * Wirkung ist S2 nach Kalibrierung, s. [de.hoshi.core.port.SttSurprisalPort]-KDoc).
         */
        val sttSurprisal: Double? = null,
        /**
         * **Höchster Einzel-Token-Surprisal** des Transkripts (additiv — Muster
         * [sttSurprisal]): ein einzelnes extrem unwahrscheinliches Token kann im
         * Mittelwert untergehen; dieser Ausreißer-Wert ist der schärfere
         * Verhör-Hinweis für die künftige S2-Kalibrierung. `null` = nicht gemessen
         * (dieselben Gründe wie [sttSurprisal]).
         */
        val sttSurprisalMax: Double? = null,
        /**
         * **Brain chat timeout hit** (additive AT LINE END — LL-2026-08-11-additive-line-end).
         * `true` exactly when the brain stream ended in a timeout; `null` = no
         * timeout observed ⇒ key absent ⇒ Done byte-identical for every other turn.
         * Not a latency: it is the reason [brainTtftMs] is null on a wedge turn —
         * without it a 30s timeout looks like a turn that never asked the brain
         * (vault/knowledge/MESSUNG-latenz-diary-2026-08-13.md, KORREKTUR).
         */
        val brainTimeout: Boolean? = null,
    )

    /**
     * Sichtbarer, freundlicher Fehler — never-silent-Vertrag: jeder Voice-Turn
     * endet in einer warmen Antwort ODER in einem sichtbaren Fehler, NIE in
     * stiller Sackgasse.
     *
     * [stage] verortet die Fehlerquelle für das FE (und das Turn-Trace): einer
     * von [Stage.STT] | [Stage.LLM] | [Stage.SIDECAR] | [Stage.TTS].
     */
    data class Error(
        val message: String,
        val stage: String = Stage.LLM,
    ) : ChatEvent()

    /** Stage-Werte für [Error.stage] — bewusst Strings (WS-JSON-Vertrag mit FE). */
    object Stage {
        const val STT = "STT"
        const val LLM = "LLM"
        const val SIDECAR = "SIDECAR"
        const val TTS = "TTS"
    }

    /**
     * Werte für [Done.pendingKind] — bewusst Strings (WS-JSON-Vertrag mit der
     * Firmware, Muster [Stage]). Die drei Konstanten sind die 1:1-Entsprechung der
     * [de.hoshi.core.pipeline.PendingTurnArbiter]-Arten `LOOKUP`/`LOCATION`/`AREA`;
     * eine vierte Art kann es auf dem Draht erst geben, wenn der Arbiter sie kennt.
     */
    object PendingKind {
        /** Online-Nachschau: Consent („soll ich nachschauen?") ODER Themen-Rückfrage. */
        const val LOOKUP = "lookup"

        /** Orts-Rückfrage („für welchen Ort?"). */
        const val LOCATION = "location"

        /** Raum-Klärung („in welchem Raum?"). */
        const val AREA = "area"
    }
}
