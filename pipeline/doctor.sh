#!/usr/bin/env bash
# pipeline/doctor.sh — der EHRLICHE Stack-Check (READ-ONLY).
#
# grün≠lebt: der Brain wird NICHT über /health gemessen, sondern über einen
# echten /v1/chat-Roundtrip (brain_classify → OK/WEDGE/ZOMBIE/DOWN/LOADING).
# Andere Sidecars: GET /health bzw. TCP-Connect. Voxtral ist BEWUSST AUS
# (launchd disabled) → wird als DISABLED(gewollt) gemeldet, NICHT als DOWN-Fehler.
#
# KEIN Start, KEIN Kill — nur messen.
#
# Exit-Code (ehrlich):
#   0  alle kritischen OK (Brain OK + ollama OK; voxtral DISABLED zählt nicht)
#   2  DEGRADED / LOADING / WEDGE (erreichbar, aber nicht voll bereit)
#   3  Brain DOWN / ZOMBIE
#
# Vom Dispatcher: bin/hoshi doctor

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/stack-lib.sh"
set +e   # Diagnose: Probes dürfen „fehlschlagen", wir werten selbst aus.

cd "$REPO_ROOT"

say "Stack-Doctor — read-only, echter Roundtrip statt Selbstauskunft"
echo

# ── Tabellen-Helfer ──────────────────────────────────────────────────────────
hr() { printf '  %s\n' "────────────┬───────┬────────────┬──────────────────────────────────────────"; }
row() { # name port status_colored detail
    printf '  %-11s │ %-5s │ %b │ %s\n' "$1" "$2" "$3" "$4"
}
st() { # raw_status → farbiges, fix-breites Label (10 Zeichen)
    case "$1" in
        OK)               printf '%bOK        %b' "$C_GREEN" "$C_RESET" ;;
        DISABLED)         printf '%bDISABLED  %b' "$C_DIM"   "$C_RESET" ;;
        LOADING)          printf '%bLOADING   %b' "$C_YELLOW" "$C_RESET" ;;
        WEDGE|DEGRADED)   printf '%b%-10s%b'      "$C_YELLOW" "$1" "$C_RESET" ;;
        DOWN|ZOMBIE)      printf '%b%-10s%b'      "$C_RED"    "$1" "$C_RESET" ;;
        *)                printf '%-10s' "$1" ;;
    esac
}

printf '  %-11s │ %-5s │ %-10s │ %s\n' "NAME" "PORT" "STATUS" "DETAIL"
hr

RC=0
note_degraded() { [ "$RC" -lt 2 ] && RC=2; }

# ── Brain (e4b) — der teure Fall: echter Roundtrip ──────────────────────────
# direkt aufrufen (nicht $()), damit BRAIN_DETAIL/BRAIN_RT_MS propagieren.
brain_classify >/dev/null
row "brain(e4b)" "$BRAIN_PORT" "$(st "$BRAIN_STATUS")" "$BRAIN_DETAIL"
case "$BRAIN_STATUS" in
    OK)             : ;;
    DOWN|ZOMBIE)    RC=3 ;;
    WEDGE|LOADING)  note_degraded ;;
esac

# ── Ollama (Embeddings) — kritisch ──────────────────────────────────────────
OLLAMA_PS="$(curl -s -m 3 "$OLLAMA_URL/api/ps" 2>/dev/null || true)"
if [ -n "$OLLAMA_PS" ] && printf '%s' "$OLLAMA_PS" | grep -q '"models"'; then
    n="$(printf '%s' "$OLLAMA_PS" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("models",[])))' 2>/dev/null || echo '?')"
    row "ollama" "$OLLAMA_PORT" "$(st OK)" "/api/ps → ${n} Modell(e) resident"
else
    row "ollama" "$OLLAMA_PORT" "$(st DOWN)" "/api/ps stumm — Embeddings tot (kritisch)"
    note_degraded
fi

# ── Reine Liveness-Sidecars (GET /health, TCP-Fallback) ─────────────────────
check_side() { # label port url
    local label="$1" port="$2" url="$3"
    if probe_http_health "$url" 4; then
        row "$label" "$port" "$(st OK)" "/health → 200"
    elif probe_tcp "$port"; then
        row "$label" "$port" "$(st DEGRADED)" "Port offen, /health != 200 (erreichbar, nicht bereit)"
        note_degraded
    else
        row "$label" "$port" "$(st DOWN)" "Port tot — kein Prozess"
        note_degraded
    fi
}
check_side "whisper-stt"  "$WHISPER_PORT"   "http://127.0.0.1:$WHISPER_PORT"
check_side "speaker-id"   "$SPEAKERID_PORT" "http://127.0.0.1:$SPEAKERID_PORT"
check_side "knowledge"    "$BRIDGE_PORT"    "http://127.0.0.1:$BRIDGE_PORT"

# ── Voxtral — BEWUSST AUS (launchd disabled). KEIN Fehler. ──────────────────
if probe_tcp "$VOXTRAL_PORT"; then
    row "voxtral-tts" "$VOXTRAL_PORT" "$(st DISABLED)" "läuft unerwartet (gewollt AUS — launchd disabled)"
else
    row "voxtral-tts" "$VOXTRAL_PORT" "$(st DISABLED)" "gewollt AUS (launchd disabled) — kein Fehler"
fi
hr
echo

# ── RAM-Wand + Brain-Guard-Status ───────────────────────────────────────────
FREE_PCT="$(mem_free_pct)"
log "RAM-Wand   : frei ${FREE_PCT:-?} (memory_pressure)"
if brain_guard_blocks; then
    warn "Brain-Guard: BLOCK — $GUARD_REASON"
else
    log "Brain-Guard: $GUARD_REASON"
fi
echo

# ── Stabilitäts-Guards (Lessons-als-Guards, READ-ONLY Hinweise) ──────────────
# WARN ist hier ein HINWEIS, kein Fehler — verschlechtert den Exit-Code NICHT.
echo "  ── Stabilitäts-Guards (Lessons-als-Guards) ──"
stability_guards   # gibt OK/WARN/INFO-Zeilen aus, setzt STABILITY_WARN=<n>
echo

# ── Gesamt-Urteil ────────────────────────────────────────────────────────────
SW="${STABILITY_WARN:-0}"
SUFFIX=""
[ "$SW" -gt 0 ] && SUFFIX=" · ${SW} Stabilitäts-WARN(s)"
case "$RC" in
    0) ok   "Urteil: ALL-OK — Brain generiert echt, ollama resident, Voxtral gewollt aus.${SUFFIX}" ;;
    2) warn "Urteil: DEGRADED — etwas ist erreichbar, aber nicht voll bereit (kein Fake-grün).${SUFFIX}" ;;
    3) fail "Urteil: BRAIN $BRAIN_STATUS — der Brain lebt NICHT (heal nötig).${SUFFIX}" ;;
esac
exit "$RC"
