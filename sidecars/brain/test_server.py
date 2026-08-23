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

import re
import sys
import time
from unittest.mock import patch

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
    "_switch_started_ts",
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
    server._switch_started_ts = None
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
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 409, response.text
        assert "Wechsel laeuft" in response.json()["detail"]
    _with_active_model(check)


# ── 409-Text traegt die Dauer ("seit Xs") — Haengen von Arbeiten unterscheidbar ─
def test_switch_model_409_includes_elapsed_duration():
    def check(client):
        server._switching = True
        server._switch_phase = "loading"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        server._switch_started_ts = time.time() - 30
        response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 409, response.text
        detail = response.json()["detail"]
        match = re.search(r"seit (\d+)s", detail)
        assert match is not None, detail
        assert int(match.group(1)) >= 30
    _with_active_model(check)


# ── Ziel hat keinen vollständigen v2-Lock -> 409, altes Modell unangetastet ──
def test_switch_model_missing_lock_is_409_and_leaves_model_untouched():
    def check(client):
        original_model, original_tok = server._model, server._tok
        with _Patch(_lookup_model_lock=lambda m: None):
            response = client.post("/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"})
        assert response.status_code == 409, response.text
        assert "v2-Lock" in response.json()["detail"]
        assert server._model is original_model
        assert server._tok is original_tok
        assert server._loaded is True
        assert server._switching is False
    _with_active_model(check)


def test_switch_model_accepts_only_locked_target_and_starts_verify_worker():
    def check(client):
        captured: dict = {}
        lock = {
            "id": "brain-e2b",
            "pinned_revision": "a" * 40,
            "license_acceptance": "gemma",
        }

        def capture_worker(target, selected_lock):
            captured["target"] = target
            captured["lock"] = selected_lock

        with _Patch(
            _lookup_model_lock=lambda model: lock,
            _start_verify_worker=capture_worker,
        ):
            response = client.post(
                "/switch-model",
                json={"model": "mlx-community/gemma-4-e2b-it-4bit"},
            )

        assert response.status_code == 202, response.text
        assert response.json() == {
            "status": "verifying",
            "model": "mlx-community/gemma-4-e4b-it-4bit",
            "target": "mlx-community/gemma-4-e2b-it-4bit",
            "changed": False,
        }
        assert captured == {
            "target": "mlx-community/gemma-4-e2b-it-4bit",
            "lock": lock,
        }
        assert server._switching is True
        assert server._switch_phase == "verifying"
    _with_active_model(check)


def test_switch_model_worker_start_failure_resets_state_and_keeps_old_model():
    def check(client):
        original_model = server._model
        lock = {
            "id": "brain-e2b",
            "pinned_revision": "a" * 40,
            "license_acceptance": "gemma",
        }

        def fail_start(target, selected_lock):
            raise RuntimeError("thread start kaputt")

        with _Patch(
            _lookup_model_lock=lambda model: lock,
            _start_verify_worker=fail_start,
        ):
            response = client.post(
                "/switch-model",
                json={"model": "mlx-community/gemma-4-e2b-it-4bit"},
            )

        assert response.status_code == 503, response.text
        detail = response.json()["detail"]
        assert "Verify-Worker konnte nicht gestartet werden" in detail
        assert server._model is original_model
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
        assert server._switch_target is None
        assert server._switch_started_ts is None
        assert server._switch_error == detail
    _with_active_model(check)


# ── Ziel fehlt/driftet -> Worker laesst altes Modell + Fetch-Hinweis ─────────
def test_verify_worker_unverified_leaves_model_untouched_and_names_fetch():
    def check(client):
        original_model = server._model
        lock = {
            "id": "brain-e2b",
            "pinned_revision": "a" * 40,
            "license_acceptance": "gemma",
        }
        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(_verify_model_artifact=lambda artifact_id: False):
            server._verify_and_swap("mlx-community/gemma-4-e2b-it-4bit", lock)
        assert "tools/verified_fetch.py fetch brain-e2b --accept-license gemma" in server._switch_error
        assert server._model is original_model
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
    _with_active_model(check)


