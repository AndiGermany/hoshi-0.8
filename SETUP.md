# SETUP — Hoshi 0.8 selbst zum Laufen bringen

> Kurzfassung vorweg, ehrlich: Mit dem, was in diesem Repo liegt, bekommst du einen
> laufenden Kotlin-Backend + Frontend + einen **kompletten Voice-Turn** — Brain (Text-
> Generation), STT (Spracherkennung) und Speaker-ID (wer spricht) sind **alle drei** als
> Python-Sidecars in diesem Repo, jeder mit `bootstrap.sh` + `run.sh`. **Text-Chat UND
> Sprache-rein funktionieren Ende-zu-Ende.** Ehrlich bleibt: `bin/hoshi up` bzw.
> `pipeline/stack-lib.sh` starten die Sidecars heute noch über Pfade aus einem privaten
> Vorgänger-Checkout (`Hoshi_0.5`) — der Cutover auf die Sidecars in diesem Repo ist ein
> offener, bewusster Schritt (siehe Abschnitt 5). Für einen frischen Klon ohne `Hoshi_0.5`
> startest du die Sidecars direkt über `sidecars/*/run.sh` — das funktioniert unabhängig
> vom Cutover-Status der Pipeline-Skripte. Dieses Dokument sagt genau, wo die Grenze
> verläuft, statt sie zu verstecken.

## 0. Für wen das hier ist

Release 1 richtet sich an einen **technisch versierten Menschen**, der bereit ist, Kanten
zu akzeptieren — kein Installer, kein Wizard, kein Support-Kanal. Wenn dir das nicht
reicht: warte auf den vollen OSS-Fahrplan (Roadmap in [`docs/vault/00-INDEX.md`](docs/vault/00-INDEX.md)).

## 1. Voraussetzungen

