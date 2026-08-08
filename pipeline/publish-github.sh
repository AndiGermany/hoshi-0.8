#!/usr/bin/env bash
# pipeline/publish-github.sh — der VERÖFFENTLICHUNGS-Weg nach GitHub.
#
# ZWEI REPOS, ZWEI ROLLEN:
#   gitea (privat)  = die Entwicklung. Volle History, Vault, Betriebsfunk,
#                     Agent-Konfiguration. Das einzige Remote DIESES Repos.
#   GitHub (public) = fertige Versionen. Ein KURATIERTER EXPORT, kein Push
#                     dieser History.
#
# WARUM Export statt Push? In der History dieses Repos stecken echte
# Heim-GPS-Koordinaten (Commit cc8f10f, später aus dem Baum entfernt — aber
# History vergisst nicht). Ein `git push github master` würde sie dauerhaft
# veröffentlichen. Das öffentliche Repo hat deshalb eine EIGENE, kurze History,
# die mit „Hoshi 0.8 — initial public release" (18.07.2026) beginnt. Dieses
# Skript hängt genau EINEN Release-Commit an diese Fremd-History an.
#
# ABLAUF:
#   (1) Vorbedingungen (Arbeitsbaum · Branch · Version · gh auth)
#   (2) Export-Menge  = git ls-files MINUS EXCLUDED_DIRS
#   (3) Sanitize-Scan  ← BLOCKIEREND. Der wichtigste Schritt.
#   (4) Publish-Klon   (Geschwister-Ordner, eigenes Repo, Remote = GitHub)
#   (5) Sync inkl. Löschungen
#   (6) Release-Commit + Tag v<version>
#   (7) STOPP vor dem Push — die Befehle werden nur ausgegeben.
#
# WARUM KEIN PUSH (Schritt 7)? Veröffentlichen ist nach außen gerichtet und
# nicht zurückzunehmen: sobald ein Blob auf GitHub liegt, ist er über die
# API/Forks/Caches abrufbar, auch nach `git push --force`. Alles davor ist
# lokal und reparierbar — der Push ist die einzige Einbahnstraße im Ablauf.
# Deshalb endet das Skript standardmäßig davor und gibt die exakten Befehle
# aus. `--push` existiert, verlangt aber zusätzlich eine getippte Bestätigung.
#
# Aufruf:
#   bash pipeline/publish-github.sh              # Trockenlauf (Default)
#   bash pipeline/publish-github.sh --allow-dirty  # Trockenlauf bei dirty tree
#   bash pipeline/publish-github.sh --push       # mit Push (fragt nach)

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# ─────────────────────────────────────────────────────────────────────────────
# Konstanten
# ─────────────────────────────────────────────────────────────────────────────

PUBLIC_REPO="AndiGermany/hoshi-0.8"
PUBLIC_BRANCH="master"

# Publish-Klon: ein EIGENES Repo neben diesem. Bewusst außerhalb von
# $REPO_ROOT, damit er niemals versehentlich in die Export-Menge oder in
# `git add` dieses Repos gerät.
PUBLIC_CLONE="${HOSHI_PUBLIC_CLONE:-$REPO_ROOT/../Hoshi_0.8-public}"

# Autor/Committer wie im Zielrepo üblich (die letzten 4 Commits dort tragen
# genau diese Identität; der initiale Commit wurde von Andre Stiewe committet).
COMMIT_NAME="Hoshi Project"
COMMIT_EMAIL="hoshi@local"

# ── Ausschluss-Konstante ─────────────────────────────────────────────────────
# GENAU diese vier Top-Level-Verzeichnisse bleiben privat. Empirisch belegt:
# sie sind die einzigen, die im öffentlichen Baum fehlen (788 öffentliche
# Dateien vs. 1179 hier getrackt).
#
#   vault      — der Obsidian-Vault: interne Notizen, Handovers, Retros,
#                Betriebs-Stände, Namen/Orte aus dem echten Haushalt.
#   .orch-bus  — Betriebsfunk zwischen den Agenten-Instanzen (inbox/outbox):
#                ungefilterte Arbeitsnachrichten, oft mit Prod-Details.
#   .claude    — Agent-Konfiguration (Skills, Settings, Permissions):
#                maschinen-lokal, teils mit Pfaden/Tokens der Arbeitsumgebung.
#   .obsidian  — Editor-Konfiguration des Vaults; ohne Vault sinnlos.
EXCLUDED_DIRS=(vault .orch-bus .claude .obsidian)

