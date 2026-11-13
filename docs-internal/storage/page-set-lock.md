# Page-set lock for commit apply

The page-set protocol applies one commit by locking every changed cache page before changing any
page. A fallback commit instead applies pages one at a time under a component epoch.
An epoch is a pair of sequence values that detects overlapping fallback apply phases.
`AtomicOperationBinaryTracking.commitChanges()` selects the protocol.

A frame (`PageFrame`) holds a page's native-memory contents and lock state.
A cache entry (`CacheEntry`) identifies the cached page and points to its frame.
A pin keeps an entry acquired so eviction cannot remove it.
A reservation (`ReadCache.WriteReservation`) claims capacity for all commit pins at once.

## Commit steps and selection

1. **Collect and select.** Collect pages with real changes during write-ahead log (WAL) validation.
   The WAL records changes before cache apply. Allocation gaps do not count as changed pages.
   Select fallback before loading any commit page. Otherwise reserve the whole set.
2. **Pin all pages.** Sort file identifiers, then page indexes within each file.
   Pin pages in that order. Finish loads, physical file extension, and possible eviction before locking.
3. **Lock all pages.** Acquire every frame's exclusive lock in the same increasing order.
   Do not change any page before the last lock is acquired.
4. **Apply and publish.** Prepare each page's WAL dependency and dirty notification before restoring contents.
   Publish the page to the write cache while its frame lock is held, then unlock it.
   Release pins only after every frame lock has been released. Return reservation capacity during cleanup.

For commits that mutate the shared cache, selection uses this precedence:

| Order | Condition | Outcome |
|---|---|---|
| 1 | Test force override is set | `FORCED` |
| 2 | At least one file is created or deleted | `FILE_LIFECYCLE` |
| 3 | Write-pin budget is zero, including the memory engine | `NO_PIN_BUDGET` |
| 4 | Changed-page count exceeds the smaller of the configured page limit and positive pin budget | `OVER_LIMIT` |
| 5 | The cache refuses the whole-set reservation | `RESERVATION_DENIED` |
| 6 | Reservation succeeds | `PAGE_SET` |

A commit with no shared-cache mutation counts `ZERO_CHANGE`, regardless of its otherwise selected outcome.
File lifecycle changes count as shared-cache mutations even without changed pages.
A count equal to the effective limit is eligible for the page-set protocol.
Concurrent reservations share one budget. A reservation can fail even when the set fits that budget alone.

A failure after reservation does not switch to fallback.
The failure propagates through reservation cleanup.
Cleanup attempts all remaining unlocks before releasing any pins.
A failed publication or pin release can retain its pin and reservation capacity for safety.
A failed apply or cleanup records no commit outcome.

Fallback enters the epoch of every locked logical component before shared-cache mutation.
A logical component is a storage structure identified by its lock name within one storage.
`AtomicOperationsManager` shares epochs by that name.
Fallback exits the entered epochs in `finally`, including on apply failure.
Standalone low-level operations can use an explicit standalone epoch.

Page-set commits do not enter these epochs. Rolled-back operations do not enter commit apply.

## Lock window

The frame-lock window starts at the first exclusive frame lock and ends at the last frame unlock.
`LockFreeReadCache.Reservation.pin()` requires strictly increasing canonical file/page coordinates.
It rejects duplicates and requires one write cache for the reservation.
Apply reuses the already pinned entries instead of loading pages again.

Existing component exclusive locks surround commit apply.
The operation already participates in the atomic-operation freezer, which controls admission of operations.
No page load, eviction, or freezer admission may occur inside the frame-lock window.
Pool return can take a frame lock, so cleanup must unlock all frames before releasing any pin.

Publication is not lock-free.
`LockFreeReadCache.publish()` uses the read-cache map's `compute` synchronization.
`WOWCache.store()` then takes the `filesLock` read lock and the page-key exclusive lock.
Preparation calls `WOWCache.updateDirtyPagesTable()` before content mutation.
It records dirty pages and marks incremental-backup candidates before transaction completion.
`ChangedPageTracker.mark()` takes neither its save-order lock nor its generation-state lock.

File close, `clearFile`, and file deletion must not overlap an optimistic attempt on the same file.
These lifecycle operations can remove the map entry before freezing it.
The page-set protocol does not replace that lifecycle exclusion.

## Reader final-check rule

An optimistic read reads shared page contents without holding a component shared lock throughout the read.
It accepts the result only after validation.
A stamp is a frame lock-state token that detects an exclusive writer or frame reuse.
A valid stamp alone does not prove that the cache entry still owns the frame.

`OptimisticReadScope` records the cache entry, frame, and nonzero stamp for every accessed page.
At the outer attempt boundary, `StorageComponent.executeOptimisticStorageRead()` follows this rule:

1. Begin the attempt in the component's epoch domain.
   Capture epoch exit before epoch enter. Nested attempts retain the outer capture and recorded pages.
   A nested attempt in a different domain fails.
