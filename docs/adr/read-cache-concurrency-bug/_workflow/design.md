# Read-cache concurrency bug — Design

## Overview

YouTrackDB today exposes the page allocator as a separate cache API
(`WriteCache.allocateNewPage`) distinct from `WriteCache.load`, with
`WriteCache.getFilledUpTo` publicly readable. A cross-TX reader inside
its own `data.compute` lambda can learn about a freshly-allocated
pageIndex N via `getFilledUpTo` before the allocator's `putIfAbsent`
installs the in-memory CachePointer and before the disk-side magic stamp
lands. The reader then either reads zeros and fails the magic check
(`StorageException`) or installs its own entry first and crashes the
allocator (`IllegalStateException`). Either branch poisons the storage.

This design replaces the asymmetric cache surface with a single total
primitive: `WriteCache.loadOrAdd(fileId, pageIndex, verifyChecksums)`.
It loads from disk if the page exists, extends the file by one page if
it doesn't, and gap-fills on recovery. It always returns a usable
`CachePointer`.

The enabling primitive on the read side is a pre-existing structural
fact: most storage components maintain a logical page count
(`entryPoint.pagesSize` / `fileSize`) on a metadata page. Three
components without an EntryPoint plus a handful of chicken-and-egg /
recovery-rebuild sites route through Track 5's named, audit-gated
helpers instead (see §"Allocation discovery surface" for the per-site
breakdown). Together these two surfaces eliminate the only path by
which a reader could learn about an in-flight pageIndex.

The change cascades into the storage components: `addPage` and its 19
external call sites are replaced by
`allocatePageForWrite(fileId, pagesSize + 1)`; the reuse-or-extend
probes disappear (the cache absorbs orphans uniformly); the do/while
reconciliation loops in `commitChanges`, `restoreAtomicUnit`, and
`restoreFromIncrementalBackup` collapse into single calls; the
`internalFilledUpTo` prediction wrapper goes away.

The rest of this document covers Class Design, Workflow, and four
dedicated sections on the cache primitive, the allocation discovery
surface, the concurrency model, and crash safety.

## Class Design

```mermaid
classDiagram
    class WriteCache {
        <<interface>>
        +loadOrAdd(fileId, pageIndex, verify) CachePointer
        +getFilledUpTo(fileId) long
        ~forEachPageDuringQuiesce(fileId, visitor) void
    }
    class WOWCache {
        +loadOrAdd(...) CachePointer
        -loadFileContent(fileId, pageIndex, verify) CachePointer
        -submitEnsureValidTask(fileId, pageIndex) void
    }
    class DirectMemoryOnlyDiskCache {
        +loadOrAdd(...) CachePointer
    }
    class LockFreeReadCache {
        +loadForRead(fileId, pageIndex) CacheEntry
        +loadOrAddForWrite(fileId, pageIndex) CacheEntry
        -data: ConcurrentLongIntHashMap~CacheEntry~
    }
    class StorageComponent {
        +loadForRead(fileId, pageIndex) CacheEntry
        +allocatePageForWrite(fileId, pageIndex) CacheEntry
        ~entryPoint: EntryPoint
    }
    class AtomicOperation {
        <<interface>>
        +loadPageForWrite(fileId, pageIndex, ...) CacheEntry
        +allocatePageForWrite(fileId, pageIndex) CacheEntry
    }
    class EntryPoint {
        +getPagesSize() int
        +setPagesSize(size) void
    }

    WriteCache <|.. WOWCache
    WriteCache <|.. DirectMemoryOnlyDiskCache
    LockFreeReadCache --> WriteCache : delegates loadOrAdd
    StorageComponent --> AtomicOperation : page I/O via
    StorageComponent --> EntryPoint : reads logical size from
    AtomicOperation --> LockFreeReadCache : loadForRead / loadOrAddForWrite
```

The diagram shows the post-fix shape. Three changes are non-obvious:

- `WriteCache.allocateNewPage`, `WriteCache.load`, and the
  `AtomicOperation.addPage` / `StorageComponent.addPage` methods are
  **deleted** — they appear in today's code but not on this diagram.
- `WriteCache.getFilledUpTo` is `@Deprecated(forRemoval=false)` post-
  Track-5 — JLS §9.4 forbids a literal package-private downgrade on
  an interface abstract method, so the audit-grep contract is enforced
  by the deprecation marker + Javadoc enumerating the retained internal
  callers + helper-set naming. The only external caller route is the
  Track 5 helper `WriteCache.physicalSizeForBackupSnapshot` from
  `DiskStorage.backupPagesWithChanges` (storage-quiesced).
