# Runbook: Brain-Revision A/B — `deb1db71` (LIVE) vs. `475b9088` (Kandidat)

Modell: `mlx-community/gemma-4-e4b-it-4bit` · Sidecar: `sidecars/brain/` · Port 8041
Treiber: `tools/modell-ab/ab-run.sh` · Vorarbeit: `vault/tracks/prep/PREP-mlx-modell-upgrade.md`

> **Kurzfassung für den eiligen Leser:** Der Kandidat bringt **70,5 MB weniger RAM
> (−1,35 %)** und **null neue Gewichte**. 40 der 70 MB sind kein Gewinn, sondern eine
> **Präzisions-Senkung** (ein bisher unquantisierter Tensor wird 4-bit). Der Preis ist ein
> mlx-lm, das es als Release **nicht gibt**. Dieses Runbook macht den Zug trotzdem
> gefahrlos ausführbar — für den Fall, dass Andi ihn sehen will, statt ihn zu glauben.

---

## 1. Was überhaupt zur Debatte steht (gemessen, nicht gelesen)

Beide Snapshots liegen vollständig im lokalen HF-Cache. `ab-run.sh preflight` rechnet
das Folgende bei jedem Lauf frisch aus dem safetensors-Header nach — die Tabelle ist
also nachprüfbar, nicht abgeschrieben:

| | ALT `deb1db71` (2026-05-19) | NEU `475b9088` (2026-07-06) |
|---|---|---|
| Tensoren | 2894 | 2770 |
| Gewichte gesamt | 5217,0 MB | 5146,4 MB (**−70,5 MB / −1,35 %**) |
| KV-shared Layer 24–41 | `k_proj`/`v_proj`/`k_norm` **dupliziert mitgeführt** (126 Tensoren, 31,0 MB) | gestrichen |
| `per_layer_model_projection` | **BF16**, 55,05 MB | **4-bit** (U32+scales+biases), 15,48 MB |
| `chat_template.jinja` | Blob `c19999a3…` | **identischer Blob** |
| `tokenizer.json` | Blob `cc8d3a0c…` | **identischer Blob** |
| `tokenizer_config.json` | — | 20 **rein additive** Keys, **kein einziger geänderter Wert** |
| `config.json` | — | zusätzlich eingebettete `generation_config` (temp 1.0 / top_k 64 / top_p 0.95) |
| `processor_config.json` | — | zusätzlicher `video_processor`-Block (Text-Pfad: irrelevant) |

