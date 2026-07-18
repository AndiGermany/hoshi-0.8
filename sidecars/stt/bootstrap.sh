#!/usr/bin/env bash
# sidecars/stt/bootstrap.sh — legt das venv an + installiert die gepinnten
# requirements.txt. Idempotent (mehrfach aufrufbar, ueberspringt was schon da
# ist) und ehrlich (bricht laut ab statt still ein kaputtes venv zu hinterlassen).
#
# [0.8-Port] Pattern uebernommen aus sidecars/brain/bootstrap.sh (selbst nach
# dem Vorbild-Adapter hoshi-speaker-id/setup.sh gebaut) — python-Praeferenz +
# venv + pip install + Trust-but-verify-Import. KEIN Modell-Download hier: das
# Whisper-Gewicht kommt ueber den HuggingFace-Cache (mlx_whisper laedt es beim
# ersten Request lazy via huggingface_hub), nicht ueber pip.
#
# Aufruf: sidecars/stt/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[bootstrap] $*"; }

# ── ffmpeg-Voraussetzung (server.py::_convert_to_wav ruft es als Subprozess) ─
command -v ffmpeg >/dev/null 2>&1 \
    || fail "ffmpeg fehlt (server.py braucht es fuer die Audio-Konvertierung) — installieren: brew install ffmpeg"

# ── Python-Version waehlen ────────────────────────────────────────────────
# Das Quell-venv (Hoshi_0.5/hoshi-stt-mlx) lief auf python3.14.6 (Homebrew-
# Default zum Pin-Zeitpunkt, 2026-06-08) — ANDERS als sidecars/brain/speaker,
# die auf python3.11 gepinnt sind. Das ist eine ehrliche Abbildung des realen
# Quell-venv, nicht eine bewusste Architektur-Entscheidung: mlx-whisper 0.4.3
# lief dort nachweislich auf 3.14. python3.14 bevorzugt, mit Fallback-Kaskade
# und LAUTER Warnung bei Abweichung (andere Minor-Version = andere Wheel-
# Kompatibilitaet fuer mlx/mlx-metal/torch, genau der Drift den Pins vermeiden
# sollen).
PY=python3.14
if ! command -v "$PY" >/dev/null 2>&1; then
    if command -v python3.11 >/dev/null 2>&1; then
        PY=python3.11
    elif command -v python3 >/dev/null 2>&1; then
        PY=python3
    else
        fail "weder python3.14 noch python3.11 noch python3 gefunden — Python fehlt komplett"
    fi
    got="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    echo "[bootstrap] WARN: python3.14 nicht gefunden, falle zurueck auf $PY ($got)." >&2
    echo "[bootstrap] WARN: das Quell-venv lief auf 3.14.6 — bei mlx/torch-Ladefehlern" >&2
    echo "[bootstrap] WARN: zuerst python3.14 installieren (brew install python@3.14)." >&2
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
say "pip install -r requirements.txt (mlx-whisper zieht torch mit — kann einige Minuten dauern)"
"$VENV_PY" -m pip install -q --upgrade pip \
    || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt \
    || fail "pip install -r requirements.txt fehlgeschlagen — s. Fehler oben. Netz da? Apple Silicon fuer mlx-*?"

# ── Trust-but-verify: die KRITISCHEN Pakete muessen wirklich importierbar sein ─
say "verifiziere Kern-Imports (mlx_whisper, numpy, fastapi, uvicorn)"
"$VENV_PY" -c "import mlx_whisper, numpy, fastapi, uvicorn" \
    || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar. Nicht mit System-Python ausweichen."

say "OK — venv bereit: $VENV_PY"
say "naechster Schritt: ./run.sh (startet den STT-Sidecar auf Port 9001)"
