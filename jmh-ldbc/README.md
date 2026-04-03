---
source_files:
  - jmh-ldbc/src/main/java/**
  - jmh-ldbc/src/main/resources/ldbc-schema.sql
  - jmh-ldbc/src/main/resources/ldbc-queries/**
  - jmh-ldbc/pom.xml
related_docs:
  - docs/README.md
---

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
| IC | `LdbcICBenchmarkBase` | IC2, IC7, IC11 | 5 | 3×5s | 5×10s | 17-215 |
| IC-slow | `LdbcICSlowBenchmarkBase` | IC1, IC4, IC6, IC9, IC12 | 5 | 3×10s | 5×10s | 1-21 |
| IC-ultra-slow | `LdbcICUltraSlowBenchmarkBase` | IC3, IC5, IC10 | 5 | 1×60s | 3×120s | <0.2 |

Each tier has single-threaded and multi-threaded concrete classes:

- **`LdbcSingleThreadISUltraFastBenchmark`** / **`LdbcMultiThreadISUltraFastBenchmark`** — IS-ultra-fast tier
- **`LdbcSingleThreadISBenchmark`** / **`LdbcMultiThreadISBenchmark`** — IS-noisy tier
- **`LdbcSingleThreadICBenchmark`** / **`LdbcMultiThreadICBenchmark`** — IC tier
- **`LdbcSingleThreadICSlowBenchmark`** / **`LdbcMultiThreadICSlowBenchmark`** — IC-slow tier
- **`LdbcSingleThreadICUltraSlowBenchmark`** / **`LdbcMultiThreadICUltraSlowBenchmark`** — IC-ultra-slow tier

Multi-threaded classes use `@Threads(Threads.MAX)` (one thread per available processor).

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
1. Check for an existing database at `./target/ldbc-bench-db` (or the path specified by `-Dldbc.db.path`). If a pre-built database exists, it is reused directly (skips steps 2-3).
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

**Critical**: All benchmark runs — CI comparisons, nightly baselines, and local profiling — **must use the same curated parameters**. The curated parameter files are stored in Hetzner S3 alongside the pre-built database and must never be regenerated independently.

- **S3 keys**: `ldbc/curated-params-v3.json`, `ldbc/factor-tables.json`
- **Install location**: `jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json` and `factor-tables.json`

**Why this matters**: The `ParameterCurator` samples 200 (person, date) pairs from a `friendsSelected × dates` cross-product using stride-based sampling. The stride depends on iteration order of `friendsSelected`, which comes from database query results. Any code change that affects internal data structure ordering (hash maps, indexes, etc.) changes the iteration order, causing different pairs to be sampled. For IC4 in particular, different pairs can have vastly different "old post counts" (the NOT-pattern cost factor), leading to **up to 7x throughput differences** between runs that should be identical. This was discovered when a cache layer change produced a spurious +586% IC4 "improvement" in CI that did not reproduce with shared parameters.

**To regenerate canonical parameters** (only when the curation algorithm itself changes):
1. Build from develop: `./mvnw -pl jmh-ldbc -am package -DskipTests`
2. Ensure the pre-built DB is extracted at `jmh-ldbc/target/ldbc-bench-db`
3. Delete existing params: `rm -f jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json jmh-ldbc/target/ldbc-bench-db/factor-tables.json`
4. Run any benchmark to trigger regeneration: `java -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1`
5. Upload the new files to S3: `ldbc/curated-params-v3.json` and `ldbc/factor-tables.json`
6. Rebuild the pre-built DB tar to include them and upload to `ldbc/ldbc-sf1-bench-db.tar.zst`

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
| `ldbc.dataset.path` | `./target/ldbc-dataset/sf1` | Path to the LDBC dataset root (must contain `static/` and `dynamic/` subdirectories). Only needed if no pre-built DB exists. |
| `ldbc.db.path` | `./target/ldbc-bench-db` | Directory where the YouTrackDB database is stored. If a pre-built DB exists here, CSV loading is skipped. |
| `ldbc.scale.factor` | `1` | Scale factor (used in default dataset path). |
| `ldbc.batch.size` | `1000` | Batch size for CSV data loading. |

Example with a custom dataset path or smaller scale factor:

```bash
# Use SF 0.1 for quick local testing
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Dldbc.scale.factor=0.1

# Use a pre-built database from a custom path
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

There are three ways to set up the benchmark database:

#### Option 1: Download pre-built database from S3 (fastest, recommended for manual runs)

A pre-built YouTrackDB database for SF 1 is maintained in Hetzner Object Storage. This is the fastest option — it skips the ~21-minute CSV loading step entirely. The nightly CI workflow automatically uploads a fresh DB snapshot after each successful run. The pre-built DB includes [canonical curated parameters](#canonical-curated-parameters) — do not delete or regenerate them.

- **Bucket**: `bench-cache`
- **Key**: `ldbc/ldbc-sf1-bench-db.tar.zst` (~1.3 GB)
- **Credentials**: stored as GitHub repository secrets `HETZNER_S3_ACCESS_KEY`, `HETZNER_S3_SECRET_KEY`, `HETZNER_S3_ENDPOINT`

```bash
# Download and extract using the AWS CLI (or any S3-compatible client)
aws s3 cp s3://bench-cache/ldbc/ldbc-sf1-bench-db.tar.zst /tmp/bench-db.tar.zst \
    --endpoint-url "$HETZNER_S3_ENDPOINT"

# Extract to the expected DB path
mkdir -p jmh-ldbc/target/ldbc-bench-db
cd jmh-ldbc/target/ldbc-bench-db
zstd -d /tmp/bench-db.tar.zst -o /tmp/bench-db.tar
tar xf /tmp/bench-db.tar
rm -f /tmp/bench-db.tar.zst /tmp/bench-db.tar
```

#### Option 2: Download CSV dataset from S3 and load fresh

Download the raw CSV dataset and let the benchmark load it into a new database. This is what the nightly CI workflow does.

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

#### Option 3: Generate using LDBC datagen Docker image

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
  src/main/resources/
    ldbc-schema.sql                    # DDL: vertex/edge classes, properties, indexes
    ldbc-queries/
      IS1.sql .. IS7.sql               # Interactive Short queries (YouTrackDB MATCH SQL)
      IC1.sql .. IC13.sql              # Interactive Complex queries (YouTrackDB MATCH SQL)
      IC4-oldpost-count.sql            # IC4 curation factor query (NOT-pattern cost)
    log4j2.xml                         # Logging configuration
```

### SQL file conventions

- **Schema DDL** (`ldbc-schema.sql`) contains one statement per line, terminated by `;`.
  Comments use `--` (stripped by the Java loader before parsing).
- **Query files** (`ldbc-queries/*.sql`) each contain a single multi-line query.
  Comments use `/* ... */` C-style syntax, which is natively supported by the
  YouTrackDB SQL parser. Do **not** use `--` in query files — the parser treats
  `--` as the decrement operator.
