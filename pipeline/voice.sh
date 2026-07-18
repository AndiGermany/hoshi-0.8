#!/usr/bin/env bash
# pipeline/voice.sh — der AUDIO-BEWEIS: der Turn ist HÖRBAR.
#
# grün≠lebt: bootet die ECHTE 0.8-App (web-inbound) auf :8090, schickt mit
# Test-Token einen deutschen Turn an POST /api/v1/chat/stream (speak=true), und
# beweist OBJEKTIV, dass durch die volle Pipeline (Routing → Honesty → Prompt →
# Brain(1×) → Never-Silent → TtsStage → Voxtral :8042) MINDESTENS EIN AudioChunk
# mit NICHT-LEEREN WAV-Bytes zurückkam. Gibt Chunks/Bytes/ms wörtlich aus und
# schreibt die zusammengesetzte WAV nach .pipeline/ (für Andis Hörprobe — das
# Wärme-Urteil ist NICHT Sache dieses Skripts).
#
# Voraussetzung: der Voxtral-TTS-Sidecar (:8042) lebt. Der e4b-Brain (:8041) ist
# Kür — fehlt er, greift Never-Silent und die warme Fallback-Phrase wird vertont
# (Audio kommt trotzdem). Beides wird ehrlich gemeldet.
#
# Exit 0 nur, wenn ≥1 AudioChunk mit nicht-leeren Bytes kam. App sauber gestoppt.
# Log: <repo>/.pipeline/voice-<ts>.log
#
# Vom Dispatcher: bin/hoshi voice

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/voice-$TS.log"
APP_LOG="$PIPELINE_LOG_DIR/voice-$TS-app.log"
SSE_OUT="$PIPELINE_LOG_DIR/voice-$TS-sse.txt"
WAV_OUT="$PIPELINE_LOG_DIR/voice-$TS.wav"

PORT=8090
TOKEN="testtoken"
LANG_CODE="${1:-DE}"   # optional: bin/hoshi voice EN  → englischer Turn
if [ "$LANG_CODE" = "EN" ]; then
    QUESTION="Say hello in one warm sentence."
else
    QUESTION="Sag in zwei warmen Sätzen Hallo und wünsch mir einen schönen Tag."
fi

cd "$REPO_ROOT"

# ── Sidecar-Vorprüfung (ehrlich) ─────────────────────────────────────────────
TTS_OK=0
if curl -s -m 5 "http://localhost:8042/health" 2>/dev/null | grep -q '"status":"ok"'; then
    TTS_OK=1
    ok "Voxtral-TTS (:8042) lebt — hörbarer Turn möglich"
else
    fail "Voxtral-TTS (:8042) NICHT erreichbar — ohne TTS-Sidecar kein Audio-Beweis"
    exit 1
fi
BRAIN_OK=0
if curl -s -m 5 "http://localhost:8041/health" 2>/dev/null | grep -q '"status":"ok"'; then
    BRAIN_OK=1
    ok "e4b-Brain (:8041) lebt — echter Brain-Satz wird vertont"
else
    warn "e4b-Brain (:8041) aus — Never-Silent-Fallback-Phrase wird vertont (Audio kommt trotzdem)"
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
say "App booten auf :$PORT (Token gesetzt, Brain → :8041, TTS → :8042)"
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

# ── (4) Der HÖRBARE TURN: speak=true → SSE mit Text+Audio-Events ─────────────
say "Hörbarer Turn: POST /api/v1/chat/stream (speak=true, lang=$LANG_CODE)  Frage: \"$QUESTION\""
START_MS=$(($(date +%s%N) / 1000000))
curl -sN -m 120 -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$QUESTION\",\"language\":\"$LANG_CODE\",\"speak\":true}" \
    "http://localhost:$PORT/api/v1/chat/stream" >"$SSE_OUT" 2>>"$LOG" || true
END_MS=$(($(date +%s%N) / 1000000))
LATENZ_MS=$((END_MS - START_MS))

# ── (5) SSE parsen: Text sammeln + AudioChunks dekodieren + WAV bauen ────────
say "SSE auswerten: AudioChunks dekodieren, Bytes/ms messen, WAV bauen"
PARSE_OUT="$(
    python3 - "$SSE_OUT" "$WAV_OUT" <<'PY'
import base64, io, json, sys, wave

sse_path, wav_path = sys.argv[1], sys.argv[2]
texts, chunks = [], []
n_audio_events = 0
saw_start = saw_end = False

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
        if kind == "delta":
            texts.append(ev.get("text", ""))
        elif kind == "tts_audio_start":
            saw_start = True
        elif kind == "tts_audio_end":
            saw_end = True
        elif kind == "audio":
            n_audio_events += 1
            data = ev.get("data", "")
            try:
                wav = base64.b64decode(data)
            except Exception:
                wav = b""
            if wav:
                chunks.append((ev.get("seq", -1), wav))

