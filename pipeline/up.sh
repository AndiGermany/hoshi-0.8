#!/usr/bin/env bash
# pipeline/up.sh — den Stack brain-guard-sicher hochfahren (idempotent).
#
# grün≠lebt: der Brain gilt erst als „oben", wenn brain_classify == OK (echter
# Roundtrip). Schon-laufende Dienste werden NICHT neu gestartet (idempotent).
#
# BRAIN-GUARD zuerst: ist 12b resident/konfiguriert, brechen wir ab (e4b-Start
# würde OOMen). Voxtral wird BEWUSST NICHT gestartet (gewollt aus).
#
# Ablauf:
#   1. brain_guard_blocks? → abbrechen (exit 4)
#   2. Brain: classify != OK → start_brain (guard-sicher, Roundtrip-Beweis)
#   3. Sidecars best-effort: whisper(:9001), knowledge-bridge(:8035),
#      speaker-id(:9002) — nur wenn Port tot UND Run-Skript existiert.
#   4. Status zeigen (doctor-Logik).
#
# Exit-Codes:
#   0  Brain OK (oben) — Sidecars best-effort
#   4  Brain-Guard blockt (12b im Spiel)
#   1  Brain konnte nicht bewiesen hochgezogen werden
#
# Vom Dispatcher: bin/hoshi up

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/stack-lib.sh"
set +e

cd "$REPO_ROOT"

say "Stack up — guard-sicher, idempotent (schon-laufende Dienste bleiben unangetastet)"
echo

# ── (1) Brain-Guard VOR allem ───────────────────────────────────────────────
if brain_guard_blocks; then
    fail "Brain-Guard blockt — $GUARD_REASON"
    fail "up ABGEBROCHEN (e4b-Start würde OOMen). Erst 12b lösen."
    exit 4
fi
log "Brain-Guard: $GUARD_REASON"
echo

# ── (2) Brain: nur starten, wenn nicht schon OK ─────────────────────────────
# direkt aufrufen (nicht $()), damit BRAIN_DETAIL/BRAIN_RT_MS propagieren.
brain_classify >/dev/null
if [ "$BRAIN_STATUS" = "OK" ]; then
    ok "Brain bereits OK (generiert echt, ${BRAIN_RT_MS}ms) — kein Neustart."
else
    say "Brain ist $BRAIN_STATUS → start_brain"
    if start_brain "up"; then
        ok "Brain oben (Roundtrip-Beweis)."
    else
        rc=$?
        fail "Brain ließ sich nicht hochziehen (rc=$rc) — siehe ${BRAIN_LOG:-?}."
        [ "$rc" -eq 4 ] && exit 4
        exit 1
    fi
fi
echo

# ── (3) Sidecars best-effort (nur wenn Port tot UND Run-Skript da) ──────────
start_sidecar() { # label port run_script
    local label="$1" port="$2" script="$3"
    if probe_tcp "$port"; then
        ok "$label (:$port) läuft bereits — übersprungen (idempotent)."
        return 0
    fi
    if [ ! -f "$script" ]; then
        warn "$label (:$port) tot, aber Run-Skript fehlt ($script) — übersprungen."
        return 0
    fi
    local ts logf
    ts="$(timestamp)"
    logf="$HOSHI_LOG_DIR/${label}-up-${ts}.log"
    mkdir -p "$HOSHI_LOG_DIR"
    say "Starte $label via $(basename "$script")  (Log: $logf)"
    nohup bash "$script" >"$logf" 2>&1 &
    disown 2>/dev/null || true
    local i
    for i in $(seq 1 40); do          # max 20s (40×0.5s)
        probe_tcp "$port" && { ok "$label (:$port) oben nach ~$((i/2))s."; return 0; }
        sleep 0.5
    done
    warn "$label (:$port) kam binnen 20s nicht hoch — siehe $logf."
    return 0
}

say "Sidecars (best-effort, launchd ist eigentlich zuständig):"
# whisper-stt + speaker-id + knowledge: sanfter Cutover auf die Repo-Sidecars mit
# sanftem Übergang (Repo-Pfad nur wenn dessen .venv existiert, sonst 0.5 +
# INFO-Zeile; HOSHI_SIDECARS_FROM_REPO=true|false erzwingt).
if resolve_sidecar_run_script "stt" "$HOSHI_05_ROOT/tools/hoshi-whisper-run.sh"; then
    start_sidecar "whisper-stt" "$WHISPER_PORT" "$SIDECAR_RUN_SCRIPT"
else
    # resolve schlägt NUR bei expliziter Fehlkonfiguration fehl (erzwungenes
    # HOSHI_SIDECARS_FROM_REPO=true ohne venv bzw. ungültiger Wert) — das darf
    # nicht als best-effort-Degradation durchrutschen: laut abbrechen.
    exit 1
fi
if resolve_sidecar_run_script "knowledge" "$HOSHI_05_ROOT/tools/hoshi-bridge-run.sh"; then
    start_sidecar "knowledge" "$BRIDGE_PORT" "$SIDECAR_RUN_SCRIPT"
else
    exit 1
fi
if resolve_sidecar_run_script "speaker" "$HOSHI_05_ROOT/tools/hoshi-speakerid-run.sh"; then
    start_sidecar "speaker-id" "$SPEAKERID_PORT" "$SIDECAR_RUN_SCRIPT"
else
    exit 1
fi
# say-TTS: reiner Repo-Sidecar (kein 0.5-Rückweg nötig — neues Feature 19.07),
# best-effort wie die Geschwister; Offline-Schlussszene des Build-Week-Videos
# braucht ihn dauerhaft laufend.
start_sidecar "say-tts" "${HOSHI_SAY_PORT:-8044}" "$REPO_ROOT/sidecars/say/run.sh"
log "voxtral (:$VOXTRAL_PORT) NICHT gestartet — gewollt aus (launchd disabled)."
echo

# ── (4) Status zeigen (doctor-Logik, read-only) ─────────────────────────────
say "Status nach up:"
echo
bash "$REPO_ROOT/pipeline/doctor.sh"
DOCTOR_RC=$?

# up-Erfolg hängt am Brain; Sidecar-Degradation reicht doctor durch (informativ).
if [ "$DOCTOR_RC" -eq 3 ]; then
    exit 1
fi
exit 0
