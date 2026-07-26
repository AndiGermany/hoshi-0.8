# Knowledge-Benchmark

Der Benchmark vergleicht zwei lokale Knowledge-Bridges auf **demselben
eingefrorenen Query-Satz**. Er kalibriert nichts, verändert keine Datenbank und
misst Retrieval — nicht die faktische Qualität der finalen Brain-Antwort.

## Schema v2: Evidenz gehört zu einem Titel

Eine JSONL-Zeile:

```json
{"schemaVersion":2,"id":"q-001","split":"holdout","query":"Wer war Marie Curie?","searchQuery":"marie curie","answerable":true,"goldPassages":[{"title":"Marie Curie","evidence":["Physikerin"]}],"exactTitleRequired":false,"topicGroup":"person-curie","stratum":"person"}
```

- `split`: `dev` oder `holdout`;
- `query`: vollständige Testfrage;
- `searchQuery`: Ausgabe des eingefrorenen produktiven Query-Reducers;
- `goldPassages`: je Goldartikel dessen kurze, wörtliche Evidenzspans;
- `topicGroup`: zusammengehörige Varianten eines Themas; eine Gruppe darf nie
  auf beide Splits verteilt werden;
- `stratum`: fachliche Eval-Schicht, etwa `definition` oder `no-answer-live`;
- `exactTitleRequired=true`: bildet die heutige definitorische Abstention nach;
  strukturell muss die reduzierte `searchQuery` dann exakt einem normalisierten
  Goldtitel entsprechen, sonst kann diese Query nie korrekt treffen und wird
  bereits beim Freeze abgewiesen;
- `answerable=false`: `goldPassages` ist leer. Jeder Treffer, der das produktive
  BM25-Gate passiert, zählt konservativ als False Grounding.

Ein Evidenzspan zählt nur, wenn er **im zugeordneten Goldtitel** vorkommt. Ein
zufällig gleicher Satz in einem falschen Artikel ist kein Passage-Treffer.

Der Reader validiert v2 strikt und verweigert unbekannte Felder. Alte v1-Zeilen
ohne `schemaVersion` werden kompatibel gelesen, sofern genau ein Goldtitel
eindeutig mit allen Evidenzspans verbunden werden kann. Mehrere v1-Goldtitel
sind mehrdeutig und müssen bewusst nach v2 migriert werden.

## Private Erfassung: add → label → review → seal → freeze

`collect.py` nutzt nur die Python-Standardbibliothek. Der rohe Intake liegt
standardmäßig unter `~/.hoshi/knowledge-bench/intake/<dataset>/`:

- Verzeichnisse `0700`, `records.jsonl` `0600`;
- Zustand zunächst `draft`, erst eine separate Freigabe setzt `reviewed`;
- Duplikate und offensichtliche Privacy-Muster werden fail-closed gemeldet;
- Namen, Räume, private Notizen und echte Haushaltsdaten gehören nicht in einen
  freizugebenden Benchmark.

Text erfassen:

```bash
python3 tools/knowledge-bench/collect.py add dewiki-v1 \
  --topic-group concept-photosynthesis \
  --stratum definition \
  --text "Was bedeutet Photosynthese?" \
  --yes
```

Optional kann eine vorhandene Audiodatei durch Hoshis **lokales** STT laufen:

```bash
python3 tools/knowledge-bench/collect.py add dewiki-v1 \
  --topic-group concept-photosynthesis \
  --stratum definition \
  --audio /pfad/zur/aufnahme.wav
```

Die STT-URL ist hart auf `127.0.0.1`, `localhost` oder `::1` begrenzt. Audio
wird nur für den Request gelesen, weder kopiert noch im Intake oder Manifest
referenziert; gespeichert wird ausschließlich das bestätigte Transkript.

Ground Truth ergänzen und danach separat prüfen:

```bash
python3 tools/knowledge-bench/collect.py label dewiki-v1 q-001 \
  --answerable yes \
  --gold-passage "Photosynthese::Lichtenergie" \
  --exact-title-required yes

python3 tools/knowledge-bench/collect.py review dewiki-v1 q-001
python3 tools/knowledge-bench/collect.py list dewiki-v1
```

## Freeze und Provenienz

Vor dem Split wird die rein öffentliche Kandidatenauswahl zusammen mit der
unveränderten Legacy-Baseline einmalig versiegelt:

