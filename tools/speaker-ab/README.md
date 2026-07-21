# speaker-ab — Offline-A/B-Runner für die Speaker-Score-Aggregation

Test-Gate-Werkzeug für `SpeakerProfileAggregation` (BEST_SAMPLE vs. TOP_TWO_MEAN vs. CENTROID,
`web-inbound/src/main/kotlin/de/hoshi/web/SpeakerIdentifyService.kt`): spielt Proben
offline gegen einen Speaker-Profile-Store und liefert einen FAR/FRR-Proxy +
kanalgetrennte Confusion-Matrix statt Anekdoten ("bei mir hat's geklappt").

## Warum offline und nicht gegen den laufenden Kotlin-Service?

Damit ein A/B-Vergleich über viele Proben/Schwellen NICHT den Produktiv-Store oder
`identify()` selbst anfassen muss. Das Skript portiert die reine Mathematik 1:1:

- **BEST_SAMPLE** = `max(cosine(probe, sample_i))` über `profile.samples`.
- **TOP_TWO_MEAN** = Mittelwert der zwei höchsten Sample-Cosines; bei einem
  Legacy-Sample exakt derselbe Score wie BEST_SAMPLE. Der Kandidat prüft die
  Hypothese, dass ein einzelner Sample-Ausreißer die Paarentscheidung nicht
  allein bestimmen sollte.
- **CENTROID** = `cosine(probe, profile.embedding)` — das im Store bereits
  L2-renormalisiert gemittelte Embedding wird nur **gelesen**, nie neu berechnet
  (Null Mathe-Divergenz zum Kotlin-Pfad).
- **Bindungsregel**: Top-1 ≥ Schwelle UND (bei ≥2 Profilen) Top-1 schlägt Top-2 um
  ≥ `margin` (Default 0.10) — sonst ehrlich Gast. Exakt die Vera-Abstandsregel aus
  `CosineSpeakerIdentifyService.identify()`.

Der Sidecar-Aufruf ist bit-identisch zu `CamppSpeakerAdapter.embed()`: `POST /embed`
mit JSON `{"audio": <base64 WAV-Bytes>, "sampleRate": 16000}` →
`{"embedding": float[512], "dim": 512}` (verifiziert aus `sidecars/speaker/server.py`
— **kein** multipart/ffmpeg, wie man vermuten könnte; der Sidecar liest WAV selbst
über `soundfile` am RIFF-Magic).

## Nutzung

```bash
# Sidecar-freier Selbsttest (kein laufender Sidecar, keine echten Stimmen nötig):
python3 tools/speaker-ab/run_ab.py --smoke

# Echter Lauf gegen ein Manifest (TSV: wav_path, truth_speaker, channel, quality):
python3 tools/speaker-ab/run_ab.py --manifest manifest.tsv \
    --store ~/.hoshi/speaker-profiles.json --sidecar http://127.0.0.1:9002

# Echter Lauf über ein Verzeichnis-Layout <truth_speaker>/<channel>/*.wav:
python3 tools/speaker-ab/run_ab.py --wav-dir proben/ --sidecar http://127.0.0.1:9002

# Reversibler Ein-Profil-Schattenlauf: nur das Zielprofil scoren und die
# Enrollment-Embeddings aller anderen Store-Profile als Impostor-Sanity anfuegen.
# Echte ID/Pfade nur lokal einsetzen, nie in Bus/Repo kopieren:
python3 tools/speaker-ab/run_ab.py --manifest owner-proben.tsv \
    --store /privat/frozen/speaker-profiles.json \
    --target-profile '<private-profile-id>' \
    --include-other-enrollment-samples \
    --sidecar http://127.0.0.1:9002
```

`--store` Default = derselbe Auflösungspfad wie `PipelineConfig.resolveSpeakerStorePath`
(explizit via `HOSHI_SPEAKER_STORE_PATH` ▷ `/var/lib/hoshi-0.8/speaker-profiles.json`
falls beschreibbar ▷ `~/.hoshi/speaker-profiles.json`).