- `LockFreeReadCache` no longer has an `allocateNewPage` method;
  `loadForRead` and `loadOrAddForWrite` differ only in the lock
  semantics they install on the returned `CacheEntry`.
- The `data` field on `LockFreeReadCache` is
  `ConcurrentLongIntHashMap<CacheEntry>` — a segmented open-addressing
  map keyed by the `(fileId, pageIndex)` long+int pair, with a
  `StampedLock` per segment. Earlier drafts showed it as
  `SegmentedMap<PageKey, ...>`; there is no `PageKey` class — the key
  is the long+int pair.

`EntryPoint` is the abstract shape most storage components already
carry: each EP-equipped component has a metadata page on pageIndex 0
with a `pagesSize` (or `fileSize`) field and dedicated WAL ops to
persist changes. The §"Allocation discovery surface" section
enumerates the concrete classes per component and names the three
EP-less components (`FreeSpaceMap`, `CollectionDirtyPageBitSet`,
`IndexHistogramManager`) that route through Track 5's gated helpers
instead.

## Workflow

The new design has three runtime paths worth diagramming: the write-side
allocation happy path, the recovery gap-fill path, and the cross-TX read
path. Each runs the same `LockFreeReadCache.data.compute` lambda
delegating to `WriteCache.loadOrAdd`; they diverge only on what
`loadOrAdd` does internally.

### Write-side allocation happy path

```mermaid
sequenceDiagram
    participant SC as StorageComponent
    participant AO as AtomicOperation
    participant RC as LockFreeReadCache
    participant LO as WriteCache.loadOrAdd
    participant AF as AsyncFile

    SC->>SC: targetIdx = entryPoint.pagesSize + 1
    SC->>AO: allocatePageForWrite(fileId, targetIdx)
    AO->>RC: loadOrAddForWrite(fileId, targetIdx)
    RC->>RC: data.compute(key, λ) [segment write lock held]
    Note over RC: entry not in data
    RC->>LO: loadOrAdd(fileId, targetIdx)
    LO->>AF: read currentSize
    Note over LO: targetIdx == currentSize, extend branch
    LO->>AF: allocateSpace(pageSize)
    LO->>LO: submit EnsurePageIsValidInFileTask
    LO-->>RC: magic-stamped empty CachePointer
    RC->>RC: install in data
    RC-->>AO: CacheEntry (write-locked)
    AO-->>SC: CacheEntry
    SC->>SC: write payload, entryPoint.setPagesSize(targetIdx)
```

