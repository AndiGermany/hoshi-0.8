#!/usr/bin/env bash
# pipeline/tts-openai.sh — der LIVE-OpenAI-TTS-BEWEIS (isoliert, OHNE Brain).
#
# grün≠lebt: ruft den NEUEN OpenAiTtsAdapter (:adapters-tts) gegen die ECHTE
# OpenAI-TTS-API (`/v1/audio/speech`) mit „Hallo, ich bin Hoshi." auf und beweist
# OBJEKTIV, dass WAV-Bytes (RIFF-Header, Länge>0) zurückkommen. KEIN voller Turn —
# der e4b-Brain (:8041) ist gerade WEDGED und hier bewusst NICHT im Spiel.
#
# Key: ~/.hoshi/openai.key → export OPENAI_API_KEY (Wert wird NIE ausgegeben/geloggt).
#
# Exit 0 nur, wenn der Adapter nicht-leere WAV-Bytes mit RIFF-Header lieferte.
# Vom Dispatcher: bin/hoshi tts-openai
# Log: <repo>/.pipeline/tts-openai-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/tts-openai-$TS.log"
OUT="$PIPELINE_LOG_DIR/tts-openai-$TS.out"

cd "$REPO_ROOT"

# ── Key laden (Wert NIE ausgeben) ────────────────────────────────────────────
KEY_FILE="${OPENAI_KEY_FILE:-$HOME/.hoshi/openai.key}"
if [ ! -f "$KEY_FILE" ]; then
    fail "Key-Datei fehlt: $KEY_FILE — ohne Key kein OpenAI-TTS-Beweis"
    exit 1
fi
export OPENAI_API_KEY="$(cat "$KEY_FILE")"
if [ -z "${OPENAI_API_KEY// /}" ]; then
    fail "OPENAI_API_KEY leer (Datei $KEY_FILE)"
    exit 1
fi
ok "OPENAI_API_KEY geladen (Länge=${#OPENAI_API_KEY}) — Wert wird NIE ausgegeben"
echo

# ── Adapter → OpenAI: „Hallo, ich bin Hoshi." → WAV ──────────────────────────
say "Live-OpenAI-TTS: OpenAiTtsAdapter → api.openai.com/v1/audio/speech"
log "Log: ${LOG#$REPO_ROOT/}"

set +e
"$GRADLEW" -q :adapters-tts:run 2>&1 | tee "$OUT" | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e

echo
if [ "$RC" -ne 0 ]; then
    fail "OpenAI-TTS-Smoke FAILED (exit $RC) — API-Fehler oder leere/ungültige WAV?"
    tail -20 "$OUT"
    exit 1
fi

BYTES="$(grep -E '^\[tts-openai\] WAV-Bytes' "$OUT" | head -1 | sed -E 's/.*:[[:space:]]*//')"
RIFF="$(grep -E '^\[tts-openai\] RIFF' "$OUT" | head -1 | sed -E 's/.*:[[:space:]]*//')"

if [ "${RIFF// /}" != "true" ] || [ -z "${BYTES// /}" ] || [ "${BYTES// /}" = "0" ]; then
    fail "kein gültiges WAV (Bytes=${BYTES:-?} RIFF=${RIFF:-?})"
    exit 1
fi

ok "OpenAI lieferte WAV-Bytes: ${BYTES} (RIFF=true)"
echo
say "${C_GREEN}tts-openai GRÜN${C_RESET} — der OpenAiTtsAdapter holt echtes Cloud-TTS-WAV von OpenAI."
exit 0
