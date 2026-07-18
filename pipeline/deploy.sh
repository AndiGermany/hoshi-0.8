#!/usr/bin/env bash
# pipeline/deploy.sh — LOKALER launchd-Deploy für das Hoshi-0.8-Backend.
#
# Was das tut (Default / --local): installiert das 0.8-Backend (web-inbound
# Spring-Boot-bootJar) als persistenten, self-healing launchd-User-Service auf
# dem Mac (Label io.hoshi.0.8.backend, :8090, HA-scharf, echtes logback-Logging)
# UND verifiziert es danach OHNE Smart-Home-Aktion:
#   1. JDK + Perimeter-Token bestimmen (Token aus ~/.hoshi/secrets.json["api"],
#      sonst frisch generiert + gemerged zurückgeschrieben — Wert NIE geloggt).
#   2. ./gradlew :web-inbound:bootJar --rerun-tasks  (frisches Bündeln).
#   3. Plist aus tools/launchd/io.hoshi.0.8.backend.plist.template rendern
#      (sed __REPO__/__HOME__/__JAVA__/__TOKEN__) → ~/Library/LaunchAgents/.
#   4. Service idempotent (re)laden: bootout (ignoriert) + bootstrap, sonst
#      kickstart -k.
#   5. Auf Health warten (max 60s, GET /api/health == 200).
#   6. Verifikation OHNE Licht:
#        (a) 401-Wand: /api/v1/ping über LAN-IP (= nicht-loopback) ohne Token
#            → 401, mit Bearer-Token → 200.
#        (b) 1 NICHT-Licht-Turn: POST /api/v1/chat/stream ("Sag in einem warmen
#            Satz Hallo.") → nicht-leere Antwort.
#        (c) Beleg, dass das launchd-Log echte logback-Zeilen hat (kein SLF4J-NOP).
#   7. Ehrlicher Report + Exit-Code.
#
# Subcommands:
#   bash pipeline/deploy.sh                  # lokaler Deploy (= --local)
#   bash pipeline/deploy.sh --local
#   bash pipeline/deploy.sh --status         # Service-Status + /api/health + doctor-Hinweis
#   bash pipeline/deploy.sh --down           # Service stoppen (launchctl bootout)
#   bash pipeline/deploy.sh --remote --https # PARALLEL-Deploy nach ct-106 (:8082) MIT TLS (Standard-Weg!)
#   bash pipeline/deploy.sh --remote         # dito, aber HTTP — Achtung: rendert TLS RAUS (nur bewusst nutzen)
#   bash pipeline/deploy.sh --remote --status   # ct-106: systemctl status + journal + health
#   bash pipeline/deploy.sh --remote --rollback # ct-106: web-inbound.jar.prev zurück + restart
#
# Der LOKALE Deploy erbt die launchd-Mechanik (sed-Templating + bootout→bootstrap
# →kickstart) von Hoshi_0.5/tools/hoshi-launchd-install.sh, mit echten Verhaltens-
# Checks statt nur Health-200. Kein SSH, kein CI.
#
# Der REMOTE-Deploy (--remote) erbt den bewährten 0.5-Mechanismus aus
# Hoshi_0.5/tools/hoshi-deploy.sh: bootJar → scp .new → Backup .prev + atomic mv
# → PORT-WACHE (0.5-erwacht-Check + fuser -k :8082, Doppel-Incident 2026-07-06)
# → systemctl restart → Health-Poll → Rollback. Er deployt 0.8 PARALLEL zu 0.5
# (eigener Port :8082, eigenes /opt/hoshi-0.8, eigene Unit hoshi-0.8-backend) —
# 0.5 bleibt UNANGETASTET. ct-106-SSH ist Andi-Gate: ohne erreichbares SSH bricht
# --remote SAUBER ab (exit 4, Runbook-Hinweis), versucht NIE blind scp/systemctl.
# Härtung 2026-07-12: die 3 Secrets (HOSHI_API_TOKEN/HOSHI_HA_TOKEN/OPENAI_API_KEY)
# landen NICHT mehr inline in der Unit, sondern in einer root-only EnvironmentFile
# (/etc/hoshi-0.8/secrets.env, chmod 600) — siehe write_remote_secrets_env().

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# lib.sh setzt `set -euo pipefail`; für die Verify-Phase wollen wir Fehler
# selbst behandeln (Checks zählen, nicht hart aussteigen).
set +e

# ── Konstanten ────────────────────────────────────────────────────────────────
LABEL="io.hoshi.0.8.backend"
PORT=8090
JAR="$REPO_ROOT/web-inbound/build/libs/web-inbound-0.8.0.jar"
TEMPLATE="$REPO_ROOT/tools/launchd/${LABEL}.plist.template"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST="$LAUNCH_AGENTS_DIR/${LABEL}.plist"
HOSHI_HOME="$HOME/.hoshi"
SECRETS="$HOSHI_HOME/secrets.json"
SVC_LOG="$HOSHI_HOME/logs/hoshi-0.8-backend.log"
HEALTH_WAIT_S=60

# ── Remote-/PARALLEL-Deploy-Konstanten (ct-106, neben 0.5:8081) ───────────────
# Alle override-bar per Env. Default: ct-106-SSH-Alias, /opt/hoshi-0.8, eigene
# systemd-Unit, Port 8082 — strikt parallel zum produktiven 0.5 (UNANGETASTET).
REMOTE_HOST="${HOSHI_08_REMOTE:-ct-106}"
REMOTE_DIR="${HOSHI_08_REMOTE_DIR:-/opt/hoshi-0.8}"
REMOTE_UNIT="${HOSHI_08_REMOTE_UNIT:-hoshi-0.8-backend}"
REMOTE_PORT="${HOSHI_08_REMOTE_PORT:-8082}"
# Die ALTE 0.5-Unit auf ct-106 — eigentlich gestoppt+disabled, aber sie ERWACHTE
# 2× am 2026-07-06 und ihr MCP-Launcher griff sich :8082 (→ Port-Wache unten).
LEGACY_UNIT="${HOSHI_05_REMOTE_UNIT:-hoshi-backend}"
REMOTE_JAR="$REMOTE_DIR/web-inbound.jar"
UNIT_TEMPLATE="$REPO_ROOT/tools/systemd/${REMOTE_UNIT}.service"
# BatchMode=yes ⇒ kein interaktiver Prompt/Passwort/Host-Key-Frage (fail-fast,
# kein Hängen). ConnectTimeout bremst unerreichbare Hosts.
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=8)
REMOTE_HEALTH_TRIES=30   # × 2s = max 60s Boot-Fenster
SSH_GATE_RC=4            # SSH-Vorbedingung nicht erfüllt = Andi-Gate (kein Crash)