The component computes its target pageIndex from local state — the
logical page count plus one. The cache does not return a "next free
pageIndex" anymore; the component states the target, and `loadOrAdd`
either loads (if the page is already on disk) or extends (if it isn't).
`entryPoint.setPagesSize` is the WAL-tracked logical bump that publishes
the new pageIndex to future cross-TX readers.

### Recovery gap-fill path

```mermaid
sequenceDiagram
    participant RP as restoreAtomicUnit
    participant AO as AtomicOperation
    participant RC as LockFreeReadCache
    participant LO as WriteCache.loadOrAdd
    participant AF as AsyncFile

    RP->>AO: loadOrAddForWrite(fileId, recordedPageIdx)
    AO->>RC: loadOrAddForWrite(fileId, recordedPageIdx)
    RC->>RC: data.compute(key, λ)
    RC->>LO: loadOrAdd(fileId, recordedPageIdx)
    LO->>AF: read currentSize
    Note over LO: recordedPageIdx greater than currentSize, gap-fill branch
    LO->>AF: allocateSpace((recordedPageIdx - currentSize + 1) * pageSize)
    loop for k in [currentSize, recordedPageIdx]
        LO->>LO: submit EnsurePageIsValidInFileTask(fileId, k)
    end
    LO-->>RC: magic-stamped empty CachePointer for recordedPageIdx
    RC->>RC: install in data
    RC-->>RP: CacheEntry (apply WAL changes to buffer)
```

Replay calls `loadOrAddForWrite` for each WAL record's pageIndex. If the
recorded pageIndex is beyond the current file size, the gap-fill branch
extends the file to the target and submits ensure-valid tasks for the
intervening pages. The replay never sees a partial extension because
`AsyncFile.allocateSpace` is a single atomic `getAndAdd`. Today's
do/while loop in `restoreAtomicUnit` collapses to a single call.

### Cross-TX read path

```mermaid
sequenceDiagram
    participant TX_A as TX_A (writer)
    participant TX_B as TX_B (reader)
    participant SC as StorageComponent
    participant EP as EntryPoint
    participant RC as LockFreeReadCache
    participant LO as WriteCache.loadOrAdd

    TX_A->>SC: write to pageIdx=N+1
    SC->>SC: entryPoint.setPagesSize(N+1)
    Note over TX_A,EP: WAL atomic unit commits, pagesSize=N+1 visible
    TX_B->>SC: iterate up to entryPoint.getPagesSize()
    SC->>EP: getPagesSize() returns N+1
    SC->>SC: target = N (in range)
    SC->>RC: loadForRead(fileId, N)
    RC->>RC: data.compute(key, λ)
    RC->>LO: loadOrAdd(fileId, N)
    LO-->>RC: load from disk (page exists)
    RC-->>SC: CacheEntry (read-locked)
```

`TX_B` learns about `N` only after `TX_A`'s WAL atomic unit has closed
— at which point `pagesSize = N+1` is the committed cross-TX state and
`TX_A`'s page-content modification has been recorded in WAL. The WAL
records themselves may still be in the in-memory log buffer at this
moment; the durability guarantee is the **write-ahead** invariant: any
subsequent flush of pageIndex `N` to disk happens only after `TX_A`'s
WAL records are durable. So when `TX_B` runs, the page at `N` is
either still in the in-memory cache holding `TX_A`'s content, or has
already been flushed to disk under WAL protection. Page changes are
visible to other transactions only after TX commit, so `TX_B` cannot
observe a partial state. `TX_B`'s `loadForRead` takes the load branch,
never the extend branches; the race vector — a reader observing an
in-flight pageIndex via `getFilledUpTo` — is not reachable from this
path.

## Cache primitive: loadOrAdd

**TL;DR.** The primitive replaces three asymmetric cache APIs (`load`,
`allocateNewPage`, public `getFilledUpTo`) with one total method. It
loads from disk, extends by one page, or gap-fills on recovery, and
always returns a usable `CachePointer`. The race goes away because
there is no longer a separate "publish in-flight pageIndex" code path
outside `data.compute`'s segment write lock.

The signature is:

```java
CachePointer loadOrAdd(long fileId, long pageIndex, boolean verifyChecksums);
```

The caller is `LockFreeReadCache`, from inside its `data.compute(fileId,
pageIndex, λ)` lambda, after the lambda has confirmed the entry is not
already in `data`. The segment write lock for the target key is held
for the duration of the call.

Inside `loadOrAdd`, the implementation reads `AsyncFile.size` once
atomically, then dispatches to one of three branches based on the
relationship of `pageIndex` to the current size in pages. There is
no separate "orphan re-stamp" branch: a magic-stamped disk-resident
orphan (scenario A in §"Crash safety") is absorbed by the Load
existing branch with no special-casing.

### Branch table

| Branch | Pre-condition | Side effect on `AsyncFile.size` | Side effect on disk | Returned `CachePointer` |
|---|---|---|---|---|
| Load existing | `pageIndex < currentSize` | none | none (read) | content from `loadFileContent` |
| One-page extend | `pageIndex == currentSize` | advances by one page | task submitted | magic-stamped empty buffer |
| Gap-fill | `pageIndex > currentSize` (recovery only) | advances to `pageIndex + 1` pages | task submitted per gap page | magic-stamped empty buffer for target |

The "task submitted" entries refer to `EnsurePageIsValidInFileTask`,
which idempotently writes the magic-stamped empty page to disk only if
the disk file's actual length is still at or below the target offset.
Magic-check failure on the load branch propagates to the caller as
`StorageException` — the new design does not change that error path.
A separate ticket (`ISSUE-ensurevalidpagetask-torn-write.md`) tracks
the related torn-write / OS-writeback durability gap that can produce
such a magic-check failure under crash; that gap pre-dates this fix.

### Why the runtime hot path takes only the extend-by-one branch

By the runtime invariant captured under §"Concurrency model" — every
production caller of the write path computes `pageIndex` as
`entryPoint.pagesSize + 1` — `pageIndex` is always exactly one beyond
`AsyncFile.size` when no concurrent allocator has raced ahead, and it
equals an existing page when this transaction is performing a normal
write to a previously-allocated index. The gap-fill branch never fires
under normal execution; it exists for WAL replay where a recorded
pageIndex may be many pages beyond the current file size on a
freshly-reopened storage.

The read path (`loadForRead`) shares the same `loadOrAdd` primitive but
its caller-imposed invariant guarantees `pageIndex < pagesSize <=
currentSize`, so the load branch is the only one reachable. If a buggy
caller violates this — passes a pageIndex beyond the logical surface —
the cache silently extends the file. This is harmless to crash safety
(an empty page leaks; the WAL has nothing recorded for it) and matches
the failure-mode shape of today's read path (which would return a
broken page). No `-ea` assertion is added because the failure mode is
not a corruption, just a leaked page.

