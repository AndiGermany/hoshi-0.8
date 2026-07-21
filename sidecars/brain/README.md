# sidecars/brain

Hoshis LLM-Sidecar ("Brain") — nimmt Chat-Turns entgegen und liefert die
Antwort als Token-Stream zurück. Spricht das Backend über den hexagonalen
`BrainPort` (`core-domain/src/main/kotlin/de/hoshi/core/port/BrainPort.kt`)
an; die aktive Implementierung ist `adapters-brain/.../MlxBrainAdapter.kt`.

## Was es heute ist

Ein Python/FastAPI-Prozess, der ein Gemma-4-E4B-Modell lokal über
[MLX](https://github.com/ml-explore/mlx) (Apples Tensor-Framework für Apple
Silicon) lädt und über HTTP bedient. `server.py` entstand als Port aus
`Hoshi_0.5/hoshi-llm-optiq/server_e4b.py`, wurde in 0.8 aber weiterentwickelt;
„1:1 unverändert" ist deshalb keine aktuelle Behauptung. Der gemeinsame Kern
bleibt Prompt-Bau, Sampling, Streaming, Prompt-Cache und Sensorik.

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
| `server.py` | aus `hoshi-llm-optiq/server_e4b.py` portiert, danach in 0.8 weiterentwickelt |
| `smoke_sensors.py` | aus `hoshi-llm-optiq/smoke_sensors.py` portiert |
| `requirements.txt` | `hoshi-llm-optiq/requirements.txt`, erweitert um die vollen Kern-Pins aus dem echten 0.5-venv (`pip freeze`) |
| `bootstrap.sh` | neu, nach dem Vorbild-Adapter-Muster aus `hoshi-speaker-id/setup.sh` |
| `run.sh` | `tools/hoshi-e4b-run.sh`, mechanisch gleichwertig aber selbstständig (kein Sourcen von `hoshi-lib.sh`) |

## Pfad-Auflösung und Cutover

`pipeline/stack-lib.sh` wählt den Repo-Sidecar automatisch, sobald dessen
`.venv` existiert. Fehlt es, bleibt der 0.5-Run-Pfad als sichtbarer Rückweg;
`HOSHI_SIDECARS_FROM_REPO=true|false` erzwingt die Auswahl. Der tatsächlich
laufende Prozess ist deshalb am Start-Log/`doctor` zu prüfen, nicht aus diesem
README abzuleiten. Repo- und 0.5-Brain nie parallel starten: Port 8041 und die
16-GB-Wand erlauben genau ein residentes Brain.
