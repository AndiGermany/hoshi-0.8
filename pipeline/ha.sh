#!/usr/bin/env bash
# pipeline/ha.sh — READ-ONLY verification of the Home Assistant edge.
#
# `bin/hoshi ha check` answers the one question SETUP.md's HA chapter leaves open:
# does the address/token this machine would hand the backend actually reach a live
# HA — and can that HA be read? It resolves address and token from EXACTLY the same
# sources as the backend (PipelineConfig.resolveHaToken / HOSHI_HA_BASE_URL default),
# so a green run here means the backend would authenticate with the same credentials.
#
# Four probes, each one line, each ✓/✗ plus one sentence:
#   (a) reachable   GET  /api/           — any HTTP answer proves the socket
#   (b) token       GET  /api/           — 200 + "API running" vs. 401/403
#   (c) areas       POST /api/template   — the SAME Jinja template HaAreaCatalogAdapter
#                                          uses (areas() → id::name||…), counts areas
#   (d) states      GET  /api/states     — entity count + newest last_updated (freshness)
#
# NEVER writes: no /api/services call, no registry write, no scene. Every request is
# a read. The token value is NEVER printed, never logged, and is handed to curl via
# `-K -` (stdin config) instead of argv, so it cannot be read out of `ps`.
#
# Honest exit code (0/2/3 convention of doctor/preflight):
#   0  all four probes ✓
#   2  reachable and token valid, but a read probe failed (degraded, not dead)
#   3  HA unreachable, or no/invalid token — nothing was proven
#
# From the dispatcher: bin/hoshi ha check

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

SUB="${1:-}"

ha_usage() {
    cat <<EOF
  ${C_BOLD}bin/hoshi ha check${C_RESET}   READ-ONLY-Probe gegen die echte Home Assistant:
                       erreichbar? · Token gültig? · Areas lesbar? · States frisch?

  Quellen (dieselben wie das Backend):
    Adresse : \$HOSHI_HA_BASE_URL   (Default http://homeassistant.local:8123)
    Token   : \$HOSHI_HA_TOKEN gewinnt, sonst ~/.hoshi/secrets.json["ha"]

  Schreibt nie und gibt den Token nie aus. Exit 0 nur, wenn alle vier Proben ✓.
EOF
}

if [ "$SUB" != "check" ]; then
    [ -n "$SUB" ] && fail "unbekanntes ha-Subcommand: $SUB"
    ha_usage
    exit 2
fi

# ── Sources: identical to the backend's resolution order ────────────────────
BASE_URL="${HOSHI_HA_BASE_URL:-http://homeassistant.local:8123}"
BASE_URL="${BASE_URL%/}"
TIMEOUT="${HOSHI_HA_CHECK_TIMEOUT:-8}"

# Env first (Bench/CI), then the secrets file — PipelineConfig.resolveHaToken order.
# Parsed with python3 (not jq: its presence is not guaranteed, s. doctor.sh).
TOKEN="${HOSHI_HA_TOKEN:-}"
TOKEN_SOURCE="Env HOSHI_HA_TOKEN"
if [ -z "$TOKEN" ]; then
    TOKEN_SOURCE="~/.hoshi/secrets.json[\"ha\"]"
    TOKEN="$(python3 - <<'PY' 2>/dev/null || true
import json, os, pathlib
p = pathlib.Path(os.path.expanduser("~/.hoshi/secrets.json"))
try:
    v = json.loads(p.read_text(encoding="utf-8")).get("ha") or ""
except Exception:
    v = ""
print(v.strip())
PY
)"
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-ha-check.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
BODY="$TMP_DIR/body"

say "HA-Check — read-only gegen die echte Home Assistant (schreibt nichts)"
log "Adresse: $BASE_URL"
if [ -n "$TOKEN" ]; then
    log "Token  : gefunden über $TOKEN_SOURCE (${#TOKEN} Zeichen, Wert wird nie ausgegeben)"
else
    log "Token  : KEINER gefunden (weder Env HOSHI_HA_TOKEN noch ~/.hoshi/secrets.json[\"ha\"])"