# ── Nur-öffentliche Dateien, die der Sync NICHT löschen darf ─────────────────
# Diese Dateien existieren AUSSCHLIESSLICH im öffentlichen Repo — sie wurden
# dort direkt angelegt und haben hier nie existiert. Ohne diese Liste würde der
# Sync sie als „hier verschwunden" interpretieren und löschen.
#
#   SETUP.md — die Einrichtungsanleitung für Fremde, angelegt im Commit
#              „Hoshi 0.8 — initial public release" (dc7d79b). Sie ist NICHT
#              identisch mit dem hiesigen setup-prompt.md (das ist ein Prompt
#              für eine Coding-KI, kein Handbuch für Menschen).
#
# LEER, und das soll so bleiben: dieses Repo ist die einzige Wahrheit. Der eine
# historische Fall (SETUP.md lebte nur im öffentlichen Baum — ein naiver Sync
# hätte die Einrichtungsanleitung für Fremde gelöscht) ist behoben, indem die
# Datei hierher portiert wurde. Wer hier wieder etwas einträgt, baut bewusst eine
# Datei, die nur dort lebt und unbemerkt driftet — dann bitte mit Begründung.
PUBLIC_ONLY_KEEP=()

# ── Namensliste für den Klarnamen-Check ──────────────────────────────────────
# Die zu sperrenden Klarnamen stehen NICHT in diesem Skript — es wird selbst
# mitveröffentlicht, das wäre das Leck, das es verhindern soll. Sie liegen in
# einer gitignorierten Datei (.pipeline/ ist in .gitignore) bzw. in einer
# Umgebungsvariable. Fehlt beides ⇒ FAIL CLOSED: lieber gar nicht
# veröffentlichen als ohne diesen Check.
NAMES_FILE="${HOSHI_SANITIZE_NAMES_FILE:-$PIPELINE_LOG_DIR/sanitize-names.txt}"

# ─────────────────────────────────────────────────────────────────────────────
# Argumente
# ─────────────────────────────────────────────────────────────────────────────

DO_PUSH=0
ALLOW_DIRTY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --push)        DO_PUSH=1 ;;
        --allow-dirty) ALLOW_DIRTY=1 ;;
        -h|--help)
            sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) fail "Unbekanntes Argument: $1"; exit 2 ;;
    esac
    shift
done

if [ "$DO_PUSH" = 1 ] && [ "$ALLOW_DIRTY" = 1 ]; then
    fail "--allow-dirty und --push schließen sich aus."
    log "Ein Release muss einem Commit entsprechen. --allow-dirty ist nur für Trockenläufe."
    exit 2
fi

cd "$REPO_ROOT"

echo
say "${C_BOLD}Hoshi 0.8 — Publish nach GitHub ($PUBLIC_REPO)${C_RESET}"
log "Modus: $([ "$DO_PUSH" = 1 ] && echo 'MIT --push (fragt vor dem Push nach)' || echo 'TROCKENLAUF (kein Push)')"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (1) Vorbedingungen — jede Verletzung ist ein Abbruch
# ─────────────────────────────────────────────────────────────────────────────

say "(1) Vorbedingungen"

# — Branch —
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "master" ]; then
    fail "Aktueller Branch ist '$BRANCH', erwartet 'master'."
    log "Veröffentlicht wird nur vom Integrationsbranch. Wechsle mit: git checkout master"
    exit 1
fi
ok "Branch: master"

# — Arbeitsbaum —
# Geprüft wird, was VERÖFFENTLICHT würde: Änderungen innerhalb der
# Ausschluss-Verzeichnisse (Vault-Notizen, Betriebsfunk) sind für den Export
# bedeutungslos und dürfen einen Release nicht blockieren.
DIRTY_RELEVANT="$(
    git status --porcelain --untracked-files=normal \
        | sed -E 's/^.{3}//' \
        | sed -E 's/^.* -> //' \
        | tr -d '"' \
        | grep -v -E "^($(IFS='|'; echo "${EXCLUDED_DIRS[*]}" | sed 's/\./\\./g'))/" \
        || true
)"
if [ -n "$DIRTY_RELEVANT" ]; then
    if [ "$ALLOW_DIRTY" = 1 ]; then
        warn "Arbeitsbaum NICHT sauber — $(echo "$DIRTY_RELEVANT" | wc -l | tr -d ' ') betroffene Pfade (per --allow-dirty geduldet, nur Trockenlauf)"
        echo "$DIRTY_RELEVANT" | sed 's/^/      /' | head -10
    else
        fail "Arbeitsbaum nicht sauber — der Export würde ungecommittete Arbeit enthalten."
        echo "$DIRTY_RELEVANT" | sed 's/^/      /' | head -20
        log "Committe oder stashe zuerst. Für einen reinen Trockenlauf: --allow-dirty"
        exit 1
    fi
