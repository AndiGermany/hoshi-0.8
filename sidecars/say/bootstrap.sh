#!/usr/bin/env bash
# sidecars/say/bootstrap.sh — legt das venv an + installiert die gepinnten
# requirements.txt (FastAPI/uvicorn — reiner Webserver-Unterbau, s. requirements.txt).
# Idempotent (mehrfach aufrufbar, ueberspringt was schon da ist) und ehrlich
# (bricht laut ab statt still ein kaputtes venv zu hinterlassen).
#
# Muster uebernommen aus sidecars/stt/bootstrap.sh (KEIN Modell-Download noetig —
# hier sogar KEIN ML-Modell ueberhaupt: server.py ruft nur /usr/bin/say +
# /usr/bin/afconvert als Subprozess, macOS-Bordmittel). Neu hier: die
# say/afconvert-Preflight-Pruefung, weil dieser Sidecar NUR auf macOS lauffaehig
# ist (anders als FastAPI/uvicorn selbst, die ueberall liefen).
#
# Aufruf: sidecars/say/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[bootstrap] $*"; }

# ── macOS-Bordmittel-Preflight (server.py::synth_wav ruft beide als Subprozess) ─
[ -x /usr/bin/say ] \
    || fail "/usr/bin/say fehlt/nicht ausfuehrbar — dieser Sidecar laeuft NUR auf macOS"
[ -x /usr/bin/afconvert ] \
    || fail "/usr/bin/afconvert fehlt/nicht ausfuehrbar — dieser Sidecar laeuft NUR auf macOS"
say "macOS-Bordmittel gefunden: /usr/bin/say, /usr/bin/afconvert"

# ── Python-Version waehlen ────────────────────────────────────────────────
# Keine ML-Runtime-Abhaengigkeit (kein mlx/torch/onnxruntime) — anders als die
# Geschwister-Sidecars ist die Python-Minor-Version hier UNKRITISCH (FastAPI/
# uvicorn/pydantic sind auf jeder halbwegs aktuellen 3.x-Version stabil).
# python3.11 trotzdem als erste Wahl (Konsistenz zu sidecars/brain/speaker).
PY=python3.11
if ! command -v "$PY" >/dev/null 2>&1; then
    command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden — Python fehlt komplett"
    PY=python3
    say "python3.11 nicht gefunden, falle zurueck auf $PY ($("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])'))"
fi
say "Python: $("$PY" --version 2>&1) ($PY)"

# ── venv anlegen (idempotent) ─────────────────────────────────────────────
if [ -d .venv ]; then
    if [ -x .venv/bin/python ]; then
        say "venv existiert schon (.venv) — skip create"
    else
        fail ".venv existiert, ist aber kaputt (kein .venv/bin/python) — erst 'rm -rf .venv' dann neu bootstrappen"
    fi
else
    say "lege .venv an ($PY)"
    "$PY" -m venv .venv || fail "venv-Erstellung fehlgeschlagen"
fi

VENV_PY=".venv/bin/python"
[ -x "$VENV_PY" ] || fail "venv-Python fehlt nach Anlage: $VENV_PY"

# ── requirements installieren ─────────────────────────────────────────────
say "pip install -r requirements.txt"
"$VENV_PY" -m pip install -q --upgrade pip \
    || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt \
    || fail "pip install -r requirements.txt fehlgeschlagen — s. Fehler oben. Netz da?"

# ── Trust-but-verify: die KRITISCHEN Pakete muessen wirklich importierbar sein ─
say "verifiziere Kern-Imports (fastapi, uvicorn, pydantic)"
"$VENV_PY" -c "import fastapi, uvicorn, pydantic" \
    || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar. Nicht mit System-Python ausweichen."

say "OK — venv bereit: $VENV_PY"
say "naechster Schritt: ./run.sh (startet den say-TTS-Sidecar auf Port 8044)"
say "Beweis ohne Server: .venv/bin/python server.py --selftest"
