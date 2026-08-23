#!/usr/bin/env bash
# pipeline/test-restore-probe.sh — the REAL restoration proof for `bin/hoshi restore`
# (Kagami-Invariante 3: a contract without a probe does not count).
#
# Builds a fully sandboxed household (every HOSHI_*_PATH env var pointed at a tmp
# directory — NEVER at ~/.hoshi or /var/lib/hoshi-0.8), runs a REAL `bin/hoshi backup`,
# mutates/deletes the "live" store files, then runs a REAL `bin/hoshi restore` and
# proves byte-for-byte that the original content came back. It also proves the error
# path: a corrupted or incomplete backup is refused BEFORE the first byte is written.
#
# This script never touches a real store, never starts/stops a service, never reaches
# ct-106. It only drives bin/hoshi against files it created itself under $TMPDIR.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"
HOSHI_BIN="$REPO_ROOT/bin/hoshi"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-restore-probe.XXXXXX")"
LIVE="$TMP_ROOT/live"                 # the sandboxed "current store" directory
SNAPSHOT="$TMP_ROOT/live-post-backup" # what the live dir looked like right after backup
BACKUP_OUT="$TMP_ROOT/backups"
LOGS="$TMP_ROOT/logs"
mkdir -p "$LIVE" "$LIVE/lookups" "$LIVE/diary" "$BACKUP_OUT" "$LOGS"

cleanup() {
    case "$TMP_ROOT" in
        "${TMPDIR:-/tmp}"/hoshi-restore-probe.*) rm -rf -- "$TMP_ROOT" ;;
        *) echo "[restore-probe] WARN: unerwarteter TMP-Pfad bleibt liegen: $TMP_ROOT" >&2 ;;
    esac
}
trap cleanup EXIT

PASS_COUNT=0
FAIL_COUNT=0
pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "[restore-probe] PASS: $*"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); echo "[restore-probe] FAIL: $*" >&2; }
section() { echo; echo "════════════════════════════════════════════════════════════"; echo "[restore-probe] $*"; echo "════════════════════════════════════════════════════════════"; }

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

# ── Sandbox: EVERY store env var points into $LIVE — never a real Hoshi path ────
export HOSHI_SETTINGS_PATH="$LIVE/skills.json"
export HOSHI_LANGUAGE_PATH="$LIVE/language.json"
export HOSHI_PERSONA_PATH="$LIVE/persona.json"
export HOSHI_TTS_ENGINE_PATH="$LIVE/tts-engine.json"
export HOSHI_BRAIN_MODEL_PATH="$LIVE/brain-model.json"
export HOSHI_BRAIN_AUTO_SWITCH_PATH="$LIVE/brain-auto-switch.json"
export HOSHI_LOOKUP_MODEL_PATH="$LIVE/lookup-model.json"
export HOSHI_EXTENDED_THINK_PATH="$LIVE/extended-think.json"
export HOSHI_WEATHER_LOCATION_PATH="$LIVE/weather-location.json"
export HOSHI_NIGHT_MODE_STORE_PATH="$LIVE/night-mode.json"
export HOSHI_LIST_STORE_PATH="$LIVE/lists.json"
export HOSHI_TIMER_STORE_PATH="$LIVE/scheduled-items.json"
export HOSHI_MEMORY_DB_PATH="$LIVE/entity-memory.db"
export HOSHI_MEMORY_EPISODIC_DB_PATH="$LIVE/episodic-memory.db"
export HOSHI_ANDI_FAKTOR_PATH="$LIVE/andi-faktor.jsonl"
export HOSHI_WORKSHOP_NOTE_PATH="$LIVE/werkstatt-notizen.jsonl"
export HOSHI_ESCALATION_LOOKUP_PATH="$LIVE/lookups/nachgeschlagen.jsonl"
export HOSHI_SPEAKER_STORE_PATH="$LIVE/speaker-profiles.json"
export HOSHI_TURN_DIARY_DIR="$LIVE/diary"
# Point port checks at a loopback port nothing is listening on, so the "backend
# running" warning stays quiet and predictable for this proof.
export HOSHI_PORT=18090
export HOSHI_BACKUP_OUT_DIR="$BACKUP_OUT"
export HOSHI_BACKUP_SPEAKER_CONFIRM=ja
export HOSHI_RESTORE_CONFIRM=ja