**"Wahrheit" (`truth_speaker`) vs. Store:** eine Probe gilt als **genuine**, wenn
`truth_speaker` exakt einem Profilnamen im Store entspricht; jeder andere Wert
(z. B. `fremd`, `gast`, ein nicht enrollter Name) gilt als **Impostor** und geht in
den FAR-Proxy-Nenner ein.

## Report

Landet **immer** unter `~/.hoshi/speaker-ab/<timestamp>/` — nie im Repo (das Skript
verweigert `--out-dir`-Pfade innerhalb des Repos hart):

- `probes.tsv` — je Probe: truth/channel/dauer_s/quality, alle Profil-Scores aller drei
  Modi, Top-1/Runner-up/Margin je Modus, Entscheidung je Schwelle × Modus.
- `report.md` — je Modus × Schwelle (0.35–0.70, Schritt 0.05): FAR-Proxy
  (fremd/Impostor als bekannt gebunden), FRR-Proxy (wahre Person nicht gebunden),
  Confusion-Matrix **getrennt je Kanal**, plus ein expliziter Hinweis, dass die
  Proben-Zahl klein ist (kein ROC/EER-Ersatz, sondern ein Test-Gate-Ersatz für
Anekdoten).

### Ein-Profil-Schattenmodus

`--target-profile` filtert ausschließlich die in-memory Score-Kandidaten des
Runners; der Store wird nicht kopiert, geschrieben oder verändert. In diesem
Modus ist die Schwellenmatrix absichtlich auf `0.45 | 0.50 | 0.55`
vorregistriert. Da nur ein Profil gescored wird, gibt es keinen Runner-up und die
Margin ist mathematisch ohne Wirkung — die Entscheidung ist schlicht
`target_score >= tau`.

`--include-other-enrollment-samples` fügt die Roh-Embeddings aller anderen
Store-Profile als zusätzliche Impostor-Proben hinzu. Sie brauchen keinen
Sidecar-Aufruf und verlassen den privaten Reportpfad nicht. Ihre Aussage ist
eng begrenzt: Samples derselben Enrollment-Sitzung sind korreliert und ersetzen
weder eine frische Aufnahme der anderen Person noch echte Gaststimmen. Sie
können einen Kandidaten offline widerlegen, aber niemals allgemeine
Gast-Sicherheit beweisen.

## Datenschutz (hart)

Keine WAVs, keine Embeddings, keine echten Namen landen im Repo — der Report-Pfad
liegt strukturell außerhalb (`~/.hoshi/…`), und das Skript bricht mit Fehler ab,
falls `--out-dir` versehentlich ins Repo zeigt. `.gitignore` deckt zusätzlich
`*.wav` und `.hoshi/` bereits ab (nichts musste ergänzt werden — das Skript schreibt
ohnehin nie Proben-/Embedding-Daten ins Repo).

## Smoke-Test — was er beweist

`--smoke` läuft ohne laufenden Sidecar und ohne echte Stimmen: ein synthetischer
Fake-Store (3 Profile: `alice`/`bob` mit je 3 deterministisch geseedeten
Zufalls-Samples, `solo` mit genau 1 Sample) + 4 synthetische WAVs (Sinus/Rauschen,
echt per `wave` gelesen für `duration_s`) werden direkt mit vorgegebenen
Fake-Embeddings ausgewertet. Vier Assertions:

1. **Report entsteht** — `probes.tsv` + `report.md` werden geschrieben.
2. **Alle drei Modi sind bei einem 1-Sample-Profil identisch** (`solo`) — bei genau
   einem Sample sind Best-Sample, Top-Two-Mittel und Centroid derselbe Cosine.
3. **Margin-Regel greift** — eine Probe, algebraisch exakt gleich weit von
   `alice` und `bob` konstruiert (`normalize(alice_sample0 + bob_sample0)`), erhält
   in beiden Profilen denselben Top-Score (Abstand ≈ 0 < `margin`) und wird trotz
   Score ≥ Schwelle korrekt zu Gast entschieden.