# ── 🔒 HTTPS-Gate (Secure-Context fürs Browser-Mikro) ─────────────────────────
# HOSHI_HTTPS_ENABLED flippt den GESAMTEN TLS-Pfad in EINER Variable: Unit-Render
# (SERVER_SSL_ENABLED) + Health-Poll-Scheme. Default OFF ⇒ reines HTTP wie bisher
# (byte-neutral). ON ⇒ Backend serviert :$REMOTE_PORT als HTTPS (self-signed PKCS12),
# Health-Poll nutzt https + curl -k. Rollback = HOSHI_HTTPS_ENABLED=false + redeploy.
HTTPS_ENABLED="${HOSHI_HTTPS_ENABLED:-false}"
case "$HTTPS_ENABLED" in true|1|yes|on) HTTPS_ON=1 ;; *) HTTPS_ON=0 ;; esac
# Self-signed Keystore-Passwort: KEIN Default mehr (Härtung 2026-07-15, OSS-Audit —
# ein Default-Passwort wäre für jeden Repo-Leser sichtbar/erratbar gewesen). MUSS
# vor einem --https-Deploy per HOSHI_SSL_KEYSTORE_PASSWORD gesetzt sein; Cert-Gen
# (Runbook Schritt 1) MUSS dasselbe Passwort nutzen. Fail-closed-Check weiter unten
# (nach der Argument-Auswertung, weil --https HTTPS_ON erst dort final setzt).
# (Passwort ohne sed-Sonderzeichen '|' & '&' wählen — render_unit nutzt '|' als Delimiter.)
SSL_KEYSTORE_PW="${HOSHI_SSL_KEYSTORE_PASSWORD:-}"
# Fallback-Quelle (wie alle Secrets): lokale secrets.json["keystore"] — kein
# Default im Repo mehr (OSS Ring 1), aber auch kein Passwort auf der Kommandozeile.
if [ -z "$SSL_KEYSTORE_PW" ] && [ -f "$HOME/.hoshi/secrets.json" ]; then
    SSL_KEYSTORE_PW="$(python3 -c 'import json,os,sys
try:
    v=json.load(open(os.path.expanduser("~/.hoshi/secrets.json"))).get("keystore","")
    sys.stdout.write(v if isinstance(v,str) else "")
except Exception:
    pass' 2>/dev/null)"
fi
if [ "$HTTPS_ON" = "1" ]; then
    SSL_ENABLED_VAL="true";  HEALTH_SCHEME="https"; CURL_K="-k"
else
    SSL_ENABLED_VAL="false"; HEALTH_SCHEME="http";  CURL_K=""
fi

usage() {
    sed -n '2,42p' "$0"
}

# ── JDK bestimmen (dieselbe JDK 21, mit der gebaut wird) ──────────────────────
resolve_java() {
    local jbin="java"
    local ghome
    ghome="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
    if [ -n "$ghome" ] && [ -x "$ghome/bin/java" ]; then
        jbin="$ghome/bin/java"
    fi
    printf '%s' "$jbin"
}

# ── Perimeter-/API-Token auflösen (secrets.json["api"]) ───────────────────────
# Liest den Token via python3 aus ~/.hoshi/secrets.json["api"]. Fehlt er, wird
# einer generiert (openssl rand -hex 24) und per merge zurückgeschrieben (andere
# Keys bleiben, chmod 600). Der Wert wird NIE geloggt — nur Quelle + Länge.
resolve_token() {
    local tok=""
    if [ -f "$SECRETS" ]; then
        tok="$(python3 - "$SECRETS" <<'PY' 2>/dev/null
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        d = json.load(f)
    v = d.get("api", "")
    print(v if isinstance(v, str) else "")
except Exception:
    print("")
PY
)"
    fi

    if [ -n "$tok" ]; then
        TOKEN_SOURCE="gelesen aus secrets.json[api]"
    else
        tok="$(openssl rand -hex 24 2>/dev/null)"
        if [ -z "$tok" ]; then
            tok="$(python3 -c 'import secrets;print(secrets.token_hex(24))' 2>/dev/null)"
        fi
        # Merge zurückschreiben: andere Keys erhalten, chmod 600.
        mkdir -p "$HOSHI_HOME"
        python3 - "$SECRETS" "$tok" <<'PY' 2>/dev/null
import json, os, sys
path, tok = sys.argv[1], sys.argv[2]
d = {}
if os.path.exists(path):
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        if not isinstance(d, dict):
            d = {}
    except Exception:
        d = {}
d["api"] = tok
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(d, f, indent=2, ensure_ascii=False)
os.replace(tmp, path)
os.chmod(path, 0o600)
PY
        TOKEN_SOURCE="frisch generiert + nach secrets.json[api] geschrieben"
    fi
    TOKEN="$tok"
}

# ── HA-Token auflösen (nur für ct-106; Wert NIE geloggt) ──────────────────────
# Liest secrets.json["ha"] (DIESELBE Quelle wie resolveHaToken in 0.8). ct-106
# hat KEIN ~/.hoshi/secrets.json → der Token MUSS in die (root-only) secrets.env.
# Fehlt er, bleibt HA_TOKEN leer → remote_deploy setzt einen sichtbaren
# Platzhalter + warnt (Andi trägt ihn nach; ct-106 hat ihn schon im 0.5-/etc/hoshi.env).
resolve_ha_token() {
    HA_TOKEN=""
    [ -f "$SECRETS" ] || return 0
    HA_TOKEN="$(python3 - "$SECRETS" <<'PY' 2>/dev/null
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        d = json.load(f)
    v = d.get("ha", "")
    print(v if isinstance(v, str) else "")
except Exception:
    print("")
PY
)"
}

# ── OpenAI-Key auflösen (für Server-TTS auf ct-106; Wert NIE geloggt) ─────────
# Liest ~/.hoshi/openai.key (Mac-Quelle; ct-106 hat sie nicht). Fehlt sie, bleibt
# OPENAI_KEY leer → remote_deploy setzt einen Platzhalter (in secrets.env);
# HOSHI_TTS=openai braucht den echten Key.
resolve_openai_key() {
    OPENAI_KEY=""
    [ -f "$HOSHI_HOME/openai.key" ] || return 0
    OPENAI_KEY="$(tr -d '\n\r' < "$HOSHI_HOME/openai.key" 2>/dev/null)"
}

# ── Wetter-Ort (secrets.json["weather"]) ──────────────────────────────────────
# Seit 15.07 stehen die Heim-Koordinaten NICHT mehr im Unit-Template (OSS Ring 0:
# GPS raus aus dem Repo). Quelle ist die lokale secrets.json[weather]{lat,lon,label};
# write_remote_secrets_env rendert sie in die EnvironmentFile. Fehlt der Block,
# bleibt WEATHER_ENV leer → Backend fällt auf den Code-Default (Berlin) zurück
# und der Deploy WARNT laut, statt still den falschen Ort zu liefern.
resolve_weather() {
    WEATHER_ENV=""
    [ -f "$SECRETS" ] || return 0
    WEATHER_ENV="$(python3 - "$SECRETS" <<'PY' 2>/dev/null
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        w = json.load(f).get("weather") or {}
    lat, lon, label = w.get("lat"), w.get("lon"), w.get("label", "")
    if lat is None or lon is None:
        print("", end="")
    else:
        lines = [f"HOSHI_WEATHER_LAT={lat}", f"HOSHI_WEATHER_LON={lon}"]
        if label:
            lines.append(f"HOSHI_WEATHER_LABEL={label}")
        print("\n".join(lines), end="")
except Exception:
    print("", end="")
PY
)"
}

