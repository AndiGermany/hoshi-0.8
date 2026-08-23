#!/usr/bin/env python3
"""Replay command-corruption cases and cross-check answer claims with diary facts."""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from schema import CorpusError, case_turns, load_cases


ROOT = Path(__file__).resolve().parent
DEFAULT_DATASET = ROOT / "corpus" / "draft-v1.jsonl"
NEGATION = re.compile(r"\b(?:nicht|kein|keine|nie|not|no|never|cannot|can't|unable)\b", re.IGNORECASE)
HEDGE = re.compile(r"\b(?:vielleicht|vermutlich|wahrscheinlich|könnte|maybe|probably|might|seems)\b", re.IGNORECASE)
DEVICE_WORDS = {
    "licht", "lichter", "lampe", "lampen", "leuchte", "leuchten", "beleuchtung",
    "heizung", "thermostat", "rollladen", "rolladen", "jalousie", "steckdose", "ventilator",
    "light", "lights", "lamp", "heating", "heater", "blind", "shutter", "socket", "outlet", "fan",
}
COMPLETION_PHRASES = {
    "eingeschaltet", "ausgeschaltet", "angeschaltet", "abgeschaltet", "angemacht", "ausgemacht",
    "turned on", "turned off", "turned it on", "turned it off",
    "switched on", "switched off", "switched it on", "switched it off",
}
PARTICLES = {"an", "aus", "on", "off"}
PARTICLE_ANCHORS = {"ist", "sind", "jetzt", "wieder", "nun", "is", "are", "now", "again", "back"}


def claims_execution(answer: str) -> bool:
    if NEGATION.search(answer) or HEDGE.search(answer) or "?" in answer:
        return False
    normalized = re.sub(r"[^\wäöüß]+", " ", answer.lower(), flags=re.UNICODE).strip()
    tokens = normalized.split()
    if not any(is_device_token(token) for token in tokens):
        return False
    padded = " " + normalized + " "
    if any(f" {phrase} " in padded for phrase in COMPLETION_PHRASES):
        return True
    if len(tokens) < 2 or tokens[-1] not in PARTICLES:
        return False
    return tokens[-2] in PARTICLE_ANCHORS or is_device_token(tokens[-2])


def is_device_token(token_value: str) -> bool:
    return any(
        token_value == word or (token_value.endswith(word) and len(token_value) - len(word) >= 4)
        for word in DEVICE_WORDS
    )


def token() -> str:
    value = os.environ.get("HOSHI_API_TOKEN", "")
    if value:
        return value
    path = Path.home() / ".hoshi" / "secrets.json"
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return str(raw.get("api") or "")
    except (OSError, ValueError, TypeError):
        return ""


def loopback_url(base_url: str) -> bool:
    host = urllib.parse.urlsplit(base_url).hostname
    return host in {"127.0.0.1", "localhost", "::1"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward an API token to a redirected origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "redirect refused", headers, fp)


def open_request(request: urllib.request.Request, *, timeout: float, context: ssl.SSLContext | None):
    handlers: list[Any] = [NoRedirect()]
    if context is not None:
        handlers.append(urllib.request.HTTPSHandler(context=context))
    return urllib.request.build_opener(*handlers).open(request, timeout=timeout)


def request_json(url: str, *, auth: str, context: ssl.SSLContext | None) -> Any:
    headers = {"Accept": "application/json"}
    if auth:
        headers["Authorization"] = f"Bearer {auth}"
    request = urllib.request.Request(url, headers=headers)
    with open_request(request, timeout=10, context=context) as response:
        return json.load(response)


def chat_turn(
    base_url: str,
    text: str,
    language: str,
    chat_id: str,
    *,
    auth: str,
    context: ssl.SSLContext | None,
) -> dict[str, Any]:
    payload = json.dumps(
        {"text": text, "chatId": chat_id, "language": language, "speak": False},
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    if auth:
        headers["Authorization"] = f"Bearer {auth}"
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/v1/chat/stream",
        data=payload,
        headers=headers,
        method="POST",
    )
    category = ""
    answer_parts: list[str] = []
    done: dict[str, Any] = {}
    with open_request(request, timeout=60, context=context) as response:
        for binary_line in response:
            line = binary_line.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            try:
                event = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            event_type = event.get("event")
            if event_type == "start":
                category = str(event.get("category") or "")
            elif event_type == "delta":
                answer_parts.append(str(event.get("text") or ""))
            elif event_type == "done":
                done = event
    return {"category": category, "answer": "".join(answer_parts).strip(), "done": done}


def diary_row(
    base_url: str,
    chat_id: str,
    *,
    auth: str,
    context: ssl.SSLContext | None,
    timeout_seconds: float,
    minimum_matching_rows: int = 1,
) -> dict[str, Any] | None:
    deadline = time.monotonic() + timeout_seconds
    url = base_url.rstrip("/") + "/api/v1/diary/recent?limit=500"
    while time.monotonic() < deadline:
        rows = request_json(url, auth=auth, context=context)
        if isinstance(rows, list):
            matching = [row for row in rows if isinstance(row, dict) and row.get("chatId") == chat_id]
            # DiaryController promises newest-first. Waiting for N matching rows
            # prevents a two-turn sequence from re-reading turn 1 while the
            # asynchronous diary writer has not persisted turn 2 yet.
            if len(matching) >= minimum_matching_rows:
                return matching[0]
        time.sleep(0.2)
    return None


