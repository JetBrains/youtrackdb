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


def mark_module_built(root, module_path):
    (root / module_path / "target").mkdir(parents=True, exist_ok=True)


def run_gate(cwd, *arguments, scan_root=None, include_root=True):
    command = [sys.executable, str(_SCRIPT)]
    if include_root:
        command.extend(("--root", str(scan_root or cwd)))
    command.extend(arguments)
    return subprocess.run(
        command,
        cwd=cwd,
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
            mark_module_built(root, module)
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
        mark_module_built(root, "integration-only")
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
        mark_module_built(root, "missing")
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
        mark_module_built(root, "plugins/nested/module")
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


def test_failsafe_default_source_names_create_expectations():
    """Every default Failsafe source name requires a report from a built module."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        names = ("ITStartsWith.java", "EndsWithIT.java", "EndsWithITCase.java")
        for index, name in enumerate(names):
            module = f"missing-{index}"
            write_it_source(root, module, name=name)
            mark_module_built(root, module)
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("default Failsafe names exit non-zero", result.returncode != 0)
        for index, name in enumerate(names):
            check(f"{name} creates an expectation", f"missing-{index}" in result.stdout)


def test_integration_source_directory_creates_no_expectation():
    """A directory with an integration test name does not require a report."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        source = root / "directory-only" / "src" / "test" / "java" / "LooksLikeIT.java"
        source.mkdir(parents=True)
        mark_module_built(root, "directory-only")
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("integration-style directory exits zero", result.returncode == 0)
        check("directory module has no missing error", "expected module" not in result.stdout)


def test_integration_source_under_target_creates_no_expectation():
    """A generated integration source under target does not require another report."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        module = "copied/target/generated"
        write_it_source(root, module, name="GeneratedIT.java")
        mark_module_built(root, module)
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("generated integration source exits zero", result.returncode == 0)
        check("generated module has no missing error", "expected module" not in result.stdout)


def test_integration_source_without_target_creates_no_expectation():
    """An unbuilt module with an integration source does not require a report."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        write_it_source(root, "unbuilt")
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("unbuilt integration module exits zero", result.returncode == 0)
        check("unbuilt module has no missing error", "expected module" not in result.stdout)


def test_it_named_resource_creates_no_expectation():
    """An integration-style file under test resources does not require a report."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        resource = root / "resources-only" / "src" / "test" / "resources" / "FakeIT.java"
        resource.parent.mkdir(parents=True)
        resource.write_text("not a Java source\n", encoding="utf-8")
        mark_module_built(root, "resources-only")
        write_report(root, "failsafe", module_path="reporting")
        result = run_gate(root, "--require", "failsafe")
        check("integration-style resource exits zero", result.returncode == 0)
        check("resource module has no missing error", "expected module" not in result.stdout)


def test_missing_root_option():
    """The command fails clearly when the required scan root option is absent."""
    with tempfile.TemporaryDirectory() as directory:
        result = run_gate(directory, "--require", "failsafe", include_root=False)
        check("missing root exits non-zero", result.returncode != 0)
        check("missing root names required option", "--root" in result.stderr)


def test_non_directory_root():
    """The command rejects a scan root that names a regular file."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        file_path = root / "report-root.txt"
        file_path.write_text("not a directory\n", encoding="utf-8")
        result = run_gate(root, "--require", "failsafe", scan_root=file_path)
        check("file root exits non-zero", result.returncode != 0)
        check("file root explains directory requirement", "not a directory" in result.stderr)


def test_nonexistent_root():
    """The command rejects a scan root path that does not exist."""
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        result = run_gate(root, "--require", "failsafe", scan_root=root / "missing")
        check("nonexistent root exits non-zero", result.returncode != 0)
        check("nonexistent root explains absence", "does not exist" in result.stderr)


def test_root_controls_scan_from_different_working_directory():
    """The explicit root produces the same failure from another working directory."""
    with tempfile.TemporaryDirectory() as directory:
        fixture = pathlib.Path(directory)
        root = fixture / "scan-root"
        other_cwd = fixture / "other-cwd"
        root.mkdir()
        other_cwd.mkdir()
        write_it_source(root, "missing")
        mark_module_built(root, "missing")
        write_report(root, "failsafe", module_path="reporting")

        from_root = run_gate(root, "--require", "failsafe")
        from_other = run_gate(other_cwd, "--require", "failsafe", scan_root=root)
        check("root-controlled scans both fail", from_root.returncode == from_other.returncode == 1)
        check(
            "root-controlled scans name module",
            "expected module(s): missing" in from_other.stdout,
        )
        check("root-controlled scan output matches", from_root.stdout == from_other.stdout)


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
