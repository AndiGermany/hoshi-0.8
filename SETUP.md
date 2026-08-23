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
> „Kompletter Voice-Turn" heißt hier: **im Browser auf diesem Mac**, und genau das ist aus
> diesem Repo beweisbar (`bin/hoshi voice` / `voicein`). Der **Hardware-Satellit** mit
> Wake-Word im Raum ist ein eigener, optionaler Rand mit eigener Firmware — Abschnitt 9.
>
> Was extern bleibt und bleiben muss: die **Modelle** (Gemma, Whisper, CAM++), die
> **Wikipedia-Datenbank** (Lizenz- und Größengründe, siehe Schritt 3) und dein **Zuhause**
> selbst — Räume und Geräte gehören Home Assistant, nicht Hoshi (Abschnitt 8). Dieses
> Dokument sagt genau, wo die Grenzen verlaufen, statt sie zu verstecken.

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

**Welche Version habe ich da?** Die eine maschinenlesbare Wahrheit ist die Zeile
`version=` in [`gradle.properties`](gradle.properties); `bin/hoshi help` zeigt genau
diese Nummer im Kopf seines Banners, und [`CHANGELOG.md`](CHANGELOG.md) erzählt, was in
den Releases wirklich passiert ist. Einen `--version`-Schalter gibt es bewusst nicht —
dieses Dokument behauptet lieber gar keine Zahl als eine handgepflegte, die altert.

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
| `mlx-community/gemma-4-e4b-it-4bit` | Brain (Default — `models.json` `required=true`) | HuggingFace (Gemma-Lizenz — Terms auf HF akzeptieren) | ja |
| `mlx-community/gemma-4-e2b-it-4bit` | Brain (Rückweg, retired) | HuggingFace (Gemma-Lizenz) | nein |
| `mlx-community/whisper-large-v3-turbo` | STT-Modellgewicht | HuggingFace (lazy beim ersten Request, siehe `sidecars/stt`) | ja, für Sprache-als-Eingabe |
| `Wespeaker/wespeaker-voxceleb-campplus` | Speaker-ID-Gewicht | Direct-Download (Apache-2.0, `sidecars/speaker/bootstrap.sh`) | nur fürs Anlernen — die Erkennung ist aus, siehe §11 |
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
  *Ehrlich: die Sprecher-**Erkennung** ist im Backend abgeschaltet (siehe Abschnitt 11) —
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

Ehrlich zum Rückweg: `pipeline/stack-lib.sh` kennt für Brain, STT und Knowledge noch einen
Fallback auf einen privaten, unveröffentlichten Vorgänger-Checkout (`HOSHI_05_ROOT`) — er
greift nur, wenn das Repo-venv fehlt, und auf einem frischen Klon existiert dieser Pfad
schlicht nicht: dann wird der Sidecar ehrlich übersprungen (Warnung statt Fake-Start). Der
**Legacy-Voxtral-Pfad** ist darüber hinaus bewusst ganz abgeschaltet.

## 6. Backend starten + verifizieren

```bash
bin/hoshi run       # bootet lokal auf :8090, prüft Health + die Auth-Wand (401 ohne Token)
bin/hoshi turn      # Text-Turn-Beweis: POST /api/v1/chat/stream → SSE-Antwort
bin/hoshi voice     # Audio-Beweis: der Turn wird wirklich gesprochen (echtes WAV)
bin/hoshi voicein   # Eingabe-Beweis: WAV → /api/v1/voice → STT → Turn
bin/hoshi doctor    # ehrlicher, read-only Stack-Status (Brain/Sidecars/RAM — OK/DEGRADED/DOWN)
bin/hoshi ha check  # nur wenn du Home Assistant anbinden willst: der HA-Rand, read-only (§8)
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

## 8. Home Assistant verbinden (optional)

Bis hierher hat Hoshi geredet, zugehört und nachgeschlagen — aber nichts im Haus angefasst.
Dafür braucht er **Home Assistant**. Das ist ein *externer* Rand: HA gehört dir, läuft in
deinem Netz, und Hoshi bringt es weder mit noch installiert er es.

**Zuerst das Wichtigste: die Decke ist zu.** `HOSHI_HA_ENABLED` ist **`false` per Default**.
Solange dieses Flag aus ist, baut das Backend gar keinen HA-Adapter, sondern den ehrlichen
Platzhalter — Hoshi sagt dann, dass er es nicht kann, statt so zu tun. **Adresse und Token
allein schalten HA nicht ein**; erst der bewusste Flip auf `true` verdrahtet den echten Rand.
Zweite Decke daneben: ohne `HOSHI_TOOLS_ENABLED=true` klassifiziert der Turn nie einen
Haus-Befehl, es erreicht also ohnehin kein Aufruf den HA-Port.

**Adresse.** `HOSHI_HA_BASE_URL`, Default `http://homeassistant.local:8123` (der
mDNS-Standardhost von HA). Wenn deine HA anders heißt oder auf einer festen IP liegt, ist das
die eine Zeile, die du setzt.

