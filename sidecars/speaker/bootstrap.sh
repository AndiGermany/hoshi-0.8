#!/usr/bin/env bash
# sidecars/speaker/bootstrap.sh — legt das venv an, installiert die gepinnten
# requirements.txt UND laedt das CAM++-ONNX-Modell (hash-/groessen-verifiziert).
# Idempotent (mehrfach aufrufbar, ueberspringt was schon da ist) und ehrlich
# (bricht laut ab statt still ein kaputtes venv/Modell zu hinterlassen).
#
# [0.8-Port] venv-Teil nach dem Muster aus sidecars/brain/bootstrap.sh; der
# Modell-Download-Teil ist 1:1 aus Hoshi_0.5/hoshi-speaker-id/setup.sh
# uebernommen (curl -L --fail -m 180 + Byte-Groessen-Check + SHA256-Verify) —
# NUR die Erwartungswerte (Bytes/SHA256/Repo/Dateiname) kommen jetzt aus
# models.json statt aus Bash-Variablen, damit es GENAU EINE Wahrheit fuer
# diese Zahlen gibt (models.json-Wahrheit, s. Auftrag). models.json selbst
# wird hier NICHT veraendert, nur gelesen.
#
# Aufruf: sidecars/speaker/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[bootstrap] $*"; }

# ── models.json finden (Repo-Root, zwei Ebenen ueber diesem Sidecar) ────────
REPO_ROOT="$(cd "$(pwd)/../.." && pwd)"
MANIFEST="$REPO_ROOT/models.json"
[ -f "$MANIFEST" ] || fail "models.json nicht gefunden: $MANIFEST (erwartet unter Repo-Root)"

# ── Python-Version waehlen ────────────────────────────────────────────────
# Das Quell-venv (Hoshi_0.5/hoshi-speaker-id) lief auf python3.11.15 (setup.sh
# bevorzugte explizit python3.11) — identische Praeferenz wie sidecars/brain.
PY=python3.11
if ! command -v "$PY" >/dev/null 2>&1; then
    command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden — Python fehlt komplett"
    PY=python3
    got="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    echo "[bootstrap] WARN: python3.11 nicht gefunden, falle zurueck auf $PY ($got)." >&2
    echo "[bootstrap] WARN: das Quell-venv lief auf 3.11.15 — bei onnxruntime/kaldi-native-fbank-" >&2
    echo "[bootstrap] WARN: Ladefehlern zuerst python3.11 installieren (brew install python@3.11)." >&2
fi
say "Python: $("$PY" --version 2>&1) ($PY)"

# ── venv anlegen (idempotent) ─────────────────────────────────────────────
if [ -d .venv ]; then
    if [ -x .venv/bin/python ]; then
        say "venv existiert schon (.venv) — skip create"
    else
        fail ".venv existiert, ist aber kaputt (kein .venv/bin/python) — erst 'rm -rf .venv' dann neu bootstrappen"
    fi
else
    say "lege .venv an ($PY)"
    "$PY" -m venv .venv || fail "venv-Erstellung fehlgeschlagen"
fi

VENV_PY=".venv/bin/python"
[ -x "$VENV_PY" ] || fail "venv-Python fehlt nach Anlage: $VENV_PY"

# ── requirements installieren ─────────────────────────────────────────────
say "pip install -r requirements.txt"
"$VENV_PY" -m pip install -q --upgrade pip \
    || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt \
    || fail "pip install -r requirements.txt fehlgeschlagen — s. Fehler oben. Netz da?"

# ── Trust-but-verify: die KRITISCHEN Pakete muessen wirklich importierbar sein ─
say "verifiziere Kern-Imports (onnxruntime, kaldi_native_fbank, soundfile, fastapi)"
"$VENV_PY" -c "import onnxruntime, kaldi_native_fbank, soundfile, fastapi, uvicorn" \
    || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar. Nicht mit System-Python ausweichen."

# ── Modell: CAM++ ONNX — Erwartungswerte aus models.json lesen (EINE Wahrheit) ─
say "lese speaker-campplus-Eintrag aus models.json"
eval "$("$VENV_PY" - "$MANIFEST" <<'PYEOF'
import json
import sys

manifest_path = sys.argv[1]
manifest = json.loads(open(manifest_path, encoding="utf-8").read())
entry = next((m for m in manifest.get("models", []) if m.get("id") == "speaker-campplus"), None)
if entry is None:
    print("echo '[bootstrap] FATAL: models.json hat keinen speaker-campplus-Eintrag' >&2; exit 1")
    sys.exit(0)

