---
name: run-jmh-benchmarks-hetzner
description: "Provision a Hetzner CCX33 server, deploy the project, run JMH benchmarks, collect results, and destroy the server. Use ONLY when the user explicitly asks to run JMH benchmarks on a Hetzner server. Do NOT trigger for general benchmark requests or local benchmark runs."
user-invocable: true
---

# Run JMH Benchmarks on Hetzner

Provision a dedicated Hetzner cloud server, deploy the current working tree, run JMH benchmarks from any module, download results, and tear down the server.

## Prerequisites

- `hcloud` CLI installed and authenticated (`hcloud version` to verify)
- SSH key pair at `~/.ssh/id_ed25519` (or `~/.ssh/id_rsa`)
- The benchmark module compiles locally

## Workflow

### Step 0: Determine benchmark module and parameters

Ask the user (or infer from context) which benchmark module to run. The project may contain multiple JMH benchmark modules. Common examples:

- `jmh-ldbc` — LDBC SNB read query benchmarks (default if user says "run benchmarks")
- Other modules with JMH dependencies — check for `jmh-core` dependency in `pom.xml`

Determine:
- **Module name** (`-pl <module>`)
- **JMH regex filter** (which benchmarks to include/exclude)
- **JMH parameters** (forks, warmup, measurement iterations)

Defaults (good for comparison runs):
- `-f 1 -wi 3 -w 5s -i 5 -r 10s`

For **jmh-ldbc** specifically:
- Exclude IC5: `-e ic5_newGroups` (pathological: ~80 min per fork in ST mode)
- Expected runtime: ~60 minutes for 38 benchmarks (19 queries x 2 suites, IC5 excluded)

### Step 1: Provision the server

```bash
# Upload local SSH public key
hcloud ssh-key create --name jmh-bench-key --public-key-from-file ~/.ssh/id_ed25519.pub

# Create CCX33: 8 dedicated AMD vCPUs, 32 GB RAM, Falkenstein DC
hcloud server create --name jmh-bench --type ccx33 --image ubuntu-24.04 --location fsn1 --ssh-key jmh-bench-key
```

Record the IPv4 address from the output. Wait ~15 seconds for the server to boot before attempting SSH.

If SSH fails with a host key conflict, remove the stale key:
```bash
ssh-keygen -f ~/.ssh/known_hosts -R <IP>
```

### Step 2: Install JDK 21

```bash
ssh -o StrictHostKeyChecking=no root@<IP> \
  'apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless git tmux > /dev/null 2>&1 && java -version'
```

### Step 3: Deploy the project

Rsync the **worktree root** (the directory containing `mvnw`, `pom.xml`, `core/`, etc.), excluding `.git`, `target`, and `.idea`:

```bash
rsync -az --exclude='.git' --exclude='target' --exclude='.idea' <worktree-root>/ root@<IP>:/root/ytdb/
```

**Important**: The working directory (e.g. `/workspace/ytdb/ldbc-jmh`) may be a git worktree — it contains the full project tree with `mvnw` at its root. Rsync this directory, NOT the parent `/workspace/ytdb/`.

Then initialize a git repo on the server (required by Spotless):
```bash
ssh root@<IP> 'git config --global --add safe.directory /root/ytdb && \
  git config --global user.email "bench@test" && \
  git config --global user.name "bench" && \
  cd /root/ytdb && git init && git add -A && git commit -m "baseline" --quiet'
```

### Step 4: Compile

```bash
ssh root@<IP> 'cd /root/ytdb && chmod +x mvnw && \
  ./mvnw -pl <module> -am compile -DskipTests -Dspotless.check.skip=true -q'
```

Replace `<module>` with the target benchmark module (e.g. `jmh-ldbc`).

Wait for BUILD SUCCESS (typically ~60-90 seconds on CCX33).

### Step 5: Run benchmarks

Start the benchmark in a tmux session so it survives SSH disconnects.

#### JVM flags

**CRITICAL**: Use production-equivalent JVM flags for benchmarks. Do **NOT** copy flags from
the `<argLine>` property in `core/pom.xml` or other test modules — those contain debug-only
flags that distort results (up to 60-70% throughput loss).

**Required flags** (heap + Java module opens):
```
-Xms4g -Xmx4g
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
--add-opens=java.base/java.net=ALL-UNNAMED
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED
--add-opens=java.base/sun.security.x509=ALL-UNNAMED
--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED
```

**Flags to NEVER use in benchmarks:**

| Flag | Why it must be excluded |
|------|------------------------|
| `-ea` | Enables assertions. `assertIfNotActive()` calls `ThreadLocal.get()` on every DB operation — measured at **5.77% of CPU**. Assertions are off in production. |
| `-Dyoutrackdb.memory.directMemory.trackMode=true` | Debug tracking of every direct memory alloc/dealloc. Adds overhead not present in production. |
| `-Dyoutrackdb.storage.diskCache.checksumMode=StoreAndThrow` | Computes CRC checksum on **every page read**. Significant overhead on the hot read path. Production default is different. |
| `-Dyoutrackdb.storage.diskCache.bufferSize=4096` | Overrides default disk cache buffer size. Unless intentionally testing a specific cache size, omit to use the production default. |
| `-Dyoutrackdb.security.createDefaultUsers=false` | Unnecessary for benchmarks. |
| `-Dyoutrackdb.security.userPasswordSaltIterations=10` | Only affects session open, not steady-state benchmarks. |
| `-Dindex.flushAfterCreate=false` | Test-only optimization. |
| `-Dstorage.makeFullCheckpoint*=false` | Test-only optimizations. |
| `-Dstorage.wal.syncOnPageFlush=false` | Test-only optimization. |
| `-Dstorage.configuration.syncOnUpdate=false` | Test-only optimization. |