**Token.** Ein *Long-Lived Access Token* aus deinem HA-Profil. Er gehört **nie ins Repo**.
Die Präzedenz im Code ist genau diese — die erste Quelle, die etwas liefert, gewinnt:

1. Umgebungsvariable **`HOSHI_HA_TOKEN`**
2. sonst der Schlüssel `"ha"` aus **`~/.hoshi/secrets.json`** (chmod 600)

Fehlt beides bei `HOSHI_HA_ENABLED=true`, bleibt es bewusst beim Platzhalter statt bei einem
Adapter, der nur scheitern könnte. Beim systemd-Deploy kommt die Env-Zufuhr ausschließlich aus
`/etc/hoshi-0.8/secrets.env` (root-only) — die kommentierte Referenz aller Zeilen ist
[`tools/systemd/hoshi-0.8-backend.service`](tools/systemd/hoshi-0.8-backend.service).

**Verifizieren, bevor du irgendetwas anschaltest:**

```bash
bin/hoshi ha check   # READ-ONLY: erreichbar? Token gültig? Areas lesbar? States frisch?
```

Der Befehl liest Adresse und Token aus denselben zwei Quellen wie das Backend und macht vier
Proben gegen deine echte HA — er **schreibt nie** (keine Schaltbefehle, keine Registry-Writes)
und **gibt den Token nie aus**. Jede Probe steht mit ✓/✗ und einem Satz da; Exit-Code 0 gibt es
nur, wenn alle vier grün sind. Ein abgelehnter Token wird ausdrücklich von „nicht erreichbar"
unterschieden — genau diese beiden Fälle verwechselt man sonst jedes Mal.

**Räume.** Der Raumkatalog ist standardmäßig eine statische Liste
(`HOSHI_AREAS_DYNAMIC_ENABLED=false`). Mit `true` liest Hoshi die **echten HA-Areas** read-only
über den Template-Endpoint (gecacht, never-throw: fällt HA aus, gilt der letzte gute Stand,
nie ein leerer Katalog) — dann kennt „schalte das Schlafzimmer ein" alle deine Räume statt nur
der eingebauten. `bin/hoshi ha check` sagt dir vorher, wie viele Areas dabei herauskämen.

**Wem was gehört.** Räume, Geräte und ihre Zuordnung sind **HA-Zustand**, nicht Hoshi-Zustand.
Was Hoshi unter `ha/last-known-states.json` ablegt, ist ein **Cache** für ehrliche Antworten,
wenn HA gerade nicht antwortet — **kein Backup deines Zuhauses**. Wiederherstellen musst du
Räume immer in HA.

**Was schreibend bleibt.** Echte Haus-Aktionen laufen durch den default-deny CapabilityKernel;
sie sind kein Installationsbeweis und gehören nicht in einen Setup-Durchlauf. Erst lesen,
dann — wenn du willst — schalten.

## 9. Sprach-Satellit (optional)

