# sidecars/piper — optionale lokale TTS-Engine

Piper ist Hoshis CPU-only-Kandidat für schnelle lokale deutsche Sprache. Diese
Scheibe liefert ausschließlich den Sidecar auf Port `8045`; Engine-Wiring,
Supervisor und ein möglicher Default-Flip gehören dem Orchestrator. Der aktuelle
Default bleibt unverändert. Für das Offline-Video ist `sidecars/say` der kleinere,
bereits integrierte Pfad — Piper darf ihn nicht blockieren.

## Ehrlicher Lizenzschnitt

Der Hoshi-Wrapper ist Apache-2.0, die beim Bootstrap separat geladene aktuelle
Piper-Runtime ist **GPL-3.0-or-later**. Das Thorsten-Modell deklariert MIT, sein
Dataset CC0-1.0. Details und Quellen stehen in [LICENSES.md](LICENSES.md), alle
URLs, Revisionen, Größen und SHA-256-Werte in `artifacts.lock.json`.

## HTTP-Vertrag

- `GET /health` → Zustand, Engine, aktive Stimme, Sample-Rate, Peak-RSS
- `GET /voices` → installierte Stimme samt Modell-/Dataset-Lizenz
- `POST /tts` `{"text":"…","voice":"de_DE-thorsten-medium"}` → WAV,
  PCM16 mono mit nativen 22.050 Hz

Antwort-Header `X-Hoshi-TTS-Ms` und `X-Hoshi-Audio-Ms` machen RTF ohne
Audio-Inhaltslogging messbar. Requests werden bewusst seriell inferiert, um den
Peak an Hoshis 16-GB-Wand zu begrenzen. `HOSHI_PIPER_THREADS` steht default auf
`2`; ONNX-Arena und Mem-Pattern-Cache bleiben an, weil ein Gegenlauf ohne Arena
über zehn Turns auf 433 MB anwuchs. Der Mehrturn-Benchmark prüft neben Latenz
deshalb ausdrücklich auch die harte 300-MB-Grenze. Die drei HTTP-Endpunkte
laufen bewusst auf Pythons Standardbibliothek statt FastAPI/Uvicorn: der
Vertrag ist winzig, und ein ganzer Webframework-Stack waere hier unnoetiges RSS.
Ein fester lokaler Mini-Satz wärmt ONNX beim Start vor; kein Nutztext wird dafür
gespeichert oder verwendet.

## Nutzung und Beweise

```bash
sidecars/piper/bootstrap.sh
sidecars/piper/.venv/bin/python sidecars/piper/test_server.py
sidecars/piper/.venv/bin/python sidecars/piper/server.py --selftest
sidecars/piper/run.sh
sidecars/piper/.venv/bin/python sidecars/piper/benchmark.py
```

`bootstrap.sh` unterstützt absichtlich nur macOS/arm64, lädt das offizielle
`piper-tts 1.5.0`-Wheel sowie `de_DE-thorsten-medium` auf unveränderliche Pins,
prüft Größe und SHA-256 und führt eine echte Synthese aus. Contract-Tests laufen
dagegen ohne Modell und ohne Piper-Import über einen Fake an derselben Portnaht.
Der reproduzierbare 10-Satz-Befund einschließlich verworfener Varianten steht in
[BENCHMARK.md](BENCHMARK.md).

Live-Abnahme vor irgendeinem Default-Vorschlag:

1. mindestens zehn kurze deutsche Hoshi-Sätze warm messen (Median/P95 RTF,
   Synthesezeit, Peak-RSS);
2. dieselben Sätze gegen `say` und den bisherigen TTS-Pfad blind anhören;
3. Never-Silent-Ausfall und unbekannte Stimme prüfen;
4. erst danach entscheidet Andi über Engine-Aktivierung. Dieses Paket führt
   weder Deploy noch Config-Flip aus.
