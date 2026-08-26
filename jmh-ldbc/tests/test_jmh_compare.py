"""Unit tests for jmh-compare.py — suite detection, Gremlin @Param arms, tables."""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


def _load_compare():
    path = Path(__file__).resolve().parents[1] / "jmh-compare.py"
    spec = importlib.util.spec_from_file_location("jmh_compare", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cmp = _load_compare()


def _entry(benchmark, score, score_error=0.1, params=None):
    entry = {
        "benchmark": benchmark,
        "primaryMetric": {"score": score, "scoreError": score_error},
    }
    if params is not None:
        entry["params"] = params
    return entry


GREMLIN_CLS = (
    "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcGremlinTranslatorBenchmark"
)
ST_CLS = "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcSingleThreadISBenchmark"
MT_CLS = "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcMultiThreadISBenchmark"


def _gremlin_on_off_fixture():
    """Minimal base/head JSON with Gremlin on+off and one SQL ST row."""
    base = [
        _entry(
            f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
            100.0,
            score_error=1.0,
            params={"translatorEnabled": "true"},
        ),
        _entry(
            f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
            200.0,
            score_error=2.0,
            params={"translatorEnabled": "false"},
        ),
        _entry(f"{ST_CLS}.is1_personProfile", 10.0, score_error=0.1),
    ]
    head = [
        _entry(
            f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
            110.0,
            score_error=1.0,
            params={"translatorEnabled": "true"},
        ),
        _entry(
            f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
            190.0,
            score_error=2.0,
            params={"translatorEnabled": "false"},
        ),
        _entry(f"{ST_CLS}.is1_personProfile", 10.0, score_error=0.1),
    ]
    return base, head


def _run_compare(base, head, extra_argv=None):
    with tempfile.TemporaryDirectory() as tmp:
        base_path = Path(tmp) / "base.json"
        head_path = Path(tmp) / "head.json"
        out_path = Path(tmp) / "out.md"
        base_path.write_text(json.dumps(base))
        head_path.write_text(json.dumps(head))
        argv = [
            "jmh-compare.py",
            "--base", str(base_path),
            "--head", str(head_path),
            "--base-sha", "aaaaaaaaaa",
            "--head-sha", "bbbbbbbbbb",
            "--output", str(out_path),
        ]
        if extra_argv:
            argv.extend(extra_argv)
        argv_backup = sys.argv
        try:
            sys.argv = argv
            cmp.main()
        finally:
            sys.argv = argv_backup
        return out_path.read_text()


class TestSuiteDetection(unittest.TestCase):
    def test_single_and_multi_thread(self):
        """ST/MT class names still map to the SQL LDBC suites."""
        out = cmp.parse_jmh_results([
            _entry(f"{ST_CLS}.is1_personProfile", 10.0),
            _entry(f"{MT_CLS}.is1_personProfile", 40.0),
        ])
        self.assertIn(("is1_personProfile", "SingleThread"), out)
        self.assertIn(("is1_personProfile", "MultiThread"), out)

    def test_gremlin_class_maps_to_gremlin_suite(self):
        """Gremlin translator benches land in the Gremlin suite, not the raw class."""
        out = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
        ])
        self.assertIn(("gremlin_knowsFirstNames [on]", "Gremlin"), out)
        self.assertNotIn(
            ("gremlin_knowsFirstNames [on]", "LdbcGremlinTranslatorBenchmark"),
            out,
        )


class TestGremlinParamArms(unittest.TestCase):
    def test_on_and_off_arms_are_distinct_rows(self):
        """Both translator arms must survive parse — previously the second overwrote."""
        out = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                200.0,
                params={"translatorEnabled": "false"},
            ),
        ])
        self.assertEqual(len(out), 2)
        self.assertEqual(out[("gremlin_knowsFirstNames [on]", "Gremlin")]["score"], 100.0)
        self.assertEqual(out[("gremlin_knowsFirstNames [off]", "Gremlin")]["score"], 200.0)

    def test_filter_default_keeps_on_only_and_strips_suffix(self):
        """Default arms=on drops off and strips [on] so each shape is one bare row."""
        parsed = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                200.0,
                params={"translatorEnabled": "false"},
            ),
            _entry(f"{ST_CLS}.is1_personProfile", 10.0),
        ])
        filtered = cmp.filter_gremlin_arms(parsed, "on")
        self.assertIn(("gremlin_knowsFirstNames", "Gremlin"), filtered)
        self.assertNotIn(("gremlin_knowsFirstNames [on]", "Gremlin"), filtered)
        self.assertNotIn(("gremlin_knowsFirstNames [off]", "Gremlin"), filtered)
        self.assertIn(("is1_personProfile", "SingleThread"), filtered)

    def test_filter_both_keeps_arm_labels(self):
        """arms=both leaves the [on]/[off] labels untouched."""
        parsed = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                200.0,
                params={"translatorEnabled": "false"},
            ),
        ])
        filtered = cmp.filter_gremlin_arms(parsed, "both")
        self.assertEqual(filtered, parsed)

    def test_unknown_params_keep_key_equals_value(self):
        """Non-translator params stay readable as key=value in the label."""
        out = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlin_knowsFirstNames",
                1.0,
                params={"threads": "8", "mode": "thrpt"},
            ),
        ])
        self.assertIn(("gremlin_knowsFirstNames [mode=thrpt, threads=8]", "Gremlin"), out)

    def test_no_params_keeps_bare_method_name(self):
        """SQL LDBC entries without JMH params keep the plain method label."""
        out = cmp.parse_jmh_results([
            _entry(f"{ST_CLS}.is1_personProfile", 10.0, params={}),
        ])
        self.assertIn(("is1_personProfile", "SingleThread"), out)


class TestGremlinTableInMarkdown(unittest.TestCase):
    def test_default_report_shows_on_arm_only(self):
        """Default compare (no --gremlin-arms) shows one bare on-arm row per shape."""
        base, head = _gremlin_on_off_fixture()
        md = _run_compare(base, head)
        self.assertIn("### Gremlin Translator Results", md)
        self.assertIn("gremlin_knowsFirstNames", md)
        self.assertNotIn("[off]", md)
        self.assertNotIn("[on]", md)
        self.assertIn("production default", md)
        self.assertIn("### Single-Thread Results", md)
        self.assertNotIn("### Multi-Thread Results", md)

    def test_both_arms_flag_keeps_on_and_off_rows(self):
        """--gremlin-arms both keeps the full A/B labels in the table."""
        base, head = _gremlin_on_off_fixture()
        md = _run_compare(base, head, extra_argv=["--gremlin-arms", "both"])
        self.assertIn("gremlin_knowsFirstNames [on]", md)
        self.assertIn("gremlin_knowsFirstNames [off]", md)
        self.assertIn("`[on]` / `[off]`", md)


if __name__ == "__main__":
    unittest.main()
