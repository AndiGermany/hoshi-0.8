#!/usr/bin/env bash
# pipeline/backup.sh — Tsugi wave 2a: the BACKUP half of `bin/hoshi backup`.
#
# Contract: docs/tsugi/BACKUP-RESTORE-CONTRACT.md + docs/tsugi/STORE-INVENTORY.md.
# Restore is deliberately NOT here (wave 2b) — this script never writes to a store,
# never starts/stops a service and never opens a source SQLite database read-write.
#
# What it does, in the contract's own order:
#   1. Read the source Hoshi version (gradle.properties, the ONE version truth) and
#      gate it against this tool's format ceiling. Unreadable or newer = BLOCKED
#      BEFORE anything is copied. A backup of a format we do not know is a lie.
#   2. Resolve the EFFECTIVE store paths with exactly the backend's precedence
#      (configured env ▷ /var/lib/hoshi-0.8 ▷ ~/.hoshi) — including the documented
#      asymmetry: settings/memory/note stores have NO /var/lib step, only the four
#      resolveDataStorePath stores (+ diary, + lookups) do.
#   3. Stage every present store, hash it, and only then write the manifest.
#   4. Pack, and VERIFY every SHA-256 back out of the archive. Success is claimed
#      only when the archive's own bytes match the manifest.
#
# Honesty rules that outrank convenience (Auflage 4 of the contract review):
#   - storeSchemaVersion is READ, never asserted. Today's stores carry no version
#     field, so the manifest says null + why. A fabricated "1" would make the
#     manifest lie fail-open the moment the format drifts.
#   - Absent optional stores are ABSENT, not an error (lists/timers are flag-off
#     by default — an empty machine is the normal case, not a broken one).
#   - A store that exists but could not be captured is SKIPPED with a reason and
#     costs the run its `consistent: true`. Half a copy is never silently shipped.
#
# Sensitivity (STORE-INVENTORY.md):
#   - speaker profiles: opt-in only (--with-speaker-profiles + second confirmation),
#     separate artifact directory, marked BIOMETRIC.
#   - raw speaker captures: NEVER, under any flag.
#   - turn diary: PRIVATE behaviour profile, opt-in only (--with-diary).
#   - secrets / models / knowledge DB / browser localStorage: excluded by contract,
#     and named in the plan so the gap is visible instead of forgotten.
#
# Exit codes (house convention 0/2 + the contract's "blocked" band):
#   0   plan/backup complete and verified
#   2   done, but degraded — something present was SKIPPED (consistent=false)
#  10   BLOCKED — nothing was written, or the artifact failed verification
#
# From the dispatcher: bin/hoshi backup [--dry-run] [--with-speaker-profiles]
#                                       [--with-diary] [--out <dir>]

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# AppleDouble sidecars (._foo) would end up as extra archive members that no
# manifest entry covers — the verify step would be right to call that a mismatch.
export COPYFILE_DISABLE=1

# ── Constants of this tool ──────────────────────────────────────────────────
CONTRACT="hoshi-backup"
CONTRACT_VERSION=1
SCHEMA_CATALOG_VERSION=1
BACKUP_TOOL_VERSION="1.0.0"
ARCHIVE_ROOT="hoshi-backup-v1"

# The format ceiling. Everything at or below this source version is a format this
# tool has actually seen; anything above is unknown and blocks. Raise this ONLY
# together with a re-read of the store formats — that is the whole point of it.
FORMAT_CEILING="0.8.5"

# ── Flags ───────────────────────────────────────────────────────────────────
DRY_RUN=false
WITH_SPEAKER=false
WITH_DIARY=false
OUT_DIR="${HOSHI_BACKUP_OUT_DIR:-$HOME/hoshi-backups}"

backup_usage() {
    cat <<EOF
  ${C_BOLD}bin/hoshi backup${C_RESET}   Haushalts-Stores sichern — READ-ONLY, mit Manifest und Verify.

  ${C_BOLD}--dry-run${C_RESET}                zeigt NUR den Plan: welcher Store, welcher Pfad,
                           wie groß, wie sensibel — plus die Manifest-Vorschau
                           und die bewusst NICHT enthaltenen Dinge. Schreibt nichts.
  ${C_BOLD}--with-speaker-profiles${C_RESET}  nimmt die Sprecherprofile (BIOMETRISCH) als separates
                           Artefakt mit. Verlangt eine zweite Bestätigung
                           (Tastatur, oder HOSHI_BACKUP_SPEAKER_CONFIRM=ja).
  ${C_BOLD}--with-diary${C_RESET}             nimmt das Turn-Diary mit (PRIVAT: Verhaltensprofil
                           pro Tag — chatId, Persona, Zielraum, Surprisal).
  ${C_BOLD}--out <dir>${C_RESET}              Zielverzeichnis für tar.gz + manifest.json
                           (Default: \$HOME/hoshi-backups)

  NIE enthalten: Secrets, Modelle, Wissens-DB, Roh-Audio der Sprecheraufnahmen,
  Home-Assistant-Räume und Browser-localStorage. Der Restore ist bewusst NICHT
  Teil dieses Skripts (Welle 2b).
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)               DRY_RUN=true ;;
        --with-speaker-profiles) WITH_SPEAKER=true ;;
        --with-diary)            WITH_DIARY=true ;;
        --out)
            [ $# -ge 2 ] || { fail "--out braucht ein Verzeichnis"; exit 10; }
            OUT_DIR="$2"; shift
            ;;
        --out=*)                 OUT_DIR="${1#--out=}" ;;
        -h|--help|help)          backup_usage; exit 0 ;;
        *)
            fail "unbekannte Option: $1"
            echo
            backup_usage
            exit 10
            ;;
    esac
    shift
