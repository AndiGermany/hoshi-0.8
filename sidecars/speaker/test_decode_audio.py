"""
test_decode_audio.py — Regressionstests für die gehärtete `_decode_audio`
[0.8-Port] 1:1 aus Hoshi_0.5/hoshi-speaker-id/test_decode_audio.py umgezogen
(siehe sidecars/speaker/README.md). Testlogik UNVERÄNDERT — nur der
Ausführungspfad in der Docstring unten folgt dem neuen Speicherort, und
`setup.sh` wurde durch `bootstrap.sh` ersetzt (Skript-Umbenennung).

(RCA 2026-07-05: webm/opus-Bytes fielen still in den Roh-PCM16-Zweig ⇒
Embedding aus Rauschen ⇒ alle Verify-Scores ~0.226).

Vertrag, der hier festgenagelt wird:
  1. WAV (RIFF)                       → 200, 512-dim (Enroll-Pfad, unverändert)
  2. webm/mp4/erkannter Container,
     den libsndfile NICHT liest       → 422 mit Format-Name (statt PCM16-Müll)
  3. OGG (libsndfile liest es)        → 200, 512-dim (legitim via soundfile)
  4. Roh-PCM16 ohne Container-Magic   → 200, 512-dim (Satelliten-Vertrag
     codec:"pcm16", wiki/satellite-contract-0.7.md) — auch wenn das erste
     Sample -1 ist (Bytes FF FF, würde vom libsndfile-MP3-Autodetect als
     MPEG-Frame-Sync missdeutet, wenn man soundfile blind laufen ließe).

Ausführen (pytest ist im Sidecar-venv NICHT installiert — bewusst kein
Install; das File ist pytest-kompatibel UND standalone lauffähig):

    sidecars/speaker/.venv/bin/python sidecars/speaker/test_decode_audio.py

Import von `server` lädt das echte CAM++-ONNX (~200 ms) — Modell muss via
bootstrap.sh vorhanden sein (models/voxceleb_CAM++.onnx).
"""
from __future__ import annotations

import base64
import io
import sys

import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient

import server  # lädt Embedder + App (Modell muss vorhanden sein)

client = TestClient(server.app)

EMBED_DIM = 512
SR = 16000


# ── Test-Audio-Fabriken ──────────────────────────────────────────────────────

def _speechish_float(seconds: float = 1.5) -> np.ndarray:
    """Deterministisches sprach-ähnliches Signal (Sinus-Mix + Hüllkurve)."""
    t = np.linspace(0.0, seconds, int(SR * seconds), dtype=np.float32)
    sig = (0.35 * np.sin(2 * np.pi * 180 * t)
           + 0.20 * np.sin(2 * np.pi * 460 * t)
           + 0.10 * np.sin(2 * np.pi * 1200 * t))
    env = 0.5 * (1.0 - np.cos(2 * np.pi * np.minimum(t / seconds, 1.0)))
    return (sig * env).astype(np.float32)


def _wav_bytes(sig: np.ndarray) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, sig, SR, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def _ogg_bytes(sig: np.ndarray) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, sig, SR, format="OGG", subtype="VORBIS")
    return buf.getvalue()


def _pcm16_bytes(sig: np.ndarray, first_sample: int | None = None) -> bytes:
    pcm = np.clip(sig * 32767.0, -32768, 32767).astype("<i2")
    if first_sample is not None:
        pcm[0] = first_sample
    return pcm.tobytes()


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _post_embed(raw: bytes, sample_rate: int = SR):
    return client.post("/embed", json={"audio": _b64(raw), "sampleRate": sample_rate})


# ── Tests ────────────────────────────────────────────────────────────────────

def test_wav_embed_200_dim512():
    """Enroll-Vertrag unverändert: echtes RIFF-WAV ⇒ 200 + 512-dim."""
    r = _post_embed(_wav_bytes(_speechish_float()))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["dim"] == EMBED_DIM
    assert len(body["embedding"]) == EMBED_DIM
    n = float(np.linalg.norm(np.asarray(body["embedding"], dtype=np.float32)))
    assert abs(n - 1.0) < 1e-3, f"Embedding nicht L2-normalisiert: |v|={n}"


def test_webm_magic_rejected_422():
    """Der 0.226-Bug: EBML/webm-Bytes dürfen NIE als PCM16 durchrutschen."""
    fake_webm = b"\x1a\x45\xdf\xa3" + bytes(range(256)) * 64  # Magic + Müll
    r = _post_embed(fake_webm)
    assert r.status_code == 422, f"erwartet 422, bekam {r.status_code}: {r.text}"
    assert "webm" in r.json()["detail"].lower()


