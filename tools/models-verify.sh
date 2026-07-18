#!/usr/bin/env bash
# tools/models-verify.sh — read-only Vollständigkeits-Check für models.json.
#
# WARUM: ein Brain-Start scheiterte an einem unvollständigen HF-Cache (refs/main
# zeigte auf einen Snapshot-Ordner ohne model.safetensors — nur der Shard-Index
# war da). Der Start-Guard fing das erst BEIM Startversuch. Dieses Skript prüft
# denselben Zustand VORHER, ohne irgendetwas zu starten oder herunterzuladen.
#
# Geprüft wird ausschließlich der lokale Zustand:
#   - HF-Cache-Einträge (~/.cache/huggingface/hub bzw. $HF_HOME/hub bzw.
#     $HUGGINGFACE_HUB_CACHE): Snapshot vorhanden? expected_files da (Glob-Muster,
#     KEINE Hashes in v1)? keine *.incomplete-Reste? refs/main byte-genau sauber
#     (kein Newline-Müll — ein bekannter wiederkehrender Fehler)?
#   - "hf-direct-file"-Einträge (Modelle, die NICHT über snapshot_download
#     laufen, sondern per direktem Download in einen Projektordner, z. B.
#     CAM++ via Hoshi_0.5/hoshi-speaker-id/setup.sh): lokale Datei + Byte-Größe.
#   - "ollama"-Einträge: via `ollama list`, best-effort (ollama fehlt ⇒ WARN,
#     kein Fail — siehe models.json).
#
# Exit 0 nur wenn ALLE required=true-Modelle OK sind. Optionale Modelle
# (required=false, z. B. das e4b-Test-Brain) beeinflussen den Exit-Code nie,
# werden aber trotzdem gemeldet (ehrliches Bild, kein stilles Verstecken).
#
# Aufruf: tools/models-verify.sh                 # Standard-Manifest models.json
#         tools/models-verify.sh /pfad/zu.json    # anderes Manifest
#         HF_HOME=/anderer/pfad tools/models-verify.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MANIFEST="${1:-$REPO_ROOT/models.json}"

# ── HF-Hub-Cache-Auflösung (dieselbe Präzedenz wie huggingface_hub selbst) ────
if [ -n "${HUGGINGFACE_HUB_CACHE:-}" ]; then
    HF_HUB_CACHE="$HUGGINGFACE_HUB_CACHE"
elif [ -n "${HF_HOME:-}" ]; then
    HF_HUB_CACHE="$HF_HOME/hub"
else
    HF_HUB_CACHE="$HOME/.cache/huggingface/hub"
fi

# Sidecar-Wurzel für "hf-direct-file"-Modelle (CAM++ liegt hier, nicht im
# HF-Hub-Cache) — dieselbe Konfig-Variable wie pipeline/stack-lib.sh.
HOSHI_05_ROOT="${HOSHI_05_ROOT:-$HOME/IdeaProjects/Hoshi_0.5}"

if [ ! -f "$MANIFEST" ]; then
    echo "FATAL: Manifest nicht gefunden: $MANIFEST" >&2
    exit 2
fi

# JSON-Handling in Python (wie stack-lib.sh _json_field, prod-probe-0.8.sh
# Token-Parsing) statt bash-eigenem JSON-Gefummel — robuster für Glob/Bytes.
exec python3 - "$MANIFEST" "$HF_HUB_CACHE" "$HOSHI_05_ROOT" <<'PYEOF'
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

manifest_path, hf_hub_cache, hoshi_05_root = sys.argv[1:4]
HF_HUB_CACHE = Path(hf_hub_cache)

isatty = sys.stdout.isatty()
def c(code, s):
    return f"\033[{code}m{s}\033[0m" if isatty else s

STATUS_COLOR = {
    "OK": "32", "FEHLT": "31", "UNVOLLSTAENDIG": "33",
    "REF-DEFEKT": "31", "WARN": "33",
}

def snapshot_download_fix(hf_repo):
    return (
        'python3 -c "from huggingface_hub import snapshot_download; '
        f'print(snapshot_download(\'{hf_repo}\'))"'
    )