done

# Private data by default: everything this script creates is owner-only.
umask 077

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-backup.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
PLAN="$WORK/plan.tsv"        # resolved stores, one line each
ENTRIES="$WORK/entries.tsv"  # manifest entries after staging/hashing
EXCLUDED="$WORK/excluded.tsv"
WARNINGS="$WORK/warnings.tsv"
: >"$PLAN"; : >"$ENTRIES"; : >"$EXCLUDED"; : >"$WARNINGS"

RC=0
CREATED_OUT_DIR=false
degraded() { [ "$RC" -lt 2 ] && RC=2 || true; }
# A blocked run leaves no trace of itself: if we created the target directory and it
# is still empty, it goes away again. "Nichts geschrieben" has to be literally true.
blocked() {
    fail "$1"
    if $CREATED_OUT_DIR; then rmdir "$OUT_DIR" 2>/dev/null || true; fi
    echo
    fail "Urteil: BLOCKIERT — kein gültiges Backup entstanden."
    exit 10
}

add_warning()  { printf '%s\t%s\n' "$1" "$2" >>"$WARNINGS"; }
add_excluded() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"$EXCLUDED"; }

file_bytes() { wc -c <"$1" | tr -d ' '; }

# shasum is guaranteed on macOS, sha256sum on most Linux; python3 is the floor
# (already a hard dependency of the pipeline, s. ha.sh).
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

# Indirect env lookup (bash 3.2 compatible — macOS ships 3.2, no assoc arrays).
env_value() { eval "printf '%s' \"\${$1:-}\""; }

say "Hoshi-Backup — Haushalts-Stores sichern (READ-ONLY, nichts wird verändert)"
$DRY_RUN && log "Modus  : DRY-RUN — nur Plan, kein Artefakt" || log "Modus  : ECHTER LAUF — erzeugt tar.gz + manifest.json"
echo

# ── (1) Version gate — fail-closed BEFORE anything is read or copied ─────────
# gradle.properties is the single version truth of this repo (s. bin/hoshi). The
# dispatcher may fall back to "dev"; a backup must NOT: an unnamed source version
# makes every later restore a guess.
say "1) Quell-Version und Formatdecke"
HOSHI_VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1 | tr -d ' \r')"
if [ -z "$HOSHI_VERSION" ]; then
    blocked "Quell-Version unlesbar — $REPO_ROOT/gradle.properties trägt kein version=… . Ohne eindeutige Version wird nicht gesichert."
fi

set +e
python3 - "$HOSHI_VERSION" "$FORMAT_CEILING" <<'PY'
import sys
def parse(v):
    core = v.split("-", 1)[0].split("+", 1)[0]
    return tuple(int(p) for p in core.split("."))
try:
    src, ceiling = parse(sys.argv[1]), parse(sys.argv[2])
except Exception:
    raise SystemExit(2)
width = max(len(src), len(ceiling))
src = src + (0,) * (width - len(src))
ceiling = ceiling + (0,) * (width - len(ceiling))
raise SystemExit(1 if src > ceiling else 0)
PY
VERSION_RC=$?
set -e
case "$VERSION_RC" in
    0) ok "Quell-Version: $HOSHI_VERSION (aus gradle.properties gelesen) ≤ Formatdecke $FORMAT_CEILING" ;;
    1) blocked "Quell-Version $HOSHI_VERSION ist NEUER als die Formatdecke $FORMAT_CEILING dieses Werkzeugs. Ein Backup eines unbekannten Formats wäre eine Behauptung — erst FORMAT_CEILING in pipeline/backup.sh anheben (nach echter Format-Prüfung), dann erneut sichern." ;;
    *) blocked "Quell-Version \"$HOSHI_VERSION\" ist nicht als Versionsnummer lesbar — fail-closed." ;;
esac
# The version we can read here is the repo working state, not necessarily the build
# a running/installed backend is serving. Say so instead of implying they are one.
log "Quelle der Version: gradle.properties (Repo-Arbeitsstand) — ein laufendes Backend kann ein anderer Build sein"
add_warning "SOURCE_VERSION_IS_REPO_NOT_RUNTIME" "hoshiVersion=$HOSHI_VERSION stammt aus gradle.properties des Repos, nicht aus einem laufenden/installierten Backend-Build"
echo

# ── (2) Effective paths — the backend's own precedence, nothing invented ─────
# Two resolvers, because the backend really has two (PipelineConfig.kt):
#   home_path  : configured ▷ ~/.hoshi/<file>                      (settings, memory, notes)
#   data_path  : configured ▷ /var/lib/hoshi-0.8/<file> if writable ▷ ~/.hoshi/<file>
# Mixing them up would silently back up the wrong file on a prod host.
PROD_DIR="/var/lib/hoshi-0.8"

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
sub_path() { # env_key subdir file  (lookups/diary pattern: same /var/lib gate, own subdir)
    local v; v="$(env_value "$1")"
    if [ -n "$v" ]; then printf '%s' "$v"
    elif [ -w "$PROD_DIR" ]; then printf '%s' "$PROD_DIR/$2/$3"
    else printf '%s' "$HOME/.hoshi/$2/$3"; fi
}

