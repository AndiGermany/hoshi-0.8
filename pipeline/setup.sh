#!/usr/bin/env bash
# pipeline/setup.sh — DER idempotente Setup-Einstieg (Profil local-mac).
#
# WARUM: SETUP.md sagt heute ehrlich, was zu tun ist — aber als manuelle
# Schrittfolge zum Abtippen (gradlew build → npm install/build →
# tools/models-verify.sh → sidecars/*/bootstrap.sh → bin/hoshi up). Codex'
# Installierbarkeits-Audit (P1) fehlt genau EIN Einstieg, der diese Folge
# orchestriert UND bei Wiederholung nur das Fehlende tut. Das ist dieses
# Skript — es erfindet NICHTS Neues: jeder Schritt ruft ein bestehendes,
# unveraendertes Skript auf (preflight.sh, gradlew, npm, sidecars/*/bootstrap.sh,
# tools/models-verify.sh). setup.sh entscheidet nur: ist der Schritt schon
# erledigt, oder nicht?
#
# IDEMPOTENZ IST DER KERN (Abnahme-Test): ein zweiter Lauf direkt nach einem
# erfolgreichen ersten Lauf muss JEDEN Schritt als "übersprungen (schon da)"
# melden und in <10s mit Exit 0 enden — er baut/installiert/bootstrappt NICHTS
# noch einmal, nur weil man ihn zweimal aufruft.
#
# Bewusst NICHT hier drin:
#   - das split-Profil (Andis Backend-auf-ct-106-Sonderfall, s. preflight.sh
#     --profile split) — bleibt manuell, dafuer gibt es preflight --profile split.
#   - bin/hoshi-Einhaengung und SETUP.md-Umschreiben — macht der Orchestrator.
#   - jeder Modell-Download (Gemma/Whisper/Wiki-DB) — bleibt bei
#     tools/models-verify.sh (read-only) + der Hand des Nutzers, weil bei
#     HuggingFace echte Lizenz-Gates haengen, die ein Skript nicht fuer den
#     Nutzer akzeptieren darf.
#   - jeder Start (kein bin/hoshi up, kein launchd) — dieses Skript baut/
#     bootstrappt nur, es startet nichts und ruehrt keinen laufenden Prozess an.
#
# --dry-run: fuehrt preflight.sh und tools/models-verify.sh trotzdem ECHT aus
# (beide sind laut ihrem eigenen Vertrag read-only/zustandsarm — "ändert
# NICHTS" gilt fuer sie ohnehin immer, dry-run oder nicht, und ihre Ausgabe
# macht den Trockenlauf erst nuetzlich: man sieht den echten Preflight- und
# Modell-Stand). NUR die Schritte mit echten Seiteneffekten (Gradle-Build,
# npm install/build, sidecars/*/bootstrap.sh) werden im dry-run NICHT
# ausgefuehrt, sondern nur als "würde ausführen: …" angekündigt.
#
# Aufruf:
#   pipeline/setup.sh              # tut, was fehlt; Report am Ende
#   pipeline/setup.sh --dry-run    # druckt nur die Entscheidungen, führt Bau-/
#                                   # Install-/Bootstrap-Schritte NICHT aus
#   pipeline/setup.sh --with-piper # GPL-3.0-Opt-in: bootstrappt zusätzlich
#                                   # sidecars/piper (sonst NIE automatisch,
#                                   # s. SETUP.md §4 + sidecars/piper/LICENSES.md)
#
# Exit-Code (wie doctor.sh/preflight.sh: 0/2/3, gleiche Bedeutung):
#   0  STARTKLAR — alle Schritte erledigt/übersprungen, Modelle vollständig
#   2  EINSCHRAENKUNGEN — Preflight-WARNs und/oder fehlende/unvollständige
#      Modelle (auf einem frischen Klon VOR dem ersten Modell-Download der
#      erwartete Normalfall, kein Grund zum Abbruch)
#   3  BLOCKIERT — Preflight blockt hart (fehlende Grundvoraussetzung), oder
#      ein Bau-/Install-/Bootstrap-Schritt selbst ist fehlgeschlagen
#
# NICHT in bin/hoshi eingehängt (macht der Orchestrator, Hot-File) und
# SETUP.md bleibt unverändert (macht ebenfalls der Orchestrator).

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
set +e   # wie doctor.sh/preflight.sh: Schritte werten wir selbst aus statt hart abzubrechen.

