#!/usr/bin/env bash
# pipeline/ask.sh — der M4-Step-2-BEWEIS: Hoshi antwortet GROUNDED mit Wiki-Wissen.
#
# grün≠lebt: bootet die ECHTE 0.8-App (web-inbound) auf :8090 — diesmal MIT
# HOSHI_GROUNDING_ENABLED=true — schickt die übergebene deutsche Frage an
# POST /api/v1/chat/stream und sammelt den Antwort-Satz. Der Unterschied zu
# `hoshi turn`: hier feuert die volle Kette inkl. echtem Keyword-Router
# (KeywordRouterImpl → Wissensfrage=FACT_SHORT) + Fts5GroundingAdapter
# (Knowledge-Bridge :8035), sodass die Antwort echtes Wiki-Wissen spiegelt.
#
# Voraussetzung für den ECHTEN Beweis: e4b-Brain (:8041) UND Knowledge-Bridge
# (:8035) leben. Fehlt die Bridge → die Antwort kommt ohne Grounding (ehrlich warnen).
#
# Exit 0, wenn der Antwort-Satz nicht leer ist. App wird sauber gestoppt.
# Frage als Argument (Default „Wer war Konrad Adenauer?").
#
# Vom Dispatcher: bin/hoshi ask ["Frage…"]
# Log: <repo>/.pipeline/ask-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/ask-$TS.log"
APP_LOG="$PIPELINE_LOG_DIR/ask-$TS-app.log"
SSE_OUT="$PIPELINE_LOG_DIR/ask-$TS-sse.txt"

PORT=8090
TOKEN="testtoken"

# ── Sprach-Flag (Multilingual-Sprachsteuerung) ───────────────────────────────
# `--en` / `--de` setzt ChatRequest.language; ohne Flag bleibt es DE (Default).
# Beweis: `hoshi ask --en "Who was Konrad Adenauer?"` MUSS englisch antworten,
#         `hoshi ask "Wer war Konrad Adenauer?"` weiterhin deutsch.
LANG_CODE="DE"
SPEAKER=""        # Multi-User-Gedächtnis: --speaker <id> → speakerContext.speakerId
ASK_ARGS=()
while [ "$#" -gt 0 ]; do
    a="$1"
    case "$a" in
        --en|--EN|--lang=en|--lang=EN) LANG_CODE="EN" ;;
        --de|--DE|--lang=de|--lang=DE) LANG_CODE="DE" ;;
        --speaker) shift; SPEAKER="${1:-}" ;;
        --speaker=*) SPEAKER="${a#--speaker=}" ;;
        *) ASK_ARGS+=("$a") ;;
    esac
    shift
done
if [ "${#ASK_ARGS[@]}" -gt 0 ]; then
    QUESTION="${ASK_ARGS[*]}"
elif [ "$LANG_CODE" = "EN" ]; then
    QUESTION="Who was Konrad Adenauer?"
else
    QUESTION="Wer war Konrad Adenauer?"
fi
BRIDGE_URL="${HOSHI_KNOWLEDGE_BRIDGE_URL:-http://localhost:8035}"

cd "$REPO_ROOT"

# ── Vorprüfung: Brain (:8041) + Bridge (:8035) ────────────────────────────────
BRAIN_OK=0
if curl -s -m 5 "http://localhost:8041/health" 2>/dev/null | grep -q '"status":"ok"'; then
    BRAIN_OK=1
    ok "e4b-Brain (:8041) lebt — echter Turn möglich"
else
    warn "e4b-Brain (:8041) NICHT erreichbar — Never-Silent liefert dann die Fallback-Phrase"
fi

BRIDGE_OK=0
if curl -s -m 5 "$BRIDGE_URL/search?q=Berlin&limit=1" 2>/dev/null | grep -q '"hits"'; then
    BRIDGE_OK=1
    ok "Knowledge-Bridge ($BRIDGE_URL) lebt — Grounding möglich"