# nicht-leere Audio-Chunks?
nonempty = [(seq, w) for seq, w in chunks if len(w) > 0]
total_bytes = sum(len(w) for _, w in nonempty)

# WAV-Frames + Dauer aus jedem Chunk lesen, zu EINER playbaren WAV zusammensetzen.
total_ms = 0.0
params = None
frames = bytearray()
for seq, w in sorted(nonempty, key=lambda t: t[0]):
    try:
        with wave.open(io.BytesIO(w), "rb") as wf:
            p = wf.getparams()
            data = wf.readframes(wf.getnframes())
            total_ms += (wf.getnframes() / float(wf.getframerate())) * 1000.0
            if params is None:
                params = p
            frames += data
    except Exception:
        # kein parsebares WAV → Bytes zählen trotzdem (roher Audio-Beleg)
        pass

if params is not None and frames:
    with wave.open(wav_path, "wb") as out:
        out.setnchannels(params.nchannels)
        out.setsampwidth(params.sampwidth)
        out.setframerate(params.framerate)
        out.writeframes(bytes(frames))
    wrote = wav_path
else:
    wrote = ""

text = "".join(texts).strip()
# Maschinen-lesbare Zeile für das Bash-Skript (key=value).
print(f"AUDIO_EVENTS={n_audio_events}")
print(f"NONEMPTY_CHUNKS={len(nonempty)}")
print(f"TOTAL_BYTES={total_bytes}")
print(f"TOTAL_MS={int(round(total_ms))}")
print(f"SAW_START={int(saw_start)}")
print(f"SAW_END={int(saw_end)}")
print(f"WROTE_WAV={wrote}")
print(f"TEXT={text}")
PY
)"

echo "$PARSE_OUT" >> "$LOG"

# key=value aus dem Python-Output ziehen
getval() { echo "$PARSE_OUT" | sed -n "s/^$1=//p" | head -1; }
AUDIO_EVENTS="$(getval AUDIO_EVENTS)"
NONEMPTY_CHUNKS="$(getval NONEMPTY_CHUNKS)"
TOTAL_BYTES="$(getval TOTAL_BYTES)"
TOTAL_MS="$(getval TOTAL_MS)"
SAW_START="$(getval SAW_START)"
SAW_END="$(getval SAW_END)"
WROTE_WAV="$(getval WROTE_WAV)"
SATZ="$(getval TEXT)"

{
    echo "# voice-proof @ $(iso_now)  (port=$PORT, tts_ok=$TTS_OK, brain_ok=$BRAIN_OK, lang=$LANG_CODE)"
    echo "# Frage         : $QUESTION"
    echo "# Satz          : $SATZ"
    echo "# AudioEvents   : $AUDIO_EVENTS"
    echo "# NonemptyChunks: $NONEMPTY_CHUNKS"
    echo "# TotalBytes    : $TOTAL_BYTES"
    echo "# TotalMs       : $TOTAL_MS"
    echo "# Turn-Latenz   : ${LATENZ_MS} ms"
    echo "# WAV           : $WROTE_WAV"
    echo "# --- roher SSE-Stream ---"
    cat "$SSE_OUT"
} >> "$LOG"

echo
echo "  ${C_DIM}roher SSE-Stream: ${SSE_OUT#$REPO_ROOT/}${C_RESET}"

# ── (6) Urteil: ≥1 nicht-leerer AudioChunk = grün ────────────────────────────
if [ -z "${NONEMPTY_CHUNKS:-}" ] || [ "${NONEMPTY_CHUNKS:-0}" -lt 1 ]; then
    fail "KEIN nicht-leerer AudioChunk — der Turn blieb stumm (App-Log + SSE prüfen)"
    echo "  --- letzte App-Log-Zeilen ---"
    tail -20 "$APP_LOG"
    exit 1
fi

ok "Antwort-Satz   : $SATZ"
ok "AudioChunks    : $NONEMPTY_CHUNKS nicht-leer (von $AUDIO_EVENTS audio-Events)"
ok "Audio-Bytes    : $TOTAL_BYTES Bytes WAV"
ok "Audio-Dauer    : ${TOTAL_MS} ms (gemessen aus den WAV-Frames)"
ok "Start/End      : TtsAudioStart=$SAW_START  TtsAudioEnd=$SAW_END"
[ -n "$WROTE_WAV" ] && ok "WAV geschrieben: ${WROTE_WAV#$REPO_ROOT/}  (Andis Hörprobe)"
ok "Turn-Latenz    : ${LATENZ_MS} ms"
echo
say "${C_GREEN}voice GRÜN${C_RESET} — der Turn ist HÖRBAR: $NONEMPTY_CHUNKS AudioChunk(s), $TOTAL_BYTES Bytes, ${TOTAL_MS} ms."
[ "$BRAIN_OK" -ne 1 ] && warn "Hinweis: Brain war aus — vertont wurde die Never-Silent-Fallback-Phrase, nicht ein echter Brain-Satz."
exit 0