cd "$REPO_ROOT"

# ── Argumente ────────────────────────────────────────────────────────────────
DRY_RUN=false
WITH_PIPER=false
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)    DRY_RUN=true; shift ;;
        --with-piper) WITH_PIPER=true; shift ;;
        *) fail "Unbekanntes Argument: $1 (erwartet: --dry-run, --with-piper)"; exit 3 ;;
    esac
done

MODE_SUFFIX=""
$DRY_RUN && MODE_SUFFIX=" · ${C_YELLOW}DRY-RUN${C_RESET}"
say "Setup — idempotenter Einstieg (Profil local-mac)${MODE_SUFFIX}"
echo

RC=0
note_degraded() { [ "$RC" -lt 2 ] && RC=2; }
note_blocked()  { RC=3; }

# ── Report-Sammlung (Muster aus pipeline/bench-brain.sh: pipe-delimitierte
# Zeilen in einem Array statt eigener Datenstruktur) ─────────────────────────
declare -a REPORT=()   # "<Schritt>|<STATUS>|<Detail>"
report() { REPORT+=("$1|$2|$3"); }

# ══════════════════════════════════════════════════════════════════════════
# (1/5) Preflight — Grundvoraussetzungen VOR jedem Bauen prüfen
# ══════════════════════════════════════════════════════════════════════════
say "(1/5) Preflight"
bash "$PIPELINE_DIR/preflight.sh" --profile local-mac
PRE_RC=$?
echo
case "$PRE_RC" in
    0) report "preflight" "DONE" "startklar (rc=0)" ;;
    2) warn "Preflight meldet Einschränkungen (rc=2) — Setup macht trotzdem weiter."
       report "preflight" "WARN" "Einschränkungen (rc=2, s. Preflight-Ausgabe oben)"
       note_degraded ;;
    3) fail "Preflight blockt (rc=3) — Setup ABGEBROCHEN, s. Preflight-Ausgabe oben."
       exit 3 ;;
    *) warn "Preflight lieferte unerwarteten rc=$PRE_RC — Setup macht vorsichtig weiter."
       report "preflight" "WARN" "unerwarteter rc=$PRE_RC"
       note_degraded ;;
esac

# ══════════════════════════════════════════════════════════════════════════
# (2/5) Backend (Kotlin/Gradle) — NUR bauen, wenn der Ziel-Jar fehlt oder
# irgendeine Quelle jünger ist als er.
# ══════════════════════════════════════════════════════════════════════════
# JAVA_HOME: pipeline/deploy.sh (gelesen vor diesem Schritt) setzt VOR seinem
# eigenen "$GRADLEW" :web-inbound:bootJar-Aufruf KEIN JAVA_HOME und keine
# sonstige Java-Auswahl-Logik — gradle.properties hat bewusst KEIN
# org.gradle.java.home mehr (P1-OSS-Fund: ein maschinenspezifischer Pfad brach
# jeden Fremd-Klon), stattdessen zieht der foojay-resolver
# (settings.gradle.kts) + org.gradle.java.installations.auto-download=true
# JDK 21 selbst, falls lokal keins passt. setup.sh übernimmt darum GENAU
# DIESELBE Nicht-Logik: "$GRADLEW" ohne jede JAVA_HOME-Vorauswahl aufrufen.
# (Das GRADLE_JAVA_HOME-Sed-Muster aus run.sh/turn.sh/voice.sh/voicein.sh/
# ask.sh ist ein ANDERER Zweck: die dortigen Skripte starten den BEREITS
# GEBAUTEN Jar direkt per `java -jar`, nicht Gradle selbst — für den reinen
# Bau-Schritt hier ist das irrelevant.)
echo
say "(2/5) Backend (Kotlin/Gradle)"

