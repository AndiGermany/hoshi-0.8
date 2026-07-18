#!/usr/bin/env bash
# pipeline/verify.sh — der GRÜNE GATE für Hoshi 0.8.
#
# 0.5 verifizierte nur COMPILE (tools/verify-kotlin.sh: :agent:compileKotlin).
# VERBESSERUNG: dieser Gate verifiziert LIVE:
#   (a) ./gradlew build  — ALLE Module (core-domain/adapters-brain/
#       capability-kernel/web-inbound), inkl. ArchUnit + Unit-Tests.
#   (b) Brain-Live-Smoke — ./gradlew :adapters-brain:run streamt echte Tokens
#       vom laufenden e4b-Brain (:8041). Wir parsen Satz + Latenz aus dem
#       [smoke]-Output und sind erst grün, wenn der Satz NICHT leer ist.
#
# grün≠lebt: erst wenn (a) UND (b) grün sind, exit 0.
#
# Log:  <repo>/.pipeline/verify-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/verify-$TS.log"

cd "$REPO_ROOT"

{
    echo "# Hoshi 0.8 — verify (grüner Gate)"
    echo "# Datum:  $(iso_now)"
    echo "# Repo:   $REPO_ROOT"
    echo "# ────────────────────────────────────────────────────────────"
    echo
} > "$LOG"

# ── (a) Build: alle Module + Tests ───────────────────────────────────────────
say "(a) ./gradlew build — alle Module + ArchUnit/Unit-Tests"
log "Log: ${LOG#$REPO_ROOT/}"
if "$GRADLEW" --console=plain build 2>&1 | tee -a "$LOG"; then
    ok "Build grün (alle Module + Tests)"
else
    fail "BUILD FAILED — siehe ${LOG#$REPO_ROOT/}"
    echo
    tail -25 "$LOG"
    exit 1
fi
echo

# ── (b) Brain-Live-Smoke gegen echten e4b (:8041) ────────────────────────────
say "(b) Brain-Live-Smoke — ./gradlew :adapters-brain:run (echter e4b :8041)"
SMOKE_OUT="$PIPELINE_LOG_DIR/verify-$TS-smoke.out"

set +e
"$GRADLEW" -q :adapters-brain:run 2>&1 | tee "$SMOKE_OUT" | tee -a "$LOG"
SMOKE_RC=${PIPESTATUS[0]}
set -e

# Satz + Latenz aus dem [smoke]-Output parsen.
SATZ="$(grep -E '^\[smoke\] Satz' "$SMOKE_OUT" | head -1 | sed -E 's/^\[smoke\] Satz[[:space:]]*:[[:space:]]*//')"
LATENZ="$(grep -E '^\[smoke\] Latenz' "$SMOKE_OUT" | head -1 | sed -E 's/^\[smoke\] Latenz[[:space:]]*:[[:space:]]*//')"

echo
if [ "$SMOKE_RC" -ne 0 ]; then
    fail "Brain-Smoke FAILED (exit $SMOKE_RC) — Brain (:8041) nicht erreichbar oder leer?"
    tail -15 "$SMOKE_OUT"
    exit 1
fi
if [ -z "${SATZ// /}" ]; then
    fail "Brain-Smoke lieferte LEEREN Satz — kein lebender Brain-Output"
    exit 1
fi

ok "Brain lebt — Satz : $SATZ"
ok "             Latenz: ${LATENZ:-?}"
echo
say "${C_GREEN}verify GRÜN${C_RESET} — Build + LIVE-Brain beide grün."
log "Voller Log: ${LOG#$REPO_ROOT/}"
exit 0
