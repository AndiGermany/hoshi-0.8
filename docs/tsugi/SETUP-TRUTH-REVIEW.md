# SETUP-Wahrheitsprüfung — Korrekturplan

> **Summary (EN):** SETUP must describe the current repository rather than a
> hand-maintained patch version, state that HA and satellite setup are external
> edges, and document the real HA enable/token precedence without claiming
> commands or installation proofs that do not exist yet.

**Status:** reviewbarer Plan. `SETUP.md` bleibt bis zur Annahme unverändert.

## Bestätigte Widersprüche und Lücken

1. `gradle.properties` nennt 0.8.5, `README.md` und `README.de.md` nennen 0.8.3,
   `SETUP.md` beschriftet die Grenzen als „Stand 0.8.2"; zusätzlich nennt
   `README.md` im Abschnitt „Known edges" 0.8.4. Der Setup-Text darf keine
   manuell gepflegte Patch-Version mehr behaupten; er soll auf
   `gradle.properties` als Versionswahrheit zeigen. Die übrigen README-Stellen
   sind eigene Release-Doku-Schulden, nicht heimlich Teil dieser SETUP-Scheibe.
2. `SETUP.md` erklärt Build, Modelle und Sidecars ausführlich, enthält aber
   kein Kapitel zum Verbinden von Home Assistant.
3. `SETUP.md` erwähnt Satellit, Voice PE, Firmware und Runbook **nirgends**. Es
   fehlt damit nicht nur ein Einrichtungsweg, sondern bereits jeder sichtbare
   Einstieg in den Hardware-Sprachweg.
4. Räume sind heute Home-Assistant-Zustand. Die existierende
   `ha/last-known-states.json` ist nur Cache; das steht im Setup nicht.
5. Ein Fresh Clone kann lokal Text/Voice beweisen, aber ein echtes Zuhause
   verlangt externe Konfiguration: HA-URL/Token und optional den separat
   veröffentlichten Satelliten. Diese Grenze muss vor „vollständig" sichtbar
   sein.

## Vorgeschlagene Änderung an `SETUP.md`

### Kopf und Versionswahrheit

- Keine Patch-Version im Abschnitt „Bekannte Grenzen"; stattdessen
  „Grenzen des aktuellen Repo-Stands".
- Direkt im Kurzweg: `gradle.properties` ist die maschinenlesbare Version;
  `bin/hoshi help` zeigt sie im Banner und `CHANGELOG.md` beschreibt Releases.
  Einen nicht existierenden `--version`-Schalter behauptet die Doku nicht.
- „Kompletter Voice-Turn" präzisieren: Browser/Mac ist aus diesem Repo
  beweisbar; Satelliten-Hardware benötigt das separate Firmware-Repo.

### Neues Kapitel „Home Assistant verbinden"

Der Abschnitt soll nur vorhandene Verträge dokumentieren:

1. Die gesamte HA-Tat-/Registry-Decke ist `HOSHI_HA_ENABLED=false` per Default.
   Erst ein bewusster Owner-Flip auf `true` verdrahtet den echten Rand; die
   reine Konfiguration von URL oder Token schaltet HA nicht ein.
2. HA-Basis-URL über `HOSHI_HA_BASE_URL` (Default
   `http://homeassistant.local:8123`).
3. Long-Lived Access Token **nicht** ins Repo. Die echte Präzedenz ist:
   `HOSHI_HA_TOKEN` gewinnt, sonst `~/.hoshi/secrets.json["ha"]`. Beim
   systemd-Deploy liegt die Env-Zufuhr ausschließlich in
   `/etc/hoshi-0.8/secrets.env`, root-only.
4. Erst read-only prüfen: `bin/hoshi doctor` und Registry-/Health-Anzeige. Das
   Setup verspricht noch kein `bin/hoshi ha check`, weil der Befehl nicht
   existiert.
5. Schreibende Hausaktionen bleiben CapabilityKernel/default-deny und werden
   nicht als Installationsprobe verwendet.
6. Räume/Geräte gehören HA. Hoshis Last-known-Datei ist Cache, kein Backup.

### Neues Kapitel „Satellit verbinden"

- Öffentlicher Einstieg:
  [`AndiGermany/hoshi-satellite`](https://github.com/AndiGermany/hoshi-satellite).
- Historische Quellwahrheit im lokalen 0.5-Baum:
  `hoshi-satellite/firmware/RUNBOOK.md` und
  `hoshi-satellite/firmware/esphome/hoshi-voice-pe.yaml`.
- Klarstellen: Wake-Word und Firmware-Flash liegen nicht in diesem Repo;
  Flashen ist ein Hardware-/Owner-Gate.
- Der Satellit verbindet sich mit `/ws/audio`; API-Token/TLS müssen zwischen
  Gerät und Backend übereinstimmen. Kein Tokenwert gehört in die Anleitung.
- Ein Browser-Voice-Turn ist der erste Softwarebeweis; Satellit folgt als
  eigener Hardwarebeweis.

### Neues Kapitel „Sichern und Wiederherstellen"

Bis das Tsugi-Werkzeug existiert, nur die ehrliche Aussage: kein integriertes
Backup/Restore; Stores und Grenzen stehen in `docs/tsugi/`. Keine Anleitung zum
blinden Kopieren laufender SQLite-Dateien.

### Explizit vertagt

Diese Scheibe korrigiert die Setup-Wahrheit, implementiert aber weder
`bin/hoshi ha check` noch den dynamischen Raumkatalog als Default. Auch der
Fremdinstall-Beweis (Release-Artefakt auf einem anderen Apple-Silicon-Mac,
ausgeführt und protokolliert durch eine andere Person) bleibt eine eigene
Bauscheibe. Ein lokaler Fresh-Clone-Test ersetzt ihn nicht.

## Abnahme der Doku-Scheibe

- `SETUP.md` enthält keine handgepflegte Patch-Version als aktuelle
  Statuswahrheit mehr. Abweichende Versionsclaims in READMEs werden separat
  inventarisiert; diese Scheibe behauptet nicht, sie mitzuändern.
- Jeder im Setup genannte Befehl existiert und sein `--help`/Dispatcher-Pfad
  ist prüfbar.
- Fresh-Clone-Weg nennt keine private IP, keinen privaten Pfad und kein Secret.
- HA und Satellit sind als optionale externe Ränder verständlich; lokaler
  Browser-Voice-Turn bleibt ohne sie möglich.
- Keine Zeile behauptet einen Restore, der noch nicht implementiert und
  fachlich gemessen wurde.
