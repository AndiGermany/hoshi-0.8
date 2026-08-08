#!/usr/bin/env bash
# sidecars/stt/run.sh — kanonischer, idiotensicherer Start des STT-Sidecars
# (mlx-whisper, Port 9001).
#
# [0.8-Port] Struktur aus sidecars/brain/run.sh: symlink-sichere absolute
# Pfade, exec-Garantie, venv-Import-Probe und Log via $HOME. Vor dem Start setzt
# run.sh HF_HUB_OFFLINE=1 und hasht den konkreten models.json-v2-Lock
# vollständig offline. Ein Warmup darf deshalb nie mehr still die
# jeweils neueste HF-Revision laden.
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
REPO_ROOT="$(cd "$STT_DIR/../.." && pwd)"

VENV_PY="$STT_DIR/.venv/bin/python"
SERVER_PY="$STT_DIR/server.py"
MODELS_JSON="$REPO_ROOT/models.json"
VERIFIED_FETCH="$REPO_ROOT/tools/verified_fetch.py"

fail() { echo "[stt-run] FATAL: $*" >&2; exit 1; }
say()  { echo "[stt-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt/nicht ausfuehrbar: $VENV_PY  (-> sidecars/stt/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"
[ -f "$MODELS_JSON" ] || fail "models.json fehlt: $MODELS_JSON"
[ -f "$VERIFIED_FETCH" ] || fail "verified_fetch fehlt: $VERIFIED_FETCH"

# ── Host/Port ueber Env (Default identisch zu server.py's eigenen argparse-Defaults) ─
HOST="${HOSHI_STT_HOST:-0.0.0.0}"
PORT="${HOSHI_STT_PORT:-9001}"
# HOSHI_SIDECAR_TOKEN (optional, Codex-Sicherheits-P0 2026-07-27): server.py
# liest diese Var SELBST per os.environ.get() (kein run.sh-Durchreichen
# noetig). Leer/ungesetzt (Default) = heutiges offenes Verhalten, NULL
# Aenderung fuers Produktiv-Setup. Gesetzt: jeder Request ausser /health
# braucht den Header X-Hoshi-Token mit exakt diesem Wert, sonst 401.
# Modell-ID kommt aus demselben Lock, den verified_fetch danach hasht. Ein
# beliebiges HOSHI_STT_MODEL waere ein unbeschriebener Download-/Lizenzpfad und
# wird deshalb fail-closed abgelehnt statt an mlx_whisper durchgereicht.
if ! LOCKED_MODEL="$("$VENV_PY" - "$MODELS_JSON" <<'PYEOF'
import json
import re
import sys

manifest = json.loads(open(sys.argv[1], encoding="utf-8").read())
entry = next((m for m in manifest.get("models", []) if m.get("id") == "stt-whisper"), None)
if manifest.get("version") != 2 or not isinstance(entry, dict):
    raise SystemExit("models.json hat keinen stt-whisper-v2-Lock")
repo = entry.get("hf_repo")
pin = entry.get("pinned_revision")
if not isinstance(repo, str) or not re.fullmatch(r"[0-9a-f]{40}", pin or ""):
    raise SystemExit("stt-whisper-Lock hat keine Repo-ID/volle Revision")
print(repo)
PYEOF
)"; then
    fail "stt-whisper-Lock konnte nicht gelesen werden"
fi
if [ -n "${HOSHI_STT_MODEL:-}" ] && [ "$HOSHI_STT_MODEL" != "$LOCKED_MODEL" ]; then
    fail "HOSHI_STT_MODEL='$HOSHI_STT_MODEL' ist nicht der gelockte Runtimevertrag '$LOCKED_MODEL' — kein ungepinnter Start"
fi
MODEL_ARGS=(--model "$LOCKED_MODEL")

# ── Trust-but-verify: das venv-Python MUSS mlx_whisper sehen ────────────────
"$VENV_PY" -c "import mlx_whisper" >/dev/null 2>&1 \
    || fail "venv-Python kann 'mlx_whisper' nicht importieren — falsches venv oder unvollstaendige Installation (-> sidecars/stt/bootstrap.sh). NICHT mit System-Python ausweichen!"

# ── ffmpeg-Voraussetzung zur Laufzeit (server.py::_convert_to_wav) ───────────
command -v ffmpeg >/dev/null 2>&1 \
    || fail "ffmpeg fehlt im PATH (server.py braucht es fuer Audio-Konvertierung) — brew install ffmpeg"

# ── Modell-Lock: Netz hart aus + Vollhash VOR Warmup ─────────────────────────
export HF_HUB_OFFLINE=1
"$VENV_PY" "$VERIFIED_FETCH" verify stt-whisper \
    || fail "stt-whisper fehlt/driftet. Bewusst beschaffen: sidecars/stt/.venv/bin/python tools/verified_fetch.py fetch stt-whisper"
say "stt-whisper gegen models.json vollstaendig verifiziert; HF_HUB_OFFLINE=1"

# Read-only Operator-/CI-Probe: beweist exakt den Start-Guard, startet aber
# weder FastAPI noch Warmup und beruehrt keinen laufenden Prozess.
if [ "${HOSHI_STT_VERIFY_ONLY:-0}" = "1" ]; then
    say "Verify-only OK — kein STT-Prozess gestartet"
    exit 0
fi

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
