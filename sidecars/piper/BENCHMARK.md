# Piper-Sidecar — lokaler Messbeleg 2026-07-19

## Umgebung

- MacBook Air, Apple M4, 16 GB
- `piper-tts 1.5.0`, `de_DE-thorsten-medium`, native 22.050 Hz
- gleichzeitig lief das Wake-Word-Training und erzeugte Piper-Samples
  (`21300/50000` unmittelbar nach dem finalen Lauf)
- zehn kurze deutsche Hoshi-Sätze über den echten HTTP-Endpunkt; WAV wurde je
  Antwort als PCM16/mono validiert, aber nicht gespeichert

## Behaltener Kandidat

Stdlib-HTTP, ONNX-Arena/Mem-Pattern an, zwei Threads, ein lokaler Mini-Warmup
beim Prozessstart:

| Metrik | Ergebnis |
|---|---:|
| Synthese Median | 247 ms |
| Synthese P95 | 369 ms |
| RTF Median | 0,105 |
| RTF P95 | 0,158 |
| RSS nach 10 Turns | 225,6 MB |
| Peak-RSS | 225,6 MB |

Der Lauf erfüllt damit das vereinbarte `<300 MB`-Budget auch unter der genannten
Parallelbelastung. Er beweist noch **keine** bevorzugte Stimme: Natürlichkeit,
Aussprache und Lautheit brauchen Andis blinde Hörprobe gegen `say` und den
bisherigen TTS-Pfad.

## Verworfene Schnitte

| Variante | Befund | Entscheidung |
|---|---|---|
| FastAPI/Pydantic/Uvicorn, 10 Turns | 302,6 MB RSS | über Budget; Webframework entfernt |
| stdlib, ONNX-Arena aus, 1 Thread | 433,0 MB RSS nach 10 Turns | scheinbar kleiner First-Turn, aber wachsender Prozess; verworfen |
| stdlib, Arena an, 1 Thread | Median 440 ms, RTF 0,191, RSS 189,9 MB | RAM gut, aber unnötig langsamer |
| stdlib, Arena an, 2 Threads, kein Warmup | erster RTF 0,322 | First-Inference-Kosten sichtbar; lokaler Start-Warmup ergänzt |

## Reproduktion

```bash
sidecars/piper/bootstrap.sh
sidecars/piper/run.sh
sidecars/piper/.venv/bin/python sidecars/piper/benchmark.py
```

Die Einzelwerte stehen im ausführenden Terminal/Sidecar-Log. `benchmark.py`
berechnet Median/P95 aus den Response-Headern `X-Hoshi-TTS-Ms` und
`X-Hoshi-Audio-Ms`; `/health` liefert aktuelles und Peak-RSS. Kein Deploy und
kein `HOSHI_TTS`-Flip sind Bestandteil dieses Beweises.
