#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# sidecars/piper/bootstrap.sh — richtet den OPTIONALEN Piper-TTS-Sidecar ein.
#
# Die Apache-2.0-Hoshi-Huelle vendort weder Piper noch Modellgewichte. Dieses
# Skript laedt die in artifacts.lock.json festgenagelten, separat lizenzierten
# Artefakte, prueft Bytegroesse + SHA256 und installiert erst DANACH lokal.
# Zielplattform ist bewusst nur der echte Hoshi-Host: macOS auf Apple Silicon.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[piper-bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[piper-bootstrap] $*"; }

[ "$(uname -s)" = "Darwin" ] || fail "Piper-Pin ist nur fuer macOS gebaut (gefunden: $(uname -s))"
[ "$(uname -m)" = "arm64" ] || fail "Piper-Pin ist nur fuer Apple Silicon arm64 gebaut (gefunden: $(uname -m))"
command -v curl >/dev/null 2>&1 || fail "curl fehlt"
command -v shasum >/dev/null 2>&1 || fail "shasum fehlt"

MANIFEST="$(pwd)/artifacts.lock.json"
[ -f "$MANIFEST" ] || fail "Artefakt-Manifest fehlt: $MANIFEST"

PY=python3.11
if ! command -v "$PY" >/dev/null 2>&1; then
    command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden"
    PY=python3
    say "WARN: python3.11 fehlt; nutze $PY ($("$PY" --version 2>&1))"
fi
say "Python: $("$PY" --version 2>&1)"

if [ -d .venv ]; then
    [ -x .venv/bin/python ] || fail ".venv existiert, aber .venv/bin/python fehlt"
else
    say "lege .venv an"
    "$PY" -m venv .venv || fail "venv-Erstellung fehlgeschlagen"
fi
VENV_PY="$(pwd)/.venv/bin/python"

# shlex.quote erzeugt fuer Bash sichere Zuweisungen. Das Manifest ist die eine
# Wahrheit fuer URLs, Groessen, Hashes und Lizenzen; keine zweite Bash-Kopie.
eval "$("$VENV_PY" - "$MANIFEST" <<'PYEOF'
import json
import shlex
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
runtime = data["runtime"]
voice_id = data["default_voice"]
voice = next(v for v in data["voices"] if v["id"] == voice_id)
values = {
    "RUNTIME_ARTIFACT": runtime["artifact"],
    "RUNTIME_URL": runtime["url"],
    "RUNTIME_BYTES": str(runtime["bytes"]),
    "RUNTIME_SHA256": runtime["sha256"],
    "VOICE_ID": voice["id"],
    "MODEL_ARTIFACT": voice["model"]["artifact"],
    "MODEL_URL": voice["model"]["url"],
    "MODEL_BYTES": str(voice["model"]["bytes"]),
    "MODEL_SHA256": voice["model"]["sha256"],
    "CONFIG_ARTIFACT": voice["config"]["artifact"],
    "CONFIG_URL": voice["config"]["url"],
    "CONFIG_BYTES": str(voice["config"]["bytes"]),
    "CONFIG_SHA256": voice["config"]["sha256"],
}
for key, value in values.items():
    print(f"{key}={shlex.quote(value)}")
PYEOF
)"

download_verified() {
    url="$1"
    target="$2"
    expected_bytes="$3"
    expected_sha="$4"
    label="$5"

    if [ -f "$target" ]; then
        actual_bytes=$(stat -f%z "$target")
        actual_sha=$(shasum -a 256 "$target" | awk '{print $1}')
        if [ "$actual_bytes" = "$expected_bytes" ] && [ "$actual_sha" = "$expected_sha" ]; then
            say "$label bereits verifiziert — skip download"
            return
        fi
        say "WARN: $label lokal inkonsistent — lade atomar neu"
    fi

    partial="${target}.partial"
    # Nur diese explizite Partialdatei wird ersetzt; nie ein Verzeichnis/Glob.
    rm -f "$partial"
    curl -fL --retry 2 --connect-timeout 15 --max-time 300 -o "$partial" "$url" \
        || { rm -f "$partial"; fail "$label Download fehlgeschlagen: $url"; }
    actual_bytes=$(stat -f%z "$partial")
    [ "$actual_bytes" = "$expected_bytes" ] \
        || { rm -f "$partial"; fail "$label unvollstaendig: $actual_bytes != $expected_bytes Bytes"; }
    actual_sha=$(shasum -a 256 "$partial" | awk '{print $1}')
    [ "$actual_sha" = "$expected_sha" ] \
        || { rm -f "$partial"; fail "$label SHA256-Mismatch: $actual_sha != $expected_sha"; }
    mv "$partial" "$target"
    say "$label verifiziert: $actual_bytes Bytes, sha256 $actual_sha"
}

mkdir -p artifacts models
RUNTIME_FILE="$(pwd)/artifacts/$RUNTIME_ARTIFACT"
MODEL_FILE="$(pwd)/models/$MODEL_ARTIFACT"
CONFIG_FILE="$(pwd)/models/$CONFIG_ARTIFACT"

download_verified "$RUNTIME_URL" "$RUNTIME_FILE" "$RUNTIME_BYTES" "$RUNTIME_SHA256" "Piper-Runtime"
download_verified "$MODEL_URL" "$MODEL_FILE" "$MODEL_BYTES" "$MODEL_SHA256" "Stimme $VOICE_ID"
download_verified "$CONFIG_URL" "$CONFIG_FILE" "$CONFIG_BYTES" "$CONFIG_SHA256" "Stimmen-Konfiguration $VOICE_ID"

say "installiere gepinnte Apache-Huelle/Abhaengigkeiten"
"$VENV_PY" -m pip install -q --upgrade pip || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt || fail "requirements-Installation fehlgeschlagen"

say "installiere separat lizenzierte Piper-Runtime aus verifiziertem Wheel"
"$VENV_PY" -m pip install -q --no-deps "$RUNTIME_FILE" || fail "Piper-Wheel-Installation fehlgeschlagen"
"$VENV_PY" -c "import onnxruntime, piper" \
    || fail "Kern-Import fehlgeschlagen"

say "lade Modell und fuehre echte lokale Synthese aus"
HOSHI_PIPER_MODEL_DIR="$(pwd)/models" HOSHI_PIPER_DEFAULT_VOICE="$VOICE_ID" \
    "$VENV_PY" server.py --selftest \
    || fail "Piper-Selftest fehlgeschlagen"

say "OK — optionaler Piper-Sidecar bereit; kein Engine-/Default-Flip ausgefuehrt"
say "Start: sidecars/piper/run.sh  (Port 8045)"
