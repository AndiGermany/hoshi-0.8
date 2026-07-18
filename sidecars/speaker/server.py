"""
server.py — Mac-Sidecar für CAM++-Speaker-Embeddings (Port 9002).
[0.8-Port] 1:1 aus Hoshi_0.5/hoshi-speaker-id/server.py umgezogen (siehe
sidecars/speaker/README.md für das Datei-Mapping). Embed-/Verify-Flow ist
UNVERÄNDERT — Umzug, kein Umbau. Einzige Namens-Delta im Code: die
Fehlermeldung bei fehlendem Modell verweist jetzt auf `bootstrap.sh` statt
`setup.sh` (Skript-Umbenennung, s. unten).

Schwester der hoshi-knowledge-bridge: ein Python-Sidecar auf dem Mac, der eine
spezifische Fähigkeit per HTTP exposed (These IV — Mac als AI-Coprocessor). Hier:
Sprecher-Embeddings für Voice-ID/Diarization. Das Backend referenziert in
`VoiceIdProperties.speakerUrl` bereits Port :9002 — also nutzen wir :9002.

Runtime (in PHASE SETUP gewählt): CAM++ als ONNX (onnxruntime + kaldi-native-
fbank), BEWUSST KEIN torch/funasr. RSS ~100-130 MB steady-state — klein genug,
dass e2b-Brain (~3 GB) + Whisper (~2.5 GB) unter der 16-GB-Wand bleiben. Der
Embedding-Core (resample→16k, fbank80+CMN, 512-d L2) lebt in `embedder.py`.

API:
- GET  /health  → {status, model, dim}
- POST /embed    (WAV-bytes, libsndfile-lesbarer Container ODER base64-PCM16-
                 16k-mono; webm/mp4 & Co. ⇒ 422 statt PCM16-Müll) → {embedding: float[], dim}
- POST /verify   ({audio, enrolled:[{speakerId,embedding}], thresholds:{high,low}})
                 → {match: speakerId|null, score, decision: 'known'|'uncertain'|'guest'}
                 2-Schwellen-Cosine (siehe `_decide`).

Embeddings sind L2-normalisiert → Cosine == Dot-Product. Schwellen-Vergleich ist
damit ein simples Skalarprodukt, kein Re-Normalisieren nötig.

Quelle/Vorbild: hoshi-knowledge-bridge/server.py (gleicher FastAPI-/argparse-/
Logging-Stil), tools/hoshi-bridge-run.sh (kanonischer venv-Start).
"""
from __future__ import annotations

import argparse
import base64
import binascii
import io
import logging
import os
import sys
from pathlib import Path
from typing import Optional, Union

import numpy as np
import soundfile as sf
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

from embedder import CamPlusEmbedder, EMBED_DIM, SAMPLE_RATE

# ── Logging ─────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("hoshi-speaker-id")

# ── Argparse ────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Hoshi-Speaker-ID: CAM++-Embedding-Sidecar")
parser.add_argument("--host", default="0.0.0.0", help="Bind-Adresse (default 0.0.0.0)")
parser.add_argument("--port", type=int, default=9002, help="Port (default 9002)")
parser.add_argument(
    "--model-path",
    default=os.environ.get(
        "HOSHI_SPEAKER_MODEL_PATH",
        str(Path(__file__).resolve().parent / "models" / "voxceleb_CAM++.onnx"),
    ),
    help="Pfad zur voxceleb_CAM++.onnx",
)
parser.add_argument(
    "--threads",
    type=int,
    default=int(os.environ.get("HOSHI_SPEAKER_THREADS", "1")),
    help="ONNX intra-op-Threads (default 1 — Cores für Whisper/Brain freihalten)",
)
args, _unknown = parser.parse_known_args()

MODEL_PATH = Path(args.model_path)
if not MODEL_PATH.exists():
    log.error("CAM++-ONNX nicht gefunden: %s (bootstrap.sh gelaufen?)", MODEL_PATH)
    sys.exit(1)

MODEL_NAME = "voxceleb_CAM++ (Wespeaker, CAMPPlus-TSTP, VoxCeleb2, Apache-2.0)"

# ── Embedder einmal laden (Session-Load ~200 ms, danach warm) ────────────────
log.info("lade CAM++-Embedder: %s (%.1f MB, %d intra-threads)",
         MODEL_PATH, MODEL_PATH.stat().st_size / 1e6, args.threads)
embedder = CamPlusEmbedder(str(MODEL_PATH), intra_threads=args.threads)
log.info("CAM++-Embedder bereit — dim=%d, sr=%d", EMBED_DIM, SAMPLE_RATE)


