# SPDX-License-Identifier: Apache-2.0
"""Kleiner HTTP-Sidecar fuer die optionale lokale Piper-TTS-Runtime.

Der Hoshi-Code in dieser Datei ist Apache-2.0. Zur Laufzeit wird das separat
lizenzierte Paket ``piper-tts`` (GPL-3.0-or-later) geladen. Der Java-Prozess
spricht ausschliesslich HTTP mit diesem Sidecar; kein Piper-Code wird vendort.
"""
from __future__ import annotations

import argparse
import io
import json
import logging
import os
import platform
import resource
import subprocess
import sys
import threading
import time
import wave
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("hoshi-tts-piper")

SIDE_CAR_DIR = Path(__file__).resolve().parent
MANIFEST_PATH = SIDE_CAR_DIR / "artifacts.lock.json"
MAX_TEXT_CHARS = 1000
MAX_REQUEST_BYTES = 16_384

parser = argparse.ArgumentParser(description="Hoshi-TTS-Piper-Sidecar")
parser.add_argument("--host", default=os.environ.get("HOSHI_PIPER_HOST", "0.0.0.0"))
parser.add_argument("--port", type=int, default=int(os.environ.get("HOSHI_PIPER_PORT", "8045")))
parser.add_argument(
    "--model-dir",
    default=os.environ.get("HOSHI_PIPER_MODEL_DIR", str(SIDE_CAR_DIR / "models")),
)
parser.add_argument(
    "--default-voice",
    default=os.environ.get("HOSHI_PIPER_DEFAULT_VOICE", "de_DE-thorsten-medium"),
)
parser.add_argument("--selftest", action="store_true")
args, _unknown = parser.parse_known_args()


@dataclass(frozen=True)
class VoiceSpec:
    voice_id: str
    locale: str
    quality: str
    sample_rate: int
    model_license: str
    dataset_license: str
    model_path: Path
    config_path: Path


def _load_voice_spec(voice_id: str, model_dir: Path) -> VoiceSpec:
    data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    entry = next((voice for voice in data["voices"] if voice["id"] == voice_id), None)
    if entry is None:
        raise ValueError(f"Stimme nicht im Manifest: {voice_id}")
    return VoiceSpec(
        voice_id=voice_id,
        locale=entry["locale"],
        quality=entry["quality"],
        sample_rate=int(entry["sample_rate"]),
        model_license=entry["model_license"],
        dataset_license=entry["dataset_license"],
        model_path=model_dir / entry["model"]["artifact"],
        config_path=model_dir / entry["config"]["artifact"],
    )


VOICE_SPEC = _load_voice_spec(args.default_voice, Path(args.model_dir))


@dataclass(frozen=True)
class SynthResult:
    wav: bytes
    synthesis_ms: int
    audio_ms: int


class Synthesizer(Protocol):
    @property
    def ready(self) -> bool: ...

    def load(self) -> None: ...

    def synthesize(self, text: str) -> SynthResult: ...


def _wav_duration_ms(wav_bytes: bytes, expected_rate: int) -> int:
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as wav_file:
            if wav_file.getnchannels() != 1:
                raise ValueError(f"Piper lieferte {wav_file.getnchannels()} Kanaele statt mono")
            if wav_file.getsampwidth() != 2:
                raise ValueError(f"Piper lieferte {wav_file.getsampwidth()} Byte statt PCM16")
            if wav_file.getframerate() != expected_rate:
                raise ValueError(f"Piper lieferte {wav_file.getframerate()} Hz statt {expected_rate} Hz")
            frames = wav_file.getnframes()
            if frames <= 0:
                raise ValueError("Piper lieferte ein leeres WAV")
            return round(frames * 1000 / wav_file.getframerate())
    except wave.Error as exc:
        raise ValueError("Piper lieferte kein gueltiges WAV") from exc


