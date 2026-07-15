# Track 10 — Technical Review

## Iteration 1

### Finding T1 [blocker]
**Location**: Track 10 description — `CollectionPositionMapV2Test` listed as a test to run
**Issue**: `CollectionPositionMapV2Test` is a pure unit test using Mockito mocks for
`ReadCache`, `WriteCache`, and `AbstractStorage`. It never creates a real database,
never touches the disk cache, and never triggers eviction. Its name also doesn't match
failsafe's `*IT` pattern. Setting `diskCache.bufferSize=4` has zero effect.
**Proposed fix**: Remove from the test list. `LocalPaginatedCollectionV2TestIT` already
exercises `CollectionPositionMapV2` indirectly (paginated collection uses it internally).

### Finding T2 [should-fix]
**Location**: Track 10 description — "Depends on: Track 8"
**Issue**: Track 9 fixed the `PageFramePool` auto-sizing bug (`maxPoolSize` derived from
`DIRECT_MEMORY_POOL_LIMIT` = Integer.MAX_VALUE → unbounded ConcurrentHashMap growth).
Without Track 9's fix, running with `diskCache.bufferSize=4` would OOM. Track 9 is
already completed so no runtime issue, but dependency should be accurate.
**Proposed fix**: Change to "Depends on: Track 8, Track 9".

### Finding T3 [should-fix]
**Location**: Track 10 scope — test timing with 4 MB cache
**Issue**: With 4 MB cache (512 pages), BTree tests with 1M keys will experience constant
eviction and disk I/O. The sbtree `BTreeTestIT.testKeyPut()` alone inserts and verifies
1M keys. The edgebtree variant runs 210 parameterized invocations. Total CI runtime could
be many hours, possibly exceeding job timeouts.
**Proposed fix**: Mitigations: (1) use 16-32 MB buffer instead of 4 MB for acceptable
eviction pressure without extreme I/O, (2) run a subset of tests or limit parameterized
range, (3) set generous timeout, (4) set explicit CI job `timeout-minutes`.

### Finding T4 [should-fix]
**Location**: Track 10 description — "BTreeTestIT"
**Issue**: Two `BTreeTestIT` classes exist:
- `core/.../storage/index/sbtree/singlevalue/v3/BTreeTestIT.java` (14 tests, 1M keys each)
- `core/.../storage/index/edgebtree/btree/BTreeTestIT.java` (10 tests × 21 params = 210 invocations)
Both will be picked up by failsafe's `**/*IT.java` pattern.
**Proposed fix**: Explicitly acknowledge both. Consider running only the sbtree variant or
limiting the edgebtree parameterized range for the small-cache job.

### Finding T5 [suggestion]
**Location**: Maven profile design
**Issue**: `argLine` in `core/pom.xml` hardcodes `diskCache.bufferSize=4096`. Overriding
the entire `argLine` would require duplicating all `--add-opens` flags.
**Proposed fix**: Use `<systemPropertyVariables>` in the failsafe plugin configuration
within the profile to override the buffer size, avoiding argLine duplication.

### Finding T6 [suggestion]
**Location**: CI workflow integration
**Issue**: Adding as a matrix entry requires a new dimension or `include` entries. A
separate job (`test-small-cache-linux`) is simpler and avoids coupling with coverage-gate
and test-count-gate jobs.
**Proposed fix**: Implement as a separate CI job with explicit `timeout-minutes`.

## Decisions

- **T1**: Accept — remove `CollectionPositionMapV2Test` from test list
- **T2**: Accept — update dependency in step file context (plan file tracks are already committed)
- **T3**: Accept — use 16 MB buffer (still forces heavy eviction: ~2048 pages vs thousands
  of B-tree pages), add explicit CI timeout. This balances eviction coverage with runtime.
- **T4**: Accept — include both BTreeTestIT classes. The edgebtree parameterized range
  already starts at 1 key and scales — lower params finish quickly. Only the highest
  params (512K, 1M) are slow under eviction.
- **T5**: Accept — use `systemPropertyVariables` approach
- **T6**: Accept — separate CI job

## Summary: PASS (blocker resolved)
