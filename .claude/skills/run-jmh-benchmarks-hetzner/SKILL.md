---
name: run-jmh-benchmarks-hetzner
description: "Provision a Hetzner CCX33 server, deploy the project, run JMH benchmarks, collect results, and destroy the server. Use ONLY when the user explicitly asks to run JMH benchmarks on a Hetzner server. Do NOT trigger for general benchmark requests or local benchmark runs."
user-invocable: true
---

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
- Expected runtime: ~7-8 hours for a full validation run using class-level annotations (no `-Djmh.args` override — each tier has its own fork/warmup/measurement settings)

### Step 1: Provision the server

**Naming convention**: Use `jmh-bench-<branch>` for the server and `jmh-bench-key-<branch>` for the SSH key, where `<branch>` is the current git branch name (sanitized: lowercase, slashes replaced with dashes, truncated to keep total name under 63 chars). This avoids conflicts when multiple benchmark runs execute concurrently on different branches.

```bash
# Determine branch-based names
BRANCH=$(git rev-parse --abbrev-ref HEAD | tr '[:upper:]/' '[:lower:]-' | cut -c1-40)
SERVER_NAME="jmh-bench-${BRANCH}"
KEY_NAME="jmh-bench-key-${BRANCH}"

# Upload local SSH public key
hcloud ssh-key create --name "$KEY_NAME" --public-key-from-file ~/.ssh/id_ed25519.pub

# Create CCX33: 8 dedicated AMD vCPUs, 32 GB RAM, Falkenstein DC
hcloud server create --name "$SERVER_NAME" --type ccx33 --image ubuntu-24.04 --location fsn1 --ssh-key "$KEY_NAME"
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

### Step 3b: Download LDBC data from Hetzner S3 (jmh-ldbc only — MANDATORY)

The LDBC SF 1 data must be available before running benchmarks. Download from Hetzner Object Storage (S3 bucket `bench-cache`).

**Available S3 artifacts:**
| Key | Size | Description |
|-----|------|-------------|
| `ldbc/ldbc-sf1-bench-db.tar.zst` | ~1.3 GB | Pre-built YouTrackDB database (SF 1) — **default** |
| `ldbc/ldbc-sf1-composite-merged-fk.tar.zst` | ~195 MB | Raw CSV dataset (SF 1) — use when user explicitly asks (e.g. testing storage format changes) |
| `ldbc/ldbc-sf0.1-composite-merged-fk.tar.zst` | ~19 MB | Raw CSV dataset (SF 0.1) — for quick smoke tests only |

**Default: Use the pre-built database.** This skips the ~21 min CSV loading step. Only fall back to the CSV dataset when the user explicitly asks — e.g. when testing storage format changes, serializer changes, or any code that affects how data is written to disk.

**Step 1**: Generate a presigned HTTPS URL locally (boto3 required on the local machine):
```bash
# For pre-built DB (default):
S3_KEY="ldbc/ldbc-sf1-bench-db.tar.zst"

# For CSV dataset (only when user explicitly asks):
# S3_KEY="ldbc/ldbc-sf1-composite-merged-fk.tar.zst"

python3 -c "
import boto3, os
s3 = boto3.client('s3',
    endpoint_url='https://nbg1.your-objectstorage.com',
    aws_access_key_id=os.environ['HETZNER_S3_ACCESS_KEY'],
    aws_secret_access_key=os.environ['HETZNER_S3_SECRET_KEY'])
url = s3.generate_presigned_url('get_object',
    Params={'Bucket': 'bench-cache', 'Key': '$S3_KEY'},
    ExpiresIn=7200)
print(url)
"
```

**Step 2a — Pre-built DB (default)**: Download and extract:
```bash
ssh root@<IP> "apt-get install -y -qq zstd > /dev/null 2>&1 && \
  mkdir -p /root/ytdb/<module>/target && \
  curl -sS -o /tmp/bench-db.tar.zst '<PRESIGNED_URL>' && \
  cd /root/ytdb/<module>/target && \
  zstd -d /tmp/bench-db.tar.zst -o /tmp/bench-db.tar && \
  tar xf /tmp/bench-db.tar && \
  rm -f /tmp/bench-db.tar.zst /tmp/bench-db.tar && \
  echo 'DB ready' && du -sh ldbc-bench-db/"