4. **Top-Two mindert eine Einzelspitze** — bei einem 3-Sample-Profil liegt der
   Mittelwert der beiden höchsten Scores strikt unter dem einzelnen Maximum.

## Verboten / außerhalb des Scopes dieses Tools

Kein Deploy und keine Änderung an Schwellen/Flags. Der neue Kotlin-Modus ist nur
eine opt-in Rechenstrategie; Default bleibt BEST_SAMPLE und der Live-Flip bleibt
gesperrt, bis echte gelabelte Holdout-Daten null Cross-Bindungen zeigen. Dieses
Skript ist rein lesend gegenüber dem Store (öffnet ihn nur zum Score-Vergleich) und
rein lesend gegenüber dem Sidecar (`POST /embed`, nie `/verify`, nie Enroll).

---

## Paar-Kalibrierung nach dem A/B (`calibrate_pair.py`)

`run_ab.py` erzeugt die Roh-Scores aller Aggregationskandidaten. `calibrate_pair.py`
bleibt bewusst auf die aktive BEST_SAMPLE-Baseline begrenzt und ist der kleinere
zweite Schritt für ein festes Zwei-Personen-Hard-Case: Es wählt einen
`Schwelle × Margin`-Betriebspunkt **nur auf Train-Sessions** und bewertet ihn
danach einmal auf vorher ungesehenen Holdout-Sessions. Es liest keine WAVs,
Embeddings, Profil-Stores oder echten Namen und ruft keinen Sidecar auf.

Warum noch kein AS-/S-Norm- oder lernendes Kalibrierungsmodell? Die aktuelle
Datenbasis ist klein. Dieselben Paar-Proben zugleich als Cohort, Trainingsdaten
und Erfolgsnachweis zu verwenden wäre Leakage. Eine globale monotone
Normalisierung kann außerdem einen exakten Gleichstand zweier Profil-Scores
nicht auflösen. Der kurzfristig falsifizierbare Kandidat ist deshalb nur die
bereits produktive Entscheidungsform:

```text
known genau dann, wenn top1 >= tau UND top1 - top2 >= delta
sonst guest
```

### Eingabevertrag (pseudonymisiertes TSV, außerhalb des Repos)

Der Header ist eine harte Allowlist; zusätzliche Spalten wie `wav_path`, Namen
oder Embeddings werden abgelehnt:

```text
probe_id  group_id  split  truth  channel  duration_s  quality  score_best_a  score_best_b
p001      g001     train  a      browser  1.24        clean    0.617         0.617
```

- `truth`: nur `a | b | guest`; keine echten Namen.
- `channel`: nur `browser | satellite`.
- `split`: nur `train | holdout`.
- `probe_id`: ausschließlich `pNNN…`, `group_id`: ausschließlich `gNNN…`.
  Dadurch können echte Namen nicht versehentlich in Fehlerlisten gelangen.
- `group_id`: gleiche Aufnahme-Session, Duplikate und abgeleitete Clips haben
  denselben Wert. Eine Gruppe in beiden Splits ist ein harter Fehler.
- `quality`: ausschließlich `clean | noisy | tv | overlap | short | mixed | unknown`;
  keine Namen oder Freitexte.
- Scores: BEST_SAMPLE-Cosines beider Profile in `[-1,1]`.

Der rohe `run_ab.py`-Report ist **nicht direkt übergabefähig**, weil er WAV-Pfade
und echte Profilnamen enthält. Auf dem privaten Host muss daraus eine kleine
Tabelle mit den obigen A/B-Spalten erzeugt werden; Audio und Embeddings bleiben
dort.

### Vorab eingefrorene Kandidaten und Gate

Das Tool probiert ausschließlich sechs Betriebspunkte:

- `tau ∈ {0.45, 0.50, 0.55}`;
- `delta ∈ {0.05, 0.10}`;
- heutige Baseline: `tau=0.45, delta=0.10`.

