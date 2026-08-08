#!/usr/bin/env bash
# pipeline/publish-satellite.sh — der wiederholbare Export-Weg für hoshi-satellite.
#
# HINTERGRUND: Das öffentliche Repo AndiGermany/hoshi-satellite entstand am
# 08.08.2026 als EINMALIGE Handarbeit aus Hoshi_0.5/hoshi-satellite (2 Commits:
# „initial public release" + ein README-Fix). Seitdem läuft die private Quelle
# weiter (neue Dateien, geänderte Firmware, getunte Wake-Word-Kalibrierung) und
# driftet vom veröffentlichten Stand weg — genau das hat der Audit gefunden.
# Dieses Skript ist der GESCHWISTER-Weg zu publish-github.sh (bitte dort zuerst
# lesen): gleiches Grundmuster (Export-Menge · blockierender Sanitize-Scan ·
# eigener Klon mit eigener History · Stopp vor dem Push), aber mit einem
# Unterschied, den die Satelliten-Quelle erzwingt:
#
#   publish-github.sh spiegelt sein Quell-Repo 1:1 (minus vier Verzeichnisse) —
#   Docs UND Code kommen von dort, Docs sind Teil der Export-Menge.
#
#   Dieses Skript NICHT: das öffentliche hoshi-satellite-Repo hat seine Doku
#   (**/*.md) bei der Handarbeit ins Englische übersetzt UND redigiert bekommen
#   (siehe README.md, docs/DECISIONS.md, docs/ARCHITECTURE.md — eigenständige
#   Prosa, keine mechanische Übersetzung). Ein Sync würde diese Arbeit jedes
#   Mal überschreiben. Deshalb ZWEI KLASSEN:
#
#   (a) TECHNIK (firmware/**, tools-taugliche Skripte — alles außer *.md):
#       wird SYNCHRONISIERT, mit einer sed/perl-TRANSFORM-TABELLE (§TRANSFORMS
#       unten) gegen bekannte Leck-Muster (Heim-IP, Hostname, Zertifikat,
#       Klarnamen).
#   (b) DOKU (**/*.md): wird NIE überschrieben. Stattdessen: eine
#       Drift-WARNUNG, wenn die Quelldatei sich seit der letzten Prüfung
#       geändert hat — „geh hin und schau, ob die public-Fassung noch stimmt".
#
# ABLAUF:
#   (1) Vorbedingungen (gh auth · perl · Quelle vorhanden & git-Subtree)
#   (2) Export-Menge = git ls-files des Subtrees, klassifiziert in TECH/DOC/
#       unbekannt
#   (3) Staging: TECH-Dateien mit Transform-Pipeline in ein Arbeitsverzeichnis
#       schreiben; CI-Artefakt (.github/workflows/esphome-ci.yml) generieren
#   (4) Sanitize-Scan über das Staging — BLOCKIEREND, wie bei publish-github.sh
#   (5) Publish-Klon vorbereiten (gh clone/fetch, hart auf origin/master)
#   (6) Diff Staging↔Klon zeigen; ohne --dry-run: in den Klon kopieren
#       (überschreibt NUR die TECH-Zielpfade + CI-Datei; löscht nichts —
#       siehe LIMITATION unten)
#   (7) Doku-Drift-Check (Hash-Baseline in .pipeline/, gitignored)
#   (8) STOPP. Es wird NIE committet oder gepusht — das bleibt Handarbeit,
#       die Befehle dafür werden nur ausgegeben.
#
# WARUM KEIN COMMIT/PUSH (anders als publish-github.sh)? Diese Quelle hat immer
# TECH+DOC-Drift gleichzeitig; ein Commit vor der manuellen Sichtung der
# Doku-Drift-Warnungen würde genau den Automatismus einführen, den die
# Doku-Klasse bewusst vermeidet. Der Klon bleibt also nach einem echten Lauf
# UNGECOMMITTET liegen — Difference selbst ansehen, dann von Hand committen.
#
# SELBSTBEZUG (wichtig): dieses Skript liegt in pipeline/ und wird von
# publish-github.sh selbst mit nach GitHub (AndiGermany/hoshi-0.8) exportiert.
# Deshalb genau wie publish-github.sh: KEINE Klarnamen im Quelltext (Namen
# kommen zur Laufzeit aus der gitignorierten NAMES_FILE) und KEIN hart
# codierter /Users/…-Pfad (Pfade sind relativ zu diesem Repo bzw. per Env
# überschreibbar).
#
# LIMITATIONS (siehe auch Rückgabe des Bau-Pods, der das hier gebaut hat):
#   - Die Transform-Tabelle ist MECHANISCH, keine Übersetzung/Redaktion. Sie
#     deckt die in der Aufgabe genannten Kategorien ab (IP, ct-106, Zertifikat,
#     Vor-/Klarnamen) — interner Jargon („0.8-CUTOVER", „server-hand", Lauf-Daten)
#     bleibt in TECH-Dateien stehen. Der Sanitize-Scan fängt nur, was er kennt.
#   - Kein Löschen im Klon: wenn eine TECH-Datei in der Quelle verschwindet,
#     bleibt ihr altes Gegenstück im Klon liegen (anders als publish-github.sh,
#     das den ganzen Baum spiegelt). Grund: TECH- und DOC-Dateien leben im
#     selben Zielbaum, ein Voll-Mirror würde kuratierte Docs mit-löschen.
#   - Doku-Mapping für Root-/docs-/firmware-Root-Dateien ist eine von Hand
#     abgeleitete Tabelle (DOC_EXPLICIT_MAP unten), keine Automatik — bei
#     Umbenennungen im Public-Repo von Hand nachziehen.
#
# Aufruf:
#   bash pipeline/publish-satellite.sh              # echter Lauf (kein Push, kein Commit)
#   bash pipeline/publish-satellite.sh --dry-run     # bis inkl. Scan, Klon bleibt unangetastet
#
# Exit-Codes:
#   0 = sauber (Scan grün, keine Doku-Drift, nichts Unklassifiziertes)
#   1 = harter Vorbedingungs-Fehler (gh/auth/perl/Quelle fehlt, Klon falsch verkabelt, …)
#   2 = Scan GRÜN, aber Warnungen vorhanden (Doku-Drift und/oder unklassifizierte Dateien)
#   3 = Sanitize-Scan ROT — nichts wurde in den Klon geschrieben

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# ─────────────────────────────────────────────────────────────────────────────
# Konstanten
# ─────────────────────────────────────────────────────────────────────────────

