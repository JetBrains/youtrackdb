#!/usr/bin/env python3
"""Fail a workflow when required Maven test reports are missing or unsuccessful."""

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

REPORT_PATTERNS = {
    "failsafe": "**/target/failsafe-reports/TEST-*.xml",
    "surefire": "**/target/surefire-reports/TEST-*.xml",
}
COUNT_ATTRIBUTES = ("tests", "failures", "errors", "skipped")


def workflow_error(message):
    """Emit a GitHub Actions error annotation."""
    message = str(message).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error::{message}")


def required_kinds(values):
    """Expand repeated and comma-separated report kind arguments."""
    kinds = []
    for value in values:
        for kind in value.split(","):
            kind = kind.strip().lower()
            if kind not in REPORT_PATTERNS:
                raise argparse.ArgumentTypeError(
                    f"unknown report kind '{kind}'. Choose failsafe or surefire"
                )
            if kind not in kinds:
                kinds.append(kind)
    if not kinds:
        raise argparse.ArgumentTypeError("at least one report kind is required")
    return kinds


def suite_elements(root):
    """Return every suite represented by a report document."""
    if root.tag == "testsuite":
        return [root]
    if root.tag == "testsuites":
        return list(root.iter("testsuite"))
    raise ValueError(f"unexpected root element <{root.tag}>")


def parse_report(path):
    """Parse one report and return its suites with validated counts."""
    root = ET.parse(path).getroot()
    suites = []
    for suite in suite_elements(root):
        counts = {}
        for attribute in COUNT_ATTRIBUTES:
            raw_value = suite.get(attribute, "0")
            value = int(raw_value)
            if value < 0:
                raise ValueError(f"negative {attribute} count '{raw_value}'")
            counts[attribute] = value

        # Report writers normally provide aggregate attributes.
        # Nested elements keep the gate safe when those aggregates are incomplete.
        counts["failures"] = max(
            counts["failures"], sum(1 for _ in suite.iter("failure"))
        )
        counts["errors"] = max(
            counts["errors"], sum(1 for _ in suite.iter("error"))
        )
        suites.append((suite.get("name") or str(path), counts))
    if not suites:
        raise ValueError("report contains no test suites")
    return suites


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify required Surefire and Failsafe XML reports"
    )
    parser.add_argument(
        "--require",
        action="append",
        required=True,
        metavar="KIND",
        help="Required report kind. Repeat it or use commas for failsafe and surefire.",
    )
    args = parser.parse_args(argv)
    try:
        kinds = required_kinds(args.require)
    except argparse.ArgumentTypeError as error:
        parser.error(str(error))

    totals = {attribute: 0 for attribute in COUNT_ATTRIBUTES}
    report_total = 0
    suite_total = 0
    failed = False
    failing_suites = []
    tests_by_kind = {kind: 0 for kind in REPORT_PATTERNS}
    parse_failed_by_kind = {kind: False for kind in REPORT_PATTERNS}

    reports_by_kind = {
        kind: sorted(pathlib.Path.cwd().glob(pattern))
        for kind, pattern in REPORT_PATTERNS.items()
    }
    for kind in kinds:
        if not reports_by_kind[kind]:
            workflow_error(f"No {kind} test reports were found.")
            failed = True

    # Required kinds control presence checks, while every discovered report controls success.
    for kind, paths in reports_by_kind.items():
        if paths:
            print(f"Found {len(paths)} {kind} report file(s).")
        report_total += len(paths)
        for path in paths:
            try:
                suites = parse_report(path)
            except (ET.ParseError, OSError, ValueError) as error:
                workflow_error(f"Unable to parse {path}: {error}")
                parse_failed_by_kind[kind] = True
                failed = True
                continue

            suite_total += len(suites)
            for suite_name, counts in suites:
                tests_by_kind[kind] += counts["tests"]
                for attribute, value in counts.items():
                    totals[attribute] += value
                if counts["failures"] > 0 or counts["errors"] > 0:
                    failing_suites.append(
                        (suite_name, path, counts["failures"], counts["errors"])
                    )

    for kind in kinds:
        if (
            reports_by_kind[kind]
            and not parse_failed_by_kind[kind]
            and tests_by_kind[kind] == 0
        ):
            workflow_error(f"Required {kind} reports contained zero tests.")
            failed = True

    if failing_suites:
        failed = True
        for suite_name, path, failures, errors in failing_suites:
            workflow_error(
                f"Suite {suite_name} in {path} reported "
                f"{failures} failure(s) and {errors} error(s)."
            )

    print(
        f"Verified {report_total} report file(s) and {suite_total} suite(s): "
        f"{totals['tests']} test(s), {totals['failures']} failure(s), "
        f"{totals['errors']} error(s), and {totals['skipped']} skipped."
    )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
