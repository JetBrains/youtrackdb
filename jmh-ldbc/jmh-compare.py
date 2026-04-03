#!/usr/bin/env python3
"""Compare two JMH JSON result files and produce a markdown comparison table.

Usage:
    python3 jmh-compare.py \
        --base base-results.json --head head-results.json \
        --base-sha abc1234 --head-sha def5678 \
        --output comparison.md
"""

import argparse
import json


def parse_jmh_results(data):
    """Parse JMH JSON into dict keyed by (query, suite)."""
    results = {}
    for entry in data:
        benchmark_full = entry["benchmark"]
        if "." not in benchmark_full:
            continue
        class_name_full, method_name = benchmark_full.rsplit(".", 1)
        class_name = class_name_full.rsplit(".", 1)[-1]

        if "SingleThread" in class_name:
            suite = "SingleThread"
        elif "MultiThread" in class_name:
            suite = "MultiThread"
        else:
            suite = class_name

        primary = entry.get("primaryMetric", {})
        score = primary.get("score", 0)
        score_error = primary.get("scoreError", 0)

        results[(method_name, suite)] = {
            "query": method_name,
            "suite": suite,
            "score": score,
            "score_error": score_error,
        }
    return results


def compute_scalability(results):
    """Compute MT/ST throughput ratio per query."""
    st = {k[0]: v for k, v in results.items() if k[1] == "SingleThread"}
    mt = {k[0]: v for k, v in results.items() if k[1] == "MultiThread"}

    scalability = {}
    for query in st:
        if query in mt and st[query]["score"] > 0:
            scalability[query] = {
                "ratio": mt[query]["score"] / st[query]["score"],
                "st_score": st[query]["score"],
                "mt_score": mt[query]["score"],
            }
    return scalability


def fmt_score(score):
    """Format throughput score with appropriate precision."""
    if score >= 1000:
        return f"{score:,.0f}"
    elif score >= 1:
        return f"{score:.1f}"
    else:
        return f"{score:.3f}"


def fmt_error(score, error):
    """Format score error as a percentage of score."""
    if score == 0:
        return "N/A"
    return f"\u00b1{error / score * 100:.1f}%"


def fmt_delta(base_val, head_val):
    """Format percentage change from base to head."""
    if base_val == 0:
        return "N/A"
    delta = (head_val - base_val) / base_val * 100
    sign = "+" if delta >= 0 else ""
    return f"{sign}{delta:.1f}%"


def errors_overlap(base_score, base_error, head_score, head_error):
    """Return True if the confidence intervals [score ± error] overlap."""
    base_lo = base_score - base_error
    base_hi = base_score + base_error
    head_lo = head_score - head_error
    head_hi = head_score + head_error
    return base_lo <= head_hi and head_lo <= base_hi


def delta_icon(base_val, head_val, threshold_pct=5.0,
               base_error=0, head_error=0):
    """Return an icon indicating regression/improvement/neutral.

    A change is only flagged when BOTH conditions hold:
    1. The percentage change exceeds ±threshold_pct.
    2. The error bars (score ± scoreError) do not overlap.
    """
    if base_val == 0:
        return ""
    delta = (head_val - base_val) / base_val * 100
    if delta <= -threshold_pct and not errors_overlap(
            base_val, base_error, head_val, head_error):
        return " :red_circle:"
    elif delta >= threshold_pct and not errors_overlap(
            base_val, base_error, head_val, head_error):
        return " :green_circle:"
    return ""


def build_suite_table(base, head, suite):
    """Build markdown table rows for a single suite (ST or MT)."""
    queries = sorted({
        k[0] for k in (base.keys() | head.keys()) if k[1] == suite
    })
    if not queries:
        return []

    rows = []
    for query in queries:
        b = base.get((query, suite))
        h = head.get((query, suite))

        if b and h:
            delta = fmt_delta(b["score"], h["score"])
            icon = delta_icon(b["score"], h["score"],
                              base_error=b["score_error"],
                              head_error=h["score_error"])
            rows.append(
                f"| {query} "
                f"| {fmt_score(b['score'])} "
                f"| {fmt_error(b['score'], b['score_error'])} "
                f"| {fmt_score(h['score'])} "
                f"| {fmt_error(h['score'], h['score_error'])} "
                f"| {delta}{icon} |"
            )
        elif b:
            rows.append(
                f"| {query} "
                f"| {fmt_score(b['score'])} "
                f"| {fmt_error(b['score'], b['score_error'])} "
                f"| \u2014 | \u2014 | removed |"
            )
        else:
            rows.append(
                f"| {query} "
                f"| \u2014 | \u2014 "
                f"| {fmt_score(h['score'])} "
                f"| {fmt_error(h['score'], h['score_error'])} "
                f"| new |"
            )
    return rows