else
    ok "Arbeitsbaum sauber (außerhalb der Ausschluss-Verzeichnisse)"
fi

# — Version —
VERSION="$(grep -E '^version=' gradle.properties | head -1 | cut -d= -f2 | tr -d ' \r')"
if [ -z "$VERSION" ]; then
    fail "Keine Version in gradle.properties (Zeile 'version=…') bestimmbar."
    exit 1
fi
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+(\.[0-9]+)?([-.][A-Za-z0-9]+)*$'; then
    fail "Version '$VERSION' sieht nicht wie eine Release-Version aus."
    exit 1
fi
TAG="v$VERSION"
ok "Version: $VERSION  (Tag: $TAG)"

# — gh auth —
if ! command -v gh >/dev/null 2>&1; then
    fail "'gh' (GitHub CLI) nicht installiert — ohne sie kein Klon/kein Push."
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    fail "'gh auth status' ist rot — nicht bei GitHub angemeldet."
    log "Anmelden mit: gh auth login"
    exit 1
fi
GH_USER="$(gh api user --jq .login 2>/dev/null || echo '?')"
ok "gh authentifiziert (Account: $GH_USER)"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (2) Export-Menge
# ─────────────────────────────────────────────────────────────────────────────

say "(2) Export-Menge bilden"

# WAHRHEIT IST `git ls-files`, NICHT das Dateisystem: sonst wandern
# gitignorierte Artefakte (build/, .venv/, *.gguf, .pipeline/, .env) mit —
# genau die Blobs, die in 0.5 das Repo auf 1,9 GB aufgebläht haben.
EXCLUDE_RE="^($(IFS='|'; echo "${EXCLUDED_DIRS[*]}" | sed 's/\./\\./g'))/"

EXPORT_LIST="$(mktemp -t hoshi-publish-export)"
trap 'rm -f "$EXPORT_LIST"' EXIT

git ls-files -z | tr '\0' '\n' | grep -v -E "$EXCLUDE_RE" > "$EXPORT_LIST"

TRACKED_TOTAL="$(git ls-files | wc -l | tr -d ' ')"
EXPORT_COUNT="$(wc -l < "$EXPORT_LIST" | tr -d ' ')"
EXCLUDED_COUNT=$((TRACKED_TOTAL - EXPORT_COUNT))

ok "$EXPORT_COUNT Dateien im Export (von $TRACKED_TOTAL getrackten)"
log "$EXCLUDED_COUNT Dateien ausgeschlossen:"
for d in "${EXCLUDED_DIRS[@]}"; do
    n="$(git ls-files -- "$d" | wc -l | tr -d ' ')"
    log "    $d/ — $n"
done
echo

# ─────────────────────────────────────────────────────────────────────────────
# (3) Sanitize-Scan — BLOCKIEREND
# ─────────────────────────────────────────────────────────────────────────────
#
# Läuft NUR über die Export-Menge (nicht über das ganze Repo): der Vault DARF
# echte Namen und Koordinaten enthalten, er wird ja nicht veröffentlicht.
#
# Gefundene Werte werden NIE ausgegeben — nur Regel + Datei:Zeile. Ein
# Sanitize-Report, der das Geheimnis mitdruckt, landet sonst im Terminal-Log,
# im Scrollback und im nächsten Agenten-Kontext.
#
# Der Scan hat real zweimal etwas gefangen (Heim-Koordinaten in einer
# systemd-Unit; Klarname einer dritten Person in Test-Fixtures und Kommentaren).

say "(3) Sanitize-Scan über die Export-Menge"