```bash
python3 tools/knowledge-bench/collect.py seal-selection dewiki-v1 \
  --candidate-selection "/pfad/candidate-selection.jsonl" \
  --baseline-database "$HOME/.hoshi/knowledge/wiki-de/articles.db"
```

Pack v1 akzeptiert in dieser JSONL nur `title` und eine **leere**
`aliases`-Liste. Die Baseline wird streaming per SHA-256 gebunden; vorhandene
SQLite-`-wal`-, `-shm`- oder `-journal`-Dateien machen den Seal ungültig.

Erst danach können vollständig `reviewed` Records eingefroren werden:

```bash
python3 tools/knowledge-bench/collect.py freeze dewiki-v1 \
  --output-dir "$HOME/.hoshi/knowledge-bench/frozen/dewiki-v1" \
  --source-dump-url "https://dumps.wikimedia.org/dewiki/YYYYMMDD/dewiki-YYYYMMDD-pages-articles-multistream.xml.bz2" \
  --source-dump-sha1 "<40 hex>" \
  --source-dump-sha256 "<64 hex>" \
  --source-dump-file "/pfad/dewiki-YYYYMMDD-pages-articles-multistream.xml.bz2"
```

Die URL muss exakt auf einen kanonischen deutschen
`pages-articles-multistream`-Dump unter `https://dumps.wikimedia.org` zeigen.
Der Operator übernimmt URL und Hashwerte ausdrücklich als Behauptung; das
Werkzeug macht **keinen** Netzwerkabruf und behauptet deshalb auch keine
Online-Verifikation. Mit dem optionalen `--source-dump-file` liest es den
lokalen Dump einmal vollständig, prüft dessen SHA-1 und SHA-256 gegen die
deklarierten Werte und hält die selbst gemessene Bytezahl fest. Ohne diese
Option steht im Manifest ehrlich `localFileVerification.performed=false`.
Lokale Pfade gelangen nie ins Manifest.

Der Freeze:

1. verweigert Drafts, normalisierte Duplikate und ungeprüfte Privacy-Warnungen;
2. beansprucht den Selection-Seal exklusiv; parallele oder wiederholte Freezes
   derselben Dataset-ID werden fail-closed abgewiesen;
3. prüft den Python-Reducer gegen gemeinsame Testvektoren und den gepinnten
   Reducer-Quellbereich aus `Fts5GroundingAdapter`;
4. verlangt den vereinbarten, vollständig geprüften Umfang von 80–100 Fragen;
5. erzeugt mit einem erst nach dem Seal zufällig generierten, im Manifest
   festgehaltenen Seed einen ungefähr 70/30 großen, nach `topicGroup`
   leckagefreien Dev-/Holdout-Split und stoppt zusätzlich, wenn eine identische
   reduzierte `searchQuery` oder ein normalisierter Goldtitel in mehreren
   Gruppen vorkommt;
6. verlangt standardmäßig mindestens 20 beantwortbare und 10
   No-Answer-Fragen im Holdout sowie beide Klassen auch im Dev-Split;
7. schreibt das unveränderliche Freeze-Verzeichnis atomar/no-replace als
   `0500` und `dev.jsonl`, `holdout.jsonl`, `candidate-selection.jsonl` sowie
   `manifest.json` als `0400`, ohne den Intake oder lokale Absolutpfade
   mitzunehmen. Die Hash-Bindung erkennt Drift; die Rechte verhindern
   zusätzlich versehentliche Bearbeitung.

Das Manifest enthält Dataset- und Dateihashes, Dump-URL/SHA-1/SHA-256,
Reducer-Vertrag/Commit, Selection-Seal, Baseline-Hash, Splitmethode,
Klassen-/Gruppenzahlen und explizit `audioPersisted=false`. Ein bestehendes
Freeze-Verzeichnis wird nie überschrieben.

Der Seal beweist die vom Werkzeug erzwungene Reihenfolge innerhalb desselben
unveränderten Intake-Verzeichnisses und verhindert dort parallele oder
wiederholte Freezes derselben Seal-ID. Kopieren, Löschen oder Rollback dieses
Verzeichnisses kann ein rein lokales Werkzeug ohne externe Registry oder
vertrauenswürdigen Zeitstempel nicht ausschließen. Der Seal beweist ebenso
wenig, dass der Operator die zuvor gelabelten Fragen nie gesehen hat. Ein
wirklich blindes Holdout braucht externe Verwahrung oder eine vorab
registrierte, unabhängig kontrollierte Evaluation; bis dahin lautet der
ehrliche Claim „eingefrorener A/B-Vergleich“, nicht „kryptografisch blind“.

