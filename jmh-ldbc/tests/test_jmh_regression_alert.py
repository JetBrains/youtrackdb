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
import io
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

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
        """Score matches previous run and 14-run mean → no alert, no skip."""
        # Previous score and mean are both 100 → 0% delta.
        latest = _latest(score=100.0, score_error=1.0)
        history = _history([100.0] * 14)
        regs, skipped = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual(regs, [])
        self.assertEqual(skipped, [])

    def test_run_over_run_regression_fires(self):
        """Latest score 15% below previous → BOTH run-over-run and vs-avg fire."""
        latest = _latest(score=85.0, score_error=1.0)
        # hist[-1] is the most recent previous run (script reads in chrono order).
        history = _history([100.0, 100.0, 100.0])
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = sorted(r["type"] for r in regs)
        # Pin both: 15% < -10 (run-over-run) and < -5 (vs 3-run avg). If a
        # refactor ever drops one silently, this assertion catches it.
        self.assertEqual(types, ["run-over-run", "vs 3-run avg"])
        ror = [r for r in regs if r["type"] == "run-over-run"][0]
        self.assertAlmostEqual(ror["delta_pct"], -15.0, places=3)
        self.assertEqual(ror["query"], "q1")
        self.assertEqual(ror["suite"], "SingleThread")

    def test_below_threshold_does_not_fire(self):
        """A 9% drop should NOT fire run-over-run (strict > threshold)."""
        latest = _latest(score=91.0, score_error=1.0)
        history = _history([100.0])  # only 1 point → avg check is skipped
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_exact_threshold_does_not_fire(self):
        """Exactly -10% drop is NOT a run-over-run regression (delta < -10 strict)."""
        latest = _latest(score=90.0, score_error=1.0)
        history = _history([100.0])
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_rolling_avg_regression_fires_without_run_over_run(self):
        """Latest is 6% below the 14-run avg but only 2% below the last run."""
        latest = _latest(score=94.0, score_error=1.0)
        # Average is 100; previous run is 96 → run-over-run delta is -2.08%.
        history = _history([100.0] * 13 + [96.0])
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = [r["type"] for r in regs]
        self.assertNotIn("run-over-run", types)
        self.assertTrue(any("-run avg" in t for t in types))
        avg_reg = [r for r in regs if "-run avg" in r["type"]][0]
        self.assertLess(avg_reg["delta_pct"], -AVG)

    def test_noisy_benchmark_is_skipped_with_warning(self):
        """score_error / score > noise threshold → excluded AND surfaced.

        The script's contract is "skip noisy", but a permanently-noisy
        benchmark must not silently become a permanently-unmonitored one.
        Pin that check_regressions reports skipped benchmarks in a second
        return value so the caller can log them.
        """
        # 40% score error on a 50% drop — gets skipped by design, but the
        # skip itself must be reported.
        latest = _latest(score=50.0, score_error=20.0)  # 40% noise
        history = _history([100.0] * 14)
        regs, skipped = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual(regs, [])
        self.assertEqual(len(skipped), 1)
        query, suite, noise_pct = skipped[0]
        self.assertEqual(query, "q1")
        self.assertEqual(suite, "SingleThread")
        self.assertAlmostEqual(noise_pct, 40.0, places=3)

    def test_empty_history_returns_empty_list(self):
        """No historical data → check_regressions returns [] for this (query, suite).

        The silent-drop protection itself lives in main() (empty-history
        sanity guard with --allow-empty-history escape hatch); this unit
        test only pins the per-entry behaviour.
        """
        latest = _latest(score=1.0, score_error=0.01)
        history = {"SingleThread": {}, "MultiThread": {}}
        regs, skipped = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual(regs, [])
        self.assertEqual(skipped, [])

    def test_missing_suite_in_history_skipped(self):
        """Suite key absent from history → check returns nothing (no KeyError)."""
        latest = _latest(score=1.0, score_error=0.01, suite="SingleThread")
        history = {}  # no suites at all
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual(regs, [])

    def test_prev_score_zero_skips_run_over_run(self):
        """prev_score == 0 must not raise ZeroDivisionError."""
        latest = _latest(score=10.0, score_error=0.1)
        history = _history([0.0])
        # Should not raise; run-over-run is skipped for prev=0.
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        self.assertEqual([r for r in regs if r["type"] == "run-over-run"], [])

    def test_short_history_skips_avg_but_keeps_run_over_run(self):
        """< 3 historical points → avg check skipped, run-over-run still runs."""
        latest = _latest(score=50.0, score_error=0.1)
        history = _history([100.0, 100.0])  # only 2 points
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        types = [r["type"] for r in regs]
        self.assertIn("run-over-run", types)
        self.assertFalse(any("-run avg" in t for t in types))

    def test_avg_type_includes_actual_history_length(self):
        """The 'vs N-run avg' label should reflect the real history size."""
        latest = _latest(score=80.0, score_error=0.1)
        history = _history([100.0, 100.0, 100.0, 100.0])  # 4 points
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
        avg_regs = [r for r in regs if "-run avg" in r["type"]]
        self.assertEqual(len(avg_regs), 1)
        self.assertEqual(avg_regs[0]["type"], "vs 4-run avg")

    def test_regression_record_shape(self):
        """Regression dicts carry the fields the Zulip formatter depends on."""
        latest = _latest(score=70.0, score_error=0.1, query="ic5", suite="MultiThread")
        history = _history([100.0] * 14, suite="MultiThread", query="ic5")
        regs, _ = alerter.check_regressions(latest, history, ROR, AVG, NOISE)
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

    def test_malformed_benchmark_name_skipped(self):
        """Benchmark names without at least one dot are skipped, not crashed on.

        JMH always produces fully-qualified names, but a defensive guard
        prevents an unexpected input shape from crashing the whole alerter
        with IndexError before any regression is even checked.
        """
        data = [
            {"benchmark": "noDots", "primaryMetric": {"score": 1.0, "scoreError": 0.0}},
            {"benchmark": "", "primaryMetric": {"score": 1.0, "scoreError": 0.0}},
            # A well-formed entry alongside the malformed ones should still parse.
            {
                "benchmark": "com.example.SomeBench.queryOk",
                "primaryMetric": {"score": 2.0, "scoreError": 0.0},
            },
        ]
        out = alerter.parse_latest_results(data)
        self.assertIn(("queryOk", "SomeBench"), out)
        # Malformed entries must be absent from the output.
        self.assertNotIn(("noDots", "noDots"), out)
        self.assertEqual(len(out), 1)


