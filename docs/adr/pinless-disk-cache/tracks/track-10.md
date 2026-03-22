# Track 10: Small disk cache eviction tests + CI job

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/3 complete)
- [ ] Track-level code review

## Base commit
`883c296bf1`

## Reviews completed
- [x] Technical

## Steps
- [x] Step 1: Create Maven profile `small-cache-it` in core pom.xml
  > **What was done:** Added `small-cache-it` profile to `core/pom.xml` with
  > failsafe configuration: `systemPropertyVariables` overrides
  > `diskCache.bufferSize=16` (16 MB), includes 4 IT classes (sbtree + edgebtree
  > BTreeTestIT, LocalPaginatedCollectionV2TestIT, FreeSpaceMapTestIT), skips
  > surefire unit tests. Explicit failsafe execution goals ensure tests run
  > during `verify` phase.
  >
  > **What was discovered:** `systemPropertyVariables` successfully overrides
  > argLine `-D` flags — confirmed by BTreeTestIT taking 31 min (vs ~2-5 min
  > with default 4096 MB cache), proving heavy eviction is active. Profile placed
  > only in core/pom.xml (not root) since only core module needs it.
  >
  > **Key files:** `core/pom.xml` (modified)

- [x] Step 2: Add `test-small-cache` CI job to maven-pipeline.yml
  > **What was done:** Added `test-small-cache-linux` job to
  > `maven-pipeline.yml`. Runs on self-hosted Linux x86 (cpx42), JDK 21,
  > temurin with 120-min timeout. Depends on `detect-changes`, not in
  > `ci-status` gate. Code review added `-Dmaven.test.failure.ignore=true`
  > and test diagnostics artifact upload.
  >
  > **Key files:** `.github/workflows/maven-pipeline.yml` (modified)

- [ ] Step 3: Run small-cache integration tests locally and fix any failures
  > Execute `./mvnw -pl core clean verify -P small-cache-it` locally.
  > Fix any test failures caused by the small cache (e.g., timeout issues,
  > assertion failures from changed timing behavior). If tests pass cleanly,
  > this step produces a minimal commit documenting the verification.
  > If fixes are needed, commit them with the verification results.