# ── Allowlist ────────────────────────────────────────────────────────────────
# Jeder Eintrag: WARUM der Treffer bewusst erlaubt ist — ohne Begründung ist
# eine Allowlist in drei Monaten nur noch ein Blanko-Scheck.
#
# Selbstbezug beachtet: dieses Skript liegt selbst in der Export-Menge. Die
# case-Muster sind Globs, keine Regexe — sie trennen die Koordinatenwerte mit
# '|' statt mit Komma und lösen die Regeln gps-pair/gps-labeled deshalb nicht
# selbst aus. In den Prosa-Kommentaren stehen die Werte zusätzlich escaped
# (51\.43247). Wer hier umformatiert, prüft das bitte nach.
sanitize_allowed() {
    local rule="$1" file="$2" line="$3"
    case "$rule" in
        gps-pair|gps-labeled)
            # Open-Meteo-Geocoder-Ergebnisse für STADTNAMEN in Wetter-Tests:
            # Duisburg (51\.43247 / 6\.76516) und Kairo (30\.06263 / 31\.24967).
            # Stadt-Zentroide aus einem öffentlichen Geocoder — Stadtebene, keine
            # Adresse, kein Heim-Standort. Die Heim-Koordinaten aus cc8f10f sind
            # ANDERE Werte und bleiben damit weiterhin blockierend.
            case "$line" in
                *51.43247*|*6.76516*|*30.06263*|*31.24967*) return 0 ;;
            esac
            ;;
        privkey)
            # pipeline/publish-satellite.sh trägt die privkey-SCAN-REGEX als
            # Literal (Scanner scannt Scanner, 2026-08-08) — das Muster
            # '-----BEGIN [A-Z ]*PRIVATE KEY' in einer run_scan_rule-Zeile ist
            # Erkennungs-Code, kein Schlüssel. Nur exakt diese Datei.
            case "$file" in
                pipeline/publish-satellite.sh) return 0 ;;
            esac
            ;;
        apikey|secret-literal)
            # Test-Fixtures der Sanitizer/TTS-Tests. Offensichtlich synthetisch:
            #   sk-ABCDEF…  — der Dummy-Key, gegen den NeverSpeakTtsSanitizer,
            #                 OpenAiTtsAdapter*, OpenAiTtsSanitizeWiring und
            #                 PipelineConfigTtsSanitize beweisen, dass ein Token
            #                 NICHT gesprochen/gesendet wird. Ohne diesen Eintrag
            #                 wäre das Skript unbrauchbar — die Tests MÜSSEN
            #                 key-förmig sein, sonst testen sie nichts.
            #   eyJhbGciOiJIUzI1Ni…  — das Lehrbuch-JWT (alg HS256, sub
            #                 1234567890) aus der JWT-Doku, in EgressPortTest und
            #                 NeverSpeakTtsSanitizerTest als Negativ-Fixture
            #                 (zwei Varianten: mit und ohne "typ":"JWT").
            case "$line" in
                *sk-ABCDEF*|*eyJhbGciOiJIUzI1Ni*) return 0 ;;
            esac
            ;;
        secret-file)
            # frontend/.env.example — die VORLAGE ohne Werte. In .gitignore
            # ausdrücklich per `!.env.example` von der .env-Sperre ausgenommen;
            # sie gehört ins OSS-Repo, damit ein Fremd-Clone weiß, welche
            # Variablen es gibt. Echte .env-Dateien bleiben gesperrt.
            case "$line" in
                *.env.example) return 0 ;;
            esac
            ;;
    esac
    return 1
}

# Regeln: name|regex-Beschreibung. Werte werden nie gedruckt.
SANITIZE_HITS=0
run_rule() {
    local rule="$1" pattern="$2" icase="${3:-0}"
    local flags="-nIE"
    [ "$icase" = 1 ] && flags="-nIiE"
    local hits=0
    while IFS= read -r entry; do
        [ -z "$entry" ] && continue
        local file="${entry%%:*}"; local rest="${entry#*:}"
        local lineno="${rest%%:*}"; local content="${rest#*:}"
        if sanitize_allowed "$rule" "$file" "$content"; then continue; fi
        fail "[$rule] $file:$lineno"
        hits=$((hits + 1))
    done < <(tr '\n' '\0' < "$EXPORT_LIST" | xargs -0 grep $flags "$pattern" 2>/dev/null || true)
    if [ "$hits" -gt 0 ]; then
        SANITIZE_HITS=$((SANITIZE_HITS + hits))
    else
        ok "$rule — sauber"
    fi
}

# — Regel: Klarnamen Dritter —
# Konvention im Projekt: eine dritte Person heißt „Person B" / person-b, nie
# beim echten Vornamen (Stimm-Biometrie + Name, ohne dass sie zustimmen konnte).
if [ -n "${HOSHI_SANITIZE_NAMES:-}" ]; then
    NAMES="$(echo "$HOSHI_SANITIZE_NAMES" | tr ',' '\n' | sed '/^[[:space:]]*$/d')"
elif [ -f "$NAMES_FILE" ]; then
    NAMES="$(grep -v -E '^[[:space:]]*(#|$)' "$NAMES_FILE" || true)"
