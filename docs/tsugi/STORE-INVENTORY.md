# Store-Inventar — Ist-Wahrheit vor Tsugi

> **Summary (EN):** This inventory maps Hoshi's current persistent stores,
> path resolution, feature gates and sensitivity. HA rooms, secrets, models
> and browser localStorage are external; timer restore is incomplete without
> the browser device identity used for ring routing.

Stand: 2026-08-14. Pfade sind **Auflösungsregeln**, keine Behauptung über einen
laufenden Host. Ein expliziter Env-/Property-Pfad schlägt den genannten Default.

## Kernbestand des Haushalts

| Logische ID | Inhalt / Owner | Pfad-Auflösung | Format heute | Konsistenz / Restore-Wahrheit | Sensitivität |
|---|---|---|---|---|---|
| `settings.skills` | aktivierte Fähigkeiten | `HOSHI_SETTINGS_PATH` → `~/.hoshi/skills.json` | JSON-Objekt, keine Schema-Version | atomare Einzeldatei-Schreibweise; kaputtes JSON startet best-effort mit Defaults | intern |
| `settings.language` | Sprache | `HOSHI_LANGUAGE_PATH` → `~/.hoshi/language.json` | JSON, keine Schema-Version | persist-then-commit; Restore heute ungetestet | intern |
| `settings.persona` | Persona | `HOSHI_PERSONA_PATH` → `~/.hoshi/persona.json` | JSON, keine Schema-Version | persist-then-commit; Restore heute ungetestet | intern |
| `settings.tts` | TTS-Engine und Stimmenwahl | `HOSHI_TTS_ENGINE_PATH` → `~/.hoshi/tts-engine.json` | JSON, keine Schema-Version | persist-then-commit; kann Cloud-Wahl enthalten, aber keinen API-Key | intern |
| `settings.brain-model` | gewähltes Brain-Soll | `HOSHI_BRAIN_MODEL_PATH` → `~/.hoshi/brain-model.json` | JSON, keine Schema-Version | Wunschzustand, nicht die Sidecar-Live-Wahrheit | intern |
| `settings.brain-auto-switch` | Auto-Modellwahl | `HOSHI_BRAIN_AUTO_SWITCH_PATH` → `~/.hoshi/brain-auto-switch.json` | JSON, keine Schema-Version | persist-then-commit | intern |
| `settings.lookup-model` | Recherche-Modell | `HOSHI_LOOKUP_MODEL_PATH` → `~/.hoshi/lookup-model.json` | JSON, keine Schema-Version | persist-then-commit | intern |
| `settings.extended-think` | Nachschlage-Modus | `HOSHI_EXTENDED_THINK_PATH` → `~/.hoshi/extended-think.json` | JSON, keine Schema-Version | persist-then-commit | intern |
| `settings.weather-location` | Wetterort und Koordinaten | `HOSHI_WEATHER_LOCATION_PATH` → `~/.hoshi/weather-location.json` | JSON, keine Schema-Version | persist-then-commit | **privat** (Ort) |
| `settings.night-mode` | Nachtmodus pro Satellit | `HOSHI_NIGHT_MODE_STORE_PATH` → beschreibbares `/var/lib/hoshi-0.8/night-mode.json`, sonst `~/.hoshi/night-mode.json` | JSON-Objekt, keine Schema-Version | atomarer Snapshot; enthält Satelliten-IDs | privat |
| `lists.default` | Einkaufs-/Haushaltsliste; Runtime default AUS via `HOSHI_LIST_ENABLED=false` | `HOSHI_LIST_STORE_PATH` → beschreibbares `/var/lib/hoshi-0.8/lists.json`, sonst `~/.hoshi/lists.json` | JSON-Array, keine Schema-Version | atomarer Gesamtsnapshot; nur bei aktivierter Listen-Fähigkeit materiell relevant; Restore heute ungetestet | **privat** |
| `timers.scheduled` | aktive sowie bereits gefeuert/unbestätigte Timer; Runtime default AUS via `HOSHI_TIMER_ENABLED=false`, Dateipersistenz zusätzlich `HOSHI_TIMER_PERSISTENCE_ENABLED=false` | `HOSHI_TIMER_STORE_PATH` → beschreibbares `/var/lib/hoshi-0.8/scheduled-items.json`, sonst `~/.hoshi/scheduled-items.json` | JSON-Objekt, keine Schema-Version | aktiver und fired-Zustand müssen gemeinsam restauriert werden; `origin` verweist auf Browser-`hoshi.deviceId`, die nicht im Backend-Backup liegt | **privat** |
| `memory.entity` | deterministische Fakten pro Sprecher | `HOSHI_MEMORY_DB_PATH` → `~/.hoshi/entity-memory.db` | SQLite, Tabellen ohne Store-Metaversion | eine laufende DB darf nicht per blindem Dateikopieren gesichert werden | **hoch** |
| `memory.episodic` | Episoden und Embeddings pro Sprecher | `HOSHI_MEMORY_EPISODIC_DB_PATH` → `~/.hoshi/episodic-memory.db` | SQLite, Tabellen ohne Store-Metaversion | konsistenter SQLite-Snapshot nötig; Embeddings sind personenbezogen | **hoch** |

