---
name: profile-jmh-regressions
description: "Profile JMH benchmark regressions using async-profiler on a Hetzner CCX33 server. Reads regressions from a PR benchmark comment, profiles both HEAD and BASE with collapsed-stack output, compares self-time and inclusive-time per method, and identifies root causes. Use when the user asks to profile regressions after a benchmark comparison run."
user-invocable: true
---

Profile benchmark regressions found in a PR's JMH comparison comment. Provisions a Hetzner CCX33 server, deploys both HEAD and BASE code, runs async-profiler on the regressing benchmarks, performs differential analysis of collapsed stacks, and reports root causes.

## Prerequisites

- `hcloud` CLI installed and authenticated
- `boto3` Python library installed locally
- SSH key pair at `~/.ssh/id_ed25519`
- A PR with a JMH benchmark comparison comment (posted by `ldbc-jmh-compare.yml` or manually)
- Environment variables: `HETZNER_S3_ACCESS_KEY`, `HETZNER_S3_SECRET_KEY`, `HETZNER_S3_ENDPOINT`

## Workflow

### Step 0: Identify regressions

Read the benchmark comparison comment from the PR on the current branch:

```bash
# Find the PR
gh pr list --head $(git rev-parse --abbrev-ref HEAD) --json number,url

# Read the latest benchmark comment (filtering by content to avoid noise)
gh pr view <NUMBER> --json comments --jq '.comments | map(select(.body | contains("## JMH LDBC Benchmark Comparison"))) | last | .body'
```

Parse the markdown table to extract all benchmarks marked with `:red_circle:` (regression). Record for each:
- **Benchmark name** (e.g., `ic4_newTopics`)
- **Suite** — Single-thread (`LdbcSingleThread*`) or Multi-thread (`LdbcMultiThread*`)
- **Regression magnitude** (delta %)

Also record the **base commit** (fork-point with develop):
```bash
git merge-base HEAD origin/develop
```

### Step 1: Determine JMH parameters per regression

Each benchmark belongs to a tier with different profiling settings. Benchmarks are assigned to tiers by query name (see `jmh-ldbc/README.md`), not by dynamic ops/s lookup. To determine the tier, check which base class the benchmark extends.

**Note**: The profiling args below are intentionally reduced from the production annotations (fewer forks, shorter warmup/measurement). Profiling needs only 1 fork — we're analyzing CPU distribution, not statistical throughput. Warmup is shortened but kept long enough for JIT to stabilize.

| Tier | Base Class | Queries | Production annotations | Profiling args |
|------|-----------|---------|----------------------|----------------|
| IS-ultra-fast | `LdbcISUltraFastBenchmarkBase` | IS1, IS3-IS6, IC13 | 5f, 1×5s wi, 3×10s | `-f 1 -wi 1 -w 5s -i 3 -r 10s -t 1` (ST) |
| IS-noisy | `LdbcISBenchmarkBase` | IS2, IS7, IC8 | 10f, 3×5s wi, 3×10s | `-f 1 -wi 1 -w 5s -i 3 -r 10s -t 1` (ST) |
| IC | `LdbcICBenchmarkBase` | IC2, IC7, IC11 | 3f, 1×10s wi, 5×20s | `-f 1 -wi 1 -w 10s -i 3 -r 20s -t 1` (ST) |
| IC-slow | `LdbcICSlowBenchmarkBase` | IC1, IC4, IC6, IC9, IC12 | 3f, 1×30s wi, 5×30s | `-f 1 -wi 1 -w 30s -i 3 -r 30s -t 1` (ST) |
| IC-ultra-slow | `LdbcICUltraSlowBenchmarkBase` | IC3, IC5, IC10 | 5f, 1×60s wi, 3×120s | `-f 1 -wi 1 -w 60s -i 3 -r 60s -t 1` (ST) |

**IC4 exception**: `ic4_newTopics` has a method-level override `@Warmup(iterations = 3, time = 30)`. For profiling, use `-wi 2 -w 30s` instead of the IC-slow default.

For multi-thread regressions, use the same warmup/measurement but omit `-t 1` (uses `@Threads(Threads.MAX)` default).

### Step 2: Provision the server

Use the same naming convention as `run-jmh-benchmarks-hetzner`:

