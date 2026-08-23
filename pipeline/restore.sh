#!/usr/bin/env bash
# pipeline/restore.sh — Tsugi wave 2b: the RESTORE half of `bin/hoshi restore`.
#
# Contract: docs/tsugi/BACKUP-RESTORE-CONTRACT.md + docs/tsugi/STORE-INVENTORY.md.
# Sibling: pipeline/backup.sh (wave 2a) — this script consumes exactly what that one
# produces (hoshi-backup-v1/manifest.json + stores/…). It never writes to a target path
# before the WHOLE archive has verified clean; a corrupt/tampered backup is refused
# before the first byte is written, not partway through (contract: "Dry-run darf nie
# grün werden ..." + Abnahmematrix "manipuliertes Byte im Archiv ⇒ Dry-run blockiert").
#
# Phases, in the contract's own order:
#   1. Archive + manifest verification (dry-run AND real run both do this; nothing on
#      disk outside $WORK is touched here): schema/contractVersion/schemaCatalogVersion,
#      hoshiVersion vs. this tool's FORMAT_CEILING, duplicate/unknown logicalIds,
#      artifact path traversal, per-entry SHA-256 + structural (JSON/JSONL/SQLite)
#      validation of the bytes actually inside the tar.
#   2. Plan: for every entry, resolve the SAME effective target path backup.sh read
#      from (lib.sh's home_path/data_path/sub_path, by env-var precedence) and decide
#      CREATE / REPLACE / SKIP (identical already) / SKIP:OPT_IN_REQUIRED.
#   3. --dry-run stops here. Nothing on a configured target path is ever touched.
#   4. Real run: owner confirms (backup-id/createdAt, hoshiVersion, local host, store
#      count) — typed JA or HOSHI_RESTORE_CONFIRM=ja. A running backend is WARNED about,
#      never stopped (owner gate, same rule as backup.sh).
#   5. Existing targets are moved aside into a same-filesystem, dated rollback directory
#      — never deleted (this doubles as the pre-restore safety snapshot the order asks
#      for and the contract's "Vorhandene Ziele werden ... verschoben, nicht gelöscht").
#      Validated bytes land via same-directory temp file + atomic rename.
#   6. Any failure mid-write rolls the WHOLE run back (new files removed, rollback
#      copies moved back) — restore is all-or-nothing, never half-applied.
#
# Deliberately NOT here: starting/stopping the backend, running `bin/hoshi doctor` or
# any functional probe, flipping speaker recognition/trust, touching HA/secrets/models/
# browser localStorage. Those stay the owner's/other tools' job (contract §Echter Restore).
#
# Exit codes (same house convention as backup.sh):
#   0   fully restored (or dry-run: fully restorable), no named gaps
#   2   restored/restorable, but with named optional gaps (opt-in store present but not
#       requested, timer origin↔deviceId routing gap, source backup was consistent=false)
#  10   BLOCKED before any write, or a mid-write failure was rolled back
#
# From the dispatcher: bin/hoshi restore <archive.tar.gz> [--dry-run]
#                                        [--with-speaker-profiles] [--with-diary]

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

export COPYFILE_DISABLE=1

RESTORE_TOOL_VERSION="1.0.0"

DRY_RUN=false
WITH_SPEAKER=false
WITH_DIARY=false
ARCHIVE=""

restore_usage() {
    cat <<EOF
  ${C_BOLD}bin/hoshi restore <archiv.tar.gz>${C_RESET}   Haushalts-Stores wiederherstellen.

  Verifiziert das GESAMTE Archiv (Manifest-Schema, Formatdecke, Hashes, Struktur)
  BEVOR irgendein Zielpfad angefasst wird. Ein korruptes/unvollständiges Backup wird
  verweigert, nicht halb angewendet. Vorhandene Ziele werden vor dem Schreiben in ein
  datiertes Rollback-Verzeichnis auf demselben Dateisystem verschoben (nie gelöscht).

  ${C_BOLD}--dry-run${C_RESET}                zeigt NUR den Plan (CREATE/REPLACE/SKIP je Store,
                           Lücken/Warnungen aus dem Manifest). Schreibt nichts.
  ${C_BOLD}--with-speaker-profiles${C_RESET}  stellt ein mitgesichertes Sprecherprofil-Artefakt
                           wieder her (BIOMETRISCH). Recognition/Trust bleiben trotzdem
                           AUS, bis das Holdout-Gate erneut besteht (Vertrag).
  ${C_BOLD}--with-diary${C_RESET}             stellt ein mitgesichertes Turn-Diary wieder her (PRIVAT).

  Der echte Lauf (ohne --dry-run) verlangt eine Owner-Bestätigung (Tastatur-JA, oder
  HOSHI_RESTORE_CONFIRM=ja) — Backup-ID, Quell-Version und Zielhost werden vorher gezeigt.
  Wirkt IMMER nur lokal auf diesem Host — kein Remote-Ziel, kein Prozess wird beendet.

  NIE wiederhergestellt: Secrets, Modelle, Wissens-DB, Home-Assistant-Räume,
  Browser-localStorage (inkl. hoshi.deviceId) — diese Lücken werden aus dem Manifest
  sichtbar wiederholt, nicht stillschweigend übergangen.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)               DRY_RUN=true ;;
        --with-speaker-profiles) WITH_SPEAKER=true ;;
        --with-diary)            WITH_DIARY=true ;;
        -h|--help|help)          restore_usage; exit 0 ;;
        --*)
            fail "unbekannte Option: $1"
            echo
            restore_usage
            exit 10
            ;;
        *)
            if [ -n "$ARCHIVE" ]; then
                fail "unerwartetes zweites Argument: $1 (Archiv ist bereits $ARCHIVE)"
                exit 10
            fi
            ARCHIVE="$1"
            ;;
    esac
    shift
