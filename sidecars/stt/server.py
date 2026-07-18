"""
server.py — Apple-Silicon-optimierter STT-Server für Hoshi (mlx-whisper).
[0.8-Port] 1:1 aus Hoshi_0.5/hoshi-stt-mlx/server.py umgezogen (siehe
sidecars/stt/README.md für das Datei-Mapping). Der Transcribe-Flow (ffmpeg-
Konvertierung, Silence-Gate, mlx_whisper.transcribe-Aufruf) ist UNVERÄNDERT —
Umzug, kein Umbau.

Drop-in-Ersatz für den bestehenden Whisper-Server (localhost:9001).
Nutzt mlx-whisper (Apple MLX Framework) statt OpenAI-Whisper —
deutlich schneller auf M1/M2/M3/M4 durch Neural-Engine-Nutzung.

API-Kompatibilität:
  POST /asr?encode=true&task=transcribe&language=de&output=json
  → {"text": "Transkribierter Text"}

Start:
  sidecars/stt/.venv/bin/python sidecars/stt/server.py --port 9001
  (kanonisch: sidecars/stt/run.sh — setzt venv/Modell-Wahl korrekt)
"""

import argparse
import io
import logging
import subprocess
import tempfile
import wave
from pathlib import Path

import mlx_whisper
import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, Query, UploadFile
from fastapi.responses import JSONResponse

# Silence-Gate (RMS+Dauer) — eigenes Modul OHNE mlx_whisper-Import, damit
# pytest die Gate-Logik isoliert laden kann (STT-SILENCE-GATE).
from silence_gate import is_silent

# ── Logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("hoshi-stt")

# ── Argparse ──────────────────────────────────────────────────────────────────

parser = argparse.ArgumentParser(description="Hoshi MLX-Whisper STT Server")
parser.add_argument("--host", default="0.0.0.0", help="Bind-Adresse (default: 0.0.0.0)")
parser.add_argument("--port", type=int, default=9001, help="Port (default: 9001, wie Whisper-Server)")
parser.add_argument(
    "--model",
    default="mlx-community/whisper-large-v3-turbo",
    help=(
        "MLX-Whisper-Modell von HuggingFace. Empfehlungen:\n"
        "  Schnell:   mlx-community/whisper-small  (~240 MB, ~0.5 s)\n"
        "  Ausgewogen: mlx-community/whisper-medium (~770 MB, ~0.8 s)\n"
        "  Beste Qualität: mlx-community/whisper-large-v3-turbo (~800 MB, ~1 s)\n"
        "  Offline:   ./models/whisper-large-v3-turbo  (lokaler Pfad)"
    ),
)
args, _ = parser.parse_known_args()

# ── FastAPI ───────────────────────────────────────────────────────────────────

app = FastAPI(title="Hoshi MLX-STT", version="1.0.0")


def _convert_to_wav(audio_bytes: bytes, source_mime: str) -> bytes:
    """Konvertiert beliebige Audio-Formate nach WAV via ffmpeg."""
    suffix = ".webm"
    if "ogg" in source_mime:
        suffix = ".ogg"
    elif "wav" in source_mime:
        return audio_bytes  # bereits WAV
    elif "mp4" in source_mime or "m4a" in source_mime:
        suffix = ".mp4"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as src_f:
        src_f.write(audio_bytes)
        src_path = src_f.name

    out_path = src_path + ".wav"
    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", src_path, "-ar", "16000", "-ac", "1", out_path],
            capture_output=True,
        )
        if result.returncode != 0:
            # Iter-11f Andi-Bug: ffmpeg-stderr wurde stillschweigend in
            # capture_output verschluckt → "Exit 183" ohne Kontext. Jetzt
            # mit echter Fehlermeldung im Log + erste Bytes der Input-Datei
            # damit wir erkennen ob das WebM-File defekt ist.
            stderr_tail = result.stderr.decode("utf-8", errors="replace")[-1500:]
            log.error("ffmpeg failed exit=%d on %s (%d bytes input)\nstderr-tail:\n%s",
                      result.returncode, src_path, len(audio_bytes), stderr_tail)
            # Auf disk lassen für Andi-Inspection
            debug_copy = f"/tmp/whisper-fail-{int(__import__('time').time())}{suffix}"
            Path(src_path).rename(debug_copy)
            log.error("input audio preserved at: %s", debug_copy)
            raise RuntimeError(f"ffmpeg returned exit {result.returncode}, see /tmp/whisper-fail-*{suffix}")
        return Path(out_path).read_bytes()
    finally:
        Path(src_path).unlink(missing_ok=True)
        Path(out_path).unlink(missing_ok=True)


