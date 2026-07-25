import type { UiStrings } from './types';

// ─────────────────────────────────────────────────────────────────────────────
//  DE — Quelle der Wahrheit. Jeder Wert hier ist BYTE-GLEICH mit dem, was bisher
//  direkt in den Komponenten stand (WEATHER_LOCATION_TEXTS, LOOKUP_MODEL_TEXTS,
//  TTS_ENGINE_TEXTS, STIMME_TEXTS, BRAIN_MODEL_TEXTS, PRIVACY_TEXTS in
//  SettingsPanel.tsx; SPEAKER_TEXTS in SpeakerSection.tsx; NIGHT_MODE_TEXTS in
//  NightModeSection.tsx; LANGUAGE_SETTINGS_TEXTS in LanguageSection.tsx;
//  FIRED_HEADLINE/MISSED_NOUN in FiredToast.tsx) — KEIN Zeichen geändert, nur
//  hierher verschoben. Die Komponenten exportieren ihre alten Konstantennamen
//  jetzt als Referenz auf die jeweilige Teilmenge hier (Tests bleiben unberührt).
//
//  `uiNotice` in `language` ist die einzige NEUE Zeile (Andi-Auftrag 21.07).
// ─────────────────────────────────────────────────────────────────────────────

export const de: UiStrings = {
  locale: 'de-DE',

  topNav: {
    overview: 'Übersicht',
    chat: 'Chat',
    rooms: 'Räume',
    activity: 'Aktivität',
    mainNav: 'Hauptnavigation',
    openSettingsAria: 'Einstellungen öffnen',
    settingsTitle: 'Einstellungen',
    closeSettingsAria: 'Einstellungen schließen',
  },

  idleFace: {
    sectionAria: 'Zuhause',
    greeting: (dayPart) =>
      ({ night: 'Gute Nacht', morning: 'Guten Morgen', day: 'Guten Tag', evening: 'Guten Abend' } as const)[
        dayPart
      ],
    noAlarm: 'Kein Wecker gestellt',
    alarmLine: (clock, remaining) => `Wecker ${clock} · noch ${remaining}`,
    alarmTrustText: 'klingelt auch offline',
    alarmTrustTitle:
      'Der Wecker lebt im lokalen Backend-Store, nicht in einer Cloud — er feuert auch ohne Internet.',
    live: 'live',
    pending: 'kommt',
    heute: {
      name: 'Heute',
      turnOne: 'Turn',
      turnMany: 'Turns',
      outageWord: 'Aussetzer',
      noTurnYet: 'Noch kein Turn',
      noteUnavailable: 'Diary nicht erreichbar — hier steht nichts Erfundenes.',
      noteEmpty: 'Ehrlich leer — nichts erfunden.',
      noteWithData: 'Echte Zahlen aus deinem heutigen Verlauf.',
    },
    geplant: {
      name: 'Geplant',
      nichtsGeplant: 'Nichts geplant',
      note: 'Echte aktive Timer, Wecker und Erinnerungen.',
    },
    wetter: {
      name: 'Wetter',
      loadingNote: 'Wird gerade gelesen.',
      liveNote: (place) => `Echte Messwerte von Open-Meteo für ${place}.`,
      offNote: 'Kommt — ehrlich leer statt erfunden. Wetter ist bei diesem Deploy ausgeschaltet.',
      unreachableNote: 'Wetter grad nicht lesbar — hier steht nichts Erfundenes.',
      settingsAria: 'Wetter-Einstellungen öffnen (Standort & Integrationen)',
      settingsTitle: 'Wetter-Ort einstellen',
    },
    status: {
      online: 'online',
      offline: 'offline',
      checking: 'wird geprüft',
      voiceCloud: 'Stimme: Cloud',
      voiceLocal: 'Stimme: lokal',
    },
  },

  weatherLocation: {
    save: 'Speichern',
    saving: 'speichert…',
    notFound: 'Ort nicht gefunden.',
    locked: 'Wetter ist beim Deploy deaktiviert — der Ort greift erst, wenn es aktiv ist.',
    failed: 'Speichern fehlgeschlagen — bitte nochmal versuchen.',
    loadError: 'Wetter-Ort grad nicht lesbar.',
    seedSuffix: ' (Standard aus dem Deploy)',
    hint: 'Der Ort für Wetter-Fragen — gilt für alle Geräte. Für einen anderen Ort frag einfach „Wetter in …?“.',
    saved: (label: string) => `Ort gespeichert: ${label}.`,
    label: 'Wetter-Ort',
    placeholder: 'z. B. Duisburg',
    loading: 'lädt…',
    current: (label: string) => `Aktuell: ${label}`,
  },

  lookupModel: {
    label: 'Online-Nachschlag',
    hint: 'Welches Modell Hoshi beim schnellen Online-Nachschlag fragt (Extended Think). Betrifft NUR den schnellen Lookup, nicht die explizite Recherche.',
    loadError: 'Lookup-Modell grad nicht lesbar.',
    switching: 'wechselt…',
    unknown: 'Unbekanntes Modell.',
    failed: 'Umschalten fehlgeschlagen — bitte nochmal versuchen.',
    priceSuffix: (cents: number) => `ca. ${cents.toFixed(2)} ct/Nachschlag`,
    loading: 'lädt…',
  },

  ttsEngine: {
    label: 'TTS-Engine',
    hint: 'Welche Engine Hoshis Antworten spricht. Nur Engines, die gerade laufen, sind wählbar — ehrlicher Live-Status, kein Fake-Zustand.',
    loadError: 'TTS-Engine-Status grad nicht lesbar.',
    switching: 'wechselt…',
    unavailable: (hinweis: string) => hinweis || 'gerade nicht verfügbar.',
    unknown: 'Unbekannte Engine.',
    failed: 'Umschalten fehlgeschlagen — bitte nochmal versuchen.',
    active: 'aktiv',
    available: 'verfügbar',
    notStarted: 'nicht gestartet',
    loading: 'lädt…',
    engineLabels: {
      openai: 'OpenAI (Cloud)',
      say: 'macOS say (lokal)',
      piper: 'Piper (lokal)',
      voxtral: 'Voxtral (lokal)',
    },
  },

  stimme: {
    label: 'Stimme',
    loadError: 'Stimmen-Liste grad nicht lesbar.',
    switching: 'wechselt…',
    unknownVoice: 'Unbekannte Stimme für diese Engine.',
    failed: 'Umschalten fehlgeschlagen — bitte nochmal versuchen.',
    cloudBadge: 'geht online',
    cloudLine: 'Hoshis gesprochene Antwort-Stimme (Cloud-TTS).',
    cloudPrivacy: 'Ehrlich: jede Hörprobe und jede Cloud-Stimme geht zu OpenAI.',
    localLine: 'Hoshis gesprochene Antwort-Stimme — läuft lokal.',
    localPrivacy: 'Verlässt das Gerät nicht.',
    loading: 'lädt…',
    sampleAria: (voice: string) => `Hörprobe der Stimme ${voice} abspielen`,
    sampleTitle: 'Hörprobe abspielen',
    noVoicesFor: (engine: string) => `Für ${engine} stehen aktuell keine Stimmen zur Auswahl.`,
    licensePrefix: (license: string) => `Lizenz: ${license}`,
    sampleFailed: 'Hörprobe grad nicht möglich.',
  },

  brainModel: {
    label: 'Brain (LLM)',
    hint: 'Welches Sprachmodell der Brain-Sidecar fährt. Ein Wechsel dauert 60-120 Sekunden (Modell wird neu geladen) — kein optimistisches UI, Hoshi zeigt den echten Ladezustand.',
    loadError: 'Brain-Status grad nicht lesbar.',
    switching: (label: string) => `wechselt zu ${label} — das dauert 60-120 Sekunden…`,
    timeout: 'Der Wechsel dauert ungewöhnlich lange — bitte den Status später prüfen.',
    unknown: 'Unbekanntes Brain-Modell.',
    switchUnavailable: 'Brain-Sidecar kann noch kein Umschalten / nicht erreichbar.',
    failed: 'Umschalten fehlgeschlagen — bitte nochmal versuchen.',
    statusOk: 'läuft',
    statusLoading: 'lädt…',
    statusUnreachable: 'nicht erreichbar',
    loading: 'lädt…',
    statusReading: '(Status wird gelesen…)',
    statusPrefix: 'Status: ',
  },

  privacy: {
    confirm: 'Wirklich? Klick nochmal',
    delete: 'Löschen',
    deleting: 'löscht…',
    notYet: 'Kommt noch — serverseitig noch nicht gebaut.',
    failed: 'Löschen fehlgeschlagen — Daten unverändert; bitte nochmal versuchen.',
    loadError: 'Privatsphäre-Übersicht grad nicht lesbar.',
  },

  speaker: {
    groupTitle: 'Erkannte Sprecher',
    intro: 'Hoshi kann lernen, wer gerade spricht, und euch beim Namen ansprechen. Das läuft lokal auf der Box.',
    consent: 'Jede/r lernt die EIGENE Stimme an — dein Profil gehört dir.',
    empty: 'Noch niemand angelernt. Lern deine Stimme an, dann erkennt Hoshi dich wieder.',
    enrollButton: 'Meine Stimme anlernen',
    delete: 'Löschen',
    confirm: 'Wirklich? Klick nochmal',
    deleting: 'löscht…',
    deleteFailed: 'Löschen fehlgeschlagen — dein Profil ist unverändert. Bitte erneut versuchen.',
    enrolledNote: 'Angelernt — Hoshi erkennt dich jetzt.',
    loadError: 'Sprecher-Liste grad nicht lesbar.',
    dialogTitle: 'Deine Stimme anlernen',
    dialogIntro: 'Sprich drei kurze Sätze, einen nach dem anderen — ganz natürlich, so wie du redest. Dein Profil braucht drei solche Sitzungen an verschiedenen Tagen, dann ist es komplett.',
    nameLabel: 'Dein Name',
    nameHint: 'So nennt Hoshi dich. Nur deine eigene Stimme — der Name steht später in der Liste.',
    nameInvalid: 'Bitte nur Buchstaben, Ziffern, _ oder − (1–64 Zeichen).',
    recordSample: 'aufnehmen',
    recordingHint: 'Ich höre zu — sprich den Satz in Ruhe.',
    finish: 'Satz fertig — speichern',
    cancel: 'Abbrechen',
    saving: 'Ich lerne deine Stimme…',
    sampleSaved: 'gespeichert.',
    nextUp: 'Als Nächstes:',
    partialHint: 'Noch nicht fertig — dein Profil ist erst nach Satz 3 komplett.',
    done: 'Profil komplett — ich erkenne dich jetzt.',
    close: 'Schließen',
    retry: 'Nochmal von vorn',
    errorPartialHint: 'Dein Profil ist so noch nicht komplett — starte am besten nochmal von vorn.',
    abortedNote: 'Anlernen abgebrochen — das unfertige Profil wurde verworfen.',
    insecure: 'Mikro-Aufnahme braucht eine sichere Verbindung (https). Öffne die Seite über https und versuch es erneut.',
    noMic: 'Kein Mikrofon verfügbar. Schließ eines an und öffne die Seite über https.',
    genericFail: 'Anlernen hat nicht geklappt. Es wurde nichts gespeichert — bitte erneut versuchen.',
    loading: 'lädt…',
    // Gruppe 1 (Index 0–2) ist BYTE-GLEICH zum alten `ENROLL_SENTENCES` (Sitzung 1). Gruppe 2
    // (3–5) und Gruppe 3 (6–8) sind neu: derselbe warme, alltagsnahe Ton, keine Zungenbrecher,
    // keine Zahlen/Abkürzungen — für Sitzung 2 und 3 an einem jeweils ANDEREN Tag.
    sentences: [
      'Hallo Hoshi, ich bin’s — ich möchte, dass du meine Stimme ab jetzt sicher wiedererkennst.',
      'Heute war ein ganz normaler Tag, und ich erzähle dir gerade in aller Ruhe ein bisschen davon.',
      'Wenn später etwas Wichtiges ansteht, dann sag mir bitte rechtzeitig und freundlich Bescheid.',
      'Ich mag es, wenn es abends ruhig wird und die Wohnung langsam zur Ruhe kommt.',
      'Am Wochenende koche ich gern etwas Neues und lasse mir dabei richtig viel Zeit.',
      'Wenn Freunde vorbeikommen, freue ich mich immer über ein gutes Gespräch bei Tee.',
      'Manchmal höre ich einfach nur Musik und lasse dabei meine Gedanken schweifen.',
      'Ein Spaziergang an der frischen Luft tut mir eigentlich immer richtig gut.',
      'Am liebsten erzähle ich abends in Ruhe, wie mein Tag eigentlich war.',
    ],
    sessionLabel: (session: number) => `Sitzung ${session} von 3`,
    sessionDoneHint: (session: number) =>
      `Sitzung ${session} von 3 fertig — die nächste bitte an einem ANDEREN TAG, mit anderen Sätzen und wenn möglich einem anderen Mikro.`,
    sessionIncompleteNote:
      'Sitzung abgebrochen — die bisherigen Sätze bleiben erhalten, die Sitzung war unvollständig.',
    statusInProgress: 'in Arbeit',
    statusComplete: 'vollständig',
  },

  nightMode: {
    groupTitle: 'Nachtmodus',
    intro: 'Dämpft Licht/Ton eines Satelliten in der Nacht — pro Gerät einstellbar.',
    loadError: 'Nachtmodus-Geräte grad nicht lesbar.',
    empty: 'Noch kein Satellit verbunden.',
    emptyHint: 'Sobald sich ein Voice-PE-Satellit verbindet, taucht er hier automatisch auf. Du kannst eine Geräte-Id auch schon jetzt manuell hinterlegen — die Einstellung greift automatisch, sobald das Gerät sich verbindet.',
    manualLabel: 'Geräte-Id manuell hinterlegen',
    manualPlaceholder: 'z. B. voice-pe-wohnzimmer',
    manualButton: 'Übernehmen',
    manualNotFound: 'Konnte diese Geräte-Id grad nicht laden — bitte nochmal versuchen.',
    onlineHint: 'verbunden',
    neverSeenHint: 'nicht verbunden · noch nicht gesehen',
    offlineHint: (when: string) => `nicht verbunden · zuletzt gesehen ${when}`,
    master: 'Nachtmodus',
    modeSchedule: 'Zeitplan',
    modeAlways: 'Immer an',
    fromLabel: 'Von',
    toLabel: 'Bis',
    dimLabel: 'Dimmen',
    save: 'Speichern',
    saving: 'speichert…',
    saved: 'Gespeichert.',
    locked: 'Serverseitig noch aus — die Einstellung wird gespeichert, greift aber erst, wenn der Nachtmodus beim Deploy aktiviert ist.',
    invalid: 'Ungültige Eingabe — bitte Werte prüfen.',
    failed: 'Speichern fehlgeschlagen — bitte nochmal versuchen.',
    loading: 'lädt…',
    deviceGroupAria: 'Gerät',
    modeGroupAria: 'Modus',
  },

  language: {
    label: 'Hoshi spricht (Server-Standard)',
    hint: 'Der Standard für Geräte ohne eigene Sprach-Wahl (z. B. den Voice-Satelliten). Deutsch ist vollständig; die anderen Sprachen sind Beta.',
    loadError: 'Sprach-Einstellung grad nicht lesbar.',
    switching: 'wechselt…',
    unknown: 'Unbekannte Sprache.',
    failed: 'Umschalten fehlgeschlagen — bitte nochmal versuchen.',
    betaSuffix: ' (Beta)',
    uiNotice: 'UI-Texte und Gespräch folgen dieser Auswahl — Smart-Home-Befehle bleiben vorerst Deutsch.',
    loading: 'lädt…',
  },

  skills: {
    hint: 'Schaltet einzelne Fähigkeiten zur Laufzeit ein/aus — serverseitig, gilt für alle Geräte.',
    loading: 'lädt…',
    badgeLocked: 'deaktiviert beim Deploy',
    badgeEgress: 'geht online',
    badgeSoon: 'kommt noch',
    future: {
      LISTS: { label: 'Listen', reason: 'Andi-Gabel offen' },
      MUSIC: { label: 'Musik', reason: 'Track startet' },
    },
    loadFailed: 'Skills laden fehlgeschlagen.',
    toggleFailed: 'Umschalten fehlgeschlagen.',
  },

  firedToast: {
    headline: {
      TIMER: 'Timer ist fertig',
      ALARM: 'Wecker klingelt',
      REMINDER: 'Erinnerung',
    },
    missedNoun: {
      TIMER: 'Timer',
      ALARM: 'Wecker',
      REMINDER: 'Erinnerung',
    },
    ackTitle: 'Tippen zum Bestätigen',
    gearAria: 'Wecker-Eskalation-Einstellungen öffnen (Fähigkeiten)',
    gearTitle: 'Eskalation ändern',
    missed: (noun, label, time) =>
      `${label ? `${noun} „${label}"` : noun} war um ${time} fällig — hab dich nicht erreicht`,
  },

  activity: {
    stateOnline: 'online', stateOffline: 'offline', stateChecking: 'wird geprüft', noData: 'keine Daten',
    noStageData: 'keine Stage-Daten (vor 06.07.)', noStageValues: 'keine Stage-Werte in diesem Turn gemessen',
    stageBreakdown: 'Stage-Zerlegung des Turns', rest: 'sonstiges', total: 'gesamt', title: 'Aktivität',
    lede: 'Was zuletzt im Zuhause passiert ist — der Turn-Feed aus Hoshis Nutzungs-Diary und der Health-Verlauf, beides echt.',
    stageLatencyTitle: 'Stage-Latenzen heute', stageLatencyHint: 'p50/p95 je Pipeline-Stage, aus denselben Diary-Zeilen aggregiert (nur heutige Turns; Turns ohne Messwert fallen aus der jeweiligen Stage heraus). „—" heißt ehrlich: heute keine Messwerte.',
    diaryUnavailable: 'Diary nicht erreichbar — keine Zusammenfassung ohne Daten.', turnFeedTitle: 'Turn-Feed', refresh: 'Aktualisieren',
    turnFeedHint: 'Jede Zeile ist ein realer Turn aus deinem Tagesbuch (heute + gestern, neueste zuerst). Geladen beim Öffnen und per „Aktualisieren" — kein Dauerpoll.',
    diaryUnavailableRetry: 'Diary nicht erreichbar — hier steht nichts Erfundenes. „Aktualisieren" versucht es erneut.',
    diaryEmpty: 'Noch kein Turn im Diary (heute + gestern) — leer ist ehrlich leer.', deflected: 'deflected',
    deflectedTitle: 'Ehrliches „wusste ich nicht“ statt einer erfundenen Antwort', error: 'Fehler', errorStage: (stage) => `Fehler-Stage: ${stage}`,
    privacy: 'Privacy by Design: das Diary trägt bewusst keine Gesprächs-Inhalte — nur Zeitpunkt, Kategorie, Persona und Messwerte. Darum steht hier auch kein Text der Turns. Jede Zeile lässt sich aufklappen und zeigt die Stage-Zerlegung (stt → grounding → brain → tts, Rest = sonstiges).',
    healthTitle: 'Health-Verlauf', healthHint: 'Jede Zeile ist eine reale Beobachtung des Verbindungsstatus. Aufgezeichnet werden Zustands­wechsel, neueste zuerst.',
    noObservation: 'Noch keine Beobachtung — die erste Health-Antwort steht aus. (Kein erfundener Verlauf.)', backendState: (state) => `Backend ${state}`,
  },

  rooms: {
    sketchRoom: 'Raum', sketchAria: 'Skizze: Hoshi im Zentrum, noch leere Raum-Platzhalter ringsum', pickerAria: (name) => `Raum für ${name} wählen`,
    assigning: 'wird zugeordnet…', chooseRoom: 'Raum wählen…', deviceCount: (count) => `${count} Gerät${count === 1 ? '' : 'e'}`,
    roomEmpty: 'Noch keine Geräte in diesem Raum.', allAssigned: 'Aktuell hat jedes gemeldete Gerät einen Raum in Home Assistant.',
    unassignedEditable: 'Diese Geräte haben in Home Assistant noch keinen Raum. Wähle rechts einen Raum — gespeichert wird direkt in Home Assistant, dort jederzeit sichtbar und umkehrbar.',
    unassignedReadOnly: 'Diese Geräte haben in Home Assistant noch keinen Raum. Zuordnen geht bislang nur direkt in Home Assistant — Hoshi zeigt die Lücke hier nur ehrlich an.',
    unassigned: 'Nicht zugeordnet', pendingTitle: 'Räume & Geräte', notWired: 'nicht verdrahtet', unreachable: 'nicht erreichbar',
    unreachableNote: 'Home Assistant ist gerade nicht erreichbar — hier steht nichts Erfundenes. Es versucht es automatisch erneut.',
    title: 'Räume', ledeEditable: 'Das Zuhause, räumlich gedacht — direkt aus Home Assistant gelesen. Nicht zugeordnete Geräte kannst du hier einem Raum geben; gespeichert wird in Home Assistant, dort jederzeit sichtbar und umkehrbar.',
    ledeReadOnly: 'Das Zuhause, räumlich gedacht — direkt aus Home Assistant gelesen. HA bleibt die eine Wahrheit; Räume ändern geht bislang nur dort (Zuordnen im Hoshi-UI kommt in einer späteren Scheibe).',
    loading: 'Wird gerade gelesen.', offNote: 'Räume kommen, sobald Home Assistant verdrahtet ist. Das 0.8-Backend exponiert heute noch keine Geräte- oder Raum-Registry — darum steht hier bewusst nichts Erfundenes.',
    idea: 'Die Idee', ideaHint: 'Eine ruhige Skizze der Verbindung — kein echter Bestand. Die Platzhalter sind gestrichelt und leer; echte Räume erscheinen erst, wenn die Registry steht.',
    assignFailed: 'Zuordnung fehlgeschlagen — bitte später erneut versuchen.',
  },

  overview: {
    heroUpTitle: 'Hoshi ist online', heroUpSub: 'Verbindung steht.', heroDownTitle: 'Hoshi ist offline', heroDownSub: 'Verbindung steht gerade nicht.',
    heroUnknownTitle: 'Status wird geprüft …', heroUnknownSub: 'erste Health-Antwort steht noch aus',
    sidecarHealthNote: 'Supervisor-/Sidecar-Status ist noch nicht über die API exponiert. Kommt, sobald das Backend ihn liefert.',
    voiceStatsNote: 'Voice/TTS-Telemetrie (Latenz, Sprecher) ist noch nicht angebunden. Kommt später.',
    devicesNote: 'Geräte-/Satelliten-Registry ist noch nicht verdrahtet. Kommt, sobald die Route steht.', backend: 'Backend', sidecarHealth: 'Sidecar-Health', voiceStats: 'Sprach-Stats', devices: 'Geräte', live: 'live', notWired: 'nicht verdrahtet',
    backendNote: 'Adresse, mit der sich diese Oberfläche verbindet.', chatTurn: 'Chat-Turn', liveStreaming: 'Live-Streaming', chatTurnNote: 'Echte Antwort in Echtzeit, kein Mock.',
    authToken: 'Auth-Token', set: 'gesetzt', missing: 'fehlt', authSetNote: 'Dein Gerät ist angemeldet — geschützte Bereiche sind freigeschaltet.', authMissingNote: 'Nicht angemeldet — geschützte Bereiche bleiben gesperrt.',
    title: 'Übersicht', lede: 'Status-first: ein ehrlicher Blick aufs Zuhause. Was läuft, kommt LIVE aus den echten Quellen — was noch fehlt, ist klar als „noch nicht verdrahtet" markiert, nie grün gefärbt.',
    lastChecked: (time) => `zuletzt geprüft ${time}`, liveWired: 'Live verdrahtet', notWiredTitle: 'Noch nicht verdrahtet', notWiredHint: 'Diese Kacheln zeigen bewusst keinen Zustand — das 0.8-Backend exponiert die Daten noch nicht. Kein erfundenes Grün.',
  },

  chat: {
    suggestions: ['Licht im Wohnzimmer aus', 'Wie wird das Wetter morgen?', 'Wie warm ist es im Wohnzimmer?'], waveTap: 'Antippen stoppt Hoshi',
    micIdle: 'Gedrückt halten und sprechen (oder antippen zum Umschalten)', micListening: 'Loslassen zum Senden — oder Esc zum Verwerfen', micTranscribing: 'Verstehe…', micResponding: 'Mikro/Esc bricht die Antwort ab (Barge-in)',
    ttsOn: 'Sprich-Modus an — Hoshi spricht die Antwort', ttsOff: 'Sprich-Modus aus — nur Text', greeting: (dayPart) => `${({ night: 'Gute Nacht', morning: 'Guten Morgen', day: 'Guten Tag', evening: 'Guten Abend' } as const)[dayPart]} — was darf ich tun?`,
    speakerSettingsAria: 'Sprecher-Einstellungen öffnen (Gedächtnis & Privatsphäre)', manageSpeakers: 'Sprecher verwalten', recordingUnderstood: 'Deine Aufnahme wird verstanden',
    sourcesTitle: 'Quellen dieser Antwort anzeigen', sources: 'Quellen', micAria: 'Mikro — gedrückt halten und sprechen', discardRecording: 'Aufnahme verwerfen', discard: 'Verwerfen (Esc)',
    speaking: 'spricht…', processingRecording: 'Verarbeite deine Aufnahme', transcribing: 'verstehe…', thinking: 'denkt nach…', placeholder: 'Nachricht an Hoshi…',
    send: 'Senden', sendTitle: 'Senden (Enter)',
  },

  voiceChat: {
    slowTurn: 'dauert grad länger als sonst — ich bin dran.', connection: 'Verbindung', errorStage: (stage) => `Fehler · ${stage}`,
    noAudioHeard: 'Ich habe nichts gehört — halt das Mikro gedrückt und sprich.',
  },
  turnAnatomy: {
    heard: 'gehört', understood: 'verstanden', route: 'Weg gewählt',
    answering: 'antwortet', speaking: 'spricht',
    recognized: (who) => `erkannt: ${who}`,
    guest: 'Gast', rowLabel: 'Was dieser Turn wirklich getan hat',
    local: 'lokal', cloudSuffix: ' · ging online',
    localTitle: 'Diese Antwort blieb auf dem Gerät', cloudTitle: 'Diese Antwort kam über einen Cloud-Provider',
    grounded: 'Wissen gedeckt', groundedTitle: 'Die Antwort war durch geladenes Wissen gedeckt (FactCoverage-Gate)',
  },
  voiceOrb: {
    sectionAria: 'Sprich mit Hoshi',
    idleHint: 'Tippen zum Sprechen',
    idleTapLabel: 'Tippen und sprechen',
    listening: (elapsed) => `hört zu… ${elapsed}`,
    listeningTapLabel: 'Nochmal tippen zum Senden — oder Esc zum Verwerfen',
    speakingTapLabel: 'Tippen bricht Hoshis Antwort ab',
  },

  settings: {
    categories: {
      darstellung: 'Darstellung',
      'sprache-stimme': 'Sprache & Stimme',
      persoenlichkeit: 'Persönlichkeit',
      'modell-leistung': 'Modell & Leistung',
      faehigkeiten: 'Fähigkeiten',
      'gedaechtnis-privatsphaere': 'Gedächtnis & Privatsphäre',
      'standort-integrationen': 'Standort & Integrationen',
    },
    categoryNavAria: 'Einstellungs-Kategorien',
    themeLabel: 'Farbthema',
    themeGroupAria: 'Farbthema',
    themes: {
      aoi: { label: 'Aoi', hint: 'Morgenblau auf Tinte (青) — der Standard' },
      yoru: { label: 'Yoru', hint: 'Warmes Laternenlicht (夜)' },
      asa: { label: 'Asa', hint: 'Heller Tag auf Washi-Papier (朝)' },
      natsunohi: {
        label: 'Natsu no Hi',
        hint: 'Sommertag in Ramune-Blau und warmem Washi (夏の日)',
      },
      kasumi: { label: 'Kasumi', hint: 'Kühle Nebel-Nacht in Jade (霞)' },
      nagareboshi: {
        label: 'Nagareboshi',
        hint: 'Stille Neumond-Nacht, dunkler Samt (流れ星)',
      },
      yoake: {
        label: 'Yoake',
        hint: 'Morgendämmerung zwischen Indigo und Koralle (夜明け)',
      },
      sora: {
        // Der Zusatz „(automatisch — folgt dem Tag)" ist in die Gruppen-
        // Überschrift gewandert (themeGroups.automatik) — er stand sonst zweimal
        // untereinander. Der Hinweis nennt jetzt alle FÜNF Stationen: seit 0.8.2
        // ist Nagareboshi Teil der Rotation (die tiefste Nacht, 02–06 Uhr).
        label: 'Sora',
        hint: 'Nagareboshi → Asa → Aoi → Kasumi → Yoru, nach Uhrzeit dieses Geräts',
      },
    },
    themeGroups: {
      automatik: {
        title: 'Folgt dem Tag',
        note: 'Keine Farbe, sondern eine Regel: Hoshi wechselt das Thema mit der Uhrzeit dieses Geräts.',
      },
      tageszeiten: {
        title: 'Tageszeiten',
        note: 'Diese fünf wechseln normalerweise von selbst. Wählst du eines, bleibt es stehen.',
      },
      stimmung: {
        title: 'Eigene Stimmung',
        note: 'Bilder statt Tageszeiten — diese beiden folgen der Uhr bewusst nicht.',
      },
    },
    themeGlosses: {
      aoi: 'Blau',
      yoru: 'Nacht',
      asa: 'Morgen',
      natsunohi: 'Sommertag',
      kasumi: 'Dunst',
      nagareboshi: 'Sternschnuppe',
      yoake: 'Morgengrauen',
      sora: 'Himmel',
    },
    themeGlossSuffix: (gloss) => ` · ${gloss}`,
    themeSoraNow: (themeName) => `folgt dem Tag · jetzt ${themeName}`,
    themeArcSeparator: ' › ',
    themeArcAria: 'Tagesbogen von Sora',
    themePinnedNote: (themeName) => `${themeName} steht gerade fest — die Automatik pausiert.`,
    themeOptionAria: (groupTitle, themeName) => `${groupTitle}: ${themeName}`,
    languageLabel: 'Sprache',
    languages: {
      auto: 'Automatisch (Deutsch / Englisch)',
      de: 'Deutsch',
      en: 'English',
    },
    languageHint: 'Steuert die Chat-Sprache und die Spracherkennung (STT).',
    languageAutoHint: 'Automatisch erkennt pro Nachricht Deutsch oder Englisch und antwortet passend.',
    personaLabel: 'Persönlichkeit',
    personas: {
      Standard: {
        label: 'Standard',
        description: 'Warm, locker, kumpelhaft — Hoshis Grundton.',
        sample: "Morgen wird's mild, so um die 18 Grad — abends könnte ein kleiner Schauer kommen.",
      },
      Kumpel: {
        label: 'Kumpel',
        description: 'Flapsig und spielfreudig: duzt, witzelt, viel Energie.',
        sample: 'Morgen? Locker 18 Grad, bisschen Regen dabei — Jacke einpacken, fertig!',
      },
      Knapp: {
        label: 'Knapp',
        description: 'Wortkarg und sachlich: nur das Nötige, kein Geplänkel.',
        sample: 'Morgen: 18 Grad, leichter Regen.',
      },
      Ruhig: {
        label: 'Ruhig',
        description: 'Sanft und entschleunigt: leise, gelassen, gedämpft.',
        sample: 'Morgen wird es mild, um die 18 Grad — gegen Abend zieht wohl etwas Regen auf.',
      },
    },
    personaSample: (sample) => `So klinge ich: „${sample}“`,
    escalationLabel: 'Wecker-Eskalation',
    escalationUnit: 'Sekunden',
    escalationHint: (seconds) =>
      `Ein Wecker bimmelt erst am Gerät, wo du ihn gestellt hast — nach ${seconds} Sekunden auf allen. So weckt dich dein Wecker zuerst leise dort, wo du bist, und wird erst laut überall, wenn niemand reagiert.`,
    skillsTitle: 'Skills',
    privacyTitle: 'Privatsphäre',
    privacyIntro:
      'Ehrlicher Ist-Stand, direkt vom Server gelesen — das Schloss bleibt auf der Box, die Wolke geht online.',
    privacyLoading: 'lädt…',
    privacyVoiceLine: (engine) => `Stimme (TTS): ${engine}`,
    privacyVoiceCloud: 'Antworttext geht für die Sprachausgabe zu OpenAI.',
    privacyVoiceLocal: 'läuft lokal — kein Text verlässt die Box.',
    privacySanitizeOn: 'Cloud-Maskierung: aktiv',
    privacySanitizeOff: 'Cloud-Maskierung: aus',
    privacySanitizeOnDetail:
      'Vor jedem Cloud-Call maskiert: Tokens, URLs, IP-Adressen, UUIDs, Smart-Home-IDs. Namen und normaler Inhalt bleiben.',
    privacySanitizeOffDetail:
      'Der Antworttext geht unmaskiert in die Cloud. Aktiviert maskiert Hoshi Tokens, URLs, IP-Adressen, UUIDs und Smart-Home-IDs — Namen bleiben.',
    privacyMemoryLine: 'Gedächtnis (Fakten über dich)',
    privacyEpisodicLine: 'Episoden-Gedächtnis (frühere Gespräche)',
    privacyDiaryLine: 'Nutzungs-Diary (Technik-Protokoll)',
    privacyTargetLabels: {
      memory: 'Gedächtnis',
      episodic: 'Episoden-Gedächtnis',
      diary: 'Nutzungs-Diary',
    },
    privacyDeleteAria: (label) => `${label} löschen`,
    privacyLocalFile: (detail) => `lokale Datei · ${detail}`,
    privacyStoreEmpty: 'noch nichts gespeichert',
    privacyStoreUnreadable: 'Anzahl grad nicht lesbar',
    privacyStoreEntries: (entries) => `${entries} Einträge`,
    privacyStoreDisabled: (count) => `${count} — Aufzeichnung ist aus, alte Einträge liegen noch da`,
    privacyDiaryDetail: (days) =>
      `${days === 1 ? '1 Tages-Datei' : `${days} Tages-Dateien`} · enthält keine Gesprächs-Inhalte, nur Timing/Kategorie`,
    privacyDeleted: (deleted, target) => {
      const [one, many] = (
        {
          memory: ['Eintrag', 'Einträge'],
          episodic: ['Eintrag', 'Einträge'],
          diary: ['Tages-Datei', 'Tages-Dateien'],
        } as const
      )[target];
      return `Gelöscht: ${deleted} ${deleted === 1 ? one : many}.`;
    },
  },

  scheduled: {
    kindWord: {
      TIMER: { one: 'Timer', many: 'Timer' },
      ALARM: { one: 'Wecker', many: 'Wecker' },
      REMINDER: { one: 'Erinnerung', many: 'Erinnerungen' },
    },
    remainingUnderMinute: 'unter 1 min',
    remainingMinutes: (minutes) => `${minutes} min`,
    remainingHours: (hours) => `${hours} h`,
    remainingHoursMinutes: (hours, minutes) => `${hours} h ${minutes} min`,
    lineOne: (word, remaining) => `${word} · noch ${remaining}`,
    lineMany: (count, word, remaining) => `${count} ${word} · nächster in ${remaining}`,
    atClock: (clock) => `um ${clock}`,
    inRemaining: (remaining) => `noch ${remaining}`,
    panelAria: 'Aktive Timer und Wecker',
    collapse: 'Zuklappen',
    expand: 'Aufklappen — verwalten',
    fallbackSummary: 'Aktive Timer & Wecker',
    empty: 'nichts aktiv',
    deleteAria: (word, label) => `${word}${label ? ` „${label}"` : ''} löschen`,
    deleteTitle: 'Löschen',
    deleteAll: 'alle löschen',
  },

  ops: {
    toneWarn: 'Ops · Achtung',
    toneCritical: 'Ops · kritisch',
    ramCritical: 'RAM kritisch',
    ramWarn: 'RAM-Druck',
    title: (overall, level) => `Ops: Gesamt ${overall} · RAM ${level}`,
    titleDetail: (detail) => ` — ${detail}`,
    voiceCloud: 'Stimme kommt gerade aus der Cloud (OpenAI)',
    voiceLocal: (engine) => `Stimme (${engine}): läuft lokal — verlässt das Gerät nicht.`,
    allLocal:
      'Alles lokal — deine Stimme verlässt das Gerät nicht. Online-Recherche nur nach deiner Freigabe.',
  },

  apiErrors: {
    unauthorized: '401 — Token fehlt oder ist ungültig (Auth-Wand). Setze VITE_TOKEN.',
    unsupportedAudioType: '415 — Backend lehnt den Audio-Content-Type ab (/api/v1/voice).',
    httpStatus: (status) => `Backend antwortete HTTP ${status}`,
  },

  speakerChip: {
    guest: 'Gast',
    guestTitle: 'Nicht sicher erkannt — lieber Gast als die falsche Person.',
    recognized: (name) => `Erkannt als ${name}`,
    recognizedWithConfidence: (name, percent) => `Erkannt als ${name} · Stimm-Ähnlichkeit ${percent}`,
    percent: (value) => `${value} %`,
  },

  stageSparkline: {
    ms: (ms) => `${ms} ms`,
    outlierSuffix: ' (Ausreißer)',
    errorSuffix: ' · Fehler',
    ariaHead: (label, count) => `${label} heute: ${count} Messwert${count === 1 ? '' : 'e'}`,
    ariaMedian: (ms) => `Median ${ms} ms`,
    ariaP95: (ms) => `p95 ${ms} ms`,
  },
};