done

if [ -z "$ARCHIVE" ]; then
    fail "Archiv fehlt — bin/hoshi restore <archiv.tar.gz>"
    echo
    restore_usage
    exit 10
fi

# Private data by default: everything this script writes is owner-only.
umask 077

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-restore.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/stage"
mkdir -p "$STAGE"
chmod 700 "$WORK" "$STAGE"

LISTING="$WORK/listing.txt"
MANIFEST_JSON="$WORK/manifest.json"
ENTRIES="$WORK/entries.tsv"          # logicalId artifact bytes sha256 sensitivity hint status storeSchemaVersion note
EXCLUDED="$WORK/excluded.tsv"
WARNINGS="$WORK/warnings.tsv"
RPLAN="$WORK/rplan.tsv"              # logicalId kind artifact target action sensitivity note
ROLLBACK_MAP="$WORK/rollback-map.tsv"
NEW_CREATES="$WORK/new-creates.tsv"
: >"$ENTRIES"; : >"$EXCLUDED"; : >"$WARNINGS"; : >"$RPLAN"; : >"$ROLLBACK_MAP"; : >"$NEW_CREATES"

RC=0
degraded() { [ "$RC" -lt 2 ] && RC=2 || true; }
# Fail-closed BEFORE any write. If we already created a rollback dir this run, that only
# happens inside the write loop (after confirmation) — a blocked() before that point is
# guaranteed to leave every target untouched.
blocked() {
    fail "$1"
    echo
    fail "Urteil: BLOCKIERT — nichts wurde geschrieben."
    exit 10
}

rollback_all() {
    warn "Rollback: Schreibfehler — alte Zustände werden atomar zurückgetauscht"
    while IFS=$'\t' read -r tgt rb; do
        [ -n "$tgt" ] || continue
        if [ -e "$tgt" ]; then
            mv -- "$tgt" "$tgt.failed-restore-$TS" 2>/dev/null || rm -f -- "$tgt" 2>/dev/null || true
        fi
        if ! mv -- "$rb" "$tgt" 2>/dev/null; then
            fail "Rollback fehlgeschlagen für $tgt — alter Zustand liegt noch unter $rb, manuell prüfen"
        fi
    done <"$ROLLBACK_MAP"
    while IFS= read -r tgt; do
        [ -n "$tgt" ] || continue
        rm -f -- "$tgt" 2>/dev/null || true
    done <"$NEW_CREATES"
}

say "Hoshi-Restore — Haushalts-Stores wiederherstellen"
$DRY_RUN && log "Modus  : DRY-RUN — nur Plan, kein Zielpfad wird angefasst" || log "Modus  : ECHTER LAUF — schreibt nach Owner-Bestätigung"
log "Archiv : $ARCHIVE"
log "Werkzeug: restore.sh $RESTORE_TOOL_VERSION"
echo

# ── (1) Archive readability + member-path safety ─────────────────────────────
say "1) Archiv öffnen"
[ -e "$ARCHIVE" ] || blocked "Archiv nicht gefunden: $ARCHIVE"
[ -L "$ARCHIVE" ] && blocked "Archiv ist ein SYMLINK — fail-closed."
[ -f "$ARCHIVE" ] || blocked "Archiv ist keine reguläre Datei — fail-closed."
[ -r "$ARCHIVE" ] || blocked "Archiv nicht lesbar (Rechte)."

tar -tzf "$ARCHIVE" >"$LISTING" 2>"$WORK/tar.err" || blocked "Archiv ist kein lesbares tar.gz: $(head -1 "$WORK/tar.err" 2>/dev/null | tr -d '\r')"
[ -s "$LISTING" ] || blocked "Archiv ist leer."
grep -qxF "$ARCHIVE_ROOT/manifest.json" "$LISTING" || blocked "Archiv enthält kein $ARCHIVE_ROOT/manifest.json."
ok "Archiv lesbar, $(wc -l <"$LISTING" | tr -d ' ') Einträge in der Tar-Liste"

