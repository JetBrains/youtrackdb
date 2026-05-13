# Track 5: Tighten `getFilledUpTo` access via gated helpers

## Description

Make `WriteCache.getFilledUpTo` non-public and route the surviving external
consumers (≥ 5 — see `**What**` below for the per-site set) through
narrowly-scoped helpers with rationale-bearing names. Phase A picks the
helper shape (one helper + intent enum vs 2-3 named helpers). Add javadoc to
`WriteCache` and `StorageComponent` documenting the discovery contract.

> **What**:
> - After Track 4 lands, downgrade `WriteCache.getFilledUpTo` from
>   `public` to package-private (or otherwise non-public).
> - Surviving external consumer set (≥ 5, per D4 revision):
>   - `DiskStorage.backupPagesWithChanges` — storage-quiesced backup
>     iteration (method @ :1387, `getFilledUpTo` call @ :1404).
>   - `CollectionPositionMapV2.create:136` — bootstrap-time emptiness
>     check; EntryPoint lives on the page being checked.
>   - `PaginatedCollectionV2.initCollectionState:2256` — same
>     bootstrap pattern.
>   - `PaginatedCollectionV2.open:391` — FSM-rebuild recovery scan;
>     logical bookkeeping was lost.
>   - `IndexHistogramManager.readSnapshotFromPage:1833` — defensive
>     physical-presence probe for the optional HLL page 1. If Track 4
>     Phase A switched this to `WriteCache.loadIfPresent(fileId, 1)`,
>     skip this entry; otherwise route through the gated helper.
>   - `CollectionDirtyPageBitSet.{clear:141, nextSetBit:168}` —
>     EP-less pure-sizing reads under per-component lock.
> - Phase A picks the helper shape — Phase 1 candidates:
>   - **One helper + intent enum**: `WriteCache.physicalSize(long
>     fileId, PhysicalReadIntent intent)` where the enum lists the
>     surviving intents (`BACKUP_QUIESCED`,
>     `BOOTSTRAP_EMPTINESS_CHECK`, `RECOVERY_REBUILD`,
>     `DEFENSIVE_PRESENCE`, `EP_LESS_PURE_SIZING`). Lowest API surface;
>     enum constant carries the audit-grep signature.
>   - **2-3 named helpers**: e.g.,
>     `forEachPageDuringQuiesce(fileId, visitor)`,
>     `physicalSizeUnderComponentLock(fileId)`,
>     `physicalSizeForBootstrap(fileId)`. More explicit names; smaller
>     enum-coupling.
>   Phase A picks one and adapts the consumer migrations accordingly.
> - Add javadoc to `WriteCache` and `StorageComponent` documenting the
>   discovery contract: cross-TX readers route through the logical
>   surface when one exists; physical-size reads route through the
>   gated helper(s) above, each with a contract-stating javadoc on
>   the helper or enum constant.
>
> **How**:
> - Step 1 (Phase A decision): pick the helper shape (one-helper-with-
>   enum vs 2-3 named helpers). Confirm with the user if the decision
>   is non-obvious from the surviving consumer set.
> - Step 2: introduce the helper(s) on `WriteCache` (or
>   `StorageComponent`, depending on which class exposes the
>   audit-grep target). Implementation re-routes to the existing
>   in-memory size read (`AsyncFile.size` / `AsyncFile.allocateSpace`
>   bookkeeping).
> - Step 3: migrate each surviving consumer to the new helper(s).
>   Verify backup tests, FSM-rebuild recovery tests, and the
>   IndexHistogramManager defensive path still behave correctly.
> - Step 4: change `WriteCache.getFilledUpTo` access to package-private.
>   Build + tests green.
> - **Verification**: PSI find-usages on `WriteCache.getFilledUpTo`
>   should show no production callers outside the cache package after
>   this track. Test mocks that live in the same package context are
>   acceptable. PSI find-usages on each new helper should match the
>   D4-revision surviving consumer set exactly — extra or missing
>   callers are findings.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/.../internal/core/storage/cache/WriteCache.java`
>   - `core/.../internal/core/storage/cache/local/WOWCache.java`
>   - `core/.../internal/core/storage/memory/DirectMemoryOnlyDiskCache.java`
>   - `core/.../internal/core/storage/disk/DiskStorage.java` (the
>     backup consumer; method @ :1387, call @ :1404)
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (if the audit-grep helper surface lives there)
>   - The surviving consumer call sites (CollectionPositionMapV2,
>     PaginatedCollectionV2, IndexHistogramManager,
>     CollectionDirtyPageBitSet).
>   - Any cache test class still using the old public method.
> - **Out of scope**: functional behavior changes; new WAL records;
>   widening the surviving-consumer set beyond what D4's revision
>   names.
>
> **Interactions**:
> - Depends on Track 4 (write-side API collapse done — replay loops
>   no longer call `getFilledUpTo` via `internalFilledUpTo`, and the
>   rationale-comment locations are pinned by Track 4's per-site
>   commits).
> - Enables nothing downstream (this is the final cache-API hygiene
>   pass).
> - Verifies invariant **I1** is enforceable at compile time —
>   external code cannot regress to a non-gated
>   `getFilledUpTo`-based discovery channel.

## Track 4 Phase C deferral absorbed — rename `loadOrAddPageForWrite` → `allocatePageForWrite`

Track 4's Phase C review surfaced that the post-Step-2 AOBT-layer
contract is **allocator-only** on the disk engine (`pageIndex` below
the committed file size raises `IllegalStateException`), yet the
method name and the surrounding API still read as "load or add"
(total semantics). The AOBT Javadoc admits this explicitly: *"Despite
the historical name, this method does NOT load existing pages on the
disk engine."* The misleading name propagates from the cache-layer
primitive `WriteCache.loadOrAdd` (which IS total) through the AOBT
wrapper (allocator-only) and the `StorageComponent` wrapper to every
production call site.

This track absorbs the rename because Track 5's existing charter is
the post-Track-4 API hygiene pass.

**Recommended new name:** `allocatePageForWrite(fileId, pageIndex)` —
parallel naming with the existing `loadPageForWrite(fileId, pageIndex,
pageCount, verifyChecksum)`. The `<verb>PageForWrite` shape makes
load-vs-allocate intent explicit at every call site.

**Scope:**
- `AtomicOperation.loadOrAddPageForWrite` (SPI interface declaration,
  one site) → `AtomicOperation.allocatePageForWrite`.
- `AtomicOperationBinaryTracking.loadOrAddPageForWrite` (impl, one
  site, plus its Javadoc).
- `StorageComponent.loadOrAddPageForWrite` (the
  `core/.../paginated/base/StorageComponent.java` protected wrapper,
  one declaration + body comment).
- 19 production allocator call sites across BTree, SLBB, CPMV2, PCV2,
  FSM, CDPB, IHM (per `git grep -c 'loadOrAddPageForWrite' core/src/main`
  the count at Track-4 tip is ~30 production refs including comments
  + Javadoc cross-references; 19 are the call sites named in Track 4's
  per-site migration record).
- Test class rename: `LoadOrAddPageForWriteTest` → `AllocatePageForWriteTest`
  (file + class header + every test-method narration that names the
  AOBT method).
- ~80 test references across `AtomicOperationBinaryTrackingWALSkipTest`,
  `AtomicOperationSnapshotProxyTest`, `CollectionPositionMapV2Test`,
  `CollectionDirtyPageBitSetTest`, `FlushPendingOperationsTest`,
  `PageOperationAccumulationLifecycleTest`, `RegisterPageOperationTest`
  (each test class has Javadoc + inline references + Mockito stubs
  keyed by the method name).
- Javadoc `{@link AtomicOperation#loadOrAddPageForWrite}` cross-
  references (need PSI to enumerate exactly — at minimum the AOBT
  impl + the SPI interface body + the `StorageComponent` body
  cross-link to it).

**Why the IDE refactor (not raw `Edit`):**
- 109 textual occurrences across 18 files at Track-4 tip; raw
  `grep + Edit` would miss polymorphic dispatch through the SPI
  interface (different code paths see different `AtomicOperation`
  implementations) and would skip Javadoc `{@link}` references.
- Per project `CLAUDE.md` § MCP Steroid → Refactoring: "Renames,
  moves, signature changes, … and any refactor that touches more
  than one reference site route through the IDE refactoring engine
  via mcp-steroid, not raw Edit."
- Phase A of Track 5 should verify mcp-steroid is reachable before
  scheduling this step — if not, surface to the user and wait for a
  session where the IDE is available rather than fall back to grep.

**Plan-correction commits introducing this deferral:**
- `9dff2ac2e3` (Track 6 scope expansion + Non-Goals unit-level
  backlog).
- The plan correction adding this Track 5 rename absorption (this
  commit, recorded in Track 4's track-completion summary).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
