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
import math

# A change is only flagged when the relative error on BOTH sides stays below
# this threshold.  High-variance results (e.g. ±15 %) are inherently unreliable
# and should not be reported as regressions or improvements.
MAX_RELATIVE_ERROR_PCT = 10.0


def _safe_score_error(value):
    """Return a finite float for scoreError, treating NaN/missing as 0."""
    if value is None:
        return 0
    try:
        f = float(value)
        return 0 if math.isnan(f) else f
    except (TypeError, ValueError):
        return 0


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
        score_error = _safe_score_error(primary.get("scoreError"))

        results[(method_name, suite)] = {
            "query": method_name,
            "suite": suite,
            "score": score,
            "score_error": score_error,
        }
    return results


def compute_scalability(results):
    """Compute MT/ST throughput ratio per query with error propagation.

    For R = MT/ST, the propagated error is:
        σ_R = R * sqrt((σ_MT/MT)² + (σ_ST/ST)²)
    """
    st = {k[0]: v for k, v in results.items() if k[1] == "SingleThread"}
    mt = {k[0]: v for k, v in results.items() if k[1] == "MultiThread"}

    scalability = {}
    for query in st:
        if query in mt and st[query]["score"] > 0 and mt[query]["score"] > 0:
            ratio = mt[query]["score"] / st[query]["score"]
            rel_err_mt = mt[query]["score_error"] / mt[query]["score"]
            rel_err_st = st[query]["score_error"] / st[query]["score"]
            ratio_error = ratio * math.sqrt(rel_err_mt ** 2 + rel_err_st ** 2)
            scalability[query] = {
                "ratio": ratio,
                "ratio_error": ratio_error,
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


def _is_high_variance(score, error):
    """Return True if relative error exceeds MAX_RELATIVE_ERROR_PCT."""
    if score <= 0:
        return False
    return error / score * 100 > MAX_RELATIVE_ERROR_PCT


def _classify_change(base_val, base_error, head_val, head_error,
                     threshold_pct=5.0):
    """Classify a single base→head change.

    Returns one of: "regression", "improvement", "suppressed", or None.

    A change is only flagged when ALL three conditions hold:
    1. The percentage change exceeds ±threshold_pct.
    2. The error bars (score ± scoreError) do not overlap.
    3. Neither side has relative error > MAX_RELATIVE_ERROR_PCT.

    When (1) and (2) hold but (3) fails, the change is "suppressed"
    (high variance).
    """
    if base_val <= 0:
        return None
    delta = (head_val - base_val) / base_val * 100
    if abs(delta) < threshold_pct:
        return None
    if errors_overlap(base_val, base_error, head_val, head_error):
        return None
    if (_is_high_variance(base_val, base_error)
            or _is_high_variance(head_val, head_error)):
        return "suppressed"
    if delta <= -threshold_pct:
        return "regression"
    return "improvement"


def delta_icon(base_val, head_val, threshold_pct=5.0,
               base_error=0, head_error=0):
    """Return an icon indicating regression/improvement/neutral/suppressed."""
    kind = _classify_change(base_val, base_error, head_val, head_error,
                            threshold_pct)
    if kind == "regression":
        return " :red_circle:"
    if kind == "improvement":
        return " :green_circle:"
    if kind == "suppressed":
        return " :warning:"
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


def _count_gated_changes(pairs, threshold_pct=5.0):
    """Count regressions, improvements, and suppressed changes.

    ``pairs`` is an iterable of (base_value, base_error, head_value, head_error)
    tuples.  Each pair is classified via ``_classify_change``.
    """
    regressions = 0
    improvements = 0
    suppressed = 0
    for base_val, base_err, head_val, head_err in pairs:
        kind = _classify_change(base_val, base_err, head_val, head_err,
                                threshold_pct)
        if kind == "regression":
            regressions += 1
        elif kind == "improvement":
            improvements += 1
        elif kind == "suppressed":
            suppressed += 1
    return regressions, improvements, suppressed


def count_changes(base, head, threshold_pct=5.0):
    """Count throughput regressions, improvements, and suppressed changes."""
    pairs = []
    for key in base.keys() | head.keys():
        b = base.get(key)
        h = head.get(key)
        if b and h:
            pairs.append((b["score"], b["score_error"],
                          h["score"], h["score_error"]))
    return _count_gated_changes(pairs, threshold_pct)


def count_scalability_changes(base_scal, head_scal, threshold_pct=5.0):
    """Count scalability ratio regressions, improvements, and suppressed."""
    pairs = []
    for query in base_scal.keys() | head_scal.keys():
        b = base_scal.get(query)
        h = head_scal.get(query)
        if b and h:
            pairs.append((b["ratio"], b["ratio_error"],
                          h["ratio"], h["ratio_error"]))
    return _count_gated_changes(pairs, threshold_pct)


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

    regressions, improvements, suppressed = count_changes(base, head)
    scal_reg, scal_imp, scal_sup = count_scalability_changes(
        base_scal, head_scal)

    lines = []
    lines.append("## JMH LDBC Benchmark Comparison")
    lines.append("")
    lines.append(
        f"**Base:** `{args.base_sha[:10]}` (fork-point with develop) "
        f"| **Head:** `{args.head_sha[:10]}`"
    )
    has_throughput = regressions > 0 or improvements > 0 or suppressed > 0
    has_scalability = scal_reg > 0 or scal_imp > 0 or scal_sup > 0
    if has_throughput or has_scalability:
        conditions = (
            f">\u00b15% threshold, non-overlapping error bars, "
            f"<{MAX_RELATIVE_ERROR_PCT:.0f}% relative error"
        )
        if has_throughput:
            parts = []
            if regressions > 0:
                parts.append(f":red_circle: {regressions} regression(s)")
            if improvements > 0:
                parts.append(
                    f":green_circle: {improvements} improvement(s)")
            if suppressed > 0:
                parts.append(
                    f":warning: {suppressed} suppressed (high variance)")
            lines.append(
                f"**Throughput:** {', '.join(parts)} ({conditions})")
        if has_scalability:
            parts = []
            if scal_reg > 0:
                parts.append(
                    f":red_circle: {scal_reg} scaling regression(s)")
            if scal_imp > 0:
                parts.append(
                    f":green_circle: {scal_imp} scaling improvement(s)")
            if scal_sup > 0:
                parts.append(
                    f":warning: {scal_sup} suppressed (high variance)")
            lines.append(
                f"**Scalability:** {', '.join(parts)} ({conditions})")
    else:
        lines.append("**Summary:** No significant changes detected.")
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
        lines.append(
            "| Benchmark | Base ratio | Base err "
            "| Head ratio | Head err | \u0394% |"
        )
        lines.append(
            "|-----------|-----------|---------|"
            "-----------|---------|-----|"
        )

        for query in all_queries:
            b = base_scal.get(query)
            h = head_scal.get(query)
            if b and h:
                delta = fmt_delta(b["ratio"], h["ratio"])
                icon = delta_icon(
                    b["ratio"], h["ratio"],
                    base_error=b["ratio_error"],
                    head_error=h["ratio_error"])
                lines.append(
                    f"| {query} "
                    f"| {b['ratio']:.2f}x "
                    f"| {fmt_error(b['ratio'], b['ratio_error'])} "
                    f"| {h['ratio']:.2f}x "
                    f"| {fmt_error(h['ratio'], h['ratio_error'])} "
                    f"| {delta}{icon} |"
                )
            elif b:
                lines.append(
                    f"| {query} "
                    f"| {b['ratio']:.2f}x "
                    f"| {fmt_error(b['ratio'], b['ratio_error'])} "
                    f"| \u2014 | \u2014 | removed |"
                )
            else:
                lines.append(
                    f"| {query} "
                    f"| \u2014 | \u2014 "
                    f"| {h['ratio']:.2f}x "
                    f"| {fmt_error(h['ratio'], h['ratio_error'])} "
                    f"| new |"
                )
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