### Räume sind heute kein Hoshi-Dateistore

Home Assistant ist die maßgebliche Raum-/Geräte-Wahrheit. Hoshi liest und
schreibt dort über die Registry-API; `HOSHI_HA_LAST_KNOWN_PATH` hält nur den
Cache `ha/last-known-states.json`. Der Cache ist rekonstruierbar und darf nie als
Raum-Backup ausgegeben werden. `HOSHI_HOME_EDIT_AUDIT_PATH` ist ein Auditlog,
nicht der Zustand.

Folge: Tsugi kann in der ersten Version Hoshi-Zustand sichern, aber **keinen
Home-Assistant-Backup ersetzen**. Das Setup muss für Räume auf HA-Backup und
HA-Restore verweisen. Ein späterer serverseitiger Satellit→Raum-Store braucht
vor seiner Einführung eine eigene Manifest-ID und Migration.

## Biometrie — separates Opt-in-Artefakt

| Logische ID | Inhalt | Pfad-Auflösung | Vertrag |
|---|---|---|---|
| `biometrics.speaker-profiles` | Namen, einzelne 512-d-Embeddings und Mittelwerte | `HOSHI_SPEAKER_STORE_PATH` → beschreibbares `/var/lib/hoshi-0.8/speaker-profiles.json`, sonst `~/.hoshi/speaker-profiles.json` | **Nie im Standard-Backup.** Nur mit explizitem `--include-speaker-profiles`, separatem Artefakt, Warnung und mindestens denselben Dateirechten wie die Quelle. Kein Audio. |

`HOSHI_SPEAKER_CAPTURE_DIR` enthält biometrisches Roh-Audio aus einer bewusst
aktivierten Testphase. Es ist **kein Backup-Gut** und wird nie automatisch
eingesammelt.

## Weitere persistente Dateien

Diese Dateien existieren, gehören aber nicht alle in ein normales
Haushalts-Restore:

| Gruppe | Beispiele | Default-Entscheidung |
|---|---|---|
| persönliche Notizen | `andi-faktor.jsonl`, `werkstatt-notizen.jsonl`, `nachgeschlagen.jsonl` | im privaten Voll-Backup enthalten; klar als persönliche Inhalte markieren |
| Turn-Evidenz | `diary/turn-diary-*.jsonl`, Home-Edit-Audit | **PRIVAT**: Diary trägt u. a. `chatId`, Persona, Zielraum und Surprisal und bildet damit ein tägliches Verhaltensprofil; nur optionales Evidenz-Artefakt, nicht für Funktions-Restore erforderlich |
| Betriebszustand | `escalation/spend.json`, `ha/last-known-states.json`, `run/brain.state`, Logs | standardmäßig ausgeschlossen; Cache/Logs sind rekonstruierbar, Kostenfenster darf ein Restore nicht still zurückdrehen |
| Wissens- und Modellartefakte | Wikipedia-DB, HuggingFace-/Ollama-Modelle, Piper-Stimmen | ausgeschlossen und über gepinnte Manifeste/Anleitungen wiederbeschaffbar |
| Secrets | `/etc/hoshi-0.8/secrets.env`, `~/.hoshi/secrets.json`, `~/.hoshi/openai.key`, TLS-Keys | **immer ausgeschlossen**; nach Restore neu bereitstellen bzw. rotieren |
| Browserzustand | Theme, `hoshi.deviceId` und sonstiges `localStorage` | heute nicht vom Backend sicherbar; `hoshi.deviceId` bestimmt über `ScheduledItem.origin` das Timer-Klingel-Routing. Restore muss diese funktionale Lücke separat melden |
| WorkingSession | laufender Gesprächskontext im RAM | absichtlich flüchtig; nie sichern |

## Bestätigte Vertragslücken

1. Kein bestehender Kernstore trägt eine durchgängige, maschinenlesbare
   Schema-Version.
2. Es gibt kein gemeinsames Inventar, das auf einem konkreten Host die effektiv
   aufgelösten Pfade zeigt.
3. Es gibt kein Backup-Manifest, keinen Dry-run und keine automatisierte
   Wiederherstellungsprobe.
4. Viele JSON-Leser degradieren bei kaputten Dateien zu leer/default. Das ist
   boot-robust, aber **kein Restore-Erfolg**; ein Restore muss vorher hart
   validieren.
5. Ein Hoshi-Backup kann Home Assistant, Browserzustand, Modelle und Secrets
   nicht vollständig rekonstruieren. Diese Grenzen müssen im Report stehen.
6. Restaurierte Timer bewahren zwar `origin`, doch ohne die zugehörige
   Browser-`hoshi.deviceId` ist die ursprüngliche Klingel-Lane nicht
   rekonstruierbar. „Timer lesbar" darf daher nie allein als erfolgreicher
   Timer-Restore gelten.
