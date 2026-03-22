# Track 9: Fix DatabaseImportTest OOM regression

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (2/3 iterations)

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

- [x] Step: Verify DatabaseImportTest passes and run full core test suite
  > **What was done:** Ran `DatabaseImportTest` in isolation — passes in
  > 14 seconds (previously OOM'd or hung). Ran full core test suite with
  > `-Dyoutrackdb.test.env=ci`: 9535 tests, 0 failures, 0 errors.
  > Build time: ~65 minutes (includes Cucumber feature tests).
  >
  > **Key files:** No code changes — verification only.