**Herkunft.** Der Kandidat ist laut HF-Commit-Message eine *Re-Konversion* von
`google/gemma-4-E4B-it@fee6332c`. Upstream liegt zwischen unserer Basis
(`d6436b3d`, 18.05., „Emit multimodal placeholders in tool response content-parts")
und `fee6332c` (03.06.) **genau ein Commit: „Update README.md"**. Es kommt also
nachweislich **kein einziges neu trainiertes Gewicht** und **keine
Template-Änderung** mit — die identischen Blob-Hashes von `chat_template.jinja`
und `tokenizer.json` belegen das lokal, ohne dem Hub glauben zu müssen.

**Die 126 gestrichenen Tensoren sind tote Gewichte.** In
`mlx_lm/models/gemma4_text.py::Attention.__call__` gilt:

```python
if shared_kv is not None:        # Layer 24..41
    keys, values = shared_kv     # -> self.k_proj / v_proj / k_norm werden NIE aufgerufen
```

Sie werden geladen und nie benutzt. Der Kandidat wirft sie weg — fachlich richtig.
Nur: `Attention.__init__` in **0.31.2 legt sie trotzdem immer an**, unabhängig davon,
ob der Layer shared ist. `load_weights(strict=True)` verlangt sie deshalb weiter.

**Damit ist die einzige verhaltensrelevante Änderung** die 4-bit-Quantisierung von
`per_layer_model_projection`. Sie kann die Qualität nur **halten oder senken**, nicht heben.

---

## 2. Beweislage: warum wir den Kandidaten heute nicht fahren

Der Ladefehler ist auf **diesem** Mac zweimal real gewesen, nicht theoretisch.

`sidecars/brain/.venv` trägt `mlx-lm 0.31.2` / `mlx 0.31.2` / `transformers 5.14.1`.
Mit dieser Version endet der Kandidat deterministisch in:

```
ValueError: Missing 54 parameters:
language_model.model.layers.24.self_attn.k_norm.weight, …k_proj.weight, …v_proj.weight,
… bis layers.41 …
```

54 = 18 KV-shared Layer × 3 Keys. `ab-run.sh preflight` leitet die Zahl selbst aus
`config.json` her (`num_hidden_layers 42 − num_kv_shared_layers 18 = erste geteilte
Schicht 24`) und trifft exakt den Logeintrag.

**Vorfall 18.→19.08.2026 (nachts, 0.8, dieser Mac):**

| Zeit | Ereignis | Quelle |
|---|---|---|
| 18.08. 22:16 | Snapshot `475b9088` beginnt in den Cache zu laufen, `refs/main` wandert mit | `snapshots/475b…` mtime |
| 18.08. 22:28 | `bin/hoshi heal` bricht laut ab: „Modell NICHT vollständig im HF-Cache … kaputte refs/main" | `~/.hoshi/logs/e4b-heal-20260818-222839.log` |
| 18.08. 23:56 | 5-GB-`model.safetensors` fertig | Symlink-mtime |
| 18.08. 23:57 – 19.08. 03:07 | Watchdog-Dauerschleife: `HEALTH FAIL` → `RESTART` → `Missing … parameters` → wieder tot, **~4,5 h** | `e4b-watchdog.log`, `e4b-sidecar-8041.log` |
| 19.08. 03:07 | `refs/main` zurück auf `deb1db71` | `refs/main` mtime |
| 19.08. 03:07:55 | `recovered` — Brain lädt in 4,5 s | `e4b-watchdog.log`, `brain-20260819-030725.log` |

Der Pin hat also **nicht** gehalten. Er war korrekt gesetzt (`models.json`
`brain-e4b.pinned_revision`, `HF_HUB_OFFLINE=1` in `run.sh`) — aber irgendetwas hat
`refs/main` bewegt, und `run.sh` liest genau diese Datei.

> **Die Falle liegt im Repo, und sie ist scharf.** `sidecars/brain/run.sh:146` gibt im
> FATAL-Text als Reparatur-Hinweis:
> `python -c "from huggingface_hub import snapshot_download as d; d('mlx-community/gemma-4-e4b-it-4bit')"`
> — **ohne `revision=`**. Dieser Befehl löst `main` auf, zieht die **neueste** Revision und
> **überschreibt `refs/main`**. Wer der Fehlermeldung folgt, löst den Ausfall aus, den sie
> beschreibt. Das ist kein Einzelfall: `vault/tracks/TESTPROTOKOLL-video-2026-07-20.md`
> notiert dasselbe Muster schon für e2b („blinder snapshot_download … hatte e2b-refs
> verbogen + 97 .incomplete"). Fix-Vorschlag in `RESULT.md`, Abschnitt „Rate-Stellen".

**Und der Fix ist nicht installierbar.** `mlx-lm` PR
[#1240](https://github.com/ml-explore/mlx-lm/pull/1240) („Fix Gemma 4 sanitize() not
stripping KV projections for shared layers") ist am **04.05.2026** als
`df1d3f3c9a7aae402dcbb8f41d4c36bcc13a50ae` nach `main` gemerged — **nach** dem
letzten Release. Neuester Release ist bis heute (22.08.2026) **v0.31.3 vom 22.04.2026**:
vier Monate Release-Stille. Der Kandidat verlangt also einen **ungereleasten
main-Commit** im Produktiv-Brain.

---

## 3. Der Ablauf

Drei Phasen laufen **ohne** Fenster, drei brauchen eins. `ab-run.sh` weigert sich,
die Reihenfolge zu überspringen (kein `.venv-next` → kein `probe`, kein `preflight`
→ kein Rückweg im State).

```
  ohne Fenster            ANDI-FENSTER (Hoshi ist hirnlos)         nur nach GO
  ─────────────           ────────────────────────────────         ───────────
  status                  probe        (Prod aus, Kandidat an)     flip
  self-test               golden-new   (messen + vergleichen)      unflip
  preflight               restore      (Prod zurück)  <- IMMER
  golden-old
  venv-next
```

Aus einem Worktree heraus gegen das echte Repo:
`HOSHI_AB_REPO_ROOT=/Users/andi/IdeaProjects/Hoshi_0.8 bash tools/modell-ab/ab-run.sh <phase>`

### 3.1 Vorher, bei Tageslicht, ohne Risiko

```bash
bash tools/modell-ab/ab-run.sh status      # refs/main, mlx-lm-Versionen, Ports, RAM
bash tools/modell-ab/ab-run.sh self-test   # beweist die Messmechanik an einer Attrappe
bash tools/modell-ab/ab-run.sh preflight   # Cache prüfen, Rückweg notieren, Index-Diff
bash tools/modell-ab/ab-run.sh golden-old  # Baseline gegen das LAUFENDE Prod-Brain
bash tools/modell-ab/ab-run.sh venv-next   # .venv-next bauen (Live-.venv unangetastet)
```

- `self-test` fährt ein Attrappen-Brain auf `:8097` hoch und lässt den echten
  Golden-Läufer dagegen laufen. Er prüft TTFT-Messung, SSE-Einsammeln und
  Byte-Vergleich — **ohne ein Byte am Prod-Brain**. Hat beim Bau dieses Runbooks
  sofort einen echten Fehler gefunden (Heredoc schluckte die Prompt-Pipe).
- `preflight` schreibt den Rückweg (`orig_ref`) nach `~/.hoshi/run/modell-ab.state`,
  **bevor** irgendetwas passiert.
- `golden-old` schickt 3 Turns an das laufende Brain — normale Requests, kein
  Neustart, frische `sessionId`, `temperature: 0.0`.
- `venv-next` baut `sidecars/brain/.venv-next` aus `requirements-next.txt`
  (mlx-lm auf den Fix-Commit gepinnt, `mlx`/`mlx-metal` 0.32.0) und kopiert
  `mlx_patches/*.py` nach — **pip löscht die bei jeder Installation**.
  Klon statt in-place: `.venv` bleibt bit-genau, wie es ist.

### 3.2 Das Fenster

```bash
bash tools/modell-ab/ab-run.sh probe       # Prod aus, Kandidat auf :8043
bash tools/modell-ab/ab-run.sh golden-new  # messen + gegen die Baseline halten
bash tools/modell-ab/ab-run.sh restore     # Prod zurück   <- IMMER, auch bei Erfolg
```

`probe` ist der Kern des Ganzen:

- stoppt gezielt den `server.py` auf `:8041` (kein `pkill -9` über alles),
- prüft danach **frei+inaktiv ≥ 6 GB** und bricht sonst ab (16-GB-Wand),
- startet den Kandidaten mit **`--model <absoluter Snapshot-Pfad>`**.
  `mlx_lm._download()` nimmt einen existierenden lokalen Pfad 1:1 durch.
  **`refs/main` wird nicht angefasst** — es gibt in dieser Phase gar keinen
  veränderten Zustand, der schiefgehen könnte. Der Rückweg ist „Prozess beenden".
- setzt `HOSHI_E4B_TOUCH_LOOP_S` auf den **Prod-Default 45**, nicht auf 0. Sonst misst
  man einen kalten Kandidaten gegen ein warmgehaltenes Prod-Brain und nennt die
  Differenz „Revision".

Lädt er nicht binnen 120 s, druckt `probe` die letzten 25 Log-Zeilen und nennt
`restore` beim Namen. Ein NO-GO an dieser Stelle ist ein Ergebnis, kein Unfall.

### 3.3 Was „grün" heißt

`golden-new` stellt beide Läufe automatisch gegenüber:

1. **Laden** — `/health loaded:true` binnen 120 s. Sonst hart NO-GO.
2. **Roundtrip** — keine leere Antwort. Leer = WEDGE = FAIL, nicht „Rauschen".
3. **Byte-Vergleich** — greedy (`temperature 0.0`) auf beiden Seiten. Eine Abweichung
   kann dann **nur** aus der Gewichtsänderung kommen. Sie ist kein automatisches
   NO-GO, aber sie **muss gelesen werden**: das ist die 4-bit-Senkung von
   `per_layer_model_projection`, sichtbar gemacht.
4. **TTFT** — Median über 3 Turns. Erwartung: **Rauschen**. 3 Turns tragen keine
   Aussage unter ±10 %; wer mehr will, nimmt `tools/measure-brain-ab.py`
   (s. 3.4). Bei einem Modell, das 1,35 % kleiner ist, wäre alles andere verdächtig.
5. **Erst dann** die Pflichtkür aus dem PREP: `training/eval-baselines.json`
   (Suiten `lora-v0`, `mitgift-base`, je 40 eingefrorene Prompts) blind ALT gegen
   NEU. Drei Turns sind ein Rauchtest, keine Abnahme.

### 3.4 Tiefere Messung (optional, dasselbe Fenster)

`tools/measure-brain-ab.py` existiert bereits und ist der richtige Träger für alles
jenseits des Rauchtests. Zwei Läufe, weil beide Revisionen nie gleichzeitig leben:

```bash
# vor dem Fenster, gegen :8041
python3 tools/measure-brain-ab.py run --config tools/modell-ab/ab-alt.json
# im Fenster, gegen :8043  (Kopie der Config mit base_url/health_url auf 8043)
python3 tools/measure-brain-ab.py run --config tools/modell-ab/ab-neu.json
python3 tools/measure-brain-ab.py analyze --raw <verzeichnis>
```

Beide Configs erben von `tools/measure-brain-ab.example.json`; **nur** `base_url`
und `health_url` unterscheiden sich. Die Arme bleiben identisch, sonst misst man
Sampling statt Revision.

---

## 4. Rückwege (beide bewiesen)

| Aus welcher Phase | Handgriff | Warum er sicher ist |
|---|---|---|
| `probe` / `golden-new` | `bash tools/modell-ab/ab-run.sh restore` | Es wurde nie Zustand verändert. Kandidat-Prozess weg, `refs/main` steht unverändert auf `deb1db71`, `bin/hoshi heal` holt das Prod-Brain. |
| `flip` (nach GO) | `bash tools/modell-ab/ab-run.sh unflip` | Schreibt `deb1db71` **byte-genau, ohne Newline** nach `refs/main` und ruft `bin/hoshi heal`. **Exakt der Handgriff, der am 19.08. um 03:07 funktioniert hat.** |

`write_ref()` prüft nach dem Schreiben auf **genau 40 Bytes** — der Trailing-Newline
ist ein dokumentierter Wiederholungsfehler (`run.sh` und `tools/models-verify.sh`
prüfen beide byte-genau). Kontrolle danach:

```bash
tools/models-verify.sh                              # schneller Zustands-Check
python3 tools/verified_fetch.py verify brain-e4b    # kryptografischer Offline-Beweis
```

---

## 5. Wenn der Zug wirklich kommt: die Rest-Liste zum `flip`

`flip` allein reicht **nicht** — es biegt nur `refs/main`. `run.sh` startet weiter mit
`.venv` (0.31.2) und läuft damit garantiert in den Ladefehler. Vollständig sind:

1. `mv .venv .venv-0.31.2 && mv .venv-next .venv` (Rückweg bleibt liegen)
2. `models.json` → `brain-e4b.pinned_revision` = `475b9088…` **und** die dortige
   `note` nachziehen (sie erklärt heute wörtlich den Pin auf die Alt-Revision)
3. `sidecars/brain/requirements.txt` aus `requirements-next.txt` nachziehen
4. `bin/hoshi heal` + `tools/models-verify.sh` + `pipeline/doctor.sh`
5. **LoRA-Adaptercheck**: `training/lora-v0` wurde gegen den **alten** Export
   trainiert. Adapter separat smoken, nicht mitlaufen lassen.
6. `sidecars/brain/test_server.py` grün — `main` hat seit dem Fix-Commit die
   Tool/Reasoning-Parser-State-Machine neu geschrieben (#1501). Wer statt des
   Minimal-Pins den HEAD nimmt, muss **PATH-B/`tool_grammar`** gezielt smoken,
   nicht nur `/v1/chat` ohne Tools.

---

## 6. Verbotene Abkürzungen

- **Kein** `snapshot_download(...)` ohne `revision=`. Das ist der Auslöser des
  Vorfalls, egal wie plausibel die Fehlermeldung klingt, die ihn vorschlägt.
- **Kein** `pip install --upgrade mlx-lm` im Live-`.venv`. `.venv-next` ist genau
  dafür da. Ein kaputtes Live-venv ist ein totes Brain, kein Experiment.
- **Kein** Parallelbetrieb zweier Brains. 16 GB, ~5,2 GB pro Brain, plus Whisper,
  Piper, Backend. `probe` misst das selbst nach und bricht ab.
- **Kein** `| head` auf einen laufenden SSE-Stream. Der Golden-Läufer liest
  grundsätzlich bis `[DONE]`.
- **Kein** `timeout` in Shell-Schritten — gibt es unter macOS/zsh nicht, leere
  Ergebnisse sind dann Artefakt und keine Messung. Deshalb überall `curl -m`
  und Python-Warteschleifen.