fi
# The deploy-time ceiling is a different truth than reachability: this shell's flag
# says nothing about the flag the RUNNING backend was started with. Say so, and keep
# it out of the verdict.
log "Decke  : HOSHI_HA_ENABLED in dieser Shell = ${HOSHI_HA_ENABLED:-nicht gesetzt (⇒ Default false)}"
log "         (nur Hinweis — was das laufende Backend fährt, steht in seiner Unit, nicht hier)"
echo

RC=0
note_degraded() { [ "$RC" -lt 2 ] && RC=2 || true; }

# curl with the bearer header fed through stdin config (-K -), so the token never
# appears in argv. Prints the HTTP status code; body lands in $BODY. Curl's own exit
# code goes to $CURL_RC (0 = a response arrived at all).
ha_request() { # method path [json_body_file]
    local method="$1" path="$2" data_file="${3:-}"
    local -a args=(-sS -o "$BODY" -w '%{http_code}' -m "$TIMEOUT" -X "$method"
                   -H 'Content-Type: application/json')
    [ -n "$data_file" ] && args+=(--data-binary "@$data_file")
    args+=("$BASE_URL$path")
    : >"$BODY"
    set +e
    HTTP_CODE="$(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" \
        | curl -K - "${args[@]}" 2>"$TMP_DIR/err")"
    CURL_RC=$?
    set -e
    HTTP_CODE="${HTTP_CODE:-000}"
}

# ── (a) reachable ───────────────────────────────────────────────────────────
# Any HTTP status proves the socket; only a transport error means unreachable.
ha_request GET /api/
if [ "$CURL_RC" -ne 0 ]; then
    fail "erreichbar : NEIN — $BASE_URL antwortet nicht ($(head -1 "$TMP_DIR/err" 2>/dev/null | tr -d '\r'))"
    echo
    fail "Urteil: HA UNERREICHBAR — Adresse/Netz prüfen (HOSHI_HA_BASE_URL). Nichts bewiesen."
    exit 3
fi
ok "erreichbar : ja — $BASE_URL antwortet auf GET /api/ (HTTP $HTTP_CODE)"

# ── (b) token valid ─────────────────────────────────────────────────────────
# The 401 distinction is the whole point: reachable-but-rejected is a token problem,
# not a network problem, and the two get confused every single time.
case "$HTTP_CODE" in
    200)
        if grep -q 'API running' "$BODY" 2>/dev/null; then
            ok "Token      : gültig — HA quittiert \"API running\" (Quelle: $TOKEN_SOURCE)"
        else
            warn "Token      : akzeptiert (HTTP 200), aber die Antwort trägt kein \"API running\" — kein HA an dieser Adresse?"
            note_degraded
        fi
        ;;
    401|403)
        if [ -z "$TOKEN" ]; then
            fail "Token      : FEHLT — HA antwortet $HTTP_CODE. Long-Lived Access Token in \$HOSHI_HA_TOKEN oder ~/.hoshi/secrets.json[\"ha\"] hinterlegen."
        else
            fail "Token      : ABGELEHNT (HTTP $HTTP_CODE) — der Token aus $TOKEN_SOURCE gilt an dieser HA nicht (abgelaufen/widerrufen/falsche Instanz)."
        fi
        echo
        fail "Urteil: KEIN ZUGANG — HA lebt, lässt uns aber nicht lesen. Nichts weiter bewiesen."
        exit 3
        ;;
    *)
        fail "Token      : unklar — HTTP $HTTP_CODE auf GET /api/ (weder 200 noch 401/403)."
        echo
        fail "Urteil: UNKLAR — die Gegenstelle verhält sich nicht wie eine HA-REST-API."
        exit 3
        ;;
esac

