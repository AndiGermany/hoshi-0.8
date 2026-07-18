#!/usr/bin/env bash
# generate-ab.sh — LoRA-v0 Blind-A/B-Eval: Base vs. Adapter auf 40 eingefrorenen Prompts.
#
# ABLAUF (D8-Rezept; Lars/Runa 2026-07-02):
#   1. Guards: kein laufendes Training, Adapter+VENV+jq vorhanden.
#   2. Brain stoppen (16-GB-Wand, wie run-overnight.sh; ct-106 faellt solange
#      auf die ehrliche Brain-Admission-Absage — akzeptierter Eval-Modus).
#   3. Je Prompt ZWEI Generationen via mlx_lm-CLI (--temp 0.0 = greedy =
#      deterministisch, Mess-Aussagekraft): einmal OHNE, einmal MIT
#      --adapter-path adapters/v0. --system-prompt existiert in mlx_lm 0.31.2
#      (per --help verifiziert), --verbose False druckt NUR die Antwort.
#   4. System-Prompt = echter Trainings-Body. ACHTUNG Trainingsdaten-Trick
#      korrigiert: Zeile 1 von train.jsonl traegt einen beispiel-spezifischen
#      HINTERGRUND-Faktenblock (Gluehlampe/Edison) — der wuerde das Abstain-Eval
#      kontaminieren. Deshalb: erster system-Content OHNE "HINTERGRUND" = purer
#      STANDARD-Body. EN-Prompts kriegen den EN-Body (damit wurden sie
#      trainiert); EVAL_EN_SYSTEM=de erzwingt den DE-Body fuer alle.
#   5. Ausgabe seiten-randomisiert nach results/ab-<ts>.jsonl:
#      {"id","prompt","a","b","adapter_ist"} — a/b zufaellig Base/Adapter,
#      Aufloesung NUR im Feld adapter_ist (fuer Andis Blind-Wahl,
#      siehe ANDI-BLIND-AB.md — Rohdatei nicht anstarren!).
#   6. IMMER (trap EXIT): Brain-Restart + Health-Poll + ECHTER /v1/chat-Roundtrip
#      (Zombie-Lehre: health luegt — nur eine echte Generation beweist Leben).
#
# NICHT parallel zum Training starten! Laufzeit grob 30-60 min (80 CLI-Laeufe,
# jeder laedt das Modell frisch — dafuer exakt der getestete Codepfad).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # .../training/lora-v0/eval
LORA="$(dirname "$ROOT")"                              # .../training/lora-v0
VENV=/Users/andi/IdeaProjects/Hoshi_0.5/hoshi-llm-optiq/.venv
MODEL="${LORA_MODEL:-mlx-community/gemma-4-e2b-it-4bit}"
BRAIN_RUN=/Users/andi/IdeaProjects/Hoshi_0.5/tools/hoshi-e4b-run.sh
ADAPTER="$LORA/adapters/v0"
PROMPTS="$ROOT/prompts.jsonl"
LOGDIR="$LORA/logs"
RESULTS="$ROOT/results"
TS="$(date +%Y%m%d-%H%M)"
OUT="$RESULTS/ab-$TS.jsonl"
GENLOG="$LOGDIR/eval-ab-$TS.log"
EN_SYS="${EVAL_EN_SYSTEM:-en}"   # en = EN-Prompts mit EN-Trainings-Body | de = DE-Body fuer alle

mkdir -p "$RESULTS" "$LOGDIR"

# ---- Guards (VOR dem Brain-Stopp — hier wird nichts restartet) ---------------
command -v jq >/dev/null 2>&1 || { echo "FEHLT: jq"; exit 1; }
[ -x "$VENV/bin/python" ] || { echo "FEHLT: VENV $VENV"; exit 1; }
[ -f "$PROMPTS" ] || { echo "FEHLT: $PROMPTS"; exit 1; }
[ -f "$LORA/data/train.jsonl" ] || { echo "FEHLT: $LORA/data/train.jsonl (System-Prompt-Quelle)"; exit 1; }
[ -f "$ADAPTER/adapters.safetensors" ] || { echo "FEHLT: Adapter $ADAPTER — erst run-overnight.sh"; exit 1; }
if pgrep -f "mlx_lm lora" >/dev/null 2>&1 || pgrep -f "run-overnight.sh" >/dev/null 2>&1; then
    echo "ABBRUCH: Training laeuft noch (mlx_lm lora / run-overnight.sh) — Eval spaeter."; exit 1
fi

# ---- Brain-Restart-Block (Muster: run-overnight.sh) ---------------------------
TMP="$(mktemp -d)"
restart_brain() {
    rm -rf "$TMP"
    echo ">> [immer] Brain-Restart ($MODEL)"
    pkill -f server_e4b.py 2>/dev/null || true; sleep 2
    nohup env E4B_MODEL="$MODEL" bash "$BRAIN_RUN" >"$LOGDIR/brain-restart.log" 2>&1 &
    for _ in $(seq 1 60); do
        curl -s --max-time 2 http://127.0.0.1:8041/health 2>/dev/null | grep -q '"loaded":true' && break
        sleep 2
    done
    # gruen != lebt: echter Roundtrip statt nur health (NIE | head auf einen Stream!)
    curl -s --max-time 90 -X POST http://127.0.0.1:8041/v1/chat \
        -H 'Content-Type: application/json' \
        -d '{"messages":[{"role":"user","content":"Sag kurz hallo."}],"max_tokens":24,"stream":false}' \
        -o "$LOGDIR/brain-smoke.json" || true
    echo ">> Brain-Smoke: $(head -c 160 "$LOGDIR/brain-smoke.json" 2>/dev/null || echo LEER) (leer => bin/hoshi heal)"
}
trap restart_brain EXIT

