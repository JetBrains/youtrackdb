#!/usr/bin/env python3
"""Unified coverage gate: checks line & branch coverage of new/changed code.

Parses git diff to identify changed lines, reads JaCoCo XML reports for
line-level coverage data (mi/ci for instructions, mb/cb for branches),
enforces thresholds, and generates a markdown report with per-file tables
of uncovered lines.

Usage:
    python coverage-gate.py --line-threshold 85 --branch-threshold 70 \
        --compare-branch origin/develop \
        --coverage-dir .coverage/reports --output-md /tmp/coverage-gate.md
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

MAX_COMMENT_CHARS = 60000


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


def match_source_file(pkg_path, src_name, changed_files_by_basename):
    """Match a JaCoCo sourcefile element to a changed file path.

    Returns:
        str or None: the matched file path, or None
    """
    possible_files = changed_files_by_basename.get(src_name, [])
    if not possible_files:
        return None
    suffix = f"{pkg_path}/{src_name}"
    for f in possible_files:
        if f.endswith(suffix):
            return f
    return None


def collect_coverage_data(xml_files, changed_lines):
    """Extract line and branch coverage data for changed lines from JaCoCo XMLs.

    JaCoCo XML line elements have:
        mi = missed instructions, ci = covered instructions
        mb = missed branches, cb = covered branches

    When multiple reports cover the same source file (e.g. unit + integration
    tests), we merge by taking the max of covered values per line.

    Returns:
        dict: {filepath: {line_nr: {ci, mi, cb, mb}}}
    """
    changed_files_by_basename = {}
    for f in changed_lines:
        basename = f.split("/")[-1]
        changed_files_by_basename.setdefault(basename, []).append(f)

    # {filepath: {line_nr: {ci, mi, cb, mb}}}
    merged = {}

    for xml_path in xml_files:
        tree = ET.parse(xml_path)
        for package in tree.findall(".//package"):
            pkg_path = package.get("name", "")
            for sourcefile in package.findall("sourcefile"):
                src_name = sourcefile.get("name")
                matching_file = match_source_file(
                    pkg_path, src_name, changed_files_by_basename
                )
                if not matching_file:
                    continue

                file_changed_lines = changed_lines[matching_file]
                file_data = merged.setdefault(matching_file, {})

                for line_elem in sourcefile.findall("line"):
                    line_nr = int(line_elem.get("nr"))
                    if line_nr not in file_changed_lines:
                        continue

                    ci = int(line_elem.get("ci", "0"))
                    mi = int(line_elem.get("mi", "0"))
                    cb = int(line_elem.get("cb", "0"))
                    mb = int(line_elem.get("mb", "0"))

                    if line_nr in file_data:
                        # Merge: take max of covered, recompute missed
                        prev = file_data[line_nr]
                        new_ci = max(prev["ci"], ci)
                        new_cb = max(prev["cb"], cb)
                        # Total instructions/branches stay the same across
                        # reports for the same line, so recompute missed
                        total_inst = prev["ci"] + prev["mi"]
                        total_br = prev["cb"] + prev["mb"]
                        if ci + mi > total_inst:
                            total_inst = ci + mi
                        if cb + mb > total_br:
                            total_br = cb + mb
                        file_data[line_nr] = {
                            "ci": new_ci,
                            "mi": max(total_inst - new_ci, 0),
                            "cb": new_cb,
                            "mb": max(total_br - new_cb, 0),
                        }
                    else:
                        file_data[line_nr] = {
                            "ci": ci, "mi": mi, "cb": cb, "mb": mb,
                        }

    return merged


def format_line_ranges(line_numbers):
    """Format a sorted list of line numbers into compact ranges.

    Example: [1, 2, 3, 5, 7, 8] -> "1-3, 5, 7-8"
    """
    if not line_numbers:
        return ""
    sorted_lines = sorted(set(line_numbers))
    ranges = []
    start = prev = sorted_lines[0]
    for n in sorted_lines[1:]:
        if n == prev + 1:
            prev = n
        else:
            ranges.append(f"{start}" if start == prev else f"{start}-{prev}")
            start = prev = n
    ranges.append(f"{start}" if start == prev else f"{start}-{prev}")
    return ", ".join(ranges)


def load_assert_lines(filepath):
    """Return the set of line numbers containing Java assert statements.

    JaCoCo always reports one uncovered branch for assert statements
    (the assertion-failure path is never taken in normal tests).  These
    phantom branches are excluded from the coverage gate.
    """
    result = set()
    try:
        with open(filepath) as f:
            for i, line in enumerate(f, 1):
                stripped = line.strip()
                if stripped.startswith("assert ") or stripped.startswith("assert("):
                    result.add(i)
    except OSError:
        pass
    return result


def compute_results(coverage_data):
    """Compute per-file and aggregate line/branch coverage results.

    Returns:
        dict with keys: line_files, branch_files, line_totals, branch_totals
        Each *_files entry is a list of (filepath, covered, total, uncovered_lines)
        sorted by filepath.
    """
    line_files = []
    branch_files = []
    total_line_covered = 0
    total_line_count = 0
    total_branch_covered = 0
    total_branch_count = 0

    for filepath, lines in sorted(coverage_data.items()):
        assert_lines = load_assert_lines(filepath)
        file_line_covered = 0
        file_line_total = 0
        file_branch_covered = 0
        file_branch_total = 0
        uncovered_lines = []
        uncovered_branch_lines = []

        for line_nr, data in sorted(lines.items()):
            # Line coverage: a line is "coverable" if it has any instructions
            if data["ci"] + data["mi"] > 0:
                file_line_total += 1
                if data["ci"] > 0:
                    file_line_covered += 1
                else:
                    uncovered_lines.append(line_nr)

            # Branch coverage: a line has branches if mb + cb > 0.
            # Exclude assert statements — JaCoCo always marks the
            # assertion-failure path as an uncovered phantom branch.
            if data["cb"] + data["mb"] > 0 and line_nr not in assert_lines:
                file_branch_total += data["cb"] + data["mb"]
                file_branch_covered += data["cb"]
                if data["mb"] > 0:
                    uncovered_branch_lines.append(line_nr)

        if file_line_total > 0:
            line_files.append(
                (filepath, file_line_covered, file_line_total, uncovered_lines)
            )
            total_line_covered += file_line_covered
            total_line_count += file_line_total

        if file_branch_total > 0:
            branch_files.append(
                (filepath, file_branch_covered, file_branch_total,
                 uncovered_branch_lines)
            )
            total_branch_covered += file_branch_covered
            total_branch_count += file_branch_total

    return {
        "line_files": line_files,
        "branch_files": branch_files,
        "line_totals": (total_line_covered, total_line_count),
        "branch_totals": (total_branch_covered, total_branch_count),
    }


def generate_markdown(results, line_threshold, branch_threshold):
    """Generate markdown report with per-file coverage tables.

    Returns:
        tuple: (markdown_string, line_passed, branch_passed)
    """
    line_covered, line_total = results["line_totals"]
    branch_covered, branch_total = results["branch_totals"]

    line_pct = (line_covered / line_total * 100) if line_total > 0 else 100.0
    branch_pct = (
        (branch_covered / branch_total * 100) if branch_total > 0 else 100.0
    )

    line_passed = line_pct >= line_threshold
    branch_passed = branch_pct >= branch_threshold

    lines = []
    lines.append("<!-- coverage-gate-comment -->")
    lines.append("# Coverage Gate Results")
    lines.append(
        f"**Thresholds**: {line_threshold:.0f}% line, "
        f"{branch_threshold:.0f}% branch"
    )
    lines.append("")

    # Line coverage section
    line_icon = ":white_check_mark:" if line_passed else ":x:"
    if line_total > 0:
        lines.append(
            f"## Line Coverage: {line_icon} {line_pct:.1f}% "
            f"({line_covered}/{line_total} lines)"
        )
    else:
        lines.append(
            f"## Line Coverage: :white_check_mark: No coverable lines in diff"
        )

    if results["line_files"]:
        lines.append("")
        lines.append("| File | Coverage | Uncovered Lines |")
        lines.append("|------|----------|-----------------|")
        for filepath, covered, total, uncovered in results["line_files"]:
            pct = covered / total * 100 if total > 0 else 100.0
            icon = ":white_check_mark:" if pct >= line_threshold else ":x:"
            uncov_str = format_line_ranges(uncovered) if uncovered else "-"
            lines.append(
                f"| `{filepath}` | {icon} {pct:.1f}% ({covered}/{total}) "
                f"| {uncov_str} |"
            )
    lines.append("")

    # Branch coverage section
    branch_icon = ":white_check_mark:" if branch_passed else ":x:"
    if branch_total > 0:
        lines.append(
            f"## Branch Coverage: {branch_icon} {branch_pct:.1f}% "
            f"({branch_covered}/{branch_total} branches)"
        )
    else:
        lines.append(
            f"## Branch Coverage: :white_check_mark: "
            f"No branches in changed lines"
        )

    if results["branch_files"]:
        lines.append("")
        lines.append(
            "| File | Coverage | Lines with Uncovered Branches |"
        )
        lines.append("|------|----------|-------------------------------|")
        for filepath, covered, total, uncovered in results["branch_files"]:
            pct = covered / total * 100 if total > 0 else 100.0
            icon = (
                ":white_check_mark:" if pct >= branch_threshold else ":x:"
            )
            uncov_str = format_line_ranges(uncovered) if uncovered else "-"
            lines.append(
                f"| `{filepath}` | {icon} {pct:.1f}% ({covered}/{total}) "
                f"| {uncov_str} |"
            )

    md = "\n".join(lines) + "\n"

    # Truncate if too large
    if len(md) > MAX_COMMENT_CHARS:
        truncation_msg = (
            "\n\n> **Note**: Report truncated due to size. "
            "See CI logs for full details.\n"
        )
        md = md[: MAX_COMMENT_CHARS - len(truncation_msg)] + truncation_msg

    return md, line_passed, branch_passed


def main():
    parser = argparse.ArgumentParser(
        description="Unified coverage gate for line and branch coverage"
    )
    parser.add_argument(
        "--line-threshold",
        type=float,
        required=True,
        help="Minimum line coverage percentage",
    )
    parser.add_argument(
        "--branch-threshold",
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
    parser.add_argument(
        "--output-md",
        default=None,
        help="Path to write markdown report (optional)",
    )
    args = parser.parse_args()

    threshold_label = (
        f"{args.line_threshold:.0f}% line, "
        f"{args.branch_threshold:.0f}% branch"
    )

    changed_lines = get_changed_lines(args.compare_branch)
    if not changed_lines:
        print("No changed Java files found. Skipping coverage gate.")
        if args.output_md:
            with open(args.output_md, "w") as f:
                f.write(
                    "<!-- coverage-gate-comment -->\n"
                    "# Coverage Gate Results\n"
                    f"**Thresholds**: {threshold_label}\n\n"
                    ":white_check_mark: No changed Java files — "
                    "coverage gate skipped.\n"
                )
        return

    xml_files = glob.glob(
        f"{args.coverage_dir}/**/jacoco.xml", recursive=True
    )
    if not xml_files:
        print("No JaCoCo XML files found. Skipping coverage gate.")
        if args.output_md:
            with open(args.output_md, "w") as f:
                f.write(
                    "<!-- coverage-gate-comment -->\n"
                    "# Coverage Gate Results\n"
                    f"**Thresholds**: {threshold_label}\n\n"
                    ":warning: No JaCoCo XML reports found — "
                    "coverage gate skipped.\n"
                )
        return

    print(f"Found {len(xml_files)} JaCoCo report(s)")
    print(f"Checking coverage for {len(changed_lines)} changed file(s)")

    coverage_data = collect_coverage_data(xml_files, changed_lines)
    results = compute_results(coverage_data)

    line_covered, line_total = results["line_totals"]
    branch_covered, branch_total = results["branch_totals"]

    md, line_passed, branch_passed = generate_markdown(
        results, args.line_threshold, args.branch_threshold
    )

    if args.output_md:
        os.makedirs(os.path.dirname(args.output_md) or ".", exist_ok=True)
        with open(args.output_md, "w") as f:
            f.write(md)
        print(f"Markdown report written to {args.output_md}")

    # Print summary to console
    if line_total > 0:
        line_pct = line_covered / line_total * 100
        status = "PASSED" if line_passed else "FAILED"
        print(
            f"Line coverage: {status} — {line_pct:.1f}% "
            f"({line_covered}/{line_total} lines)"
        )
    else:
        print("Line coverage: PASSED — no coverable lines in diff")

    if branch_total > 0:
        branch_pct = branch_covered / branch_total * 100
        status = "PASSED" if branch_passed else "FAILED"
        print(
            f"Branch coverage: {status} — {branch_pct:.1f}% "
            f"({branch_covered}/{branch_total} branches)"
        )
    else:
        print("Branch coverage: PASSED — no branches in changed lines")

    if not (line_passed and branch_passed):
        failures = []
        if not line_passed:
            failures.append(
                f"line coverage {line_covered / line_total * 100:.1f}% "
                f"(threshold {args.line_threshold:.0f}%)"
            )
        if not branch_passed:
            failures.append(
                f"branch coverage "
                f"{branch_covered / branch_total * 100:.1f}% "
                f"(threshold {args.branch_threshold:.0f}%)"
            )
        print(f"FAILED: {' and '.join(failures)}")
        sys.exit(1)
    else:
        print(
            f"PASSED: All coverage meets thresholds "
            f"({threshold_label})"
        )


if __name__ == "__main__":
    main()
