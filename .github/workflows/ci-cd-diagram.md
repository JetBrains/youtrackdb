# CI/CD Pipeline Diagram

```mermaid
flowchart TB
    subgraph triggers["Triggers"]
        push_develop["Push to develop"]
        pr_develop["PR to develop"]
        schedule["Daily Schedule<br/>(2:00 AM UTC)"]
        push_main["Push to main"]
        manual["Manual Dispatch"]
    end

    subgraph maven_pipeline["maven-pipeline.yml<br/>(develop branch)"]
        direction TB
        mp_test["Test Matrix<br/>JDK 21, 25<br/>temurin, corretto, oracle, zulu, microsoft<br/>ubuntu-x64, ubuntu-arm64, windows"]
        mp_deploy["Deploy Maven Artifacts"]
        mp_annotate["Annotate Versions"]
        mp_notify["Zulip Notifications"]
        mp_test --> mp_deploy
        mp_deploy --> mp_annotate
        mp_deploy --> mp_notify
    end

    subgraph integration_pipeline["maven-integration-tests-pipeline.yml"]
        direction TB
        it_check["Check for Changes<br/>(Skip if already tested)"]
        it_cache_restore["Restore Maven Cache<br/>(Hetzner S3)"]
        it_test_linux["Linux Test Matrix<br/>JDK 21, 25<br/>temurin, corretto, oracle, zulu<br/>x86, arm<br/><i>Pool Runners (always-on)</i>"]
        it_cache_save["Save Maven Cache<br/>(Hetzner S3)"]
        it_merge["Merge develop → main<br/>(fast-forward only)<br/><i>skipped on manual dispatch</i>"]
        it_notify["Zulip Notifications<br/><i>skipped on manual dispatch</i>"]
        it_check --> it_cache_restore
        it_cache_restore --> it_test_linux
        it_test_linux --> it_cache_save
        it_cache_save -->|"schedule only"| it_merge
        it_merge --> it_notify
    end

    subgraph pool_manager["runner-pool-manager.yml<br/>(Every 5 minutes)"]
        direction TB
        pm_scale["Auto-scale Pool<br/>Min: 1 server/arch<br/>Max: 2 servers/arch"]
        pm_status["Update Server Status<br/>(idle/busy)"]
        pm_cleanup["Cleanup Idle Servers<br/>(after 30 min)"]
        pm_scale --> pm_status
        pm_status --> pm_cleanup
    end

    subgraph main_pipeline["maven-main-deploy-pipeline.yml<br/>(main branch)"]
        direction TB
        main_deploy["Deploy Maven Artifacts"]
        main_annotate["Annotate Versions"]
        main_docker["Deploy Docker Images<br/>ubuntu-x64, ubuntu-arm64"]
        main_notify["Zulip Notifications"]
        main_deploy --> main_annotate
        main_deploy --> main_notify
        main_docker --> main_notify
    end

    subgraph artifacts["Deployed Artifacts"]
        direction TB
        maven_dev["Maven Central<br/>X.Y.Z-dev-SNAPSHOT<br/>X.Y.Z-TIMESTAMP-SHA-dev-SNAPSHOT"]
        maven_release["Maven Central<br/>X.Y.Z-SNAPSHOT<br/>X.Y.Z-TIMESTAMP-SHA-SNAPSHOT"]
        docker["Docker Hub<br/>youtrackdb/console<br/>youtrackdb/server"]
    end

%% Trigger connections
    push_develop --> maven_pipeline
    pr_develop --> maven_pipeline
    manual --> maven_pipeline
    manual --> integration_pipeline
    manual --> main_pipeline
    schedule --> integration_pipeline
    push_main --> main_pipeline
%% Pipeline to artifacts
    mp_deploy --> maven_dev
    main_deploy --> maven_release
    main_docker --> docker
%% Integration triggers main
    it_merge -->|" triggers "| push_main
%% Styling
  classDef trigger fill: #fff, stroke: #1565c0, stroke-width: 2px
  classDef pipeline fill: #fff3e0, stroke: #e65100
  classDef artifact fill: #fff, stroke: #2e7d32, stroke-width: 2px
  class push_develop trigger
  class pr_develop trigger
  class schedule trigger
  class push_main trigger
  class manual trigger
  class maven_pipeline pipeline
  class integration_pipeline pipeline
  class main_pipeline pipeline
  class maven_dev artifact
  class maven_release artifact
  class docker artifact
```

## Workflow Descriptions

### maven-pipeline.yml (Develop Branch)

This is the primary CI pipeline triggered on every push or pull request to the `develop` branch. It
runs the full test matrix across multiple JDK versions (21, 25), distributions (temurin, corretto,
oracle, zulu, microsoft), and platforms (ubuntu-x64, ubuntu-arm64, windows). On successful push (not
PRs), it deploys Maven artifacts with the `-dev-SNAPSHOT` suffix to Maven Central. Each deployment
is annotated with the exact version for traceability.

### maven-integration-tests-pipeline.yml (Nightly / Manual)

This pipeline runs on a daily schedule (2:00 AM UTC) to execute comprehensive integration tests. It
first checks if there are new changes since the last successful run to avoid redundant testing.

