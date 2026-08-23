from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import collect
import mutate
import run_replay
from schema import CorpusError, case_turns, load_cases


class SchemaTest(unittest.TestCase):
    def test_draft_corpus_is_valid_and_unique(self) -> None:
        cases = load_cases(HERE / "corpus" / "draft-v1.jsonl")
        self.assertEqual(13, len(cases))
        # Andi-Review 21.08.: alle 12 DRAFT-Labels APPROVED (homophone-01 -> TOOL_CALL).
        self.assertEqual(0, sum(case["label_status"] == "DRAFT" for case in cases))
        self.assertEqual(13, sum(case["label_status"] == "APPROVED" for case in cases))
        documented = [case for case in cases if case["origin"]["kind"] == "documented_summary"]
        self.assertEqual(4, len(documented))
        self.assertTrue(all(case["origin"]["exact"] is False for case in documented))
        clarify = cases[-1]
        self.assertEqual(2, len(case_turns(clarify)))
        self.assertEqual("asked", case_turns(clarify)[0]["expected"]["pending_clarify"])
        self.assertEqual("resolved", case_turns(clarify)[1]["expected"]["pending_clarify"])

    def test_duplicate_ids_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.jsonl"
            case = load_cases(HERE / "corpus" / "draft-v1.jsonl")[0]
            path.write_text(json.dumps(case) + "\n" + json.dumps(case) + "\n", encoding="utf-8")
            with self.assertRaises(CorpusError):
                load_cases(path)


