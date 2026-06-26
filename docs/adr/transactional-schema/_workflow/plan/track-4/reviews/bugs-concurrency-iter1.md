<!--MANIFEST
dimension: bugs-concurrency
iteration: 1
review_target: "Track 4, Step 1 — c766f693ef~1..c766f693ef"
verdict: PASS
blocker_count: 0
finding_count: 2
evidence_base: "12 PSI/source checks; ScalableRWLock RW-lock visibility semantics + indexEngines/collections plain-collection field types confirmed; engine/engineData/indexMetadata name+id equality traced through IndexEngineData ctor and DefaultIndexFactory"
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: BC1
    sev: suggestion
    anchor: "#bc1-pre-lock-checkindexid-in-getindexengine-now-redundant-with-the-one-inside-dogetindexengine"
    loc: "core/.../AbstractStorage.java:3292,3332"
    cert: C2
    basis: "getIndexEngine validates indexId twice — once before readLock (3292), once under readLock via doGetIndexEngine->checkIndexId (3332). The under-lock check is strictly safer (closes a TOCTOU window); the pre-lock check is now redundant but harmless"
  - id: BC2
    sev: suggestion
    anchor: "#bc2-dogetindexengine-is-lock-free-only-relative-to-a-caller-held-lock-no-in-method-guard-for-the-step-2-writelock-consumer"
    loc: "core/.../AbstractStorage.java:3330-3337"
    cert: C1
    basis: "doGetIndexEngine reads a plain ArrayList with no synchronization; correctness depends entirely on the caller holding stateLock (read or write). In this step the sole caller (getIndexEngine) holds readLock (PSI: 1 usage). The writeLock-holding lock-free consumer arrives in Step 2; nothing in the method enforces the documented obligation"
-->

## Findings

### BC1 [suggestion] Pre-lock `checkIndexId` in `getIndexEngine` now redundant with the one inside `doGetIndexEngine`

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`, `getIndexEngine(int)` (line 3292) and `doGetIndexEngine(int)` (line 3332).
- **Issue**: After the extraction, `getIndexEngine` validates the id twice. It calls `checkIndexId(indexId)` once at line 3292 *before* taking `stateLock.readLock()` (carried over verbatim from the original inlined body), then delegates to `doGetIndexEngine(indexId)` at line 3299, which calls `checkIndexId(internalId)` again at line 3332 *under* the read lock. The original method checked the id only once (pre-lock) and then did a bare `indexEngines.get(indexId)` under the lock. This is not a defect — the change is strictly safer, because the original pre-lock check was a TOCTOU read: between the unlocked `checkIndexId` and the locked `indexEngines.get`, a concurrent `deleteIndexEngine` (under `writeLock`) could null the slot, and the original would then read `null` and trip the `assert engine.getId()` with an NPE on a fail-fast (`-ea`) build, or return `null` without `-ea`. Routing through `doGetIndexEngine` re-validates under the read lock and closes that window. The leftover pre-lock `checkIndexId` at 3292 is now dead weight: it reads `indexEngines` with no lock (a benign racy read whose only effect is a possibly-stale early throw), and every id it would reject is re-rejected under the lock.
- **Evidence**: code path for `getIndexEngine(id)`:
  ```
  METHOD: AbstractStorage.getIndexEngine(int)  (3288)
    indexId = extractInternalId(indexId)              // 3289
    checkIndexId(indexId)                              // 3292  -- UNLOCKED read of indexEngines (redundant)
    stateLock.readLock().lock()                        // 3294
      checkOpennessAndMigration()                      // 3297
      return doGetIndexEngine(indexId)                 // 3299
                 -> checkIndexId(internalId)           // 3332  -- LOCKED read of indexEngines (authoritative)
                 -> indexEngines.get(internalId)       // 3334  -- non-null guaranteed by 3332
                 -> assert internalId == engine.getId()// 3335
    readLock().unlock()                                // 3301
  ```
  The under-lock `checkIndexId` makes the read-then-`get`-then-`assert` sequence consistent under a single lock hold; the pre-lock one duplicates that work without the lock. No NPE on the new path: `checkIndexId` at 3332 throws `InvalidIndexEngineIdException` when the slot is null, so `engine` at 3334 is non-null when the assert runs.
- **Refutation considered**: Could the pre-lock check be load-bearing — e.g., cheaper rejection before a contended lock, or required because `doGetIndexEngine` is reachable by some path that skips it? No. PSI find-usages on `doGetIndexEngine` returns exactly one call site (`getIndexEngine`), so the only entry already runs the under-lock check; and the pre-lock check guards nothing the under-lock check does not. The behavior is preserved (every id the original rejected is still rejected, only the *when* shifts to under-lock, which is the safer order). I flag it at suggestion level, not should-fix, because it is harmless and removing it is purely a cleanup.
- **Suggestion**: Drop the pre-lock `checkIndexId(indexId)` at line 3292; rely on the one inside `doGetIndexEngine` under the read lock, matching the lock-consistent pattern the extraction otherwise establishes. Leaving it is acceptable.

### BC2 [suggestion] `doGetIndexEngine` is lock-free only relative to a caller-held lock; no in-method guard for the Step-2 writeLock consumer

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`, `doGetIndexEngine(int)` (lines 3330-3337).
- **Issue**: `doGetIndexEngine` reads `indexEngines` (a plain, non-`volatile`, non-concurrent `ArrayList` — field declared `private final List<BaseIndexEngine> indexEngines = new ArrayList<>()` at line 291) and `checkIndexId` reads it twice (`indexEngines.size()`, `indexEngines.get(id)`) with no synchronization inside the method. Its correctness therefore rests entirely on the caller holding `stateLock` — either the read lock (the public wrapper) or the write lock (the future commit window). That obligation is documented in the Javadoc but not enforced anywhere in the method. In *this* step the contract holds: PSI find-usages reports the sole caller is `getIndexEngine`, which holds `readLock` across the call. So the lock-free read is, in this commit, always performed under a lock, with the standard `ReadWriteLock` happens-before edge to any prior write under the same lock — visibility and structural integrity are sound. The risk is forward-looking: Step 2 introduces the `writeLock`-holding caller that reaches this resolver with no `stateLock`-of-its-own, and if any future caller invokes it off-lock (or under only the metadata write locks but not `stateLock`), it reads a plain `ArrayList` racing a registrar with no memory barrier and no structural-modification guard. Nothing in the method signals that misuse.
- **Evidence**: visibility/structural reasoning grounded in the field types and lock semantics:
  ```
  PREMISE: indexEngines = plain ArrayList (291), not volatile, not Collections.synchronizedList,
           not CopyOnWriteArrayList. Writers: addIndexEngine/deleteIndexEngine (writeLock),
           publishIndexEngine/setIndexEngine (from addIndexEngine, writeLock), open-time loaders.
           Per design D10/F88 the non-commit registrars (rebuild, loadExternalIndexEngine,
           recreateIndexes) run under stateLock.write alone.
  CLAIM:   A read of indexEngines is safe iff the reader holds stateLock (read or write), because
           that excludes every writer and supplies the happens-before edge for the slot/size writes.
  THIS STEP: doGetIndexEngine has exactly 1 caller (PSI), getIndexEngine, which holds readLock
           around the call (3294..3301). Safe.
  STEP 2:  the schema-carrying commit holds writeLock and calls doGetIndexEngine lock-free; the
           writeLock is exclusive, so it also excludes every writer above -> safe, BUT only because
           the commit took writeLock. The method itself provides no guard if that precondition slips.
  ```
