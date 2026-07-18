#!/usr/bin/env bash
# pipeline/run.sh — der "lauffähig"-Beweis für Hoshi 0.8.
#
# grün≠lebt: bootet die ECHTE 0.8-App (web-inbound, Spring-Boot-WebFlux) lokal
# auf :8090 (bewusst NICHT 8080/8081 → kein Clash mit 0.5) und beweist die
# Trust-Wand am gebooteten Context:
#   1. GET /api/health                 → 200 (öffentlich)
#   2. GET /api/v1/ping  ohne Token    → 401 (Wand greift)
#   3. GET /api/v1/ping  mit Token     → 200 (Token kommt durch)
#
# LOOPBACK-FINESSE: Der PerimeterPort lässt Loopback IMMER frei (dokumentiert in
# PerimeterWallTest: ein localhost-Request umginge die Wand). Damit das 401
# ECHT ist, treffen die geschützten Checks die App über die LAN-IP
# (= nicht-loopback) — genau das Bedrohungsmodell, das die Wand abwehren soll.
#
# Exit 0 nur, wenn alle drei stimmen. App wird am Ende sauber gestoppt.
#
# Log: <repo>/.pipeline/run-<ts>.log

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/run-$TS.log"
APP_LOG="$PIPELINE_LOG_DIR/run-$TS-app.log"

PORT=8090
TOKEN="testtoken"

cd "$REPO_ROOT"

# ── LAN-IP bestimmen (nicht-loopback, damit die Wand beweisbar ist) ───────────
IFACE="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
LAN_IP="$(ipconfig getifaddr "${IFACE:-en0}" 2>/dev/null || true)"
[ -z "$LAN_IP" ] && LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[ -z "$LAN_IP" ] && LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
if [ -z "$LAN_IP" ]; then
    warn "Keine LAN-IP gefunden — falle auf localhost zurück (401-Check evtl. durch Loopback-Bypass entschärft)"
    LAN_IP="127.0.0.1"
fi

# ── Java bestimmen: dieselbe JDK 21, mit der gebaut wird (gradle.properties),
#    sonst PATH-java. (Boot-Jar ist Target 21; PATH kann JDK 26 sein.) ─────────
JAVA_BIN="java"
GRADLE_JAVA_HOME="$(sed -n 's/^org\.gradle\.java\.home=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
if [ -n "$GRADLE_JAVA_HOME" ] && [ -x "$GRADLE_JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$GRADLE_JAVA_HOME/bin/java"
fi

# ── (1) bootJar bauen ────────────────────────────────────────────────────────
say "bootJar bauen — ./gradlew :web-inbound:bootJar"
if ! "$GRADLEW" -q :web-inbound:bootJar >"$LOG" 2>&1; then
    fail "bootJar-Build fehlgeschlagen — siehe ${LOG#$REPO_ROOT/}"
    tail -25 "$LOG"
    exit 1
fi
JAR="$(ls -t "$REPO_ROOT"/web-inbound/build/libs/*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)"
if [ -z "$JAR" ]; then
    fail "Kein bootJar gefunden in web-inbound/build/libs/"
    exit 1
fi
ok "Artefakt: ${JAR#$REPO_ROOT/}"

# ── (2) App auf :8090 im Hintergrund booten ──────────────────────────────────
say "App booten auf :$PORT (Token gesetzt: HOSHI_API_TOKEN + hoshi.perimeter.token)"
APP_PID=""
cleanup() {
    if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        for _ in $(seq 1 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.25; done
        kill -9 "$APP_PID" 2>/dev/null || true
        log "App (PID $APP_PID) gestoppt."
    fi
}
trap cleanup EXIT

log "java: $JAVA_BIN"
HOSHI_API_TOKEN="$TOKEN" "$JAVA_BIN" -jar "$JAR" \
    --server.port="$PORT" \
    --hoshi.perimeter.enabled=true \
    --hoshi.perimeter.token="$TOKEN" \
    >"$APP_LOG" 2>&1 &
APP_PID=$!
log "PID=$APP_PID  ·  App-Log: ${APP_LOG#$REPO_ROOT/}"

# ── (3) Auf Health warten (max 60s, localhost = garantiert lauschend) ─────────
say "Warte auf Health (poll http://localhost:$PORT/api/health, max 60s)"
READY=0
for i in $(seq 1 120); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        fail "App-Prozess vorzeitig beendet — App-Log:"
        tail -30 "$APP_LOG"
        exit 1
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://localhost:$PORT/api/health" 2>/dev/null || echo 000)"
    if [ "$code" = "200" ]; then READY=1; ok "Health 200 nach ~$((i / 2))s"; break; fi
    sleep 0.5
done
if [ "$READY" -ne 1 ]; then
    fail "Health nicht 200 binnen 60s — App-Log:"
    tail -30 "$APP_LOG"
    exit 1
fi
echo

# ── (4) Die drei Wand-Checks ─────────────────────────────────────────────────
say "Trust-Wand prüfen (geschützte Pfade über LAN-IP $LAN_IP = nicht-loopback)"
PASS=1

c1="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://$LAN_IP:$PORT/api/health" 2>/dev/null || echo 000)"
if [ "$c1" = "200" ]; then ok  "[1] GET /api/health            → $c1  (erwartet 200)"
else                        fail "[1] GET /api/health            → $c1  (erwartet 200)"; PASS=0; fi

c2="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://$LAN_IP:$PORT/api/v1/ping" 2>/dev/null || echo 000)"
if [ "$c2" = "401" ]; then ok  "[2] GET /api/v1/ping ohne Token → $c2  (erwartet 401)"
else                       fail "[2] GET /api/v1/ping ohne Token → $c2  (erwartet 401)"; PASS=0; fi

c3="$(curl -s -o /dev/null -w '%{http_code}' -m 5 -H "Authorization: Bearer $TOKEN" "http://$LAN_IP:$PORT/api/v1/ping" 2>/dev/null || echo 000)"
if [ "$c3" = "200" ]; then ok  "[3] GET /api/v1/ping mit Token  → $c3  (erwartet 200)"
else                       fail "[3] GET /api/v1/ping mit Token  → $c3  (erwartet 200)"; PASS=0; fi

echo
{
    echo "# run-Checks @ $(iso_now)  (host=$LAN_IP:$PORT)"
    echo "[1] /api/health            -> $c1  (want 200)"
    echo "[2] /api/v1/ping no-token  -> $c2  (want 401)"
    echo "[3] /api/v1/ping token     -> $c3  (want 200)"
} >> "$LOG"

if [ "$PASS" -eq 1 ]; then
    say "${C_GREEN}run GRÜN${C_RESET} — App lebt auf :$PORT, Trust-Wand 200/401/200 bestätigt."
    exit 0
else
    fail "run ROT — mindestens ein Check stimmt nicht."
    exit 1
fi
