#!/usr/bin/env bash
# golden-replay-satellit.sh — B2: Golden-Utterances-Replay gegen Prod (ct-106:8082)
# VOR dem Satellit-Ohr-Test (Cowork-Checkliste B2: „Rot vorab spart Abend-Frust").
#
# STATE-SICHER kuratiert (satellite-hand, 2026-07-08):
#   - Gefeuert: Probe (#20) · Timer-Zyklus #1–#7 SELBSTREINIGEND (Set→Query→Cancel,
#     Endzustand „kein Timer" wird als letzter Schritt BEWIESEN) · Wecker-QUERY (#9)
#     · Wetter (#17/#18) · Brain-Wächter (#11/#12/#16) · Listen-Ist-Stand (#13/#14).
#   - NICHT gefeuert: #8 „Weck mich morgen um halb sieben" + #10 „Wecker auf 22.57"
#     (ECHTER Wecker würde klingeln — kein verifizierter Voice-Wecker-Cancel) und
#     #19 „Licht im Wohnzimmer an" (echter Aktor-Flip). Diese drei = Testtag-live.
#   - #15 LIST_REMOVE übersprungen (kein Listen-Store; #13/#14 dokumentieren die Lücke).
#
# Muster = tools/prod-probe-0.8.sh: Token via python3 aus ~/.hoshi/secrets.json["api"],
# NIE ausgegeben/geloggt. SSE landet in Dateien (NIE `| head` auf einen Live-Stream).
# chatId-Präfix „replay-satellit-" markiert die Turns als Test-Traffic.
#
# Aufruf: bash tools/golden-replay-satellit.sh [outdir]
set -uo pipefail

BASE="${HOSHI_08_BASE:-https://ct-106:8082}"
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

OUT="${1:-$(mktemp -d "${TMPDIR:-/tmp}/hoshi-golden-replay.XXXXXX")}"
mkdir -p "$OUT"

# Ein Turn: SSE in Datei, dann kompakte Zeile: label · category · model · Text (gekürzt).
turn() { # $1=label $2=golden-nr $3=utterance
  local label="$1" nr="$2" text="$3" f="$OUT/sse-$1.txt"
  local body
  body=$(python3 -c 'import json,sys;print(json.dumps({"text":sys.argv[1],"chatId":"replay-satellit-"+sys.argv[2],"speak":False}))' "$text" "$label")
  curl -sk -N --max-time 45 -X POST "$BASE/api/v1/chat/stream" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "$body" > "$f" 2>&1 || true
  python3 - "$f" "$label" "$nr" "$text" <<'PYEOF'
import json, sys
f, label, nr, text = sys.argv[1:5]
cat = model = "-"; parts = []; err = ""; timings = {}
for line in open(f, encoding="utf-8", errors="replace"):
    line = line.strip()
    if not line.startswith("data:"): continue
    try: ev = json.loads(line[5:].strip())
    except Exception: continue
    t = ev.get("event")
    if t == "start": cat, model = ev.get("category","-"), ev.get("model","-")
    elif t == "delta": parts.append(ev.get("text",""))
    elif t == "error": err = f"ERROR[{ev.get('stage')}] {ev.get('message','')}"
    elif t == "done": timings = ev.get("stageTimings") or {}
answer = "".join(parts).replace("\n"," ").strip()
tstr = ",".join(f"{k}={v}" for k,v in timings.items() if v is not None)
print(f"#{nr:>3} {label:<10} {cat:<12} {model:<7} | {text}")
print(f"     → {answer[:220] or '(kein Text)'}")
if err: print(f"     ⚠ {err}")
if tstr: print(f"     ⏱ {tstr}")
PYEOF
}

echo "== Golden-Replay Satellit gegen $BASE — $(date '+%Y-%m-%d %H:%M') =="
echo "-- Kette/Probe --"
turn probe   20 "Hoshi, Probe."
echo "-- Timer-Zyklus (selbstreinigend) --"
turn t5a      5 "Läuft noch ein Timer?"
turn t3a      3 "Wie lange geht der Timer noch?"
turn t4a      4 "Wie lange noch?"
turn t1       1 "Stell einen Timer auf zehn Minuten."
turn t3b      3 "Wie lange geht der Timer noch?"
turn t6       6 "Stopp den Timer."
turn t2       2 "Timer auf acht Minuten."
turn t7       7 "Lösch alle Timer."
turn t5b      5 "Läuft noch ein Timer?"
echo "-- Wecker (nur Query — Set = Testtag-live) --"
turn w9       9 "Wann klingelt der Wecker?"
echo "-- Wetter --"
turn we17    17 "Wie wird das Wetter morgen?"
turn we18    18 "Brauch ich heute eine Jacke?"
echo "-- Brain-Wächter --"
turn b11     11 "Wie lange dauert Pasta kochen?"
turn b12     12 "Stell keinen Timer."
turn t5c      5 "Läuft noch ein Timer?"
turn b16     16 "Mach mir eine Liste von Ideen für Papas Geburtstag."
echo "-- Listen (known-missing dokumentieren) --"
turn l13     13 "Setz Milch auf die Einkaufsliste."
turn l14     14 "Was steht auf der Liste?"
echo "== fertig. SSE-Rohdaten: $OUT =="