- **Refutation considered**: Is this a real defect in the diff under review? No — in this commit the single caller always holds a lock, so the read is correct and behavior-preserving (it is the original inlined `getIndexEngine` body verbatim). I am not asserting a bug here; I am recording the load-bearing precondition so the Step-2 reviewer can confirm the commit-window caller genuinely holds `stateLock.writeLock()` for the full span of the `doGetIndexEngine` call (not released between reconciliation and the index-apply path), which is the only thing standing between this lock-free read and a data race on a plain `ArrayList`. Considered and rejected promoting to should-fix: adding an `assert stateLock.isWriteLockedByCurrentThread() || ...` is not feasible (`ScalableRWLock` exposes no cheap current-thread-holds query, and the read-or-write disjunction is awkward to assert), so a runtime guard is not a clean fix at this seam.
- **Suggestion**: No code change required for this step. Carry the precondition into Step 2's review: verify the commit holds `stateLock.writeLock()` across every `doGetIndexEngine` call it makes, and that no new caller reaches the resolver under only the metadata write locks. Optionally tighten the Javadoc to state the obligation as an invariant the caller MUST satisfy (it already does, in prose).

## Evidence base

#### C1 — `doGetIndexEngine` lock-free read is safe in this step (sole caller holds readLock) and the publication/visibility model is sound (BC2)

Refutation attempt: "The new lock-free read of the plain `ArrayList` `indexEngines` is a data race / unsafe-publication bug in this commit." REFUTED for this step. `indexEngines` is `private final List<BaseIndexEngine> indexEngines = new ArrayList<>()` (line 291) — plain, not `volatile`/concurrent — so an unsynchronized read would be a race. But PSI find-usages (`ReferencesSearch` on `transactional-schema-b4l1mcdq`) reports `doGetIndexEngine` has exactly one caller, `AbstractStorage#getIndexEngine`, which holds `stateLock.readLock()` across the call (3294-3301). A read under the read lock has the `ReadWriteLock` happens-before edge to every write performed under the matching write lock (all of `addIndexEngine`/`deleteIndexEngine`/`publishIndexEngine`/`setIndexEngine` hold `writeLock`), so both visibility and structural integrity are guaranteed. The body is the original inlined `getIndexEngine` resolution verbatim (`indexEngines.get(id)` + `assert id == engine.getId()`), now preceded by an under-lock `checkIndexId`. The writeLock-holding lock-free consumer the method is built for arrives in Step 2; it is the exclusive write lock that makes *that* read safe, and the obligation is documented but unenforced — recorded as BC2 (suggestion), a forward-looking precondition for Step 2, not a defect in this diff.

