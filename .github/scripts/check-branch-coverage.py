#!/usr/bin/env python3
"""Check branch coverage of new/changed lines using JaCoCo XML reports.

Parses git diff to identify changed lines, then reads JaCoCo XML line-level
branch data (mb/cb attributes) to compute branch coverage for only those lines.

Usage:
    python check-branch-coverage.py --threshold 85 --compare-branch origin/develop \
        --coverage-dir .coverage/reports
"""

import argparse
import glob
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def get_changed_lines(compare_branch):
    """Parse git diff to get changed line numbers per file.

    Returns:
        dict: {filepath: set of line numbers}
    """
    result = subprocess.run(
        [
            "git", "diff", f"{compare_branch}...HEAD",
            "--unified=0", "--diff-filter=ACM", "--no-color", "--", "*.java",
        ],
        capture_output=True,
        text=True,
        check=True,
    )

    changed = {}
    current_file = None

    for line in result.stdout.split("\n"):
        if line.startswith("+++ b/"):
            current_file = line[6:]
            changed.setdefault(current_file, set())
        elif line.startswith("@@ ") and current_file is not None:
            match = re.search(r"\+(\d+)(?:,(\d+))?", line)
            if match:
                start = int(match.group(1))
                count = int(match.group(2)) if match.group(2) else 1
                for i in range(start, start + count):
                    changed[current_file].add(i)

    return changed


def get_branch_data(xml_files, changed_lines):
    """Extract branch coverage data for changed lines from JaCoCo XMLs.

    JaCoCo XML line elements have:
        mb = missed branches, cb = covered branches

    Returns:
        tuple: (total_covered, total_missed)
    """
    total_missed = 0
    total_covered = 0

    for xml_path in xml_files:
        tree = ET.parse(xml_path)
        for package in tree.findall(".//package"):
            pkg_path = package.get("name", "")
            for sourcefile in package.findall("sourcefile"):
                src_name = sourcefile.get("name")
                suffix = f"{pkg_path}/{src_name}"

                matching_file = None
                for f in changed_lines:
                    if f.endswith(suffix):
                        matching_file = f
                        break
                if not matching_file:
                    continue

                file_changed_lines = changed_lines[matching_file]
                for line_elem in sourcefile.findall("line"):
                    line_nr = int(line_elem.get("nr"))
                    if line_nr in file_changed_lines:
                        mb = int(line_elem.get("mb", "0"))
                        cb = int(line_elem.get("cb", "0"))
                        if mb + cb > 0:
                            total_missed += mb
                            total_covered += cb

    return total_covered, total_missed


def main():
    parser = argparse.ArgumentParser(
        description="Check branch coverage of new/changed lines"
    )
    parser.add_argument(
        "--threshold",
        type=float,
        required=True,
        help="Minimum branch coverage percentage",
    )
    parser.add_argument(
        "--compare-branch",
        required=True,
        help="Git branch to compare against (e.g. origin/develop)",
    )
    parser.add_argument(
        "--coverage-dir",
        required=True,
        help="Directory containing JaCoCo XML reports",
    )
    args = parser.parse_args()

    changed_lines = get_changed_lines(args.compare_branch)
    if not changed_lines:
        print("No changed Java files found. Skipping branch coverage check.")
        return

    xml_files = glob.glob(f"{args.coverage_dir}/**/jacoco.xml", recursive=True)
    if not xml_files:
        print("No JaCoCo XML files found. Skipping branch coverage check.")
        return

    print(f"Found {len(xml_files)} JaCoCo report(s)")
    print(f"Checking branch coverage for {len(changed_lines)} changed file(s)")

    covered, missed = get_branch_data(xml_files, changed_lines)
    total = covered + missed

    if total == 0:
        print("No branches found in changed lines. Skipping.")
        return

    pct = (covered / total) * 100
    print(f"Branch coverage of new/changed lines: {pct:.1f}% ({covered}/{total} branches)")

    if pct < args.threshold:
        print(f"FAILED: Branch coverage {pct:.1f}% is below threshold {args.threshold}%")
        sys.exit(1)
    else:
        print(f"PASSED: Branch coverage {pct:.1f}% meets threshold {args.threshold}%")


if __name__ == "__main__":
    main()