# ── Vollhash PASS -> Reihenfolge verify -> entladen -> laden ─────────────────
def test_verify_worker_unloads_only_after_fullhash_pass():
    def check(client):
        call_order: list = []
        original_unload = server._unload_model

        def spy_unload():
            call_order.append("unload")
            original_unload()

        def fake_load(model_id):
            call_order.append(("load", model_id))
            return ("FAKE_MODEL", "FAKE_TOK")

        lock = {"id": "brain-e2b", "pinned_revision": "a" * 40}

        def verified(artifact_id):
            call_order.append(("verify", artifact_id))
            return True

        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(_verify_model_artifact=verified,
                    _unload_model=spy_unload, _load_model=fake_load):
            server._verify_and_swap("mlx-community/gemma-4-e2b-it-4bit", lock)

        assert call_order == [
            ("verify", "brain-e2b"),
            "unload",
            ("load", "mlx-community/gemma-4-e2b-it-4bit"),
        ]
        assert server._model == "FAKE_MODEL"
        assert server._tok == "FAKE_TOK"
        assert server._loaded is True
        assert server._switching is False
        assert server._switch_phase is None
    _with_active_model(check)


# ── Ladefehler: ehrlich kaputt melden, KEIN stiller Rueckfall ────────────────
def test_verify_worker_load_failure_marks_brain_honestly_unloaded():
    def check(client):
        def failing_load(model_id):
            raise RuntimeError("absichtlicher Testfehler")

        lock = {"id": "brain-e2b", "pinned_revision": "a" * 40}
        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(_verify_model_artifact=lambda artifact_id: True,
                    _load_model=failing_load):
            server._verify_and_swap("mlx-community/gemma-4-e2b-it-4bit", lock)

        assert server._model is None
        assert server._tok is None
        assert server._loaded is False
        assert server.MODEL_ID == "mlx-community/gemma-4-e2b-it-4bit"
        assert server._switching is False
        assert server._switch_error is not None
    _with_active_model(check)


class _FakeStuckLock:
    """Simuliert die haengende Generierung des realen Vorfalls (2026-07-25): eine
    Generierung haelt _GEN_LOCK fuer immer, acquire(timeout=...) liefert deshalb
    IMMER False — egal welcher Timeout uebergeben wird. Kein echtes Warten im Test."""

    def __init__(self):
        self.acquire_calls: list = []
        self.released = False

    def acquire(self, timeout=None):
        self.acquire_calls.append(timeout)
        return False

    def release(self):
        self.released = True


# ── Realer Vorfall: _GEN_LOCK.acquire() haengt -> ehrlicher 503-Abbruch ─────
# statt fuer immer blockieren. Kritisch: _unload_model()/_load_model() duerfen
# beim Timeout NIE aufgerufen werden (Reihenfolge-Check aus dem Auftrag) — am
# geladenen Zustand darf GAR NICHTS veraendert werden.
def test_verify_worker_lock_timeout_aborts_honestly_without_touching_loaded_model():
    def check(client):
        original_model, original_tok, original_model_id = (
            server._model, server._tok, server.MODEL_ID
        )
        fake_lock = _FakeStuckLock()

        def boom_unload():
            raise AssertionError("_unload_model() wurde trotz Lock-Timeout aufgerufen")

        def boom_load(model_id):
            raise AssertionError("_load_model() wurde trotz Lock-Timeout aufgerufen")

        lock = {"id": "brain-e2b", "pinned_revision": "a" * 40}
        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(_GEN_LOCK=fake_lock,
                    _verify_model_artifact=lambda artifact_id: True,
                    _unload_model=boom_unload, _load_model=boom_load):
            server._verify_and_swap("mlx-community/gemma-4-e2b-it-4bit", lock)

        assert f"{server._SWITCH_GEN_LOCK_TIMEOUT_S}s" in server._switch_error
        assert "Wechsel abgebrochen" in server._switch_error
        assert "bleibt/blieb geladen" in server._switch_error
        # GAR NICHTS am geladenen Zustand veraendert:
        assert server._model is original_model
        assert server._tok is original_tok
        assert server.MODEL_ID == original_model_id
        assert server._loaded is True
        # Wechsel-Zustand ehrlich zurueckgesetzt, nicht fuer immer "switching":
        assert server._switching is False
        assert server._switch_phase is None
        assert server._switch_target is None
        assert server._switch_started_ts is None
        assert server._switch_error is not None
        assert "Sperre" in server._switch_error
        # Lock wurde NIE erlangt -> darf auch nicht released werden.
        assert fake_lock.released is False
        assert fake_lock.acquire_calls == [server._SWITCH_GEN_LOCK_TIMEOUT_S]
    _with_active_model(check)


