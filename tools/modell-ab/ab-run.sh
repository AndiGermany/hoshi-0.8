#!/usr/bin/env bash
# tools/modell-ab/ab-run.sh — gefahrloser A/B-Treiber für den Brain-Revisions-Zug
# mlx-community/gemma-4-e4b-it-4bit:  deb1db71 (Pin, LIVE)  ->  475b9088 (Kandidat)
#
# WARUM ES DAS GIBT
# Die neue Revision lässt sich mit dem gepinnten mlx-lm 0.31.2 NICHT laden
# ("ValueError: Missing 54 parameters", Layer 24–41 k_norm/k_proj/v_proj). Das ist
# auf DIESEM Mac zweimal live passiert — zuletzt in der Nacht 18.->19.08.2026
# (~4,5 h totes Brain, Watchdog-Restart-Schleife, siehe ab-runbook.md "Beweislage").
# Dieses Skript macht den Zug in kleinen, einzeln umkehrbaren Schritten — und lässt
# refs/main bis zum allerletzten Schritt UNANGETASTET.
#
# KERNPRINZIP (das ist der Unterschied zu "mal eben ausprobieren")
#   Phase `probe` biegt KEINEN Ref um. Sie übergibt server.py den ABSOLUTEN
#   Snapshot-Pfad der neuen Revision als --model. mlx_lm._download() nimmt einen
#   existierenden lokalen Pfad 1:1 durch. Damit ist der Rückweg aus Phase `probe`
#   = "Prozess beenden" — es gibt gar keinen veränderten Zustand.
#   Erst `flip` (nach GO) fasst refs/main an, und `unflip` ist der bewiesene Rückweg
#   (exakt der Handgriff, der am 19.08. um 03:07 das Brain zurückgeholt hat).
#
# 16-GB-WAND: e4b belegt ~5,2 GB resident. ZWEI Brains passen nicht. Jede Phase, die
# ein Modell lädt, verlangt vorher ein totes Prod-Brain — das Skript prüft das selbst
# und bricht laut ab statt zu raten. Deshalb ist `probe` ein ANDI-FENSTER: Hoshi ist
# in dieser Zeit hirnlos (Voice/Chat antworten nicht).
#
# Aufruf:  bash tools/modell-ab/ab-run.sh <phase>
# Phasen:  status | preflight | golden-old | venv-next | probe | golden-new |
#          restore | flip | unflip
# Ablauf:  ab-runbook.md (dort steht auch, was JEDE Phase beweisen muss)

set -euo pipefail

