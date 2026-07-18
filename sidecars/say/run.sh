#!/usr/bin/env bash
# sidecars/say/run.sh — kanonischer, idiotensicherer Start des say-TTS-Sidecars
# (macOS `say`/`afconvert`, Port 8044).
#
# Struktur 1:1 aus sidecars/stt/run.sh uebernommen (symlink-sichere absolute
# Pfade, exec-Garantie, venv-Import-Probe, Log via $HOME) — kein Modell-
# Cache-Preflight noetig (kein Modell: server.py ruft nur say/afconvert).
#
# GARANTIE (wie bei den Geschwister-Sidecars):
#   - Startet IMMER ueber das sidecars/say/.venv-Python (absolut, kein PATH-Glueck).
#   - Nutzt ausschliesslich absolute/$HOME-abgeleitete Pfade — KEIN hart
#     codierter Home-Pfad.
#   - exec -> der Python-Prozess ERSETZT diese Shell (sauberes SIGTERM, korrekte PID).
#   - Bindet 0.0.0.0:8044 per Default (Backend auf ct-106 erreicht den
#     Mac-Sidecar ueber die Mac-IP, analog Voxtral :8042/STT :9001/Speaker :9002).
#
# Aufruf: sidecars/say/run.sh   (vorher einmalig: sidecars/say/bootstrap.sh)
set -euo pipefail

# ── Absolute Pfade (symlink-sicher) ──────────────────────────────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
SAY_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

VENV_PY="$SAY_DIR/.venv/bin/python"
SERVER_PY="$SAY_DIR/server.py"

fail() { echo "[say-run] FATAL: $*" >&2; exit 1; }
say_()  { echo "[say-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt/nicht ausfuehrbar: $VENV_PY  (-> sidecars/say/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"

# ── macOS-Bordmittel-Preflight (server.py bricht ohne sie beim ersten /tts) ──
[ -x /usr/bin/say ] || fail "/usr/bin/say fehlt — dieser Sidecar laeuft NUR auf macOS"
[ -x /usr/bin/afconvert ] || fail "/usr/bin/afconvert fehlt — dieser Sidecar laeuft NUR auf macOS"

# ── Host/Port/Default-Voice ueber Env (Default identisch zu server.py's eigenen argparse-Defaults) ─
HOST="${HOSHI_SAY_HOST:-0.0.0.0}"
PORT="${HOSHI_SAY_PORT:-8044}"
# HOSHI_SAY_DEFAULT_VOICE wird von server.py SELBST per os.environ.get()
# gelesen (s. argparse-Default dort) — hier nur durchgereicht, kein Doppel-Resolver.

# ── Trust-but-verify: das venv-Python MUSS fastapi/uvicorn sehen ────────────
"$VENV_PY" -c "import fastapi, uvicorn" >/dev/null 2>&1 \
    || fail "venv-Python kann fastapi/uvicorn nicht importieren — falsches venv oder unvollstaendige Installation (-> sidecars/say/bootstrap.sh). NICHT mit System-Python ausweichen!"

# ── Log-Pfad ueber Env/$HOME ableiten (NIE hart codierter Home-Pfad) ─────────
LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/say-$(date +%Y%m%d-%H%M%S).log"

# ── venv-Umgebung explizit setzen (Guertel + Hosentraeger) ───────────────────
export VIRTUAL_ENV="$SAY_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
export PYTHONUNBUFFERED=1
unset PYTHONHOME 2>/dev/null || true

say_ "starte say-TTS-Sidecar: $VENV_PY $SERVER_PY --host $HOST --port $PORT"
say_ "Log: $LOG_FILE (zusaetzlich zu stdout/stderr dieses Terminals)"

cd "$SAY_DIR"

# stdout/stderr gleichzeitig ins Log spiegeln UND am Terminal zeigen (tee via
# Process-Substitution), OHNE die exec-Garantie zu brechen: der finale exec
# ersetzt weiterhin diesen Prozess 1:1 durch Python (korrekte PID/SIGTERM) —
# die Redirection oben wirkt schon auf die geerbten Filedescriptoren.
exec > >(tee -a "$LOG_FILE") 2>&1
exec "$VENV_PY" "$SERVER_PY" --host "$HOST" --port "$PORT" "$@"