# ── /health: Dead-Man-Sichtbarkeit erst ueber der Schwelle, KEINE Auto-Raeumung ─
def test_health_reports_switch_stuck_seconds_only_past_threshold():
    def check(client):
        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"

        # Unterhalb der Schwelle: normales Arbeiten, kein falscher Alarm.
        server._switch_started_ts = time.time() - 5
        response = client.get("/health")
        assert response.json()["switch_stuck_seconds"] is None

        # Ueber der Schwelle: sichtbar in /health, aber State bleibt unangetastet
        # (keine stille Auto-Raeumung — nur Sichtbarkeit fuer Watchdog/heal).
        server._switch_started_ts = time.time() - (server._SWITCH_STUCK_THRESHOLD_S + 5)
        response = client.get("/health")
        body = response.json()
        assert body["switch_stuck_seconds"] is not None
        assert body["switch_stuck_seconds"] >= server._SWITCH_STUCK_THRESHOLD_S
        assert server._switching is True
        assert server._switch_phase == "verifying"
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


# ── waehrend des Vollhashs bedient das alte Modell normal weiter ────────────
def test_chat_does_not_503_during_verifying_phase():
    class _PastGuardMarker(Exception):
        pass

    def boom(*_args, **_kwargs):
        raise _PastGuardMarker("Guard liess die Anfrage durch bis build_prompt()")

    def check(client):
        server._switching = True
        server._switch_phase = "verifying"
        server._switch_target = "mlx-community/gemma-4-e2b-it-4bit"
        with _Patch(build_prompt=boom):
            try:
                client.post("/v1/chat", json={"messages": [{"role": "user", "content": "Hallo"}]})
            except _PastGuardMarker:
                pass  # erwartet: Guard hat NICHT 503't, Request kam bis build_prompt() durch
            else:
                raise AssertionError(
                    "build_prompt()-Sabotage wurde nie erreicht — der 503-Guard "
                    "griff faelschlich auch waehrend der reinen Verify-Phase"
                )
    _with_active_model(check)


# ── Der Verify-Subprozess ist exakt, offline und mutiert nichts ─────────────
def test_verify_model_artifact_calls_only_offline_verify():
    captured: dict = {}

    class Result:
        returncode = 0

    def fake_run(command, **kwargs):
        captured["command"] = command
        captured["kwargs"] = kwargs
        return Result()

    with patch.object(server.subprocess, "run", fake_run):
        assert server._verify_model_artifact("brain-e2b") is True

    assert captured["command"] == [
        sys.executable,
        server._VERIFIED_FETCH_PATH,
        "verify",
        "brain-e2b",
    ]
    assert captured["kwargs"]["env"]["HF_HUB_OFFLINE"] == "1"
    assert captured["kwargs"]["cwd"] == server._REPO_ROOT
    assert captured["kwargs"]["timeout"] == server._MODEL_VERIFY_TIMEOUT_S
    assert captured["kwargs"]["check"] is False