# ── LAN-IP (nicht-loopback) — damit die 401-Wand beweisbar ist ────────────────
# PerimeterPort lässt Loopback IMMER frei (dokumentiert): ein localhost-Request
# umginge die Wand. Der 401-Check muss daher über die LAN-IP laufen.
resolve_lan_ip() {
    local iface ip
    iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
    ip="$(ipconfig getifaddr "${iface:-en0}" 2>/dev/null)"
    [ -z "$ip" ] && ip="$(ipconfig getifaddr en0 2>/dev/null)"
    [ -z "$ip" ] && ip="$(ipconfig getifaddr en1 2>/dev/null)"
    printf '%s' "${ip:-127.0.0.1}"
}

# ── Service geladen? ─────────────────────────────────────────────────────────
svc_loaded() { launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; }

# ─────────────────────────────────────────────────────────────────────────────
#  --status
# ─────────────────────────────────────────────────────────────────────────────
do_status() {
    say "Status: $LABEL"
    if svc_loaded; then
        ok "launchd: geladen (gui/$UID/$LABEL)"
        launchctl print "gui/$UID/$LABEL" 2>/dev/null \
            | grep -E 'state|pid|program|last exit' | sed 's/^/    /'
    else
        warn "launchd: NICHT geladen (Service nicht installiert/aktiv)"
    fi
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 4 "http://localhost:$PORT/api/health" 2>/dev/null || echo 000)"
    if [ "$code" = "200" ]; then ok "Health: GET /api/health → 200"
    else warn "Health: GET /api/health → $code (erwartet 200)"; fi
    log "tieferer Stack-Check: bin/hoshi doctor"
    [ "$code" = "200" ] && return 0 || return 1
}

# ─────────────────────────────────────────────────────────────────────────────
#  --down
# ─────────────────────────────────────────────────────────────────────────────
do_down() {
    say "Service stoppen: $LABEL"
    if svc_loaded; then
        if launchctl bootout "gui/$UID/$LABEL" 2>/dev/null; then
            ok "bootout ok — Service gestoppt"
        else
            warn "bootout meldete Fehler (evtl. schon weg)"
        fi
    else
        log "Service war nicht geladen — nichts zu tun"
    fi
    return 0
}

# ─────────────────────────────────────────────────────────────────────────────
#  --remote  — PARALLEL-Deploy nach ct-106 (:8082, neben 0.5:8081)
#
#  Erbt den 0.5-Mechanismus (bootJar → scp .new → Backup .prev + atomic mv →
#  systemctl restart → Health-Poll → Rollback), deployt 0.8 aber STRIKT PARALLEL
#  (eigener Port/Dir/Unit) — 0.5 bleibt unangetastet. Dieses Skript FÜHRT das nur
#  aus, wenn ct-106-SSH erreichbar ist (Andi-Gate via Bash-Regel + Connectivity).
#  Sonst: sauberer Abbruch (exit $SSH_GATE_RC), kein blindes scp/systemctl.
# ─────────────────────────────────────────────────────────────────────────────

# SSH-Vorbedingung: ist $REMOTE_HOST per BatchMode-SSH erreichbar? (kein Prompt)
remote_ssh_ok() {
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" 'true' >/dev/null 2>&1
}

# Ehrlicher Gate-Abbruch, wenn ct-106-SSH NICHT geht. KEIN Fehler-Spam.
remote_gate_abort() {
    fail "ct-106-SSH ('$REMOTE_HOST') nicht erreichbar — ABBRUCH (kein scp/systemctl)."
    warn "Das ist das ERWARTETE Andi-Gate, kein Bug:"
    log  "  • ct-106-SSH braucht eine explizite Bash-Regel in .claude/settings.local.json"
    log  "    (z.B. Bash(ssh ct-106:*), Bash(scp *ct-106:*)) — die nur Andi setzen darf."
    log  "  • Und einen erreichbaren SSH-Alias '$REMOTE_HOST' (~/.ssh/config) mit Key-Auth."
    log  "Runbook: vault/tracks/CUTOVER-ct-106.md  ·  Override-Host: HOSHI_08_REMOTE=…"
    return "$SSH_GATE_RC"
}

# Health-Poll auf ct-106 (via ssh, loopback ⇒ Perimeter lässt durch, kein Token).
# 0.8 serviert PLAIN HTTP (die Wand ist der Bearer-Filter, KEIN TLS) → http
# primär; https -k als Fallback (Parität mit 0.5, falls je TLS davor kommt).
remote_health_poll() {
    say "Warte auf 0.8-Boot auf ct-106 (poll $HEALTH_SCHEME://:$REMOTE_PORT/api/health, max $((REMOTE_HEALTH_TRIES * 2))s)"
    local n=0 code
    while [ "$n" -lt "$REMOTE_HEALTH_TRIES" ]; do
        # Konfiguriertes Scheme zuerst ($HEALTH_SCHEME, +$CURL_K für self-signed), dann
        # https-k UND http als Fallback — robust während eines HTTP↔HTTPS-Flips.
        code="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" \
            "curl -sS $CURL_K -o /dev/null -w '%{http_code}' -m 3 $HEALTH_SCHEME://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null \
             || curl -ksS -o /dev/null -w '%{http_code}' -m 3 https://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null \
             || curl -sS  -o /dev/null -w '%{http_code}' -m 3 http://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null" \
            2>/dev/null)"
        if [ "$code" = "200" ]; then ok "Health 200 nach ~$((n * 2))s"; return 0; fi
        sleep 2; n=$((n + 1)); printf "."
    done
    echo
    return 1
}

# systemd-Unit aus tools/systemd/<unit>.service rendern (Tokens NIE geloggt).
# Härtung 2026-07-12: die 3 Secrets (API_TOKEN/HA_TOKEN/OPENAI_KEY) werden NICHT
# mehr in die Unit gerendert — sie landen in der separaten root-only
# EnvironmentFile (siehe write_remote_secrets_env). Die Unit selbst bleibt frei
# von Klartext-Secrets, `systemctl cat` zeigt nur noch den Datei-Pfad.
render_unit() {
    local out="$1"
    sed -e "s|__REMOTE_DIR__|$REMOTE_DIR|g" \
        -e "s|__REMOTE_PORT__|$REMOTE_PORT|g" \
        -e "s|__MAC_IP__|$MAC_IP|g" \
        -e "s|__SSL_ENABLED__|$SSL_ENABLED_VAL|g" \
        -e "s|__SSL_KEYSTORE_PW__|$SSL_KEYSTORE_PW|g" \
        -e "s|__BRAIN_EXPECTED__|$(resolve_brain_expected)|g" \
        "$UNIT_TEMPLATE" > "$out"
}