def check_hf(entry):
    hf_repo = entry["hf_repo"]
    repo_dir = HF_HUB_CACHE / ("models--" + hf_repo.replace("/", "--"))
    fix = snapshot_download_fix(hf_repo)

    if not repo_dir.is_dir():
        return "FEHLT", f"kein HF-Cache-Eintrag unter {repo_dir}", fix

    incomplete = sorted(repo_dir.rglob("*.incomplete"))
    refs_main = repo_dir / "refs" / "main"
    snapshots_dir = repo_dir / "snapshots"

    if not refs_main.is_file():
        if snapshots_dir.is_dir() and any(snapshots_dir.iterdir()):
            return "REF-DEFEKT", "refs/main fehlt, obwohl Snapshots vorhanden sind — Cache-Zeiger kaputt", fix
        return "FEHLT", f"Cache-Ordner {repo_dir} existiert, aber kein refs/main und keine Snapshots", fix

    raw = refs_main.read_bytes()
    stripped = raw.strip()
    if raw != stripped:
        return (
            "REF-DEFEKT",
            f"refs/main enthält Whitespace/Newline-Müll ({len(raw)} Bytes, erwartet 40 reine Hex-Zeichen ohne Trailing-Newline)",
            fix,
        )
    revision = stripped.decode("utf-8", errors="replace")
    if not re.fullmatch(r"[0-9a-f]{7,40}", revision):
        return "REF-DEFEKT", f"refs/main enthält keinen gültigen Commit-Hash: {revision!r}", fix

    snapshot_dir = snapshots_dir / revision
    if not snapshot_dir.is_dir():
        return "REF-DEFEKT", f"refs/main zeigt auf {revision}, aber kein solcher Snapshot-Ordner existiert", fix

    missing = []
    for pattern in entry.get("expected_files", []):
        if "*" in pattern or "?" in pattern:
            matches = [p for p in snapshot_dir.glob(pattern) if p.exists()]
            if not matches:
                missing.append(pattern)
        else:
            p = snapshot_dir / pattern
            if not p.exists():
                missing.append(pattern)

    if missing:
        return "UNVOLLSTAENDIG", f"Snapshot {revision[:12]} — fehlende Datei(en)/Muster: {', '.join(missing)}", fix
    if incomplete:
        rel = ", ".join(str(p.relative_to(repo_dir)) for p in incomplete)
        return "UNVOLLSTAENDIG", f"*.incomplete-Reste im Cache (abgebrochener Download): {rel}", fix

    return "OK", f"Snapshot {revision[:12]} vollständig ({len(entry.get('expected_files', []))} Muster erfüllt)", None

def check_hf_direct_file(entry):
    local_path = entry["local_path"].replace("$HOSHI_05_ROOT", hoshi_05_root)
    p = Path(local_path)
    setup_hint = entry.get("hf_repo", "?")
    fix = f"bash {hoshi_05_root}/hoshi-speaker-id/setup.sh   # lädt {local_path} neu von huggingface.co/{setup_hint}"

    if not p.exists():
        return "FEHLT", f"lokale Datei fehlt: {local_path}", fix

    expected_bytes = entry.get("expected_bytes")
    actual = p.stat().st_size
    if expected_bytes is not None and actual != expected_bytes:
        return (
            "UNVOLLSTAENDIG",
            f"{local_path}: {actual} Bytes, erwartet {expected_bytes} Bytes (abgebrochener/korrupter Download)",
            fix,
        )
    return "OK", f"{local_path}: {actual} Bytes, Größe stimmt", None

def check_ollama(entry):
    name = entry["ollama_name"]
    fix = f"ollama pull {name}"

    if shutil.which("ollama") is None:
        return "WARN", "ollama-Kommando nicht gefunden — best-effort, kein Fail", fix

    try:
        out = subprocess.run(
            ["ollama", "list"], capture_output=True, text=True, timeout=5, check=False
        )
    except Exception as e:  # noqa: BLE001 — best-effort, jeder Fehler ist ein WARN
        return "WARN", f"`ollama list` nicht erreichbar ({e}) — best-effort", fix

    if out.returncode != 0:
        detail = (out.stderr or out.stdout or "").strip().splitlines()[:1]
        return "WARN", f"`ollama list` Exit {out.returncode}: {detail[0] if detail else '?'} — best-effort", fix

    names = set()
    for line in out.stdout.splitlines()[1:]:  # erste Zeile ist der Header
        line = line.strip()
        if line:
            names.add(line.split()[0])

    if name in names:
        return "OK", f"in `ollama list` gefunden ({name})", None
    return "FEHLT", f"{name} nicht in `ollama list`", fix

CHECKERS = {"hf": check_hf, "hf-direct-file": check_hf_direct_file, "ollama": check_ollama}

manifest = json.loads(Path(manifest_path).read_text())
models = manifest.get("models", [])

print(f"Modell-Verify — Manifest: {manifest_path}")
print(f"  HF-Hub-Cache : {HF_HUB_CACHE}")
print(f"  Hoshi_0.5    : {hoshi_05_root}")
print()

counts = {"OK": 0, "FEHLT": 0, "UNVOLLSTAENDIG": 0, "REF-DEFEKT": 0, "WARN": 0}
blocking = []  # required=true UND status zählt gegen den Exit-Code

for entry in models:
    mtype = entry.get("type")
    checker = CHECKERS.get(mtype)
    if checker is None:
        status, detail, fix = "REF-DEFEKT", f"unbekannter type={mtype!r} im Manifest", None
    else:
        status, detail, fix = checker(entry)

    counts[status] = counts.get(status, 0) + 1
    required = bool(entry.get("required", False))
    counts_against = required and status not in ("OK", "WARN")
    if counts_against:
        blocking.append(entry["id"])

    label = f"[{status}]".ljust(17)
    req_tag = "required" if required else "optional"
    source = entry.get("hf_repo") or entry.get("ollama_name") or "?"
    print(f"{c(STATUS_COLOR.get(status, '0'), label)} {entry['role']:<10} ({entry['id']}, {req_tag})  {source}")
    print(f"    {detail}")
    if fix and status != "OK":
        print(f"    Fix: {fix}")
    print()

print("── Zusammenfassung ──")
print(
    "  "
    + "  ".join(f"{k}: {v}" for k, v in counts.items() if v)
    or "  (keine Modelle im Manifest)"
)

if blocking:
    print(f"  Exit 1 — required-Modell(e) nicht OK: {', '.join(blocking)}")
    sys.exit(1)
else:
    print("  Exit 0 — alle required-Modelle OK.")
    sys.exit(0)
PYEOF
