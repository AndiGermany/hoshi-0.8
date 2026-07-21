# SPDX-License-Identifier: Apache-2.0
"""Standalone-faehige Contract-Tests; brauchen weder Piper noch Modell."""
from __future__ import annotations

import io
import http.client
import json
import re
import sys
import tempfile
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
        self.load_calls = 0

    @property
    def ready(self) -> bool:
        return self._ready

    def load(self) -> None:
        self.load_calls += 1
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


def _with_client(fn, fail: bool = False, extra_voices: dict[str, server.VoiceSpec] | None = None):
    """Startet einen echten [PiperHandler]-HTTP-Server ueber einem FRISCHEN,
    fake-bestueckten [server.VoiceCache] — Muster der Vor-Mehrstimmen-Version
    (``server.synthesizer = fake``), nur an die Mehrstimmen-Architektur
    angepasst: [server.VOICE_CACHE] wird komplett ausgetauscht statt eines
    einzelnen globalen Synthesizers, damit KEIN echtes Piper/ONNX-Paket noetig
    ist (auch nicht fuer eine zweite/englische Test-Stimme, s. [extra_voices]).
    """
    default_id = server.VOICE_SPEC.voice_id
    specs = {default_id: server.VOICE_SPEC}
    if extra_voices:
        specs.update(extra_voices)

    default_fake = FakeSynthesizer(fail=fail)
    default_fake.load()

    test_cache = server.VoiceCache(specs, threads=1)
    test_cache.cache[default_id] = default_fake
    # Jede weitere (lazy angefragte) Stimme bekommt IHRE EIGENE frische Fake-
    # Instanz statt der Default-Instanz — sonst waere ein Test blind gegen
    # "spricht mit der FALSCHEN Stimme"-Regressionen.
    test_cache.synthesizer_factory = lambda spec: FakeSynthesizer(fail=fail)

    original_cache = server.VOICE_CACHE
    server.VOICE_CACHE = test_cache
    httpd = HTTPServer(("127.0.0.1", 0), server.PiperHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        fn(LocalClient(httpd.server_port), test_cache)
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)
        server.VOICE_CACHE = original_cache


def test_health_and_voice_manifest():
    def check(client, _cache):
        health = client.get("/health")
        assert health.status_code == 200, health.text
        assert health.json()["status"] == "ok"
        assert health.json()["runtime_license"] == "GPL-3.0-or-later"
        voices = client.get("/voices")
        assert voices.status_code == 200, voices.text
        thorsten = next(v for v in voices.json()["voices"] if v["id"] == "de_DE-thorsten-medium")
        assert thorsten == {
            "id": "de_DE-thorsten-medium",
            "locale": "de_DE",
            "quality": "medium",
            "sample_rate": 22050,
            "model_license": "MIT",
            "dataset_license": "CC0-1.0",
        }
    _with_client(check)


def test_voices_lists_only_the_default_voice_when_thats_the_only_one_in_the_cache():
    """Mehrstimmen-faehig heisst NICHT "immer alle Manifest-Stimmen behaupten":
    [_with_client] ohne [extra_voices] baut den Test-Cache bewusst NUR mit der
    Default-Stimme — /voices darf dann auch nur genau die eine melden (ehrlich
    entlang dem, was der Cache wirklich kennt)."""
    def check(client, _cache):
        voices = client.get("/voices").json()["voices"]
        assert [v["id"] for v in voices] == ["de_DE-thorsten-medium"]
    _with_client(check)


def test_voices_lists_a_second_voice_once_its_files_are_present_in_manifest_order():
    kristin_spec = server.VoiceSpec(
        voice_id="en_US-kristin-medium",
        locale="en_US",
        quality="medium",
        sample_rate=22050,
        model_license="MIT (Repo rhasspy/piper-voices)",
        dataset_license="public domain (LibriVox)",
        model_path=Path("/nicht/echt.onnx"),
        config_path=Path("/nicht/echt.onnx.json"),
    )

    def check(client, _cache):
        voices = client.get("/voices").json()["voices"]
        assert [v["id"] for v in voices] == ["de_DE-thorsten-medium", "en_US-kristin-medium"], (
            "Manifest-Reihenfolge bleibt erhalten: DE zuerst, dann EN"
        )
        kristin = next(v for v in voices if v["id"] == "en_US-kristin-medium")
        assert kristin["locale"] == "en_US"
        assert kristin["sample_rate"] == 22050

    _with_client(check, extra_voices={"en_US-kristin-medium": kristin_spec})


def test_tts_contract_returns_pcm16_mono_wav_and_metrics():
    def check(client, _cache):
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
    def check(client, _cache):
        assert client.post("/tts", json={"text": "  "}).status_code == 422
        assert client.post("/tts", json={"text": "x" * 1001}).status_code == 413
        response = client.post("/tts", json={"text": "Hallo", "voice": "nicht-da"})
        assert response.status_code == 422
        assert "nicht geladen" in response.json()["detail"]
    _with_client(check)


