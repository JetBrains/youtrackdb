# Track 10: Small disk cache eviction tests + CI job

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/3 complete)
- [ ] Track-level code review

## Base commit
`883c296bf1`

## Reviews completed
- [x] Technical

## Steps
- [ ] Step 1: Create Maven profile `small-cache-it` in root and core pom.xml
  > Create a `small-cache-it` Maven profile that configures the
  > `maven-failsafe-plugin` with `<systemPropertyVariables>` to set
  > `youtrackdb.storage.diskCache.bufferSize=16` (16 MB — forces heavy
  > eviction with ~2048 pages while keeping runtime manageable). The profile
  > activates integration tests for the core module only. Uses
  > `systemPropertyVariables` to override the buffer size without duplicating
  > the `argLine` `--add-opens` flags.
  >
  > Test classes to run: both `BTreeTestIT` variants (sbtree + edgebtree),
  > `LocalPaginatedCollectionV2TestIT`, `FreeSpaceMapTestIT`. Note:
  > `CollectionPositionMapV2Test` is excluded — it's a unit test with mocks
  > that doesn't exercise real storage (T1 finding).
  >
  > Verify locally: `./mvnw -pl core clean verify -P small-cache-it`

- [ ] Step 2: Add `test-small-cache` CI job to maven-pipeline.yml
  > Add a separate `test-small-cache-linux` job (not a matrix entry — T6
  > finding) to the PR/develop CI workflow. Runs on Linux x86, JDK 21,
  > temurin only. Command:
  > `./mvnw -pl core clean verify -P small-cache-it`
  > Set explicit `timeout-minutes` (e.g., 120) to bound runtime.
  > The job depends on `detect-changes` (skip if no build-relevant changes).
  > Do NOT add to `ci-status-gate` required checks — this is an advisory
  > job that should not block PRs.
  >
  > Verify: push branch and confirm the job appears in CI.

- [ ] Step 3: Run small-cache integration tests locally and fix any failures
  > Execute `./mvnw -pl core clean verify -P small-cache-it` locally.
  > Fix any test failures caused by the small cache (e.g., timeout issues,
  > assertion failures from changed timing behavior). If tests pass cleanly,
  > this step produces a minimal commit documenting the verification.
  > If fixes are needed, commit them with the verification results.
