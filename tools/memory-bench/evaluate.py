# SPDX-License-Identifier: Apache-2.0
"""Wertet vorproduzierte B0/H1/H2-Retrieval-Resultate gegen einen N0-Freeze aus."""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import random
import secrets
from pathlib import Path

from io_utils import publish_directory_no_replace, remove_private_tree, utc_now, write_new_json
from schema import (
    ensure_private_directory,
    read_json,
    read_jsonl,
    scenario_indexes,
    sha256_file,
    validate_query,
    validate_result,
)


def wilson_interval(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    proportion = successes / total
    denominator = 1 + z * z / total
    centre = proportion + z * z / (2 * total)
    radius = z * math.sqrt(proportion * (1 - proportion) / total + z * z / (4 * total * total))
    return ((centre - radius) / denominator, (centre + radius) / denominator)


def zero_event_upper(total: int, alpha: float = 0.05) -> float | None:
    return None if total <= 0 else 1 - alpha ** (1 / total)


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile_value * len(ordered)))
    return ordered[rank - 1]


def bootstrap_mean_interval(values: list[float], seed: str, iterations: int = 5000) -> tuple[float, float] | None:
    if not values:
        return None
    rng = random.Random(int(hashlib.sha256(seed.encode("utf-8")).hexdigest(), 16))
    count = len(values)
    means = [sum(values[rng.randrange(count)] for _ in range(count)) / count for _ in range(iterations)]
    return (percentile(means, 0.025), percentile(means, 0.975))  # type: ignore[return-value]


def paired_delta_interval(baseline: list[float], candidate: list[float], seed: str, iterations: int = 5000) -> tuple[float, float] | None:
    if not baseline or len(baseline) != len(candidate):
        return None
    deltas = [candidate[index] - baseline[index] for index in range(len(baseline))]
    return bootstrap_mean_interval(deltas, seed, iterations)


def one_sided_exact_improvement_p(baseline: list[bool], candidate: list[bool]) -> dict:
    if len(baseline) != len(candidate):
        raise ValueError("gepaarte Serien haben verschiedene Laengen")
    gains = sum(not left and right for left, right in zip(baseline, candidate))
    losses = sum(left and not right for left, right in zip(baseline, candidate))
    discordant = gains + losses
    if discordant == 0:
        p_value = 1.0
    else:
        p_value = sum(math.comb(discordant, value) for value in range(gains, discordant + 1)) / (2 ** discordant)
    return {"gains": gains, "losses": losses, "discordant": discordant, "pValue": p_value}


def rate(successes: int, total: int) -> dict:
    low, high = wilson_interval(successes, total)
    value = successes / total if total else None
    return {
        "events": successes,
        "n": total,
        "observed": value,
        "wilson95": [low, high] if total else None,
        "zeroEventUpperOneSided95": zero_event_upper(total) if successes == 0 and total else None,
    }


