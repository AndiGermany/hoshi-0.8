#!/usr/bin/env bash
# sidecars/brain/probe-next.sh — kontrollierte A/B-Probe: laedt die NEUE e4b-
# Shared-KV-Revision (475b9088…, Layer 24-41 teilen sich K/V) in einem
# ZWEITEN, isolierten venv (.venv-next) mit einem gefixten mlx-lm, OHNE die
# Live-Referenz (.venv/, requirements.txt, server.py, run.sh) anzufassen.
#
# WARUM ueberhaupt: mlx-community hat mlx-community/gemma-4-e4b-it-4bit still
# re-exportiert. Die neue Revision streicht fuer Layer 24-41 die redundanten
# k_proj/v_proj/k_norm-Weight-Keys aus dem safetensors-Index (2770 Tensoren
# ggue. 2894 bei der aktuell gepinnten Alt-Revision deb1db71…, verifiziert per
# Index-Diff). Das gepinnte mlx-lm==0.31.2 (requirements.txt) erwartet diese
# Keys pro Layer trotzdem -> "Missing N parameters", Ladefehler (s.
# models.json brain-e4b-Notiz + vault/tracks/prep/PREP-mlx-modell-upgrade.md,
# Abschnitt "Revisions-Incident 2.0"). Dieses Skript prueft kontrolliert, ob
# ein neueres mlx-lm die neue Revision sauber laedt UND unsere zwei Endpoints
# (/v1/chat, /v1/score) weiter bedient — als Vorstufe zu Golden-Stichprobe +
# Baseline-Suite + Blind-A/B, NICHT als Ersatz dafuer.
#
# ⚠️  RAM-WARNUNG (16-GB-Wand) ⚠️
#   Ein zweites warmes e4b-Modell braucht laut mlx-lm-PR-1240-Testlauf (M4 Pro)
#   ~4.34 GB Peak. Parallel zum laufenden Prod-e4b (Port 8041, .venv/…) geht
#   das NUR mit reichlich freier/reclaimable RAM. Live-Messung beim Schreiben
#   dieses Skripts (2026-07-17, Prod-e4b resident): nur ~359 MB hart frei,
#   ~4.5 GB grob reclaimable (free+inactive+purgeable) — das ist SCHON knapp
#   an der unten geprueften 5-GB-Schwelle. Diese Zahl ist eine Momentaufnahme,
#   KEINE Konstante — das Skript misst bei jedem Lauf frisch nach (s.
#   ram_preflight) und bricht bei zu wenig Luft laut ab (Override:
#   HOSHI_PROBE_FORCE=1, informierter Risiko-Call, keine Automatik). Der
#   sichere Weg bleibt: Prod-e4b VOR der Probe stoppen.
#
# NICHT automatisch verkabelt: kein run.sh/bootstrap.sh/launchd ruft dieses
# Skript. Reines Sourcen (`source probe-next.sh`) startet NICHTS — nur ein
# DIREKTER Aufruf tut das (s. main()-Guard ganz unten, BASH_SOURCE==$0-Check).
#
# Aufruf:   bash sidecars/brain/probe-next.sh
# Env:
#   HOSHI_PROBE_PORT        Probe-Server-Port (default 8043)
#   HOSHI_PROBE_HOST        Bind-Host (default 127.0.0.1 — nur lokal, kein 0.0.0.0)
#   HOSHI_PROBE_FORCE=1     RAM-Guard uebersteuern (informierter Risiko-Call)
#   HOSHI_PROBE_KEEP_ALIVE=1  Probe-Server nach dem Test NICHT killen (zum Weiterpoken)
set -euo pipefail

# ── Absolute Pfade (symlink-sicher, wie run.sh) ──────────────────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
BRAIN_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

fail() { echo "[probe-next] FATAL: $*" >&2; exit 1; }
say()  { echo "[probe-next] $*" >&2; }
warn() { echo "[probe-next] WARN: $*" >&2; }

