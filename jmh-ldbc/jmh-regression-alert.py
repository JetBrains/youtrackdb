#!/usr/bin/env python3
"""Check JMH results for performance regressions and alert via Zulip."""

import argparse
import json
import sys
import urllib.parse
import urllib.request
import urllib.error
import base64


def parse_latest_results(data):
    """Parse JMH JSON into {(query, suite): {score, score_error}} dict."""
    results = {}
    for entry in data:
        parts = entry["benchmark"].rsplit(".", 2)
        class_name = parts[-2]
        method_name = parts[-1]

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
            "score": score,
            "score_error": score_error,
        }
    return results


def query_influxdb(url, token, org, bucket, suite, branch, limit):
    """Query InfluxDB for the last N scores per query (excluding the latest push)."""
    # Get historical data: last `limit` runs before the most recent
    flux = f'''
from(bucket: "{bucket}")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "jmh_benchmark")
  |> filter(fn: (r) => r.suite == "{suite}")
  |> filter(fn: (r) => r.branch == "{branch}")
  |> filter(fn: (r) => r._field == "score")
  |> group(columns: ["query"])
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: {limit})
  |> sort(columns: ["_time"])
  |> keep(columns: ["_time", "_value", "query"])
'''
    query_url = f"{url.rstrip('/')}/api/v2/query?org={org}"
    body = json.dumps({"query": flux, "type": "flux"}).encode("utf-8")
    req = urllib.request.Request(
        query_url,
        data=body,
        headers={
            "Authorization": f"Token {token}",
            "Content-Type": "application/json",
            "Accept": "application/csv",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            csv_data = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        # Exit non-zero instead of returning {}: an empty history would
        # masquerade as "no regressions" and silently drop alerts.
        body_text = e.read().decode("utf-8", errors="replace")
        print(f"InfluxDB query failed: {e.code} - {body_text}", file=sys.stderr)
        sys.exit(1)

    return parse_influx_csv(csv_data)


def parse_influx_csv(csv_data):
    """Parse InfluxDB CSV response into {query: [scores_oldest_first]} dict.

    InfluxDB annotated CSV format:
      ,result,table,_time,_value,query
      ,_result,0,2026-03-21T03:48:00Z,56216.45,is1_personProfile
    """
    history = {}
    header_cols = None
    value_idx = None
    query_idx = None

    for line in csv_data.strip().splitlines():
        # Skip annotation rows
        if line.startswith("#"):
            continue
        # Skip empty lines
        if not line.strip():
            continue

        parts = [p.strip() for p in line.split(",")]

        # Detect header row (contains column names)
        if "_value" in parts and "query" in parts:
            header_cols = parts
            value_idx = parts.index("_value")
            query_idx = parts.index("query")
            continue

        # Parse data rows
        if value_idx is not None and query_idx is not None and len(parts) > max(
            value_idx, query_idx
        ):
            try:
                value = float(parts[value_idx])
                query = parts[query_idx].strip()
                if query:
                    history.setdefault(query, []).append(value)
            except (ValueError, IndexError):
                continue

    return history


def check_regressions(latest, history, run_over_run_pct, avg_pct, noise_threshold):
    """Check for regressions. Returns list of regression dicts."""
    regressions = []

    for (query, suite), data in latest.items():
        score = data["score"]
        score_error = data["score_error"]

        # Skip noisy benchmarks (score_error > noise_threshold% of score)
        if score > 0 and (score_error / score) > (noise_threshold / 100.0):
            continue

        hist = history.get(suite, {}).get(query, [])
        if not hist:
            continue

        # Run-over-run check: compare with the most recent historical run
        prev_score = hist[-1]
        if prev_score > 0:
            ror_delta = (score - prev_score) / prev_score * 100.0
            if ror_delta < -run_over_run_pct:
                regressions.append({
                    "query": query,
                    "suite": suite,
                    "type": "run-over-run",
                    "score": score,
                    "baseline": prev_score,
                    "delta_pct": ror_delta,
                    "threshold": -run_over_run_pct,
                })

        # vs 14-run average check
        if len(hist) >= 3:  # need at least 3 data points for meaningful average
            avg = sum(hist) / len(hist)
            if avg > 0:
                avg_delta = (score - avg) / avg * 100.0
                if avg_delta < -avg_pct:
                    regressions.append({
                        "query": query,
                        "suite": suite,
                        "type": f"vs {len(hist)}-run avg",
                        "score": score,
                        "baseline": avg,
                        "delta_pct": avg_delta,
                        "threshold": -avg_pct,
                    })

    return regressions


def format_zulip_message(regressions, branch, commit_sha, dashboard_url):
    """Format regressions into a Zulip markdown message."""
    lines = [
        ":warning: **JMH Benchmark Regression Detected**",
        "",
        f"**Branch:** `{branch}` | "
        f"**Commit:** [{commit_sha[:8]}]"
        f"(https://github.com/JetBrains/youtrackdb/commit/{commit_sha})",
        "",
    ]

    # Group by type
    ror = [r for r in regressions if r["type"] == "run-over-run"]
    avg = [r for r in regressions if r["type"] != "run-over-run"]

    if ror:
        lines.append("### Run-over-run regressions (>10% drop vs previous)")
        lines.append("")
        lines.append("| Query | Suite | Score | Previous | \u0394% |")
        lines.append("|---|---|---|---|---|")
        for r in sorted(ror, key=lambda x: x["delta_pct"]):
            lines.append(
                f"| `{r['query']}` | {r['suite']} | "
                f"{r['score']:.1f} | {r['baseline']:.1f} | "
                f"**{r['delta_pct']:+.1f}%** |"
            )
        lines.append("")

    if avg:
        lines.append("### Below rolling average (>5% drop vs 14-run avg)")
        lines.append("")
        lines.append("| Query | Suite | Score | Avg | \u0394% |")
        lines.append("|---|---|---|---|---|")
        for r in sorted(avg, key=lambda x: x["delta_pct"]):
            lines.append(
                f"| `{r['query']}` | {r['suite']} | "
                f"{r['score']:.1f} | {r['baseline']:.1f} | "
                f"**{r['delta_pct']:+.1f}%** |"
            )
        lines.append("")

    lines.append(f":bar_chart: [Dashboard]({dashboard_url})")

    return "\n".join(lines)


def send_zulip_message(content, zulip_url, api_key, bot_email, stream, topic):
    """Send a message to Zulip via the API."""
    url = f"{zulip_url.rstrip('/')}/api/v1/messages"
    params = urllib.parse.urlencode({
        "type": "stream",
        "to": stream,
        "topic": topic,
        "content": content,
    }).encode("utf-8")

    credentials = base64.b64encode(
        f"{bot_email}:{api_key}".encode("utf-8")
    ).decode("utf-8")

    req = urllib.request.Request(
        url,
        data=params,
        headers={
            "Authorization": f"Basic {credentials}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            if result.get("result") == "success":
                print(f"Zulip message sent (id={result.get('id')})")
            else:
                print(f"Zulip response: {result}", file=sys.stderr)
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        print(f"Zulip API error: {e.code} - {body_text}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="Check JMH results for regressions and alert via Zulip"
    )
    parser.add_argument("--input", required=True, help="JMH JSON results file")
    parser.add_argument("--influxdb-url", required=True, help="InfluxDB URL")
    parser.add_argument("--influxdb-token", required=True, help="InfluxDB read token")
    parser.add_argument("--influxdb-org", default="youtrackdb", help="InfluxDB org")
    parser.add_argument(
        "--influxdb-bucket", default="jmh-benchmarks", help="InfluxDB bucket"
    )
    parser.add_argument("--branch", required=True, help="Git branch name")
    parser.add_argument("--commit-sha", required=True, help="Git commit SHA")
    parser.add_argument(
        "--run-over-run-threshold",
        type=float,
        default=10.0,
        help="Run-over-run regression threshold in %% (default: 10)",
    )
    parser.add_argument(
        "--avg-threshold",
        type=float,
        default=5.0,
        help="Rolling average regression threshold in %% (default: 5)",
    )
    parser.add_argument(
        "--history-size",
        type=int,
        default=14,
        help="Number of historical runs to average (default: 14)",
    )
    parser.add_argument(
        "--noise-threshold",
        type=float,
        default=10.0,
        help="Skip benchmarks with score_error > N%% of score (default: 10)",
    )
    parser.add_argument(
        "--zulip-url",
        default="https://youtrackdb.zulipchat.com",
        help="Zulip organization URL",
    )
    parser.add_argument("--zulip-api-key", required=True, help="Zulip bot API key")
    parser.add_argument(
        "--zulip-bot-email",
        default="ci-status-bot@youtrackdb.zulipchat.com",
        help="Zulip bot email",
    )
    parser.add_argument("--zulip-stream", default="ytdb", help="Zulip stream")
    parser.add_argument(
        "--zulip-topic", default="jmh-ldbc-bench", help="Zulip topic"
    )
    parser.add_argument(
        "--dashboard-url",
        default="https://bench.youtrackdb.io",
        help="Grafana dashboard URL",
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="Print message without sending"
    )
    args = parser.parse_args()

    with open(args.input) as f:
        data = json.load(f)

    latest = parse_latest_results(data)
    print(f"Parsed {len(latest)} benchmark results from current run")

    # Query historical data per suite
    history = {}
    for suite in ("SingleThread", "MultiThread"):
        hist = query_influxdb(
            args.influxdb_url,
            args.influxdb_token,
            args.influxdb_org,
            args.influxdb_bucket,
            suite,
            args.branch,
            args.history_size,
        )
        history[suite] = hist
        total_points = sum(len(v) for v in hist.values())
        print(f"Fetched {total_points} historical data points for {suite}")

    regressions = check_regressions(
        latest,
        history,
        args.run_over_run_threshold,
        args.avg_threshold,
        args.noise_threshold,
    )

    if not regressions:
        print("No regressions detected. All clear.")
        return

    print(f"Detected {len(regressions)} regression(s)!")
    message = format_zulip_message(
        regressions, args.branch, args.commit_sha, args.dashboard_url
    )

    if args.dry_run:
        print("\n--- Zulip message (dry run) ---")
        print(message)
        return

    send_zulip_message(
        message,
        args.zulip_url,
        args.zulip_api_key,
        args.zulip_bot_email,
        args.zulip_stream,
        args.zulip_topic,
    )


if __name__ == "__main__":
    main()
