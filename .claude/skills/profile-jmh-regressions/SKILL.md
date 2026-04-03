---
name: profile-jmh-regressions
description: "Profile JMH benchmark regressions using async-profiler on a Hetzner CCX33 server. Reads regressions from a PR benchmark comment, profiles both HEAD and BASE with collapsed-stack output, compares self-time and inclusive-time per method, and identifies root causes. Use when the user asks to profile regressions after a benchmark comparison run."
user-invocable: true
---

Profile benchmark regressions found in a PR's JMH comparison comment. Provisions a Hetzner CCX33 server, deploys both HEAD and BASE code, runs async-profiler on the regressing benchmarks, performs differential analysis of collapsed stacks, and reports root causes.

## Prerequisites

- `hcloud` CLI installed and authenticated
- SSH key pair at `~/.ssh/id_ed25519`
- A PR with a JMH benchmark comparison comment (posted by `ldbc-jmh-compare.yml` or manually)
- Environment variables: `HETZNER_S3_ACCESS_KEY`, `HETZNER_S3_SECRET_KEY`, `HETZNER_S3_ENDPOINT`

## Workflow

### Step 0: Identify regressions

Read the benchmark comparison comment from the PR on the current branch:

```bash
# Find the PR
gh pr list --head $(git rev-parse --abbrev-ref HEAD) --json number,url

# Read the latest benchmark comment
gh pr view <NUMBER> --json comments --jq '.comments[-1].body'
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

Each benchmark belongs to a tier with different profiling settings. Choose parameters based on the base ops/s from the PR comment:

| Tier | Base ops/s | Profiling args |
|------|-----------|----------------|
| IS-ultra-fast | >2,700 | `-f 1 -wi 1 -w 5s -i 3 -r 10s -t 1` (ST) |
| IS-noisy | 400–2,700 | `-f 1 -wi 1 -w 5s -i 3 -r 10s -t 1` (ST) |
| IC | 17–215 | `-f 1 -wi 1 -w 5s -i 3 -r 15s -t 1` (ST) |
| IC-slow | 1–21 | `-f 1 -wi 2 -w 10s -i 3 -r 30s -t 1` (ST) |
| IC-ultra-slow | <0.2 | `-f 1 -wi 2 -w 10s -i 3 -r 60s -t 1` (ST) |

For multi-thread regressions, use the same warmup/measurement but omit `-t 1` (uses `@Threads(Threads.MAX)` default).

### Step 2: Provision the server

Use the same naming convention as `run-jmh-benchmarks-hetzner`:

```bash
BRANCH=$(git rev-parse --abbrev-ref HEAD | tr '[:upper:]/' '[:lower:]-' | cut -c1-40)
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
  'apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless git tmux zstd > /dev/null 2>&1 && java -version'
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
rsync -az --exclude='.git' --exclude='target' --exclude='.idea' <worktree-root>/ root@<IP>:/root/ytdb/
ssh root@<IP> 'git config --global --add safe.directory /root/ytdb && \
  git config --global user.email "bench@test" && \
  git config --global user.name "bench" && \
  cd /root/ytdb && git init && git add -A && git commit -m "head" --quiet'
```

**BASE** (fork-point commit): Create a local worktree, rsync to a separate directory:
```bash
BASE_COMMIT=$(git merge-base HEAD origin/develop)
git worktree add /tmp/ytdb-base-$$ $BASE_COMMIT

rsync -az --exclude='.git' --exclude='target' --exclude='.idea' /tmp/ytdb-base-$$/ root@<IP>:/root/ytdb-base/
ssh root@<IP> 'git config --global --add safe.directory /root/ytdb-base && \
  cd /root/ytdb-base && git init && git add -A && git commit -m "base" --quiet'
```

### Step 5: Compile both versions and download LDBC data

Compile both in parallel (they use separate directories):
```bash
# HEAD
ssh root@<IP> 'cd /root/ytdb && chmod +x mvnw && \
  ./mvnw -pl jmh-ldbc -am package -DskipTests -Dspotless.check.skip=true -q'

# BASE
ssh root@<IP> 'cd /root/ytdb-base && chmod +x mvnw && \
  ./mvnw -pl jmh-ldbc -am package -DskipTests -Dspotless.check.skip=true -q'
