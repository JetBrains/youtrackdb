#!/usr/bin/env python3
"""Mutation testing gate: checks PIT mutation kill rate on new/changed code.

Parses PIT's mutations.xml report for each module, computes kill rates,
enforces a threshold, and generates a markdown report with per-file tables
of survived and no-coverage mutations.

Usage:
    python mutation-gate.py --threshold 85 --modules core,server \
        --output-md /tmp/mutation-gate.md
"""

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

MAX_COMMENT_CHARS = 60000

# Human-readable names for PIT mutator classes
MUTATOR_NAMES = {
    "ConditionalsBoundaryMutator": "changed conditional boundary",
    "IncrementsMutator": "changed increment/decrement",
    "InvertNegsMutator": "inverted negation",
    "MathMutator": "replaced math operator",
    "NegateConditionalsMutator": "negated conditional",
    "ReturnValsMutator": "changed return value",
    "VoidMethodCallMutator": "removed method call",
    "EmptyObjectReturnValsMutator": "replaced return with empty object",
    "FalseReturnValsMutator": "replaced return with false",
    "TrueReturnValsMutator": "replaced return with true",
    "NullReturnValsMutator": "replaced return with null",
    "PrimitiveReturnsMutator": "replaced return with 0",
}


def simplify_mutator(mutator_fqn):
    """Extract a short human-readable name from a PIT mutator fully-qualified class name.

    Example: 'org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator'
             -> 'negated conditional'
    """
    short_name = mutator_fqn.rsplit(".", 1)[-1] if "." in mutator_fqn else mutator_fqn
    return MUTATOR_NAMES.get(short_name, short_name)


def parse_pit_report(xml_path):
    """Parse a PIT mutations.xml file.

    Returns:
        list of dicts with keys: status, source_file, mutated_class,
        mutated_method, line_number, mutator, description
    """
    tree = ET.parse(xml_path)
    root = tree.getroot()
    mutations = []

    for mutation_elem in root.findall("mutation"):
        status = mutation_elem.get("status", "UNKNOWN")

        source_file = mutation_elem.findtext("sourceFile", "")
        mutated_class = mutation_elem.findtext("mutatedClass", "")
        mutated_method = mutation_elem.findtext("mutatedMethod", "")
        line_number = int(mutation_elem.findtext("lineNumber", "0"))
        mutator = mutation_elem.findtext("mutator", "")
        description = mutation_elem.findtext("description", "")

        mutations.append({
            "status": status,
            "source_file": source_file,
            "mutated_class": mutated_class,
            "mutated_method": mutated_method,
            "line_number": line_number,
            "mutator": mutator,
            "description": description,
        })

    return mutations


def collect_mutations(modules):
    """Collect mutations from all module PIT reports.

    Returns:
        list of mutation dicts (from all modules combined)
    """
    all_mutations = []
    for module in modules:
        xml_path = os.path.join(module, "target", "pit-reports", "mutations.xml")
        if not os.path.isfile(xml_path):
            print(f"No PIT report found at {xml_path}, skipping module '{module}'")
            continue
        print(f"Parsing PIT report: {xml_path}")
        mutations = parse_pit_report(xml_path)
        print(f"  Found {len(mutations)} mutations")
        all_mutations.extend(mutations)
    return all_mutations


def compute_results(mutations):
    """Compute per-file and aggregate mutation results.

    Returns:
        dict with keys: total, killed, survived, no_coverage, other,
        kill_rate, per_file (dict of file -> stats)
    """
    total = len(mutations)
    killed = sum(1 for m in mutations if m["status"] == "KILLED")
    survived = sum(1 for m in mutations if m["status"] == "SURVIVED")
    no_coverage = sum(1 for m in mutations if m["status"] == "NO_COVERAGE")
    other = total - killed - survived - no_coverage

    kill_rate = (killed / total * 100) if total > 0 else 100.0

    # Per-file breakdown
    per_file = defaultdict(lambda: {
        "total": 0,
        "killed": 0,
        "survived": [],
        "no_coverage": [],
    })

    for m in mutations:
        # Use mutated_class as the file key for grouping
        file_key = m["mutated_class"]
        per_file[file_key]["total"] += 1
        if m["status"] == "KILLED":
            per_file[file_key]["killed"] += 1
        elif m["status"] == "SURVIVED":
            per_file[file_key]["survived"].append(m)
        elif m["status"] == "NO_COVERAGE":
            per_file[file_key]["no_coverage"].append(m)

    return {
        "total": total,
        "killed": killed,
        "survived": survived,
        "no_coverage": no_coverage,
        "other": other,
        "kill_rate": kill_rate,
        "per_file": dict(per_file),
    }


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


