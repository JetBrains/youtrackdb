#!/usr/bin/env python3
"""Unit tests for jmh-regression-alert.check_regressions().

The script filename contains a dash, so we load it via importlib instead of a
normal import. These tests pin the regression-detection math so a future
refactor cannot silently break alert firing (cf. the 2026-04-14 incident where
the Grafana Flux rule was mathematically incapable of firing for months
without anyone noticing).

Run locally:
    python3 -m unittest jmh-ldbc/tests/test_jmh_regression_alert.py
or, with discovery:
    python3 -m unittest discover -s jmh-ldbc/tests
"""

import importlib.util
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parent.parent / "jmh-regression-alert.py"
_spec = importlib.util.spec_from_file_location("jmh_regression_alert", SCRIPT_PATH)
alerter = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(alerter)


# Shorthand: default thresholds matching the CLI defaults (10% run-over-run,
# 5% rolling avg, 10% score-error noise threshold).
ROR = 10.0
AVG = 5.0
NOISE = 10.0


def _latest(score, score_error, query="q1", suite="SingleThread"):
    """Build a one-entry `latest` dict as parse_latest_results() would."""
    return {(query, suite): {"score": score, "score_error": score_error}}


def _history(scores, suite="SingleThread", query="q1"):
    """Build a history dict keyed by suite then query, oldest first."""
    return {suite: {query: list(scores)}}