# check_backend_build JAR → setzt BACKEND_BUILD_NEEDED (0/1) + BACKEND_BUILD_REASON.
# RATE-STELLE: das ist eine grobe, externe mtime-Heuristik (kein Gradle-eigener
# up-to-date-Check) — "neueste Quelle" = jüngste *.kt-, build.gradle.kts-,
# settings.gradle.kts- oder gradle.properties-Datei außerhalb von build/-,
# node_modules/-, .git/- und ähnlichen Ableitungs-/Fremd-Verzeichnissen.
check_backend_build() {
    local jar="$1"
    if [ ! -f "$jar" ]; then
        BACKEND_BUILD_NEEDED=1
        BACKEND_BUILD_REASON="Jar fehlt: ${jar#$REPO_ROOT/}"
        return
    fi
    local hit
    hit="$(find "$REPO_ROOT" \
        \( -name build -o -name node_modules -o -name .git -o -name .venv \
           -o -name .gradle -o -name .gradle-jdks -o -name .kotlin \
           -o -name frontend -o -name vault -o -name .pipeline \
           -o -name .obsidian -o -name .orch-bus -o -name .pytest_cache \) -prune -o \
        \( -name '*.kt' -o -name 'build.gradle.kts' -o -name 'settings.gradle.kts' \
           -o -name 'gradle.properties' \) -newer "$jar" -print -quit 2>/dev/null)"
    if [ -n "$hit" ]; then
        BACKEND_BUILD_NEEDED=1
        BACKEND_BUILD_REASON="Quelle jünger als Jar: ${hit#$REPO_ROOT/}"
    else
        BACKEND_BUILD_NEEDED=0
        BACKEND_BUILD_REASON="Jar aktuell: ${jar#$REPO_ROOT/}"
    fi
}

if [ ! -x "$GRADLEW" ]; then
    fail "gradlew nicht ausführbar: ${GRADLEW#$REPO_ROOT/}"
    report "backend-build" "FAIL" "gradlew fehlt/nicht ausführbar"
    note_blocked
else
    # Ziel-Jar per Version aus gradle.properties ableiten — NICHT raten
    # (dasselbe Muster wie deploy.sh's JAR_VERSION, dort mit Begründung:
    # ein hart verdrahteter Jar-Name überlebte 2026-07-25 genau EINEN
    # Versions-Sprung nicht und deployte still den alten Stand weiter).
    HOSHI_VERSION="$(sed -n 's/^version=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
    if [ -z "$HOSHI_VERSION" ]; then
        fail "keine version= in gradle.properties gefunden — kann Ziel-Jar nicht bestimmen."
        report "backend-build" "FAIL" "gradle.properties ohne version="
        note_blocked
    else
        JAR="$REPO_ROOT/web-inbound/build/libs/web-inbound-${HOSHI_VERSION}.jar"
        check_backend_build "$JAR"
        if [ "$BACKEND_BUILD_NEEDED" -eq 1 ]; then
            if $DRY_RUN; then
                log "würde ausführen: ./gradlew :web-inbound:bootJar — $BACKEND_BUILD_REASON"
                report "backend-build" "PLAN" "$BACKEND_BUILD_REASON"
            else
                say "  $BACKEND_BUILD_REASON → ./gradlew :web-inbound:bootJar"
                "$GRADLEW" :web-inbound:bootJar
                GRC=$?
                if [ "$GRC" -eq 0 ] && [ -f "$JAR" ]; then
                    ok "Backend gebaut: $(basename "$JAR")"
                    report "backend-build" "DONE" "gebaut: $(basename "$JAR")"
                else
                    fail "./gradlew :web-inbound:bootJar fehlgeschlagen (rc=$GRC) oder Jar fehlt danach."
                    report "backend-build" "FAIL" "gradlew rc=$GRC"
                    note_blocked
                fi
            fi
        else
            ok "Backend-Jar aktuell — übersprungen: $(basename "$JAR")"
            report "backend-build" "SKIP" "$BACKEND_BUILD_REASON"
        fi
    fi
fi

# ══════════════════════════════════════════════════════════════════════════
# (3/5) Frontend (npm) — install nur bei fehlendem/veraltetem node_modules,
# build nur bei fehlendem/veraltetem dist/.
# ══════════════════════════════════════════════════════════════════════════
echo
say "(3/5) Frontend (npm)"