# Source guard: we only ever copy regular files we can read. A symlink source is a
# fail-closed case (the contract's "keine Symlink-Flucht, reguläre Quelldateien") —
# it could point anywhere, including outside the data directory.
#
# Sets the global CHECK_STATUS instead of echoing: a `blocked` inside a command
# substitution would only kill the subshell and let the run continue — exactly the
# fail-open behaviour this whole contract exists to prevent.
CHECK_STATUS=""
check_source() { # path logicalId  → sets CHECK_STATUS=PRESENT|ABSENT, blocks on anything weird
    local p="$1" id="$2"
    CHECK_STATUS="ABSENT"
    case "$p" in *"'"*) blocked "Pfad von $id enthält ein Hochkomma ($p) — das Werkzeug lehnt das ab, statt an SQLite-Quoting zu scheitern." ;; esac
    if [ -L "$p" ]; then
        blocked "$id: $p ist ein SYMLINK — fail-closed. Ein Backup folgt keinem Link aus dem Datenverzeichnis heraus."
    elif [ ! -e "$p" ]; then
        CHECK_STATUS="ABSENT"
    elif [ ! -f "$p" ]; then
        blocked "$id: $p ist keine reguläre Datei — fail-closed."
    elif [ ! -r "$p" ]; then
        blocked "$id: $p ist nicht lesbar (Rechte) — fail-closed statt halb gesichert."
    else
        # World-readable private data is a source-side problem we report but do not
        # fix (fixing it would be a write). Our own copies are 600 regardless.
        # The warning names the logical ID only — the manifest must stay free of
        # absolute source paths and user names (contract §Artefaktform).
        case "$(ls -l "$p" | cut -c8-10)" in
            *r*) add_warning "SOURCE_WORLD_READABLE" "$id: die Quelldatei ist welt-lesbar — die Kopie im Backup ist 600, die Quelle bleibt unverändert" ;;
        esac
        CHECK_STATUS="PRESENT"
    fi
}

plan_add() { # logicalId kind src artifact sensitivity hint note
    check_source "$3" "$1"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" "$CHECK_STATUS" "$7" >>"$PLAN"
}

say "2) Effektive Store-Pfade (Präzedenz: konfiguriert ▷ $PROD_DIR ▷ ~/.hoshi)"
if [ -w "$PROD_DIR" ]; then
    log "Prod-Datenverzeichnis $PROD_DIR ist beschreibbar ⇒ Daten-Stores liegen DORT"
else
    log "Prod-Datenverzeichnis $PROD_DIR fehlt/nicht beschreibbar ⇒ Daten-Stores fallen auf ~/.hoshi (Dev-Mac)"
fi

# Settings family — resolveSettingsPath-style: NO /var/lib step, ever.
plan_add "settings.skills"            json "$(home_path HOSHI_SETTINGS_PATH skills.json)"                    "stores/settings/skills.json"            INTERNAL HOSHI_SETTINGS_PATH            "aktivierte Fähigkeiten"
plan_add "settings.language"          json "$(home_path HOSHI_LANGUAGE_PATH language.json)"                  "stores/settings/language.json"          INTERNAL HOSHI_LANGUAGE_PATH            "Sprache"
plan_add "settings.persona"           json "$(home_path HOSHI_PERSONA_PATH persona.json)"                    "stores/settings/persona.json"           INTERNAL HOSHI_PERSONA_PATH             "Persona"
plan_add "settings.tts"               json "$(home_path HOSHI_TTS_ENGINE_PATH tts-engine.json)"              "stores/settings/tts-engine.json"        INTERNAL HOSHI_TTS_ENGINE_PATH          "TTS-Engine/Stimme (enthält KEINEN API-Key)"
plan_add "settings.brain-model"       json "$(home_path HOSHI_BRAIN_MODEL_PATH brain-model.json)"            "stores/settings/brain-model.json"       INTERNAL HOSHI_BRAIN_MODEL_PATH         "Brain-Soll (Wunsch, nicht Sidecar-Live-Wahrheit)"
plan_add "settings.brain-auto-switch" json "$(home_path HOSHI_BRAIN_AUTO_SWITCH_PATH brain-auto-switch.json)" "stores/settings/brain-auto-switch.json" INTERNAL HOSHI_BRAIN_AUTO_SWITCH_PATH   "Auto-Modellwahl"
plan_add "settings.lookup-model"      json "$(home_path HOSHI_LOOKUP_MODEL_PATH lookup-model.json)"          "stores/settings/lookup-model.json"      INTERNAL HOSHI_LOOKUP_MODEL_PATH        "Recherche-Modell"
plan_add "settings.extended-think"    json "$(home_path HOSHI_EXTENDED_THINK_PATH extended-think.json)"      "stores/settings/extended-think.json"    INTERNAL HOSHI_EXTENDED_THINK_PATH      "Nachschlage-Modus"
plan_add "settings.weather-location"  json "$(home_path HOSHI_WEATHER_LOCATION_PATH weather-location.json)"  "stores/settings/weather-location.json"  PRIVATE  HOSHI_WEATHER_LOCATION_PATH     "Wetterort + Koordinaten (Wohnort!)"

# Data-store family — resolveDataStorePath: the /var/lib step exists for exactly these.
plan_add "settings.night-mode" json "$(data_path HOSHI_NIGHT_MODE_STORE_PATH night-mode.json)"    "stores/settings/night-mode.json"  PRIVATE HOSHI_NIGHT_MODE_STORE_PATH "Nachtmodus je Satellit (Satelliten-IDs)"
plan_add "lists.default"       json "$(data_path HOSHI_LIST_STORE_PATH lists.json)"               "stores/lists/lists.json"          PRIVATE HOSHI_LIST_STORE_PATH       "Haushaltsliste — Runtime default AUS (HOSHI_LIST_ENABLED=false)"
plan_add "timers.scheduled"    json "$(data_path HOSHI_TIMER_STORE_PATH scheduled-items.json)"    "stores/timers/scheduled-items.json" PRIVATE HOSHI_TIMER_STORE_PATH     "Timer aktiv+gefeuert — default AUS (HOSHI_TIMER_ENABLED / _PERSISTENCE_ENABLED)"

