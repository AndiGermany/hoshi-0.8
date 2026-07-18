package de.hoshi.core.dto

/**
 * Per-Turn-Sprecher-Kontext (Identity-Isolation). Wird MIT dem Request getragen,
 * statt global aus einem Holder gefischt zu werden — so mischt sich bei
 * überlappenden Sessions/Satelliten kein Sprecher.
 */
data class SpeakerContext(
    val speakerId: String = "unknown",
    val displayName: String = "Unbekannt",
    val score: Double = 0.0,
)

/**
 * Das Inbound-Wire-DTO eines Voice-/Chat-Turns (PORT-Einheit aus dem Hoshi-0.5
 * brain-streaming-Ledger).
 *
 * [language] (0.8-Ergänzung): die Sprache, in der der Turn läuft. Fließt von
 * Anfang an durch den Turn ([TurnPrompt]) — STT-Hint, Brain-/Persona-Prompt,
 * TTS-Stimme, Filler/Verweigerungs-Phrasen folgen der Wahl. Default [Language.DEFAULT].
 *
 * [languagePolicy] (0.8, bilinguales AUTO): die WUNSCH-Sprache des Clients —
 * AUTO/DE/EN. Bewusst NULLABLE (Default null): ein alter Client ohne Policy fällt
 * auf [language] zurück (kein Override). Der LanguageResolver am Inbound-Rand löst
 * die Policy VOR Orchestrator UND TTS zu genau einer konkreten [Language] auf — die
 * Pipeline sieht nie AUTO. Jackson deserialisiert "AUTO"/"DE"/"EN"; fehlt das Feld,
 * bleibt es null.
 *
 * [voice] (0.8, Backlog #6 „Stimme wählen"): der per-Turn gewünschte OpenAI-Voice-Name
 * (z.B. "coral", "nova", "alloy"). Bewusst NULLABLE (Default null): ohne gesetztes Feld
 * greift die Boot-Default-Stimme des Adapters ⇒ byte-neutral für alle Alt-Clients.
 * Der Wert wird NIE roh an die Cloud durchgereicht — der
 * [de.hoshi.adapters.tts]-OpenAI-Adapter prüft ihn gegen seine Voice-Whitelist
 * (unbekannt ⇒ Boot-Default). Voxtral ignoriert das Feld ehrlich (eigene
 * Sidecar-Stimmen-Tags, keine OpenAI-Namen).
 *
 * [deviceId] (0.8, Wecker-Ursprung): die Gerät-/Session-Id des Clients, der diesen
 * Turn stellt. Bewusst NULLABLE (Default null): fließt allein in die Wecker-Lane —
 * ein per Voice/Chat gestellter Timer merkt sich hier seinen URSPRUNG
 * ([de.hoshi.core.port.ScheduledItem.origin]), damit das FE beim Feuern später
 * entscheiden kann, WO geklingelt wird. Fehlt das Feld (alte Clients), bleibt
 * `origin=null` ⇒ das FE klingelt überall (sicherer Default) und das bestehende
 * Verhalten ist byte-identisch. Wird NIE an den Brain/die Cloud durchgereicht.
 *
 * [persona] (0.8, Charakter-Steuerung): die per-Turn gewählte Charakter-Stimme
 * (Standard/Kumpel/Knapp/Ruhig). Fließt — exakt wie [language] — von Anfang an
 * durch den Turn ([TurnPrompt.persona]) an die Persona (Prompt-Body +
 * Default-Stimmung/Temperatur). Default [Persona.STANDARD]. Jackson deserialisiert
 * den Wire-String tolerant über [Persona.fromCode] (PascalCase "Kumpel"; unbekannt
 * -> STANDARD). Der [de.hoshi.core.pipeline.PersonaResolver] kollabiert am Inbound-Rand
 * bei `HOSHI_PERSONA_ENABLED=false` ALLE Personas auf STANDARD (byte-neutral).
 */
data class ChatRequest(
    val text: String,
    val chatId: String? = null,
    val speak: Boolean = true,
    val provider: String? = null,
    /** Pro-Request wählbares Brain ("ollama:<model>"). null = Default-Brain. */
    val model: String? = null,
    val speakerContext: SpeakerContext? = null,
    val history: List<ChatMessage> = emptyList(),
    val language: Language = Language.DEFAULT,
    val languagePolicy: LanguagePolicy? = null,
    val persona: Persona = Persona.STANDARD,
    /** Per-Turn-OpenAI-Voice-Wunsch (Whitelist im Adapter, nie roh durchgereicht). null = Boot-Default. */
    val voice: String? = null,
    /** Gerät-/Session-Id des Turn-Ursprungs (Wecker-`origin`); null = alt-Client ⇒ FE klingelt überall. */
    val deviceId: String? = null,
    /**
     * [originSatelliteId] (0.8, PREP-wecker-am-satelliten): die `satelliteId` des `/ws/audio`-
     * Ursprungs, NUR gesetzt vom [de.hoshi.web.AudioWebSocketHandler] (Muster: Nachtmodus-
     * `satelliteId` aus dem `start`-Frame). GETRENNT von [deviceId]: [deviceId] ist die FE-
     * Browser-`deviceId` (Ursprungs-Bimmeln/Eskalation, `localStorage`); [originSatelliteId]
     * ist die ECHTE, im [de.hoshi.core.port.DeviceDownlinkPort] registrierte Geräte-Kennung
     * eines Voice-PE-Satelliten. Chat/FE/alte Clients ⇒ `null` (byte-neutraler Default). Wird
     * NIE an den Brain/die Cloud durchgereicht; fließt allein in
     * [de.hoshi.core.port.ScheduledItem.originSatelliteId] für den späteren Ring-Downlink
     * beim Feuern.
     */
    val originSatelliteId: String? = null,
    /**
     * Eingangs-Rand des Turns ("chat"/"voice"/"ws") — 0.8-additiv (Tagesnote):
     * die Voice-Ränder setzen ihn beim Bauen des Requests, der Chat-Rand lässt
     * ihn null (alt-Client ebenso) ⇒ der Orchestrator behandelt null als "chat".
     * Fließt NUR in lokale Notiz-Nähte ([de.hoshi.core.port.DailyNote.source]),
     * NIE an den Brain/die Cloud.
     */
    val source: String? = null,
)
