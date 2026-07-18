#!/usr/bin/env bash
# diary-gap-rate.sh — S1 des Nachtschicht-Spikes (PREP-nachtschicht-spike.md).
#
# READ-ONLY Lücken-Rate-Report aus dem Turn-Diary (#10):
#   Turns gesamt · deflected-Rate · deflected nach category · groundingUsed-Rate · error-Rate.
#
# Quelle 1: GET /api/v1/diary/recent?limit=500 (ct-106, HTTPS :8082, PerimeterWebFilter-Token)
# Quelle 2 (Fallback): lokale JSONL ~/.hoshi/diary/turn-diary-YYYY-MM-DD.jsonl (heute+gestern)
#
# Ehrlichkeits-Regel: leeres Diary => "KEINE DATEN" (Exit 3), NIE 0% erfinden.
# Token: env HOSHI_API_TOKEN, sonst ~/.hoshi/secrets.json["api"] via python3.
# Der Token wird NIE geloggt/ausgegeben.
#
# Aufruf: tools/diary-gap-rate.sh            # menschlicher Report auf stdout
#         HOSHI_08_BASE=https://ct-106:8082  # Basis-URL überschreibbar
set -euo pipefail

BASE="${HOSHI_08_BASE:-https://ct-106:8082}"
LIMIT=500

# ct-106 ist (auf dem Mac) nur ein SSH-Alias, kein DNS-Name — Host aus ssh -G
# auflösen, damit der Default-Aufruf ohne Env-Override funktioniert.
if [[ "$BASE" == *"//ct-106:"* ]] && ! ping -c1 -t1 ct-106 >/dev/null 2>&1; then
  resolved="$(ssh -G ct-106 2>/dev/null | awk '/^hostname /{print $2}')"
  [[ -n "$resolved" ]] && BASE="${BASE/ct-106/$resolved}"
fi
SECRETS="${HOME}/.hoshi/secrets.json"
DIARY_DIR="${HOSHI_TURN_DIARY_DIR:-${HOME}/.hoshi/diary}"

# --- Token besorgen (nie ausgeben!) -----------------------------------------
token="${HOSHI_API_TOKEN:-}"
if [[ -z "$token" && -r "$SECRETS" ]]; then
  token="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("api",""))' "$SECRETS" 2>/dev/null || true)"
fi

# --- Quelle 1: API (heute+gestern, max 500, neueste zuerst) ------------------
data=""
source_label=""
if [[ -n "$token" ]]; then
  api_json="$(curl -sk --max-time 15 -H "Authorization: Bearer ${token}" \
      "${BASE}/api/v1/diary/recent?limit=${LIMIT}" 2>/dev/null || true)"
  if jq -e 'type=="array"' >/dev/null 2>&1 <<<"$api_json"; then
    data="$api_json"
    source_label="API ${BASE}/api/v1/diary/recent?limit=${LIMIT} (heute+gestern, Kappe ${LIMIT})"
  fi
fi

# --- Quelle 2: lokale JSONL (Fallback) ---------------------------------------
if [[ -z "$data" || "$(jq 'length' <<<"$data")" == "0" ]]; then
  files=()
  for d in "$(date +%F)" "$(date -v-1d +%F 2>/dev/null || date -d yesterday +%F)"; do
    f="${DIARY_DIR}/turn-diary-${d}.jsonl"
    [[ -s "$f" ]] && files+=("$f")
  done
  if (( ${#files[@]} > 0 )); then
    local_json="$(cat "${files[@]}" | jq -s '[.[] | select(type=="object")]' 2>/dev/null || echo '[]')"
    if [[ "$(jq 'length' <<<"$local_json")" != "0" ]]; then
      data="$local_json"
      source_label="lokale JSONL: ${files[*]}"
    fi
  fi
fi

# --- Ehrlichkeit: nichts da ist nichts da ------------------------------------
if [[ -z "$data" || "$(jq 'length' <<<"$data")" == "0" ]]; then
  echo "KEINE DATEN — Diary leer/nicht erreichbar (API ${BASE} und ${DIARY_DIR}). Gate NICHT reif, keine Rate erfunden."
  exit 3
fi

# --- Statistik (eine jq-Passage, keine geschätzten Zahlen) --------------------
jq -r --arg src "$source_label" '
  length as $n
  | ([.[] | select(.deflected == true)]) as $defl
  | ([.[] | select(.groundingUsed == true)] | length) as $ground
  | ([.[] | select(.error != null and .error != "")] | length) as $err
  | ([.[].ts] | sort) as $ts
  | "Quelle: \($src)",
    "Zeitraum: \($ts[0]) .. \($ts[-1])",
    "Turns gesamt: \($n)",
    "deflected: \($defl|length) / \($n) = \(($defl|length)*10000/$n|round/100)%",
    "groundingUsed: \($ground) / \($n) = \($ground*10000/$n|round/100)%",
    "error: \($err) / \($n) = \($err*10000/$n|round/100)%",
    "",
    "deflected nach category (Anteil an allen Turns dieser Kategorie):",
    ( group_by(.category)[]
      | (map(select(.deflected==true))|length) as $d
      | length as $c
      | "  \(.[0].category // "?"): \($d) / \($c) = \($d*10000/$c|round/100)%" ),
    "",
    "Turns nach category:",
    ( group_by(.category)[] | "  \(.[0].category // "?"): \(length)" ),
    "",
    # source-Segmentierung (seit 05.07 trägt jede Zeile den Eingangs-Rand:
    # "chat" | "voice" | "ws"; ""/fehlend = Alt-Zeile aus der Chat-only-Ära).
    # NO_INPUT (stumme Voice-Turns) gesondert: sie zählen in die error-Rate oben,
    # dürfen aber die Deflect-/Grounding-Deutung nicht verwässern.
    "Turns nach source (\"\"=Alt-Zeile vor 05.07, Chat-only-Ära):",
    ( group_by(.source // "")[]
      | (map(select(.deflected==true))|length) as $d
      | (map(select(.groundingUsed==true))|length) as $g
      | (map(select(.category=="NO_INPUT"))|length) as $ni
      | length as $c
      | "  \(.[0].source // "" | if .=="" then "\"\" (alt)" else . end): \($c) Turns · deflected \($d) · groundingUsed \($g)\(if $ni>0 then " · davon NO_INPUT \($ni) (stumm)" else "" end)" )
' <<<"$data"
