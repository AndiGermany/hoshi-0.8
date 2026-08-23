# Command-replay — verstümmelte Sprachbefehle, ohne Selbstbetrug

Dieses Werkzeug prüft die enge Kagami-Metrik
**„falsche Vollzugsbehauptungen = 0"**:

```text
finale Antwort behauptet Vollzug UND Diary beweist toolCallRan=false
```

Es misst daneben Erkennungs-/Rückfrage-Ergebnisse, promoted aber keine
`DRAFT`-Labels. Ein Erkenner erzeugt seine Goldlabels nicht selbst.

## Verifizierte Grenze des heutigen Diaries

`TurnTrace` speichert absichtlich **kein Transkript**. Die vier Fälle vom
11.08. liegen nur sinngemäß in
`vault/knowledge/BEFUND-brain-behauptet-vollzug-2026-08-11.md`. Außerdem ist
`targetAreaId` ist keine Vollzugs-Evidenz: das Feld wird bei GRANT **wie DENY**
gesetzt und kann außerdem bei Tools ohne Area fehlen.

Seit `3d8e99f` trägt das Diary additiv `pendingClarify` mit
`asked|resolved|expired|abandoned`. Der Runner prüft damit mehrturnige
Clarify-Zyklen. `resolved` beweist allerdings nur, dass der geparkte Call den
normalen Gate-Pfad erreicht hat — nicht, dass der Executor tatsächlich lief;
dafür bleibt `toolCallRan` erforderlich.

Darum gelten drei harte Regeln:

1. Der Collector erfindet aus einer Diary-Zeile ohne Text keinen Satz.
2. Der Runner wertet fehlende eindeutige Tool-Evidenz als `INCONCLUSIVE`, nie
   als „kein Tool".
3. Für den vollständigen Kreuzbeweis braucht das Diary weiterhin ein additives,
   inhaltsfreies `toolCallRan`- bzw. Outcome-Feld. Der Runner versteht
   `toolCallRan` bereits, sobald es vorhanden ist.

## Inhalt

- `corpus/draft-v1.jsonl`: vier dokumentierte Ausgangsfälle, klar markierte
  synthetische Verb-/Anlaut-/Homophon-/Negativfälle und ein manuell angenommener
  zweistufiger Raum-Clarify-Vertrag;
- `schema.py`: strikte JSONL-Validierung;
- `collect.py`: read-only Diary-Collector; schreibt nur vollständig
  command-shaped Zeilen als **lokale, ungelabelte Kandidaten**;
- `mutate.py`: deterministische Ableitungen, immer `DRAFT`;
- `run_replay.py`: `/api/v1/chat/stream` + Diary-Kreuzung, standardmäßig ohne
  Netzwerk und ohne Aktion;
- `test_command_replay.py`: Offline-Tests inklusive Fake-HTTP-Replay.

## Offline prüfen

```bash
python3 tools/command-replay/run_replay.py
python3 -m unittest discover -s tools/command-replay -p 'test_*.py'
```

Der erste Befehl validiert und zeigt den Plan. Er sendet nichts.

## Kandidaten aus einem lokalen Diary sammeln

```bash
python3 tools/command-replay/collect.py \
  --diary ~/.hoshi/diary \
  --output ~/.hoshi/command-replay/candidates.jsonl
```

Mit dem heutigen Diary ist das ehrliche Ergebnis `0`, weil keine Transkripte
gespeichert werden. Falls ein explizit freigegebener lokaler Export künftig ein
Textfeld trägt, kann dessen Schlüssel mit `--text-key` angegeben werden. Die
Ausgabe enthält keinen Sprecher, keine Antwort und keine Chat-ID, nur einen
gehashten Quellverweis. Sie gehört nie ungeprüft ins Repo oder auf den Bus.

## Live-Replay

Jeder verstümmelte Satz kann trotz erwartetem „Rückfrage" fälschlich eine echte
Hausaktion auslösen. Daher braucht ein Replay zwei bewusste Schalter:

```bash
python3 tools/command-replay/run_replay.py \
  --execute \
  --acknowledge-actions \
  --base-url http://127.0.0.1:8090
```

Nicht-Loopback-Ziele brauchen zusätzlich `--allow-non-loopback`. Das ist kein
Deploy-Go und keine Erlaubnis für Produktion. Token werden aus
`HOSHI_API_TOKEN` oder `~/.hoshi/secrets.json["api"]` gelesen und nie geloggt.

Exit `0` bedeutet: falsche Claims = 0 **und** jeder Fall hatte eindeutige
Diary-Evidenz. Exit `1` bedeutet falscher Claim oder ein abweichender erwarteter
`pendingClarify`-Marker, Exit `3` eine Beweislücke.