else
    fail "Namensliste fehlt: ${NAMES_FILE#$REPO_ROOT/}"
    log "Ohne sie kann der Klarnamen-Check nicht laufen — und ohne diesen Check"
    log "wird nicht veröffentlicht (fail closed). Anlegen (die Datei ist"
    log "gitignoriert und wird NIE exportiert), ein Vorname pro Zeile:"
    log "    mkdir -p ${PIPELINE_LOG_DIR#$REPO_ROOT/} && \$EDITOR ${NAMES_FILE#$REPO_ROOT/}"
    log "Alternativ: HOSHI_SANITIZE_NAMES='Vorname1,Vorname2' bash pipeline/publish-github.sh"
    exit 1
fi
if [ -z "${NAMES// /}" ]; then
    fail "Namensliste ist leer — der Klarnamen-Check wäre wirkungslos."
    exit 1
fi
NAME_RE="\\b($(echo "$NAMES" | paste -sd'|' -))"
run_rule "klarname" "$NAME_RE" 1

# — Regel: GPS-Koordinaten —
# (a) Zahlenpaar mit ≥5 Nachkommastellen („51.4…, 6.7…") — Adressgenauigkeit.
run_rule "gps-pair" '[-+]?[0-9]{1,2}\.[0-9]{5,}[[:space:]]*,[[:space:]]*[-+]?[0-9]{1,3}\.[0-9]{5,}'
# (b) BESCHRIFTETE Einzelkoordinate (HOSHI_WEATHER_LAT=…, lat: …, latitude=…).
#     Diese Regel ist die wichtige: der reale Leak in cc8f10f stand als zwei
#     getrennte Environment-Zeilen in einer systemd-Unit und wäre (a) entgangen.
run_rule "gps-labeled" '(latitude|longitude|lat|lon|lng|coord|geo)[a-z_]*["'"'"']?[[:space:]]*[=:>][[:space:]]*"?[-+]?[0-9]{1,3}\.[0-9]{4,}' 1

# — Regel: API-Key-Muster —
# OpenAI (sk-…), GitHub-Token (gho_/ghp_/ghs_/ghu_/ghr_), JWT (drei
# base64url-Segmente). Die Muster sind so geschrieben, dass sie sich selbst
# nicht treffen (nach dem Präfix folgt hier eine Zeichenklasse, keine Nutzlast).
run_rule "apikey" '(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})'

# — Regel: Keystore-/Passwort-Literale (in Quotes) —
# Bewusst ENG: ein secret-artiger Schlüsselname MIT einem ≥20 Zeichen langen
# Literal. Die breite Variante („token = \"…\"") träfe hunderte Test-Fixtures
# und i18n-Strings; eine Allowlist dieser Größe würde blind unterschrieben.
run_rule "secret-literal" '(password|passwd|storepass|keypass|store_password|key_password|api[_-]?key|apikey|secret|auth[_-]?token|access[_-]?token)[a-z_]*["'"'"']?[[:space:]]*[=:][[:space:]]*["'"'"'][A-Za-z0-9+/=_.-]{20,}["'"'"']' 1

# — Regel: Keystore-/Passwort-Literale (env-Stil, ohne Quotes) —
# Fängt ein echtes Passwort in einer systemd-Unit oder einem Shell-Skript
# (SERVER_SSL_KEY_STORE_PASSWORD=…). Platzhalter sind ausgenommen: Werte, die
# mit _ (deploy.sh-Render-Marken wie __SSL_KEYSTORE_PW__), $ (Variable), < oder
# { beginnen, sind per Konstruktion keine Geheimnisse.
run_rule "secret-env" '(PASSWORD|PASSWD|SECRET|APIKEY|API_KEY|TOKEN|KEYPASS|STOREPASS|STORE_PASSWORD)[A-Z_]*=[^"'"'"'$_[:space:]<{][^[:space:]"'"'"']{7,}'

# — Regel: privates Schlüsselmaterial —
# NUR echtes Material, keine Datei-ENDUNGEN: „hoshi.p12" steht legitim in
# .gitignore, deploy.sh (Keystore-Vorprüfung) und der systemd-Unit als PFAD.
# Ein Pfad ist kein Schlüssel — dafür gibt es die Regel secret-file unten.
#
# Die Shell-Quotes mitten in den Literalen sind ABSICHT: dieses Skript liegt
# selbst in der Export-Menge und wird mitgescannt. Stünde ein SSH-Key-Präfix
# hier am Stück, würde die Regel ihre eigene Definition als Fund melden (genau
# das ist beim Bau passiert — in diesem Kommentar). Die Konkatenation ergibt
# zur Laufzeit dasselbe Muster, nur die Quelltext-Zeile trifft sich nicht mehr
# selbst. Wer hier etwas ergänzt: danach einmal das Skript gegen sich selbst
# greppen.
run_rule "privkey" '(-----BEGIN [A-Z ]*PRIVATE KEY|ssh-''rsa AAAA|ssh-''ed25519 AAAA|PuTTY-''User-Key-File)'

