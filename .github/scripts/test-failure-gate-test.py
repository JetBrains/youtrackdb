#!/usr/bin/env python3
"""Stand-alone tests for test-failure-gate.py."""

import pathlib
import subprocess
import sys
import tempfile

_SCRIPT = pathlib.Path(__file__).with_name("test-failure-gate.py").resolve()
_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


def write_report(root, kind, name="ExampleSuite", tests=1, failures=0, errors=0, skipped=0):
    report_dir = root / "module" / "target" / f"{kind}-reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = report_dir / f"TEST-{name}.xml"
    report.write_text(
        f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"></testsuite>',
        encoding="utf-8",
    )
    return report


def run_gate(root, *arguments):
    return subprocess.run(
        [sys.executable, str(_SCRIPT), *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )


def test_clean_passing_report_set():
    """Clean reports for both required kinds pass and print verified counts."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="IntegrationSuite", tests=2)
        write_report(root, "surefire", name="UnitSuite", tests=3)
        result = run_gate(root, "--require", "failsafe", "--require", "surefire")
        check("clean report set exits zero", result.returncode == 0)
        check("clean report set prints counts", "5 test(s)" in result.stdout)


def test_report_with_failures():
    """A positive failure count fails and names the affected suite."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "failsafe", name="FailingSuite", failures=1)
        result = run_gate(root, "--require", "failsafe")
        check("failure count exits non-zero", result.returncode != 0)
        check("failure annotation names suite", "::error::Suite FailingSuite" in result.stdout)


def test_report_with_errors():
    """A positive error count fails and names the affected suite."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire", name="ErrorSuite", errors=2)
        result = run_gate(root, "--require", "surefire")
        check("error count exits non-zero", result.returncode != 0)
        check("error annotation names suite", "::error::Suite ErrorSuite" in result.stdout)


def test_required_kind_absent():
    """A required kind with no files fails and identifies that kind."""
    with tempfile.TemporaryDirectory() as directory:
        result = run_gate(directory, "--require", "failsafe")
        check("absent kind exits non-zero", result.returncode != 0)
        check("absent kind annotation identifies failsafe", "::error::No failsafe" in result.stdout)


def test_malformed_xml_file():
    """Malformed Extensible Markup Language content causes a parsing failure."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        report = write_report(root, "surefire")
        report.write_text("not xml", encoding="utf-8")
        result = run_gate(root, "--require", "surefire")
        check("malformed XML exits non-zero", result.returncode != 0)
        check("malformed XML emits annotation", "::error::Unable to parse" in result.stdout)


def test_truncated_xml_file():
    """A truncated report causes a parsing failure instead of a warning."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        report = write_report(root, "failsafe")
        report.write_text('<testsuite name="CutOff" tests="1">', encoding="utf-8")
        result = run_gate(root, "--require", "failsafe")
        check("truncated XML exits non-zero", result.returncode != 0)
        check("truncated XML emits annotation", "::error::Unable to parse" in result.stdout)


def test_both_kinds_required_with_one_present():
    """Comma-separated requirements fail when only one report kind exists."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire")
        result = run_gate(root, "--require", "failsafe,surefire")
        check("one of two kinds exits non-zero", result.returncode != 0)
        check("missing member identifies failsafe", "::error::No failsafe" in result.stdout)


def test_skipped_tests_only():
    """A suite containing only skipped tests passes with no failures or errors."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_report(root, "surefire", name="SkippedSuite", tests=4, skipped=4)
        result = run_gate(root, "--require", "surefire")
        check("skipped-only suite exits zero", result.returncode == 0)
        check("skipped-only suite prints skipped count", "4 skipped" in result.stdout)


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