def test_verify_model_artifact_timeout_is_fail_closed():
    with patch.object(
        server.subprocess,
        "run",
        side_effect=server.subprocess.TimeoutExpired(["verify"], 1),
    ):
        assert server._verify_model_artifact("brain-e2b") is False


def test_real_model_lock_maps_repo_to_unchanged_full_pin():
    lock = server._lookup_model_lock("mlx-community/gemma-4-e2b-it-4bit")
    assert lock is not None
    assert lock["id"] == "brain-e2b"
    assert lock["pinned_revision"] == "2c3e507453b4f218d05fe3cc97bea5c5a654257e"
    assert lock["license_acceptance"] == "gemma"


# ── memory-Feld (Andi-Auftrag 2026-07-25/26): Schwellen, Cache, ehrliches Fehlen ──
# Realer Vorfall (2x, 25./26.07.): das 12B druckte den Mac auf <500 MB frei + vollen
# Swap, Whisper transkribierte 45s lang nichts bei GRÜNEM Health. Diese Tests decken
# genau das ab, was der Auftrag verlangt: Schwellen, Caching, sysctl-Fehler ⇒ Feld
# fehlt statt Lüge — plus der /health-Kontrakt, den BrainMemoryHeuristic (BE) liest.

def _raw_mem(free_mb, inactive_mb, swap_total_mb=None, swap_used_mb=None, compressor_pages=0) -> dict:
    """Test-Fixture: baut das raw-dict nach, das _read_vm_stat_and_swap() liefern würde."""
    return {
        "free_bytes": int(free_mb * 1024 * 1024),
        "inactive_bytes": int(inactive_mb * 1024 * 1024),
        "compressor_pages": compressor_pages,
        "swap_total_mb": swap_total_mb,
        "swap_used_mb": swap_used_mb,
    }


def test_classify_memory_ok_when_free_and_inactive_are_plenty():
    raw = _raw_mem(free_mb=2000, inactive_mb=3000, swap_total_mb=8192, swap_used_mb=1000)
    result = server._classify_memory(raw, compressor_growing=False)
    assert result["level"] == "ok", result
    assert "entspannt" in result["detail"]


def test_classify_memory_warn_below_free_plus_inactive_threshold():
    # 800 MB frei + 400 MB inaktiv = 1,2 GB < 1,5-GB-Schwelle, Swap unauffällig.
    raw = _raw_mem(free_mb=800, inactive_mb=400, swap_total_mb=8192, swap_used_mb=1000)
    result = server._classify_memory(raw, compressor_growing=False)
    assert result["level"] == "warn", result
    assert "knapp" in result["detail"]


def test_classify_memory_ok_andis_case_16gb_high_swap_but_healthy_free_plus_inactive():
    # Andi-Befund 2026-08-19: 16-GB-Mac, 5874 MB frei+inaktiv, Swap 60 % belegt —
    # das war die falsche Warnung (Swap ALLEIN > 50 % reichte vorher). Hoher Swap
    # ist bei reichlich frei+inaktiv normal (macOS lagert Idle-Pages aus) und
    # zählt jetzt nur noch zusammen mit knappem frei+inaktiv (< 3 GB).
    raw = _raw_mem(free_mb=3000, inactive_mb=2874, swap_total_mb=16384, swap_used_mb=9830)
    result = server._classify_memory(raw, compressor_growing=False)
    assert result["level"] == "ok", result
    assert round(result["free_inactive_mb"]) == 5874
    assert round(result["swap_used_pct"]) == 60


def test_classify_memory_warn_combined_high_swap_and_tight_free_plus_inactive():
    # Echter Druck: Swap > 85 % UND frei+inaktiv < 3 GB gleichzeitig -> warn.
    raw = _raw_mem(free_mb=1200, inactive_mb=1200, swap_total_mb=16384, swap_used_mb=14500)
    result = server._classify_memory(raw, compressor_growing=False)
    assert result["level"] == "warn", result
    assert "Swap" in result["detail"]


