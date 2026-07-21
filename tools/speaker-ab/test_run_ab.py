# SPDX-License-Identifier: Apache-2.0
"""Paritaetstests fuer die optionalen Speaker-Aggregationen im Offline-Runner."""

import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_ab import (  # noqa: E402
    MODES,
    ONE_PROFILE_THRESHOLDS,
    Probe,
    Profile,
    enrollment_impostor_probes,
    evaluate,
    score_profile,
    select_scoring_profiles,
)


def unit(cosine_to_x):
    return [cosine_to_x, math.sqrt(1.0 - cosine_to_x * cosine_to_x)]


class TopTwoMeanScoringTest(unittest.TestCase):

    def test_top_two_mean_uses_two_highest_sample_cosines(self):
        profile = Profile(
            name="profil-a",
            embedding=unit(0.5),
            samples=[unit(0.9), unit(0.1), unit(0.7)],
        )

        score = score_profile([1.0, 0.0], profile, "top-two-mean")

        self.assertAlmostEqual(0.8, score, places=12)

    def test_single_sample_is_identical_to_best_sample(self):
        profile = Profile(name="profil-a", embedding=unit(0.77), samples=[unit(0.77)])

        best = score_profile([1.0, 0.0], profile, "best-sample")
        top_two = score_profile([1.0, 0.0], profile, "top-two-mean")

        self.assertAlmostEqual(best, top_two, places=12)

    def test_candidate_is_part_of_the_fixed_ab_modes(self):
        self.assertEqual(("best-sample", "top-two-mean", "centroid"), MODES)

    def test_unknown_mode_fails_closed(self):
        profile = Profile(name="profil-a", embedding=unit(0.5), samples=[unit(0.5)])
        with self.assertRaises(ValueError):
            score_profile([1.0, 0.0], profile, "unbekannt")


class OneProfileShadowModeTest(unittest.TestCase):

    def setUp(self):
        self.target = Profile(
            name="profil-a",
            embedding=unit(0.8),
            samples=[unit(0.8), unit(0.7), unit(0.6)],
        )
        self.other = Profile(
            name="profil-b",
            embedding=unit(0.4),
            samples=[unit(0.4), unit(0.3), unit(0.2)],
        )

    def test_target_filter_returns_one_profile_without_mutating_input(self):
        profiles = [self.target, self.other]

        selected = select_scoring_profiles(profiles, "profil-a")

        self.assertEqual([self.target], selected)
        self.assertEqual([self.target, self.other], profiles)

    def test_unknown_target_fails_instead_of_generating_empty_report(self):
        with self.assertRaises(SystemExit):
            select_scoring_profiles([self.target, self.other], "nicht-vorhanden")

    def test_other_enrollment_samples_become_explicit_impostor_sanity(self):
        probes = enrollment_impostor_probes([self.target, self.other], "profil-a")

        self.assertEqual(3, len(probes))
        self.assertTrue(all(isinstance(probe, Probe) for probe in probes))
        self.assertTrue(all(probe.truth_speaker == "profil-b" for probe in probes))
        self.assertTrue(all(probe.channel == "enrollment" for probe in probes))
        self.assertTrue(all(probe.quality == "enrollment-sanity" for probe in probes))
        self.assertEqual(self.other.samples, [probe.embedding for probe in probes])

    def test_no_other_profile_samples_is_a_hard_error(self):
        with self.assertRaises(SystemExit):
            enrollment_impostor_probes([self.target], "profil-a")

    def test_one_profile_matrix_has_no_runner_up_and_uses_fixed_thresholds(self):
        clear_other = Profile(
            name="profil-b",
            embedding=[-1.0, 0.0],
            samples=[[-1.0, 0.0], [-0.9, -math.sqrt(0.19)], [-0.8, -0.6]],
        )
        genuine = Probe(
            truth_speaker="profil-a",
            channel="browser",
            quality="clean",
            embedding=unit(0.75),
        )
        impostors = enrollment_impostor_probes([self.target, clear_other], "profil-a")

        results = evaluate(
            [genuine] + impostors,
            [self.target],
            list(ONE_PROFILE_THRESHOLDS),
            margin=0.10,
        )

        self.assertEqual(4, len(results))
        self.assertTrue(all(result.runner_up["best-sample"] is None for result in results))
        self.assertEqual("profil-a", results[0].decisions[("best-sample", 0.45)])
        self.assertIsNone(results[1].decisions[("best-sample", 0.55)])


if __name__ == "__main__":
    unittest.main()