# Manifest is a fixed, non-user-controlled member path — safe to extract directly.
tar -xOzf "$ARCHIVE" "$ARCHIVE_ROOT/manifest.json" >"$MANIFEST_JSON" 2>"$WORK/extract.err" \
    || blocked "manifest.json ließ sich nicht aus dem Archiv extrahieren: $(head -1 "$WORK/extract.err" 2>/dev/null)"

# ── (2) Manifest + tar-listing validation — the ONE gate that decides BLOCK/OK ──
say "2) Manifest + Archiv-Layout prüfen (Schema, Formatdecke, Hashes-Ankündigung, Traversal)"
set +e
VALOUT="$(python3 - "$LISTING" "$MANIFEST_JSON" "$ENTRIES" "$EXCLUDED" "$WARNINGS" \
                    "$ARCHIVE_ROOT" "$FORMAT_CEILING" "$CONTRACT_VERSION" "$SCHEMA_CATALOG_VERSION" <<'PY'
import json, sys

(listing_f, manifest_f, entries_out, excluded_out, warnings_out,
 root, ceiling_s, supported_cv_s, supported_scv_s) = sys.argv[1:10]

def block(msg):
    print("BLOCK\t" + msg)
    sys.exit(0)

with open(listing_f, encoding="utf-8") as fh:
    members = [l.rstrip("\n") for l in fh if l.strip()]

for m in members:
    if m != root and not m.startswith(root + "/"):
        block(f"Archiv-Eintrag außerhalb von {root}/: {m}")
    if m.startswith("/"):
        block(f"Archiv-Eintrag mit absolutem Pfad: {m}")
    if any(p == ".." for p in m.split("/")):
        block(f"Archiv-Eintrag mit Pfad-Traversal (..): {m}")

try:
    with open(manifest_f, encoding="utf-8") as fh:
        m = json.load(fh)
except Exception as e:
    block(f"manifest.json ist kein gültiges JSON: {e}")

if not isinstance(m, dict):
    block("manifest.json ist kein JSON-Objekt")
if m.get("contract") != "hoshi-backup":
    block(f"unbekannter contract im Manifest: {m.get('contract')!r}")

try:
    cv = int(m.get("contractVersion"))
except Exception:
    block("contractVersion fehlt/ist keine Zahl")
supported_cv = {int(x) for x in supported_cv_s.split(",") if x}
if cv not in supported_cv:
    block(f"contractVersion {cv} ist diesem Restore-Werkzeug unbekannt (bekannt: {sorted(supported_cv)}) — fail-closed")

scv_raw = m.get("schemaCatalogVersion")
try:
    scv = int(scv_raw)
except Exception:
    block("schemaCatalogVersion fehlt/ist keine Zahl")
supported_scv = {int(x) for x in supported_scv_s.split(",") if x}
if scv not in supported_scv:
    block(f"schemaCatalogVersion {scv} ist diesem Restore-Werkzeug unbekannt — fail-closed")

hv = m.get("hoshiVersion")
if not hv or not isinstance(hv, str):
    block("hoshiVersion im Manifest fehlt/unlesbar")

def parse_ver(v):
    core = v.split("-", 1)[0].split("+", 1)[0]
    return tuple(int(p) for p in core.split("."))

try:
    src_v, ceil_v = parse_ver(hv), parse_ver(ceiling_s)
except Exception:
    block(f"hoshiVersion {hv!r} im Manifest ist keine lesbare Versionsnummer — fail-closed")
w = max(len(src_v), len(ceil_v))
src_v = src_v + (0,) * (w - len(src_v))
ceil_v = ceil_v + (0,) * (w - len(ceil_v))
if src_v > ceil_v:
    block(f"Quell-Version {hv} im Manifest ist NEUER als die Formatdecke {ceiling_s} dieses Restore-Werkzeugs — fail-closed")

entries = m.get("entries")
if not isinstance(entries, list):
    block("entries im Manifest fehlt/ist kein Array")