def generate_markdown(results, threshold, sampled=False, total_changed=0):
    """Generate markdown report with per-file mutation tables.

    Returns:
        tuple: (markdown_string, passed)
    """
    kill_rate = results["kill_rate"]
    passed = kill_rate >= threshold

    lines = []
    lines.append("<!-- mutation-gate-comment -->")
    lines.append("# Mutation Testing Gate Results")
    lines.append(f"**Threshold**: {threshold:.0f}%")
    lines.append("")
    if sampled:
        lines.append(
            f"> **Note**: Mutation testing ran on a random sample of 20 "
            f"out of {total_changed} changed classes."
        )
        lines.append("")

    # Overall result
    icon = ":white_check_mark:" if passed else ":x:"
    lines.append(
        f"## Mutation Score: {icon} {kill_rate:.1f}% "
        f"({results['killed']}/{results['total']} mutations killed)"
    )
    lines.append("")

    if results["survived"] > 0 or results["no_coverage"] > 0:
        lines.append(
            f"**Survived**: {results['survived']} | "
            f"**No Coverage**: {results['no_coverage']} | "
            f"**Other**: {results['other']}"
        )
        lines.append("")

    # Per-file summary table
    files_with_issues = {
        cls: data for cls, data in sorted(results["per_file"].items())
        if data["survived"] or data["no_coverage"]
    }

    if files_with_issues:
        lines.append("## Per-File Summary")
        lines.append("")
        lines.append("| Class | Kill Rate | Survived Lines | No Coverage Lines |")
        lines.append("|-------|-----------|---------------|-------------------|")

        for cls, data in files_with_issues.items():
            file_total = data["total"]
            file_killed = data["killed"]
            file_rate = (file_killed / file_total * 100) if file_total > 0 else 100.0
            file_icon = ":white_check_mark:" if file_rate >= threshold else ":x:"

            survived_lines = [m["line_number"] for m in data["survived"]]
            no_cov_lines = [m["line_number"] for m in data["no_coverage"]]
            survived_str = format_line_ranges(survived_lines) if survived_lines else "-"
            no_cov_str = format_line_ranges(no_cov_lines) if no_cov_lines else "-"

            lines.append(
                f"| `{cls}` | {file_icon} {file_rate:.1f}% "
                f"({file_killed}/{file_total}) "
                f"| {survived_str} | {no_cov_str} |"
            )
        lines.append("")

    # Detail section: list each survived/no-coverage mutation
    all_issues = []
    for cls, data in sorted(results["per_file"].items()):
        for m in data["survived"]:
            all_issues.append((cls, m, "SURVIVED"))
        for m in data["no_coverage"]:
            all_issues.append((cls, m, "NO_COVERAGE"))

    if all_issues:
        lines.append("<details>")
        lines.append("<summary>Mutation Details (click to expand)</summary>")
        lines.append("")
        lines.append("| Status | Class | Method | Line | Mutation |")
        lines.append("|--------|-------|--------|------|----------|")

        for cls, m, status in sorted(all_issues, key=lambda x: (x[0], x[1]["line_number"])):
            status_icon = ":warning:" if status == "SURVIVED" else ":no_entry_sign:"
            desc = m["description"] if m["description"] else simplify_mutator(m["mutator"])
            lines.append(
                f"| {status_icon} {status} | `{cls}` "
                f"| `{m['mutated_method']}` "
                f"| {m['line_number']} | {desc} |"
            )

        lines.append("")
        lines.append("</details>")

    md = "\n".join(lines) + "\n"

    # Truncate if too large
    if len(md) > MAX_COMMENT_CHARS:
        truncation_msg = (
            "\n\n> **Note**: Report truncated due to size. "
            "See CI logs for full details.\n"
        )
        md = md[: MAX_COMMENT_CHARS - len(truncation_msg)] + truncation_msg

    return md, passed


def main():
    parser = argparse.ArgumentParser(
        description="Mutation testing gate for PIT reports"
    )
    parser.add_argument(
        "--threshold",
        type=float,
        required=True,
        help="Minimum mutation kill rate percentage",
    )
    parser.add_argument(
        "--modules",
        required=True,
        help="Comma-separated list of Maven module paths (e.g. core,server)",
    )
    parser.add_argument(
        "--output-md",
        default=None,
        help="Path to write markdown report (optional)",
    )
    parser.add_argument(
        "--sampled",
        action="store_true",
        default=False,
        help="Indicates that class sampling was used",
    )
    parser.add_argument(
        "--total-changed",
        type=int,
        default=0,
        help="Total number of changed classes before sampling",
    )
    args = parser.parse_args()

    modules = [m.strip() for m in args.modules.split(",") if m.strip()]
    if not modules:
        print("No modules specified. Skipping mutation gate.")
        return

    mutations = collect_mutations(modules)
    if not mutations:
        print("No mutations found in any module. Skipping mutation gate.")
        if args.output_md:
            os.makedirs(os.path.dirname(args.output_md) or ".", exist_ok=True)
            with open(args.output_md, "w") as f:
                f.write(
                    "<!-- mutation-gate-comment -->\n"
                    "# Mutation Testing Gate Results\n"
                    f"**Threshold**: {args.threshold:.0f}%\n\n"
                    ":white_check_mark: No mutations found — "
                    "mutation gate skipped.\n"
                )
        return

    results = compute_results(mutations)
    md, passed = generate_markdown(
        results, args.threshold,
        sampled=args.sampled, total_changed=args.total_changed
    )

    if args.output_md:
        os.makedirs(os.path.dirname(args.output_md) or ".", exist_ok=True)
        with open(args.output_md, "w") as f:
            f.write(md)
        print(f"Markdown report written to {args.output_md}")

    # Print summary to console
    print(
        f"Mutation score: {results['kill_rate']:.1f}% "
        f"({results['killed']}/{results['total']} killed)"
    )
    print(
        f"  Survived: {results['survived']}, "
        f"No coverage: {results['no_coverage']}, "
        f"Other: {results['other']}"
    )

    if not passed:
        print(
            f"FAILED: Mutation score {results['kill_rate']:.1f}% "
            f"below threshold {args.threshold:.0f}%"
        )
        sys.exit(1)
    else:
        print(
            f"PASSED: Mutation score meets threshold {args.threshold:.0f}%"
        )


if __name__ == "__main__":
    main()
