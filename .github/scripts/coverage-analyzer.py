#!/usr/bin/env python3
"""Per-package coverage analyzer for JaCoCo XML reports.

Parses JaCoCo XML reports and produces per-package overall coverage summaries.
Unlike coverage-gate.py (which checks only changed lines in PRs), this script
computes totals across all lines in each package and generates a sorted table
of packages by uncovered line count.

Usage:
    python coverage-analyzer.py --coverage-dir .coverage/reports/youtrackdb-core
"""

import argparse
import glob
import sys
import xml.etree.ElementTree as ET


def parse_jacoco_reports(coverage_dir):
    """Parse all JaCoCo XML files under coverage_dir.

    Returns:
        dict: {package_name: {"line_missed": int, "line_covered": int,
               "branch_missed": int, "branch_covered": int}}
    """
    xml_files = glob.glob(f"{coverage_dir}/**/jacoco.xml", recursive=True)
    if not xml_files:
        print(f"No JaCoCo XML files found under {coverage_dir}", file=sys.stderr)
        sys.exit(1)

    packages = {}

    for xml_path in xml_files:
        tree = ET.parse(xml_path)
        root = tree.getroot()

        for package in root.findall(".//package"):
            raw_name = package.get("name", "")
            # Convert from path-style (com/jetbrains/...) to dot-style
            pkg_name = raw_name.replace("/", ".")

            line_missed = 0
            line_covered = 0
            branch_missed = 0
            branch_covered = 0

            for counter in package.findall("counter"):
                ctype = counter.get("type")
                missed = int(counter.get("missed", "0"))
                covered = int(counter.get("covered", "0"))
                if ctype == "LINE":
                    line_missed = missed
                    line_covered = covered
                elif ctype == "BRANCH":
                    branch_missed = missed
                    branch_covered = covered

            total_lines = line_missed + line_covered
            if total_lines == 0:
                continue

            if pkg_name in packages:
                # Merge: sum covered/missed across reports
                prev = packages[pkg_name]
                prev["line_missed"] += line_missed
                prev["line_covered"] += line_covered
                prev["branch_missed"] += branch_missed
                prev["branch_covered"] += branch_covered
            else:
                packages[pkg_name] = {
                    "line_missed": line_missed,
                    "line_covered": line_covered,
                    "branch_missed": branch_missed,
                    "branch_covered": branch_covered,
                }

    return packages


def compute_percentage(covered, total):
    """Compute coverage percentage, returning 100.0 if total is 0."""
    if total == 0:
        return 100.0
    return covered / total * 100


def generate_table(packages):
    """Generate a markdown table sorted by uncovered lines descending.

    Returns:
        str: markdown table
    """
    rows = []
    for pkg_name, data in packages.items():
        total_lines = data["line_missed"] + data["line_covered"]
        total_branches = data["branch_missed"] + data["branch_covered"]
        line_pct = compute_percentage(data["line_covered"], total_lines)
        branch_pct = compute_percentage(data["branch_covered"], total_branches)
        rows.append((
            pkg_name,
            line_pct,
            branch_pct,
            data["line_missed"],
            total_lines,
        ))

    # Sort by uncovered lines descending
    rows.sort(key=lambda r: r[3], reverse=True)

    lines = []
    lines.append("| Package | Line% | Branch% | Uncovered Lines | Total Lines |")
    lines.append("|---------|-------|---------|-----------------|-------------|")
    for pkg_name, line_pct, branch_pct, uncov, total in rows:
        lines.append(
            f"| {pkg_name} | {line_pct:.1f}% | {branch_pct:.1f}% "
            f"| {uncov} | {total} |"
        )

    return "\n".join(lines)


def generate_aggregate(packages):
    """Compute and format aggregate totals across all packages.

    Returns:
        str: aggregate summary lines
    """
    total_line_covered = sum(d["line_covered"] for d in packages.values())
    total_line_missed = sum(d["line_missed"] for d in packages.values())
    total_branch_covered = sum(d["branch_covered"] for d in packages.values())
    total_branch_missed = sum(d["branch_missed"] for d in packages.values())

    total_lines = total_line_covered + total_line_missed
    total_branches = total_branch_covered + total_branch_missed

    line_pct = compute_percentage(total_line_covered, total_lines)
    branch_pct = compute_percentage(total_branch_covered, total_branches)

    lines = []
    lines.append("## Aggregate Totals")
    lines.append("")
    lines.append(
        f"- **Line coverage**: {line_pct:.1f}% "
        f"({total_line_covered}/{total_lines} lines covered, "
        f"{total_line_missed} uncovered)"
    )
    lines.append(
        f"- **Branch coverage**: {branch_pct:.1f}% "
        f"({total_branch_covered}/{total_branches} branches covered, "
        f"{total_branch_missed} uncovered)"
    )
    lines.append(f"- **Packages**: {len(packages)}")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Per-package coverage analyzer for JaCoCo XML reports"
    )
    parser.add_argument(
        "--coverage-dir",
        required=True,
        help="Directory containing JaCoCo XML reports "
             "(e.g., .coverage/reports/youtrackdb-core)",
    )
    args = parser.parse_args()

    packages = parse_jacoco_reports(args.coverage_dir)
    if not packages:
        print("No packages with coverable lines found.", file=sys.stderr)
        sys.exit(1)

    aggregate = generate_aggregate(packages)
    table = generate_table(packages)

    print(aggregate)
    print()
    print("## Per-Package Coverage")
    print()
    print(table)


if __name__ == "__main__":
    main()
