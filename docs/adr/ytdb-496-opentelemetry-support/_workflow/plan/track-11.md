<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 11: Quick-start observability stack (docker-compose example)

## Purpose / Big Picture

After this track lands, an operator who clones the repo, runs `docker compose up` inside `youtrackdb-opentelemetry/examples/docker-compose/`, points YTDB at `http://localhost:4317`, and opens `http://localhost:3000` in a browser sees YTDB traces, logs, and metrics flowing into Grafana with pre-provisioned datasources and dashboards. Zero hand-wiring. The track ships a self-contained, reproducible "what does YTDB look like under OTel" demo that doubles as a smoke test for the nineteen `OPENTELEMETRY_*` config entries Track 5 added.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The stack assembles the OTel Collector (OTLP gRPC/HTTP receivers), Jaeger (traces), Loki (logs), Prometheus (metrics), and Grafana (UI + dashboards) into one compose file with pinned image versions and provisioned config. No source-code edits to `core`, `server`, or the OTel module: Track 11 is example-files-only, sitting under `youtrackdb-opentelemetry/examples/docker-compose/`.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

Three reasons this lives in YTDB-496's PR rather than as a follow-up ticket:

1. The nineteen `OPENTELEMETRY_*` config entries Track 5 introduces have no end-to-end verification surface unless an operator can stand up a real collector. Without a bundled example, the first operator to try YTDB-OTel has to assemble the stack from upstream OTel docs, debug version-mismatch surprises in the Collector config schema, and write their own dashboards. The friction kills adoption.
2. The PR description already names Jaeger, Tempo, Grafana Cloud, Honeycomb, and Datadog as supported backends. Shipping a working example for the open-source path (Jaeger + Loki + Prometheus + Grafana) anchors that claim. Hosted backends substitute their exporter endpoints into the same `OPENTELEMETRY_EXPORTER_*` settings.
3. The three pillars (traces from Tracks 1+3+4, logs from Track 7, metrics from Track 8) need an integrated viewer to demonstrate trace-to-logs and trace-to-metrics correlation. A separate ticket would mean shipping the pillars unverified and the integration ungrokkable.

Concrete deliverables, all under `youtrackdb-opentelemetry/examples/docker-compose/`:

1. **`docker-compose.yml`** ships five services with pinned image versions, a shared bridge network, healthchecks on each service, named volumes for Prometheus and Loki persistence, and port mappings:
   - `otel-collector` (`otel/opentelemetry-collector-contrib:0.110.0`) exposes `4317` (OTLP gRPC) and `4318` (OTLP HTTP) to the host; connects to Jaeger / Loki / Prometheus internally.
   - `jaeger` (`jaegertracing/all-in-one:1.62`) UI at `:16686`, accepts OTLP from the Collector on `:4317` internally.
   - `loki` (`grafana/loki:3.2.0`) accepts logs from the Collector via OTLP HTTP on `:3100`.
   - `prometheus` (`prom/prometheus:v2.55.0`) scrapes the Collector's `/metrics` endpoint; UI at `:9090`.
   - `grafana` (`grafana/grafana:11.3.0`) UI at `:3000`; provisioned datasources and dashboards loaded at startup.

2. **`otel-collector-config.yaml`** — Collector pipeline config:
   - Receivers: `otlp` (both gRPC `:4317` and HTTP `:4318`).
   - Processors: `batch` (1 s batch window, 1024 max queue size), `memory_limiter` (default 512 MiB ballast), `resource` (adds `deployment.environment=local` resource attribute).
   - Exporters: `otlp/jaeger` (traces to `jaeger:4317`), `otlphttp/loki` (logs to `loki:3100/otlp`), `prometheus` (metrics endpoint at `:8889` for Prometheus to scrape).
   - Pipelines: three pipelines (traces, logs, metrics) each wiring `otlp` → processors → respective exporter.

3. **`prometheus.yml`** — scrape config: one job named `otel-collector` scraping `otel-collector:8889` at 15-second interval.

4. **`loki-config.yaml`** — single-binary Loki config (filesystem storage, no Boltdb shipper) suitable for local dev; retention 7 days.

5. **`grafana/provisioning/datasources/datasources.yml`** — three datasources auto-provisioned at startup: Jaeger (`http://jaeger:16686`), Loki (`http://loki:3100`), Prometheus (`http://prometheus:9090`). Jaeger datasource configured with the `tracesToLogsV2` correlator pointing at Loki by `trace_id`; Jaeger datasource configured with the `tracesToMetrics` correlator pointing at Prometheus by `service.name`.

6. **`grafana/provisioning/dashboards/dashboards.yml`** — dashboard provider config pointing at `/var/lib/grafana/dashboards`.

