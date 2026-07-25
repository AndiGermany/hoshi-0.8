#!/usr/bin/env bash
# pipeline/test-render-unit.sh — Prüfskript für die UNIT-RENDER-NAHT von deploy.sh.
#
# WARUM ES DAS GIBT
# -----------------
# `render_unit()` in pipeline/deploy.sh entscheidet, welche TTS-Engine in PRODUKTION
# spricht: sie liest secrets.json["tts"], defaultet lokal-first auf "say" und rendert
# den Wert per sed in den Platzhalter __TTS_ENGINE__ der systemd-Unit. Bis 2026-07-25
# war das der einzige ungeprüfte Prod-Entscheider im Repo — kein Test, keine Validierung.
# Ein Tippfehler ("pipper") wäre wörtlich in die Unit gewandert; heute brechen
# Shell UND Backend unbekannte Werte hart ab.
#
# WAS GEPRÜFT WIRD (11 Fälle, siehe unten)
#   1. gesetztes "tts":"openai"          ⇒ Unit enthält Environment=HOSHI_TTS=openai
#   2. fehlender Schlüssel               ⇒ =say UND sichtbare Warnung auf stderr
#   3. unbekannter Wert ("pipper")       ⇒ HARTER Abbruch (rc≠0), keine Unit geschrieben
#   4. gerenderte Unit                   ⇒ KEIN ungefülltes __…__ mehr übrig
#   5. "  OpenAI " (Whitespace/Case)     ⇒ normalisiert auf 'openai' + Hinweis
#   6. Template mit kaputtem Platzhalter ⇒ Platzhalter-Riegel greift (Positiv-Kontrolle)
#   7. Platzhalter NUR im Kommentar      ⇒ nur Warnung, Render bleibt grün
#   8. nicht-String (`["say"]`)          ⇒ Typfehler + harter Abbruch
#   9. kaputtes JSON                     ⇒ Parsefehler + harter Abbruch
#  10. sed-Sonderzeichen (`say&|`)       ⇒ Allowlist-Abbruch, keine Unit
#  11. nur Whitespace (`"   "`)           ⇒ =say wie Kotlin + sichtbare Warnung
#
# SICHERHEIT — dieses Skript ist OFFLINE und HARMLOS:
#   • kein ssh/scp/curl, kein systemctl, kein Deploy, kein Netz
#   • es liest NIE ~/.hoshi/secrets.json: $HOME wird VOR dem Sourcen auf ein
#     mktemp-Verzeichnis gebogen, alle Fixtures leben dort und werden am Ende gelöscht
#     (der Pfad wird zusätzlich hart geprüft, s. "Schutzwall" unten)
#   • deploy.sh wird mit HOSHI_DEPLOY_SOURCE_ONLY=1 gesourct — der Argument-Dispatch
#     am Dateiende steigt davor aus, es wird nur die Funktionsdefinition geladen
#
# Aufruf:  bash pipeline/test-render-unit.sh      (rc 0 = grün, rc 1 = rot)
# Hängt außerdem in pipeline/verify.sh als erster, offline-fähiger Gate-Schritt.

# ── tmp-Sandbox + $HOME-Umleitung (VOR dem Sourcen von deploy.sh!) ────────────
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/hoshi-render-test.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
REAL_HOME="$HOME"
export HOME="$TMP_ROOT"
mkdir -p "$TMP_ROOT/.hoshi"

# ── deploy.sh sourcen (nur Funktionen, KEIN Dispatch) ─────────────────────────
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
HOSHI_DEPLOY_SOURCE_ONLY=1 source "$SELF_DIR/deploy.sh"
# lib.sh setzt `set -euo pipefail`, deploy.sh danach `set +e`. Wir wollen hier
# ebenfalls Fehler selbst behandeln (Fälle zählen statt aussteigen).
set +e