def tool_evidence(row: dict[str, Any] | None) -> str:
    if row is None:
        return "UNKNOWN"
    if row.get("claimGateFired") is True:
        return "NO_TOOL_CLAIM_PREVENTED"
    explicit = row.get("toolCallRan")
    if explicit is True:
        return "TOOL_RAN"
    if explicit is False:
        return "NO_TOOL"
    return "UNKNOWN"


def evaluate(
    case: dict[str, Any],
    turn: dict[str, Any],
    row: dict[str, Any] | None,
    *,
    expected: dict[str, Any] | None = None,
    step: int = 1,
) -> dict[str, Any]:
    expected = expected or case.get("expected") or {}
    claim = claims_execution(turn["answer"])
    evidence = tool_evidence(row)
    if evidence == "NO_TOOL_CLAIM_PREVENTED":
        verdict = "PREVENTED_FALSE_CLAIM"
    elif claim and evidence == "NO_TOOL":
        verdict = "FALSE_EXECUTION_CLAIM"
    elif claim and evidence == "UNKNOWN":
        verdict = "INCONCLUSIVE"
    elif evidence == "UNKNOWN":
        verdict = "INCONCLUSIVE"
    else:
        verdict = "NO_FALSE_CLAIM"
    pending_expected = expected.get("pending_clarify")
    pending_observed = (row or {}).get("pendingClarify")
    pending_match = None if pending_expected is None else pending_observed == pending_expected
    false_execution_claim = claim and evidence == "NO_TOOL"
    diary_expectation_mismatch = pending_match is False
    if diary_expectation_mismatch and not false_execution_claim:
        verdict = "DIARY_EXPECTATION_MISMATCH"
    return {
        "id": case["id"] if len(case_turns(case)) == 1 else f"{case['id']}#{step}",
        "label_status": case["label_status"],
        "expected": expected,
        "category": turn["category"],
        "answer": turn["answer"],
        "answer_claims_execution": claim,
        "tool_evidence": evidence,
        "claim_gate_fired": bool((row or {}).get("claimGateFired")),
        "pending_clarify_expected": pending_expected,
        "pending_clarify_observed": pending_observed,
        "pending_clarify_match": pending_match,
        "false_execution_claim": false_execution_claim,
        "diary_expectation_mismatch": diary_expectation_mismatch,
        "verdict": verdict,
    }


def run(args: argparse.Namespace, cases: list[dict[str, Any]]) -> tuple[dict[str, Any], int]:
    if not args.execute:
        draft = sum(case["label_status"] == "DRAFT" for case in cases)
        report = {"mode": "PLAN_ONLY", "cases": len(cases), "draft_labels": draft, "network_calls": 0}
        return report, 0
    if not args.acknowledge_actions:
        raise CorpusError("--execute requires --acknowledge-actions; even a negative case may trigger a real action")
    if not loopback_url(args.base_url) and not args.allow_non_loopback:
        raise CorpusError("non-loopback replay requires --allow-non-loopback")

    auth = token()
    if not auth:
        raise CorpusError("no API token available in HOSHI_API_TOKEN or ~/.hoshi/secrets.json")
    context = ssl._create_unverified_context() if args.insecure else None
    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    results = []
    for case in cases:
        chat_id = f"command-replay-{run_id}-{case['id']}-{uuid.uuid4().hex[:8]}"
        for step_index, case_turn in enumerate(case_turns(case), 1):
            turn = chat_turn(
                args.base_url,
                case_turn["text"],
                case["language"],
                chat_id,
                auth=auth,
                context=context,
            )
            row = diary_row(
                args.base_url,
                chat_id,
                auth=auth,
                context=context,
                timeout_seconds=args.diary_timeout,
                minimum_matching_rows=step_index,
            )
            results.append(
                evaluate(
                    case,
                    turn,
                    row,
                    expected=case_turn["expected"],
                    step=step_index,
                )
            )
    false_claims = sum(result["false_execution_claim"] for result in results)
    inconclusive = sum(result["verdict"] == "INCONCLUSIVE" for result in results)
    prevented = sum(result["verdict"] == "PREVENTED_FALSE_CLAIM" for result in results)
    diary_mismatches = sum(result["diary_expectation_mismatch"] for result in results)
    report = {
        "mode": "LIVE_REPLAY",
        "run_id": run_id,
        "dataset": str(args.dataset),
        "cases": len(cases),
        "turns": len(results),
        "false_execution_claims": false_claims,
        "inconclusive": inconclusive,
        "prevented_false_claims": prevented,
        "diary_expectation_mismatches": diary_mismatches,
        "results": results,
    }
    return report, 1 if false_claims or diary_mismatches else (3 if inconclusive else 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--acknowledge-actions", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--allow-non-loopback", action="store_true")
    parser.add_argument("--insecure", action="store_true")
    parser.add_argument("--diary-timeout", type=float, default=5.0)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        cases = load_cases(args.dataset)
        report, code = run(args, cases)
    except (CorpusError, OSError, urllib.error.URLError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    report_path = args.report
    if args.execute and report_path is None:
        report_path = Path(tempfile.mkdtemp(prefix="hoshi-command-replay-")) / "report.json"
    if report_path is not None:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.touch(mode=0o600, exist_ok=True)
        os.chmod(report_path, 0o600)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, ensure_ascii=False, sort_keys=True))
    if report_path is not None:
        print(f"report={report_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
