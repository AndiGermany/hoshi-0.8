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

# ── Backup/Restore shared constants + helpers (docs/tsugi/BACKUP-RESTORE-CONTRACT.md) ──
# Added here so pipeline/backup.sh (wave 2a) and pipeline/restore.sh (wave 2b) read the
# SAME format ceiling and path-resolution rules from ONE place — a restore that resolves
# a store's path differently than the backup that produced it would silently write to the
# wrong file. backup.sh already redefines each of these locally with identical values
# (its own source-of-truth predates this file); those local redefinitions simply shadow
# the ones below, so adding them here does not change backup.sh's behaviour at all.
CONTRACT="hoshi-backup"
CONTRACT_VERSION=1
SCHEMA_CATALOG_VERSION=1
ARCHIVE_ROOT="hoshi-backup-v1"
# Raise ONLY together with a re-read of the store formats — see backup.sh's own comment.
FORMAT_CEILING="0.8.5"
PROD_DIR="/var/lib/hoshi-0.8"

file_bytes() { wc -c <"$1" | tr -d ' '; }

# shasum is guaranteed on macOS, sha256sum on most Linux; python3 is the floor.
sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
    fi
}
sha256_of_stdin() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 - | cut -d' ' -f1
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum - | cut -d' ' -f1
    else
        python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
    fi
}

validate_json() { python3 -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$1" 2>/dev/null; }
validate_jsonl() {
    python3 - "$1" <<'PY' 2>/dev/null
import json, sys
raw = open(sys.argv[1], "rb").read()
if not raw:
    raise SystemExit(0)                       # an empty note file is legal
if not raw.endswith(b"\n"):
    raise SystemExit(3)                       # partial last line = invalid entry
for line in raw.decode("utf-8").splitlines():
    if line.strip():
        json.loads(line)
PY
}

# Indirect env lookup (bash 3.2 compatible — macOS ships 3.2, no assoc arrays).
env_value() { eval "printf '%s' \"\${$1:-}\""; }

# Effective store paths — EXACTLY the backend's own precedence (PipelineConfig.kt):
#   home_path : configured ▷ ~/.hoshi/<file>                      (settings, memory, notes)
#   data_path : configured ▷ /var/lib/hoshi-0.8/<file> if writable ▷ ~/.hoshi/<file>
#   sub_path  : same /var/lib gate as data_path, but with an own subdir (lookups/diary)
home_path() { # env_key file
    local v; v="$(env_value "$1")"
    if [ -n "$v" ]; then printf '%s' "$v"; else printf '%s' "$HOME/.hoshi/$2"; fi
}
data_path() { # env_key file
    local v; v="$(env_value "$1")"
    if [ -n "$v" ]; then printf '%s' "$v"
    elif [ -w "$PROD_DIR" ]; then printf '%s' "$PROD_DIR/$2"
    else printf '%s' "$HOME/.hoshi/$2"; fi
}
sub_path() { # env_key subdir file
    local v; v="$(env_value "$1")"
    if [ -n "$v" ]; then printf '%s' "$v"
    elif [ -w "$PROD_DIR" ]; then printf '%s' "$PROD_DIR/$2/$3"
    else printf '%s' "$HOME/.hoshi/$2/$3"; fi
}