2. Perform all payload reads.
3. Run `validateOrThrow()` after those reads. Reject an already failed attempt first.
4. For each recorded page, check entry liveness and ownership of the recorded frame before checking its stamp.
5. After the page loop, require equal captured enter and exit values.
   Also require that the live enter value still equals the captured enter value.

`validateLastOrThrow()` checks only the most recently recorded entry and stamp.
It is an early traversal check before following a page pointer, not a final validation.
It does not check the epoch.

Pinned reads inside an optimistic attempt also participate in its final validation.
`LockFreeReadCache.loadForRead(..., scope)` uses a cache-installed entry and a non-extending read load.
It records the entry, frame, and stamp with `recordPinned()`.
A pin prevents eviction, but does not replace stamp validation against a writer.

Load failures mark the enclosing attempt as failed. Cleanup releases pins not handed to the caller.
A failed nested attempt cannot run a locked fallback inside the enclosing attempt.
The outer wrapper ends attempt state before repeating the read under the component shared lock.
Non-repeatable load failures propagate instead of being hidden by a retry.

Zero-copy results retain a frame reference instead of copying record bytes.
`RawPageBuffer` requires identity validation before stamp validation after result extraction.
Lazy entity loading through `DatabaseSessionEmbedded` and `EntityImpl.fillFromPage` is the stamp-only exception.
The read's final identity check has already passed on that path.
This exception does not remove identity checks from general optimistic reads.

The epoch rejects a read that overlaps fallback apply in its component domain.
Fallback can otherwise expose mixed pre-commit and post-commit pages whose individual stamps remain valid.
For page-set commits, all frames lock before the first change.
Frame stamps, cached-entry identity, and the pinned-load rules protect the reader against those commits.
The epoch is not their cross-page guarantee.

## Startup settings

`GlobalConfiguration` owns both defaults. `EngineLocalPaginated.startup()` reads and validates both values.
Both settings apply to the whole Java process and are read once at engine startup.
A per-database-factory `YouTrackDBConfig` value has no effect.
Set a system property or the corresponding environment variable before startup.

| Configuration constant | System property | Default | Valid range |
|---|---|---|---|
| `DISK_CACHE_COMMIT_PAGE_LIMIT` | `youtrackdb.storage.diskCache.commitPageLimit` | 1024 pages | Integer 1 through 2147483647 |
| `DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT` | `youtrackdb.storage.diskCache.commitPinBudgetPercent` | 1.0 percent | Finite decimal 0 through 15 inclusive |

The environment variables are `YOUTRACKDB_DISK_CACHE_COMMIT_PAGE_LIMIT` and
`YOUTRACKDB_DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT`.
Budget pages equal `floor(readCachePages * percent / 100)`.
The shared read cache owns this budget, not an individual storage or commit.
The effective per-commit limit is the smaller of the page limit and positive budget.
Zero budget disables page-set apply.
A positive percentage that rounds down to zero also disables it and logs a startup warning.

The memory engine uses fallback rather than these disk settings.
Pinned pages can keep the cache above its configured size.
The frame pool can retain freed memory, so a large budget needs enough free process memory.

## Measurements and counters

### Read failure detections

`OptimisticReadFailureCounters` holds process-wide counters by `Cause`.
These count failure detections, not totals of reads that use locked fallback.
Successful checks do no counter work. Samples are weakly consistent while readers run.

| Cause | Detection |
|---|---|
| `STAMP` | Zero acquisition stamp, or invalid stamp at scope validation or zero-copy result extraction |
| `EPOCH` | Fallback apply active at capture, fallback entered before final validation, or nested attempt in a different epoch domain |
| `COORDINATES` | Looked-up frame has unassigned coordinates or belongs to another page |
| `LOOKUP_MISS` | Missing shared entry/frame, out-of-range pinned load, or pinned load returning no page |
| `LOCAL_CHANGES` | Transaction-private changes prohibit reading shared contents |
| `FRAME_IDENTITY` | Recorded entry is dead, has no pointer, or no longer owns the recorded frame |
| `LOAD` | Repeatable pinned-load exception or otherwise uncounted nested computation failure |

Only the first failing check in a call counts.
`OptimisticReadScope.fail()` retains the first failure so later loads and final checks do not count it again.
Identity precedes stamp validation, so an identity failure does not also count as `STAMP`.

Zero-copy extraction uses the same identity/stamp validator and contributes to these causes.
Component-specific guards throw without incrementing these counters.
Non-repeatable post-load effects propagate without a failure-counter increment.
A successful locked read, a guard rejection, or an arbitrary outer speculative exception is not a fallback-total increment.

### Commit outcomes

`CommitProtocolCounters` belongs to one storage's `AtomicOperationsManager`.
Storage reopen creates fresh counters.
Each successful apply records exactly one outcome after apply and cleanup succeed.
Failed applies and failed cleanup record nothing.
The selection table defines `PAGE_SET`, `FORCED`, `FILE_LIFECYCLE`, `NO_PIN_BUDGET`, `OVER_LIMIT`, and `RESERVATION_DENIED`.
`ZERO_CHANGE` counts commits without shared-cache mutation and is excluded from protocol totals.

