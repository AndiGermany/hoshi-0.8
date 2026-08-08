#!/usr/bin/env bash
# sidecars/knowledge/run.sh — kanonischer Start der lokalen Wikipedia-Bridge.
# Kein Download, keine DB-Mutation: server.py oeffnet articles.db mit mode=ro.
set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
KNOWLEDGE_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

VENV_PY="$KNOWLEDGE_DIR/.venv/bin/python"
SERVER_PY="$KNOWLEDGE_DIR/server.py"
HOST="${HOSHI_KNOWLEDGE_HOST:-0.0.0.0}"
PORT="${HOSHI_KNOWLEDGE_PORT:-${HOSHI_BRIDGE_PORT:-8035}}"
DB_PATH="${HOSHI_WIKI_DB_PATH:-$HOME/.hoshi/knowledge/wiki-de/articles.db}"
# HOSHI_SIDECAR_TOKEN (optional, Codex-Sicherheits-P0 2026-07-27): server.py
# liest diese Var SELBST per os.environ.get() (kein run.sh-Durchreichen
# noetig). Leer/ungesetzt (Default) = heutiges offenes Verhalten, NULL
# Aenderung fuers Produktiv-Setup. Gesetzt: jeder Request ausser /health
# braucht den Header X-Hoshi-Token mit exakt diesem Wert, sonst 401.

fail() { echo "[knowledge-run] FATAL: $*" >&2; exit 1; }
say()  { echo "[knowledge-run] $*" >&2; }

[ -x "$VENV_PY" ] || fail ".venv-Python fehlt: $VENV_PY (-> sidecars/knowledge/bootstrap.sh)"
[ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"
[ -f "$DB_PATH" ] || fail "Wikipedia-DB fehlt oder ist keine Datei: $DB_PATH"
[ -r "$DB_PATH" ] || fail "Wikipedia-DB ist nicht lesbar: $DB_PATH"

"$VENV_PY" -c "import fastapi, pydantic, uvicorn, zstandard" >/dev/null 2>&1 \
    || fail "venv kann die gepinnten Runtime-Pakete nicht importieren"

# Pack-v1-Preflight. Legacy bleibt startbar, aber ein explizit gefordertes oder
# vorhandenes Manifest muss VOR dem Port-Bind sauber zu genau dieser DB passen.
MANIFEST_PATH="${HOSHI_KNOWLEDGE_MANIFEST_PATH:-$(dirname "$DB_PATH")/manifest.json}"
REQUIRE_MANIFEST="${HOSHI_KNOWLEDGE_REQUIRE_MANIFEST:-false}"
PACK_VERIFIER="$KNOWLEDGE_DIR/../../tools/knowledge-pack/verify_pack.py"
MANIFEST_REQUIRED=false
case "$REQUIRE_MANIFEST" in
    1|true|TRUE|yes|YES|on|ON) MANIFEST_REQUIRED=true ;;
esac
if [ -f "$MANIFEST_PATH" ]; then
    [ -f "$PACK_VERIFIER" ] || fail "Pack-Verifier fehlt: $PACK_VERIFIER"
    PYTHONPATH="$KNOWLEDGE_DIR" "$VENV_PY" "$PACK_VERIFIER" "$MANIFEST_PATH" --fast \
        >/dev/null || fail "Knowledge-Pack-Manifest/Schema unbrauchbar: $MANIFEST_PATH"
    say "Knowledge-Pack-v1 Fast-Verify: OK ($MANIFEST_PATH)"
elif [ "$MANIFEST_REQUIRED" = true ]; then
    fail "Knowledge-Pack-Manifest ist erforderlich, fehlt aber: $MANIFEST_PATH"
else
    say "WARN: Legacy-DB ohne Manifest; /v1/health weist legacy-unmanifested aus"
fi

# Billige Schema-Probe ohne Tabellen-Scan; die eigentliche Bridge oeffnet
# dieselbe Datei anschliessend weiterhin read-only (`mode=ro`).
"$VENV_PY" - "$DB_PATH" <<'PY' \
    || fail "DB ist nicht als erwartete read-only Knowledge-DB oeffenbar"
import sqlite3
import sys

path = sys.argv[1]
with sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=5.0) as conn:
    names = {row[0] for row in conn.execute(
        "SELECT name FROM sqlite_master WHERE name IN "
        "('articles', 'classifications', 'classifications_fts')"
    )}
required = {"articles", "classifications", "classifications_fts"}
missing = sorted(required - names)
if missing:
    raise SystemExit(f"fehlende Tabellen: {', '.join(missing)}")
PY

LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
mkdir -p "$LOG_DIR" 2>/dev/null || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
LOG_FILE="$LOG_DIR/knowledge-$(date +%Y%m%d-%H%M%S).log"

export VIRTUAL_ENV="$KNOWLEDGE_DIR/.venv"
export PATH="$VIRTUAL_ENV/bin:$PATH"
export PYTHONUNBUFFERED=1
unset PYTHONHOME 2>/dev/null || true

say "starte Knowledge-Bridge: $VENV_PY $SERVER_PY --host $HOST --port $PORT"
say "DB: $DB_PATH (read-only); Log: $LOG_FILE"
cd "$KNOWLEDGE_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1
# Env-/Preflight-Werte zuletzt setzen: auch wenn ein Aufrufer versehentlich
# dieselbe argparse-Option in "$@" mitgibt, startet der Server garantiert mit
# genau der zuvor geprueften DB/Host/Port-Wahrheit.
exec "$VENV_PY" "$SERVER_PY" "$@" \
    --host "$HOST" --port "$PORT" --db-path "$DB_PATH"
