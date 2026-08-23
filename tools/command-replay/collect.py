#!/usr/bin/env python3
"""Read-only, privacy-conservative collector for command-shaped transcript rows."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Iterable


PII = re.compile(
    r"(?:https?://|www\.|[\w.+-]+@[\w.-]+|\b\d{1,3}(?:\.\d{1,3}){3}\b|"
    r"\b[0-9a-f]{8}-[0-9a-f-]{27,}\b|\b(?:\+?\d[\d /()-]{7,}\d)\b)",
    re.IGNORECASE,
)
COMMAND = re.compile(
    r"^(?:hoshi[ ,]+)?(?:bitte\s+)?(?:"
    r"(?:schalt(?:e)?|mach(?:e)?|stell(?:e)?|setz(?:e)?|dreh(?:e)?)\s+.+|"
    r"(?:das\s+)?(?:licht|lampe|leuchte|heizung|thermostat|rollladen|jalousie|"
    r"steckdose|ventilator)(?:\s+.+)?\s+(?:an|aus|ein)"
    r")[.!?]*$",
    re.IGNORECASE,
)
FREE_SPEECH = re.compile(
    r"\b(?:mein name|meine adresse|ich wohne|ich fühle|ich habe angst|"
    r"arzt|diagnose|konto|passwort|geheimnis)\b",
    re.IGNORECASE,
)


def iter_rows(path: Path) -> Iterable[dict[str, Any]]:
    files = sorted(path.glob("turn-diary-*.jsonl")) if path.is_dir() else [path]
    for file in files:
        if not file.is_file():
            continue
        with file.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(row, dict):
                    yield row


def command_candidate(text: Any) -> str | None:
    if not isinstance(text, str):
        return None
    value = " ".join(text.strip().split())
    if not value or len(value) > 160 or PII.search(value) or FREE_SPEECH.search(value):
        return None
    return value if COMMAND.fullmatch(value) else None


def source_ref(row: dict[str, Any]) -> str:
    material = f"{row.get('ts', '')}\0{row.get('chatId', '')}".encode("utf-8")
    return "sha256:" + hashlib.sha256(material).hexdigest()


def collect(path: Path, *, text_key: str) -> tuple[list[dict[str, Any]], dict[str, int]]:
    candidates: list[dict[str, Any]] = []
    stats = {"rows": 0, "without_text": 0, "rejected": 0, "candidates": 0}
    for row in iter_rows(path):
        stats["rows"] += 1
        if text_key not in row:
            stats["without_text"] += 1
            continue
        text = command_candidate(row.get(text_key))
        if text is None:
            stats["rejected"] += 1
            continue
        candidates.append(
            {
                "candidate_id": f"candidate-{len(candidates) + 1:04d}",
                "text": text,
                "language": str(row.get("language") or "DE"),
                "source_ref": source_ref(row),
                "label_status": "UNLABELED",
            }
        )
        stats["candidates"] += 1
    return candidates, stats


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--text-key", default="transcript")
    args = parser.parse_args()

    candidates, stats = collect(args.diary, text_key=args.text_key)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.touch(mode=0o600, exist_ok=True)
    os.chmod(args.output, 0o600)
    with args.output.open("w", encoding="utf-8") as handle:
        for candidate in candidates:
            handle.write(json.dumps(candidate, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps(stats, sort_keys=True))
    if stats["rows"] and stats["without_text"] == stats["rows"]:
        print(f"no candidates: diary rows do not contain the configured {args.text_key!r} field")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
