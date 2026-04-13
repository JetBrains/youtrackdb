---
name: profile-query-bottleneck
description: "Profile and diagnose YouTrackDB SQL/MATCH query bottlenecks. Combines async-profiler flame graphs, step-by-step selectivity measurement, and fan-out analysis on a Hetzner CCX33 server against the LDBC SF 1 dataset. Use when a query is slower than expected and you need to understand WHERE time is spent and WHY."
user-invocable: true
---

# Profile Query Bottleneck

Diagnose performance bottlenecks in YouTrackDB SQL and MATCH queries by combining
CPU profiling (async-profiler), step-by-step selectivity analysis, and fan-out
measurement on a dedicated Hetzner CCX33 server with the LDBC SF 1 dataset.

## When to Use

- A query is slower than expected after optimization
- You need to understand WHERE time is spent (CPU profile) and WHY (data selectivity)
- You want to quantify the fan-out at each step of a multi-step MATCH query
- You need to decide between query rewrite vs engine-level optimization

## Prerequisites

- `hcloud` CLI installed and authenticated
- `boto3` Python library installed locally
- SSH key pair at `~/.ssh/id_ed25519`
- The `jmh-ldbc` module compiles locally
- Environment variables: `HETZNER_S3_ACCESS_KEY`, `HETZNER_S3_SECRET_KEY`, `HETZNER_S3_ENDPOINT`

## Phase 1: Infrastructure Setup

Follow the `run-jmh-benchmarks-hetzner` skill's Steps 1–4 to:
1. Provision a CCX33 server
2. Install JDK 21, git, tmux
3. Deploy the project via rsync
4. Download the LDBC SF 1 CSV dataset from Hetzner S3
5. Compile `jmh-ldbc`
6. Run a pre-load fork to create the database from CSV and cache curated parameters

Additionally install async-profiler:
```bash
ssh root@<IP> 'cd /tmp && \
  curl -sLO https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz && \
  tar xzf async-profiler-3.0-linux-x64.tar.gz && \
  ln -sf /tmp/async-profiler-3.0-linux-x64 /opt/async-profiler && \
  echo 1 > /proc/sys/kernel/perf_event_paranoid && \
  echo 0 > /proc/sys/kernel/kptr_restrict && \
  echo "async-profiler ready"'
```

## Phase 2: CPU Profiling with async-profiler

### 2a. Create a Wrapper Script

