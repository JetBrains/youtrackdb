# Track 9 Technical Review

## Review scope
Track 9: Fix DatabaseImportTest OOM regression

## Findings

### Finding T1 [blocker]
**Location**: PageFramePool initialization via ByteBufferPool.pageFramePool() (line 216)
**Issue**: PageFramePool is created with `maxPoolSize = poolSize`, where poolSize
comes from `DIRECT_MEMORY_POOL_LIMIT` which defaults to `Integer.MAX_VALUE`. This
means the pool never trims excess frames during `release()` — the condition
`poolSize.incrementAndGet() > maxPoolSize` is never true. The `allocatedFrames`
ConcurrentHashMap grows without bound, accumulating PageFrame objects (each holding
a StampedLock + Pointer reference) on the Java heap. With a 4 GB cache (524K
8 KB pages) and 4 GB heap, the combined overhead of PageFrame objects + CHM nodes
+ StampedLock instances pushes the JVM past its heap limit during workloads that
create and destroy multiple databases (like DatabaseImportTest, which creates two
separate YouTrackDB instances with full storage stacks).
**Proposed fix**: Derive `maxPoolSize` from the actual disk cache capacity rather
than `DIRECT_MEMORY_POOL_LIMIT`. A natural bound is `DISK_CACHE_SIZE / PAGE_SIZE`
(the max number of pages the cache can hold). This ensures excess frames are
deallocated when the pool exceeds cache capacity, preventing unbounded heap growth.

### Finding T2 [suggestion]
**Location**: Track 9 description in implementation-plan.md
**Issue**: Track 8 Step 3 episode concluded the OOM was "a pre-existing
infrastructure issue on this machine (4GB heap limit insufficient for the import
workload), not a code regression from the rebase." This is incorrect — the root
cause is the new PageFramePool's unbounded pool sizing, which does not exist on
develop (develop uses ByteBufferPool without PageFramePool). The test is trivial
(schema-only export/import of 3 classes) and should not require significant memory.
**Proposed fix**: No plan change needed — the track already targets this fix.
Just noting the corrected root cause analysis for episode accuracy.

## Summary
One blocker (T1) with a clear fix path. The track scope of ~1-2 steps is
appropriate: fix the pool sizing and verify the test passes. **PASS** after
addressing T1 during implementation.
