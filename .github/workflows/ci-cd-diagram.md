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
        it_start["Start Hetzner Runners<br/>(x86 + arm)"]
        it_test_linux["Linux Test Matrix<br/>JDK 21, 25<br/>temurin, corretto, oracle, zulu, microsoft<br/>x86, arm<br/><i>Self-hosted Hetzner</i>"]
        it_test_windows["Windows Test Matrix<br/>JDK 21, 25<br/>temurin, corretto, oracle, zulu, microsoft<br/><i>GitHub-hosted</i>"]
        it_stop["Stop Hetzner Runners<br/>(cleanup)"]
        it_merge["Merge develop → main<br/>(fast-forward only)<br/><i>skipped on manual dispatch</i>"]
        it_notify["Zulip Notifications<br/><i>skipped on manual dispatch</i>"]
        it_check --> it_start
        it_check --> it_test_windows
        it_start --> it_test_linux
        it_test_linux --> it_stop
        it_test_linux -->|"schedule only"| it_merge
        it_test_windows -->|"schedule only"| it_merge
        it_stop -->|"schedule only"| it_merge
        it_merge --> it_notify
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

**Infrastructure**: Linux tests run on self-hosted Hetzner Cloud runners (x86 and arm64) that are
dynamically provisioned at the start of the pipeline and cleaned up after tests complete. Windows
tests run on GitHub-hosted runners. This approach provides cost-effective, dedicated compute for
Linux workloads while maintaining compatibility with Windows testing.

**Job Flow**:
1. `check-changes` - Skip if current commit was already tested successfully
2. `start-runners` - Provision Hetzner VMs and wait for runners to come online
3. `test-linux` / `test-windows` - Run test matrix in parallel on respective runners
4. `stop-runners` - Remove runners from GitHub and delete Hetzner servers (always runs)
5. `merge-to-main` - Fast-forward merge develop into main (schedule only)

Upon successful completion of all integration tests, it automatically merges `develop` into `main`
using fast-forward only, ensuring `main` always contains fully tested code.

**Manual Dispatch Mode**: When triggered manually via `workflow_dispatch`, the pipeline runs only the
integration tests without merging to `main` or sending Zulip notifications. This is useful for
validating changes before the nightly run or debugging test failures.

### maven-main-deploy-pipeline.yml (Main Branch)

Triggered by pushes to `main` (typically from the integration tests pipeline merge), this pipeline
handles production-ready deployments. It deploys Maven artifacts without the `-dev` prefix to Maven
Central and builds/publishes Docker images for both `console` and `server` components to Docker Hub.
This ensures that `main` branch artifacts are always the stable, fully tested versions.

## Workflow Summary

| Workflow                                 | Trigger                   | Purpose                                      | Infrastructure                          | Artifacts                                                       |
|------------------------------------------|---------------------------|----------------------------------------------|-----------------------------------------|-----------------------------------------------------------------|
| **maven-pipeline.yml**                   | Push/PR to `develop`      | Run tests, deploy dev artifacts              | GitHub-hosted runners                   | `X.Y.Z-dev-SNAPSHOT`, `X.Y.Z-TIMESTAMP-SHA-dev-SNAPSHOT`        |
| **maven-integration-tests-pipeline.yml** | Daily schedule (2 AM UTC) | Run integration tests, merge to main         | Hetzner (Linux), GitHub-hosted (Windows)| N/A (triggers main pipeline)                                    |
| **maven-integration-tests-pipeline.yml** | Manual dispatch           | Run integration tests only (no merge/notify) | Hetzner (Linux), GitHub-hosted (Windows)| N/A                                                             |
| **maven-main-deploy-pipeline.yml**       | Push to `main`            | Deploy release artifacts & Docker            | GitHub-hosted runners                   | `X.Y.Z-SNAPSHOT`, `X.Y.Z-TIMESTAMP-SHA-SNAPSHOT`, Docker images |

## Version Format

- **Timestamp format**: `YYYYMMDD.HHMMSS` (UTC) - enables chronological sorting
- **Example versions**:
    - develop: `0.5.0-20260123.143052-abc1234-dev-SNAPSHOT`
    - main: `0.5.0-20260123.143052-abc1234-SNAPSHOT`
