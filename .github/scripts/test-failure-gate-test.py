#!/usr/bin/env python3
"""Stand-alone tests for test-failure-gate.py."""

import os
import pathlib
import subprocess
import sys
import tempfile

_DEFAULT_SCRIPT = pathlib.Path(__file__).with_name("test-failure-gate.py")
_SCRIPT = pathlib.Path(os.environ.get("TEST_FAILURE_GATE_SCRIPT", _DEFAULT_SCRIPT)).resolve()
_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


def write_raw_report(root, kind, content, name="ExampleSuite", module_path="module"):
    report_dir = root / module_path / "target" / f"{kind}-reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = report_dir / f"TEST-{name}.xml"
    report.write_text(content, encoding="utf-8")
    return report


def write_report(
    root,
    kind,
    name="ExampleSuite",
    tests=1,
    failures=0,
    errors=0,
    skipped=0,
    module_path="module",
):
    content = (
        f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"></testsuite>'
    )
    return write_raw_report(root, kind, content, name, module_path)


def write_it_source(root, module_path, name="ExampleIT.java"):
    source = root / module_path / "src" / "test" / "java" / name
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text("// Integration test fixture.\n", encoding="utf-8")
    return source


def run_gate(root, *arguments):
    return subprocess.run(
        [sys.executable, str(_SCRIPT), *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )


def test_allowed_missing_module_suppresses_only_named_module():
    """An allowance suppresses one module while another missing module still fails."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for module in ("allowed/module", "missing/module", "reporting/module"):
            write_it_source(root, module)
        write_report(root, "failsafe", module_path="reporting/module")
        result = run_gate(
            root,
            "--require",
            "failsafe",
            "--allow-missing-module",
            "allowed/module",
        )
        check("unallowed missing module exits non-zero", result.returncode != 0)
        check("unallowed missing module is named", "missing/module" in result.stdout)
        check("allowed missing module is omitted", "allowed/module" not in result.stdout)


def test_both_expected_modules_report():
    """Two expected modules pass when both produce required reports."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for module in ("first", "second"):
            write_it_source(root, module)
            write_report(root, "failsafe", module_path=module)
        result = run_gate(root, "--require", "failsafe")
        check("two reporting expected modules exit zero", result.returncode == 0)
        check("two reporting modules contribute tests", "2 test(s)" in result.stdout)


def test_both_kinds_required_with_one_present():
    """Comma-separated requirements fail when only one report kind exists."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire")
        result = run_gate(root, "--require", "failsafe,surefire")
        check("one of two kinds exits non-zero", result.returncode != 0)
        check("missing member identifies failsafe", "::error::No failsafe" in result.stdout)


def test_clean_passing_report_set():
    """Clean reports for both required kinds pass and print verified counts."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="IntegrationSuite", tests=2)
        write_report(root, "surefire", name="UnitSuite", tests=3)
        result = run_gate(root, "--require", "failsafe", "--require", "surefire")
        check("clean report set exits zero", result.returncode == 0)
        check("clean report set prints counts", "5 test(s)" in result.stdout)


def test_deeply_nested_report():
    """Recursive scanning finds reports below several module directory levels."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(
            root,
            "failsafe",
            name="DeepSuite",
            tests=2,
            module_path="outer/inner/module",
        )
        result = run_gate(root, "--require", "failsafe")
        check("deeply nested report exits zero", result.returncode == 0)
        check("deeply nested report contributes counts", "2 test(s)" in result.stdout)


def test_expected_module_missing_non_required_kind():
    """A missing expected Failsafe module passes when only Surefire is required."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_it_source(root, "integration-only")
        write_report(root, "surefire", module_path="unit")
        result = run_gate(root, "--require", "surefire")
        check("non-required missing module exits zero", result.returncode == 0)
        check(
            "non-required kind emits no missing error",
            "missing for expected" not in result.stdout,
        )


def test_expected_module_without_required_report():
    """An expected module without required reports fails and names that module."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_it_source(root, "missing")
        write_it_source(root, "reporting")
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("missing expected module exits non-zero", result.returncode != 0)
        check("missing expected module is named", "expected module(s): missing" in result.stdout)


def test_failing_optional_kind():
    """Every discovered report controls success even when its kind is not required."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="PassingIntegration")
        write_report(root, "surefire", name="FailingUnit", failures=1)
        result = run_gate(root, "--require", "failsafe")
        check("failing optional kind exits non-zero", result.returncode != 0)
        check("failing optional kind names suite", "::error::Suite FailingUnit" in result.stdout)


def test_malformed_xml_file():
    """Malformed Extensible Markup Language content causes a parsing failure."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        report = write_report(root, "surefire")
        report.write_text("not xml", encoding="utf-8")
        result = run_gate(root, "--require", "surefire")
        check("malformed XML exits non-zero", result.returncode != 0)
        check("malformed XML emits annotation", "::error::Unable to parse" in result.stdout)


def test_missing_attribute_with_nested_failure():
    """A nested failure fails when its suite omits aggregate count attributes."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        content = (
            '<testsuite name="NestedFailure">'
            '<testcase name="fails"><failure message="boom"/></testcase>'
            '</testsuite>'
        )
        write_raw_report(root, "failsafe", content, name="NestedFailure")
        result = run_gate(root, "--require", "failsafe")
        check("nested failure without count exits non-zero", result.returncode != 0)
        check("nested failure is counted", "1 failure(s)" in result.stdout)


