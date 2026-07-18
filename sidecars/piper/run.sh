#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# sidecars/piper/run.sh — startet den optionalen lokalen Piper-TTS-Sidecar.
set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
PIPER_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

fail() { echo "[piper-run] FATAL: $*" >&2; exit 1; }
say_() { echo "[piper-run] $*" >&2; }

VENV_PY="$PIPER_DIR/.venv/bin/python"
SERVER_PY="$PIPER_DIR/server.py"
[ -x "$VENV_PY" ] || fail ".venv fehlt — zuerst sidecars/piper/bootstrap.sh"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"
"$VENV_PY" -c "import onnxruntime, piper" >/dev/null 2>&1 \
    || fail "Piper-Runtime unvollstaendig — bootstrap.sh erneut ausfuehren"

export HOSHI_PIPER_HOST="${HOSHI_PIPER_HOST:-0.0.0.0}"
export HOSHI_PIPER_PORT="${HOSHI_PIPER_PORT:-8045}"
export HOSHI_PIPER_MODEL_DIR="${HOSHI_PIPER_MODEL_DIR:-$PIPER_DIR/models}"
export HOSHI_PIPER_DEFAULT_VOICE="${HOSHI_PIPER_DEFAULT_VOICE:-de_DE-thorsten-medium}"
export HOSHI_PIPER_THREADS="${HOSHI_PIPER_THREADS:-2}"
export PYTHONUNBUFFERED=1
export VIRTUAL_ENV="$PIPER_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
unset PYTHONHOME 2>/dev/null || true

MODEL_FILE="$HOSHI_PIPER_MODEL_DIR/$HOSHI_PIPER_DEFAULT_VOICE.onnx"
CONFIG_FILE="$MODEL_FILE.json"
[ -f "$MODEL_FILE" ] || fail "Modell fehlt: $MODEL_FILE"
[ -f "$CONFIG_FILE" ] || fail "Konfiguration fehlt: $CONFIG_FILE"

LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/piper-$(date +%Y%m%d-%H%M%S).log"

say_ "starte Piper-TTS :$HOSHI_PIPER_PORT, Stimme=$HOSHI_PIPER_DEFAULT_VOICE, Threads=$HOSHI_PIPER_THREADS"
say_ "externe Runtime-Lizenz: GPL-3.0-or-later; Hoshi-Engine bleibt default-OFF"
say_ "Log: $LOG_FILE"

cd "$PIPER_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1
exec "$VENV_PY" "$SERVER_PY" "$@"