section "0) Sandbox-Fixtures anlegen ($LIVE)"
cat >"$LIVE/skills.json" <<'JSON'
{"enabled":["timer","lists"]}
JSON
cat >"$LIVE/lists.json" <<'JSON'
["Milch","Brot","Kaffee"]
JSON
cat >"$LIVE/scheduled-items.json" <<'JSON'
{"active":[{"id":"t1","label":"Kaffee","origin":"browser-device-abc123"}],"fired":[]}
JSON
cat >"$LIVE/andi-faktor.jsonl" <<'JSONL'
{"day":"2026-08-18","note":"Testnotiz vor Mutation"}
JSONL
cat >"$LIVE/lookups/nachgeschlagen.jsonl" <<'JSONL'
{"q":"Wie spaet ist es in Tokio","a":"probe-antwort"}
JSONL
cat >"$LIVE/speaker-profiles.json" <<'JSON'
{"profiles":[{"name":"Probe-Sprecher"}]}
JSON
cat >"$LIVE/diary/turn-diary-2026-08-18.jsonl" <<'JSONL'
{"chatId":"c1","persona":"Hoshi","room":"Kueche","surprisal":0.2}
JSONL
rm -f "$LIVE/entity-memory.db"
sqlite3 "$LIVE/entity-memory.db" "CREATE TABLE fact(k TEXT PRIMARY KEY, v TEXT); INSERT INTO fact VALUES('probe','PROBE-WERT-VOR-MUTATION');"
pass "Fixtures angelegt: $(ls "$LIVE" | tr '\n' ' ')"

section "1) ECHTES Backup ziehen"
set +e
"$HOSHI_BIN" backup --with-speaker-profiles --with-diary --out "$BACKUP_OUT" >"$LOGS/01-backup.log" 2>&1
BACKUP_RC=$?
set -e
cat "$LOGS/01-backup.log"
[ "$BACKUP_RC" -eq 0 ] && pass "Backup exit=0" || fail "Backup exit=$BACKUP_RC (erwartet 0)"

ARCHIVE="$(find "$BACKUP_OUT" -maxdepth 1 -name '*.tar.gz' | head -1)"
if [ -z "$ARCHIVE" ]; then
    fail "kein Archiv unter $BACKUP_OUT gefunden — Probe kann nicht fortfahren"
    exit 1
fi
pass "Archiv: $ARCHIVE ($(wc -c <"$ARCHIVE" | tr -d ' ') B)"
tar -tzf "$ARCHIVE" | sort

# Ground truth for the SQLite byte-diff below: backup.sh captures via the SQLite
# ".backup" API, which can legitimately write a different byte layout than the live
# file it read (same content, different page/free-list bytes — wave-2a's own proof
# already documented this). So the correct "did restore reproduce this exactly"
# baseline is the ARCHIVED member, not the pre-mutation live file.
tar -xOzf "$ARCHIVE" "hoshi-backup-v1/stores/memory/entity-memory.db" >"$TMP_ROOT/archived-entity-memory.db"

section "2) Post-Backup-Snapshot ziehen (Beweisgrundlage) + Quellen mutieren/löschen"
cp -R "$LIVE" "$SNAPSHOT"
echo "-- vor Mutation --"
( cd "$LIVE" && find . -type f | sort | xargs -I{} sh -c 'echo "{}  $(shasum -a 256 "{}" | cut -d" " -f1)"' )

# Mutate every fixture kind: overwrite, delete, and corrupt a running DB.
echo '{"enabled":["MUTIERT"]}' >"$LIVE/skills.json"
rm -f "$LIVE/lists.json"
echo '{"active":[],"fired":[],"MUTATION":true}' >"$LIVE/scheduled-items.json"
echo '{"broken":' >>"$LIVE/andi-faktor.jsonl"
rm -f "$LIVE/lookups/nachgeschlagen.jsonl"
echo '{"profiles":[{"name":"MUTIERT"}]}' >"$LIVE/speaker-profiles.json"
rm -f "$LIVE/diary/turn-diary-2026-08-18.jsonl"
sqlite3 "$LIVE/entity-memory.db" "UPDATE fact SET v='MUTIERT-NACH-BACKUP' WHERE k='probe';"
echo "-- nach Mutation --"
( cd "$LIVE" && find . -type f | sort | xargs -I{} sh -c 'echo "{}  $(shasum -a 256 "{}" | cut -d" " -f1)"' )
pass "Quellen mutiert/gelöscht (Datenverlust simuliert)"

section "3) restore --dry-run gegen die mutierte Sandbox"
set +e
"$HOSHI_BIN" restore "$ARCHIVE" --dry-run --with-speaker-profiles --with-diary >"$LOGS/03-dry-run.log" 2>&1
DRYRUN_RC=$?
set -e
cat "$LOGS/03-dry-run.log"
if [ "$DRYRUN_RC" -eq 0 ] || [ "$DRYRUN_RC" -eq 2 ]; then
    pass "dry-run exit=$DRYRUN_RC (0 oder 2 = restaurierbar)"
else
    fail "dry-run exit=$DRYRUN_RC (erwartet 0 oder 2)"
fi
if grep -q "es wurde NICHTS geschrieben" "$LOGS/03-dry-run.log"; then
    pass "dry-run bestätigt: nichts geschrieben"
