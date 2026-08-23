# Nagori Memory-Benchmark N0

N0 ist der **Messvertrag vor der Gedächtnis-Kaskade**. Das Werkzeug ändert
keine Hoshi-Runtime, liest keine echte Memory-Datenbank und ruft weder Ollama
noch einen Sidecar auf. Es erzeugt kohärente synthetische Haushaltsszenarien,
lässt einen Menschen die Fragen labeln und reviewen, friert Dev/Holdout nach
Templatefamilien getrennt ein und wertet später erzeugte B0/H1/H2-Resultate aus.

Die Reihenfolge ist die Funktion:

```text
synthetisch erzeugen → menschlich labeln → separat reviewen → Seal → Freeze
→ externe Retrieval-Arme messen → Bericht
```

Der Retriever erzeugt niemals Labels. Ein Freeze ist lokal unveränderlich und
hashgebunden, aber nicht kryptografisch blind: Wer Zugriff auf den Rechner hat,
kann die Holdout-Datei lesen. Der öffentliche Claim lautet deshalb nur
„eingefrorener A/B-Vergleich“.

## Was N0 absichtlich nicht enthält

- keinen Port des alten `0.9-memory-kaskade`-Harness;
- keine H1-/H2-/FTS-/Recency-Runtime;
- keine Memory-Flags oder Schwellen;
- keinen Zugriff auf `~/.hoshi/episodic-memory.db`;
- keinen echten Nutzertext, keine Stimme und keine Speaker-Embeddings;
- noch keinen Adapter-Runner. N1/N2 müssen Resultat-JSONL gegen diesen bereits
  eingefrorenen Vertrag produzieren, statt das Dataset an ihre Implementierung
  anzupassen.

Alle Tools verwenden ausschließlich die Python-Standardbibliothek und öffnen
kein Netzwerk.

## 1. Synthetischen Intake erzeugen

Der produktive Mindestvertrag erzeugt standardmäßig 12 unabhängige Szenarien,
6 getrennte Templatefamilien und 144 Fragen aus sieben Klassen:

```bash
python3 tools/memory-bench/generate_synthetic.py nagori-v1 --yes
```

Der private Intake liegt unter
`~/.hoshi/memory-bench/intake/nagori-v1/` (`0700`, Dateien `0600`).
`intake.json` und das Audit halten ausdrücklich fest:
`labelsGenerated=false`, `audioPersisted=false`, `userDataRead=false`.

Der Generator ist deterministisch. Vor dem Seal baut das Tool Szenarien und
unveränderliche Fragefelder noch einmal aus dem gebundenen Seed auf. Manuell
eingeschmuggelter Text — auch harmloser — macht den Seal ungültig. Änderbar sind
nur Label-, Review- und Revisionsfelder über die vorgesehenen Befehle.

## 2. Ein Mensch labelt und reviewt

Offene Fragen zeigen:

```bash
python3 tools/memory-bench/collect.py list nagori-v1
python3 tools/memory-bench/collect.py show nagori-v1 query-s01-03
```

`show` zeigt die Frage, den synthetischen Sprecher, `asOf` und die vollständige
chronologische Szenario-Welt. Danach setzt ein Mensch drei **disjunkte** Mengen:

- `acceptable`: alle tatsächlich richtigen Episoden;
- `forbidden-stale`: überholte Episoden desselben Sprechers;
- `forbidden-foreign`: passende Episoden eines anderen Sprechers.

Beispiel für eine zeitliche Aktualisierung:

```bash
python3 tools/memory-bench/collect.py label nagori-v1 query-s01-03 \
  --answerable yes \
  --acceptable episode-s01-08 \
  --forbidden-stale episode-s01-04 \
  --yes

python3 tools/memory-bench/collect.py review nagori-v1 query-s01-03 --yes
```

Eine ehrlich leere Fremdsprecher-Frage:

```bash
python3 tools/memory-bench/collect.py label nagori-v1 query-s01-08 \
  --answerable no \
  --forbidden-foreign episode-s01-02 \
  --yes

python3 tools/memory-bench/collect.py review nagori-v1 query-s01-08 --yes
```

`label` und `review` sind absichtlich zwei Schritte. Ein falsches Review kann
vor dem Seal mit einem endlichen, nicht frei beschreibbaren Grund wieder
geöffnet werden:

```bash
python3 tools/memory-bench/collect.py reopen nagori-v1 query-s01-03 \
  --reason label-error --yes
```

Das Audit enthält nur opake IDs, Zähler, endliche Reason-Codes und Zeitstempel —
niemals Frage- oder Episodentext.

## 3. Seal und Freeze

Der Seal akzeptiert nur den vollständigen Vertrag:

- 120–240 Fragen, mindestens 12 Szenarien und 6 Templatefamilien;
- jede Query menschlich gelabelt und separat reviewed;
- mindestens je 50 beantwortbare und ehrlich-leere Fragen;
- jeder Fragetyp mindestens zehnmal;
- mindestens zwölf Altwert- und zwölf Fremdsprecher-Proben;
- lückenlose Audit-Revisionen;
- bytegenauer synthetischer Ursprung.

```bash
python3 tools/memory-bench/collect.py seal nagori-v1 --yes

python3 tools/memory-bench/collect.py freeze nagori-v1 \
  --output-dir "$HOME/.hoshi/memory-bench/frozen/nagori-v1" \
  --yes
```