# ── Request/Response-Schemas ─────────────────────────────────────────────────
class EmbedRequest(BaseModel):
    """Audio base64-kodiert in `audio`. Auto-Erkennung (siehe `_decode_audio`):
    WAV-Magic „RIFF" → soundfile; erkannter Nicht-WAV-Container (webm/mp4/ogg/
    flac/… via Magic-Bytes) → soundfile-Versuch, sonst HTTP 422 (NIE stiller
    PCM16-Fallback — 0.226-Bug); ohne erkennbares Magic → roher PCM16LE
    @ `sampleRate` (Satelliten-Vertrag codec:"pcm16")."""
    audio: str                              # base64: WAV-bytes ODER PCM16-LE
    sampleRate: int = SAMPLE_RATE           # nur für rohes PCM relevant (WAV trägt sr selbst)


class EmbedResponse(BaseModel):
    embedding: list[float]
    dim: int


class EnrolledSpeaker(BaseModel):
    speakerId: str
    embedding: list[float]


class Thresholds(BaseModel):
    high: float = 0.80                      # >= high → known (Tau-known aus VK)
    low: float = 0.50                       # <  low  → guest; dazwischen → uncertain


class VerifyRequest(BaseModel):
    audio: str                              # base64: WAV-bytes ODER PCM16-LE (wie /embed)
    sampleRate: int = SAMPLE_RATE
    enrolled: list[EnrolledSpeaker] = []
    thresholds: Thresholds = Thresholds()


class VerifyResponse(BaseModel):
    match: Optional[str]                    # speakerId des best-Treffers ODER null
    score: float                            # Cosine des besten Kandidaten (oder 0.0)
    decision: str                           # 'known' | 'uncertain' | 'guest'


class HealthResponse(BaseModel):
    status: str
    model: str
    dim: int


# ── Audio-Dekodierung ────────────────────────────────────────────────────────
def _sniff_container(raw: bytes) -> Optional[str]:
    """Magic-Byte-Sniffing für bekannte Audio-CONTAINER (≠ WAV, ≠ Roh-PCM).

    Zweck (RCA 2026-07-05, der 0.226-Live-Bug): Browser-MediaRecorder-Audio
    (webm/opus in Chrome, mp4/aac in Safari) landete früher STILL im Roh-PCM16-
    Zweig → Embedding aus Rauschen → alle Scores ~0.226. Erkannte Container
    dürfen deshalb NIE als PCM16 interpretiert werden: entweder liest soundfile
    sie legitim (OGG/FLAC/AIFF/CAF/AU) oder es gibt ein lautes 422.

    Alle Magics sind exakte 3-12-Byte-Präfixe — Kollision mit einem echten
    Roh-PCM16-Stream ist praktisch ausgeschlossen (bewusst KEIN MPEG-Frame-Sync
    0xFFEx: der kollidiert real mit PCM-Sample -1 am Turn-Anfang).
    """
    if len(raw) >= 4 and raw[:4] == b"\x1a\x45\xdf\xa3":
        return "webm/matroska (EBML)"
    if len(raw) >= 12 and raw[4:8] == b"ftyp":
        return "mp4/m4a/mov (ISO-BMFF ftyp)"
    if len(raw) >= 4 and raw[:4] == b"OggS":
        return "ogg (OggS)"
    if len(raw) >= 4 and raw[:4] == b"fLaC":
        return "flac"
    if len(raw) >= 3 and raw[:3] == b"ID3":
        return "mp3 (ID3)"
    if len(raw) >= 12 and raw[:4] == b"FORM" and raw[8:12] in (b"AIFF", b"AIFC"):
        return "aiff"
    if len(raw) >= 4 and raw[:4] == b"caff":
        return "caf"
    if len(raw) >= 4 and raw[:4] == b".snd":
        return "au"
    if len(raw) >= 4 and raw[:4] == b"RF64":
        return "rf64"
    if len(raw) >= 4 and raw[:4] == b"riff":
        return "w64"
    return None