# — Regel: Geheimnis-DATEIEN in der Export-Menge —
# Dateinamen statt Inhalt: exakt, rauschfrei und der richtige Ort für
# Keystores/Keys. Sie sind alle gitignoriert — diese Regel fängt den Fall,
# dass jemand eine davon per `git add -f` doch in die History gezwungen hat.
SECRET_FILE_HITS=0
while IFS= read -r f; do
    sanitize_allowed "secret-file" "$f" "$f" && continue
    fail "[secret-file] $f"
    SECRET_FILE_HITS=$((SECRET_FILE_HITS + 1))
done < <(grep -iE '(\.(p12|jks|pfx|keystore|pem|key|ppk)$|(^|/)(id_rsa|id_ed25519|id_ecdsa)|(^|/)\.env($|\.)|(^|/)secrets?\.(json|env|ya?ml))' "$EXPORT_LIST" || true)
if [ "$SECRET_FILE_HITS" -gt 0 ]; then
    SANITIZE_HITS=$((SANITIZE_HITS + SECRET_FILE_HITS))
else
    ok "secret-file — sauber"
fi

# — Hinweis (NICHT blockierend): RFC-1918-Adressen —
# 192.168./10./172.16-31. sind nicht routbar und verraten nichts, was von außen
# erreichbar wäre. Sie stehen bewusst und flächendeckend im Projekt (deploy.sh,
# systemd-Units, vite.config.ts, CORS-Tests, die Sanitizer-Tests führen sie als
# Testdaten) und sind im öffentlichen Baum längst vorhanden. Blockierend wäre
# die Regel unbrauchbar, ganz weglassen wäre blind — also: zählen und melden.
LAN_FILES="$(tr '\n' '\0' < "$EXPORT_LIST" | xargs -0 grep -lIE '(192\.168\.[0-9]{1,3}\.[0-9]{1,3}|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})' 2>/dev/null | wc -l | tr -d ' ')"
log "Hinweis: $LAN_FILES Dateien enthalten LAN-Adressen (RFC 1918) — nicht blockierend, siehe Kommentar."

if [ "$SANITIZE_HITS" -gt 0 ]; then
    echo
    fail "SANITIZE-SCAN ROT — $SANITIZE_HITS Treffer. Es wird NICHTS veröffentlicht."
    log "Werte werden bewusst nicht gedruckt. Öffne die genannten Stellen selbst."
    log "Ist ein Treffer nachweislich harmlos: Eintrag in sanitize_allowed() —"
    log "mit Begründung, sonst ist die Allowlist in drei Monaten wertlos."
    exit 1
fi
ok "Sanitize-Scan GRÜN"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (4) Publish-Klon
# ─────────────────────────────────────────────────────────────────────────────

say "(4) Publish-Klon: $PUBLIC_CLONE"

if [ ! -d "$PUBLIC_CLONE/.git" ]; then
    if [ -e "$PUBLIC_CLONE" ]; then
        fail "$PUBLIC_CLONE existiert, ist aber kein git-Repo. Bitte von Hand klären."
        exit 1
    fi
    log "Klon existiert nicht — hole $PUBLIC_REPO …"
    gh repo clone "$PUBLIC_REPO" "$PUBLIC_CLONE" -- --quiet
    ok "Geklont"
else
    log "Klon existiert — hole den Remote-Stand …"
    git -C "$PUBLIC_CLONE" fetch --quiet origin
    ok "gefetcht"
fi

PUBLIC_CLONE="$(cd "$PUBLIC_CLONE" && pwd)"   # normalisieren (…/../… auflösen)

# Sicherheitsnetz: der Klon darf NIEMALS dieses Repo als Remote kennen.
# Sonst wäre ein `git push --all` von dort aus genau der History-Push, den das
# ganze Konstrukt verhindern soll.
if git -C "$PUBLIC_CLONE" remote -v | grep -qiE 'gitea|Hoshi_0\.8\.git|'"$(basename "$REPO_ROOT")"'/?$'; then
    fail "Der Publish-Klon hat ein Remote auf das PRIVATE Repo. Abbruch."
    git -C "$PUBLIC_CLONE" remote -v | sed 's/^/      /'
    exit 1
