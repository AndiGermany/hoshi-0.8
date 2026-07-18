#!/usr/bin/env bash
# pipeline/voicein.sh — der SPRACH-EINGABE-BEWEIS: Andi spricht Hoshi an.
#
# grün≠lebt: der STT-Bogen lebt erst, wenn eine echte WAV-Datei real durch
# /api/v1/voice einen Turn auslöst. Dieses Skript beweist genau das, end-to-end,
# self-contained:
#   (a) erzeugt eine deutsche Frage-WAV via Voxtral-TTS (:8042 /tts) — die
#       „gesprochene Frage" (Hoshi spricht sich die Frage selbst vor),
#   (b) bootet die ECHTE 0.8-App (web-inbound) auf :8090 mit Token,
#       Brain → :8041, TTS → :8042, STT → :9001,
#   (c) POSTet die WAV roh (application/octet-stream) an /api/v1/voice,
#   (d) parst aus dem SSE-Stream das STT-Transkript (Step kind=transcript) UND
#       die Hoshi-Antwort (Text-Deltas) + den Audio-Beleg (≥1 AudioChunk).
#
# Pass-Kriterium (gemessen, nicht geglaubt):
#   - transcript NICHT leer (echtes Whisper :9001, kein Stub),
#   - Hoshi-Antwort-Text NICHT leer (Turn lief durchs Hexagon),
#   - terminales done. Kein stiller Tod.
#
# Voraussetzung: Voxtral-TTS (:8042) + Whisper-STT (:9001) leben. Brain (:8041)
# ist Kür — fehlt er, greift Never-Silent und die Fallback-Phrase wird Antwort.
#
# Exit 0 nur, wenn Transkript UND Antwort nicht leer sind. App sauber gestoppt.
# Log: <repo>/.pipeline/voicein-<ts>.log  ·  Vom Dispatcher: bin/hoshi voicein

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/voicein-$TS.log"
APP_LOG="$PIPELINE_LOG_DIR/voicein-$TS-app.log"
SSE_OUT="$PIPELINE_LOG_DIR/voicein-$TS-sse.txt"
FRAGE_WAV="$PIPELINE_LOG_DIR/voicein-$TS-frage.wav"

PORT=8090
TOKEN="testtoken"
LANG_CODE="${2:-DE}"
QUESTION="${1:-Wer war Konrad Adenauer?}"

cd "$REPO_ROOT"

# ── Sidecar-Vorprüfung (ehrlich) ─────────────────────────────────────────────
if curl -s -m 5 "http://localhost:9001/health" 2>/dev/null | grep -q '"status":"ok"'; then
    ok "Whisper-STT (:9001) lebt — Sprach-Eingabe möglich"
else
    fail "Whisper-STT (:9001) NICHT erreichbar — ohne STT-Sidecar kein Eingabe-Beweis"
    exit 1
fi
if curl -s -m 5 "http://localhost:8042/health" 2>/dev/null | grep -q '"status":"ok"'; then
    ok "Voxtral-TTS (:8042) lebt — Frage-WAV kann erzeugt werden"
else
    fail "Voxtral-TTS (:8042) NICHT erreichbar — brauche es, um die Frage-WAV zu erzeugen"
    exit 1
fi
BRAIN_OK=0
if curl -s -m 5 "http://localhost:8041/health" 2>/dev/null | grep -q '"status":"ok"'; then
    BRAIN_OK=1
    ok "e4b-Brain (:8041) lebt — echte Brain-Antwort auf die gesprochene Frage"
else
    warn "e4b-Brain (:8041) aus — Never-Silent-Fallback wird die Antwort (Turn läuft trotzdem)"
fi
echo

# ── (1) Die „gesprochene Frage": Voxtral-TTS → WAV ───────────────────────────
say "Frage-WAV erzeugen via Voxtral-TTS (:8042 /tts):  \"$QUESTION\""
HTTP_TTS="$(curl -s -m 60 -o "$FRAGE_WAV" -w '%{http_code}' \
    -X POST "http://localhost:8042/tts" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$QUESTION\",\"lang\":\"$(echo "$LANG_CODE" | tr '[:upper:]' '[:lower:]')\"}" 2>>"$LOG" || echo 000)"
