#!/usr/bin/env bash
# seam-watch.sh — Der Seam-Watcher der Hoshi-0.8-Pipeline.
#
# Institutionalisiert "gruen != lebt": probt jede Naht des verteilten Stacks
# live, vergleicht das GEMESSENE Brain-`model` gegen den Soll-Wert aus
# pipeline/expected.env und sagt je Naht ehrlich OK / DRIFT / UNREACHABLE.
#
#   OK          Naht erreichbar UND erfuellt die Erwartung.
#   DRIFT       Naht erreichbar, ABER Wert weicht ab (z.B. falsches Brain,
#               Auth-Wand offen, Backend nicht "up"). -> das ist die Luege,
#               die "gruen" sonst verdeckt.
#   UNREACHABLE Naht antwortet nicht. KEIN gruen, eigener Zustand.
#
# Exit-Codes (ehrlich, kein Fake-gruen):
#   0  alles OK
#   2  mindestens eine DRIFT  (Soll != Live)
#   3  keine DRIFT, aber mindestens eine UNREACHABLE
#
# Nur curl + bash + python3 (JSON). Read-only — proben, nicht aendern.
#
# Usage:
#   bash pipeline/seam-watch.sh            # einmal proben
#   bash pipeline/seam-watch.sh --quiet    # nur die Summary-Zeile + Exit-Code
#
# Env-Overrides (Defaults = die etablierte 0.x-Topologie):
#   HOSHI_BRAIN_URL    (default http://localhost:8041)
#   HOSHI_BACKEND_URL  (default https://192.168.178.106:8081)
#   SEAM_TIMEOUT       (default 6, Sekunden je Probe)

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPECTED_FILE="${SCRIPT_DIR}/expected.env"

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

# ── Soll-Werte laden ─────────────────────────────────────────────────────────
if [ ! -f "$EXPECTED_FILE" ]; then
    echo "FATAL: $EXPECTED_FILE fehlt — kein Soll-Wert, kein ehrlicher Vergleich." >&2
    exit 4
fi
# shellcheck disable=SC1090
. "$EXPECTED_FILE"
EXPECTED_BRAIN="${EXPECTED_BRAIN:-}"
AUTH_WALL="${AUTH_WALL:-401}"
EXPECTED_BACKEND_STATUS="${EXPECTED_BACKEND_STATUS:-up}"

# ── Endpunkte (override-bar) ─────────────────────────────────────────────────
BRAIN_URL="${HOSHI_BRAIN_URL:-http://localhost:8041}"
BACKEND_URL="${HOSHI_BACKEND_URL:-https://192.168.178.106:8081}"
WHISPER_URL="${HOSHI_WHISPER_URL:-http://localhost:9001}"
CAMPP_URL="${HOSHI_CAMPP_URL:-http://localhost:9002}"
KNOWLEDGE_URL="${HOSHI_KNOWLEDGE_URL:-http://localhost:8035}"
VOXTRAL_URL="${HOSHI_VOXTRAL_URL:-http://localhost:8042}"
OLLAMA_URL="${HOSHI_OLLAMA_URL:-http://localhost:11434}"
TIMEOUT="${SEAM_TIMEOUT:-6}"

# ── Farben ───────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_DIM=$'\033[2m'
else
    C_RESET=""; C_BOLD=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""
fi

OK_N=0; DRIFT_N=0; UNREACH_N=0

# print one seam result line + tally
report() { # state name detail
    local state="$1" name="$2" detail="$3" tag
    case "$state" in
        OK)          OK_N=$((OK_N+1));      tag="${C_GREEN}OK         ${C_RESET}" ;;
        DRIFT)       DRIFT_N=$((DRIFT_N+1)); tag="${C_RED}DRIFT      ${C_RESET}" ;;
        UNREACHABLE) UNREACH_N=$((UNREACH_N+1)); tag="${C_YELLOW}UNREACHABLE${C_RESET}" ;;
    esac
    [ "$QUIET" = "1" ] && return 0
    printf "  %s  %-22s %s\n" "$tag" "$name" "${C_DIM}${detail}${C_RESET}"
}

# ── Helpers ──────────────────────────────────────────────────────────────────
# http_code URL -> echoes HTTP status, "000" = unreachable
http_code() { curl -sk --max-time "$TIMEOUT" -o /dev/null -w '%{http_code}' "$1" 2>/dev/null || echo "000"; }

# body URL -> echoes response body ("" on failure)
body() { curl -sk --max-time "$TIMEOUT" "$1" 2>/dev/null || true; }

# json_path '<json>' dotted.path -> echoes value or ""
json_path() {
    printf '%s' "$1" | python3 -c '
import sys, json
path = sys.argv[1]
try:
    d = json.load(sys.stdin)
    for k in path.split("."):
        d = d[k]
    print(d if not isinstance(d, bool) else str(d).lower())
except Exception:
    print("")
' "$2"
}

# Generic liveness probe (GET expects 200) for sidecars without a value contract.
check_live() { # name url
    local name="$1" url="$2" code
    code=$(http_code "$url/health")
    if [ "$code" = "000" ]; then
        report UNREACHABLE "$name" "$url/health — keine Antwort"
    elif [ "$code" = "200" ]; then
        report OK "$name" "$url/health -> 200"
    else
        report DRIFT "$name" "$url/health -> $code (erwartet 200)"
    fi
}

