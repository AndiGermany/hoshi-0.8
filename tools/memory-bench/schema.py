# SPDX-License-Identifier: Apache-2.0
"""Strikter Dateivertrag fuer Nagoris episodischen Memory-Benchmark."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import stat
from datetime import datetime
from pathlib import Path
from typing import Iterable


SCHEMA_VERSION = 1
DATASET_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
SCENARIO_ID = re.compile(r"scenario-[a-z0-9][a-z0-9-]{0,47}")
TEMPLATE_FAMILY = re.compile(r"family-[a-z0-9][a-z0-9-]{0,47}")
EPISODE_ID = re.compile(r"episode-[a-z0-9][a-z0-9-]{0,63}")
QUERY_ID = re.compile(r"query-[a-z0-9][a-z0-9-]{0,63}")
SPEAKER_ID = re.compile(r"speaker-[a-z0-9][a-z0-9-]{0,47}")
VARIANTS = {"B0", "H1", "H2", "H1_H2"}
QUERY_TYPES = {
    "semantic_paraphrase",
    "exact_entity",
    "temporal_update",
    "no_answer",
    "cross_speaker",
    "conversation_context",
    "metadata_disambiguation",
}

_SCENARIO_FIELDS = {
    "schemaVersion",
    "scenarioId",
    "templateFamily",
    "episodes",
}
_EPISODE_FIELDS = {
    "episodeId",
    "speakerId",
    "occurredAt",
    "text",
    "channel",
    "room",
}
_QUERY_FIELDS = {
    "schemaVersion",
    "queryId",
    "scenarioId",
    "templateFamily",
    "requesterSpeakerId",
    "asOf",
    "queryType",
    "text",
    "conversationContext",
    "state",
    "label",
    "reviewedAt",
    "revision",
    "privacyFindings",
}
_LABEL_FIELDS = {
    "answerable",
    "acceptableEpisodeIds",
    "forbiddenStaleEpisodeIds",
    "forbiddenForeignEpisodeIds",
    "labeledAt",
    "labelSource",
}
_RESULT_FIELDS = {
    "schemaVersion",
    "variant",
    "condition",
    "queryId",
    "retrievedEpisodeIds",
    "latencyMs",
}


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def jsonl_bytes(rows: Iterable[dict]) -> bytes:
    return ("".join(canonical_json(row) + "\n" for row in rows)).encode("utf-8")


def _strict_fields(item: dict, expected: set[str], where: str) -> None:
    unknown = sorted(set(item) - expected)
    missing = sorted(expected - set(item))
    if unknown:
        raise ValueError(f"{where}: unbekannte Felder: {', '.join(unknown)}")
    if missing:
        raise ValueError(f"{where}: fehlende Felder: {', '.join(missing)}")


def _text(value: object, field: str, where: str, *, maximum: int = 500) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{where}: {field} muss nicht-leerer Text sein")
    result = value.strip()
    if len(result) > maximum:
        raise ValueError(f"{where}: {field} ist laenger als {maximum} Zeichen")
    if any(ord(char) < 32 and char not in "\t\n" for char in result):
        raise ValueError(f"{where}: {field} enthaelt Steuerzeichen")
    return result


def _id(value: object, pattern: re.Pattern[str], field: str, where: str) -> str:
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise ValueError(f"{where}: {field} hat kein erlaubtes opakes Format")
    return value


def parse_timestamp(value: object, field: str, where: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError(f"{where}: {field} muss ein UTC-Zeitstempel mit Z sein")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ValueError(f"{where}: {field} ist kein ISO-8601-Zeitstempel") from exc
    if parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError(f"{where}: {field} muss UTC sein")
    return parsed


def normalize_text(value: str) -> str:
    return " ".join(value.casefold().split())


def validate_dataset_id(value: str) -> str:
    if not DATASET_ID.fullmatch(value):
        raise ValueError("dataset muss [a-z0-9-] verwenden und 1..64 Zeichen lang sein")
    return value


def validate_scenario(item: object, where: str) -> dict:
    if not isinstance(item, dict):
        raise ValueError(f"{where}: Objekt erwartet")
    _strict_fields(item, _SCENARIO_FIELDS, where)
    if item["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError(f"{where}: nicht unterstuetzte schemaVersion")
    scenario_id = _id(item["scenarioId"], SCENARIO_ID, "scenarioId", where)
    family = _id(item["templateFamily"], TEMPLATE_FAMILY, "templateFamily", where)
    episodes = item["episodes"]
    if not isinstance(episodes, list) or not (4 <= len(episodes) <= 64):
        raise ValueError(f"{where}: episodes muss 4..64 Eintraege enthalten")
    seen: set[str] = set()
    previous: datetime | None = None
    normalized_episodes: list[dict] = []
    for index, raw in enumerate(episodes, 1):
        episode_where = f"{where}:episodes[{index}]"
        if not isinstance(raw, dict):
            raise ValueError(f"{episode_where}: Objekt erwartet")
        _strict_fields(raw, _EPISODE_FIELDS, episode_where)
        episode_id = _id(raw["episodeId"], EPISODE_ID, "episodeId", episode_where)
        if episode_id in seen:
            raise ValueError(f"{where}: doppelte episodeId {episode_id}")
        seen.add(episode_id)
        speaker_id = _id(raw["speakerId"], SPEAKER_ID, "speakerId", episode_where)
        occurred = parse_timestamp(raw["occurredAt"], "occurredAt", episode_where)
        if previous is not None and occurred <= previous:
            raise ValueError(f"{where}: Episoden muessen streng chronologisch sein")
        previous = occurred
        text = _text(raw["text"], "text", episode_where, maximum=1000)
        channel = _text(raw["channel"], "channel", episode_where, maximum=32)
        room = _text(raw["room"], "room", episode_where, maximum=64)
        normalized_episodes.append(
            {
                "episodeId": episode_id,
                "speakerId": speaker_id,
                "occurredAt": raw["occurredAt"],
                "text": text,
                "channel": channel,
                "room": room,
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scenarioId": scenario_id,
        "templateFamily": family,
        "episodes": normalized_episodes,
    }


def scenario_indexes(scenarios: list[dict]) -> tuple[dict[str, dict], dict[str, tuple[str, dict]]]:
    by_scenario: dict[str, dict] = {}
    by_episode: dict[str, tuple[str, dict]] = {}
    for index, raw in enumerate(scenarios, 1):
        scenario = validate_scenario(raw, f"scenarios:{index}")
        scenario_id = scenario["scenarioId"]
        if scenario_id in by_scenario:
            raise ValueError(f"scenarios: doppelte scenarioId {scenario_id}")
        by_scenario[scenario_id] = scenario
        for episode in scenario["episodes"]:
            episode_id = episode["episodeId"]
            if episode_id in by_episode:
                raise ValueError(f"scenarios: global doppelte episodeId {episode_id}")
            by_episode[episode_id] = (scenario_id, episode)
    return by_scenario, by_episode


def _id_list(value: object, field: str, where: str) -> list[str]:
    if not isinstance(value, list):
        raise ValueError(f"{where}: {field} muss eine Liste sein")
    result = [_id(entry, EPISODE_ID, field, where) for entry in value]
    if len(result) != len(set(result)):
        raise ValueError(f"{where}: {field} enthaelt Duplikate")
    return result


def validate_query(
    item: object,
    scenarios: dict[str, dict],
    episodes: dict[str, tuple[str, dict]],
    where: str,
    *,
    frozen_split: str | None = None,
) -> dict:
    if not isinstance(item, dict):
        raise ValueError(f"{where}: Objekt erwartet")
    expected = _QUERY_FIELDS | ({"split"} if frozen_split is not None else set())
    _strict_fields(item, expected, where)
    if item["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError(f"{where}: nicht unterstuetzte schemaVersion")
    if frozen_split is not None and item["split"] != frozen_split:
        raise ValueError(f"{where}: split muss {frozen_split} sein")
    query_id = _id(item["queryId"], QUERY_ID, "queryId", where)
    scenario_id = _id(item["scenarioId"], SCENARIO_ID, "scenarioId", where)
    if scenario_id not in scenarios:
        raise ValueError(f"{where}: unbekannte scenarioId {scenario_id}")
    family = _id(item["templateFamily"], TEMPLATE_FAMILY, "templateFamily", where)
    if family != scenarios[scenario_id]["templateFamily"]:
        raise ValueError(f"{where}: templateFamily widerspricht dem Szenario")
    requester = _id(item["requesterSpeakerId"], SPEAKER_ID, "requesterSpeakerId", where)
    if requester not in {entry["speakerId"] for entry in scenarios[scenario_id]["episodes"]}:
        raise ValueError(f"{where}: requesterSpeakerId kommt im Szenario nicht vor")
    as_of = parse_timestamp(item["asOf"], "asOf", where)
    query_type = item["queryType"]
    if query_type not in QUERY_TYPES:
        raise ValueError(f"{where}: unbekannter queryType {query_type!r}")
    text = _text(item["text"], "text", where, maximum=500)
    context = item["conversationContext"]
    if not isinstance(context, list) or len(context) > 4:
        raise ValueError(f"{where}: conversationContext muss eine Liste mit max. 4 Texten sein")
    normalized_context = [
        _text(entry, "conversationContext", where, maximum=500) for entry in context
    ]
    state = item["state"]
    if state not in {"draft", "labeled", "reviewed"}:
        raise ValueError(f"{where}: state muss draft, labeled oder reviewed sein")
    revision = item["revision"]
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
        raise ValueError(f"{where}: revision muss ein positiver Integer sein")
    findings = item["privacyFindings"]
    if not isinstance(findings, list) or any(
        not isinstance(entry, str) or not entry for entry in findings
    ):
        raise ValueError(f"{where}: privacyFindings muss eine String-Liste sein")
    if len(findings) != len(set(findings)):
        raise ValueError(f"{where}: privacyFindings enthaelt Duplikate")

    label = item["label"]
    reviewed_at = item["reviewedAt"]
    if state == "draft":
        if label is not None or reviewed_at is not None:
            raise ValueError(f"{where}: draft darf weder Label noch Review tragen")
    else:
        if not isinstance(label, dict):
            raise ValueError(f"{where}: {state} braucht ein Label")
        _strict_fields(label, _LABEL_FIELDS, f"{where}:label")
        if label["labelSource"] != "human":
            raise ValueError(f"{where}: labelSource muss human sein")
        parse_timestamp(label["labeledAt"], "labeledAt", f"{where}:label")
        answerable = label["answerable"]
        if not isinstance(answerable, bool):
            raise ValueError(f"{where}: answerable muss boolean sein")
        acceptable = _id_list(label["acceptableEpisodeIds"], "acceptableEpisodeIds", where)
        stale = _id_list(label["forbiddenStaleEpisodeIds"], "forbiddenStaleEpisodeIds", where)
        foreign = _id_list(label["forbiddenForeignEpisodeIds"], "forbiddenForeignEpisodeIds", where)
        if answerable != bool(acceptable):
            raise ValueError(f"{where}: answerable und acceptableEpisodeIds widersprechen sich")
        if set(acceptable) & (set(stale) | set(foreign)) or set(stale) & set(foreign):
            raise ValueError(f"{where}: Gold-, Alt- und Fremd-IDs muessen disjunkt sein")
        for field, values in (
            ("acceptableEpisodeIds", acceptable),
            ("forbiddenStaleEpisodeIds", stale),
            ("forbiddenForeignEpisodeIds", foreign),
        ):
            for episode_id in values:
                if episode_id not in episodes:
                    raise ValueError(f"{where}: {field} referenziert unbekannte Episode {episode_id}")
                linked_scenario, episode = episodes[episode_id]
                if linked_scenario != scenario_id:
                    raise ValueError(f"{where}: {field} darf das Szenario nicht verlassen")
                if parse_timestamp(episode["occurredAt"], "occurredAt", where) > as_of:
                    raise ValueError(f"{where}: {field} referenziert eine Episode nach asOf")
        if any(episodes[entry][1]["speakerId"] != requester for entry in acceptable):
            raise ValueError(f"{where}: akzeptierte Episode gehoert nicht dem fragenden Sprecher")
        if any(episodes[entry][1]["speakerId"] != requester for entry in stale):
            raise ValueError(f"{where}: Altwert muss dem fragenden Sprecher gehoeren")
        if any(episodes[entry][1]["speakerId"] == requester for entry in foreign):
            raise ValueError(f"{where}: Fremd-ID gehoert faelschlich dem fragenden Sprecher")
        if query_type == "temporal_update" and (not acceptable or not stale):
            raise ValueError(f"{where}: temporal_update braucht aktuellen Gold- und verbotenen Altwert")
        if query_type == "cross_speaker" and not foreign:
            raise ValueError(f"{where}: cross_speaker braucht mindestens eine explizite Fremd-ID")
        if query_type == "no_answer" and answerable:
            raise ValueError(f"{where}: no_answer darf nicht beantwortbar gelabelt sein")
        if state == "labeled" and reviewed_at is not None:
            raise ValueError(f"{where}: labeled darf noch kein reviewedAt tragen")
        if state == "reviewed":
            parse_timestamp(reviewed_at, "reviewedAt", where)

    result = dict(item)
    result["queryId"] = query_id
    result["scenarioId"] = scenario_id
    result["templateFamily"] = family
    result["requesterSpeakerId"] = requester
    result["text"] = text
    result["conversationContext"] = normalized_context
    result["privacyFindings"] = list(findings)
    return result


def validate_result(item: object, where: str) -> dict:
    if not isinstance(item, dict):
        raise ValueError(f"{where}: Objekt erwartet")
    _strict_fields(item, _RESULT_FIELDS, where)
    if item["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError(f"{where}: nicht unterstuetzte schemaVersion")
    variant = item["variant"]
    if variant not in VARIANTS:
        raise ValueError(f"{where}: variant muss eine von {sorted(VARIANTS)} sein")
    condition = item["condition"]
    if condition not in {"cold", "warm"}:
        raise ValueError(f"{where}: condition muss cold oder warm sein")
    query_id = _id(item["queryId"], QUERY_ID, "queryId", where)
    retrieved = _id_list(item["retrievedEpisodeIds"], "retrievedEpisodeIds", where)
    if len(retrieved) > 2:
        raise ValueError(f"{where}: topK=2 erlaubt hoechstens 2 Retrieval-IDs")
    latency = item["latencyMs"]
    if isinstance(latency, bool) or not isinstance(latency, (int, float)):
        raise ValueError(f"{where}: latencyMs muss numerisch sein")
    latency = float(latency)
    if not math.isfinite(latency) or not 0 <= latency <= 60_000:
        raise ValueError(f"{where}: latencyMs ausserhalb 0..60000")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "variant": variant,
        "condition": condition,
        "queryId": query_id,
        "retrievedEpisodeIds": retrieved,
        "latencyMs": latency,
    }


def read_jsonl(path: Path) -> list[dict]:
    ensure_regular_file(path)
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.endswith("\n"):
                raise ValueError(f"{path.name}:{line_number}: Zeile endet nicht mit Newline")
            if not line.strip():
                raise ValueError(f"{path.name}:{line_number}: leere Zeile")
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path.name}:{line_number}: ungueltiges JSON") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path.name}:{line_number}: Objekt erwartet")
            rows.append(value)
    return rows


def read_json(path: Path) -> dict:
    ensure_regular_file(path)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path.name}: ungueltiges JSON") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{path.name}: Objekt erwartet")
    return value


def ensure_regular_file(path: Path) -> None:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as exc:
        raise ValueError(f"Datei fehlt: {path}") from exc
    if not stat.S_ISREG(mode) or stat.S_ISLNK(mode):
        raise ValueError(f"Nur regulaere Dateien erlaubt: {path}")


def ensure_private_directory(path: Path, *, create: bool = False) -> None:
    if create:
        existed = path.exists()
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not existed:
            os.chmod(path, 0o700)
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as exc:
        raise ValueError(f"Verzeichnis fehlt: {path}") from exc
    if not stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
        raise ValueError(f"Nur echte Verzeichnisse erlaubt: {path}")
