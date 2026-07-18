#!/usr/bin/env bash
# pipeline/turn.sh — der M2-BEWEIS: ein echter Turn durchs neue Hexagon.
#
# grün≠lebt: bootet die ECHTE 0.8-App (web-inbound) auf :8090, schickt mit
# Test-Token eine deutsche Frage an POST /api/v1/chat/stream und beweist, dass
# durch die volle Pipeline (Routing → Honesty → Prompt → Brain(1×) → Never-Silent)
# eine NICHT-leere deutsche Antwort als SSE-ChatEvent-Stream zurückkommt.
#
# Voraussetzung: der e4b-Brain (:8041) lebt — sonst greift Never-Silent und es
# käme die warme Fallback-Phrase statt einer echten Brain-Antwort (das prüfen wir).
#
# Exit 0 nur, wenn der gesammelte Antwort-Satz nicht leer ist. App wird sauber
# gestoppt. Log: <repo>/.pipeline/turn-<ts>.log
#
# Vom Dispatcher: bin/hoshi turn

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/turn-$TS.log"
APP_LOG="$PIPELINE_LOG_DIR/turn-$TS-app.log"
SSE_OUT="$PIPELINE_LOG_DIR/turn-$TS-sse.txt"

PORT=8090
TOKEN="testtoken"
QUESTION="Sag in einem warmen Satz Hallo."

cd "$REPO_ROOT"

# ── Brain-Vorprüfung (ehrlich: ist der e4b überhaupt da?) ─────────────────────
BRAIN_OK=0
if curl -s -m 5 "http://localhost:8041/health" 2>/dev/null | grep -q '"status":"ok"'; then
    BRAIN_OK=1
    ok "e4b-Brain (:8041) lebt — echter Turn möglich"
else
    warn "e4b-Brain (:8041) NICHT erreichbar — Never-Silent liefert dann die Fallback-Phrase"
fi

# ── Java bestimmen: dieselbe JDK 21, mit der gebaut wird ──────────────────────
JAVA_BIN="java"
GRADLE_JAVA_HOME="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
if [ -n "$GRADLE_JAVA_HOME" ] && [ -x "$GRADLE_JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$GRADLE_JAVA_HOME/bin/java"
fi

# ── (1) bootJar bauen ────────────────────────────────────────────────────────
say "bootJar bauen — ./gradlew :web-inbound:bootJar"
if ! "$GRADLEW" -q :web-inbound:bootJar >"$LOG" 2>&1; then
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

# ── (2) App auf :8090 booten ─────────────────────────────────────────────────
say "App booten auf :$PORT (Token gesetzt, Brain → :8041)"
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
    >"$APP_LOG" 2>&1 &
APP_PID=$!
log "PID=$APP_PID  ·  App-Log: ${APP_LOG#$REPO_ROOT/}"

# ── (3) Auf Health warten (max 60s) ──────────────────────────────────────────
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

# ── (4) Der LIVE-TURN: deutsche Frage → SSE-ChatEvent-Stream ─────────────────
say "Live-Turn: POST /api/v1/chat/stream  (Frage: \"$QUESTION\")"
START_MS=$(($(date +%s%N) / 1000000))
# Loopback ist laut PerimeterPort immer frei; Token schicken wir trotzdem mit
# (auth-gated), damit der Pfad realistisch durch die Wand geht.
curl -sN -m 90 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$QUESTION\",\"language\":\"DE\",\"speak\":false}" \
    "http://localhost:$PORT/api/v1/chat/stream" >"$SSE_OUT" 2>>"$LOG" || true
END_MS=$(($(date +%s%N) / 1000000))
LATENZ_MS=$((END_MS - START_MS))

# ── (5) SSE parsen: alle TextDelta zusammensetzen ────────────────────────────
SATZ="$(
    python3 - "$SSE_OUT" <<'PY'
import json, sys, re
texts = []
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
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
        if ev.get("event") == "delta":
            texts.append(ev.get("text", ""))
print("".join(texts).strip())
PY
)"

{
    echo "# turn-smoke @ $(iso_now)  (port=$PORT, brain_ok=$BRAIN_OK)"
    echo "# Frage : $QUESTION"
    echo "# Satz  : $SATZ"
    echo "# Latenz: ${LATENZ_MS} ms"
    echo "# --- roher SSE-Stream ---"
    cat "$SSE_OUT"
} >> "$LOG"

echo
echo "  ${C_DIM}roher SSE-Stream: ${SSE_OUT#$REPO_ROOT/}${C_RESET}"
if [ -z "${SATZ// /}" ]; then
    fail "LEERER Antwort-Satz — der Turn lieferte keinen Text (App-Log + SSE prüfen)"
    echo "  --- letzte App-Log-Zeilen ---"
    tail -20 "$APP_LOG"
    echo "  --- roher SSE ---"
    cat "$SSE_OUT"
    exit 1
fi

ok "Antwort-Satz : $SATZ"
ok "Latenz       : ${LATENZ_MS} ms"
echo
if [ "$BRAIN_OK" -eq 1 ]; then
    say "${C_GREEN}turn GRÜN${C_RESET} — ein echter deutscher Turn lief durch das neue Hexagon."
else
    warn "turn lief, aber via Never-Silent-Fallback (Brain war aus) — kein echter Brain-Beweis."
fi
exit 0
