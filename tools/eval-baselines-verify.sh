#!/usr/bin/env bash
# tools/eval-baselines-verify.sh — read-only Drift-Check für eingefrorene Eval-Suiten.
#
# WARUM: das Modell-Upgrade-Ritual braucht einen stabilen Blind-A/B-Maßstab.
# Ohne eingefrorene Baseline vergleicht ein künftiger Lauf die aktuelle
# Prompt-Suite mit der ERINNERUNG an einen alten Lauf, nicht mit denselben
# Prompts — das ist kein fairer Vergleich. training/eval-baselines.json hält
# sha256 + Zeilenzahl pro Suite fest; dieses Skript prüft NUR den lokalen
# Ist-Zustand dagegen (liest/verändert keine Prompt-Inhalte, keine Schreib-
# zugriffe). DRIFT heißt: die Suite wurde verändert — Vergleiche mit alten
# Läufen sind ab jetzt ungültig. Ist die Änderung bewusst (neue Prompts,
# Korrektur), muss training/eval-baselines.json mit dem neuen Hash/der neuen
# Zeilenzahl nachgezogen und als neue Baseline-Version (version-Feld hoch-
# zählen, z. B. v1 -> v2) dokumentiert werden — kein stilles Überschreiben.
#
# Aufruf: tools/eval-baselines-verify.sh                  # Standard-Manifest training/eval-baselines.json
#         tools/eval-baselines-verify.sh /pfad/zu.json     # anderes Manifest
#         HOSHI_EVAL_BASELINE_<ID>_PATH=/pfad tools/eval-baselines-verify.sh
#           # prüft NUR diese eine Suite gegen eine andere Datei (z. B. eine
#           # Kopie in /tmp) statt gegen suite.path — für Drift-Proben, ohne
#           # das Original anzufassen. <ID> = Suite-id in GROSS, "-" -> "_"
#           # (lora-v0 -> LORA_V0, mitgift-base -> MITGIFT_BASE).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MANIFEST="${1:-$REPO_ROOT/training/eval-baselines.json}"

if [ ! -f "$MANIFEST" ]; then
    echo "FATAL: Manifest nicht gefunden: $MANIFEST" >&2
    exit 2
fi

# JSON/Hash-Handling in Python (wie tools/models-verify.sh) statt bash-eigenem
# Gefummel — robuster für Bytes/Hex.
exec python3 - "$MANIFEST" "$REPO_ROOT" <<'PYEOF'
import hashlib
import json
import os
import sys
from pathlib import Path

manifest_path, repo_root = sys.argv[1:3]
repo_root = Path(repo_root)

manifest = json.loads(Path(manifest_path).read_text())
version = manifest.get("version", "?")
suites = manifest.get("suites", [])

print(f"Eval-Baselines-Verify — Manifest: {manifest_path} (version {version})")
print()

drift_count = 0
for suite in suites:
    sid = suite["id"]
    env_key = "HOSHI_EVAL_BASELINE_" + sid.upper().replace("-", "_") + "_PATH"
    override = os.environ.get(env_key)
    path = Path(override) if override else (repo_root / suite["path"])
    label = sid + (f" [OVERRIDE via {env_key}={path}]" if override else f" ({suite['path']})")

    if not path.is_file():
        print(f"[DRIFT] {label}")
        print(f"        Datei fehlt unter {path}")
        drift_count += 1
        print()
        continue

    data = path.read_bytes()
    actual_sha = hashlib.sha256(data).hexdigest()
    actual_lines = data.count(b"\n")  # dieselbe Zählweise wie `wc -l`

    expected_sha = suite["sha256"]
    expected_lines = suite["lines"]

    problems = []
    if actual_sha != expected_sha:
        problems.append(f"sha256 {actual_sha} != erwartet {expected_sha}")
    if actual_lines != expected_lines:
        problems.append(f"{actual_lines} Zeilen != erwartet {expected_lines}")

    if problems:
        print(f"[DRIFT] {label}")
        for p in problems:
            print(f"        {p}")
        print("        DRIFT bedeutet: die Suite wurde verändert — Vergleiche mit alten Läufen")
        print("        sind ungültig. Bewusste Änderung? -> Manifest (sha256/lines) nachziehen")
        print("        und als neue Baseline-Version dokumentieren (version-Feld hochzählen).")
        drift_count += 1
    else:
        print(f"[OK]    {label}")
        print(f"        sha256 {actual_sha} · {actual_lines} Zeilen")
    print()

if drift_count:
    print(f"Zusammenfassung: {drift_count}/{len(suites)} Suite(n) mit DRIFT")
    sys.exit(1)
else:
    print(f"Zusammenfassung: alle {len(suites)} Suite(n) OK")
    sys.exit(0)
PYEOF