Die Produktionsgrenzen 80–100 sowie 20/10 im Holdout sind absichtlich keine
CLI-Optionen und können bei einem echten Freeze nicht abgesenkt werden. Das
Manifest enthält außerdem Stratum-Zahlen für Gesamtmenge, Dev und Holdout.

Der Freeze prüft Schema, Titel-/Evidenz-Zuordnung und die strukturelle
`exactTitleRequired`-Invariante. Er parst den Wikipedia-Dump jedoch nicht, um
jeden Evidenzspan gegen den zugeordneten Artikel zu validieren. Diese
**Evidence-vs-Dump-Prüfung ist ein offenes Gate**, kein erledigter Beweis; das
Manifest weist sie explizit als `performed=false`, `isFreezeGate=false` und
`status=open` aus. Bis dieses Gate implementiert ist, bleibt dafür das
menschliche Review verantwortlich.

`query-reducer-contract-v1.json` ist ein bewusster Pin, weil der produktive
Kotlin-Reducer nicht aus einem stdlib-Python-Tool aufrufbar ist. Ändert sich
der ab `productionRegionStart` markierte Dateisuffix oder ein gemeinsamer
Testvektor, stoppt der Freeze, bis der Vertrag bewusst nachgezogen wurde. Der
vollständige Quellstand am gepinnten Commit wird zusätzlich per Git-Blob und
SHA-256 belegt; die aktuelle Arbeitsdatei wird nicht pauschal als vollständig
eingefroren bezeichnet.

## A/B ausführen

```bash
python3 tools/knowledge-bench/run_bench.py \
  --queries "$HOME/.hoshi/knowledge-bench/frozen/dewiki-v1/holdout.jsonl" \
  --manifest "$HOME/.hoshi/knowledge-bench/frozen/dewiki-v1/manifest.json" \
  --split holdout \
  --baseline-url http://127.0.0.1:8035 \
  --baseline-database "$HOME/.hoshi/knowledge/wiki-de/articles.db" \
  --baseline-endpoint /search \
  --candidate-url http://127.0.0.1:8135 \
  --candidate-endpoint /v1/search \
  --candidate-pack-manifest "$HOME/.hoshi/knowledge/packs/de-core-20260701/manifest.json" \
  --candidate-source-dump "$HOME/.hoshi/knowledge/dumps/dewiki-20260701-pages-articles-multistream.xml.bz2" \
  --output-dir "$HOME/.hoshi/knowledge-bench/reports/$(date +%Y%m%d-%H%M%S)"
```

Der Runner akzeptiert ausschließlich Loopback-Bridges und deaktiviert
System-Proxys. Vor dem Parsen erstellt er einen privaten stabilen Snapshot des
exakten Freeze-Verzeichnisses, prüft beide Splitdateien, Auswahl und
Gesamtdataset-Hash und verweigert spätere Pfad-/Inode-Drift. Gemischte
Dev-/Holdout-Dateien werden nicht still gefiltert, sondern abgelehnt. Ein
Candidate benötigt immer beide lokalen Belege:
`--candidate-pack-manifest` und `--candidate-source-dump`.

Die Baseline ist ebenfalls kein beliebiger Loopback-Prozess: Ihre DB muss
bytegleich zur im Seal gehashten Legacy-Datei sein, darf keine SQLite-Sidecars
besitzen und der historische `/health`-Endpunkt muss genau diesen lokalen Inode
melden. Zusätzlich muss `/v1/health` die SHA-256 der tatsächlich geladenen
`server.py` und `pack_manifest.py` nennen; der Runner vergleicht sie mit seiner
commit-gebundenen Source-Closure. Vor und nach dem Lauf werden Datei- und
Runtime-Identität geprüft. Auch das ist eine prüfbare Selbstauskunft desselben
lokalen Prozesses und keine kryptografische Remote-Attestation.