def _wav_to_pcm_int16(wav_bytes: bytes):
    """
    Extrahiert int16-Mono-PCM + Sample-Rate aus WAV-Bytes für das Silence-Gate.

    Gibt (pcm_int16, sample_rate) zurück, oder (None, 0) wenn die Bytes nicht
    als 16-bit-PCM-WAV lesbar sind (dann gaten wir defensiv NICHT, sondern
    lassen Whisper laufen).
    """
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            if w.getsampwidth() != 2:
                return None, 0  # kein int16 → Gate überspringen
            sample_rate = w.getframerate()
            frames = w.readframes(w.getnframes())
        pcm = np.frombuffer(frames, dtype=np.int16)
        return pcm, sample_rate
    except (wave.Error, EOFError, ValueError):
        return None, 0


def _transcribe_bytes(wav_bytes: bytes, language: str, task: str) -> dict:
    """Schreibt WAV-Bytes in eine Temp-Datei und transkribiert sie."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        f.write(wav_bytes)
        tmp_path = f.name
    try:
        return mlx_whisper.transcribe(
            tmp_path,
            path_or_hf_repo=args.model,
            language=language,
            task=task,
            verbose=False,
            # Whisper-Härtung gegen geerbte Floskel-Halluzinationen bei leisem
            # Input (HA-Gerät sendet sauberes, aber leises 16k-WAV → Whisper
            # liefert sonst leer/Floskel → no_input/Rotlicht). Alle vier Params
            # sind von mlx_whisper.transcribe akzeptiert (Signatur geprüft):
            #   - temperature=0.0: deterministisch, kein Greedy-Fallback-Drift
            #   - no_speech_threshold=0.6: explizit (= mlx-Default, festgenagelt)
            #   - logprob_threshold=-1.0: explizit (= mlx-Default, festgenagelt)
            #   - condition_on_previous_text=False: KILLT die geerbten Floskel-
            #     Halluzinationen (z.B. ZDF-Untertitel), die sonst aus dem
            #     vorherigen Kontext übernommen werden.
            temperature=0.0,
            no_speech_threshold=0.6,
            logprob_threshold=-1.0,
            condition_on_previous_text=False,
        )
    finally:
        Path(tmp_path).unlink(missing_ok=True)


@app.on_event("startup")
async def startup():
    import asyncio
    log.info("Lade Whisper-Modell: %s", args.model)
    # Warm-up via Stille-WAV (44-Byte minimal WAV-Header + 1600 Nullbytes PCM)
    silence_wav = (
        b"RIFF" + (1636).to_bytes(4, "little") +
        b"WAVEfmt " + (16).to_bytes(4, "little") +
        (1).to_bytes(2, "little") +   # PCM
        (1).to_bytes(2, "little") +   # mono
        (16000).to_bytes(4, "little") + (32000).to_bytes(4, "little") +
        (2).to_bytes(2, "little") + (16).to_bytes(2, "little") +
        b"data" + (1600).to_bytes(4, "little") + b"\x00" * 1600
    )
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, lambda: _transcribe_bytes(silence_wav, "de", "transcribe"))
    log.info("Modell bereit. Server läuft auf %s:%d", args.host, args.port)


@app.post("/asr")
async def transcribe(
    audio_file: UploadFile = File(...),
    task: str = Query(default="transcribe"),
    language: str = Query(default="de"),
    output: str = Query(default="json"),
    encode: bool = Query(default=True),
):
    """
    Haupt-Endpunkt — kompatibel mit WhisperSttClient in Hoshi.

    Query-Parameter werden wie beim Original-Whisper-Server akzeptiert,
    aber nur language + task werden tatsächlich ausgewertet.
    """
    raw = await audio_file.read()
    content_type = audio_file.content_type or "audio/webm"
    log.info("Eingehend: %d Bytes, mime=%s, language=%s", len(raw), content_type, language)

    if len(raw) < 500:
        log.warning("Audio zu kurz (%d B), übersprungen", len(raw))
        return JSONResponse({"text": ""})

    # Konvertierung falls nötig (erst ffmpeg→PCM)
    wav_bytes = _convert_to_wav(raw, content_type) if encode else raw

    # Silence-Gate VOR Whisper (Codex-Fund: 3244B-Warmup-Stille halluziniert
    # "ZDF-Untertitel"). RMS+Dauer-Check auf dem konvertierten int16-PCM —
    # bei Stille direkt {"text": ""} statt teurer Whisper-Halluzination.
    pcm, sample_rate = _wav_to_pcm_int16(wav_bytes)
    if pcm is not None and is_silent(pcm, sample_rate):
        log.info("Silence-Gate: Chunk als Stille erkannt (%d samples @ %d Hz), übersprungen",
                 pcm.size, sample_rate)
        return JSONResponse({"text": ""})

    result = _transcribe_bytes(wav_bytes, language, task)
    text = result.get("text", "").strip()
    log.info("Transkript: %r", text)
    return JSONResponse({"text": text})


@app.get("/health")
def health():
    return {"status": "ok", "model": args.model}


# ── Main ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