`epochCount()` derives the sum of the five fallback outcomes. It is not another incremented counter.
Samples are weakly consistent during concurrent commits. Compare complete snapshots when writers are quiescent.

### Cache progress and other observable values

`PageCacheCounters` separates eviction-policy measurements from write-cache flush measurements.

| Value | Meaning |
|---|---|
| `flushSkips` | Count of failed nonblocking shared frame-lock attempts by flush paths, not unique pages or skipped flush jobs |
| `evictionStops` | Count of bounded Eden purges that end while Eden remains oversized |
| `largestExcess` | Lifetime maximum of `max(0, eden + probation + protection - maxSize)` at those stops, in pages |

Eden, probation, and protection are the eviction policy's three page lists.
Queued additions alone do not count as eviction stops.
`largestExcess` is an absolute high-water mark, not a counter delta or queued-add lag.
`ApplyPhaseEpoch.applyEnterSeq` and `applyExitSeq` describe fallback brackets, not completed-commit statistics.

`LockFreeReadCache.reservedWritePins()` is current reserved capacity, including capacity retained after cleanup failure.
It is a gauge, not a cumulative counter.
Benchmark `CounterProbes.iteration()` reports deltas except for the absolute `cache.largestExcess` value.

## Test seams

A test seam is an entry point that lets a test control or observe the protocol.
`AtomicOperationTestBridge` exposes the package-private controls to tests.

| Seam | Selection and behavior |
|---|---|
| `forceEpochApply()` / `setForceEpochApplyForTesting(true)` | Forces fallback for mutating commits |
| `installPageApplyHook()` / `setPageApplyHook()` with a non-null hook | Forces fallback and permits per-file ordering and pauses before page apply |
| Clearing the page apply hook | Leaves the force override set. Reset the override explicitly to restore ordinary selection |
| `installPageSetPauseHook()` / `setPageSetPauseHook()` | Does not change selection. Runs only for a selected nonempty page set |
| `applyPhaseEpoch()` | Observes the component epoch without forcing a protocol |

The page apply hook's order must be a complete permutation of the file's changed pages.
Unknown indexes, duplicates, and omissions fail the commit.
The page-set pause hook sees the sorted set after all frame locks and after the first publication and unlock.
Its callback must not mutate the array, load pages, or acquire locks.
Callback exceptions propagate through reservation cleanup.

A force override takes precedence, so a page-set pause hook does not run on the forced path.
Zero-change commits count `ZERO_CHANGE` and do not call the page-set pause hook.

`PageSetEntryAssertions` replaces real-cache entries and inspects pins without forcing selection.
`PageSetTrackingAssertions` controls backup generations, inspects dirty state, and injects backup-mark failure without forcing selection.
The acquisition, prepare, restore, publication, unlock, and release spies in `CommitPageSetApplyTest` also force no protocol.

## Protocol tests

| Test class | Checks |
|---|---|
| `PageSetReaderConsistencyStressTest` | Mixed page-set and fallback writers with readers that reject inconsistent generations across real trees |
| `PageSetPinBudgetStressTest` | Maximum-budget cold commits evict filler and release pins, while zero-budget fallback preserves progress and values |
| `PageSetPausedReaderTest` | Outside-set reads, locked and old-stamp rejection, partial commits, identity rejection, and split growth before locks |
| `CommitPageSetApplyTest` | Whole-set order, fallback equivalence, selection boundaries, WAL dependencies, outcome counts, and failure cleanup |
| `LockFreeReadCacheDirtyNotificationFailureTest` | Reservation order, shared budget, pin-before-lock behavior, and acquisition/publication/cleanup failures |
| `WOWCacheChangedPageTrackingTest` | Dirty and backup marks during publication, pinned loads across eviction drains, and WAL-before-page-write ordering |
| `WTinyLFUPolicyTest` | Pinned eviction candidates, measured excess, retry, and queued-add exclusions |
| `LockFreeReadCacheOptimisticTest` | Entry identity, zero-copy validation, scoped pinned loads, failure propagation, and pin cleanup |
| `ApplyPhaseEpochTest` | Fallback epoch capture and validation, including overlapping writers |
| `OptimisticReadScopeTest` | Recorded stamps and identity, reset and growth, and epoch validation |
| `SharedLinkBagBTreeMixedApplyStateRegressionTest` | Deterministic real-tree split race with explicitly forced fallback |

## Manual benchmark limits

The manual benchmarks compare throughput under controlled page patterns and writer workloads.
They expose protocol selection and cache progress without collecting natural application read patterns.
A valid run shows behavior for its configured workload, build, and machine.
It does not prove correctness or throughput for production workloads.
See [Manual page-lock runs](../../jmh-ldbc/README.md#manual-page-lock-runs) for paired build, seed, and run commands.
