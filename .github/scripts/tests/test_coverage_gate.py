"""Regression tests for the explicit, temporary TinkerPop coverage exception."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "coverage-gate.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)

# Keep the approved module identities independent of the production exclusion set.
APPROVED_COPIED_MODULES = frozenset({
    "gremlin-shaded", "gremlin-language", "gremlin-core", "gremlin-util",
    "gremlin-driver", "gremlin-groovy", "gremlin-server", "gremlin-console",
    "gremlin-test", "tinkergraph-gremlin", "gremlin-socket-server",
})


class TinkerPopCoverageExceptionTest(unittest.TestCase):
    """Keep excluded third-party paths separate from measured YouTrackDB paths."""

    def run_gate(self, changed, reports, output=None):
        args = [
            str(SCRIPT), "--line-threshold", "85", "--branch-threshold", "70",
            "--compare-branch", "origin/develop", "--coverage-dir", "unused",
        ]
        if output:
            args.extend(["--output-md", str(output)])
        stdout = io.StringIO()
        with patch("sys.argv", args), patch.object(
            GATE, "get_changed_lines", return_value=dict(changed)
        ), patch.object(GATE.glob, "glob", return_value=reports), contextlib.redirect_stdout(stdout):
            GATE.main()
        return stdout.getvalue()

    def test_exclusions_match_the_approved_module_identities(self):
        """Only the 11 approved copied modules may bypass changed-line measurement."""
        self.assertEqual(APPROVED_COPIED_MODULES, GATE.TINKERPOP_COVERAGE_EXCLUSIONS)

    def test_only_copied_modules_report_an_explicit_exemption_without_reports(self):
        """Each of the 11 excluded modules must report an exemption, not measured coverage."""
        changed = {
            f"{module}/src/main/java/Example.java": {1}
            for module in APPROVED_COPIED_MODULES
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "gate.md"
            result = self.run_gate(changed, [], output)
            self.assertIn("Explicitly excluded 11 changed Java file(s)", result)
            self.assertIn("No changed Java files outside the explicit TinkerPop", result)
            self.assertIn("PR 2 removes this exception", output.read_text())
            self.assertNotIn("PASSED: All coverage", result)
            self.assertNotIn("No JaCoCo XML files found", result)

    def test_exemption_report_creates_a_missing_parent_directory(self):
        """An exempt-only diff must write Markdown even when its parent folders are absent."""
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "missing" / "nested" / "gate.md"
            self.run_gate({"gremlin-core/src/main/java/Example.java": {1}}, [], output)
            self.assertIn("Explicitly excluded 1 changed Java files", output.read_text())

    def test_a_module_with_a_similar_name_is_not_excluded(self):
        """The path boundary must not exempt a different module that shares a name prefix."""
        result = self.run_gate({"gremlin-core-extra/src/main/java/Example.java": {1}}, [])
        self.assertNotIn("Explicitly excluded", result)
        self.assertIn("No JaCoCo XML files found", result)

    def test_mixed_diff_still_measures_and_rejects_uncovered_youtrackdb_lines(self):
        """An exempt file must not hide a changed YouTrackDB file with zero coverage."""
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(
                '<report><package name="com/example"><sourcefile name="Example.java">'
                '<line nr="1" mi="1" ci="0" mb="1" cb="0"/>'
                '</sourcefile></package></report>'
            )
            output = Path(directory) / "gate.md"
            with self.assertRaises(SystemExit) as raised:
                self.run_gate({
                    "gremlin-core/src/main/java/com/example/Example.java": {1},
                    "core/src/main/java/com/example/Example.java": {1},
                }, [str(report)], output)
            self.assertEqual(1, raised.exception.code)
            self.assertIn("core/src/main/java/com/example/Example.java", output.read_text())
            self.assertNotIn("gremlin-core/src/main/java", output.read_text())

    def test_core_only_diff_is_measured_and_rejected_when_uncovered(self):
        """A core-only change with zero line and branch coverage must fail, not be exempt."""
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(
                '<report><package name="com/example"><sourcefile name="Example.java">'
                '<line nr="1" mi="1" ci="0" mb="1" cb="0"/>'
                '</sourcefile></package></report>'
            )
            output = Path(directory) / "gate.md"
            with self.assertRaises(SystemExit) as raised:
                self.run_gate({"core/src/main/java/com/example/Example.java": {1}},
                              [str(report)], output)
            self.assertEqual(1, raised.exception.code)
            self.assertIn("core/src/main/java/com/example/Example.java", output.read_text())

    def test_empty_diff_keeps_the_no_java_skip(self):
        """A change without Java files must keep the ordinary no-Java skip message."""
        result = self.run_gate({}, [])
        self.assertIn("No changed Java files found. Skipping coverage gate.", result)
        self.assertNotIn("Explicitly excluded", result)


if __name__ == "__main__":
    unittest.main()
