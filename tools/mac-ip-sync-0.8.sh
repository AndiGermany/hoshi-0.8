#!/usr/bin/env bash
# tools/mac-ip-sync-0.8.sh — IP-Drift-Watchdog für das 0.8-Backend auf ct-106.
#
# ── INCIDENT-REFERENZ 2026-07-06 ──────────────────────────────────────────────
# Die Mac-IP driftete per DHCP (→ 192.168.178.84). ct-106s systemd-Unit
# (hoshi-0.8-backend) trägt die Mac-IP nur als DEPLOY-ZEIT-Rendering
# (__MAC_IP__ in tools/systemd/hoshi-0.8-backend.service, gerendert von
# pipeline/deploy.sh resolve_lan_ip()). Ergebnis: Hoshi war einen ganzen Tag
# taub — Brain (:8041), Whisper (:9001), Speaker (:9002), Knowledge-Bridge
# (:8035) und Ollama (:11434) unerreichbar, während alle Healths grün logten
# (der Backend-Prozess selbst lief ja).
#
# ── WAS DIESES SKRIPT TUT (läuft auf dem MAC, idempotent, best-effort) ────────
#   1. Aktuelle LAN-IP des Macs ermitteln (exakt das resolve_lan_ip()-Muster
#      aus pipeline/deploy.sh: route→interface, ipconfig getifaddr en0/en1).
#   2. Via `ssh ct-106 grep` die im Remote-Unit hinterlegte(n) Mac-IP(s) lesen —
#      GEZIELT über die bekannten Mac-Sidecar-Anker-Zeilen, NIE „irgendeine
#      192.168.x" (0.5-Lehre Iter-96: sonst erwischt man ct-106s eigene IP aus
#      HOSHI_HA_BASE_URL=…178.56 und zerschießt die HA-Zeile):
#        hoshi.brain.base-url            (ExecStart, :8041)
#        HOSHI_STT_BASE_URL              (:9001)
#        HOSHI_SPEAKER_BASE_URL          (:9002)
#        HOSHI_KNOWLEDGE_BRIDGE_BASE_URL (:8035)
#        HOSHI_EPISODIC_EMBED_URL        (:11434)
#   3. Bei Abweichung: NUR die IP-Vorkommen in genau diesen Zeilen per sed
#      patchen (Backup .ipsync-prev), dann systemctl daemon-reload + restart
#      + Health-Poll (Loopback via ssh, http→https-k wie deploy.sh).
#   4. Log nach ~/.hoshi/log/mac-ip-sync.log. Tokens/Secrets werden NIE
#      gelesen, geloggt oder angefasst — es werden ausschließlich IPs aus den
#      Anker-Zeilen extrahiert, nie ganze Unit-Inhalte ausgegeben.
#   5. ssh nicht erreichbar → still exit 0 (best-effort-Watchdog, nie Lärm).
#
# ── AUFRUF ────────────────────────────────────────────────────────────────────
#   bash tools/mac-ip-sync-0.8.sh            # echter Lauf (braucht ct-106-ssh)
#   bash tools/mac-ip-sync-0.8.sh --dry-run  # alles bis zum ssh-Schritt; printet
#                                            # nur die WÜRDE-Aktionen, kein ssh.
#   Periodisch: tools/launchd/de.hoshi.mac-ip-sync.plist (StartInterval 300s) —
#   INERT ausgeliefert, Aktivierung = bewusster launchctl-bootstrap (Andi-Gate).
#
# ── ALTERNATIVEN / EINORDNUNG ─────────────────────────────────────────────────
#   • NACHHALTIGSTER FIX (Andi-Klick, kein Code): FritzBox-Lease festpinnen —
#     fritz.box → Heimnetz → Netzwerk → Mac → „Diesem Netzwerkgerät immer die
#     gleiche IPv4-Adresse zuweisen". Dann driftet nichts mehr; dieses Skript
#     wird zum reinen Sicherheitsnetz.
#   • 0.5 löst es SPIEGELVERKEHRT (tools/hoshi-mac-ip-sync.sh läuft AUF ct-106
#     per systemd-Timer, probt Mac.fritz.box-Kandidaten am Ollama-Port, patcht
#     /etc/hoshi.env). Vorteil dort: kein Mac-launchd→LAN-Problem. Falls die
#     macOS „Local Network"-Privacy-Wand (siehe Unit-Header, sie blockte schon
#     den lokalen 0.8-Service Richtung LAN) auch launchd-ssh trifft, ist die
#     ct-106-seitige Variante der Plan B.
#   • Redeploy (pipeline/deploy.sh --remote --https) rendert die IP ebenfalls
#     frisch — RACE-HINWEIS: läuft ein Deploy gleichzeitig, gewinnt der spätere
#     Schreiber; beide schreiben aber dieselbe (korrekte) aktuelle IP.
#
# Sicherheit/Grenzen: Kein Token-Handling. Kein Schreiben ohne erkannte Drift.
# Kein Auto-Rollback nach Health-Fail (die ALTE IP ist die nachweislich tote —
# Rückrollen wäre Verschlimmbesserung); stattdessen lauter Log-Eintrag +
# Backup ct-106:/etc/systemd/system/<unit>.service.ipsync-prev für Handarbeit.

