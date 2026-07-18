#!/usr/bin/env bash
# run-overnight.sh — Hoshi-Stimme-LoRA v0: QLoRA-Training auf dem 16GB-Mac (nachts).
#
# ABLAUF (Andi-Gate 2026-07-01 gezogen: "Lokal ueber Nacht"):
#   1. Brain stoppen (16-GB-Wand: Training braucht den RAM; ct-106-Turns fallen
#      solange auf die ehrliche Brain-Admission-Absage — akzeptierter Nacht-Modus).
#   2. Phase-0-Lade-Smoke: laedt gemma-4-e2b in mlx-lm (beweist Trainierbarkeit der
#      MatFormer-Architektur, bevor Stunden investiert werden).
#   3. QLoRA (batch 1, 8 Layer, grad-checkpoint, 800 iters) -> adapters/v0.
#   4. WATCHDOG: max 4h (macOS hat kein `timeout`) — dann kill.
#   5. IMMER (trap EXIT): Brain-Restart + Health-Poll + ECHTER /v1/chat-Roundtrip
#      (Zombie-Lehre: health luegt — nur eine echte Generation beweist Leben).
#
# EVAL danach (naechste Session / morgens):
#   $VENV/bin/python -m mlx_lm generate --model $MODEL --adapter-path adapters/v0 \
#     --prompt "..." ; dann verifyOffline + Golden-Set + Andi-Hoerprobe A/B.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV=/Users/andi/IdeaProjects/Hoshi_0.5/hoshi-llm-optiq/.venv
MODEL="${LORA_MODEL:-mlx-community/gemma-4-e2b-it-4bit}"
BRAIN_RUN=/Users/andi/IdeaProjects/Hoshi_0.5/tools/hoshi-e4b-run.sh
DATA="$ROOT/data"; OUT="$ROOT/adapters/v0"; LOGDIR="$ROOT/logs"
LOG="$LOGDIR/train-$(date +%Y%m%d-%H%M).log"
MAX_SECS=$((4 * 3600))

mkdir -p "$OUT" "$LOGDIR"
[ -f "$DATA/train.jsonl" ] && [ -f "$DATA/valid.jsonl" ] || {
    echo "FEHLT: $DATA/train.jsonl|valid.jsonl — erst tools/validate_merge.py"; exit 1; }

restart_brain() {
    echo ">> [immer] Brain-Restart ($MODEL)"
    pkill -f server_e4b.py 2>/dev/null || true; sleep 2
    nohup env E4B_MODEL="$MODEL" bash "$BRAIN_RUN" >"$LOGDIR/brain-restart.log" 2>&1 &
    for _ in $(seq 1 60); do
        curl -s --max-time 2 http://127.0.0.1:8041/health 2>/dev/null | grep -q '"loaded":true' && break
        sleep 2
    done
    # grün≠lebt: echter Roundtrip statt nur health (NIE | head auf einen Stream!)
    curl -s --max-time 90 -X POST http://127.0.0.1:8041/v1/chat \
        -H 'Content-Type: application/json' \
        -d '{"messages":[{"role":"user","content":"Sag kurz hallo."}],"max_tokens":24,"stream":false}' \
        -o "$LOGDIR/brain-smoke.json" || true
    echo ">> Brain-Smoke: $(head -c 160 "$LOGDIR/brain-smoke.json" 2>/dev/null || echo LEER) (leer => bin/hoshi heal)"
}
trap restart_brain EXIT

echo ">> Brain stoppen (RAM freigeben)"
pkill -f server_e4b.py 2>/dev/null || true
sleep 3

echo ">> Phase 0: Lade-Smoke (gemma-4-e2b in mlx-lm ladbar?)"
if ! "$VENV/bin/python" -c "
from mlx_lm import load
m, t = load('$MODEL')
print('SMOKE OK: Modell laedt in mlx-lm', type(m).__name__)
" 2>&1 | tee "$LOGDIR/smoke.log"; then
    echo "SMOKE ROT — gemma-4-e2b laedt nicht trainierbar. Fallback-Basen: gemma-3-4b-it / Qwen3-4B (siehe Research)."
    exit 2
fi

echo ">> QLoRA-Training -> $LOG (Watchdog ${MAX_SECS}s)"
"$VENV/bin/python" -m mlx_lm lora \
    --model "$MODEL" --train --data "$DATA" \
    --batch-size 1 --num-layers 8 --iters 800 \
    --grad-checkpoint --save-every 200 --steps-per-report 50 \
    --adapter-path "$OUT" >"$LOG" 2>&1 &
TRAIN_PID=$!
SECS=0
while kill -0 "$TRAIN_PID" 2>/dev/null; do
    sleep 30; SECS=$((SECS + 30))
    [ "$SECS" -ge "$MAX_SECS" ] && { echo ">> WATCHDOG: ${MAX_SECS}s erreicht — kill"; kill "$TRAIN_PID" 2>/dev/null; break; }
done
wait "$TRAIN_PID" 2>/dev/null; RC=$?
echo ">> Training rc=$RC — letzte Log-Zeilen:"; tail -6 "$LOG"
echo ">> Adapter: $(ls -la "$OUT" 2>/dev/null | tail -3)"
# trap EXIT -> Brain-Restart + Smoke
