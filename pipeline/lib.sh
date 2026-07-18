#!/usr/bin/env bash
# pipeline/lib.sh — shared helpers für die Hoshi-0.8-Dev-Pipeline.
#
# Idee aus 0.5 (tools/verify-kotlin.sh + hoshi-deploy.sh) übernommen:
#   - tty-erkennende Farben, kompakte say_*-Logger, REPO-root-Find.
# VERBESSERUNG ggü. 0.5: ein gemeinsames lib.sh statt copy-paste je Skript,
# und ein .pipeline/-Logverzeichnis (gitignored) statt docs/incidents.
#
# Wird von jedem pipeline/*.sh gesourct:  source "$(dirname "$0")/lib.sh"

set -euo pipefail

# ── REPO-root-Find ───────────────────────────────────────────────────────────
# lib.sh liegt in <repo>/pipeline/ ⇒ Repo-Root ist eine Ebene höher.
PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$PIPELINE_DIR/.." && pwd)"

# ── Log-Verzeichnis (gitignored) ─────────────────────────────────────────────
PIPELINE_LOG_DIR="$REPO_ROOT/.pipeline"

# ── Farben (nur am tty) ──────────────────────────────────────────────────────
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_DIM=$'\033[2m'; C_BLUE=$'\033[34m'
else
    C_RESET=""; C_BOLD=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_BLUE=""
fi

# ── Zeitstempel ──────────────────────────────────────────────────────────────
timestamp() { date +%Y%m%d-%H%M%S; }
iso_now()   { date -Iseconds 2>/dev/null || date "+%Y-%m-%dT%H:%M:%S%z"; }

# ── Logger ───────────────────────────────────────────────────────────────────
say()      { echo "${C_BOLD}▶${C_RESET} $*"; }
log()      { echo "  ${C_DIM}$*${C_RESET}"; }
ok()       { echo "  ${C_GREEN}✓${C_RESET} $*"; }
warn()     { echo "  ${C_YELLOW}!${C_RESET} $*"; }
fail()     { echo "  ${C_RED}✗${C_RESET} $*" >&2; }

# ── Gradle-Wrapper-Pfad ──────────────────────────────────────────────────────
GRADLEW="$REPO_ROOT/gradlew"

ensure_log_dir() { mkdir -p "$PIPELINE_LOG_DIR"; }
