#!/usr/bin/env python3
"""Strikter, migrationsfähiger Dateivertrag für Hoshis Knowledge-Benchmark."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


SCHEMA_VERSION = 2

_V1_FIELDS = {
    "schemaVersion",
    "id",
    "split",
    "query",
    "searchQuery",
    "answerable",
    "goldTitles",
    "goldEvidence",
    "exactTitleRequired",
}
_V2_FIELDS = {
    "schemaVersion",
    "id",
    "split",
    "query",
    "searchQuery",
    "answerable",
    "goldPassages",
    "exactTitleRequired",
    "topicGroup",
    "stratum",
}
_PASSAGE_FIELDS = {"title", "evidence"}


def normalize_title(value: str) -> str:
    return " ".join(value.casefold().replace("_", " ").split())


def normalize_evidence(value: str) -> str:
    return " ".join(value.casefold().split())


@dataclass(frozen=True)
class GoldPassage:
    title: str
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class Query:
    id: str
    split: str
    query: str
    search_query: str
    answerable: bool
    gold_passages: tuple[GoldPassage, ...]
    exact_title_required: bool
    topic_group: str
    stratum: str
    source_schema_version: int = SCHEMA_VERSION

    @property
    def gold_titles(self) -> tuple[str, ...]:
        return tuple(passage.title for passage in self.gold_passages)

    @property
    def gold_evidence(self) -> tuple[str, ...]:
        return tuple(
            span
            for passage in self.gold_passages
            for span in passage.evidence
        )

    def as_v2_item(self) -> dict:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "id": self.id,
            "split": self.split,
            "query": self.query,
            "searchQuery": self.search_query,
            "answerable": self.answerable,
            "goldPassages": [
                {"title": passage.title, "evidence": list(passage.evidence)}
                for passage in self.gold_passages
            ],
            "exactTitleRequired": self.exact_title_required,
            "topicGroup": self.topic_group,
            "stratum": self.stratum,
        }


def _text(item: dict, key: str, where: str) -> str:
    value = item.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{where}: {key} fehlt")
    return value.strip()


def _string_list(value: object, field: str, where: str) -> tuple[str, ...]:
    if not isinstance(value, list) or any(
        not isinstance(entry, str) or not entry.strip() for entry in value
    ):
        raise ValueError(f"{where}: {field} muss eine Liste nicht-leerer Strings sein")
    stripped = tuple(entry.strip() for entry in value)
    normalized = [normalize_evidence(entry) for entry in stripped]
    if len(normalized) != len(set(normalized)):
        raise ValueError(f"{where}: {field} enthält Duplikate")
    return stripped


def _common(item: dict, where: str) -> tuple[str, str, str, str, bool, bool]:
    query_id = _text(item, "id", where)
    split = item.get("split")
    if split not in {"dev", "holdout"}:
        raise ValueError(f"{where}: split muss dev oder holdout sein")
    query = _text(item, "query", where)
    search_query = item.get("searchQuery", query)
    if not isinstance(search_query, str) or not search_query.strip():
        raise ValueError(f"{where}: searchQuery muss ein nicht-leerer String sein")
    answerable = item.get("answerable")
    if not isinstance(answerable, bool):
        raise ValueError(f"{where}: answerable muss boolean sein")
    exact_title_required = item.get("exactTitleRequired", False)
    if not isinstance(exact_title_required, bool):
        raise ValueError(f"{where}: exactTitleRequired muss boolean sein")
    if not answerable and exact_title_required:
        raise ValueError(
            f"{where}: unbeantwortbare Fragen dürfen keinen exakten Titel verlangen"
        )
    return (
        query_id,
        split,
        query,
        search_query.strip(),
        answerable,
        exact_title_required,
    )


def _parse_v2(item: dict, where: str) -> Query:
    unknown = sorted(set(item) - _V2_FIELDS)
    missing = sorted(_V2_FIELDS - set(item))
    if unknown:
        raise ValueError(f"{where}: unbekannte v2-Felder: {', '.join(unknown)}")
    if missing:
        raise ValueError(f"{where}: fehlende v2-Felder: {', '.join(missing)}")
    (
        query_id,
        split,
        query,
        search_query,
        answerable,
        exact_title_required,
    ) = _common(item, where)

    raw_passages = item.get("goldPassages")
    if not isinstance(raw_passages, list):
        raise ValueError(f"{where}: goldPassages muss eine Liste sein")
    passages: list[GoldPassage] = []
    seen_titles: set[str] = set()
    for index, raw in enumerate(raw_passages, 1):
        passage_where = f"{where}:goldPassages[{index}]"
        if not isinstance(raw, dict):
            raise ValueError(f"{passage_where}: Objekt erwartet")
        unexpected = sorted(set(raw) - _PASSAGE_FIELDS)
        missing_passage = sorted(_PASSAGE_FIELDS - set(raw))
        if unexpected:
            raise ValueError(
                f"{passage_where}: unbekannte Felder: {', '.join(unexpected)}"
            )
        if missing_passage:
            raise ValueError(
                f"{passage_where}: fehlende Felder: {', '.join(missing_passage)}"
            )
        title = _text(raw, "title", passage_where)
        title_key = normalize_title(title)
        if title_key in seen_titles:
            raise ValueError(f"{where}: doppelter Goldtitel {title!r}")
        seen_titles.add(title_key)
        evidence = _string_list(raw.get("evidence"), "evidence", passage_where)
        if not evidence:
            raise ValueError(f"{passage_where}: mindestens ein Evidenzspan nötig")
        passages.append(GoldPassage(title=title, evidence=evidence))

    if answerable != bool(passages):
        raise ValueError(
            f"{where}: beantwortbar braucht goldPassages; "
            "unbeantwortbar darf keine tragen"
        )
    if exact_title_required and normalize_title(search_query) not in seen_titles:
        raise ValueError(
            f"{where}: exactTitleRequired verlangt eine searchQuery, die einem "
            "normalisierten Goldtitel entspricht"
        )
    topic_group = _text(item, "topicGroup", where)
    stratum = _text(item, "stratum", where)
    return Query(
        id=query_id,
        split=split,
        query=query,
        search_query=search_query,
        answerable=answerable,
        gold_passages=tuple(passages),
        exact_title_required=exact_title_required,
        topic_group=topic_group,
        stratum=stratum,
    )


def _parse_v1(item: dict, where: str) -> Query:
    unknown = sorted(set(item) - _V1_FIELDS)
    if unknown:
        raise ValueError(f"{where}: unbekannte v1-Felder: {', '.join(unknown)}")
    (
        query_id,
        split,
        query,
        search_query,
        answerable,
        exact_title_required,
    ) = _common(item, where)
    titles = _string_list(item.get("goldTitles"), "goldTitles", where)
    evidence = _string_list(item.get("goldEvidence"), "goldEvidence", where)
    if answerable != bool(titles):
        raise ValueError(
            f"{where}: beantwortbar braucht Goldtitel; "
            "unbeantwortbar darf keinen tragen"
        )
    if answerable != bool(evidence):
        raise ValueError(
            f"{where}: beantwortbar braucht Gold-Evidenz; "
            "unbeantwortbar darf keine tragen"
        )
    if len(titles) > 1:
        raise ValueError(
            f"{where}: v1 mit mehreren Goldtiteln ist nicht eindeutig migrierbar; "
            "bitte goldPassages in Schema v2 verwenden"
        )
    passages = (
        (GoldPassage(title=titles[0], evidence=evidence),)
        if titles
        else ()
    )
    if (
        exact_title_required
        and normalize_title(search_query)
        not in {normalize_title(title) for title in titles}
    ):
        raise ValueError(
            f"{where}: exactTitleRequired verlangt eine searchQuery, die einem "
            "normalisierten Goldtitel entspricht"
        )
    return Query(
        id=query_id,
        split=split,
        query=query,
        search_query=search_query,
        answerable=answerable,
        gold_passages=passages,
        exact_title_required=exact_title_required,
        topic_group=f"legacy:{query_id}",
        stratum="legacy-v1",
        source_schema_version=1,
    )


def parse_query_item(item: object, where: str) -> Query:
    if not isinstance(item, dict):
        raise ValueError(f"{where}: Objekt erwartet")
    version = item.get("schemaVersion")
    if version == SCHEMA_VERSION or "goldPassages" in item:
        if version != SCHEMA_VERSION:
            raise ValueError(
                f"{where}: goldPassages erfordert schemaVersion={SCHEMA_VERSION}"
            )
        return _parse_v2(item, where)
    if version not in {None, 1}:
        raise ValueError(f"{where}: nicht unterstützte schemaVersion {version!r}")
    return _parse_v1(item, where)


def read_queries(path: Path, split: str) -> list[Query]:
    if split not in {"dev", "holdout", "all"}:
        raise ValueError(f"ungültiger Split {split!r}")
    queries: list[Query] = []
    ids: set[str] = set()
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            where = f"{path.name}:{line_no}"
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{where}: ungültiges JSON: {exc}") from exc
            query = parse_query_item(item, where)
            if query.id in ids:
                raise ValueError(f"{where}: doppelte id {query.id!r}")
            ids.add(query.id)
            if split == "all" or query.split == split:
                queries.append(query)
    if not queries:
        raise ValueError(f"keine Queries für split={split}")
    return queries