### Edge cases / Gotchas

- **Concurrent allocators on different `(fileId, pageIndex)`** — the
  segment locks are independent across keys. Both branches' calls to
  `AsyncFile.allocateSpace(getAndAdd)` interleave atomically; the
  in-memory `size` is monotonic. The disk-side `EnsurePageIsValidInFileTask`
  for each page is independent.
- **`EnsurePageIsValidInFileTask` failure** — disk-full or I/O error
  surfaces asynchronously via WOWCache's existing background-error
  reporting. `loadOrAdd` itself returns an in-memory
  `CachePointer` regardless, and the failed disk write is observed at
  the next checkpoint or recovery.
- **`pageIndex == 0` for fresh file** — pageIndex 0 is normally the
  metadata / EntryPoint page. `loadOrAdd(fileId, 0)` extends from
  `currentSize=0` to `1`; the magic-stamped empty buffer is what every
  `EntryPoint.create()` then overwrites with its initial content.
- **`DirectMemoryOnlyDiskCache.loadOrAdd`** — the in-memory engine has
  no `AsyncFile` and no async stamp task. Its implementation reduces to
  a `ConcurrentHashMap` install of magic-stamped empty buffers under
  the same segment-lock-held lambda, with gap-fill collapsing to a loop
  over `put`-if-absent.

### References

- D-records: D1 (`loadOrAdd` as the sole cache primitive)
- Invariants: I2, I3 (extension under segment lock; total primitive)

## Allocation discovery surface

**TL;DR.** Cross-TX readers learn page existence from
`entryPoint.pagesSize` / `entryPoint.fileSize` where the component has
an EntryPoint, and through Track 5's named, audit-gated helpers
otherwise. Both branches close the discovery channel that exposes
in-flight pageIndices: the logical surface is preferred where one
exists, and per-component-lock + Track 1's `loadOrAdd` totality + the
gated helpers cover the components without one. Per-component
reuse-or-extend probes disappear, the no-pageIndex `addPage` API
disappears, the `commitChanges` / `restoreAtomicUnit` reconciliation
loops collapse, and `WriteCache.getFilledUpTo` becomes `@Deprecated` + audit-gated (JLS §9.4 forbids package-private on an interface abstract method; the contract is enforced via the deprecation marker, Javadoc enumerating retained internal callers, and Track 5's named helpers `WriteCache.physicalSizeForBackupSnapshot` + `StorageComponent.physicalSize(op, fileId, PhysicalReadIntent)`).

### Logical-size surface per component

Most storage components carry a logical page count on a metadata
page, persisted via dedicated WAL operations. The set is fixed, was
already in place before this fix, and was already partially consumed
by the reuse-or-extend probe pattern. Three components have no
EntryPoint — see the post-table note.

| Component | Field | Getter | WAL op |
|---|---|---|---|
| `CellBTreeSingleValueEntryPointV3` | `pagesSize` | `getPagesSize()` | `BTreeSVEntryPointV3SetPagesSizeOp` |
| `CellBTreeSingleValueEntryPointV1` | `pagesSize` | `getPagesSize()` | analogous |
| `CellBTreeMultiValueV2EntryPoint` | `pagesSize` | `getPagesSize()` | `BTreeMVEntryPointV2SetPagesSizeOp` |
| `ridbagbtree.EntryPoint` | `pagesSize` | `getPagesSize()` | `RidbagEntryPointSetPagesSizeOp` |
| `collection.v2.MapEntryPoint` | `fileSize` | `getFileSize()` | `MapEntryPointSetFileSizeOp` |
| `collection.v2.PaginatedCollectionStateV2` | `fileSize` | `getFileSize()` | `PaginatedCollectionStateV2SetFileSizeOp` |
| `versionmap.MapEntryPoint` | `fileSize` | `getFileSize()` | analogous |

Three components in the original Track 3 scope have **no EntryPoint
metadata page and no logical-size field at all**: `FreeSpaceMap`,
`CollectionDirtyPageBitSet`, and `IndexHistogramManager` (verified via
PSI during Phase A of the retired Track 3). Their per-component locks
plus Track 1's `loadOrAdd` totality uphold invariant I1 even without
a logical surface; `getFilledUpTo` reads from these components route
through Track 5's rationale-bearing gated helpers (see "Migration
shape" below and D4 in the plan file's Architecture Notes for the
helper-surface contract).

