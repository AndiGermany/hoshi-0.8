<!-- German-first project — your AI reads German fine. This file, the code comments,
     and most docs are German by design; see CONTRIBUTING.md#sprache. -->

# AGENTS.md — Hoshi 0.8

## Was ist Hoshi
Hoshi ist ein privater, deutscher, **lokal-first** Voice-Assistent für ein einzelnes
Zuhause (aktuell: ein Apple-Silicon-Mac, 16 GB). STT/Brain(LLM)/TTS laufen on-device;
Cloud ist opt-in, sanitisiert, abschaltbar — nie der Default. Kein für beliebige
Umgebungen poliertes Produkt: Pfade/Ports/Annahmen spiegeln genau diese eine Maschine
(README.md „Ehrlichkeit zuerst"). 0.8 ist eine Neugründung auf sauberem Fundament,
aktiv in Entwicklung.

## Architektur (kurz)
- **Hexagonal (Ports & Adapters).** `core-domain` ist rein — kein Spring, kein
  Infra-Wissen, nur DTOs queren die Grenze. ArchUnit-Guards erzwingen das.
- **Modul-DAG** (`settings.gradle.kts` ist die Wahrheit): `core-domain` ·
  `capability-kernel` (Trust-Kernel, default-deny) · zehn `adapters-*`
  (brain/tts/stt/speaker/knowledge/memory/routing/supervision/ha/radio/escalation —
  austauschbare Engines hinter Ports) · `web-inbound` (einziges Spring-/Wiring-Modul,
  REST + `/ws/audio`) · `frontend` (React/Vite/TS, eigenes Modul außerhalb Gradle).
- **Sidecars via HTTP.** Python/MLX-Prozesse — Brain `:8041`, Whisper-STT `:9001`,
  Speaker-ID/CAM++ `:9002`, Knowledge-Bridge `:8035`, TTS `:8042`. Brain/STT/Speaker
  leben in `sidecars/` (je `bootstrap.sh` + `run.sh`, gepinnt); Knowledge + TTS sind
  noch extern (separater lokaler Checkout, siehe README.md „Python-Sidecars").
  `bin/hoshi up` wählt Repo-Sidecars automatisch sobald gebootstrapped
  (`HOSHI_SIDECARS_FROM_REPO` erzwingt) und überspringt Fehlendes ehrlich
  (Java-Backend baut/läuft trotzdem).
- **Satellit** (ESPHome/HA Voice PE) spricht `/ws/audio` als Wire-Vertrag mit
  `web-inbound`; State ist `sessionId`-keyed (`AudioWebSocketHandler.kt`).

## Ausführbare Wahrheit statt Behauptung
`grün ≠ lebt` — jede Zeile unten ist ein Befehl, den DU selbst laufen lassen kannst,
statt diesem Text zu glauben:

| Behauptung | Prüf-Befehl |
|---|---|
| Backend baut, alle Module + ArchUnit-Guards | `./gradlew build` |
| Modell-Cache vollständig (kein Zombie-Download) | `bash tools/models-verify.sh` |
| Eval-Baseline-Suiten unverändert (kein stiller Drift) | `bash tools/eval-baselines-verify.sh` |
| Frontend-Tests grün | `cd frontend && npm test` |
| Stack-Ist-Zustand, READ-ONLY, echter Roundtrip | `bin/hoshi doctor` |

Alle fünf liefen am 2026-07-18 grün auf der Entwicklungsmaschine. Auf einem frischen
Klon **ohne** die Sidecar-Server zeigt `doctor` ehrlich DOWN/DEGRADED für die
Sidecar-Zeilen — das ist ein korrekter Befund, kein kaputtes Setup. Weitere Beweis-
Befehle (Live-Turn, Voice, Health/Auth-Wand): `bin/hoshi help`.

## Die bindenden Werte (nicht verhandelbar)
- **grün ≠ lebt.** Ein grüner Test beweist nichts über das laufende System —
  Behauptungen werden am echten Stack gemessen.
- **Never-Silent.** Der Turn endet nie stumm; Fehler/Leer-Antworten bekommen eine
  warme, modus-spezifische Phrase statt Stille (siehe `TurnOrchestrator`-Tests).
- **default-deny.** Jede schreibwirkende Aktion läuft durch den `CapabilityKernel`
  (DENY-by-default, least-privilege). Siehe SECURITY.md.
- **flag-gated, default-OFF für alles Sensible.** Neue Voice-/Persona-/Memory-/
  Cloud-Pfade starten ausgeschaltet; Einschalten ist eine bewusste Entscheidung.
  Bei Flag-OFF muss das Verhalten **byte-neutral** zum Vorher bleiben — das wird
  bewiesen (Tests), nicht behauptet.
- **Kommentare = WARUM, ohne Personen.** Code-Kommentare erklären die Mechanik-
  Begründung; Namen/Daten/Chat-Zitate/Herkunft gehören in Commit-Message + Vault,
  nie in den Quellcode.
- **Die 16-GB-Wand.** Ein Brain-Modell resident, nie zwei. `keep_alive` nie `-1`.
- **Sauberes Repo.** Nie `git add -A` (untracked Sidecar-Checkouts sind Gigabyte-
  groß und nicht gitignored).

## Owner-Gates — das entscheidet der Mensch, nicht die KI
Deploy/Prod-Rollout · Flag-Flips in `/etc/hoshi.env` bzw. dem systemd-Unit ·
Reboot / Kill fremder Prozesse · Geräte-Flash (Satellit) · Hörproben-Freigabe
(Lautstärke/Filler/Stimme) · Cloud-/Privacy-Opt-in · SSH auf den Produktions-Host.
Du darfst bauen, testen, vorschlagen, PRs öffnen — ziehen (mergen/deployen/flippen)
tut der Mensch. Im Zweifel: vorschlagen + warten statt ausführen.

## Tiefer
`SETUP.md` (falls vorhanden — Schritt-für-Schritt-Einrichtung) · `setup-prompt.md`
(Dialog-Prompt für deine erste Sitzung hier) · [`CONTRIBUTING.md`](CONTRIBUTING.md)
(Workflow, DCO, Sprache) · [`README.md`](README.md) (Build/Run im Detail) ·
[`SECURITY.md`](SECURITY.md) (Kernel-Invarianten, offene Befunde) · `docs/` ·
[`vault/00-INDEX.md`](vault/00-INDEX.md) (Obsidian, interne Projekt-Historie —
nicht Teil der öffentlichen Wahrheit, aber lesbar).