[ "$QUIET" = "0" ] && {
    echo "${C_BOLD}Seam-Watch${C_RESET}  $(date '+%Y-%m-%d %H:%M:%S')  soll-brain='${EXPECTED_BRAIN}' auth-wall=${AUTH_WALL}"
    echo
}

# ── Naht 1: Brain (e4b :8041) — Wert-Vergleich gegen EXPECTED_BRAIN ──────────
brain_body="$(body "$BRAIN_URL/health")"
if [ -z "$brain_body" ]; then
    report UNREACHABLE "brain(e4b)" "$BRAIN_URL/health — keine Antwort"
else
    measured_model="$(json_path "$brain_body" model)"
    loaded="$(json_path "$brain_body" loaded)"
    if [ -z "$measured_model" ]; then
        report DRIFT "brain(e4b)" "Antwort ohne .model-Feld: $(printf '%s' "$brain_body" | head -c 80)"
    elif case "$measured_model" in *"$EXPECTED_BRAIN"*) true;; *) false;; esac && [ "$loaded" = "true" ]; then
        report OK "brain(e4b)" "model='$measured_model' loaded=true"
    elif case "$measured_model" in *"$EXPECTED_BRAIN"*) true;; *) false;; esac; then
        report DRIFT "brain(e4b)" "model ok aber loaded='$loaded' (erwartet true)"
    else
        report DRIFT "brain(e4b)" "model='$measured_model' enthaelt nicht soll='$EXPECTED_BRAIN'"
    fi
fi

# ── Naht 2..5: Mac-Sidecars (reine Liveness) ────────────────────────────────
check_live "whisper-stt"      "$WHISPER_URL"
check_live "campplus-spk"     "$CAMPP_URL"
check_live "knowledge-bridge" "$KNOWLEDGE_URL"
check_live "voxtral-tts"      "$VOXTRAL_URL"

# ── Naht 6: Ollama (Embeddings) — /api/ps muss JSON liefern ─────────────────
ps_body="$(body "$OLLAMA_URL/api/ps")"
if [ -z "$ps_body" ]; then
    report UNREACHABLE "ollama(embed)" "$OLLAMA_URL/api/ps — keine Antwort"
elif printf '%s' "$ps_body" | grep -q '"models"'; then
    n_models=$(printf '%s' "$ps_body" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("models",[])))' 2>/dev/null || echo "?")
    report OK "ollama(embed)" "/api/ps -> ${n_models} model(s) resident"
else
    report DRIFT "ollama(embed)" "/api/ps ohne models-Feld"
fi

# ── Naht 7: ct-106 Backend /api/health — .status == EXPECTED_BACKEND_STATUS ──
be_body="$(body "$BACKEND_URL/api/health")"
if [ -z "$be_body" ]; then
    report UNREACHABLE "ct-106 backend" "$BACKEND_URL/api/health — keine Antwort"
else
    be_status="$(json_path "$be_body" status)"
    be_llm="$(json_path "$be_body" llm.detail)"
    if [ "$be_status" = "$EXPECTED_BACKEND_STATUS" ]; then
        # Cross-Seam-Bonus: meldet das Backend dasselbe Brain wie der Soll-Wert?
        if [ -n "$be_llm" ] && case "$be_llm" in *"$EXPECTED_BRAIN"*) false;; *) true;; esac; then
            report DRIFT "ct-106 backend" "status=up ABER backend-llm='$be_llm' != soll-brain"
        else
            report OK "ct-106 backend" "status=$be_status llm='$be_llm'"
        fi
    else
        report DRIFT "ct-106 backend" "status='$be_status' (erwartet '$EXPECTED_BACKEND_STATUS')"
    fi
fi

# ── Naht 8: Auth-Wand /api/v1/speakers — muss AUTH_WALL (401) sein ──────────
wall_code="$(http_code "$BACKEND_URL/api/v1/speakers")"
if [ "$wall_code" = "000" ]; then
    report UNREACHABLE "auth-wall" "$BACKEND_URL/api/v1/speakers — keine Antwort"
elif [ "$wall_code" = "$AUTH_WALL" ]; then
    report OK "auth-wall" "/api/v1/speakers -> $wall_code (Perimeter dicht)"
else
    report DRIFT "auth-wall" "/api/v1/speakers -> $wall_code (erwartet $AUTH_WALL — PERIMETER?)"
fi

# ── Summary + ehrlicher Exit ────────────────────────────────────────────────
echo
TOTAL=$((OK_N + DRIFT_N + UNREACH_N))
if [ "$DRIFT_N" -gt 0 ]; then
    verdict="${C_RED}DRIFT${C_RESET}"; rc=2
elif [ "$UNREACH_N" -gt 0 ]; then
    verdict="${C_YELLOW}UNREACHABLE${C_RESET}"; rc=3
else
    verdict="${C_GREEN}ALL-OK${C_RESET}"; rc=0
fi
echo "${C_BOLD}Summary:${C_RESET} $verdict — ${OK_N}/${TOTAL} OK · ${DRIFT_N} DRIFT · ${UNREACH_N} UNREACHABLE  (exit ${rc})"
exit $rc
