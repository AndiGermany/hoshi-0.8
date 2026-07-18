#!/usr/bin/env bash
# pipeline/bench-brain.sh — WIEDERVERWENDBARE Brain-Micro-Benchmark-Harness.
#
# Misst Latenz + Qualität des MLX-Brain-Sidecars (POST <url>, MLX-Format
# {messages, max_tokens, temperature, stream}) über eine feste Test-Matrix
# (Latenz / Wissen / Smart-Home  ×  DE / EN). Pro Prompt werden gemessen:
#   - TTFT        : Zeit bis zum 1. Token (Wall-Clock, im Stream-Reader gestoppt)
#   - Gesamt-Latenz: bis [DONE] bzw. Stream-Ende
#   - Tokens      : Anzahl nicht-leerer delta-Frames (≈ generierte Tokens)
#   - Antwort     : wörtlich (für das Qualitäts-Urteil)
# SSE-Parse exakt wie der Brain-Adapter: jede Zeile `data: {"delta":"..."}`,
# Ende `data: [DONE]` (zusätzlich OpenAI-Form choices[0].delta.content defensiv).
#
# Parametrierbar, damit nach einem Modell-Swap (e2b/12b) wiederverwendbar:
#   --url    Brain-Endpoint   (Default http://localhost:8041/v1/chat)
#   --label  Modell-Label     (Default e4b)  — nur für Ausgabe/Logname
#   --timeout  Sekunden/Prompt (Default 40)
#   --repeat   Wiederholungen der KURZEN Latenz-Prompts (Default 2, Mittelwert)
#
# Lädt NICHTS — nutzt nur das bereits geladene Modell. Crasht nicht bei
# leeren Antworten (Memory-Druck wird ehrlich vermerkt).
#
# Beispiel:
#   bash pipeline/bench-brain.sh --url http://localhost:8041/v1/chat --label e4b
#
# Log: <repo>/.pipeline/bench-brain-<label>-<ts>.{log,md}

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# ── Parameter ────────────────────────────────────────────────────────────────
URL="http://localhost:8041/v1/chat"
LABEL="e4b"
TIMEOUT=40
REPEAT=2
while [ "$#" -gt 0 ]; do
    case "$1" in
        --url)      shift; URL="${1:-}" ;;
        --url=*)    URL="${1#--url=}" ;;
        --label)    shift; LABEL="${1:-}" ;;
        --label=*)  LABEL="${1#--label=}" ;;
        --timeout)  shift; TIMEOUT="${1:-40}" ;;
        --timeout=*) TIMEOUT="${1#--timeout=}" ;;
        --repeat)   shift; REPEAT="${1:-2}" ;;
        --repeat=*) REPEAT="${1#--repeat=}" ;;
        -h|--help)
            grep -E '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) warn "unbekanntes Argument: $1" ;;
    esac
    shift
done

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/bench-brain-$LABEL-$TS.log"
MD="$PIPELINE_LOG_DIR/bench-brain-$LABEL-$TS.md"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/benchbrain.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# ── Stream-Client (Python, stdlib) ───────────────────────────────────────────
# Liest messages aus $1 (JSON-Datei), streamt POST gegen --url, stoppt TTFT im
# Reader, summiert delta-Tokens, gibt EINE JSON-Zeile auf stdout aus:
#   {"ttft_ms":int|null,"total_ms":int,"tokens":int,"text":str,"error":str|null}
CLIENT="$WORK/client.py"
cat >"$CLIENT" <<'PY'
import json, sys, time, urllib.request, urllib.error

url      = sys.argv[1]
msg_file = sys.argv[2]
max_tok  = int(sys.argv[3])
temp     = float(sys.argv[4])
timeout  = float(sys.argv[5])

with open(msg_file, encoding="utf-8") as f:
    messages = json.load(f)

body = json.dumps({
    "messages": messages,
    "max_tokens": max_tok,
    "temperature": temp,
    "stream": True,
}).encode("utf-8")

req = urllib.request.Request(
    url, data=body,
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    method="POST",
)