```

The pre-built DB tar includes canonical curated parameters (`curated-params-v3.json` and `factor-tables.json`). **Do NOT delete them.** All runs must use the same canonical parameters to ensure comparable results — see the [jmh-ldbc README](jmh-ldbc/README.md#canonical-curated-parameters) for details.

If the pre-built DB is missing curated params (e.g., old tar), download them separately:
```bash
# Generate presigned URLs for canonical params (locally)
for KEY in curated-params-v3.json factor-tables.json; do
  python3 -c "
import boto3, os
s3 = boto3.client('s3',
    endpoint_url='https://nbg1.your-objectstorage.com',
    aws_access_key_id=os.environ['HETZNER_S3_ACCESS_KEY'],
    aws_secret_access_key=os.environ['HETZNER_S3_SECRET_KEY'])
url = s3.generate_presigned_url('get_object',
    Params={'Bucket': 'bench-cache', 'Key': 'ldbc/$KEY'},
    ExpiresIn=7200)
print(url)
"
done

# Download to the DB directory on the server
ssh root@<IP> "curl -sS -o /root/ytdb/<module>/target/ldbc-bench-db/curated-params-v3.json '<CURATED_PARAMS_URL>' && \
  curl -sS -o /root/ytdb/<module>/target/ldbc-bench-db/factor-tables.json '<FACTOR_TABLES_URL>'"
```

**Step 2b — CSV dataset (only when user asks)**: Download and extract:
```bash
ssh root@<IP> "apt-get install -y -qq zstd > /dev/null 2>&1 && \
  mkdir -p /root/ytdb/<module>/target/ldbc-dataset/sf1 && \
  curl -sS -o /tmp/dataset.tar.zst '<PRESIGNED_URL>' && \
  cd /root/ytdb/<module>/target/ldbc-dataset/sf1 && \
  zstd -d /tmp/dataset.tar.zst -o /tmp/dataset.tar && \
  tar xf /tmp/dataset.tar && \
  rm -f /tmp/dataset.tar.zst /tmp/dataset.tar && \
  echo 'Dataset ready' && ls static/ dynamic/"
```

The CSV dataset uses LDBC datagen v1.0.0 CsvCompositeMergeForeign format. The DB will be created from CSVs during the pre-load step (Step 4b), which takes ~21 minutes for SF 1.

Replace `<module>` with the benchmark module (e.g. `jmh-ldbc`) and `<PRESIGNED_URL>` with the URL from Step 1.

**Important**: Use presigned HTTPS URLs + curl for S3 downloads. Do NOT use boto3 or awscli on the server — pip install is slow and boto3 downloads can hang over HTTP (port 80 is often blocked). The presigned URL approach is faster and more reliable.

**Do not use** the SURF repository at `repository.surfsara.nl` — it provides CsvComposite format (v0.3.5), which is incompatible with the benchmark loaders.

### Step 4: Compile

```bash
ssh root@<IP> 'cd /root/ytdb && chmod +x mvnw && \
  ./mvnw -pl <module> -am compile -DskipTests -Dspotless.check.skip=true -q'
```

Replace `<module>` with the target benchmark module (e.g. `jmh-ldbc`).

Wait for BUILD SUCCESS (typically ~60-90 seconds on CCX33).

### Step 4b: Pre-load and curate parameters (jmh-ldbc only)

**Critical for jmh-ldbc**: The first JMH fork triggers DB loading (if using CSV) and parameter curation inside `@Setup(Level.Trial)`. For multi-threaded benchmarks, threads start executing queries on a partially-loaded database, producing wildly inaccurate results.

**Always run a pre-load fork** before the real benchmarks to ensure the DB is ready and curated parameters are cached:

```bash
ssh root@<IP> 'cd /root/ytdb && ./mvnw -pl <module> -am verify -P bench -DskipTests -Dspotless.check.skip=true \
  -Djmh.args="ic5_newGroups -f 1 -wi 0 -i 1 -r 1s -t 1" 2>&1 | tail -20'
