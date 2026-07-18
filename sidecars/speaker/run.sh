#!/usr/bin/env bash
# sidecars/speaker/run.sh — kanonischer, idiotensicherer Start des Speaker-ID-
# Sidecars (CAM++-ONNX, Port 9002).
#
# [0.8-Port] Struktur 1:1 aus sidecars/brain/run.sh uebernommen (symlink-
# sichere absolute Pfade, exec-Garantie, venv-Import-Probe, Log via $HOME).
# Kein Model-Cache-Preflight wie beim Brain noetig: server.py selbst prueft
# beim Import schon `MODEL_PATH.exists()` und bricht mit sys.exit(1) laut ab
# (bestehendes Verhalten aus Hoshi_0.5, hier NICHT neu gebaut) — run.sh
# verlaesst sich bewusst darauf statt die Pruefung zu duplizieren.
#
# GARANTIE (wie bei sidecars/brain):
#   - Startet IMMER ueber das sidecars/speaker/.venv-Python (absolut, kein PATH-Glueck).
#   - Nutzt ausschliesslich absolute/$HOME-abgeleitete Pfade — KEIN hart
#     codierter Home-Pfad.
#   - exec -> der Python-Prozess ERSETZT diese Shell (sauberes SIGTERM, korrekte PID).
#   - Bindet 0.0.0.0:9002 per Default (Backend auf anderem Host erreicht den
#     Mac-Sidecar ueber die Mac-IP, analog B-091 in Hoshi_0.5).
#
# Aufruf: sidecars/speaker/run.sh   (vorher einmalig: sidecars/speaker/bootstrap.sh)
set -euo pipefail

# ── Absolute Pfade (symlink-sicher) ──────────────────────────────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
SPEAKER_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

VENV_PY="$SPEAKER_DIR/.venv/bin/python"
SERVER_PY="$SPEAKER_DIR/server.py"

fail() { echo "[speaker-run] FATAL: $*" >&2; exit 1; }
say()  { echo "[speaker-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt/nicht ausfuehrbar: $VENV_PY  (-> sidecars/speaker/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"

# ── Host/Port/Threads ueber Env (Default identisch zu server.py's eigenen argparse-Defaults) ─
HOST="${HOSHI_SPEAKER_HOST:-0.0.0.0}"
PORT="${HOSHI_SPEAKER_PORT:-9002}"
# HOSHI_SPEAKER_MODEL_PATH / HOSHI_SPEAKER_THREADS werden von server.py SELBST
# per os.environ.get() gelesen (s. argparse-Defaults dort) — hier nur
# durchreichen, kein doppelter Resolver.

# ── Trust-but-verify: das venv-Python MUSS die Kern-Runtime sehen ───────────
"$VENV_PY" -c "import onnxruntime, kaldi_native_fbank, soundfile" >/dev/null 2>&1 \
    || fail "venv-Python kann onnxruntime/kaldi_native_fbank/soundfile nicht importieren — falsches venv oder unvollstaendige Installation (-> sidecars/speaker/bootstrap.sh). NICHT mit System-Python ausweichen!"

# ── Log-Pfad ueber Env/$HOME ableiten (NIE hart codierter Home-Pfad) ─────────
LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/speaker-$(date +%Y%m%d-%H%M%S).log"

# ── venv-Umgebung explizit setzen (Guertel + Hosentraeger) ───────────────────
export VIRTUAL_ENV="$SPEAKER_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
export PYTHONUNBUFFERED=1
unset PYTHONHOME 2>/dev/null || true

say "starte Speaker-ID-Sidecar: $VENV_PY $SERVER_PY --host $HOST --port $PORT"
say "Log: $LOG_FILE (zusaetzlich zu stdout/stderr dieses Terminals)"

cd "$SPEAKER_DIR"

# stdout/stderr gleichzeitig ins Log spiegeln UND am Terminal zeigen (tee via
# Process-Substitution), OHNE die exec-Garantie zu brechen: der finale exec
# ersetzt weiterhin diesen Prozess 1:1 durch Python (korrekte PID/SIGTERM) —
# die Redirection oben wirkt schon auf die geerbten Filedescriptoren.
exec > >(tee -a "$LOG_FILE") 2>&1
exec "$VENV_PY" "$SERVER_PY" --host "$HOST" --port "$PORT" "$@"
