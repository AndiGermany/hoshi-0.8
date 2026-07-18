#!/usr/bin/env bash
# sidecars/brain/run.sh — kanonischer, idiotensicherer Start des Brain-Sidecars
# (Gemma-4-E4B via MLX, Port 8041).
#
# [0.8-Port] Start-Mechanik 1:1 aus Hoshi_0.5/tools/hoshi-e4b-run.sh portiert,
# aber SELBSTSTAENDIG: kein Sourcen von hoshi-lib.sh/stack-lib.sh, kein
# ~/.hoshi/run/brain.model-Datei-Mechanismus. Modell-Wahl folgt stattdessen
# EXAKT dem Env-Muster von Hoshi_0.8/pipeline/stack-lib.sh::resolve_brain_model
# (HOSHI_BRAIN_MODEL=e4b|e2b|12b|volle-HF-Repo-ID) — nur der DEFAULT weicht
# bewusst ab: stack-lib.sh defaultet global auf "e2b" (dokumentierte BE-weite
# Default-Wahl vom 2026-06-30, s. pipeline/stack-lib.sh-Kommentar), dieser
# Sidecar IST aber der e4b-Brain (server.py's eigener
# MODEL_ID-Default) und vault/tracks/LEDGER-sidecars.md haelt fest: "0.8 = ein
# e4b-Brain, keine Doppel-Default-Drift" (e2b fuers 0.8-Ziel retired). Darum
# hier Default=e4b. Sobald Scheibe 3 (stack-lib-Umstellung auf dieses Sidecar-
# Verzeichnis) kommt, muss diese Divergenz bewusst aufgeloest werden.
#
# GARANTIE (wie im 0.5-Original):
#   - Startet IMMER ueber das sidecars/brain/.venv-Python (absolut, kein PATH-Glueck).
#   - Nutzt ausschliesslich absolute/$HOME-abgeleitete Pfade — KEIN hart
#     codierter Home-Pfad.
#   - exec -> der Python-Prozess ERSETZT diese Shell (sauberes SIGTERM, korrekte PID).
#   - Bindet 0.0.0.0:8041 per Default, damit ein Backend auf anderem Host ueber
#     die Mac-IP zugreifen kann (B-091 in Hoshi_0.5).
#   - Bricht LAUT ab, BEVOR ein kaputter Interpreter/unvollstaendiges Modell den
#     Port belegt (Brief-15-Prinzip: nicht still auf einen anderen Brain degradieren).
#
# Aufruf: sidecars/brain/run.sh   (vorher einmalig: sidecars/brain/bootstrap.sh)
set -euo pipefail

# ── Absolute Pfade (symlink-sicher) ──────────────────────────────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
BRAIN_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

VENV_PY="$BRAIN_DIR/.venv/bin/python"
SERVER_PY="$BRAIN_DIR/server.py"