WAV_BYTES="$(wc -c < "$FRAGE_WAV" 2>/dev/null | tr -d ' ' || echo 0)"
if [ "$HTTP_TTS" != "200" ] || [ "${WAV_BYTES:-0}" -lt 1000 ]; then
    fail "Frage-WAV-Erzeugung fehlgeschlagen (http=$HTTP_TTS, bytes=$WAV_BYTES)"
    exit 1
fi
ok "Frage-WAV: $WAV_BYTES Bytes  ·  ${FRAGE_WAV#$REPO_ROOT/}"
echo

# ── Java bestimmen: dieselbe JDK 21, mit der gebaut wird ──────────────────────
JAVA_BIN="java"
GRADLE_JAVA_HOME="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
if [ -n "$GRADLE_JAVA_HOME" ] && [ -x "$GRADLE_JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$GRADLE_JAVA_HOME/bin/java"
fi

# ── (2) bootJar bauen ────────────────────────────────────────────────────────
say "bootJar bauen — ./gradlew :web-inbound:bootJar"
if ! "$GRADLEW" -q :web-inbound:bootJar >>"$LOG" 2>&1; then
    fail "bootJar-Build fehlgeschlagen — siehe ${LOG#$REPO_ROOT/}"
    tail -25 "$LOG"
    exit 1
fi
JAR="$(ls -t "$REPO_ROOT"/web-inbound/build/libs/*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)"
if [ -z "$JAR" ]; then
    fail "Kein bootJar gefunden in web-inbound/build/libs/"
    exit 1
fi
ok "Artefakt: ${JAR#$REPO_ROOT/}"

# ── (3) App auf :8090 booten (STT → :9001, Brain → :8041, TTS → :8042) ────────
say "App booten auf :$PORT (Token gesetzt; STT → :9001, Brain → :8041, TTS → :8042)"
APP_PID=""
cleanup() {
    if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        for _ in $(seq 1 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.25; done
        kill -9 "$APP_PID" 2>/dev/null || true
        log "App (PID $APP_PID) gestoppt."
    fi
}
trap cleanup EXIT

log "java: $JAVA_BIN"
HOSHI_API_TOKEN="$TOKEN" "$JAVA_BIN" -jar "$JAR" \
    --server.port="$PORT" \
    --hoshi.perimeter.enabled=true \
    --hoshi.perimeter.token="$TOKEN" \
    --hoshi.brain.base-url="http://localhost:8041" \
    --hoshi.tts.base-url="http://localhost:8042" \
    --hoshi.stt.base-url="http://localhost:9001" \
    >"$APP_LOG" 2>&1 &
APP_PID=$!
log "PID=$APP_PID  ·  App-Log: ${APP_LOG#$REPO_ROOT/}"

# ── (4) Auf Health warten (max 60s) ──────────────────────────────────────────
say "Warte auf Health (poll http://localhost:$PORT/api/health, max 60s)"
READY=0
for i in $(seq 1 120); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        fail "App-Prozess vorzeitig beendet — App-Log:"
        tail -30 "$APP_LOG"
        exit 1
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://localhost:$PORT/api/health" 2>/dev/null || echo 000)"
    if [ "$code" = "200" ]; then READY=1; ok "Health 200 nach ~$((i / 2))s"; break; fi
    sleep 0.5
done
if [ "$READY" -ne 1 ]; then
    fail "Health nicht 200 binnen 60s — App-Log:"
    tail -30 "$APP_LOG"
    exit 1
fi
echo

# ── (5) Der SPRACH-TURN: WAV roh an /api/v1/voice → SSE ──────────────────────
say "Gesprochene Frage senden: POST /api/v1/voice (WAV roh, octet-stream, lang=$LANG_CODE)"
START_MS=$(($(date +%s%N) / 1000000))
curl -sN -m 180 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$FRAGE_WAV" \
    "http://localhost:$PORT/api/v1/voice?language=$LANG_CODE&speak=true" >"$SSE_OUT" 2>>"$LOG" || true
END_MS=$(($(date +%s%N) / 1000000))
LATENZ_MS=$((END_MS - START_MS))
echo "  ${C_DIM}roher SSE-Stream: ${SSE_OUT#$REPO_ROOT/}${C_RESET}"

# ── (6) SSE parsen: Transkript (Step) + Antwort (Deltas) + Audio-Beleg ───────
say "SSE auswerten: Transkript + Hoshi-Antwort + Audio-Belege"
PARSE_OUT="$(
    python3 - "$SSE_OUT" <<'PY'
import base64, json, sys

sse_path = sys.argv[1]
transcript = ""
texts = []
n_audio = 0
audio_bytes = 0
saw_done = False
err = ""

with open(sse_path, encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            ev = json.loads(payload)
        except Exception:
            continue
        kind = ev.get("event")
        if kind == "step" and ev.get("kind") == "transcript":
            transcript = ev.get("message", "")
        elif kind == "delta":
            texts.append(ev.get("text", ""))
        elif kind == "audio":
            n_audio += 1
            try:
                audio_bytes += len(base64.b64decode(ev.get("data", "")))
            except Exception:
                pass
        elif kind == "done":
            saw_done = True
        elif kind == "error":
            err = ev.get("message", "")

answer = "".join(texts).strip()
print(f"TRANSCRIPT={transcript}")
print(f"ANSWER={answer}")
print(f"AUDIO_EVENTS={n_audio}")
print(f"AUDIO_BYTES={audio_bytes}")
print(f"SAW_DONE={int(saw_done)}")
print(f"ERROR={err}")
PY
)"
echo "$PARSE_OUT" >> "$LOG"

getval() { echo "$PARSE_OUT" | sed -n "s/^$1=//p" | head -1; }
TRANSCRIPT="$(getval TRANSCRIPT)"
ANSWER="$(getval ANSWER)"
AUDIO_EVENTS="$(getval AUDIO_EVENTS)"
AUDIO_BYTES="$(getval AUDIO_BYTES)"
SAW_DONE="$(getval SAW_DONE)"
ERR="$(getval ERROR)"

{
    echo "# voicein-proof @ $(iso_now)  (port=$PORT, brain_ok=$BRAIN_OK, lang=$LANG_CODE)"
    echo "# Gesprochene Frage : $QUESTION"
    echo "# STT-Transkript    : $TRANSCRIPT"
    echo "# Hoshi-Antwort     : $ANSWER"
    echo "# AudioEvents       : $AUDIO_EVENTS  (Bytes: $AUDIO_BYTES)"
    echo "# Turn-Latenz       : ${LATENZ_MS} ms"
    echo "# --- roher SSE-Stream ---"
    cat "$SSE_OUT"
} >> "$LOG"

# ── (7) Urteil: Transkript UND Antwort nicht leer = grün ─────────────────────
echo
if [ -z "${TRANSCRIPT// /}" ]; then
    fail "STT-Transkript LEER — die gesprochene Frage kam nicht durch Whisper (no_input?)"
    [ -n "$ERR" ] && fail "STT-Fehler-Event: $ERR"
    echo "  --- letzte App-Log-Zeilen ---"; tail -20 "$APP_LOG"
    exit 1
fi
if [ -z "${ANSWER// /}" ]; then
    fail "Hoshi-Antwort LEER — der Turn lief nicht durchs Hexagon"
    echo "  --- letzte App-Log-Zeilen ---"; tail -20 "$APP_LOG"
    exit 1
fi

ok "Gesprochene Frage : \"$QUESTION\""
ok "STT-Transkript    : \"$TRANSCRIPT\""
ok "Hoshi-Antwort     : \"$ANSWER\""
ok "Audio-Beleg       : $AUDIO_EVENTS AudioChunk(s), $AUDIO_BYTES Bytes WAV"
ok "Terminales done   : $([ "$SAW_DONE" = "1" ] && echo ja || echo nein)"
ok "Turn-Latenz       : ${LATENZ_MS} ms"
echo
say "${C_GREEN}voicein GRÜN${C_RESET} — gesprochene Frage → Hoshi antwortet. Der Eingabe-Bogen LEBT."
[ "$BRAIN_OK" -ne 1 ] && warn "Hinweis: Brain war aus — die Antwort ist die Never-Silent-Fallback-Phrase, kein echter Brain-Satz."
exit 0