Das hörbare „Hey Hoshi" im Raum kommt **nicht** aus diesem Repo. Die Hardware-Seite ist ein
eigenes, öffentliches Repo: **[`AndiGermany/hoshi-satellite`](https://github.com/AndiGermany/hoshi-satellite)**
— eigene ESPHome-Firmware für einen ESP32-S3-Satelliten, LED-Ring als Sprache, Wake-Word
on-device.

Die ehrlichen Grenzen dieses Randes:

- **Firmware und Wake-Word liegen dort, nicht hier.** Bauen und Flashen ist ein
  Hardware-Schritt an deinem Gerät — dieses Setup kann ihn weder ausführen noch prüfen.
- **Der Mac bleibt der Sprach-Host.** Der Satellit nimmt auf und gibt aus; Erkennung, Modell
  und Stimme laufen weiter auf dem Apple-Silicon-Mac aus Abschnitt 1.
- **Die Naht ist `/ws/audio`** — authentifiziert. API-Token und TLS-Einstellung müssen auf
  beiden Seiten übereinstimmen; welcher Wert das ist, steht in keiner Anleitung, sondern nur
  in deiner Konfiguration.
- **Reihenfolge der Beweise:** erst der Browser-Voice-Turn (`bin/hoshi voice`) als
  Software-Beweis, dann der Satellit als eigener Hardware-Beweis. Wer mit der Hardware
  anfängt, debuggt zwei unbewiesene Dinge gleichzeitig.

## 10. Sichern und Wiederherstellen

Ehrlich und kurz: **es gibt noch kein integriertes Backup/Restore.** Kein `bin/hoshi backup`,
kein Restore-Pfad, keine gemessene Wiederherstellung. Was es gibt, ist die vorbereitende
Arbeit daran — welche Stores es überhaupt gibt, wer sie schreibt und welche Grenzen dabei
gelten, steht in [`docs/tsugi/`](docs/tsugi/README.md).

Bis dahin gilt: **kopiere keine laufenden SQLite-Dateien blind weg.** Ein Dateikopie-„Backup"
einer offenen Datenbank sieht aus wie eine Sicherung und ist keine. Und was in Home Assistant
lebt (Räume, Geräte, Zuordnungen), sichert HA — nicht Hoshi.

## 11. Grenzen des aktuellen Repo-Stands

- **Hoshi antwortet in fünf Sprachen, versteht aber noch nicht in allen fünf gleich gut.**
  Übersetzt sind die Antworten (Backend und Oberfläche). Bei den *Erkennern* — was als Befehl
  durchgeht — ist das Bild gemischt: der Haus-/Komplexitäts-Klassifizierer trägt eigene
  Wortlisten für **Deutsch und Englisch**, ES/FR/IT laufen dort bewusst weiter auf dem
  deutschen Set (`IntentClassifier`); mehrere Fastpaths (Radio, Notizen, schwache Domänen)
  erkennen dagegen bereits in allen fünf. Auf Spanisch gestellt bekommst du also spanische
  Antworten, für Haus-Befehle bleibt aber Deutsch oder Englisch der sichere Weg. Hoshi weist
  im Sprach-Panel selbst darauf hin; alles außer Deutsch trägt dort ein „Beta".
- **ES/FR/IT sind nicht muttersprachlich gegengelesen.** Es sind echte Übersetzungen, keine
  Platzhalter — aber niemand mit der jeweiligen Muttersprache hat sie geprüft. Für diese
  drei Sprachen gibt es außerdem keine Piper-Stimme; sie werden von `say` gesprochen.
- **Die Sprecher-Erkennung ist abgeschaltet.** Das erste lokale Sicherheits-Gate hat keinen
  tragfähigen Betriebspunkt gefunden (reproduzierte Fehlbindung), deshalb bleibt sie aus.
  Anlernen und Profile funktionieren; erkannt wird niemand. Unbekannte Stimmen werden nie
  automatisch angelernt.
- **Dein Zuhause ist ein externer Rand — und ab Werk aus.** `HOSHI_HA_ENABLED` ist `false`;
  ohne Home Assistant, eigenen Token und bewussten Flip schaltet Hoshi nichts (Abschnitt 8).
  Ebenso ist der Sprach-Satellit ein separates Repo mit eigener Firmware (Abschnitt 9).
- **Kein Backup/Restore.** Es gibt keinen eingebauten Weg, Hoshis Zustand zu sichern oder
  zurückzuspielen (Abschnitt 10).
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
