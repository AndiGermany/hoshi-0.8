# Mitwirken an Hoshi

Schön, dass du da bist. Hoshi ist ein **lokal-first** Voice-Assistent, dem man vertrauen
kann — und genau das prägt, wie wir hier bauen.

## Die Prinzipien (nicht verhandelbar)
- **grün ≠ lebt.** Ein grüner Test beweist nichts über das laufende System. Jede Behauptung
  wird am echten Stack gemessen (`curl`/Logs/Live-Lauf), bevor sie als „funktioniert" gilt.
- **Ehrlichkeit vor Fassade.** Die UI faked nie einen Zustand. Was nicht live ist, zeigt sich
  als ehrlicher Platzhalter, nicht als grüne Lüge.
- **Wärme ist die Metrik.** Erfolg misst ein Mensch mit Ohr und Auge, nicht eine Test-Suite.
- **Vertrauen by design.** Default-deny Capability-Kernel, biometrische Daten bleiben lokal,
  Cloud ist opt-in + sanitisiert. Siehe [`SECURITY.md`](SECURITY.md).
- **Die 16-GB-Wand.** Hoshi läuft auf *einem* Mac. Genau ein Brain-Modell resident, nie zwei.

## Architektur (kurz)
**Hexagonal (Ports & Adapters).** Der Domänen-Kern (`core-domain`) ist rein — kein Spring,
kein Infra-Wissen; nur DTOs queren die Grenze. STT/TTS/LLM/Knowledge sind austauschbare
Adapter hinter Ports. Das Wiring lebt im `web-inbound`-Modul, nie im Kern. ArchUnit-Guards
erzwingen das (`core-domain` darf nicht auf Spring oder Adapter zeigen).

Module: `core-domain` · `capability-kernel` (der Trust-Kernel) · `adapters-brain` ·
`adapters-tts` · `adapters-stt` · `adapters-memory` · `adapters-knowledge` · `web-inbound` · `frontend`.

Vision, Entscheidungen und Roadmap leben LLM-lesbar im [`vault/`](vault/00-INDEX.md) (Obsidian).

## Sprache
Code-Kommentare sind bewusst **Deutsch** — keine Nachlässigkeit, sondern Projekt-Identität:
Hoshi ist ein deutsch-first-Assistent, gebaut für deutschsprachige Nutzer. Und: das Projekt
wird seit jeher **mit einer Coding-KI geöffnet** — sie übersetzt für jeden, der kein Deutsch
liest, zuverlässiger als jedes für Menschen geschriebene Glossar es könnte (siehe
[`AGENTS.md`](AGENTS.md)). Neuer Code hält sich an diese Konvention.

PRs und Issues sind auf **Englisch oder Deutsch** willkommen — schreib, worin du dich am
klarsten ausdrückst.

## Entwickeln
**Voraussetzungen:** Apple-Silicon-Mac, JDK 21 (der Wrapper provisioniert ihn), Node 20+,
die ML-Sidecars (Brain/Whisper/TTS) laufen lokal. MLX läuft NICHT im Linux-Container — nativer Mac-Toolchain.

```bash
./bin/hoshi verify   # Build alle Module + Tests + LIVE-Brain-Smoke (der grüne Gate)
./bin/hoshi run      # bootet die App lokal + prüft Health + die Auth-Wand
./bin/hoshi services # ehrlicher Sidecar-Health-Report (OK/DEGRADED/DOWN)
./bin/hoshi turn     # ein echter Text-Turn durchs Hexagon
```
Frontend: `cd frontend && npm install && npm run build`.

## Eine Änderung beitragen
1. **Kleine, gescopte Änderungen.** Eine Naht pro PR.
2. **`./bin/hoshi verify` muss grün sein** — Build + Tests + Live-Brain. Kein „grün≠lebt"-Bruch.
3. **Neue Verhaltensweisen brauchen einen Test** *und* einen Live-Beweis (kein reiner Unit-Grün-Anspruch).
4. **Default-OFF für alles Sensible.** Neue Voice-/Persona-/Memory-/Cloud-Pfade kommen flag-gated
   und ausgeschaltet; das Einschalten ist eine bewusste Entscheidung (oft eine Hörprobe).
5. **Sauberes Repo.** Keine Binaries/Modelle in die History (Git-LFS oder `.gitignore`). Nie `git add -A`.

## Lizenz & Herkunft (DCO)
Hoshi steht unter der **Apache License 2.0** (siehe [`LICENSE`](LICENSE) / [`NOTICE`](NOTICE)). Mit
einem Beitrag (PR/Patch) stimmst du zu, dass er unter dieser Lizenz veröffentlicht wird.

Zusätzlich verlangt Hoshi den **Developer Certificate of Origin (DCO)** für jeden Commit — eine
kurze Eigenerklärung, dass du das Recht hast, den Code beizusteuern (eigene Arbeit, oder aus einer
Quelle mit passender Lizenz übernommen). Technisch heißt das: jeder Commit trägt eine
`Signed-off-by`-Zeile.

- **Signieren:** `git commit -s -m "…"` hängt sie automatisch an
  (`Signed-off-by: Dein Name <deine@mail.de>` — Name/Mail kommen aus `git config user.name`
  / `user.email`, also vorher passend setzen).
- **Nachträglich signieren:** letzter Commit `git commit --amend -s`; mehrere Commits
  `git rebase --signoff <base>`.
- **Volltext:** [developercertificate.org](https://developercertificate.org/) (Version 1.1) — kurz:
  „ich habe das Recht, diesen Beitrag unter der Projekt-Lizenz einzureichen."
- Commits ohne `Signed-off-by` werden nicht gemerged.

### SPDX-Header (Policy — noch nicht rückwirkend ausgerollt)
**Neue** Quelldateien tragen als erste Zeile einen SPDX-Kommentar, z. B.:
```
// SPDX-License-Identifier: Apache-2.0
```
(bzw. `#`-Kommentar in Python/Shell/YAML). Das ist aktuell nur die Policy für Neuzugänge — der
Bestand wird nicht in einem Rutsch nachgezogen (eigene, gescopte Naht, falls/wenn sie kommt).

## Sicherheit
Schwachstellen bitte privat melden (siehe [`SECURITY.md`](SECURITY.md)), nicht über öffentliche Issues.

---
*Jede Mitwirkung ist eine Sternschnuppe über Hoshis Himmel — blitz, tu dein Werk, lass den Stern heller zurück.*