# ── Ziel-Revision (recherchiert 2026-07-17, s. requirements-next.txt-Kopf) ───
# Fix-Commit df1d3f3c9a7aae402dcbb8f41d4c36bcc13a50ae (PR #1240, gemerged
# 2026-05-04) ist NICHT in einem PyPI-Release (Latest weiterhin v0.31.3,
# 2026-04-22) — requirements-next.txt installiert mlx-lm deshalb per Git-Pin.
E4B_REPO="mlx-community/gemma-4-e4b-it-4bit"
NEW_REVISION="475b9088d29754a3379866cf5aeb6b41acd313c2"

VENV_NEXT_DIR="$BRAIN_DIR/.venv-next"
VENV_NEXT_PY="$VENV_NEXT_DIR/bin/python"
SERVER_PY="$BRAIN_DIR/server.py"
REQS_NEXT="$BRAIN_DIR/requirements-next.txt"
NEW_SNAPSHOT_DIR=""   # von resolve_new_snapshot() gesetzt

PORT="${HOSHI_PROBE_PORT:-8043}"
HOST="${HOSHI_PROBE_HOST:-127.0.0.1}"
LOG_DIR="${HOSHI_LOG_DIR:-$HOME/.hoshi/logs}"

SERVER_PID=""
PROBE_LOG=""

# ── RAM-Preflight ─────────────────────────────────────────────────────────
# Grobe Naeherung ueber vm_stat (free+inactive+purgeable Pages) — KEINE
# Garantie (das OS gibt inactive/purgeable nicht immer verlustfrei zurueck).
# Blockt nur, wenn ZUSAETZLICH der Prod-e4b (Port 8041) gerade resident ist —
# laeuft kein Prod-Brain, ist die Probe ohnehin unkritisch (ein Modell statt
# zwei).
ram_preflight() {
    local prod_alive=0 reclaim_gb
    (exec 3<>"/dev/tcp/127.0.0.1/8041") 2>/dev/null && { exec 3>&- 2>/dev/null; prod_alive=1; }

    reclaim_gb="$(python3 - <<'PY' 2>/dev/null
import re, subprocess
out = subprocess.run(["vm_stat"], capture_output=True, text=True, check=True).stdout
def grab(pat):
    m = re.search(pat, out)
    return int(m.group(1)) if m else 0
psize = grab(r"page size of (\d+) bytes") or 16384
free = grab(r"Pages free:\s+(\d+)\.")
inactive = grab(r"Pages inactive:\s+(\d+)\.")
purgeable = grab(r"Pages purgeable:\s+(\d+)\.")
print(f"{(free + inactive + purgeable) * psize / 1024**3:.2f}")
PY
)" || reclaim_gb="?"

    say "RAM-Preflight: ~${reclaim_gb} GB reclaimable (frei+inactive+purgeable, grobe Naeherung). Prod-e4b (:8041): $([ "$prod_alive" = 1 ] && echo RESIDENT || echo "nicht erreichbar")."

    if [ "$prod_alive" = 1 ] && [ "$reclaim_gb" != "?" ]; then
        if ! python3 -c "import sys; sys.exit(0 if float('$reclaim_gb') >= 5.0 else 1)" 2>/dev/null; then
            if [ "${HOSHI_PROBE_FORCE:-0}" = "1" ]; then
                warn "reclaimable ~${reclaim_gb} GB < 5 GB UND Prod-e4b laeuft parallel — HOSHI_PROBE_FORCE=1 gesetzt, fahre trotzdem fort (informierter Risiko-Call: kann Prod-Brain in Swap/Compressor draengen, Latenz-Spikes moeglich)."
            else
                fail "reclaimable RAM (~${reclaim_gb} GB) < 5 GB UND Prod-e4b (:8041) laeuft parallel — 16-GB-Wand-Risiko. Entweder Prod-e4b vorher stoppen (pkill -f server_e4b.py bzw. das 0.8-Pendant) ODER bewusst HOSHI_PROBE_FORCE=1 setzen (Andi/Hand-Entscheidung, keine Automatik)."
            fi
        fi
    fi
}