def count_changes(base, head, threshold_pct=5.0):
    """Count regressions and improvements across all suites.

    A change is counted only when the percentage exceeds the threshold
    AND the error bars do not overlap.
    """
    regressions = 0
    improvements = 0
    for key in base.keys() | head.keys():
        b = base.get(key)
        h = head.get(key)
        if b and h and b["score"] > 0:
            delta = (h["score"] - b["score"]) / b["score"] * 100
            overlap = errors_overlap(
                b["score"], b["score_error"],
                h["score"], h["score_error"])
            if delta <= -threshold_pct and not overlap:
                regressions += 1
            elif delta >= threshold_pct and not overlap:
                improvements += 1
    return regressions, improvements


def main():
    parser = argparse.ArgumentParser(description="Compare two JMH result files")
    parser.add_argument("--base", required=True, help="Base (fork-point) results JSON")
    parser.add_argument("--head", required=True, help="Head (branch tip) results JSON")
    parser.add_argument("--base-sha", required=True, help="Base commit SHA")
    parser.add_argument("--head-sha", required=True, help="Head commit SHA")
    parser.add_argument("--output", default="-", help="Output file (- for stdout)")
    args = parser.parse_args()

    with open(args.base) as f:
        base_data = json.load(f)
    with open(args.head) as f:
        head_data = json.load(f)

    base = parse_jmh_results(base_data)
    head = parse_jmh_results(head_data)

    base_scal = compute_scalability(base)
    head_scal = compute_scalability(head)

    regressions, improvements = count_changes(base, head)

    lines = []
    lines.append("## JMH LDBC Benchmark Comparison")
    lines.append("")
    lines.append(
        f"**Base:** `{args.base_sha[:10]}` (fork-point with develop) "
        f"| **Head:** `{args.head_sha[:10]}`"
    )
    if regressions > 0 or improvements > 0:
        parts = []
        if regressions > 0:
            parts.append(f":red_circle: {regressions} regression(s)")
        if improvements > 0:
            parts.append(f":green_circle: {improvements} improvement(s)")
        lines.append(
            f"**Summary:** {', '.join(parts)} "
            f"(>\u00b15% threshold, non-overlapping error bars)"
        )
    lines.append("")

    for suite, label in [("SingleThread", "Single-Thread"),
                         ("MultiThread", "Multi-Thread")]:
        rows = build_suite_table(base, head, suite)
        if not rows:
            continue
        lines.append(f"### {label} Results")
        lines.append("")
        lines.append(
            "| Benchmark | Base ops/s | Base err | Head ops/s | Head err | \u0394% |"
        )
        lines.append(
            "|-----------|-----------|---------|-----------|---------|-----|"
        )
        lines.extend(rows)
        lines.append("")

    # Scalability table
    all_queries = sorted(base_scal.keys() | head_scal.keys())
    if all_queries:
        lines.append("### Scalability (MT/ST ratio)")
        lines.append("")
        lines.append("| Benchmark | Base ratio | Head ratio | \u0394% |")
        lines.append("|-----------|-----------|-----------|-----|")

        for query in all_queries:
            b = base_scal.get(query)
            h = head_scal.get(query)
            if b and h:
                delta = fmt_delta(b["ratio"], h["ratio"])
                lines.append(
                    f"| {query} | {b['ratio']:.2f}x | {h['ratio']:.2f}x | {delta} |"
                )
            elif b:
                lines.append(f"| {query} | {b['ratio']:.2f}x | \u2014 | removed |")
            else:
                lines.append(f"| {query} | \u2014 | {h['ratio']:.2f}x | new |")
        lines.append("")

    output = "\n".join(lines)

    if args.output == "-":
        print(output)
    else:
        with open(args.output, "w") as f:
            f.write(output)
        print(f"Comparison written to {args.output}")


if __name__ == "__main__":
    main()