def verify_freeze(freeze: Path, split: str) -> tuple[dict, dict, list[dict], dict[str, tuple[str, dict]]]:
    ensure_private_directory(freeze)
    if freeze.stat().st_mode & 0o222:
        raise ValueError("Freeze-Verzeichnis ist schreibbar")
    manifest_path = freeze / "manifest.json"
    manifest = read_json(manifest_path)
    if manifest_path.stat().st_mode & 0o222:
        raise ValueError("Freeze-Manifest ist schreibbar")
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Freeze-Manifest: nicht unterstuetzte schemaVersion")
    files = manifest.get("files")
    required = {
        "dev.scenarios.jsonl",
        "dev.queries.jsonl",
        "holdout.scenarios.jsonl",
        "holdout.queries.jsonl",
        "evaluation-contract.json",
    }
    if not isinstance(files, dict) or set(files) != required:
        raise ValueError("Freeze-Manifest: unvollstaendiger Dateivertrag")
    for name, expected in files.items():
        path = freeze / name
        if not isinstance(expected, dict):
            raise ValueError(f"Freeze-Manifest: {name} unlesbar")
        if sha256_file(path) != expected.get("sha256") or path.stat().st_size != expected.get("bytes"):
            raise ValueError(f"Freeze-Drift: {name}")
        if path.stat().st_mode & 0o222:
            raise ValueError(f"Freeze-Datei ist schreibbar: {name}")
    contract = read_json(freeze / "evaluation-contract.json")
    expected_contract = {
        "schemaVersion": 1,
        "topK": 2,
        "variants": ["B0", "H1", "H2", "H1_H2"],
        "conditions": ["cold", "warm"],
        "retrievalMustMatchAcrossConditions": True,
        "promotion": {
            "fmrMustNotIncreaseVsB0": True,
            "crossSpeakerLeakCountMax": 0,
            "recallAt2DeltaMustBePositive": True,
            "recallAt2OneSidedExactPMax": 0.05,
        },
        "interval": {"kind": "wilson", "confidence": 0.95},
        "zeroEventClaim": {
            "kind": "one-sided-exact-upper",
            "confidence": 0.95,
            "minimumNForUpperBelowFivePercent": 59,
        },
    }
    if contract != expected_contract:
        raise ValueError("Evaluation-Contract ist nicht N0/topK=2")
    scenarios = read_jsonl(freeze / f"{split}.scenarios.jsonl")
    queries_raw = read_jsonl(freeze / f"{split}.queries.jsonl")
    scenario_map, episode_map = scenario_indexes(scenarios)
    queries: list[dict] = []
    seen: set[str] = set()
    for index, row in enumerate(queries_raw, 1):
        query = validate_query(row, scenario_map, episode_map, f"{split}.queries:{index}", frozen_split=split)
        if query["state"] != "reviewed":
            raise ValueError("Freeze enthaelt nicht-reviewte Query")
        if query["queryId"] in seen:
            raise ValueError(f"Freeze enthaelt doppelte Query {query['queryId']}")
        seen.add(query["queryId"])
        queries.append(query)
    return manifest, contract, queries, episode_map


def load_results(
    path: Path,
    queries: list[dict],
    episodes: dict[str, tuple[str, dict]],
) -> tuple[dict[str, dict[str, dict[str, dict]]], str]:
    rows = read_jsonl(path)
    query_map = {row["queryId"]: row for row in queries}
    by_variant: dict[str, dict[str, dict[str, dict]]] = {}
    for index, raw in enumerate(rows, 1):
        row = validate_result(raw, f"results:{index}")
        query_id = row["queryId"]
        if query_id not in query_map:
            raise ValueError(f"results:{index}: Query gehoert nicht zum gewaehlten Split")
        variant_conditions = by_variant.setdefault(row["variant"], {"cold": {}, "warm": {}})
        variant_rows = variant_conditions[row["condition"]]
        if query_id in variant_rows:
            raise ValueError(f"results:{index}: doppelte Query/Variante/Condition")
        scenario_id = query_map[query_id]["scenarioId"]
        for episode_id in row["retrievedEpisodeIds"]:
            if episode_id not in episodes:
                raise ValueError(f"results:{index}: unbekannte Episode {episode_id}")
            if episodes[episode_id][0] != scenario_id:
                raise ValueError(f"results:{index}: Retrieval darf die isolierte Szenario-Welt nicht verlassen")
        variant_rows[query_id] = row
    if "B0" not in by_variant:
        raise ValueError("Resultate brauchen immer die B0-Baseline")
    expected = set(query_map)
    for variant, conditions in by_variant.items():
        for condition, variant_rows in conditions.items():
            if set(variant_rows) != expected:
                missing = len(expected - set(variant_rows))
                extra = len(set(variant_rows) - expected)
                raise ValueError(
                    f"Variante {variant}/{condition}: unvollstaendiger Split "
                    f"(missing={missing}, extra={extra})"
                )
        for query_id in expected:
            if (
                conditions["cold"][query_id]["retrievedEpisodeIds"]
                != conditions["warm"][query_id]["retrievedEpisodeIds"]
            ):
                raise ValueError(
                    f"Variante {variant}: Retrieval driftet zwischen cold/warm bei {query_id}"
                )
    if len(by_variant) < 2:
        raise ValueError("Neben B0 muss mindestens ein Ablationsarm vorliegen")
    return by_variant, sha256_file(path)