seen_ids = set()
rows = []
for e in entries:
    if not isinstance(e, dict):
        block("ein entries-Element ist kein Objekt")
    lid = e.get("logicalId")
    status = e.get("status")
    if not lid or not isinstance(lid, str):
        block("ein Eintrag hat keine logicalId")
    if lid in seen_ids:
        block(f"doppelte logicalId im Manifest: {lid}")
    seen_ids.add(lid)
    if status != "INCLUDED" and status != "ABSENT" and not (isinstance(status, str) and status.startswith("SKIPPED")):
        block(f"{lid}: unbekannter status im Manifest: {status!r}")

    artifact = e.get("artifact") or ""
    sha = e.get("sha256")
    bytes_ = e.get("bytes")
    sver = e.get("storeSchemaVersion")
    hint = e.get("sourcePathHint") or ""
    sens = e.get("sensitivity") or ""
    note = e.get("note") or ""

    if status == "INCLUDED":
        if not artifact:
            block(f"{lid}: status INCLUDED ohne artifact-Pfad")
        if artifact.startswith("/") or any(p == ".." for p in artifact.split("/")):
            block(f"{lid}: artifact-Pfad verdächtig (absolut/Traversal): {artifact}")
        if not artifact.startswith(("stores/", "speaker-profiles/", "evidence/")):
            block(f"{lid}: artifact-Pfad außerhalb des erwarteten Layouts: {artifact}")
        if not sha or not isinstance(sha, str) or len(sha) != 64:
            block(f"{lid}: sha256 fehlt/ungültig")
        if not isinstance(bytes_, int) or bytes_ < 0:
            block(f"{lid}: bytes fehlt/ungültig")
        member = f"{root}/{artifact}"
        if member not in members:
            block(f"{lid}: artifact {artifact} steht im Manifest, fehlt aber im Archiv")
        if sver is not None:
            if not isinstance(sver, int):
                block(f"{lid}: storeSchemaVersion ist weder null noch eine Zahl")
            # v1 restore knows exactly one migration-free family: unversioned (null).
            # ANY explicit version is a format this tool has never seen a migration
            # for — refuse rather than guess (contract: "migriert nur über vollständig
            # bekannte, lückenlose Pfade").
            block(f"{lid}: storeSchemaVersion {sver} verlangt eine Migration, die dieses Restore-Werkzeug (v1) nicht kennt — fail-closed")

    rows.append((lid, artifact, str(bytes_) if isinstance(bytes_, int) else "",
                 sha or "", sens, hint, status, "", note))

with open(entries_out, "w", encoding="utf-8") as fh:
    for row in rows:
        fh.write("\t".join(row) + "\n")

excluded = m.get("excluded") or []
with open(excluded_out, "w", encoding="utf-8") as fh:
    for x in excluded:
        if isinstance(x, dict):
            fh.write(f"{x.get('logicalId','')}\t{x.get('reason','')}\t{x.get('detail','')}\n")

warnings = m.get("warnings") or []
with open(warnings_out, "w", encoding="utf-8") as fh:
    for x in warnings:
        if isinstance(x, dict):
            fh.write(f"{x.get('code','')}\t{x.get('detail','')}\n")

consistent = "true" if m.get("consistent") is True else "false"
created = m.get("createdAt", "")
tool_ver = m.get("backupToolVersion", "")
print(f"OK\t{cv}\t{hv}\t{created}\t{tool_ver}\t{consistent}")
PY
)"
PY_RC=$?
set -e
[ "$PY_RC" -eq 0 ] || blocked "Manifest-Validator ist abgestürzt (rc=$PY_RC) — fail-closed."

STATUS_LINE="$(printf '%s\n' "$VALOUT" | tail -1)"
STATUS="$(printf '%s' "$STATUS_LINE" | cut -f1)"
if [ "$STATUS" = "BLOCK" ]; then
    blocked "$(printf '%s' "$STATUS_LINE" | cut -f2-)"
fi
[ "$STATUS" = "OK" ] || blocked "Manifest-Validator lieferte eine unerwartete Antwort — fail-closed."
IFS=$'\t' read -r _ MANIFEST_CV MANIFEST_HOSHI_VERSION MANIFEST_CREATED_AT MANIFEST_TOOL_VERSION MANIFEST_CONSISTENT <<<"$STATUS_LINE"
ok "Manifest gültig — contractVersion=$MANIFEST_CV, hoshiVersion=$MANIFEST_HOSHI_VERSION, erzeugt $MANIFEST_CREATED_AT, gepackt mit backup.sh $MANIFEST_TOOL_VERSION"
if [ "$MANIFEST_CONSISTENT" != "true" ]; then
    printf 'SOURCE_BACKUP_WAS_DEGRADED\tdas Quell-Backup selbst hatte consistent=false — restauriert wird nur, was tatsächlich als INCLUDED im Archiv liegt\n' >>"$WARNINGS"
    degraded
fi