```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/--*/-/g' | cut -c1-40)
SERVER_NAME="jmh-prof-${BRANCH}"
KEY_NAME="jmh-prof-key-${BRANCH}"

hcloud ssh-key create --name "$KEY_NAME" --public-key-from-file ~/.ssh/id_ed25519.pub
hcloud server create --name "$SERVER_NAME" --type ccx33 --image ubuntu-24.04 --location fsn1 --ssh-key "$KEY_NAME"
```

Record the IPv4. Wait ~15s for boot. Remove stale host key if needed:
```bash
ssh-keygen -f ~/.ssh/known_hosts -R <IP>
```

### Step 3: Install dependencies

```bash
ssh -o StrictHostKeyChecking=no root@<IP> \
  'apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless git tmux > /dev/null 2>&1 && java -version'
```

Install async-profiler:
```bash
ssh root@<IP> 'cd /tmp && \
  curl -sLO https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz && \
  tar xzf async-profiler-3.0-linux-x64.tar.gz && \
  echo 1 > /proc/sys/kernel/perf_event_paranoid && \
  echo 0 > /proc/sys/kernel/kptr_restrict'
```

The async-profiler library path is: `/tmp/async-profiler-3.0-linux-x64/lib/libasyncProfiler.so`

### Step 4: Deploy HEAD and BASE

**HEAD** (current worktree):
```bash
rsync -az --exclude='.git' --exclude='target' --exclude='.idea' "$(git rev-parse --show-toplevel)/" root@<IP>:/root/ytdb/
ssh root@<IP> 'git config --global --add safe.directory /root/ytdb && \
  git config --global user.email "bench@test" && \
  git config --global user.name "bench" && \
  cd /root/ytdb && git init && git add -A && git commit -m "head" --quiet'
```

**BASE** (fork-point commit): Create a local worktree, rsync to a separate directory:
```bash
BASE_COMMIT=$(git merge-base HEAD origin/develop)
WORKTREE_DIR="/tmp/ytdb-base-profiling-$$"
rm -rf "$WORKTREE_DIR" && git worktree prune
git worktree add "$WORKTREE_DIR" "$BASE_COMMIT"

rsync -az --exclude='.git' --exclude='target' --exclude='.idea' "$WORKTREE_DIR/" root@<IP>:/root/ytdb-base/
ssh root@<IP> 'cd /root/ytdb-base && git init && git add -A && git commit -m "base" --quiet'
```

### Step 5: Compile both versions and download LDBC CSV dataset

Compile both in parallel using isolated local repositories to avoid `~/.m2/repository` corruption from concurrent writes:
```bash
ssh root@<IP> '
  (cd /root/ytdb && chmod +x mvnw && ./mvnw -pl jmh-ldbc -am package -DskipTests -Dspotless.check.skip=true -q -Dmaven.repo.local=/root/.m2-head) &
  (cd /root/ytdb-base && chmod +x mvnw && ./mvnw -pl jmh-ldbc -am package -DskipTests -Dspotless.check.skip=true -q -Dmaven.repo.local=/root/.m2-base) &
  wait'
```

Download the LDBC SF 1 CSV dataset and canonical curated parameters from Hetzner S3. Generate presigned URLs locally (see `run-jmh-benchmarks-hetzner` skill Step 3b for the boto3 presigned URL generation):

```bash
# Generate presigned URLs locally (boto3 required)
# IMPORTANT: Force HTTPS — the HETZNER_S3_ENDPOINT env var may contain http://
# but Hetzner servers cannot reach the S3 endpoint over plain HTTP (connection timeout).
python3 -c "
import boto3, os
endpoint = os.environ['HETZNER_S3_ENDPOINT']
if endpoint.startswith('http://'):
    endpoint = 'https://' + endpoint[len('http://'):]
s3 = boto3.client('s3',
    endpoint_url=endpoint,
    aws_access_key_id=os.environ['HETZNER_S3_ACCESS_KEY'],
    aws_secret_access_key=os.environ['HETZNER_S3_SECRET_KEY'])
for key in ['ldbc/ldbc-sf1-composite-merged-fk.tar.zst', 'ldbc/curated-params-v3.json', 'ldbc/factor-tables.json']:
    url = s3.generate_presigned_url('get_object',
        Params={'Bucket': 'bench-cache', 'Key': key},
        ExpiresIn=7200)
    print(f'{key}: {url}')
"
```

