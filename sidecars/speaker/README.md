# sidecars/speaker

Hoshis Speaker-ID-Sidecar (CAM++-Embeddings via ONNX) — nimmt Audio entgegen
und liefert ein 512-d Sprecher-Embedding bzw. eine Known/Uncertain/Guest-
Entscheidung zurück. `server.py`/`embedder.py` entstanden als Port aus
`Hoshi_0.5/hoshi-speaker-id/`; der Repo-Sidecar ist der gepflegte 0.8-Pfad,
der 0.5-Run-Pfad bleibt als kompatibler Rückweg erhalten.

Wichtig: Ein gesunder Sidecar beweist nur, dass Embedding/Verify erreichbar
sind. Die nutzerseitige Sprechererkennung ist ein separates Backend-Flag und
ist nach einem reproduzierten Cross-Bind derzeit **OFF**. Profile und Enroll-
Rand können vorhanden sein, ohne dass Voice-Turns eine Identität zugewiesen
bekommen.

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
| `server.py` | aus `hoshi-speaker-id/server.py` portiert und in 0.8 gepflegt |
| `embedder.py` | aus `hoshi-speaker-id/embedder.py` portiert |
| `test_decode_audio.py` | aus `hoshi-speaker-id/test_decode_audio.py` portiert — braucht das geladene Modell (Import von `server` lädt es). |
| `requirements.txt` | `hoshi-speaker-id/requirements.txt`, ersetzt durch die vollen Kern-Pins aus dem echten 0.5-venv (`pip freeze`) |
| `bootstrap.sh` | neu; venv-Teil nach `sidecars/brain/bootstrap.sh`-Muster, Modell-Download 1:1 aus `hoshi-speaker-id/setup.sh` (curl+Byte-Check+SHA256), Erwartungswerte jetzt aus `models.json` gelesen statt hart im Skript kopiert |
| `run.sh` | neu, nach dem Muster aus `sidecars/brain/run.sh` |

Nicht portiert: `setup.sh` (durch `bootstrap.sh` ersetzt), `smoke_test.py`
(referenziert `../tools/voice-picker/tts-shootout/` außerhalb des Sidecar-
Verzeichnisses — in 0.8 nicht vorhanden; echte Adaption statt reinem Umzug,
daher bewusst ausgelassen, kein Verlust an Kernfunktion).

## Pfad-Auflösung und Cutover

`pipeline/up.sh` wählt den Repo-Sidecar automatisch, sobald dessen `.venv`
existiert. Fehlt es, bleibt der 0.5-Run-Pfad als sichtbarer Rückweg;
`HOSHI_SIDECARS_FROM_REPO=true|false` erzwingt eine Seite. `models.json`
führt weiterhin beide Modellpfade. Der aktive Prozess ist am Start-Log/
`doctor` zu prüfen; beide Varianten nie parallel starten (Port-Kollision auf
9002). Ein Prozess-Cutover ändert das Recognition-Flag nicht und ist kein
Freigabebeweis für Identitätszuweisung.