# ── Store catalog: same mapping as backup.sh's plan_add() calls, one direction back ──
# store_meta LOGICAL_ID → sets META_KIND/META_RESOLVER/META_ENVKEY/META_FILE/META_SUBDIR,
# or returns 1 for anything not in this table (contract: unbekannte logische IDs ablehnen).
store_meta() {
    META_KIND=""; META_RESOLVER=""; META_ENVKEY=""; META_FILE=""; META_SUBDIR=""
    case "$1" in
        settings.skills)            META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_SETTINGS_PATH            META_FILE=skills.json ;;
        settings.language)          META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_LANGUAGE_PATH            META_FILE=language.json ;;
        settings.persona)           META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_PERSONA_PATH             META_FILE=persona.json ;;
        settings.tts)                META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_TTS_ENGINE_PATH         META_FILE=tts-engine.json ;;
        settings.brain-model)        META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_BRAIN_MODEL_PATH        META_FILE=brain-model.json ;;
        settings.brain-auto-switch)  META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_BRAIN_AUTO_SWITCH_PATH  META_FILE=brain-auto-switch.json ;;
        settings.lookup-model)       META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_LOOKUP_MODEL_PATH       META_FILE=lookup-model.json ;;
        settings.extended-think)     META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_EXTENDED_THINK_PATH     META_FILE=extended-think.json ;;
        settings.weather-location)   META_KIND=json META_RESOLVER=home META_ENVKEY=HOSHI_WEATHER_LOCATION_PATH   META_FILE=weather-location.json ;;
        settings.night-mode)  META_KIND=json   META_RESOLVER=data META_ENVKEY=HOSHI_NIGHT_MODE_STORE_PATH  META_FILE=night-mode.json ;;
        lists.default)        META_KIND=json   META_RESOLVER=data META_ENVKEY=HOSHI_LIST_STORE_PATH        META_FILE=lists.json ;;
        timers.scheduled)     META_KIND=json   META_RESOLVER=data META_ENVKEY=HOSHI_TIMER_STORE_PATH       META_FILE=scheduled-items.json ;;
        memory.entity)        META_KIND=sqlite META_RESOLVER=home META_ENVKEY=HOSHI_MEMORY_DB_PATH          META_FILE=entity-memory.db ;;
        memory.episodic)      META_KIND=sqlite META_RESOLVER=home META_ENVKEY=HOSHI_MEMORY_EPISODIC_DB_PATH META_FILE=episodic-memory.db ;;
        notes.daily)    META_KIND=jsonl META_RESOLVER=home META_ENVKEY=HOSHI_ANDI_FAKTOR_PATH       META_FILE=andi-faktor.jsonl ;;
        notes.workshop) META_KIND=jsonl META_RESOLVER=home META_ENVKEY=HOSHI_WORKSHOP_NOTE_PATH     META_FILE=werkstatt-notizen.jsonl ;;
        notes.lookup)   META_KIND=jsonl META_RESOLVER=sub  META_ENVKEY=HOSHI_ESCALATION_LOOKUP_PATH META_SUBDIR=lookups META_FILE=nachgeschlagen.jsonl ;;
        biometrics.speaker-profiles) META_KIND=json  META_RESOLVER=data META_ENVKEY=HOSHI_SPEAKER_STORE_PATH META_FILE=speaker-profiles.json ;;
        # HOSHI_TURN_DIARY_DIR overrides a DIRECTORY (backup.sh globs turn-diary-*.jsonl
        # inside it), unlike every other sub_path env var which overrides one FILE.
        # resolve_target's "diarydir" branch resolves the dir, then joins the leaf name.
        evidence.turn-diary.*)       META_KIND=jsonl META_RESOLVER=diarydir META_ENVKEY=HOSHI_TURN_DIARY_DIR META_SUBDIR=diary META_FILE="" ;;
        *) return 1 ;;
    esac
    return 0
}

resolve_target() { # logicalId artifact → sets TARGET; returns 0 ok / 1 unknown id / 2 filename mismatch
    local id="$1" artifact="$2" leaf
    store_meta "$id" || return 1
    leaf="$(basename "$artifact")"
    if [ -n "$META_FILE" ] && [ "$leaf" != "$META_FILE" ]; then
        TARGET=""
        return 2
    fi
    case "$META_RESOLVER" in
        home) TARGET="$(home_path "$META_ENVKEY" "$leaf")" ;;
        data) TARGET="$(data_path "$META_ENVKEY" "$leaf")" ;;
        sub)  TARGET="$(sub_path "$META_ENVKEY" "$META_SUBDIR" "$leaf")" ;;
        diarydir)
            local dir
            dir="$(sub_path "$META_ENVKEY" "$META_SUBDIR" '')"
            dir="${dir%/}"
            TARGET="$dir/$leaf"
            ;;
        *) return 1 ;;
    esac
    return 0
}