Download and extract CSV dataset:
```bash
# Download and extract CSV dataset to HEAD
# Note: the tar archive contains a top-level sf1/ directory, so extract into ldbc-dataset/
# to get the expected ldbc-dataset/sf1/static/ and ldbc-dataset/sf1/dynamic/ layout.
ssh root@<IP> 'apt-get install -y -qq zstd > /dev/null 2>&1 && \
  mkdir -p /root/ytdb/jmh-ldbc/target/ldbc-dataset && \
  cd /root/ytdb/jmh-ldbc/target/ldbc-dataset && \
  curl -sS "<CSV_PRESIGNED_URL>" | zstd -dc | tar xf - && \
  echo "Dataset ready" && ls sf1/static/ sf1/dynamic/'

# Copy CSV dataset to BASE
ssh root@<IP> 'cp -r /root/ytdb/jmh-ldbc/target/ldbc-dataset /root/ytdb-base/jmh-ldbc/target/'
```

Download canonical curated parameters for both HEAD and BASE:
```bash
# Install canonical curated params into HEAD DB directory
ssh root@<IP> 'mkdir -p /root/ytdb/jmh-ldbc/target/ldbc-bench-db && \
  curl -sS -o /root/ytdb/jmh-ldbc/target/ldbc-bench-db/factor-tables.json "<FACTOR_TABLES_URL>" && \
  curl -sS -o /root/ytdb/jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json "<CURATED_PARAMS_URL>" && \
  echo "HEAD curated params installed"'

# Install canonical curated params into BASE DB directory
ssh root@<IP> 'mkdir -p /root/ytdb-base/jmh-ldbc/target/ldbc-bench-db && \
  curl -sS -o /root/ytdb-base/jmh-ldbc/target/ldbc-bench-db/factor-tables.json "<FACTOR_TABLES_URL>" && \
  curl -sS -o /root/ytdb-base/jmh-ldbc/target/ldbc-bench-db/curated-params-v3.json "<CURATED_PARAMS_URL>" && \
  echo "BASE curated params installed"'
```

**Critical**: Both HEAD and BASE must use the same canonical curated parameters downloaded from S3. Never let either version regenerate params independently — internal data structure changes can alter iteration order and produce incomparable parameter sets (see IC4 desync incident in `jmh-ldbc/README.md`).

### Step 6: Pre-load database

Run a throwaway fork on **each** version to trigger DB creation from CSV files (~21 min for SF 1) and load canonical curated parameters. Any benchmark name works here — the goal is just to trigger `@Setup(Level.Trial)` which creates the DB from CSV and loads the pre-downloaded curated parameters from the JSON cache files installed in Step 5.

**Important**: Use `-f 1` (not `-f 0`). With `-f 0` the benchmark runs in-process and JMH exits 0 even when all benchmarks fail — silent failures are hard to diagnose on a remote server.

**Important**: Run HEAD and BASE pre-loads **sequentially**, not in parallel. JMH uses a global lock file (`/tmp/jmh*.lock`) regardless of the working directory, so concurrent runs will fail with "Another JMH instance might be running". If a prior run left a stale lock, delete it with `rm -f /tmp/jmh*.lock` before starting.

```bash
# HEAD (run first)
ssh root@<IP> 'rm -f /tmp/jmh*.lock && cd /root/ytdb/jmh-ldbc && java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
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
  -Xms4096m -Xmx4096m \
  -jar target/youtrackdb-jmh-ldbc-*.jar \
  "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1'

# BASE (run after HEAD completes — JMH global lock prevents parallel runs)
ssh root@<IP> 'rm -f /tmp/jmh*.lock && cd /root/ytdb-base/jmh-ldbc && java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
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
  -Xms4096m -Xmx4096m \
  -jar target/youtrackdb-jmh-ldbc-*.jar \
  "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1'
```

### Step 7: Triage run — filter false positives

Before spending time on profiling, run each regressing benchmark **without async-profiler** on both HEAD and BASE to confirm the regression reproduces. Use the same tier-based JMH parameters from Step 1.