PUBLIC_REPO="AndiGermany/hoshi-satellite"
PUBLIC_BRANCH="master"

# Quelle: git-Subtree in EINEM ANDEREN Repo (Hoshi_0.5). Relativ zu diesem
# Repo (Geschwister-Verzeichnis) statt hart codiertem /Users/…-Pfad —
# überschreibbar, falls die Verzeichnisse auf einer anderen Maschine anders
# liegen.
SRC_ROOT="${HOSHI_SATELLITE_SRC:-$REPO_ROOT/../Hoshi_0.5/hoshi-satellite}"

# Publish-Klon: ein Arbeitsverzeichnis unter .pipeline/ (bereits gitignored,
# siehe .gitignore-Eintrag „.pipeline/"). Bewusst NICHT im Baum dieses Repos,
# damit er nie in eine Export-Menge irgendeines Skripts gerät.
PUBLIC_CLONE="${HOSHI_SATELLITE_PUBLIC_CLONE:-$PIPELINE_LOG_DIR/hoshi-satellite-public}"

# Klarnamen-Liste: EXAKT dieselbe Datei/Konvention wie publish-github.sh
# (bewusst geteilt — eine Liste, zwei Skripte, kein zweiter Pflegeort).
# Warum die Namen nicht hier im Skript stehen: siehe Kopfkommentar „SELBSTBEZUG".
NAMES_FILE="${HOSHI_SANITIZE_NAMES_FILE:-$PIPELINE_LOG_DIR/sanitize-names-satellite.txt}"

# Doku-Drift-Baseline: Hash je Quell-.md-Datei vom letzten Lauf (gitignored).
DOC_BASELINE_FILE="$PIPELINE_LOG_DIR/publish-satellite-doc-baseline.tsv"

PLACEHOLDER_CERT="REPLACE-WITH-YOUR-OWN-SERVER-LEAF-CERT-BASE64-LINES"

# ─────────────────────────────────────────────────────────────────────────────
# Argumente
# ─────────────────────────────────────────────────────────────────────────────

DRY_RUN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help)
            sed -n '2,80p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) fail "Unbekanntes Argument: $1"; exit 1 ;;
    esac
    shift
done

echo
say "${C_BOLD}hoshi-satellite — Publish-Vorbereitung ($PUBLIC_REPO)${C_RESET}"
log "Modus: $([ "$DRY_RUN" = 1 ] && echo 'TROCKENLAUF (Klon bleibt unangetastet)' || echo 'ECHTER LAUF (schreibt in den Klon — committet/pusht NICHT)')"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (1) Vorbedingungen
# ─────────────────────────────────────────────────────────────────────────────

say "(1) Vorbedingungen"

if ! command -v gh >/dev/null 2>&1; then
    fail "'gh' (GitHub CLI) nicht installiert — ohne sie kein Klon."
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    fail "'gh auth status' ist rot — nicht bei GitHub angemeldet."
    log "Anmelden mit: gh auth login"
    exit 1
fi
GH_USER="$(gh api user --jq .login 2>/dev/null || echo '?')"
ok "gh authentifiziert (Account: $GH_USER)"

if ! command -v perl >/dev/null 2>&1; then
    fail "'perl' fehlt — die Transform-Pipeline braucht es (macOS bringt es mit)."
    exit 1
fi
ok "perl vorhanden"

if [ ! -d "$SRC_ROOT" ]; then
    fail "Quelle nicht gefunden: $SRC_ROOT"
    log "Überschreibbar per HOSHI_SATELLITE_SRC=<pfad>."
    exit 1
