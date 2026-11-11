"""Known-value statistics and strict replay tests for the page-lock runner."""

import importlib.util
import json
import math
import tempfile
import unittest
from decimal import Decimal, localcontext
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "page_lock_runner", Path(__file__).resolve().parents[1] / "page-lock-runner.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
SETTINGS = {"cells": ["records-1"], "pilot_pairs": 0, "commitPageLimit": 17}
CHECKSUMS = {"branch": "abc", "develop": "def"}


def state(settings=SETTINGS):
    result = runner.State(settings, CHECKSUMS)
    result.apply(result.experiment)
    return result


def finish(current, model="CPU", validity="valid", throughput=100):
    start = dict(current.next_run(), type="run_started")
    current.apply(start)
    event = dict(start, type="run_finished", throughput=throughput,
                 protocol_share="unavailable", counters={"commits": "unavailable"},
                 processor_model=model, validity=validity,
                 reason="error" if validity == "invalid" else "")
    current.apply(event)
    return start, event


class StatisticsTest(unittest.TestCase):
    def test_quantiles_by_density_integration(self):
        """Independent Simpson integration places every quantile at CDF 119/120."""
        for n, q in runner.QUANTILES.items():
            df, steps = n - 1, 20000
            scale = math.exp(math.lgamma((df + 1) / 2) - math.lgamma(df / 2))
            scale /= math.sqrt(df * math.pi)
            density = lambda x: scale * (1 + x * x / df) ** (-(df + 1) / 2)
            h = q / steps
            area = h / 3 * (density(0) + density(q) + sum(
                (4 if i % 2 else 2) * density(i * h) for i in range(1, steps)))
            self.assertAlmostEqual(0.5 + area, 119 / 120, delta=2e-10)

    def test_analytic_intervals_and_drops(self):
        """Symmetric log ratios have mean .03 and known sample spread at each look."""
        # Decimal worksheet: mean .03, sum of squared deviations n/100.
        expected = {20: (-0.030224059952, 0.090224059952, -0.030685443415, 0.086273567167),
                    50: (-0.005413614743, 0.065413614743, -0.005428294834, 0.063320041396),
                    100: (0.005523919105, 0.054476080895, 0.005508690318, 0.053018840471)}
        for n, bounds in expected.items():
            result = runner.look([-0.07, 0.13] * (n // 2))
            for actual, known in zip(result["log_bounds"] + result["drop_bounds"], bounds):
                self.assertAlmostEqual(actual, known, delta=2e-12)

    def test_asymmetric_interval_uses_arithmetic_mean(self):
        """One large ratio shifts the mean and leaves the 20-pair decision undecided."""
        # A separate 70-digit Decimal worksheet gives sum .01 and squared deviations .041895.
        expected = (-0.0270636120865, 0.0280636120865,
                    -0.027433157851690821, 0.027673486884311808)
        result = runner.look([-0.01] * 19 + [0.2])
        for actual, known in zip(result["log_bounds"] + result["drop_bounds"], expected):
            self.assertAlmostEqual(actual, known, delta=2e-12)
        self.assertEqual("undecided", result["decision"])

    def test_decisions_boundary_zero_variance_and_direction(self):
        """A 98/100 branch passes exactly at 2 percent. A slower branch has positive cost."""
        with localcontext() as context:
            context.prec = 70
            boundary = float((Decimal(50) / Decimal(49)).ln())
        self.assertEqual("pass", runner.look([boundary] * 20)["decision"])
        self.assertEqual("pass", runner.look([runner.paired_log(100, 98)] * 20)["decision"])
        self.assertEqual("fail", runner.look([boundary + 1e-10] * 20)["decision"])
        self.assertEqual("pass", runner.look([0] * 20)["decision"])
        self.assertEqual("undecided", runner.look([-0.07, 0.13] * 10)["decision"])
        self.assertEqual("goes to the user", runner.look([-0.1, 0.1] * 50)["decision"])
        self.assertAlmostEqual(
            0.5, runner.look([runner.paired_log(200, 100)] * 20)["drop_bounds"][0])
        for n in (0, 19, 21, 49, 51, 99, 101):
            self.assertIsNone(runner.look([0] * n))
        for value in (0, -1, math.inf, math.nan):
            with self.assertRaises(ValueError):
                runner.paired_log(value, 1)

    def test_all_six_cells_across_servers(self):
        """Every required cell must pass, including when records come from different servers."""
        cells = list("abcdef")
        self.assertTrue(runner.goal9_passes(
            cells, dict.fromkeys("abc", "pass"), dict.fromkeys("def", "pass")))
        for decision in (None, "undecided", "fail", "goes to the user"):
            decisions = dict.fromkeys(cells, "pass")
            decisions.pop("f")
            if decision:
                decisions["f"] = decision
            self.assertFalse(runner.goal9_passes(cells, decisions))
        self.assertFalse(runner.goal9_passes(cells, dict.fromkeys(cells, "pass"), {"a": "fail"}))
        self.assertFalse(runner.goal9_passes([], {}))


class RecordTest(unittest.TestCase):
    def replay_events(self, events, settings=SETTINGS, checksums=CHECKSUMS, suffix=""):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.jsonl"
            path.write_text("".join(json.dumps(e) + "\n" for e in events) + suffix)
            return runner.replay(path, settings, checksums)

    def test_empty_started_and_finished_first_side(self):
        """An interrupted start repeats exactly. A finished first side waits for its partner."""
        initial = self.replay_events([])
        start = dict(initial.next_run(), type="run_started")
        resumed = self.replay_events([initial.experiment, start, start])
        self.assertEqual(initial.next_run(), resumed.next_run())
        current = state()
        events = finish(current)
        resumed = self.replay_events([current.experiment, *events])
        self.assertEqual("second", resumed.next_run()["order"])
        self.assertEqual(1, len(resumed.runs))
        self.assertEqual("unavailable", next(iter(resumed.runs.values()))["counters"]["commits"])

    def test_interrupted_second_side_replays_without_counting_a_pair(self):
        """Replay repeats an unfinished second side and counts only its eventual finish."""
        current = state()
        events = [current.experiment, *finish(current)]
        second = {"type": "run_started", "cell": "records-1", "phase": "result",
                  "attempt": 0, "order": "second", "side": "develop"}
        resumed = self.replay_events([*events, second])
        self.assertEqual({k: v for k, v in second.items() if k != "type"}, resumed.next_run())
        self.assertEqual(1, len(resumed.runs))
        self.assertEqual([], resumed.values["records-1", "result"])
        self.assertEqual(0, resumed.attempts["records-1", "result"])
        finish(resumed)
        self.assertEqual([0.0], resumed.values["records-1", "result"])
        self.assertEqual(1, resumed.next_run()["attempt"])

    def test_first_side_alternates_through_retries_and_resets_for_results(self):
        """Both phases alternate over four attempts, including an excluded second attempt."""
        current = state(dict(SETTINGS, pilot_pairs=3))
        for phase in ("pilot", "result"):
            for attempt, (first, second) in enumerate((
                    ("branch", "develop"), ("develop", "branch"),
                    ("branch", "develop"), ("develop", "branch"))):
                for order, side in (("first", first), ("second", second)):
                    self.assertEqual(
                        {"cell": "records-1", "phase": phase, "attempt": attempt,
                         "order": order, "side": side}, current.next_run())
                    finish(current, model="other CPU" if attempt == 1 and order == "second"
                           else "CPU")
            self.assertEqual(3, len(current.values["records-1", phase]))
            self.assertIn(("records-1", phase, 1), current.excluded)

    def test_six_valid_pilots_required_despite_an_excluded_pair(self):
        """Six valid pilot pairs unlock results. A CPU mismatch consumes only an attempt."""
        current = state(dict(SETTINGS, pilot_pairs=6))
        for attempt, valid_pairs in enumerate((1, 2, 2, 3, 4, 5, 6)):
            with self.subTest(attempt=attempt):
                self.assertEqual("pilot", current.next_run()["phase"])
                self.assertEqual(attempt, current.next_run()["attempt"])
                finish(current)
                self.assertEqual("pilot", current.next_run()["phase"])
                finish(current, model="other CPU" if attempt == 2 else "CPU")
                self.assertEqual(valid_pairs, len(current.values["records-1", "pilot"]))
                self.assertEqual([], current.values["records-1", "result"])
                self.assertEqual("pilot" if valid_pairs < 6 else "result",
                                 current.next_run()["phase"])
        self.assertIn(("records-1", "pilot", 2), current.excluded)
        self.assertEqual({"cell": "records-1", "phase": "result", "attempt": 0,
                          "order": "first", "side": "branch"}, current.next_run())

    def test_exclusion_alternation_and_pilot_separation(self):
        """A CPU mismatch consumes an attempt, not a pair. Pilots never enter result values."""
        settings = dict(SETTINGS, cells=["custom", "other"], pilot_pairs=1)
        current = state(settings)
        events = [current.experiment, *finish(current), *finish(current, model="different")]
        excluded = {"type": "pair_excluded", "cell": "custom", "phase": "pilot",
                    "attempt": 0, "reason": "processor model mismatch"}
        events.append(excluded)
        current = self.replay_events(events, settings)
        self.assertEqual("develop", current.next_run()["side"])
        self.assertEqual(1, current.next_run()["attempt"])
        self.assertEqual([], current.values["custom", "pilot"])
        finish(current)
        finish(current)
        self.assertEqual("other", current.next_run()["cell"])
        finish(current)
        finish(current)
        self.assertEqual(("custom", "result", 0, "branch"), tuple(
            current.next_run()[k] for k in ("cell", "phase", "attempt", "side")))
        self.assertEqual([], current.values["custom", "result"])

    def test_decided_cells_stop_and_final_undecided_stops(self):
        """Pass and fail stop at 20 pairs. An unresolved interval stops at 100 pairs."""
        for mode, pairs, expected in (("pass", 20, "pass"), ("fail", 20, "fail"),
                                       ("mixed", 100, "goes to the user")):
            current = state()
            for i in range(pairs):
                for _ in range(2):
                    side = current.next_run()["side"]
                    branch = 50 if mode == "fail" else 100
                    develop = (100 * math.exp(.02 + (.1 if i % 2 else -.1))
                               if mode == "mixed" else 100)
                    finish(current, throughput=branch if side == "branch" else develop)
            self.assertIsNone(current.next_run())
            self.assertEqual(expected, current.decisions["records-1"])
            current.apply({"type": "cell_decision", "cell": "records-1", "pairs": pairs,
                           "decision": expected})

    def test_result_exclusion_and_undecided_look_annotations(self):
        """Excluded result pairs do not count. Undecided look records allow the next run."""
        current = state()
        finish(current)
        finish(current, model="other CPU")
        self.assertEqual([], current.values["records-1", "result"])
        self.assertEqual(1, current.next_run()["attempt"])
        for i in range(50):
            for _ in range(2):
                side = current.next_run()["side"]
                finish(current, throughput=100 if side == "branch" else
                       100 * math.exp(.02 + (.1 if i % 2 else -.1)))
            if i + 1 in (20, 50):
                current.apply({"type": "cell_decision", "cell": "records-1",
                               "pairs": i + 1, "decision": "undecided"})
                self.assertIsNotNone(current.next_run())

    def test_invalid_run_stays_stopped(self):
        """An invalid finished run remains recorded and blocks any continuation after replay."""
        current = state()
        events = finish(current, validity="invalid", throughput=None)
        resumed = self.replay_events([current.experiment, *events])
        self.assertEqual("error", resumed.stopped["reason"])
        self.assertIsNone(resumed.next_run())
        with self.assertRaises(ValueError):
            resumed.apply(events[0])

    def test_corruption_unknown_event_and_experiment_mismatch(self):
        """No corrupt line, unknown event, changed settings or changed checksum is skipped."""
        experiment = state().experiment
        for suffix in ('{"type":', '{}\n{}\n', '{bad}\n{}\n', '{"type":"unknown"}\n'):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "record line 2"):
                self.replay_events([experiment], suffix=suffix)
        # This scheduled event is valid JSON. Only its missing newline makes it torn.
        start = dict(state().next_run(), type="run_started")
        with self.assertRaisesRegex(ValueError, "record line 2: torn line"):
            self.replay_events([experiment], suffix=json.dumps(start))
        resumed = self.replay_events([experiment], suffix=json.dumps(start) + "\n")
        self.assertEqual(state().next_run(), resumed.next_run())
        for settings, checksums in ((dict(SETTINGS, commitPageLimit=18), CHECKSUMS),
                                    (SETTINGS, dict(CHECKSUMS, develop="changed"))):
            with self.assertRaisesRegex(ValueError, "settings or checksums differ"):
                self.replay_events([experiment], settings, checksums)

    def test_durable_write_and_replay_round_trip(self):
        """Real appends preserve all finished runs and call fsync after flushing each event."""
        current = state()
        events = [current.experiment, *finish(current), *finish(current)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.jsonl"
            real_sync = runner.os.fsync
            def check_flushed(fd):
                self.assertEqual(len(path.read_text().splitlines()), sync.call_count)
                real_sync(fd)
            with patch.object(runner.os, "fsync", side_effect=check_flushed) as sync:
                for event in events:
                    runner.append_event(path, event)
                self.assertEqual(len(events), sync.call_count)
            resumed = runner.replay(path, SETTINGS, CHECKSUMS)
            self.assertEqual(current.runs, resumed.runs)
            self.assertEqual(current.next_run(), resumed.next_run())
