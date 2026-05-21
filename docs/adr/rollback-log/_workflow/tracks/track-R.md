# Track R: StampedLock-based optimistic reads on all page access paths

## Description

Converts remaining direct shared-mode page-read sites (`IndexAbstract`, `IndexMultiValues`, `IndexOneValue`, `IndexManagerEmbedded`, minor `PaginatedCollectionV2` / `DiskStorage`) to `executeOptimisticStorageRead`. Pure refactor; commit semantics unchanged.
**Scope:** ~5 steps including concurrent-reader integration tests.

> **What**:
> - Convert the ~33 remaining direct `acquireSharedLock` read sites in
>   the index subsystem and minor paginated-collection paths to route
>   through the existing
>   `StorageComponent.executeOptimisticStorageRead` helper. The helper
>   already orchestrates "optimistic-first with pinned-shared-lock
>   fallback" and records stamps into `OptimisticReadScope`.
> - Files and approximate call-site counts (exact offsets resolved at
>   Phase A):
>   - `core/.../internal/core/index/IndexAbstract.java` — ~12 sites.
>   - `core/.../internal/core/index/IndexMultiValues.java` — ~8 sites.
>   - `core/.../internal/core/index/IndexOneValue.java` — ~8 sites.
>   - `core/.../internal/core/index/IndexManagerEmbedded.java` —
>     ~4 sites.
>   - `core/.../internal/core/storage/collection/v2/PaginatedCollectionV2.java`
>     — 1 site.
>   - `core/.../internal/core/storage/disk/DiskStorage.java` — 1 site.
> - Concurrent-reader integration tests — one per subsystem: read
>   under concurrent writer's `put`. Asserts readers do not block the
>   writer's exclusive-latch acquisition at component-op commit.

> **How**:
> - Each converted method follows the pattern already established by
>   `BTree.get()` (sbtree v3) and `SharedLinkBagBTree.findCurrentEntry()`:
>   ```java
>   return executeOptimisticStorageRead(
>       atomicOp,
>       () -> /* optimistic: loadPageOptimistic + read + maybe
>                validateLastOrThrow on indirect-pointer reads */,
>       () -> /* pinned fallback: existing shared-lock code, unchanged */);
>   ```
> - Recommended step order:
>   - R1 — `IndexAbstract`: ~12 sites.
>   - R2 — `IndexMultiValues`: ~8 sites.
>   - R3 — `IndexOneValue`: ~8 sites.
>   - R4 — `IndexManagerEmbedded` + minor: ~4 sites in
>     `IndexManagerEmbedded` plus single sites in
>     `PaginatedCollectionV2` and `DiskStorage`.
>   - R5 — Concurrent-reader integration tests.
> - The optimistic lambdas must:
>   - Use `StorageComponent.loadPageOptimistic` on every page load.
>   - Call `scope.validateLastOrThrow()` after following indirect
>     pointers.
>   - Not catch `OptimisticReadFailedException` internally.

> **Constraints**:
> - In scope: the six files listed above.
> - Out of scope:
>   - `acquireSharedLock` sites inside
>     `StorageComponent.executeOptimisticStorageRead`'s pinned-fallback
>     path.
>   - Cache-internal `acquireSharedLock` implementations.
>   - Existing converted reads in `BTree`, `SharedLinkBagBTree`,
>     `FreeSpaceMap`.
> - No new exception type, no runtime assertion, no package-visibility
>   changes (D14).
> - Commit semantics are unchanged; this track is a pure refactor.

> **Interactions**:
> - **Track D** is downstream — D's stamp-validation-at-commit
>   produces "short-term latches only" only when readers are in
>   optimistic mode.
> - **Independent of all other tracks** — parallelizable with A, L, V,
>   C, H.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
