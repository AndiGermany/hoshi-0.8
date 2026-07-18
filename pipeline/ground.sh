#!/usr/bin/env bash
# pipeline/ground.sh — der M4-Step-1-BEWEIS: echtes Wiki-Grounding über die Bridge.
#
# grün≠lebt: ruft den NEUEN Fts5GroundingAdapter (:adapters-knowledge) gegen die
# ECHTE Knowledge-Bridge (:8035, articles.db, 4,98M dt. Wiki-Artikel) mit einer
# Wissensfrage auf und gibt den abgerufenen Grounding-Block WÖRTLICH aus. Beweis:
# echte deutsche Wiki-Passagen kommen an. KEIN voller Turn — nur Adapter→Bridge.
#
# Exit 0 nur, wenn der Grounding-Block NICHT leer ist.
# Bridge-URL via HOSHI_KNOWLEDGE_BRIDGE_URL (Default http://localhost:8035).
# Query als Argument (Default „Wer war Konrad Adenauer?").
#
# Vom Dispatcher: bin/hoshi ground ["Frage…"]
# Log: <repo>/.pipeline/ground-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/ground-$TS.log"
OUT="$PIPELINE_LOG_DIR/ground-$TS.out"

BRIDGE_URL="${HOSHI_KNOWLEDGE_BRIDGE_URL:-http://localhost:8035}"
QUERY="${*:-Wer war Konrad Adenauer?}"

cd "$REPO_ROOT"

# ── Bridge-Vorprüfung (ehrlich: lebt der Sidecar :8035?) ─────────────────────
if curl -s -m 5 "$BRIDGE_URL/search?q=Berlin&limit=1" 2>/dev/null | grep -q '"hits"'; then
    ok "Knowledge-Bridge ($BRIDGE_URL) lebt — echtes Grounding möglich"
else
    fail "Knowledge-Bridge ($BRIDGE_URL) NICHT erreichbar — kein Live-Grounding-Beweis"
    exit 1
fi
echo

# ── Adapter → Bridge: echte Wissensfrage → Grounding-Block ───────────────────
say "Live-Grounding: Fts5GroundingAdapter → Bridge  (Frage: \"$QUERY\")"
log "Log: ${LOG#$REPO_ROOT/}"

set +e
HOSHI_KNOWLEDGE_BRIDGE_URL="$BRIDGE_URL" \
    "$GRADLEW" -q :adapters-knowledge:run --args="$QUERY" 2>&1 | tee "$OUT" | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e

echo
if [ "$RC" -ne 0 ]; then
    fail "Grounding-Smoke FAILED (exit $RC) — Bridge aus oder leerer Block?"
    tail -20 "$OUT"
    exit 1
fi

# Beweis: zwischen den Markern muss echter Block-Text stehen (kein "(leer)").
if grep -q '^(leer)$' "$OUT"; then
    fail "Grounding-Block war LEER — kein echter Wiki-Treffer"
    exit 1
fi

ok "Grounding-Block kam an (echte Wiki-Passage)"
echo
say "${C_GREEN}ground GRÜN${C_RESET} — der Fts5GroundingAdapter holt echte Wiki-Passagen aus der Bridge."
exit 0
