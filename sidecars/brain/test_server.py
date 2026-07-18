"""Standalone-faehige Contract-Tests fuer POST /switch-model (+ /v1/chat-Guard).

Brauchen KEIN echtes Gemma-4-Modell/GPU-Load: server.py ruft mlx_lm.load()
ausschliesslich ueber die injizierbare _load_model()-Funktion auf (main() beim
Start, _do_swap() beim Wechsel) — Tests patchen genau diese Stelle. mlx/mlx_lm
selbst werden ECHT importiert (dieses .venv hat sie, Apple-Silicon-Mac); nur der
schwere load()-Call wird nie wirklich ausgefuehrt.

Kein pytest im .venv installiert (Stand 2026-07-20) -> Muster wie
sidecars/piper/test_server.py: reine test_*()-Funktionen + eigener
if __name__ == "__main__"-Runner. Laeuft trotzdem unter `pytest` (falls
vorhanden), da pytest plain assert-Funktionen collectet.
"""
from __future__ import annotations

import sys

from fastapi.testclient import TestClient

import server


class _Patch:
    """Monkeypatch-Helfer: merkt sich Original-Attribute von `server` und stellt
    sie beim Verlassen des with-Blocks wieder her (kein pytest-monkeypatch-
    Fixture verfuegbar, s. Modul-Docstring)."""

    def __init__(self, **attrs):
        self._attrs = attrs
        self._originals: dict = {}

    def __enter__(self):
        for name, value in self._attrs.items():
            self._originals[name] = getattr(server, name)
            setattr(server, name, value)
        return self

    def __exit__(self, *exc_info):
        for name, value in self._originals.items():
            setattr(server, name, value)
        return False


_STATE_ATTRS = [
    "_model", "_tok", "_loaded", "MODEL_ID",
    "_switching", "_switch_phase", "_switch_target", "_switch_error",
]


def _with_active_model(fn, model_id: str = "mlx-community/gemma-4-e4b-it-4bit"):
    """Simuliert einen bereits geladenen, ruhigen Brain (kein echtes MLX-Modell
    noetig — /switch-model und der /v1/chat-Guard pruefen nur _loaded/_model-
    Wahrheit, nicht deren Typ). Stellt den kompletten Modell-/Wechsel-State nach
    dem Test wieder her (Tests laufen im selben Prozess nacheinander)."""
    snapshot = {name: getattr(server, name) for name in _STATE_ATTRS}
    server._model = object()  # Platzhalter fuer "irgendein geladenes Modell"
    server._tok = object()
    server._loaded = True
    server.MODEL_ID = model_id
    server._switching = False
    server._switch_phase = None
    server._switch_target = None
    server._switch_error = None
    client = TestClient(server.app)
    try:
        fn(client)
    finally:
        for name, value in snapshot.items():
            setattr(server, name, value)


# ── Whitelist ────────────────────────────────────────────────────────────────
def test_switch_model_rejects_unknown_model_with_422():
    def check(client):
        response = client.post("/switch-model", json={"model": "mlx-community/nicht-erlaubt"})
        assert response.status_code == 422, response.text
        assert "nicht erlaubt" in response.json()["detail"]
    _with_active_model(check)


# ── Bereits aktiv ────────────────────────────────────────────────────────────
def test_switch_model_already_active_is_a_noop_200():
    def check(client):
        response = client.post("/switch-model", json={"model": server.MODEL_ID})
        assert response.status_code == 200, response.text
        assert response.json() == {"status": "ok", "model": server.MODEL_ID, "changed": False}
    _with_active_model(check)


# ── Doppel-POST waehrend eines laufenden Wechsels ───────────────────────────
def test_switch_model_second_call_while_switching_is_409():
    def check(client):
        server._switching = True
        server._switch_phase = "downloading"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 409, response.text
        assert "Wechsel laeuft" in response.json()["detail"]
    _with_active_model(check)


# ── Ziel fehlt im Cache UND hat keinen Pin -> 409, altes Modell unangetastet ──
def test_switch_model_missing_pin_is_409_and_leaves_model_untouched():
    def check(client):
        original_model, original_tok = server._model, server._tok
        with _Patch(_model_fully_cached=lambda m: False, _lookup_pinned_revision=lambda m: None):
            response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 409, response.text
        assert "Pin" in response.json()["detail"]
        assert server._model is original_model
        assert server._tok is original_tok
        assert server._loaded is True
        assert server._switching is False
    _with_active_model(check)


# ── Ziel fehlt im Cache, Pin da, aber zu wenig Platz -> 507, nichts angefasst ─
def test_switch_model_low_disk_is_507_and_leaves_model_untouched():
    def check(client):
        original_model = server._model
        with _Patch(_model_fully_cached=lambda m: False,
                    _lookup_pinned_revision=lambda m: "deadbeefpin",
                    _free_disk_bytes=lambda: 1024):
            response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 507, response.text
        assert "GB" in response.json()["detail"]
        assert server._model is original_model
        assert server._switching is False
    _with_active_model(check)