fi
SRC_GIT_ROOT="$(cd "$SRC_ROOT" && git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$SRC_GIT_ROOT" ]; then
    fail "$SRC_ROOT liegt in keinem git-Repo — 'git ls-files' (die Wahrheit für die Export-Menge) geht nicht."
    exit 1
fi
SRC_SUBTREE_REL="$(cd "$SRC_ROOT" && git rev-parse --show-prefix 2>/dev/null | sed 's:/$::')"
if [ -z "$SRC_SUBTREE_REL" ]; then
    fail "Konnte den Subtree-Pfad von $SRC_ROOT relativ zu $SRC_GIT_ROOT nicht bestimmen."
    exit 1
fi
ok "Quelle: $SRC_GIT_ROOT (Subtree: $SRC_SUBTREE_REL/) — NUR LESEND, kein git-Schreibbefehl in diesem Repo"

# NAMES_FILE — fail closed wie publish-github.sh: ohne Liste kein Klarnamen-Check,
# ohne Klarnamen-Check keine Veröffentlichung.
if [ -n "${HOSHI_SANITIZE_NAMES:-}" ]; then
    NAMES_RAW="$(echo "$HOSHI_SANITIZE_NAMES" | tr ',' '\n' | sed '/^[[:space:]]*$/d')"
elif [ -f "$NAMES_FILE" ]; then
    NAMES_RAW="$(grep -v -E '^[[:space:]]*(#|$)' "$NAMES_FILE" || true)"
else
    fail "Namensliste fehlt: ${NAMES_FILE#$REPO_ROOT/}"
    log "Dieselbe Datei wie publish-github.sh — siehe deren Anlegen-Hinweis."
    exit 1
fi
if [ -z "${NAMES_RAW// /}" ]; then
    fail "Namensliste ist leer — der Klarnamen-Check wäre wirkungslos."
    exit 1
fi
# kein mapfile (bash 3.2 auf macOS hat es nicht) — portable while-read-Schleife.
NAMES_ARR=()
while IFS= read -r n; do
    [ -n "$n" ] && NAMES_ARR+=("$n")
done <<< "$NAMES_RAW"
ok "Klarnamen-Liste geladen (${#NAMES_ARR[@]} Einträge, Werte werden nie ausgegeben)"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (2) Export-Menge + Klassifizierung
# ─────────────────────────────────────────────────────────────────────────────

say "(2) Export-Menge bilden + klassifizieren"

EXPORT_LIST="$(mktemp -t hoshi-publish-satellite-export)"
STAGING_DIR="$(mktemp -d -t hoshi-publish-satellite-staging)"
cleanup() { rm -f "$EXPORT_LIST"; rm -rf "$STAGING_DIR"; }
trap cleanup EXIT

git -C "$SRC_GIT_ROOT" ls-files -- "$SRC_SUBTREE_REL" \
    | sed "s#^$SRC_SUBTREE_REL/##" > "$EXPORT_LIST"
EXPORT_COUNT="$(wc -l < "$EXPORT_LIST" | tr -d ' ')"
ok "$EXPORT_COUNT getrackte Dateien im Subtree"