def test_tts_turns_engine_failure_into_honest_500():
    def check(client, _cache):
        response = client.post("/tts", json={"text": "Hallo"})
        assert response.status_code == 500
        assert response.json()["detail"] == "Piper-Synthese fehlgeschlagen: RuntimeError"
        assert "Hallo" not in response.text
    _with_client(check, fail=True)


def test_http_boundary_rejects_bad_json_large_body_and_unknown_path():
    def check(client, _cache):
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


# ── Mehrstimmen-Naht (Andi-Auftrag 21.07 Build-Week-Video): lazy laden, ────────
# ── danach cachen, ehrlich listen — s. server.VoiceCache-KDoc ─────────────────

def test_available_voice_specs_only_lists_voices_whose_files_really_exist_on_disk():
    """Der Ehrlichkeits-Kern von (a): eine im Lockfile stehende, aber (noch)
    nicht heruntergeladene Stimme wird NIE gemeldet. Deterministisch ueber
    temporaere Modell-Ordner geprüft — unabhängig davon, was auf DIESER
    Maschine gerade unter sidecars/piper/models/ wirklich liegt."""
    manifest = json.loads((Path(__file__).parent / "artifacts.lock.json").read_text(encoding="utf-8"))
    thorsten = next(v for v in manifest["voices"] if v["id"] == "de_DE-thorsten-medium")
    kristin = next(v for v in manifest["voices"] if v["id"] == "en_US-kristin-medium")

    with tempfile.TemporaryDirectory() as tmp:
        model_dir = Path(tmp)
        assert server._available_voice_specs(model_dir) == {}, "leerer Ordner ⇒ keine Stimme gemeldet"

        (model_dir / thorsten["model"]["artifact"]).write_bytes(b"\x00")
        (model_dir / thorsten["config"]["artifact"]).write_text("{}", encoding="utf-8")
        only_thorsten = server._available_voice_specs(model_dir)
        assert list(only_thorsten.keys()) == ["de_DE-thorsten-medium"], "nur Thorstens Dateien liegen vor"

        (model_dir / kristin["model"]["artifact"]).write_bytes(b"\x00")
        (model_dir / kristin["config"]["artifact"]).write_text("{}", encoding="utf-8")
        both = server._available_voice_specs(model_dir)
        assert list(both.keys()) == [
            "de_DE-thorsten-medium",
            "en_US-kristin-medium",
        ], "beide Dateien liegen vor, IN MANIFEST-REIHENFOLGE"

    with tempfile.TemporaryDirectory() as tmp:
        model_dir = Path(tmp)
        # NUR das .onnx, OHNE die .onnx.json ⇒ weiterhin nicht gelistet (BEIDE
        # Dateien sind Pflicht, eine halbe Stimme ist keine Stimme).
        (model_dir / thorsten["model"]["artifact"]).write_bytes(b"\x00")
        assert server._available_voice_specs(model_dir) == {}, "Konfig fehlt ⇒ ehrlich nicht vorhanden"


def test_voice_cache_loads_lazily_once_per_voice_and_then_serves_from_cache():
    """[server.VoiceCache.get] darf eine Stimme NUR beim ERSTEN Zugriff laden
    (teures ONNX-Setup) und muss sie danach dauerhaft im Cache halten — kein
    zweites `.load()` fuer denselben Request, aber ein GETRENNTES `.load()` je
    Stimme (zwei Stimmen kosten zwei Ladevorgänge, s. RAM-KDoc)."""
    thorsten_spec = server.VOICE_SPEC
    kristin_spec = server.VoiceSpec(
        voice_id="en_US-kristin-medium",
        locale="en_US",
        quality="medium",
        sample_rate=22050,
        model_license="MIT (Repo rhasspy/piper-voices)",
        dataset_license="public domain (LibriVox)",
        model_path=Path("/nicht/echt.onnx"),
        config_path=Path("/nicht/echt.onnx.json"),
    )
    built: list[str] = []

    def factory(spec: server.VoiceSpec) -> FakeSynthesizer:
        built.append(spec.voice_id)
        return FakeSynthesizer()

    cache = server.VoiceCache({thorsten_spec.voice_id: thorsten_spec, kristin_spec.voice_id: kristin_spec}, threads=1)
    cache.synthesizer_factory = factory

    assert cache.cache == {}, "vor dem ersten Zugriff ist NICHTS geladen (lazy)"

    thorsten_synth = cache.get(thorsten_spec.voice_id)
    assert built == [thorsten_spec.voice_id]
    assert thorsten_synth.ready

    # Zweiter Zugriff auf DIESELBE Stimme: kein zweiter Ladevorgang, dieselbe Instanz.
    again = cache.get(thorsten_spec.voice_id)
    assert again is thorsten_synth
    assert built == [thorsten_spec.voice_id], "keine zweite Instanz/kein zweites .load() fuer eine bereits gecachte Stimme"

    # Eine ZWEITE, andere Stimme: eigener, getrennter Ladevorgang.
    kristin_synth = cache.get(kristin_spec.voice_id)
    assert built == [thorsten_spec.voice_id, kristin_spec.voice_id]
    assert kristin_synth is not thorsten_synth
    assert kristin_synth.ready