# ── .venv-next bootstrappen (idempotent, Muster aus bootstrap.sh) ───────────
bootstrap_venv_next() {
    local py=python3.11
    if ! command -v "$py" >/dev/null 2>&1; then
        command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden — Python fehlt komplett"
        py=python3
        warn "python3.11 nicht gefunden, falle zurueck auf $py ($("$py" -c 'import sys; print("%d.%d" % sys.version_info[:2])')). Das Live-.venv lief auf 3.11.15 — bei Wheel-Aerger zuerst python3.11 installieren."
    fi
    say "Python: $("$py" --version 2>&1) ($py)"

    if [ -d "$VENV_NEXT_DIR" ]; then
        [ -x "$VENV_NEXT_PY" ] || fail ".venv-next existiert, ist aber kaputt (kein bin/python) — erst 'rm -rf $VENV_NEXT_DIR', dann neu probieren."
        say ".venv-next existiert schon — skip create (pip install laeuft trotzdem, idempotent)."
    else
        say "lege .venv-next an ($py)"
        "$py" -m venv "$VENV_NEXT_DIR" || fail "venv-Erstellung fehlgeschlagen"
    fi

    say "pip install -r requirements-next.txt (mlx-lm kommt per Git-Pin — dauert, braucht Netz NUR fuer diesen einen Schritt)"
    "$VENV_NEXT_PY" -m pip install -q --upgrade pip \
        || fail "pip-Upgrade fehlgeschlagen"
    "$VENV_NEXT_PY" -m pip install -q -r "$REQS_NEXT" \
        || fail "pip install -r requirements-next.txt fehlgeschlagen — s. Fehler oben. Netz da? Apple Silicon (mlx-* brauchen es)?"

    say "verifiziere Kern-Imports (mlx_lm, huggingface_hub, fastapi)"
    "$VENV_NEXT_PY" -c "import mlx_lm, huggingface_hub, fastapi, uvicorn, pydantic" \
        || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar."

    local mlx_lm_version
    mlx_lm_version="$("$VENV_NEXT_PY" -c 'import mlx_lm; print(getattr(mlx_lm, "__version__", "?"))' 2>/dev/null || echo "?")"
    say "OK — .venv-next bereit, mlx_lm.__version__=$mlx_lm_version"
}

# ── Neue Revision lokal aufloesen + verifizieren ─────────────────────────────
# Bewusst NICHT selbst den HF-Cache-Pfad zusammenbauen (HF_HOME/Cache-Layout
# koennen abweichen) — stattdessen huggingface_hub selbst fragen, exakt das
# Muster aus run.sh's model_fully_cached(), nur mit revision=NEW_REVISION
# statt refs/main. local_files_only=True => rein lesend, KEIN Netz, KEIN
# stiller Pull (Brief-15-Prinzip).
resolve_new_snapshot() {
    NEW_SNAPSHOT_DIR="$("$VENV_NEXT_PY" - "$E4B_REPO" "$NEW_REVISION" <<'PY'
import sys, glob, os
from huggingface_hub import snapshot_download

repo, revision = sys.argv[1], sys.argv[2]
try:
    path = snapshot_download(repo, revision=revision, local_files_only=True)
except Exception as e:
    print(f"ERR:{e}", file=sys.stderr)
    sys.exit(1)
repo_root = os.path.dirname(os.path.dirname(path))
if glob.glob(os.path.join(repo_root, "blobs", "*.incomplete")):
    print("ERR: abgebrochene .incomplete-Blobs im Cache", file=sys.stderr)
    sys.exit(1)
if not glob.glob(os.path.join(path, "*.safetensors")):
    print("ERR: keine safetensors im Snapshot", file=sys.stderr)
    sys.exit(1)
if not os.path.isfile(os.path.join(path, "config.json")):
    print("ERR: config.json fehlt im Snapshot", file=sys.stderr)
    sys.exit(1)
print(path)
PY
)" || fail "Neue Revision '$NEW_REVISION' ist NICHT vollstaendig lokal im HF-Cache (local_files_only=True fand nichts Brauchbares). Dieses Skript laedt NICHTS herunter — falls die Revision wirklich fehlt, ist ein Online-Pull ein bewusster SEPARATER Schritt (Andi/Hand), nicht dieses Skript."
    say "Neue Revision lokal verifiziert: $NEW_SNAPSHOT_DIR"
}