# ── Verzeichnis-Mapping TECHNIK (Erst-Export-Struktur, im Klon geprüft) ──────
#   bridge/  -> tools/bridge/
#   wake/    -> tools/wake/
#   firmware/esphome/ -> firmware/esphome/ (Pfad bleibt gleich)
# Alles andere (Root-Dateien, docs/, firmware/*.md direkt, measurements/) hat
# keine Verzeichnisregel — diese Pfade sind ausnahmslos *.md (siehe unten) und
# laufen über DOC_EXPLICIT_MAP statt über eine Pfadregel.
map_tech_dest() {
    local rel="$1"
    case "$rel" in
        bridge/*)           echo "tools/${rel}" ;;
        wake/*)              echo "tools/${rel}" ;;
        firmware/esphome/*)  echo "$rel" ;;
        *)                   echo "" ;;
    esac
}

# Dateien, die es bewusst NICHT in den Sync schaffen, obwohl sie unter einer
# obigen Verzeichnisregel lägen: .gitignore-Dateien sind je Repo eigenständig
# (das öffentliche Repo hat sein *eigenes* .gitignore, gewachsen aus seinen
# eigenen Build-/Ignore-Bedürfnissen — ein Sync von hier würde es überschreiben
# ohne Mehrwert; geprüft: die 2 Einträge existieren im Public-Repo bereits in
# dessen eigenem Top-Level-.gitignore).
is_skip_path() {
    case "$1" in
        .gitignore|firmware/esphome/.gitignore) return 0 ;;
        *) return 1 ;;
    esac
}

# ── Doku-Mapping (best-effort, von Hand aus dem Diff Erst-Export↔Quelle
#    abgeleitet — siehe Kopfkommentar). Als Funktion statt assoziativem Array:
#    bash 3.2 (macOS-Systemstand, kein declare -A) — derselbe Stil wie
#    map_tech_dest() oben. "__EXCLUDED__" = bewusst kein Public-Gegenstück
#    (rein interne Datei, nie veröffentlicht); "" = nicht explizit gelistet,
#    Verzeichnisregel (map_tech_dest) entscheidet. ─────────────────────────────
doc_explicit_map() {
    case "$1" in
        "README.md") echo "README.md" ;;
        "CONTRACT.md") echo "docs/PROTOCOL.md" ;;
        "docs/decisions.md") echo "docs/DECISIONS.md" ;;
        "firmware/BIG-TEST-PLAN.md") echo "firmware/TESTPLAN.md" ;;
        "firmware/IDEAS-edge.md") echo "docs/IDEAS.md" ;;
        "firmware/PROTOCOL.md") echo "docs/PROTOCOL.md" ;;
        "firmware/README.md") echo "firmware/README.md" ;;
        "firmware/RUNBOOK.md") echo "firmware/RUNBOOK.md" ;;
        "firmware/recovery/README.md") echo "firmware/recovery/README.md" ;;
        # unsicher/best-effort: Inhalt ähnelt docs/ARCHITECTURE.md, aber die
        # Zuordnung ist nicht byte-belegt wie die anderen Zeilen — von Hand
        # geprüft, nicht automatisch abgeleitet.
        "firmware/REDESIGN-2026-06-21.md") echo "docs/ARCHITECTURE.md" ;;
        "measurements/README.md") echo "docs/MEASUREMENTS.md" ;;
        # HANDOFF ist die Übergabe-Notiz zwischen Agenten-Sitzungen dieses privaten
        # Repos — nie im Public-Repo, wird nie eins haben. Kein Drift-Check nötig
        # (die Drift-Schleife unten überspringt diesen Dateinamen zusätzlich explizit).
        "HANDOFF-satellite-hand.md") echo "__EXCLUDED__" ;;
        *) echo "" ;;
    esac
}

map_doc_dest() {
    local rel="$1" explicit
    explicit="$(doc_explicit_map "$rel")"
    if [ "$explicit" = "__EXCLUDED__" ]; then
        echo ""
        return
    fi
    if [ -n "$explicit" ]; then
        echo "$explicit"
        return
    fi
    map_tech_dest "$rel"
}

TECH_FILES=()      # rel:dest Paare
DOC_FILES=()        # rel:dest Paare (dest kann leer sein = kein Gegenstück)
UNCLASSIFIED=()

while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    if is_skip_path "$rel"; then
        continue
    fi
    case "$rel" in
        *.md)
            dest="$(map_doc_dest "$rel")"
            DOC_FILES+=("$rel:$dest")
            ;;
        *)
            dest="$(map_tech_dest "$rel")"
            if [ -n "$dest" ]; then
                TECH_FILES+=("$rel:$dest")
            else
                UNCLASSIFIED+=("$rel")
            fi
            ;;
    esac
done < "$EXPORT_LIST"

ok "${#TECH_FILES[@]} TECH-Dateien (werden synchronisiert), ${#DOC_FILES[@]} DOC-Dateien (nur Drift-Check)"
if [ "${#UNCLASSIFIED[@]}" -gt 0 ]; then
    warn "${#UNCLASSIFIED[@]} Datei(en) weder TECH noch DOC zugeordnet — werden NICHT synchronisiert:"
    printf '%s\n' "${UNCLASSIFIED[@]}" | sed 's/^/      ? /'
    log "Neu in der Quelle aufgetaucht? map_tech_dest()/DOC_EXPLICIT_MAP in diesem Skript ergänzen."
fi
echo

# ─────────────────────────────────────────────────────────────────────────────
# (3) Staging: TECH-Dateien transformieren, CI-Artefakt generieren
# ─────────────────────────────────────────────────────────────────────────────

say "(3) Staging + Transform-Pipeline"

# ── TRANSFORM-TABELLE (mechanisch, sed/perl — siehe Kopfkommentar LIMITATIONS) ─
#   a) CT106_LEAF_PEM             -> SERVER_LEAF_PEM        (Symbol-Umbenennung)
#   b) eingebettetes Zertifikat   -> PLACEHOLDER_CERT        (Byte-Body ersetzt,
#                                                              Anführungsstil erhalten)
#   c) ct-106 (jede Schreibweise) -> hoshi-server             (Hostname-Token)
#   d) 192.168.178.106            -> 192.0.2.10               (RFC-5737-Doku-IP)
#   e) Klarname[0] (+ "-gate(d)"/"-GO"/"-Befund"/Genitiv)
#                                  -> hardware-step / hardware-gated /
#                                     maintainer sign-off / field finding /
#                                     the maintainer('s)
#   f) Klarname[1..]               -> a household member('s)
# JEDE andere 192.168.*-Adresse wird NICHT transformiert (nur die bekannte
# Heim-IP ist eine Regel) — taucht eine andere auf, soll der Scan unten darauf
# schlagen, nicht eine Transform-Regel sie leise wegbügeln.
export HOSHI_TXNAME_PRIMARY="${NAMES_ARR[0]:-}"
if [ "${#NAMES_ARR[@]}" -gt 1 ]; then
    export HOSHI_TXNAME_OTHERS="$(printf '%s\x1f' "${NAMES_ARR[@]:1}")"
else
    export HOSHI_TXNAME_OTHERS=""
fi

transform_file() {
    local src="$1" dst="$2"
    mkdir -p "$(dirname "$dst")"
    perl -0777 -pe '
        BEGIN { our $PLACEHOLDER = "'"$PLACEHOLDER_CERT"'"; }

        # a) Symbol-Umbenennung
        s/\bCT106_LEAF_PEM\b/SERVER_LEAF_PEM/g;

        # b) eingebettetes Zertifikat einsammeln, Anführungsstil der BEGIN-Zeile
        #    beibehalten (die C++-Komponente quotet jede PEM-Zeile einzeln).
        s{
            ^([ \t]*"?-----BEGIN[ \t]+[A-Z ]*CERTIFICATE-----.*\n)
            (?:.*\n)*?
            ([ \t]*"?-----END[ \t]+[A-Z ]*CERTIFICATE-----.*\n)
        }{
            my ($beg,$end) = ($1,$2);
            my $mid = ($beg =~ /^([ \t]*)"/) ? "$1\"$PLACEHOLDER\\n\"\n" : "$PLACEHOLDER\n";
            $beg . $mid . $end;
        }xemg;

        # c) Hostname-Token, unabhängig von Groß/Klein
        s/ct-106/hoshi-server/gi;

        # d) genau die bekannte Heim-IP (keine andere — siehe Kommentar oben)
        s/192\.168\.178\.106/192.0.2.10/g;

        # e/f) Klarnamen -> neutral. Werte NUR aus der Umgebung (NAMES_FILE),
        #      nie als Literal in diesem Skript (Selbstbezug, siehe Kopf).
        my $primary = $ENV{HOSHI_TXNAME_PRIMARY} // "";
        if (length $primary) {
            my $qp = quotemeta($primary);
            s/\b$qp-gated\b/hardware-gated/gi;
            s/\b$qp-gate\b/hardware-step/gi;
            s/\b$qp-GO\b/maintainer sign-off/gi;
            s/\b$qp-Befund\b/field finding/gi;
            s/\b$qp\x27s\b/the maintainer\x27s/gi;
            s/\b$qp\b/the maintainer/gi;
        }
        my @others = length($ENV{HOSHI_TXNAME_OTHERS}//"") ? split(/\x1f/, $ENV{HOSHI_TXNAME_OTHERS}) : ();
        for my $n (@others) {
            next unless length $n;
            my $qn = quotemeta($n);
            s/\b$qn\x27s\b/a household member\x27s/gi;
            s/\b$qn\b/a household member/gi;
        }
    ' "$src" > "$dst"
}

if [ "${#TECH_FILES[@]}" -gt 0 ]; then
    for pair in "${TECH_FILES[@]}"; do
        rel="${pair%%:*}"; dest="${pair#*:}"
        transform_file "$SRC_ROOT/$rel" "$STAGING_DIR/$dest"
    done
fi
ok "${#TECH_FILES[@]} TECH-Dateien transformiert nach $STAGING_DIR"

# ── CI-Artefakt: .github/workflows/esphome-ci.yml ────────────────────────────
# Von diesem Skript AUTORISCH generiert (kein Quell-Gegenstück) — bei jedem
# Lauf neu geschrieben. Liest den esphome-Pin zur CI-LAUFZEIT aus
# hoshi-voice-pe.yaml's `esphome: min_version:` (aktuell in der Quelle NICHT
# gesetzt — siehe Rückgabe des Bau-Pods; der Job floated dann bewusst sichtbar
# auf "latest" statt eine erfundene Version reinzuschreiben).
mkdir -p "$STAGING_DIR/.github/workflows"
cat <<'YAML_EOF' > "$STAGING_DIR/.github/workflows/esphome-ci.yml"
# .github/workflows/esphome-ci.yml — managed by pipeline/publish-satellite.sh
# in the private source repo. Regenerated on every publish run; hand edits
# here will be overwritten by the next sync (edit the generator instead).
name: ESPHome firmware CI

on:
  push:
    paths:
      - "firmware/esphome/**"
      - ".github/workflows/esphome-ci.yml"
  pull_request:
    paths:
      - "firmware/esphome/**"
      - ".github/workflows/esphome-ci.yml"
  workflow_dispatch:

jobs:
  compile:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: firmware/esphome
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: "3.11"

      - name: Read esphome min_version pin from the config
        id: pin
        run: |
          PIN="$(grep -m1 -E '^[[:space:]]*min_version:' hoshi-voice-pe.yaml | sed -E 's/^[^:]*:[[:space:]]*//; s/"//g' | tr -d '\r' | xargs || true)"
          echo "pin=$PIN" >> "$GITHUB_OUTPUT"
          if [ -n "$PIN" ]; then
            echo "Pinned via esphome.min_version: $PIN"
          else
            echo "::warning::hoshi-voice-pe.yaml has no esphome.min_version — installing latest esphome (unpinned). Consider adding one so CI tracks a known-good version."
          fi

      - name: Install esphome
        run: |
          if [ -n "${{ steps.pin.outputs.pin }}" ]; then
            pip install "esphome==${{ steps.pin.outputs.pin }}"
          else
            pip install esphome
          fi
          esphome version

      - name: Stub secrets.yaml for CI (no real credentials exist here)
        run: |
          if [ -f secrets.yaml.example ]; then
            cp secrets.yaml.example secrets.yaml
          else
            echo "::warning::secrets.yaml.example is missing — compile will likely fail on !secret lookups."
          fi

      - name: esphome config (schema + substitution check)
        run: esphome config hoshi-voice-pe.yaml

      - name: esphome compile
        run: esphome compile hoshi-voice-pe.yaml
YAML_EOF
TECH_FILES+=(".github/workflows/esphome-ci.yml (generiert):.github/workflows/esphome-ci.yml")
ok "CI-Artefakt generiert: .github/workflows/esphome-ci.yml"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (4) Sanitize-Scan über das Staging — BLOCKIEREND
# ─────────────────────────────────────────────────────────────────────────────

say "(4) Sanitize-Scan über das Staging"

SCAN_HITS=0

run_scan_rule() {
    local rule="$1" pattern="$2" icase="${3:-0}"
    local flags="-rInE"
    [ "$icase" = 1 ] && flags="-rInIiE"
    local hits=0
    while IFS= read -r entry; do
        [ -z "$entry" ] && continue
        local file="${entry%%:*}"; local rest="${entry#*:}"
        local lineno="${rest%%:*}"
        fail "[$rule] ${file#$STAGING_DIR/}:$lineno"
        hits=$((hits + 1))
    done < <(grep $flags "$pattern" "$STAGING_DIR" 2>/dev/null || true)
    if [ "$hits" -gt 0 ]; then
        SCAN_HITS=$((SCAN_HITS + hits))
    else
        ok "$rule — sauber"
    fi
}

# Jede 192.168.*-Adresse blockiert (nicht nur die bekannte Heim-IP — die ist ja
# schon transformiert; taucht hier noch eine auf, ist entweder die Transform-Regel
# kaputt oder eine ANDERE LAN-Adresse ist neu in die Quelle gerutscht).
run_scan_rule "lan-ip" '192\.168\.[0-9]{1,3}\.[0-9]{1,3}'
run_scan_rule "ct-106-residual" 'ct-106' 1
run_scan_rule "mac-address" '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}'
run_scan_rule "sha256-fingerprint" '([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}'
run_scan_rule "abs-user-path" '/Users/[A-Za-z0-9_.-]+/'
# Zusätzlich zur Aufgabenstellung (billig, kein False-Positive-Risiko hier):
# echtes Schlüsselmaterial fängt keine Kategorie oben ab.
run_scan_rule "privkey" '(-----BEGIN [A-Z ]*PRIVATE KEY|ssh-rsa AAAA|ssh-ed25519 AAAA)'

# Klarnamen — dieselbe Liste, dieselbe Konvention wie publish-github.sh.
NAME_RE="\\b($(printf '%s\n' "${NAMES_ARR[@]}" | paste -sd'|' -))\\b"
run_scan_rule "klarname" "$NAME_RE" 1

# BEGIN CERTIFICATE mit echten Bytes: jede BEGIN-Zeile MUSS von der
# PLACEHOLDER-Zeile gefolgt werden (Ergebnis der Transform-Regel b oben) —
# alles andere heißt, echte Zertifikats-Bytes haben die Collapse-Regex nicht
# getroffen (z.B. weil eine Datei außerhalb der Sync-Map ein Zertifikat trägt).
CERT_HITS=0
while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    file="${hit%%:*}"; rest="${hit#*:}"; lineno="${rest%%:*}"
    nextline="$(sed -n "$((lineno + 1))p" "$file")"
    if [[ "$nextline" != *"$PLACEHOLDER_CERT"* ]]; then
        fail "[cert-bytes] ${file#$STAGING_DIR/}:$lineno — auf BEGIN CERTIFICATE folgen keine Platzhalter-Bytes"
        CERT_HITS=$((CERT_HITS + 1))
    fi
done < <(grep -rInE 'BEGIN[ \t]+[A-Z ]*CERTIFICATE-----' "$STAGING_DIR" 2>/dev/null || true)
if [ "$CERT_HITS" -gt 0 ]; then
    SCAN_HITS=$((SCAN_HITS + CERT_HITS))
else
    ok "cert-bytes — sauber"
fi

if [ "$SCAN_HITS" -gt 0 ]; then
    echo
    fail "SANITIZE-SCAN ROT — $SCAN_HITS Treffer. Es wird NICHTS in den Klon geschrieben."
    log "Werte werden bewusst nicht gedruckt. Quelle an der genannten Stelle prüfen —"
    log "entweder die TRANSFORM-TABELLE oben erweitern oder die Quelldatei von Hand fixen."
    exit 3
fi
ok "Sanitize-Scan GRÜN"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (5) Publish-Klon vorbereiten
# ─────────────────────────────────────────────────────────────────────────────

say "(5) Publish-Klon: $PUBLIC_CLONE"

mkdir -p "$(dirname "$PUBLIC_CLONE")"
if [ ! -d "$PUBLIC_CLONE/.git" ]; then
    if [ -e "$PUBLIC_CLONE" ]; then
        fail "$PUBLIC_CLONE existiert, ist aber kein git-Repo. Bitte von Hand klären."
        exit 1
    fi
    log "Klon existiert nicht — hole $PUBLIC_REPO (read-only: nur clone, kein push) …"
    gh repo clone "$PUBLIC_REPO" "$PUBLIC_CLONE" -- --quiet
    ok "Geklont"
else
    log "Klon existiert — hole den Remote-Stand (read-only: nur fetch) …"
    git -C "$PUBLIC_CLONE" fetch --quiet origin
    ok "gefetcht"
fi

PUBLIC_CLONE="$(cd "$PUBLIC_CLONE" && pwd)"

# Sicherheitsnetz wie publish-github.sh: der Klon darf niemals ein privates
# Repo als Remote kennen.
if git -C "$PUBLIC_CLONE" remote -v | grep -qiE 'gitea|Hoshi_0\.[58]|Hoshi_0\.5-satellite'; then
    fail "Der Publish-Klon hat ein Remote auf ein PRIVATES Repo. Abbruch."
    git -C "$PUBLIC_CLONE" remote -v | sed 's/^/      /'
    exit 1
fi
CLONE_ORIGIN="$(git -C "$PUBLIC_CLONE" remote get-url origin)"
case "$CLONE_ORIGIN" in
    *github.com*"$PUBLIC_REPO"*|*github.com*"$(basename "$PUBLIC_REPO")"*) ;;
    *) fail "origin des Klons zeigt nicht auf $PUBLIC_REPO: $CLONE_ORIGIN"; exit 1 ;;
