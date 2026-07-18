#!/usr/bin/env python3
from __future__ import annotations

import csv
import contextlib
import importlib.util
import io
import json
import math
import stat
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("calibrate_pair.py")
SPEC = importlib.util.spec_from_file_location("calibrate_pair", MODULE_PATH)
assert SPEC and SPEC.loader
cal = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = cal
SPEC.loader.exec_module(cal)


def calibrate(rows):
    return cal.calibrate(rows, cal.SELFTEST_CONFIG)


class SpeakerPairDecisionTest(unittest.TestCase):
    def test_decision_matches_two_profile_kotlin_rule(self) -> None:
        candidate = cal.Candidate(0.45, 0.10)
        self.assertEqual("a", cal.decide(0.70, 0.55, candidate))
        self.assertEqual("b", cal.decide(0.40, 0.60, candidate))
        self.assertEqual("guest", cal.decide(0.44, 0.10, candidate))
        exact = cal.Candidate(0.45, 0.125)  # binaer exakt darstellbar, kein Dezimal-Rundungsartefakt
        self.assertEqual("a", cal.decide(0.625, 0.50, exact), "Margin == delta bindet wie Kotlin (< verwirft)")

    def test_exact_tie_is_always_guest(self) -> None:
        for candidate in cal.CANDIDATES:
            self.assertEqual("guest", cal.decide(0.617, 0.617, candidate))
        self.assertEqual("guest", cal.decide(0.617, 0.617, cal.Candidate(0.45, 0.0)))

    def test_train_selection_is_deterministic_and_conservative_on_tie(self) -> None:
        selected, evaluations = cal.select_on_train(cal._fixture_rows()[:6])
        self.assertEqual(cal.Candidate(0.55, 0.05), selected)
        self.assertEqual(len(cal.CANDIDATES), len(evaluations))

    def test_holdout_cannot_change_train_selection(self) -> None:
        fixture = cal._fixture_rows()
        train = [row for row in fixture if row.split == "train"]
        holdout = [row for row in fixture if row.split == "holdout"]
        first, _ = cal.select_on_train(train)
        hostile_holdout = [
            replace(row, score_a=row.score_b, score_b=row.score_a)
            for row in holdout
        ]
        # Die Auswahl-API akzeptiert absichtlich nur train; zwei komplett andere
        # Holdouts koennen deshalb den Betriebspunkt nicht beeinflussen.
        second, _ = cal.select_on_train(train)
        self.assertNotEqual(holdout, hostile_holdout)
        self.assertEqual(first, second)


