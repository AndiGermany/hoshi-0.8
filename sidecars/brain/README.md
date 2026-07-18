# sidecars/brain

Hoshis LLM-Sidecar ("Brain") — nimmt Chat-Turns entgegen und liefert die
Antwort als Token-Stream zurück. Spricht das Backend über den hexagonalen
`BrainPort` (`core-domain/src/main/kotlin/de/hoshi/core/port/BrainPort.kt`)
an; die aktive Implementierung ist `adapters-brain/.../MlxBrainAdapter.kt`.

## Was es heute ist

Ein Python/FastAPI-Prozess, der ein Gemma-4-E4B-Modell lokal über
[MLX](https://github.com/ml-explore/mlx) (Apples Tensor-Framework für Apple
Silicon) lädt und über HTTP bedient. `server.py` ist ein 1:1-Umzug aus
`Hoshi_0.5/hoshi-llm-optiq/server_e4b.py` — nur der Dateiname wurde neutral
(Sidecar statt Modell-Codename), der `gen()`-Flow (Prompt-Bau, Sampling,
Streaming, Prompt-Cache, Sensoren) ist unverändert. Bis zum bewiesenen
Cutover (siehe unten) bleibt die 0.5-Kopie die laufende Wahrheit — dies hier
ist der portierte, aber noch nicht produktiv geschaltete Nachbau.

## Mac/MLX heute, Plattform-Offenheit für morgen

Der heutige Server ist **Apple-Silicon-only**: MLX hat keine Intel- oder
Nicht-Mac-Unterstützung (`requirements.txt` pinnt `mlx`/`mlx-lm`/`mlx-metal`
entsprechend). Das ist aber eine Eigenschaft dieser einen Implementierung,
nicht des Vertrags. `BrainPort` kennt nur den HTTP-Vertrag:

- `POST /v1/chat` — Chat-Turn, Antwort als SSE-Stream
  (`data: {"delta":"…"}\n\n` … `data: [DONE]\n\n`)
- `POST /v1/score` — reiner Teacher-Forcing-Prefill (keine Generation), liefert
  Logprobs/Surprisal für Sensor-Zwecke (Entropie, Verhör-Erkennung)
- `GET /health` — `{"status":"ok","model":…,"loaded":true}`

Jeder Server, der diesen Vertrag erfüllt, ist ein gültiger Brain — auf einer
Nicht-Mac-Plattform (Linux+CUDA, Cloud-Endpoint, …) kann dort ein komplett
anderer Prozess stehen, ohne dass Backend oder `BrainPort`-Interface sich
ändern. Diese `requirements.txt`/`server.py` sind der Mac/MLX-Weg, nicht der
einzig mögliche.

## Nutzung

```bash
sidecars/brain/bootstrap.sh   # einmalig: .venv anlegen + requirements.txt installieren
sidecars/brain/run.sh         # Start (Modell-Cache-Guard, HF_HUB_OFFLINE, Port 8041)
```

`bootstrap.sh` installiert nur Python-Pakete — das Gemma-4-E4B-Gewicht selbst
kommt über den HuggingFace-Cache (`huggingface_hub.snapshot_download`), nicht
über pip. `run.sh` bricht laut ab, wenn das Modell nicht vollständig im Cache
liegt, statt still auf einen toten Port zu laufen (Brief-15-Prinzip).

Modell-Wahl: `HOSHI_BRAIN_MODEL=e4b|e2b|12b|<volle HF-Repo-ID>` (Default
`e4b` — siehe Kopf-Kommentar in `run.sh` für die Begründung des Defaults
und die bewusste Divergenz zu `pipeline/stack-lib.sh`s globalem `e2b`-Default).

## Dateien

| Datei | Herkunft (Hoshi_0.5) |
|---|---|
| `server.py` | `hoshi-llm-optiq/server_e4b.py` (1:1, nur Datei-/Pfadnamen) |
| `smoke_sensors.py` | `hoshi-llm-optiq/smoke_sensors.py` (1:1, Import-Umbenennung) |
| `requirements.txt` | `hoshi-llm-optiq/requirements.txt`, erweitert um die vollen Kern-Pins aus dem echten 0.5-venv (`pip freeze`) |
| `bootstrap.sh` | neu, nach dem Vorbild-Adapter-Muster aus `hoshi-speaker-id/setup.sh` |
| `run.sh` | `tools/hoshi-e4b-run.sh`, mechanisch gleichwertig aber selbstständig (kein Sourcen von `hoshi-lib.sh`) |

## Cutover-Status

**Die 0.5-Kopie (`Hoshi_0.5/hoshi-llm-optiq/server_e4b.py`, Port 8041) ist bis
zum bewiesenen Cutover die laufende Wahrheit.** Dieser Sidecar ist der Port,
noch nicht der produktive Ersatz. Bevor `bin/hoshi`/der Supervisor auf dieses
Verzeichnis umgestellt wird, braucht es mindestens: eigenes `.venv` hier
gebootstrapt + smoke-getestet, `pipeline/stack-lib.sh` auf diesen Pfad
umgestellt (statt `Hoshi_0.5/tools/hoshi-e4b-run.sh`), und ein launchd-Template
für den eigenständigen Start. Bis dahin NICHT parallel zum laufenden 0.5-Brain
starten (16-GB-Wand, ein Mac trägt nur ein warmes Modell).
