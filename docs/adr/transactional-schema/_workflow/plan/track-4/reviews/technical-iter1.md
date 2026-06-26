<!-- MANIFEST
findings: 3   severity: {blocker: 1, should-fix: 1, suggestion: 1}
index:
  - {id: T1, sev: blocker,    loc: "AbstractStorage.java lockIndexes/getIndexEngine + IndexAbstract.java:930", anchor: "### T1 ", cert: "Edge case: index-lock under write lock", basis: "lockIndexes->acquireAtomicExclusiveLock->getIndexEngine re-acquires stateLock.readLock under the held writeLock; ScalableRWLock.sharedLock spins forever on isWriteLocked -> commit self-deadlocks whenever the tx has index ops"}
  - {id: T2, sev: should-fix, loc: "track-4.md D3 / Plan of Work step 4; AbstractStorage.lockIndexes", anchor: "### T2 ", cert: "Premise: lockIndexes resolves engines by id and throws on missing", basis: "D3 rationale mischaracterizes lockIndexes (it locks tx Index objects, the throw-on-missing is the InvalidIndexEngineIdException retry inside acquireAtomicExclusiveLock); the load-bearing hazard is read-lock re-entry, not a missing-engine throw"}
  - {id: T3, sev: suggestion, loc: "track-4.md D19; YTDBGraphImplAbstract.createVertexWithClass, SQLMatchStatement.getLowerSubclass", anchor: "### T3 ", cert: "Integration: two lock-based read sites convert to snapshot-first", basis: "64 AbstractStorage methods take stateLock.readLock; decomposition should confirm only the two named SchemaShared read sites stall behind the write lock and that no other commit-reachable read re-enters"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 11}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: P10, verdict: CONFIRMED, anchor: "#### P10 "}
  - {id: P11, verdict: PARTIAL, anchor: "#### P11 "}
  - {id: E1, verdict: WRONG, anchor: "#### E1 "}
  - {id: I1, verdict: MATCHES, anchor: "#### I1 "}
  - {id: I2, verdict: CALLERS AT RISK, anchor: "#### I2 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [blocker]
**Certificate**: Edge case E1 (index-lock acquisition under the held write lock), resting on Premise P9 and Integration I2.
**Location**: Track 4 `## Plan of Work` step 1 + step 4 (the write-lock-from-start branch and the reconcile-then-`lockIndexes` ordering); `AbstractStorage.commit` → `lockIndexes(SortedMap, AtomicOperation)`; `IndexAbstract.acquireAtomicExclusiveLock` (`core/.../index/IndexAbstract.java:930`); `AbstractStorage.getIndexEngine(int)`; `ScalableRWLock.sharedLock` (`core/.../common/concur/lock/ScalableRWLock.java:309`).

**Issue**: Under D19 the commit holds `stateLock.writeLock()` from the start whenever the tx carries schema **or index** changes. But the commit's existing index path re-enters the read lock:

```
commit() [holds stateLock.writeLock()]
  -> lockIndexes(indexOperations, atomicOperation)
       -> changes.getIndex().acquireAtomicExclusiveLock(atomicOperation)   // IndexAbstract
            -> storage.getIndexEngine(indexId)                              // AbstractStorage
                 -> stateLock.readLock().lock()                            // == sharedLock()
```

`ScalableRWLock` is non-reentrant, and `sharedLock()` does **not** special-case the current thread holding the write lock — it tests the global flag `stampedLock.isWriteLocked()`:

```java
currentReadersState.set(SRWL_STATE_READING);
if (!stampedLock.isWriteLocked()) { return; }      // not reached: writer is held
else {
  currentReadersState.lazySet(SRWL_STATE_NOT_READING);
  while (stampedLock.isWriteLocked()) { Thread.yield(); }   // spins forever
}
```

The commit thread is the writer, so `isWriteLocked()` stays `true` for as long as the thread is in this spin — and it never leaves the spin to release the write lock. The result is a self-induced live-lock (a busy-spinning self-deadlock) that fires on **every** schema-or-index commit that has at least one index operation, which by D19/I-U5 is exactly the set of commits that take the write-lock branch. This is the same non-reentrant-`stateLock` hazard the design names for the public structural methods (`addCollection`, etc.), but it lives on the **already-existing** index-lock and engine-lookup path that the commit calls today and that the track does not touch. Today it is harmless only because the commit holds `readLock()` and read-on-read is fine; flipping to `writeLock()` breaks it.

The track's mitigations (extract `doAddIndexEngine`/`doDeleteIndexEngine`, reconcile through lock-free collection primitives) cover **creation** of structure, not the post-creation **lock-and-apply** of tx index entries through `getIndexEngine`. So even a schema-only commit that also has any index operation (and an index-only commit, which D19 routes to the write-lock branch by design) hits the spin.

**Proposed fix**: Make this an explicit step and Decision-Log note in Track 4. Concretely, one of:
- Add a lock-free engine-resolution path for the commit window — e.g. an internal `getIndexEngineInternal(int)` / `doGetIndexEngine(int)` that reads `indexEngines.get(id)` without taking `stateLock`, and route the commit's `lockIndexes`/index-apply through it (mirroring `doGetAndCheckCollection`, which is already lock-free and so is write-lock-safe). The public `getIndexEngine(int)` keeps its read lock for off-commit callers.
- Or have the commit resolve and lock engines before flipping to the write lock, then never call a read-lock-taking lookup inside the write-lock window.

Either way the track must state which `stateLock.readLock()`-taking methods are reachable from the commit body and confirm each is replaced by a lock-free variant under the write lock. The current `## Interfaces and Dependencies` "In scope" list names only the `doAddIndexEngine`/`doDeleteIndexEngine` extraction and the two SchemaShared read-site conversions; the `lockIndexes`/`getIndexEngine` re-entry is unlisted.

### T2 [should-fix]
**Certificate**: Premise P9 (the `lockIndexes` mechanism) — verdict WRONG against the track's paraphrase.
**Location**: Track 4 `## Decision Log` D3 ("index-engine creation must land before `lockIndexes` (which resolves engines by id and throws on a missing one)"); plan D3 mirror.

**Issue**: The rationale as written is inaccurate about what `lockIndexes` does, and the inaccuracy hides the T1 hazard. The actual `lockIndexes` body is:

```java
private static void lockIndexes(SortedMap<String, FrontendTransactionIndexChanges> indexes,
    AtomicOperation atomicOperation) {
  for (final var changes : indexes.values()) {
    changes.getIndex().acquireAtomicExclusiveLock(atomicOperation);
  }
}
```

It iterates the transaction's `FrontendTransactionIndexChanges` and locks each tx `Index` object. The "resolve by id and throw on missing" is one layer down, inside `IndexAbstract.acquireAtomicExclusiveLock`, which calls `storage.getIndexEngine(indexId)` in a `while(true)` that catches `InvalidIndexEngineIdException` and calls `doReloadIndexEngine()` to retry — it does not propagate a throw to abort the commit; a genuinely-missing engine would loop. So the ordering constraint "create the engine before `lockIndexes`" is real and correct as a *conclusion*, but the *mechanism* the D-record cites (a throw on missing) is wrong, and the truly load-bearing reason the engine must be reachable lock-free before this point is the read-lock re-entry (T1), not a missing-engine exception.

**Proposed fix**: Correct D3's risk/rationale text to describe the real mechanism: `lockIndexes` locks each tx index's engine via `IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine(int)`, so (a) the engine must be created and registered before this call, and (b) the lookup must use a lock-free engine-resolution path because `getIndexEngine(int)` re-acquires the non-reentrant `stateLock.readLock()`. Fold the lock-free-lookup requirement (T1) into the same record.

### T3 [suggestion]
**Certificate**: Integration I1 (the two read-site conversions) — verdict MATCHES, with a residual coverage caveat.
**Location**: Track 4 D19 / `## Context and Orientation` ("Two lock-based read sites remain"); `YTDBGraphImplAbstract.createVertexWithClass`, `SQLMatchStatement.getLowerSubclass`.

**Issue**: Both named sites resolve and read the schema through the lock-based path (`session.getSharedContext().getSchema().getClass(...)` / `session.getMetadata().getSchema().getClass(...)`), so converting them to snapshot-first is feasible and in scope. But the "only two remaining" claim is a global property of the read path, and 64 `AbstractStorage` methods take `stateLock.readLock()`. The hazard the conversion addresses (a lock-based schema read stalling behind the commit's write lock) is about `SchemaShared.lock`-based reads, not `stateLock`, so the count above is not directly comparable — yet the same diligence applies: any schema read reachable while a peer commit holds the write lock that is *not* snapshot-first will stall for the commit's duration.

**Proposed fix**: During decomposition, add a short verification step (or a test) that enumerates the schema reads that take `SchemaShared.lock.readLock()` on a hot path and confirms only the two named sites remain non-snapshot, so the D19 "two remaining" premise is checked rather than assumed. No plan change if the enumeration holds.

## Evidence base

#### P1 Premise: AbstractStorage exists at the named FQN
- **Track claim**: the track centers on `AbstractStorage.commit`.
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName("AbstractStorage")`.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage` @ `core/.../storage/impl/local/AbstractStorage.java`.
- **Actual behavior**: single class resolves at the expected package.
- **Verdict**: CONFIRMED
- **Detail**: —

#### P2 Premise: the named support classes resolve (ChangeableRecordId, SchemaShared, MetadataWriteMutex, YTDBGraphImplAbstract, SQLMatchStatement)
- **Track claim**: the track references these production classes by name.
- **Search performed**: PSI `getClassesByName` for each short name.
- **Code location**: `ChangeableRecordId` @ `.../id/ChangeableRecordId.java`; `SchemaShared` @ `.../metadata/schema/SchemaShared.java`; `MetadataWriteMutex` @ `.../db/MetadataWriteMutex.java`; `YTDBGraphImplAbstract` @ `.../gremlin/YTDBGraphImplAbstract.java`; `SQLMatchStatement` @ `.../sql/parser/SQLMatchStatement.java`.
- **Actual behavior**: each resolves to exactly one class at the reconstructed FQN.
- **Verdict**: CONFIRMED
- **Detail**: `MetadataWriteMutex` is a Track-3-introduced class; it now exists, consistent with the prior-episode hand-off.

#### P3 Premise: the lock-free inner collection primitives exist and are lock-free
- **Track claim**: reconciliation calls `doAddCollection` / `dropCollectionInternal` (existing for collections).
- **Search performed**: PSI `findMethodsByName` on `AbstractStorage`; body text scan for `stateLock`.
- **Code location**: `doAddCollection(AtomicOperation, String)` and `doAddCollection(AtomicOperation, String, int)`; `dropCollectionInternal(AtomicOperation, int)`.
- **Actual behavior**: both are `private`, neither body contains `stateLock`. `dropCollectionInternal` mutates `collections`, `collectionMap` directly.
- **Verdict**: CONFIRMED
- **Detail**: —

#### P4 Premise: doAddIndexEngine / doDeleteIndexEngine do NOT yet exist (planned extraction)
- **Track claim**: "engines need `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation, …)` extracted from the inlined public bodies."
- **Search performed**: PSI `findMethodsByName("doAddIndexEngine")` / `("doDeleteIndexEngine")` on `AbstractStorage`.
- **Code location**: NOT FOUND (count 0 for both).
- **Actual behavior**: neither method exists today.
- **Verdict**: CONFIRMED
- **Detail**: planned-new by this track per its `## Interfaces and Dependencies` "Signatures"; absence is expected, not a blocker.

#### P5 Premise: the public addIndexEngine / deleteIndexEngine take stateLock.writeLock (the extraction source)
- **Track claim**: the public engine methods take the write lock, so a lock-free body must be extracted for the commit window.
- **Search performed**: PSI body dump of `addIndexEngine(IndexMetadata, Map)` and `deleteIndexEngine(int)`.
- **Code location**: `addIndexEngine` and `deleteIndexEngine` in `AbstractStorage`.
- **Actual behavior**: both call `stateLock.writeLock().lock()` then run inside `calculateInsideAtomicOperation` / `executeInsideAtomicOperation`.
- **Verdict**: CONFIRMED
- **Detail**: confirms a straight call from the commit (already holding the write lock) would re-enter `writeLock` — itself reentrant on `ScalableRWLock` only for the writer, but the extraction is still required to avoid the nested atomic-operation/lock semantics; the read-lock re-entry on the lookup side is the live hazard (T1).

#### P6 Premise: commit today takes stateLock.readLock (D19 baseline)
- **Track claim**: "`AbstractStorage.commit` today takes `stateLock.readLock()`."
- **Search performed**: PSI body scan of `commit(FrontendTransactionImpl, boolean)` for `readLock`/`writeLock`.
- **Code location**: `commit(FrontendTransactionImpl, boolean)` — `stateLock.readLock().lock()` in the try, `stateLock.readLock().unlock()` in the finally; zero `writeLock` occurrences.
- **Actual behavior**: matches the claim exactly.
- **Verdict**: CONFIRMED
- **Detail**: the read-lock-from-start baseline is what makes today's index-lookup re-entry harmless and what T1 breaks.

#### P7 Premise: lockIndexes runs before the record-position allocation loop inside the commit lock (D3 ordering)
- **Track claim**: "engines before `lockIndexes`, collections before the record-position-allocation loop."
- **Search performed**: PSI body region scan after `lockIndexes(indexOperations, ...)`.
- **Code location**: commit body — `lockIndexes(indexOperations, atomicOperation)` is immediately followed by the `for (newRecords)` loop that calls `doGetAndCheckCollection(collectionId)` then `collection.allocatePosition(...)`.
- **Actual behavior**: ordering is as the track states; both sit inside the single lock window.
- **Verdict**: CONFIRMED
- **Detail**: the ordering conclusion (reconcile before these) is correct; see T2 for the mechanism mischaracterization.

#### P8 Premise: doGetAndCheckCollection is lock-free (collection allocation is write-lock-safe)
- **Track claim**: implicit — the collection-creation/allocation path runs under the held write lock.
- **Search performed**: PSI body of `doGetAndCheckCollection(int)`.
- **Code location**: `doGetAndCheckCollection` — `collections.get(collectionId)` plus a range check; no `stateLock`.
- **Actual behavior**: lock-free array access.
- **Verdict**: CONFIRMED
- **Detail**: the collection side of reconciliation is write-lock-safe; the hazard is confined to the index side (T1).

#### P9 Premise: lockIndexes resolves engines by id and throws on a missing one (D3 wording)
- **Track claim**: D3 — "`lockIndexes` (which resolves engines by id and throws on a missing one)."
- **Search performed**: PSI body of `lockIndexes`; body of `IndexAbstract.acquireAtomicExclusiveLock`; body of `AbstractStorage.getIndexEngine(int)`.
- **Code location**: `lockIndexes` iterates `FrontendTransactionIndexChanges` and calls `changes.getIndex().acquireAtomicExclusiveLock(atomicOperation)`. `IndexAbstract.acquireAtomicExclusiveLock` (`IndexAbstract.java:930`) calls `storage.getIndexEngine(indexId)` in a `while(true)` catching `InvalidIndexEngineIdException` and retrying via `doReloadIndexEngine()`. `getIndexEngine(int)` takes `stateLock.readLock().lock()`.
- **Actual behavior**: `lockIndexes` locks tx `Index` objects, not a by-id storage lookup; a missing engine triggers a reload-retry loop, not a commit-aborting throw; the engine lookup re-acquires `stateLock.readLock()`.
- **Verdict**: WRONG
- **Detail**: drives T2 (correct the rationale) and is the lookup half of T1.

#### P10 Premise: forceSnapshot exists on SchemaShared (promotion)
- **Track claim**: promotion fires "one trailing `forceSnapshot`."
- **Search performed**: PSI `getMethodsByName("forceSnapshot")`.
- **Code location**: `SchemaShared#forceSnapshot()` — the only `forceSnapshot` in the project.
- **Actual behavior**: exists on `SchemaShared`; `SchemaShared.lock` is a (reentrant) `ReentrantReadWriteLock` and `snapshotLock` is a `ReentrantLock`.
- **Verdict**: CONFIRMED
- **Detail**: the second of the four locks (`SchemaShared.lock`) is reentrant, so promotion under it nesting `forceSnapshot` is safe — distinct from the non-reentrant `stateLock` that drives T1.

#### P11 Premise: the collectionId < 0 (not == -1) convention is multi-site (D2 "11+ places")
- **Track claim**: "the schema layer tests `collectionId < 0` (not `== -1`) in 11+ places."
- **Search performed**: grep `collectionId\s*<\s*0` over `core/src/main/java` excluding tests and the generated parser (reference-accuracy caveat: textual grep, not PSI — appropriate for a literal-pattern count).
- **Code location**: 20 matches across 8 files (`SchemaEmbedded`, `SchemaShared`, `SchemaClassImpl`, `DatabaseSessionEmbedded`, `RecordIdInternal`, `DeleteEdgeExecutionPlanner`, `AbstractStorage`, `CellBTreeSingleValueBucketV3`); 7 of them inside the `metadata/schema` package.
- **Actual behavior**: the `< 0` convention is real and broadly distributed — above the "11+" floor the design cites.
- **Verdict**: PARTIAL
- **Detail**: PARTIAL only because the count is a floor in the design and the predicate-split scope (D2/D9: distinguish `-1` from `<= -2`) must cover the in-memory-map sites specifically; the claim is directionally CONFIRMED and not a finding.

#### E1 Edge case: a schema-or-index commit takes the write lock then calls into the index-lock path
- **Trigger**: any committing transaction whose unified schema-or-index signal selects the D19 write-lock branch and that carries at least one index operation (includes an index-only tx, which D19 routes to the write-lock branch by design).
- **Code path trace**:
  1. Entry: `commit(FrontendTransactionImpl, boolean)` takes `stateLock.writeLock()` (post-D19) @ `AbstractStorage`.
  2. `lockIndexes(indexOperations, atomicOperation)` @ `AbstractStorage` — iterates tx index changes.
  3. `IndexAbstract.acquireAtomicExclusiveLock(atomicOperation)` @ `IndexAbstract.java:930` — calls `storage.getIndexEngine(indexId)`.
  4. `AbstractStorage.getIndexEngine(int)` — `stateLock.readLock().lock()` == `ScalableRWLock.sharedLock()`.
  5. `ScalableRWLock.sharedLock()` @ line 309 — sets `READING`, sees `stampedLock.isWriteLocked() == true` (the same thread's writer), backs off to `NOT_READING`, then `while (stampedLock.isWriteLocked()) Thread.yield()` — spins forever; the writer is the spinning thread and never releases.
- **Outcome**: self-induced live-lock (busy-spinning self-deadlock); the commit never completes. No exception, no partial state — the thread hangs.
- **Track coverage**: no. The track addresses the structural-creation primitives but not the existing index-lock/engine-lookup read-lock re-entry inside the write-lock window.

#### I1 Integration: convert the two lock-based read sites to snapshot-first
- **Plan claim**: D19 — convert `YTDBGraphImplAbstract.createVertexWithClass` and `SQLMatchStatement.getLowerSubclass` to snapshot-first so the write lock is not a read outage.
- **Actual entry point**: `createVertexWithClass(DatabaseSessionEmbedded, String)` reads `session.getSharedContext().getSchema().getClass(label)`; `getLowerSubclass(DatabaseSessionEmbedded, String, String)` reads `session.getMetadata().getSchema().getClass(...)`. Both go through the `SchemaProxy`/`SchemaShared` lock-based read path.
- **Caller analysis**: both are small, self-contained methods; the conversion is local. The commit already calls `makeThreadLocalSchemaSnapshot()` at entry, so a thread-local snapshot is available to read from.
- **Breaking change risk**: low — snapshot-first reads return committed state, which is what these non-tx-schema callers want.
- **Verdict**: MATCHES
- **Detail**: feasible and in scope; see T3 for the residual "only two remaining" coverage caveat.

#### I2 Integration: getIndexEngine(int) and its production callers on the commit path
- **Plan claim**: reconciliation runs under the held write lock via lock-free inner primitives (D3).
- **Actual entry point**: `AbstractStorage.getIndexEngine(int)` (read-lock-taking) is the by-id engine resolver.
- **Caller analysis**: PSI find-usages — production callers are `IndexAbstract.acquireAtomicExclusiveLock` (`:930`), `IndexAbstract.getStatistics`/`getHistogram`/`analyzeHistogram`/`setBulkLoading`/`buildHistogramAfterFill` (the rest are tests). `acquireAtomicExclusiveLock` is reached unconditionally from the commit's `lockIndexes` for every tx index operation. Separately, 64 `AbstractStorage` methods take `stateLock.readLock()` (`callIndexEngine` among them, though its only production caller is `IndexAbstract.onIndexEngineChange`).
- **Breaking change risk**: CALLERS AT RISK — under the write lock, `lockIndexes` → `acquireAtomicExclusiveLock` → `getIndexEngine` self-deadlocks (T1). The lookup path is the one the track's lock-free-primitive plan does not cover.
- **Verdict**: CALLERS AT RISK
- **Detail**: drives T1.
