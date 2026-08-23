# SPDX-License-Identifier: Apache-2.0
"""Adversariale N0-Vertragstests; rein synthetisch, ohne Netz oder Runtime."""

from __future__ import annotations

import argparse
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import collect  # noqa: E402
import evaluate  # noqa: E402
import generate_synthetic  # noqa: E402
import schema  # noqa: E402
import verify_freeze  # noqa: E402


STAMP_LABEL = "2027-01-01T20:00:00Z"
STAMP_REVIEW = "2027-01-01T20:01:00Z"


class MemoryBenchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "intake"
        self.dataset = "nagori-test"

    def tearDown(self):
        self.temporary.cleanup()

    def generate(self) -> Path:
        return generate_synthetic.generate_dataset(self.dataset, self.root, 12, 6, 20260811)

    def load(self, directory: Path | None = None):
        return collect.load_intake(directory or (self.root / self.dataset))

    def fully_label(self, directory: Path | None = None) -> None:
        directory = directory or (self.root / self.dataset)
        intake, scenarios, queries, scenario_map, episode_map = collect.load_intake(directory)
        del intake
        audit = schema.read_jsonl(directory / "audit.jsonl")
        for row in queries:
            position = int(row["queryId"].rsplit("-", 1)[1])
            prefix = row["scenarioId"].replace("scenario-", "")
            ids = lambda number: f"episode-{prefix}-{number:02d}"
            acceptable: list[str] = []
            stale: list[str] = []
            foreign: list[str] = []
            if position == 1:
                acceptable, stale = [ids(7)], [ids(1)]
            elif position == 2:
                acceptable = [ids(3)]
            elif position == 3:
                acceptable, stale = [ids(8)], [ids(4)]
            elif position == 4:
                acceptable = [ids(6)]
            elif position == 5:
                acceptable = [ids(10)]
            elif position == 6:
                acceptable = [ids(5)]
            elif position == 8:
                foreign = [ids(2)]
            answerable = bool(acceptable)
            row["state"] = "reviewed"
            row["label"] = {
                "answerable": answerable,
                "acceptableEpisodeIds": acceptable,
                "forbiddenStaleEpisodeIds": stale,
                "forbiddenForeignEpisodeIds": foreign,
                "labeledAt": STAMP_LABEL,
                "labelSource": "human",
            }
            row["reviewedAt"] = STAMP_REVIEW
            row["revision"] = 3
            schema.validate_query(row, scenario_map, episode_map, row["queryId"])
            audit.extend(
                [
                    {
                        "event": "human-label",
                        "at": STAMP_LABEL,
                        "queryId": row["queryId"],
                        "revision": 2,
                        "answerable": answerable,
                        "acceptableCount": len(acceptable),
                        "staleCount": len(stale),
                        "foreignCount": len(foreign),
                    },
                    {
                        "event": "human-review",
                        "at": STAMP_REVIEW,
                        "queryId": row["queryId"],
                        "revision": 3,
                        "privacyAcknowledged": False,
                    },
                ]
            )
        collect.atomic_replace_jsonl(directory / "queries.jsonl", queries)
        collect.atomic_replace_jsonl(directory / "audit.jsonl", audit)

    def seal(self, directory: Path | None = None) -> None:
        collect.command_seal(
            argparse.Namespace(
                root=self.root,
                dataset=self.dataset,
                yes=True,
                acknowledge_privacy=False,
            )
        )

    def freeze(self) -> Path:
        output = self.base / "frozen" / "nagori-v1"
        collect.command_freeze(
            argparse.Namespace(
                root=self.root,
                dataset=self.dataset,
                output_dir=output,
                yes=True,
            )
        )
        return output

    def result_rows(self, freeze: Path, split: str, *, mode: str = "perfect") -> list[dict]:
        queries = schema.read_jsonl(freeze / f"{split}.queries.jsonl")
        scenarios = schema.read_jsonl(freeze / f"{split}.scenarios.jsonl")
        _, episodes = schema.scenario_indexes(scenarios)
        rows: list[dict] = []
        for variant in ("B0", "H2"):
            for condition in ("cold", "warm"):
                for query in queries:
                    label = query["label"]
                    retrieved: list[str] = []
                    if variant == "H2" and label["answerable"]:
                        retrieved = label["acceptableEpisodeIds"][:1]
                    if variant == "H2" and mode == "fmr" and not label["answerable"]:
                        own = next(
                            episode_id
                            for episode_id, (_, episode) in episodes.items()
                            if episode["speakerId"] == query["requesterSpeakerId"]
                        )
                        retrieved = [own]
                    if variant == "H2" and mode == "foreign" and query["queryType"] == "cross_speaker":
                        retrieved = label["forbiddenForeignEpisodeIds"][:1]
                    rows.append(
                        {
                            "schemaVersion": 1,
                            "variant": variant,
                            "condition": condition,
                            "queryId": query["queryId"],
                            "retrievedEpisodeIds": retrieved,
                            "latencyMs": (30.0 if condition == "cold" else 10.0) + (1.0 if variant == "H2" else 0.0),
                        }
                    )
        return rows

    def write_results(self, freeze: Path, split: str, mode: str = "perfect") -> Path:
        path = self.base / f"results-{split}-{mode}.jsonl"
        path.write_bytes(schema.jsonl_bytes(self.result_rows(freeze, split, mode=mode)))
        return path

    # ── Schema ──────────────────────────────────────────────────────────────
    def test_scenario_rejects_unknown_fields(self):
        directory = self.generate()
        scenario = schema.read_jsonl(directory / "scenarios.jsonl")[0]
        scenario["secret"] = "no"
        with self.assertRaisesRegex(ValueError, "unbekannte Felder"):
            schema.validate_scenario(scenario, "scenario")

    def test_query_rejects_overlapping_gold_stale_and_foreign(self):
        directory = self.generate()
        _, _, queries, scenarios, episodes = self.load(directory)
        row = queries[0]
        row["state"] = "labeled"
        row["label"] = {
            "answerable": True,
            "acceptableEpisodeIds": ["episode-s01-07"],
            "forbiddenStaleEpisodeIds": ["episode-s01-07"],
            "forbiddenForeignEpisodeIds": [],
            "labeledAt": STAMP_LABEL,
            "labelSource": "human",
        }
        with self.assertRaisesRegex(ValueError, "disjunkt"):
            schema.validate_query(row, scenarios, episodes, "query")

    def test_query_rejects_foreign_episode_as_acceptable(self):
        directory = self.generate()
        _, _, queries, scenarios, episodes = self.load(directory)
        row = queries[0]
        row["state"] = "labeled"
        row["label"] = {
            "answerable": True,
            "acceptableEpisodeIds": ["episode-s01-02"],
            "forbiddenStaleEpisodeIds": [],
            "forbiddenForeignEpisodeIds": [],
            "labeledAt": STAMP_LABEL,
            "labelSource": "human",
        }
        with self.assertRaisesRegex(ValueError, "fragenden Sprecher"):
            schema.validate_query(row, scenarios, episodes, "query")

    def test_temporal_update_requires_current_and_stale_ids(self):
        directory = self.generate()
        _, _, queries, scenarios, episodes = self.load(directory)
        row = queries[2]
        row["state"] = "labeled"
        row["label"] = {
            "answerable": True,
            "acceptableEpisodeIds": ["episode-s01-08"],
            "forbiddenStaleEpisodeIds": [],
            "forbiddenForeignEpisodeIds": [],
            "labeledAt": STAMP_LABEL,
            "labelSource": "human",
        }
        with self.assertRaisesRegex(ValueError, "temporal_update"):
            schema.validate_query(row, scenarios, episodes, "query")

    def test_cross_speaker_requires_explicit_foreign_id(self):
        directory = self.generate()
        _, _, queries, scenarios, episodes = self.load(directory)
        row = queries[7]
        row["state"] = "labeled"
        row["label"] = {
            "answerable": False,
            "acceptableEpisodeIds": [],
            "forbiddenStaleEpisodeIds": [],
            "forbiddenForeignEpisodeIds": [],
            "labeledAt": STAMP_LABEL,
            "labelSource": "human",
        }
        with self.assertRaisesRegex(ValueError, "cross_speaker"):
            schema.validate_query(row, scenarios, episodes, "query")

    def test_label_cannot_reference_episode_after_query_time(self):
        directory = self.generate()
        _, _, queries, scenarios, episodes = self.load(directory)
        row = queries[1]
        row["asOf"] = "2026-01-02T00:00:00Z"
        row["state"] = "labeled"
        row["label"] = {
            "answerable": True,
            "acceptableEpisodeIds": ["episode-s01-03"],
            "forbiddenStaleEpisodeIds": [],
            "forbiddenForeignEpisodeIds": [],
            "labeledAt": STAMP_LABEL,
            "labelSource": "human",
        }
        with self.assertRaisesRegex(ValueError, "nach asOf"):
            schema.validate_query(row, scenarios, episodes, "query")

    def test_result_rejects_nan_and_unknown_variant(self):
        base = {
            "schemaVersion": 1,
            "variant": "B0",
            "condition": "warm",
            "queryId": "query-s01-01",
            "retrievedEpisodeIds": [],
            "latencyMs": float("nan"),
        }
        with self.assertRaisesRegex(ValueError, "latencyMs"):
            schema.validate_result(base, "result")
        base["latencyMs"] = 1
        base["variant"] = "H9"
        with self.assertRaisesRegex(ValueError, "variant"):
            schema.validate_result(base, "result")

    def test_result_enforces_the_top_two_contract(self):
        result = {
            "schemaVersion": 1,
            "variant": "B0",
            "condition": "warm",
            "queryId": "query-s01-01",
            "retrievedEpisodeIds": ["episode-s01-01", "episode-s01-02", "episode-s01-03"],
            "latencyMs": 1,
        }
        with self.assertRaisesRegex(ValueError, "topK=2"):
            schema.validate_result(result, "result")

    # ── Generator / human workflow ─────────────────────────────────────────
    def test_generator_emits_no_labels_and_private_permissions(self):
        directory = self.generate()
        intake, scenarios, queries, _, _ = self.load(directory)
        self.assertEqual(len(scenarios), 12)
        self.assertEqual(len(queries), 144)
        self.assertTrue(all(row["state"] == "draft" and row["label"] is None for row in queries))
        self.assertFalse(intake["generator"]["labelsGenerated"])
        self.assertFalse(intake["privacy"]["userDataRead"])
        self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((directory / "queries.jsonl").stat().st_mode), 0o600)

    def test_generator_is_deterministic_for_scenario_and_query_bytes(self):
        first = self.generate()
        second_root = self.base / "intake-two"
        second = generate_synthetic.generate_dataset("nagori-two", second_root, 12, 6, 20260811)
        self.assertEqual((first / "scenarios.jsonl").read_bytes(), (second / "scenarios.jsonl").read_bytes())
        self.assertEqual((first / "queries.jsonl").read_bytes(), (second / "queries.jsonl").read_bytes())

    def test_generator_never_overwrites_dataset(self):
        self.generate()
        with self.assertRaisesRegex(ValueError, "nie ueberschrieben"):
            self.generate()

    def test_template_families_have_distinct_phrasing_and_questions_are_unique(self):
        directory = self.generate()
        _, _, queries, _, _ = self.load(directory)
        texts = [schema.normalize_text(row["text"]) for row in queries]
        self.assertEqual(len(texts), len(set(texts)))
        first_by_family = {}
        for row in queries:
            first_by_family.setdefault(row["templateFamily"], row["text"])
        prefixes = {text.split("Testwelt", 1)[0] for text in first_by_family.values()}
        self.assertEqual(len(prefixes), 6)

    def test_label_then_review_are_separate_and_audited_without_query_text(self):
        directory = self.generate()
        collect.command_label(
            argparse.Namespace(
                root=self.root,
                dataset=self.dataset,
                query_id="query-s01-03",
                answerable="yes",
                acceptable=["episode-s01-08"],
                forbidden_stale=["episode-s01-04"],
                forbidden_foreign=None,
                yes=True,
            )
        )
        _, _, queries, _, _ = self.load(directory)
        row = next(item for item in queries if item["queryId"] == "query-s01-03")
        self.assertEqual(row["state"], "labeled")
        self.assertIsNone(row["reviewedAt"])
        collect.command_review(
            argparse.Namespace(
                root=self.root,
                dataset=self.dataset,
                query_id="query-s01-03",
                acknowledge_privacy=False,
                yes=True,
            )
        )
        _, _, queries, _, _ = self.load(directory)
        row = next(item for item in queries if item["queryId"] == "query-s01-03")
        self.assertEqual(row["state"], "reviewed")
        audit_text = (directory / "audit.jsonl").read_text(encoding="utf-8")
        self.assertNotIn(row["text"], audit_text)
        self.assertIn('"event":"human-label"', audit_text)
        self.assertIn('"event":"human-review"', audit_text)

    def test_label_and_review_require_explicit_yes(self):
        self.generate()
        with self.assertRaisesRegex(ValueError, "--yes"):
            collect.command_label(argparse.Namespace(yes=False))
        with self.assertRaisesRegex(ValueError, "--yes"):
            collect.command_review(argparse.Namespace(yes=False))

    def test_reopen_is_audited_and_requires_new_review(self):
        directory = self.generate()
        self.fully_label(directory)
        collect.command_reopen(
            argparse.Namespace(
                root=self.root,
                dataset=self.dataset,
                query_id="query-s01-01",
                reason="label-error",
                yes=True,
            )
        )
        _, _, queries, _, _ = self.load(directory)
        row = next(item for item in queries if item["queryId"] == "query-s01-01")
        self.assertEqual(row["state"], "labeled")
        self.assertIsNone(row["reviewedAt"])
        self.assertIn('"reason":"label-error"', (directory / "audit.jsonl").read_text())

    def test_seal_refuses_drafts(self):
        self.generate()
        with self.assertRaisesRegex(ValueError, "Review"):
            self.seal()

    def test_seal_binds_all_intake_files_and_blocks_mutation(self):
        directory = self.generate()
        self.fully_label(directory)
        self.seal(directory)
        seal = schema.read_json(directory / collect.SEAL_FILE)
        self.assertEqual(set(seal["files"]), {"intake.json", "scenarios.jsonl", "queries.jsonl", "audit.jsonl"})
        with self.assertRaisesRegex(ValueError, "versiegelt"):
            collect.command_review(
                argparse.Namespace(
                    root=self.root,
                    dataset=self.dataset,
                    query_id="query-s01-01",
                    acknowledge_privacy=False,
                    yes=True,
                )
            )

    def test_seal_rejects_audit_revision_mismatch(self):
        directory = self.generate()
        self.fully_label(directory)
        audit = schema.read_jsonl(directory / "audit.jsonl")
        audit[-1]["revision"] = 999
        collect.atomic_replace_jsonl(directory / "audit.jsonl", audit)
        with self.assertRaisesRegex(ValueError, "Audit-Revision"):
            self.seal(directory)

    def test_seal_rejects_source_text_tamper_even_when_privacy_scanner_is_quiet(self):
        directory = self.generate()
        self.fully_label(directory)
        queries = schema.read_jsonl(directory / "queries.jsonl")
        queries[0]["text"] = "In Testwelt S01: Eine andere harmlose Frage?"
        collect.atomic_replace_jsonl(directory / "queries.jsonl", queries)
        with self.assertRaisesRegex(ValueError, "ausserhalb des Labelvertrags"):
            self.seal(directory)

    def test_seal_rejects_unknown_audit_fields_that_could_hide_private_text(self):
        directory = self.generate()
        self.fully_label(directory)
        audit = schema.read_jsonl(directory / "audit.jsonl")
        audit[-1]["note"] = "darf nicht ins Audit"
        collect.atomic_replace_jsonl(directory / "audit.jsonl", audit)
        with self.assertRaisesRegex(ValueError, "unbekannte Felder"):
            self.seal(directory)

    def test_seal_rejects_review_without_a_label_transition(self):
        directory = self.generate()
        self.fully_label(directory)
        audit = schema.read_jsonl(directory / "audit.jsonl")
        query_id = audit[1]["queryId"]
        audit.append({
            "event": "human-review",
            "at": STAMP_REVIEW,
            "queryId": query_id,
            "revision": 4,
            "privacyAcknowledged": False,
        })
        queries = schema.read_jsonl(directory / "queries.jsonl")
        next(row for row in queries if row["queryId"] == query_id)["revision"] = 4
        collect.atomic_replace_jsonl(directory / "queries.jsonl", queries)
        collect.atomic_replace_jsonl(directory / "audit.jsonl", audit)
        with self.assertRaisesRegex(ValueError, "Audit-Reviewfolge"):
            self.seal(directory)

    def test_seal_rejects_free_text_reopen_reason_in_tampered_audit(self):
        directory = self.generate()
        self.fully_label(directory)
        audit = schema.read_jsonl(directory / "audit.jsonl")
        query_id = audit[1]["queryId"]
        audit.insert(
            3,
            {
                "event": "human-reopen",
                "at": STAMP_REVIEW,
                "queryId": query_id,
                "revision": 4,
                "reason": "private Freitextnotiz",
            },
        )
        audit.insert(
            4,
            {
                "event": "human-label",
                "at": STAMP_LABEL,
                "queryId": query_id,
                "revision": 5,
                "answerable": True,
                "acceptableCount": 1,
                "staleCount": 1,
                "foreignCount": 0,
            },
        )
        audit.insert(
            5,
            {
                "event": "human-review",
                "at": STAMP_REVIEW,
                "queryId": query_id,
                "revision": 6,
                "privacyAcknowledged": False,
            },
        )
        queries = schema.read_jsonl(directory / "queries.jsonl")
        next(row for row in queries if row["queryId"] == query_id)["revision"] = 6
        collect.atomic_replace_jsonl(directory / "queries.jsonl", queries)
        collect.atomic_replace_jsonl(directory / "audit.jsonl", audit)
        with self.assertRaisesRegex(ValueError, "Audit-Reopen"):
            self.seal(directory)

    # ── Freeze ──────────────────────────────────────────────────────────────
    def prepared_freeze(self) -> Path:
        self.generate()
        self.fully_label()
        self.seal()
        return self.freeze()

    def test_freeze_is_family_isolated_hash_bound_and_honest(self):
        freeze = self.prepared_freeze()
        result = verify_freeze.verify_all(freeze)
        self.assertEqual(result["devQueries"] + result["holdoutQueries"], 144)
        manifest = schema.read_json(freeze / "manifest.json")
        self.assertFalse(manifest["claims"]["cryptographicallyBlindHoldout"])
        self.assertEqual(stat.S_IMODE(freeze.stat().st_mode), 0o500)
        self.assertEqual(stat.S_IMODE((freeze / "manifest.json").stat().st_mode), 0o400)
        text = (freeze / "manifest.json").read_text(encoding="utf-8")
        self.assertNotIn(str(self.base), text)

    def test_freeze_never_overwrites_output(self):
        freeze = self.prepared_freeze()
        with self.assertRaisesRegex(ValueError, "existiert"):
            collect.command_freeze(
                argparse.Namespace(root=self.root, dataset=self.dataset, output_dir=freeze, yes=True)
            )

    def test_freeze_intent_cannot_be_redirected_to_a_prettier_split(self):
        freeze = self.prepared_freeze()
        other = freeze.parent / "other"
        with self.assertRaisesRegex(ValueError, "anderen Freeze"):
            collect.command_freeze(
                argparse.Namespace(root=self.root, dataset=self.dataset, output_dir=other, yes=True)
            )

    def test_stale_lock_file_after_a_crash_does_not_block_retry(self):
        directory = self.generate()
        self.fully_label(directory)
        self.seal(directory)
        (directory / collect.FREEZE_LOCK_FILE).write_text("stale", encoding="utf-8")
        freeze = self.freeze()
        self.assertTrue((freeze / "manifest.json").is_file())
        self.assertFalse((directory / collect.FREEZE_LOCK_FILE).exists())

    def test_active_freeze_lock_fails_closed_without_unlinking_the_lock(self):
        directory = self.generate()
        self.fully_label(directory)
        self.seal(directory)
        lock_path = directory / collect.FREEZE_LOCK_FILE
        lock_fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
        collect.fcntl.flock(lock_fd, collect.fcntl.LOCK_EX | collect.fcntl.LOCK_NB)
        try:
            with self.assertRaisesRegex(ValueError, "laeuft.*bereits"):
                self.freeze()
            self.assertTrue(lock_path.exists())
        finally:
            collect.fcntl.flock(lock_fd, collect.fcntl.LOCK_UN)
            os.close(lock_fd)
        freeze = self.freeze()
        self.assertTrue((freeze / "manifest.json").is_file())

    def test_freeze_verifier_detects_byte_drift(self):
        freeze = self.prepared_freeze()
        target = freeze / "dev.queries.jsonl"
        os.chmod(target, 0o600)
        target.write_bytes(target.read_bytes() + b"\n")
        os.chmod(target, 0o400)
        with self.assertRaisesRegex(ValueError, "Freeze-Drift"):
            verify_freeze.verify_all(freeze)

    def test_freeze_verifier_rejects_writable_container_or_manifest(self):
        freeze = self.prepared_freeze()
        os.chmod(freeze, 0o700)
        with self.assertRaisesRegex(ValueError, "Verzeichnis ist schreibbar"):
            verify_freeze.verify_all(freeze)
        os.chmod(freeze, 0o500)
        os.chmod(freeze / "manifest.json", 0o600)
        with self.assertRaisesRegex(ValueError, "Manifest ist schreibbar"):
            verify_freeze.verify_all(freeze)

    def test_freeze_refuses_tampered_intake_after_seal(self):
        directory = self.generate()
        self.fully_label(directory)
        self.seal(directory)
        target = directory / "queries.jsonl"
        target.write_bytes(target.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "Seal-Drift"):
            self.freeze()

    # ── Evaluation ──────────────────────────────────────────────────────────
    def test_zero_event_upper_needs_59_for_below_five_percent(self):
        self.assertGreaterEqual(evaluate.zero_event_upper(58), 0.05)
        self.assertLess(evaluate.zero_event_upper(59), 0.05)

    def test_one_sided_exact_test_counts_gains_and_losses(self):
        result = evaluate.one_sided_exact_improvement_p(
            [False] * 6 + [True],
            [True] * 6 + [False],
        )
        self.assertEqual(result["gains"], 6)
        self.assertEqual(result["losses"], 1)
        self.assertAlmostEqual(result["pValue"], 8 / 128)

    def test_perfect_candidate_passes_all_promotion_gates(self):
        freeze = self.prepared_freeze()
        results = self.write_results(freeze, "holdout", "perfect")
        output = self.base / "reports" / "perfect"
        report = evaluate.run_evaluation(freeze, "holdout", results, output)
        self.assertTrue(report["comparisons"]["H2"]["promotionPass"])
        self.assertEqual(report["variants"]["H2"]["falseMemoryRate"]["events"], 0)
        self.assertEqual(report["variants"]["H2"]["crossSpeakerLeakRate"]["events"], 0)
        self.assertEqual(report["variants"]["H2"]["latencyMs"]["cold"]["p95"], 31.0)
        self.assertEqual(report["variants"]["H2"]["latencyMs"]["warm"]["p95"], 11.0)
        self.assertTrue((output / "report.md").is_file())

    def test_fmr_regression_is_no_go_even_with_perfect_recall(self):
        freeze = self.prepared_freeze()
        results = self.write_results(freeze, "holdout", "fmr")
        report = evaluate.run_evaluation(freeze, "holdout", results, self.base / "reports" / "fmr")
        comparison = report["comparisons"]["H2"]
        self.assertFalse(comparison["promotionPass"])
        self.assertFalse(comparison["gates"]["fmrNonIncreaseVsB0"])

    def test_cross_speaker_leak_is_hard_no_go(self):
        freeze = self.prepared_freeze()
        results = self.write_results(freeze, "holdout", "foreign")
        report = evaluate.run_evaluation(freeze, "holdout", results, self.base / "reports" / "foreign")
        comparison = report["comparisons"]["H2"]
        self.assertFalse(comparison["promotionPass"])
        self.assertFalse(comparison["gates"]["crossSpeakerLeakCountMax"])

    def test_results_must_cover_every_query_for_every_variant(self):
        freeze = self.prepared_freeze()
        rows = self.result_rows(freeze, "holdout")[:-1]
        path = self.base / "incomplete.jsonl"
        path.write_bytes(schema.jsonl_bytes(rows))
        _, _, queries, episodes = evaluate.verify_freeze(freeze, "holdout")
        with self.assertRaisesRegex(ValueError, "unvollstaendiger Split"):
            evaluate.load_results(path, queries, episodes)

    def test_retrieval_must_be_identical_in_cold_and_warm_measurements(self):
        freeze = self.prepared_freeze()
        rows = self.result_rows(freeze, "holdout")
        cold = next(
            row
            for row in rows
            if row["variant"] == "H2" and row["condition"] == "cold" and row["retrievedEpisodeIds"]
        )
        cold["retrievedEpisodeIds"] = []
        path = self.base / "cold-warm-drift.jsonl"
        path.write_bytes(schema.jsonl_bytes(rows))
        _, _, queries, episodes = evaluate.verify_freeze(freeze, "holdout")
        with self.assertRaisesRegex(ValueError, "driftet zwischen cold/warm"):
            evaluate.load_results(path, queries, episodes)

    def test_result_cannot_leave_its_isolated_scenario(self):
        freeze = self.prepared_freeze()
        rows = self.result_rows(freeze, "holdout")
        candidate = next(row for row in rows if row["variant"] == "H2")
        other = next(
            row["retrievedEpisodeIds"][0]
            for row in rows
            if row["variant"] == "H2"
            and row["retrievedEpisodeIds"]
            and row["queryId"].split("-")[1] != candidate["queryId"].split("-")[1]
        )
        candidate["retrievedEpisodeIds"] = [other]
        path = self.base / "cross-scenario.jsonl"
        path.write_bytes(schema.jsonl_bytes(rows))
        _, _, queries, episodes = evaluate.verify_freeze(freeze, "holdout")
        with self.assertRaisesRegex(ValueError, "Szenario-Welt"):
            evaluate.load_results(path, queries, episodes)

    def test_public_report_contains_no_queries_or_local_paths(self):
        freeze = self.prepared_freeze()
        generated_results = self.write_results(freeze, "holdout")
        results = self.base / "local-sensitive-label.jsonl"
        results.write_bytes(generated_results.read_bytes())
        output = self.base / "reports" / "privacy"
        evaluate.run_evaluation(freeze, "holdout", results, output)
        text = (output / "report.json").read_text(encoding="utf-8")
        query_text = schema.read_jsonl(freeze / "holdout.queries.jsonl")[0]["text"]
        self.assertNotIn(query_text, text)
        self.assertNotIn(str(self.base), text)
        self.assertNotIn(results.name, text)
        self.assertIn('"n":', text)

    def test_evaluation_never_overwrites_report(self):
        freeze = self.prepared_freeze()
        results = self.write_results(freeze, "holdout")
        output = self.base / "reports" / "once"
        evaluate.run_evaluation(freeze, "holdout", results, output)
        with self.assertRaisesRegex(ValueError, "nie ueberschrieben"):
            evaluate.run_evaluation(freeze, "holdout", results, output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