The `jmh-ldbc` module's `exec:exec` plugin configuration (line 145 of `jmh-ldbc/pom.xml`)
already uses the correct production-equivalent flags. Use it as the reference.

#### Running benchmarks

**If the module has a `bench` Maven profile** (like `jmh-ldbc`):
```bash
ssh root@<IP> 'tmux new-session -d -s bench \
  "cd /root/ytdb && ./mvnw -pl <module> -am verify -P bench -DskipTests -Dspotless.check.skip=true \
  -Djmh.args=\"<jmh-args> -rf json -rff /root/results.json\" \
  2>&1 | tee /root/bench.log"'
```

**If the module produces an uber-jar**:
```bash
ssh root@<IP> 'tmux new-session -d -s bench \
  "cd /root/ytdb && java -jar <module>/target/benchmarks.jar \
  <jmh-args> -rf json -rff /root/results.json \
  2>&1 | tee /root/bench.log"'
```

**If running a benchmark class from test scope** (e.g., benchmarks in `core/src/test/`),
build the classpath and run `org.openjdk.jmh.Main` directly with the correct JVM flags:
```bash
# Build classpath
ssh root@<IP> 'cd /root/ytdb && ./mvnw -pl core -am test-compile -DskipTests -Dspotless.check.skip=true -q && \
  ./mvnw -pl core dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/tmp/cp.txt -Dspotless.check.skip=true -q'

# Run with production-equivalent flags (NO -ea, NO debug tracking)
ssh root@<IP> 'CP=$(cat /tmp/cp.txt):/root/ytdb/core/target/test-classes:/root/ytdb/core/target/classes && \
  java -Xms4g -Xmx4g \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
  --add-opens=java.base/sun.security.x509=ALL-UNNAMED \
  --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED \
  -cp "$CP" org.openjdk.jmh.Main "<BenchmarkClass>" \
  <jmh-args> -rf json -rff /root/results.json \
  2>&1 | tee /root/bench.log'
```

**JMH parameters explained:**
- `-f 1` — 1 fork (sufficient for comparison runs; use `-f 3` for publication-grade results)
- `-wi 3 -w 5s` — 3 warmup iterations, 5 seconds each
- `-i 5 -r 10s` — 5 measurement iterations, 10 seconds each
- `-e <pattern>` — exclude benchmarks matching regex
- `-rf json -rff /root/results.json` — save results as JSON

### Step 6: Monitor progress

Poll periodically (every 5-10 minutes):

```bash
# Count completed benchmarks
ssh root@<IP> 'grep "^Result" /root/bench.log 2>/dev/null | wc -l'

# Check current benchmark
ssh root@<IP> 'tail -5 /root/bench.log'

# Check if complete
ssh root@<IP> 'grep "^# Run complete\|BUILD" /root/bench.log'
```

### Step 7: Collect results

Once `# Run complete` appears in the log:

```bash
# Download JSON results
scp root@<IP>:/root/results.json /tmp/claude-code-results.json

# Show summary table
ssh root@<IP> 'grep "^Benchmark\|thrpt\|avgt" /root/bench.log | head -60'
```

Copy the JSON to the project directory with a descriptive name:
```bash
cp /tmp/claude-code-results.json <module>/<name>-results-ccx33.json
```

### Step 8: Destroy the server

Always clean up to avoid charges:

```bash
hcloud server delete jmh-bench
hcloud ssh-key delete jmh-bench-key
```

### Step 9: Compare results

If baseline data exists (e.g. in memory files or previous JSON), present a comparison table with:
- Benchmark name
- Baseline score
- New score
- Percentage change
- Assessment (regression / noise / improvement)

Changes within ~5-7% are typically measurement noise for multi-threaded benchmarks. Single-threaded benchmarks are more stable (~2-3% noise floor).

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `mvnw: No such file or directory` | You rsynced the wrong directory. Rsync the worktree root that contains `mvnw`. |
| SSH host key conflict | `ssh-keygen -f ~/.ssh/known_hosts -R <IP>` |
| `detected dubious ownership` | `git config --global --add safe.directory /root/ytdb` |
| JMH hangs or needs restart | `ssh root@<IP> 'rm -f /tmp/jmh.lock'` then re-run in tmux |
| Core test compilation fails | Add `-Dmaven.test.skip=true` to the compile command |
| Need real-time output | Use tmux + tee (already in the command above) |

## Notes

- **Server type**: CCX33 provides 8 dedicated AMD EPYC vCPUs — dedicated (not shared) cores ensure consistent benchmark results. For heavier benchmarks, consider CCX43 (16 vCPUs) or CCX53 (32 vCPUs).
- **jmh-ldbc IC5**: The `ic5_newGroups` benchmark is pathological (~80 min per fork in single-thread mode). Always exclude unless specifically testing IC5.
- **jmh-ldbc Threads.MAX**: The multi-threaded LDBC benchmark uses `@Threads(Threads.MAX)` — one thread per available processor. On CCX33 this means 8 threads.
- **Memory file**: For LDBC benchmarks, update `ldbc-jmh-benchmarks.md` in the auto-memory directory with new results after each run.