# BOOT-Default-Soll der Ops-Pille aus DERSELBEN Wahl wie der Brain-Start (Zwilling
# der resolve_brain_model-Tabelle in pipeline/stack-lib.sh — Kurzname reicht, die
# Drift-Prüfung ist ein contains() auf der vollen Modell-ID). Unbekannte Wahl ⇒
# leer ⇒ Backend prüft keine Erwartung (ehrlicher als eine geratene).
#
# NUR der Boot-Fallback: sobald jemand per PUT /api/v1/settings/brain zur Laufzeit
# umschaltet, überschreibt das GEWÄHLTE Modell dieses Literal im Backend
# (JsonFileBrainModelStore, s. SidecarHealthService.kt) — dieser Deploy-Wert gilt
# nur, bis der erste Runtime-Switch passiert bzw. nach einem Neustart ohne
# Runtime-Switch (Andi-Befund 2026-07-20: vorher war dies die EINZIGE Soll-Quelle,
# ein bewusster Runtime-Wechsel meldete darum fälschlich „Drift").
resolve_brain_expected() {
    case "${HOSHI_BRAIN_MODEL:-e4b}" in
        e2b|E2B) printf 'gemma-4-e2b-it-4bit' ;;
        e4b|E4B) printf 'gemma-4-e4b-it-4bit' ;;
        *)       printf '%s' "${HOSHI_BRAIN_MODEL:-}" ;;
    esac
}

# ── 🔒 Secrets → root-only EnvironmentFile (statt inline in der Unit) ─────────
# HÄRTUNG 2026-07-12: bis dahin standen HOSHI_API_TOKEN/HOSHI_HA_TOKEN/
# OPENAI_API_KEY als inline `Environment=<wert>` in der gerenderten Unit — ein
# `systemctl cat hoshi-0.8-backend` dumpte sie im Klartext (so leakten sie real
# in ein Transcript, 2026-07-11). Jetzt schreiben wir sie in eine root-only
# EnvironmentFile (/etc/hoshi-0.8/secrets.env, chmod 600, owner root); die Unit
# referenziert nur noch den PFAD (EnvironmentFile=…, siehe Service-Template).
# Die Werte gehen NIE als CLI-Argument raus (wäre lokal UND remote per `ps`
# sichtbar) — sie laufen als Heredoc über ssh-STDIN direkt in `cat > …` auf
# ct-106. Idempotent: jeder Re-Deploy überschreibt die Datei sauber.
# EHRLICH (keine Übertreibung): das schützt gegen `systemctl cat` + rohe
# Datei-Dumps der Unit (der reale Vektor vom 2026-07-11-Leak). Es schützt NICHT
# gegen `systemctl show -p Environment` — dort löst systemd EnvironmentFile-
# Werte trotzdem sichtbar auf. Wer root auf ct-106 hat, sieht die Secrets so
# oder so (das war nie das Bedrohungsmodell).
write_remote_secrets_env() {
    local remote_cmd
    remote_cmd="mkdir -p /etc/hoshi-0.8 \
&& chown root:root /etc/hoshi-0.8 \
&& chmod 0755 /etc/hoshi-0.8 \
&& umask 077 \
&& cat > /etc/hoshi-0.8/secrets.env \
&& chown root:root /etc/hoshi-0.8/secrets.env \
&& chmod 600 /etc/hoshi-0.8/secrets.env"
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "$remote_cmd" >/dev/null 2>&1 <<SECRETS_EOF
HOSHI_API_TOKEN=$API_TOKEN
HOSHI_HA_TOKEN=$HA_TOKEN
OPENAI_API_KEY=$OPENAI_KEY
$WEATHER_ENV
SECRETS_EOF
}