def test_voice_cache_get_raises_for_unknown_or_missing_voice_never_silent_fallback():
    """Unbekannte/nicht vorhandene Stimme ⇒ klarer Fehler — NIE ein stiller
    Rueckfall auf eine andere (z.B. die deutsche Default-)Stimme."""
    cache = server.VoiceCache({server.VOICE_SPEC.voice_id: server.VOICE_SPEC}, threads=1)
    cache.synthesizer_factory = lambda spec: FakeSynthesizer()
    try:
        cache.get("es_ES-irgendwas-medium")
        raise AssertionError("haette ValueError werfen muessen")
    except ValueError as exc:
        assert "es_ES-irgendwas-medium" in str(exc)


def test_tts_speaks_with_the_explicitly_requested_second_voice_not_the_default():
    """Der zentrale Mehrstimmen-Beweis: ein `/tts`-Request mit `voice` einer
    ZWEITEN, vorhandenen Stimme wird lazy geladen und antwortet mit GENAU
    dieser Stimme im `X-Hoshi-Voice`-Header — nicht mit der deutschen Default-
    Stimme (Andi-Vorgabe: keine heimliche Deutsch-Antwort auf eine englische Wahl)."""
    kristin_spec = server.VoiceSpec(
        voice_id="en_US-kristin-medium",
        locale="en_US",
        quality="medium",
        sample_rate=22050,
        model_license="MIT (Repo rhasspy/piper-voices)",
        dataset_license="public domain (LibriVox)",
        model_path=Path("/nicht/echt.onnx"),
        config_path=Path("/nicht/echt.onnx.json"),
    )

    def check(client, cache):
        response = client.post("/tts", json={"text": "Hello there.", "voice": "en_US-kristin-medium"})
        assert response.status_code == 200, response.text
        assert response.headers["x-hoshi-voice"] == "en_US-kristin-medium"
        assert "en_US-kristin-medium" in cache.cache, "die zweite Stimme wurde WIRKLICH lazy geladen und gecacht"
        assert cache.cache["en_US-kristin-medium"].load_calls == 1

        # Ein zweiter Request derselben Stimme laedt NICHT erneut.
        client.post("/tts", json={"text": "Hi again.", "voice": "en_US-kristin-medium"})
        assert cache.cache["en_US-kristin-medium"].load_calls == 1, "die zweite Stimme wird nur EINMAL geladen"

        # Die deutsche Default-Stimme bleibt davon unberuehrt anfragbar.
        de_response = client.post("/tts", json={"text": "Hallo."})
        assert de_response.status_code == 200
        assert de_response.headers["x-hoshi-voice"] == "de_DE-thorsten-medium"

    _with_client(check, extra_voices={"en_US-kristin-medium": kristin_spec})


# ── REGRESSIONSPFLICHT: der deutsche Pfad bleibt byte-identisch ───────────────

def test_german_default_path_stays_byte_identical_across_the_multivoice_change():
    """Die eine bindende Pin-Probe fuer diese Naht (s. Auftrag): Default-Stimme,
    /health- und /voices-Felder sowie die Antwort-Header fuer Thorsten bleiben
    EXAKT wie vor der Mehrstimmen-Umstellung — unabhaengig davon, ob/welche
    weiteren Stimmen der Cache sonst noch kennt."""
    assert server.VOICE_SPEC.voice_id == "de_DE-thorsten-medium"

    kristin_spec = server.VoiceSpec(
        voice_id="en_US-kristin-medium",
        locale="en_US",
        quality="medium",
        sample_rate=22050,
        model_license="MIT (Repo rhasspy/piper-voices)",
        dataset_license="public domain (LibriVox)",
        model_path=Path("/nicht/echt.onnx"),
        config_path=Path("/nicht/echt.onnx.json"),
    )

    def check(client, _cache):
        health = client.get("/health").json()
        assert health["status"] == "ok"
        assert health["engine"] == "piper"
        assert health["runtime_license"] == "GPL-3.0-or-later"
        assert health["default_voice"] == "de_DE-thorsten-medium"
        assert health["sample_rate"] == 22050
        assert set(["status", "engine", "runtime_license", "default_voice", "sample_rate", "threads", "warmup_ms", "rss_mb", "peak_rss_mb"]) <= set(
            health.keys()
        )

        voices = client.get("/voices").json()["voices"]
        thorsten = next(v for v in voices if v["id"] == "de_DE-thorsten-medium")
        assert thorsten == {
            "id": "de_DE-thorsten-medium",
            "locale": "de_DE",
            "quality": "medium",
            "sample_rate": 22050,
            "model_license": "MIT",
            "dataset_license": "CC0-1.0",
        }

        response = client.post("/tts", json={"text": "Guten Abend."})
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("audio/wav")
        assert response.headers["x-hoshi-voice"] == "de_DE-thorsten-medium", (
            "der deutsche Default-Request traegt weiterhin GENAU diesen Stimmen-Header"
        )

    # Die Praesenz einer zweiten, verfuegbaren Stimme im Cache darf den
    # deutschen Pfad in KEINEM Feld veraendern — deshalb bewusst MIT kristin
    # im selben Testlauf gegengeprueft.
    _with_client(check, extra_voices={"en_US-kristin-medium": kristin_spec})


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
