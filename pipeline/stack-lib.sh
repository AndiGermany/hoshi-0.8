#!/usr/bin/env bash
# pipeline/stack-lib.sh — gemeinsame Stack-Ops-Helfer für doctor/heal/up.
#
# KERN-PRINZIP (M3-These, teuer gelernt): grün≠lebt.
#   Der Brain (e4b :8041) kann auf GET /health "loaded:true" sagen UND trotzdem
#   tot sein — entweder als WEDGE (Health ok, aber /v1/chat hängt) oder als
#   ZOMBIE (Prozess läuft, bindet aber keinen Port — der echte 5-Tage-Zombie band
#   :8041 nie). Deshalb klassifizieren wir über Port-Bindung + einen ECHTEN
#   Roundtrip, nie über /health allein. „Trau dem, was generiert, nicht der
#   Selbstauskunft." (KEIN RSS-Kriterium: MLX hält Gewichte in Metal, nicht RSS.)
#
# BRAIN-GUARD (16-GB-Wand): der Mac fährt e4b ODER 12b, NIE beide (OOM). Bevor
# wir je einen e4b starten, prüfen wir, ob 12b resident (ollama /api/ps) oder
# konfiguriert ist (~/.hoshi/run/brain.state == ollama). Wenn ja: VERBOTEN.
#
# Wird gesourct von doctor.sh / heal.sh / up.sh:
#   source "$(dirname "$0")/stack-lib.sh"
#
# Sourct seinerseits lib.sh (Farben, Logger, timestamp, iso_now, REPO_ROOT, …).

# lib.sh setzt set -euo pipefail; diese Lib definiert nur Funktionen + Konfig.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# ── Konfig (alles override-bar) ──────────────────────────────────────────────
HOSHI_05_ROOT="${HOSHI_05_ROOT:-$HOME/IdeaProjects/Hoshi_0.5}"
HOSHI_BRAIN_MODEL="${HOSHI_BRAIN_MODEL:-e4b}"        # e4b|e2b|12b|volle HF-Repo-ID — Default MUSS dem gewählten Betriebs-Modell entsprechen, sonst startet heal/up nach einem Ausfall still das falsche Brain (Modell-Wechsel darf nie Restart-Nebeneffekt sein)
HOSHI_LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"
HOSHI_BRAIN_STATE_FILE="${HOSHI_BRAIN_STATE_FILE:-$HOME/.hoshi/run/brain.state}"

# Ports (override-bar)
BRAIN_PORT="${HOSHI_BRAIN_PORT:-8041}"
WHISPER_PORT="${HOSHI_WHISPER_PORT:-9001}"
SPEAKERID_PORT="${HOSHI_SPEAKERID_PORT:-9002}"
BRIDGE_PORT="${HOSHI_BRIDGE_PORT:-8035}"
OLLAMA_PORT="${HOSHI_OLLAMA_PORT:-11434}"
VOXTRAL_PORT="${HOSHI_VOXTRAL_PORT:-8042}"

# URLs
BRAIN_URL="${HOSHI_BRAIN_URL:-http://127.0.0.1:$BRAIN_PORT}"
OLLAMA_URL="${HOSHI_OLLAMA_URL:-http://127.0.0.1:$OLLAMA_PORT}"

# ── Stabilitäts-Guards-Konfig (Lessons-als-Guards, alles override-bar) ───────
# mlx-lm-Pin: 0.31.3 = Regression #1242 ("Missing 54 parameters", Boot-Hang).
HOSHI_MLX_PIN="${HOSHI_MLX_PIN:-mlx-lm-0.31.2}"
# 0.8-Deploy-Service (lokaler launchd-Job; siehe pipeline/deploy.sh).
HOSHI_DEPLOY_PORT="${HOSHI_DEPLOY_PORT:-8090}"
HOSHI_DEPLOY_LABEL="${HOSHI_DEPLOY_LABEL:-io.hoshi.0.8.backend}"
HOSHI_DEPLOY_JAR="${HOSHI_DEPLOY_JAR:-$REPO_ROOT/web-inbound/build/libs/web-inbound-0.8.0.jar}"
# Sidecar-venvs (dead symlink = latenter Totalausfall). Whisper best-effort.
HOSHI_BRAIN_VENV="${HOSHI_BRAIN_VENV:-$HOSHI_05_ROOT/hoshi-llm-optiq/.venv/bin/python}"
HOSHI_WHISPER_VENV="${HOSHI_WHISPER_VENV:-$HOSHI_05_ROOT/hoshi-stt-mlx/.venv/bin/python}"

