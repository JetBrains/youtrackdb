# Track 3 Technical Review

## Findings

### Finding T1 [should-fix]
**Certificate**: EC-3 (storage.close API verification)
**Location**: Track 3 plan, durability test constraint
**Issue**: `storage.close(true)` is not the correct API. The established pattern uses `ytdb.internal.forceDatabaseClose(dbName)`. Both still flush WAL + dirty pages — neither simulates true mid-operation crash.
**Proposed fix**: Use `forceDatabaseClose` pattern from `SharedLinkBagBTreeTombstoneGCDurabilityTest`. Document crash fidelity limitation in Javadoc.

### Finding T2 [suggestion]
**Certificate**: EC-4, EC-8 (storage type consistency)
**Location**: Track 3 plan, concurrent test constraint: "can use in-memory storage"
**Issue**: Both `SharedLinkBagBTreeTombstoneGCStressTest` and `BTreeTombstoneGCTest` use DISK storage. In-memory diverges without clear benefit.
**Proposed fix**: Use DISK storage for consistency.

### Finding T3 [suggestion]
**Certificate**: EC-10 (reflection helpers)
**Location**: Track 3 concurrent stress test approach
**Issue**: Raw BTree level test requires duplicating reflection helpers from `BTreeTombstoneGCTest` (pinLwm, registerStubEngine). Graph API level avoids this but offers less control.
**Proposed fix**: Use raw BTree level for stress test (more precise GC triggering control), duplicate helpers. Use graph API level for durability test (more realistic).

### Finding T4 [should-fix]
**Certificate**: EC-9 (test suite placement)
**Location**: Track 3 plan: "placed in integration test suite if I/O-bound"
**Issue**: Edge GC equivalent tests run in regular unit test suite. Lightweight entry counts don't warrant integration test placement.
**Proposed fix**: Place both tests in regular unit test suite.

### Finding T5 [suggestion]
**Certificate**: EC-2, EC-7 (BTree exclusive lock serialization)
**Location**: Track 3 plan: "cross-thread GC interaction"
**Issue**: `BTree.put()` acquires exclusive lock — operations serialize. Tests verify contention safety, not true concurrent GC.
**Proposed fix**: Document this in test Javadoc, matching edge GC stress test pattern.
