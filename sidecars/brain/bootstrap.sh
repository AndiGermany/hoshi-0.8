#!/usr/bin/env bash
# sidecars/brain/bootstrap.sh — legt das venv an + installiert die gepinnten
# requirements.txt. Idempotent (mehrfach aufrufbar, ueberspringt was schon da
# ist) und ehrlich (bricht laut ab statt still ein kaputtes venv zu hinterlassen).
#
# [0.8-Port] Pattern uebernommen aus Hoshi_0.5/hoshi-speaker-id/setup.sh
# (dort als "Vorbild-Adapter" markiert, vault/tracks/LEDGER-sidecars.md) —
# python3.11-Praeferenz + venv + pip install. KEIN Modell-Download hier: das
# Gemma-4-E4B-Gewicht kommt ueber den HuggingFace-Cache (s. run.sh-Guard),
# nicht ueber bootstrap.sh (Modelle sind kein pip-Paket).
#
# Aufruf: sidecars/brain/bootstrap.sh
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "[bootstrap] FATAL: $*" >&2; exit 1; }
say()  { echo "[bootstrap] $*"; }

# ── Python-Version waehlen ────────────────────────────────────────────────
# Das Quell-venv (Hoshi_0.5) lief auf 3.11.15 (stabile arm64-Wheels fuer
# mlx/mlx-lm zum Pin-Zeitpunkt) — 3.11 bevorzugt, System-python3 als Fallback.
# Bei Fallback WARNEN statt still zu installieren: eine andere Python-Minor-
# Version kann andere Wheel-Kompatibilitaet fuer die gepinnten mlx-Versionen
# bedeuten (genau der Version-Drift, den requirements.txt vermeiden soll).
PY=python3.11
if ! command -v "$PY" >/dev/null 2>&1; then
    command -v python3 >/dev/null 2>&1 || fail "weder python3.11 noch python3 gefunden — Python fehlt komplett"
    PY=python3
    got="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    echo "[bootstrap] WARN: python3.11 nicht gefunden, falle zurueck auf $PY ($got)." >&2
    echo "[bootstrap] WARN: das Quell-venv lief auf 3.11.15 — bei mlx/mlx-lm-Ladefehlern" >&2
    echo "[bootstrap] WARN: zuerst python3.11 installieren (brew install python@3.11)." >&2
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
say "pip install -r requirements.txt (das kann bei mlx/mlx-lm einige Minuten dauern)"
"$VENV_PY" -m pip install -q --upgrade pip \
    || fail "pip-Upgrade fehlgeschlagen"
"$VENV_PY" -m pip install -q -r requirements.txt \
    || fail "pip install -r requirements.txt fehlgeschlagen — s. Fehler oben. Netz da? Falsches Python (Apple Silicon noetig fuer mlx-*)?"

# ── Trust-but-verify: die KRITISCHEN Pakete muessen wirklich importierbar sein ─
say "verifiziere Kern-Imports (mlx_lm, huggingface_hub, fastapi)"
"$VENV_PY" -c "import mlx_lm, huggingface_hub, fastapi, uvicorn, pydantic" \
    || fail "Kern-Import fehlgeschlagen trotz 'erfolgreichem' pip install — venv ist NICHT nutzbar. Nicht mit System-Python ausweichen."

# ── mlx-lm-Patches einsetzen (Architekturen, die mlx-lm noch nicht mitbringt) ─
# WARUM HIER und nicht als pip-Patch: `pip install` legt das models/-Verzeichnis
# neu an — ein dort abgelegtes Modul waere nach jedem Lauf weg. Die Quelle liegt
# darum im Repo (mlx_patches/) und wird nach JEDER Installation neu eingesetzt.
# Ohne den Patch endet `gemma-4-12B-it-4bit` mit
# "ValueError: Model type gemma4_unified not supported" (s. Kopf der Patch-Datei).
if [ -d mlx_patches ]; then
    MODELS_DIR="$("$VENV_PY" -c 'import mlx_lm, os; print(os.path.join(os.path.dirname(mlx_lm.__file__), "models"))' 2>/dev/null)"
    if [ -n "$MODELS_DIR" ] && [ -d "$MODELS_DIR" ]; then
        for patch in mlx_patches/*.py; do
            [ -e "$patch" ] || continue
            cp "$patch" "$MODELS_DIR/" || fail "Patch $patch liess sich nicht einsetzen"
            say "Patch eingesetzt: $(basename "$patch") -> ${MODELS_DIR}"
        done
        # Trust-but-verify: der Patch muss auch importierbar sein, nicht nur liegen.
        "$VENV_PY" -c "import mlx_lm.models.gemma4_unified" \
            || fail "gemma4_unified-Patch liegt, ist aber nicht importierbar"
    else
        say "WARNUNG: models/-Verzeichnis von mlx_lm nicht gefunden — Patches NICHT eingesetzt"
    fi
fi

say "OK — venv bereit: $VENV_PY"
say "naechster Schritt: ./run.sh (startet den Brain-Sidecar auf Port 8041)"