# ── (3) Extract + verify every INCLUDED entry BEFORE any target is touched ───────
echo
say "3) Store-Inhalte extrahieren, gegen Manifest-Hash prüfen, strukturell validieren"
TIMER_ORIGIN_COUNT=""
while IFS=$'\t' read -r id artifact bytes sha sens hint status sver note; do
    [ -n "$id" ] || continue

    store_meta "$id" || blocked "$id: unbekannte logicalId — dieses Restore-Werkzeug kennt diesen Store nicht, fail-closed."

    if [ "$status" != "INCLUDED" ]; then
        printf '%s\t%s\t\t\tNO_SOURCE\t%s\t%s\n' "$id" "$META_KIND" "$sens" "$note" >>"$RPLAN"
        log "$status  $id — nichts im Archiv, nichts wird angefasst"
        continue
    fi

    dst="$STAGE/$artifact"
    mkdir -p "$(dirname "$dst")"
    member="$ARCHIVE_ROOT/$artifact"
    tar -xOzf "$ARCHIVE" "$member" >"$dst" 2>"$WORK/x.err" || blocked "$id: Extraktion von $member scheiterte: $(head -1 "$WORK/x.err" 2>/dev/null)"
    chmod 600 "$dst"

    actual_bytes="$(file_bytes "$dst")"
    [ "$actual_bytes" = "$bytes" ] || blocked "$id: Größe weicht ab — Manifest ${bytes} B, Archiv ${actual_bytes} B (manipuliertes/beschädigtes Archiv?)"
    actual_sha="$(sha256_of "$dst")"
    [ "$actual_sha" = "$sha" ] || blocked "$id: SHA-256 weicht ab — Manifest ${sha:0:12}… vs. Archiv ${actual_sha:0:12}… (manipuliertes/beschädigtes Archiv?)"

    case "$META_KIND" in
        json)
            validate_json "$dst" || blocked "$id: extrahiertes JSON ist ungültig — Archiv-Inhalt beschädigt, fail-closed."
            ;;
        jsonl)
            set +e
            validate_jsonl "$dst"; v_rc=$?
            set -e
            [ "$v_rc" -eq 0 ] || blocked "$id: extrahiertes JSONL ist ungültig/unvollständig (rc=$v_rc) — fail-closed."
            ;;
        sqlite)
            command -v sqlite3 >/dev/null 2>&1 || blocked "$id: sqlite3 fehlt auf diesem Host — eine SQLite-Kopie wird nie ungeprüft übernommen."
            [ "$(sqlite3 -readonly "$dst" 'PRAGMA integrity_check;' 2>/dev/null | head -1)" = "ok" ] \
                || blocked "$id: PRAGMA integrity_check auf der extrahierten Kopie war nicht ok — fail-closed."
            ;;
        *)
            blocked "$id: unbekannte Store-Art \"$META_KIND\" — fail-closed."
            ;;
    esac
    ok "$id verifiziert ($META_KIND, $actual_bytes B, sha256:$(printf '%s' "$actual_sha" | cut -c1-12)…)"

    if [ "$id" = "timers.scheduled" ]; then
        TIMER_ORIGIN_COUNT="$(python3 - "$dst" <<'PY' 2>/dev/null || echo "?"
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
    fi

    resolve_target "$id" "$artifact"; rc=$?
    case "$rc" in
        0) ;;
        2) blocked "$id: Dateiname im Archiv ($(basename "$artifact")) passt nicht zum erwarteten Store-Namen ($META_FILE) — fail-closed." ;;
        *) blocked "$id: Zielpfad ließ sich nicht auflösen — fail-closed." ;;
    esac
    TGT="$TARGET"

    ACTION="CREATE"
    NEEDS_OPTIN=false
    case "$id" in
        biometrics.speaker-profiles) $WITH_SPEAKER || NEEDS_OPTIN=true ;;
        evidence.turn-diary.*)       $WITH_DIARY   || NEEDS_OPTIN=true ;;
    esac

    if [ -e "$TGT" ]; then
        if [ -L "$TGT" ]; then blocked "$id: Ziel $TGT ist ein SYMLINK — fail-closed (kein Restore folgt einem Link aus dem Datenverzeichnis)."; fi
        if [ ! -f "$TGT" ]; then blocked "$id: Ziel $TGT ist keine reguläre Datei — fail-closed."; fi
        if [ -r "$TGT" ]; then
            existing_sha="$(sha256_of "$TGT")"
            if [ "$existing_sha" = "$sha" ]; then ACTION="SKIP:UNCHANGED"; else ACTION="REPLACE"; fi
        else
            ACTION="REPLACE"
        fi
    fi
    if $NEEDS_OPTIN && [ "$ACTION" != "SKIP:UNCHANGED" ]; then
        ACTION="SKIP:OPT_IN_REQUIRED"
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$META_KIND" "$artifact" "$TGT" "$ACTION" "$sens" "$note" >>"$RPLAN"
done <"$ENTRIES"

# ── Plan table ─────────────────────────────────────────────────────────────────
echo
say "4) Plan — was mit welcher Aktion wiederhergestellt würde"
printf '  %-32s %-6s %-20s %s\n' "STORE" "TYP" "AKTION" "ZIEL"
CREATE_COUNT=0; REPLACE_COUNT=0; SKIP_SAME_COUNT=0; SKIP_OPTIN_COUNT=0; NO_SOURCE_COUNT=0
while IFS=$'\t' read -r id kind artifact tgt action sens note; do
    [ -n "$id" ] || continue
    printf '  %-32s %-6s %-20s %s\n' "$id" "$kind" "$action" "${tgt:-—}"
    [ -n "$note" ] && printf '      %s%s%s\n' "$C_DIM" "$note" "$C_RESET"
    case "$action" in
        CREATE) CREATE_COUNT=$((CREATE_COUNT + 1)) ;;
        REPLACE) REPLACE_COUNT=$((REPLACE_COUNT + 1)) ;;
        SKIP:UNCHANGED) SKIP_SAME_COUNT=$((SKIP_SAME_COUNT + 1)) ;;
        SKIP:OPT_IN_REQUIRED) SKIP_OPTIN_COUNT=$((SKIP_OPTIN_COUNT + 1)); degraded ;;
        NO_SOURCE) NO_SOURCE_COUNT=$((NO_SOURCE_COUNT + 1)) ;;
    esac
