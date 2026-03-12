#!/usr/bin/env python3
"""Test count gate: detects unintentional test removal or disabling.

Compares current test counts against a baseline stored in git notes
on the develop branch. Fails if any module's test count drops by more
than the allowed tolerance percentage.

The gate can be bypassed when the PR title contains [no-test-number-check],
which is useful for intentional refactorings that restructure tests.

Usage:
    python test-count-gate.py \
        --baseline baseline.json \
        --current current.json \
        --tolerance 5 \
        --output-md /tmp/test-count-gate.md

    # Bypass mode (PR title contains marker):
    python test-count-gate.py \
        --baseline baseline.json \
        --current current.json \
        --skip \
        --output-md /tmp/test-count-gate.md
"""

import argparse
import json
import os
import sys

MAX_COMMENT_CHARS = 60000


def load_counts(path):
    """Load test counts from JSON file.

    Returns:
        dict with "modules" and "total" keys, or empty structure
    """
    try:
        with open(path) as f:
            data = json.load(f)
        return data
    except (OSError, json.JSONDecodeError):
        return {"modules": {}, "total": 0}


def check_counts(baseline, current, tolerance_pct):
    """Compare current counts against baseline with tolerance.

    A module fails if its test count dropped by more than tolerance_pct
    compared to the baseline. New modules (not in baseline) always pass.
    Modules that disappeared entirely are flagged as failures.

    Returns:
        list of (module, baseline_count, current_count, drop_pct, passed)
    """
    results = []

    baseline_modules = baseline.get("modules", {})
    current_modules = current.get("modules", {})

    all_modules = sorted(set(baseline_modules) | set(current_modules))

    for module in all_modules:
        base = baseline_modules.get(module, 0)
        curr = current_modules.get(module, 0)

        if base == 0:
            # New module or no baseline — always passes
            results.append((module, base, curr, 0.0, True))
            continue

        drop_pct = (base - curr) / base * 100 if curr < base else 0.0
        passed = drop_pct <= tolerance_pct
        results.append((module, base, curr, drop_pct, passed))

    return results


def generate_markdown(results, baseline_total, current_total,
                      tolerance_pct, skipped):
    """Generate markdown report with per-module comparison table.

    Returns:
        tuple: (markdown_string, all_passed)
    """
    lines = []
    lines.append("<!-- test-count-gate-comment -->")
    lines.append("# Test Count Gate Results")

    if skipped:
        lines.append("")
        lines.append(
            ":white_check_mark: Check bypassed — PR title contains "
            "`[no-test-number-check]`"
        )
        return "\n".join(lines) + "\n", True

    lines.append(
        f"**Tolerance**: {tolerance_pct:.0f}% drop allowed per module"
    )
    lines.append("")

    all_passed = all(r[4] for r in results)

    diff = current_total - baseline_total
    diff_str = f"+{diff}" if diff >= 0 else str(diff)

    icon = ":white_check_mark:" if all_passed else ":x:"
    lines.append(
        f"## Overall: {icon} {current_total} tests "
        f"(baseline: {baseline_total}, {diff_str})"
    )
    lines.append("")

    lines.append("| Module | Baseline | Current | Change | Status |")
    lines.append("|--------|----------|---------|--------|--------|")

    for module, base, curr, drop_pct, passed in results:
        status = ":white_check_mark:" if passed else ":x:"
        diff = curr - base
        change_str = f"+{diff}" if diff >= 0 else str(diff)
        if not passed:
            change_str += f" (-{drop_pct:.1f}%)"
        lines.append(
            f"| `{module}` | {base} | {curr} | {change_str} | {status} |"
        )

    md = "\n".join(lines) + "\n"

    if len(md) > MAX_COMMENT_CHARS:
        truncation_msg = (
            "\n\n> **Note**: Report truncated due to size.\n"
        )
        md = md[:MAX_COMMENT_CHARS - len(truncation_msg)] + truncation_msg

    return md, all_passed


def main():
    parser = argparse.ArgumentParser(
        description="Test count gate: detect unintentional test removal"
    )
    parser.add_argument(
        "--baseline",
        required=True,
        help="Path to baseline test counts JSON (from git notes)",
    )
    parser.add_argument(
        "--current",
        required=True,
        help="Path to current test counts JSON",
    )
    parser.add_argument(
        "--tolerance",
        type=float,
        default=5.0,
        help="Allowed percentage drop per module (default: 5%%)",
    )
    parser.add_argument(
        "--output-md",
        default=None,
        help="Path to write markdown report (optional)",
    )
    parser.add_argument(
        "--skip",
        action="store_true",
        help="Skip the check (PR title contains [no-test-number-check])",
    )
    args = parser.parse_args()

    # Bypass mode
    if args.skip:
        print("Test count gate bypassed — PR title contains "
              "[no-test-number-check].")
        if args.output_md:
            md, _ = generate_markdown(
                [], 0, 0, args.tolerance, skipped=True
            )
            out_dir = os.path.dirname(args.output_md)
            if out_dir:
                os.makedirs(out_dir, exist_ok=True)
            with open(args.output_md, "w") as f:
                f.write(md)
            print(f"Markdown report written to {args.output_md}")
        return

    baseline = load_counts(args.baseline)
    current = load_counts(args.current)

    baseline_total = baseline.get("total", 0)
    current_total = current.get("total", 0)

    # No baseline yet (first run) — pass gracefully
    if not baseline.get("modules"):
        print(
            "No baseline found (first run or empty git note). "
            "Skipping test count gate."
        )
        if args.output_md:
            out_dir = os.path.dirname(args.output_md)
            if out_dir:
                os.makedirs(out_dir, exist_ok=True)
            with open(args.output_md, "w") as f:
                f.write(
                    "<!-- test-count-gate-comment -->\n"
                    "# Test Count Gate Results\n\n"
                    ":white_check_mark: No baseline available yet — "
                    "gate skipped (first run).\n"
                )
            print(f"Markdown report written to {args.output_md}")
        return

    results = check_counts(baseline, current, args.tolerance)
    md, all_passed = generate_markdown(
        results, baseline_total, current_total,
        args.tolerance, skipped=False,
    )

    if args.output_md:
        out_dir = os.path.dirname(args.output_md)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
        with open(args.output_md, "w") as f:
            f.write(md)
        print(f"Markdown report written to {args.output_md}")

    # Console summary
    print(f"Test counts: {current_total} (baseline: {baseline_total})")
    for module, base, curr, drop_pct, passed in results:
        status = "OK" if passed else "FAILED"
        print(f"  {module}: {curr} (baseline: {base}) — {status}")

    if not all_passed:
        failed = [(m, b, c, d) for m, b, c, d, p in results if not p]
        for module, base, curr, drop_pct in failed:
            print(
                f"FAILED: {module} dropped {drop_pct:.1f}% "
                f"(from {base} to {curr}, "
                f"tolerance: {args.tolerance:.0f}%)"
            )
        sys.exit(1)
    else:
        print(
            f"PASSED: All modules within "
            f"{args.tolerance:.0f}% tolerance"
        )


if __name__ == "__main__":
    main()