def test_mp4_ftyp_rejected_422():
    """Safari-MediaRecorder-Pfad: mp4/ftyp ⇒ 422 mit Format-Name."""
    fake_mp4 = b"\x00\x00\x00\x20ftypisom" + bytes(1024)
    r = _post_embed(fake_mp4)
    assert r.status_code == 422, f"erwartet 422, bekam {r.status_code}: {r.text}"
    assert "mp4" in r.json()["detail"].lower()


def test_ogg_vorbis_accepted_200():
    """OGS-Container, den libsndfile liest ⇒ legitim 200 + 512-dim."""
    r = _post_embed(_ogg_bytes(_speechish_float()))
    assert r.status_code == 200, r.text
    assert r.json()["dim"] == EMBED_DIM


def test_ogg_magic_with_garbage_rejected_422():
    """OggS-Magic, aber kein lesbarer Stream ⇒ 422 (kein PCM16-Fallback)."""
    fake_ogg = b"OggS" + bytes(2048)
    r = _post_embed(fake_ogg)
    assert r.status_code == 422, f"erwartet 422, bekam {r.status_code}: {r.text}"
    assert "ogg" in r.json()["detail"].lower()


def test_raw_pcm16_still_accepted_200():
    """Satelliten-Vertrag (codec:"pcm16"): Roh-PCM16 ohne Magic ⇒ 200 + 512-dim.

    first_sample=-1 (Bytes FF FF) ist der harte Fall: ein blinder
    soundfile-Versuch würde das als MP3-Frame-Sync missdeuten (empirisch
    belegt, libsndfile 1.2.2) — der Zweig muss ohne soundfile durchgehen.
    """
    sig = _speechish_float()
    r = _post_embed(_pcm16_bytes(sig, first_sample=-1))
    assert r.status_code == 200, r.text
    assert r.json()["dim"] == EMBED_DIM


def test_raw_pcm16_matches_wav_embedding():
    """Regressions-Anker: BIT-IDENTISCHE Samples als Roh-PCM16 und als WAV
    müssen dasselbe Embedding ergeben — beweist, dass der PCM16-Zweig
    weiterhin korrekt dekodiert statt anders zu laufen. (Die int16-Samples
    werden aus dem WAV zurückgelesen, damit beide Pfade exakt dieselben
    Bytes sehen — eigene *32767-Quantisierung driftet ±1 LSB.)"""
    wav_raw = _wav_bytes(_speechish_float())
    pcm_int16, sr = sf.read(io.BytesIO(wav_raw), dtype="int16")
    assert sr == SR
    r_pcm = _post_embed(pcm_int16.astype("<i2").tobytes())
    r_wav = _post_embed(wav_raw)
    assert r_pcm.status_code == 200 and r_wav.status_code == 200
    a = np.asarray(r_pcm.json()["embedding"], dtype=np.float32)
    b = np.asarray(r_wav.json()["embedding"], dtype=np.float32)
    cos = float(a @ b)
    assert cos > 0.9999, f"PCM16- vs WAV-Embedding driftet: cosine={cos}"


def test_verify_webm_magic_rejected_422():
    """/verify nutzt denselben Decode-Pfad — webm ⇒ 422, nicht guest-by-noise."""
    fake_webm = b"\x1a\x45\xdf\xa3" + bytes(4096)
    r = client.post("/verify", json={
        "audio": _b64(fake_webm),
        "sampleRate": SR,
        "enrolled": [{"speakerId": "andi", "embedding": [1.0] + [0.0] * (EMBED_DIM - 1)}],
        "thresholds": {"high": 0.8, "low": 0.5},
    })
    assert r.status_code == 422, f"erwartet 422, bekam {r.status_code}: {r.text}"
    assert "webm" in r.json()["detail"].lower()


def test_bad_base64_rejected_422():
    r = client.post("/embed", json={"audio": "das ist kein base64!!", "sampleRate": SR})
    assert r.status_code == 422


# ── Standalone-Runner (kein pytest im venv — bewusst nichts installiert) ─────
if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"PASS  {name}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {name}: {e}")
        except Exception as e:  # harte Fehler auch als FAIL zählen
            failed += 1
            print(f"ERROR {name}: {type(e).__name__}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