### Migration shape

The 16 production call sites of `StorageComponent.getFilledUpTo`
split into four groups after the Phase A audit of the retired
Track 3:

- **Pure-sizing migrations to the logical surface (1 site).**
  `BTree.doAssertFreePages:1389` migrates to
  `entryPoint.getPagesSize() + 1` on `CellBTreeSingleValueEntryPointV3`.
  Test-only assertion path (`-ea` only via `assertFreePages:1373`);
  adds a single `loadPageForRead(ENTRY_POINT_INDEX)` at method entry
  to obtain the EntryPoint. Lands in Track 4 alongside the BTree
  probe collapses (which already load the same EntryPoint).
- **Reuse-or-extend probes collapsed by `loadOrAdd` (7 + 2 absorbed
  from the retired Track 3).** The originally-listed 7 sites —
  `BTree.allocateNewPage:2156/2161`,
  `SharedLinkBagBTree.{splitNonRootBucket:922/927, splitRootBucket:1050}`,
  `CollectionPositionMapV2.allocate:208`,
  `PaginatedCollectionV2.allocateNewPage:2233` — plus 2 growth-loop
  probes previously misclassified as pure-sizing reads:
  `FreeSpaceMap.updatePageFreeSpace:227` and
  `CollectionDirtyPageBitSet.ensureCapacity:194`. Both growth-loops
  have the shape `for (i = filledUpTo; i ≤ required; i++) addPage(...)`
  and collapse to `allocatePageForWrite(fileId, knownIndex)` per the
  same pattern. All collapse work lands in Track 4.