# Memory — SQLite, highest sensitivity, own backup path (see step 5).
plan_add "memory.entity"   sqlite "$(home_path HOSHI_MEMORY_DB_PATH entity-memory.db)"             "stores/memory/entity-memory.db"   HIGH HOSHI_MEMORY_DB_PATH           "Fakten je Sprecher — default AUS (HOSHI_MEMORY_ENABLED)"
plan_add "memory.episodic" sqlite "$(home_path HOSHI_MEMORY_EPISODIC_DB_PATH episodic-memory.db)"  "stores/memory/episodic-memory.db" HIGH HOSHI_MEMORY_EPISODIC_DB_PATH  "Episoden + Embeddings — default AUS (HOSHI_EPISODIC_ENABLED)"

# Personal notes — JSONL. Three notes, three resolutions (see RESULT/Rate-Stellen).
plan_add "notes.daily"    jsonl "$(home_path HOSHI_ANDI_FAKTOR_PATH andi-faktor.jsonl)"                       "stores/notes/andi-faktor.jsonl"      PRIVATE HOSHI_ANDI_FAKTOR_PATH       "Tagesnote"
plan_add "notes.workshop" jsonl "$(home_path HOSHI_WORKSHOP_NOTE_PATH werkstatt-notizen.jsonl)"               "stores/notes/werkstatt-notizen.jsonl" PRIVATE HOSHI_WORKSHOP_NOTE_PATH     "Werkstatt-Briefkasten (append-only)"
plan_add "notes.lookup"   jsonl "$(sub_path HOSHI_ESCALATION_LOOKUP_PATH lookups nachgeschlagen.jsonl)"       "stores/notes/nachgeschlagen.jsonl"   PRIVATE HOSHI_ESCALATION_LOOKUP_PATH "Nachgeschlagen-Notizen"

# ── Opt-ins ─────────────────────────────────────────────────────────────────
SPEAKER_SRC="$(data_path HOSHI_SPEAKER_STORE_PATH speaker-profiles.json)"
if $WITH_SPEAKER; then
    # Contract: explicit switch AND a second confirmation. Non-interactive callers
    # confirm through the env variable — but never by silence. A dry-run writes
    # nothing and extracts nothing, so it only announces the gate instead of
    # blocking a preview behind a prompt.
    if $DRY_RUN; then
        warn "Sprecherprofile: der ECHTE Lauf verlangt zusätzlich eine zweite Bestätigung (JA / HOSHI_BACKUP_SPEAKER_CONFIRM=ja)"
    else
        CONFIRM="${HOSHI_BACKUP_SPEAKER_CONFIRM:-}"
        if [ -z "$CONFIRM" ] && [ -t 0 ]; then
            warn "Sprecherprofile sind BIOMETRISCHE Daten (Namen + 512-d-Embeddings)."
            printf '  Zum Mitsichern bitte JA eintippen: '
            read -r CONFIRM || CONFIRM=""
        fi
        case "$CONFIRM" in
            [jJ][aA]|[yY][eE][sS]) ;;
            *) blocked "Sprecherprofile ohne zweite Bestätigung — Abbruch. (Tastatur-JA, oder HOSHI_BACKUP_SPEAKER_CONFIRM=ja)" ;;
        esac
    fi
    plan_add "biometrics.speaker-profiles" json "$SPEAKER_SRC" "speaker-profiles/speaker-profiles.json" BIOMETRIC HOSHI_SPEAKER_STORE_PATH "SENSITIV — Namen + Sprecher-Embeddings, separates Artefakt"
else
    add_excluded "biometrics.speaker-profiles" "OPT_IN_REQUIRED" "biometrisch — nur mit --with-speaker-profiles (+ zweiter Bestätigung)"
fi

DIARY_DIR="$(sub_path HOSHI_TURN_DIARY_DIR diary '')"
DIARY_DIR="${DIARY_DIR%/}"
if $WITH_DIARY; then
    if [ -d "$DIARY_DIR" ]; then
        for f in "$DIARY_DIR"/turn-diary-*.jsonl; do
            [ -e "$f" ] || continue
            base="$(basename "$f")"
            day="${base#turn-diary-}"; day="${day%.jsonl}"
            plan_add "evidence.turn-diary.$day" jsonl "$f" "evidence/diary/$base" PRIVATE HOSHI_TURN_DIARY_DIR "PRIVAT — Verhaltensprofil des Tages (chatId, Persona, Zielraum, Surprisal)"
        done
    fi
    # An empty/absent diary directory is not an error — the diary is default OFF.
    grep -q 'evidence\.turn-diary' "$PLAN" 2>/dev/null || \
        add_excluded "evidence.turn-diary" "ABSENT" "kein turn-diary-*.jsonl am aufgelösten Pfad (HOSHI_TURN_DIARY_DIR ▷ /var/lib/hoshi-0.8/diary ▷ ~/.hoshi/diary)"
else
    add_excluded "evidence.turn-diary" "OPT_IN_REQUIRED" "PRIVAT (Verhaltensprofil pro Tag) — nur mit --with-diary"