- **Apple-Silicon-Mac** (M1 oder neuer), macOS. Der Brain-Sidecar braucht [MLX](https://github.com/ml-explore/mlx)
  (Apples Metal-Tensor-Framework) — läuft **nicht** in einem Linux-Container, einer VM oder auf x86.
  16 GB RAM ist die untere Kante (eng, aber gemessen machbar mit dem `e2b`-Modell); 24 GB ist komfortabler.
- **JDK 21** — musst du nicht selbst installieren, der Gradle-Wrapper zieht es automatisch (siehe README).
- **Python 3.11+** mit `venv` für den Brain-Sidecar.
- **Node 20+** fürs Frontend.
- Ein **HuggingFace-Account** (für den Modell-Download; einige Modelle brauchen eine
  akzeptierte Lizenz, siehe Schritt 3).
- Optional: ein eigener **OpenAI-API-Key**, wenn du Cloud-TTS (gesprochene Antworten ohne
  lokalen TTS-Sidecar) oder die Online-Eskalation nutzen willst. Ohne Key läuft Hoshi
  lokal-only.

## 2. Backend + Frontend bauen

```bash
git clone <dein-fork-oder-diese-url> hoshi-0.8 && cd hoshi-0.8
./gradlew build                       # alle Kotlin-Module + Tests + ArchUnit-Guards
cd frontend && npm install && npm run build && cd ..
```

## 3. Modelle besorgen

`models.json` im Repo-Root ist das Manifest: welche Modelle, woher (HuggingFace-Repo,
Ollama-Name oder Direct-Download), unter welcher Lizenz, welche Dateien erwartet werden.
`tools/models-verify.sh` prüft **read-only**, ob dein lokaler Cache vollständig ist —
lauf es zuerst, bevor du irgendetwas startest:

```bash
tools/models-verify.sh
```

Was du brauchst (Details + Lizenzen in `models.json`):

| Modell | Rolle | Woher | Pflicht? |
|---|---|---|---|
| `mlx-community/gemma-4-e2b-it-4bit` | Brain (Default) | HuggingFace (Gemma-Lizenz — Terms auf HF akzeptieren) | ja |
| `mlx-community/gemma-4-e4b-it-4bit` | Brain (Alt/Komfort) | HuggingFace (Gemma-Lizenz) | nein |
| `mlx-community/whisper-large-v3-turbo` | STT-Modellgewicht | HuggingFace (lazy beim ersten Request, siehe `sidecars/stt`) | ja, für Sprache-als-Eingabe |
| `Wespeaker/wespeaker-voxceleb-campplus` | Speaker-ID-Gewicht | Direct-Download (Apache-2.0, `sidecars/speaker/bootstrap.sh`) | ja, für Sprecher-Erkennung |
| `embeddinggemma:300m` | Episodic-Memory-Embedding | Ollama (`ollama pull embeddinggemma:300m`) | ja, für Memory |

Modelle werden **nie** in diesem Repo mitgeliefert (Lizenzgründe + Größe) — Download läuft
über den HuggingFace-Cache (`huggingface_hub`) bzw. `ollama pull`, nicht über Git.

## 4. Sidecars starten (Brain, STT, Speaker-ID)

Alle drei Voice-Turn-Sidecars folgen demselben Muster: `bootstrap.sh` einmalig (venv +
Requirements, ggf. Modell-Download), `run.sh` zum Starten.

```bash
sidecars/brain/bootstrap.sh     && sidecars/brain/run.sh     # :8041 — Text-Generation (Gemma-4/MLX)
sidecars/stt/bootstrap.sh       && sidecars/stt/run.sh       # :9001 — Sprache → Text (Whisper/MLX)
sidecars/speaker/bootstrap.sh   && sidecars/speaker/run.sh   # :9002 — wer spricht (CAM++/ONNX)
```

Alle drei sind FastAPI-Prozesse, die einen dokumentierten HTTP-Vertrag sprechen (Details
je `README.md` im jeweiligen Sidecar-Ordner):

- **Brain** (`sidecars/brain`): `POST /v1/chat`, `POST /v1/score`, `GET /health` — der
  `BrainPort`-Vertrag.
- **STT** (`sidecars/stt`): `POST /asr?...` (Multipart-Audio) → `{"text": "…"}`,
  `GET /health`. Braucht zusätzlich installiertes `ffmpeg` (`brew install ffmpeg`).
- **Speaker-ID** (`sidecars/speaker`): `POST /embed`, `POST /verify`, `GET /health` —
  512-d CAM++-Embeddings, bewusst ohne torch/funasr (ONNX, ~100-130 MB RSS).

Mit allen drei laufend + Backend + Frontend hast du einen **vollständigen Voice-Turn**:
`bin/hoshi turn` beweist den Text-Pfad, `bin/hoshi voicein` beweist den gesprochenen
Eingabe-Pfad (WAV → `/api/v1/voice` → STT → Turn) gegen die echte App.

## 5. Ehrlich bleibt: der Pipeline-Cutover ist offen

Die Sidecars in `sidecars/` sind 1:1-Portierungen aus einem privaten, unveröffentlichten
Vorgänger-Checkout (`Hoshi_0.5`) — Logik unverändert, nur Bootstrap/Run neu gebaut (siehe
`Cutover-Status` in `sidecars/stt/README.md` und `sidecars/speaker/README.md`). Was noch
NICHT passiert ist: `bin/hoshi up` und `pipeline/stack-lib.sh` selbst starten die Sidecars
weiterhin über `$HOSHI_05_ROOT`-Pfade aus diesem alten Checkout, nicht über
`sidecars/*/run.sh` in diesem Repo. Das ist die "Scheibe 4" aus der internen Roadmap —
ein eigener, bewusster Schritt, kein versteckter Blocker.

**Was das konkret bedeutet:**

- **Wenn du dieses Repo frisch klonst** (kein `Hoshi_0.5`-Checkout vorhanden), ignorierst
  du `bin/hoshi up` für die Sidecars und startest sie direkt wie in Abschnitt 4 gezeigt —
  das funktioniert unabhängig vom Cutover-Status, da `sidecars/*/run.sh` keine Abhängigkeit
  auf `HOSHI_05_ROOT` hat.
- **`bin/hoshi up` versucht weiterhin**, die Sidecars best-effort über `$HOSHI_05_ROOT` zu
  starten; findet es keinen konfigurierten Checkout, überspringt es sie **still, mit
  Warnung**, und der Java-Teil startet trotzdem. Kein Absturz, aber auch keine
  automatisch gestarteten Sidecars — starte sie in dem Fall manuell (Abschnitt 4).
- Sobald die Sidecars manuell laufen (egal ob über `sidecars/*/run.sh` oder eine eigene
  Implementierung), spricht das Backend mit ihnen über dieselben Ports/Verträge — die
  Kotlin-Adapter (`adapters-stt/.../WhisperSttAdapter.kt`,
  `adapters-speaker/.../CamppSpeakerAdapter.kt`) kennen keinen Unterschied. Konfiguration
  bei Bedarf über `HOSHI_STT_BASE_URL` / `HOSHI_SPEAKER_BASE_URL`.
- **Gesprochene Antworten (Text-zu-Sprache)** gehen zusätzlich, wenn du einen eigenen
  OpenAI-Key setzt (`OpenAiTtsAdapter`, Cloud, opt-in) — kein lokaler TTS-Sidecar nötig
  dafür. `bin/hoshi tts-openai` beweist das isoliert.

## 6. Backend starten + verifizieren

```bash
bin/hoshi run       # bootet lokal auf :8090, prüft Health + die Auth-Wand (401 ohne Token)
bin/hoshi turn       # Text-Turn-Beweis: POST /api/v1/chat/stream → SSE-Antwort
bin/hoshi doctor     # ehrlicher, read-only Stack-Status (Brain/Sidecars/RAM — OK/DEGRADED/DOWN)
```

`bin/hoshi doctor` sagt dir schwarz auf weiß, welche Sidecars es sieht und welche fehlen —
das ist der schnellste Weg herauszufinden, wo du gerade stehst.

## 7. Konfiguration

Laufzeit-Flags sind Spring-Properties über `Environment=`-Zeilen bzw. Env-Vars. Die
vollständige, kommentierte Referenz (inkl. aller Sidecar-URLs, Feature-Flags,
Cloud-Opt-ins) ist [`tools/systemd/hoshi-0.8-backend.service`](tools/systemd/hoshi-0.8-backend.service) —
für lokale Entwicklung reichen die Defaults. `HOSHI_API_TOKEN` musst du selbst generieren
(z.B. `openssl rand -hex 32`) und **nie committen**.

## 8. Bekannte Grenzen (Stand dieses Exports)

- Pipeline-Cutover (`bin/hoshi up` startet Sidecars noch über `$HOSHI_05_ROOT`): siehe
  §5 — offen. Manueller Start über `sidecars/*/run.sh` umgeht das vollständig.
- Knowledge-Bridge/Wiki-RAG (`:8035`, `HOSHI_KNOWLEDGE_BRIDGE_URL`) ist kein Bestandteil
  dieses Exports — Grounding-Antworten fallen ohne sie ehrlich auf "ohne Wiki-Grounding"
  zurück (siehe `pipeline/ground.sh`), der Rest der Pipeline läuft trotzdem.
- Dieses Setup ist auf **einer** konkreten Maschine gehärtet (ein Apple-Silicon-Mac);
  Pfade/Ports/Annahmen spiegeln das. Erwarte Anpassungsarbeit auf abweichender Hardware.
- Kein Installer, kein First-Run-Wizard, kein Auto-Update — dieses Dokument *ist* der Weg.
- Support: keiner. Issues/PRs sind willkommen (siehe [`CONTRIBUTING.md`](CONTRIBUTING.md)),
  aber es gibt kein Versprechen auf Antwortzeiten.

---
*Wenn irgendein Schritt hier nicht stimmt: das ist ein Bug in der Doku, kein Naturgesetz —
bitte melden.*
