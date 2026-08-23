# SPDX-License-Identifier: Apache-2.0
"""Read-only Vollpruefung eines Nagori-N0-Freezes."""

from __future__ import annotations

import argparse
from pathlib import Path

from evaluate import verify_freeze
from schema import sha256_file


def verify_all(directory: Path) -> dict:
    dev_manifest, dev_contract, dev_queries, dev_episodes = verify_freeze(directory, "dev")
    hold_manifest, hold_contract, hold_queries, hold_episodes = verify_freeze(directory, "holdout")
    if dev_manifest != hold_manifest or dev_contract != hold_contract:
        raise ValueError("Dev und Holdout sehen verschiedene Manifest-/Contract-Bytes")
    dev_families = {row["templateFamily"] for row in dev_queries}
    hold_families = {row["templateFamily"] for row in hold_queries}
    if dev_families & hold_families:
        raise ValueError("Templatefamilie leakt ueber Dev und Holdout")
    dev_scenarios = {row["scenarioId"] for row in dev_queries}
    hold_scenarios = {row["scenarioId"] for row in hold_queries}
    if dev_scenarios & hold_scenarios:
        raise ValueError("Szenario leakt ueber Dev und Holdout")
    dev_ids = {row["queryId"] for row in dev_queries}
    hold_ids = {row["queryId"] for row in hold_queries}
    if dev_ids & hold_ids:
        raise ValueError("Query-ID leakt ueber Dev und Holdout")
    if set(dev_episodes) & set(hold_episodes):
        raise ValueError("Episode-ID leakt ueber Dev und Holdout")
    split = dev_manifest.get("split", {})
    if set(split.get("holdoutFamilies", [])) != hold_families:
        raise ValueError("Manifest nennt andere Holdout-Familien als die Dateien")
    if split.get("dev", {}).get("queries") != len(dev_queries):
        raise ValueError("Manifest-Dev-Zahl widerspricht den Dateien")
    if split.get("holdout", {}).get("queries") != len(hold_queries):
        raise ValueError("Manifest-Holdout-Zahl widerspricht den Dateien")
    claims = dev_manifest.get("claims", {})
    if claims.get("cryptographicallyBlindHoldout") is not False:
        raise ValueError("Manifest muss die lokale Holdout-Grenze ehrlich ausweisen")
    return {
        "manifestSha256": sha256_file(directory / "manifest.json"),
        "devQueries": len(dev_queries),
        "holdoutQueries": len(hold_queries),
        "devFamilies": len(dev_families),
        "holdoutFamilies": len(hold_families),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("freeze", type=Path)
    args = parser.parse_args()
    try:
        result = verify_all(args.freeze)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(
        "[memory-bench] OK "
        f"manifest={result['manifestSha256']} "
        f"dev={result['devQueries']} holdout={result['holdoutQueries']}"
    )
    print("[memory-bench] Claim: eingefroren und hashgebunden; NICHT kryptografisch blind")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