def score_variant(
    variant: str,
    conditions: dict[str, dict[str, dict]],
    queries: list[dict],
    episodes: dict[str, tuple[str, dict]],
    top_k: int,
    seed: str,
) -> tuple[dict, dict[str, list]]:
    recall_flags: list[bool] = []
    reciprocal_ranks: list[float] = []
    wrong_memory_flags: list[bool] = []
    fmr_flags: list[bool] = []
    stale_flags: list[bool] = []
    explicit_foreign_flags: list[bool] = []
    cross_speaker_flags: list[bool] = []
    update_correct_flags: list[bool] = []
    update_intrusion_flags: list[bool] = []
    coverage_flags: list[bool] = []
    latencies = {"cold": [], "warm": []}
    for query in queries:
        result = conditions["warm"][query["queryId"]]
        retrieved = result["retrievedEpisodeIds"][:top_k]
        label = query["label"]
        acceptable = set(label["acceptableEpisodeIds"])
        stale = set(label["forbiddenStaleEpisodeIds"])
        foreign = set(label["forbiddenForeignEpisodeIds"])
        coverage_flags.append(bool(retrieved))
        for condition in ("cold", "warm"):
            latencies[condition].append(conditions[condition][query["queryId"]]["latencyMs"])
        cross_speaker_flags.append(
            any(episodes[episode_id][1]["speakerId"] != query["requesterSpeakerId"] for episode_id in retrieved)
        )
        if label["answerable"]:
            hit = any(episode_id in acceptable for episode_id in retrieved)
            recall_flags.append(hit)
            wrong_memory_flags.append(bool(retrieved) and not hit)
            rank = next((index for index, episode_id in enumerate(retrieved, 1) if episode_id in acceptable), None)
            reciprocal_ranks.append(0.0 if rank is None else 1.0 / rank)
        else:
            fmr_flags.append(bool(retrieved))
        if stale:
            stale_flags.append(any(episode_id in stale for episode_id in retrieved))
        if foreign:
            explicit_foreign_flags.append(any(episode_id in foreign for episode_id in retrieved))
        if query["queryType"] == "temporal_update":
            update_correct_flags.append(any(episode_id in acceptable for episode_id in retrieved))
            update_intrusion_flags.append(any(episode_id in stale for episode_id in retrieved))
    recall_rate = rate(sum(recall_flags), len(recall_flags))
    mrr = sum(reciprocal_ranks) / len(reciprocal_ranks) if reciprocal_ranks else None
    metrics = {
        "variant": variant,
        "recallAt2": recall_rate,
        "mrrAt2": {
            "mean": mrr,
            "n": len(reciprocal_ranks),
            "bootstrap95": bootstrap_mean_interval(reciprocal_ranks, f"{seed}:{variant}:mrr"),
        },
        "answerableWrongMemoryRate": rate(sum(wrong_memory_flags), len(wrong_memory_flags)),
        "falseMemoryRate": rate(sum(fmr_flags), len(fmr_flags)),
        "staleIntrusionRate": rate(sum(stale_flags), len(stale_flags)),
        "explicitForeignIntrusionRate": rate(sum(explicit_foreign_flags), len(explicit_foreign_flags)),
        "crossSpeakerLeakRate": rate(sum(cross_speaker_flags), len(cross_speaker_flags)),
        "updateCorrectRate": rate(sum(update_correct_flags), len(update_correct_flags)),
        "updateIntrusionRate": rate(sum(update_intrusion_flags), len(update_intrusion_flags)),
        "coverageRate": rate(sum(coverage_flags), len(coverage_flags)),
        "latencyMs": {
            condition: {
                "n": len(values),
                "p50": percentile(values, 0.50),
                "p95": percentile(values, 0.95),
            }
            for condition, values in latencies.items()
        },
    }
    vectors = {
        "recall": recall_flags,
        "mrr": reciprocal_ranks,
        "fmr": fmr_flags,
    }
    return metrics, vectors