# ── 🛡️ PORT-WACHE + (re)start — statt nacktem `systemctl restart` ─────────────
# DOPPEL-INCIDENT 2026-07-06 (2× am selben Tag): das gestoppt+disabled geglaubte
# 0.5-Backend ($LEGACY_UNIT) erwachte, sein MCP-Launcher band :8082 → 0.8 crash-
# loopte (ChannelBindException), der Deploy-Health-Poll blieb rot, Andi musste
# manuell `fuser -k`. Darum läuft VOR dem Start — über DENSELBEN SSH-Kanal/Alias
# wie der restart selbst — eine Wache in EINEM ssh-Call:
#   1. `systemctl is-active $LEGACY_UNIT` → aktiv? Marker (→ LAUTE Warnung lokal,
#      Empfehlung mask). Wir stoppen/masken 0.5 NICHT selbst — das ist Andis Call.
#   2. eigenen Service stoppen, DANN prüfen, ob :$REMOTE_PORT noch belegt ist
#      (= nur noch Fremd-Halter möglich) → `fuser -k … || true` + 2s Socket-Frist.
#   3. frischer `systemctl start` → danach Health-Poll wie gehabt.
# Idempotent + STILL, wenn alles sauber (keine Marker ⇒ keine Warnzeilen).
remote_guarded_restart() {
    say "Port-Wache (:$REMOTE_PORT, 0.5-Check: $LEGACY_UNIT) + systemctl restart $REMOTE_UNIT"
    local guard_out
    guard_out="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "
        set -e
        if systemctl is-active --quiet $LEGACY_UNIT 2>/dev/null; then
            echo GUARD_05_ACTIVE
            # Andis stehende Order (Cutover 2026-06-27: nur noch 0.8) + sein
            # disable von 06.07 reichte 2x nicht -> stop + mask (reversibel:
            # systemctl unmask $LEGACY_UNIT && systemctl enable --now ...).
            systemctl stop $LEGACY_UNIT 2>/dev/null || true
            # mask scheitert, wenn das Unit-File DIREKT in /etc/systemd/system/
            # liegt (dort will mask seinen /dev/null-Symlink anlegen) — genau
            # unser Fall (Befund 08.07: Wache meldete GEMASKT, real nur
            # disabled). Darum: Unit-File erst beiseite legen (reversibel,
            # bleibt als .0.5-archiv daneben liegen), dann mask — und Erfolg
            # MESSEN statt behaupten (is-enabled == masked), sonst ehrlicher
            # Marker statt falscher Erfolgsmeldung.
            if [ -f /etc/systemd/system/$LEGACY_UNIT.service ]; then
                mv /etc/systemd/system/$LEGACY_UNIT.service /etc/systemd/system/$LEGACY_UNIT.service.0.5-archiv 2>/dev/null || true
                systemctl daemon-reload 2>/dev/null || true
            fi
            systemctl mask $LEGACY_UNIT 2>/dev/null || true
            if [ \"\$(systemctl is-enabled $LEGACY_UNIT 2>&1)\" = masked ]; then
                echo GUARD_05_MASKED
            else
                echo GUARD_05_MASK_FAILED
            fi
        fi
        systemctl stop $REMOTE_UNIT 2>/dev/null || true
        if fuser $REMOTE_PORT/tcp >/dev/null 2>&1; then
            echo GUARD_PORT_HELD
            fuser -k $REMOTE_PORT/tcp >/dev/null 2>&1 || true
            sleep 2
        fi
        systemctl start $REMOTE_UNIT
    " 2>/dev/null)" || { fail "Port-Wache/systemctl start $REMOTE_UNIT fehlgeschlagen"; return 1; }
    if printf '%s\n' "$guard_out" | grep -q 'GUARD_05_ACTIVE'; then
        warn "🚨 0.5-BACKEND ($LEGACY_UNIT) IST WIEDER AKTIV — sein MCP greift :$REMOTE_PORT und crash-loopt 0.8 (Doppel-Incident 2026-07-06)!"
        if printf '%s\n' "$guard_out" | grep -qx 'GUARD_05_MASKED'; then
            warn "   disable hat 2× NICHT gereicht — die Wache hat 0.5 GESTOPPT+GEMASKT, Erfolg GEMESSEN (is-enabled=masked; Andis stehende Cutover-Order 27.06.)."
            warn "   Rückweg falls je gebraucht: ssh ct-106 'systemctl unmask $LEGACY_UNIT; mv /etc/systemd/system/$LEGACY_UNIT.service.0.5-archiv /etc/systemd/system/$LEGACY_UNIT.service; systemctl daemon-reload; systemctl enable --now $LEGACY_UNIT'"
        else
            warn "   ⚠️ 0.5 GESTOPPT, aber MASK NICHT GEGRIFFEN (is-enabled != masked) — manuell nachziehen:"
            warn "   ssh ct-106 'mv /etc/systemd/system/$LEGACY_UNIT.service /etc/systemd/system/$LEGACY_UNIT.service.0.5-archiv; systemctl daemon-reload; systemctl mask $LEGACY_UNIT'"
        fi
    fi
    if printf '%s\n' "$guard_out" | grep -q 'GUARD_PORT_HELD'; then
        warn "Port :$REMOTE_PORT war nach dem Stop von $REMOTE_UNIT noch von einem FREMD-Prozess belegt — per fuser -k geräumt (+2s)"
    fi
    ok "$REMOTE_UNIT restarted (Port-Wache durchlaufen)"
    return 0
}

# ── --remote (Default-Action: deploy) ─────────────────────────────────────────
remote_deploy() {
    cd "$REPO_ROOT" || { fail "REPO_ROOT nicht erreichbar"; return 1; }

    # (1) Vorbedingungen — ssh/scp/gradlew lokal da, dann SSH-Connectivity (Gate).
    say "Vorbedingungen (ct-106-Parallel-Deploy)"
    command -v ssh  >/dev/null 2>&1 || { fail "ssh fehlt"; return 1; }
    command -v scp  >/dev/null 2>&1 || { fail "scp fehlt"; return 1; }
    [ -x "$GRADLEW" ] || { fail "gradlew nicht ausführbar: ${GRADLEW#$REPO_ROOT/}"; return 1; }
    [ -f "$UNIT_TEMPLATE" ] || { fail "Unit-Template fehlt: ${UNIT_TEMPLATE#$REPO_ROOT/}"; return 1; }
    ok "ssh + scp + gradlew + Unit-Template vorhanden"
    log "Ziel: $REMOTE_HOST:$REMOTE_DIR  ·  Unit: $REMOTE_UNIT  ·  Port: $REMOTE_PORT (parallel zu 0.5:8081)"

    say "SSH-Vorbedingung: '$REMOTE_HOST' erreichbar?"
    if ! remote_ssh_ok; then
        remote_gate_abort; return "$SSH_GATE_RC"
    fi
    ok "SSH zu $REMOTE_HOST ok"

    # (1b) HTTPS-Gate: Modus melden + bei ON den Keystore VORHER prüfen. Fehlt die
    #      hoshi.p12, würde das Backend mit SERVER_SSL_ENABLED=true beim Start crashen
    #      (kaputter Keystore) → wir brechen SAUBER ab und nennen den Cert-Gen-Schritt,
    #      statt den Stack unerreichbar zu deployen (Nils-Veto: kein Change ohne
    #      getesteten Rollback / verifizierte Erreichbarkeit).
    if [ "$HTTPS_ON" = "1" ]; then
        say "HTTPS-Modus AN (HOSHI_HTTPS_ENABLED) — :$REMOTE_PORT wird per TLS serviert"
        if ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "test -f $REMOTE_DIR/hoshi.p12" 2>/dev/null; then
            ok "Keystore vorhanden: $REMOTE_HOST:$REMOTE_DIR/hoshi.p12"
        else
            fail "HOSHI_HTTPS_ENABLED=true, aber $REMOTE_HOST:$REMOTE_DIR/hoshi.p12 FEHLT — Abbruch (Backend würde beim Start crashen)."
            warn "Erst den EINMALIGEN Cert-Gen-Schritt fahren (Runbook Schritt 1), DANN re-deployen:"
            log  "  ssh $REMOTE_HOST 'P=$REMOTE_DIR/hoshi.p12; [ -f \"\$P\" ] || { openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes -keyout /tmp/h.key -out /tmp/h.crt -subj \"/CN=192.168.178.106\" -addext \"subjectAltName=IP:192.168.178.106,DNS:ct-106,DNS:ct-106.local,IP:127.0.0.1,DNS:localhost\" && openssl pkcs12 -export -inkey /tmp/h.key -in /tmp/h.crt -out \"\$P\" -name hoshi -passout pass:$SSL_KEYSTORE_PW && chown hoshi:hoshi \"\$P\" && chmod 640 \"\$P\" && rm -f /tmp/h.key /tmp/h.crt; }'"
            return 1
        fi
    else
        log "HTTPS-Modus AUS (Default) — :$REMOTE_PORT bleibt reines HTTP (byte-neutral)"
    fi

    # (2) bootJar bauen (frisches Bündeln, wie der lokale Deploy).
    say "bootJar bauen — ./gradlew :web-inbound:bootJar --rerun-tasks"
    ensure_log_dir
    local build_log="$PIPELINE_LOG_DIR/deploy-remote-build-$(timestamp).log"
    if ! "$GRADLEW" -q :web-inbound:bootJar --rerun-tasks >"$build_log" 2>&1; then
        fail "Build fehlgeschlagen — siehe ${build_log#$REPO_ROOT/}"
        tail -25 "$build_log"
        return 1
    fi
    [ -f "$JAR" ] || { fail "Erwartetes Jar fehlt: ${JAR#$REPO_ROOT/}"; return 1; }
    ok "Artefakt: ${JAR#$REPO_ROOT/} ($(du -h "$JAR" | awk '{print $1}'))"

    # (3) Token + Mac-IP auflösen (Werte NIE geloggt) — für secrets.env + Unit.
    say "Secret-/Unit-Parameter auflösen (Token + Mac-IP)"
    resolve_token;    API_TOKEN="$TOKEN"
    resolve_ha_token
    MAC_IP="$(resolve_lan_ip)"
    ok "API-Token: $TOKEN_SOURCE (Länge ${#API_TOKEN})"
    if [ -n "$HA_TOKEN" ]; then ok "HA-Token: aus secrets.json[ha] (Länge ${#HA_TOKEN})"
    else warn "HA-Token: nicht in secrets.json[ha] → secrets.env bekommt Platzhalter (Andi trägt nach)"; HA_TOKEN="REPLACE_WITH_HA_TOKEN"; fi
    resolve_openai_key
    if [ -n "$OPENAI_KEY" ]; then ok "OpenAI-Key: aus ~/.hoshi/openai.key (Länge ${#OPENAI_KEY}) → Server-TTS"
    else warn "OpenAI-Key: nicht in ~/.hoshi/openai.key → Voice-TTS bleibt aus"; OPENAI_KEY="REPLACE_WITH_OPENAI_KEY"; fi
    resolve_weather
    if [ -n "$WEATHER_ENV" ]; then ok "Wetter-Ort: aus secrets.json[weather] → secrets.env"
    else warn "Wetter-Ort: nicht in secrets.json[weather] → Backend fällt auf Berlin-Default zurück"; fi
    if [ "$MAC_IP" != "127.0.0.1" ]; then ok "Mac-IP (Brain :8041): $MAC_IP"
    else warn "Mac-IP nicht gefunden → Unit bekommt Platzhalter (DHCP-Drift, vgl. /etc/hoshi.env HOSHI_OMNI_URL)"; MAC_IP="REPLACE_WITH_MAC_IP"; fi

    # (4) Remote-Verzeichnis sicherstellen + scp .new (atomic-rename-Pattern).
    say "scp → $REMOTE_HOST:$REMOTE_JAR.new"
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "mkdir -p $REMOTE_DIR" 2>/dev/null \
        || { fail "mkdir -p $REMOTE_DIR auf $REMOTE_HOST fehlgeschlagen (Rechte? User?)"; return 1; }
    # Datenverzeichnis für persistente Adapter (Entity-Memory sqlite) — hoshi-eigen,
    # weil der Service-User kein Home hat + /opt nicht schreiben darf (sonst Bean-Crash).
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "mkdir -p /var/lib/hoshi-0.8 && chown hoshi:hoshi /var/lib/hoshi-0.8" 2>/dev/null \
        || warn "Datenverzeichnis /var/lib/hoshi-0.8 (chown hoshi) meldete Fehler — Entity-Memory braucht es schreibbar"
    if ! scp "${SSH_OPTS[@]}" -q "$JAR" "$REMOTE_HOST:$REMOTE_JAR.new"; then
        fail "scp fehlgeschlagen"; return 1
    fi
    ok "scp fertig"

    # (5) atomic swap auf ct-106 (Backup .prev + mv).
    say "Atomic swap (Backup web-inbound.jar.prev + mv .new → .jar)"
    if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "
        set -e
        cd $REMOTE_DIR
        [ -f web-inbound.jar ] && cp -p web-inbound.jar web-inbound.jar.prev || true
        mv -f web-inbound.jar.new web-inbound.jar
    " 2>/dev/null; then
        fail "Atomic swap fehlgeschlagen"; return 1
    fi
    ok "Atomic swap fertig"

    # (5b) Secrets → root-only EnvironmentFile auf ct-106 (Härtung 2026-07-12,
    #      s.o.). MUSS vor dem restart (Schritt 7) stehen — die Werte gehen per
    #      ssh-Heredoc/STDIN raus, NIE als CLI-Argument (ps-sichtbar), NIE geloggt.
    say "secrets.env schreiben (root-only, chmod 600): $REMOTE_HOST:/etc/hoshi-0.8/secrets.env"
    if ! write_remote_secrets_env; then
        fail "secrets.env-Schreiben fehlgeschlagen (ssh/Rechte? root nötig für chown)"; return 1
    fi
    ok "secrets.env geschrieben (3 Werte, root:root, chmod 600 — Werte nicht geloggt)"

    # (6) systemd-Unit IMMER aus dem Template rendern + installieren. Seit 0.5 weg ist,
    #     ist das Template (tools/systemd/hoshi-0.8-backend.service) die SINGLE SOURCE OF
    #     TRUTH für die Naht-Flags (Grounding/Memory/Ambient/HA/Tools). Backup der alten
    #     Unit als .prev, stale drop-ins bereinigt, daemon-reload. Die Unit selbst enthält
    #     seit der Härtung 2026-07-12 KEINE Secrets mehr (nur EnvironmentFile=-Pfad).
    say "systemd-Unit rendern + installieren (Template = Source of Truth): /etc/systemd/system/$REMOTE_UNIT.service"
    local rendered="$PIPELINE_LOG_DIR/$REMOTE_UNIT.service.rendered"
    ensure_log_dir
    render_unit "$rendered" || { fail "Unit-Rendering (sed) fehlgeschlagen"; return 1; }
    chmod 600 "$rendered"
    if ! scp "${SSH_OPTS[@]}" -q "$rendered" "$REMOTE_HOST:/etc/systemd/system/$REMOTE_UNIT.service.new"; then
        rm -f "$rendered"; fail "scp der Unit fehlgeschlagen (Rechte? root nötig)"; return 1
    fi
    rm -f "$rendered"   # lokale Kopie aufräumen (enthält seit der Härtung ohnehin keine Secrets mehr)
    if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "
        set -e
        cd /etc/systemd/system
        [ -f $REMOTE_UNIT.service ] && cp -p $REMOTE_UNIT.service $REMOTE_UNIT.service.prev || true
        mv -f $REMOTE_UNIT.service.new $REMOTE_UNIT.service
        rm -rf $REMOTE_UNIT.service.d
        systemctl daemon-reload
        systemctl enable $REMOTE_UNIT
    " 2>/dev/null; then
        warn "Unit-Install/daemon-reload meldete Fehler"
    fi
    ok "Unit installiert (Template-Render, alte als .prev, drop-ins bereinigt, daemon-reload)"

    # (7) PORT-WACHE + restart + Health-Poll (Doppel-Incident 2026-07-06, s.o.).
    remote_guarded_restart || return 1

    if remote_health_poll; then
        echo
        say "${C_GREEN}REMOTE-DEPLOY GRÜN${C_RESET} — 0.8 läuft auf $REMOTE_HOST:$REMOTE_PORT (parallel zu 0.5:8081)."
        log "HA-Turn echt testbar (Linux kann LAN): siehe Runbook Verifikation."
        log "Status: bash pipeline/deploy.sh --remote --status  ·  Rollback: --remote --rollback"
        return 0
    fi
    fail "Health auf $REMOTE_HOST:$REMOTE_PORT nicht 200 — letzte journal-Zeilen:"
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "journalctl -u $REMOTE_UNIT -n 30 --no-pager" 2>/dev/null | sed 's/^/    /'
    log "Rollback: bash pipeline/deploy.sh --remote --rollback"
    return 1
}

# ── --remote --status ─────────────────────────────────────────────────────────
remote_status() {
    say "ct-106-Status: $REMOTE_UNIT (Port $REMOTE_PORT)"
    if ! remote_ssh_ok; then remote_gate_abort; return "$SSH_GATE_RC"; fi
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "systemctl status $REMOTE_UNIT --no-pager -n 0 2>/dev/null || true"
    echo
    local code
    code="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" \
        "curl -sS $CURL_K -o /dev/null -w '%{http_code}' -m 3 $HEALTH_SCHEME://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null \
         || curl -ksS -o /dev/null -w '%{http_code}' -m 3 https://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null \
         || curl -sS  -o /dev/null -w '%{http_code}' -m 3 http://127.0.0.1:$REMOTE_PORT/api/health 2>/dev/null" 2>/dev/null)"
    if [ "$code" = "200" ]; then ok "Health ($HEALTH_SCHEME): :$REMOTE_PORT/api/health → 200"
    else warn "Health: :$REMOTE_PORT/api/health → ${code:-000} (erwartet 200)"; fi
    say "Letzte 30 journal-Zeilen"
    ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "journalctl -u $REMOTE_UNIT -n 30 --no-pager" 2>/dev/null
    [ "$code" = "200" ] && return 0 || return 1
}

