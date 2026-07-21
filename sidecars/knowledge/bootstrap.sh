#!/usr/bin/env bash
# sidecars/knowledge/bootstrap.sh — reproduzierbares venv fuer die lokale
# Wikipedia-FTS5-Bridge. Laedt weder die Wiki-DB noch ein Modell herunter.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[knowledge-bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[knowledge-bootstrap] $*"; }

command -v shasum >/dev/null 2>&1 \
    || fail "shasum fehlt — requirements-Integritaet kann nicht geprueft werden"
shasum -a 256 -c requirements.sha256 \
    || fail "requirements.txt stimmt nicht mit requirements.sha256 ueberein"

# Das echte 0.5-Quell-venv lief auf Python 3.14.6. Fallbacks sind erlaubt,
# aber laut sichtbar, weil andere Minor-Versionen andere Wheels aufloesen.
PY=python3.14
if ! command -v "$PY" >/dev/null 2>&1; then
    if command -v python3.11 >/dev/null 2>&1; then
        PY=python3.11
    elif command -v python3 >/dev/null 2>&1; then
        PY=python3
    else
        fail "weder python3.14 noch python3.11 noch python3 gefunden"
    fi
    got="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    echo "[knowledge-bootstrap] WARN: python3.14 fehlt; nutze $PY ($got)." >&2
fi
say "Python: $("$PY" --version 2>&1) ($PY)"

if [ -d .venv ]; then
    [ -x .venv/bin/python ] \
        || fail ".venv existiert, aber .venv/bin/python fehlt — defektes venv nicht uebergehen"
    say "venv existiert bereits — skip create"
else
    "$PY" -m venv .venv || fail "venv-Erstellung fehlgeschlagen"
fi

VENV_PY=.venv/bin/python
"$VENV_PY" -m pip install -q --upgrade pip \
    || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt \
    || fail "pip install -r requirements.txt fehlgeschlagen"

say "verifiziere Runtime-Imports und SQLite-FTS5"
"$VENV_PY" - <<'PY' \
    || fail "Runtime- oder SQLite-FTS5-Probe fehlgeschlagen"
import sqlite3
import fastapi
import pydantic
import uvicorn
import zstandard

with sqlite3.connect(":memory:") as conn:
    conn.execute("CREATE VIRTUAL TABLE probe USING fts5(text)")
    conn.execute("INSERT INTO probe(text) VALUES ('hoshi')")
    assert conn.execute("SELECT count(*) FROM probe WHERE probe MATCH 'hoshi'").fetchone()[0] == 1
PY

say "OK — venv bereit; Wiki-DB bleibt extern und unveraendert"
say "Start: ./run.sh (Default :8035)"