def _peak_rss_mb() -> float:
    rss = float(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    # macOS meldet Bytes, Linux KiB. Das Label bleibt bewusst peak_rss_mb.
    return round(rss / (1024 * 1024) if platform.system() == "Darwin" else rss / 1024, 1)


def _current_rss_mb() -> float | None:
    """Aktuelles RSS auf dem Zielhost; Peak und steady state nicht vermischen."""
    if platform.system() != "Darwin":
        return None
    try:
        result = subprocess.run(
            ["/bin/ps", "-o", "rss=", "-p", str(os.getpid())],
            check=True,
            capture_output=True,
            text=True,
            timeout=2,
        )
        return round(int(result.stdout.strip()) / 1024, 1)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


class PiperSynthesizer:
    def __init__(self, spec: VoiceSpec, threads: int = 2):
        self.spec = spec
        self.threads = max(1, min(8, threads))
        self._voice = None
        self._lock = threading.Lock()
        self.warmup_ms: int | None = None

    @property
    def ready(self) -> bool:
        return self._voice is not None

    def load(self) -> None:
        if self._voice is not None:
            return
        if not self.spec.model_path.is_file():
            raise FileNotFoundError(f"Piper-Modell fehlt: {self.spec.model_path}")
        if not self.spec.config_path.is_file():
            raise FileNotFoundError(f"Piper-Konfiguration fehlt: {self.spec.config_path}")
        import onnxruntime
        from piper import PiperVoice  # externe GPL-Runtime bewusst erst hier laden
        from piper.config import PiperConfig

        started = time.perf_counter()
        config = PiperConfig.from_dict(json.loads(self.spec.config_path.read_text(encoding="utf-8")))
        session_options = onnxruntime.SessionOptions()
        session_options.intra_op_num_threads = self.threads
        session_options.inter_op_num_threads = 1
        # Arena + Mem-Pattern bleiben bewusst an: ein Live-A/B mit zehn Turns
        # zeigte ohne Arena einen wachsenden Prozess (433 MB), weil wiederholte
        # Allokationen nicht stabil wiederverwendet wurden. Zwei gezielt gesetzte
        # Threads begrenzen stattdessen Compute; der Mehrturn-Benchmark ist das Gate.
        session_options.enable_cpu_mem_arena = True
        session_options.enable_mem_pattern = True
        session = onnxruntime.InferenceSession(
            str(self.spec.model_path),
            sess_options=session_options,
            providers=["CPUExecutionProvider"],
        )
        self._voice = PiperVoice(config=config, session=session)
        # Der erste ONNX-Lauf ist sichtbar langsamer. Ein fester, nicht privater
        # Mini-Satz zahlt diesen Preis beim Start statt im ersten echten Turn.
        warmup = self.synthesize("Hallo.")
        self.warmup_ms = warmup.synthesis_ms
        log.info(
            "Piper bereit: voice=%s threads=%d load_plus_warmup_ms=%d warmup_ms=%d rss_mb=%s peak_rss_mb=%.1f",
            self.spec.voice_id,
            self.threads,
            round((time.perf_counter() - started) * 1000),
            warmup.synthesis_ms,
            _current_rss_mb(),
            _peak_rss_mb(),
        )

    def synthesize(self, text: str) -> SynthResult:
        if self._voice is None:
            raise RuntimeError("Piper ist noch nicht geladen")
        started = time.perf_counter()
        output = io.BytesIO()
        # Ein ONNX-Voice-Objekt, ein Request gleichzeitig: begrenzter Peak statt
        # unkontrollierter Parallel-Inferenz an der 16-GB-Wand.
        with self._lock:
            with wave.open(output, "wb") as wav_file:
                self._voice.synthesize_wav(text, wav_file)
        synthesis_ms = round((time.perf_counter() - started) * 1000)
        wav_bytes = output.getvalue()
        audio_ms = _wav_duration_ms(wav_bytes, self.spec.sample_rate)
        return SynthResult(wav=wav_bytes, synthesis_ms=synthesis_ms, audio_ms=audio_ms)


PIPER_THREADS = max(1, min(8, int(os.environ.get("HOSHI_PIPER_THREADS", "2"))))
synthesizer: Synthesizer = PiperSynthesizer(VOICE_SPEC, threads=PIPER_THREADS)


def health_payload() -> dict:
    return {
        "status": "ok" if synthesizer.ready else "starting",
        "engine": "piper",
        "runtime_license": "GPL-3.0-or-later",
        "default_voice": VOICE_SPEC.voice_id,
        "sample_rate": VOICE_SPEC.sample_rate,
        "threads": PIPER_THREADS,
        "warmup_ms": getattr(synthesizer, "warmup_ms", None),
        "rss_mb": _current_rss_mb(),
        "peak_rss_mb": _peak_rss_mb(),
    }


def voices_payload() -> dict:
    return {
        "voices": [
            {
                "id": VOICE_SPEC.voice_id,
                "locale": VOICE_SPEC.locale,
                "quality": VOICE_SPEC.quality,
                "sample_rate": VOICE_SPEC.sample_rate,
                "model_license": VOICE_SPEC.model_license,
                "dataset_license": VOICE_SPEC.dataset_license,
            }
        ]
    }


@dataclass(frozen=True)
class ApiResponse:
    status: int
    content: bytes
    content_type: str
    headers: dict[str, str]


def _json_api_response(status: int, payload: dict) -> ApiResponse:
    return ApiResponse(
        status=status,
        content=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        content_type="application/json; charset=utf-8",
        headers={},
    )


def tts_response(payload: object) -> ApiResponse:
    if not isinstance(payload, dict):
        return _json_api_response(422, {"detail": "JSON-Objekt erwartet"})
    raw_text = payload.get("text")
    if not isinstance(raw_text, str):
        return _json_api_response(422, {"detail": "text fehlt/ist kein String"})
    text = raw_text.strip()
    if not text:
        return _json_api_response(422, {"detail": "text fehlt/leer"})
    if len(text) > MAX_TEXT_CHARS:
        return _json_api_response(413, {"detail": f"text zu lang ({len(text)} > {MAX_TEXT_CHARS})"})
    raw_voice = payload.get("voice")
    if raw_voice is not None and not isinstance(raw_voice, str):
        return _json_api_response(422, {"detail": "voice ist kein String"})
    voice = raw_voice or VOICE_SPEC.voice_id
    if voice != VOICE_SPEC.voice_id:
        return _json_api_response(422, {"detail": f"Stimme nicht geladen: {voice}"})
    try:
        result = synthesizer.synthesize(text)
    except Exception as exc:  # noqa: BLE001 — HTTP-Rand meldet Fehler, nie Textinhalt
        log.exception("Piper-Synthese fehlgeschlagen (text_len=%d)", len(text))
        return _json_api_response(500, {"detail": f"Piper-Synthese fehlgeschlagen: {type(exc).__name__}"})
    rtf = result.synthesis_ms / result.audio_ms if result.audio_ms else 0.0
    log.info(
        "Piper-Synthese: text_len=%d synth_ms=%d audio_ms=%d rtf=%.3f",
        len(text),
        result.synthesis_ms,
        result.audio_ms,
        rtf,
    )
    return ApiResponse(
        status=200,
        content=result.wav,
        content_type="audio/wav",
        headers={
            "X-Hoshi-TTS-Ms": str(result.synthesis_ms),
            "X-Hoshi-Audio-Ms": str(result.audio_ms),
            "X-Hoshi-Voice": VOICE_SPEC.voice_id,
        },
    )


class PiperHandler(BaseHTTPRequestHandler):
    """Absichtlich winzige stdlib-HTTP-Huelle statt ~30 MB Webframework-Stack."""

    server_version = "HoshiPiper/0.1"
    sys_version = ""

    def _write(self, response: ApiResponse) -> None:
        self.send_response(response.status)
        self.send_header("Content-Type", response.content_type)
        self.send_header("Content-Length", str(len(response.content)))
        for name, value in response.headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(response.content)

    def do_GET(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler-Vertrag
        path = urlsplit(self.path).path
        if path == "/health":
            self._write(_json_api_response(200, health_payload()))
        elif path == "/voices":
            self._write(_json_api_response(200, voices_payload()))
        else:
            self._write(_json_api_response(404, {"detail": "nicht gefunden"}))

    def do_POST(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler-Vertrag
        if urlsplit(self.path).path != "/tts":
            self._write(_json_api_response(404, {"detail": "nicht gefunden"}))
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._write(_json_api_response(400, {"detail": "ungueltige Content-Length"}))
            return
        if content_length <= 0:
            self._write(_json_api_response(400, {"detail": "leerer Request-Body"}))
            return
        if content_length > MAX_REQUEST_BYTES:
            self._write(_json_api_response(413, {"detail": "Request-Body zu gross"}))
            return
        try:
            payload = json.loads(self.rfile.read(content_length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._write(_json_api_response(400, {"detail": "ungueltiges JSON"}))
            return
        self._write(tts_response(payload))

    def log_message(self, fmt: str, *values) -> None:
        # Base-Handler loggt nur Methode/Pfad/Status, nie den JSON-Body/Text.
        log.info("http %s", fmt % values)


def serve(host: str, port: int) -> None:
    if not synthesizer.ready:
        synthesizer.load()
    httpd = HTTPServer((host, port), PiperHandler)
    log.info("hoshi-tts-piper lauscht auf %s:%d (stdlib HTTP, seriell)", host, port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("hoshi-tts-piper stoppt auf SIGINT")
    finally:
        httpd.server_close()


def _selftest() -> bool:
    try:
        synthesizer.load()
        result = synthesizer.synthesize("Hallo, ich bin Hoshi und arbeite lokal.")
        rtf = result.synthesis_ms / result.audio_ms if result.audio_ms else float("inf")
        print(
            f"[selftest] PASS voice={VOICE_SPEC.voice_id} wav_bytes={len(result.wav)} "
            f"sample_rate={VOICE_SPEC.sample_rate} synth_ms={result.synthesis_ms} "
            f"audio_ms={result.audio_ms} rtf={rtf:.3f} rss_mb={_current_rss_mb()} "
            f"peak_rss_mb={_peak_rss_mb():.1f}"
        )
        return True
    except Exception as exc:  # noqa: BLE001 — Selftest muss jeden Fehler sichtbar machen
        print(f"[selftest] FAIL {type(exc).__name__}: {exc}", file=sys.stderr)
        return False


if __name__ == "__main__":
    if args.selftest:
        sys.exit(0 if _selftest() else 1)
    serve(args.host, args.port)
