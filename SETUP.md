# SETUP — Hoshi 0.8 selbst zum Laufen bringen

> Kurzfassung vorweg, ehrlich: Mit dem, was in diesem Repo liegt, bekommst du ein
> laufendes Kotlin-Backend + Frontend + einen **kompletten Voice-Turn** — Brain (Text-
> Generation), STT (Spracherkennung), Speaker-ID (wer spricht), die Knowledge-Bridge
> (Wiki-Suche) und die Sprachausgabe liegen **alle** als Python-Sidecars in diesem Repo,
> jeder mit `bootstrap.sh` + `run.sh`. **Text-Chat, Sprache-rein und Sprache-raus
> funktionieren Ende-zu-Ende, ohne einen einzigen API-Schlüssel.** Die Sprachausgabe ist
> auf einem frischen Klon `say`, das macOS-Bordmittel: kein Schlüssel, kein
> Modell-Download, nur einmalig `sidecars/say/bootstrap.sh`.
>
> Was extern bleibt und bleiben muss: die **Modelle** (Gemma, Whisper, CAM++) und die
> **Wikipedia-Datenbank** — Lizenz- und Größengründe, siehe Schritt 3. Dieses Dokument sagt
> genau, wo die Grenzen verlaufen, statt sie zu verstecken.

**Der kürzeste Weg** — was ein frischer Klon wirklich erlebt, wenn alles gut geht:

```bash
bin/hoshi preflight   # kann diese Maschine Hoshi fahren? (read-only, startet nichts)
bin/hoshi setup       # baut/installiert idempotent alles, was fehlt — Wiederholen immer sicher
bin/hoshi up          # Stack hoch + ehrlicher Status
bin/hoshi voice       # der Beweis: Hoshi spricht wirklich
```

`setup` orchestriert exakt die Schritte, die früher hier zum Abtippen standen
(Gradle-Build → npm → Sidecar-Bootstraps → Modell-Prüfung) und tut beim zweiten
Lauf nur noch das Fehlende — auf einer fertigen Maschine meldet er in unter
einer Sekunde „schon da". Zwei ehrliche Grenzen: **Modelle lädt er nie selbst**
(der Abschluss-Report sagt dir, was fehlt und wo du es holst — Lizenz-Klicks
bei HuggingFace bleiben deine, siehe Schritt 3), und **Piper** bleibt ein
Opt-in (`bin/hoshi setup --with-piper`, GPL-Begründung unten). `--dry-run`
zeigt den Plan, ohne irgendetwas zu tun.

Alles darunter erklärt dieselben Schritte langsam, inklusive der Stellen, an denen sie
schiefgehen — und ist zugleich der Handweg, falls du `setup` nicht magst.

## 0. Für wen das hier ist

Das hier richtet sich an einen **technisch versierten Menschen**, der bereit ist, Kanten
zu akzeptieren — kein Installer, kein Wizard, kein Support-Kanal. Wenn dir das nicht
reicht: warte auf den vollen OSS-Fahrplan (Roadmap in [`vault/00-INDEX.md`](vault/00-INDEX.md)).

## 1. Voraussetzungen