set -uo pipefail

# launchd startet mit minimalem PATH — ssh/ipconfig/route absolut auffindbar machen.
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

# ── Konstanten (Override-Envs identisch zu pipeline/deploy.sh) ────────────────
REMOTE_HOST="${HOSHI_08_REMOTE:-ct-106}"
REMOTE_UNIT="${HOSHI_08_REMOTE_UNIT:-hoshi-0.8-backend}"
REMOTE_PORT="${HOSHI_08_REMOTE_PORT:-8082}"
UNIT_PATH="/etc/systemd/system/${REMOTE_UNIT}.service"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=8)   # wie deploy.sh: fail-fast, nie Prompt
HEALTH_TRIES=30                                    # × 2s = max 60s Boot-Fenster
LOG_DIR="${HOME}/.hoshi/log"
LOG_FILE="${LOG_DIR}/mac-ip-sync.log"

# Anker-Zeilen, die die MAC-IP tragen (und NUR die — HOSHI_HA_BASE_URL=…178.56
# ist ct-106s HA-Ziel und bleibt UNBERÜHRT). Als ERE-Fragmente für grep+sed.
ANCHOR_KEYS=(
    'hoshi\.brain\.base-url='
    'HOSHI_STT_BASE_URL='
    'HOSHI_SPEAKER_BASE_URL='
    'HOSHI_KNOWLEDGE_BRIDGE_BASE_URL='
    'HOSHI_EPISODIC_EMBED_URL='
)

DRY_RUN=0
case "${1:-}" in
    --dry-run) DRY_RUN=1 ;;
    "") ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) echo "Unbekanntes Argument: $1 (erlaubt: --dry-run)"; exit 2 ;;
esac

mkdir -p "$LOG_DIR" 2>/dev/null || true
log() { echo "$(date '+%F %T') $*" >> "$LOG_FILE" 2>/dev/null || true; }
dry() { echo "DRY: $*"; }

# ── LAN-IP ermitteln — exakt das Muster aus pipeline/deploy.sh resolve_lan_ip()
resolve_lan_ip() {
    local iface ip
    iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
    ip="$(ipconfig getifaddr "${iface:-en0}" 2>/dev/null)"
    [ -z "$ip" ] && ip="$(ipconfig getifaddr en0 2>/dev/null)"
    [ -z "$ip" ] && ip="$(ipconfig getifaddr en1 2>/dev/null)"
    printf '%s' "${ip:-}"
}

# grep-Alternation über alle Anker-Keys, z.B. (a=|b=|c=) — einmal bauen.
anchor_alternation() {
    local alt="" key
    for key in "${ANCHOR_KEYS[@]}"; do
        alt="${alt}${alt:+|}${key%=}"
    done
    printf '(%s)=' "$alt"
}

