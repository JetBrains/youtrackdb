#!/usr/bin/env python3
"""Collect test counts from Surefire/Failsafe XML reports.

Walks module directories, parses TEST-*.xml report files, and outputs
a JSON file with per-module test counts (number of test methods).

Usage:
    python collect-test-counts.py --output test-counts.json
"""

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from glob import glob


def count_tests_in_dir(report_dir):
    """Count total test methods from TEST-*.xml files in a report directory.

    Each TEST-*.xml has a root <testsuite tests="N" ...> element where N
    is the number of test methods in that class.
    """
    total = 0
    for xml_path in sorted(glob(os.path.join(report_dir, "TEST-*.xml"))):
        try:
            tree = ET.parse(xml_path)
            root = tree.getroot()
            tests = int(root.get("tests", "0"))
            total += tests
        except (ET.ParseError, ValueError, OSError) as e:
            print(f"  Warning: failed to parse {xml_path}: {e}",
                  file=sys.stderr)
    return total


def collect_counts():
    """Collect test counts per module from surefire and failsafe reports.

    Returns:
        dict: {module_name: test_count}
    """
    counts = {}

    report_dirs = sorted(
        glob("**/target/surefire-reports", recursive=True)
        + glob("**/target/failsafe-reports", recursive=True)
    )

    for report_dir in report_dirs:
        parts = report_dir.replace("\\", "/").split("/")
        try:
            target_idx = parts.index("target")
        except ValueError:
            continue
        module = "/".join(parts[:target_idx]) if target_idx > 0 else "root"

        count = count_tests_in_dir(report_dir)
        counts[module] = counts.get(module, 0) + count

    return counts


def main():
    parser = argparse.ArgumentParser(
        description="Collect test counts from Surefire/Failsafe XML reports"
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Output JSON file path",
    )
    args = parser.parse_args()

    counts = collect_counts()
    total = sum(counts.values())

    result = {"modules": counts, "total": total}

    out_dir = os.path.dirname(args.output)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.output, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)

    print(f"Collected test counts for {len(counts)} module(s), total: {total}")
    for module, count in sorted(counts.items()):
        print(f"  {module}: {count}")


if __name__ == "__main__":
    main()