# ── --remote --rollback ───────────────────────────────────────────────────────
remote_rollback() {
    say "ct-106-Rollback: web-inbound.jar.prev → web-inbound.jar + restart"
    if ! remote_ssh_ok; then remote_gate_abort; return "$SSH_GATE_RC"; fi
    if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "
        set -e
        cd $REMOTE_DIR
        [ -f web-inbound.jar.prev ] || { echo 'kein Backup (.prev) vorhanden' >&2; exit 1; }
        mv -f web-inbound.jar.prev web-inbound.jar
    " 2>/dev/null; then
        fail "Rollback fehlgeschlagen (kein Backup oder Rechte?)"; return 1
    fi
    # Auch der Rollback-Restart läuft durch die Port-Wache (gleicher Failure-Mode:
    # 0.5 erwacht + hält :8082 → auch das .prev-Jar würde crash-loopen).
    remote_guarded_restart || return 1
    ok "Rollback fertig — $REMOTE_UNIT restarted"
    remote_health_poll && return 0 || return 1
}

# Dispatcher für --remote (Default deploy; --status / --rollback als Sub-Action).
do_remote() {
    case "$REMOTE_ACTION" in
        deploy)   remote_deploy ;;
        status)   remote_status ;;
        rollback) remote_rollback ;;
        *) fail "unbekannte --remote Sub-Action: $REMOTE_ACTION"; return 2 ;;
    esac
}