done <"$RPLAN"
echo
log "CREATE=$CREATE_COUNT · REPLACE=$REPLACE_COUNT · unverändert=$SKIP_SAME_COUNT · optional übersprungen=$SKIP_OPTIN_COUNT · ohne Quelle=$NO_SOURCE_COUNT"

if [ -n "$TIMER_ORIGIN_COUNT" ] && [ "$TIMER_ORIGIN_COUNT" != "0" ] && [ "$TIMER_ORIGIN_COUNT" != "?" ]; then
    warn "BROWSER_DEVICE_ID_NOT_RESTORED_COUNT: $TIMER_ORIGIN_COUNT Timer-Eintrag/Einträge mit nichtleerem origin — Klingel-Routing bleibt DEGRADED bis eine Browser-hoshi.deviceId-Zuordnung bestätigt ist. Ein wiederhergestellter Timer-JSON-Eintrag ist KEIN Klingelbeweis (Vertrag §Echter Restore Punkt 7)."
    degraded
fi
printf 'BROWSER_DEVICE_ID_NOT_RESTORED\tTimer tragen in origin die Browser-hoshi.deviceId aus localStorage. Sie ist NICHT im Backup ⇒ Klingel-Routing-Lücke.\n' >>"$WARNINGS"

echo
say "5) Bewusst NICHT wiederherstellbar (aus dem Manifest übernommen)"
while IFS=$'\t' read -r id reason detail; do
    [ -n "$id" ] || continue
    printf '  %s✗%s %-30s %-28s %s\n' "$C_DIM" "$C_RESET" "$id" "$reason" "$detail"
done <"$EXCLUDED"
log "Immer extern, unabhängig vom Manifest: Home-Assistant-Räume, Secrets/TLS-Keys, Browser-localStorage, Modelle/Wissens-DB"

echo
say "6) Warnungen"
while IFS=$'\t' read -r code detail; do
    [ -n "$code" ] || continue
    warn "$code: $detail"
done <"$WARNINGS"

# ── DRY-RUN ends here ───────────────────────────────────────────────────────────
if $DRY_RUN; then
    echo
    ok "DRY-RUN fertig — es wurde NICHTS geschrieben, kein Zielpfad angefasst."
    if [ "$RC" -eq 0 ]; then
        ok "Urteil: VOLLSTÄNDIG RESTAURIERBAR"
    else
        warn "Urteil: RESTAURIERBAR MIT BENANNTEN LÜCKEN (exit 2, s. Warnungen oben)"
    fi
    log "Echter Lauf: bin/hoshi restore \"$ARCHIVE\"$($WITH_SPEAKER && printf ' --with-speaker-profiles')$($WITH_DIARY && printf ' --with-diary')"
    exit "$RC"
fi

# ── (7) Owner confirmation — REAL run only ────────────────────────────────────
echo
say "7) Owner-Bestätigung"
log "Backup-ID (Archiv-Datei) : $(basename "$ARCHIVE")"
log "erzeugt (createdAt)      : $MANIFEST_CREATED_AT"
log "Quell-Hoshi-Version      : $MANIFEST_HOSHI_VERSION (Formatdecke dieses Werkzeugs: $FORMAT_CEILING)"
log "Zielhost                 : $(uname -n) (lokal — dieses Werkzeug kennt kein Remote-Ziel)"
log "geplant                  : CREATE=$CREATE_COUNT · REPLACE=$REPLACE_COUNT · unverändert=$SKIP_SAME_COUNT · optional übersprungen=$SKIP_OPTIN_COUNT"
[ "$RC" -ne 0 ] && warn "Dieser Lauf bleibt EINGESCHRÄNKT (exit 2) — s. Warnungen oben."

if curl -fsS -m 2 -o /dev/null "http://127.0.0.1:${HOSHI_PORT:-8090}/api/health" 2>/dev/null; then
    warn "BACKEND_RUNNING: Auf :${HOSHI_PORT:-8090} antwortet ein Backend. Dieses Skript stoppt NIE selbst einen Dienst — für einen sauberen Restore ein Owner-Wartungsfenster nutzen (Vertrag §Echter Restore Punkt 2)."
fi

CONFIRM="${HOSHI_RESTORE_CONFIRM:-}"
if [ -z "$CONFIRM" ] && [ -t 0 ]; then
    printf '  Restore jetzt ausführen? Zum Fortfahren JA eintippen: '
    read -r CONFIRM || CONFIRM=""
fi
case "$CONFIRM" in
    [jJ][aA]|[yY][eE][sS]) ;;
    *) blocked "Restore ohne Owner-Bestätigung — Abbruch. (Tastatur-JA, oder HOSHI_RESTORE_CONFIRM=ja)" ;;
esac
ok "Owner-Bestätigung erhalten."