class CollectorTest(unittest.TestCase):
    def test_current_diary_shape_yields_no_fabricated_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            diary = Path(tmp) / "turn-diary-2026-08-11.jsonl"
            diary.write_text(json.dumps({"ts": "2026-08-11T21:07:03Z", "chatId": "private", "category": "FACT_SHORT"}) + "\n", encoding="utf-8")
            candidates, stats = collect.collect(diary, text_key="transcript")
            self.assertEqual([], candidates)
            self.assertEqual(1, stats["without_text"])

    def test_candidate_keeps_only_command_text_and_hashes_source(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            diary = Path(tmp) / "turn-diary-2026-08-11.jsonl"
            rows = [
                {"ts": "2026-08-11T21:07:03Z", "chatId": "private-id", "transcript": "Schalte das Licht im Flur ein."},
                {"ts": "2026-08-11T21:08:03Z", "chatId": "private-id-2", "transcript": "Mein Name ist Beispiel."},
            ]
            diary.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            candidates, stats = collect.collect(diary, text_key="transcript")
            self.assertEqual(1, len(candidates))
            self.assertNotIn("private-id", json.dumps(candidates))
            self.assertTrue(candidates[0]["source_ref"].startswith("sha256:"))
            self.assertEqual(1, stats["rejected"])


class MutationTest(unittest.TestCase):
    def test_mutations_are_draft_and_homophone_asks_back(self) -> None:
        case = load_cases(HERE / "corpus" / "draft-v1.jsonl")[2]
        onset = mutate.mutate_case(case, "onset_drop")
        homophone = mutate.mutate_case(case, "homophone")
        self.assertEqual("Chalte das Licht im Flur ein.", onset["text"])
        self.assertEqual("DRAFT", onset["label_status"])
        self.assertEqual({"kind": "CLARIFY"}, homophone["expected"])


class ClaimEvaluationTest(unittest.TestCase):
    def test_false_claim_requires_explicit_no_tool_evidence(self) -> None:
        case = load_cases(HERE / "corpus" / "draft-v1.jsonl")[1]
        turn = {"answer": "Das Flurlicht ist an.", "category": "FACT_SHORT"}
        unknown = run_replay.evaluate(case, turn, {"targetAreaId": None})
        false = run_replay.evaluate(case, turn, {"toolCallRan": False})
        self.assertEqual("INCONCLUSIVE", unknown["verdict"])
        self.assertEqual("FALSE_EXECUTION_CLAIM", false["verdict"])
        self.assertTrue(false["false_execution_claim"])

    def test_claim_gate_is_counted_as_prevented(self) -> None:
        case = load_cases(HERE / "corpus" / "draft-v1.jsonl")[1]
        turn = {"answer": "Das habe ich nicht sicher als Schaltbefehl verstanden.", "category": "FACT_SHORT"}
        result = run_replay.evaluate(case, turn, {"claimGateFired": True})
        self.assertEqual("PREVENTED_FALSE_CLAIM", result["verdict"])

    def test_target_area_and_resolved_clarify_do_not_pretend_executor_ran(self) -> None:
        case = load_cases(HERE / "corpus" / "draft-v1.jsonl")[-1]
        turn = {"answer": "Das Licht ist an.", "category": "SMART_HOME"}
        row = {"targetAreaId": "wohnzimmer", "pendingClarify": "resolved"}
        result = run_replay.evaluate(
            case,
            turn,
            row,
            expected=case_turns(case)[1]["expected"],
            step=2,
        )
        self.assertEqual("UNKNOWN", result["tool_evidence"])
        self.assertEqual("INCONCLUSIVE", result["verdict"])
        self.assertTrue(result["pending_clarify_match"])


class FakeReplayHandler(BaseHTTPRequestHandler):
    rows: list[dict] = []

    def log_message(self, *_args) -> None:
        pass

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
        chat_id = body["chatId"]
        if body["text"] == "Mach das Licht an":
            row = {"chatId": chat_id, "toolCallRan": False, "pendingClarify": "asked"}
            events = [
                {"event": "start", "category": "SMART_HOME"},
                {"event": "delta", "text": "Welchen Raum meinst du?"},
                {"event": "done", "pendingClarify": "asked"},
            ]
        elif body["text"] == "Wohnzimmer":
            row = {"chatId": chat_id, "toolCallRan": True, "pendingClarify": "resolved"}
            events = [
                {"event": "start", "category": "SMART_HOME"},
                {"event": "delta", "text": "Das Licht ist eingeschaltet."},
                {"event": "done", "pendingClarify": "resolved"},
            ]
        else:
            row = {"chatId": chat_id, "toolCallRan": False, "claimGateFired": True}
            events = [
                {"event": "start", "category": "FACT_SHORT"},
                {"event": "delta", "text": "Das habe ich nicht sicher als Schaltbefehl verstanden."},
                {"event": "done", "claimGateFired": True},
            ]
        self.rows.append(row)
        payload = "".join("data:" + json.dumps(event) + "\n\n" for event in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        payload = json.dumps(list(reversed(self.rows))).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class FakeReplayIntegrationTest(unittest.TestCase):
    def test_chat_and_diary_are_crossed(self) -> None:
        FakeReplayHandler.rows = []
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeReplayHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        previous = os.environ.get("HOSHI_API_TOKEN")
        os.environ["HOSHI_API_TOKEN"] = "test-token"
        try:
            args = argparse.Namespace(
                execute=True,
                acknowledge_actions=True,
                base_url=f"http://127.0.0.1:{server.server_port}",
                allow_non_loopback=False,
                insecure=False,
                diary_timeout=1.0,
                dataset=HERE / "corpus" / "draft-v1.jsonl",
            )
            case = load_cases(args.dataset)[:1]
            report, code = run_replay.run(args, case)
            self.assertEqual(0, code)
            self.assertEqual(1, report["prevented_false_claims"])
            self.assertEqual(0, report["false_execution_claims"])
        finally:
            server.shutdown()
            server.server_close()
            if previous is None:
                os.environ.pop("HOSHI_API_TOKEN", None)
            else:
                os.environ["HOSHI_API_TOKEN"] = previous

    def test_two_turn_clarify_reuses_chat_and_checks_both_diary_rows(self) -> None:
        FakeReplayHandler.rows = []
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeReplayHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        previous = os.environ.get("HOSHI_API_TOKEN")
        os.environ["HOSHI_API_TOKEN"] = "test-token"
        try:
            args = argparse.Namespace(
                execute=True,
                acknowledge_actions=True,
                base_url=f"http://127.0.0.1:{server.server_port}",
                allow_non_loopback=False,
                insecure=False,
                diary_timeout=1.0,
                dataset=HERE / "corpus" / "draft-v1.jsonl",
            )
            case = load_cases(args.dataset)[-1:]
            report, code = run_replay.run(args, case)
            self.assertEqual(0, code)
            self.assertEqual(2, report["turns"])
            self.assertEqual(0, report["diary_expectation_mismatches"])
            self.assertEqual(["asked", "resolved"], [r["pending_clarify_observed"] for r in report["results"]])
            self.assertEqual(1, len({row["chatId"] for row in FakeReplayHandler.rows}))
        finally:
            server.shutdown()
            server.server_close()
            if previous is None:
                os.environ.pop("HOSHI_API_TOKEN", None)
            else:
                os.environ["HOSHI_API_TOKEN"] = previous


if __name__ == "__main__":
    unittest.main()
