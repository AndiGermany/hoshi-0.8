#!/usr/bin/env python3
"""Validiert + merged die LoRA-v0-Rohdaten -> train.jsonl / valid.jsonl.

Gates (Mira/Sara/Lara-Regeln, mechanisch):
  - valides JSON, messages=[system,user,assistant], alle non-empty
  - Verbots-Tokens in der Assistant-Antwort (Ton-Regressionen)
  - User-Dedupe ueber ALLE Lanes
  - deterministischer Shuffle (seed 42) + 95/5-Split
"""
import json, re, sys, random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw"
OUT = ROOT / "data"

FORBIDDEN = [
    (re.compile(r"\bAlter\b"), "Alter-Seed"),
    (re.compile(r"das ist (ganz )?einfach", re.I), "Herablassung"),
    (re.compile(r"wenn man die Grundlagen kennt", re.I), "Herablassung"),
    (re.compile(r"\bAls KI\b", re.I), "Meta"),
    (re.compile(r"\*\*|```|^- ", re.M), "Markdown/Liste"),
    (re.compile(r"^(Natürlich|Selbstverständlich|Gerne)[!,.]", re.M), "Floskel"),
]

def main() -> int:
    rows, seen_users, errors, forbidden_hits = [], set(), 0, 0
    for f in sorted(RAW.glob("*.jsonl")):
        for i, line in enumerate(f.read_text().splitlines(), 1):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
                msgs = obj["messages"]
                assert [m["role"] for m in msgs] == ["system", "user", "assistant"]
                assert all(isinstance(m["content"], str) and m["content"].strip() for m in msgs)
            except Exception as e:
                print(f"SCHEMA {f.name}:{i}: {e}"); errors += 1; continue
            user, assistant = msgs[1]["content"], msgs[2]["content"]
            if user in seen_users:
                continue  # Duplikat still verwerfen
            hit = next((label for rx, label in FORBIDDEN if rx.search(assistant)), None)
            if hit:
                print(f"VERBOT[{hit}] {f.name}:{i}: {assistant[:80]!r}"); forbidden_hits += 1; continue
            seen_users.add(user)
            rows.append((f.stem, line))
    if errors or forbidden_hits:
        print(f"\nROT: {errors} Schema-Fehler, {forbidden_hits} Verbots-Treffer -> NICHT gemerged")
        return 1
    random.Random(42).shuffle(rows)
    cut = max(1, len(rows) // 20)  # 5% valid
    (OUT / "valid.jsonl").write_text("\n".join(l for _, l in rows[:cut]) + "\n")
    (OUT / "train.jsonl").write_text("\n".join(l for _, l in rows[cut:]) + "\n")
    by_lane = {}
    for lane, _ in rows:
        by_lane[lane] = by_lane.get(lane, 0) + 1
    print(f"GRUEN: {len(rows)} unikat ({by_lane}) -> train={len(rows)-cut} valid={cut}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