- **Apple-Silicon-Mac** (M1 oder neuer), macOS. Der Brain-Sidecar braucht [MLX](https://github.com/ml-explore/mlx)
  (Apples Metal-Tensor-Framework) — läuft **nicht** in einem Linux-Container, einer VM oder auf x86.
  16 GB RAM ist die untere Kante (eng, aber gemessen machbar mit dem `e2b`-Modell); 24 GB ist komfortabler.
- **JDK 21** — musst du nicht selbst installieren, der Gradle-Wrapper zieht es automatisch (siehe README).
- **Python 3.11+** mit `venv` für den Brain-Sidecar.
- **Node 20+** fürs Frontend.
- Ein **HuggingFace-Account** (für den Modell-Download; einige Modelle brauchen eine
  akzeptierte Lizenz, siehe Schritt 3).
- **Kein API-Schlüssel nötig.** Sprachausgabe, Spracherkennung und Sprachmodell laufen
  vollständig lokal. Ein eigener **OpenAI-API-Key** ist nur dann interessant, wenn du die
  Cloud-Stimme oder die Online-Recherche ausprobieren willst — beides ist opt-in und
  standardmäßig aus.
- Für Piper (die zweite lokale Sprachausgabe) zusätzlich: die Bereitschaft, eine
  **GPL-3.0-Laufzeit** zu installieren und Stimm-Modelle zu laden. Piper ist bewusst
  optional; `say` braucht das alles nicht.

## 2. Backend + Frontend bauen

```bash
git clone <dein-fork-oder-diese-url> hoshi-0.8 && cd hoshi-0.8
./gradlew build                       # alle Kotlin-Module + Tests + ArchUnit-Guards
cd frontend && npm install && npm run build && cd ..
```

## 3. Modelle besorgen

`models.json` im Repo-Root ist das Manifest — seit v2 mit **voller Revision,
Dateiliste, Bytes und SHA-256 je Modell**: welche Modelle, woher (HuggingFace-Repo,
Ollama-Name oder Direct-Download), unter welcher Lizenz. `tools/models-verify.sh`
prüft **read-only** gegen genau diese Pins — lauf es zuerst, bevor du irgendetwas
startest. Zum Beschaffen gibt es den **verifizierten Fetcher** (Hash-Prüfung vor
Aktivierung, Resume, Lizenz-Gates — er akzeptiert Lizenzen NIE stellvertretend
für dich):

```bash
tools/models-verify.sh                          # was fehlt? (read-only)
python3 tools/verified_fetch.py plan            # was würde geholt? (read-only)
python3 tools/verified_fetch.py fetch --accept-license gemma   # holen, Hash-geprüft
```

Was du brauchst (Details + Lizenzen in `models.json`):

| Modell | Rolle | Woher | Pflicht? |
|---|---|---|---|
| `mlx-community/gemma-4-e4b-it-4bit` | Brain (Default seit 0.8.3) | HuggingFace (Gemma-Lizenz — Terms auf HF akzeptieren) | ja |
| `mlx-community/gemma-4-e2b-it-4bit` | Brain (Rückweg, retired) | HuggingFace (Gemma-Lizenz) | nein |
| `mlx-community/whisper-large-v3-turbo` | STT-Modellgewicht | HuggingFace (lazy beim ersten Request, siehe `sidecars/stt`) | ja, für Sprache-als-Eingabe |
| `Wespeaker/wespeaker-voxceleb-campplus` | Speaker-ID-Gewicht | Direct-Download (Apache-2.0, `sidecars/speaker/bootstrap.sh`) | nur fürs Anlernen — die Erkennung ist aus, siehe §8 |
| `embeddinggemma:300m` | Episodic-Memory-Embedding | Ollama (`ollama pull embeddinggemma:300m`) | ja, für Memory |

Modelle werden **nie** in diesem Repo mitgeliefert (Lizenzgründe + Größe) — Download läuft
über den HuggingFace-Cache (`huggingface_hub`) bzw. `ollama pull`, nicht über Git.

## 4. Sidecars bootstrappen

Alle Sidecars folgen demselben Muster: `bootstrap.sh` einmalig (venv + gepinnte
Requirements, ggf. Modell-Download), `run.sh` zum Starten. **Das Bootstrappen ist der
eigentliche Einrichtungsschritt** — starten kann sie danach `bin/hoshi up` für dich
(Abschnitt 5).

```bash
sidecars/say/bootstrap.sh        # :8044 — Sprachausgabe (macOS `say`) ← der Fresh-Clone-Default
sidecars/brain/bootstrap.sh      # :8041 — Text-Generation (Gemma-4/MLX)
sidecars/stt/bootstrap.sh        # :9001 — Sprache → Text (Whisper/MLX)
sidecars/speaker/bootstrap.sh    # :9002 — wer spricht (CAM++/ONNX)
sidecars/knowledge/bootstrap.sh  # :8035 — Wiki-Suche (SQLite-FTS5, DB extern)
```

Alle sind FastAPI-Prozesse mit dokumentiertem HTTP-Vertrag (Details je `README.md` im
jeweiligen Sidecar-Ordner):

- **say-TTS** (`sidecars/say`): der eine Default, wenn nichts gewählt wurde. Ruft
  `/usr/bin/say` + `/usr/bin/afconvert` als Unterprozess — **kein ML-Modell, kein
  Download, kein Schlüssel**. Läuft dafür ausschließlich auf macOS; das prüft
  `bootstrap.sh` vorab und bricht sonst ehrlich ab.
- **Brain** (`sidecars/brain`): `POST /v1/chat`, `POST /v1/score`, `GET /health` — der
  `BrainPort`-Vertrag.
- **STT** (`sidecars/stt`): `POST /asr?...` (Multipart-Audio) → `{"text": "…"}`,
  `GET /health`. Braucht zusätzlich installiertes `ffmpeg` (`brew install ffmpeg`).
- **Speaker-ID** (`sidecars/speaker`): `POST /embed`, `POST /verify`, `GET /health` —
  512-d CAM++-Embeddings, bewusst ohne torch/funasr (ONNX, ~100-130 MB RSS).
  *Ehrlich: die Sprecher-**Erkennung** ist im Backend abgeschaltet (siehe Abschnitt 8) —
  der Sidecar wird gebraucht, seine Antwort aber nicht zur Identifikation benutzt.*
- **Knowledge-Bridge** (`sidecars/knowledge`): `GET /health`, `GET /search`,
  `GET /article/{id}` sowie der versionierte Pack-Vertrag unter `/v1/health`,
  `/v1/manifest` und `/v1/search`. `bootstrap.sh` lädt **keine Datenbank** — `run.sh` bricht ab, wenn
  unter `HOSHI_WIKI_DB_PATH` (Default `~/.hoshi/knowledge/wiki-de/articles.db`) keine
  lesbare Wikipedia-DB liegt. Ohne sie läuft der Rest weiter, Antworten fallen ehrlich auf
  „ohne Wiki-Grounding" zurück. Ein kleiner, rein öffentlicher und vollständig
  verifizierbarer Pack lässt sich mit
  [`tools/knowledge-pack`](tools/knowledge-pack/README.md) bauen; die Auswahl ist
  erst nach dem getrennten [`tools/knowledge-bench`](tools/knowledge-bench/README.md)
  ein Produktkandidat.

Piper (`sidecars/piper`) ist die **optionale** zweite lokale Sprachausgabe. Sein
`bootstrap.sh` lädt Laufzeit und Stimm-Modelle erst nach einer bewussten Entscheidung —
Piper ist GPL-3.0-or-later, Begründung in [`sidecars/piper/LICENSES.md`](sidecars/piper/LICENSES.md).
Erst danach ist `HOSHI_TTS=piper` bzw. die Auswahl im Einstellungs-Panel sinnvoll.

## 5. Stack starten

```bash
bin/hoshi up        # fährt Brain + Sidecars idempotent hoch und zeigt danach den doctor-Status
```

`bin/hoshi up` wählt **automatisch** den Sidecar aus diesem Repo, sobald dessen `.venv`
existiert — also sobald `bootstrap.sh` einmal gelaufen ist. Fehlt das venv, sagt es das in
einer Zeile und **überspringt den Sidecar mit einer Warnung**, statt einen Start
vorzutäuschen; auf einem frischen Klon ohne Bootstrap ist das der Normalfall. Erzwingen
lässt sich die Seite mit `HOSHI_SIDECARS_FROM_REPO=true|false` — mit `true` und fehlendem
venv gibt es einen **lauten Fehler statt eines stillen Rückfalls**, denn ein kaputter Pfad
soll nie scheinbar gesund starten. Ohne laufendes Brain endet `bin/hoshi up` ehrlich mit
Fehlercode; fehlende Nebensidecars degradieren nur.

Für die Sprachausgabe ist das explizit verdrahtet: fehlt `sidecars/say/.venv`, wartet
`bin/hoshi up` nicht zwanzig Sekunden auf einen Prozess, der unmöglich starten kann,
sondern nennt exakt den nächsten Zug — `sidecars/say/bootstrap.sh`. Dasselbe gilt für
`bin/hoshi voice` und `bin/hoshi voicein`. Kein stiller Cloud-Fallback; ein Prüfskript
(`pipeline/test-first-run-tts.sh`) hält genau das fest.

Du kannst jeden Sidecar auch weiterhin einzeln über `sidecars/*/run.sh` starten — das
Backend spricht mit ihnen über dieselben Ports und Verträge, die Kotlin-Adapter kennen
keinen Unterschied (Konfiguration bei Bedarf über `HOSHI_STT_BASE_URL` /
`HOSHI_SPEAKER_BASE_URL`).

Der einzige Pfad, der noch auf einen privaten, unveröffentlichten Vorgänger-Checkout
(`HOSHI_05_ROOT`) zeigt, ist der **abgeschaltete Legacy-Voxtral-Pfad**. Auf einem frischen
Klon wird ein fehlender Sidecar ehrlich übersprungen — Warnung statt Fake-Start.

## 6. Backend starten + verifizieren

```bash
bin/hoshi run       # bootet lokal auf :8090, prüft Health + die Auth-Wand (401 ohne Token)
bin/hoshi turn      # Text-Turn-Beweis: POST /api/v1/chat/stream → SSE-Antwort
bin/hoshi voice     # Audio-Beweis: der Turn wird wirklich gesprochen (echtes WAV)
bin/hoshi voicein   # Eingabe-Beweis: WAV → /api/v1/voice → STT → Turn
bin/hoshi doctor    # ehrlicher, read-only Stack-Status (Brain/Sidecars/RAM — OK/DEGRADED/DOWN)
```

Der Unterschied zwischen `run` und `voice` ist Absicht: **`bin/hoshi run` beweist nur
App-Boot und Auth-Wand, nicht die Hörbarkeit.** `bin/hoshi voice` ist der echte
Audio-Beweis und bricht mit einer konkreten Bootstrap-/Sidecar-Meldung ab, statt einen
stummen Turn als „läuft" zu verkaufen.

`bin/hoshi doctor` sagt dir schwarz auf weiß, welche Sidecars es sieht und welche fehlen —
das ist der schnellste Weg herauszufinden, wo du gerade stehst. Die Gesundheitsanzeige in
der App prüft dazu die **tatsächlich aktive** Sprachausgabe-Engine statt immer derselben:
sonst kann „alles grün" dastehen, während genau die Komponente, die wirklich spricht,
unerreichbar ist.

## 7. Konfiguration

Laufzeit-Flags sind Spring-Properties über `Environment=`-Zeilen bzw. Env-Vars. Die
vollständige, kommentierte Referenz (inkl. aller Sidecar-URLs, Feature-Flags,
Cloud-Opt-ins) ist [`tools/systemd/hoshi-0.8-backend.service`](tools/systemd/hoshi-0.8-backend.service) —
für lokale Entwicklung reichen die Defaults. `HOSHI_API_TOKEN` musst du selbst generieren
(z.B. `openssl rand -hex 32`) und **nie committen**.

## 8. Bekannte Grenzen (Stand 0.8.2)

- **Hoshi antwortet in fünf Sprachen, versteht aber noch nicht in fünf.** Übersetzt sind
  die Antworten (Backend und Oberfläche); die *Erkenner* — was als Befehl durchgeht — sind
  in großen Teilen weiterhin deutsch. Auf Spanisch gestellt bekommst du spanische
  Antworten, musst Befehle aber weiter auf Deutsch oder Englisch sagen. Hoshi weist im
  Sprach-Panel selbst darauf hin; alles außer Deutsch trägt dort ein „Beta".
- **ES/FR/IT sind nicht muttersprachlich gegengelesen.** Es sind echte Übersetzungen, keine
  Platzhalter — aber niemand mit der jeweiligen Muttersprache hat sie geprüft. Für diese
  drei Sprachen gibt es außerdem keine Piper-Stimme; sie werden von `say` gesprochen.
- **Die Sprecher-Erkennung ist abgeschaltet.** Das erste lokale Sicherheits-Gate hat keinen
  tragfähigen Betriebspunkt gefunden (reproduzierte Fehlbindung), deshalb bleibt sie aus.
  Anlernen und Profile funktionieren; erkannt wird niemand. Unbekannte Stimmen werden nie
  automatisch angelernt.
- **Der sanfte Neustart ist konfiguriert, aber nicht bewiesen.** Laufende Gespräche bekommen
  beim Herunterfahren bis zu 20 Sekunden (`server.shutdown=graceful`). Plausibel, aber noch
  nicht gegen ein echtes laufendes Gespräch gemessen.
- **Die Wikipedia-Datenbank ist nicht Teil dieses Repos.** Der Knowledge-Sidecar liegt hier
  (`sidecars/knowledge`), die DB nicht — ohne sie fallen Grounding-Antworten ehrlich auf
  „ohne Wiki-Grounding" zurück (siehe `pipeline/ground.sh`), der Rest läuft weiter.
  Knowledge-Pack v1 ist ein Builder-/Manifest-Vertrag, kein mitgelieferter Korpus.
- Der **Legacy-Voxtral-Pfad** ist gewollt abgeschaltet und als einziger noch nicht aus dem
  privaten Vorgänger-Checkout portiert.
- Dieses Setup ist auf **einer** konkreten Maschine gehärtet (ein Apple-Silicon-Mac);
  Pfade/Ports/Annahmen spiegeln das. Erwarte Anpassungsarbeit auf abweichender Hardware.
- Kein Installer, kein First-Run-Wizard, kein Auto-Update — dieses Dokument *ist* der Weg.
- Support: keiner. Issues/PRs sind willkommen (siehe [`CONTRIBUTING.md`](CONTRIBUTING.md)),
  aber es gibt kein Versprechen auf Antwortzeiten.

---
*Wenn irgendein Schritt hier nicht stimmt: das ist ein Bug in der Doku, kein Naturgesetz —
bitte melden.*