fi

# ── Deliberately NOT in the artifact (contract §Ziel/Nicht-Ziele) ────────────
add_excluded "secrets"              "NEVER_BACKED_UP"                "~/.hoshi/secrets.json, ~/.hoshi/openai.key, /etc/hoshi-0.8/secrets.env, TLS-Keys — nach Restore neu bereitstellen/rotieren"
add_excluded "rooms"                "OWNED_BY_HOME_ASSISTANT"        "Räume/Geräte sind HA-Wahrheit — ein Hoshi-Backup ersetzt KEIN HA-Backup"
add_excluded "ha.last-known-states" "RECONSTRUCTABLE_CACHE"          "ha/last-known-states.json ist Cache, nie ein Raum-Backup"
add_excluded "home-edit.audit"      "AUDIT_LOG_NOT_STATE"            "HOSHI_HOME_EDIT_AUDIT_PATH ist ein Protokoll, nicht der Zustand"
add_excluded "escalation.spend"     "COST_WINDOW_MUST_NOT_REWIND"    "escalation/spend.json — ein Restore darf das Kostenfenster nicht still zurückdrehen"
add_excluded "runtime.state"        "RECONSTRUCTABLE"                "run/brain.state, Logs, filler-cache"
add_excluded "knowledge.models"     "LARGE_REPROCURABLE"             "Wikipedia-DB, HuggingFace-/Ollama-Modelle, Piper-Stimmen — über gepinnte Manifeste wiederbeschaffbar"
add_excluded "browser.localStorage" "NOT_REACHABLE_FROM_BACKEND"     "Theme + hoshi.deviceId leben im Browser; das Backend kann sie nicht sichern"
add_excluded "speaker.captures"     "BIOMETRIC_RAW_AUDIO_NEVER"      "HOSHI_SPEAKER_CAPTURE_DIR — Roh-Audio wird unter KEINEM Schalter eingesammelt"
add_excluded "working-session"      "INTENTIONALLY_EPHEMERAL"        "laufender Gesprächskontext im RAM — absichtlich flüchtig"

# The timer↔deviceId gap: always named, and escalated to a hard count when the
# timer store really carries origins. "Timer lesbar" is not "Timer klingelt".
add_warning "BROWSER_DEVICE_ID_NOT_RESTORED" "Timer tragen in origin die Browser-hoshi.deviceId aus localStorage. Sie ist NICHT im Backup ⇒ nach einem Restore kann das Klingel-Routing seine Lane verlieren. Ein restaurierter Timer ist kein Klingel-Beweis."
TIMER_SRC="$(data_path HOSHI_TIMER_STORE_PATH scheduled-items.json)"
if [ -f "$TIMER_SRC" ]; then
    ORIGIN_COUNT="$(python3 - "$TIMER_SRC" <<'PY' 2>/dev/null || echo "?"
import json, sys
def walk(node):
    n = 0
    if isinstance(node, dict):
        if str(node.get("origin") or "").strip():
            n += 1
        for v in node.values():
            n += walk(v)
    elif isinstance(node, list):
        for v in node:
            n += walk(v)
    return n
try:
    print(walk(json.load(open(sys.argv[1], encoding="utf-8"))))
except Exception:
    print("?")
PY
)"
    if [ "$ORIGIN_COUNT" != "0" ] && [ "$ORIGIN_COUNT" != "?" ]; then
        add_warning "BROWSER_DEVICE_ID_NOT_RESTORED_COUNT" "$ORIGIN_COUNT Timer-Eintrag/Einträge mit nichtleerem origin gefunden — genau diese verlieren ohne die zugehörige Browser-deviceId ihre Klingel-Zuordnung"
    fi
fi

# A live backend can append to a JSONL file between our read and our hash. We never
# stop it ourselves (owner gate) — we say it.
if curl -fsS -m 2 -o /dev/null "http://127.0.0.1:${HOSHI_PORT:-8090}/api/health" 2>/dev/null; then
    add_warning "BACKEND_RUNNING" "Auf :${HOSHI_PORT:-8090} antwortet ein Backend. JSONL-Stores (Notizen/Diary) können währenddessen wachsen; für einen garantierten Schnappschuss ist ein Owner-Wartungsfenster nötig. Dieses Skript stoppt NIE selbst einen Dienst."
fi

# ── Plan table ──────────────────────────────────────────────────────────────
echo
say "3) Plan — was gesichert würde"
printf '  %-32s %-6s %-10s %10s  %s\n' "STORE" "TYP" "SENSIBEL" "BYTES" "PFAD"
PRESENT_COUNT=0
ABSENT_COUNT=0
TOTAL_BYTES=0
while IFS=$'\t' read -r id kind src artifact sens hint status note; do
    [ -n "$id" ] || continue
    if [ "$status" = "PRESENT" ]; then
        bytes="$(file_bytes "$src")"
        TOTAL_BYTES=$((TOTAL_BYTES + bytes))
        PRESENT_COUNT=$((PRESENT_COUNT + 1))
        printf '  %-32s %-6s %-10s %10s  %s\n' "$id" "$kind" "$sens" "$bytes" "$src"
    else
        ABSENT_COUNT=$((ABSENT_COUNT + 1))
        printf '  %-32s %-6s %-10s %10s  %s\n' "$id" "$kind" "$sens" "ABSENT" "$src"
    fi
    if [ -n "$note" ]; then printf '      %s%s%s\n' "$C_DIM" "$note" "$C_RESET"; fi
