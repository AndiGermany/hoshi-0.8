#!/usr/bin/env bash
# Endabnahme der Basis-Mitgift mit Andis ORIGINAL-Validator (unverändert kopiert
# aus lora-v0 — er härtet Schema, Verbots-Regex, User-Dedupe und macht den
# deterministischen 95/5-Split). Layout-Brücke: der Validator erwartet
# data/raw/*.jsonl relativ zu seinem Eltern-Ordner.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/raw
cp raw/warmth.jsonl raw/grounded.jsonl raw/abstain.jsonl data/raw/
cp ../lora-v0/tools/validate_merge.py tools/validate_merge.py
python3 tools/validate_merge.py
