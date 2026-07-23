# YouTrackDB LDBC JMH Benchmarks

JMH microbenchmarks for YouTrackDB based on the [LDBC Social Network Benchmark (SNB)](https://ldbcouncil.org/benchmarks/snb/) Interactive workload. Each benchmark method executes a single LDBC read query using YouTrackDB's SQL engine (MATCH queries).

## Queries

The benchmark covers 20 read-only queries from the LDBC SNB Interactive v1 specification:

| Query | Description |
|-------|-------------|
| IS1 | Person profile lookup |
| IS2 | Recent messages of a person |
| IS3 | Friends of a person |
| IS4 | Content of a message |
| IS5 | Creator of a message |
| IS6 | Forum of a message |
| IS7 | Replies to a message |
| IC1 | Transitive friends with a certain first name (3-hop KNOWS) |
| IC2 | Recent messages by friends |
| IC3 | Friends/FoF who posted in two given countries |
| IC4 | New topics — tags on friends' posts in a time window |
| IC5 | New groups — forums joined by friends/FoF after a date |
| IC6 | Tag co-occurrence on friends' posts |
| IC7 | Recent likers of a person's messages |
| IC8 | Recent replies to a person's messages |
| IC9 | Recent messages by friends and friends-of-friends |
| IC10 | Friend recommendation based on common interests |
| IC11 | Job referral — friends/FoF working in a country |
| IC12 | Expert search — friends' comments on posts in a tag class hierarchy |
| IC13 | Shortest path between two persons |

## Benchmark Classes

Benchmarks are split into 6 tiers based on SF 1 throughput characteristics. With [curated parameters](#parameter-curation) eliminating parameter-dependent variance, shorter measurement iterations are sufficient. The tier split is tuned so that all 40 benchmarks (20 queries × 2 suites) achieve score-error < 7% on CCX33:

| Tier | Base Class | Queries | Forks | Warmup | Measurement | ST ops/s |
|------|-----------|---------|-------|--------|-------------|----------|
| IS-ultra-fast | `LdbcISUltraFastBenchmarkBase` | IS1, IS3-IS6, IC13 | 5 | 1×5s | 3×10s | >2,700 |
| IS-noisy | `LdbcISBenchmarkBase` | IS2, IS7, IC8 | 10 | 3×5s | 3×10s | 400-2,700 |
| IC | `LdbcICBenchmarkBase` | IC2, IC7, IC11 | 3 | 1×10s | 5×20s | 17-215 |
| IC-slow | `LdbcICSlowBenchmarkBase` | IC1, IC4, IC6, IC9, IC12 | 3 | 1×30s | 5×30s | 1-21 |
| IC-ultra-slow | `LdbcICUltraSlowBenchmarkBase` | IC3, IC5, IC10 | 5 | 1×60s | 3×120s | <0.2 |

Each tier has single-threaded and multi-threaded concrete classes:

- **`LdbcSingleThreadISUltraFastBenchmark`** / **`LdbcMultiThreadISUltraFastBenchmark`** — IS-ultra-fast tier
- **`LdbcSingleThreadISBenchmark`** / **`LdbcMultiThreadISBenchmark`** — IS-noisy tier
- **`LdbcSingleThreadICBenchmark`** / **`LdbcMultiThreadICBenchmark`** — IC tier
- **`LdbcSingleThreadICSlowBenchmark`** / **`LdbcMultiThreadICSlowBenchmark`** — IC-slow tier
- **`LdbcSingleThreadICUltraSlowBenchmark`** / **`LdbcMultiThreadICUltraSlowBenchmark`** — IC-ultra-slow tier

Multi-threaded classes use `@Threads(Threads.MAX)` (one thread per available processor).

## AnalyzedExpr Predicate-Eval Benchmarks (YTDB-916 S1)

These are **dataset-free microbenchmarks** — independent of the LDBC SNB dataset above — that cover the predicate-evaluation code paths changed by YTDB-916 S1 (the analyzed-expression IR migration). Instead of the SF 1 CSV, they spin up an **in-memory** YouTrackDB with a small synthetic `Bench` schema, so they run anywhere with no dataset download. All three live in package `com.jetbrains.youtrackdb.benchmarks.ldbc` alongside the LDBC classes and share the same `-P bench` gate and uber-jar.

The suite uses **two complementary measurement instruments**: a high-sensitivity in-branch A/B (Bench 1) and per-path absolute-throughput coverage benches (Bench 2/3).

### The three benchmarks

| # | Class (+ `SingleThread` variant) | Instrument | Mode | Methods |
|---|----------------------------------|-----------|------|---------|
| 1 | `AnalyzedExprEvaluatorBenchmark` / `AnalyzedExprEvaluatorSingleThreadBenchmark` | **SENSITIVITY** | `AverageTime` (ns/op) | `evalIr`, `evalAst` |
| 2 | `FilterStepThroughputBenchmark` / `FilterStepThroughputSingleThreadBenchmark` | **COVERAGE** | `Throughput` (ops/s) | `filterStep_ir`, `filterStep_astFallback` |
| 3 | `ExpandStepThroughputBenchmark` / `ExpandStepThroughputSingleThreadBenchmark` | **COVERAGE** | `Throughput` (ops/s) | `expandStep_ir`, `expandStep_astFallback` |

JMH run configuration (from the class annotations; the `SingleThread` variants set `@Threads(1)`):

| Bench | Forks | Warmup | Measurement |
|-------|-------|--------|-------------|
| 1 — evaluator A/B | 10 | 3×1s | 5×1s |
| 2/3 — throughput | 3 | 3×3s | 5×3s |

**Bench 1 — evaluator sensitivity (in-branch A/B).** Runs the **same** predicate through both evaluation arms:

- `evalIr` → the new IR evaluator, `AnalyzedExprEvaluator.evaluate(analyzed, row, ctx)`.
- `evalAst` → the AST path, `SQLWhereClause.matchesFilters(row, ctx)`.

Because both arms evaluate the identical predicate over the identical rows, this is an apples-to-apples A/B that **cannot be masked by pipeline cost**. The predicate is selected by the `predicateCase` `@Param` axis with 9 values, each exercising a distinct evaluator path:

| `predicateCase` | Predicate | Exercises |
|-----------------|-----------|-----------|
| `EQ_FAST` | `age = 30` (entity row) | YTDB-628 in-place equality fast path |
| `CMP_FAST` | `age < 30` (entity row) | in-place comparison fast path |
| `EQ_SLOW` | `age = 30` (projection row) | generic slow path |
| `AND_OR` | `age > 20 AND age < 40` | lazy short-circuit |
| `IS_NULL` | `mid IS NULL` | untyped null-test |
| `IS_NOT_NULL` | `mid IS NOT NULL` | `NOT(IS_NULL)` |
| `PARAM` | `age > :p` | per-execution `Param` resolution |
| `CI_COLLATION` | `nameCi = 'xyz'` (ci prop) | fast-path decline → slow path |
| `ARITH` | `age + 1 > 30` | arithmetic `BinaryOp` |

**Bench 2 — `FilterStep` coverage.** Drives the real execution pipeline's `FilterStep.filterMap` branch end-to-end via two **independent, never-compared** `@Benchmark` methods:

- `filterStep_ir` — `age > :p` (lowerable → IR evaluator branch).
- `filterStep_astFallback` — `age IN [...]` (unlowerable → AST fallback branch).

It is **deliberately filter-dominated**: an unindexed full sequential scan with ~50% selectivity, so the filter runs on every scanned row.

**Bench 3 — `ExpandStep` coverage.** Same two-independent-method shape on a synthetic star graph (`Root` → many `Leaf`), exercising `ExpandStep.filterMap` on a pushed-down filter:

- `expandStep_ir` — lowerable push-down filter (IR branch).
- `expandStep_astFallback` — unlowerable push-down filter (AST fallback branch).

### How to interpret the results

- **Bench 1's `evalIr`-vs-`evalAst` A/B is the PRIMARY REGRESSION GATE for the evaluator.** It is a high-sensitivity lab measurement; the before/after signal for the S1 migration is carried by this A/B **plus** the CCX33 LDBC end-to-end run.
- **Bench 2/3 are per-path ABSOLUTE THROUGHPUT, tracked OVER TIME** (via InfluxDB / `jmh-regression-alert.py`) to guard against future regressions. The IR and AST-fallback methods within a bench are **NEVER compared to each other** — they do semantically different work (set-membership vs comparison), so each is its own independent time series.
- **Bench 2/3 are COVERAGE-ONLY.** `filterMap` / expand-filter cost is a minority of per-row pipeline cost (scan + entity-load dominate), so these benches **attenuate** regressions. Bench 1 is the sensitive detector; Bench 2/3 confirm the paths run end-to-end and provide a long-term throughput baseline.
- **By design there is NO cross-branch build and NO force-AST production hook.** Both were considered and deliberately rejected (a cross-branch build is infeasible because `jmh-ldbc` will not compile on `develop` with the IR-referencing classes present; a force-AST hot-path hook cannot self-certify zero overhead in-branch). See the plan and PR for the full rationale.

### How to run

All commands obey the **serial-build invariant**: never run two Maven builds/tests concurrently in this worktree. The `-am` flag is required so the `core` module is rebuilt alongside `jmh-ldbc`.

```bash
# Build (core + jmh-ldbc)
./mvnw -pl jmh-ldbc -am -DskipTests package

# Bench 1 — evaluator sensitivity A/B (single-threaded)
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Djmh.args="AnalyzedExprEvaluatorSingleThreadBenchmark.*"

# Bench 2/3 — filter/expand throughput coverage
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Djmh.args="FilterStepThroughput.*|ExpandStepThroughput.*"
```

Dataset-size overrides (JVM system properties, read by `BenchDataset` inside the forked JMH JVM):

| Property | Default | Description |
|----------|---------|-------------|
| `analyzed.bench.rows` | `100000` | Row count of the flat `Bench` dataset (Bench 1 & 2). |
| `analyzed.bench.expand.leaves` | `10000` | Number of `Leaf` vertices in the Bench 3 star graph. |

**These properties are read in the JMH `@Fork` child JVM, not the Maven JVM.** A plain `-Danalyzed.bench.rows=...` placed on the `./mvnw ... verify -P bench` command sets the property on the Maven/`exec` JVM only — the `bench` profile forwards just `${jmh.args}` into the forked `java` process, so the property never reaches `BenchDataset` and the override silently no-ops. Pass it through to the fork via JMH's `-jvmArgsAppend`, placed inside `-Djmh.args`:

```bash
# Smaller flat dataset (Bench 1 & 2): 20k rows instead of 100k
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Djmh.args="AnalyzedExprEvaluatorSingleThreadBenchmark.* -jvmArgsAppend -Danalyzed.bench.rows=20000"

# Smaller star graph (Bench 3): 2k leaves instead of 10k
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Djmh.args="ExpandStepThroughput.* -jvmArgsAppend -Danalyzed.bench.expand.leaves=2000"
```

### Correctness guards (JUnit, not JMH)

Two JUnit tests pin the benchmark's invariants and must stay green:

- `AnalyzedExprGuardTest` — asserts IR/AST **parity** over all 9 Bench-1 `predicateCase` values (IR variant lowers, and IR and AST produce identical boolean outcomes over sample rows).
- `ExpandStepIrPathGuardTest` — verifies the push-down into `ExpandStep` actually occurs (the IR branch is genuinely taken end-to-end).

```bash
./mvnw -pl jmh-ldbc -am test -Dtest=AnalyzedExprGuardTest \
    -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl jmh-ldbc -am test -Dtest=ExpandStepIrPathGuardTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

## Execution Time

Fork count and iteration length vary by tier to achieve stable results within a reasonable time budget. Parameter curation significantly reduces total runtime compared to the random-sampling approach.

| Suite | Benchmarks | Approx. Time per mode (SF 1) |
|-------|-----------|------------------------------|
| IS-ultra-fast (ST or MT) | 6 | ~18 min |
| IS-noisy (ST or MT) | 3 | ~23 min |
| IC (ST or MT) | 3 | ~16 min |
| IC-slow (ST or MT) | 5 | ~33 min |
| IC-ultra-slow (ST or MT) | 3 | ~54 min |
| **All suites (ST + MT)** | 40 | **~7-8 hours** |
| First run from CSV (includes DB init + factor tables) | — | adds ~25 min (SF 1) |

## Prerequisites

- **JDK 21+**
- **LDBC dataset** in CsvCompositeMergeForeign format — see [Dataset](#dataset) section for how to obtain it.

## Quick Start (Maven)

The simplest way to run benchmarks — builds everything from source and launches JMH in a single command. There are two approaches:

### Single command (recommended): `verify -P bench`

The `bench` Maven profile binds `exec:exec` to the `verify` phase, so compile + run happens automatically:

```bash
# Run all benchmarks (compiles core + jmh-ldbc, then launches JMH)
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests

# Run only single-threaded benchmarks (~22 min)
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcSingleThread.*"

# Run a specific query (~1 min)
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args=".*ic1_transitiveFriends"

# Quick smoke test: 1 warmup, 1 measurement, 1s each
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Djmh.args=".*is1_personProfile -f 1 -wi 1 -i 1 -r 1s"

# List all available benchmarks without running them
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="-l"
```

### Two-step: `compile` + `exec:exec`

If you prefer explicit control over the build and run phases:

```bash
# Run all benchmarks
./mvnw -pl jmh-ldbc -am compile exec:exec

# Pass full JMH options (regex, forks, warmup, iterations)
./mvnw -pl jmh-ldbc -am compile exec:exec \
    -Djmh.args="LdbcSingleThread.* -f 1 -wi 2 -i 3"
```

Both approaches fork a new JVM with all required `--add-opens` flags and 4 GB heap preconfigured.

### First Run

On the first run, the benchmark setup phase will:
1. Check for an existing database at `./target/ldbc-bench-db` (or the path specified by `-Dldbc.db.path`). If a database exists, it is reused directly (skips steps 2-3).
2. Otherwise, look for the LDBC CSV dataset at `./target/ldbc-dataset/sf1` (or the path specified by `-Dldbc.dataset.path`). The dataset must be obtained beforehand — see [Dataset](#dataset).
3. Create a YouTrackDB database, create the LDBC schema from `ldbc-schema.sql` (vertex/edge classes + indexes), and load all CSV data (~3.6M records for SF 1, ~21 min on CCX33).
4. Run **parameter curation** — compute factor tables from the loaded data (friend counts, FoF/FoFoF counts, name frequencies, etc.), apply gap-based grouping to select entities with similar query difficulty, and generate 500 per-query parameter tuples. Factor tables are cached to `factor-tables.json` alongside the DB so they are computed only once.

Subsequent runs reuse the existing database and cached factor tables (~2s startup).

## Running via Uber-Jar

For CI or standalone use, the shade plugin produces a self-contained jar:

```bash
# Build the uber-jar
./mvnw -pl jmh-ldbc -am clean package -DskipTests

# Run all benchmarks
java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar

# Run a subset
java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThread.*"

# Override thread count
java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar -t 16 "LdbcMultiThread.*"

# JSON result file for post-processing
java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar -rf json -rff results.json
```

## Database Tool

The `LdbcDatabaseTool` utility supports export/import and backup/restore operations on the benchmark database. This is useful for pre-building a database snapshot to share or for migrating between machines.

```bash
# Build classpath (run from jmh-ldbc/ directory after compile)
CP="target/classes:$(mvn dependency:build-classpath -DincludeScope=runtime \
    -Dmdep.outputFile=/dev/stdout -q)"

# Export database to gzipped JSON (~47 MB, ~11s)
java -cp "$CP" com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcDatabaseTool export ./target/ldbc-export

# Import database from JSON export (~8 min)
java -cp "$CP" com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcDatabaseTool import ./target/ldbc-export.gz ./target/new-db

# Binary backup (~182 MB, ~20s)
java -cp "$CP" com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcDatabaseTool backup ./target/ldbc-backup

# Restore from binary backup (~10s)
java -cp "$CP" com.jetbrains.youtrackdb.benchmarks.ldbc.LdbcDatabaseTool restore ./target/ldbc-backup ./target/new-db
```

## Parameter Curation

Query parameters are **curated** rather than randomly sampled, following the approach from the [LDBC SNB parameter generation](https://github.com/ldbc/ldbc_snb_interactive_v2_driver/tree/main/paramgen) framework. This eliminates parameter-dependent throughput variance — the main source of benchmark noise.

### Why curation matters

Different persons have vastly different KNOWS neighborhoods (10 friends vs 500 friends). A random `personId` can make IC1 run in 0.1s or 5s. Without curation, extremely long measurement iterations (60-300s) and many forks (5-10) are needed to average out this parameter sensitivity, resulting in ~10 hour total runtime.

With curation, all parameter sets produce similar query difficulty, so shorter iterations suffice.

### How it works

The `ParameterCurator` class implements a 3-step pipeline:

1. **Factor table computation** — For each entity type, compute the metric that drives query difficulty:

   | Factor | Metric | Used by |
   |--------|--------|---------|
   | Person friend count | `out('KNOWS').size()` | IC4, IC7, IC8, IC11, IC13 |
   | Person FoF count | 2-hop KNOWS count | IC2, IC3, IC5, IC6, IC9, IC10, IC12 |
   | Person FoFoF count | 3-hop KNOWS count | IC1 |
   | First name frequency | `count(*) GROUP BY firstName` | IC1 |
   | Creation day message count | Messages per day | IC3, IC4, IC9 |
   | Tag person count | `in('HAS_INTEREST').size()` | IC6 |
   | IC4 oldPost count | Friends' posts before startDate | IC4 (NOT-pattern cost) |

   Factor tables are cached to `factor-tables.json` alongside the database, so the expensive 2/3-hop traversals are computed only once.

2. **Gap-based grouping** — For each factor table:
   - Sort entities by the metric
   - Filter out trivially small values
   - Walk sorted values; start a new group when consecutive difference exceeds a threshold
   - Among qualifying groups (≥ N members), pick the one with the lowest standard deviation

   This selects a pool of entities that all have similar query difficulty. If the official LDBC thresholds are too strict for the current scale factor, the algorithm auto-escalates (threshold × 2, × 3, ... up to × 100) until a qualifying group is found.

3. **Per-query parameter generation** — Cross-join curated pools to produce 500 parameter tuples per query. Each query uses its own dedicated parameter array instead of drawing from shared pools:

   | Query | Person pool | Secondary pool |
   |-------|------------|----------------|
   | IC1 | FoFoF-selected | first names |
   | IC3 | FoF-selected | country pairs × dates |
   | IC4 | friends-selected | dates |
   | IC6 | FoF-selected | tags |
   | IC9 | FoF-selected | dates |
   | IC12 | FoF-selected | tag classes |
   | IS1-7 | friends-selected | messages |

### Canonical curated parameters

**Critical**: All benchmark runs — CI comparisons, nightly baselines, and local profiling — **must use the same curated parameters**. The canonical parameter files are the sole source of truth, stored in Hetzner S3 as separate objects and downloaded before each run. They must never be regenerated independently.

- **S3 keys**: `ldbc/curated-params-v3.json`, `ldbc/factor-tables.json`
- **S3 bucket**: `bench-cache`
- **Install location**: `jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json` and `factor-tables.json`
- **Credentials**: stored as GitHub repository secrets `HETZNER_S3_ACCESS_KEY`, `HETZNER_S3_SECRET_KEY`, `HETZNER_S3_ENDPOINT`

**Why this matters**: The `ParameterCurator` samples 200 (person, date) pairs from a `friendsSelected × dates` cross-product using stride-based sampling. The stride depends on iteration order of `friendsSelected`, which comes from database query results. Any code change that affects internal data structure ordering (hash maps, indexes, etc.) changes the iteration order, causing different pairs to be sampled. For IC4 in particular, different pairs can have vastly different "old post counts" (the NOT-pattern cost factor), leading to **up to 7x throughput differences** between runs that should be identical. This was discovered when a cache layer change produced a spurious +586% IC4 "improvement" in CI that did not reproduce with shared parameters.

**Regeneration is blocked by default.** If canonical curated params are missing from the DB directory, the benchmark fails with an `IllegalStateException` directing you to download them from S3. This prevents accidental parameter desync between runs.

**To regenerate canonical parameters** (only when the curation algorithm itself changes):
1. Build from develop: `./mvnw -pl jmh-ldbc -am package -DskipTests`
2. Ensure a database exists at `jmh-ldbc/target/ldbc-bench-db` (load from CSV if needed)
3. Delete existing params: `rm -f jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json jmh-ldbc/target/ldbc-bench-db/factor-tables.json`
4. Run with the generation flag enabled: `java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1 -jvmArgsAppend "-Dldbc.allow.param.generation=true"` (note: `-f 1` is required — with `-f 0` JMH runs in-process and `-jvmArgsAppend` is ignored, so the flag never reaches `ParameterCurator`)
5. Upload the new files to S3: `ldbc/curated-params-v3.json` and `ldbc/factor-tables.json`

### Per-query parameter access

Each `@Benchmark` method accesses its own curated parameters via typed accessors on `LdbcBenchmarkState`:

```java
// Before (shared random pool):
state.personId(i)      // same pool for all queries
state.firstName(i)     // independent of personId

// After (per-query curated tuples):
state.ic1PersonId(i)   // person from FoFoF-selected pool
state.ic1FirstName(i)  // name paired with that person
```

## Configuration

All settings are passed as JVM system properties. When using Maven, add `-D` flags before `exec:exec`; when using the uber-jar, add them before `-jar`:

| Property | Default | Description |
|----------|---------|-------------|
| `ldbc.dataset.path` | `./target/ldbc-dataset/sf1` | Path to the LDBC dataset root (must contain `static/` and `dynamic/` subdirectories). Only needed on first run when no database exists yet. |
| `ldbc.db.path` | `./target/ldbc-bench-db` | Directory where the YouTrackDB database is stored. If a database already exists here, CSV loading is skipped. |
| `ldbc.scale.factor` | `1` | Scale factor (used in default dataset path). |
| `ldbc.batch.size` | `1000` | Batch size for CSV data loading. |
| `ldbc.allow.param.generation` | `false` | When `true`, allows regeneration of curated parameters if cached files are missing or stale (factor tables newer than curated params). Without this flag, missing/stale params cause a hard failure. Only set when intentionally regenerating canonical parameters. |

Example with a custom dataset path or smaller scale factor:

```bash
# Use SF 0.1 for quick local testing
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Dldbc.scale.factor=0.1

# Use an existing database from a custom path
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Dldbc.db.path=/data/ldbc-bench-db

# Uber-jar with custom dataset
java -Dldbc.dataset.path=/data/ldbc/sf1 \
     -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar
```

## Dataset

The benchmark uses the LDBC SNB Interactive dataset in **CsvCompositeMergeForeign** format (datagen v1.0.0). In this format, 1-to-N relationships are embedded as foreign key columns in entity CSV files (e.g., `Person` CSV contains a `LocationCityId` column), while N-to-M relationships are in separate edge files.

**Important**: The dataset format must be CsvCompositeMergeForeign. Other formats (CsvComposite, CsvBasic, etc.) have different column layouts and are **not compatible** with the benchmark loaders.

SF 1 (default) contains:

| Entity | Count |
|--------|-------|
| Person | 10,621 |
| Post | 1,192,944 |
| Comment | 2,391,709 |
| Forum | ~130K |
| Tag | 16,080 |
| Organisation | 7,955 |
| Place | 1,460 |
| **Total messages** | **~3.58M** |

SF 0.1 (for quick local testing) is also available — see below.

The dataset is loaded into YouTrackDB with the full LDBC schema: 15 vertex classes, 15 edge classes, and 21 indexes.

### Obtaining the dataset

There are two ways to set up the benchmark database:

#### Option 1: Download CSV dataset from S3 and load fresh (recommended)

Download the raw CSV dataset and let the benchmark load it into a new database. This is the standard setup path used by all CI workflows.

- **SF 1 CSV**: `ldbc/ldbc-sf1-composite-merged-fk.tar.zst` (~196 MB, loads in ~21 min on CCX33)
- **SF 0.1 CSV**: `ldbc/ldbc-sf0.1-composite-merged-fk.tar.zst` (~19 MB, loads in ~30s)

```bash
# Download SF 1 CSV dataset
aws s3 cp s3://bench-cache/ldbc/ldbc-sf1-composite-merged-fk.tar.zst /tmp/dataset.tar.zst \
    --endpoint-url "$HETZNER_S3_ENDPOINT"

# Extract to the expected location
mkdir -p jmh-ldbc/target/ldbc-dataset/sf1
cd jmh-ldbc/target/ldbc-dataset/sf1
zstd -d /tmp/dataset.tar.zst -o /tmp/dataset.tar
tar xf /tmp/dataset.tar
rm -f /tmp/dataset.tar.zst /tmp/dataset.tar
```

Or using Python `boto3`:
```python
import boto3
s3 = boto3.client("s3",
    endpoint_url=HETZNER_S3_ENDPOINT,
    aws_access_key_id=HETZNER_S3_ACCESS_KEY,
    aws_secret_access_key=HETZNER_S3_SECRET_KEY)
s3.download_file("bench-cache",
    "ldbc/ldbc-sf1-composite-merged-fk.tar.zst",
    "/tmp/dataset.tar.zst")
```

#### Option 2: Generate using LDBC datagen Docker image

If you don't have access to the Hetzner S3 credentials, generate the dataset locally using the official [LDBC datagen](https://github.com/ldbc/ldbc_snb_datagen_spark) Docker image:

```bash
# Generate SF 1 dataset in CsvCompositeMergeForeign format
docker run --rm \
    -v "$(pwd)/jmh-ldbc/target/ldbc-dataset/sf1:/out" \
    ldbc/datagen-standalone:latest \
    --scale-factor 1 \
    --mode raw \
    --format CsvCompositeMergeForeign \
    --epoch-millis

# Verify the expected structure
ls jmh-ldbc/target/ldbc-dataset/sf1/static/   # Place, Tag, Organisation, ...
ls jmh-ldbc/target/ldbc-dataset/sf1/dynamic/  # Person, Post, Comment, Forum, ...
```

The generated dataset directory must contain `static/` and `dynamic/` subdirectories. Entity files should contain foreign key columns (e.g., `Person` should have `LocationCityId`, `Post` should have `CreatorPersonId`, `ContainerForumId`, etc.).

> **Note**: The SURF Data Repository (`repository.surfsara.nl`) hosts LDBC datasets on tape storage. It provides the CsvComposite format (v0.3.5), **not** CsvCompositeMergeForeign. Do not use SURF datasets with this benchmark — the column layouts are incompatible.

## Project Structure

```
jmh-ldbc/
  pom.xml
  README.md
  src/main/java/.../ldbc/
    LdbcBenchmarkState.java            # @State — DB lifecycle, data loading, curated param access
    ParameterCurator.java              # Factor tables, gap-based grouping, per-query param gen
    LdbcQuerySql.java                  # Loads SQL query strings from classpath resources
    LdbcISUltraFastBenchmarkBase.java  # IS-ultra-fast: IS1,IS3-6,IC13 (5f, 1×5s, 3×10s)
    LdbcISBenchmarkBase.java           # IS-noisy: IS2,IS7,IC8 (10f, 3×5s, 3×10s)
    LdbcICBenchmarkBase.java           # IC: IC2,IC7,IC11 (5f, 3×5s, 5×10s)
    LdbcICSlowBenchmarkBase.java       # IC-slow: IC1,4,6,9,12 (5f, 3×10s, 5×10s)
    LdbcICUltraSlowBenchmarkBase.java  # IC-ultra-slow: IC3,5,10 (5f, 1×60s, 3×120s)
    LdbcSingleThread{ISUltraFast,IS,IC,ICSlow,ICUltraSlow}Benchmark.java  # @Threads(1)
    LdbcMultiThread{ISUltraFast,IS,IC,ICSlow,ICUltraSlow}Benchmark.java   # @Threads(MAX)
    LdbcDatabaseTool.java              # CLI for export/import/backup/restore operations
    LdbcExplainTool.java               # EXPLAIN/PROFILE for all queries
    BenchDataset.java                  # YTDB-916 S1: in-memory Bench schema + star-graph builder (size overrides)
    AnalyzedExprBenchmarkState.java    # @State — Bench 1: predicate cases, IR+AST, rotating rows
    ThroughputBenchmarkState.java      # @State — Bench 2: flat Bench dataset (FilterStep)
    ExpandThroughputBenchmarkState.java  # @State — Bench 3: star graph (ExpandStep)
    AnalyzedExprEvaluator{,SingleThread}Benchmark.java  # Bench 1: evalIr vs evalAst A/B (AverageTime)
    FilterStepThroughput{,SingleThread}Benchmark.java   # Bench 2: filterStep_ir/_astFallback (Throughput)
    ExpandStepThroughput{,SingleThread}Benchmark.java   # Bench 3: expandStep_ir/_astFallback (Throughput)
  src/main/resources/
    ldbc-schema.sql                    # DDL: vertex/edge classes, properties, indexes
    ldbc-queries/
      IS1.sql .. IS7.sql               # Interactive Short queries (YouTrackDB MATCH SQL)
      IC1.sql .. IC13.sql              # Interactive Complex queries (YouTrackDB MATCH SQL)
      IC4-oldpost-count.sql            # IC4 curation factor query (NOT-pattern cost)
    log4j2.xml                         # Logging configuration
  src/test/java/.../ldbc/
    AnalyzedExprGuardTest.java         # Bench-1 IR/AST parity guard (all 9 predicate cases)
    ExpandStepIrPathGuardTest.java     # Verifies the ExpandStep IR push-down path is taken
    LdbcQueryCorrectnessTest.java      # LDBC query result-correctness checks
    LdbcQueryExplainTest.java          # LDBC EXPLAIN/PROFILE checks
```

### SQL file conventions

- **Schema DDL** (`ldbc-schema.sql`) contains one statement per line, terminated by `;`.
  Comments use `--` (stripped by the Java loader before parsing).
- **Query files** (`ldbc-queries/*.sql`) each contain a single multi-line query.
  Comments use `/* ... */` C-style syntax, which is natively supported by the
  YouTrackDB SQL parser. Do **not** use `--` in query files — the parser treats
  `--` as the decrement operator.
