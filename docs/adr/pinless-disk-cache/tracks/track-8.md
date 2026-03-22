# Track 8: Fix pre-existing test failures from rebase conflict resolution

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/3 complete)
- [ ] Track-level code review

## Base commit
(to be recorded at Phase B start)

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Fix WOWCache flushExclusiveWriteCache NPE on deleted files (14 tests)
  > Add a null guard in `WOWCache.flushExclusiveWriteCache()` at line 3331
  > where `files.get(externalFileId(pageKeyToFlush.fileId))` can return null
  > when a file has been deleted (e.g., during backup restore). When the file
  > is null, skip pages for that file — they will be cleaned up by
  > `doRemoveCachePages` or the next flush cycle. This fixes 14 tests:
  > StorageBackupTest (11), IndexHistogramDurabilityTest (2),
  > DatabaseImportTest (1). The NPE sets `AbstractStorage.error`, causing all
  > subsequent operations to fail with "Internal error happened in storage."
  >
  > **Key files:** `WOWCache.java` (modified), `StorageBackupTest` (verify)
  >
  > **Note:** This same bug exists on develop but doesn't trigger there
  > because the RRWL→StampedLock timing change makes the periodic flush
  > consistently race with file deletion during restore on this branch.

- [ ] Step: Fix histogram engine test mocks — stub startAtomicOperation (10 tests)
  > The rebase changed `keyStreamSupplier` from `Supplier<Stream<Object>>`
  > to `Function<AtomicOperation, Stream<Object>>`, adding a call to
  > `storage.getAtomicOperationsManager().startAtomicOperation()` inside
  > `doRebalance()`. All histogram unit test fixtures use
  > `mock(AtomicOperationsManager.class)` without stubbing this method, so
  > it returns null, causing NPE in `doRebalance`.
  >
  > Fix: In histogram test fixtures (`IncrementalMaintenanceTest`,
  > `RebalanceTriggerTest`, `ThreeTierTransitionTest`), stub
  > `startAtomicOperation()` to return a mock `AtomicOperation`. Also fix
  > `BTreeEngineHistogramBuildTest.statsFileExists` test to pass a mock
  > `AtomicOperation` instead of null (the null fallback was removed in
  > Track 1).
  >
  > **Key files:** `IncrementalMaintenanceTest.java` (modified),
  > `RebalanceTriggerTest.java` (modified), `ThreeTierTransitionTest.java`
  > (modified), `BTreeEngineHistogramBuildTest.java` (modified)

- [ ] Step: Full core test suite verification pass
  > Run `./mvnw -pl core clean test -Dyoutrackdb.test.env=ci` to verify all
  > 24 previously failing tests now pass and no regressions were introduced.
  > This step produces no code changes — it is a verification-only step that
  > confirms the fixes from Steps 1-2 are complete.
  >
  > If any additional failures are discovered, fix them in this step.