class TestFluxEscape(unittest.TestCase):
    """Pin that interpolated Flux values are escaped against syntax breakage.

    If a branch name ever contains `"` or `\\`, unescaped interpolation would
    corrupt the query. These tests are small, but they'd have prevented a
    real outage on a weirdly-named branch.
    """

    def test_plain_string_unchanged(self):
        self.assertEqual(alerter._flux_escape("develop"), "develop")

    def test_double_quote_escaped(self):
        self.assertEqual(alerter._flux_escape('weird"branch'), 'weird\\"branch')

    def test_backslash_escaped_before_quote(self):
        # Must escape `\` first, otherwise escaping `"` → `\"` creates an
        # ambiguous sequence. Check the doubled-backslash output explicitly.
        self.assertEqual(alerter._flux_escape("a\\b"), "a\\\\b")
        self.assertEqual(alerter._flux_escape('a\\"b'), 'a\\\\\\"b')

    def test_non_string_coerced(self):
        self.assertEqual(alerter._flux_escape(42), "42")


class TestQueryInfluxdbErrorHandling(unittest.TestCase):
    """Pin that an HTTP failure raises RuntimeError (instead of sys.exit).

    Raising lets main() decide how to exit and, more importantly, keeps the
    function reusable from tests. If this ever regresses to sys.exit(1), the
    test suite itself would be terminated by the call — which is exactly the
    failure mode we want to prevent.
    """

    def test_http_error_raises_runtime_error(self):
        http_error = urllib.error.HTTPError(
            url="http://example/api/v2/query",
            code=503,
            msg="Service Unavailable",
            hdrs=None,
            fp=io.BytesIO(b"influx is down"),
        )
        with mock.patch.object(alerter.urllib.request, "urlopen", side_effect=http_error):
            with self.assertRaises(RuntimeError) as cm:
                alerter.query_influxdb(
                    url="http://example",
                    token="t",
                    org="o",
                    bucket="b",
                    suite="SingleThread",
                    branch="develop",
                    limit=14,
                )
        # Error message should carry the status code and body so operators
        # can diagnose without rerunning with extra logging.
        self.assertIn("503", str(cm.exception))
        self.assertIn("influx is down", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