def test_missing_attribute_with_nested_error():
    """A nested error fails when its suite omits aggregate count attributes."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        content = (
            '<testsuite name="NestedError">'
            '<testcase name="errors"><error message="boom"/></testcase>'
            '</testsuite>'
        )
        write_raw_report(root, "surefire", content, name="NestedError")
        result = run_gate(root, "--require", "surefire")
        check("nested error without count exits non-zero", result.returncode != 0)
        check("nested error is counted", "1 error(s)" in result.stdout)


def test_nested_expected_module_identity():
    """A deeply nested expected module uses its full relative path as identity."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_it_source(root, "plugins/nested/module")
        write_it_source(root, "reporting")
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("nested missing module exits non-zero", result.returncode != 0)
        check("nested missing module keeps identity", "plugins/nested/module" in result.stdout)


def test_non_numeric_attribute_with_nested_error():
    """A non-numeric aggregate remains invalid when a nested error exists."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        content = (
            '<testsuite name="InvalidCount" tests="1" errors="many">'
            '<testcase name="errors"><error message="boom"/></testcase>'
            '</testsuite>'
        )
        write_raw_report(root, "surefire", content, name="InvalidCount")
        result = run_gate(root, "--require", "surefire")
        check("non-numeric count exits non-zero", result.returncode != 0)
        check("non-numeric count emits annotation", "::error::Unable to parse" in result.stdout)


def test_report_with_errors():
    """A positive error count fails and names the affected suite."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire", name="ErrorSuite", errors=2)
        result = run_gate(root, "--require", "surefire")
        check("error count exits non-zero", result.returncode != 0)
        check("error annotation names suite", "::error::Suite ErrorSuite" in result.stdout)


def test_report_with_failures():
    """A positive failure count fails and names the affected suite."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="FailingSuite", failures=1)
        result = run_gate(root, "--require", "failsafe")
        check("failure count exits non-zero", result.returncode != 0)
        check("failure annotation names suite", "::error::Suite FailingSuite" in result.stdout)


def test_required_kind_absent():
    """A required kind with no files fails and identifies that kind."""
    with tempfile.TemporaryDirectory() as directory:
        result = run_gate(directory, "--require", "failsafe")
        check("absent kind exits non-zero", result.returncode != 0)
        check("absent kind annotation identifies failsafe", "::error::No failsafe" in result.stdout)


def test_required_kind_skipped_tests_only():
    """A required kind passes when every declared test is skipped."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(
            root,
            "failsafe",
            name="RequiredSkippedSuite",
            tests=3,
            skipped=3,
        )
        result = run_gate(root, "--require", "failsafe")
        check("required skipped-only suite exits zero", result.returncode == 0)
        check("required skipped-only suite prints counts", "3 skipped" in result.stdout)


def test_required_kind_with_zero_tests():
    """A required kind fails when its reports collectively declare zero tests."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="EmptyIntegration", tests=0)
        result = run_gate(root, "--require", "failsafe")
        check("required zero-test kind exits non-zero", result.returncode != 0)
        check(
            "required zero-test kind emits annotation",
            "::error::Required failsafe reports contained zero tests." in result.stdout,
        )


def test_skipped_tests_only():
    """A suite containing only skipped tests passes with no failures or errors."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire", name="SkippedSuite", tests=4, skipped=4)
        result = run_gate(root, "--require", "surefire")
        check("skipped-only suite exits zero", result.returncode == 0)
        check("skipped-only suite prints skipped count", "4 skipped" in result.stdout)


def test_testsuites_wrapper():
    """A testsuites wrapper exposes every nested suite and its aggregate counts."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        content = (
            '<testsuites>'
            '<testsuite name="PassingSuite" tests="2" failures="0" errors="0"/>'
            '<testsuite name="WrappedFailure" tests="1" failures="1" errors="0"/>'
            '</testsuites>'
        )
        write_raw_report(root, "failsafe", content, name="WrappedSuites")
        result = run_gate(root, "--require", "failsafe")
        check("wrapped failing suite exits non-zero", result.returncode != 0)
        check("wrapped failing suite is named", "::error::Suite WrappedFailure" in result.stdout)


def test_truncated_xml_file():
    """A truncated report causes a parsing failure instead of a warning."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        report = write_report(root, "failsafe")
        report.write_text('<testsuite name="CutOff" tests="1">', encoding="utf-8")
        result = run_gate(root, "--require", "failsafe")
        check("truncated XML exits non-zero", result.returncode != 0)
        check("truncated XML emits annotation", "::error::Unable to parse" in result.stdout)


def main():
    tests = sorted(
        (
            function
            for name, function in globals().items()
            if name.startswith("test_") and callable(function)
        ),
        key=lambda function: function.__name__,
    )
    for test in tests:
        test()
    if _failures:
        print(f"\n{len(_failures)} check(s) failed.")
        return 1
    print(f"\nAll checks passed across {len(tests)} test function(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