check_frontend_install() {
    local nm="$REPO_ROOT/frontend/node_modules" lock="$REPO_ROOT/frontend/package-lock.json"
    if [ ! -d "$nm" ]; then
        FRONTEND_INSTALL_NEEDED=1; FRONTEND_INSTALL_REASON="node_modules fehlt"
    elif [ "$lock" -nt "$nm" ]; then
        FRONTEND_INSTALL_NEEDED=1; FRONTEND_INSTALL_REASON="package-lock.json neuer als node_modules"
    else
        FRONTEND_INSTALL_NEEDED=0; FRONTEND_INSTALL_REASON="node_modules aktuell (>= package-lock.json)"
    fi
}
# RATE-STELLE (bewusst gemäß Auftrag, kein zusätzliches Raten): "älter als
# src/" wird WÖRTLICH genommen — nur frontend/src/ zählt als Quelle, NICHT
# vite.config.ts/package.json/tsconfig*.json/index.html. Ändert sich NUR eine
# dieser Konfigdateien (kein src/-File), meldet dieser Check fälschlich
# "aktuell". Siehe LIMITATIONS in der Rückgabe.
check_frontend_build() {
    local dist="$REPO_ROOT/frontend/dist" src="$REPO_ROOT/frontend/src" hit
    if [ ! -d "$dist" ]; then
        FRONTEND_BUILD_NEEDED=1; FRONTEND_BUILD_REASON="dist/ fehlt"
        return
    fi
    hit="$(find "$src" -newer "$dist" -print -quit 2>/dev/null)"
    if [ -n "$hit" ]; then
        FRONTEND_BUILD_NEEDED=1; FRONTEND_BUILD_REASON="src/ jünger als dist/: ${hit#$REPO_ROOT/}"
    else
        FRONTEND_BUILD_NEEDED=0; FRONTEND_BUILD_REASON="dist/ aktuell (>= src/)"
    fi
}

if ! command -v npm >/dev/null 2>&1; then
    warn "npm nicht gefunden — Frontend-Schritte übersprungen (Backend läuft trotzdem, s. preflight)."
    report "frontend-install" "WARN" "npm fehlt"
    report "frontend-build" "WARN" "npm fehlt"
    note_degraded
else
    check_frontend_install
    if [ "$FRONTEND_INSTALL_NEEDED" -eq 1 ]; then
        if $DRY_RUN; then
            log "würde ausführen: (cd frontend && npm install) — $FRONTEND_INSTALL_REASON"
            report "frontend-install" "PLAN" "$FRONTEND_INSTALL_REASON"
        else
            say "  $FRONTEND_INSTALL_REASON → npm install"
            (cd "$REPO_ROOT/frontend" && npm install)
            NRC=$?
            if [ "$NRC" -eq 0 ]; then
                ok "npm install fertig"
                report "frontend-install" "DONE" "$FRONTEND_INSTALL_REASON"
            else
                fail "npm install fehlgeschlagen (rc=$NRC)"
                report "frontend-install" "FAIL" "npm install rc=$NRC"
                note_blocked
            fi
        fi
    else
        ok "npm install übersprungen — $FRONTEND_INSTALL_REASON"
        report "frontend-install" "SKIP" "$FRONTEND_INSTALL_REASON"
    fi

    check_frontend_build
    if [ "$FRONTEND_BUILD_NEEDED" -eq 1 ]; then
        if $DRY_RUN; then
            log "würde ausführen: (cd frontend && npm run build) — $FRONTEND_BUILD_REASON"
            report "frontend-build" "PLAN" "$FRONTEND_BUILD_REASON"
        else
            say "  $FRONTEND_BUILD_REASON → npm run build"
            (cd "$REPO_ROOT/frontend" && npm run build)
            NRC=$?
            if [ "$NRC" -eq 0 ] && [ -d "$REPO_ROOT/frontend/dist" ]; then
                ok "Frontend gebaut (dist/)"
                report "frontend-build" "DONE" "$FRONTEND_BUILD_REASON"
            else
                fail "npm run build fehlgeschlagen (rc=$NRC)"
                report "frontend-build" "FAIL" "npm run build rc=$NRC"
                note_blocked
            fi
        fi
    else
        ok "npm run build übersprungen — $FRONTEND_BUILD_REASON"
        report "frontend-build" "SKIP" "$FRONTEND_BUILD_REASON"
    fi