Use the wrapper script (without `-prof`):
```bash
ssh root@<IP> 'cat > /root/run-bench.sh << '\''SCRIPT'\''
#!/bin/bash
DIR=$1        # /root/ytdb or /root/ytdb-base
BENCH=$2      # benchmark regex
shift 2
ARGS="$@"     # JMH args (shift+$@ preserves spaces in multi-word args)

JVM_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.cs=ALL-UNNAMED --add-opens java.base/sun.security.x509=ALL-UNNAMED --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED -Xms4096m -Xmx4096m"

cd $DIR/jmh-ldbc && java $JVM_ARGS \
  -jar target/youtrackdb-jmh-ldbc-*.jar \
  "$BENCH" $ARGS
SCRIPT
chmod +x /root/run-bench.sh'
```

For each regression, run HEAD then BASE sequentially:
```bash
ssh root@<IP> '/root/run-bench.sh /root/ytdb "<benchmark-regex>" "<jmh-args>"'
ssh root@<IP> '/root/run-bench.sh /root/ytdb-base "<benchmark-regex>" "<jmh-args>"'
```

**Decision rule**: Compare HEAD vs BASE ops/s from the triage run. Classify as **measurement noise** and skip profiling if ANY of these hold:
- Delta is **<5%** or in the **opposite direction** (HEAD faster)
- **Confidence intervals overlap** — especially when one side has high error (>10%). Overlapping CIs mean the difference is not statistically significant. Check the CI from JMH output: `CI (99.9%): [low, high]`
- The **same benchmark in the other suite** (ST vs MT) shows improvement — a real regression in the code path would appear in both suites, not just one

Only proceed to Step 8 for benchmarks that reproduce a **≥5% regression** with **non-overlapping confidence intervals** in the triage run.

Record the triage results in the final report alongside profiling throughput for transparency.

### Step 8: Profile confirmed regressions

Run each **confirmed** regressing benchmark with async-profiler **collapsed-stack** output. Use the uber-jar directly to avoid shell escaping issues with Maven's `-Djmh.args`.

**Important**: Run benchmarks sequentially — never concurrently on the same server. HEAD and BASE can interleave (HEAD-ic4, BASE-ic4, HEAD-is3, BASE-is3...) or run all HEAD first then all BASE. Sequential within a version is easier to manage.

**SSH escaping**: The `-prof async:...;...;...` argument contains semicolons that are interpreted by the remote shell when passed through SSH, causing the profiler to silently not attach. To avoid this, create a wrapper script on the server:

```bash
ssh root@<IP> 'cat > /root/run-profile.sh << '\''SCRIPT'\''
#!/bin/bash
VERSION=$1    # head or base
DIR=$2        # /root/ytdb or /root/ytdb-base
BENCH=$3      # benchmark regex
shift 3
ARGS="$@"     # JMH args (shift+$@ preserves spaces in multi-word args)

JVM_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.cs=ALL-UNNAMED --add-opens java.base/sun.security.x509=ALL-UNNAMED --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED -Xms4096m -Xmx4096m"

mkdir -p /root/profiles/$VERSION

cd $DIR/jmh-ldbc && java $JVM_ARGS \
  -jar target/youtrackdb-jmh-ldbc-*.jar \
  "$BENCH" $ARGS \
  -prof "async:libPath=/tmp/async-profiler-3.0-linux-x64/lib/libasyncProfiler.so;output=collapsed;dir=/root/profiles/$VERSION;event=cpu"
SCRIPT
chmod +x /root/run-profile.sh'
```

For each regression, run:
```bash
ssh root@<IP> '/root/run-profile.sh <version> /root/ytdb<-base> "<benchmark-regex>" "<jmh-args>"'
```

**Benchmark regex format**: `LdbcSingleThread.*<benchmark_name>` or `LdbcMultiThread.*<benchmark_name>`

**Output files**: Collapsed stacks are written as `.csv` files under `/root/profiles/<version>/<fully-qualified-benchmark-name>-Throughput/collapsed-cpu.csv`

### Step 9: Analyze profiles

All analysis commands in this step run **on the remote server** via SSH (the profile `.csv` files are in `/root/profiles/` on the server). Either wrap each command in `ssh root@<IP> '...'` or open an interactive SSH session.

For each confirmed regression, perform five levels of analysis:

#### 9a. Filter non-measurement stacks