# Remote-Lese-Kommando (extrahiert NUR IPs aus den Anker-Zeilen, nie Inhalte).
build_read_cmd() {
    printf '%s' "grep -oE '$(anchor_alternation)http://[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:' ${UNIT_PATH} 2>/dev/null | grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+' | sort -u"
}

# Remote-Patch-Kommando: je stale IP × Anker-Zeile eine adressierte sed-Ersetzung.
# $1 = whitespace-separierte stale IPs. Backup vor dem Schreiben, dann reload+restart.
build_patch_cmd() {
    local stale_ips="$1" stale stale_re key sed_exprs=""
    for stale in $stale_ips; do
        stale_re="${stale//./\\.}"
        for key in "${ANCHOR_KEYS[@]}"; do
            sed_exprs="${sed_exprs} -e '/${key}/s|http://${stale_re}:|http://${LAN_IP}:|g'"
        done
    done
    printf '%s' "set -e; cp -p ${UNIT_PATH} ${UNIT_PATH}.ipsync-prev; sed -i -E${sed_exprs} ${UNIT_PATH}; systemctl daemon-reload; systemctl restart ${REMOTE_UNIT}"
}

# Health-Poll wie deploy.sh remote_health_poll: Loopback via ssh (Perimeter lässt
# durch), http zuerst, https -k als Fallback (robust während HTTP↔HTTPS-Flip).
poll_health() {
    local n=0 code
    while [ "$n" -lt "$HEALTH_TRIES" ]; do
        code="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" \
            "curl -sS -o /dev/null -w '%{http_code}' -m 3 http://127.0.0.1:${REMOTE_PORT}/api/health 2>/dev/null \
             || curl -ksS -o /dev/null -w '%{http_code}' -m 3 https://127.0.0.1:${REMOTE_PORT}/api/health 2>/dev/null" \
            2>/dev/null)"
        if [ "$code" = "200" ]; then HEALTH_SECS=$((n * 2)); return 0; fi
        sleep 2; n=$((n + 1))
    done
    return 1
}

# ── (1) Eigene LAN-IP — ohne valide IP kein Schreiben, nirgends. ─────────────
LAN_IP="$(resolve_lan_ip)"
if ! printf '%s' "$LAN_IP" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
   || [ "${LAN_IP#127.}" != "$LAN_IP" ]; then
    if [ "$DRY_RUN" = "1" ]; then
        dry "ABBRUCH-Pfad: keine valide LAN-IP ermittelbar ('${LAN_IP:-leer}') — würde loggen + exit 0 (nie mit Loopback/leer patchen)."
        exit 0
    fi
    log "WARN: keine valide LAN-IP ('${LAN_IP:-leer}') — skip (nie mit Loopback/leer patchen)"
    exit 0
fi

READ_CMD="$(build_read_cmd)"

# ── (--dry-run) Alles bis zum ssh-Schritt; WÜRDE-Aktionen nur printen. ───────
if [ "$DRY_RUN" = "1" ]; then
    dry "LAN-IP des Macs (resolve_lan_ip, Muster deploy.sh): ${LAN_IP}"
    dry "Log-Ziel: ${LOG_FILE} (Verzeichnis ist angelegt)"
    dry "würde (a) ssh-Erreichbarkeit prüfen:  ssh ${SSH_OPTS[*]} ${REMOTE_HOST} true   → bei Fehlschlag: still exit 0"
    dry "würde (b) Remote-Mac-IP(s) lesen:     ssh ${REMOTE_HOST} \"${READ_CMD}\""
    dry "würde (c) vergleichen: alle gelesenen IPs == ${LAN_IP} → in sync, exit 0 (idempotent)"
    dry "würde (d) bei Drift <STALE_IP> ≠ ${LAN_IP} remote patchen (NUR Anker-Zeilen, HA-Zeile …178.56 unberührt):"
    dry "          ssh ${REMOTE_HOST} \"$(LAN_IP="$LAN_IP" build_patch_cmd '<STALE_IP>')\""
    dry "würde (e) Health pollen (max $((HEALTH_TRIES * 2))s): ssh ${REMOTE_HOST} curl http://127.0.0.1:${REMOTE_PORT}/api/health (Fallback https -k) bis 200"
    dry "würde (f) Ergebnis loggen nach ${LOG_FILE} — Tokens/Secrets tauchen in KEINEM Schritt auf."
    dry "ENDE dry-run — kein ssh ausgeführt, nichts geschrieben."
    exit 0