- **Stay-on-physical via Track 5 gated helpers (3 EP-equipped sites +
  3 EP-less sites = 6 sites).** EP-equipped: bootstrap emptiness checks
  at `CollectionPositionMapV2.create:136` and
  `PaginatedCollectionV2.initCollectionState:2256` (the EntryPoint
  lives on the page being checked — chicken-and-egg); FSM-rebuild
  recovery scan at `PaginatedCollectionV2.open:391` (logical
  bookkeeping was lost — physical-by-design). EP-less: defensive
  physical-presence probe at
  `IndexHistogramManager.readSnapshotFromPage:1819` (discriminator at
  `:1843`; guards against
  partial crash between page-0 and page-1 writes in the same atomic
  op; IHM has no EntryPoint per the §"Logical-size surface per
  component" footer); and the 2 pure-sizing reads in
  `CollectionDirtyPageBitSet.{clear:141, nextSetBit:168}`. Each site
  gets a rationale-bearing inline comment in Track 4; the access path
  moves to Track 5's gated helper(s).
- **Storage-quiesced full-file iteration (1 site on
  `WriteCache.getFilledUpTo`).** `DiskStorage.backupPagesWithChanges`
  (method @ :1387, call @ :1404) routes through Track 5's
  quiesce-named helper.

Concrete count: 16 `StorageComponent.getFilledUpTo` callers (1
logical migration + 9 probe collapses + 6 stay-on-physical via gated
helper) + 1 `WriteCache.getFilledUpTo` caller (backup, gated).
Track 4 contains the migrations + probe collapses + rationale
comments; Track 5 contains the helper introduction + access
tightening.

### Why `addPage` is deletable

`StorageComponent.addPage(fileId)` has a no-pageIndex signature: the
cache picks via `allocateSpace.getAndAdd` and the caller learns the
result. This is what forced two pieces of complexity:

- The `internalFilledUpTo` prediction wrapper in
  `AtomicOperationBinaryTracking`, which pre-allocates a synthetic
  pageIndex for the in-progress TX and rewrites it on commit if the
  prediction was wrong.
- The do/while reconciliation loop in `commitChanges`, which calls
  `readCache.allocateNewPage` repeatedly until the returned pageIndex
  matches the predicted one (and similarly in `restoreAtomicUnit` and
  `restoreFromIncrementalBackup` during recovery).

Once allocators state the target pageIndex up front — derived from the
component's `entryPoint.pagesSize + 1` — neither prediction nor
reconciliation has anything to do. The 19 external `addPage` call
sites all already know their target from local state (~9 fresh-file
sequential allocations at index 0 or 1; ~8 reuse-or-extend branches
that compute `pagesSize + 1`; 2 sites at
`FreeSpaceMap.updatePageFreeSpace:229` and
`CollectionDirtyPageBitSet.ensureCapacity:197` sit inside growth-loops
absorbed from the retired Track 3, whose `getFilledUpTo` reads at
`FSM:227` / `CDPB:194` collapse alongside the `addPage` deletion under
the same `allocatePageForWrite` migration (introduced under the original name `loadOrAddPageForWrite` in Track 4 and renamed by Track 5); Phase A confirms the
exact split). The 20th PSI reference
to `addPage` is the recursive call inside
`StorageComponent.allocatePageForWrite` (today a `loadPageForWrite`-
then-`addPage` fallback) — Track 4 rewires that body to delegate to
the new cache primitive rather than calling `addPage`. Migration is
mechanical.

WAL is unaffected: page extension stays implicit (no `AddPage*`
record), and the `SetPagesSizeOp` / `SetFileSizeOp` records that
already track logical-size advances continue to do so.

### Edge cases / Gotchas

- **Components without an EntryPoint stay on physical sizing.**
  `FreeSpaceMap`, `CollectionDirtyPageBitSet`, and
  `IndexHistogramManager` have no metadata page and no logical-size
  field (PSI-verified during Phase A of the retired Track 3). After
  Track 4's probe collapses, only
  `CollectionDirtyPageBitSet.{clear, nextSetBit}` survive as
  pure-sizing reads on physical extent; they route through Track 5's
  gated helper under per-component lock. I1 is upheld by the lock +
  Track 1's `loadOrAdd` totality, not by routing through logical
  state.
- **`PaginatedCollectionV2.open:391` (FSM-rebuild recovery).** This
  branch runs when the FSM file is absent on open (post-crash FSM
  loss) and rebuilds free-space info by iterating every physically
  existing data page. Logical bookkeeping has just been determined
  unreliable (the FSM was lost), so trusting it for the iteration
  bound would silently leave orphan pages out of the rebuilt FSM.
  Stays on physical via Track 5's gated helper; the entire branch is
  storage-quiesced under the component's exclusive lock.
- **`CollectionPositionMapV2.create:136` (fresh-file emptiness
  check).** Checks "does page 0 exist physically?" before
  instantiating `MapEntryPoint` over page 0. The EntryPoint lives on
  the page being tested, so a logical read is chicken-and-egg. Stays
  on physical via Track 5's gated helper; rationale recorded inline
  in Track 4.
- **`PaginatedCollectionV2.initCollectionState:2256`.** Same
  chicken-and-egg shape as `CollectionPositionMapV2.create:136` —
  checks "does page 0 exist physically?" before instantiating
  `PaginatedCollectionStateV2` over page 0. Stays on physical via
  Track 5's gated helper; rationale recorded inline in Track 4.
- **`IndexHistogramManager.readSnapshotFromPage:1819`** (discriminator at `:1843`)**.** Defensive
  physical-presence probe guarding against a partial crash between
  page-0 and page-1 writes in the same atomic op (the in-line comment
  documents the intent). Track 4 Phase A picks between (a) staying on
  `getFilledUpTo` via Track 5's gated helper, or (b) migrating to
  `WriteCache.loadIfPresent(fileId, 1)` + null check — both preserve
  the defensive intent. Note: the page-1 discriminator
  (`op.filledUpTo > 1 ? load : allocate`) is also what justifies
  excluding IHM from Track 7's EP-driven recovery-time
  orphan-truncation pass — see Non-Goals in `implementation-plan.md`.
- **Backup path** — `DiskStorage.backupPagesWithChanges` runs under
  storage quiesce, which holds back any concurrent cache writes. The
  storage-quiesced contract (no concurrent extension, no concurrent
  flush) is what makes the physical size read safe; the gated helper's
  javadoc states this explicitly so future callers don't reach for it
  outside quiesce.

### References

- D-records: D2 (logical surface as discovery — revised after Track 3
  audit), D3 (`addPage` deletion + reconciliation collapse),
  D4 (`getFilledUpTo` lockdown — revised after Track 3 audit), D5,
  D6 (recovery-time orphan truncation — anchors the IHM / EP-less
  carve-out in the gotcha bullet)
- Invariants: I1, I5

## Concurrency model

**TL;DR.** Page extension occurs only inside
`LockFreeReadCache.data.compute`'s segment write lock; per-component
locks (BTree mutex, position-map mutex, …) serialize concurrent
allocators that share a `fileId`. The combination forecloses both
within-key and cross-key races: two TXs cannot allocate the same
`(fileId, pageIndex)`, and two TXs allocating different pageIndices
under the same fileId interleave atomically via `AsyncFile.allocateSpace`.

### Lock layering

Three layers of synchronization apply during a `loadOrAdd` call. They
nest in this order, top to bottom:

1. **Per-component lock** (above the cache; e.g. `BTree`'s synchronize
   block, `PaginatedCollectionV2`'s mutex, `CollectionPositionMapV2`'s
   internal serialization). Held by every storage component before it
   reads `entryPoint.pagesSize` and computes `pagesSize + 1` as the
   target pageIndex. This is what guarantees two TXs operating on the
   same component-file never compute the same target. (**Phase A
   audit**: confirm the exact serialization mechanism per component —
   is it `synchronized(this)`, a per-instance `Lock` field,
   `componentLock`, or something else? The wording above is coarse;
   the implementer pins the concrete lock-field name per component
   before migrating that component's `addPage` call sites in Track 4.)
2. **Segment write lock on `LockFreeReadCache.data`** (held by
   `data.compute` for the duration of the lambda). Serializes
   concurrent attempts to install or update the same `(fileId,
   pageIndex)` cache entry.
3. **Within `WOWCache.loadOrAdd`**: `filesLock.readLock` (allowing
   concurrent file operations) plus `files.acquire(fileId)` (a
   per-file exclusion). The ordering matches today's
   `WOWCache.allocateNewPage`; `loadOrAdd` does not invert it.

`AsyncFile.size` itself is updated atomically via `getAndAdd` — there
is no separate lock around it. Multiple `loadOrAdd` calls on different
keys can advance the size in interleaved order; the resulting
in-memory size is monotonic.

### Why two TXs cannot race for the same `(fileId, pageIndex)`

Two TXs target the same pageIndex only if they read the same
`entryPoint.pagesSize` and both compute `pagesSize + 1`. The
per-component lock prevents that: only one TX at a time holds the lock
to compute its target. The TX that loses the race waits, then reads
the post-bump `pagesSize` and computes a different target.

Recovery is single-threaded, so concurrent allocators do not exist
during replay.

### Why concurrent readers cannot fabricate an in-flight pageIndex

A cross-TX reader's `loadForRead(fileId, K)` requires `K <
entryPoint.pagesSize`. `pagesSize` is only bumped after the WAL atomic
unit closes — at which point the corresponding page is materialized in
the cache and the disk-side stamp task has been submitted. The reader
cannot observe a `pagesSize` that names an in-flight page; the race
vector is not reachable.

### Edge cases / Gotchas

- **Cross-fileId concurrency** — no shared lock; each file's
  `AsyncFile.size` is independent and atomic. Cross-file allocations
  proceed in parallel without contention.
- **Flush worker concurrency** — the dirty-page flush worker runs in
  its own thread, takes the segment read lock plus the page's content
  lock to read the buffer, and writes to disk. Its interaction with
  `loadOrAdd` is the same as today's `load`: the flush worker reads a
  consistent snapshot of the page content; the `loadOrAdd` extension
  branches do not block on it.
- **`EnsurePageIsValidInFileTask` concurrency** — multiple tasks for
  the same fileId can be in flight simultaneously, each targeting a
  different pageIndex. Each task's `writeValidPageInFile` is
  idempotent and the underlying I/O is serialized by the OS file lock;
  no additional synchronization is needed at the task level.
- **`DirectMemoryOnlyDiskCache`** — has no `AsyncFile` and no
  per-file `files.acquire` lock; the segment lock alone is sufficient
  because all state is in-memory and consistent under
  `ConcurrentHashMap`'s contract.

### References

- D-records: D1 (cache primitive), D2 (discovery surface)
- Invariants: I2, I4

## Crash safety

**TL;DR.** The new design preserves crash safety against three
scenarios that today's allocator+task split must handle. Orphan disk
pages from an in-flight TX are absorbed by `loadOrAdd`'s load branch
(they are always magic-stamped under FIFO submission); WAL replay's
extend-and-gap-fill is handled by `loadOrAdd` directly. No new
vulnerability is introduced.

`EnsurePageIsValidInFileTask` runs on a single-threaded
`wowCacheFlushExecutor` (`YouTrackDBEnginesManager.java:231`), and
submissions for a given `fileId` are monotonic in pageIndex by
construction (each `loadOrAdd` extension targets `pagesSize + 1`,
serialized by the per-component lock). FIFO + monotonic submission
forecloses sparse-zero interior pages: if the disk file was extended
through pageIndex `K`, every pageIndex in `[old_size, K]` was stamped
in order before the extension to `K+1` could begin. A separate ticket
(`ISSUE-ensurevalidpagetask-torn-write.md`) tracks the orthogonal
torn-write / OS-writeback durability gap that pre-dates this fix.

### Scenario walk-through

The three crash scenarios apply to a TX that does an extension. They
follow today's vocabulary: in-memory file size advance via
`AsyncFile.allocateSpace`, asynchronous magic-stamping via
`EnsurePageIsValidInFileTask`, and on-reopen the in-memory size
re-initializes from the disk file's actual length.

#### A. TX in flight, never committed

WAL has no `AtomicUnitEndRecord` for the TX, so replay skips the unit.
`entryPoint.pagesSize` was never bumped (the `setPagesSize` op was
inside the unit). The disk file may or may not have been physically
extended — depends on whether the ensure-valid task ran before the
crash.

On reopen: `AsyncFile.size` re-initializes from disk length. Any
physical extension that survived is an orphan (disk has the page,
component doesn't count it).

Next TX: reads `entryPoint.pagesSize`, computes `pagesSize + 1`, calls
`allocatePageForWrite`. Inside `loadOrAdd`:

- If the orphan exists on disk: it is magic-stamped (FIFO + monotonic
  submission guarantees that every disk-resident orphan completed its
  ensure-valid task before any later orphan's task could start). Load
  branch fires; the next TX overwrites the empty page with its
  content.
- If the file has no orphan at this index: extend branch fires.

In both sub-cases, the next TX gets a usable page at the target index;
the orphan (if any) is absorbed transparently. **Consistent.**

#### B. TX committed, ensure-valid task never ran

WAL has the full atomic unit including `setPagesSize` and the page-
content op (`UpdatePageRecord` / `PageOperation`). Disk file size on
reopen = pre-extension.

On replay: `restoreAtomicUnit` calls `loadOrAddForWrite(fileId, N)`.
Cache: `pageIndex == currentSize`, extend branch fires, advances size,
submits ensure-valid task, returns magic-stamped empty pointer.
Replay applies the WAL changes to the in-memory buffer. Async task
runs in background; idempotent stamp.

**Consistent.**

#### C. TX committed, ensure-valid task ran fully

Disk has the magic-stamped empty page from the task, plus (on
checkpoint flush) the TX's actual content. On replay: load branch
fires, magic check passes, replay applies WAL changes to the buffer.

**Consistent.**

### Role of `EnsurePageIsValidInFileTask` in the new design

The task was load-bearing for two reasons in the legacy design: ensuring
the disk file is long enough to read pageIndex N, and stamping the
magic so the magic-check leg of recovery passes. Both reasons remain
under the new design. The task's role narrows in **scope**, not in
**function**: it is no longer the primary mechanism for "publishing"
a new page (the segment-locked install of the in-memory `CachePointer`
is what publishes), but it is still the mechanism for making the page
durable post-eviction.

### Edge cases / Gotchas

- **Post-WAL-replay file truncation** — for EP-equipped components
  (`BTree`, `SharedLinkBagBTree`, `CollectionPositionMapV2`,
  `PaginatedCollectionV2`), the recovery-time pass introduced by
  D6 / Track 7 runs from a new
  `AbstractStorage.truncateOrphansAfterRecovery()` invoked from
  `open()` after `openCollections` / `openIndexes` /
  `linkCollectionsBTreeManager::load` populate the iteration targets
  (around `AbstractStorage.java:801`), and from
  `DiskStorage.postProcessIncrementalRestore` between `:1671` (after
  `openIndexes`) and `:1673` (before `flushAllData()`). The pass
  compares the entry-point logical page count to
  `AsyncFile.getFileSize() / pageSize` and truncates physical orphans
  via the new `WriteCache.shrinkFile(fileId, targetBytes)` primitive
  (I6). EP-less components (`FreeSpaceMap`,
  `CollectionDirtyPageBitSet`) and `IndexHistogramManager` are
  deliberately out of scope per the Non-Goals — their growth-loops
  are `getFilledUpTo`-anchored or use a page-1 discriminator pattern.
- **`DoubleWriteLog` interaction** — anti-tear protection for
  partially-written pages is orthogonal to allocation. Unchanged.
- **In-memory engine** — `DirectMemoryOnlyDiskCache` has no
  persistence, so crash safety is trivially preserved (no disk to
  diverge from).

### References

- D-records: D1 (`loadOrAdd` covers all three scenarios), D5 (the
  marker-bit alternative would have papered over scenario B without
  removing the discovery channel), D6 (recovery-time orphan
  truncation for EP-equipped components)
- Invariants: I3, I5, I6 (post-recovery `logical == physical` for
  EP-equipped components)
- Related: `ISSUE-ensurevalidpagetask-torn-write.md` (orthogonal
  torn-write / OS-writeback durability gap, out of scope here)
