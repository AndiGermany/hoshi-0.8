# SPDX-License-Identifier: Apache-2.0
"""Standalone-faehige Contract-Tests; brauchen weder Piper noch Modell."""
from __future__ import annotations

import io
import http.client
import json
import re
import sys
import threading
import wave
from dataclasses import dataclass
from http.server import HTTPServer
from pathlib import Path

import server


def _valid_wav(sample_rate: int = 22050, frames: int = 2205) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00\x00" * frames)
    return output.getvalue()


class FakeSynthesizer:
    def __init__(self, fail: bool = False):
        self._ready = False
        self.fail = fail

    @property
    def ready(self) -> bool:
        return self._ready

    def load(self) -> None:
        self._ready = True

    def synthesize(self, text: str) -> server.SynthResult:
        if self.fail:
            raise RuntimeError("absichtlicher Testfehler")
        return server.SynthResult(wav=_valid_wav(), synthesis_ms=12, audio_ms=100)


@dataclass(frozen=True)
class ClientResponse:
    status_code: int
    content: bytes
    headers: dict[str, str]

    @property
    def text(self) -> str:
        return self.content.decode("utf-8", errors="replace")

    def json(self) -> dict:
        return json.loads(self.content)


class LocalClient:
    def __init__(self, port: int):
        self.port = port

    def request(self, method: str, path: str, payload: dict | None = None) -> ClientResponse:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {} if payload is None else {"Content-Type": "application/json"}
        return self.raw_request(method, path, body, headers)

    def raw_request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> ClientResponse:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return ClientResponse(
                status_code=response.status,
                content=response.read(),
                headers={name.lower(): value for name, value in response.getheaders()},
            )
        finally:
            connection.close()

    def get(self, path: str) -> ClientResponse:
        return self.request("GET", path)

    def post(self, path: str, json: dict) -> ClientResponse:
        return self.request("POST", path, json)


def _with_client(fn, fail: bool = False):
    original = server.synthesizer
    fake = FakeSynthesizer(fail=fail)
    fake.load()
    server.synthesizer = fake
    httpd = HTTPServer(("127.0.0.1", 0), server.PiperHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        fn(LocalClient(httpd.server_port))
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)
        server.synthesizer = original


def test_health_and_voice_manifest():
    def check(client):
        health = client.get("/health")
        assert health.status_code == 200, health.text
        assert health.json()["status"] == "ok"
        assert health.json()["runtime_license"] == "GPL-3.0-or-later"
        voices = client.get("/voices")
        assert voices.status_code == 200, voices.text
        assert voices.json()["voices"] == [{
            "id": "de_DE-thorsten-medium",
            "locale": "de_DE",
            "quality": "medium",
            "sample_rate": 22050,
            "model_license": "MIT",
            "dataset_license": "CC0-1.0",
        }]
    _with_client(check)


def test_tts_contract_returns_pcm16_mono_wav_and_metrics():
    def check(client):
        response = client.post("/tts", json={"text": "Guten Abend."})
        assert response.status_code == 200, response.text
        assert response.headers["content-type"].startswith("audio/wav")
        assert response.headers["x-hoshi-tts-ms"] == "12"
        assert response.headers["x-hoshi-audio-ms"] == "100"
        with wave.open(io.BytesIO(response.content), "rb") as wav_file:
            assert wav_file.getnchannels() == 1
            assert wav_file.getsampwidth() == 2
            assert wav_file.getframerate() == 22050
    _with_client(check)


def test_tts_rejects_empty_too_long_and_unknown_voice():
    def check(client):
        assert client.post("/tts", json={"text": "  "}).status_code == 422
        assert client.post("/tts", json={"text": "x" * 1001}).status_code == 413
        response = client.post("/tts", json={"text": "Hallo", "voice": "nicht-da"})
        assert response.status_code == 422
        assert "nicht geladen" in response.json()["detail"]
    _with_client(check)


def test_tts_turns_engine_failure_into_honest_500():
    def check(client):
        response = client.post("/tts", json={"text": "Hallo"})
        assert response.status_code == 500
        assert response.json()["detail"] == "Piper-Synthese fehlgeschlagen: RuntimeError"
        assert "Hallo" not in response.text
    _with_client(check, fail=True)


def test_http_boundary_rejects_bad_json_large_body_and_unknown_path():
    def check(client):
        bad_json = client.raw_request(
            "POST",
            "/tts",
            b"{",
            {"Content-Type": "application/json"},
        )
        assert bad_json.status_code == 400
        too_large = client.raw_request(
            "POST",
            "/tts",
            b"x" * (server.MAX_REQUEST_BYTES + 1),
            {"Content-Type": "application/json"},
        )
        assert too_large.status_code == 413
        assert client.get("/nicht-da").status_code == 404
    _with_client(check)


def test_artifact_lock_is_pinned_and_license_explicit():
    lock = json.loads((Path(__file__).parent / "artifacts.lock.json").read_text(encoding="utf-8"))
    runtime = lock["runtime"]
    assert runtime["version"] == "1.5.0"
    assert runtime["license"] == "GPL-3.0-or-later"
    assert runtime["artifact"].endswith("macosx_11_0_arm64.whl")
    assert re.fullmatch(r"[0-9a-f]{64}", runtime["sha256"])
    assert runtime["bytes"] > 1_000_000
    voice = lock["voices"][0]
    assert voice["revision"] != "main"
    assert voice["model_license"] == "MIT"
    assert voice["dataset_license"] == "CC0-1.0"
    for artifact in (voice["model"], voice["config"]):
        assert re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"])
        assert f"/resolve/{voice['revision']}/" in artifact["url"]


if __name__ == "__main__":
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"PASS  {name}")
        except Exception as exc:  # noqa: BLE001 — standalone Runner zaehlt alles als Fehler
            failed += 1
            print(f"FAIL  {name}: {type(exc).__name__}: {exc}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
