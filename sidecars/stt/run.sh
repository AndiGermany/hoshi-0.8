#!/usr/bin/env bash
# sidecars/stt/run.sh — kanonischer, idiotensicherer Start des STT-Sidecars
# (mlx-whisper, Port 9001).
#
# [0.8-Port] Struktur 1:1 aus sidecars/brain/run.sh uebernommen (symlink-
# sichere absolute Pfade, exec-Garantie, venv-Import-Probe, Log via $HOME),
# aber ABGESPECKT: kein Model-Cache-Preflight wie beim Brain — mlx_whisper
# laedt sein Modell selbst lazy beim Warmup-Request in server.py's
# @app.on_event("startup") (bestehendes Verhalten, hier NICHT neu gebaut).
#
# GARANTIE (wie bei sidecars/brain):
#   - Startet IMMER ueber das sidecars/stt/.venv-Python (absolut, kein PATH-Glueck).
#   - Nutzt ausschliesslich absolute/$HOME-abgeleitete Pfade — KEIN hart
#     codierter Home-Pfad.
#   - exec -> der Python-Prozess ERSETZT diese Shell (sauberes SIGTERM, korrekte PID).
#   - Bindet 0.0.0.0:9001 per Default (Backend auf anderem Host erreicht den
#     Mac-Sidecar ueber die Mac-IP, analog B-091 in Hoshi_0.5).
#
# Aufruf: sidecars/stt/run.sh   (vorher einmalig: sidecars/stt/bootstrap.sh)
set -euo pipefail

# ── Absolute Pfade (symlink-sicher) ──────────────────────────────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
STT_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

VENV_PY="$STT_DIR/.venv/bin/python"
SERVER_PY="$STT_DIR/server.py"

fail() { echo "[stt-run] FATAL: $*" >&2; exit 1; }
say()  { echo "[stt-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt/nicht ausfuehrbar: $VENV_PY  (-> sidecars/stt/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"

# ── Host/Port ueber Env (Default identisch zu server.py's eigenen argparse-Defaults) ─
HOST="${HOSHI_STT_HOST:-0.0.0.0}"
PORT="${HOSHI_STT_PORT:-9001}"
# Optionale Modell-Wahl: leer = server.py's eigener Default
# (mlx-community/whisper-large-v3-turbo). Nur gesetzt, wenn HOSHI_STT_MODEL
# explizit gesetzt ist — kein neuer Resolver, reines Env->--model-Durchreichen.
MODEL_ARGS=()
if [ -n "${HOSHI_STT_MODEL:-}" ]; then
    MODEL_ARGS=(--model "$HOSHI_STT_MODEL")
fi

# ── Trust-but-verify: das venv-Python MUSS mlx_whisper sehen ────────────────
"$VENV_PY" -c "import mlx_whisper" >/dev/null 2>&1 \
    || fail "venv-Python kann 'mlx_whisper' nicht importieren — falsches venv oder unvollstaendige Installation (-> sidecars/stt/bootstrap.sh). NICHT mit System-Python ausweichen!"

# ── ffmpeg-Voraussetzung zur Laufzeit (server.py::_convert_to_wav) ───────────
command -v ffmpeg >/dev/null 2>&1 \
    || fail "ffmpeg fehlt im PATH (server.py braucht es fuer Audio-Konvertierung) — brew install ffmpeg"

# ── Log-Pfad ueber Env/$HOME ableiten (NIE hart codierter Home-Pfad) ─────────
LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/stt-$(date +%Y%m%d-%H%M%S).log"

# ── venv-Umgebung explizit setzen (Guertel + Hosentraeger) ───────────────────
export VIRTUAL_ENV="$STT_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
export PYTHONUNBUFFERED=1
unset PYTHONHOME 2>/dev/null || true

say "starte STT-Sidecar: $VENV_PY $SERVER_PY --host $HOST --port $PORT ${MODEL_ARGS[*]:-}"
say "Log: $LOG_FILE (zusaetzlich zu stdout/stderr dieses Terminals)"

cd "$STT_DIR"

# stdout/stderr gleichzeitig ins Log spiegeln UND am Terminal zeigen (tee via
# Process-Substitution), OHNE die exec-Garantie zu brechen: der finale exec
# ersetzt weiterhin diesen Prozess 1:1 durch Python (korrekte PID/SIGTERM) —
# die Redirection oben wirkt schon auf die geerbten Filedescriptoren.
exec > >(tee -a "$LOG_FILE") 2>&1
# ${arr[@]+...}-Expansion statt "${arr[@]}": macOS-bash 3.2 wertet ein leeres
# Array unter set -u als unbound variable (brach den ersten S4-Cutover-Start).
exec "$VENV_PY" "$SERVER_PY" --host "$HOST" --port "$PORT" ${MODEL_ARGS[@]+"${MODEL_ARGS[@]}"} "$@"
