# SPDX-License-Identifier: Apache-2.0
"""Menschliches Label/Review sowie Single-seal/Freeze fuer Nagori N0."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import itertools
import os
import random
import re
import secrets
import stat
from collections import Counter
from pathlib import Path

import generate_synthetic

from io_utils import (
    atomic_replace_jsonl,
    publish_directory_no_replace,
    remove_private_tree,
    utc_now,
    write_new_json,
)
from schema import (
    QUERY_TYPES,
    SCHEMA_VERSION,
    canonical_json,
    ensure_private_directory,
    jsonl_bytes,
    normalize_text,
    read_json,
    read_jsonl,
    parse_timestamp,
    scenario_indexes,
    sha256_bytes,
    sha256_file,
    validate_dataset_id,
    validate_query,
)


DEFAULT_ROOT = Path.home() / ".hoshi" / "memory-bench" / "intake"
SEAL_FILE = "selection-seal.json"
FREEZE_INTENT_FILE = "freeze-intent.json"
FREEZE_LOCK_FILE = ".freeze.lock"
MIN_TOTAL = 120
MAX_TOTAL = 240
MIN_SCENARIOS = 12
MIN_FAMILIES = 6
MIN_CLASS_TOTAL = 50
MIN_TYPE_TOTAL = 10
MIN_HOLDOUT_ANSWERABLE = 20
MIN_HOLDOUT_NO_ANSWER = 20
MIN_HOLDOUT_STALE = 4
MIN_HOLDOUT_FOREIGN = 4
REOPEN_REASONS = {"label-error", "privacy-review", "scenario-error", "other"}

_PRIVACY_PATTERNS = (
    ("email", re.compile(r"\b[^\s@]+@[^\s@]+\.[^\s@]+\b", re.IGNORECASE)),
    ("home-path", re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\\\Users\\\\)")),
    ("private-ip", re.compile(r"\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))(?:\.\d{1,3}){2}\b")),
    ("uuid", re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b", re.IGNORECASE)),
    ("home-assistant-entity", re.compile(r"\b(?:light|sensor|switch|person|device_tracker)\.[a-z0-9_]+\b", re.IGNORECASE)),
)


def dataset_directory(root: Path, dataset: str) -> Path:
    validate_dataset_id(dataset)
    ensure_private_directory(root)
    directory = root / dataset
    ensure_private_directory(directory)
    return directory


def privacy_findings(texts: list[str]) -> list[str]:
    joined = "\n".join(texts)
    return sorted(name for name, pattern in _PRIVACY_PATTERNS if pattern.search(joined))


def load_intake(directory: Path) -> tuple[dict, list[dict], list[dict], dict[str, dict], dict[str, tuple[str, dict]]]:
    intake = read_json(directory / "intake.json")
    if intake.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("intake.json: nicht unterstuetzte schemaVersion")
    if intake.get("datasetId") != directory.name:
        raise ValueError("intake.json: datasetId widerspricht dem Verzeichnis")
    scenarios = read_jsonl(directory / "scenarios.jsonl")
    queries = read_jsonl(directory / "queries.jsonl")
    scenario_map, episode_map = scenario_indexes(scenarios)
    seen_queries: set[str] = set()
    validated: list[dict] = []
    for index, row in enumerate(queries, 1):
        query = validate_query(row, scenario_map, episode_map, f"queries:{index}")
        if query["queryId"] in seen_queries:
            raise ValueError(f"queries: doppelte queryId {query['queryId']}")
        seen_queries.add(query["queryId"])
        expected_findings = privacy_findings([query["text"], *query["conversationContext"]])
        if query["privacyFindings"] != expected_findings:
            raise ValueError(f"queries:{index}: privacyFindings widersprechen dem Scanner")
        validated.append(query)
    return intake, scenarios, validated, scenario_map, episode_map


def require_mutable(directory: Path) -> None:
    if (directory / SEAL_FILE).exists():
        raise ValueError("Intake ist versiegelt; Labels und Reviews sind unveraenderlich")


def require_yes(value: bool, action: str) -> None:
    if not value:
        raise ValueError(f"{action} braucht --yes")


def update_audit(directory: Path, event: dict) -> None:
    path = directory / "audit.jsonl"
    rows = read_jsonl(path)
    rows.append(event)
    atomic_replace_jsonl(path, rows)


def parse_ids(values: list[str] | None) -> list[str]:
    return sorted(set(values or []))


def command_label(args: argparse.Namespace) -> None:
    require_yes(args.yes, "Labeln")
    directory = dataset_directory(args.root, args.dataset)
    require_mutable(directory)
    _, scenarios, queries, scenario_map, episode_map = load_intake(directory)
    del scenarios
    matches = [index for index, row in enumerate(queries) if row["queryId"] == args.query_id]
    if len(matches) != 1:
        raise ValueError(f"query-id nicht eindeutig vorhanden: {args.query_id}")
    index = matches[0]
    current = queries[index]
    if current["state"] == "reviewed":
        raise ValueError("reviewte Query zuerst mit reopen bewusst wieder oeffnen")
    answerable = args.answerable == "yes"
    labeled_at = utc_now()
    updated = dict(current)
    updated.update(
        {
            "state": "labeled",
            "label": {
                "answerable": answerable,
                "acceptableEpisodeIds": parse_ids(args.acceptable),
                "forbiddenStaleEpisodeIds": parse_ids(args.forbidden_stale),
                "forbiddenForeignEpisodeIds": parse_ids(args.forbidden_foreign),
                "labeledAt": labeled_at,
                "labelSource": "human",
            },
            "reviewedAt": None,
            "revision": current["revision"] + 1,
        }
    )
    updated = validate_query(updated, scenario_map, episode_map, args.query_id)
    queries[index] = updated
    atomic_replace_jsonl(directory / "queries.jsonl", queries)
    update_audit(
        directory,
        {
            "event": "human-label",
            "at": labeled_at,
            "queryId": args.query_id,
            "revision": updated["revision"],
            "answerable": answerable,
            "acceptableCount": len(updated["label"]["acceptableEpisodeIds"]),
            "staleCount": len(updated["label"]["forbiddenStaleEpisodeIds"]),
            "foreignCount": len(updated["label"]["forbiddenForeignEpisodeIds"]),
        },
    )
    print(f"[memory-bench] {args.query_id}: menschlich gelabelt, noch NICHT reviewed")


def command_review(args: argparse.Namespace) -> None:
    require_yes(args.yes, "Review")
    directory = dataset_directory(args.root, args.dataset)
    require_mutable(directory)
    _, _, queries, scenario_map, episode_map = load_intake(directory)
    matches = [index for index, row in enumerate(queries) if row["queryId"] == args.query_id]
    if len(matches) != 1:
        raise ValueError(f"query-id nicht eindeutig vorhanden: {args.query_id}")
    index = matches[0]
    current = queries[index]
    if current["state"] != "labeled":
        raise ValueError("Review verlangt zuerst ein menschliches Label")
    if current["privacyFindings"] and not args.acknowledge_privacy:
        raise ValueError(
            "Privacy-Warnung muss bewusst mit --acknowledge-privacy bestaetigt werden: "
            + ", ".join(current["privacyFindings"])
        )
    reviewed_at = utc_now()
    updated = dict(current)
    updated["state"] = "reviewed"
    updated["reviewedAt"] = reviewed_at
    updated["revision"] = current["revision"] + 1
    updated = validate_query(updated, scenario_map, episode_map, args.query_id)
    queries[index] = updated
    atomic_replace_jsonl(directory / "queries.jsonl", queries)
    update_audit(
        directory,
        {
            "event": "human-review",
            "at": reviewed_at,
            "queryId": args.query_id,
            "revision": updated["revision"],
            "privacyAcknowledged": bool(current["privacyFindings"]),
        },
    )
    print(f"[memory-bench] {args.query_id}: reviewed")


def command_reopen(args: argparse.Namespace) -> None:
    require_yes(args.yes, "Reopen")
    reason = args.reason
    directory = dataset_directory(args.root, args.dataset)
    require_mutable(directory)
    _, _, queries, scenario_map, episode_map = load_intake(directory)
    matches = [index for index, row in enumerate(queries) if row["queryId"] == args.query_id]
    if len(matches) != 1:
        raise ValueError(f"query-id nicht eindeutig vorhanden: {args.query_id}")
    index = matches[0]
    current = queries[index]
    if current["state"] != "reviewed":
        raise ValueError("nur eine reviewte Query braucht reopen")
    reopened_at = utc_now()
    updated = dict(current)
    updated["state"] = "labeled"
    updated["reviewedAt"] = None
    updated["revision"] = current["revision"] + 1
    updated = validate_query(updated, scenario_map, episode_map, args.query_id)
    queries[index] = updated
    atomic_replace_jsonl(directory / "queries.jsonl", queries)
    update_audit(
        directory,
        {
            "event": "human-reopen",
            "at": reopened_at,
            "queryId": args.query_id,
            "revision": updated["revision"],
            "reason": reason,
        },
    )
    print(f"[memory-bench] {args.query_id}: wieder labeled; Review muss neu erfolgen")


def command_list(args: argparse.Namespace) -> None:
    directory = dataset_directory(args.root, args.dataset)
    _, scenarios, queries, _, _ = load_intake(directory)
    states = Counter(row["state"] for row in queries)
    types = Counter(row["queryType"] for row in queries)
    labels = Counter(
        "answerable" if row["label"] and row["label"]["answerable"] else "no-answer"
        for row in queries
        if row["label"] is not None
    )
    print(f"dataset={args.dataset} scenarios={len(scenarios)} queries={len(queries)}")
    print("states=" + ",".join(f"{key}:{states[key]}" for key in sorted(states)))
    print("labels=" + ",".join(f"{key}:{labels[key]}" for key in sorted(labels)))
    print("types=" + ",".join(f"{key}:{types[key]}" for key in sorted(types)))
    for row in queries:
        if args.all or row["state"] != "reviewed":
            print(f"{row['queryId']}\t{row['state']}\t{row['queryType']}\t{row['text']}")


def command_show(args: argparse.Namespace) -> None:
    directory = dataset_directory(args.root, args.dataset)
    _, _, queries, scenario_map, _ = load_intake(directory)
    matches = [row for row in queries if row["queryId"] == args.query_id]
    if len(matches) != 1:
        raise ValueError(f"query-id nicht eindeutig vorhanden: {args.query_id}")
    query = matches[0]
    print(f"queryId: {query['queryId']}")
    print(f"state: {query['state']}  type: {query['queryType']}")
    print(f"requester: {query['requesterSpeakerId']}  asOf: {query['asOf']}")
    if query["conversationContext"]:
        print("context:")
        for line in query["conversationContext"]:
            print(f"  - {line}")
    print(f"question: {query['text']}")
    print("episodes:")
    for episode in scenario_map[query["scenarioId"]]["episodes"]:
        print(
            f"  {episode['episodeId']}  {episode['occurredAt']}  "
            f"{episode['speakerId']}  [{episode['channel']}/{episode['room']}]  {episode['text']}"
        )
    if query["label"] is not None:
        print("label: " + canonical_json(query["label"]))


def _validate_ready_for_seal(scenarios: list[dict], queries: list[dict]) -> dict:
    if not MIN_TOTAL <= len(queries) <= MAX_TOTAL:
        raise ValueError(f"Seal verlangt {MIN_TOTAL}..{MAX_TOTAL} Queries")
    if len(scenarios) < MIN_SCENARIOS:
        raise ValueError(f"Seal verlangt mindestens {MIN_SCENARIOS} Szenarien")
    families = {row["templateFamily"] for row in scenarios}
    if len(families) < MIN_FAMILIES:
        raise ValueError(f"Seal verlangt mindestens {MIN_FAMILIES} Templatefamilien")
    if any(row["state"] != "reviewed" for row in queries):
        raise ValueError("Seal verlangt menschliches Label UND Review fuer jede Query")
    normalized = Counter(normalize_text(row["text"]) for row in queries)
    if any(count > 1 for count in normalized.values()):
        raise ValueError("Seal verweigert doppelte normalisierte Fragetexte")
    classes = Counter("answerable" if row["label"]["answerable"] else "no-answer" for row in queries)
    if classes["answerable"] < MIN_CLASS_TOTAL or classes["no-answer"] < MIN_CLASS_TOTAL:
        raise ValueError(f"Seal verlangt mindestens {MIN_CLASS_TOTAL} answerable und no-answer")
    type_counts = Counter(row["queryType"] for row in queries)
    missing_types = sorted(query_type for query_type in QUERY_TYPES if type_counts[query_type] < MIN_TYPE_TOTAL)
    if missing_types:
        raise ValueError(
            f"Seal verlangt pro queryType mindestens {MIN_TYPE_TOTAL}: " + ", ".join(missing_types)
        )
    stale = sum(bool(row["label"]["forbiddenStaleEpisodeIds"]) for row in queries)
    foreign = sum(bool(row["label"]["forbiddenForeignEpisodeIds"]) for row in queries)
    if stale < 12 or foreign < 12:
        raise ValueError("Seal verlangt mindestens 12 explizite Altwert- und 12 Fremdsprecher-Proben")
    return {
        "scenarioCount": len(scenarios),
        "queryCount": len(queries),
        "templateFamilyCount": len(families),
        "classCounts": dict(sorted(classes.items())),
        "queryTypeCounts": dict(sorted(type_counts.items())),
        "staleOpportunityCount": stale,
        "foreignOpportunityCount": foreign,
    }


def _validate_audit_for_seal(directory: Path, intake: dict, queries: list[dict]) -> None:
    audit = read_jsonl(directory / "audit.jsonl")
    event_fields = {
        "synthetic-generated": {
            "event", "at", "generatorVersion", "seed", "scenarioCount", "queryCount", "labelsGenerated",
        },
        "human-label": {
            "event", "at", "queryId", "revision", "answerable", "acceptableCount", "staleCount", "foreignCount",
        },
        "human-review": {
            "event", "at", "queryId", "revision", "privacyAcknowledged",
        },
        "human-reopen": {
            "event", "at", "queryId", "revision", "reason",
        },
    }
    if not audit or audit[0].get("event") != "synthetic-generated":
        raise ValueError("Audit beginnt nicht mit der synthetischen Erzeugung")
    for index, row in enumerate(audit, 1):
        event = row.get("event") if isinstance(row, dict) else None
        if event not in event_fields or set(row) != event_fields[event]:
            raise ValueError(f"Audit:{index} enthaelt unbekannte Felder oder Ereignisse")
        # Uhrzeit ist Provenienz, keine Ordnungs-/Sicherheitsquelle: lokale Uhren
        # duerfen korrigiert werden. Die Reihenfolge wird durch die append-Reihenfolge
        # und lueckenlose Query-Revisionen belegt.
        parse_timestamp(row["at"], "at", f"audit:{index}")
    generated = audit[0]
    if (
        generated["at"] != intake["createdAt"]
        or generated["labelsGenerated"] is not False
        or generated["generatorVersion"] != generate_synthetic.GENERATOR_VERSION
        or isinstance(generated["seed"], bool)
        or not isinstance(generated["seed"], int)
        or generated["seed"] != intake["generator"]["seed"]
        or isinstance(generated["queryCount"], bool)
        or not isinstance(generated["queryCount"], int)
        or generated["queryCount"] != len(queries)
        or isinstance(generated["scenarioCount"], bool)
        or not isinstance(generated["scenarioCount"], int)
        or generated["scenarioCount"] != len({row["scenarioId"] for row in queries})
    ):
        raise ValueError("Audit widerspricht dem gebundenen synthetischen Ursprung")
    by_query: dict[str, list[dict]] = {}
    for row in audit[1:]:
        query_id = row.get("queryId")
        if not isinstance(query_id, str):
            raise ValueError("Audit-Ereignis ohne queryId")
        by_query.setdefault(query_id, []).append(row)
    query_ids = {row["queryId"] for row in queries}
    if set(by_query) - query_ids:
        raise ValueError("Audit referenziert unbekannte Queries")
    for query in queries:
        history = by_query.get(query["queryId"], [])
        labels = [row for row in history if row.get("event") == "human-label"]
        if not labels:
            raise ValueError(f"Audit ohne menschliches Label fuer {query['queryId']}")
        if not history or history[-1].get("event") != "human-review":
            raise ValueError(f"Audit endet nicht mit Review fuer {query['queryId']}")
        if history[-1].get("revision") != query["revision"]:
            raise ValueError(f"Audit-Revision widerspricht {query['queryId']}")
        expected_revision = 1
        audit_state = "draft"
        for event in history:
            expected_revision += 1
            if event.get("revision") != expected_revision:
                raise ValueError(f"Audit hat eine Revisionsluecke fuer {query['queryId']}")
            event_name = event["event"]
            if event_name == "human-label":
                if audit_state not in {"draft", "labeled"}:
                    raise ValueError(f"Audit labelt reviewten Stand ohne Reopen fuer {query['queryId']}")
                if not isinstance(event["answerable"], bool) or any(
                    isinstance(event[field], bool)
                    or not isinstance(event[field], int)
                    or event[field] < 0
                    for field in ("acceptableCount", "staleCount", "foreignCount")
                ):
                    raise ValueError(f"Audit-Labeltypen ungueltig fuer {query['queryId']}")
                audit_state = "labeled"
            elif event_name == "human-review":
                if audit_state != "labeled" or not isinstance(event["privacyAcknowledged"], bool):
                    raise ValueError(f"Audit-Reviewfolge ungueltig fuer {query['queryId']}")
                audit_state = "reviewed"
            elif event_name == "human-reopen":
                if audit_state != "reviewed" or event["reason"] not in REOPEN_REASONS:
                    raise ValueError(f"Audit-Reopen ungueltig fuer {query['queryId']}")
                audit_state = "labeled"
        if audit_state != "reviewed":
            raise ValueError(f"Audit bildet keinen reviewten Endzustand fuer {query['queryId']}")
        latest_label = labels[-1]
        label = query["label"]
        if (
            latest_label["answerable"] != label["answerable"]
            or latest_label["acceptableCount"] != len(label["acceptableEpisodeIds"])
            or latest_label["staleCount"] != len(label["forbiddenStaleEpisodeIds"])
            or latest_label["foreignCount"] != len(label["forbiddenForeignEpisodeIds"])
        ):
            raise ValueError(f"Audit-Labelzaehler widersprechen {query['queryId']}")
        if latest_label["at"] != label["labeledAt"] or history[-1]["at"] != query["reviewedAt"]:
            raise ValueError(f"Audit-Zeitstempel widersprechen {query['queryId']}")
        if history[-1]["privacyAcknowledged"] != bool(query["privacyFindings"]):
            raise ValueError(f"Audit-Privacy-Review widerspricht {query['queryId']}")


def _verify_synthetic_origin(intake: dict, scenarios: list[dict], queries: list[dict]) -> None:
    if set(intake) != {"schemaVersion", "datasetId", "createdAt", "generator", "privacy"}:
        raise ValueError("intake.json besitzt kein exaktes Synthetic-v1-Schema")
    parse_timestamp(intake["createdAt"], "createdAt", "intake.json")
    generator = intake.get("generator")
    privacy = intake.get("privacy")
    if not isinstance(generator, dict) or set(generator) != {"name", "version", "seed", "labelsGenerated"}:
        raise ValueError("intake.json: Generatorvertrag ungueltig")
    if generator != {
        "name": "nagori-synthetic-v1",
        "version": generate_synthetic.GENERATOR_VERSION,
        "seed": generator.get("seed"),
        "labelsGenerated": False,
    }:
        raise ValueError("intake.json: nicht der freigegebene label-freie Generator")
    seed = generator["seed"]
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise ValueError("intake.json: Generator-Seed muss Integer sein")
    if privacy != {"syntheticOnly": True, "audioPersisted": False, "userDataRead": False}:
        raise ValueError("intake.json: Privacy-Vertrag ist nicht synthetic-only")
    family_count = len({row["templateFamily"] for row in scenarios})
    if not 12 <= len(scenarios) <= 60 or family_count != len(generate_synthetic.FAMILY_QUESTION_TEMPLATES):
        raise ValueError("intake.json: Umfang passt nicht zum freigegebenen Synthetic-v1-Vertrag")
    rng = random.Random(seed)
    expected_scenarios: list[dict] = []
    expected_queries: list[dict] = []
    for number in range(1, len(scenarios) + 1):
        family_number = ((number - 1) % family_count) + 1
        scenario, drafts = generate_synthetic.build_scenario(number, family_number, rng)
        expected_scenarios.append(scenario)
        expected_queries.extend(drafts)
    if scenarios != expected_scenarios:
        raise ValueError("Szenario-Bytes stammen nicht mehr aus dem versiegelten Synthetic-v1-Generator")
    immutable_query_fields = {
        "schemaVersion",
        "queryId",
        "scenarioId",
        "templateFamily",
        "requesterSpeakerId",
        "asOf",
        "queryType",
        "text",
        "conversationContext",
        "privacyFindings",
    }
    if len(queries) != len(expected_queries):
        raise ValueError("Query-Anzahl widerspricht dem synthetischen Ursprung")
    for current, expected in zip(queries, expected_queries):
        current_immutable = {key: current[key] for key in immutable_query_fields}
        expected_immutable = {key: expected[key] for key in immutable_query_fields}
        if current_immutable != expected_immutable:
            raise ValueError(f"Query-Quelle wurde ausserhalb des Labelvertrags veraendert: {current['queryId']}")


def command_seal(args: argparse.Namespace) -> None:
    require_yes(args.yes, "Seal")
    directory = dataset_directory(args.root, args.dataset)
    require_mutable(directory)
    intake, scenarios, queries, _, _ = load_intake(directory)
    _verify_synthetic_origin(intake, scenarios, queries)
    counts = _validate_ready_for_seal(scenarios, queries)
    _validate_audit_for_seal(directory, intake, queries)
    privacy = sorted({finding for row in queries for finding in row["privacyFindings"]})
    if privacy and not args.acknowledge_privacy:
        raise ValueError(
            "Seal verweigert Privacy-Warnungen ohne --acknowledge-privacy: " + ", ".join(privacy)
        )
    sealed_at = utc_now()
    files = {}
    for name in ("intake.json", "scenarios.jsonl", "queries.jsonl", "audit.jsonl"):
        path = directory / name
        files[name] = {"sha256": sha256_file(path), "bytes": path.stat().st_size}
    body = {
        "schemaVersion": SCHEMA_VERSION,
        "datasetId": args.dataset,
        "sealedAt": sealed_at,
        "files": files,
        "counts": counts,
        "privacy": {
            "findingTypes": privacy,
            "humanAcknowledged": bool(privacy),
            "audioPersisted": False,
            "realUserDataAllowed": False,
        },
        "splitContract": {
            "unit": "templateFamily",
            "targetHoldoutFraction": 0.33,
            "seedChosenAfterSeal": True,
            "minimumHoldoutAnswerable": MIN_HOLDOUT_ANSWERABLE,
            "minimumHoldoutNoAnswer": MIN_HOLDOUT_NO_ANSWER,
            "minimumHoldoutStale": MIN_HOLDOUT_STALE,
            "minimumHoldoutForeign": MIN_HOLDOUT_FOREIGN,
        },
        "labelContract": {
            "source": "human",
            "retrieverGeneratedLabels": False,
            "allQueriesReviewed": True,
        },
    }
    body["sealId"] = sha256_bytes(canonical_json(body).encode("utf-8"))
    write_new_json(directory / SEAL_FILE, body, 0o400)
    print(f"[memory-bench] Seal {body['sealId']} — Intake ab jetzt unveraenderlich")


def _verify_seal(directory: Path, dataset: str) -> dict:
    seal = read_json(directory / SEAL_FILE)
    if seal.get("schemaVersion") != SCHEMA_VERSION or seal.get("datasetId") != dataset:
        raise ValueError("Seal passt nicht zum Dataset")
    seal_id = seal.get("sealId")
    if not isinstance(seal_id, str) or len(seal_id) != 64:
        raise ValueError("Seal-ID ungueltig")
    unsigned = dict(seal)
    del unsigned["sealId"]
    if sha256_bytes(canonical_json(unsigned).encode("utf-8")) != seal_id:
        raise ValueError("Seal-Inhalt und Seal-ID widersprechen sich")
    files = seal.get("files")
    if not isinstance(files, dict) or set(files) != {"intake.json", "scenarios.jsonl", "queries.jsonl", "audit.jsonl"}:
        raise ValueError("Seal-Dateivertrag ungueltig")
    for name, expected in files.items():
        path = directory / name
        if sha256_file(path) != expected.get("sha256") or path.stat().st_size != expected.get("bytes"):
            raise ValueError(f"Seal-Drift: {name}")
    return seal


def _group_stats(queries: list[dict]) -> dict[str, tuple[int, int, int, int, int]]:
    grouped: dict[str, list[dict]] = {}
    for row in queries:
        grouped.setdefault(row["templateFamily"], []).append(row)
    return {
        name: (
            len(rows),
            sum(row["label"]["answerable"] is True for row in rows),
            sum(row["label"]["answerable"] is False for row in rows),
            sum(bool(row["label"]["forbiddenStaleEpisodeIds"]) for row in rows),
            sum(bool(row["label"]["forbiddenForeignEpisodeIds"]) for row in rows),
        )
        for name, rows in grouped.items()
    }


def choose_holdout_families(queries: list[dict], seed: str) -> set[str]:
    stats = _group_stats(queries)
    names = sorted(
        stats,
        key=lambda name: hashlib.sha256(f"{seed}:{name}".encode("utf-8")).hexdigest(),
    )
    total = len(queries)
    total_answerable = sum(row["label"]["answerable"] is True for row in queries)
    total_no_answer = total - total_answerable
    targets = (round(total * 0.33), round(total_answerable * 0.33), round(total_no_answer * 0.33))
    feasible: list[tuple[tuple[int, int, int, int, int], tuple[str, ...]]] = []
    # Familie ist der Leckage-Block. Die reale Mindestzahl ist klein (>=6),
    # deshalb ist eine exhaustive Auswahl hier beweisbarer als ein Greedy-Split.
    if len(names) > 20:
        raise ValueError("mehr als 20 Templatefamilien: Split-Suche bewusst nicht approximieren")
    for size in range(1, len(names)):
        for chosen in itertools.combinations(names, size):
            counts = tuple(sum(stats[name][index] for name in chosen) for index in range(5))
            rows, answerable, no_answer, stale, foreign = counts
            remaining_types = Counter(row["queryType"] for row in queries if row["templateFamily"] not in chosen)
            holdout_types = Counter(row["queryType"] for row in queries if row["templateFamily"] in chosen)
            if (
                answerable >= MIN_HOLDOUT_ANSWERABLE
                and no_answer >= MIN_HOLDOUT_NO_ANSWER
                and stale >= MIN_HOLDOUT_STALE
                and foreign >= MIN_HOLDOUT_FOREIGN
                and rows < total
                and total_answerable - answerable > 0
                and total_no_answer - no_answer > 0
                and all(remaining_types[query_type] > 0 and holdout_types[query_type] > 0 for query_type in QUERY_TYPES)
            ):
                feasible.append((counts, chosen))
    if not feasible:
        raise ValueError("kein templateFamily-getrennter Split erfuellt Klassen-/Intrusionsminima")
    _, chosen = min(
        feasible,
        key=lambda item: (
            abs(item[0][0] - targets[0]),
            abs(item[0][1] - targets[1]) + abs(item[0][2] - targets[2]),
            tuple(hashlib.sha256(f"{seed}:{name}".encode()).hexdigest() for name in item[1]),
        ),
    )
    return set(chosen)


def _freeze_rows(rows: list[dict], split: str) -> list[dict]:
    output = []
    for row in rows:
        frozen = dict(row)
        frozen["split"] = split
        output.append(frozen)
    return output


def _write_freeze(
    temporary: Path,
    dataset: str,
    seal: dict,
    scenarios: list[dict],
    queries: list[dict],
    holdout_families: set[str],
    split_seed: str,
) -> None:
    scenario_split = {
        row["scenarioId"]: ("holdout" if row["templateFamily"] in holdout_families else "dev")
        for row in scenarios
    }
    dev_scenarios = [row for row in scenarios if scenario_split[row["scenarioId"]] == "dev"]
    holdout_scenarios = [row for row in scenarios if scenario_split[row["scenarioId"]] == "holdout"]
    dev_queries = _freeze_rows([row for row in queries if scenario_split[row["scenarioId"]] == "dev"], "dev")
    holdout_queries = _freeze_rows([row for row in queries if scenario_split[row["scenarioId"]] == "holdout"], "holdout")
    files = {
        "dev.scenarios.jsonl": jsonl_bytes(dev_scenarios),
        "dev.queries.jsonl": jsonl_bytes(dev_queries),
        "holdout.scenarios.jsonl": jsonl_bytes(holdout_scenarios),
        "holdout.queries.jsonl": jsonl_bytes(holdout_queries),
    }
    for name, payload in files.items():
        path = temporary / name
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
        try:
            with os.fdopen(descriptor, "wb", closefd=False) as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
        finally:
            os.close(descriptor)
        os.chmod(path, 0o400)
    contract = {
        "schemaVersion": SCHEMA_VERSION,
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
    write_new_json(temporary / "evaluation-contract.json", contract, 0o400)
    file_manifest = {
        name: {"sha256": sha256_bytes(payload), "bytes": len(payload)}
        for name, payload in files.items()
    }
    contract_payload = (canonical_json(contract) + "\n").encode("utf-8")
    file_manifest["evaluation-contract.json"] = {
        "sha256": sha256_bytes(contract_payload),
        "bytes": len(contract_payload),
    }
    def split_counts(split_queries: list[dict], split_scenarios: list[dict]) -> dict:
        return {
            "scenarios": len(split_scenarios),
            "queries": len(split_queries),
            "answerable": sum(row["label"]["answerable"] for row in split_queries),
            "noAnswer": sum(not row["label"]["answerable"] for row in split_queries),
            "staleOpportunities": sum(bool(row["label"]["forbiddenStaleEpisodeIds"]) for row in split_queries),
            "foreignOpportunities": sum(bool(row["label"]["forbiddenForeignEpisodeIds"]) for row in split_queries),
            "queryTypes": dict(sorted(Counter(row["queryType"] for row in split_queries).items())),
        }
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "datasetId": dataset,
        "frozenAt": utc_now(),
        "sealId": seal["sealId"],
        "split": {
            "unit": "templateFamily",
            "seed": split_seed,
            "targetHoldoutFraction": 0.33,
            "holdoutFamilies": sorted(holdout_families),
            "dev": split_counts(dev_queries, dev_scenarios),
            "holdout": split_counts(holdout_queries, holdout_scenarios),
        },
        "files": file_manifest,
        "claims": {
            "humanReviewed": True,
            "retrieverGeneratedLabels": False,
            "audioPersisted": False,
            "containsRealUserData": False,
            "cryptographicallyBlindHoldout": False,
            "description": "eingefrorener lokaler A/B-Vergleich; kein kryptografisch blinder Holdout",
        },
    }
    write_new_json(temporary / "manifest.json", manifest, 0o400)
    os.chmod(temporary, 0o500)


def command_freeze(args: argparse.Namespace) -> None:
    require_yes(args.yes, "Freeze")
    directory = dataset_directory(args.root, args.dataset)
    seal = _verify_seal(directory, args.dataset)
    output = args.output_dir.absolute()
    ensure_private_directory(output.parent, create=True)
    if output.exists():
        raise ValueError("Output existiert bereits; Freeze wird nie ueberschrieben")
    lock_path = directory / FREEZE_LOCK_FILE
    lock_fd = os.open(
        lock_path,
        os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    if not stat.S_ISREG(os.fstat(lock_fd).st_mode):
        os.close(lock_fd)
        raise ValueError("Freeze-Lock ist keine regulaere Datei")
    try:
        os.fchmod(lock_fd, 0o600)
    except OSError:
        os.close(lock_fd)
        raise
    lock_acquired = False
    try:
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            lock_acquired = True
        except BlockingIOError as exc:
            raise ValueError("Freeze laeuft fuer dieses Dataset bereits") from exc
        intent_path = directory / FREEZE_INTENT_FILE
        if intent_path.exists():
            intent = read_json(intent_path)
            if intent.get("sealId") != seal["sealId"] or intent.get("outputName") != output.name:
                raise ValueError("Seal ist bereits fuer einen anderen Freeze verbraucht")
            split_seed = intent.get("splitSeed")
            if not isinstance(split_seed, str) or len(split_seed) != 64:
                raise ValueError("Freeze-Intent hat keinen gueltigen Split-Seed")
        else:
            split_seed = secrets.token_hex(32)
            write_new_json(
                intent_path,
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "sealId": seal["sealId"],
                    "createdAt": utc_now(),
                    "outputName": output.name,
                    "splitSeed": split_seed,
                    "singleUse": True,
                },
                0o400,
            )
        _, scenarios, queries, _, _ = load_intake(directory)
        holdout_families = choose_holdout_families(queries, split_seed)
        temporary = output.parent / f".{output.name}.tmp-{secrets.token_hex(8)}"
        temporary.mkdir(mode=0o700)
        try:
            _write_freeze(temporary, args.dataset, seal, scenarios, queries, holdout_families, split_seed)
            publish_directory_no_replace(temporary, output)
        finally:
            remove_private_tree(temporary)
    finally:
        if lock_acquired:
            # Unter gehaltenem Lock entkoppeln: Ein konkurrierender Prozess kann
            # kein zweites Lock-Inode zwischen Unlock und Unlink einschieben.
            lock_path.unlink(missing_ok=True)
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)
    print(f"[memory-bench] Freeze publiziert: {output}")
    print("[memory-bench] Ehrlicher Claim: eingefroren, lokal lesbar — NICHT kryptografisch blind")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    commands = root.add_subparsers(dest="command", required=True)

    listing = commands.add_parser("list", help="Intake-Status anzeigen")
    listing.add_argument("dataset")
    listing.add_argument("--all", action="store_true")
    listing.set_defaults(func=command_list)

    show = commands.add_parser("show", help="Frage und ihre synthetische Zeitlinie anzeigen")
    show.add_argument("dataset")
    show.add_argument("query_id")
    show.set_defaults(func=command_show)

    label = commands.add_parser("label", help="Gold/Verbote menschlich setzen")
    label.add_argument("dataset")
    label.add_argument("query_id")
    label.add_argument("--answerable", choices=("yes", "no"), required=True)
    label.add_argument("--acceptable", action="append")
    label.add_argument("--forbidden-stale", action="append")
    label.add_argument("--forbidden-foreign", action="append")
    label.add_argument("--yes", action="store_true")
    label.set_defaults(func=command_label)

    review = commands.add_parser("review", help="Label separat menschlich pruefen")
    review.add_argument("dataset")
    review.add_argument("query_id")
    review.add_argument("--acknowledge-privacy", action="store_true")
    review.add_argument("--yes", action="store_true")
    review.set_defaults(func=command_review)

    reopen = commands.add_parser("reopen", help="Review mit protokolliertem Grund oeffnen")
    reopen.add_argument("dataset")
    reopen.add_argument("query_id")
    reopen.add_argument(
        "--reason",
        choices=tuple(sorted(REOPEN_REASONS)),
        required=True,
    )
    reopen.add_argument("--yes", action="store_true")
    reopen.set_defaults(func=command_reopen)

    seal = commands.add_parser("seal", help="reviewte Intake-Bytes einmalig binden")
    seal.add_argument("dataset")
    seal.add_argument("--acknowledge-privacy", action="store_true")
    seal.add_argument("--yes", action="store_true")
    seal.set_defaults(func=command_seal)

    freeze = commands.add_parser("freeze", help="nach dem Seal unveraenderlich splitten")
    freeze.add_argument("dataset")
    freeze.add_argument("--output-dir", type=Path, required=True)
    freeze.add_argument("--yes", action="store_true")
    freeze.set_defaults(func=command_freeze)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.func(args)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"[memory-bench] FEHLER: {exc}") from exc
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