else
    warn "Knowledge-Bridge ($BRIDGE_URL) NICHT erreichbar — Antwort kommt OHNE Wiki-Grounding"
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

# ── (2) App auf :8090 booten — GROUNDING AN ──────────────────────────────────
say "App booten auf :$PORT (Token gesetzt, Brain → :8041, GROUNDING=ON, Bridge → $BRIDGE_URL)"
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
HOSHI_API_TOKEN="$TOKEN" \
HOSHI_GROUNDING_ENABLED=true \
HOSHI_MEMORY_ENABLED=true \
HOSHI_EPISODIC_ENABLED=true \
    "$JAVA_BIN" -jar "$JAR" \
    --server.port="$PORT" \
    --hoshi.perimeter.enabled=true \
    --hoshi.perimeter.token="$TOKEN" \
    --hoshi.brain.base-url="http://localhost:8041" \
    --HOSHI_GROUNDING_ENABLED=true \
    --HOSHI_MEMORY_ENABLED=true \
    --HOSHI_EPISODIC_ENABLED=true \
    --hoshi.knowledge.bridge.base-url="$BRIDGE_URL" \
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

# ── (4) Der GROUNDED ASK: deutsche Wissensfrage → SSE-ChatEvent-Stream ───────
# Multi-User-Gedächtnis: optional speakerContext.speakerId (--speaker <id>).
# Ohne --speaker bleibt der Sprecher leer (Default „unknown"=Gast → kein Memory).
SPEAKER_JSON=""
if [ -n "$SPEAKER" ]; then
    SPEAKER_JSON=",\"speakerContext\":{\"speakerId\":\"$SPEAKER\"}"
fi
say "Grounded ask: POST /api/v1/chat/stream  (lang=$LANG_CODE, speaker=${SPEAKER:-<none>}, Frage: \"$QUESTION\")"
START_MS=$(($(date +%s%N) / 1000000))
curl -sN -m 120 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$QUESTION\",\"language\":\"$LANG_CODE\",\"speak\":false${SPEAKER_JSON}}" \
    "http://localhost:$PORT/api/v1/chat/stream" >"$SSE_OUT" 2>>"$LOG" || true
END_MS=$(($(date +%s%N) / 1000000))
LATENZ_MS=$((END_MS - START_MS))

# ── (5) SSE parsen: alle TextDelta zusammensetzen + Start-Kategorie ──────────
SATZ="$(
    python3 - "$SSE_OUT" <<'PY'
import json, sys
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

CATEGORY="$(
    python3 - "$SSE_OUT" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        try:
            ev = json.loads(payload)
        except Exception:
            continue
        if ev.get("event") == "start":
            print(ev.get("category", "?"))
            break
PY
)"

{
    echo "# ask-grounded @ $(iso_now)  (port=$PORT, brain_ok=$BRAIN_OK, bridge_ok=$BRIDGE_OK)"
    echo "# Frage    : $QUESTION"
    echo "# Kategorie: $CATEGORY"
    echo "# Satz     : $SATZ"
    echo "# Latenz   : ${LATENZ_MS} ms"
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

ok "Routing-Kategorie : ${CATEGORY:-?}"
ok "Antwort-Satz      : $SATZ"
ok "Latenz            : ${LATENZ_MS} ms"
echo
if [ "$BRAIN_OK" -eq 1 ] && [ "$BRIDGE_OK" -eq 1 ]; then
    say "${C_GREEN}ask GRÜN${C_RESET} — grounded Turn durch das Hexagon (Router→FACT→Grounding→Brain)."
elif [ "$BRAIN_OK" -eq 1 ]; then
    warn "ask lief, aber OHNE Bridge — die Antwort kann kein Wiki-Wissen tragen."
else
    warn "ask lief via Never-Silent-Fallback (Brain war aus) — kein echter Brain-Beweis."
fi
exit 0