Vor der ersten privaten Query hasht der Verifier die vollständige SQLite-Datei
und die vollständigen Quelldump-Bytes. Zusätzlich verlangt der Runner den
frischen kanonischen Wikimedia-Status, den deterministischen Abgleich der
logischen Records gegen Dump plus gebündelte Auswahl und einen vollständigen
FTS-Integritätscheck. Die gepinnte Builder-Toolchain erzeugt außerdem eine
frische SQLite, die bytegleich zum Kandidaten sein muss. Danach muss die
laufende Candidate-Bridge über
`/v1/manifest` ausdrücklich `status: manifest-content-verified`,
`verification.contentSha256Verified: true` und den selbst gemessenen
`verification.actualDatabaseSha256` melden. Dieser tatsächliche Runtime-Hash
muss exakt dem lokal gehashten Pack entsprechen; die bloße
`database.sha256`-Behauptung aus dem Manifest reicht nicht. Zusätzlich meldet
der Provenienz-Endpunkt pfadfreie SHA-256 der geladenen Runtime-Quellen. Der
Runner vergleicht sie mit seiner commit-gebundenen lokalen Source-Closure.
Diese Selbstauskunft ist ein Reproduzierbarkeitsbeleg, keine kryptografische
Attestation gegen einen bösartigen lokalen Prozess.

Der öffentliche Report bindet den Hash des autoritativen Statusbelegs, den
logischen Record-Digest und den tatsächlichen Runtime-DB-Hash, aber keine
lokalen Dump- oder Pack-Pfade. Ein Legacy-Pack, ein Metadata-only-Start oder ein
bloß selbst behauptetes `release-candidate` kann deshalb kein
Produktions-PASS erzeugen.

Baseline und Candidate werden pro Query deterministisch gegengewichtet
verschachtelt, nach exakt einer Warmup-Runde exakt dreimal wiederholt. Für einen
Produktionslauf sind außerdem `limit=3`, `bm25-max=-3.0` und alle drei
HTTP-Pfade fest; NaN/Inf oder abweichende Werte werden vor Probe 1 abgewiesen.
Retrieval-Ergebnisse
müssen über die Repeats identisch bleiben; die Latenz nutzt alle Messungen.
Zusätzlich zum beobachteten Gewinn wird ein gepaarter, einseitiger exakter Test
über Passage-Recall@3 ausgewiesen.

Das Produktions-Gate verlangt gleichzeitig:

- ausschließlich den Split `holdout`;
- mindestens 20 beantwortbare und 10 No-Answer-Fragen;
- keine HTTP-/Parsefehler;
- Passage-Recall@3-Gewinn von mindestens `+0,10`;
- einen gepaarten Recall-Test mit `p ≤ 0,05`;
- keine höhere `falseRetrievalCandidateRate` und absolut höchstens `0,05`;
- höchstens `+150 ms` zusätzliche p95-Wall-Latenz.

Die `+0,10` ist eine vorab gesetzte Promotionshypothese, kein kalibrierter
Naturwert. `falseRetrievalCandidateRate` bedeutet nur: Der Retriever lieferte
für eine als unbeantwortbar gelabelte Frage mindestens einen Kandidaten. Das
ist **weder** eine gemessene Hoshi-Antwort noch eine
False-Grounding-/Halluzinationsrate; dafür bleibt ein separates
End-to-End-Abstention-Gate offen.

Berichte enthalten Dateiname und SHA-256 des Query-Satzes, den Hash des
Freeze-Manifests, Baseline-/Pack-Identität, den kanonischen
Ausführungsvertrag samt SHA-256 und eine Git-/Byte-Bindung der
entscheidungsrelevanten Runner-/Verifier-Quellen, aber keinen lokalen
Absolutpfad. Sie werden atomar,
no-overwrite und privat (`0700`/`0600`) geschrieben. Holdout-Fragen, -Titel und
-Evidenz dürfen weder Query-Entwicklung noch Kandidatenauswahl beeinflussen;
dafür ist ausschließlich `dev` da. Insbesondere muss auch die explizite
Wikipedia-Titelauswahl für `de-core` vor Öffnung des Holdouts eingefroren werden;
Goldtitel aus dem Holdout dürfen nicht nachträglich ins Pack übernommen werden.

`examples/public-smoke.jsonl` ist nur ein öffentliches Schema-/Parserbeispiel.
Es enthält bewusst gemischte Splits und kein Freeze-Manifest; der gehärtete
Runner muss es deshalb ablehnen. Ein echter Ablauf beginnt immer mit einem
manifestierten Freeze.
