"""Unit tests for jmh-compare.py — suite detection, Gremlin @Param arms, tables."""

import importlib.util
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
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
        ])
        self.assertIn(("gremlinKnowsFirstNames [on]", "Gremlin"), out)
        self.assertNotIn(
            ("gremlinKnowsFirstNames [on]", "LdbcGremlinTranslatorBenchmark"),
            out,
        )


class TestGremlinParamArms(unittest.TestCase):
    def test_on_and_off_arms_are_distinct_rows(self):
        """Both translator arms must survive — previously the second overwrote the first."""
        out = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                100.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                200.0,
                params={"translatorEnabled": "false"},
            ),
        ])
        self.assertEqual(len(out), 2)
        self.assertEqual(out[("gremlinKnowsFirstNames [on]", "Gremlin")]["score"], 100.0)
        self.assertEqual(out[("gremlinKnowsFirstNames [off]", "Gremlin")]["score"], 200.0)

    def test_unknown_params_keep_key_equals_value(self):
        """Non-translator params stay readable as key=value in the label."""
        out = cmp.parse_jmh_results([
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                1.0,
                params={"threads": "8", "mode": "thrpt"},
            ),
        ])
        self.assertIn(("gremlinKnowsFirstNames [mode=thrpt, threads=8]", "Gremlin"), out)

    def test_no_params_keeps_bare_method_name(self):
        """SQL LDBC entries without JMH params keep the plain method label."""
        out = cmp.parse_jmh_results([
            _entry(f"{ST_CLS}.is1_personProfile", 10.0, params={}),
        ])
        self.assertIn(("is1_personProfile", "SingleThread"), out)


class TestGremlinTableInMarkdown(unittest.TestCase):
    def test_comparison_includes_gremlin_section(self):
        """Full compare output gets a Gremlin Translator Results section when present."""
        base = [
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                100.0,
                score_error=1.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                200.0,
                score_error=2.0,
                params={"translatorEnabled": "false"},
            ),
            _entry(f"{ST_CLS}.is1_personProfile", 10.0, score_error=0.1),
        ]
        head = [
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                110.0,
                score_error=1.0,
                params={"translatorEnabled": "true"},
            ),
            _entry(
                f"{GREMLIN_CLS}.gremlinKnowsFirstNames",
                190.0,
                score_error=2.0,
                params={"translatorEnabled": "false"},
            ),
            _entry(f"{ST_CLS}.is1_personProfile", 10.0, score_error=0.1),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            base_path = Path(tmp) / "base.json"
            head_path = Path(tmp) / "head.json"
            out_path = Path(tmp) / "out.md"
            import json
            base_path.write_text(json.dumps(base))
            head_path.write_text(json.dumps(head))

            # Drive main() via argv
            import sys
            argv_backup = sys.argv
            try:
                sys.argv = [
                    "jmh-compare.py",
                    "--base", str(base_path),
                    "--head", str(head_path),
                    "--base-sha", "aaaaaaaaaa",
                    "--head-sha", "bbbbbbbbbb",
                    "--output", str(out_path),
                ]
                cmp.main()
            finally:
                sys.argv = argv_backup

            md = out_path.read_text()
            self.assertIn("### Gremlin Translator Results", md)
            self.assertIn("gremlinKnowsFirstNames [on]", md)
            self.assertIn("gremlinKnowsFirstNames [off]", md)
            self.assertIn("### Single-Thread Results", md)
            # MT absent → no Multi-Thread section
            self.assertNotIn("### Multi-Thread Results", md)


if __name__ == "__main__":
    unittest.main()