echo ">> Brain stoppen (RAM freigeben fuer 80 Generationen)"
pkill -f server_e4b.py 2>/dev/null || true
sleep 3

# ---- System-Prompts aus den Trainingsdaten extrahieren ------------------------
"$VENV/bin/python" - "$LORA/data/train.jsonl" "$TMP/sys_de.txt" "$TMP/sys_en.txt" <<'PY'
import json, sys
path, out_de, out_en = sys.argv[1], sys.argv[2], sys.argv[3]
de = en = None
for line in open(path):
    line = line.strip()
    if not line:
        continue
    msgs = json.loads(line)["messages"]
    s = next((m["content"] for m in msgs if m["role"] == "system"), None)
    if not s or "HINTERGRUND" in s:   # Kontext-Injektions-Zeilen ueberspringen
        continue
    if de is None and s.startswith("Du bist Hoshi"):
        de = s
    if en is None and s.startswith("You are Hoshi"):
        en = s
    if de and en:
        break
assert de, "STANDARD-DE-Body nicht gefunden"
open(out_de, "w").write(de)
open(out_en, "w").write(en or de)
PY
[ -s "$TMP/sys_de.txt" ] || { echo "ABBRUCH: System-Prompt-Extraktion fehlgeschlagen"; exit 1; }
echo ">> System-Prompt DE: $(head -c 50 "$TMP/sys_de.txt")… ($(wc -c <"$TMP/sys_de.txt" | tr -d ' ') Zeichen), EN-Modus: $EN_SYS"

# gen <sysfile> <text> [extra-args…] -> stdout: nur die Antwort
# MESS-FAIRNESS (Lars 2026-07-02): enable_thinking=false fuer BEIDE Seiten —
# exakt der prod-Mechanismus aus server_e4b.py (build_prompt reicht
# enable_thinking=False an apply_chat_template; Server laeuft thinking:false).
# Ohne das leakt die BASIS <|channel>thought/"Thinking Process" (im Lauf
# 20260702-0133: 40/40 Basis-Antworten), der Adapter nie -> unfairer Vergleich.
gen() {
    local sysfile="$1" text="$2"; shift 2
    "$VENV/bin/python" -m mlx_lm generate \
        --model "$MODEL" --max-tokens 120 --temp 0.0 --verbose False \
        --chat-template-config '{"enable_thinking": false}' \
        --system-prompt "$(cat "$sysfile")" --prompt "$text" \
        "$@" </dev/null 2>>"$GENLOG"
}

# ---- Hauptschleife: je Prompt Base + Adapter, dann Seiten-Muenzwurf ------------
TOTAL="$(grep -c . "$PROMPTS")"; N=0; FAILS=0
: >"$OUT"
echo ">> $TOTAL Prompts, je 2 Generationen -> $OUT"
while IFS= read -r line <&3; do
    [ -n "$line" ] || continue
    N=$((N + 1))
    id="$(jq -r '.id' <<<"$line")"
    text="$(jq -r '.text' <<<"$line")"
    kat="$(jq -r '.kategorie' <<<"$line")"
    sysfile="$TMP/sys_de.txt"
    [ "$kat" = "en" ] && [ "$EN_SYS" = "en" ] && sysfile="$TMP/sys_en.txt"
    echo ">> [$N/$TOTAL] $id ($kat)"
    base="$(gen "$sysfile" "$text")" || true
    adap="$(gen "$sysfile" "$text" --adapter-path "$ADAPTER")" || true
    if [ -z "$base" ] || [ -z "$adap" ]; then
        echo "   WARNUNG: leere Antwort bei $id (Details: $GENLOG)"; FAILS=$((FAILS + 1))
    fi
    # Muenzwurf: wo landet der Adapter? (Blindheit — Aufloesung nur in adapter_ist)
    if [ $((RANDOM % 2)) -eq 0 ]; then a="$base"; b="$adap"; adapter_ist="b"
    else                              a="$adap"; b="$base"; adapter_ist="a"; fi
    jq -cn --arg id "$id" --arg prompt "$text" --arg a "$a" --arg b "$b" \
        --arg adapter_ist "$adapter_ist" \
        '{id:$id, prompt:$prompt, a:$a, b:$b, adapter_ist:$adapter_ist}' >>"$OUT"
done 3<"$PROMPTS"

echo ">> FERTIG: $(grep -c . "$OUT") Zeilen in $OUT (leere Antworten: $FAILS)"
echo ">> Blind lesen (adapter_ist bleibt versteckt):"
echo "   jq -r '\"### \\(.id)\\nPROMPT: \\(.prompt)\\n\\n  [a] \\(.a)\\n\\n  [b] \\(.b)\\n\"' $OUT | less"
echo ">> Anleitung + Auswertung: $ROOT/ANDI-BLIND-AB.md"
# trap EXIT -> Brain-Restart + Health-Poll + echter Roundtrip