def compare_candidate(
    variant: str,
    baseline_metrics: dict,
    baseline_vectors: dict[str, list],
    candidate_metrics: dict,
    candidate_vectors: dict[str, list],
    contract: dict,
    seed: str,
) -> dict:
    baseline_recall = baseline_metrics["recallAt2"]["observed"]
    candidate_recall = candidate_metrics["recallAt2"]["observed"]
    recall_delta = candidate_recall - baseline_recall
    exact = one_sided_exact_improvement_p(baseline_vectors["recall"], candidate_vectors["recall"])
    promotion = contract["promotion"]
    gates = {
        "fmrNonIncreaseVsB0": (
            candidate_metrics["falseMemoryRate"]["observed"]
            <= baseline_metrics["falseMemoryRate"]["observed"]
        ),
        "crossSpeakerLeakCountMax": (
            candidate_metrics["crossSpeakerLeakRate"]["events"]
            <= promotion["crossSpeakerLeakCountMax"]
        ),
        "recallAt2DeltaPositive": recall_delta > 0,
        "recallAt2ExactP": exact["pValue"] <= promotion["recallAt2OneSidedExactPMax"],
    }
    return {
        "variant": variant,
        "recallAt2Delta": recall_delta,
        "recallAt2DeltaPairedBootstrap95": paired_delta_interval(
            [float(value) for value in baseline_vectors["recall"]],
            [float(value) for value in candidate_vectors["recall"]],
            f"{seed}:{variant}:recall-delta",
        ),
        "recallAt2OneSidedExact": exact,
        "falseMemoryRateDelta": (
            candidate_metrics["falseMemoryRate"]["observed"]
            - baseline_metrics["falseMemoryRate"]["observed"]
        ),
        "gates": gates,
        "promotionPass": all(gates.values()),
    }


def _fmt_rate(value: dict) -> str:
    observed = value["observed"]
    return "n/a" if observed is None else f"{observed:.3f} ({value['events']}/{value['n']})"