fi

# ── (2) ssh-Vorbedingung (BatchMode, fail-fast) — Fehlschlag = still exit 0. ──
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" 'true' >/dev/null 2>&1; then
    log "INFO: ${REMOTE_HOST} per ssh nicht erreichbar — best-effort skip (exit 0)"
    exit 0
fi

# ── (3) Im Unit hinterlegte Mac-IP(s) lesen (nur IPs, nie Unit-Inhalte). ──────
REMOTE_IPS="$(ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "$READ_CMD" 2>/dev/null | tr '\n' ' ')"
REMOTE_IPS="${REMOTE_IPS%% }"
if [ -z "$REMOTE_IPS" ]; then
    log "INFO: keine Mac-IP in den Anker-Zeilen von ${UNIT_PATH} gefunden (Platzhalter-/Template-Stand?) — skip"
    exit 0
fi

# ── (4) Vergleich — idempotent: alles in sync ⇒ leiser Exit. ─────────────────
STALE_IPS=""
for ip in $REMOTE_IPS; do
    [ "$ip" != "$LAN_IP" ] && STALE_IPS="${STALE_IPS}${STALE_IPS:+ }${ip}"
done
if [ -z "$STALE_IPS" ]; then
    # Heartbeat 1×/Tag: totale Stille wäre sonst „lief nie" ODER „alles gut" —
    # ununterscheidbar (Fehl-Diagnose 2026-07-15: sporadische Skip-Zeilen wurden
    # als „Watchdog blind" gelesen, weil der Erfolgsfall nie loggte).
    if ! grep -q "^$(date '+%F') .*SYNC-OK" "$LOG_FILE" 2>/dev/null; then
        log "SYNC-OK: Unit trägt ${LAN_IP} (in sync), Watchdog lebt"
    fi
    exit 0
fi

# ── (5) Drift! Patchen (nur Anker-Zeilen) + daemon-reload + restart. ──────────
log "DRIFT erkannt: Unit trägt [${STALE_IPS}], Mac ist ${LAN_IP} — patche ${UNIT_PATH} auf ${REMOTE_HOST}"
PATCH_CMD="$(build_patch_cmd "$STALE_IPS")"
if ! ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "$PATCH_CMD" >/dev/null 2>&1; then
    log "WARN: Patch/daemon-reload/restart fehlgeschlagen — Unit evtl. unverändert (Backup: ${UNIT_PATH}.ipsync-prev). Nächster Lauf versucht es erneut."
    exit 0
fi
log "gepatcht: [${STALE_IPS}] -> ${LAN_IP} in Anker-Zeilen · daemon-reload + restart ${REMOTE_UNIT} ok"

# ── (6) Health-Poll — Beweis statt Hoffnung. ──────────────────────────────────
if poll_health; then
    log "OK: /api/health = 200 nach ~${HEALTH_SECS}s — Drift geheilt (${LAN_IP})"
else
    log "WARN: /api/health nach $((HEALTH_TRIES * 2))s NICHT 200 — Unit gepatcht, Backend bootet nicht sauber. KEIN Auto-Rollback (alte IP war die tote). Handarbeit: ssh ${REMOTE_HOST} 'journalctl -u ${REMOTE_UNIT} -n 50' · Backup: ${UNIT_PATH}.ipsync-prev"
fi
exit 0