Niedrigere Schwellen werden nicht getestet. Auf `train` werden zuerst alle
Kandidaten mit Cross-Bindung oder Guest-Bindung verworfen; danach gewinnt die
höchste korrekte Coverage, bei Gleichstand der konservativere Betriebspunkt.
Der Holdout beeinflusst diese Wahl im Code nicht. „Unabhängige Gruppen" bedeutet
dabei präzise: verschiedene vom Betreiber gelieferte `group_id`s. Dass diese
tatsächlich getrennte Aufnahme-Sessions darstellen, bleibt eine dokumentierte
Betreiber-Attestation und wird nicht aus Scores erraten.

`READY FUER OWNER-GATE` gibt es nur, wenn:

1. A, B und Guest in Train und Holdout auf Browser **und** Satellit vorkommen;
2. der ausgewählte Holdout-Punkt null Cross- und null Guest-Bindungen hat;
3. er die Baseline auf mindestens zwei unabhängigen Holdout-Gruppen verbessert;
4. keine andere Holdout-Gruppe eine korrekte Baseline-Bindung verliert.

Das ist absichtlich strenger als „17 Tests grün“. Auch ein READY führt keinen
Live-Flip aus: Wiring, Deploy und Owner-Gate bleiben beim Orchestrator/Andi.

### Nutzung

```bash
# Sidecar-freier Contract-Smoke:
python3 tools/speaker-ab/calibrate_pair.py --selftest

# Vollständige Negativ-/Leakage-Tests:
python3 -m unittest tools/speaker-ab/test_calibrate_pair.py

# Echter Lauf; Eingabe und Ausgabe MÜSSEN außerhalb des Repos liegen:
python3 tools/speaker-ab/calibrate_pair.py \
  --input /private/tmp/hoshi-speaker-scores.tsv \
  --out-dir ~/.hoshi/speaker-pair-calibration/frozen2 \
  --active-aggregation best-sample \
  --active-tau 0.45 \
  --active-delta 0.10 \
  --config-evidence-sha256 <SHA256-DER-SANITISIERTEN-BOOTZEILE>
```

Die vier `active-*`-/Evidence-Angaben sind Pflicht. Weicht der bewiesene
Laufzeitstand von der vorregistrierten Baseline
`best-sample / tau=0.45 / delta=0.10` ab, entsteht kein READY-Report. Der
Evidence-Hash verweist auf die sanitisiert festgehaltene aktive Boot-/Readback-
Zeile; die Zeile selbst bleibt beim Betreiber bzw. kann ohne Namen/Scores auf
dem Bus quittiert werden.

Ausgabe: `report.md` für das menschliche Gate und
`calibration-report.json` für maschinelles Readback. Beide enthalten nur
aggregierte Zähler und pseudonymisierte Fehler-IDs, kein Audio/Embedding. Das
Ausgabeverzeichnis wird mit Modus `0700`, die Dateien mit `0600` angelegt und
niemals überschrieben. Der SHA-256 des Eingabe-Manifests steht im Report, damit
der exakt eingefrorene Train-/Holdout-Schnitt auf dem Bus belegbar ist, ohne die
privaten Scorezeilen zu teilen.

„Einmaliger Holdout" ist zusätzlich eine Prozessregel: **vor** dem ersten
Öffnen des Holdout-Reports werden Manifest-SHA und Code-Commit auf dem Bus
vorregistriert. Das Tool verhindert mathematisches Train/Holdout-Leakage, kann
aber nicht erkennen, ob ein Mensch nach einem Lauf den Split neu etikettiert;
genau dafür dienen Hash + Bus-Historie.

### Claim-Grenze bei kleinem N

Null beobachtete Fehler werden nie als „0 % Fehlerrate“ ausgegeben. Der Report
nennt optional die einseitige 95%-Obergrenze `1 - 0.05^(1/n)` und markiert sie
ausdrücklich als optimistisch, weil Aufnahmen derselben Umgebung korreliert sein
können. Das Ergebnis ist ein lokales Holdout-Gate für dieses Stimmenpaar, keine
EER-, allgemeine FAR- oder SOTA-Messung.
