# Track 10: Small disk cache eviction tests + CI job

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
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

- [x] Step 3: Parameterize BTree dataset sizes and verify small-cache tests
  > **What was done:** Added system properties to control BTree IT dataset sizes:
  > `youtrackdb.test.btree.keysCount` (sbtree, default 1M) and
  > `youtrackdb.test.btree.maxPower` (edgebtree, default 20). The small-cache-it
  > profile passes keysCount=10000 and maxPower=14. All 271 tests pass in 3:40.
  >
  > **What was discovered:** Original BTree tests with 1M keys + 16 MB cache
  > took 90+ minutes (edgebtree 58 min, sbtree 31 min). FreeSpaceMapTestIT (1s)
  > uses too few pages for eviction to trigger — acceptable since its value is
  > testing FreeSpaceMap correctness, not eviction. LocalPaginatedCollectionV2TestIT
  > (147s) is the main eviction-exercising test alongside the reduced BTree tests.
  >
  > **What changed from the plan:** Step 3 was originally "run and fix failures".
  > Instead it became "parameterize datasets to make tests viable for CI" —
  > a necessary adaptation since the full-size tests are too slow for PR pipeline.
  > Default behavior (without the profile) is unchanged.
  >
  > **Key files:** `BTreeTestIT.java` (sbtree, modified),
  > `BTreeTestIT.java` (edgebtree, modified), `core/pom.xml` (modified)