```

Download pre-built LDBC database (see `run-jmh-benchmarks-hetzner` skill for S3 presigned URL generation):
```bash
# Generate presigned URL locally
S3_KEY="ldbc/ldbc-sf1-bench-db.tar.zst"
PRESIGNED_URL=$(python3 -c "
import boto3, os
s3 = boto3.client('s3',
    endpoint_url='https://nbg1.your-objectstorage.com',
    aws_access_key_id=os.environ['HETZNER_S3_ACCESS_KEY'],
    aws_secret_access_key=os.environ['HETZNER_S3_SECRET_KEY'])
url = s3.generate_presigned_url('get_object',
    Params={'Bucket': 'bench-cache', 'Key': '$S3_KEY'},
    ExpiresIn=7200)
print(url)
")

# Download and extract to HEAD
ssh root@<IP> "mkdir -p /root/ytdb/jmh-ldbc/target && \
  curl -sS -o /tmp/bench-db.tar.zst '$PRESIGNED_URL' && \
  cd /root/ytdb/jmh-ldbc/target && \
  zstd -d /tmp/bench-db.tar.zst -o /tmp/bench-db.tar && \
  tar xf /tmp/bench-db.tar && rm -f /tmp/bench-db.tar.zst /tmp/bench-db.tar"

# Clear curation caches
ssh root@<IP> 'rm -f /root/ytdb/jmh-ldbc/target/ldbc-bench-db/curated-params*.json \
  /root/ytdb/jmh-ldbc/target/ldbc-bench-db/factor-tables.json'

# Copy to BASE
ssh root@<IP> 'cp -r /root/ytdb/jmh-ldbc/target/ldbc-bench-db /root/ytdb-base/jmh-ldbc/target/'
```

### Step 6: Pre-load and curate parameters

Run a throwaway fork on **each** version to trigger DB open + parameter curation:

```bash
# HEAD
ssh root@<IP> 'cd /root/ytdb/jmh-ldbc && java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -Xms4096m -Xmx4096m \
  -jar target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar \
  "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1'

# BASE (use -Djmh.ignoreLock=true if HEAD is still running, but prefer sequential)
ssh root@<IP> 'cd /root/ytdb-base/jmh-ldbc && java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -Xms4096m -Xmx4096m -Djmh.ignoreLock=true \
  -jar target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar \
  "LdbcSingleThread.*ic5_newGroups" -f 1 -wi 0 -i 1 -r 1s -t 1'
```

### Step 7: Triage run — filter false positives

Before spending time on profiling, run each regressing benchmark **without async-profiler** on both HEAD and BASE to confirm the regression reproduces. Use the same tier-based JMH parameters from Step 1.

Use the wrapper script (without `-prof`):
```bash
ssh root@<IP> 'cat > /root/run-bench.sh << '\''SCRIPT'\''
#!/bin/bash
VERSION=$1    # head or base
DIR=$2        # /root/ytdb or /root/ytdb-base
BENCH=$3      # benchmark regex
ARGS=$4       # JMH args

JVM_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Xms4096m -Xmx4096m -Djmh.ignoreLock=true"

cd $DIR/jmh-ldbc && java $JVM_ARGS \
  -jar target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar \
  "$BENCH" $ARGS
SCRIPT
chmod +x /root/run-bench.sh'
```

For each regression, run HEAD then BASE sequentially:
```bash
ssh root@<IP> '/root/run-bench.sh head /root/ytdb "<benchmark-regex>" "<jmh-args>"'
ssh root@<IP> '/root/run-bench.sh base /root/ytdb-base "<benchmark-regex>" "<jmh-args>"'
```

**Decision rule**: Compare HEAD vs BASE ops/s from the triage run. If the delta is **<3%** or in the **opposite direction** (HEAD faster), classify as **measurement noise** and skip profiling. Only proceed to Step 8 for benchmarks that reproduce a **≥3% regression** in the triage run.

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
ARGS=$4       # JMH args

JVM_ARGS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Xms4096m -Xmx4096m -Djmh.ignoreLock=true"

mkdir -p /root/profiles/$VERSION

cd $DIR/jmh-ldbc && java $JVM_ARGS \
  -jar target/youtrackdb-jmh-ldbc-0.5.0-SNAPSHOT.jar \
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

For each confirmed regression, perform four levels of analysis:

#### 9a. Filter non-measurement stacks

Async-profiler captures ALL JVM threads across the entire fork lifetime — including `@TearDown`, WAL vacuum, GC, and JVM service threads. These inflate HEAD/BASE sample counts unevenly and obscure the real benchmark-thread signal. **Always filter before computing leaf self-time.**

```bash
# Filter out non-measurement stacks
grep -v 'tearDown\|WALVacuum\|G1Conc\|G1ParScan\|GCThread\|GangWorker\|VMThread\|CompilerThread\|ServiceThread' <file.csv> > <file-filtered.csv>
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

Count how many stacks contain a given method (measures total time including children):

```bash
grep -c "<method-name>" <file-filtered.csv>
```

Focus on methods from the changed code: `SQLBinaryCondition.evaluate`, `getCollate`, `tryInPlaceComparison`, `isPropertyEqual`, `comparePropertyTo`, `deserializeFieldForComparison`, `InPlaceCompar`, `EntityImpl.hasProperty`, `EntityImpl.deserializeProperties`, `checkPropertyNameIfValid`, `getFieldSizeAndType`, `executeReadRecord`, `ConcurrentLongIntHashMap`, `ConcurrentHashMap`, `LockFreeReadCache`.

#### 9d. Children of hot methods

Extract what a specific method calls (its direct children in the profile):

```bash
grep "<parent-method>;" <file-filtered.csv> | sed 's/.*<parent-method>;//' | awk -F";" '{print $1}' | sort | uniq -c | sort -rn | head -15
```

Compare HEAD vs BASE children. Changes in child method distribution indicate:
- **New children**: overhead from added code paths
- **Shifted sample counts**: JIT inlining/de-inlining effects (method bytecode size changes)
- **Disappeared children**: methods being inlined into the parent

#### 9e. Bytecode size check (if JIT effects suspected)

```bash
# Compare evaluate method bytecode sizes
javap -c <HEAD-class-file> | awk '/<method-signature>/,/^$/' | wc -l
javap -c <BASE-class-file> | awk '/<method-signature>/,/^$/' | wc -l
```

HotSpot default inlining threshold is ~325 bytecodes. Methods exceeding this won't be inlined at call sites, causing cascading de-inlining effects.

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
# Remove local worktree
git worktree remove /tmp/ytdb-base-$$

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

#### 12c. Propose updates

If any desynchronizations or improvements were found, **present them to the user** as a numbered list of proposed skill edits. Include:

1. What to change (quote the current text)
2. Why (what went wrong or what would improve)
3. The proposed new text

Apply changes only after user approval. If nothing needs updating, explicitly state: "Skill is in sync — no updates needed."

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Another JMH instance might be running` | Add `-Djmh.ignoreLock=true` to JVM_ARGS |
| `No matching benchmarks` | List benchmarks with `-l` flag; use `LdbcSingleThread*` / `LdbcMultiThread*` prefix |
| async-profiler `perf_event_open failed` | Run `echo 1 > /proc/sys/kernel/perf_event_paranoid` |
| Collapsed output is `.csv` not `.collapsed` | This is normal for async-profiler 3.0 — the format is the same (semicolon-separated stacks, space, count) |
| Profiling throughput doesn't match benchmark | Expected — profiling adds ~5-15% overhead uniformly. Compare relative differences, not absolutes |
| High variance in slow benchmarks (IC4, IC3) | These benchmarks are inherently noisy. If profiling shows <5% regression or opposite direction, classify as noise |
| Profiler produces no output files (empty `/root/profiles/`) | Semicolons in `-prof async:...;...;...` are eaten by the remote shell. Use the wrapper script approach documented in Step 8 |

## Notes

- **Profiling adds overhead** (~5-15%) but it's uniform across HEAD and BASE, so relative comparisons are valid.
- **One fork is sufficient** for profiling. We're looking at CPU distribution, not precise throughput numbers.
- **Collapsed stacks** are preferred over flamegraph HTML because they can be analyzed programmatically with `awk`/`grep`/`sort`.
- **JIT warmup matters** — always include at least 1 warmup iteration to avoid profiling the interpreter.
- **IC4 and IC3** are extremely noisy benchmarks (±10-15% error). Treat regressions in these with skepticism unless profiling clearly confirms.
