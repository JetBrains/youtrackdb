#!/usr/bin/env python3
"""Parse JMH JSON results and push to InfluxDB 2.x via line protocol."""

import argparse
import json
import sys
import urllib.request
import urllib.error


def parse_jmh_results(data):
    """Parse JMH JSON into a list of measurement dicts."""
    measurements = []
    for entry in data:
        benchmark_full = entry["benchmark"]
        # e.g. "com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcSingleThreadBenchmark.is1_personProfile"
        parts = benchmark_full.rsplit(".", 2)
        class_name = parts[-2]  # LdbcSingleThreadBenchmark
        method_name = parts[-1]  # is1_personProfile

        if "SingleThread" in class_name:
            suite = "SingleThread"
        elif "MultiThread" in class_name:
            suite = "MultiThread"
        else:
            suite = class_name

        primary = entry.get("primaryMetric", {})
        score = primary.get("score", 0)
        score_error = primary.get("scoreError", 0)

        # Extract percentiles if available
        percentiles = primary.get("scorePercentiles", {})

        measurements.append({
            "query": method_name,
            "suite": suite,
            "score": score,
            "score_error": score_error,
            "p0_00": percentiles.get("0.0", 0),
            "p50_00": percentiles.get("50.0", 0),
            "p90_00": percentiles.get("90.0", 0),
            "p95_00": percentiles.get("95.0", 0),
            "p99_00": percentiles.get("99.0", 0),
            "p99_99": percentiles.get("99.99", 0),
            "p100_00": percentiles.get("100.0", 0),
        })
    return measurements


def compute_scalability(measurements):
    """Compute MT/ST throughput ratio for each query."""
    st = {m["query"]: m["score"] for m in measurements if m["suite"] == "SingleThread"}
    mt = {m["query"]: m["score"] for m in measurements if m["suite"] == "MultiThread"}

    scalability = []
    for query in st:
        if query in mt and st[query] > 0:
            scalability.append({
                "query": query,
                "ratio": mt[query] / st[query],
                "st_score": st[query],
                "mt_score": mt[query],
            })
    return scalability


def escape_tag(value):
    """Escape special characters in InfluxDB tag values."""
    return str(value).replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=")


def to_line_protocol(measurements, scalability, branch, commit_sha, timestamp_s):
    """Convert measurements to InfluxDB line protocol."""
    timestamp_ns = int(timestamp_s) * 1_000_000_000
    lines = []

    for m in measurements:
        tags = (
            f"query={escape_tag(m['query'])},"
            f"suite={escape_tag(m['suite'])},"
            f"branch={escape_tag(branch)},"
            f"commit_sha={escape_tag(commit_sha)}"
        )
        fields = (
            f"score={m['score']},"
            f"score_error={m['score_error']},"
            f"score_p0_00={m['p0_00']},"
            f"score_p50_00={m['p50_00']},"
            f"score_p90_00={m['p90_00']},"
            f"score_p95_00={m['p95_00']},"
            f"score_p99_00={m['p99_00']},"
            f"score_p99_99={m['p99_99']},"
            f"score_p100_00={m['p100_00']}"
        )
        lines.append(f"jmh_benchmark,{tags} {fields} {timestamp_ns}")

    for s in scalability:
        tags = (
            f"query={escape_tag(s['query'])},"
            f"branch={escape_tag(branch)},"
            f"commit_sha={escape_tag(commit_sha)}"
        )
        fields = (
            f"ratio={s['ratio']},"
            f"st_score={s['st_score']},"
            f"mt_score={s['mt_score']}"
        )
        lines.append(f"scalability,{tags} {fields} {timestamp_ns}")

    return "\n".join(lines)


def push_to_influxdb(line_data, url, token, org, bucket):
    """Push line protocol data to InfluxDB 2.x via HTTP API."""
    write_url = f"{url.rstrip('/')}/api/v2/write?org={org}&bucket={bucket}&precision=ns"
    req = urllib.request.Request(
        write_url,
        data=line_data.encode("utf-8"),
        headers={
            "Authorization": f"Token {token}",
            "Content-Type": "text/plain; charset=utf-8",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"InfluxDB write successful: {resp.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"InfluxDB write failed: {e.code} - {body}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Push JMH results to InfluxDB")
    parser.add_argument("--input", required=True, help="JMH JSON results file")
    parser.add_argument("--influxdb-url", required=True, help="InfluxDB URL")
    parser.add_argument("--influxdb-token", required=True, help="InfluxDB write token")
    parser.add_argument("--influxdb-org", default="youtrackdb", help="InfluxDB org")
    parser.add_argument("--influxdb-bucket", default="jmh-benchmarks", help="InfluxDB bucket")
    parser.add_argument("--branch", required=True, help="Git branch name")
    parser.add_argument("--commit-sha", required=True, help="Git commit SHA")
    parser.add_argument("--timestamp", required=True, help="Unix timestamp (seconds)")
    parser.add_argument("--dry-run", action="store_true", help="Print line protocol without sending")
    args = parser.parse_args()

    with open(args.input) as f:
        data = json.load(f)

    measurements = parse_jmh_results(data)
    scalability = compute_scalability(measurements)

    print(f"Parsed {len(measurements)} benchmark results, {len(scalability)} scalability metrics")

    line_data = to_line_protocol(
        measurements, scalability, args.branch, args.commit_sha, args.timestamp
    )

    if args.dry_run:
        print(line_data)
        return

    push_to_influxdb(
        line_data, args.influxdb_url, args.influxdb_token, args.influxdb_org, args.influxdb_bucket
    )


if __name__ == "__main__":
    main()