Async-profiler captures ALL JVM threads across the entire fork lifetime — including `@TearDown`, WAL vacuum, GC, and JVM service threads. These inflate HEAD/BASE sample counts unevenly and obscure the real benchmark-thread signal. **Always filter before computing leaf self-time.**

```bash
# Filter out non-measurement stacks
grep -vE 'tearDown|WALVacuum|G1Conc|G1ParScan|GCThread|GangWorker|VMThread|CompilerThread|ServiceThread|SafepointSynchronize|SafepointCleanup|MonitorDeflation' <file.csv> > <file-filtered.csv>
```

Compare total samples before and after filtering for both HEAD and BASE. Large deltas indicate:
- **TearDown overhead**: new code paths in storage shutdown (e.g., O(n) cache eviction) — a production concern but not a measurement regression
- **Background thread contention**: spinning/yielding on locks (e.g., WALVacuum on ScalableRWLock) — visible as `sched_yield`/`__schedule_[k]` samples; does not steal CPU on multi-core servers for single-threaded benchmarks

Use the filtered files for all subsequent analysis steps.

#### 9b. Leaf self-time comparison

Extract the method with the most self-time (CPU samples where this method is the leaf frame):

```bash
awk -F";" '{split($NF, a, " "); method=a[1]; samples=a[2]; if(method != "") leaf[method]+=samples} END {for(m in leaf) print leaf[m], m}' <file-filtered.csv> | sort -rn | head -30
```

Compare HEAD vs BASE top-30 leaf methods. Look for:
- New methods appearing only in HEAD
- Methods with >20% increase in sample count
- Methods disappearing from HEAD (may indicate JIT inlining changes)

#### 9c. Inclusive method time

Sum the sample counts across all stacks containing a given method (measures total time including children):

```bash
grep -E "(^|;)<method-name>(;| )" <file-filtered.csv> | awk '{sum += $NF} END {print sum}'
# Note: escape dots in method names for exact matching, e.g. EntityImpl\.hasProperty
```

Focus on methods from the current branch's changed code. To identify them, diff HEAD vs BASE:
```bash
git diff --name-only $(git merge-base HEAD origin/develop) HEAD -- '*.java' | head -30
```
Then grep the profiles for class/method names from those changed files. Common hot-path methods worth checking in any regression: `executeReadRecord`, `EntityImpl.deserializeProperties`, `LockFreeReadCache`, `ConcurrentHashMap`.

#### 9d. Children of hot methods

Extract what a specific method calls (its direct children in the profile):

```bash
grep -E "(^|;)<parent-method>;" <file-filtered.csv> | sed 's/^[^;]*<parent-method>;//' | awk -F"[ ;]" '{sum[$1]+=$NF} END {for (c in sum) print sum[c], c}' | sort -rn | head -15
```

Compare HEAD vs BASE children. Changes in child method distribution indicate:
- **New children**: overhead from added code paths
- **Shifted sample counts**: JIT inlining/de-inlining effects (method bytecode size changes)
- **Disappeared children**: methods being inlined into the parent

#### 9e. Bytecode size check (if JIT effects suspected)

```bash
# Compare method bytecode sizes by checking the last instruction offset
# The last offset in javap output is the actual bytecode size — counting lines is NOT a reliable proxy
# Search in core/target/classes (not jmh-ldbc/target/classes — the shade uber-jar doesn't unpack dependency classes there)
javap -c $(find /root/ytdb/core/target/classes -name "SQLBinaryCondition.class" | head -n 1) | awk '/evaluate/,/^$/' | tail -5
javap -c $(find /root/ytdb-base/core/target/classes -name "SQLBinaryCondition.class" | head -n 1) | awk '/evaluate/,/^$/' | tail -5
```

The **last instruction's offset** (e.g., `324:` in `javap` output) indicates the bytecode size. HotSpot default inlining threshold is ~325 bytecodes. Methods exceeding this won't be inlined at call sites, causing cascading de-inlining effects.

### Step 10: Report findings

For each regression, report:

1. **Triage throughput** (HEAD vs BASE ops/s from Step 7) and **profiling throughput** (from Step 8) — confirms whether the regression reproduces or is noise
2. **Root cause category**:
   - **Guard overhead**: new condition checks that always execute but rarely succeed
   - **Double work**: same computation performed redundantly (e.g., `getCollate` called in guard + fallthrough)
   - **JIT de-inlining**: method grew past inlining budget, causing cascade
   - **TearDown overhead**: new code in `@TearDown` (e.g., O(n) cache eviction) that inflates raw sample counts but does not affect throughput measurement — flag as a production concern, not a benchmark regression
   - **Measurement noise**: profiling shows no regression or opposite direction
3. **Key evidence**: specific method sample counts (HEAD vs BASE) and percentages
4. **Suggested fix** (if applicable)

### Step 11: Cleanup

```bash
# Remove local worktree (use the same $WORKTREE_DIR from Step 4)
git worktree remove --force "$WORKTREE_DIR"

# Destroy server
hcloud server delete "$SERVER_NAME"
hcloud ssh-key delete "$KEY_NAME"
```

Always destroy the server — CCX33 costs ~0.09 EUR/hour.

### Step 12: Self-improvement review

After completing the analysis, review the entire session for desynchronizations and improvements. This step is **mandatory** — do not skip it.

#### 12a. Skill desynchronization check

Compare what actually happened during execution against what this skill document describes. Flag any discrepancies:

- **File paths or formats that changed**: e.g., async-profiler output extension (`.csv` vs `.collapsed`), jar name, directory layout
- **Commands that failed or needed modification**: e.g., shell escaping issues, missing flags, incorrect regex patterns
- **New workarounds discovered**: e.g., `apt-get` lock on fresh servers, JMH lock conflicts between HEAD/BASE runs
- **Benchmark names or tiers that shifted**: e.g., a query moved tiers, new benchmarks added, class names changed
- **Analysis methods that didn't work or needed adaptation**: e.g., `awk` field separator assumptions, stack frame format changes

#### 12b. Routine improvement proposals

Reflect on the profiling session and identify improvements to the workflow:

- **Efficiency**: Were there unnecessary sequential steps that could be parallelized? Did any step take much longer than expected?
- **Analysis gaps**: Was any important signal missed that required backtracking? Would a different analysis order have been faster?
- **New patterns**: Did a new root cause category emerge that isn't listed in Step 10? Were new methods or code paths important that aren't in the Step 9c focus list?
- **Tooling**: Would a different async-profiler output format (e.g., `jfr`, `tree`) have been more useful? Would differential flamegraphs help?

**Important**: All proposed improvements must be **generally applicable** — they should benefit any future profiling session, not just the specific benchmarks or regressions analyzed in this session. Do not propose narrow fixes that only apply to one query, one benchmark tier, or one particular code path.

#### 12c. Propose updates

If any desynchronizations or improvements were found, **present them to the user** as a numbered list of proposed skill edits. Include:

1. What to change (quote the current text)
2. Why (what went wrong or what would improve)
3. The proposed new text

Apply changes only after user approval. If nothing needs updating, explicitly state: "Skill is in sync — no updates needed."

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Another JMH instance might be running` | A prior run left a stale lock file. Delete `/tmp/jmh-*.lock` in the benchmark directory, or add `-Djmh.ignoreLock=true` to JVM_ARGS |
| `No matching benchmarks` | List benchmarks with `-l` flag; use `LdbcSingleThread*` / `LdbcMultiThread*` prefix |
| async-profiler `perf_event_open failed` | Run `echo 1 > /proc/sys/kernel/perf_event_paranoid` |
| Collapsed output is `.csv` not `.collapsed` | This is normal for async-profiler 3.0 — the format is the same (semicolon-separated stacks, space, count) |
| Profiling throughput doesn't match benchmark | Expected — profiling adds ~5-15% overhead uniformly. Compare relative differences, not absolutes |
| Profiler produces no output files (empty `/root/profiles/`) | Semicolons in `-prof async:...;...;...` are eaten by the remote shell. Use the wrapper script approach documented in Step 8 |

## Notes

- **Profiling adds overhead** (~5-15%) but it's uniform across HEAD and BASE, so relative comparisons are valid.
- **One fork is sufficient** for profiling. We're looking at CPU distribution, not precise throughput numbers.
- **Collapsed stacks** are preferred over flamegraph HTML because they can be analyzed programmatically with `awk`/`grep`/`sort`.
- **JIT warmup matters** — always include at least 1 warmup iteration to avoid profiling the interpreter.