class TestCheckRegressions(unittest.TestCase):
    def test_clean_run_no_regressions(self):
        """Score matches previous run and 14-run mean → no alert."""
        # Previous score and mean are both 100 → 0% delta.
        latest = _latest(score=100.0, score_error=1.0)
        history = _history([100.0] * 14)
        self.assertEqual(
            alerter.check_regressions(latest, history, ROR, AVG, NOISE), []
        )

    def test_run_over_run_regression_fires(self):
        """Latest score 15% below previous → run-over-run alert fires."""
        latest = _latest(score=85.0, score_error=1.0)
        # hist[-1] is the most recent previous run (script reads in chrono order).
        history = _history([100.0, 100.0, 100.0])
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = sorted(r["type"] for r in regs)
        # Both checks should trigger: 15% < -10 (run-over-run) and < -5 (vs avg).
        self.assertIn("run-over-run", types)
        ror = [r for r in regs if r["type"] == "run-over-run"][0]
        self.assertAlmostEqual(ror["delta_pct"], -15.0, places=3)
        self.assertEqual(ror["query"], "q1")
        self.assertEqual(ror["suite"], "SingleThread")

    def test_below_threshold_does_not_fire(self):
        """A 9% drop should NOT fire run-over-run (strict > threshold)."""
        latest = _latest(score=91.0, score_error=1.0)
        history = _history([100.0])  # only 1 point → avg check is skipped
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_exact_threshold_does_not_fire(self):
        """Exactly -10% drop is NOT a run-over-run regression (delta < -10 strict)."""
        latest = _latest(score=90.0, score_error=1.0)
        history = _history([100.0])
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_rolling_avg_regression_fires_without_run_over_run(self):
        """Latest is 6% below the 14-run avg but only 2% below the last run."""
        latest = _latest(score=94.0, score_error=1.0)
        # Average is 100; previous run is 96 → run-over-run delta is -2.08%.
        history = _history([100.0] * 13 + [96.0])
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = [r["type"] for r in regs]
        self.assertNotIn("run-over-run", types)
        self.assertTrue(any("-run avg" in t for t in types))
        avg_reg = [r for r in regs if "-run avg" in r["type"]][0]
        self.assertLess(avg_reg["delta_pct"], -AVG)

    def test_noisy_benchmark_is_skipped(self):
        """score_error / score > noise threshold → benchmark excluded entirely."""
        # 40% score error on a 50% drop — we must NOT silently drop the alert
        # when the underlying signal is noisy, but the script's contract is
        # "skip noisy" — pin that behaviour explicitly so it can't drift.
        latest = _latest(score=50.0, score_error=20.0)  # 40% noise
        history = _history([100.0] * 14)
        self.assertEqual(
            alerter.check_regressions(latest, history, ROR, AVG, NOISE), []
        )

    def test_empty_history_skipped(self):
        """No historical data for this (query, suite) → no alert."""
        latest = _latest(score=1.0, score_error=0.01)
        history = {"SingleThread": {}, "MultiThread": {}}
        self.assertEqual(
            alerter.check_regressions(latest, history, ROR, AVG, NOISE), []
        )

    def test_missing_suite_in_history_skipped(self):
        """Suite key absent from history → check returns nothing (no KeyError)."""
        latest = _latest(score=1.0, score_error=0.01, suite="SingleThread")
        history = {}  # no suites at all
        self.assertEqual(
            alerter.check_regressions(latest, history, ROR, AVG, NOISE), []
        )

    def test_prev_score_zero_skips_run_over_run(self):
        """prev_score == 0 must not raise ZeroDivisionError."""
        latest = _latest(score=10.0, score_error=0.1)
        history = _history([0.0])
        # Should not raise; run-over-run is skipped for prev=0.
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_short_history_skips_avg_but_keeps_run_over_run(self):
        """< 3 historical points → avg check skipped, run-over-run still runs."""
        latest = _latest(score=50.0, score_error=0.1)
        history = _history([100.0, 100.0])  # only 2 points
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = [r["type"] for r in regs]
        self.assertIn("run-over-run", types)
        self.assertFalse(any("-run avg" in t for t in types))

    def test_avg_type_includes_actual_history_length(self):
        """The 'vs N-run avg' label should reflect the real history size."""
        latest = _latest(score=80.0, score_error=0.1)
        history = _history([100.0, 100.0, 100.0, 100.0])  # 4 points
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        avg_regs = [r for r in regs if "-run avg" in r["type"]]
        self.assertEqual(len(avg_regs), 1)
        self.assertEqual(avg_regs[0]["type"], "vs 4-run avg")

    def test_regression_record_shape(self):
        """Regression dicts carry the fields the Zulip formatter depends on."""
        latest = _latest(score=70.0, score_error=0.1, query="ic5", suite="MultiThread")
        history = _history([100.0] * 14, suite="MultiThread", query="ic5")
        regs = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertTrue(regs)
        required = {"query", "suite", "type", "score", "baseline", "delta_pct", "threshold"}
        for r in regs:
            self.assertTrue(required.issubset(r.keys()),
                            msg=f"missing fields in {r.keys()}")
            self.assertEqual(r["query"], "ic5")
            self.assertEqual(r["suite"], "MultiThread")


class TestParseLatestResults(unittest.TestCase):
    def test_single_thread_suite_detection(self):
        data = [{
            "benchmark": "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcSingleThreadBenchmark.is1_personProfile",
            "primaryMetric": {"score": 42.0, "scoreError": 1.0},
        }]
        out = alerter.parse_latest_results(data)
        self.assertIn(("is1_personProfile", "SingleThread"), out)

    def test_multi_thread_suite_detection(self):
        data = [{
            "benchmark": "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcMultiThreadBenchmark.ic5_newGroups",
            "primaryMetric": {"score": 7.0, "scoreError": 0.1},
        }]
        out = alerter.parse_latest_results(data)
        self.assertIn(("ic5_newGroups", "MultiThread"), out)

    def test_fallback_suite_uses_class_name(self):
        """Class names without SingleThread/MultiThread fall back to the raw class."""
        data = [{
            "benchmark": "com.example.SomeOtherBench.queryA",
            "primaryMetric": {"score": 1.0, "scoreError": 0.0},
        }]
        out = alerter.parse_latest_results(data)
        self.assertIn(("queryA", "SomeOtherBench"), out)


if __name__ == "__main__":
    unittest.main()