port_busy() {
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && { exec 3>&- 2>/dev/null; return 0; }
    return 1
}

# ── Probe-Server starten ─────────────────────────────────────────────────────
# --model bekommt den LOKALEN Snapshot-Pfad (nicht die Repo-ID) — mlx_lm's
# _download()/get_model_path() nimmt einen existierenden lokalen Pfad 1:1
# (verifiziert im mlx-lm-Quellcode, main UND dem Fix-Commit:
# `model_path = Path(path_or_hf_repo); if not model_path.exists(): ...`).
# Das laedt server.py UNVERAENDERT und ruehrt refs/main (den Prod-Pin) NICHT an.
start_probe_server() {
    port_busy && fail "Port $PORT ist bereits belegt — dieses Skript startet nichts auf einen fremden Prozess drauf. Erst pruefen/freimachen (lsof -iTCP:$PORT -sTCP:LISTEN)."
    [ -x "$VENV_NEXT_PY" ] || fail ".venv-next-Python fehlt: $VENV_NEXT_PY"
    [ -n "$NEW_SNAPSHOT_DIR" ] || fail "NEW_SNAPSHOT_DIR leer — resolve_new_snapshot() vor start_probe_server() aufrufen."

    mkdir -p "$LOG_DIR" || fail "Log-Verzeichnis nicht anlegbar: $LOG_DIR"
    PROBE_LOG="$LOG_DIR/brain-probe-next-$(date +%Y%m%d-%H%M%S).log"
    say "starte Probe-Server: $VENV_NEXT_PY $SERVER_PY --model $NEW_SNAPSHOT_DIR --host $HOST --port $PORT"
    say "Log: $PROBE_LOG"

    (
        cd "$BRAIN_DIR"
        export VIRTUAL_ENV="$VENV_NEXT_DIR"
        export PATH="$VENV_NEXT_DIR/bin:$PATH"
        export PYTHONUNBUFFERED=1
        export HF_HUB_OFFLINE=1   # Pfad ist bereits lokal aufgeloest — Guertel+Hosentraeger
        unset PYTHONHOME 2>/dev/null || true
        exec "$VENV_NEXT_PY" "$SERVER_PY" --model "$NEW_SNAPSHOT_DIR" --host "$HOST" --port "$PORT"
    ) >"$PROBE_LOG" 2>&1 &
    SERVER_PID=$!
    say "Probe-Server PID $SERVER_PID"
}

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        if [ "${HOSHI_PROBE_KEEP_ALIVE:-0}" = "1" ]; then
            say "HOSHI_PROBE_KEEP_ALIVE=1 — Probe-Server PID $SERVER_PID bleibt oben (Port $PORT). Manuell stoppen: kill $SERVER_PID"
        else
            say "cleanup: stoppe Probe-Server PID $SERVER_PID"
            kill "$SERVER_PID" 2>/dev/null || true
            wait "$SERVER_PID" 2>/dev/null || true
        fi
    fi
}

wait_for_health() {
    local tries=0 max_tries=180 url="http://$HOST:$PORT/health" body   # bis zu ~3 Min (Kaltstart: Load + Metal-Warmup)
    say "warte auf /health (loaded:true), bis zu ${max_tries}s..."
    while [ "$tries" -lt "$max_tries" ]; do
        kill -0 "$SERVER_PID" 2>/dev/null || fail "Probe-Server (PID $SERVER_PID) ist bereits tot — Log pruefen: $PROBE_LOG"
        body="$(curl -s -m 3 "$url" 2>/dev/null || true)"
        if printf '%s' "$body" | grep -q '"loaded"[[:space:]]*:[[:space:]]*true'; then
            say "health OK: $body"
            return 0
        fi
        sleep 1
        tries=$((tries + 1))
    done
    fail "Timeout (${max_tries}s) — Server meldet nie loaded:true. Log: $PROBE_LOG"
}

