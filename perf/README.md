# LDBC SNB Benchmark (YouTrackDB)

## What it is

LDBC SNB Interactive is a realistic graph benchmark with mixed reads/writes, complex queries, and scheduled execution based on simulated social-network activity. It measures throughput and per-query latency percentiles; schedule audit is available when scheduled mode is enabled.

## Architecture

Two-machine setup:

- **Orchestrator (self-hosted GitHub runner)**: builds images and driver JAR, runs the driver locally, drives the workflow.
- **DB host**: runs YouTrackDB and loader containers; holds datasets/backups.

```
GitHub Actions
      |
      v
Orchestrator (driver + build)
      |
      |  ssh + docker save/load
      v
DB Host (YouTrackDB + Loader)
```

## Pipeline (orchestrator)

1. Build YouTrackDB Docker image, loader image, driver JAR
2. Preflight checks (SSH, Docker, artifacts present)
3. Deploy images to DB host and start DB container
4. Load data, run validation, reload data
5. Run benchmark, collect logs/results

## Key parameters (perf/config.yml)

| Key | Meaning | Default |
|---|---|---|
| `ldbc.scale_factor` | Dataset size | `0.1` |
| `ldbc.operation_count` | Number of operations | `10000` |
| `ldbc.thread_count` | Read threads | `24` |
| `ldbc.update_partitions` | Write threads = `2 * update_partitions` | `1` |
| `ldbc.time_compression_ratio` | Schedule compression (lower = harder) | `0.1` |
| `ldbc.ignore_scheduled_start_times` | `true` = stress mode (no schedule audit) | `true` |
| `ldbc.warmup_count` | Warmup operations | `50` |
| `ldbc.validation_params_limit` | Validation params subset size | `200` |

Notes:
- When `ignore_scheduled_start_times=true`, operations run back-to-back; TCR becomes irrelevant.
- When `false`, the driver enforces scheduling and reports schedule audit status.

## Data layout / prerequisites

- Driver params on **orchestrator** at `paths.driver_params` (default `/data/ldbc-snb`):
  - `sf<scale>/substitution_parameters`
  - `sf<scale>/update_streams/numpart-<N>`
  - `sf<scale>/validation_params/validation_params-sf<scale>.csv`
- Loader dataset on **DB host** at `paths.loader_data` (default `/data/ldbc-snb`).
- SSH alias to DB host (default `bench-db`) and Docker installed on DB host.

## Run

Local (on orchestrator machine):

```bash
python orchestrator.py config.yml
```

The GitHub workflow `.github/workflows/ldbc-snb-benchmark.yml` sets the required env vars and runs the same command.

## References

- LDBC SNB spec: https://ldbcouncil.org/benchmarks/snb/
- Driver docs: https://github.com/ldbc/ldbc_snb_interactive_v1_driver
- TinkerPop Driver + YTDB loader repo (loader currently lives in the driver repo): https://github.com/JetBrains/ldbc-snb-interactive-gremlin