# ─────────────────────────────────────────────────────────────────────────────
#  LOKALER DEPLOY (Default / --local)
# ─────────────────────────────────────────────────────────────────────────────
do_local() {
    cd "$REPO_ROOT" || { fail "REPO_ROOT nicht erreichbar"; return 1; }

    # (1) JDK + Token
    say "JDK + Perimeter-Token bestimmen"
    JAVA_BIN="$(resolve_java)"
    ok "java: $JAVA_BIN"
    resolve_token
    [ -z "$TOKEN" ] && { fail "Token konnte nicht aufgelöst werden"; return 1; }
    ok "Token: $TOKEN_SOURCE (Länge ${#TOKEN})"

    # (2) bootJar bauen — WICHTIG --rerun-tasks (frisches adapters-Bündeln)
    say "bootJar bauen — ./gradlew :web-inbound:bootJar --rerun-tasks"
    local build_log="$PIPELINE_LOG_DIR/deploy-build-$(timestamp).log"
    ensure_log_dir
    if ! "$GRADLEW" -q :web-inbound:bootJar --rerun-tasks >"$build_log" 2>&1; then
        fail "Build fehlgeschlagen — siehe ${build_log#$REPO_ROOT/}"
        tail -25 "$build_log"
        return 1
    fi
    [ -f "$JAR" ] || { fail "Erwartetes Jar fehlt: ${JAR#$REPO_ROOT/}"; return 1; }
    ok "Artefakt: ${JAR#$REPO_ROOT/}"

    # (3) Plist rendern
    say "launchd-Plist rendern → $PLIST"
    [ -f "$TEMPLATE" ] || { fail "Template fehlt: ${TEMPLATE#$REPO_ROOT/}"; return 1; }
    mkdir -p "$LAUNCH_AGENTS_DIR" "$HOSHI_HOME/logs"
    # Token kann sed-Sonderzeichen enthalten? openssl hex = nur [0-9a-f], safe.
    sed -e "s|__REPO__|$REPO_ROOT|g" \
        -e "s|__HOME__|$HOME|g" \
        -e "s|__JAVA__|$JAVA_BIN|g" \
        -e "s|__TOKEN__|$TOKEN|g" \
        "$TEMPLATE" > "$PLIST" || { fail "sed-Rendering fehlgeschlagen"; return 1; }
    chmod 644 "$PLIST"
    ok "Plist geschrieben (Token eingebrannt, nicht geloggt)"

    # (4) Service idempotent (re)laden
    say "Service (re)laden: bootout → auf Port-frei warten → bootstrap → ggf. kickstart"
    launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
    # bootout ist ASYNC: das LABEL bleibt kurz in der launchd-Registry (Übergang),
    # und ein sofortiges bootstrap desselben Labels scheitert mit error 5 (live
    # gemessen 2026-06-26: bootstrap „nicht gegriffen" → Service down). Darum auf
    # echtes Deregistrieren pollen (svc_loaded false) UND Port-frei, statt zu raten.
    local pf
    for pf in $(seq 1 40); do
        # fertig, sobald Label deregistriert UND Port frei ist.
        if ! svc_loaded && ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
            break
        fi
        sleep 0.25
    done
    if svc_loaded; then
        warn "Label nach bootout noch geladen (Übergang) — bootstrap könnte error 5 geben"
    fi
    if launchctl bootstrap "gui/$UID" "$PLIST" 2>/dev/null; then
        ok "bootstrap ok"
    else
        warn "bootstrap nicht gegriffen — versuche kickstart -k"
        launchctl kickstart -k "gui/$UID/$LABEL" 2>/dev/null \
            || warn "kickstart meldete Fehler (Health-Poll entscheidet)"
    fi
    if svc_loaded; then ok "launchd: $LABEL ist geladen"; else
        warn "launchd: $LABEL NICHT geladen — Health-Poll entscheidet"
    fi

    # (5) Auf Health warten
    say "Warte auf Health (poll http://localhost:$PORT/api/health, max ${HEALTH_WAIT_S}s)"
    local ready=0 i code
    for i in $(seq 1 $((HEALTH_WAIT_S * 2))); do
        code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://localhost:$PORT/api/health" 2>/dev/null || echo 000)"
        if [ "$code" = "200" ]; then ready=1; ok "Health 200 nach ~$((i / 2))s"; break; fi
        sleep 0.5
    done
    if [ "$ready" -ne 1 ]; then
        fail "Health nicht 200 binnen ${HEALTH_WAIT_S}s — letzte Service-Log-Zeilen:"
        tail -30 "$SVC_LOG" 2>/dev/null | sed 's/^/    /'
        return 1
    fi

    # ── Verifikation OHNE Licht ───────────────────────────────────────────────
    local fails=0
    local lan_ip; lan_ip="$(resolve_lan_ip)"
    echo

    # (a) 401-Wand über LAN-IP (nicht-loopback)
    say "Verify (a) 401-Wand über LAN-IP $lan_ip (= nicht-loopback)"
    if [ "$lan_ip" = "127.0.0.1" ]; then
        warn "keine LAN-IP gefunden — 401-Check via Loopback-Bypass entschärft (ehrlich vermerkt)"
    fi
    local c_noauth c_auth
    c_noauth="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://$lan_ip:$PORT/api/v1/ping" 2>/dev/null || echo 000)"
    c_auth="$(curl -s -o /dev/null -w '%{http_code}' -m 5 -H "Authorization: Bearer $TOKEN" "http://$lan_ip:$PORT/api/v1/ping" 2>/dev/null || echo 000)"
    if [ "$c_noauth" = "401" ]; then ok "/api/v1/ping ohne Token → 401 (Wand dicht)"
    else fail "/api/v1/ping ohne Token → $c_noauth (erwartet 401)"; fails=$((fails+1)); fi
    if [ "$c_auth" = "200" ]; then ok "/api/v1/ping mit Bearer-Token → 200 (Token kommt durch)"
    else fail "/api/v1/ping mit Bearer-Token → $c_auth (erwartet 200)"; fails=$((fails+1)); fi

    # (b) 1 NICHT-Licht-Turn (warmes Hallo, KEINE Smart-Home-Eingabe)
    echo
    say "Verify (b) 1 NICHT-Licht-Turn: POST /api/v1/chat/stream"
    local sse_out="$PIPELINE_LOG_DIR/deploy-turn-$(timestamp).sse"
    curl -sN -m 90 -X POST \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"text":"Sag in einem warmen Satz Hallo.","language":"DE","speak":false}' \
        "http://localhost:$PORT/api/v1/chat/stream" >"$sse_out" 2>/dev/null
    local satz
    satz="$(python3 - "$sse_out" <<'PY'