texts, ttft_ms, ntok, err = [], None, 0, None
t0 = time.monotonic()
try:
    resp = urllib.request.urlopen(req, timeout=timeout)
    for raw in resp:  # zeilenweise → Streaming
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            if payload == "[DONE]":
                break
            continue
        try:
            node = json.loads(payload)
        except Exception:
            continue
        # Brain-Format: flaches {"delta":"..."}; defensiv OpenAI choices[].delta.content
        delta = node.get("delta")
        if not isinstance(delta, str) or delta == "":
            try:
                delta = node["choices"][0]["delta"].get("content", "")
            except Exception:
                delta = ""
        if delta:
            if ttft_ms is None:
                ttft_ms = int((time.monotonic() - t0) * 1000)
            ntok += 1
            texts.append(delta)
except urllib.error.URLError as e:
    err = f"URLError: {getattr(e, 'reason', e)}"
except Exception as e:  # noqa
    err = f"{type(e).__name__}: {e}"

total_ms = int((time.monotonic() - t0) * 1000)
print(json.dumps({
    "ttft_ms": ttft_ms,
    "total_ms": total_ms,
    "tokens": ntok,
    "text": "".join(texts).strip(),
    "error": err,
}, ensure_ascii=False))
PY

# ── Helfer: einen Lauf ausführen, JSON-Zeile zurückgeben ─────────────────────
# run_once <messages-json-file> <max_tokens> <temp>
run_once() {
    python3 "$CLIENT" "$URL" "$1" "$2" "$3" "$TIMEOUT" 2>>"$LOG" \
        || echo '{"ttft_ms":null,"total_ms":0,"tokens":0,"text":"","error":"client crashed"}'
}

# field <json> <key> — zieht EIN Feld robust per python (Antwort kann Pipes/Newlines tragen)
field() { printf '%s' "$1" | python3 -c 'import json,sys; v=json.load(sys.stdin).get(sys.argv[1]); print("" if v is None else v)' "$2" 2>/dev/null; }

# ── Test-Matrix bauen (messages-Dateien) ─────────────────────────────────────
SH_SYS_DE='Du bist ein Smart-Home-Parser. Gib NUR kompaktes JSON {"domain","service","entity_hint"} zurück, nichts sonst.'
SH_SYS_EN='You are a smart-home parser. Return ONLY compact JSON {"domain","service","entity_hint"}, nothing else.'

mk_user() { # mk_user <file> <user>
    python3 -c 'import json,sys; json.dump([{"role":"user","content":sys.argv[2]}], open(sys.argv[1],"w",encoding="utf-8"), ensure_ascii=False)' "$1" "$2"
}
mk_sys_user() { # mk_sys_user <file> <system> <user>
    python3 -c 'import json,sys; json.dump([{"role":"system","content":sys.argv[2]},{"role":"user","content":sys.argv[3]}], open(sys.argv[1],"w",encoding="utf-8"), ensure_ascii=False)' "$1" "$2" "$3"
}

mk_user      "$WORK/p1.json" "Sag in einem kurzen Satz Hallo."
mk_user      "$WORK/p2.json" "Say hello in one short sentence."
mk_user      "$WORK/p3.json" "Wer war Konrad Adenauer? Antworte in 1-2 Sätzen."
mk_user      "$WORK/p4.json" "Who was Konrad Adenauer? Answer in 1-2 sentences."
mk_sys_user  "$WORK/p5.json" "$SH_SYS_DE" "Mach das Licht im Wohnzimmer an."
mk_sys_user  "$WORK/p6.json" "$SH_SYS_EN" "Turn on the living room light."

# Spalten je Prompt:  id | typ | lang | file | max_tokens | temp | repeat
#   (Latenz-Prompts: repeat=$REPEAT → Mittelwert; Rest: 1×)
MATRIX=(
  "P1|Latenz|DE|$WORK/p1.json|30|0.3|$REPEAT"
  "P2|Latenz|EN|$WORK/p2.json|30|0.3|$REPEAT"
  "P3|Wissen|DE|$WORK/p3.json|80|0.3|1"
  "P4|Wissen|EN|$WORK/p4.json|80|0.3|1"
  "P5|SmartHome|DE|$WORK/p5.json|40|0.0|1"
  "P6|SmartHome|EN|$WORK/p6.json|40|0.0|1"
)