def markdown_report(report: dict) -> str:
    lines = [
        "# Nagori Memory-Benchmark",
        "",
        f"- Split: `{report['split']}`",
        f"- Freeze-Manifest: `{report['freezeManifestSha256']}`",
        f"- Resultate: `{report['results']['sha256']}`",
        "",
        "| Variante | Recall@2 | MRR@2 | FMR | stale | cross-speaker | p95 cold | p95 warm |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for variant in sorted(report["variants"], key=lambda value: (value != "B0", value)):
        metrics = report["variants"][variant]
        lines.append(
            "| " + " | ".join(
                [
                    variant,
                    _fmt_rate(metrics["recallAt2"]),
                    f"{metrics['mrrAt2']['mean']:.3f}",
                    _fmt_rate(metrics["falseMemoryRate"]),
                    _fmt_rate(metrics["staleIntrusionRate"]),
                    _fmt_rate(metrics["crossSpeakerLeakRate"]),
                    f"{metrics['latencyMs']['cold']['p95']:.1f}",
                    f"{metrics['latencyMs']['warm']['p95']:.1f}",
                ]
            ) + " |"
        )
    lines.extend(["", "## Promotion", ""])
    for variant, comparison in sorted(report["comparisons"].items()):
        outcome = "PASS" if comparison["promotionPass"] else "NO-GO"
        lines.append(
            f"- **{variant}: {outcome}** — ΔRecall@2={comparison['recallAt2Delta']:+.3f}, "
            f"ΔFMR={comparison['falseMemoryRateDelta']:+.3f}, "
            f"exact p={comparison['recallAt2OneSidedExact']['pValue']:.4f}; "
            + ", ".join(f"{key}={'PASS' if value else 'FAIL'}" for key, value in comparison["gates"].items())
        )
    lines.extend(
        [
            "",
            "## Rate-Stellen / Grenzen",
            "",
            "- Alle Raten nennen Ereignisse und Nenner; Intervalle stehen im JSON-Report.",
            "- Null beobachtete Fehler sind kein Beweis fuer Null-Risiko; der JSON-Report nennt die einseitige 95%-Obergrenze.",
            "- Der Holdout ist eingefroren, aber lokal lesbar und daher nicht kryptografisch blind.",
            "- Gemessen wird Retrieval gegen synthetische Szenarien, nicht die faktische Qualitaet der finalen Brain-Antwort.",
            "- Der erste anzuzweifelnde Punkt bleibt die Uebertragbarkeit der menschlich gelabelten synthetischen Fragen auf echte kurze Voice-Queries.",
            "",
        ]
    )
    return "\n".join(lines)


def run_evaluation(freeze: Path, split: str, results_path: Path, output: Path) -> dict:
    manifest, contract, queries, episodes = verify_freeze(freeze, split)
    results, results_sha = load_results(results_path, queries, episodes)
    manifest_sha = sha256_file(freeze / "manifest.json")
    seed = f"{manifest_sha}:{results_sha}:{split}"
    variants: dict[str, dict] = {}
    vectors: dict[str, dict[str, list]] = {}
    for variant, conditions in results.items():
        variants[variant], vectors[variant] = score_variant(
            variant, conditions, queries, episodes, contract["topK"], seed
        )
    comparisons = {
        variant: compare_candidate(
            variant,
            variants["B0"],
            vectors["B0"],
            metrics,
            vectors[variant],
            contract,
            seed,
        )
        for variant, metrics in variants.items()
        if variant != "B0"
    }
    report = {
        "schemaVersion": 1,
        "createdAt": utc_now(),
        "datasetId": manifest["datasetId"],
        "split": split,
        "freezeManifestSha256": manifest_sha,
        "evaluationContractSha256": sha256_file(freeze / "evaluation-contract.json"),
        "results": {"sha256": results_sha},
        "variants": dict(sorted(variants.items())),
        "comparisons": dict(sorted(comparisons.items())),
        "limitations": [
            "synthetic-scenarios-not-production",
            "frozen-not-cryptographically-blind",
            "retrieval-not-final-answer",
            "human-label-quality-not-independently-attested",
        ],
    }
    ensure_private_directory(output.parent, create=True)
    if output.exists():
        raise ValueError("Report-Output existiert; Ergebnisse werden nie ueberschrieben")
    temporary = output.parent / f".{output.name}.tmp-{secrets.token_hex(8)}"
    temporary.mkdir(mode=0o700)
    try:
        write_new_json(temporary / "report.json", report, 0o600)
        report_md = markdown_report(report).encode("utf-8")
        descriptor = os.open(temporary / "report.md", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb", closefd=False) as handle:
                handle.write(report_md)
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            os.close(descriptor)
        publish_directory_no_replace(temporary, output)
    finally:
        remove_private_tree(temporary)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", type=Path, required=True)
    parser.add_argument("--split", choices=("dev", "holdout"), required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = run_evaluation(args.freeze, args.split, args.results, args.output_dir)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    for variant, comparison in report["comparisons"].items():
        print(f"[memory-bench] RESULT {variant}: {'PASS' if comparison['promotionPass'] else 'NO-GO'}")
    print(f"[memory-bench] Report: {args.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