#### C2 — Double `checkIndexId` is redundant-but-safer, not a behavior break (BC1) — survived

`getIndexEngine` calls `checkIndexId` at 3292 (unlocked, redundant) and again at 3332 inside `doGetIndexEngine` (under readLock, authoritative); the original validated once pre-lock then did a bare `get` under the lock. The under-lock re-validation closes a TOCTOU window (a concurrent `deleteIndexEngine` nulling the slot between the unlocked check and the locked `get` would NPE the `assert` on `-ea`); every id the original rejected is still rejected. Confirmed behavior-preserving with a strictly-safer ordering; suggestion-level cleanup only.

#### C3 — `addIndexEngine` / `deleteIndexEngine` extraction is behavior-preserving including the registry/WAL reorder — survived

Refutation attempt: "The create/publish split changes what `addIndexEngine` registers (name or id), or the reorder of in-memory publication after the WAL config write leaves a phantom registration or a resource leak on partial failure." REFUTED. (1) Name equality: `publishIndexEngine` uses `engine.getName()` and `configuration.addIndexEngine` uses `engineData.getName()`; the original used `indexMetadata.getName()` for both. `IndexEngineData`'s `IndexMetadata`-ctor sets `this.name = metadata.getName()` (IndexEngineData.java:53), and `DefaultIndexFactory.createIndexEngine` constructs the engine with `data.getName()`/`data.getIndexId()` (DefaultIndexFactory.java:137-142), so `engine.getName() == engineData.getName() == indexMetadata.getName()` and `engine.getId() == engineData.getIndexId()`. (2) Id slot: public path allocates `generatedId = indexEngines.size()`, and `setIndexEngine(size, engine)` takes the `size <= id` branch with a no-op `while` and `add(engine)` — identical to the original `indexEngines.add(engine)`. (3) Reorder: in-memory publication now trails `configuration.addIndexEngine` (the WAL config write), whereas the original published first. Safe because the whole op runs under `writeLock` (no concurrent observer of the intermediate state), `configuration.addIndexEngine` does not read back `indexEngineNameMap`/`indexEngines`, and any throw propagates out of `calculateInsideAtomicOperation` rolling back the buffered file creates — so no durable phantom and no new leak (the engine Java object is GC'd, identical to the original throw path). `deleteIndexEngine` is a pure two-line extraction (`engine.delete` + `configuration.deleteIndexEngine(engine.getName())`) with the in-memory map mutation still deferred to after the atomic op (3116-3117), byte-for-byte the original sequence.

#### C4 — Collection create/publish split is behavior-preserving including the `registerCollection` reorder — survived

Refutation attempt: "`doCreateCollection` passes the wrong id to `createComponent`/`updateCollection`, or moving `registerCollection` to last changes the duplicate-name guard or breaks a dependency on the in-memory registry." REFUTED. The original `doAddCollection` computed `createdCollectionId = registerCollection(collection)` and passed it to `createComponent`; `registerCollection` returns `collection.getId()`, and the collection was configured via `collection.configure(collectionPos, …)`, so `createdCollectionId == collectionPos`. The new `doCreateCollection` passes `collectionPos` directly to both `createComponent` and (via `generateCollectionConfig()`, id = `collectionPos`) `updateCollection` — same value. Neither callee reads back `AbstractStorage.collections`/`collectionMap`: `LinkCollectionsBTreeManagerShared.createComponent` only creates a BTree file and puts into its own `fileIdBTreeMap` (LinkCollectionsBTreeManagerShared.java:90-98); `CollectionBasedStorageConfiguration.updateCollection` only mutates its config cache by `config.id()` and WAL-stores (CollectionBasedStorageConfiguration.java:1363-1390). So moving the in-memory `registerCollection` to after both WAL writes is safe. The `registerCollection` internal duplicate-name guard now runs after the WAL writes, but both public `addCollection` overloads pre-check `collectionMap.containsKey(...)` before entering the atomic op (lines 1446, 1492) while holding `writeLock` (so `collectionMap` cannot change underneath), making the inner guard unreachable on the public path; were it ever to throw, the atomic op rolls back. The `null`-name no-op (legacy `-1` return) is preserved: `doCreateCollection` returns `null` for a null name and `doAddCollection` maps that to `-1` (5108-5110). The case-sensitivity gap between the raw-name pre-check and the lower-cased inner check is pre-existing, not introduced here.