# ── Vorprüfung: lebt der Brain? ──────────────────────────────────────────────
HEALTH_URL="${URL%/v1/chat}/health"
MODEL="?"
if H="$(curl -s -m 5 "$HEALTH_URL" 2>/dev/null)" && printf '%s' "$H" | grep -q '"status":"ok"'; then
    MODEL="$(printf '%s' "$H" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("model","?"))' 2>/dev/null || echo '?')"
    ok "Brain lebt @ $HEALTH_URL  ·  model=$MODEL"
else
    warn "Health ($HEALTH_URL) nicht ok — versuche trotzdem (URL evtl. ohne /health)"
fi

say "Benchmark  label=$LABEL  url=$URL  timeout=${TIMEOUT}s  repeat(latenz)=$REPEAT"
echo "# bench-brain  label=$LABEL  url=$URL  model=$MODEL  @ $(iso_now)" >"$LOG"

# ── Lauf ─────────────────────────────────────────────────────────────────────
declare -a ROWS=()   # je Zeile: "typ|lang|ttft|total|tokens|answer_short|verdict_raw"
for row in "${MATRIX[@]}"; do
    IFS='|' read -r id typ lang file maxt temp rep <<<"$row"
    say "$id  $typ/$lang  (max_tokens=$maxt, temp=$temp, runs=$rep)"

    sum_ttft=0; sum_total=0; got_ttft=0; last_text=""; last_tokens=0; last_err=""
    for r in $(seq 1 "$rep"); do
        out="$(run_once "$file" "$maxt" "$temp")"
        printf '%s\n' "$out" >>"$LOG"
        ttft="$(field "$out" ttft_ms)"
        total="$(field "$out" total_ms)"
        toks="$(field "$out" tokens)"
        txt="$(field "$out" text)"
        errf="$(field "$out" error)"
        [ -z "$total" ] && total=0
        [ -z "$toks" ] && toks=0
        if [ -n "$ttft" ]; then sum_ttft=$((sum_ttft + ttft)); got_ttft=$((got_ttft + 1)); fi
        sum_total=$((sum_total + total))
        last_text="$txt"; last_tokens="$toks"; last_err="$errf"
        log "  run $r: ttft=${ttft:-—}ms total=${total}ms tokens=${toks} ${errf:+ERR=$errf}"
    done

    if [ "$got_ttft" -gt 0 ]; then avg_ttft=$((sum_ttft / got_ttft)); else avg_ttft=""; fi
    avg_total=$((sum_total / rep))

    # Antwort kürzen (eine Zeile, max ~90 Zeichen) für die Tabelle
    short="$(printf '%s' "$last_text" | tr '\n' ' ' | python3 -c 'import sys; s=sys.stdin.read().strip(); print((s[:90]+"…") if len(s)>90 else s)')"
    [ -z "${last_text// /}" ] && short="∅ (LEER${last_err:+ / $last_err})"

    ok "  → TTFT=${avg_ttft:-—}ms  Gesamt=${avg_total}ms  Tokens=${last_tokens}"
    echo "    Antwort: $last_text" | sed 's/^/  /'

    ROWS+=("$typ|$lang|${avg_ttft:-—}|${avg_total}|${last_tokens}|$short")
    # Volltext fürs MD/Log
    {
        echo "## $id $typ/$lang"
        echo "TTFT=${avg_ttft:-—}ms  Gesamt=${avg_total}ms  Tokens=${last_tokens}  ${last_err:+ERR=$last_err}"
        echo "Antwort: $last_text"
        echo
    } >>"$MD"
done

# ── Tabelle ──────────────────────────────────────────────────────────────────
echo
say "ERGEBNIS-TABELLE  (label=$LABEL, model=$MODEL)"
{
    echo
    printf '| %-9s | %-5s | %8s | %12s | %6s | %s\n' "Prompt-Typ" "Spr." "TTFT" "Gesamt" "Tokens" "Antwort (gekürzt)"
    printf '| %-9s | %-5s | %8s | %12s | %6s | %s\n' "---------" "----" "--------" "------------" "------" "-----------------"
    for r in "${ROWS[@]}"; do
        IFS='|' read -r typ lang ttft total toks ans <<<"$r"
        printf '| %-9s | %-5s | %6sms | %10sms | %6s | %s\n' "$typ" "$lang" "$ttft" "$total" "$toks" "$ans"
    done
    echo
} | tee -a "$LOG"

ok "Log : ${LOG#$REPO_ROOT/}"
ok "MD  : ${MD#$REPO_ROOT/}"
exit 0
