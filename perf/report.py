#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


def parse_results(path: Path) -> tuple[dict, list[dict]]:
    with open(path) as file:
        data = json.load(file)
    return {
        "throughput": data.get("throughput", 0),
        "duration_ms": data.get("total_duration", 0),
        "total_count": data.get("total_count", 0),
    }, data.get("all_metrics", [])


def format_ms(ms: float) -> str:
    return f"{ms:.0f}ms"


def extract_number(name: str) -> int:
    match = re.search(r'\d+', name)
    return int(match.group()) if match else 999


def generate_markdown(summary: dict, metrics: list[dict]) -> str:
    lines = ["## LDBC SNB Benchmark Results", ""]

    lines.append(f"**Throughput: {summary['throughput']:.1f} ops/sec** | "
                 f"Duration: {format_ms(summary['duration_ms'])} | "
                 f"Operations: {summary['total_count']:,}")
    lines.append("")

    complex_reads = []
    short_reads = []
    updates = []

    for metric in metrics:
        run_time = metric["run_time"]
        query = {
            "name": metric["name"],
            "count": run_time["count"],
            "p50": run_time["50th_percentile"],
            "p95": run_time["95th_percentile"],
            "p99": run_time["99th_percentile"],
            "max": run_time["max"],
        }
        if metric["name"].startswith("LdbcQuery"):
            complex_reads.append(query)
        elif metric["name"].startswith("LdbcShortQuery"):
            short_reads.append(query)
        elif metric["name"].startswith("LdbcUpdate"):
            updates.append(query)

    complex_reads.sort(key=lambda query: extract_number(query["name"]))
    short_reads.sort(key=lambda query: extract_number(query["name"]))
    updates.sort(key=lambda query: extract_number(query["name"]))

    def render_table(title: str, queries: list[dict]) -> None:
        if not queries:
            return
        lines.append(f"### {title}")
        lines.append("")
        lines.append("| Query | Count | p50 | p95 | p99 | Max |")
        lines.append("|-------|------:|----:|----:|----:|----:|")
        for query in queries:
            lines.append(f"| {query['name']} | {query['count']} | {format_ms(query['p50'])} | "
                        f"{format_ms(query['p95'])} | {format_ms(query['p99'])} | {format_ms(query['max'])} |")
        lines.append("")

    render_table("Complex Reads", complex_reads)
    render_table("Short Reads", short_reads)
    render_table("Updates", updates)

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()

    if not args.results.exists():
        sys.exit(f"Results file not found: {args.results}")

    summary, metrics = parse_results(args.results)
    markdown = generate_markdown(summary, metrics)
    print(markdown)

    if summary_file := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary_file, "a") as file:
            file.write(markdown + "\n")


if __name__ == "__main__":
    main()
