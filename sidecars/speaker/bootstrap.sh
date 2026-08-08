#!/usr/bin/env bash
# sidecars/speaker/bootstrap.sh — legt das venv an, installiert die gepinnten
# requirements.txt UND laedt das CAM++-ONNX-Modell (hash-/groessen-verifiziert).
# Idempotent (mehrfach aufrufbar, ueberspringt was schon da ist) und ehrlich
# (bricht laut ab statt still ein kaputtes venv/Modell zu hinterlassen).
#
# [0.8-Port] venv-Teil nach dem Muster aus sidecars/brain/bootstrap.sh. Das
# Modell holt der zentrale verified_fetch aus dem v2-Lock: volle Revision,
# Bytegroesse, SHA-256, Resume und atomare Aktivierung stammen aus genau einer
# Wahrheit statt aus einem zweiten Bootstrap-Downloadpfad.
#
# Aufruf: sidecars/speaker/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[bootstrap] $*"; }

# ── Repo-Root finden (verified_fetch + Lockfiles leben dort) ───────────────
REPO_ROOT="$(cd "$(pwd)/../.." && pwd)"
[ -f "$REPO_ROOT/models.json" ] || fail "models.json nicht gefunden: $REPO_ROOT/models.json"

# ── Python-Version waehlen ────────────────────────────────────────────────
# Das Quell-venv (Hoshi_0.5/hoshi-speaker-id) lief auf python3.11.15 (setup.sh
# bevorzugte explizit python3.11) — identische Praeferenz wie sidecars/brain.
PY=python3.11
if ! command -v "$PY" >/dev/null 2>&1; then
    command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden — Python fehlt komplett"
    PY=python3
    got="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    echo "[bootstrap] WARN: python3.11 nicht gefunden, falle zurueck auf $PY ($got)." >&2
    echo "[bootstrap] WARN: das Quell-venv lief auf 3.11.15 — bei onnxruntime/kaldi-native-fbank-" >&2
    echo "[bootstrap] WARN: Ladefehlern zuerst python3.11 installieren (brew install python@3.11)." >&2
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
say "verifiziere Kern-Imports (onnxruntime, kaldi_native_fbank, soundfile, fastapi)"
"$VENV_PY" -c "import onnxruntime, kaldi_native_fbank, soundfile, fastapi, uvicorn" \
    || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar. Nicht mit System-Python ausweichen."

# ── Modell: zentraler, gelockter Downloadvertrag ──────────────────────────
say "hole/verifiziere speaker-campplus ueber tools/verified_fetch.py"
"$VENV_PY" "$REPO_ROOT/tools/verified_fetch.py" fetch speaker-campplus \
    || fail "speaker-campplus konnte nicht gelockt geladen/verifiziert werden"

say "OK — venv + Modell bereit: $VENV_PY, models/voxceleb_CAM++.onnx"
say "naechster Schritt: ./run.sh (startet den Speaker-ID-Sidecar auf Port 9002)"
