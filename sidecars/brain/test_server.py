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
        server._switch_phase = "downloading"
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
def test_do_swap_lock_timeout_aborts_honestly_without_touching_loaded_model():
    def check(client):
        original_model, original_tok, original_model_id = (
            server._model, server._tok, server.MODEL_ID
        )
        fake_lock = _FakeStuckLock()

        def boom_unload():
            raise AssertionError("_unload_model() wurde trotz Lock-Timeout aufgerufen")

        def boom_load(model_id):
            raise AssertionError("_load_model() wurde trotz Lock-Timeout aufgerufen")

        with _Patch(_GEN_LOCK=fake_lock, _model_fully_cached=lambda m: True,
                    _unload_model=boom_unload, _load_model=boom_load):
            response = client.post(
                "/switch-model", json={"model": "mlx-community/gemma-4-e2b-it-4bit"}
            )

        assert response.status_code == 503, response.text
        detail = response.json()["detail"]
        assert f"{server._SWITCH_GEN_LOCK_TIMEOUT_S}s" in detail
        assert "Wechsel abgebrochen" in detail
        assert "bleibt/blieb geladen" in detail
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
        server._switch_phase = "downloading"
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
        assert server._switch_phase == "downloading"
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


def test_classify_memory_warn_high_swap_pct_even_with_healthy_free_plus_inactive():
    # frei+inaktiv reichlich (4 GB), aber Swap zu >50% belegt.
    raw = _raw_mem(free_mb=2000, inactive_mb=2000, swap_total_mb=8192, swap_used_mb=5000)
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