fi

# ══════════════════════════════════════════════════════════════════════════
# (4/5) Sidecar-Bootstraps — je Sidecar NUR wenn .venv/bin/python fehlt/nicht
# ausführbar ist. say zuerst (kein Modell-Download, schnellster Fresh-Clone-
# Beweis), piper NUR mit --with-piper (GPL-3.0-Opt-in, s. SETUP.md §4).
# ══════════════════════════════════════════════════════════════════════════
echo
say "(4/5) Sidecar-Bootstraps"

SIDECAR_ORDER=(say brain stt speaker knowledge)
for sc in "${SIDECAR_ORDER[@]}"; do
    dir="$REPO_ROOT/sidecars/$sc"
    venv_py="$dir/.venv/bin/python"
    if [ -x "$venv_py" ]; then
        ok "$sc: .venv vorhanden — übersprungen (schon da)"
        report "bootstrap-$sc" "SKIP" ".venv vorhanden"
    elif $DRY_RUN; then
        log "würde ausführen: sidecars/$sc/bootstrap.sh — .venv fehlt"
        report "bootstrap-$sc" "PLAN" ".venv fehlt"
    else
        say "  $sc: .venv fehlt → sidecars/$sc/bootstrap.sh"
        bash "$dir/bootstrap.sh"
        BRC=$?
        if [ "$BRC" -eq 0 ] && [ -x "$venv_py" ]; then
            ok "$sc bootstrapped"
            report "bootstrap-$sc" "DONE" "bootstrap.sh rc=0"
        else
            fail "$sc bootstrap.sh fehlgeschlagen (rc=$BRC)"
            report "bootstrap-$sc" "FAIL" "bootstrap.sh rc=$BRC"
            note_blocked
        fi
    fi
done

# piper — bewusst getrennt von der Schleife: nur mit explizitem --with-piper
# überhaupt versucht, unabhängig davon aber ehrlich gemeldet, falls schon da
# (z.B. weil jemand es früher manuell oder mit --with-piper bootstrappt hat).
piper_dir="$REPO_ROOT/sidecars/piper"
piper_venv_py="$piper_dir/.venv/bin/python"
if [ -x "$piper_venv_py" ]; then
    ok "piper: .venv vorhanden — übersprungen (schon da)"
    report "bootstrap-piper" "SKIP" ".venv vorhanden"
elif ! $WITH_PIPER; then
    log "piper: übersprungen (optional, GPL-3.0-or-later — nur mit --with-piper, s. sidecars/piper/LICENSES.md)"
    report "bootstrap-piper" "OPT-IN" "nicht installiert, kein --with-piper"
elif $DRY_RUN; then
    log "würde ausführen: sidecars/piper/bootstrap.sh — .venv fehlt, --with-piper gesetzt"
    report "bootstrap-piper" "PLAN" ".venv fehlt, --with-piper gesetzt"
else
    say "  piper: .venv fehlt, --with-piper gesetzt → sidecars/piper/bootstrap.sh"
    bash "$piper_dir/bootstrap.sh"
    BRC=$?
    if [ "$BRC" -eq 0 ] && [ -x "$piper_venv_py" ]; then
        ok "piper bootstrapped"
        report "bootstrap-piper" "DONE" "bootstrap.sh rc=0"
    else
        fail "piper bootstrap.sh fehlgeschlagen (rc=$BRC)"
        report "bootstrap-piper" "FAIL" "bootstrap.sh rc=$BRC"
        note_blocked
    fi
fi

# ══════════════════════════════════════════════════════════════════════════
# (5/5) Modell-Vollständigkeit — READ-ONLY, lädt NICHTS. setup.sh lädt nie
# selbst Modelle (Lizenz-Gates bei HuggingFace bleiben beim Nutzer) — hier
# wird nur tools/models-verify.sh' eigenes Urteil durchgereicht.
# ══════════════════════════════════════════════════════════════════════════
echo
say "(5/5) Modell-Vollständigkeit (tools/models-verify.sh, read-only)"
if $DRY_RUN; then
    log "würde ausführen: tools/models-verify.sh — read-only, s. Ausgabe unten (läuft auch im dry-run, weil zustandsfrei)"