done <"$PLAN"
echo
log "$PRESENT_COUNT Store(s) vorhanden · $ABSENT_COUNT ABSENT (flag-off/nie angelegt = normal) · zusammen $TOTAL_BYTES Bytes"

echo
say "4) Bewusst NICHT enthalten"
while IFS=$'\t' read -r id reason detail; do
    [ -n "$id" ] || continue
    printf '  %s✗%s %-30s %-28s %s\n' "$C_DIM" "$C_RESET" "$id" "$reason" "$detail"
done <"$EXCLUDED"

echo
say "5) Warnungen"
while IFS=$'\t' read -r code detail; do
    [ -n "$code" ] || continue
    warn "$code: $detail"
done <"$WARNINGS"

# ── Manifest renderer (shared by dry-run preview and the real run) ───────────
# Absolute source paths and user names NEVER enter the manifest (contract): each
# entry names only its artifact path inside the archive and the config KEY it came
# from. The plan table above is for the owner's screen, the manifest is for a
# stranger who must audit the artifact offline.
render_manifest() { # entries_file out_file consistent(true|false) mode(PLAN|BACKUP)
    python3 - "$1" "$2" "$3" "$4" "$EXCLUDED" "$WARNINGS" \
             "$CONTRACT" "$CONTRACT_VERSION" "$SCHEMA_CATALOG_VERSION" \
             "$BACKUP_TOOL_VERSION" "$HOSHI_VERSION" "$FORMAT_CEILING" "$SCOPE_CSV" "$PLATFORM" <<'PY'
import json, os, sys, datetime

(entries_f, out_f, consistent, mode, excluded_f, warnings_f,
 contract, contract_version, catalog_version, tool_version,
 hoshi_version, ceiling, scope_csv, platform) = sys.argv[1:15]

def rows(path, n):
    out = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if line:
                    out.append((line.split("\t") + [""] * n)[:n])
    return out

entries = []
for (lid, artifact, byts, sha, sens, hint, status, sver, ssrc, note) in rows(entries_f, 10):
    entries.append({
        "logicalId": lid,
        # Auflage 4 is law: the store schema version is READ or it is null. Today no
        # core store carries a machine-readable version, so null + the reason is the
        # only honest value. A hard-coded 1 would make this manifest lie on drift.
        "storeSchemaVersion": (int(sver) if sver.isdigit() else None),
        "storeSchemaVersionSource": ssrc,
        "artifact": artifact,
        "bytes": int(byts) if byts.isdigit() else None,
        "sha256": sha or None,
        "sensitivity": sens,
        "sourcePathHint": hint,
        "status": status,
        "requiredForRestore": False,
        "note": note,
    })

manifest = {
    "contract": contract,
    "contractVersion": int(contract_version),
    "createdAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "hoshiVersion": hoshi_version,
    "hoshiVersionSource": "gradle.properties (Repo-Arbeitsstand, gelesen — nicht geraten)",
    "backupToolVersion": tool_version,
    "supportedSourceVersionCeiling": ceiling,
    "schemaCatalogVersion": int(catalog_version),
    "sourcePlatform": platform,
    "scope": [s for s in scope_csv.split(",") if s],
    "mode": mode,
    "consistent": consistent == "true",
    "entries": entries,
    "excluded": [{"logicalId": a, "reason": b, "detail": c} for (a, b, c) in rows(excluded_f, 3)],
    "warnings": [{"code": a, "detail": b} for (a, b) in rows(warnings_f, 2)],
}
text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
if out_f == "-":
    sys.stdout.write(text)
else:
    tmp = out_f + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, out_f)   # the manifest lands atomically, and last
PY
}

PLATFORM="$(uname -s | tr 'A-Z' 'a-z')-$(uname -m)"
SCOPE_CSV="household,notes"
$WITH_SPEAKER && SCOPE_CSV="$SCOPE_CSV,biometrics"
$WITH_DIARY   && SCOPE_CSV="$SCOPE_CSV,evidence"

# ── DRY-RUN ends here: plan + manifest preview, nothing written ──────────────
if $DRY_RUN; then
    # Preview entries: bytes are real (a stat is read-only), hashes are not —
    # a SQLite snapshot is hashed after the backup API produced it, so claiming a
    # hash now would be a guess. PLANNED says exactly that.
    while IFS=$'\t' read -r id kind src artifact sens hint status note; do
        [ -n "$id" ] || continue
        if [ "$status" = "PRESENT" ]; then
            printf '%s\t%s\t%s\t\t%s\t%s\tPLANNED\t\t%s\t%s\n' \
                "$id" "$artifact" "$(file_bytes "$src")" "$sens" "$hint" \
                "beim Packen aus dem Store gelesen (heute: kein Versionsfeld ⇒ null)" "$note" >>"$ENTRIES"
        else
            printf '%s\t%s\t\t\t%s\t%s\tABSENT\t\tnicht vorhanden\t%s\n' \
                "$id" "$artifact" "$sens" "$hint" "$note" >>"$ENTRIES"
        fi
    done <"$PLAN"

    echo
    say "6) Manifest-Vorschau (exakt die spätere manifest.json — ohne absolute Pfade)"
    render_manifest "$ENTRIES" "-" "false" "PLAN"
    echo
    ok "DRY-RUN fertig — es wurde NICHTS geschrieben, keine Quelle angefasst."
    HINT="bin/hoshi backup"
    $WITH_SPEAKER && HINT="$HINT --with-speaker-profiles"
    $WITH_DIARY   && HINT="$HINT --with-diary"
    log "Echter Lauf: $HINT --out <dir>"
    exit 0
