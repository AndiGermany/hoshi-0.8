# sidecars/stt

Hoshis STT-Sidecar (Whisper via Apple MLX) — nimmt Audio entgegen und liefert
das Transkript zurück. `server.py` entstand als Port aus
`Hoshi_0.5/hoshi-stt-mlx/server.py`; der Transcribe-Flow umfasst
ffmpeg-Konvertierung, Silence-Gate und `mlx_whisper.transcribe`. Die
Repo-Fassung ist der gepflegte 0.8-Pfad, während der 0.5-Run-Pfad als
kompatibler Rückweg erhalten bleibt.

## Mac/MLX heute, Plattform-Offenheit für morgen

Wie `sidecars/brain`: **Apple-Silicon-only** (mlx/mlx-whisper haben keine
Intel-/Nicht-Mac-Unterstützung), aber das ist eine Eigenschaft dieser
Implementierung, nicht des Vertrags. Der HTTP-Vertrag:

- `POST /asr?encode=true&task=transcribe&language=de&output=json`
  (Multipart-Feld `audio_file`) → `{"text": "…"}`
- `GET /health` → `{"status":"ok","model":…}`

Jeder Server, der diesen Vertrag erfüllt, ist ein gültiger STT-Adapter.

## Nutzung

```bash
sidecars/stt/bootstrap.sh   # einmalig: .venv anlegen + requirements.txt installieren
sidecars/stt/run.sh         # Start (Port 9001, Env-basiert)
```

Zusätzlich zu den pip-Paketen braucht `_convert_to_wav()` ein installiertes
`ffmpeg` (`brew install ffmpeg`, kein pip-Paket) — `bootstrap.sh` und `run.sh`
prüfen das jeweils und brechen laut ab, wenn es fehlt.

Das Whisper-Modell selbst (Default `mlx-community/whisper-large-v3-turbo`)
kommt über den HuggingFace-Cache, aber nicht mehr implizit beim Warmup:
`run.sh` liest den `stt-whisper`-v2-Lock aus `models.json`, setzt
`HF_HUB_OFFLINE=1` und führt vor jedem Start den vollständigen Offline-Hash
über `tools/verified_fetch.py verify` aus. Fehlt oder driftet der Snapshot, startet
kein Server. Die bewusste Beschaffung ist ein separater Befehl:

```bash
sidecars/stt/.venv/bin/python tools/verified_fetch.py fetch stt-whisper
```

Env-Variablen (`run.sh`):

| Variable | Default | Wirkung |
|---|---|---|
| `HOSHI_STT_HOST` | `0.0.0.0` | Bind-Adresse |
| `HOSHI_STT_PORT` | `9001` | Port |
| `HOSHI_STT_MODEL` | (leer → `models.json`) | darf nur der gelockten `stt-whisper`-Repo-ID entsprechen; andere Werte brechen fail-closed ab |
| `HOSHI_STT_VERIFY_ONLY` | `0` | `1` prüft Runtime, Lock und Vollhash, startet aber weder FastAPI noch Warmup |
| `HOSHI_LOG_DIR` | `$HOME/.hoshi/logs` | Log-Ablage |

## Dateien

| Datei | Herkunft (Hoshi_0.5) |
|---|---|
| `server.py` | aus `hoshi-stt-mlx/server.py` portiert |
| `silence_gate.py` | aus `hoshi-stt-mlx/silence_gate.py` portiert |
| `test_silence_gate.py` | aus `hoshi-stt-mlx/test_silence_gate.py` portiert (pytest, keine mlx_whisper-Abhängigkeit) |
| `requirements.txt` | `hoshi-stt-mlx/requirements.txt`, ersetzt durch die vollen Kern-Pins aus dem echten 0.5-venv (`pip freeze`) |
| `bootstrap.sh` | neu, nach dem Muster aus `sidecars/brain/bootstrap.sh` |
| `run.sh` | neu, nach dem Muster aus `sidecars/brain/run.sh`; v2-Lock-Vollhash + `HF_HUB_OFFLINE=1` vor Warmup |

Nicht portiert: `setup.sh` (durch `bootstrap.sh` ersetzt, siehe oben).

## Pfad-Auflösung und Cutover

`pipeline/up.sh` wählt den Repo-Sidecar automatisch, sobald dessen `.venv`
existiert. Fehlt es, bleibt der 0.5-Run-Pfad als sichtbarer Rückweg;
`HOSHI_SIDECARS_FROM_REPO=true|false` erzwingt eine Seite. Der aktive Pfad ist
am Start-Log/`doctor` zu prüfen. Beide Varianten nie parallel starten
(Port-Kollision auf 9001).
