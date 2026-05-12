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

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