fi

# ── (6) Target preflight ────────────────────────────────────────────────────
echo
say "6) Ziel prüfen"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
[ -d "$OUT_DIR" ] || CREATED_OUT_DIR=true
mkdir -p "$OUT_DIR" || blocked "Zielverzeichnis $OUT_DIR nicht anlegbar"
[ -w "$OUT_DIR" ] || blocked "Zielverzeichnis $OUT_DIR ist nicht beschreibbar"
ARCHIVE="$OUT_DIR/hoshi-backup-$TS.tar.gz"
MANIFEST_OUT="$OUT_DIR/hoshi-backup-$TS.manifest.json"
# Never overwrite an existing artifact — a backup that silently replaces another
# backup is how two-generation recovery dies.
[ -e "$ARCHIVE" ] && blocked "$ARCHIVE existiert bereits — es wird nie überschrieben."
[ -e "$MANIFEST_OUT" ] && blocked "$MANIFEST_OUT existiert bereits — es wird nie überschrieben."

AVAIL_KB="$(df -k "$OUT_DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
NEEDED_KB=$(( (TOTAL_BYTES / 1024) * 2 + 1024 ))
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt "$NEEDED_KB" ] 2>/dev/null; then
    blocked "Zu wenig Platz in $OUT_DIR: ${AVAIL_KB} KB frei, ~${NEEDED_KB} KB nötig."
fi
ok "Ziel: $OUT_DIR (frei: ${AVAIL_KB:-unbekannt} KB, gebraucht ~${NEEDED_KB} KB)"

STAGE="$WORK/stage/$ARCHIVE_ROOT"
mkdir -p "$STAGE"
chmod 700 "$WORK/stage" "$STAGE"

# ── (7) Stage every present store ───────────────────────────────────────────
echo
say "7) Stores sichern (JSON/JSONL kopieren + validieren · SQLite über die Backup-API)"
CONSISTENT=true

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

while IFS=$'\t' read -r id kind src artifact sens hint status note; do
    [ -n "$id" ] || continue
    if [ "$status" != "PRESENT" ]; then
        printf '%s\t%s\t\t\t%s\t%s\tABSENT\t\tnicht vorhanden\t%s\n' "$id" "$artifact" "$sens" "$hint" "$note" >>"$ENTRIES"
        log "ABSENT  $id"
        continue
    fi
    dst="$STAGE/$artifact"
    mkdir -p "$(dirname "$dst")"
    schema_version=""
    schema_source=""

    case "$kind" in
        json)
            if ! validate_json "$src"; then
                blocked "$id: $src ist kein gültiges JSON. Ein Backup mit kaputtem Store wäre kein Backup (Vertrag: kein consistent=true) — Datei prüfen, dann erneut sichern."
            fi
            cp "$src" "$dst"
            chmod 600 "$dst"
            # Read a version if the store carries one. It does not today — say so.
            schema_version="$(python3 - "$dst" <<'PY' 2>/dev/null || true
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    d = None
if isinstance(d, dict):
    for k in ("storeSchemaVersion", "schemaVersion", "version"):
        v = d.get(k)
        if isinstance(v, int):
            print(v)
            break
PY
)"
            if [ -n "$schema_version" ]; then
                schema_source="im Store gelesen (JSON-Feld)"
            else
                schema_source="unversioned — der Store trägt heute KEIN Versionsfeld (nicht behauptet)"
            fi
            ;;
        jsonl)
            set +e
            validate_jsonl "$src"; v_rc=$?
            set -e
            if [ "$v_rc" -eq 3 ]; then
                blocked "$id: $src endet mitten in einer Zeile (partielle JSONL-Zeile). Der Vertrag verbietet daraus einen gültigen Eintrag — Schreiber im Owner-Fenster schließen, dann erneut sichern."
            elif [ "$v_rc" -ne 0 ]; then
                blocked "$id: $src enthält eine nicht parsebare JSONL-Zeile — fail-closed."
            fi
            cp "$src" "$dst"
            chmod 600 "$dst"
            schema_source="unversioned — JSONL ohne Kopfzeile/Versionsfeld (nicht behauptet)"
            ;;
        sqlite)
            if ! command -v sqlite3 >/dev/null 2>&1; then
                warn "SKIPPED $id — sqlite3 fehlt auf diesem Host. Eine laufende DB wird NIE blind kopiert."
                printf '%s\t%s\t\t\t%s\t%s\tSKIPPED:SQLITE3_MISSING\t\tnicht gelesen (kein sqlite3)\t%s\n' "$id" "$artifact" "$sens" "$hint" "$note" >>"$ENTRIES"
                CONSISTENT=false
                degraded
                continue
            fi
            # -readonly: the source is opened read-only, so a backup can never
            # checkpoint/alter the live DB. If SQLite would need to write (hot WAL),
            # this fails — and then we skip honestly instead of touching the source.
            if ! sqlite3 -readonly "$src" ".backup '$dst'" 2>"$WORK/sqlite.err"; then
                warn "SKIPPED $id — SQLite-Backup-API scheiterte am read-only geöffneten Quell-Store: $(head -1 "$WORK/sqlite.err" | tr -d '\r')"
                warn "        (heißes WAL? Dann braucht dieser Store ein Owner-Wartungsfenster — halb kopiert wird nicht.)"
                rm -f "$dst"
                printf '%s\t%s\t\t\t%s\t%s\tSKIPPED:SQLITE_READONLY_BACKUP_FAILED\t\tnicht gelesen\t%s\n' "$id" "$artifact" "$sens" "$hint" "$note" >>"$ENTRIES"
                CONSISTENT=false
                degraded
                continue
            fi
            chmod 600 "$dst"
            if [ "$(sqlite3 "$dst" 'PRAGMA integrity_check;' 2>/dev/null | head -1)" != "ok" ]; then
                warn "SKIPPED $id — PRAGMA integrity_check auf der Kopie war nicht ok."
                rm -f "$dst"
                printf '%s\t%s\t\t\t%s\t%s\tSKIPPED:INTEGRITY_CHECK_FAILED\t\tnicht verwertbar\t%s\n' "$id" "$artifact" "$sens" "$hint" "$note" >>"$ENTRIES"
                CONSISTENT=false
                degraded
                continue
            fi
            # PRAGMA user_version IS a readable version slot — read it from the COPY
            # (never from the live source). 0 means "never set", not "version 0".
            uv="$(sqlite3 "$dst" 'PRAGMA user_version;' 2>/dev/null | head -1)"
            if [ -n "$uv" ] && [ "$uv" != "0" ]; then
                schema_version="$uv"
                schema_source="aus der Kopie gelesen (PRAGMA user_version=$uv)"
            else
                schema_source="unversioned — PRAGMA user_version=0 (nie gesetzt; keine Version behauptet)"
            fi
            ;;
        *)
            blocked "$id: unbekannte Store-Art \"$kind\" — fail-closed."
            ;;
    esac

    bytes="$(file_bytes "$dst")"
    sha="$(sha256_of "$dst")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\tINCLUDED\t%s\t%s\t%s\n' \
        "$id" "$artifact" "$bytes" "$sha" "$sens" "$hint" "$schema_version" "$schema_source" "$note" >>"$ENTRIES"
    ok "$(printf '%-32s %8s B  sha256:%s…' "$id" "$bytes" "$(printf '%s' "$sha" | cut -c1-12)")"