def _decode_audio(audio_b64: str, sample_rate: int) -> tuple[np.ndarray, int]:
    """base64 → (float32-mono-wav, sr).

    Akzeptierte Formen (Vertrag mit dem Backend, gehärtet 2026-07-05):
      1. WAV-Container (beginnt mit „RIFF"…„WAVE") — soundfile liest sr + Daten
         selbst, `sample_rate` wird ignoriert. Robust für 8/16/24/32-bit.
         (FE-Enroll-Pfad: useSpeakerEnroll.ts baut genau so ein PCM16-WAV.)
      2. Erkannter Nicht-WAV-Container (Magic-Bytes, `_sniff_container`) —
         EIN soundfile-Versuch: liest libsndfile ihn (OGG/FLAC/AIFF/CAF/AU),
         wird er legitim akzeptiert; sonst HTTP 422 mit Format-Name
         (webm/opus, mp4/aac … — der frühere stille Müll-Fallback, 0.226-Bug).
      3. Roher PCM16-LE-Stream (KEIN erkennbares Container-Magic) —
         interpretiert als int16-mono @ `sample_rate`, skaliert nach float32
         [-1,1]. Legitimer Live-Aufrufer: der Satellit streamt
         `{codec:"pcm16"}` über /ws/audio (wiki/satellite-contract-0.7.md),
         AudioWebSocketHandler reicht die Turn-Bytes 1:1 an /verify durch.

    WICHTIG: Der soundfile-Versuch läuft NUR bei erkanntem Container-Magic —
    NICHT blind auf allen non-RIFF-Bytes. Beleg (libsndfile 1.2.2, 2026-07-05):
    Roh-PCM16, dessen erstes Sample -1 ist (Bytes FF FF — typischer leiser
    Turn-Anfang), wird vom MP3-Autodetect als MPEG-Frame-Sync missdeutet und
    „erfolgreich" zu 44.1k-Stereo-Müll dekodiert — das würde den Satelliten-
    Vertrag still brechen.

    Der Embedder resampled intern auf 16 kHz, daher ist jede sr erlaubt.
    """
    if not audio_b64:
        raise HTTPException(status_code=422, detail="audio (base64) fehlt/leer")
    try:
        raw = base64.b64decode(audio_b64, validate=True)
    except (binascii.Error, ValueError) as e:
        raise HTTPException(status_code=422, detail=f"audio ist kein gültiges base64: {e}")
    if not raw:
        raise HTTPException(status_code=422, detail="audio dekodiert zu 0 bytes")

    # Form 1: WAV-Container (RIFF…WAVE-Magic). soundfile übernimmt sr/bit-depth.
    if len(raw) >= 12 and raw[:4] == b"RIFF" and raw[8:12] == b"WAVE":
        try:
            wav, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=False)
        except Exception as e:
            raise HTTPException(status_code=422, detail=f"WAV nicht dekodierbar: {e}")
        wav = np.asarray(wav, dtype=np.float32)
        if wav.ndim > 1:
            wav = wav.mean(axis=1)          # Stereo → Mono
        return wav, int(sr)

    # Form 2: erkannter Nicht-WAV-Container → EIN soundfile-Versuch, sonst 422.
    # NIE in den PCM16-Zweig durchfallen lassen (0.226-Bug: webm-Bytes als
    # PCM16 gelesen ⇒ Embedding aus Rauschen ⇒ alle Verify-Scores ~0.226).
    sniffed = _sniff_container(raw)
    if sniffed is not None:
        try:
            wav, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=False)
        except Exception:
            raise HTTPException(
                status_code=422,
                detail=(
                    f"Audio-Format '{sniffed}' wird nicht unterstützt (soundfile/"
                    f"libsndfile kann es nicht lesen) und wird bewusst NICHT als "
                    f"Roh-PCM16 interpretiert (Schutz vor Rausch-Embeddings, "
                    f"0.226-Bug). Unterstützt: WAV (RIFF), von libsndfile lesbare "
                    f"Container (z.B. OGG/FLAC/AIFF), oder roher PCM16-LE mono "
                    f"@ sampleRate ohne Container-Header."
                ),
            )
        wav = np.asarray(wav, dtype=np.float32)
        if wav.ndim > 1:
            wav = wav.mean(axis=1)          # Stereo → Mono
        log.info("audio als '%s' via soundfile dekodiert (sr=%d)", sniffed, int(sr))
        return wav, int(sr)

    # Form 3: roher PCM16-LE @ sample_rate (Satelliten-Vertrag, codec:"pcm16").
    if sample_rate <= 0:
        raise HTTPException(status_code=422, detail="sampleRate muss > 0 sein für rohes PCM16")
    if len(raw) % 2 != 0:
        raise HTTPException(status_code=422, detail="PCM16-Stream hat ungerade Byte-Zahl")
    pcm = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if pcm.size == 0:
        raise HTTPException(status_code=422, detail="PCM16-Stream ist leer")
    return pcm, int(sample_rate)


def _embed_from_request(audio_b64: str, sample_rate: int) -> np.ndarray:
    """Dekodiert Audio und liefert das L2-normalisierte 512-d Embedding."""
    wav, sr = _decode_audio(audio_b64, sample_rate)
    try:
        return embedder.embed(wav, sr)
    except Exception as e:
        log.exception("embed fehlgeschlagen")
        raise HTTPException(status_code=500, detail=f"embedding fehlgeschlagen: {e}")