import json, sys
texts = []
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            ev = json.loads(payload)
        except Exception:
            continue
        if ev.get("event") == "delta":
            texts.append(ev.get("text", ""))
print("".join(texts).strip())
PY
)"
    if [ -n "${satz// /}" ]; then
        ok "Turn lieferte nicht-leere Antwort: \"$satz\""
    else
        fail "Turn lieferte LEERE Antwort (SSE: ${sse_out#$REPO_ROOT/})"
        tail -10 "$sse_out" 2>/dev/null | sed 's/^/    /'
        fails=$((fails+1))
    fi

    # (c) echtes logback-Logging im Service-Log (kein SLF4J-NOP)
    echo
    say "Verify (c) echtes logback-Logging in ${SVC_LOG/#$HOME/~}"
    if [ ! -s "$SVC_LOG" ]; then
        fail "Service-Log fehlt oder ist leer: $SVC_LOG"; fails=$((fails+1))
    elif grep -qiE 'No SLF4J providers|SLF4J: Failed to load|NOP(LoggerFactory)?' "$SVC_LOG"; then
        fail "Service-Log enthält SLF4J-NOP-Warnung (Logging NICHT scharf):"
        grep -iE 'SLF4J' "$SVC_LOG" | head -3 | sed 's/^/    /'
        fails=$((fails+1))
    elif grep -qE ' (INFO|WARN|ERROR|DEBUG) ' "$SVC_LOG"; then
        ok "logback-Zeilen vorhanden (Level-Pattern erkannt)"
        grep -E ' (INFO|WARN|ERROR) ' "$SVC_LOG" | tail -2 | sed 's/^/    /'
    else
        warn "Service-Log nicht leer, aber kein klares Level-Pattern — letzte Zeilen:"
        tail -3 "$SVC_LOG" | sed 's/^/    /'
        # nicht-leeres Log ohne NOP-Warnung: kein harter Fail, aber sichtbar.
    fi

    # ── Report ────────────────────────────────────────────────────────────────
    echo
    if [ "$fails" -eq 0 ]; then
        say "${C_GREEN}DEPLOY GRÜN${C_RESET} — $LABEL läuft unter launchd auf :$PORT,"
        say "  Health 200 · 401-Wand dicht · Nicht-Licht-Turn nicht-leer · Log scharf."
        log "Status jederzeit: bash pipeline/deploy.sh --status   |   Stop: --down"
        return 0
    fi
    fail "DEPLOY ROT — $fails Verify-Check(s) fehlgeschlagen (Service läuft evtl. trotzdem)."
    return 1
}

# ── Argument-Dispatch ─────────────────────────────────────────────────────────
# --https (beliebige Position) = HOSHI_HTTPS_ENABLED=true als CLI-Flag. Nötig, weil
# die permission-erlaubte Aufrufform exakt mit `bash pipeline/deploy.sh` beginnen
# muss — ein Env-Präfix davor bricht den Match. Wirkung identisch zur Env-Var.
_ARGS=()
for _a in "$@"; do
    if [ "$_a" = "--https" ]; then
        HTTPS_ON=1; SSL_ENABLED_VAL="true"; HEALTH_SCHEME="https"; CURL_K="-k"
    else
        _ARGS+=("$_a")
    fi
done
set -- ${_ARGS[@]+"${_ARGS[@]}"}
# 🔒 Fail-closed: HTTPS ohne Keystore-Passwort deployt NIE (kein stiller Default
# mehr, siehe Kommentar bei SSL_KEYSTORE_PW oben). Erst HIER prüfbar, weil sowohl
# HOSHI_HTTPS_ENABLED (Env, oben) als auch --https (Flag, direkt drüber) HTTPS_ON
# setzen können.
if [ "$HTTPS_ON" = "1" ] && [ -z "$SSL_KEYSTORE_PW" ]; then
    fail "HTTPS ist angefordert (HOSHI_HTTPS_ENABLED/--https), aber HOSHI_SSL_KEYSTORE_PASSWORD ist NICHT gesetzt — Abbruch (fail-closed, kein Default-Passwort mehr)."
    fail "Setze HOSHI_SSL_KEYSTORE_PASSWORD (dasselbe Passwort MUSS beim Cert-Gen, Runbook Schritt 1, verwendet werden) und starte den Deploy erneut."
    exit 3
fi

MODE="local"
REMOTE_ACTION="deploy"
case "${1:-}" in
    ""|--local) MODE="local" ;;
    --status)   MODE="status" ;;
    --down)     MODE="down" ;;
    --remote)
        MODE="remote"
        case "${2:-}" in
            ""|--deploy) REMOTE_ACTION="deploy" ;;
            --status)    REMOTE_ACTION="status" ;;
            --rollback)  REMOTE_ACTION="rollback" ;;
            *) echo "Unbekannte --remote Sub-Action: ${2}" >&2; usage; exit 2 ;;
        esac
        ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "Unbekanntes Argument: ${1}" >&2; usage; exit 2 ;;
esac

if [ "$MODE" = "remote" ]; then
    say "${C_BOLD}Hoshi-0.8 Deploy${C_RESET}  mode=remote/$REMOTE_ACTION  host=$REMOTE_HOST  dir=$REMOTE_DIR  unit=$REMOTE_UNIT  port=$REMOTE_PORT"
else
    say "${C_BOLD}Hoshi-0.8 Deploy${C_RESET}  mode=$MODE  label=$LABEL  port=$PORT"
fi
echo

case "$MODE" in
    local)  do_local;  exit $? ;;
    status) do_status; exit $? ;;
    down)   do_down;   exit $? ;;
    remote) do_remote; exit $? ;;
esac