done <"$PLAN"

# ── (8) Manifest, then pack ─────────────────────────────────────────────────
echo
say "8) Manifest schreiben und packen"
render_manifest "$ENTRIES" "$STAGE/manifest.json" "$CONSISTENT" "BACKUP"
ok "manifest.json geschrieben (atomar, zuletzt) — consistent=$CONSISTENT"

tar -czf "$ARCHIVE" -C "$WORK/stage" "$ARCHIVE_ROOT" || blocked "tar scheiterte — kein Artefakt."
chmod 600 "$ARCHIVE"
ok "Archiv: $ARCHIVE ($(file_bytes "$ARCHIVE") B)"

# ── (9) Verify: hash everything back OUT of the archive ─────────────────────
# This is the step that turns "Datei kopiert" into a claim: the bytes that are
# really inside the tar must reproduce the manifest, member by member.
echo
say "9) Verify — jede SHA-256 gegen den Tar-Inhalt"
VERIFY_FAILS=0
VERIFY_OK=0
while IFS=$'\t' read -r id artifact bytes sha sens hint status sver ssrc note; do
    [ "$status" = "INCLUDED" ] || continue
    member="$ARCHIVE_ROOT/$artifact"
    actual="$(tar -xOzf "$ARCHIVE" "$member" 2>/dev/null | sha256_of_stdin || true)"
    if [ "$actual" = "$sha" ]; then
        VERIFY_OK=$((VERIFY_OK + 1))
    else
        fail "MISMATCH $id — Manifest ${sha:0:12}… vs. Archiv ${actual:-<leer>}"
        VERIFY_FAILS=$((VERIFY_FAILS + 1))
    fi
done <"$ENTRIES"

# The manifest inside the archive must be the manifest we hand out beside it.
MANIFEST_IN_TAR_SHA="$(tar -xOzf "$ARCHIVE" "$ARCHIVE_ROOT/manifest.json" 2>/dev/null | sha256_of_stdin || true)"
MANIFEST_STAGE_SHA="$(sha256_of "$STAGE/manifest.json")"
if [ "$MANIFEST_IN_TAR_SHA" != "$MANIFEST_STAGE_SHA" ]; then
    fail "MISMATCH manifest.json — das Manifest im Archiv weicht vom geschriebenen ab"
    VERIFY_FAILS=$((VERIFY_FAILS + 1))
fi

if [ "$VERIFY_FAILS" -gt 0 ]; then
    rm -f "$ARCHIVE"
    blocked "Verify fehlgeschlagen ($VERIFY_FAILS Abweichung(en)) — das unbewiesene Archiv wurde gelöscht, kein Manifest daneben abgelegt."
fi
ok "$VERIFY_OK Store-Eintrag/Einträge + manifest.json byte-genau aus dem Archiv gegengeprüft"

# Only now, after the archive proved itself, does a manifest appear beside it.
cp "$STAGE/manifest.json" "$MANIFEST_OUT"
chmod 600 "$MANIFEST_OUT"

echo
log "Artefakt : $ARCHIVE"
log "Manifest : $MANIFEST_OUT"
log "Inhalt   : $VERIFY_OK Store(s) · Scope $SCOPE_CSV · consistent=$CONSISTENT"
if [ "$RC" -eq 0 ]; then
    ok "Urteil: BACKUP GRÜN — gepackt, verifiziert, Quellen unverändert. (Restore ist Welle 2b — dieses Skript kann ihn NICHT.)"
else
    warn "Urteil: EINGESCHRÄNKT — Artefakt existiert und ist verifiziert, aber mindestens ein vorhandener Store wurde SKIPPED (consistent=false)."
fi
exit "$RC"
