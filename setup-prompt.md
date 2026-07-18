# setup-prompt.md — für deine Coding-KI

> **Für Menschen:** Öffne dieses Repo mit deiner Coding-KI (Claude Code, Cursor,
> Copilot, Codex, Gemini CLI, …) und gib ihr den Block unten als ersten Prompt.
> Sie führt dich durch die Einrichtung — im Dialog, nicht als stumme Checkliste.

---

## Der Prompt (kopieren, an deine KI geben)

```
Du richtest gerade Hoshi auf einer neuen Maschine ein — einem Apple-Silicon-Mac.
Lies zuerst AGENTS.md im Repo-Root, dann diese Datei zu Ende.

Deine Aufgabe ist NICHT, eine Liste stumm abzuarbeiten. Führe mich (den Menschen
vor dir) im Dialog durch die Einrichtung: erkläre kurz, was du gerade prüfst und
warum, zeig mir die echte Ausgabe der Befehle, und frag nach, wenn ein Schritt
eine Entscheidung braucht, die ich treffen muss (siehe „Owner-Gates" in AGENTS.md
— Deploy/Flags/Hörproben sind meine Entscheidung, nicht deine).

1. Preflight — prüfe, was schon da ist, bevor du irgendetwas installierst:
   - `git status` (sauberer Checkout?)
   - `node -v` (Node 20+ fürs Frontend gebraucht)
   - Existiert `SETUP.md` im Repo-Root? Falls ja: folge ihr Schritt für Schritt,
     sie ist die menschenlesbare Kanon-Anleitung. Falls nein (kann bei einem
     frühen Repo-Stand fehlen): nutze README.md Abschnitt „Build & Run" als
     Ist-Stand-Ersatz.
   - JDK 21 musst du NICHT manuell installieren — der Gradle-Wrapper zieht es
     sich beim ersten Build selbst (README.md „Voraussetzungen").

2. Backend bauen — beweisen, nicht behaupten:
   `./gradlew build`
   Zeig mir die letzten ~20 Zeilen. `BUILD SUCCESSFUL` heißt: alle Kotlin-Module
   + ArchUnit-Guards + Unit-Tests grün. Ein Fehlschlag hier ist der billigste Ort
   ihn zu fangen — lies den echten Fehler, rate nicht.

3. Frontend:
   `cd frontend && npm install && npm test && npm run build`
   `npm test` läuft mit vitest — zeig mir die Zusammenfassung (X passed / Dateien).

4. Modell-/Eval-Zustand prüfen (read-only, lädt nichts herunter):
   `bash tools/models-verify.sh`
   `bash tools/eval-baselines-verify.sh`
   Meldet `models-verify` fehlende/unvollständige Modell-Gewichte: normal bei
   einem frischen Setup ohne die Sidecar-Gewichte — sag mir ehrlich, was fehlt,
   statt es zu übergehen oder als „OK" zu beschönigen.

5. `bin/hoshi doctor` — der ehrliche Blick auf den Gesamtzustand:
   `bin/hoshi doctor`
   Das ist READ-ONLY: Backend-Health, Sidecars (Brain/Whisper/Speaker-ID/
   Knowledge/TTS), RAM-Wand. Interpretiere die Ausgabe für mich, statt sie roh
   durchzureichen:
   - Backend/Java-Teil OK, Sidecars DOWN/DEGRADED: **normal** auf einem frischen
     Klon — die ML-Sidecars (Python/MLX) sind heute kein Teil dieses Repos
     (README.md „Python-Sidecars — ehrlicher Stand"). Voice-Turns brauchen sie;
     Backend-Build/-Start funktionieren trotzdem ohne sie.
   - Backend selbst nicht grün: das ist ein echter Fehler, keine erwartete
     Lücke — zeig mir die Zeile, an der es hakt, bevor du irgendetwas „reparierst".

6. Ehrliche Bilanz am Ende, kein Gesamt-Grün erfinden. Sag mir in 3-5 Zeilen: was
   lief grün, was fehlt (z. B. Sidecars, Modell-Gewichte), was als Nächstes meine
   Entscheidung braucht. Nenne mir die default-OFF-Flags, die dir beim Bauen
   aufgefallen sind (`grep -rn '@Value.*HOSHI_' web-inbound/src/main` bzw.
   `tools/systemd/hoshi-0.8-backend.service` als Referenzliste) — sie
   einzuschalten ist meine Entscheidung, nicht deine.

Was du NICHT tust, ohne dass ich es explizit sage: deployen, `/etc/hoshi.env`
oder das systemd-Unit anfassen, ein Flag von OFF auf ON drehen, `git add -A`
verwenden, irgendetwas an ein Gerät flashen, eine Hörprobe als „gut" abnehmen.
Das sind Owner-Gates (siehe AGENTS.md) — deine Aufgabe endet bei bauen, testen,
zeigen, erklären.
```

---

## Warum dieser Prompt so aussieht (für Menschen, die nachlesen)
- **Dialog statt Abarbeitung.** Eine KI, die nur Häkchen setzt, verschleiert genau
  die Stellen, an denen dieses Projekt früher „an den Nähten log" (die `grün≠lebt`-
  Lehre in AGENTS.md) — hier soll sie erklären und echte Ausgaben zeigen, nicht
  nur „erledigt" melden.
- **Beweis-Pflicht.** Jeder Schritt hat einen realen, lauffähigen Befehl mit
  echter Ausgabe dahinter, keine Prosa-Behauptung.
- **Ehrliche Grenzen.** Ein frischer Klon ohne die Sidecar-Checkouts ist ein
  **erwarteter** Teilzustand, kein Fehler — die KI soll ihn benennen, nicht
  überspielen.
- **Owner-Gates zuerst genannt, nicht zuletzt.** Deploy/Flags/Flash/Hörproben
  bleiben Menschen-Entscheidungen — ausdrücklich auch gegenüber der KI selbst,
  nicht nur gegenüber Menschen im Team.
