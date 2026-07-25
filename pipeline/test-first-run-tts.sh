#!/usr/bin/env bash
# pipeline/test-first-run-tts.sh — Fresh-HOME-Beweis für den TTS-Erststart.
#
# Zwei ehrliche Ausgänge sind grün:
#   1. say-Sidecar gebootstrapped: echter /health- und /tts-Roundtrip liefert
#      ein nichtleeres RIFF/WAVE — ohne Key und ohne Hoshi-Konfiguration.
#   2. say-Sidecar noch nicht gebootstrapped: run.sh bricht sofort ab und nennt
#      exakt `sidecars/say/bootstrap.sh`; kein stiller Cloud-Fallback.
#
# Das Skript startet ausschließlich einen eigenen Prozess auf einem freien
# Loopback-Port und räumt ihn wieder auf. Kein Deploy, kein Prod-Zugriff.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"
RUN_SH="$REPO_ROOT/sidecars/say/run.sh"
VENV_PY="$REPO_ROOT/sidecars/say/.venv/bin/python"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-first-run-tts.XXXXXX")"
FRESH_HOME="$TMP_ROOT/home"
LOG_DIR="$TMP_ROOT/logs"
SIDECAR_OUT="$TMP_ROOT/sidecar.out"
WAV_OUT="$TMP_ROOT/answer.wav"
mkdir -p "$FRESH_HOME" "$LOG_DIR"

SIDECAR_PID=""
cleanup() {
    if [ -n "$SIDECAR_PID" ] && kill -0 "$SIDECAR_PID" 2>/dev/null; then
        kill "$SIDECAR_PID" 2>/dev/null || true
        wait "$SIDECAR_PID" 2>/dev/null || true
    fi
    case "$TMP_ROOT" in
        "${TMPDIR:-/tmp}"/hoshi-first-run-tts.*) rm -rf -- "$TMP_ROOT" ;;
        *) echo "[first-run-tts] WARN: unerwarteter TMP-Pfad bleibt liegen: $TMP_ROOT" >&2 ;;
    esac
}
trap cleanup EXIT

pass() { echo "[first-run-tts] PASS: $*"; }
fail() { echo "[first-run-tts] FAIL: $*" >&2; exit 1; }

[ -x "$RUN_SH" ] || fail "Startskript fehlt/nicht ausführbar: sidecars/say/run.sh"
[ -x /usr/bin/say ] || fail "/usr/bin/say fehlt — say-TTS ist nur auf macOS verfügbar"
[ -x /usr/bin/afconvert ] || fail "/usr/bin/afconvert fehlt — say-TTS ist nur auf macOS verfügbar"

# Schutzwall: Der Test darf weder echte Hoshi-Konfiguration lesen noch einen
# geerbten Cloud-Key benötigen. HOME zeigt ausschließlich in die mktemp-Sandbox.
case "$FRESH_HOME" in
    "$TMP_ROOT"/*) : ;;
    *) fail "Fresh-HOME liegt nicht unter der Sandbox: $FRESH_HOME" ;;
esac
[ ! -e "$FRESH_HOME/.hoshi/secrets.json" ] || fail "Fresh-HOME enthält unerwartet secrets.json"

run_without_cloud_env() {
    env \
        -u OPENAI_API_KEY \
        -u HOSHI_OPENAI_API_KEY \
        -u HOSHI_TTS \
        -u HOSHI_TTS_SAY_BASE_URL \
        HOME="$FRESH_HOME" \
        HOSHI_LOG_DIR="$LOG_DIR" \
        "$@"
}

if [ ! -x "$VENV_PY" ]; then
    set +e
    run_without_cloud_env "$RUN_SH" >"$SIDECAR_OUT" 2>&1
    rc=$?
    set -e

    [ "$rc" -ne 0 ] || fail "fehlender Bootstrap startete wider Erwarten einen Sidecar"
    grep -Fq "[say-run] FATAL:" "$SIDECAR_OUT" \
        || fail "fehlender Bootstrap wurde nicht als FATAL gemeldet"
    grep -Fq "sidecars/say/bootstrap.sh" "$SIDECAR_OUT" \
        || fail "Fehler nennt nicht den exakten nächsten Schritt sidecars/say/bootstrap.sh"
    [ ! -e "$FRESH_HOME/.hoshi/secrets.json" ] \
        || fail "Fehlerpfad hat unerwartet eine Secrets-Datei erzeugt"

    pass "kein Bootstrap ⇒ sofortiger, wahrer Abbruch mit sidecars/say/bootstrap.sh; kein Cloud-Fallback"
    exit 0
fi

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
[[ "$PORT" =~ ^[0-9]+$ ]] || fail "kein freier Loopback-Port ermittelt"

run_without_cloud_env \
    HOSHI_SAY_HOST=127.0.0.1 \
    HOSHI_SAY_PORT="$PORT" \
    "$RUN_SH" >"$SIDECAR_OUT" 2>&1 &
SIDECAR_PID=$!

HEALTH=""
for _ in {1..80}; do
    if ! kill -0 "$SIDECAR_PID" 2>/dev/null; then
        fail "say-Sidecar starb beim Start: $(tail -n 4 "$SIDECAR_OUT" | tr '\n' ' ')"
    fi
    HEALTH="$(curl -fsS --max-time 1 "http://127.0.0.1:$PORT/health" 2>/dev/null || true)"
    [ -n "$HEALTH" ] && break
    sleep 0.25
done

[ -n "$HEALTH" ] || fail "say-Sidecar wurde auf Loopback :$PORT nicht gesund"
printf '%s' "$HEALTH" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"' \
    || fail "/health meldet nicht status=ok: $HEALTH"
printf '%s' "$HEALTH" | grep -Eq '"engine"[[:space:]]*:[[:space:]]*"say"' \
    || fail "/health meldet nicht engine=say: $HEALTH"

HTTP_CODE="$(
    curl -sS --max-time 30 \
        -o "$WAV_OUT" \
        -w '%{http_code}' \
        -H 'Content-Type: application/json' \
        --data-binary '{"text":"Hallo, ich bin Hoshi."}' \
        "http://127.0.0.1:$PORT/tts"
)"
[ "$HTTP_CODE" = "200" ] || fail "/tts antwortete HTTP $HTTP_CODE statt 200"

python3 - "$WAV_OUT" <<'PY'
import pathlib
import sys
import wave

path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
if len(data) <= 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
    raise SystemExit("kein nichtleeres RIFF/WAVE")
with wave.open(str(path), "rb") as wav:
    if wav.getnframes() <= 0:
        raise SystemExit("WAV hat keine Audioframes")
PY

[ ! -e "$FRESH_HOME/.hoshi/secrets.json" ] \
    || fail "Live-Pfad hat unerwartet eine Secrets-Datei erzeugt"

WAV_BYTES="$(wc -c <"$WAV_OUT" | tr -d ' ')"
pass "Fresh HOME, keine Keys/Config, engine=say, echter /tts-Roundtrip: HTTP 200, $WAV_BYTES Byte RIFF/WAVE"