**Infrastructure**:

| Platform | Runners | JDK Distributions | Maven Goal | Tests |
|----------|---------|-------------------|------------|-------|
| Linux (Hetzner) | Pool runners (always-on) | temurin, corretto, oracle, zulu | `verify` | Unit + Integration |
| Windows (GitHub) | GitHub-hosted | temurin, corretto, oracle, zulu, microsoft | `package` | Unit only (disk limits) |

- **Runner Pool**: Managed by `runner-pool-manager.yml` (see below)
- **Maven cache**: Shared via Hetzner S3 Object Storage (synced before/after each job)

**Job Flow**:
1. `check-changes` - Skip if current commit was already tested successfully
2. `test-linux` - Restore Maven cache from S3, run full integration tests (16 jobs), save cache to S3
3. `test-windows` - Run unit tests only on GitHub-hosted runners (10 jobs)
4. `merge-to-main` - Fast-forward merge develop into main (schedule only)

Upon successful completion of all tests, it automatically merges `develop` into `main`
using fast-forward only, ensuring `main` always contains fully tested code.

**Manual Dispatch Mode**: When triggered manually via `workflow_dispatch`, the pipeline runs only the
tests without merging to `main` or sending Zulip notifications. This is useful for
validating changes before the nightly run or debugging test failures.

**Note**: Windows tests use `package` goal (unit tests only) instead of `verify` due to disk space
limitations on GitHub-hosted runners. Full integration tests run on Linux/Hetzner only.

### runner-pool-manager.yml (Runner Pool)

This workflow manages a pool of always-on Hetzner servers with GitHub self-hosted runners. It runs
every 5 minutes to maintain pool health and auto-scale based on demand.

**Pool Configuration**:

| Setting | Value | Description |
|---------|-------|-------------|
| MIN_SERVERS_PER_ARCH | 1 | Always keep at least 1 server per architecture (x86 + arm) |
| MAX_SERVERS_PER_ARCH | 2 | Maximum servers per architecture during high load |
| RUNNERS_PER_SERVER | 2 | Each server runs 2 GitHub runner instances |
| IDLE_TIMEOUT_MINUTES | 30 | Delete extra servers after being idle for this duration |

**Server Types**:
- x86: `cx53` (16 vCPUs, 32GB RAM, 160GB disk)
- arm: `cax41` (16 vCPUs, 32GB RAM, 160GB disk)

**How It Works**:
1. **Minimum Pool**: 2 servers always running (1 x86 + 1 arm), each with 2 runners = 4 parallel jobs
2. **Scale Up**: When pending jobs exceed available runners, add servers (up to max)
3. **Scale Down**: Extra servers deleted after 30 minutes of idle time
4. **Status Tracking**: Servers labeled as `idle` or `busy` based on runner activity

**Manual Actions** (via workflow_dispatch):
- `init` - Initialize pool with minimum servers
- `scale` - Run scaling logic (default)
- `status` - Show current pool status
- `cleanup` - Delete all pool servers

### maven-main-deploy-pipeline.yml (Main Branch)

Triggered by pushes to `main` (typically from the integration tests pipeline merge), this pipeline
handles production-ready deployments. It deploys Maven artifacts without the `-dev` prefix to Maven
Central and builds/publishes Docker images for both `console` and `server` components to Docker Hub.
This ensures that `main` branch artifacts are always the stable, fully tested versions.

## Workflow Summary

| Workflow                                 | Trigger                   | Purpose                                      | Infrastructure                                              | Artifacts                                                       |
|------------------------------------------|---------------------------|----------------------------------------------|-------------------------------------------------------------|-----------------------------------------------------------------|
| **maven-pipeline.yml**                   | Push/PR to `develop`      | Run tests, deploy dev artifacts              | GitHub-hosted runners                                       | `X.Y.Z-dev-SNAPSHOT`, `X.Y.Z-TIMESTAMP-SHA-dev-SNAPSHOT`        |
| **maven-integration-tests-pipeline.yml** | Daily schedule (2 AM UTC) | Run integration tests, merge to main         | Pool runners (Hetzner) + GitHub (Windows) + S3 cache        | N/A (triggers main pipeline)                                    |
| **maven-integration-tests-pipeline.yml** | Manual dispatch           | Run integration tests only (no merge/notify) | Pool runners (Hetzner) + GitHub (Windows) + S3 cache        | N/A                                                             |
| **runner-pool-manager.yml**              | Every 5 minutes           | Maintain runner pool, auto-scale             | Hetzner (2-4 servers, 4-8 runners)                          | N/A                                                             |
| **maven-main-deploy-pipeline.yml**       | Push to `main`            | Deploy release artifacts & Docker            | GitHub-hosted runners                                       | `X.Y.Z-SNAPSHOT`, `X.Y.Z-TIMESTAMP-SHA-SNAPSHOT`, Docker images |

## Version Format

- **Timestamp format**: `YYYYMMDD.HHMMSS` (UTC) - enables chronological sorting
- **Example versions**:
    - develop: `0.5.0-20260123.143052-abc1234-dev-SNAPSHOT`
    - main: `0.5.0-20260123.143052-abc1234-SNAPSHOT`