```

This runs a single fork (`-f 1`) that triggers:
1. DB creation from CSV files (if no pre-built DB exists) — ~21 min for SF 1
2. Factor table computation and caching to `factor-tables.json`
3. Parameter curation (including IC4 oldPost-count difficulty sampling) and caching to `curated-params-v<N>.json` (versioned filename — bumped when curation logic changes)

Subsequent forked runs will find the existing DB and load curated parameters from the JSON cache — zero SQL queries needed.

**Important**: Use `-f 1` (not `-f 0`). With `-f 0` the benchmark runs in-process and the database may not persist to disk.

**When comparing two code versions (A/B testing)**: Deploy both versions to separate directories (e.g., `/root/ytdb` and `/root/ytdb-base`). Copy the pre-built DB (including canonical curated params) to both. **Both versions must use identical curated parameters** — never let either version regenerate params independently, as internal data structure changes can alter iteration order and produce incomparable parameter sets (see IC4 desync incident in jmh-ldbc README).

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

Always clean up to avoid charges. Use the same branch-based names from Step 1:

```bash
hcloud server delete "$SERVER_NAME"
hcloud ssh-key delete "$KEY_NAME"
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
| Dataset not found error during setup | Dataset must be pre-downloaded via Step 3b (Hetzner S3). The benchmark no longer auto-downloads from SURF. |

## Notes

- **Server type**: CCX33 provides 8 dedicated AMD EPYC vCPUs — dedicated (not shared) cores ensure consistent benchmark results. For heavier benchmarks, consider CCX43 (16 vCPUs) or CCX53 (32 vCPUs).
- **jmh-ldbc Threads.MAX**: The multi-threaded LDBC benchmark uses `@Threads(Threads.MAX)` — one thread per available processor. On CCX33 this means 8 threads.
- **jmh-ldbc dataset loading**: By default, use the pre-built SF 1 database from S3 (see Step 3b). Fall back to CSV dataset only when the user explicitly asks (e.g. for storage format testing). Always pre-load with `-f 1` before real benchmarks (see Step 4b). The DB path is `./target/ldbc-bench-db`.
- **jmh-ldbc curated params**: Canonical curated parameters are stored in S3 (`ldbc/curated-params-v3.json`, `ldbc/factor-tables.json`) and included in the pre-built DB tar. **Never delete or regenerate them** — different code versions produce different iteration orders, which cause the stride-based parameter sampling to select different query parameter sets, making results incomparable. See `jmh-ldbc/README.md` for the regeneration procedure (only needed when curation algorithm changes).
- **Never run benchmarks concurrently**: Multiple JMH processes on the same server will contend for CPU and produce unreliable numbers. Always run one at a time.
- **Ubuntu apt lock on fresh servers**: Newly provisioned Ubuntu 24.04 servers run `unattended-upgrades` on first boot. If `apt-get install` fails with "Could not get lock", wait 30 seconds and retry.
- **Memory file**: For LDBC benchmarks, update `ldbc-jmh-benchmarks.md` in the auto-memory directory with new results after each run.
- **S3 artifacts**: S3 bucket `bench-cache` contains pre-built DB (`ldbc-sf1-bench-db.tar.zst`, ~930 MB, includes canonical curated params), canonical curated params (`ldbc/curated-params-v3.json`, `ldbc/factor-tables.json`), SF 1 CSV (`ldbc-sf1-composite-merged-fk.tar.zst`, ~195 MB), and SF 0.1 CSV (`ldbc-sf0.1-composite-merged-fk.tar.zst`, ~19 MB). Credentials are in env vars `HETZNER_S3_ACCESS_KEY` / `HETZNER_S3_SECRET_KEY` / `HETZNER_S3_ENDPOINT` — never hardcode them.
- **Do not use SURF**: The SURF Data Repository (`repository.surfsara.nl`) provides the CsvComposite format (v0.3.5), which is **incompatible** with the benchmark loaders that expect CsvCompositeMergeForeign column layouts.
