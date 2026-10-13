"""Regression tests for the shared changed-line coverage rule."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "coverage-gate.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class CoverageGateTest(unittest.TestCase):
    """Exercise the gate with real reactor POMs and report files in a temporary tree."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        previous = Path.cwd()
        os.chdir(self.directory.name)
        self.addCleanup(os.chdir, previous)
        self.modules = []
        self.write_pom(Path("."), "parent")
        self.output = Path("missing/nested/gate.md")

    def write_pom(self, directory, artifact, modules=()):
        directory.mkdir(parents=True, exist_ok=True)
        children = "".join(f"<module>{module}</module>" for module in modules)
        (directory / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<parent><artifactId>parent-artifact</artifactId></parent>'
            f'<artifactId>{artifact}</artifactId><modules>{children}</modules></project>'
        )

    def add_module(self, module="gremlin-core", artifact=None, tests=True):
        self.modules.append(module)
        self.write_pom(Path("."), "parent", self.modules)
        self.write_pom(Path(module), artifact or module)
        if tests:
            (Path(module) / "src/test/java").mkdir(parents=True)
        return f"{module}/src/main/java/com/example/Example.java"

    def write_report(self, artifact="gremlin-core", source="Example.java", ci=1, cb=1):
        report = Path(".coverage/reports") / artifact / "jacoco.xml"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(
            f'<report><package name="com/example"><sourcefile name="{source}">'
            f'<line nr="1" mi="{1 - ci}" ci="{ci}" mb="{1 - cb}" cb="{cb}"/>'
            '</sourcefile></package></report>'
        )
        return report

    def run_gate(self, changed, output=None):
        args = [
            str(SCRIPT), "--line-threshold", "85", "--branch-threshold", "70",
            "--compare-branch", "origin/develop", "--coverage-dir", ".coverage/reports",
            "--output-md", str(output or self.output),
        ]
        stdout = io.StringIO()
        code = 0
        with patch("sys.argv", args), patch.object(
            GATE, "get_changed_lines", return_value=dict(changed)
        ), contextlib.redirect_stdout(stdout):
            try:
                GATE.main()
            except SystemExit as error:
                code = error.code
        return code, stdout.getvalue()

    def test_missing_report_fails_even_when_no_reports_exist(self):
        """A changed main file in a tested reactor module must fail before the no-report skip."""
        source = self.add_module()
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Missing JaCoCo report for module `gremlin-core`", stdout)
        self.assertIn(source, stdout)
        self.assertIn(".coverage/reports/gremlin-core/jacoco.xml", stdout)
        self.assertNotIn("Skipping coverage gate", stdout)
        self.assertIn(source, self.output.read_text())
        self.assertIn(":x: Required JaCoCo reports are missing", self.output.read_text())

    def test_other_module_report_does_not_replace_missing_report(self):
        """A report for another module must not hide missing data for the changed module."""
        source = self.add_module()
        self.write_report("youtrackdb-core")
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Missing JaCoCo report for module `gremlin-core`", stdout)
        self.assertNotIn("PASSED", stdout)

    def test_integration_report_does_not_replace_unit_report(self):
        """An integration report must not satisfy the required unit-report path."""
        source = self.add_module()
        self.write_report("gremlin-core-it")
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn(".coverage/reports/gremlin-core/jacoco.xml", stdout)

    def test_missing_reports_name_each_module_and_file(self):
        """A mixed diff must list each missing module, report path, and changed file."""
        copied = self.add_module()
        core = self.add_module("core", "youtrackdb-core")
        code, stdout = self.run_gate({copied: {1}, core: {1}})
        self.assertEqual(1, code)
        for source in (copied, core):
            self.assertIn(source, stdout)
            self.assertIn(source, self.output.read_text())
        self.assertIn(".coverage/reports/youtrackdb-core/jacoco.xml", stdout)

    def test_artifact_id_not_module_directory_selects_report(self):
        """Both naming styles and an unrelated artifact name must resolve from the module POM."""
        for module, artifact in (
            ("core", "youtrackdb-core"), ("gremlin-test", "gremlin-test"),
            ("custom-module", "different-artifact"),
        ):
            with self.subTest(module=module):
                source = self.add_module(module, artifact)
                self.write_report(artifact)
                code, stdout = self.run_gate({source: {1}})
                self.assertEqual(0, code)
                self.assertIn("100.0% (1/1 lines)", stdout)

    def test_nested_reactor_module_requires_report(self):
        """Recursive module declarations must identify tested modules below an aggregator."""
        self.add_module("group", tests=False)
        self.write_pom(Path("group"), "group-parent", ["nested"])
        self.write_pom(Path("group/nested"), "nested-artifact")
        Path("group/nested/src/test/java").mkdir(parents=True)
        source = "group/nested/src/main/java/com/example/Example.java"
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("module `group/nested`", stdout)
        self.assertIn("nested-artifact/jacoco.xml", stdout)

    def test_existing_report_without_changed_file_passes(self):
        """An interface or excluded parser absent from an existing module report must pass."""
        source = self.add_module()
        self.write_report(source="Other.java", ci=0, cb=0)
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(0, code)
        self.assertIn("no coverable lines in diff", stdout)
        self.assertIn("PASSED: All coverage meets thresholds", stdout)

    def test_copied_module_uncovered_lines_fail(self):
        """Copied main Java sources must fail the shared line and branch thresholds."""
        source = self.add_module()
        self.write_report(ci=0, cb=0)
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Line coverage: FAILED", stdout)
        self.assertIn("Branch coverage: FAILED", stdout)
        self.assertIn(source, self.output.read_text())

    def test_required_report_omitted_by_discovery_still_fails_for_uncovered_lines(self):
        """Directory-read errors hidden by glob must not skip an uncovered required report."""
        source = self.add_module()
        self.write_report(ci=0, cb=0)
        with patch.object(GATE.glob, "glob", return_value=[]):
            code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Found 1 JaCoCo report(s)", stdout)
        self.assertIn("Line coverage: FAILED", stdout)
        self.assertNotIn("Skipping coverage gate", stdout)

    def test_required_report_omitted_by_discovery_is_measured_when_covered(self):
        """A readable required report must be measured even when glob cannot list its directory."""
        source = self.add_module()
        self.write_report()
        with patch.object(GATE.glob, "glob", return_value=[]):
            code, stdout = self.run_gate({source: {1}})
        self.assertEqual(0, code)
        self.assertIn("100.0% (1/1 lines)", stdout)
        self.assertNotIn("Skipping coverage gate", stdout)

    def test_core_uncovered_lines_fail(self):
        """YouTrackDB main Java sources must still fail when line and branch coverage are zero."""
        source = self.add_module("core", "youtrackdb-core")
        self.write_report("youtrackdb-core", ci=0, cb=0)
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Line coverage: FAILED", stdout)
        self.assertIn(source, self.output.read_text())

    def test_branch_threshold_applies_to_copied_module(self):
        """Full line coverage must not hide an uncovered branch in a copied module."""
        source = self.add_module()
        self.write_report(cb=0)
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(1, code)
        self.assertIn("Line coverage: PASSED", stdout)
        self.assertIn("Branch coverage: FAILED", stdout)

    def test_module_without_java_tests_needs_no_report(self):
        """A module with only non-Java test sources must not require a JaCoCo report."""
        source = self.add_module(tests=False)
        Path("gremlin-core/src/test/resources").mkdir(parents=True)
        code, stdout = self.run_gate({source: {1}})
        self.assertEqual(0, code)
        self.assertIn("No JaCoCo XML files found", stdout)

    def test_non_reactor_source_needs_no_report(self):
        """A tested directory with a POM outside the reactor must not require a report."""
        self.write_pom(Path("outside"), "outside")
        Path("outside/src/test/java").mkdir(parents=True)
        code, stdout = self.run_gate({"outside/src/main/java/Example.java": {1}})
        self.assertEqual(0, code)
        self.assertIn("No JaCoCo XML files found", stdout)

    def test_changed_test_source_needs_no_report(self):
        """A test-only Java diff must not require a module production coverage report."""
        self.add_module()
        code, stdout = self.run_gate({"gremlin-core/src/test/java/ExampleTest.java": {1}})
        self.assertEqual(0, code)
        self.assertIn("No JaCoCo XML files found", stdout)

    def test_custom_coverage_directory_is_used(self):
        """The report-presence check must honor the caller's coverage directory."""
        source = self.add_module()
        report = self.write_report()
        destination = Path("custom/reports/gremlin-core/jacoco.xml")
        destination.parent.mkdir(parents=True)
        report.rename(destination)
        required = GATE.get_required_reports({source: {1}}, "custom/reports")
        self.assertEqual([], GATE.get_missing_reports(required))
        required = GATE.get_required_reports({source: {1}}, ".coverage/reports")
        self.assertTrue(GATE.get_missing_reports(required))

    def test_empty_diff_keeps_the_no_java_skip(self):
        """A change without Java files must skip without requiring reports or a reactor lookup."""
        Path("pom.xml").unlink()
        code, stdout = self.run_gate({})
        self.assertEqual(0, code)
        self.assertIn("No changed Java files found. Skipping coverage gate.", stdout)
        self.assertIn("No changed Java files", self.output.read_text())


if __name__ == "__main__":
    unittest.main()