def test_classify_memory_critical_needs_low_free_and_growing_compressor():
    # Anker: realer Vorfall — 477 MB frei / 5,4 GB Swap war 'critical'.
    raw = _raw_mem(free_mb=477, inactive_mb=50, swap_total_mb=8192, swap_used_mb=5400)
    result = server._classify_memory(raw, compressor_growing=True)
    assert result["level"] == "critical", result
    assert "Kritischer" in result["detail"]


def test_classify_memory_low_free_but_stable_compressor_is_not_critical():
    # Gleiche Werte wie im Vorfall, aber der Kompressor WÄCHST NICHT (dauerhaft
    # knapper, aber STABILER Wert) → darf NICHT critical sein (Fehlalarm-Schutz,
    # s. Kommentar bei _MEM_CACHE_S). 527 MB frei+inaktiv < 1,5 GB → fällt auf warn.
    raw = _raw_mem(free_mb=477, inactive_mb=50, swap_total_mb=8192, swap_used_mb=5400)
    result = server._classify_memory(raw, compressor_growing=False)
    assert result["level"] == "warn", result


def test_memory_status_returns_none_when_measurement_fails():
    """sysctl/vm_stat-Fehler ⇒ Feld fehlt (None), NIE eine erfundene Zahl."""
    with _Patch(_mem_cache=None, _mem_cache_ts=0.0, _read_vm_stat_and_swap=lambda: None):
        assert server._memory_status() is None


def test_read_vm_stat_and_swap_returns_none_on_subprocess_error():
    """Die echte Subprozess-Kette: ein sysctl/vm_stat-Absturz darf NIE crashen —
    nur ehrlich None liefern."""
    with patch("subprocess.run", side_effect=RuntimeError("kein vm_stat auf diesem System")):
        assert server._read_vm_stat_and_swap() is None


def test_memory_status_is_cached_within_the_window():
    calls = {"n": 0}

    def fake_read():
        calls["n"] += 1
        return _raw_mem(free_mb=2000, inactive_mb=2000)

    with _Patch(_mem_cache=None, _mem_cache_ts=0.0, _mem_prev_compressor_pages=None,
                _read_vm_stat_and_swap=fake_read):
        first = server._memory_status()
        second = server._memory_status()  # sofort danach -> noch im 5s-Fenster
        assert first is not None and second is not None
        assert calls["n"] == 1, "zweiter Call innerhalb des Cache-Fensters darf NICHT neu messen"


def test_memory_status_refreshes_after_cache_window_elapses():
    calls = {"n": 0}

    def fake_read():
        calls["n"] += 1
        return _raw_mem(free_mb=2000, inactive_mb=2000)

    with _Patch(_mem_cache=None, _mem_cache_ts=0.0, _mem_prev_compressor_pages=None,
                _read_vm_stat_and_swap=fake_read):
        server._memory_status()
        assert calls["n"] == 1
        # Cache künstlich abgelaufen setzen (statt real 5s zu schlafen).
        server._mem_cache_ts = time.time() - (server._MEM_CACHE_S + 1)
        server._memory_status()
        assert calls["n"] == 2, "nach Ablauf des Cache-Fensters muss neu gemessen werden"


def test_memory_status_detects_compressor_growth_across_calls():
    """Der Wachstums-Vergleich braucht zwei Messungen — bei der 1. Messung gibt es noch
    keine Vorgeschichte (growing=False, kein Fehlalarm auf dem allerersten Call).
    Wächst der Kompressor zur 2. Messung, wird das im Ergebnis sichtbar und hebt
    (bei niedrigem frei) den Level auf critical."""
    pages = {"n": 100}

    def fake_read():
        return _raw_mem(free_mb=100, inactive_mb=50, compressor_pages=pages["n"])

    with _Patch(_mem_cache=None, _mem_cache_ts=0.0, _mem_prev_compressor_pages=None,
                _read_vm_stat_and_swap=fake_read):
        first = server._memory_status()
        assert first["compressor_growing"] is False, "erste Messung hat keine Vorgeschichte"
        assert first["level"] == "warn", first  # < 500 MB frei, aber noch nicht als Trend belegt
        server._mem_cache_ts = time.time() - (server._MEM_CACHE_S + 1)  # Cache erzwungen ablaufen lassen
        pages["n"] = 500  # Kompressor ist gewachsen
        second = server._memory_status()
        assert second["compressor_growing"] is True
        assert second["level"] == "critical", "< 500 MB frei + wachsender Kompressor -> critical"