# ── 🛡️ Schutzwall: NIEMALS gegen echte Secrets laufen ─────────────────────────
case "$SECRETS" in
    "$TMP_ROOT"/*) : ;;
    *)
        echo "ABBRUCH: \$SECRETS zeigt NICHT in die tmp-Sandbox ($SECRETS) — Test würde echte Secrets lesen." >&2
        exit 2
        ;;
esac
if [ "$REAL_HOME" = "$HOME" ]; then
    echo "ABBRUCH: \$HOME wurde nicht umgeleitet — Test würde echte Secrets lesen." >&2
    exit 2
fi

# Unit-Parameter, die sonst remote_deploy setzt (render_unit braucht sie; `set -u`).
MAC_IP="192.168.178.99"
SSL_KEYSTORE_PW="fixture-pw"
REAL_TEMPLATE="$UNIT_TEMPLATE"

# ── Mini-Harness ──────────────────────────────────────────────────────────────
PASSED=0
FAILED=0
check() {   # check <bedingung-rc> <beschreibung>
    if [ "$1" -eq 0 ]; then ok "$2"; PASSED=$((PASSED + 1))
    else fail "$2"; FAILED=$((FAILED + 1)); fi
}
write_fixture() {  # write_fixture <json>
    printf '%s\n' "$1" > "$TMP_ROOT/.hoshi/secrets.json"
}
# Rendert in eine frische Datei; setzt RC / OUT / ERR_TXT / ALL_TXT.
# ERR_TXT = nur stderr, ALL_TXT = stdout+stderr. Der Unterschied ist gewollt: die
# TTS-Default-Warnung schreibt deploy.sh EXPLIZIT nach stderr (>&2), während der
# Kommentar-Tier des Platzhalter-Riegels den lib.sh-Helfer warn() nutzt — und der
# schreibt nach STDOUT (Hausstil, alle warn/ok/say in der Pipeline tun das). Die
# Zusicherungen prüfen darum genau den Strom, der jeweils zugesagt ist.
run_render() {
    OUT="$TMP_ROOT/rendered-$1.service"
    local err="$TMP_ROOT/stderr-$1.txt" outf="$TMP_ROOT/stdout-$1.txt"
    rm -f "$OUT"
    TTS_ENGINE=""          # Vorauflösung aus remote_deploy bewusst neutralisieren
    render_unit "$OUT" >"$outf" 2>"$err"
    RC=$?
    ERR_TXT="$(cat "$err")"
    ALL_TXT="$(cat "$outf" "$err")"
}

say "${C_BOLD}test-render-unit${C_RESET} — Unit-Render-Naht von deploy.sh (offline, tmp-Fixtures)"
log "Sandbox: $TMP_ROOT  ·  Template: ${REAL_TEMPLATE#$REPO_ROOT/}"
echo

# ── (1) gesetztes "tts":"openai" ⇒ Environment=HOSHI_TTS=openai ───────────────
say "(1) secrets.json[\"tts\"]=\"openai\" ⇒ Unit rendert Environment=HOSHI_TTS=openai"
write_fixture '{"tts":"openai"}'
run_render 1
check $((RC == 0 ? 0 : 1)) "render_unit rc=0 (rc=$RC)"
grep -qx 'Environment=HOSHI_TTS=openai' "$OUT" 2>/dev/null
check $? "Unit enthält exakt 'Environment=HOSHI_TTS=openai'"
OPENAI_RENDER="$OUT"
echo

# ── (2) fehlender Schlüssel ⇒ say + laute Warnung auf stderr ──────────────────
say "(2) kein \"tts\"-Schlüssel ⇒ lokal-first Default 'say' + Warnung auf stderr"
write_fixture '{"api":"fixture-token-not-a-real-secret"}'
run_render 2
check $((RC == 0 ? 0 : 1)) "render_unit rc=0 (rc=$RC)"
grep -qx 'Environment=HOSHI_TTS=say' "$OUT" 2>/dev/null
check $? "Unit enthält exakt 'Environment=HOSHI_TTS=say'"
printf '%s' "$ERR_TXT" | grep -q "'say'"
check $? "Warnung erscheint auf stderr (nicht still): ${ERR_TXT%%$'\n'*}"
echo

# ── (3) unbekannter Wert ⇒ HARTER Abbruch, keine Unit ────────────────────────
say "(3) \"tts\":\"pipper\" (Tippfehler) ⇒ HARTER Abbruch statt wörtlichem Render"
write_fixture '{"tts":"pipper"}'
run_render 3
check $((RC != 0 ? 0 : 1)) "render_unit rc≠0 (rc=$RC) — Deploy würde abbrechen"
[ ! -f "$OUT" ]
check $? "KEINE Unit geschrieben (kein halbfertiges Artefakt mit falscher Engine)"
printf '%s' "$ERR_TXT" | grep -q "pipper"
check $? "Fehlermeldung nennt den schlechten Wert"
printf '%s' "$ERR_TXT" | grep -q 'openai say piper voxtral'
check $? "Fehlermeldung nennt die bekannten IDs (Quelle: TtsEngineIds.ALL)"
echo

# ── (4) gerenderte Ausgabe: KEIN __…__ mehr ──────────────────────────────────
say "(4) gerenderte Unit enthält KEINEN ungefüllten Platzhalter __…__ mehr"
LEFTOVER="$(grep -oE '__[A-Z0-9_]+__' "$OPENAI_RENDER" 2>/dev/null | sort -u | tr '\n' ' ')"
[ -z "${LEFTOVER// /}" ]
check $? "keine __…__-Reste in der ganzen Datei (gefunden: '${LEFTOVER:-–}')"
assert_no_placeholders "$OPENAI_RENDER" >/dev/null 2>&1
check $? "assert_no_placeholders() bestätigt die saubere Unit (rc=0)"
echo

# ── (5) Whitespace/Groß-Klein ⇒ normalisiert statt abgelehnt ──────────────────
say "(5) \"tts\":\"  OpenAI \" ⇒ normalisiert auf 'openai' (deckungsgleich mit Kotlin)"
write_fixture '{"tts":"  OpenAI "}'
run_render 5
check $((RC == 0 ? 0 : 1)) "render_unit rc=0 (rc=$RC)"
grep -qx 'Environment=HOSHI_TTS=openai' "$OUT" 2>/dev/null
check $? "Unit enthält die kanonische Kleinschreibung 'openai'"
printf '%s' "$ERR_TXT" | grep -q 'normalisiert'
check $? "Normalisierung wird sichtbar gemeldet (nicht still)"
echo

# ── (6) Positiv-Kontrolle: kaputtes Template ⇒ Riegel greift ─────────────────
say "(6) Template mit ungefülltem __BOGUS_VALUE__ (wirksame Zeile) ⇒ Riegel bricht ab"
write_fixture '{"tts":"openai"}'
BROKEN_TPL="$TMP_ROOT/broken.service"
{ cat "$REAL_TEMPLATE"; printf 'Environment=HOSHI_SOMETHING=__BOGUS_VALUE__\n'; } > "$BROKEN_TPL"
UNIT_TEMPLATE="$BROKEN_TPL"
run_render 6
check $((RC != 0 ? 0 : 1)) "render_unit rc≠0 (rc=$RC) — Deploy würde abbrechen"
printf '%s' "$ERR_TXT" | grep -q '__BOGUS_VALUE__'
check $? "Meldung nennt den ungefüllten Platzhalter"
[ ! -f "$OUT" ]
check $? "halbfertige Unit wurde entfernt (enthielte das Keystore-Passwort)"
UNIT_TEMPLATE="$REAL_TEMPLATE"
echo

# ── (7) Platzhalter NUR im Kommentar ⇒ Warnung, aber grün ────────────────────
say "(7) ungefüllter Platzhalter NUR in einer Kommentar-Zeile ⇒ Warnung, kein Abbruch"
COMMENT_TPL="$TMP_ROOT/comment.service"
{ cat "$REAL_TEMPLATE"; printf '# Doku-Rest: __ALTER_PLATZHALTER__ füllt niemand mehr\n'; } > "$COMMENT_TPL"
UNIT_TEMPLATE="$COMMENT_TPL"
run_render 7
check $((RC == 0 ? 0 : 1)) "render_unit rc=0 (rc=$RC) — zur Laufzeit harmlos"
printf '%s' "$ALL_TXT" | grep -q '__ALTER_PLATZHALTER__'
check $? "Doku-Drift wird trotzdem als Warnung gemeldet (warn() → stdout, Hausstil)"
UNIT_TEMPLATE="$REAL_TEMPLATE"
echo

# ── (8) JSON-Typ ist Teil des Vertrags ────────────────────────────────────────
say "(8) \"tts\":[\"say\"] ⇒ harter Typfehler statt Python-Listenrepräsentation im sed"
write_fixture '{"tts":["say"]}'
run_render 8
check $((RC != 0 ? 0 : 1)) "render_unit rc≠0 (rc=$RC) — Nicht-String wird abgelehnt"
[ ! -f "$OUT" ]
check $? "KEINE Unit geschrieben"
printf '%s' "$ERR_TXT" | grep -q 'muss ein String sein'
check $? "Fehlermeldung nennt den erwarteten JSON-Typ"
echo

# ── (9) Eine vorhandene kaputte Config ist NICHT dasselbe wie keine Config ───
say "(9) kaputtes secrets.json ⇒ harter Parsefehler statt stiller say-Default"
write_fixture '{"tts":"say"'
run_render 9
check $((RC != 0 ? 0 : 1)) "render_unit rc≠0 (rc=$RC) — kaputtes JSON wird abgelehnt"
[ ! -f "$OUT" ]
check $? "KEINE Unit geschrieben"
printf '%s' "$ERR_TXT" | grep -q 'kein gueltiges JSON'
check $? "Fehlermeldung nennt den Parsefehler"
echo

# ── (10) sed-Sonderzeichen erreichen die Substitution nie ─────────────────────
say "(10) \"tts\":\"say&|\" ⇒ Allowlist-Abbruch vor sed"
write_fixture '{"tts":"say&|"}'
run_render 10
check $((RC != 0 ? 0 : 1)) "render_unit rc≠0 (rc=$RC) — Sonderzeichen werden abgelehnt"
[ ! -f "$OUT" ]
check $? "KEINE Unit geschrieben"
printf '%s' "$ERR_TXT" | grep -q 'UNBEKANNTE TTS-Engine'
check $? "Fehlermeldung kommt aus der Allowlist, nicht erst von sed"
echo

# ── (11) Whitespace-only ist nach Normalisierung LEER wie in canonicalOf ──────
say "(11) \"tts\":\"   \" ⇒ lokal-first Default 'say' (deckungsgleich mit Kotlin)"
write_fixture '{"tts":"   "}'
run_render 11
check $((RC == 0 ? 0 : 1)) "render_unit rc=0 (rc=$RC)"
grep -qx 'Environment=HOSHI_TTS=say' "$OUT" 2>/dev/null
check $? "Unit enthält exakt 'Environment=HOSHI_TTS=say'"
printf '%s' "$ERR_TXT" | grep -q 'nur aus Rand-Whitespace'
check $? "Whitespace-Normalisierung wird sichtbar gemeldet"
echo

# ── Report ────────────────────────────────────────────────────────────────────
if [ "$FAILED" -eq 0 ]; then
    say "${C_GREEN}test-render-unit GRÜN${C_RESET} — 11 Fälle, $PASSED Zusicherungen, 0 Fehler."
    log "Geprüft: TTS-Engine-Auflösung (Default/Allowlist/Typ/JSON/sed-Zeichen) + Platzhalter-Riegel."
    exit 0
fi
fail "test-render-unit ROT — $FAILED von $((PASSED + FAILED)) Zusicherungen fehlgeschlagen."
exit 1