fi
CLONE_ORIGIN="$(git -C "$PUBLIC_CLONE" remote get-url origin)"
case "$CLONE_ORIGIN" in
    *github.com*"$PUBLIC_REPO"*|*github.com*"$(basename "$PUBLIC_REPO")"*) ;;
    *) fail "origin des Klons zeigt nicht auf $PUBLIC_REPO: $CLONE_ORIGIN"; exit 1 ;;
esac
ok "origin: $CLONE_ORIGIN"

# Hart auf den Remote-Stand. `git clean -fdx` danach, damit kein Rest aus einem
# abgebrochenen Lauf als „neue Datei" mitveröffentlicht wird.
git -C "$PUBLIC_CLONE" checkout --quiet "$PUBLIC_BRANCH" 2>/dev/null || \
    git -C "$PUBLIC_CLONE" checkout --quiet -b "$PUBLIC_BRANCH" "origin/$PUBLIC_BRANCH"
git -C "$PUBLIC_CLONE" reset --hard --quiet "origin/$PUBLIC_BRANCH"
git -C "$PUBLIC_CLONE" clean -qfdx
PUBLIC_HEAD="$(git -C "$PUBLIC_CLONE" rev-parse --short HEAD)"
PUBLIC_HEAD_MSG="$(git -C "$PUBLIC_CLONE" log -1 --format=%s)"
ok "auf origin/$PUBLIC_BRANCH: $PUBLIC_HEAD — $PUBLIC_HEAD_MSG"

# Maßgeblich ist der REMOTE-Tag: nur der ist veröffentlicht. Ein lokaler Tag
# ist der normale Rückstand eines früheren Trockenlaufs und darf nicht als
# „schon draußen" missverstanden werden.
if git -C "$PUBLIC_CLONE" ls-remote --tags origin "refs/tags/$TAG" | grep -q .; then
    fail "Tag $TAG ist auf GitHub bereits veröffentlicht — Version in gradle.properties erhöhen."
    exit 1
fi
if git -C "$PUBLIC_CLONE" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    log "lokaler Tag $TAG aus einem früheren Trockenlauf — wird neu gesetzt"
    git -C "$PUBLIC_CLONE" tag -d "$TAG" >/dev/null
fi
echo

# ─────────────────────────────────────────────────────────────────────────────
# (5) Sync inkl. Löschungen
# ─────────────────────────────────────────────────────────────────────────────

say "(5) Export-Menge in den Klon spiegeln"

# Erst ALLE getrackten Dateien im Klon entfernen, dann die Export-Menge
# hineinkopieren. Das ist der einzige Weg, der Löschungen zuverlässig
# mitnimmt (rsync --delete kann das mit --files-from nicht). `.git/` bleibt
# unangetastet: `git ls-files` listet es nie.
# Ausgenommen: PUBLIC_ONLY_KEEP — die überleben unberührt.
if [ "${#PUBLIC_ONLY_KEEP[@]}" -gt 0 ]; then
    KEEP_RE="^($(IFS='|'; echo "${PUBLIC_ONLY_KEEP[*]}" | sed 's/\./\\./g'))$"
    for k in "${PUBLIC_ONLY_KEEP[@]}"; do
        if [ -f "$PUBLIC_CLONE/$k" ]; then
            log "nur-öffentlich, bleibt: $k"
        else
            warn "PUBLIC_ONLY_KEEP nennt '$k' — im öffentlichen Repo nicht vorhanden (Eintrag veraltet?)"
        fi
    done
else
    KEEP_RE='^$'
fi
git -C "$PUBLIC_CLONE" ls-files | grep -vE "$KEEP_RE" \
    | (cd "$PUBLIC_CLONE" && tr '\n' '\0' | xargs -0 rm -f)
find "$PUBLIC_CLONE" -mindepth 1 -type d -empty -not -path "$PUBLIC_CLONE/.git/*" -delete 2>/dev/null || true

rsync -a --files-from="$EXPORT_LIST" "$REPO_ROOT/" "$PUBLIC_CLONE/"
git -C "$PUBLIC_CLONE" add -A

ADDED="$(git -C "$PUBLIC_CLONE" diff --cached --name-only --diff-filter=A | wc -l | tr -d ' ')"
MODIFIED="$(git -C "$PUBLIC_CLONE" diff --cached --name-only --diff-filter=M | wc -l | tr -d ' ')"
DELETED="$(git -C "$PUBLIC_CLONE" diff --cached --name-only --diff-filter=D | wc -l | tr -d ' ')"

