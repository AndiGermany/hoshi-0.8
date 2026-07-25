#!/usr/bin/env bash
# pipeline/verify.sh — der GRÜNE GATE für Hoshi 0.8.
#
# 0.5 verifizierte nur COMPILE (tools/verify-kotlin.sh: :agent:compileKotlin).
# VERBESSERUNG: dieser Gate verifiziert LIVE:
#   (a) pipeline/test-render-unit.sh — die Unit-Render-Naht von deploy.sh
#       (TTS-Engine-Auflösung + Platzhalter-Riegel). Läuft ZUERST, weil offline,
#       in Sekunden und ohne jede Abhängigkeit (kein Netz/ssh/Brain) — und weil
#       render_unit der Prod-Entscheider ist, der bis 2026-07-25 ungeprüft war.
#   (b) pipeline/test-first-run-tts.sh — isolierter Fresh-HOME-Beweis:
#       echter lokaler WAV-Roundtrip ODER sofortiger, exakter Bootstrap-Hinweis.
#   (c) ./gradlew build  — ALLE Module (core-domain/adapters-brain/
#       capability-kernel/web-inbound), inkl. ArchUnit + Unit-Tests.
#   (d) Brain-Live-Smoke — ./gradlew :adapters-brain:run streamt echte Tokens
#       vom laufenden e4b-Brain (:8041). Wir parsen Satz + Latenz aus dem
#       [smoke]-Output und sind erst grün, wenn der Satz NICHT leer ist.
#
# grün≠lebt: erst wenn (a), (b), (c) UND (d) grün sind, exit 0.
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

# ── (a) Deploy-Render-Naht (offline, kein Netz/ssh/Prod) ─────────────────────
say "(a) pipeline/test-render-unit.sh — TTS-Engine-Auflösung + Platzhalter-Riegel"
if bash "$PIPELINE_DIR/test-render-unit.sh" 2>&1 | tee -a "$LOG"; then
    ok "Render-Naht grün (deploy.sh rendert die systemd-Unit korrekt)"
else
    fail "RENDER-NAHT ROT — siehe ${LOG#$REPO_ROOT/}"
    exit 1
fi
echo

# ── (b) TTS-Fresh-HOME: echter WAV-Pfad oder exakte Setup-Wahrheit ───────────
say "(b) pipeline/test-first-run-tts.sh — Fresh HOME, ohne Key/Config"
if bash "$PIPELINE_DIR/test-first-run-tts.sh" 2>&1 | tee -a "$LOG"; then
    ok "TTS-Erststart ehrlich (lokales WAV oder exakter Bootstrap-Hinweis)"
else
    fail "TTS-FIRST-RUN ROT — siehe ${LOG#$REPO_ROOT/}"
    exit 1
fi
echo

# ── (c) Build: alle Module + Tests ───────────────────────────────────────────
say "(c) ./gradlew build — alle Module + ArchUnit/Unit-Tests"
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

# ── (d) Brain-Live-Smoke gegen echten e4b (:8041) ────────────────────────────
say "(d) Brain-Live-Smoke — ./gradlew :adapters-brain:run (echter e4b :8041)"
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
say "${C_GREEN}verify GRÜN${C_RESET} — Render-Naht + TTS-First-Run + Build + LIVE-Brain alle grün."
log "Voller Log: ${LOG#$REPO_ROOT/}"
exit 0