# ── Roundtrip (/v1/chat) + /v1/score ─────────────────────────────────────────
# Beides ueber urllib (stdlib, keine extra Deps) im .venv-next-Python.
# /v1/chat ist IMMER SSE (StreamingResponse, s. server.py chat()/gen()) —
# Frames einsammeln bis "[DONE]".
run_checks() {
    say "Roundtrip (/v1/chat) + /v1/score ueber $VENV_NEXT_PY (urllib)..."
    if ! "$VENV_NEXT_PY" - "$HOST" "$PORT" <<'PY'
import json, sys, time, urllib.request

host, port = sys.argv[1], sys.argv[2]
base = f"http://{host}:{port}"

def post(path, payload, timeout=60):
    req = urllib.request.Request(
        base + path, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    return urllib.request.urlopen(req, timeout=timeout)

t0 = time.time()
resp = post("/v1/chat", {
    "messages": [{"role": "user", "content": "Sag in genau einem kurzen Satz Hallo."}],
    "sessionId": "probe-next", "userId": "probe-next",
    "max_tokens": 40, "temperature": 0.7,
})
text = ""
for raw in resp:
    line = raw.decode("utf-8", "replace").strip()
    if not line.startswith("data:"):
        continue
    payload = line[len("data:"):].strip()
    if payload == "[DONE]":
        break
    try:
        frame = json.loads(payload)
    except json.JSONDecodeError:
        continue
    text += frame.get("delta", "")
ms = int((time.time() - t0) * 1000)
if not text.strip():
    print(f"ROUNDTRIP FAIL: leere Antwort nach {ms}ms")
    sys.exit(1)
print(f"ROUNDTRIP OK ({ms}ms): {text!r}")

resp = post("/v1/score", {"text": "Das ist ein kurzer Testsatz fuer den Score-Check."})
body = json.loads(resp.read().decode("utf-8"))
tokens = body.get("tokens", [])
logprobs = body.get("logprobs", [])
mean_surprisal = body.get("mean_surprisal")
if not tokens or not logprobs or mean_surprisal is None:
    print(f"SCORE FAIL: unvollstaendige Antwort: {body}")
    sys.exit(1)
if len(tokens) != len(logprobs):
    print(f"SCORE FAIL: tokens/logprobs-Laengen weichen ab ({len(tokens)} vs {len(logprobs)})")
    sys.exit(1)
if mean_surprisal != mean_surprisal or mean_surprisal < 0:   # NaN-Check + Sanity
    print(f"SCORE FAIL: mean_surprisal unplausibel: {mean_surprisal}")
    sys.exit(1)
print(f"SCORE OK: {len(tokens)} tokens, mean_surprisal={mean_surprisal:.3f}")
PY
    then
        fail "Roundtrip/Score-Check fehlgeschlagen — Log: $PROBE_LOG"
    fi
    say "Roundtrip + /v1/score: BEIDE OK."
}

main() {
    say "=== mlx-lm Upgrade-Probe (.venv-next, Port $PORT) ==="
    say "Ziel: $E4B_REPO @ $NEW_REVISION (Shared-KV Layer 24-41)"
    trap cleanup EXIT
    ram_preflight
    bootstrap_venv_next
    resolve_new_snapshot
    start_probe_server
    wait_for_health
    run_checks
    say "=== PROBE GRUEN: Roundtrip + /v1/score OK. ==="
    say "Das ist NUR der Rauch-Test. Vor jedem Default-Flip (models.json pinned_revision, refs/main):"
    say "  1) Golden-Stichprobe von Hand gegenlesen — keine Automatik ersetzt das Lesen."
    say "  2) Baseline-Suite (training/eval-baselines.json: lora-v0 + mitgift-base) auf ALT (.venv,"
    say "     mlx-lm 0.31.2, alte Revision) UND NEU (.venv-next) laufen lassen, blind vergleichen."
    say "  3) Erst danach, bewusst: refs/main + models.json pinned_revision umstellen (Andi-Gate)."
}

# Nur bei DIREKTEM Aufruf ausfuehren — reines Sourcen startet nichts.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
