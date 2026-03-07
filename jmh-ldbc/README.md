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

- **`LdbcSingleThreadBenchmark`** — runs all 20 queries with a single thread.
- **`LdbcMultiThreadBenchmark`** — runs all 20 queries with one thread per available processor (`Threads.MAX`, configurable at runtime via `-t`).

Both classes inherit from `LdbcReadBenchmarkBase` and differ only in the `@Threads` annotation.

## Execution Time

Default JMH settings per benchmark: 3 warmup iterations (5s each) + 5 measurement iterations (10s each) + 1 fork.

| Suite | Benchmarks | Approx. Time |
|-------|-----------|-------------|
| `LdbcSingleThreadBenchmark` | 20 | ~22 min |
| `LdbcMultiThreadBenchmark` | 20 | ~22 min |
| **Both suites** | 40 | **~44 min** |
| First run (includes DB init) | — | adds ~5 min |

## Prerequisites

- **JDK 21+**
- **curl** or **wget** — for automatic dataset download.
- **zstd** CLI or **python3** with the `zstandard` package — for extracting the dataset archive. The benchmark tries `zstd` first, then falls back to `python3 -c "import zstandard"`.
  ```bash
  # Install zstd
  sudo apt install zstd        # Ubuntu/Debian
  brew install zstd             # macOS
  sudo dnf install zstd         # Fedora

  # Or install the Python fallback
  pip install zstandard
  ```
- **Network access** to `repository.surfsara.nl` for automatic dataset download (only on first run). The SURF Data Repository stores files on tape; if the dataset is offline, the benchmark automatically stages it and polls until it becomes available (can take up to 20 minutes on the very first request).

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
1. Download the LDBC SF 0.1 dataset (~22 MB compressed) from the SURF repository.
2. Extract it using `zstd` or Python `zstandard`.
3. Create a YouTrackDB database, create the LDBC schema from `ldbc-schema.sql` (vertex/edge classes + indexes), and load all CSV data (~1.8M records, ~5 min).
4. Sample query parameters (person IDs, message IDs, tag names, etc.) from the loaded data.

Subsequent runs reuse the existing database (~2s startup).

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

## Configuration

All settings are passed as JVM system properties. When using Maven, add `-D` flags before `exec:exec`; when using the uber-jar, add them before `-jar`:

| Property | Default | Description |
|----------|---------|-------------|
| `ldbc.dataset.path` | `./target/ldbc-dataset/sf0.1` | Path to the LDBC dataset root (must contain `static/` and `dynamic/` subdirectories). If absent, the dataset is auto-downloaded. |
| `ldbc.db.path` | `./target/ldbc-bench-db` | Directory where the YouTrackDB database is stored. |
| `ldbc.scale.factor` | `0.1` | Scale factor for auto-download. Determines which dataset archive to fetch. |
| `ldbc.batch.size` | `1000` | Batch size for CSV data loading. |

Example with a pre-downloaded dataset and larger scale factor:

```bash
# Maven (single command)
./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
    -Dldbc.dataset.path=/data/ldbc/sf1 \
    -Dldbc.scale.factor=1

# Maven (two-step)
./mvnw -pl jmh-ldbc -am compile exec:exec \
    -Dldbc.dataset.path=/data/ldbc/sf1 \
    -Dldbc.scale.factor=1

# Uber-jar
java -Dldbc.dataset.path=/data/ldbc/sf1 \
     -Dldbc.scale.factor=1 \
     -jar jmh-ldbc/target/youtrackdb-jmh-ldbc-*.jar
```

## Dataset

The benchmark uses the LDBC SNB Interactive dataset in **CSV Composite (long date formatter)** format, hosted at the [SURF Data Repository](https://repository.surfsara.nl/datasets/cwi/snb/).

Available scale factors: 0.1, 0.3, 1, 3, 10, 30, 100. Larger scale factors produce more data and longer-running queries.

SF 0.1 (default) contains:

| Entity | Count |
|--------|-------|
| Person | 1,528 |
| Post | 135,701 |
| Comment | 151,043 |
| Forum | 13,750 |
| Tag | 16,080 |
| Organisation | 7,955 |
| Place | 1,460 |
| **Total records** | **~1.8M** |

The dataset is loaded into YouTrackDB with the full LDBC schema: 15 vertex classes, 15 edge classes, and 21 indexes.

## Project Structure

```
jmh-ldbc/
  pom.xml
  README.md
  src/main/java/.../ldbc/
    LdbcBenchmarkState.java        # @State — DB lifecycle, dataset download, data loading,
                                   #   parameter sampling, SQL execution helper
    LdbcQuerySql.java              # Loads SQL query strings from classpath resources
    LdbcReadBenchmarkBase.java     # Abstract base with 20 @Benchmark methods
    LdbcSingleThreadBenchmark.java # @Threads(1) — single-threaded suite
    LdbcMultiThreadBenchmark.java  # @Threads(8) — multi-threaded suite
    LdbcDatabaseTool.java          # CLI for export/import/backup/restore operations
  src/main/resources/
    ldbc-schema.sql                # DDL: vertex/edge classes, properties, indexes
    ldbc-queries/
      IS1.sql .. IS7.sql           # Interactive Short queries (YouTrackDB MATCH SQL)
      IC1.sql .. IC13.sql          # Interactive Complex queries (YouTrackDB MATCH SQL)
    log4j2.xml                     # Logging configuration
```

### SQL file conventions

- **Schema DDL** (`ldbc-schema.sql`) contains one statement per line, terminated by `;`.
  Comments use `--` (stripped by the Java loader before parsing).
- **Query files** (`ldbc-queries/*.sql`) each contain a single multi-line query.
  Comments use `/* ... */` C-style syntax, which is natively supported by the
  YouTrackDB SQL parser. Do **not** use `--` in query files — the parser treats
  `--` as the decrement operator.