class ManifestValidationTest(unittest.TestCase):
    def _write(self, rows: list[dict[str, str]], headers: tuple[str, ...] = cal.FIELDS) -> Path:
        tmp = tempfile.NamedTemporaryFile(prefix="speaker-pair-", suffix=".tsv", delete=False)
        tmp.close()
        path = Path(tmp.name)
        with path.open("w", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=headers, delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    @staticmethod
    def _row(**changes: str) -> dict[str, str]:
        row = {
            "probe_id": "p001",
            "group_id": "g001",
            "split": "train",
            "truth": "a",
            "channel": "browser",
            "duration_s": "1.5",
            "quality": "clean",
            "score_best_a": "0.61",
            "score_best_b": "0.42",
        }
        row.update(changes)
        return row

    def test_valid_manifest_loads(self) -> None:
        rows = cal.load_manifest(self._write([self._row()]))
        self.assertEqual(1, len(rows))
        self.assertEqual("a", rows[0].truth)

    def test_group_must_not_cross_train_holdout(self) -> None:
        path = self._write([
            self._row(probe_id="p001", group_id="g777", split="train"),
            self._row(probe_id="p002", group_id="g777", split="holdout"),
        ])
        with self.assertRaisesRegex(cal.ValidationError, "Leakage"):
            cal.load_manifest(path)

    def test_duplicate_probe_id_is_rejected(self) -> None:
        path = self._write([self._row(), self._row(group_id="g002")])
        with self.assertRaisesRegex(cal.ValidationError, "doppelt"):
            cal.load_manifest(path)

    def test_sensitive_extra_column_is_rejected(self) -> None:
        headers = cal.FIELDS + ("wav_path",)
        row = self._row()
        row["wav_path"] = "/private/voice.wav"
        path = self._write([row], headers=headers)
        with self.assertRaisesRegex(cal.ValidationError, "exakt"):
            cal.load_manifest(path)

    def test_nonfinite_and_out_of_range_values_are_rejected(self) -> None:
        invalid = (
            {"duration_s": "nan"},
            {"duration_s": "0"},
            {"score_best_a": "inf"},
            {"score_best_b": "1.01"},
        )
        for index, changes in enumerate(invalid):
            with self.subTest(changes=changes):
                path = self._write([self._row(probe_id=f"p{index:03d}", **changes)])
                with self.assertRaises(cal.ValidationError):
                    cal.load_manifest(path)

    def test_unknown_enums_and_nonopaque_ids_are_rejected(self) -> None:
        invalid = (
            {"truth": "Andi"},
            {"channel": "macbook"},
            {"split": "test"},
            {"probe_id": "andi001"},
            {"group_id": "cindy001"},
            {"quality": "cindy"},
        )
        for index, changes in enumerate(invalid):
            with self.subTest(changes=changes):
                values = {"group_id": f"g{index:03d}"}
                values.update(changes)
                path = self._write([self._row(**values)])
                with self.assertRaises(cal.ValidationError):
                    cal.load_manifest(path)

    def test_missing_and_ragged_cells_are_controlled_validation_errors(self) -> None:
        missing = self._write([self._row(duration_s=None)])
        with self.assertRaises(cal.ValidationError):
            cal.load_manifest(missing)
        ragged_row = self._row()
        ragged_row.pop("score_best_b")
        ragged = self._write([ragged_row])
        with self.assertRaises(cal.ValidationError):
            cal.load_manifest(ragged)


class CalibrationGateTest(unittest.TestCase):
    def test_complete_fixture_reaches_owner_gate(self) -> None:
        report = calibrate(cal._fixture_rows())
        self.assertTrue(report["ready_for_owner_gate"])
        self.assertEqual({"tau": 0.55, "delta": 0.05}, report["selected"]["candidate"])
        self.assertGreaterEqual(len(report["selected"]["gain_groups"]), 2)
        self.assertEqual([], report["selected"]["loss_groups"])

    def test_missing_guest_or_channel_prevents_ready(self) -> None:
        rows = [
            row for row in cal._fixture_rows()
            if not (row.split == "holdout" and row.truth == "guest" and row.channel == "satellite")
        ]
        report = calibrate(rows)
        self.assertFalse(report["ready_for_owner_gate"])
        self.assertIn("holdout:guest:satellite", report["coverage_gaps"])

    def test_empty_train_has_no_selected_candidate(self) -> None:
        rows = [replace(row, split="holdout") for row in cal._fixture_rows() if row.split == "holdout"]
        report = calibrate(rows)
        self.assertIsNone(report["selected"])
        self.assertFalse(report["ready_for_owner_gate"])

    def test_any_holdout_cross_bind_prevents_ready(self) -> None:
        rows = cal._fixture_rows()
        changed = []
        for row in rows:
            if row.split == "holdout" and row.truth == "a" and row.channel == "browser":
                row = replace(row, score_a=0.40, score_b=0.70)
            changed.append(row)
        report = calibrate(changed)
        self.assertFalse(report["ready_for_owner_gate"])
        self.assertGreater(report["selected"]["holdout"]["counts"]["cross_bind"], 0)

    def test_no_claim_when_only_one_group_improves(self) -> None:
        rows = cal._fixture_rows()
        changed = []
        kept_gain = False
        for row in rows:
            if row.split == "holdout" and row.truth in ("a", "b"):
                if not kept_gain:
                    kept_gain = True
                else:
                    # Baseline und Kandidat binden beide korrekt; kein zusaetzlicher Gain.
                    if row.truth == "a":
                        row = replace(row, score_a=0.75, score_b=0.55)
                    else:
                        row = replace(row, score_a=0.55, score_b=0.75)
            changed.append(row)
        report = calibrate(changed)
        self.assertFalse(report["ready_for_owner_gate"])
        self.assertEqual(1, len(report["selected"]["gain_groups"]))

    def test_zero_error_bound_is_not_zero(self) -> None:
        self.assertAlmostEqual(1.0 - math.pow(0.05, 1 / 38), cal.zero_error_upper95(38))
        self.assertGreater(cal.zero_error_upper95(38), 0.07)
        self.assertIsNone(cal.zero_error_upper95(0))

    def test_report_target_inside_repo_is_rejected(self) -> None:
        with self.assertRaisesRegex(cal.ValidationError, "im Repo"):
            cal.write_report(calibrate(cal._fixture_rows()), cal.REPO_ROOT / "forbidden-report")

    def test_existing_report_target_is_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory(prefix="speaker-pair-report-") as tmp:
            target = Path(tmp) / "already-there"
            target.mkdir()
            with self.assertRaisesRegex(cal.ValidationError, "kein Ueberschreiben"):
                cal.write_report(calibrate(cal._fixture_rows()), target)

    def test_rendered_report_contains_no_raw_contract_fields(self) -> None:
        text = cal.render_markdown(calibrate(cal._fixture_rows()))
        self.assertNotIn("wav_path", text)
        self.assertNotIn("embedding", text.lower())
        self.assertIn("NICHT 0 %", text.upper())

    def test_cli_roundtrip_hashes_manifest_and_uses_private_modes(self) -> None:
        with tempfile.TemporaryDirectory(prefix="speaker-pair-cli-") as tmp:
            root = Path(tmp)
            manifest = root / "scores.tsv"
            with manifest.open("w", newline="", encoding="utf-8") as fh:
                writer = csv.DictWriter(fh, fieldnames=cal.FIELDS, delimiter="\t")
                writer.writeheader()
                for row in cal._fixture_rows():
                    writer.writerow({
                        "probe_id": row.probe_id,
                        "group_id": row.group_id,
                        "split": row.split,
                        "truth": row.truth,
                        "channel": row.channel,
                        "duration_s": row.duration_s,
                        "quality": row.quality,
                        "score_best_a": row.score_a,
                        "score_best_b": row.score_b,
                    })
            out_dir = root / "out"
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                exit_code = cal.main([
                    "--input", str(manifest),
                    "--out-dir", str(out_dir),
                    "--active-aggregation", "best-sample",
                    "--active-tau", "0.45",
                    "--active-delta", "0.10",
                    "--config-evidence-sha256", "a" * 64,
                ])
            self.assertEqual(0, exit_code)
            payload = json.loads((out_dir / "calibration-report.json").read_text(encoding="utf-8"))
            self.assertTrue(payload["ready_for_owner_gate"])
            self.assertRegex(payload["manifest_sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual("a" * 64, payload["active_config"]["evidence_sha256"])
            self.assertEqual(0o700, stat.S_IMODE(out_dir.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((out_dir / "report.md").stat().st_mode))

    def test_active_config_mismatch_is_release_blocker(self) -> None:
        mismatches = (
            cal.ActiveConfig("centroid", 0.45, 0.10, "a" * 64),
            cal.ActiveConfig("best-sample", 0.80, 0.10, "a" * 64),
            cal.ActiveConfig("best-sample", 0.45, 0.05, "a" * 64),
        )
        for config in mismatches:
            with self.subTest(config=config), self.assertRaises(cal.ValidationError):
                cal.calibrate(cal._fixture_rows(), config)

    def test_changed_holdout_changes_result_but_never_selected_candidate(self) -> None:
        original = cal._fixture_rows()
        hostile = [
            replace(row, score_a=row.score_b, score_b=row.score_a)
            if row.split == "holdout" and row.truth in ("a", "b") else row
            for row in original
        ]
        first = calibrate(original)
        second = calibrate(hostile)
        self.assertEqual(first["selected"]["candidate"], second["selected"]["candidate"])
        self.assertNotEqual(
            first["selected"]["holdout"]["counts"],
            second["selected"]["holdout"]["counts"],
        )


if __name__ == "__main__":
    unittest.main()
