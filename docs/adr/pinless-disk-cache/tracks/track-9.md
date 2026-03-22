# Track 9: Fix DatabaseImportTest OOM regression

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/2 complete)
- [ ] Track-level code review

## Base commit
`d07c6a9540`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Bound PageFramePool maxPoolSize to disk cache capacity
  > **What was done:** Added `PAGE_FRAME_POOL_LIMIT` config option in
  > `GlobalConfiguration` (default -1 = auto-size). In
  > `ByteBufferPool.pageFramePool()`, derive maxPoolSize from
  > `2 × DISK_CACHE_SIZE / PAGE_SIZE` when auto-sizing. Also fixed an
  > infinite loop in `WOWCache.flushExclusiveWriteCache()` that was
  > exposed once the OOM was resolved: added progress tracking
  > (`flushedPagesAtCycleStart`) to both `continue flushCycle` sites,
  > breaking out when no pages are flushed (prevents deadlocking the
  > single `commitExecutor` thread). Added WARN-level logging for the
  > no-progress exit.
  >
  > **What was discovered:** The OOM was masking a second bug — an
  > infinite loop in `flushExclusiveWriteCache`. When pages exist in
  > `exclusiveWritePages` but not in `writeCachePages` and the file is
  > too small to extend, `continue flushCycle` resets the iterator
  > endlessly. This monopolizes the `commitExecutor()` single thread,
  > deadlocking `deleteFile()` which submits tasks to the same executor.
  > The test hung for 10+ minutes at 100% CPU in the flush thread
  > calling `Files.size()` in a tight loop.
  >
  > **What changed from the plan:** Step scope expanded to include the
  > flush deadlock fix and the `PAGE_FRAME_POOL_LIMIT` config option
  > (user requested configurability). Step 2 verification remains
  > unchanged.
  >
  > **Key files:** `GlobalConfiguration.java` (modified),
  > `ByteBufferPool.java` (modified), `WOWCache.java` (modified)

- [ ] Step: Verify DatabaseImportTest passes and run full core test suite
  Run `DatabaseImportTest` in isolation to confirm the OOM is resolved. Then run
  the full core test suite (`./mvnw -pl core clean test -Dyoutrackdb.test.env=ci`)
  to verify no regressions. This is a verification-only step with no code changes
  — the commit records the verification result in the step episode.