# ── Pfade (symlink-sicher, keine hart codierten Home-Pfade) ──────────────────
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
    DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ "$SOURCE" != /* ]] && SOURCE="$DIR/$SOURCE"
done
SELF_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
# HOSHI_AB_REPO_ROOT: nötig, wenn das Skript aus einem Worktree heraus gegen das
# ECHTE Repo laufen soll — .venv/ und training/ liegen nur dort (gitignored).
REPO_ROOT="$(cd -P "${HOSHI_AB_REPO_ROOT:-$SELF_DIR/../..}" && pwd)"

BRAIN_DIR="$REPO_ROOT/sidecars/brain"
VENV_PROD="$BRAIN_DIR/.venv"
VENV_NEXT="$BRAIN_DIR/.venv-next"
SERVER_PY="$BRAIN_DIR/server.py"

REPO_ID="mlx-community/gemma-4-e4b-it-4bit"
REV_PIN="deb1db712068b1c9f83fb1c97f08c1204b9459a1"      # LIVE, bewährt
REV_NEW="475b9088d29754a3379866cf5aeb6b41acd313c2"      # Kandidat (2026-07-06)

HF_CACHE="${HF_HOME:-$HOME/.cache/huggingface}"
[ -d "$HF_CACHE/hub" ] && HF_CACHE="$HF_CACHE/hub"
REPO_CACHE="$HF_CACHE/models--mlx-community--gemma-4-e4b-it-4bit"
REFS_MAIN="$REPO_CACHE/refs/main"

PROD_PORT="${HOSHI_BRAIN_PORT:-8041}"
PROBE_PORT="${HOSHI_AB_PROBE_PORT:-8043}"

STATE_DIR="${HOSHI_RUN_DIR:-$HOME/.hoshi/run}"
STATE_FILE="$STATE_DIR/modell-ab.state"
OUT_DIR="${HOSHI_AB_OUT_DIR:-$REPO_ROOT/training/modell-ab}"

# ── Ausgabe ──────────────────────────────────────────────────────────────────
if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[0m'; G=$'\033[32m'; Y=$'\033[33m'; E=$'\033[31m'; D=$'\033[2m';
else B=""; R=""; G=""; Y=""; E=""; D=""; fi
say()  { echo "${B}[ab]${R} $*"; }
ok()   { echo "${G}  ✓${R} $*"; }
warn() { echo "${Y}  !${R} $*"; }
info() { echo "${D}    $*${R}"; }
fail() { echo "${E}  ✗ FATAL${R} $*" >&2; exit 1; }

# ── Golden Turns ─────────────────────────────────────────────────────────────
# Drei kurze, für Hoshis echten Verkehr repräsentative Turns. Greedy (temperature
# 0.0 -> make_sampler(temp=0.0) -> argmax) und frische sessionId pro Request:
# damit ist der Vergleich ALT vs. NEU byte-genau führbar, nicht "klingt ähnlich".
# Überschreibbar: HOSHI_AB_GOLDEN=/pfad/zu.jsonl  (je Zeile {"prompt": "..."}).
golden_prompts() {
    if [ -n "${HOSHI_AB_GOLDEN:-}" ]; then
        [ -f "$HOSHI_AB_GOLDEN" ] || fail "HOSHI_AB_GOLDEN zeigt ins Leere: $HOSHI_AB_GOLDEN"
        python3 -c '
import json,sys
for line in open(sys.argv[1], encoding="utf-8"):
    line=line.strip()
    if line: print(json.loads(line)["prompt"])
' "$HOSHI_AB_GOLDEN"
        return
    fi
    cat <<'EOF'
Sag in genau einem kurzen Satz Hallo.
Mach das Licht im Wohnzimmer an und sag mir in einem Satz, was du getan hast.
Erklär mir in zwei Sätzen, warum es sinnvoll ist, ein Modell auf eine feste Revision zu pinnen.
EOF
}

# ── Bausteine ────────────────────────────────────────────────────────────────
port_busy() { nc -z 127.0.0.1 "$1" >/dev/null 2>&1; }

health_json() { curl -s -m 4 "http://127.0.0.1:$1/health" 2>/dev/null || true; }

health_loaded() {
    python3 -c '
import json,sys
try: print("true" if json.loads(sys.argv[1]).get("loaded") is True else "false")
except Exception: print("false")
' "$(health_json "$1")" 2>/dev/null || echo false
}

# freier + inaktiver Speicher in MB (dieselbe Quelle wie server.py::_classify_memory)
mem_free_inactive_mb() {
    vm_stat | python3 -c '
import re,sys
pg=4096; vals={}
for line in sys.stdin:
    m=re.match(r"^(.*?):\s+(\d+)\.", line.strip())
    if m: vals[m.group(1)]=int(m.group(2))
    m2=re.search(r"page size of (\d+) bytes", line)
    if m2: pg=int(m2.group(1))
free=vals.get("Pages free",0)+vals.get("Pages inactive",0)+vals.get("Pages speculative",0)
print(int(free*pg/1024/1024))
'
}

snapshot_dir() {  # $1 = revision
    local d="$REPO_CACHE/snapshots/$1"
    [ -d "$d" ] || fail "Snapshot fehlt im HF-Cache: $d"
    [ -e "$d/model.safetensors" ] || fail "Snapshot ohne Gewichte: $d/model.safetensors"
    printf '%s' "$d"
}

assert_no_incomplete() {
    local n
    n="$(find "$REPO_CACHE/blobs" -name '*.incomplete' 2>/dev/null | wc -l | tr -d ' ')"
    [ "$n" = "0" ] || fail "$n .incomplete-Reste im HF-Cache — erst aufräumen, sonst startet gar nichts sauber."
}

current_ref() { [ -f "$REFS_MAIN" ] && tr -d '\n' < "$REFS_MAIN" || echo "(fehlt)"; }

# refs/main BYTE-GENAU schreiben (ohne Trailing-Newline — run.sh prüft genau das).
write_ref() {
    mkdir -p "$(dirname "$REFS_MAIN")"
    printf '%s' "$1" > "$REFS_MAIN"
    local got; got="$(current_ref)"
    [ "$got" = "$1" ] || fail "refs/main-Schreiben misslungen (ist '$got', soll '$1')"
    [ "$(wc -c < "$REFS_MAIN" | tr -d ' ')" = "40" ] || fail "refs/main hat nicht exakt 40 Bytes — Newline-Müll."
}

kill_on_port() {  # $1 = port; beendet NUR einen server.py auf genau diesem Port
    local pids
    pids="$(pgrep -f "server\.py .*--port $1" 2>/dev/null || true)"
    [ -z "$pids" ] && return 0
    say "beende Brain-Prozess(e) auf :$1 → $pids"
    kill -TERM $pids 2>/dev/null || true
    local i
    for i in $(seq 1 40); do
        port_busy "$1" || { ok "Port :$1 frei"; return 0; }
        sleep 0.25
    done
    kill -9 $pids 2>/dev/null || true
    for i in $(seq 1 20); do
        port_busy "$1" || { ok "Port :$1 frei (nach SIGKILL)"; return 0; }
        sleep 0.25
    done
    fail "Port :$1 bleibt belegt — von Hand nachsehen, NICHT weitermachen."
}

wait_loaded() {  # $1 = port, $2 = max s
    local i
    for i in $(seq 1 "$2"); do
        [ "$(health_loaded "$1")" = "true" ] && { ok "/health loaded:true nach ~${i}s"; return 0; }
        sleep 1
    done
    return 1
}

# ── Golden-Lauf: 3 Turns greedy, TTFT + tok/s + Volltext ─────────────────────
# SSE wird IMMER vollständig bis [DONE] konsumiert (nie `| head` auf einen Live-
# Stream — das ist die teuer gelernte Regel). TTFT = Zeit bis zum ersten
# nicht-leeren delta, gemessen im Client, weil server.py keine Stage-Metrik im
# Stream mitschickt.
run_golden() {  # $1 = port, $2 = label
    local port="$1" label="$2" outfile promptfile
    mkdir -p "$OUT_DIR"
    outfile="$OUT_DIR/golden-${label}-$(date +%Y%m%d-%H%M%S).json"
    [ "$(health_loaded "$port")" = "true" ] || fail "Kein geladenes Brain auf :$port — Golden-Lauf abgebrochen."
    say "Golden-Turns gegen :$port  (Label: $label)"
    # Prompts über eine DATEI, nicht über eine Pipe: das Python-Programm kommt selbst
    # per Heredoc auf stdin — eine zusätzliche Pipe würde davon still geschluckt
    # (genau dieser Fehler ist beim Self-Test aufgeflogen).
    promptfile="$(mktemp "${TMPDIR:-/tmp}/hoshi-ab-prompts.XXXXXX")"
    golden_prompts > "$promptfile"
    [ -s "$promptfile" ] || { rm -f "$promptfile"; fail "Keine Golden-Prompts — Abbruch statt Schein-Messung."; }
    python3 - "$port" "$label" "$outfile" "$promptfile" <<'PY'
import json, sys, time, urllib.request, uuid

port, label, outfile, promptfile = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
url = f"http://127.0.0.1:{port}/v1/chat"
with open(promptfile, encoding="utf-8") as f:
    prompts = [p for p in (l.rstrip("\n") for l in f) if p.strip()]
if not prompts:
    print("    KEINE Prompts geladen — Abbruch."); sys.exit(3)
runs = []
for i, prompt in enumerate(prompts, 1):
    body = json.dumps({
        "messages": [{"role": "user", "content": prompt}],
        # frische sessionId => kein Prefix-/KV-Cache-Treffer aus einem Vorlauf
        "sessionId": f"modell-ab-{label}-{uuid.uuid4().hex[:8]}",
        "userId": "andi",
        "stream": True,
        "max_tokens": 160,
        "temperature": 0.0,          # greedy => byte-vergleichbar zwischen Revisionen
    }).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    t0 = time.monotonic(); ttft = None; deltas = []
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:                       # bis [DONE] durchlesen, nie abbrechen
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload or payload == "[DONE]":
                continue
            try:
                ev = json.loads(payload)
            except Exception:
                continue
            d = ev.get("delta")
            if d:
                if ttft is None:
                    ttft = time.monotonic() - t0
                deltas.append(d)
    total = time.monotonic() - t0
    text = "".join(deltas)
    runs.append({
        "n": i, "prompt": prompt, "text": text,
        "ttft_ms": None if ttft is None else round(ttft * 1000, 1),
        "total_ms": round(total * 1000, 1),
        "chunks": len(deltas), "chars": len(text),
        "chars_per_s": round(len(text) / total, 1) if total > 0 else None,
    })
    print(f"    [{i}/{len(prompts)}] TTFT {runs[-1]['ttft_ms']} ms · gesamt {runs[-1]['total_ms']} ms "
          f"· {len(text)} Zeichen")
    print(f"        {text[:110]!r}")

ttfts = sorted(r["ttft_ms"] for r in runs if r["ttft_ms"] is not None)
med = ttfts[len(ttfts)//2] if ttfts else None
doc = {"label": label, "port": int(port), "when": time.strftime("%Y-%m-%dT%H:%M:%S"),
       "ttft_median_ms": med, "runs": runs}
with open(outfile, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
print(f"    TTFT-Median: {med} ms")
print(f"    geschrieben: {outfile}")
if any(not r["text"].strip() for r in runs):
    print("    LEERE ANTWORT dabei — das ist ein FAIL, kein Rauschen.")
    sys.exit(3)
PY
    local rc=$?
    rm -f "$promptfile"
    [ "$rc" -eq 0 ] || fail "Golden-Lauf '$label' fehlgeschlagen (rc=$rc)"
    ok "Golden-Lauf '$label' fertig"
}

# ── Phasen ───────────────────────────────────────────────────────────────────

# self-test: beweist die MESSMECHANIK (SSE-Einsammeln, TTFT, Byte-Vergleich) gegen
# ein Attrappen-Brain auf einem eigenen Port. Fasst weder Prod noch das Modell an
# und darf jederzeit laufen — auch tagsüber, auch ohne Fenster.
phase_self_test() {
    local port="${HOSHI_AB_SELFTEST_PORT:-8097}"
    say "Self-Test der Messmechanik gegen ein Attrappen-Brain auf :$port (kein echtes Modell)"
    port_busy "$port" && fail "Port :$port ist belegt — HOSHI_AB_SELFTEST_PORT auf einen freien Port setzen."
    local fake="${TMPDIR:-/tmp}/hoshi-ab-fake-$$.py"
    cat > "$fake" <<'PY'
import json, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        b = json.dumps({"loaded": True, "model": "fake"}).encode()
        self.send_response(200); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.send_response(200); self.send_header("Content-Type","text/event-stream"); self.end_headers()
        time.sleep(0.15)                      # messbares TTFT
        for tok in ("Hallo", ", ", "hier ", "ist ", "die ", "Attrappe", "."):
            self.wfile.write(f'data: {json.dumps({"delta": tok})}\n\n'.encode()); self.wfile.flush()
            time.sleep(0.02)
        self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
    python3 "$fake" "$port" >/dev/null 2>&1 & local fpid=$!
    disown 2>/dev/null || true
    # shellcheck disable=SC2064
    trap "kill $fpid >/dev/null 2>&1 || true; rm -f '$fake'" EXIT
    local i
    for i in $(seq 1 40); do port_busy "$port" && break; sleep 0.25; done
    port_busy "$port" || fail "Attrappe kam nicht hoch"
    OUT_DIR="${TMPDIR:-/tmp}/hoshi-ab-selftest-$$"
    run_golden "$port" "alt-selftest"
    run_golden "$port" "neu-selftest"
    say "Vergleichs-Auswertung (muss 3/3 byte-identisch melden — gleiche Attrappe):"
    python3 - "$OUT_DIR" <<'PY'
import glob, json, os, sys
d = sys.argv[1]
def newest(pat):
    f = sorted(glob.glob(os.path.join(d, pat)))
    return json.load(open(f[-1], encoding="utf-8")) if f else None
a, b = newest("golden-alt-*.json"), newest("golden-neu-*.json")
assert a and b, "Self-Test: Ergebnisdateien fehlen"
same = sum(ra["text"] == rb["text"] for ra, rb in zip(a["runs"], b["runs"]))
print(f"    {same}/{len(a['runs'])} byte-identisch · TTFT-Median alt {a['ttft_median_ms']} ms / neu {b['ttft_median_ms']} ms")
assert same == len(a["runs"]), "Self-Test FAIL: identische Attrappe lieferte Abweichungen"
assert a["ttft_median_ms"] and a["ttft_median_ms"] > 100, "Self-Test FAIL: TTFT nicht plausibel gemessen"
print("    Self-Test OK — SSE-Einsammeln, TTFT-Messung und Byte-Vergleich funktionieren.")
PY
    rm -rf "$OUT_DIR"
    ok "Messmechanik bewiesen (ohne ein einziges Byte am Prod-Brain)"
}

phase_status() {
    say "Stand"
    info "Repo:            $REPO_ROOT"
    info "HF-Cache:        $REPO_CACHE"
    info "refs/main:       $(current_ref)"
    info "  Pin (LIVE):    $REV_PIN"
    info "  Kandidat:      $REV_NEW"
    if [ "$(current_ref)" = "$REV_PIN" ]; then ok "refs/main steht auf dem bewährten Pin"
    elif [ "$(current_ref)" = "$REV_NEW" ]; then warn "refs/main steht auf dem KANDIDATEN — mit .venv (0.31.2) startet das Brain NICHT."
    else warn "refs/main steht auf etwas Drittem."; fi
    info "mlx-lm (.venv):      $("$VENV_PROD/bin/python" -c 'import mlx_lm;print(mlx_lm.__version__)' 2>/dev/null || echo '—')"
    if [ -x "$VENV_NEXT/bin/python" ]; then
        info "mlx-lm (.venv-next): $("$VENV_NEXT/bin/python" -c 'import mlx_lm;print(mlx_lm.__version__)' 2>/dev/null || echo '?')"
    else
        info "mlx-lm (.venv-next): (noch nicht gebaut)"
    fi
    info "Prod-Brain :$PROD_PORT:   loaded=$(health_loaded "$PROD_PORT")"
    info "Probe-Brain :$PROBE_PORT:  loaded=$(health_loaded "$PROBE_PORT")"
    info "frei+inaktiv:    $(mem_free_inactive_mb) MB"
    [ -f "$STATE_FILE" ] && info "State:           $(cat "$STATE_FILE")"
    return 0
}

phase_preflight() {
    say "Preflight (rein lesend — Prod bleibt oben)"
    [ -f "$SERVER_PY" ] || fail "server.py fehlt: $SERVER_PY"
    [ -x "$VENV_PROD/bin/python" ] || fail ".venv fehlt: $VENV_PROD"
    assert_no_incomplete; ok "keine .incomplete-Reste im Cache"
    local sp sn
    sp="$(snapshot_dir "$REV_PIN")"; ok "Pin-Snapshot vollständig:      $sp"
    sn="$(snapshot_dir "$REV_NEW")"; ok "Kandidat-Snapshot vollständig: $sn"
    [ "$(current_ref)" = "$REV_PIN" ] || warn "refs/main steht NICHT auf dem Pin (ist: $(current_ref))"

    # Rückweg festschreiben, BEVOR irgendetwas passiert.
    mkdir -p "$STATE_DIR"
    printf 'orig_ref=%s\nwhen=%s\n' "$(current_ref)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE"
    ok "Rückweg notiert in $STATE_FILE"

    # Index-Diff: was die neue Revision wirklich ändert (Zahlen, keine Meinung).
    python3 - "$sp" "$sn" <<'PY'
import json, os, struct, sys
def hdr(p):
    f = os.path.realpath(os.path.join(p, "model.safetensors"))
    with open(f, "rb") as fh:
        n = struct.unpack("<Q", fh.read(8))[0]
        return json.loads(fh.read(n))
o, n = hdr(sys.argv[1]), hdr(sys.argv[2])
sz = lambda h, k: h[k]["data_offsets"][1] - h[k]["data_offsets"][0]
keys_o = {k for k in o if k != "__metadata__"}
keys_n = {k for k in n if k != "__metadata__"}
gone, added = keys_o - keys_n, keys_n - keys_o
tot_o = sum(sz(o, k) for k in keys_o); tot_n = sum(sz(n, k) for k in keys_n)
print(f"    Tensoren:  ALT {len(keys_o)}  ->  NEU {len(keys_n)}   ({len(gone)} weg, {len(added)} neu)")
print(f"    Gewichte:  ALT {tot_o/1e6:.1f} MB -> NEU {tot_n/1e6:.1f} MB  (Δ {(tot_o-tot_n)/1e6:.1f} MB, "
      f"{100*(tot_o-tot_n)/tot_o:.2f} %)")
diff = [k for k in keys_o & keys_n if o[k]["dtype"] != n[k]["dtype"] or o[k]["shape"] != n[k]["shape"]]
print(f"    gemeinsame Tensoren mit anderem dtype/shape: {len(diff)}")
for k in sorted(diff):
    print(f"      {k}:  {o[k]['dtype']}{o[k]['shape']}  ->  {n[k]['dtype']}{n[k]['shape']}")
PY

    # Erwartete Ladefehler-Zahl aus der config ableiten — belegt, nicht geraten.
    python3 - "$sp" <<'PY'
import json, os, sys
c = json.load(open(os.path.join(sys.argv[1], "config.json")))
t = c.get("text_config", c)
n_layers = t.get("num_hidden_layers"); n_shared = t.get("num_kv_shared_layers")
if n_layers and n_shared:
    print(f"    KV-shared: Layer {n_layers-n_shared}..{n_layers-1} ({n_shared} Stück) "
          f"=> {n_shared*3} Parameter, die mlx-lm 0.31.2 vermisst, wenn die neue Revision geladen wird.")
PY
    say "Preflight OK. Nächster Schritt: golden-old (noch OHNE Fenster)."
}

phase_golden_old() {
    say "Golden-Baseline gegen das LAUFENDE Prod-Brain (:$PROD_PORT) — kein Neustart, kein Risiko"
    [ "$(health_loaded "$PROD_PORT")" = "true" ] || fail "Prod-Brain auf :$PROD_PORT ist nicht loaded — erst 'bin/hoshi heal'."
    run_golden "$PROD_PORT" "alt-${REV_PIN:0:8}"
    say "Baseline liegt in $OUT_DIR. Erst JETZT lohnt das Fenster."
}

phase_venv_next() {
    say "Zweites venv bauen (.venv-next) — das Live-.venv wird NICHT angefasst"
    [ -f "$BRAIN_DIR/requirements-next.txt" ] || fail "requirements-next.txt fehlt in $BRAIN_DIR"
    if [ -d "$VENV_NEXT" ]; then
        warn ".venv-next existiert bereits — wird verwendet. Neubau: erst 'rm -rf $VENV_NEXT'."
    else
        "$VENV_PROD/bin/python" -m venv "$VENV_NEXT" || fail "venv-Anlage fehlgeschlagen"
        ok "leeres venv angelegt: $VENV_NEXT"
    fi
    "$VENV_NEXT/bin/pip" install --upgrade pip >/dev/null
    say "pip install -r requirements-next.txt  (zieht mlx-lm vom Fix-Commit, ~einige 100 MB)"
    "$VENV_NEXT/bin/pip" install -r "$BRAIN_DIR/requirements-next.txt" || fail "pip install fehlgeschlagen"
    # Hoshi-eigene mlx-Patches nachziehen — pip löscht sie bei jeder Installation.
    if [ -d "$BRAIN_DIR/mlx_patches" ]; then
        local target
        target="$("$VENV_NEXT/bin/python" -c 'import mlx_lm.models,os;print(os.path.dirname(mlx_lm.models.__file__))')"
        cp "$BRAIN_DIR"/mlx_patches/*.py "$target"/ && ok "mlx_patches nach $target kopiert"
    fi
    info "mlx-lm in .venv-next: $("$VENV_NEXT/bin/python" -c 'import mlx_lm;print(mlx_lm.__version__)')"
    "$VENV_NEXT/bin/python" -c 'import mlx_lm, mlx.core' || fail ".venv-next kann mlx_lm/mlx nicht importieren"
    ok ".venv-next steht. Prod läuft unverändert weiter."
}

phase_probe() {
    say "${B}ANDI-FENSTER${R} — ab hier ist Hoshi kurz hirnlos (16-GB-Wand: nur EIN Brain)"
    [ -x "$VENV_NEXT/bin/python" ] || fail ".venv-next fehlt — erst Phase 'venv-next'."
    [ -f "$STATE_FILE" ] || fail "Kein State — erst Phase 'preflight' (die schreibt den Rückweg)."
    local snap; snap="$(snapshot_dir "$REV_NEW")"

    kill_on_port "$PROD_PORT"
    kill_on_port "$PROBE_PORT"

    local freemb; freemb="$(mem_free_inactive_mb)"
    info "frei+inaktiv nach dem Stoppen: ${freemb} MB"
    [ "$freemb" -ge 6000 ] || fail "nur ${freemb} MB frei+inaktiv — für ein 5,2-GB-Brain zu wenig. Erst RAM freimachen."

    say "starte Kandidat-Revision aus .venv-next auf :$PROBE_PORT"
    info "--model $snap   (ABSOLUTER Snapshot-Pfad → refs/main wird NICHT angefasst)"
    mkdir -p "$HOME/.hoshi/logs"
    local log="$HOME/.hoshi/logs/modell-ab-probe-$(date +%Y%m%d-%H%M%S).log"
    # Touch-Loop auf den PROD-Default (45s) — nicht aus. Sonst misst man den
    # Kandidaten kalt gegen ein warmgehaltenes Prod-Brain und nennt die Differenz
    # "Revision". Gleiche Bedingungen oder keine Messung.
    ( cd "$BRAIN_DIR" && \
      HF_HUB_OFFLINE=1 \
      HOSHI_E4B_TOUCH_LOOP_S="${HOSHI_E4B_TOUCH_LOOP_S:-45}" \
      HOSHI_E4B_WIRED_MB="${HOSHI_E4B_WIRED_MB:-0}" \
      HOSHI_E4B_PERSONA_KV_FREEZE="${HOSHI_E4B_PERSONA_KV_FREEZE:-0}" \
      nohup "$VENV_NEXT/bin/python" "$SERVER_PY" \
            --model "$snap" --host 127.0.0.1 --port "$PROBE_PORT" >"$log" 2>&1 & )
    info "Log: $log"

    if ! wait_loaded "$PROBE_PORT" 120; then
        warn "Kandidat wurde binnen 120s nicht loaded — das ist ein NO-GO, kein Drama."
        echo "${D}    ── letzte Log-Zeilen ──${R}"
        tail -25 "$log" || true
        echo
        fail "Probe fehlgeschlagen. Rückweg: bash $0 restore  (Prod-Brain kommt zurück)"
    fi
    ok "Kandidat geladen. refs/main unverändert: $(current_ref)"
    say "Weiter mit: bash $0 golden-new    danach IMMER: bash $0 restore"
}

phase_golden_new() {
    say "Golden-Turns gegen den Kandidaten (:$PROBE_PORT)"
    run_golden "$PROBE_PORT" "neu-${REV_NEW:0:8}"
    say "Vergleich (greedy => Byte-Gleichheit ist die Messlatte):"
    python3 - "$OUT_DIR" <<'PY'
import glob, json, os, sys
d = sys.argv[1]
def newest(pat):
    f = sorted(glob.glob(os.path.join(d, pat)))
    return json.load(open(f[-1], encoding="utf-8")) if f else None
a, b = newest("golden-alt-*.json"), newest("golden-neu-*.json")
if not a or not b:
    print("    (noch kein Paar alt/neu — 'golden-old' fehlt?)"); sys.exit(0)
print(f"    ALT {a['label']}  TTFT-Median {a['ttft_median_ms']} ms")
print(f"    NEU {b['label']}  TTFT-Median {b['ttft_median_ms']} ms")
if a["ttft_median_ms"] and b["ttft_median_ms"]:
    dv = 100 * (b["ttft_median_ms"] - a["ttft_median_ms"]) / a["ttft_median_ms"]
    print(f"    TTFT-Δ: {dv:+.1f} %   (alles unter ±10 % ist auf 3 Turns Rauschen)")
same = 0
for ra, rb in zip(a["runs"], b["runs"]):
    eq = ra["text"] == rb["text"]
    same += eq
    print(f"    [{ra['n']}] {'BYTE-GLEICH' if eq else 'ABWEICHUNG'}  ({ra['chars']} vs {rb['chars']} Zeichen)")
    if not eq:
        print(f"        alt: {ra['text'][:100]!r}")
        print(f"        neu: {rb['text'][:100]!r}")
print(f"    => {same}/{len(a['runs'])} Turns byte-identisch.")
print("    Lesart: greedy + gleiche Params => Abweichung kann nur aus der Gewichts-")
print("    änderung kommen (per_layer_model_projection BF16 -> 4-bit). Abweichung ist")
print("    kein automatisches NO-GO, aber sie MUSS gelesen werden, nicht weggeklickt.")
PY
}

phase_restore() {
    say "Rückweg: Kandidat weg, Prod-Brain zurück"
    kill_on_port "$PROBE_PORT"
    local want="$REV_PIN"
    [ -f "$STATE_FILE" ] && want="$(grep '^orig_ref=' "$STATE_FILE" | cut -d= -f2)"
    [ -n "$want" ] || want="$REV_PIN"
    if [ "$(current_ref)" != "$want" ]; then
        warn "refs/main steht auf $(current_ref) — schreibe $want zurück"
        write_ref "$want"
    fi
    ok "refs/main: $(current_ref)"
    say "Brain über den kanonischen Weg hochfahren: bin/hoshi heal"
    if [ -x "$REPO_ROOT/bin/hoshi" ]; then
        "$REPO_ROOT/bin/hoshi" heal || warn "hoshi heal meldete einen Fehler — Log lesen, nicht weiterklicken."
    else
        warn "bin/hoshi nicht gefunden — Brain von Hand starten: $BRAIN_DIR/run.sh"
    fi
    [ "$(health_loaded "$PROD_PORT")" = "true" ] && ok "Prod-Brain wieder loaded auf :$PROD_PORT" \
        || warn "Prod-Brain noch nicht loaded — 'bin/hoshi heal' wiederholen und Log lesen."
}

phase_flip() {
    say "${B}GO-Zug${R}: refs/main auf den Kandidaten umbiegen (erst NACH grünem golden-new + Andis GO)"
    [ "${HOSHI_AB_I_MEAN_IT:-0}" = "1" ] || fail "Sicherung: nur mit HOSHI_AB_I_MEAN_IT=1 (bewusster Zug, kein Versehen)."
    [ -x "$VENV_NEXT/bin/python" ] || fail ".venv-next fehlt — der Flip ist ohne neues mlx-lm ein garantierter Ausfall."
    kill_on_port "$PROD_PORT"; kill_on_port "$PROBE_PORT"
    write_ref "$REV_NEW"; ok "refs/main = $(current_ref)"
    warn "JETZT noch nötig, sonst startet run.sh weiter mit dem alten mlx-lm:"
    info "  1. .venv-next zum Prod-venv machen (mv .venv .venv-0.31.2 && mv .venv-next .venv)"
    info "  2. models.json  brain-e4b.pinned_revision  auf $REV_NEW"
    info "  3. sidecars/brain/requirements.txt aus requirements-next.txt nachziehen"
    info "  4. bin/hoshi heal  +  tools/models-verify.sh"
    say "Rückweg jederzeit: bash $0 unflip"
}

phase_unflip() {
    say "Rückweg aus dem GO-Zug — exakt der Handgriff, der am 19.08. um 03:07 das Brain zurückholte"
    kill_on_port "$PROD_PORT"; kill_on_port "$PROBE_PORT"
    write_ref "$REV_PIN"; ok "refs/main = $(current_ref)"
    [ -d "$BRAIN_DIR/.venv-0.31.2" ] && warn "Falls .venv getauscht wurde: mv .venv .venv-next && mv .venv-0.31.2 .venv"
    if [ -x "$REPO_ROOT/bin/hoshi" ]; then "$REPO_ROOT/bin/hoshi" heal || true; fi
    [ "$(health_loaded "$PROD_PORT")" = "true" ] && ok "Prod-Brain wieder loaded" || warn "noch nicht loaded — Log lesen."
}

# ── Dispatch ─────────────────────────────────────────────────────────────────
case "${1:-}" in
    status)      phase_status ;;
    self-test)   phase_self_test ;;
    preflight)   phase_preflight ;;
    golden-old)  phase_golden_old ;;
    venv-next)   phase_venv_next ;;
    probe)       phase_probe ;;
    golden-new)  phase_golden_new ;;
    restore)     phase_restore ;;
    flip)        phase_flip ;;
    unflip)      phase_unflip ;;
    *)
        cat <<EOF
${B}ab-run.sh${R} — A/B für die Brain-Revision (Details: tools/modell-ab/ab-runbook.md)

  ${B}Ohne Fenster${R} (Prod läuft weiter):
    status       Stand: refs/main, mlx-lm-Versionen, Ports, RAM
    self-test    Messmechanik gegen ein Attrappen-Brain beweisen (fasst nichts an)
    preflight    Cache prüfen, Rückweg notieren, Index-Diff ALT/NEU zeigen
    golden-old   Baseline gegen das laufende Prod-Brain
    venv-next    .venv-next bauen (Live-.venv unangetastet)

  ${B}Andi-Fenster${R} (Hoshi ist solange hirnlos):
    probe        Prod stoppen, Kandidat aus .venv-next auf :$PROBE_PORT — OHNE refs/main
    golden-new   Golden-Turns gegen den Kandidaten + Vergleich zur Baseline
    restore      Kandidat weg, Prod-Brain zurück  ${D}<- der Rückweg, immer${R}

  ${B}Nur nach GO${R}:
    flip         refs/main auf den Kandidaten (braucht HOSHI_AB_I_MEAN_IT=1)
    unflip       zurück auf den bewährten Pin + heal
EOF
        exit 2 ;;
esac