else
    fail "dry-run-Ausgabe nennt nicht explizit 'nichts geschrieben'"
fi
# Prove the dry-run genuinely touched nothing: mutated state must be unchanged.
if [ "$(sha "$LIVE/entity-memory.db")" != "$(sha "$SNAPSHOT/entity-memory.db")" ] && [ ! -f "$LIVE/lists.json" ]; then
    pass "Sandbox nach dry-run weiterhin im mutierten Zustand (kein stiller Write)"
else
    fail "Sandbox veränderte sich durch dry-run — VERBOTEN"
fi

section "4) ECHTER restore gegen die mutierte Sandbox"
set +e
"$HOSHI_BIN" restore "$ARCHIVE" --with-speaker-profiles --with-diary >"$LOGS/04-restore.log" 2>&1
RESTORE_RC=$?
set -e
cat "$LOGS/04-restore.log"
if [ "$RESTORE_RC" -eq 0 ] || [ "$RESTORE_RC" -eq 2 ]; then
    pass "restore exit=$RESTORE_RC (0 oder 2 = erfolgreich/mit benannter Lücke)"
else
    fail "restore exit=$RESTORE_RC (erwartet 0 oder 2)"
fi

section "5) Byte-genauer Diff-Beweis: wiederhergestellt == Zustand direkt nach dem Backup"
DIFF_OK=true
for f in skills.json lists.json scheduled-items.json andi-faktor.jsonl \
         lookups/nachgeschlagen.jsonl speaker-profiles.json diary/turn-diary-2026-08-18.jsonl; do
    if [ ! -f "$LIVE/$f" ]; then
        fail "fehlt nach Restore: $f"
        DIFF_OK=false
        continue
    fi
    if diff -u "$SNAPSHOT/$f" "$LIVE/$f" >"$LOGS/diff-$(basename "$f").txt"; then
        pass "$f byte-identisch zum Post-Backup-Snapshot (sha256 $(sha "$LIVE/$f" | cut -c1-16)…)"
    else
        fail "$f weicht vom Post-Backup-Snapshot ab:"
        cat "$LOGS/diff-$(basename "$f").txt"
        DIFF_OK=false
    fi
done

echo
echo "-- SQLite-Inhaltsbeweis (entity-memory.db) --"
RESTORED_VALUE="$(sqlite3 "$LIVE/entity-memory.db" "SELECT v FROM fact WHERE k='probe';")"
echo "restaurierter Wert: $RESTORED_VALUE"
if [ "$RESTORED_VALUE" = "PROBE-WERT-VOR-MUTATION" ]; then
    pass "SQLite-Inhalt wiederhergestellt (war MUTIERT-NACH-BACKUP, ist wieder PROBE-WERT-VOR-MUTATION)"
else
    fail "SQLite-Inhalt NICHT wiederhergestellt: $RESTORED_VALUE"
    DIFF_OK=false
fi
if [ "$(sha "$LIVE/entity-memory.db")" = "$(sha "$TMP_ROOT/archived-entity-memory.db")" ]; then
    pass "entity-memory.db byte-identisch zum ARCHIVIERTEN Store-Mitglied (die korrekte Referenz — die SQLite-Backup-API kann beim Sichern selbst einen anderen Byte-Layout als die Live-Quelle erzeugen, s. Kommentar)"
else
    fail "entity-memory.db weicht byteweise vom archivierten Store-Mitglied ab — das WÄRE ein echter Restore-Fehler"
fi

$DIFF_OK && pass "Gesamter Diff-Beweis: alle geprüften Stores byte-/inhaltsgenau wiederhergestellt"

section "6) Rollback-Snapshot wurde angelegt (Pre-Restore-Safety-Snapshot)"
ROLLBACK_DIRS="$(find "$LIVE" -maxdepth 2 -type d -name '.hoshi-restore-rollback-*' 2>/dev/null | sort)"
if [ -n "$ROLLBACK_DIRS" ]; then
    pass "Rollback-Verzeichnis(se) gefunden:"
    echo "$ROLLBACK_DIRS"
    find $ROLLBACK_DIRS -type f 2>/dev/null | sort
else
    fail "kein Rollback-Verzeichnis gefunden — REPLACE hätte eines anlegen müssen"
fi

section "7) Fehlerpfad — korruptes Archiv wird VOR dem ersten Schreiben verweigert"
CORRUPT="$TMP_ROOT/corrupt-byte.tar.gz"
cp "$ARCHIVE" "$CORRUPT"
# Flip one byte roughly in the middle of the archive — payload or manifest, either
# way the gzip stream or a member's SHA-256 must stop matching the manifest.
python3 - "$CORRUPT" <<'PY'
import sys
p = sys.argv[1]
with open(p, "r+b") as fh:
    fh.seek(0, 2)
    size = fh.tell()
    off = size // 2
    fh.seek(off)
    b = fh.read(1)
    fh.seek(off)
    fh.write(bytes([b[0] ^ 0xFF]))
