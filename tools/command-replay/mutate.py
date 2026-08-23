#!/usr/bin/env python3
"""Create deterministic DRAFT mutations without pretending they are gold labels."""

from __future__ import annotations

import argparse
import copy
import re
from pathlib import Path

from schema import load_cases, write_cases


LEADING_VERB = re.compile(r"^(Schalte|Schalt|Mach|Mache)\s+", re.IGNORECASE)


def mutation_text(text: str, kind: str) -> str | None:
    match = LEADING_VERB.match(text)
    if kind == "verb_drop":
        return LEADING_VERB.sub("", text, count=1) if match else None
    if kind == "onset_drop":
        if not match:
            return None
        verb = match.group(1)
        remainder = verb[1:]
        if verb[0].isupper():
            remainder = remainder[0].upper() + remainder[1:]
        return remainder + text[match.end(1) :]
    if kind == "homophone":
        if re.search(r"\baus([.!?]?)$", text, re.IGNORECASE):
            return re.sub(r"\baus([.!?]?)$", r"Haus\1", text, flags=re.IGNORECASE)
        if re.search(r"\bein([.!?]?)$", text, re.IGNORECASE):
            return re.sub(r"\bein([.!?]?)$", r"einen\1", text, flags=re.IGNORECASE)
    return None


def mutate_case(case: dict, kind: str) -> dict | None:
    if "turns" in case:
        return None
    text = mutation_text(case["text"], kind)
    if text is None or text == case["text"]:
        return None
    result = copy.deepcopy(case)
    result["id"] = f"{case['id']}--{kind}"
    result["text"] = text
    result["label_status"] = "DRAFT"
    result["origin"] = {
        "kind": "synthetic",
        "reference": f"mutation:{case['id']}",
        "exact": True,
    }
    result["mutation"] = {"kind": kind, "parent_id": case["id"]}
    if kind == "homophone":
        result["expected"] = {"kind": "CLARIFY"}
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--kinds",
        nargs="+",
        choices=["verb_drop", "onset_drop", "homophone"],
        default=["verb_drop", "onset_drop", "homophone"],
    )
    args = parser.parse_args()
    generated = []
    for case in load_cases(args.input):
        for kind in args.kinds:
            candidate = mutate_case(case, kind)
            if candidate is not None:
                generated.append(candidate)
    write_cases(args.output, generated)
    print(f"generated={len(generated)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
