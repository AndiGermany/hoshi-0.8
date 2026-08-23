#!/usr/bin/env python3
"""Strict, dependency-free schema for the command replay corpus."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable


EXPECTED_KINDS = {"TOOL_CALL", "CLARIFY", "NO_COMMAND"}
ORIGIN_KINDS = {"documented_summary", "synthetic", "manual_review"}
MUTATION_KINDS = {"verb_drop", "onset_drop", "homophone", "negative_control"}
LABEL_STATES = {"DRAFT", "APPROVED"}
PENDING_CLARIFY_OUTCOMES = {"asked", "resolved", "expired", "abandoned"}


class CorpusError(ValueError):
    pass


def _nonblank(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise CorpusError(f"{field} must be a non-blank string")
    return value.strip()


def _text(value: Any, field: str) -> str:
    text = _nonblank(value, field)
    if len(text) > 200 or "\n" in text or "\r" in text:
        raise CorpusError(f"{field} must be one line and at most 200 characters")
    return text


def _expected(value: Any, field: str) -> None:
    if not isinstance(value, dict):
        raise CorpusError(f"{field} must be an object")
    expected_kind = _nonblank(value.get("kind"), f"{field}.kind")
    if expected_kind not in EXPECTED_KINDS:
        raise CorpusError(f"{field}.kind must be one of {sorted(EXPECTED_KINDS)}")
    if expected_kind == "TOOL_CALL":
        _nonblank(value.get("domain"), f"{field}.domain")
        _nonblank(value.get("action"), f"{field}.action")
    pending = value.get("pending_clarify")
    if pending is not None and pending not in PENDING_CLARIFY_OUTCOMES:
        raise CorpusError(
            f"{field}.pending_clarify must be one of {sorted(PENDING_CLARIFY_OUTCOMES)}"
        )


def validate_case(raw: Any, *, line_no: int | None = None) -> dict[str, Any]:
    where = f"line {line_no}: " if line_no is not None else ""
    try:
        if not isinstance(raw, dict):
            raise CorpusError("case must be an object")
        case_id = _nonblank(raw.get("id"), "id")
        language = _nonblank(raw.get("language"), "language")
        if language not in {"DE", "EN"}:
            raise CorpusError("language must be DE or EN")
        label_status = _nonblank(raw.get("label_status"), "label_status")
        if label_status not in LABEL_STATES:
            raise CorpusError(f"label_status must be one of {sorted(LABEL_STATES)}")

        origin = raw.get("origin")
        if not isinstance(origin, dict):
            raise CorpusError("origin must be an object")
        origin_kind = _nonblank(origin.get("kind"), "origin.kind")
        if origin_kind not in ORIGIN_KINDS:
            raise CorpusError(f"origin.kind must be one of {sorted(ORIGIN_KINDS)}")
        _nonblank(origin.get("reference"), "origin.reference")
        if not isinstance(origin.get("exact"), bool):
            raise CorpusError("origin.exact must be boolean")

        turns = raw.get("turns")
        if turns is None:
            _text(raw.get("text"), "text")
            _expected(raw.get("expected"), "expected")
        else:
            if "text" in raw or "expected" in raw:
                raise CorpusError("a sequence uses turns, never top-level text/expected")
            if not isinstance(turns, list) or not 2 <= len(turns) <= 5:
                raise CorpusError("turns must contain between 2 and 5 turn objects")
            for index, turn in enumerate(turns, 1):
                if not isinstance(turn, dict):
                    raise CorpusError(f"turns[{index}] must be an object")
                _text(turn.get("text"), f"turns[{index}].text")
                _expected(turn.get("expected"), f"turns[{index}].expected")

        mutation = raw.get("mutation")
        if mutation is not None:
            if turns is not None:
                raise CorpusError("sequence cases cannot be automatic mutations")
            if not isinstance(mutation, dict):
                raise CorpusError("mutation must be an object")
            mutation_kind = _nonblank(mutation.get("kind"), "mutation.kind")
            if mutation_kind not in MUTATION_KINDS:
                raise CorpusError(f"mutation.kind must be one of {sorted(MUTATION_KINDS)}")
            _nonblank(mutation.get("parent_id"), "mutation.parent_id")

        return raw
    except CorpusError as exc:
        raise CorpusError(where + str(exc)) from exc


def case_turns(case: dict[str, Any]) -> list[dict[str, Any]]:
    """Return one normalized list of turns without mutating the corpus row."""
    if "turns" in case:
        return list(case["turns"])
    return [{"text": case["text"], "expected": case["expected"]}]


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    seen: set[str] = set()
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as exc:
                raise CorpusError(f"line {line_no}: invalid JSON: {exc.msg}") from exc
            case = validate_case(raw, line_no=line_no)
            if case["id"] in seen:
                raise CorpusError(f"line {line_no}: duplicate id {case['id']!r}")
            seen.add(case["id"])
            cases.append(case)
    if not cases:
        raise CorpusError("corpus is empty")
    return cases


def write_cases(path: Path, cases: Iterable[dict[str, Any]]) -> None:
    checked = [validate_case(case) for case in cases]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for case in checked:
            handle.write(json.dumps(case, ensure_ascii=False, sort_keys=True) + "\n")