Der Split-Seed entsteht erst nach dem Seal. Dev und Holdout werden nach ganzen
`templateFamily`-Blöcken getrennt; Szenarien, Episoden und Query-IDs können die
Grenze nicht queren. Ein Single-use-Intent bindet Seed und Zielname vor der
Publikation. Nach einem Abbruch kann nur exakt derselbe Freeze fortgesetzt
werden — kein wiederholtes Splitten bis zum gewünschten Ergebnis. Das
Prozess-Lock wird bei einem Crash vom Betriebssystem freigegeben; eine
zurückgebliebene Lock-Datei blockiert diesen identischen Retry nicht.

Der Output ist ein atomar/no-replace publiziertes `0500`-Verzeichnis mit
`0400`-Dateien. Vollprüfung:

```bash
python3 tools/memory-bench/verify_freeze.py \
  "$HOME/.hoshi/memory-bench/frozen/nagori-v1"
```

## 4. Vertrag für N1/N2-Resultate

Der künftige Adapter-Runner isoliert jedes `scenarioId` in einer frischen
synthetischen Store-Welt. Er schreibt weder Querytext noch Scores, sondern pro
Variante, Condition und Query genau eine JSONL-Zeile:

```json
{"schemaVersion":1,"variant":"B0","condition":"cold","queryId":"query-s01-03","retrievedEpisodeIds":["episode-s01-08"],"latencyMs":241.7}
```

Erlaubte Varianten sind `B0`, `H1`, `H2`, `H1_H2`; Conditions sind `cold` und
`warm`. Jede Variante muss jede Query in beiden Conditions enthalten. Die
Retrieval-IDs müssen cold/warm identisch sein — nur die Latenz darf sich
unterscheiden. Fehlende Queries, NaN/Inf, unbekannte Episoden, mehr als zwei
Treffer oder ein Sprung in eine andere Szenario-Welt brechen fail-closed ab.

Auswertung, zunächst auf Dev und erst nach eingefrorener Parameterauswahl einmal
auf Holdout:

```bash
python3 tools/memory-bench/evaluate.py \
  --freeze "$HOME/.hoshi/memory-bench/frozen/nagori-v1" \
  --split dev \
  --results /privater/pfad/dev-results.jsonl \
  --output-dir "$HOME/.hoshi/memory-bench/reports/dev-001"
```

## Metriken und Promotion

Der Bericht trennt:

- Recall@2 und MRR@2 auf beantwortbaren Fragen;
- False-Memory-Rate: irgendein Recall bei `answerable=false`;
- falscher Recall bei beantwortbaren Fragen ohne Goldtreffer;
- Stale-Intrusion und Update-Intrusion;
- explizite Fremd-ID-Intrusion und direkte Cross-Speaker-Leaks;
- Update-Correct;
- Coverage;
- p50/p95 getrennt für cold und warm.

Raten enthalten immer Ereignisse, Nenner und Wilson-95%-Intervalle. MRR und
gepaarte Recall-Differenzen erhalten deterministische Bootstrap-Intervalle;
Recall-Gewinne zusätzlich einen einseitigen exakten gepaarten Test.

Ein Kandidat besteht nur gleichzeitig:

1. beobachtete FMR steigt gegenüber B0 nicht;
2. Cross-Speaker-Leak-Count ist exakt null;
3. Recall@2 steigt;
4. der einseitige exakte Recall-Test hat `p <= 0,05`.

Die Schwellen stehen im eingefrorenen `evaluation-contract.json`, nicht im
Runner. H1/H2-Parameter gehören ausschließlich in Dev. Ein Holdout-NO-GO wird
nicht durch Nachstimmen und erneutes Öffnen repariert, sondern durch eine neue
vorab begründete Benchmark-Version.

### Null Fehler ist keine Null-Rate

Bei null beobachteten Fehlern weist der JSON-Report die einseitige exakte
95%-Obergrenze `1 - 0,05^(1/n)` aus. `0/30` ergibt etwa 9,5 Prozent und beweist
nicht „unter fünf Prozent“. Erst `0/59` liegt knapp darunter. Das Tool schreibt
deshalb „0 beobachtet“ und niemals „sicher null“.

## Tests

```bash
python3 -m unittest tools/memory-bench/test_memory_bench.py -v
python3 -m py_compile tools/memory-bench/*.py
```

Die Tests manipulieren ausschließlich temporäre synthetische Artefakte. Sie
prüfen unter anderem Schema-Schmuggel, Sprecher-/Zeit-Goldfehler, Source- und
Seal-Drift, Audit-Lücken, Split-Shopping, Cross-Szenario-Treffer, cold/warm-
Drift, FMR-Regression, Cross-Speaker-NO-GO, No-overwrite und pfadfreie Reports.

## Rate-Stellen / zuerst anzweifeln

- Der Generator beweist Testkohärenz, nicht Repräsentativität echter kurzer
  Voice-Queries.
- Das Tool kann menschliches Review und Dateirevisionen belegen, nicht die
  fachliche Qualität oder Unabhängigkeit der Person, die labelt.
- Der Holdout ist gegen versehentliche Drift gehärtet, nicht gegen einen lokalen
  Betreiber, der ihn bewusst liest.
- N0 misst noch keinen echten Adapter. Jede Qualitäts- oder Latenzzahl vor N1/N2
  wäre erfunden.
- Als Erstes anzuzweifeln ist die Übertragbarkeit der sechs synthetischen
  Templatefamilien. Vor einem Produktionsclaim braucht es zusätzlich einen
  getrennten, menschlich formulierten Voice-Fragensatz — ohne echte private
  Episoden in Repo, Bus oder Report.