The `-prof async:...;...;...` argument contains semicolons that are interpreted by the
remote shell when passed through SSH, causing the profiler to silently not attach. Use
the uber-jar directly (not Maven's `-Djmh.args`) with a wrapper script to avoid all
shell escaping issues:

```bash
ssh root@<IP> 'cat > /root/run-profile.sh << '\''SCRIPT'\''
#!/bin/bash
BENCH=$1      # benchmark regex
OUTPUT=$2     # flamegraph or collapsed
shift 2
ARGS="$@"     # JMH args

JVM_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.cs=ALL-UNNAMED --add-opens java.base/sun.security.x509=ALL-UNNAMED --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED -Xms4096m -Xmx4096m"

mkdir -p /root/profiles

cd /root/ytdb/jmh-ldbc && java $JVM_ARGS \
  -jar target/youtrackdb-jmh-ldbc-*.jar \
  "$BENCH" $ARGS \
  -prof "async:libPath=/opt/async-profiler/lib/libasyncProfiler.so;output=$OUTPUT;dir=/root/profiles;event=cpu"
SCRIPT
chmod +x /root/run-profile.sh'
```

### 2b. Flame Graph

Run the target benchmark with async-profiler attached:

```bash
ssh root@<IP> '/root/run-profile.sh "<BenchmarkClass>.<method>" flamegraph -f 1 -wi 1 -w 10s -i 1 -r 60s -t 1'
```

**Important**: Use `-t 1` (single thread) for profiling — multi-threaded profiles are
harder to interpret and the contention patterns differ from the actual bottleneck.

**Important**: Do NOT run any other CPU-intensive process (builds, other benchmarks,
diagnostic programs) while profiling. Concurrent processes compete for CPU and memory,
causing OOM kills (exit 137), corrupted profiles, and skewed results. Finish all other
work first, then run the profiler on a quiet machine.

Download the flame graphs:
```bash
scp root@<IP>:/root/profiles/<benchmark-name>-Throughput/flame-cpu-reverse.html /tmp/flame-reverse-$$.html
scp root@<IP>:/root/profiles/<benchmark-name>-Throughput/flame-cpu-forward.html /tmp/flame-forward-$$.html
```

### 2c. Collapsed Stacks for Programmatic Analysis

Run with `collapsed` output to get machine-parseable stacks:

```bash
ssh root@<IP> '/root/run-profile.sh "<BenchmarkClass>.<method>" collapsed -f 1 -wi 1 -w 10s -i 1 -r 60s -t 1'
```

Download:
```bash
scp root@<IP>:/root/profiles/<benchmark-name>-Throughput/collapsed-cpu.csv /tmp/collapsed-$$.csv
```

### 2d. Filter Non-Measurement Stacks

Async-profiler captures ALL JVM threads across the entire fork lifetime — including
`@TearDown`, WAL vacuum, GC, and JVM service threads. These inflate sample counts
and obscure the real benchmark-thread signal. **Always filter before analysis.**

```bash
grep -vE 'tearDown|WALVacuum|G1Conc|G1ParScan|GCThread|GangWorker|VMThread|CompilerThread|ServiceThread|SafepointSynchronize|SafepointCleanup|MonitorDeflation' collapsed-cpu.csv > collapsed-filtered.csv
```

Compare total samples before and after filtering. Large deltas indicate TearDown
overhead or background thread contention — production concerns but not measurement
bottlenecks.

Use the filtered file for all subsequent analysis steps.

### 2e. Analyzing Collapsed Stacks

The collapsed stack format is: `frame1;frame2;...;leafFrame count`

**Find hottest leaf methods** (actual CPU consumers):
```bash
cat collapsed-filtered.csv | awk '{
    n = split($0, parts, ";")
    last = parts[n]
    idx = match(last, / [0-9]+$/)
    if (idx > 0) { count = substr(last, idx+1)+0; frame = substr(last, 1, idx-1) }
    else next
    leaves[frame] += count
  } END { for (f in leaves) print leaves[f], f }' | sort -rn | head -30
```

**Aggregate by YouTrackDB/Gremlin method** (inclusive samples):
```bash
cat collapsed-filtered.csv | awk -F';' '{
    n = split($0, parts, ";")
    last = parts[n]; split(last, lp, " "); count = lp[length(lp)]
    for (i=1; i<=n; i++) {
      frame = parts[i]; if (i == n) { split(frame, fp, " "); frame = fp[1] }
      if (frame ~ /youtrackdb|tinkerpop|gremlin/) {
        gsub(/ $/, "", frame); frames[frame] += count
      }
    }
  } END { for (f in frames) print frames[f], f }' | sort -rn | head -40
```

**Trace call chains from a specific method** (e.g., what calls `loadEntity`):
```bash
cat collapsed-filtered.csv | grep 'loadEntity' | awk '{
    n = split($0, parts, ";"); last = parts[n]
    idx = match(last, / [0-9]+$/); count = substr(last, idx+1)+0
    for (i=1; i<=n; i++) {
      if (parts[i] ~ /loadEntity/) {
        key = ""
        for (j=i-3; j<=i; j++) {
          if (j >= 1) { frame = parts[j]; gsub(/ [0-9]+$/, "", frame)
            gsub(/.*\//, "", frame); key = key (key=="" ? "" : " -> ") frame }
        }
        paths[key] += count; break
      }
    }
  } END { for (p in paths) print paths[p], p }' | sort -rn | head -20
```

### 2f. Key Methods to Watch For

| Method | What it means |
|---|---|
| `EdgeFromLinkBagIterator.loadEdge` | Loading edge records — proportional to edges traversed |
| `VertexFromLinkBagIterator.loadVertex` | Loading vertex records from link bags |
| `EdgeEntityImpl.getTo/getFrom` | Resolving edge→vertex (each triggers a record load) |
| `SQLFunctionMove.execute` | Graph traversal step (.out/.in/.outE/.inE) |
| `SQLFunctionInV/OutV.move` | .inV()/.outV() resolution |
| `SQLAndBlock.evaluate` | WHERE clause filter evaluation |
| `MatchEdgeTraverser.executeTraversal` | MATCH edge step execution |
| `MatchEdgeTraverser.applyPreFilter` | Pre-filter (RID intersection) application |
| `AbstractLinkBag$MergingSpliterator` | Link bag iteration (proportional to adjacency list size) |
| `RecordCacheWeakRefs.get` | Record cache lookups |
| `EntityImpl.deserializeProperties` | Deserialization cost |
| `FrontendTransactionImpl.loadEntity` | Full entity load (cache miss → disk read) |
| `DatabaseSessionEmbedded.executeReadRecord` | Lowest-level record read |

**High `loadEdge`/`loadVertex` samples** = too many records being loaded.
**High `SQLAndBlock.evaluate`** = filter evaluation is expensive (complex WHERE clauses).
**High `deserializeProperties`** = loading properties that aren't needed.

## Phase 3: Step-by-Step Selectivity Analysis

This is the most valuable diagnostic. For a multi-step MATCH query, measure the row count
and execution time at each intermediate step to find the combinatorial explosion point.

### 3a. Write a Diagnostic Java Program

Create a Java file that opens the LDBC database and runs progressively longer prefixes
of the MATCH query, measuring row count and time at each step.

**Template** (adapt the MATCH chain to your query):

```java
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.*;

public class QueryDiag {
  static YTDBGraphTraversalSourceDSL g;

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> sql(String q, Object... kv) {
    return g.computeInTx(tx -> {
      var dsl = (YTDBGraphTraversalSource) tx;
      var results = new ArrayList<Map<String, Object>>();
      for (Object r : dsl.yql(q, kv).toList()) results.add((Map<String, Object>) r);
      return results;
    });
  }

  static int count(String q, Object... kv) { return sql(q, kv).size(); }

  public static void main(String[] args) throws Exception {
    var db = YourTracks.instance("./jmh-ldbc/target/ldbc-bench-db");
    g = (YTDBGraphTraversalSourceDSL) db.openTraversal("ldbc_benchmark", "admin", "admin");

    // Get sample parameter values
    var persons = sql("SELECT id FROM Person ORDER BY id LIMIT 3");
    // ... extract IDs ...

    for (long pid : pids) {
      // Step 1: first edge only
      long t0 = System.nanoTime();
      int step1 = count("MATCH {class: Person, where: (id = :pid)}"
          + ".out('KNOWS'){as: friend} RETURN friend", "pid", pid);
      System.out.printf("Step 1: %d rows [%d ms]%n", step1, (System.nanoTime()-t0)/1_000_000);

      // Step 2: first + second edge
      // ... progressively add edges ...

      // Step N: full query
      // ... measure full query ...
    }

    g.close();
    db.close();
  }
}
```

### 3b. API Gotchas

- **Named parameters**: `yql()` takes key/value pairs: `"paramName", value, "param2", value2`
  NOT positional `?` placeholders.
- **Date parameters**: Pass `new Date(epochMillis)`, NOT raw `Long`. The histogram
  selectivity estimator will throw `ClassCastException: Long cannot be cast to Date`
  if you pass Long for a Date-typed indexed property.
- **Return type**: `yql().toList()` returns `List<Object>` where each object is a
  `Map<String, Object>` (not a `Result` instance). Cast directly to `Map`.
- **DB locking**: If the program crashes or is killed, remove lock files before re-running:
  `find /root/ytdb/jmh-ldbc/target/ldbc-bench-db -name "*.lock" -exec rm -f {} \;`
- **JMH lock file**: If JMH exits abnormally (OOM kill, SIGKILL), it leaves `/tmp/jmh.lock`.
  Remove it before re-running: `rm -f /tmp/jmh.lock`
- **SQL parser limitations**: The YouTrackDB SQL parser does not support function calls
  like `out('KNOWS').size()` in ORDER BY. Always alias computed expressions first:
  `SELECT out('KNOWS').size() as cnt ... ORDER BY cnt DESC` (not `ORDER BY out('KNOWS').size() DESC`)

### 3c. Compiling and Running

**Important**: Run from the **project root** (`/root/ytdb`), not from `jmh-ldbc/`.
The diagnostic program uses `./jmh-ldbc/target/ldbc-bench-db` as the DB path.
The benchmark itself (JMH via Maven) runs from `jmh-ldbc/` and uses `./target/ldbc-bench-db`.

```bash
# From /root/ytdb — compile against the uber-jar (has all dependencies)
javac -proc:none -cp "jmh-ldbc/target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar" QueryDiag.java

# Run with required --add-opens flags
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     --add-opens java.base/java.io=ALL-UNNAMED \
     --add-opens java.base/java.nio=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
     --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
     --add-opens java.base/java.net=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens java.base/sun.nio.cs=ALL-UNNAMED \
     --add-opens java.base/sun.security.x509=ALL-UNNAMED \
     --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED \
     -cp ".:jmh-ldbc/target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar" -Xmx4g QueryDiag
```

Note: the `jdk.unsupported/sun.misc=ALL-UNNAMED` flag is required for the storage engine.
Without it, the DB opens but fails with `InaccessibleObjectException` on first record load.

### 3d. What to Measure

For each step in the MATCH chain, record:

| Metric | Why |
|---|---|
| **Row count** | Identifies the fan-out explosion point |
| **Distinct row count** | Reveals duplicate amplification |
| **Execution time** | Shows per-step cost |
| **Time delta vs previous step** | Isolates the expensive step |

Also compute **selectivity ratios**:
- `rows[N] / rows[N-1]` = fan-out at step N
- `final_rows / intermediate_rows` = overall selectivity (how much work is wasted)
- `distinct / total` = duplicate ratio

### 3e. Fan-Out Statistics

For key edges, measure the degree distribution:

```sql
-- Average out-degree for an edge class from a specific vertex set
SELECT min(cnt) as minD, max(cnt) as maxD, avg(cnt) as avgD FROM (
  SELECT out('EDGE_CLASS').size() as cnt FROM (
    MATCH {class: StartClass, where: (id = :id)}
    .out('PREV_EDGE'){as: v} RETURN v))
```

This reveals whether fan-out is uniform or skewed (a few vertices with huge adjacency lists).

## Phase 4: Interpreting Results

### Decision Framework

After collecting profile + selectivity data, classify the bottleneck:

**1. Combinatorial explosion in intermediate rows**
- Symptom: Step K produces 100K+ rows, but final result is <1% of that
- Profile: CPU spread across many methods, no single hotspot dominates
- Fix: Query rewrite (reorder joins, add early filtering, pre-compute sets)

**2. Expensive per-row operation**
- Symptom: A specific step takes disproportionate time relative to its row count
- Profile: Single method dominates (e.g., `loadEdge`, `deserializeProperties`)
- Fix: Reduce per-row cost (caching, avoid unnecessary loads, batch operations)

**3. Large adjacency list iteration**
- Symptom: High samples in `AbstractLinkBag$MergingSpliterator`, `LinkBag.iterator`
- Profile: `EdgeFromLinkBagIterator.hasNext` dominates
- Fix: Pre-filter with RID intersection, index-assisted traversal, limit iteration

**4. Filter evaluation overhead**
- Symptom: High samples in `SQLAndBlock.evaluate`, `SQLOrBlock.evaluate`
- Profile: WHERE clause evaluation dominates, not data access
- Fix: Push filters earlier, simplify expressions, use index pre-filters

### Common MATCH Query Patterns and Their Costs

| Pattern | Cost Model | Notes |
|---|---|---|
| `.out('E'){class: V}` | O(adjacency list size) | Filtered by collection ID |
| `.outE('E').inV()` | O(adjacency list) + O(edge load per match) | Each edge must be loaded to resolve target vertex |
| `while: ($depth < N)` | O(fan-out^N) | Exponential — the most expensive pattern |
| `where: (@rid = $matched.X.@rid)` | O(1) with pre-filter, O(adjacency list) without | Back-reference check |
| `where: (prop >= :val)` on indexed edge | O(log N) with index pre-filter | Requires index on edge class property |

### The "700K Rows" Anti-Pattern

A common bottleneck in LDBC queries: `while: ($depth < 2)` on KNOWS produces
~3-5K friends, each with ~100-200 posts = 300K-1M intermediate rows. Even with
O(1) per-row filtering downstream, the sheer volume dominates.

**Mitigations**:
1. **Pre-compute filter sets**: Before MATCH, compute the set of valid target IDs
   (e.g., forums the person belongs to), then filter during traversal
2. **Hash join**: Collect one side of a join into a set, probe during the other side
3. **Early DISTINCT**: If downstream steps only need distinct values, deduplicate early
4. **Inverted direction**: Sometimes traversing from the "filter side" first produces
   fewer intermediate rows

## Phase 5: Cleanup

Always destroy the Hetzner server when done. Use the same branch-based names
from `run-jmh-benchmarks-hetzner` Step 1:
```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD | tr '[:upper:]/' '[:lower:]-' | cut -c1-40)
SERVER_NAME="jmh-bench-${BRANCH}"
KEY_NAME="jmh-bench-key-${BRANCH}"

hcloud server delete "$SERVER_NAME"
hcloud ssh-key delete "$KEY_NAME"
```

## Phase 6: Self-Improvement Review

After completing the analysis, review the entire session for desynchronizations and improvements. This step is **mandatory** — do not skip it.

### 6a. Skill desynchronization check

Compare what actually happened during execution against what this skill document describes. Flag any discrepancies:

- **File paths or formats that changed**: e.g., async-profiler output extension (`.csv` vs `.collapsed`), jar name, directory layout
- **Commands that failed or needed modification**: e.g., shell escaping issues, missing flags, incorrect regex patterns
- **New workarounds discovered**: e.g., `apt-get` lock on fresh servers, JMH lock conflicts, DB lock files after crashes
- **API changes**: e.g., `yql()` return type changed, new parameter passing conventions, class renames
- **Analysis methods that didn't work or needed adaptation**: e.g., `awk` field separator assumptions, stack frame format changes

### 6b. Routine improvement proposals

Reflect on the profiling session and identify improvements to the workflow:

- **Efficiency**: Were there unnecessary sequential steps that could be parallelized? Did any step take much longer than expected?
- **Analysis gaps**: Was any important signal missed that required backtracking? Would a different analysis order have been faster?
- **New patterns**: Did a new bottleneck category emerge that isn't listed in Phase 4? Were new methods or code paths important that aren't in Section 2f?
- **Selectivity analysis**: Did the diagnostic program template need significant adaptation? Would a different measurement approach have been more informative?
- **Tooling**: Would a different async-profiler output format (e.g., `jfr`, `tree`) have been more useful? Would differential flamegraphs help?

**Important**: All proposed improvements must be **generally applicable** — they should benefit any future profiling session, not just the specific query or bottleneck analyzed in this session. Do not propose narrow fixes that only apply to one query or one particular code path.

### 6c. Propose updates

If any desynchronizations or improvements were found, **present them to the user** as a numbered list of proposed skill edits. Include:

1. What to change (quote the current text)
2. Why (what went wrong or what would improve)
3. The proposed new text

Apply changes only after user approval. If nothing needs updating, explicitly state: "Skill is in sync — no updates needed."

## Reference: LDBC SF 1 Dataset Statistics

| Entity | Count |
|---|---|
| Person | 10,620 |
| Post | 1,192,942 |
| Comment | ~2,000,000 |
| Forum | 106,594 |
| Company | ~1,575 |
| Country | ~111 |
| KNOWS edges | ~360,000 |
| HAS_CREATOR edges | ~3,200,000 |
| HAS_MEMBER edges | 3,260,692 |
| WORK_AT edges | 22,766 |
| STUDY_AT edges | ~17,000 |
| IS_LOCATED_IN edges | ~4,400,000 |

Average degrees:
- KNOWS: ~34 per person (min 0, max 977)
- HAS_CREATOR (Post only): ~112 posts per person
- HAS_MEMBER: ~30 members per forum, ~307 forums per person
- CONTAINER_OF: ~11 posts per forum
- WORK_AT: ~2.15 per person (min 0, max 5)
- Companies per country: ~14 on average

WORK_AT.workFrom distribution (year → edge count):
1998: 117, 1999: 411, 2000: 887, 2001: 1227, 2002: 1667, 2003: 1999,
2004: 2155, 2005: 2127, 2006: 2168, 2007: 2332, 2008: 2252, 2009: 1918,
2010: 1484, 2011: 1105, 2012: 825, 2013: 92
→ 85% of WORK_AT edges have workFrom < 2010

These numbers are essential for estimating query cost. A `while: ($depth < 2)` KNOWS
traversal from a typical person produces 34 + 34*34 ≈ 1,190 paths (with duplicates),
~800 distinct friends. For high-degree persons (top 5 have 936-977 KNOWS), this
explodes to ~51K paths (~8.4K distinct) with 6.1x duplication.
