#!/usr/bin/env bash
# deploy-fe.sh -- Hoshi 0.8: FE production-build -> ct-106:/opt/hoshi-0.8/web
#
# Port von Hoshi 0.5 `tools/hoshi-deploy-fe.sh`. Andi oeffnet die UI wieder ueber
# das Backend (same-origin) statt `npm run dev` lokal: der Mac baut das Vite-Bundle
# mit RELATIVER API-Base (VITE_API_BASE='' -> Calls gehen auf /api/... same-origin,
# KEIN absolutes ct-106 im Bundle, kein CORS noetig), schickt dist/ nach ct-106.
#
# VORAUSSETZUNG BACKEND: das Backend muss das FE-Serving scharf haben, sonst liefert
# es die Assets nicht:
#   HOSHI_WEB_SERVE_FRONTEND=true            (Spring: hoshi.web.serve-frontend=true)
#   HOSHI_WEB_STATIC_DIR=/opt/hoshi-0.8/web  (optional; das ist der Default)
# systemd-Drop-in / /etc/hoshi-0.8.env auf ct-106 entsprechend setzen + Backend neu starten.
#
# SSH-GATE (Agent/Andi-Guardrail): dieses Script fasst ct-106 ueber ssh/scp an. Ein
# Agent fuehrt es NICHT selbst aus -- der Orchestrator/Andi startet es nach dem
# SSH-Gate (so wie Deploy generell Andi-gated ist).
#
# Usage:
#   pipeline/deploy-fe.sh             # build (relative API-Base) + deploy
#   pipeline/deploy-fe.sh --no-build  # nur deploy von frontend/dist/
#
# Overrides (ENV):
#   HOSHI_DEPLOY_TARGET   SSH-Ziel               (default ct-106)
#   HOSHI_WEB_DIR         Remote-Web-Verzeichnis (default /opt/hoshi-0.8/web)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
FE_DIR="$REPO_ROOT/frontend"
SSH_TARGET="${HOSHI_DEPLOY_TARGET:-ct-106}"
REMOTE_WEB="${HOSHI_WEB_DIR:-/opt/hoshi-0.8/web}"

SKIP_BUILD=0
[ "${1:-}" = "--no-build" ] && SKIP_BUILD=1

if [ "$SKIP_BUILD" = "0" ]; then
    echo ">> FE Build (npm run build, VITE_API_BASE='' -> same-origin/relativ)"
    # Leere API-Base => config.ts haelt sie als '' (nicht nullish) => API_BASE=''
    # => alle Calls sind relativ (/api/...) und gehen same-origin ans Backend.
    ( cd "$FE_DIR" && VITE_API_BASE='' npm run build )
fi

[ -d "$FE_DIR/dist" ] || { echo "ERR dist/ fehlt -- erst bauen (ohne --no-build)" >&2; exit 1; }

echo ">> Deploy dist/ -> $SSH_TARGET:$REMOTE_WEB"
ssh "$SSH_TARGET" "mkdir -p $REMOTE_WEB && rm -rf $REMOTE_WEB/*"
# COPYFILE_DISABLE verhindert macOS-AppleDouble (._-Dateien) im tar.
( cd "$FE_DIR/dist" && COPYFILE_DISABLE=1 tar czf /tmp/hoshi-0.8-fe-dist.tgz . )
scp -q /tmp/hoshi-0.8-fe-dist.tgz "$SSH_TARGET:/tmp/"
ssh "$SSH_TARGET" "cd $REMOTE_WEB && tar xzf /tmp/hoshi-0.8-fe-dist.tgz && rm -f ._* assets/._* /tmp/hoshi-0.8-fe-dist.tgz && chown -R hoshi:hoshi $REMOTE_WEB"

echo "OK FE deployed nach $SSH_TARGET:$REMOTE_WEB"
echo "   Backend braucht HOSHI_WEB_SERVE_FRONTEND=true (sonst werden die Assets nicht serviert)."
echo "   Test: curl -s http://ct-106:8082/ | grep -i title   (Scheme/Port = der live Backend-Rand)"