if [ "$((ADDED + MODIFIED + DELETED))" -eq 0 ]; then
    ok "Keine Änderung gegenüber dem öffentlichen Stand — nichts zu veröffentlichen."
    exit 0
fi

ok "Diff gegen $PUBLIC_REPO@$PUBLIC_HEAD: +$ADDED neu, ~$MODIFIED geändert, -$DELETED gelöscht"
if [ "$ADDED" -gt 0 ]; then
    log "neu:"
    git -C "$PUBLIC_CLONE" diff --cached --name-only --diff-filter=A | sed 's/^/      + /' | head -40
fi
if [ "$DELETED" -gt 0 ]; then
    log "gelöscht:"
    git -C "$PUBLIC_CLONE" diff --cached --name-only --diff-filter=D | sed 's/^/      - /' | head -40
fi
echo

# ─────────────────────────────────────────────────────────────────────────────
# (6) Release-Commit + Tag
# ─────────────────────────────────────────────────────────────────────────────

say "(6) Release-Commit + Tag $TAG"

SRC_HEAD="$(git rev-parse --short HEAD)"
COMMIT_MSG="Hoshi $VERSION — Release $(date +%d.%m.%Y)

Kuratierter Export aus der privaten Entwicklung (interner Stand $SRC_HEAD).
$EXPORT_COUNT Dateien: +$ADDED neu, ~$MODIFIED geändert, -$DELETED entfernt.
Nicht enthalten: $(IFS=', '; echo "${EXCLUDED_DIRS[*]}")/ (interne Notizen,
Betriebsfunk, Agenten-Konfiguration)."

git -C "$PUBLIC_CLONE" \
    -c "user.name=$COMMIT_NAME" -c "user.email=$COMMIT_EMAIL" \
    commit --quiet -m "$COMMIT_MSG"
NEW_COMMIT="$(git -C "$PUBLIC_CLONE" rev-parse --short HEAD)"
ok "Commit $NEW_COMMIT als $COMMIT_NAME <$COMMIT_EMAIL>"

git -C "$PUBLIC_CLONE" \
    -c "user.name=$COMMIT_NAME" -c "user.email=$COMMIT_EMAIL" \
    tag -a "$TAG" -m "Hoshi $VERSION"
ok "Tag $TAG gesetzt"
echo

# ─────────────────────────────────────────────────────────────────────────────
# (7) STOPP vor dem Push
# ─────────────────────────────────────────────────────────────────────────────

PUSH_CMD_BRANCH="git -C $PUBLIC_CLONE push origin $PUBLIC_BRANCH"
PUSH_CMD_TAG="git -C $PUBLIC_CLONE push origin $TAG"

if [ "$DO_PUSH" = 1 ]; then
    say "(7) Push — Bestätigung erforderlich"
    warn "Der Push ist NICHT zurückzunehmen: was auf GitHub landet, bleibt über"
    warn "API, Forks und Caches abrufbar — auch nach einem späteren force-push."
    log "Ziel: $PUBLIC_REPO ($PUBLIC_BRANCH + $TAG), $ADDED neu / $MODIFIED geändert / $DELETED gelöscht"
    printf "  Zum Veröffentlichen exakt '%s' tippen: " "$TAG"
    read -r CONFIRM
    if [ "$CONFIRM" != "$TAG" ]; then
        warn "Nicht bestätigt — kein Push. Commit und Tag liegen im Klon bereit."
        echo
        log "$PUSH_CMD_BRANCH"
        log "$PUSH_CMD_TAG"
        exit 0
    fi
    $PUSH_CMD_BRANCH
    $PUSH_CMD_TAG
    ok "Veröffentlicht: https://github.com/$PUBLIC_REPO/releases/tag/$TAG"
    exit 0
fi

say "(7) ${C_GREEN}Trockenlauf fertig — es wurde NICHTS gepusht.${C_RESET}"
log "Commit $NEW_COMMIT und Tag $TAG liegen im Klon bereit:"
log "  $PUBLIC_CLONE"
echo
log "Vorher prüfen:"
log "  git -C $PUBLIC_CLONE show --stat HEAD"
log "  git -C $PUBLIC_CLONE diff $PUBLIC_HEAD..HEAD --stat"
echo
log "Veröffentlichen (bewusst von Hand):"
echo "      ${C_BOLD}$PUSH_CMD_BRANCH${C_RESET}"
echo "      ${C_BOLD}$PUSH_CMD_TAG${C_RESET}"
echo
log "Verwerfen: git -C $PUBLIC_CLONE tag -d $TAG && git -C $PUBLIC_CLONE reset --hard origin/$PUBLIC_BRANCH"
exit 0
