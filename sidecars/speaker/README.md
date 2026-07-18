# sidecars/speaker

Hoshis Speaker-ID-Sidecar (CAM++-Embeddings via ONNX) — nimmt Audio entgegen
und liefert ein 512-d Sprecher-Embedding bzw. eine Known/Uncertain/Guest-
Entscheidung zurück. `server.py`/`embedder.py` sind ein 1:1-Umzug aus
`Hoshi_0.5/hoshi-speaker-id/` — Embed-/Verify-/Decode-Flow unverändert. Bis
zum bewiesenen Cutover (siehe unten) bleibt die 0.5-Kopie die laufende
Wahrheit — dies hier ist der portierte, aber noch nicht produktiv geschaltete
Nachbau.

Bewusst **kein torch/funasr**: CAM++ läuft als ONNX (onnxruntime +
kaldi-native-fbank), RSS ~100-130 MB steady-state — klein genug, dass Brain
(~3-5 GB) + Whisper (~2.5 GB) unter der 16-GB-Wand bleiben.

## HTTP-Vertrag (aus server.py abgeleitet)

- `GET /health` → `{"status":"ok","model":"voxceleb_CAM++ (…)","dim":512}`
- `POST /embed` `{"audio":"<base64>","sampleRate":16000}` →
  `{"embedding":[512 floats],"dim":512}` — L2-normalisiert, Cosine == Dot-Product.
- `POST /verify` `{"audio":"<base64>","sampleRate":16000,"enrolled":[{"speakerId":…,"embedding":[…]}],"thresholds":{"high":0.80,"low":0.50}}`
  → `{"match": "<speakerId>"|null, "score": <float>, "decision": "known"|"uncertain"|"guest"}`

`audio` akzeptiert: WAV (RIFF-Magic, robust für 8/16/24/32-bit), jeden von
libsndfile lesbaren Container (OGG/FLAC/AIFF/CAF/AU — erkannt per Magic-Bytes,
sonst HTTP 422 statt stillem PCM16-Fallback, s. `_decode_audio`-Docstring in
`server.py` / der 0.226-Bug), oder rohes PCM16-LE @ `sampleRate` ohne
Container-Header (Satelliten-Vertrag `codec:"pcm16"`).

## Nutzung

```bash
sidecars/speaker/bootstrap.sh   # einmalig: .venv + requirements.txt + CAM++-ONNX-Download
sidecars/speaker/run.sh         # Start (Port 9002, Env-basiert)
```

`bootstrap.sh` lädt das CAM++-ONNX-Modell (`voxceleb_CAM++.onnx`,
Wespeaker/wespeaker-voxceleb-campplus, Apache-2.0) nach `sidecars/speaker/models/`
— Erwartungswerte (URL-Bestandteile, Byte-Größe, SHA256) kommen dabei
**ausschließlich aus `models.json`** (`speaker-campplus`-Eintrag, Repo-Root),
nicht aus einer zweiten, eigenen Kopie der Zahlen im Skript. Größe UND SHA256
werden nach dem Download verifiziert — Mismatch ⇒ Datei wird gelöscht, laut
FATAL statt stiller Teil-Download.

Env-Variablen (`run.sh` + `server.py`):

| Variable | Default | Wirkung |
|---|---|---|
| `HOSHI_SPEAKER_HOST` | `0.0.0.0` | Bind-Adresse |
| `HOSHI_SPEAKER_PORT` | `9002` | Port |
| `HOSHI_SPEAKER_MODEL_PATH` | `sidecars/speaker/models/voxceleb_CAM++.onnx` (dateirelativ) | ONNX-Pfad, von `server.py` selbst gelesen |
| `HOSHI_SPEAKER_THREADS` | `1` | ONNX intra-op-Threads, von `server.py` selbst gelesen |
| `HOSHI_LOG_DIR` | `$HOME/.hoshi/logs` | Log-Ablage |

## Dateien

| Datei | Herkunft (Hoshi_0.5) |
|---|---|
| `server.py` | `hoshi-speaker-id/server.py` (1:1, Docstring-Pfadverweise + 1 Log-Meldung `setup.sh`→`bootstrap.sh`) |
| `embedder.py` | `hoshi-speaker-id/embedder.py` (byte-identisch) |
| `test_decode_audio.py` | `hoshi-speaker-id/test_decode_audio.py` (1:1, Docstring-Pfadverweise) — braucht das geladene Modell (Import von `server` lädt es). |
| `requirements.txt` | `hoshi-speaker-id/requirements.txt`, ersetzt durch die vollen Kern-Pins aus dem echten 0.5-venv (`pip freeze`) |
| `bootstrap.sh` | neu; venv-Teil nach `sidecars/brain/bootstrap.sh`-Muster, Modell-Download 1:1 aus `hoshi-speaker-id/setup.sh` (curl+Byte-Check+SHA256), Erwartungswerte jetzt aus `models.json` gelesen statt hart im Skript kopiert |
| `run.sh` | neu, nach dem Muster aus `sidecars/brain/run.sh` |

Nicht portiert: `setup.sh` (durch `bootstrap.sh` ersetzt), `smoke_test.py`
(referenziert `../tools/voice-picker/tts-shootout/` außerhalb des Sidecar-
Verzeichnisses — in 0.8 nicht vorhanden; echte Adaption statt reinem Umzug,
daher bewusst ausgelassen, kein Verlust an Kernfunktion).

## Cutover-Status

**Die 0.5-Kopie (`Hoshi_0.5/hoshi-speaker-id/server.py`, Port 9002) ist bis
zum bewiesenen Cutover die laufende Wahrheit.** `pipeline/stack-lib.sh`/
`pipeline/up.sh` starten den Speaker-ID-Sidecar heute noch über
`$HOSHI_05_ROOT/tools/hoshi-speakerid-run.sh` — die Umstellung auf dieses
Verzeichnis ist ein eigener, bewusster Schritt (Scheibe 4), nicht Teil dieses
Ports. `models.json`s `speaker-campplus`-Eintrag behält deshalb `local_path`
auf dem 0.5-Speicherort (der von `tools/models-verify.sh` geprüfte Live-Pfad)
und trägt zusätzlich `sidecar_local_path` als Zeiger auf den neuen Ort — bis
Scheibe 4 lauffähig cuttet, existiert das Modell an BEIDEN Orten unabhängig
voneinander (zwei Downloads, kein Symlink). Bis dahin NICHT parallel zum
laufenden 0.5-Speaker-ID starten (Port-Kollision auf 9002).