PY
pass "ein Byte in $CORRUPT geflippt (Mitte der Datei)"

# Re-mutate the sandbox to a distinctive marker so we can prove NOTHING moved.
echo '{"marker":"UNVERAENDERT-VOR-FEHLERPROBE"}' >"$LIVE/skills.json"
MARK_SHA_BEFORE="$(sha "$LIVE/skills.json")"

set +e
"$HOSHI_BIN" restore "$CORRUPT" >"$LOGS/07-corrupt.log" 2>&1
CORRUPT_RC=$?
set -e
cat "$LOGS/07-corrupt.log"
if [ "$CORRUPT_RC" -ge 10 ]; then
    pass "korruptes Archiv: restore exit=$CORRUPT_RC (BLOCKIERT, >=10)"
else
    fail "korruptes Archiv: restore exit=$CORRUPT_RC (erwartet >=10)"
fi
if grep -q "BLOCKIERT — nichts wurde geschrieben" "$LOGS/07-corrupt.log"; then
    pass "korruptes Archiv: Meldung bestätigt 'nichts wurde geschrieben'"
else
    fail "korruptes Archiv: erwartete BLOCKIERT-Meldung fehlt"
fi
MARK_SHA_AFTER="$(sha "$LIVE/skills.json")"
if [ "$MARK_SHA_BEFORE" = "$MARK_SHA_AFTER" ]; then
    pass "Sandbox nach BLOCKIERTEM Restore unverändert (skills.json exakt gleich)"
else
    fail "Sandbox VERÄNDERT trotz BLOCKIERTEM Restore — VERBOTEN"
fi

section "8) Fehlerpfad — unvollständiges Archiv (fehlendes Store-Mitglied) wird verweigert"
INCOMPLETE_STAGE="$TMP_ROOT/incomplete-stage"
INCOMPLETE="$TMP_ROOT/incomplete.tar.gz"
mkdir -p "$INCOMPLETE_STAGE"
( cd "$INCOMPLETE_STAGE" && tar -xzf "$ARCHIVE" )
# Remove one store file the manifest still claims exists — "incomplete backup".
rm -f "$INCOMPLETE_STAGE/hoshi-backup-v1/stores/memory/entity-memory.db"
( cd "$INCOMPLETE_STAGE" && tar -czf "$INCOMPLETE" hoshi-backup-v1 )
pass "unvollständiges Archiv gebaut (entity-memory.db aus dem Tar entfernt, Manifest nennt es weiterhin)"

echo '{"marker":"UNVERAENDERT-VOR-INCOMPLETE-PROBE"}' >"$LIVE/skills.json"
MARK2_BEFORE="$(sha "$LIVE/skills.json")"
set +e
"$HOSHI_BIN" restore "$INCOMPLETE" >"$LOGS/08-incomplete.log" 2>&1
INCOMPLETE_RC=$?
set -e
cat "$LOGS/08-incomplete.log"
if [ "$INCOMPLETE_RC" -ge 10 ]; then
    pass "unvollständiges Archiv: restore exit=$INCOMPLETE_RC (BLOCKIERT, >=10)"
else
    fail "unvollständiges Archiv: restore exit=$INCOMPLETE_RC (erwartet >=10)"
fi
MARK2_AFTER="$(sha "$LIVE/skills.json")"
if [ "$MARK2_BEFORE" = "$MARK2_AFTER" ]; then
    pass "Sandbox nach BLOCKIERTEM (unvollständigem) Restore unverändert"
else
    fail "Sandbox VERÄNDERT trotz BLOCKIERTEM (unvollständigem) Restore — VERBOTEN"
fi

section "9) Fehlerpfad — fehlende Owner-Bestätigung blockiert den echten Lauf"
set +e
HOSHI_RESTORE_CONFIRM="" "$HOSHI_BIN" restore "$ARCHIVE" </dev/null >"$LOGS/09-noconfirm.log" 2>&1
NOCONFIRM_RC=$?
set -e
cat "$LOGS/09-noconfirm.log"
if [ "$NOCONFIRM_RC" -ge 10 ]; then
    pass "ohne Owner-Bestätigung: restore exit=$NOCONFIRM_RC (BLOCKIERT, >=10)"
else
    fail "ohne Owner-Bestätigung: restore exit=$NOCONFIRM_RC (erwartet >=10)"
fi

section "Ergebnis"
echo "PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
if [ "$FAIL_COUNT" -eq 0 ]; then
    echo "[restore-probe] ALLE PROBEN GRÜN"
    exit 0
else
    echo "[restore-probe] $FAIL_COUNT PROBE(N) FEHLGESCHLAGEN" >&2
    exit 1
fi