# ── Ziel schon vollstaendig im Cache -> synchroner Tausch, Reihenfolge entladen->laden
def test_switch_model_cached_target_unloads_then_loads_in_order():
    def check(client):
        call_order: list = []
        original_unload = server._unload_model

        def spy_unload():
            call_order.append("unload")
            original_unload()

        def fake_load(model_id):
            call_order.append(("load", model_id))
            return ("FAKE_MODEL", "FAKE_TOK")

        with _Patch(_model_fully_cached=lambda m: True,
                    _unload_model=spy_unload, _load_model=fake_load):
            response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})

        assert response.status_code == 200, response.text
        body = response.json()
        assert body["status"] == "ok"
        assert body["model"] == "mlx-community/gemma-4-e2b-it-4bit"
        assert body["changed"] is True
        assert isinstance(body["loadMs"], int)
        assert call_order == ["unload", ("load", "mlx-community/gemma-4-e2b-it-4bit")]
        assert server._model == "FAKE_MODEL"
        assert server._tok == "FAKE_TOK"
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
    _with_active_model(check)


# ── Ladefehler: ehrlich kaputt melden, KEIN stiller Rueckfall ────────────────
def test_switch_model_load_failure_is_honest_500_and_marks_unloaded():
    def check(client):
        def failing_load(model_id):
            raise RuntimeError("absichtlicher Testfehler")

        with _Patch(_model_fully_cached=lambda m: True, _load_model=failing_load):
            response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})

        assert response.status_code == 500, response.text
        assert "fehlgeschlagen" in response.json()["detail"]
        assert server._model is None
        assert server._tok is None
        assert server._loaded is False
        assert server.MODEL_ID == "mlx-community/gemma-4-e2b-it-4bit"
        assert server._switching is False
        assert server._switch_error is not None
    _with_active_model(check)


# ── /v1/chat lehnt NUR waehrend der echten Lade-Phase ab ────────────────────
def test_chat_rejects_with_503_during_loading_phase():
    def check(client):
        server._switching = True
        server._switch_phase = "loading"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        response = client.post("/v1/chat", json={"messages": [{"role": "user", "content": "Hallo"}]})
        assert response.status_code == 503, response.text
        assert "wechselt" in response.json()["detail"]
    _with_active_model(check)


# ── waehrend eines Hintergrund-Downloads bedient das alte Modell normal weiter
def test_chat_does_not_503_during_download_phase():
    class _PastGuardMarker(Exception):
        pass

    def boom(*_args, **_kwargs):
        raise _PastGuardMarker("Guard liess die Anfrage durch bis build_prompt()")

    def check(client):
        server._switching = True
        server._switch_phase = "downloading"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(build_prompt=boom):
            try:
                client.post("/v1/chat", json={"messages": [{"role": "user", "content": "Hallo"}]})
            except _PastGuardMarker:
                pass  # erwartet: Guard hat NICHT 503't, Request kam bis build_prompt() durch
            else:
                raise AssertionError(
                    "build_prompt()-Sabotage wurde nie erreicht — der 503-Guard "
                    "griff faelschlich auch waehrend der reinen Download-Phase"
                )
    _with_active_model(check)


# ── Hintergrund-Download: NUR gegen den gepinnten Snapshot, dann Tausch ─────
def test_download_and_swap_uses_pinned_revision_then_swaps():
    def check(client):
        calls: dict = {}

        def fake_snapshot_download(repo_id, revision=None):
            calls["download"] = (repo_id, revision)
            return "/fake/cache/path"

        def fake_load(model_id):
            return ("FAKE_MODEL2", "FAKE_TOK2")

        with _Patch(snapshot_download=fake_snapshot_download,
                    _model_fully_cached=lambda m: True,  # Cache-Recheck NACH dem Download: ok
                    _load_model=fake_load):
            server._switching = True
            server._switch_phase = "downloading"
            server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
            server._download_and_swap("mlx-community/gemma-4-e2b-it-4bit", "deadbeefpin")

        assert calls["download"] == ("mlx-community/gemma-4-e2b-it-4bit", "deadbeefpin")
        assert server._model == "FAKE_MODEL2"
        assert server._tok == "FAKE_TOK2"
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
    _with_active_model(check)


# ── Download-Fehler: altes Modell laeuft unveraendert weiter ────────────────
def test_download_and_swap_failure_leaves_old_model_untouched():
    def check(client):
        original_model, original_tok, original_model_id = (
            server._model, server._tok, server.MODEL_ID
        )

        def failing_snapshot_download(repo_id, revision=None):
            raise RuntimeError("Netzwerk weg")

        with _Patch(snapshot_download=failing_snapshot_download):
            server._switching = True
            server._switch_phase = "downloading"
            server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
            server._download_and_swap("mlx-community/gemma-4-e2b-it-4bit", "deadbeefpin")

        assert server._model is original_model
        assert server._tok is original_tok
        assert server.MODEL_ID == original_model_id
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
        assert "Download fehlgeschlagen" in server._switch_error
    _with_active_model(check)


if __name__ == "__main__":
    tests = [(name, fn) for name, fn in sorted(globals().items())
              if name.startswith("test_") and callable(fn)]
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
