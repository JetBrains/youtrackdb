<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: T1, sev: suggestion, loc: IndexManagerEmbedded.java:114, anchor: "### T1 ", cert: "Edge case: de-guarded membership site nested in an open user tx", basis: "executeInTxInternal is reentrant (count-down, no real commit) when nested; de-guarding alone leaves the eager shared-Index apply, not a self-commit escape — clarify the rework target"}
  - {id: T2, sev: suggestion, loc: DatabaseSessionEmbedded.java:3145, anchor: "### T2 ", cert: "Integration: normal mutex release in the outermost teardown finally", basis: "no single existing outermost teardown finally — commitImpl has no top-level finally and rollback() is a separate path; pin the concrete release site at decomposition"}
evidence_base: {section: "## Evidence base", certs: 17, matches: 17}
cert_index:
  - {id: "Premise: SchemaProxy resolves", verdict: CONFIRMED, anchor: "#### Premise: SchemaProxy "}
  - {id: "Premise: SchemaClassProxy resolves", verdict: CONFIRMED, anchor: "#### Premise: SchemaClassProxy "}
  - {id: "Premise: SchemaPropertyProxy resolves", verdict: CONFIRMED, anchor: "#### Premise: SchemaPropertyProxy "}
  - {id: "Premise: SchemaShared resolves", verdict: CONFIRMED, anchor: "#### Premise: SchemaShared "}
  - {id: "Premise: SchemaClassImpl resolves", verdict: CONFIRMED, anchor: "#### Premise: SchemaClassImpl "}
  - {id: "Premise: IndexManagerEmbedded resolves", verdict: CONFIRMED, anchor: "#### Premise: IndexManagerEmbedded "}
  - {id: "Premise: ClassIndexManager resolves", verdict: CONFIRMED, anchor: "#### Premise: ClassIndexManager "}
  - {id: "Premise: TxSchemaState / MetadataWriteMutex planned-new", verdict: CONFIRMED, anchor: "#### Premise: TxSchemaState "}
  - {id: "Premise: fromStream/toStream re-parse seed feasible", verdict: CONFIRMED, anchor: "#### Premise: fromStream "}
  - {id: "Premise: SchemaClassImpl.owner is final", verdict: CONFIRMED, anchor: "#### Premise: SchemaClassImpl.owner "}
  - {id: "Premise: per-class recordId field present (Track 2)", verdict: CONFIRMED, anchor: "#### Premise: per-class recordId "}
  - {id: "Premise: schema-record save throw-guard + self-commit", verdict: CONFIRMED, anchor: "#### Premise: schema-record save "}
  - {id: "Premise: dropClass/dropClassInternal throw-guards", verdict: CONFIRMED, anchor: "#### Premise: dropClass "}
  - {id: "Premise: index-manager createIndex/dropIndex throw-guards + membership self-commit", verdict: CONFIRMED, anchor: "#### Premise: createIndex "}
  - {id: "Premise: captured delegate on the proxies (tier-2/tier-3)", verdict: CONFIRMED, anchor: "#### Premise: captured delegate "}
  - {id: "Integration: mutex engage above the shared metadata locks", verdict: MATCHES, anchor: "#### Integration: mutex engage "}
  - {id: "Edge case: I-A7 membership ripple reaches addCollectionToIndex", verdict: CONFIRMED, anchor: "#### Edge case: I-A7 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [suggestion]
**Certificate**: Edge case: de-guarded membership site nested in an open user tx
**Location**: Track file `## Context and Orientation` (the self-commit paragraph) and `## Plan of Work` (the de-guard step); `IndexManagerEmbedded.addCollectionToIndex` @ `core/.../index/IndexManagerEmbedded.java:114`, `removeCollectionFromIndex` @ `:144`; `DatabaseSessionEmbedded.executeInTxInternal` @ `:3494` / `begin` @ `:1344` / `commitImpl` @ `:3162`.
**Issue**: The track's prose says the self-commit "escapes the user transaction, becomes visible to other sessions, and survives a rollback." That is precisely true on today's storage-leads path, where `createClass` runs with **no** user transaction open: the `executeInTxInternal` at line 114 then opens its own top-level transaction and the membership change is durable the instant the closure returns (`createClassInternal` calls `addCollectionToIndex` at line 199-202 while holding the schema write lock, before `releaseSchemaWriteLock`→`saveInternal`'s active-tx throw even fires). But the de-guarded model runs these sites **inside** an open user transaction, and `executeInTxInternal` is reentrant: `begin()` (line 1349-1352) calls `currentTx.beginInternal()` (count-up) when a tx is already active, and the matching `commit()` sees `amountOfNestedTxs() > 1` (line 3162-3165) and does a count-down only, no real commit. So once de-guarded, the residual hazard is not a nested-commit escape — it is that `index.addCollection(transaction, ...)` (line 123) still applies the membership eagerly into the **shared** `Index`'s `collectionsToIndex` (and against an as-yet-provisional collection), which the rollback test in `## Validation` line 132-135 guards. An implementer who reads the prose literally might assume that simply removing the throw-guards and letting these sites ride the now-reentrant `executeInTxInternal` is sufficient, leaving the eager shared apply in place and silently failing the rollback assertion.
**Proposed fix**: In the `## Context and Orientation` self-commit paragraph and the `## Plan of Work` de-guard sentence, state that the rework replaces the `executeInTxInternal` body itself (route the membership change into the `TxSchemaState` changed-class set / index overlay), not merely strips the throw-guard — the reentrancy means the nested commit is already harmless, but the eager `index.addCollection` into the shared registry is the leak the track is defending against. No Decision Record change; this is a precision edit to the track narrative so step decomposition targets the right site.

### T2 [suggestion]
**Certificate**: Integration: normal mutex release in the outermost teardown finally
**Location**: Track file `## Plan of Work` ("release it in the outermost commit/rollback teardown `finally`") and `## Interfaces and Dependencies` ("normal-release wiring in the session teardown"); `DatabaseSessionEmbedded.commitImpl` @ `:3145`, `rollback` @ `:3253`, `executeInTxInternal`'s `finally { finishTx(ok); }` @ `:3507`.
**Issue**: The track names "the outermost commit/rollback teardown `finally`" as a single release site, but no such single site exists today. `commitImpl` (line 3145) has no top-level `finally` — its success path returns through `currentTx.commitInternal()` (line 3185) and its catch arm rethrows after a best-effort rollback (line 3213); `rollback()` (line 3253) is a separate method that only calls `currentTx.rollbackInternal()`. A user driving the transaction with explicit `begin()`/`commit()` does not pass through `executeInTxInternal`'s `finally { finishTx(ok) }` (line 3507) at all. The nesting guard further means the release must fire only when the **outermost** frame tears down (`amountOfNestedTxs()` returns to its base), not on every nested `commitInternal()` count-down. Track 7 owns the abnormal-termination handshake, but Track 3 ships the normal release, so the concrete site must be chosen here.
**Proposed fix**: During decomposition, pin the release to a site that covers both the explicit `commit()`/`rollback()` paths and the `executeInTx*` wrappers, gated on the outermost-frame condition (mutex owner-session match plus the tx fully closing), rather than the literal phrase "outermost teardown finally". A natural anchor is the point where `commitImpl`/`rollback` observe the transaction transitioning out of active at the base nesting level. Add a one-line note to `## Idempotence and Recovery` (currently a placeholder) that the normal release is idempotent against the Track 7 compare-and-clear so the two releasers never double-release. No Decision Record change.

## Evidence base

#### Premise: SchemaProxy resolves
- **Track claim**: `SchemaProxy` is the read/write routing seam (`## Purpose`, `## Plan of Work`, `## Interfaces`).
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName("SchemaProxy", allScope)`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxy.java`
- **Actual behavior**: `public final class SchemaProxy extends ProxedResource<SchemaShared> implements SchemaInternal`; every method delegates to the captured `delegate` (`SchemaShared`). FQN `com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProxy`.
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: SchemaClassProxy resolves
- **Track claim**: `SchemaClassProxy` holds a captured `delegate` and becomes name-binding in a schema tx.
- **Search performed**: PSI `getClassesByName("SchemaClassProxy", allScope)`.
- **Code location**: `core/.../metadata/schema/SchemaClassProxy.java`
- **Actual behavior**: extends `ProxedResource` (FQN `...metadata.schema.SchemaClassProxy`).
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: SchemaPropertyProxy resolves
- **Track claim**: `SchemaPropertyProxy` is a routed proxy handle.
- **Search performed**: PSI `getClassesByName("SchemaPropertyProxy", allScope)`.
- **Code location**: `core/.../metadata/schema/SchemaPropertyProxy.java`
- **Actual behavior**: extends `ProxedResource` (FQN `...metadata.schema.SchemaPropertyProxy`).
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: SchemaShared resolves
- **Track claim**: tx-local copy is a `SchemaShared` seeded by `copyForTx`/`fromStream`; shared `SchemaShared` mutated at commit.
- **Search performed**: PSI `getClassesByName("SchemaShared", allScope)`.
- **Code location**: `core/.../metadata/schema/SchemaShared.java`
- **Actual behavior**: `public abstract class SchemaShared implements CloseableInStorage`; sole concrete subclass `SchemaEmbedded` (PSI `ClassInheritorsSearch`). FQN `...metadata.schema.SchemaShared`.
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: SchemaClassImpl resolves
- **Track claim**: `SchemaClassImpl` has a record-RID field (Track 2) and a `final owner`.
- **Search performed**: PSI `getClassesByName("SchemaClassImpl", allScope)` + field enumeration.
- **Code location**: `core/.../metadata/schema/SchemaClassImpl.java`
- **Actual behavior**: FQN `...metadata.schema.SchemaClassImpl`; field list inspected (see the `owner` and `recordId` premises).
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: IndexManagerEmbedded resolves
- **Track claim**: index-manager `createIndex`/`dropIndex` throw-guards and `addCollectionToIndex`/`removeCollectionFromIndex` self-commit sites.
- **Search performed**: PSI `getClassesByName("IndexManagerEmbedded", allScope)`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java`
- **Actual behavior**: FQN `...core.index.IndexManagerEmbedded`.
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: ClassIndexManager resolves
- **Track claim**: named in the plan's Component Map / scope as a routing-seam-adjacent class (Track 5 owns the seam; Track 3 names it for context).
- **Search performed**: PSI `getClassesByName("ClassIndexManager", allScope)`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/ClassIndexManager.java`
- **Actual behavior**: FQN `...core.index.ClassIndexManager`.
- **Verdict**: CONFIRMED
- **Detail**: ClassIndexManager is named in the plan scope row, not modified by Track 3; existence confirmed for completeness.

#### Premise: TxSchemaState / MetadataWriteMutex are planned-new
- **Track claim**: "the new `TxSchemaState`" and "the new `MetadataWriteMutex` primitive" (`## Interfaces and Dependencies`, `## Plan of Work`) — this track introduces both.
- **Search performed**: PSI `getClassesByName("TxSchemaState", allScope)` and `getClassesByName("MetadataWriteMutex", allScope)`.
- **Code location**: NOT FOUND (both)
- **Actual behavior**: neither class exists; the track explicitly creates them.
- **Verdict**: CONFIRMED
- **Detail**: planned by this track — NOT-FOUND is expected and correct under the planned-class rule.

#### Premise: fromStream/toStream re-parse seed is feasible
- **Track claim**: seed the tx-local copy by a `fromStream` re-parse (D8); `SchemaShared.copyForTx() : SchemaShared` (`## Signatures`).
- **Search performed**: PSI method enumeration on `SchemaShared`; read `fromStream`/`toStream` bodies.
- **Code location**: `SchemaShared.java:492` (`fromStream(DatabaseSessionEmbedded, EntityImpl)`), `:686` (`toStream(DatabaseSessionEmbedded) : EntityImpl`); `copyForTx` NOT FOUND (planned).
- **Actual behavior**: `fromStream` parses the per-class link-set root record into `classes`; `toStream` writes per-class standalone records. Both exist; `copyForTx` (the seed wrapper) is the planned-new method. `fromStream` rejects `schemaVersion != CURRENT_VERSION_NUMBER` (line 506).
- **Verdict**: CONFIRMED
- **Detail**: the re-parse path the seed needs is present; `copyForTx` will compose `toStream` + a fresh `SchemaShared` + `fromStream`.

#### Premise: SchemaClassImpl.owner is final
- **Track claim**: a field clone would leave classes pointing at the shared `owner`/siblings, so the seed must re-parse (D8).
- **Search performed**: PSI field enumeration on `SchemaClassImpl`.
- **Code location**: `SchemaClassImpl.java` field `protected final SchemaShared owner`.
- **Actual behavior**: `owner` is `final`; `superClasses` and `subclasses` are `List<SchemaClassImpl>` direct references. A field clone cannot rebind `owner` and would keep shared sibling references.
- **Verdict**: CONFIRMED
- **Detail**: confirms the design's rejection of a field-level clone and the requirement that the seed re-parse.

#### Premise: per-class recordId field present (Track 2)
- **Track claim**: "the seed binds each existing class's committed per-class record RID (D14)"; "`SchemaClassImpl` has no record-RID field today (added in Track 2)".
- **Search performed**: PSI field enumeration on `SchemaClassImpl`.
- **Code location**: `SchemaClassImpl.java` field `@Nullable protected RID recordId`.
- **Actual behavior**: nullable `recordId` field is present (Track 2 landed it); `toStream`/load bind it via the link set (`SchemaShared.toStream` lines 711-729 self-heal/bind the RID).
- **Verdict**: CONFIRMED
- **Detail**: the seed can bind RIDs faithfully, matching the Track 2 episode's forward-carried contract (c).

#### Premise: schema-record save throw-guard + self-commit
- **Track claim**: "the `SchemaShared` schema-record save … throw[s] when a transaction is active" (throw-guard) and is a self-commit site.
- **Search performed**: read `SchemaShared.saveInternal` and `releaseSchemaWriteLock`.
- **Code location**: `SchemaShared.java:896` (`saveInternal`), reached from `releaseSchemaWriteLock` (line 436-438) when `modificationCounter == 1`.
- **Actual behavior**: `saveInternal` throws `SchemaException("Cannot change the schema while a transaction is active…")` if `tx.isActive()` (lines 898-903), then `session.executeInTx(... toStream ...)` (line 905) — both a throw-guard and a self-commit. `toStream` asserts `lock.isWriteLockedByCurrentThread()` and takes no other lock (lines 690-691), matching the Track 2 write-lock-only contract.
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: dropClass / dropClassInternal throw-guards
- **Track claim**: "`dropClass` / `dropClassInternal` … throw when a transaction is active".
- **Search performed**: PSI `getMethodsByName` (located both in `SchemaEmbedded`), read bodies.
- **Code location**: `SchemaEmbedded.java:370` (`dropClass`), `:414` (`dropClassInternal`).
- **Actual behavior**: both throw `IllegalStateException("Cannot drop a class inside a transaction")` when `session.getTransactionInternal().isActive()` (lines 373-375, 417-419).
- **Verdict**: CONFIRMED
- **Detail**: the exception type is `IllegalStateException` (not `SchemaException` as for the schema-record save); the track only claims "throw", which holds. No finding — informational.

#### Premise: createIndex / dropIndex throw-guards + membership self-commit
- **Track claim**: index-manager `createIndex`/`dropIndex` throw on active tx; `addCollectionToIndex`/`removeCollectionFromIndex` self-commit via `executeInTxInternal`.
- **Search performed**: grep + read `IndexManagerEmbedded` bodies.
- **Code location**: `IndexManagerEmbedded.java:306-307` (`createIndex` throw), `:458-459` (`dropIndex` throw), `:114` / `:144` (`executeInTxInternal` in `addCollectionToIndex` / `removeCollectionFromIndex`).
- **Actual behavior**: `createIndex` throws `IllegalStateException("Cannot create a new index inside a transaction")`; `dropIndex` throws `IllegalStateException("Cannot drop an index inside a transaction")`; both membership methods wrap `index.addCollection`/`removeCollection` in `session.executeInTxInternal(...)` and apply into the shared `Index`.
- **Verdict**: CONFIRMED
- **Detail**: —

#### Premise: captured delegate on the proxies (tier-2/tier-3)
- **Track claim**: each `SchemaClassProxy` holds a captured `delegate` direct reference to its `SchemaClassImpl`; tier-3 re-resolves by name in a schema tx.
- **Search performed**: PSI field enumeration on `ProxedResource`, `SchemaClassProxy`, `SchemaPropertyProxy`.
- **Code location**: `ProxedResource` fields `protected final T delegate` and `protected final DatabaseSessionEmbedded session`.
- **Actual behavior**: `SchemaClassProxy` and `SchemaPropertyProxy` both `extends ProxedResource`, inheriting the captured `delegate`. The field is `final`, so tier-3 name re-resolution must be a lookup path that bypasses `delegate` rather than re-pointing it.
- **Verdict**: CONFIRMED
- **Detail**: the `final` `delegate` shapes the tier-3 implementation (name lookup against the tx-local copy, not field mutation) — consistent with the track's name-binding model.

#### Integration: mutex engage above the shared metadata locks
- **Plan claim**: "the mutex engage must sit strictly above any shared metadata lock and before the tx-local seed" (`## Plan of Work`, D7/I-C2); mutex lives on the shared context.
- **Actual entry point**: `SchemaProxy` write methods (e.g. `createClass` @ `SchemaProxy.java:70`) call `delegate.createClass(...)` → `SchemaEmbedded.doCreateClass` → `acquireSchemaWriteLock` (`SchemaEmbedded.java:89`); the proxy layer is strictly above `acquireSchemaWriteLock`. `SharedContext` (`SharedContext.java`) holds `schema` (line 33) and `indexManager` (line 48) plus a `ReentrantLock` (line 50), the natural home for the per-storage `Semaphore(1)`.
- **Caller analysis**: PSI find-usages confirm the write-routing entry points are the proxy methods; `acquireSchemaWriteLock` is taken only inside the `SchemaEmbedded`/`SchemaShared` mutation methods, below the proxy. A mutex engaged in the proxy method body sits above the shared lock, satisfying I-C2.
- **Breaking change risk**: low — engage is additive at the proxy/index-routing layer; existing callers keep working. The same-thread loud-reject (I-C4) and the snapshot-read non-blocking property (I-A6) are structurally supported: `makeSnapshot` (`SchemaProxy.java:52`) routes to `delegate.makeSnapshot` independent of the mutex.
- **Verdict**: MATCHES

#### Edge case: I-A7 membership ripple reaches addCollectionToIndex
- **Trigger**: `createClass` / `addSuperClass` inside a transaction triggers the polymorphic collection-membership ripple.
- **Code path trace**:
  1. Entry: `SchemaEmbedded.createClassInternal(...)` @ `SchemaEmbedded.java:151` — acquires schema write lock (157).
  2. For each superclass, `IndexManagerEmbedded.addCollectionToIndex(...)` @ `:199-202` (still under the write lock).
  3. `addCollectionToIndex` @ `IndexManagerEmbedded.java:114` runs `session.executeInTxInternal(... index.addCollection ...)`.
  4. `addSuperClass` ripple: PSI find-usages — `SchemaClassEmbedded.addPolymorphicCollectionId` → `addCollectionIdToIndexes` → `addCollectionToIndex`; `removeCollectionFromIndex` ← `SchemaClassImpl.removeCollectionFromIndexes`.
  5. `releaseSchemaWriteLock` @ `:212` → (no active user tx) `saveInternal` throw-guard fires only after the membership self-commit already happened.
- **Outcome**: with no user tx, the membership change is durable immediately and survives a later rollback — the silent self-commit leak. Production callers (PSI): `SchemaClassEmbedded#addCollectionIdToIndexes`, `SchemaEmbedded#createClassInternal` (add); `SchemaClassImpl#removeCollectionFromIndexes` (remove).
- **Track coverage**: yes — `## Validation` lines 132-135 test exactly the rollback-leaves-`collectionsToIndex`-untouched assertion (I-A7). Feeds finding T1 (the de-guard must replace the eager apply, given `executeInTxInternal` reentrancy).