esac
ok "origin: $CLONE_ORIGIN"

git -C "$PUBLIC_CLONE" checkout --quiet "$PUBLIC_BRANCH" 2>/dev/null || \
    git -C "$PUBLIC_CLONE" checkout --quiet -b "$PUBLIC_BRANCH" "origin/$PUBLIC_BRANCH"
git -C "$PUBLIC_CLONE" reset --hard --quiet "origin/$PUBLIC_BRANCH"
git -C "$PUBLIC_CLONE" clean -qfdx
PUBLIC_HEAD="$(git -C "$PUBLIC_CLONE" rev-parse --short HEAD)"
PUBLIC_HEAD_MSG="$(git -C "$PUBLIC_CLONE" log -1 --format=%s)"
ok "auf origin/$PUBLIC_BRANCH: $PUBLIC_HEAD — $PUBLIC_HEAD_MSG"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (6) Diff Staging ↔ Klon; ohne --dry-run: anwenden
# ─────────────────────────────────────────────────────────────────────────────

say "(6) TECH-Dateien: Staging ↔ Klon"

NEW_FILES=(); MODIFIED_FILES=(); UNCHANGED_COUNT=0

if [ "${#TECH_FILES[@]}" -gt 0 ]; then
    for pair in "${TECH_FILES[@]}"; do
        dest="${pair#*:}"
        s="$STAGING_DIR/$dest"
        d="$PUBLIC_CLONE/$dest"
        if [ ! -f "$d" ]; then
            NEW_FILES+=("$dest")
        elif ! cmp -s "$s" "$d"; then
            MODIFIED_FILES+=("$dest")
        else
            UNCHANGED_COUNT=$((UNCHANGED_COUNT + 1))
        fi
    done