# ── (c) area registry ───────────────────────────────────────────────────────
# Exactly the template HaAreaCatalogAdapter sends (id::name, joined with ||) — so a
# green line here means the dynamic room catalogue would load, not something adjacent.
cat >"$TMP_DIR/template.json" <<'JSON'
{"template": "{% set ns = namespace(parts=[]) %}{% for a in areas() %}{% set ns.parts = ns.parts + [a ~ '::' ~ (area_name(a) | default(a, true))] %}{% endfor %}{{ ns.parts | join('||') }}"}
JSON
ha_request POST /api/template "$TMP_DIR/template.json"
if [ "$CURL_RC" -ne 0 ]; then
    fail "Areas      : POST /api/template lief in einen Transportfehler — Katalog nicht prüfbar"
    note_degraded
elif [ "$HTTP_CODE" != "200" ]; then
    fail "Areas      : POST /api/template → HTTP $HTTP_CODE (Template-Endpoint gesperrt oder Token zu schwach)"
    note_degraded
else
    AREA_COUNT="$(python3 - "$BODY" <<'PY' 2>/dev/null || echo "?"
import sys
raw = open(sys.argv[1], encoding="utf-8").read().strip()
parts = [p for p in raw.split("||") if "::" in p and p.split("::", 1)[0].strip()]
print(len(parts))
PY
)"
    if [ "$AREA_COUNT" = "0" ] || [ "$AREA_COUNT" = "?" ]; then
        fail "Areas      : Template antwortete, lieferte aber keine lesbare Area — dynamischer Raumkatalog fiele auf die statische Liste zurück"
        note_degraded
    else
        ok "Areas      : $AREA_COUNT Area(s) lesbar — der dynamische Raumkatalog (HOSHI_AREAS_DYNAMIC_ENABLED) hätte echte Räume"
    fi
fi

# ── (d) states + freshness ──────────────────────────────────────────────────
# Count alone can be stale: an HA that answers with a frozen snapshot looks healthy.
# The newest last_updated is the cheap freshness witness. No entity_id is printed —
# what is in this house stays out of pasted logs.
ha_request GET /api/states
if [ "$CURL_RC" -ne 0 ]; then
    fail "States     : GET /api/states lief in einen Transportfehler — Zustände nicht prüfbar"
    note_degraded
elif [ "$HTTP_CODE" != "200" ]; then
    fail "States     : GET /api/states → HTTP $HTTP_CODE"
    note_degraded
else
    STATE_LINE="$(python3 - "$BODY" <<'PY' 2>/dev/null || echo "ERROR"
import datetime, json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("ERROR"); raise SystemExit(0)
if not isinstance(data, list) or not data:
    print("EMPTY"); raise SystemExit(0)
newest = ""
for entry in data:
    ts = str(entry.get("last_updated") or "")
    if ts > newest:
        newest = ts
age = "?"
try:
    dt = datetime.datetime.fromisoformat(newest.replace("Z", "+00:00"))
    delta = int((datetime.datetime.now(datetime.timezone.utc) - dt).total_seconds())
    age = f"{delta} s" if delta < 120 else f"{delta // 60} min"
except Exception:
    pass
print(f"{len(data)}|{newest}|{age}")
PY
)"
    case "$STATE_LINE" in
        ERROR|"")
            fail "States     : Antwort war kein lesbares JSON-Array — Zustände nicht auswertbar"
            note_degraded
            ;;
        EMPTY)
            fail "States     : 0 Entitäten — diese HA kennt keine Geräte (frische/leere Instanz?)"
            note_degraded
            ;;
        *)
            ok "States     : ${STATE_LINE%%|*} Entität(en) lesbar · jüngstes last_updated $(printf '%s' "$STATE_LINE" | cut -d'|' -f2) (vor $(printf '%s' "$STATE_LINE" | cut -d'|' -f3))"
            ;;
    esac
fi

echo
case "$RC" in
    0) ok   "Urteil: HA-Rand GRÜN — erreichbar, Token gültig, Areas und Zustände lesbar (nichts geschrieben)." ;;
    *) warn "Urteil: EINGESCHRÄNKT — HA lebt und lässt uns rein, aber mindestens eine Lese-Probe kam nicht durch." ;;
esac
exit "$RC"