hf_repo = entry.get("hf_repo")
expected_files = entry.get("expected_files") or []
filename = expected_files[0] if expected_files else None
expected_bytes = entry.get("expected_bytes")
expected_sha256 = entry.get("expected_sha256")

missing = [k for k, v in (("hf_repo", hf_repo), ("expected_files.0", filename),
                          ("expected_bytes", expected_bytes), ("expected_sha256", expected_sha256)) if not v]
if missing:
    # Kommagetrennt statt Python-Listen-Repr — vermeidet Klammern/Anfuehrungs-
    # zeichen im evaluierten Bash-String (Glob-Char-Klassen-Risiko bei "[...]").
    # HINWEIS zu diesem Heredoc: KEINE Apostrophe in Kommentaren/Strings hier
    # einfuegen ausser paarweise (je ein oeffnendes und ein schliessendes) --
    # eine insgesamt ungerade Anzahl einfacher Anfuehrungszeichen im gesamten
    # PYEOF-Block bricht die Bash-Syntaxpruefung (bash -n), weil dieser
    # Heredoc in einer Kommandosubstitution "eval $(...)" steckt. Empirisch
    # verifiziert (2026-07-18): ein einzelnes Apostroph in einem Kommentar
    # genuegte, um bash -n mit "unexpected EOF" scheitern zu lassen, obwohl
    # der Heredoc-Terminator gequotet ist ('PYEOF', eigentlich voll literal).
    print(f"echo '[bootstrap] FATAL: models.json speaker-campplus fehlt Feld(er): {', '.join(missing)}' >&2; exit 1")
    sys.exit(0)

url = f"https://huggingface.co/{hf_repo}/resolve/main/{filename}"
print(f"MODEL_URL={url!r}")
print(f"EXPECTED_BYTES={expected_bytes!r}")
print(f"EXPECTED_SHA256={expected_sha256!r}")
print(f"MODEL_FILENAME={filename!r}")
PYEOF
)"

[ -n "${MODEL_URL:-}" ] || fail "Konnte Modell-Erwartungswerte nicht aus models.json lesen (s. Fehler oben)"
say "models.json-Wahrheit: $MODEL_FILENAME, $EXPECTED_BYTES bytes, sha256 $EXPECTED_SHA256"

MODEL_DIR="models"
MODEL_FILE="$MODEL_DIR/$MODEL_FILENAME"
mkdir -p "$MODEL_DIR"

needs_dl=1
if [ -f "$MODEL_FILE" ]; then
    actual=$(stat -f%z "$MODEL_FILE" 2>/dev/null || stat -c%s "$MODEL_FILE")
    if [ "$actual" = "$EXPECTED_BYTES" ]; then
        say "Modell schon vollstaendig ($actual bytes) — skip download"
        needs_dl=0
    else
        say "WARN: Modell unvollstaendig ($actual != $EXPECTED_BYTES) — lade neu"
        rm -f "$MODEL_FILE"
    fi
fi

if [ "$needs_dl" = 1 ]; then
    say "lade CAM++-ONNX ($EXPECTED_BYTES bytes, 180s HARD-Timeout — kein Zombie)"
    # --fail: 404 -> Fehler; -L: redirect; -m 180: HARTER Timeout (kein Haenger)
    curl -L --fail -m 180 -o "$MODEL_FILE" "$MODEL_URL" \
        || fail "Download fehlgeschlagen: $MODEL_URL"
    actual=$(stat -f%z "$MODEL_FILE" 2>/dev/null || stat -c%s "$MODEL_FILE")
    if [ "$actual" != "$EXPECTED_BYTES" ]; then
        rm -f "$MODEL_FILE"
        fail "Download unvollstaendig ($actual != $EXPECTED_BYTES bytes) — Partial entfernt"
    fi
fi

# Trust-but-verify: SHA256 (schuetzt vor stillem Repo-Drift / Teil-Download)
got_sha=$(shasum -a 256 "$MODEL_FILE" | awk '{print $1}')
if [ "$got_sha" != "$EXPECTED_SHA256" ]; then
    rm -f "$MODEL_FILE"
    fail "SHA256-Mismatch ($got_sha != $EXPECTED_SHA256) — Datei entfernt, nicht vertrauenswuerdig"
fi
say "Modell verifiziert: $actual bytes, sha256 $got_sha"

say "OK — venv + Modell bereit: $VENV_PY, $MODEL_FILE"
say "naechster Schritt: ./run.sh (startet den Speaker-ID-Sidecar auf Port 9002)"