fi

ok "${#NEW_FILES[@]} neu, ${#MODIFIED_FILES[@]} geändert, $UNCHANGED_COUNT unverändert (von ${#TECH_FILES[@]} TECH-Dateien)"
if [ "${#NEW_FILES[@]}" -gt 0 ]; then
    log "neu:"
    printf '%s\n' "${NEW_FILES[@]}" | sed 's/^/      + /' | head -40
fi
if [ "${#MODIFIED_FILES[@]}" -gt 0 ]; then
    log "geändert:"
    printf '%s\n' "${MODIFIED_FILES[@]}" | sed 's/^/      ~ /' | head -40
fi
log "HINWEIS: kein Löschen im Klon (siehe LIMITATIONS im Kopfkommentar) — nur Neu/Geändert wird angewendet."

if [ "$DRY_RUN" = 1 ]; then
    log "Trockenlauf — Klon bleibt bei origin/$PUBLIC_BRANCH ($PUBLIC_HEAD), nichts wurde geschrieben."
else
    if [ "${#TECH_FILES[@]}" -gt 0 ]; then
        for pair in "${TECH_FILES[@]}"; do
            dest="${pair#*:}"
            mkdir -p "$(dirname "$PUBLIC_CLONE/$dest")"
            cp "$STAGING_DIR/$dest" "$PUBLIC_CLONE/$dest"
        done
    fi
    ok "In den Klon geschrieben (UNGECOMMITTET): $PUBLIC_CLONE"