fail() { echo "[brain-run] FATAL: $*" >&2; exit 1; }
say()  { echo "[brain-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt/nicht ausfuehrbar: $VENV_PY  (-> sidecars/brain/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"

# ── Modell-Aufloesung (Kurz-Token -> kanonische mlx-community-Repo-ID) ───────
# Identische case-Logik wie pipeline/stack-lib.sh::resolve_brain_model — s.
# Kopf-Kommentar fuer den bewussten Default-Unterschied (e4b statt e2b hier).
resolve_brain_model() {
    local choice="${HOSHI_BRAIN_MODEL:-e4b}"
    case "$choice" in
        */*)     printf '%s' "$choice" ;;                          # volle Repo-ID durchreichen
        e2b|E2B) printf '%s' "mlx-community/gemma-4-e2b-it-4bit" ;;
        e4b|E4B) printf '%s' "mlx-community/gemma-4-e4b-it-4bit" ;;
        12b|12B) printf '%s' "mlx-community/gemma-4-12B-it-4bit" ;;
        *)       printf '%s' "mlx-community/gemma-4-${choice}-it-4bit" ;;
    esac
}
MODEL="$(resolve_brain_model)"
PORT="${HOSHI_BRAIN_PORT:-8041}"
HOST="${HOSHI_BRAIN_HOST:-0.0.0.0}"

# Cold-Fix/KV-Freeze-Feintuning: EXAKT dieselben Env-Namen wie server.py sie
# selbst liest (os.environ.get("HOSHI_E4B_...")) — NICHT umbenennen, sonst
# verstummen die Features stillschweigend. Defaults identisch zum 0.5-Original
# (hoshi-e4b-run.sh / hoshi-e4b-watchdog.sh): Touch-Loop an (Idle-Cold-Gegenmittel,
# Iter-129d-Experiment), wired aus, Persona-KV-Freeze aus (Iter-137: 2 von 11
# reproduzierbare Drifts im A/B, defensiv OFF).
export HOSHI_E4B_TOUCH_LOOP_S="${HOSHI_E4B_TOUCH_LOOP_S:-45}"
export HOSHI_E4B_WIRED_MB="${HOSHI_E4B_WIRED_MB:-0}"
export HOSHI_E4B_PERSONA_KV_FREEZE="${HOSHI_E4B_PERSONA_KV_FREEZE:-0}"

# ── Trust-but-verify: das venv-Python MUSS die MLX-LLM-Runtime sehen ────────
# server.py nutzt mlx_lm (+ mlx.core); wir pruefen mlx_lm, akzeptieren
# defensiv aber auch das schlanke 'mlx' als Beleg eines echten MLX-venv (der
# Server stirbt sonst laut beim Load, was wir bewusst zulassen statt zu raten).
if ! "$VENV_PY" -c "import mlx_lm" >/dev/null 2>&1; then
    if "$VENV_PY" -c "import mlx" >/dev/null 2>&1; then
        say "WARN: 'mlx_lm' nicht importierbar, aber 'mlx' vorhanden — fahre fort (Server prueft beim Load)."
    else
        fail "venv-Python kann weder 'mlx_lm' noch 'mlx' importieren — falsches venv oder unvollstaendige Installation (-> sidecars/brain/bootstrap.sh). NICHT mit System-Python ausweichen!"
    fi
fi

# ── Modell-Cache-Vollstaendigkeit (Brief-15-Prinzip: nicht STILL degradieren) ─
# Live-Befund aus Hoshi_0.5 (2026-06-15): ein Modell lag nur als abgebrochener
# .incomplete-Download im HF-Cache. Der Sidecar crashte beim Load -> das
# Backend fiel STILL auf einen anderen Brain zurueck, waehrend der State-File
# weiter den toten Brain behauptete. Hier: LAUT abbrechen, BEVOR ein toter
# :8041 entsteht. local_files_only=True => rein lesend, kein Netz.
#
# ERWEITERUNG ggue. dem 0.5-Original: refs/main-Newline-Check. Die HF-Cache-
# Struktur legt unter blobs/models--org--name/refs/<branch> eine Datei mit dem
# aufgeloesten Commit-Hash ab (im Normalfall ohne Trailing-Newline). Extern
# seedbare Caches (rsync/Docker-Image-Bau/Backup-Restore) koennen das mit
# Trailing-\n schreiben — ein naiver Read/Vergleich ohne Strip zeigt dann auf
# einen nicht-existenten snapshots/<hash>-Ordner (stiller Fallback-Trigger,
# exakt das Brief-15-Muster). Hier: robust lesen + strippen + verifizieren.
model_fully_cached() {
    "$VENV_PY" - "$MODEL" <<'PY' 2>/dev/null
import sys, glob, os
from huggingface_hub import snapshot_download

model = sys.argv[1]
try:
    path = snapshot_download(model, local_files_only=True)
except Exception:
    sys.exit(1)   # gar kein Snapshot lokal
# local_files_only sagt nur "Snapshot existiert", NICHT "Gewichte komplett".
repo_root = os.path.dirname(os.path.dirname(path))   # …/models--org--name
if glob.glob(os.path.join(repo_root, "blobs", "*.incomplete")):
    sys.exit(1)   # abgebrochener Download
if not glob.glob(os.path.join(path, "*.safetensors")):
    sys.exit(1)   # keine Gewichte

refs_main = os.path.join(repo_root, "refs", "main")
if os.path.isfile(refs_main):
    with open(refs_main, "rb") as f:
        raw = f.read()
    ref_hash = raw.strip().decode("ascii", "replace")
    if not ref_hash:
        sys.exit(1)   # leere/kaputte refs/main
    if raw != ref_hash.encode("ascii"):
        print(f"[brain-run] WARN: refs/main hatte Whitespace/Newline, gestrippt zu {ref_hash!r}", file=sys.stderr)
    if not os.path.isdir(os.path.join(repo_root, "snapshots", ref_hash)):
        sys.exit(1)   # refs/main zeigt ins Leere
sys.exit(0)
PY
}
if ! model_fully_cached; then
    fail "Modell '$MODEL' ist NICHT vollstaendig im HF-Cache (fehlende safetensors / nur .incomplete-Reste / kaputte refs/main). NICHT starten — ein toter Sidecar loest stille Fallbacks aus. Erst sauber laden:
    '$VENV_PY' -c \"from huggingface_hub import snapshot_download as d; d('$MODEL')\""
fi

# ── Modell-Revision PINNEN (Lehre aus Hoshi_0.5-Incident 2026-07-08) ─────────
# Ein simpler Brain-Restart zog dort ungefragt die NEUESTE HF-Revision — die
# war mit dem installierten mlx-lm inkompatibel ("Missing 60 parameters"),
# Brain tot. Default: NUR aus dem lokalen Cache laden (refs/main zeigt auf den
# bewaehrten Snapshot). Ein Modell-Update ist damit ein bewusster Zug:
# HOSHI_BRAIN_ALLOW_ONLINE=1 setzen, danach ehrlich smoken.
if [ "${HOSHI_BRAIN_ALLOW_ONLINE:-0}" != "1" ]; then
    export HF_HUB_OFFLINE=1
fi

# ── Log-Pfad ueber Env/$HOME ableiten (NIE hart codierter Home-Pfad) ─────────
LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/brain-$(date +%Y%m%d-%H%M%S).log"

# ── venv-Umgebung explizit setzen (Guertel + Hosentraeger) ───────────────────
export VIRTUAL_ENV="$BRAIN_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
export PYTHONUNBUFFERED=1
unset PYTHONHOME 2>/dev/null || true

say "starte Brain-Sidecar: $VENV_PY $SERVER_PY --model $MODEL --host $HOST --port $PORT"
say "Log: $LOG_FILE (zusaetzlich zu stdout/stderr dieses Terminals)"

cd "$BRAIN_DIR"

# stdout/stderr gleichzeitig ins Log spiegeln UND am Terminal zeigen (tee via
# Process-Substitution), OHNE die exec-Garantie zu brechen: der finale exec
# ersetzt weiterhin diesen Prozess 1:1 durch Python (korrekte PID/SIGTERM) —
# die Redirection oben wirkt schon auf die geerbten Filedescriptoren.
exec > >(tee -a "$LOG_FILE") 2>&1
exec "$VENV_PY" "$SERVER_PY" --model "$MODEL" --host "$HOST" --port "$PORT" "$@"