7. **`grafana/dashboards/youtrackdb-overview.json`** — top-level dashboard:
   - Row 1: query span throughput (Prometheus `db_client_operation_duration_seconds_count` rate), query span P50 / P95 / P99 latency (`histogram_quantile(...)`), commit span throughput, commit failure rate.
   - Row 2: top 10 slowest queries (Jaeger query embed by `db.system.name=youtrackdb` + sort-by-duration), top 10 collections by span count.
   - Row 3: error log rate (Loki query `{service_name="youtrackdb"} | logfmt | level="ERROR"` rate), severity distribution pie.

8. **`grafana/dashboards/youtrackdb-queries.json`** — query-focused dashboard with per-operation breakdown (`db.operation.name` SELECT vs INSERT vs UPDATE vs DELETE vs MATCH), per-collection breakdown (`db.collection.name`), slow-query gate hit rate, the six `db.youtrackdb.*` vendor attribute distributions (where_present, order_present, limit_present ratio gauges, from_class_count histogram, step_count histogram, has_subtraversal ratio).

9. **`grafana/dashboards/youtrackdb-storage.json`** — storage-focused dashboard for the metrics groups Track 8 surfaces: cache hit ratio, page read rate, WAL pending bytes + flush rate, lock-wait count, database size growth.

10. **`README.md`** — operator-facing walkthrough: prerequisites (Docker + Compose v2), one-command bring-up, link table to the four UIs (Grafana / Jaeger / Prometheus / Loki), the YTDB `youtrackdb.properties` snippet that wires YTDB to the local collector, troubleshooting for the three common failure modes (Collector won't start because port 4317 is taken; Grafana shows "no data" because YTDB hasn't run a query yet; Loki rejects logs because of the OTLP HTTP path mismatch), one-command tear-down. House style applies: BLUF lead, no AI-tell vocabulary, em-dash cap.

11. **`sample-youtrackdb.properties`** — minimal property file showing every `OPENTELEMETRY_*` setting the stack expects, with the defaults Track 5 ships and commented-out lines for the gating entries operators commonly tune (slow-query threshold, heartbeat interval, metrics period, logs severity). Each property carries a one-line comment pointing at the design-doc anchor.

12. **`scripts/up.sh`, `scripts/down.sh`, `scripts/logs.sh`** — three thin wrappers around `docker compose` for the common ops, plus `scripts/smoke.sh` running a minimal embedded YTDB query against the stack and exiting non-zero if no spans land in Jaeger within 30 s.

## Plan of Work

Five edits.

The first edit creates `docker-compose.yml`, the Collector config, the Prometheus and Loki configs, and the Grafana provisioning files. Pinned image versions everywhere — no `latest` tags. Each service carries a healthcheck so `docker compose up --wait` blocks until the stack is actually ready, not just started.

The second edit creates the three Grafana dashboards as committed JSON. Dashboards are authored by exporting from a running Grafana once the stack is up, then committing the exported JSON with the `datasource` UIDs replaced by placeholder strings the provisioning file resolves. This keeps the dashboards reproducible across operator machines without manually fixing datasource references.

The third edit creates `README.md` and `sample-youtrackdb.properties`. The README walks an operator from clone to first span in under five minutes. The properties file is the bridge between the YTDB side (Track 5's config entries) and the stack side (the collector's `:4317` endpoint).

The fourth edit creates the four scripts under `scripts/`. The smoke script is the load-bearing one: it gives the CI gate a reliable signal that the example stack actually works end-to-end with the current OTel module code.

The fifth edit wires the smoke script into the existing CI workflow (`.github/workflows/`) as an optional job gated on a label (`run-otel-example-smoke`) so the example stack does not slow down the default PR pipeline but does run on PRs that touch the OTel module or the example directory itself.

Ordering: edits 1–4 are sequential (each depends on the previous artifact). Edit 5 follows edit 4.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 11:

- `cd youtrackdb-opentelemetry/examples/docker-compose && docker compose up -d --wait` brings up five healthy services. `docker compose ps` reports all `(healthy)`.
- A host process running embedded YTDB with `OPENTELEMETRY_ENABLED=true` and `OPENTELEMETRY_EXPORTER_ENDPOINT=http://localhost:4317` emits one CLIENT span per `db.query(...)` / `db.command(...)`, visible in the Jaeger UI at `http://localhost:16686` within 1 second of the call.
- The Grafana "YouTrackDB overview" dashboard at `http://localhost:3000/d/youtrackdb-overview` shows non-zero query rate, latency percentiles, and zero error rate after a smoke workload runs.
- The Grafana "YouTrackDB queries" dashboard renders per-operation breakdown bars for SELECT / INSERT / UPDATE / DELETE / MATCH after a mixed workload runs.
- Clicking a span in Jaeger and using the "Logs for this span" link in the trace-detail panel lands the operator in Loki filtered by `trace_id`, showing every YTDB log line that fired inside that span's `Scope.makeCurrent()` window (Track 7 hard-context correlation).
- Clicking the "Metrics for this service" link in the Jaeger trace-detail panel lands the operator in the Prometheus panel filtered by `service.name=youtrackdb` (Track 8 metrics emission).
- `scripts/smoke.sh` runs a minimal embedded query against the stack and exits 0 within 30 seconds when spans / logs / metrics all land. Exits non-zero with a diagnostic message naming the missing pillar otherwise.
- `docker compose down -v` removes the stack and the named volumes. A subsequent `docker compose up -d --wait` rebuilds cleanly.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

The stack targets local development and smoke testing, not production. Three deliberate omissions an operator productionizing YTDB-OTel must address before deployment:

- **No authentication on the Collector's OTLP receivers.** A production Collector terminates TLS and authenticates the YTDB client; the example skips both because it binds to `127.0.0.1` only.
- **No retention or storage tuning.** Loki and Prometheus run with default retention (Loki 7 days, Prometheus 15 days) and filesystem storage. Production deployments use object-storage backends (S3 / GCS) and tuned retention.
- **No alerting rules.** Grafana ships datasources and dashboards but no alert rules. The "queries with error rate > 1%" or "commit P99 > 100 ms" rules an operator wants are workload-dependent and stay out of the example.

Choice of Jaeger over Tempo for traces: simpler single-binary deployment, OTLP receiver native since 1.35, built-in UI sufficient for the demo. Operators preferring Tempo substitute one image and the Collector's `otlp/jaeger` exporter; the YTDB side does not change.

Choice of Loki over an Elasticsearch-style backend for logs: works through the Collector's `otlphttp` exporter without an additional Fluent Bit / Fluentd hop, and Grafana's Loki datasource is the same UI as the Jaeger and Prometheus datasources.

The directory layout under `youtrackdb-opentelemetry/examples/docker-compose/` does not interact with the Maven build. The example is excluded from the module's `pom.xml` `<resources>` section so `mvn package` does not copy the directory into the built JAR. The example ships as part of the source tree (committed to git), discovered by operators who clone the repo or browse the GitHub UI.

## Interfaces and Dependencies

In scope (new files under `youtrackdb-opentelemetry/examples/docker-compose/`):
- `docker-compose.yml`
- `otel-collector-config.yaml`
- `prometheus.yml`
- `loki-config.yaml`
- `grafana/provisioning/datasources/datasources.yml`
- `grafana/provisioning/dashboards/dashboards.yml`
- `grafana/dashboards/youtrackdb-overview.json`
- `grafana/dashboards/youtrackdb-queries.json`
- `grafana/dashboards/youtrackdb-storage.json`
- `README.md`
- `sample-youtrackdb.properties`
- `scripts/up.sh`
- `scripts/down.sh`
- `scripts/logs.sh`
- `scripts/smoke.sh`
- `.github/workflows/otel-example-smoke.yml` (the optional, label-gated CI job)

Out of scope:
- Production deployment manifests (Kubernetes, Nomad, systemd units). Separate follow-up tickets if operator demand surfaces.
- Hosted-backend example configs (Honeycomb, Datadog, Grafana Cloud). The `OPENTELEMETRY_EXPORTER_HEADERS` config entry already supports them, but each backend's auth scheme deserves its own README that the local-dev example would clutter. Separate follow-up if requested.
- Helm charts for the observability stack itself: explicitly out of scope; users on Kubernetes already run their own collector + UI stack.
- Alerting rules.
- A second example using Tempo instead of Jaeger.

Inter-track dependencies:
- Depends on Track 5 (the `OPENTELEMETRY_*` config entries the `sample-youtrackdb.properties` references must exist).
- Depends on Tracks 1+3+4 (traces pillar), Track 7 (logs pillar), Track 8 (metrics pillar) — the smoke script and the dashboards verify all three pillars emit.
- No dependents — Track 11 is a leaf in the dependency graph. Other tracks do not consume any artifact this track produces.

External-tool version policy: every image carries a pinned tag chosen at the time of Track 11 implementation. A follow-up ticket (YTDB-OTel-EXAMPLE-VERSIONS) covers the periodic refresh; the example stack is not auto-bumped by Dependabot because the Collector config schema changes between minor versions and an unattended bump can silently break the example.