fi
echo

# ─────────────────────────────────────────────────────────────────────────────
# (7) Doku-Drift-Check
# ─────────────────────────────────────────────────────────────────────────────

say "(7) Doku-Drift-Check (**/*.md, seit letzter Prüfung)"

ensure_log_dir
# Kein assoziatives Array (bash 3.2 hat declare -A nicht) — Lookup je Datei
# per awk direkt gegen die Baseline-Datei (Zeilenzahl klein, kein Performance-
# Thema).
baseline_hash_for() {
    local rel="$1"
    [ -f "$DOC_BASELINE_FILE" ] || { echo ""; return; }
    awk -F'\t' -v want="$rel" '$1==want{print $2; exit}' "$DOC_BASELINE_FILE"
}

DOC_DRIFT_COUNT=0
DOC_NEW_BASELINE_COUNT=0
DOC_EXCLUDED_COUNT=0
NEW_BASELINE_LINES=()

if [ "${#DOC_FILES[@]}" -gt 0 ]; then
    for pair in "${DOC_FILES[@]}"; do
        rel="${pair%%:*}"; dest="${pair#*:}"
        if [ "$rel" = "HANDOFF-satellite-hand.md" ]; then
            DOC_EXCLUDED_COUNT=$((DOC_EXCLUDED_COUNT + 1))
            continue   # bewusst nie geprüft — siehe DOC_EXPLICIT_MAP-Kommentar
        fi
        cur_hash="$(shasum -a 256 "$SRC_ROOT/$rel" | awk '{print $1}')"
        prev_hash="$(baseline_hash_for "$rel")"
        if [ -z "$prev_hash" ]; then
            DOC_NEW_BASELINE_COUNT=$((DOC_NEW_BASELINE_COUNT + 1))
            NEW_BASELINE_LINES+=("$rel	$cur_hash")
        elif [ "$prev_hash" != "$cur_hash" ]; then
            DOC_DRIFT_COUNT=$((DOC_DRIFT_COUNT + 1))
            pub_hint="${dest:-<kein bekanntes Gegenstück — manuell prüfen>}"
            warn "Datei $rel lokal geändert seit letzter Prüfung — public-Fassung prüfen: $pub_hint"
            NEW_BASELINE_LINES+=("$rel	$cur_hash")
        else
            NEW_BASELINE_LINES+=("$rel	$cur_hash")
        fi
    done