# ── 2-Schwellen-Entscheidung ─────────────────────────────────────────────────
def _decide(best_score: float, high: float, low: float) -> str:
    """2-Schwellen-Cosine über dem BESTEN Kandidaten-Score:

        best_score >= high   → 'known'      (sicher dieser Sprecher)
        low <= best_score < high → 'uncertain' (Hysterese-Zone: Backend kann
                                   nachfragen / vorsichtig behandeln)
        best_score <  low    → 'guest'      (kein bekannter Sprecher)

    Schwellen kommen aus dem Request (`thresholds.high/low`); Defaults 0.80/0.50.
    Da Embeddings L2-normalisiert sind, ist `score` direkt das Cosine (∈[-1,1]).
    Anti-Inversion: falls high < low übergeben wird, werden sie getauscht, damit
    die Zonen-Logik nie kippt (lieber robust als ein stiller Fehlbescheid).
    """
    hi, lo = (high, low) if high >= low else (low, high)
    if best_score >= hi:
        return "known"
    if best_score >= lo:
        return "uncertain"
    return "guest"


# ── FastAPI-App ──────────────────────────────────────────────────────────────
app = FastAPI(title="hoshi-speaker-id", version="0.1.0")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Billiger Liveness-Check: der Embedder ist beim Start geladen, eine offene
    ONNX-Session beweist Modell + Runtime intakt. Kein Inferenz-Lauf nötig."""
    return HealthResponse(status="ok", model=MODEL_NAME, dim=EMBED_DIM)


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest) -> EmbedResponse:
    """WAV-bytes oder base64-PCM16-16k-mono → L2-normalisiertes 512-d Embedding.

    Das Embedding ist deterministisch (dither=0) und so skaliert, dass Cosine ==
    Dot-Product ist — das Backend kann es direkt als enrolled-Vektor speichern.
    """
    emb = _embed_from_request(req.audio, req.sampleRate)
    return EmbedResponse(embedding=emb.tolist(), dim=int(emb.shape[0]))


@app.post("/verify", response_model=VerifyResponse)
def verify(req: VerifyRequest) -> VerifyResponse:
    """Audio gegen enrolled-Sprecher prüfen, 2-Schwellen-Cosine.

    Ablauf:
      1. Audio → Probe-Embedding (L2-normalisiert).
      2. Cosine (= Dot) gegen JEDEN enrolled-Vektor; bester Kandidat gewinnt.
         Enrolled-Vektoren werden defensiv re-normalisiert (falls ein Caller
         einen un-normalisierten Vektor schickt), sonst verzerrt die Norm das
         Cosine.
      3. `_decide` mappt den Best-Score auf known/uncertain/guest.
         `match` ist die speakerId NUR bei decision=='known' — in der
         uncertain/guest-Zone gibt es bewusst keinen harten Treffer (das Backend
         soll nicht auf einen unsicheren Score hin eine Identität festschreiben).
    """
    probe = _embed_from_request(req.audio, req.sampleRate)

    # Keine enrolled-Sprecher → es kann per Definition niemand bekannt sein.
    if not req.enrolled:
        return VerifyResponse(match=None, score=0.0, decision="guest")

    best_id: Optional[str] = None
    best_score = -1.0
    dim = int(probe.shape[0])
    for sp in req.enrolled:
        vec = np.asarray(sp.embedding, dtype=np.float32)
        if vec.shape[0] != dim:
            # Dim-Mismatch ist ein harter Vertrags-Bruch — laut statt stilles 0.
            raise HTTPException(
                status_code=422,
                detail=(f"enrolled '{sp.speakerId}' hat dim {vec.shape[0]}, "
                        f"erwartet {dim}"),
            )
        n = float(np.linalg.norm(vec))
        if n > 0:
            vec = vec / n
        score = float(np.dot(probe, vec))
        if score > best_score:
            best_score, best_id = score, sp.speakerId

    decision = _decide(best_score, req.thresholds.high, req.thresholds.low)
    # match nur bei sicherem 'known' — sonst null (uncertain/guest binden keine ID).
    match = best_id if decision == "known" else None
    return VerifyResponse(match=match, score=round(best_score, 6), decision=decision)


if __name__ == "__main__":
    import uvicorn
    log.info("hoshi-speaker-id startet auf %s:%d (model=%s, dim=%d)",
             args.host, args.port, MODEL_PATH.name, EMBED_DIM)
    # workers=1: ein Prozess, eine geteilte ONNX-Session (thread-sicher via Lock
    # im Embedder). Mehr Worker würden das Modell N-fach in den RAM laden — gegen
    # die 16-GB-Wand.
    uvicorn.run(app, host=args.host, port=args.port, log_level="info", workers=1)
