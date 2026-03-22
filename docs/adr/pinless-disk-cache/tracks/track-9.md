# Track 9: Fix DatabaseImportTest OOM regression

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
_(to be set at Phase B start)_

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Bound PageFramePool maxPoolSize to disk cache capacity
  Derive `maxPoolSize` from `DISK_CACHE_SIZE / PAGE_SIZE` instead of
  `DIRECT_MEMORY_POOL_LIMIT` (which defaults to `Integer.MAX_VALUE`). In
  `ByteBufferPool.pageFramePool()`, compute the bound from the disk cache buffer
  size configuration and pass it as `maxPoolSize`. This ensures the pool trims
  excess frames during `release()`, preventing unbounded growth of the
  `allocatedFrames` tracking set and associated heap pressure. Update
  `PageFramePoolTest` to verify trimming behavior when pool exceeds capacity.

- [ ] Step: Verify DatabaseImportTest passes and run full core test suite
  Run `DatabaseImportTest` in isolation to confirm the OOM is resolved. Then run
  the full core test suite (`./mvnw -pl core clean test -Dyoutrackdb.test.env=ci`)
  to verify no regressions. This is a verification-only step with no code changes
  — the commit records the verification result in the step episode.
