# Sidecar-Token-Flip-Drill — **NICHT AUSFÜHREN**

> **Summary (EN):** This is a deliberately non-executable, owner-gated drill
> for sidecar-token rollout and rotation. Today's single-token processes
> require restarts; no deploy dry-run exists, and the shared header name must
> not confuse perimeter authentication with sidecar authentication.

Dieser Drill beschreibt `HOSHI_SIDECAR_TOKEN` / `X-Hoshi-Token`, nicht den
Perimeter-Token des Browsers/Satelliten (`HOSHI_API_TOKEN`). Letzterer berührt
Firmware und Client-Sitzungen und braucht einen eigenen Drill.

**Namenskollision:** Beide Wände verwenden heute den Headernamen
`X-Hoshi-Token`, obwohl ihre Secrets und Vertrauensränder verschieden sind.
Eine 200/401-Messung ohne notierte Route, Richtung und Akteur kann daher die
falsche Wand beweisen. Das Messblatt trennt mindestens `Perimeter inbound`
von `Backend → Sidecar`; ein Tokenwert wird nie zwischen den Rollen
wiederverwendet oder aus einem Erfolg an der anderen Wand abgeleitet.

**Owner-Gate:** Setzen, Restart, Deploy, Rotation und Rollback werden nur in
einem ausdrücklich freigegebenen Tagesfenster ausgeführt. Dieses Dokument ist
kein solches Go.

## Aktueller Gate-Stand

Der Flip bleibt NO-GO, bis alle folgenden Punkte im Repo bewiesen sind:

1. `doctor`, `heal`, `ask`, `ground`, Benches und die kanonischen Python-/Shell-
   Clients senden den Header bei gesetztem Token; leer bleibt der Request exakt
   unverändert. Ein `401` wird nie als WEDGE, DOWN oder Messwert umgedeutet.
2. Der Deploy-Pfad verteilt einen **eigenen** Sidecar-Token reproduzierbar an
   Backend-Clients und Sidecar-Dienste; der Wert erscheint weder in Logs noch
   Prozessargumenten.
3. Server und Clients erzwingen dieselbe strenge Grammatik. Für den Drill ist
   ein 64-stelliges Hex-Secret (`openssl rand -hex 32`) der kompatible Kandidat:
   druckbares ASCII, kein Whitespace.
4. STT und `say` besitzen dieselbe eingecheckte Auth-Matrix wie Brain,
   Knowledge, Speaker und Piper.
5. Diagnose-Details liegen nicht mehr unauthentifiziert auf dem offenen
   Liveness-`/health`.

Fehlt ein Punkt, endet der Drill hier mit `BLOCKED`; manuelles Verteilen ist
kein Ersatz.

## Rollen und Messblatt

- **Owner:** gibt Fenster frei und bestätigt Restart/Deploy/Rollback.
- **Operator:** führt genau eine Zeile nach der anderen aus, schreibt nur
  Status/Zeiten, nie Tokenwerte.
- **Observer:** prüft Client-, Backend- und Sidecar-Rand unabhängig.

Vor Start werden notiert: Commit/Build-ID, aktive Hosts/Sidecars, aktueller
Tokenzustand `OFF|A` (nur Bezeichner), Startzeit, erwarteter Rückweg und die
letzte erfolgreiche `doctor`-/Text-/Voice-Probe.

## Phase 0 — Baseline

1. `bin/hoshi doctor` — alle erwarteten Komponenten und echter Brain-Roundtrip.
2. `bin/hoshi turn` — vollständiger lokaler Textturn.
3. `bin/hoshi voice` oder die freigegebene echte Voice-Probe.
4. Modellwechsel-/Voices-Katalog sowie Knowledge- und Speaker-Probe nur dann,
   wenn sie im aktuellen Profil erwartet werden.
5. Ohne Token muss eine geschützte Sidecar-Produkt-Route im vorbereiteten
   Auth-Check den **erwarteten aktuellen Zustand** zeigen: vor Erstflip offen,
   nach bereits aktivem Token 401. `/health` allein ist kein Beweis.

Jede Abweichung: `STOP`, Ursache klären, keine Rotation beginnen.

## Phase 1 — Erstflip `OFF → A`

Dieser Übergang kann client-first erfolgen: ein noch ungeschützter Sidecar
ignoriert den zusätzlichen Header, während vorbereitete Clients ihn bereits
senden.

1. Token A lokal erzeugen und ausschließlich in den vorgesehenen Secret-Stores
   mit Modus 0600 ablegen. Nicht ausgeben, nicht in Shell-History übernehmen.
