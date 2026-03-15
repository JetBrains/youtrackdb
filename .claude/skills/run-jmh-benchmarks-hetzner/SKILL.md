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
- Expected runtime: ~90 minutes for 40 benchmarks (20 queries x 2 suites) with `-f 1 -wi 3 -w 5s -i 5 -r 10s`

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

### Step 3b: Download dataset from Storage Box (jmh-ldbc only)

Instead of relying on the LDBC dataset download during benchmark setup (which uses the SURF tape archive and can take 20+ minutes to stage), pre-download the dataset from the Hetzner Storage Box:

```bash
ssh root@<IP> 'apt-get install -y -qq sshpass > /dev/null 2>&1 && \
  mkdir -p /root/ytdb/<module>/target/ldbc-dataset/tmp && \
  sshpass -p "Ldbc#Bench2026" scp -o StrictHostKeyChecking=no \
    u561694@u561694.your-storagebox.de:social_network-sf0.1-CsvComposite-LongDateFormatter.tar.zst \
    /root/ytdb/<module>/target/ldbc-dataset/tmp/'
```

Then extract and rename:
```bash
ssh root@<IP> 'cd /root/ytdb/<module>/target/ldbc-dataset/tmp && \
  apt-get install -y -qq zstd > /dev/null 2>&1 && \
  tar --use-compress-program=unzstd -xf social_network-sf0.1-CsvComposite-LongDateFormatter.tar.zst && \
  mv social_network-sf0.1-CsvComposite-LongDateFormatter sf0.1'
```

Replace `<module>` with the benchmark module (e.g. `jmh-ldbc`).

**Fallback — Cloudflare R2**: If the Storage Box is unavailable, download from Cloudflare R2 instead (fast from Hetzner, ~1s):
```bash
ssh root@<IP> 'cd /root/ytdb/<module>/target/ldbc-dataset/tmp && \
  curl -sLO https://datasets.ldbcouncil.org/snb-interactive-v1/social_network-sf0.1-CsvComposite-LongDateFormatter.tar.zst && \
  tar --use-compress-program=unzstd -xf social_network-sf0.1-CsvComposite-LongDateFormatter.tar.zst && \
  mv social_network-sf0.1-CsvComposite-LongDateFormatter sf0.1'
```

**Avoid** the SURF repository at `repository.surfsara.nl` — it stores files on tape and can take 20+ minutes to stage before the download begins.

### Step 4: Compile

```bash
ssh root@<IP> 'cd /root/ytdb && chmod +x mvnw && \
  ./mvnw -pl <module> -am compile -DskipTests -Dspotless.check.skip=true -q'
```

Replace `<module>` with the target benchmark module (e.g. `jmh-ldbc`).

Wait for BUILD SUCCESS (typically ~60-90 seconds on CCX33).

### Step 4b: Pre-load LDBC dataset (jmh-ldbc only)

**Critical for jmh-ldbc**: The LDBC dataset is downloaded and loaded into the database inside JMH's `@Setup(Level.Trial)` method. This means the first fork's warmup iteration includes dataset download + DB creation time. For multi-threaded benchmarks, threads start executing queries on a partially-loaded database, producing wildly inaccurate results (e.g., 300+ ops/s when the real throughput is ~3 ops/s).

**Always pre-load the dataset** before running actual benchmarks:

```bash
ssh root@<IP> 'cd /root/ytdb && ./mvnw -pl <module> -am verify -P bench -DskipTests -Dspotless.check.skip=true \
  -Djmh.args="ic5_newGroups -f 0 -wi 0 -i 1 -r 1s -t 1" 2>&1 | tail -20'
```

This runs a single in-process iteration (`-f 0`) that triggers dataset download and DB creation. Subsequent forked runs will find the existing DB at `./target/ldbc-bench-db` and skip loading.

**If the dataset was pre-downloaded via Step 3b**: The pre-load step is still required — it creates the YouTrackDB database from the CSV files. However, the download phase will be skipped automatically because the dataset files already exist in `target/ldbc-dataset/`.

**When comparing two code versions (A/B testing)**: After running version A, delete the benchmark database before running version B to avoid stale cached data:

```bash
ssh root@<IP> 'rm -rf /root/ytdb/jmh-ldbc/target/ldbc-bench-db'
```

The dataset files (`target/ldbc-dataset/`) can be kept — only the DB needs to be recreated.

### Step 5: Run benchmarks

**IMPORTANT**: Never run multiple benchmarks concurrently on the same server. Always wait for one benchmark run to complete before starting the next.

Start the benchmark in a tmux session so it survives SSH disconnects.

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
| Wild/inconsistent ops/s in MT benchmarks | Dataset not pre-loaded. Run Step 4b first. The first fork loads the DB during warmup; MT threads see partially loaded data. |
| `apt-get` lock on fresh server | Wait 30s for `unattended-upgrades` to finish, then retry. |
| SURF tape staging takes 20+ minutes | Use Storage Box (Step 3b) or Cloudflare R2 URL instead. |

## Notes

- **Server type**: CCX33 provides 8 dedicated AMD EPYC vCPUs — dedicated (not shared) cores ensure consistent benchmark results. For heavier benchmarks, consider CCX43 (16 vCPUs) or CCX53 (32 vCPUs).
- **jmh-ldbc Threads.MAX**: The multi-threaded LDBC benchmark uses `@Threads(Threads.MAX)` — one thread per available processor. On CCX33 this means 8 threads.
- **jmh-ldbc dataset loading**: The LDBC dataset download and DB creation happens inside `LdbcBenchmarkState.@Setup(Level.Trial)`. This runs once per JMH fork. The first fork downloads and loads (several minutes); subsequent forks detect the existing DB and skip. Always pre-load with `-f 0` before real benchmarks (see Step 4b). The DB path is `./target/ldbc-bench-db`; the dataset cache is `./target/ldbc-dataset/`.
- **Never run benchmarks concurrently**: Multiple JMH processes on the same server will contend for CPU and produce unreliable numbers. Always run one at a time.
- **Ubuntu apt lock on fresh servers**: Newly provisioned Ubuntu 24.04 servers run `unattended-upgrades` on first boot. If `apt-get install` fails with "Could not get lock", wait 30 seconds and retry.
- **Memory file**: For LDBC benchmarks, update `ldbc-jmh-benchmarks.md` in the auto-memory directory with new results after each run.
- **Storage Box**: A Hetzner Storage Box at `u561694.your-storagebox.de` (username: `u561694`, password: `Ldbc#Bench2026`) caches the LDBC dataset archive (`social_network-sf0.1-CsvComposite-LongDateFormatter.tar.zst`). This avoids the slow SURF tape staging. Access via SCP using `sshpass`. The Cloudflare R2 mirror at `https://datasets.ldbcouncil.org/snb-interactive-v1/` is a fast alternative (~1s from Hetzner).
