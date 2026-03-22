# Track 8: Fix pre-existing test failures from rebase conflict resolution

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/3 complete)
- [ ] Track-level code review

## Base commit
`40023d852f`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Fix WOWCache flushExclusiveWriteCache NPE on deleted files (14 tests)
  > **What was done:** Added null guards at three `files.get()` call sites in
  > `WOWCache.flushExclusiveWriteCache()`: (1) the file size lookup at the
  > start of each page iteration, (2) the inner loop's pointer-null branch,
  > and (3) the lock-contention branch (found during code review — the third
  > site was missed in the original plan). When a file is deleted concurrently,
  > pages for that file are skipped instead of throwing NPE.
  >
  > **What was discovered:** A third unguarded `files.get()` call existed in
  > the lock-contention path (`tryAcquireSharedLock() == 0`), not identified
  > in the plan's analysis. Also, `DatabaseImportTest` crashes the surefire
  > fork when run in isolation (OOM-killed, not a code bug) — will verify it
  > passes as part of the full suite in Step 3.
  >
  > **Key files:** `WOWCache.java` (modified)

- [x] Step: Fix histogram engine test mocks — stub startAtomicOperation (10 tests)
  > **What was done:** Stubbed `startAtomicOperation()` to return a mock
  > `AtomicOperation` in `createMockStorage()` across all four histogram
  > test files. Changed `BTreeEngineHistogramBuildTest.statsFileExists` to
  > pass a mock `AtomicOperation` and stub `isFileExists()` on it (null
  > fallback removed in Track 1). Code review also caught that
  > `BTreeEngineHistogramBuildTest.createMockStorage()` lacked the stub —
  > aligned it for consistency.
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