# Globale Out-Parameter (von Funktionen gesetzt, vom Aufrufer gelesen).
# WICHTIG: diese Funktionen NICHT in $()-Substitution aufrufen, sonst gehen die
# Globals im Subshell verloren. Stattdessen direkt aufrufen (ggf. >/dev/null)
# und die Globals lesen. Sie ECHO'en zusätzlich (Spec), nur ist der Echo redundant.
BRAIN_RT_MS=0          # Latenz des letzten brain_roundtrip in ms
BRAIN_RT_TEXT=""       # Antwort-Text des letzten brain_roundtrip
BRAIN_STATUS=""        # Status der letzten brain_classify
BRAIN_DETAIL=""        # Detail-String der letzten brain_classify
GUARD_REASON=""        # Grund des letzten brain_guard_blocks
STABILITY_WARN=0       # Anzahl WARN aus dem letzten stability_guards-Lauf

# 12b-Modellname für den Guard (enthält "12b")
HOSHI_BRAIN_12B_MODEL="${HOSHI_BRAIN_12B_MODEL:-${OLLAMA_MODEL:-gemma4:12b}}"

# ── Modell-Auflösung (Kurz-Token → kanonische mlx-community-Repo-ID) ──────────
resolve_brain_model() {
    local choice="${1:-$HOSHI_BRAIN_MODEL}"
    case "$choice" in
        */*)     printf '%s' "$choice" ;;                          # volle Repo-ID
        e2b|E2B) printf '%s' "mlx-community/gemma-4-e2b-it-4bit" ;;
        e4b|E4B) printf '%s' "mlx-community/gemma-4-e4b-it-4bit" ;;
        12b|12B) printf '%s' "mlx-community/gemma-4-12B-it-4bit" ;;
        *)       printf '%s' "mlx-community/gemma-4-${choice}-it-4bit" ;;
    esac
}

# ── Sidecar-Run-Skript-Aufloesung (S4-Cutover, sanfter Uebergang) ────────────
# Waehlt zwischen dem neuen Repo-Sidecar (sidecars/<name>/run.sh) und dem alten
# 0.5-Pfad. Default AUTO: Repo-Pfad NUR wenn dessen .venv existiert (Sidecar
# schon gebootstrapped), sonst automatisch der 0.5-Pfad + INFO-Zeile (kein
# Fake-Fortschritt: Betrieb bleibt ehrlich auf 0.5, bis bootstrap.sh lief).
# Override HOSHI_SIDECARS_FROM_REPO=true|false erzwingt eine Seite explizit;
# true + fehlendes venv ⇒ LAUTER Fehler ("bootstrap.sh zuerst"), KEIN stiller
# Rueckfall (Brief-15-Prinzip: nie einen kaputten Pfad scheinbar gesund starten).
#
# Args: name (brain|stt|speaker|knowledge) legacy_run_script (0.5-Pfad)
# Setzt SIDECAR_RUN_SCRIPT (Pfad, leer bei Fehler). return 0 ok, 1 Fehler.
SIDECAR_RUN_SCRIPT=""
resolve_sidecar_run_script() {
    local name="$1" legacy_script="$2"
    local repo_script="$REPO_ROOT/sidecars/$name/run.sh"
    local repo_venv="$REPO_ROOT/sidecars/$name/.venv/bin/python"
    SIDECAR_RUN_SCRIPT=""
    case "${HOSHI_SIDECARS_FROM_REPO:-}" in
        true|TRUE|1)
            if [ -x "$repo_venv" ]; then
                SIDECAR_RUN_SCRIPT="$repo_script"
                return 0
            fi
            fail "HOSHI_SIDECARS_FROM_REPO=true erzwingt Repo-Sidecar '$name', aber venv fehlt: $repo_venv — erst sidecars/$name/bootstrap.sh laufen lassen."
            return 1
            ;;
        false|FALSE|0)
            SIDECAR_RUN_SCRIPT="$legacy_script"
            return 0
            ;;
        "")
            if [ -x "$repo_venv" ]; then
                SIDECAR_RUN_SCRIPT="$repo_script"
                return 0
            fi
            log "Repo-Sidecar '$name' noch nicht gebootstrapped (fehlt: $repo_venv) — nutze 0.5-Pfad ($legacy_script)."
            SIDECAR_RUN_SCRIPT="$legacy_script"
            return 0
            ;;
        *)
            fail "HOSHI_SIDECARS_FROM_REPO='$HOSHI_SIDECARS_FROM_REPO' ungueltig — erwartet true|false (oder leer fuer AUTO)."
            return 1
            ;;
    esac
}

# ── Low-level Probes ─────────────────────────────────────────────────────────
# probe_tcp PORT → 0 wenn 127.0.0.1:PORT einen TCP-Connect annimmt, sonst 1.
probe_tcp() {
    local port="$1"
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && { exec 3>&- 2>/dev/null; return 0; }
    return 1
}

# probe_http_health URL [timeout] → 0 wenn GET URL/health == 200, sonst 1.
probe_http_health() {
    local url="$1" tmo="${2:-4}" code
    code="$(curl -s -o /dev/null -w '%{http_code}' -m "$tmo" "$url/health" 2>/dev/null || echo 000)"
    [ "$code" = "200" ]
}

# _now_ms → Millisekunden seit Epoch (python3, BSD-date-sicher).
_now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# _json_field '<json>' key → Top-Level-Wert (bool kleingeschrieben), sonst "".
_json_field() {
    printf '%s' "$1" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin); v = d[sys.argv[1]]
    print(str(v).lower() if isinstance(v, bool) else v)
except Exception:
    print("")
' "$2" 2>/dev/null || true
}

# ── Brain-Health (rohe /health-Antwort, leer = unerreichbar) ─────────────────
brain_health() {
    curl -s -m 4 "$BRAIN_URL/health" 2>/dev/null || true
}

# ── Brain-Roundtrip: ECHTE Generierung über POST /v1/chat ────────────────────
# Args: [prompt] [timeout_s]. Setzt BRAIN_RT_TEXT (Antwort-Text, leer = tot) +
# BRAIN_RT_MS (Latenz in ms) und echo't zusätzlich den Text (Spec).
# NICHT in $()-Substitution aufrufen, sonst gehen die Globals verloren —
# stattdessen: `brain_roundtrip ... >/dev/null` und $BRAIN_RT_TEXT lesen.
brain_roundtrip() {
    local prompt="${1:-Sag in genau einem kurzen Satz Hallo.}" tmo="${2:-30}"
    local body tmpf t0 t1
    body="$(python3 -c '
import json, sys
print(json.dumps({
    "messages":[{"role":"user","content":sys.argv[1]}],
    "sessionId":"doctor","userId":"andi","stream":True,
    "max_tokens":24,"temperature":0.7}))' "$prompt")"
    tmpf="$(mktemp "${TMPDIR:-/tmp}/hoshi-rt.XXXXXX")"
    t0="$(_now_ms)"
    curl -sN -m "$tmo" -X POST \
        -H 'Content-Type: application/json' \
        -d "$body" \
        "$BRAIN_URL/v1/chat" >"$tmpf" 2>/dev/null || true
    t1="$(_now_ms)"
    BRAIN_RT_MS=$((t1 - t0))
    # SSE-Deltas (data: {"delta":"…"} … data: [DONE]) zusammensetzen.
    BRAIN_RT_TEXT="$(python3 -c '
import sys, json
out = []
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for raw in f:
        line = raw.strip()
        if not line.startswith("data:"):
            continue
        p = line[5:].strip()
        if not p or p == "[DONE]":
            continue
        try:
            ev = json.loads(p)
        except Exception:
            continue
        d = ev.get("delta")
        if d:
            out.append(d)
print("".join(out).strip())
' "$tmpf" 2>/dev/null || true)"
    rm -f "$tmpf" 2>/dev/null || true
    printf '%s' "$BRAIN_RT_TEXT"
}

# ── Brain-Klassifikation: OK | WEDGE | ZOMBIE | DOWN | LOADING ────────────────
# Setzt BRAIN_STATUS + BRAIN_DETAIL (+ BRAIN_RT_MS bei OK/WEDGE) und echo't den
# Status (Spec). NICHT in $() aufrufen — direkt `brain_classify >/dev/null`,
# dann $BRAIN_STATUS/$BRAIN_DETAIL lesen (sonst gehen die Globals verloren).
# Reihenfolge: LOADING vor ZOMBIE (lädt → niedrige RSS, kein Fehlurteil).
brain_classify() {
    BRAIN_STATUS=""; BRAIN_DETAIL=""
    local health status loaded pids
    health="$(brain_health)"
    status="$(_json_field "$health" status)"
    loaded="$(_json_field "$health" loaded)"

    # (1) LOADING — Health sagt es selbst.
    if [ "$status" = "loading" ]; then
        BRAIN_STATUS="LOADING"; BRAIN_DETAIL="/health status=loading (Modell lädt noch)"
        echo "$BRAIN_STATUS"; return 0
    fi

    # Muster deckt BEIDE Brain-Generationen: 0.5-Rueckweg (server_e4b.py) UND
    # Repo-Sidecar (sidecars/brain/server.py) — nach dem S4-Cutover matchte das alte
    # Muster nichts mehr, heal war brain-seitig wirkungslos (Fund 19.07).
    pids="$(pgrep -f 'server_e4b\.py|sidecars/brain/server\.py' 2>/dev/null || true)"

    # (2) ZOMBIE — Prozess da, aber Port NICHT gebunden (der echte Zombie band :8041 nie).
    #     KEIN RSS-Kriterium: MLX hält die Modellgewichte in Metal/unified memory (mmap),
    #     ein gesundes e4b hat legitim nur ~350 MB RSS → RSS wäre ein Fehlalarm. Die
    #     verlässlichen Signale sind Port-Bindung (hier) + echter Roundtrip (Schritt 3).
    if [ -n "$pids" ] && ! probe_tcp "$BRAIN_PORT"; then
        BRAIN_STATUS="ZOMBIE"
        BRAIN_DETAIL="Prozess($(echo $pids | tr '\n' ' ' | xargs)) läuft, bindet :$BRAIN_PORT NICHT"
        echo "$BRAIN_STATUS"; return 0
    fi

    # (3) Health vorhanden + loaded:true → echter Roundtrip entscheidet OK/WEDGE.
    if [ -n "$health" ] && [ "$loaded" = "true" ]; then
        brain_roundtrip "Sag in genau einem kurzen Satz Hallo." 15 >/dev/null
        if [ -n "${BRAIN_RT_TEXT// /}" ]; then
            BRAIN_STATUS="OK"
            BRAIN_DETAIL="loaded:true + Roundtrip ok (${BRAIN_RT_MS}ms): \"$(printf '%s' "$BRAIN_RT_TEXT" | head -c 60)\""
            echo "$BRAIN_STATUS"; return 0
        fi
        BRAIN_STATUS="WEDGE"
        BRAIN_DETAIL="loaded:true ABER /v1/chat leer/timeout (${BRAIN_RT_MS}ms) → WEDGE"
        echo "$BRAIN_STATUS"; return 0
    fi

    # (4) Health da, aber nicht loaded (und nicht loading) → halb-tot = WEDGE.
    if [ -n "$health" ]; then
        BRAIN_STATUS="WEDGE"
        BRAIN_DETAIL="/health antwortet, aber loaded=${loaded:-?} (nicht bereit)"
        echo "$BRAIN_STATUS"; return 0
    fi

    # (5) Prozess ohne Health/Port → ZOMBIE; sonst gar nichts → DOWN.
    if [ -n "$pids" ]; then
        BRAIN_STATUS="ZOMBIE"; BRAIN_DETAIL="Prozess läuft, aber /health stumm"
        echo "$BRAIN_STATUS"; return 0
    fi
    BRAIN_STATUS="DOWN"; BRAIN_DETAIL="kein server_e4b.py-Prozess, :$BRAIN_PORT tot"
    echo "$BRAIN_STATUS"; return 0
}

# ── Brain-Guard: blockiert e4b-Start, wenn 12b im Spiel ist (16-GB-Wand) ──────
# return 0 = BLOCK (12b resident ODER brain.state==ollama). return 1 = frei.
# Setzt + echo't GUARD_REASON.
brain_guard_blocks() {
    GUARD_REASON=""
    # (1) IST: lädt Ollama ein 12b-Modell? /api/ps listet nur residente Modelle.
    local ps_body
    ps_body="$(curl -s -m 3 "$OLLAMA_URL/api/ps" 2>/dev/null || true)"
    if printf '%s' "$ps_body" | grep -qF "\"$HOSHI_BRAIN_12B_MODEL\"" \
       || printf '%s' "$ps_body" | grep -qiE '"name"[[:space:]]*:[[:space:]]*"[^"]*12b'; then
        GUARD_REASON="12b resident (ollama /api/ps listet 12b) → e4b-Start VERBOTEN (OOM)"
        echo "$GUARD_REASON"; return 0
    fi
    # (2) SOLL: brain.state == ollama|12b → 12b konfiguriert (lazy-load-OOM-Fenster).
    local state
    state="$(tr -d '[:space:]' <"$HOSHI_BRAIN_STATE_FILE" 2>/dev/null || true)"
    case "$state" in
        ollama|12b)
            GUARD_REASON="brain.state=$state (12b konfiguriert) → e4b-Start VERBOTEN (OOM)"
            echo "$GUARD_REASON"; return 0 ;;
    esac
    GUARD_REASON="frei (kein 12b resident, brain.state=${state:-fehlt})"
    return 1
}

# ── kill_brain: Zombie/Wedge hart beenden, auf freien Port warten (max 10s) ──
kill_brain() {
    pkill -9 -f 'server_e4b\.py|sidecars/brain/server\.py' 2>/dev/null || true
    local i
    for i in $(seq 1 40); do
        probe_tcp "$BRAIN_PORT" || { return 0; }   # Port frei → fertig
        sleep 0.25
    done
    # nach 10s noch belegt:
    probe_tcp "$BRAIN_PORT" && return 1 || return 0
}

# ── start_brain VERB: e4b guard-sicher starten + auf bewiesene Generierung warten
# Echo = Statuszeilen. return 0 nur bei loaded:true UND nicht-leerem Roundtrip.
# Setzt BRAIN_LOG (Pfad).
BRAIN_LOG=""
start_brain() {
    local verb="${1:-start}" model ts
    if brain_guard_blocks; then
        fail "Brain-Guard blockt Start: $GUARD_REASON"
        return 4
    fi
    model="$(resolve_brain_model)"
    ts="$(timestamp)"
    mkdir -p "$HOSHI_LOG_DIR"
    BRAIN_LOG="$HOSHI_LOG_DIR/e4b-${verb}-${ts}.log"
    if ! resolve_sidecar_run_script "brain" "$HOSHI_05_ROOT/tools/hoshi-e4b-run.sh"; then
        return 1
    fi
    local run_script="$SIDECAR_RUN_SCRIPT"
    if [ ! -f "$run_script" ]; then
        fail "Brain-Run-Skript fehlt: $run_script"
        return 1
    fi
    say "Starte e4b ($model) via $run_script"
    log "Log: $BRAIN_LOG"
    # macOS hat KEIN setsid — nur nohup. E4B_MODEL gewinnt über brain.model
    # (0.5-Pfad); HOSHI_BRAIN_MODEL ist das Env-Muster des Repo-Sidecars —
    # beide gesetzt macht den Aufruf pfad-unabhängig korrekt (jedes Skript
    # liest nur die Variable, die es kennt).
    nohup env E4B_MODEL="$model" HOSHI_BRAIN_MODEL="$model" bash "$run_script" >"$BRAIN_LOG" 2>&1 &
    disown 2>/dev/null || true

    # (a) auf loaded:true warten (max 90s).
    local i loaded
    for i in $(seq 1 90); do
        loaded="$(_json_field "$(brain_health)" loaded)"
        if [ "$loaded" = "true" ]; then
            ok "/health loaded:true nach ~${i}s"
            break
        fi
        sleep 1
    done
    if [ "$loaded" != "true" ]; then
        fail "Brain wurde binnen 90s nicht loaded:true — siehe $BRAIN_LOG"
        return 1
    fi

    # (b) EIN echter Roundtrip als Beweis (kein Fake-grün).
    brain_roundtrip "Sag in genau einem kurzen Satz Hallo." 30 >/dev/null
    if [ -z "${BRAIN_RT_TEXT// /}" ]; then
        fail "Brain loaded, aber /v1/chat lieferte LEER (${BRAIN_RT_MS}ms) — WEDGE, kein Beweis."
        return 1
    fi
    ok "Roundtrip-Beweis (${BRAIN_RT_MS}ms): \"$(printf '%s' "$BRAIN_RT_TEXT" | head -c 80)\""
    return 0
}

# ── RAM-Wand: freier Speicher-Prozentsatz aus memory_pressure ────────────────
mem_free_pct() {
    memory_pressure 2>/dev/null | tail -1 | grep -oE '[0-9]+%' | tail -1 || true
}

# ── Stabilitäts-Guards: READ-ONLY Lessons-als-Guards (aus 0.5-LEDGER) ─────────
# Jeder Guard druckt EINE Zeile OK/WARN/INFO + Detail. WARN ist ein HINWEIS,
# kein Fehler: es zählt STABILITY_WARN hoch, verschlechtert aber NIE den Exit.
# Defensiv: fehlt ein Werkzeug (lsof/launchctl/git), gibt es INFO statt Crash.
# set +e-Stil wie die anderen Skripte — kein Probe-Fehler darf abbrechen.
stability_guards() {
    STABILITY_WARN=0
    # kleine Label-Logger (10-breit) — gok/gwarn/ginfo nur hier benutzt.
    gok()   { ok   "$(printf '%-13s OK   %s' "$1" "$2")"; }
    gwarn() { warn "$(printf '%-13s WARN %s' "$1" "$2")"; STABILITY_WARN=$((STABILITY_WARN+1)); }
    ginfo() { log  "$(printf '%-13s INFO %s' "$1" "$2")"; }

    # ── Guard 1: mlx-lm Version-Pin ──────────────────────────────────────────
    local health engine
    health="$(brain_health)"
    engine="$(_json_field "$health" engine)"
    if [ -z "$engine" ]; then
        ginfo "mlx-pin" "Brain /health stumm — Engine-Version nicht prüfbar"
    elif [ "$engine" = "$HOSHI_MLX_PIN" ]; then
        gok "mlx-pin" "engine=$engine (Pin $HOSHI_MLX_PIN)"
    else
        gwarn "mlx-pin" "mlx-lm-Drift: $engine (Pin 0.31.2; 0.31.3 = Regression #1242 'Missing 54 parameters', Boot-Hang)"
    fi

    # ── Guard 2: 0.0.0.0-Bind je Sidecar (LAN/ct-106-Erreichbarkeit) ─────────
    if ! command -v lsof >/dev/null 2>&1; then
        ginfo "bind" "nicht prüfbar (kein lsof)"
    else
        local bn bp out
        for pair in "brain:$BRAIN_PORT" "whisper:$WHISPER_PORT" "bridge:$BRIDGE_PORT"; do
            bn="${pair%%:*}"; bp="${pair##*:}"
            out="$(lsof -nP -iTCP:"$bp" -sTCP:LISTEN 2>/dev/null)"
            if [ -z "$out" ]; then
                continue                       # Port tot → die Tabelle meldet's schon
            elif printf '%s\n' "$out" | grep -qE "(\*|0\.0\.0\.0):$bp"; then
                gok "bind:$bn" "bindet *:$bp (LAN/ct-106 erreichbar)"
            elif printf '%s\n' "$out" | grep -qE "127\.0\.0\.1:$bp|\[::1\]:$bp"; then
                gwarn "bind:$bn" "$bn bindet nur localhost:$bp — ct-106/LAN kann es nicht erreichen (B-091: 1 Monat stille Grounding-Ausfälle)"
            else
                ginfo "bind:$bn" "Bind-Adresse unklar auf :$bp — manuell prüfen"
            fi
        done
    fi

    # ── Guard 3: Reboot-Readiness (launchd) ──────────────────────────────────
    if ! command -v launchctl >/dev/null 2>&1; then
        ginfo "launchd" "nicht prüfbar (kein launchctl)"
    else
        if launchctl print "gui/$(id -u)/$HOSHI_DEPLOY_LABEL" >/dev/null 2>&1; then
            gok "launchd" "0.8-Backend launchd-geladen (übersteht Reboot)"
        else
            gwarn "launchd" "0.8-Backend NICHT unter launchd — übersteht keinen Reboot (bin/hoshi deploy)"
        fi
        local jobs
        jobs="$(launchctl list 2>/dev/null | grep -iE 'hoshi|e4b' | awk '{print $3}' \
                | grep -vxF "$HOSHI_DEPLOY_LABEL" | tr '\n' ' ')"
        if [ -n "${jobs// /}" ]; then
            ginfo "launchd-jobs" "weitere geladene hoshi/e4b-Jobs: ${jobs%% }"
        else
            ginfo "launchd-jobs" "kein separater e4b-/Sidecar-launchd-Job geladen (Sidecars laufen manuell/anders → kein Auto-Restart nach Reboot)"
        fi
    fi

    # ── Guard 4: venv-Gesundheit (dead symlink = latenter Totalausfall) ──────
    # Prüft das venv des Pfads, den resolve_sidecar_run_script WÄHLEN würde
    # (S4-Cutover): sonst attestiert der Guard dem 0.5-venv Gesundheit, während
    # der laufende Sidecar längst aus dem Repo kommt (Ehrlichkeits-Drift).
    local brain_venv_eff="$HOSHI_BRAIN_VENV" whisper_venv_eff="$HOSHI_WHISPER_VENV"
    case "${HOSHI_SIDECARS_FROM_REPO:-}" in
        false|FALSE|0) ;;
        *)
            [ -x "$REPO_ROOT/sidecars/brain/.venv/bin/python" ] && brain_venv_eff="$REPO_ROOT/sidecars/brain/.venv/bin/python"
            [ -x "$REPO_ROOT/sidecars/stt/.venv/bin/python" ] && whisper_venv_eff="$REPO_ROOT/sidecars/stt/.venv/bin/python"
            ;;
    esac
    local vn vp
    for pair in "brain:$brain_venv_eff" "whisper:$whisper_venv_eff"; do
        vn="${pair%%:*}"; vp="${pair#*:}"
        if [ -z "$vp" ]; then
            ginfo "venv:$vn" "Pfad unbekannt — skip"
        elif [ -x "$vp" ]; then
            gok "venv:$vn" "venv-python ausführbar ($vp)"
        elif [ -e "$vp" ]; then
            gwarn "venv:$vn" "venv-python existiert, aber NICHT ausführbar: $vp"
        else
            gwarn "venv:$vn" "venv-python fehlt/dead symlink (latenter Totalausfall): $vp"
        fi
    done

    # ── Guard 5: Deploy-Drift (laufendes Jar älter als letzter CODE-Commit) ──
    # Präzise: vergleicht gegen den letzten Commit, der KOMPILIERTE Quellen berührt
    # (*.kt/*.gradle.kts/settings/gradle.properties) — NICHT gegen jeden Commit
    # (sonst feuert es bei Doku-/Shell-/Vault-Commits fälschlich „deploy nötig").
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 4 "http://127.0.0.1:$HOSHI_DEPLOY_PORT/api/health" 2>/dev/null || echo 000)"
    if [ "$code" != "200" ]; then
        ginfo "deploy-drift" "0.8-Service (:$HOSHI_DEPLOY_PORT /api/health=$code) nicht 200 — Drift nicht prüfbar"
    elif [ ! -f "$HOSHI_DEPLOY_JAR" ]; then
        gwarn "deploy-drift" "Service läuft, aber Jar fehlt: $HOSHI_DEPLOY_JAR"
    elif ! command -v git >/dev/null 2>&1 || ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        ginfo "deploy-drift" "kein git-Repo unter $REPO_ROOT — Drift nicht prüfbar"
    else
        local jar_mt head_ct
        jar_mt="$(stat -f %m "$HOSHI_DEPLOY_JAR" 2>/dev/null || stat -c %Y "$HOSHI_DEPLOY_JAR" 2>/dev/null || echo 0)"
        head_ct="$(git -C "$REPO_ROOT" log -1 --format=%ct -- '*.kt' '*.gradle.kts' 'settings.gradle.kts' 'gradle.properties' 2>/dev/null || echo 0)"
        if [ "${jar_mt:-0}" -gt 0 ] && [ "${head_ct:-0}" -gt 0 ] && [ "$jar_mt" -lt "$head_ct" ]; then
            gwarn "deploy-drift" "laufendes Jar älter als letzter Code-Commit (Jar $(date -r "$jar_mt" '+%m-%d %H:%M:%S') < $(date -r "$head_ct" '+%m-%d %H:%M:%S')) — bin/hoshi deploy nötig"
        else
            gok "deploy-drift" "Jar aktuell ($(date -r "${jar_mt:-0}" '+%Y-%m-%d %H:%M') ≥ letzter Code-Commit)"
        fi
    fi

    # ── Guard 6: Modell-Vollständigkeit (fehlende safetensors/.incomplete-Reste,
    # Lehre aus einem gescheiterten Brain-Start) — der harte Prüfer bleibt
    # tools/models-verify.sh selbst; hier ist es NUR ein nicht-blockierender
    # Hinweis, damit doctor niemals wegen eines optionalen Modells (z. B. das
    # e4b-Test-Brain) rot wird. ──────────────────────────────────────────────
    local models_script="$REPO_ROOT/tools/models-verify.sh"
    if [ ! -x "$models_script" ]; then
        ginfo "models" "tools/models-verify.sh fehlt/nicht ausführbar — Modell-Check übersprungen"
    else
        local models_out models_rc counts_line
        models_out="$("$models_script" 2>&1)" && models_rc=0 || models_rc=$?
        counts_line="$(printf '%s\n' "$models_out" | grep -A1 '── Zusammenfassung ──' | tail -1 | sed 's/^  *//')"
        # WARN nicht nur bei required-Fehlschlag (models_rc≠0), sondern auch wenn
        # ein NICHT-required Modell (z. B. e4b-Test-Brain) FEHLT/UNVOLLSTAENDIG/
        # REF-DEFEKT ist — genau der Fall, der heute den Brain-Start überraschte.
        if [ "$models_rc" -ne 0 ]; then
            gwarn "models" "REQUIRED-Modell(e) nicht OK — ${counts_line:-siehe Details} (tools/models-verify.sh)"
        elif printf '%s' "$counts_line" | grep -qE 'FEHLT: [1-9]|UNVOLLSTAENDIG: [1-9]|REF-DEFEKT: [1-9]'; then
            gwarn "models" "optionale(s) Modell(e) mit Problem — ${counts_line:-siehe Details} (tools/models-verify.sh)"
        else
            gok "models" "${counts_line:-alle Modelle OK} (tools/models-verify.sh)"
        fi
    fi

    # ── Guard 7: Eval-Baseline-Frische — driftende Prompt-Suiten machen Blind-A/B-
    # Vergleiche mit alten Läufen still ungültig; der Guard macht es sichtbar
    # (WARN, non-blocking — bewusste Neu-Baselines ziehen das Manifest nach).
    local eval_script="$REPO_ROOT/tools/eval-baselines-verify.sh"
    if [ -f "$eval_script" ]; then
        local eval_rc=0
        "$eval_script" >/dev/null 2>&1 || eval_rc=$?
        if [ "$eval_rc" -eq 0 ]; then
            gok "eval-baselines" "Suiten unverändert (tools/eval-baselines-verify.sh)"
        else
            gwarn "eval-baselines" "DRIFT erkannt — Blind-A/B gegen alte Läufe ungültig (tools/eval-baselines-verify.sh)"
        fi
    fi
}
