#!/usr/bin/env bash
# pipeline/services.sh — der M3-Beweis: der EINE ehrliche Sidecar-Supervisor.
#
# 0.5 hatte 5 copy-paste-Bash-Watchdogs + ein `/health`, das log ("grün != lebt":
# 6-Tage-Zombie-Download, tote Knowledge-Bridge, 3-Tage-hängende Bridge rutschten
# still durch). 0.8 hat EINEN Supervisor (core `de.hoshi.core.supervision`), der
# jede Naht der Registry live read-only probt und ehrlich OK / DEGRADED / DOWN sagt.
#
# Dieses Skript läuft den Supervisor gegen die ECHTE Live-Infra:
#   ./gradlew :adapters-supervision:run  →  HttpSidecarProbe gegen :8041/:9001/:9002/
#   :8035/:8042, RAM-Snapshot aus vm_stat, RAM-Arbiter-Urteil je Sidecar.
#
# READ-ONLY: KEIN echter Restart (RestartPort ist GATED/dry-run — Andi-Gate). Geplante
# Restarts werden nur als Plan gezeigt.
#
# Ehrlicher Exit-Code (vom Supervisor durchgereicht):
#   0  alle Sidecars OK
#   2  mind. ein DEGRADED (erreichbar, aber nicht bereit — z.B. loading)
#   3  mind. ein DOWN (unerreichbar)
#
# Log:  <repo>/.pipeline/services-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/services-$TS.log"

cd "$REPO_ROOT"

say "Sidecar-Supervisor — live read-only inspect (echte Infra, Restart GATED)"
log "Log: ${LOG#$REPO_ROOT/}"
echo

{
    echo "# Hoshi 0.8 — services (Sidecar-Supervision, M3)"
    echo "# Datum:  $(iso_now)"
    echo "# Repo:   $REPO_ROOT"
    echo "# ────────────────────────────────────────────────────────────"
    echo
} > "$LOG"

set +e
"$GRADLEW" -q :adapters-supervision:run 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e

echo
case "$RC" in
    0) ok   "Supervisor: ALL-OK — alle Sidecars erreichbar UND bereit." ;;
    2) warn "Supervisor: DEGRADED — mind. eine Naht erreichbar, aber nicht bereit (ehrlich, kein Fake-grün)." ;;
    3) fail "Supervisor: DOWN — mind. eine Naht unerreichbar." ;;
    *) fail "Supervisor: unerwarteter Exit $RC" ;;
esac
log "Voller Log: ${LOG#$REPO_ROOT/}"
exit "$RC"