def test_safe_memory_status_never_raises():
    """Der /health-Aufrufer: ein unerwarteter Fehler in der Kette darf /health NIE crashen."""
    def boom():
        raise RuntimeError("unerwarteter Fehler in der Klassifikation")

    with _Patch(_memory_status=boom):
        assert server._safe_memory_status() is None


# ── /health trägt das memory-Feld (BE-Kontrakt: BrainMemoryHeuristic liest es zuerst) ──

def test_health_includes_memory_field_from_safe_memory_status():
    def check(client):
        canned = {"level": "warn", "detail": "RAM wird knapp: 900 MB frei+inaktiv."}
        with _Patch(_safe_memory_status=lambda: canned):
            response = client.get("/health")
        assert response.status_code == 200, response.text
        assert response.json()["memory"] == canned
    _with_active_model(check)


def test_health_memory_field_is_none_when_measurement_unavailable():
    def check(client):
        with _Patch(_safe_memory_status=lambda: None):
            response = client.get("/health")
        assert response.status_code == 200, response.text
        assert response.json()["memory"] is None
    _with_active_model(check)


# ── HOSHI_SIDECAR_TOKEN-Wand (Codex-Sicherheits-P0 2026-07-27) ──────────────
# server.py liest _HOSHI_SIDECAR_TOKEN EINMAL beim Modul-Import aus os.environ
# (leer im Testlauf, da die Var beim Testrun nicht gesetzt ist) — die Tests
# patchen deshalb direkt das Modul-Attribut statt os.environ (wie der uebrige
# State in diesem File, s. _Patch oben), sonst wuerde ein nachtraeglich
# gesetztes os.environ.setdefault() den bereits gelesenen Wert nicht mehr
# aendern.

def test_token_wall_open_without_token_when_env_empty():
    """Leer/ungesetzt (Default) ⇒ heutiges offenes Verhalten, NULL Aenderung —
    kein X-Hoshi-Token-Header noetig, auch nicht fuer Nicht-/health-Pfade."""
    def check(client):
        assert server._HOSHI_SIDECAR_TOKEN == "", "Testvoraussetzung: Token-Wand ist im Testlauf aus"
        response = client.post("/switch-model", json={"model": server.MODEL_ID})
        assert response.status_code == 200, response.text
    _with_active_model(check)


def test_token_wall_rejects_missing_or_wrong_token_with_401_when_set():
    def check(client):
        with _Patch(_HOSHI_SIDECAR_TOKEN="geheimwert-test"):
            missing = client.post("/switch-model", json={"model": server.MODEL_ID})
            assert missing.status_code == 401, missing.text
            assert missing.json() == {"detail": "unauthorized"}

            wrong = client.post(
                "/switch-model", json={"model": server.MODEL_ID},
                headers={"X-Hoshi-Token": "falscher-wert"},
            )
            assert wrong.status_code == 401, wrong.text

            correct = client.post(
                "/switch-model", json={"model": server.MODEL_ID},
                headers={"X-Hoshi-Token": "geheimwert-test"},
            )
            assert correct.status_code == 200, correct.text
    _with_active_model(check)


def test_token_wall_never_blocks_health_even_when_token_is_set():
    def check(client):
        with _Patch(_HOSHI_SIDECAR_TOKEN="geheimwert-test"):
            response = client.get("/health")
        assert response.status_code == 200, response.text
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