# ── (8) Write: pre-restore safety snapshot (move aside) + atomic same-dir rename ──
echo
say "8) Schreiben (vorhandene Ziele → Rollback-Verzeichnis, dann atomarer Rename)"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
WRITE_FAILED=false
FAIL_REASON=""
while IFS=$'\t' read -r id kind artifact tgt action sens note; do
    [ -n "$id" ] || continue
    case "$action" in
        CREATE|REPLACE) ;;
        *) continue ;;
    esac

    staged="$STAGE/$artifact"
    [ -f "$staged" ] || { WRITE_FAILED=true; FAIL_REASON="$id: Staging-Datei $staged nicht gefunden (interner Fehler)"; break; }

    targetdir="$(dirname "$tgt")"
    if ! mkdir -p "$targetdir" 2>"$WORK/mkdir.err"; then
        WRITE_FAILED=true; FAIL_REASON="$id: Zielverzeichnis $targetdir nicht anlegbar: $(head -1 "$WORK/mkdir.err" 2>/dev/null)"
        break
    fi

    if [ "$action" = "REPLACE" ]; then
        rb_dir="$targetdir/.hoshi-restore-rollback-$TS"
        if ! mkdir -p "$rb_dir" 2>"$WORK/mkdir.err"; then
            WRITE_FAILED=true; FAIL_REASON="$id: Rollback-Verzeichnis $rb_dir nicht anlegbar"
            break
        fi
        chmod 700 "$rb_dir" 2>/dev/null || true
        rb_path="$rb_dir/$(basename "$tgt")"
        if ! mv -- "$tgt" "$rb_path" 2>"$WORK/mv.err"; then
            WRITE_FAILED=true; FAIL_REASON="$id: bestehendes Ziel $tgt ließ sich nicht ins Rollback-Verzeichnis verschieben: $(head -1 "$WORK/mv.err" 2>/dev/null)"
            break
        fi
        printf '%s\t%s\n' "$tgt" "$rb_path" >>"$ROLLBACK_MAP"
    fi

    tmp_target="$targetdir/.hoshi-restore-tmp.$$.$(basename "$tgt")"
    if ! cp "$staged" "$tmp_target" 2>"$WORK/cp.err"; then
        WRITE_FAILED=true; FAIL_REASON="$id: Kopie nach $tmp_target scheiterte: $(head -1 "$WORK/cp.err" 2>/dev/null)"
        break
    fi
    chmod 600 "$tmp_target"
    if ! mv -- "$tmp_target" "$tgt" 2>"$WORK/mv2.err"; then
        WRITE_FAILED=true; FAIL_REASON="$id: atomarer Rename nach $tgt scheiterte: $(head -1 "$WORK/mv2.err" 2>/dev/null)"
        rm -f -- "$tmp_target" 2>/dev/null || true
        break
    fi
    [ "$action" = "CREATE" ] && printf '%s\n' "$tgt" >>"$NEW_CREATES"
    ok "$id → $tgt ($action)"
done <"$RPLAN"

if $WRITE_FAILED; then
    fail "$FAIL_REASON"
    rollback_all
    echo
    fail "Urteil: FEHLGESCHLAGEN — Schreibfehler, alle bereits gemachten Änderungen wurden zurückgetauscht."
    exit 10
fi

# ── (9) Summary ─────────────────────────────────────────────────────────────────
echo
say "9) Abschluss"
log "wiederhergestellt: CREATE=$CREATE_COUNT · REPLACE=$REPLACE_COUNT · unverändert (übersprungen)=$SKIP_SAME_COUNT · optional übersprungen=$SKIP_OPTIN_COUNT"
if [ -n "$TIMER_ORIGIN_COUNT" ] && [ "$TIMER_ORIGIN_COUNT" != "0" ] && [ "$TIMER_ORIGIN_COUNT" != "?" ]; then
    warn "Timer-Teilstatus: DEGRADED — $TIMER_ORIGIN_COUNT Eintrag/Einträge mit origin ohne bestätigte Browser-deviceId-Zuordnung. Kein Klingelbeweis."
fi
$WITH_SPEAKER && log "Sprecherprofile (falls im Archiv vorhanden) wiederhergestellt — Recognition/Trust bleiben AUS bis das Holdout-Gate erneut besteht und der Owner beide Flags freigibt (Vertrag §Sprecherprofile). Dieses Skript flippt KEINE Recognition/Trust-Schalter."
log "Extern/nicht durch dieses Werkzeug rekonstruierbar: Home-Assistant-Räume, Secrets, Browser-localStorage (inkl. hoshi.deviceId), Modelle/Wissens-DB."
log "Nächster Schritt (nicht Teil dieses Skripts): bin/hoshi doctor, danach read-only Fachproben für Settings/Listen/Timer/Memory (Vertrag §Echter Restore Punkt 6)."
if [ "$RC" -eq 0 ]; then
    ok "Urteil: RESTORE GRÜN"
else
    warn "Urteil: RESTORE EINGESCHRÄNKT — s. Warnungen oben (exit 2)"
fi
exit "$RC"