fi
bash "$REPO_ROOT/tools/models-verify.sh"
MV_RC=$?
echo
if [ "$MV_RC" -eq 0 ]; then
    report "models-verify" "DONE" "alle required-Modelle OK"
else
    report "models-verify" "WARN" "required-Modell(e) fehlen/unvollständig (rc=$MV_RC) — s. Fix-Zeilen oben"
    note_degraded
fi

# ══════════════════════════════════════════════════════════════════════════
# Abschluss-Report
# ══════════════════════════════════════════════════════════════════════════
echo
say "Report"
echo
report_hr()  { printf '  %s\n' "───────────────────┬────────┬────────────────────────────────────────────────────"; }
report_row() { printf '  %-18s │ %-6s │ %s\n' "$1" "$2" "$3"; }
report_st() {
    case "$1" in
        DONE)   printf '%bDONE  %b' "$C_GREEN"  "$C_RESET" ;;
        SKIP)   printf '%bSKIP  %b' "$C_DIM"    "$C_RESET" ;;
        PLAN)   printf '%bPLAN  %b' "$C_BLUE"   "$C_RESET" ;;
        WARN)   printf '%bWARN  %b' "$C_YELLOW" "$C_RESET" ;;
        FAIL)   printf '%bFAIL  %b' "$C_RED"    "$C_RESET" ;;
        OPT-IN) printf '%bOPT-IN%b' "$C_DIM"    "$C_RESET" ;;
        *)      printf '%-6s' "$1" ;;
    esac
}
printf '  %-18s │ %-6s │ %s\n' "SCHRITT" "STATUS" "DETAIL"
report_hr
DONE_N=0; SKIP_N=0; PLAN_N=0
declare -a ISSUE_LINES=()
declare -a INFO_LINES=()
for entry in "${REPORT[@]}"; do
    IFS='|' read -r r_name r_status r_detail <<< "$entry"
    report_row "$r_name" "$(report_st "$r_status")" "$r_detail"
    case "$r_status" in
        DONE)   DONE_N=$((DONE_N+1)) ;;
        SKIP)   SKIP_N=$((SKIP_N+1)) ;;
        PLAN)   PLAN_N=$((PLAN_N+1)) ;;
        WARN|FAIL) ISSUE_LINES+=("$r_name: $r_detail") ;;
        OPT-IN) INFO_LINES+=("$r_name: $r_detail") ;;
    esac
done
report_hr
echo

SUMMARY_LINE="Getan: $DONE_N · Übersprungen (schon da): $SKIP_N"
$DRY_RUN && SUMMARY_LINE="$SUMMARY_LINE · Geplant (dry-run, nichts ausgeführt): $PLAN_N"
log "$SUMMARY_LINE"

if [ "${#ISSUE_LINES[@]}" -gt 0 ]; then
    warn "Fehlt noch / Hinweise:"
    for l in "${ISSUE_LINES[@]}"; do
        log "  - $l"
    done
fi
if [ "${#INFO_LINES[@]}" -gt 0 ]; then
    log "Optional (nicht installiert, bewusst opt-in):"
    for l in "${INFO_LINES[@]}"; do
        log "  - $l"
    done
fi
echo
log "Nächster Schritt: bin/hoshi up   (fährt Brain + Sidecars idempotent hoch, zeigt danach den doctor-Status)"
echo

DRY_SUFFIX=""
$DRY_RUN && DRY_SUFFIX=" (DRY-RUN — nichts ausgeführt/geändert)"
case "$RC" in
    0) ok   "Urteil: STARTKLAR — alle Schritte erledigt/übersprungen, Modelle vollständig.${DRY_SUFFIX}" ;;
    2) warn "Urteil: EINSCHRAENKUNGEN — Setup lief durch, s. WARN oben (Preflight und/oder Modelle).${DRY_SUFFIX}" ;;
    3) fail "Urteil: BLOCKIERT — mindestens ein Schritt ist fehlgeschlagen, s. FAIL oben.${DRY_SUFFIX}" ;;
esac
exit "$RC"
