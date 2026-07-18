#!/usr/bin/env bash
# prod-probe-0.8.sh — Live-Beweis-Probe gegen Prod (ct-106:8082, HTTPS).
#
# grün≠lebt in EINEM Aufruf: (a) Warm-Turn (Erst-Call nach Boot zeigt oft einen
# transienten Hakt-Fallback — darum zählt die ZWEITE Probe) · (b) ET-S3-Cache-
# Re-Ask („Dienstag" — erwartet direkte warme Antwort aus der Nachgeschlagen-
# Notiz, KEIN Deflect, KEIN zweiter Cloud-Call) · (c) Timer-Named-Cancel ohne
# Treffer (erwartet ehrliche Antwort statt Rückfrage, model=policy) · danach
# Diary-Tail mit den S4-Feldern (escalated/escalationCostCents/cacheHit).
#
# Muster = tools/diary-gap-rate.sh: Token via python3 aus ~/.hoshi/secrets.json
# ["api"], NIE ausgegeben/geloggt. SSE landet in Dateien (nie `| head` auf einen
# Live-Stream!), Ausgabe ist der zusammengesetzte Antwort-Text (gekürzt).
#
# Aufruf: bash tools/prod-probe-0.8.sh          # (cwd egal, BASE überschreibbar)
#         HOSHI_08_BASE=https://ct-106:8082 bash tools/prod-probe-0.8.sh
set -euo pipefail

BASE="${HOSHI_08_BASE:-https://ct-106:8082}"
# ct-106 ist (auf dem Mac) nur ein SSH-Alias, kein DNS-Name — Host aus ssh -G.
if [[ "$BASE" == *"//ct-106:"* ]] && ! ping -c1 -t1 ct-106 >/dev/null 2>&1; then
  resolved="$(ssh -G ct-106 2>/dev/null | awk '/^hostname /{print $2}')"
  [[ -n "$resolved" ]] && BASE="${BASE/ct-106/$resolved}"
fi
SECRETS="${HOME}/.hoshi/secrets.json"
token="${HOSHI_API_TOKEN:-}"
if [[ -z "$token" && -r "$SECRETS" ]]; then
  token="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("api",""))' "$SECRETS" 2>/dev/null || true)"
fi
[[ -n "$token" ]] || { echo "FEHLER: kein Token (HOSHI_API_TOKEN oder secrets.json[api])" >&2; exit 2; }

OUT="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-prod-probe.XXXXXX")"

turn() { # $1=label $2=json-body — SSE in Datei, Antwort-Text zusammensetzen
  local label="$1" body="$2" f="$OUT/sse-$1.txt"
  curl -sk -N --max-time 35 -X POST "$BASE/api/v1/chat/stream" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "$body" > "$f" 2>&1 || true
  echo "── $label ──"
  grep '^data:' "$f" | sed 's/^data: *//' \
    | jq -rs '[.[] | select(.text? // empty | length>0) | .text] | join("")' 2>/dev/null \
    | cut -c1-400
}

turn warm  '{"text":"Kurz: alles ok bei dir?","chatId":"probe-warm","speak":false}'
turn cache '{"text":"Woher kommt der Name Dienstag?","chatId":"probe-cache","speak":false}'
turn timer '{"text":"Stopp bitte den Nudel-Timer.","chatId":"probe-timer","speak":false}'
turn golden20 '{"text":"Hoshi, Probe.","chatId":"probe-golden20","speak":false}'

sleep 2
echo "── Diary (letzte 5: chatId · category · model · escalated · costCents · cacheHit · deflected) ──"
curl -sk --max-time 15 -H "Authorization: Bearer ${token}" \
  "$BASE/api/v1/diary/recent?limit=5" 2>/dev/null \
  | jq -r '.[] | [.chatId // "-", .category // "-", .model // "-", (.escalated|tostring), (.escalationCostCents|tostring), (.cacheHit|tostring), (.deflected // false|tostring)] | @tsv' 2>/dev/null \
  || echo "Diary-Read fehlgeschlagen"
echo "(SSE-Rohdaten: $OUT)"