2. Weil `bin/hoshi deploy` heute **keinen** Dry-run besitzt, erstellt der
   Operator vor dem Owner-Gate einen read-only geprüften Deploy-Plan aus den
   tatsächlichen Units, Env-Zuführungen und Clientpfaden. `bin/hoshi setup
   --dry-run` ist ein anderer Befehl und zählt ausdrücklich nicht als
   Deploy-Beweis. Der Plan zeigt, welche Backend- und Sidecar-Dienste A
   erhalten würden, ohne den Wert zu zeigen.
3. **Owner bestätigt Deploy.** Zuerst Backend-/Tool-Clients mit A aktivieren,
   Sidecar-Wände bleiben noch offen.
4. `doctor` und `heal` ausführen. Beide müssen gesund bleiben; insbesondere
   darf kein 401 als Wedge erscheinen.
5. Text-, Voice-, Knowledge-, Modellwechsel-, Voices- und Speaker-Probe fahren.
6. **Owner bestätigt Wand-Aktivierung.** Sidecars kontrolliert mit A starten.
7. Auth-Matrix: `/health` lebt; Produktpfad ohne Header = 401; Produktpfad über
   den kanonischen Client = fachlich erfolgreich.
8. Die Proben aus Schritt 5 wiederholen und Zeiten/Status vergleichen.

Erfolg erst, wenn alle erwarteten Clients funktionieren und jede getestete
Produkt-Route ohne Token schließt.

## Phase 2 — Rotation `A → B`

Die heutige Ein-Token-Wand unterstützt keine überlappende Annahme von A und B.
Ein wirklich unterbrechungsfreier Wechsel ist daher **nicht behauptbar**. Die
sichere v1-Variante ist ein kurzes Owner-Wartungsfenster:

1. Baseline mit A wiederholen und Rückweg A sicher verfügbar halten.
2. Token B erzeugen, validieren und in Staging-Secrets vorbereiten.
3. **Owner startet Wartungsfenster:** keine neuen Turns annehmen.
4. Sidecars kontrolliert stoppen, Backend stoppen; fremde Prozesse nie per
   breitem `pkill` treffen.
5. B auf beiden Seiten über den geprüften Deploy-/Secret-Pfad aktivieren.
6. Sidecars starten, dann Backend/Clients. Wartungsfenster bleibt offen.
7. Auth-Matrix und alle Baseline-Proben ausführen.
8. Erst nach vollständigem Grün Wartungsfenster schließen und A aus den
   aktiven Secret-Stores entfernen. A bleibt nur bis Ende des vereinbarten
   Rollback-Fensters in der geschützten Rückweg-Ablage.

Eine spätere Dual-Token-Gnadenperiode wäre ein eigener Security-Vertrag; sie
wird nicht still in diesen Drill hineingedacht.

## Rollback

### Erstflip fehlgeschlagen

1. Neue Sidecar-Wände zuerst wieder deaktivieren bzw. Sidecars mit dem vorherigen
   OFF-Zustand starten.
2. Ohne-Token-Produktprobe muss wieder den alten fachlichen Weg erreichen.
3. Danach A aus Backend-/Tool-Clients entfernen und Backend kontrolliert neu
   starten/deployen.
4. `doctor`, `heal`, Text und Voice erneut messen.

### Rotation fehlgeschlagen

1. Wartungsfenster offen lassen; alle betroffenen Dienste kontrolliert stoppen.
2. Geschützte A-Secrets auf **beiden** Seiten wiederherstellen.
3. Sidecars, dann Backend/Clients starten.
4. Auth-Matrix und Baseline vollständig wiederholen.
5. Erst nach belegtem A-Zustand Wartungsfenster schließen. B wird nicht
   vernichtet, bevor die Fehlerursache dokumentiert ist, bleibt aber inaktiv.

## Erfolgsprotokoll

Das Protokoll enthält ausschließlich:

- Commit/Build-ID und Zeitpunkt;
- Übergang `OFF→A`, `A→B` oder Rollback, nie die Werte;
- Status jedes Gates und Latenz der Randproben;
- 401 ohne Token / fachlicher Erfolg über kanonischen Client;
- geprüfte Wand je Messung (`Perimeter inbound` oder `Backend → Sidecar`),
  ohne Tokenwerte;
- tatsächliche Restart-Reihenfolge;
- Abweichungen, Rückweg und Endzustand.

„Health grün" allein, ein einzelner Unit-Test oder ein nicht authentifizierter
`/health`-Call zählt ausdrücklich nicht als Flip-Beweis.
