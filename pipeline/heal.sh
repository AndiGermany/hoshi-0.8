#!/usr/bin/env bash
# pipeline/heal.sh — den Brain auto-heilen (Zombie/Wedge/Down → bewiesen lebendig).
#
# grün≠lebt: wir klassifizieren über echten Roundtrip. Nur wenn der Brain wirklich
# nicht generiert (ZOMBIE/WEDGE/DOWN), greifen wir ein — und melden Erfolg ERST,
# wenn ein finaler /v1/chat-Roundtrip echten Text liefert.
#
# BRAIN-GUARD zuerst: wäre 12b resident/konfiguriert, würde ein e4b-Start OOMen →
# wir brechen dann EHRLICH ab (exit 4), statt den Mac umzubringen.
#
# Ablauf:
#   OK             → nichts zu heilen (exit 0)
#   LOADING        → lädt grad, kein Eingriff (exit 2)
#   ZOMBIE|WEDGE   → guard? → kill_brain → start_brain → Roundtrip-Beweis
#   DOWN           → guard? → start_brain → Roundtrip-Beweis
#
# Exit-Codes:
#   0  Brain bewiesen lebendig (generiert)
#   2  LOADING (kein Eingriff nötig/sinnvoll)
#   4  Brain-Guard blockt (12b im Spiel) — bewusst NICHT geheilt
#   1  Heilung versucht, aber kein Beweis (immer noch tot)
#
# Arg: --brain-only (Default) — nur den Brain heilen (Sidecars sind launchd-Sache).
# Vom Dispatcher: bin/hoshi heal

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/stack-lib.sh"
set +e

# Arg parsen (nur --brain-only akzeptiert; Default sowieso brain-only).
SCOPE="brain-only"
case "${1:-}" in
    ""|--brain-only) SCOPE="brain-only" ;;
    *) warn "unbekanntes Arg '$1' — ignoriere (heal ist brain-only)"; ;;
esac

cd "$REPO_ROOT"
ensure_log_dir
TS="$(timestamp)"
LOG="$PIPELINE_LOG_DIR/heal-$TS.log"
{ echo "# hoshi heal @ $(iso_now)  scope=$SCOPE"; } > "$LOG"

say "Brain-Heilung — erst klassifizieren, dann nur bei Bedarf eingreifen"
echo

# direkt aufrufen (nicht $()), damit BRAIN_DETAIL/BRAIN_RT_MS propagieren.
brain_classify >/dev/null
STATUS="$BRAIN_STATUS"
log "Klassifikation: $STATUS — $BRAIN_DETAIL"
echo "$STATUS · $BRAIN_DETAIL" >> "$LOG"

case "$STATUS" in
    OK)
        ok "Brain ist OK (generiert echt, ${BRAIN_RT_MS}ms) — nichts zu heilen."
        exit 0
        ;;
    LOADING)
        warn "Brain LÄDT gerade (loaded:false) — kein Eingriff, gleich nochmal prüfen."
        exit 2
        ;;
    ZOMBIE|WEDGE|DOWN)
        : # weiter unten heilen
        ;;
    *)
        fail "Unerwarteter Status '$STATUS' — sicherheitshalber kein Eingriff."
        exit 1
        ;;
esac

# ── Guard VOR jedem Start/Kill: 12b im Spiel? Dann ehrlich abbrechen. ────────
if brain_guard_blocks; then
    fail "Brain-Guard blockt — $GUARD_REASON"
    fail "Heilung ABGEBROCHEN (e4b-Start würde OOMen). Erst 12b lösen, dann erneut."
    exit 4
fi
log "Brain-Guard: $GUARD_REASON"
echo

# ── Wedge/Zombie: erst den toten/hängenden Prozess hart beenden ─────────────
if [ "$STATUS" = "WEDGE" ] || [ "$STATUS" = "ZOMBIE" ]; then
    say "Status $STATUS → kill_brain (pkill -9, beide Brain-Generationen), warte auf freien Port"
    if kill_brain; then
        ok "Port :$BRAIN_PORT frei."
    else
        warn "Port :$BRAIN_PORT nach 10s noch belegt — versuche trotzdem zu starten."
    fi
    echo
fi

# ── Starten (guard-sicher) + Roundtrip-Beweis ───────────────────────────────
say "Brain starten ($STATUS → frisch hochziehen)"
if start_brain "heal"; then
    echo
    ok "HEAL GRÜN — Brain bewiesen lebendig (echter /v1/chat-Roundtrip)."
    log "Log: ${BRAIN_LOG:-?}"
    exit 0
else
    rc=$?
    echo
    fail "HEAL ROT — Brain ließ sich nicht bewiesen wiederbeleben (rc=$rc)."
    [ -n "${BRAIN_LOG:-}" ] && fail "Brain-Log: $BRAIN_LOG"
    # rc 4 (guard) reichen wir durch; sonst 1.
    [ "$rc" -eq 4 ] && exit 4
    exit 1
fi