fi

if [ "$DOC_DRIFT_COUNT" -eq 0 ] && [ "$DOC_NEW_BASELINE_COUNT" -eq 0 ]; then
    ok "keine Doku-Drift seit letzter Prüfung (${#DOC_FILES[@]} Dateien, davon $DOC_EXCLUDED_COUNT bewusst ausgeschlossen: HANDOFF)"
else
    ok "$DOC_DRIFT_COUNT Datei(en) mit Drift, $DOC_NEW_BASELINE_COUNT neu in die Baseline aufgenommen"
fi

if [ "$DRY_RUN" = 1 ]; then
    log "Trockenlauf — Baseline-Datei wird NICHT geschrieben."
else
    if [ "${#NEW_BASELINE_LINES[@]}" -gt 0 ]; then
        printf '%s\n' "${NEW_BASELINE_LINES[@]}" > "$DOC_BASELINE_FILE"
    else
        : > "$DOC_BASELINE_FILE"
    fi
    ok "Baseline aktualisiert: ${DOC_BASELINE_FILE#$REPO_ROOT/}"
fi
echo

# ─────────────────────────────────────────────────────────────────────────────
# (8) STOPP — nie Commit, nie Push
# ─────────────────────────────────────────────────────────────────────────────

say "(8) ${C_GREEN}Fertig — es wurde NICHTS committet und NICHTS gepusht.${C_RESET}"
log "Klon: $PUBLIC_CLONE"
echo
log "Von Hand prüfen und weitermachen:"
echo "      ${C_BOLD}git -C $PUBLIC_CLONE status${C_RESET}"
echo "      ${C_BOLD}git -C $PUBLIC_CLONE diff${C_RESET}"
echo "      # wenn gut: committen + pushen, von Hand, mit eigener Nachricht:"
echo "      ${C_BOLD}git -C $PUBLIC_CLONE add -A && git -C $PUBLIC_CLONE commit -m '…'${C_RESET}"
echo "      ${C_BOLD}git -C $PUBLIC_CLONE push origin $PUBLIC_BRANCH${C_RESET}"
echo
log "Verwerfen: git -C $PUBLIC_CLONE reset --hard origin/$PUBLIC_BRANCH && git -C $PUBLIC_CLONE clean -fdx"
echo

if [ "$DOC_DRIFT_COUNT" -gt 0 ] || [ "${#UNCLASSIFIED[@]}" -gt 0 ]; then
    exit 2
fi
exit 0
