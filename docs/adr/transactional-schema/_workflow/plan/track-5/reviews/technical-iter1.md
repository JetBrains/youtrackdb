<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: FetchFromClassExecutionStep.java:106, anchor: "### T1 ", cert: "Edge case: same-tx query against a tx-created (provisional-collection) class", basis: "provisional collection id enters the scan set and fails downstream collection resolution; the track's fix names only the index planner, not the fetch-step collection-scan guard"}
  - {id: T2, sev: suggestion, loc: ClassIndexManager.java:51, anchor: "### T2 ", cert: "Premise: ClassIndexManager reads a cached index set materialized at snapshot init", basis: "the cached indexes set lives on SchemaImmutableClass, not ClassIndexManager; the static ClassIndexManager reads it via cls.getRawIndexes()"}
  - {id: T3, sev: suggestion, loc: IndexManagerEmbedded.java:586, anchor: "### T3 ", cert: "Premise: SchemaProxy.makeSnapshot committed-only read + drop-side comment wording", basis: "minor citation drift: makeSnapshot at :75 not :78; dropIndex comment already attributes drop to 'a later track' not Track 4; IndexException message has trailing period"}
evidence_base: {section: "## Evidence base", certs: 16, matches: 14}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: PARTIAL,   anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CONFIRMED, anchor: "#### C12 "}
  - {id: C13, verdict: PARTIAL,   anchor: "#### C13 "}
  - {id: C14, verdict: CONFIRMED, anchor: "#### C14 "}
  - {id: C15, verdict: CONFIRMED, anchor: "#### C15 "}
  - {id: C16, verdict: CONFIRMED, anchor: "#### C16 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Edge case: same-tx query against a tx-created (provisional-collection) class (C13)
**Location**: track-5.md `## Plan of Work` (D21 Risk 2 fold, "extend the planner's skip-unbuilt treatment") and `## Interfaces and Dependencies` Signatures ("the planner guard excluding `getIndexId() < 0`"); source `FetchFromClassExecutionStep.java:105-120` and `:131-139`.
**Issue**: The track frames the "query against a tx-created class in the same tx" gap (D21 Risk 2, and the D13 extension) as an *index-planner* problem — its In-scope / Signatures name only "the planner skip-unbuilt-index guard (`getIndexesInternal`)" and "excluding `getIndexId() < 0` engines". But the real break for a tx-created class is not in index selection; it is in the collection-scan setup. Traced:
- Once D21 makes the snapshot tx-aware, `FetchFromClassExecutionStep.loadClassFromSchema` (`:131`) resolves the tx-created class (today it throws `CommandExecutionException("Class ... not found")` because the class is not in the committed snapshot — D21 changes this to succeed).
- The ctor then reads `clazz.getPolymorphicCollectionIds()` (`:106`), and for each id calls `getCollectionNameById(collectionId)` (`:110`). For a provisional id (`<= -2`) this returns null (`DatabaseSessionEmbedded.getCollectionNameById` returns null for `collectionId < 0`, `:2842`).
- At `:111` the filter is `if (collections == null || collections.contains(collectionName))`. In the common scan-all case `collections == null`, so the **provisional id is added to `filteredClassCollections`** (`:112`) despite naming no real collection; `collectionIds` (`:120`) then carries it into `RecordIteratorCollections` at `internalStart` (`:148`), which will try to iterate a collection id that does not exist in storage.

So the provisional-collection class does not silently fall through to a correct merged-tx scan — the scan set is populated with an id that fails collection resolution. The index-side guard (`getIndexId() < 0`) the track names does not cover this; a separate guard is needed in the collection-scan path (skip provisional collection ids in the fetch step, and route the tx-created class's own rows through the tx-record merge).
**Proposed fix**: In the D21 Risk 2 fold, name `FetchFromClassExecutionStep` (the polymorphic-collection-id resolution loop at `:105-120`) as the site that must exclude provisional (`<= -2`) collection ids, distinct from the index-planner `getIndexId() < 0` guard. Add a step (or extend the planner-guard step) that (a) filters provisional collection ids out of the scan set and (b) still surfaces the tx's own rows for the tx-created class via the existing tx-record merge. Add a Validation line asserting the same-tx query against a tx-created class returns the tx's own rows without a collection-not-found error (the track already has a close acceptance line; make it name the fetch-step path rather than "resolving a collection or engine").

### T2 [suggestion]
**Certificate**: Premise: ClassIndexManager reads a cached index set materialized at snapshot init (C9)
**Location**: track-5.md `## Context and Orientation` ("`ClassIndexManager` reads a cached `indexes` set materialized once at snapshot init") and D15 Risks/Caveats (same phrasing).
**Issue**: `ClassIndexManager` (`com.jetbrains.youtrackdb.internal.core.index.ClassIndexManager`) is a `public` class of `static` methods with no fields and no cached index set of its own. `processIndexOnCreate` (`:51`) obtains indexes as `cls.getRawIndexes()` where `cls = entity.getImmutableSchemaClass(...)` — i.e., it reads the index set held by the immutable snapshot class. The cached `indexes` set actually lives on `SchemaImmutableClass` (`HashSet<Index> indexes`, field `:91`), materialized once in `init` (`:135`, `this.indexes = new HashSet<>()`, filled through `getRawClassIndexes` → the index manager). The force-rebuild mechanism D15 relies on is sound (rebuilding the snapshot rebuilds `SchemaImmutableClass.indexes`, which `ClassIndexManager` transitively reads), but the ownership attribution is wrong: `ClassIndexManager` does not hold the cache; the snapshot class does.
**Proposed fix**: Reword the Context and D15-caveat sentences to attribute the cached set to `SchemaImmutableClass` and say `ClassIndexManager` reads it via `getImmutableSchemaClass(...).getRawIndexes()`. This keeps the force-rebuild rationale intact while pointing the implementer at the correct field to invalidate.

### T3 [suggestion]
**Certificate**: Premise: SchemaProxy.makeSnapshot committed-only read + drop-side comment wording (C2, C11)
**Location**: track-5.md `## Context and Orientation` / D21 (`SchemaProxy.java:78`); `## Plan of Work` drop-side gap ("also tighten the `IndexManagerEmbedded` drop comment, which currently reads as if the Track 4 commit already drops the index"); and gap (2) error-message quote.
**Issue**: Three minor citation drifts, none affecting the design:
- `SchemaProxy.makeSnapshot()` is declared at line 75; the `return delegate.makeSnapshot(session)` committed-only read is at ~line 79. The track cites `:78`, which lands inside the method but is not the declaration line. The "Tier 1" comment the track references is the code comment inside this method ("Tier 1: the immutable snapshot is taken from the committed instance, not the tx-local copy."), confirmed present.
- The drop-side comment claim is inverted. The current `dropIndex` tx-path comment (`IndexManagerEmbedded.java:583-589`) reads: "The commit removes the entry and deletes the engine inside its own atomic operation (the tx-local index-definition overlay that hides the dropped index and the commit-time engine drop are **a later track**), so a rollback leaves the index in place." It already attributes the drop to a later track (this one), not to a Track-4 commit that "already drops the index". The Plan of Work's characterization is stale; the comment still needs tightening (it currently *promises* the commit removal that this track must actually implement), but not for the reason stated.
- The gap-(2) error message is quoted as `IndexException("Collection with id -2 does not exist")`; the actual thrown text is `"Collection with id " + collectionId + " does not exist."` (trailing period; `findCollectionsByIds`, `:571`). Substantively correct, cosmetically off.
**Proposed fix**: Update the D21 citation to `SchemaProxy.java:75` (or "the `makeSnapshot()` body, ~`:79`"). Reword the drop-comment note to "the comment promises a commit-time drop this track must implement" rather than "reads as if the Track 4 commit already drops the index". Optionally match the exact `IndexException` wording. All cosmetic; safe to fold at decomposition.

## Evidence base

#### C1 Premise: every production class named in track-5 resolves via PSI findClass
- **Track claim**: track-5 names `EntityImpl`, `SchemaProxy`, `MetadataDefault`, `AbstractStorage`, `IndexManagerEmbedded`, `ClassIndexManager`, `SchemaImmutableClass`, `FetchFromClassExecutionStep`, `DatabaseSessionEmbedded`, `TxSchemaState`, `SchemaShared`, `Index`, `IndexException`, `SchemaClassProxy`, `SchemaPropertyProxy`, `SchemaClassImpl`, `SchemaProxedResource`, and the planned new `IndexOverlay`.
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName` over `GlobalSearchScope.allScope`.
- **Code location**: all resolve under `com.jetbrains.youtrackdb.internal.core.*` (schema: `...metadata.schema`; index: `...core.index`; storage: `...storage.impl.local`; executor: `...sql.executor`; record: `...record.impl`; db: `...core.db`). `IndexOverlay => NONE`.
- **Actual behavior**: All existing names resolve to a single YouTrackDB FQN (`Index` and `EntityImpl` also match unrelated library classes, but the YTDB FQN is present). `IndexOverlay` does not resolve.
- **Verdict**: CONFIRMED
- **Detail**: `IndexOverlay` is the class this track creates (plan Component Map: "IndexOverlay (new)"; track Signatures: "`IndexOverlay.effectiveIndexes() : Set`"). Planned-class rule: CONFIRMED, planned by this track.

#### C2 Premise: SchemaProxy.makeSnapshot reads the committed delegate (Tier 1), not tx-local
- **Track claim**: "`SchemaProxy.makeSnapshot()` ... reads the committed `delegate`, `SchemaProxy.java:78`" and D21 will change it to resolve the tx-local `SchemaShared`.
- **Search performed**: PSI `findMethodsByName("makeSnapshot")` on `SchemaProxy`.
- **Code location**: `SchemaProxy.java:75` (method declaration); body `return delegate.makeSnapshot(session);` at ~`:79`.
- **Actual behavior**: `public ImmutableSchema makeSnapshot() { assert session.assertIfNotActive(); /* Tier 1: ... committed instance, not the tx-local copy. */ return delegate.makeSnapshot(session); }`
- **Verdict**: CONFIRMED
- **Detail**: Committed-only read confirmed. Line anchor drifts (`:78` vs `:75`) — see T3.

#### C3 Premise: MetadataDefault.getImmutableSchemaSnapshot / makeThreadLocalSchemaSnapshot exist with refcount pinning
- **Track claim**: D21 — the snapshot is "refcount-pinned per operation (`MetadataDefault.makeThreadLocalSchemaSnapshot`)".
- **Search performed**: PSI `findMethodsByName` on `MetadataDefault`.
- **Code location**: `getImmutableSchemaSnapshot` @ `:105`; `makeThreadLocalSchemaSnapshot` @ `:77`.
- **Actual behavior**: `makeThreadLocalSchemaSnapshot` guards on `immutableCount == 0`, sets `immutableSchema = schema.makeSnapshot()`, then `immutableCount++` — a refcount. `getImmutableSchemaSnapshot` returns the pinned `immutableSchema` or builds via `schema.makeSnapshot()`.
- **Verdict**: CONFIRMED

#### C4 Premise: EntityImpl.validate resolves class via committed-only snapshot and guards behind if(immutableSchemaClass != null)
- **Track claim**: D21 / Context — `EntityImpl.validate()` (`EntityImpl.java:3932`) resolves the class through the committed-only snapshot and guards every check behind `if (immutableSchemaClass != null)`, so a same-tx class resolves null and constraints are skipped.
- **Search performed**: PSI `findMethodsByName("validate")` on `EntityImpl`; traced `getImmutableSchemaClass`.
- **Code location**: `validate()` @ `:3914` (method start; the guarded body region covers `:3932`). `getImmutableSchemaClass(session)` @ `:4181` delegates to `getImmutableSchemaClass(session, getImmutableSchemaSnapshot())` @ `:4187`.
- **Actual behavior**: `final var immutableSchemaClass = getImmutableSchemaClass(session); if (immutableSchemaClass != null) { ... strict/property checks; ... getImmutableSchemaSnapshot(); ... }`. `getImmutableSchemaClass(session, immutableSchema)` returns null when `immutableSchema.getClass(className)` is null.
- **Verdict**: CONFIRMED
- **Detail**: Class resolution flows through `getImmutableSchemaSnapshot()` (committed-only per C2), so a tx-created class → null → constraints silently skipped. Method-start line is `:3914`; `:3932` is inside the body (the guard region), so the anchor is acceptable.

#### C5 Premise: computeCommitWorkingSet exists and reads getImmutableSchemaClass then getCollectionForNewInstance then doGetAndCheckCollection
- **Track claim**: D21 Risk 1 — `computeCommitWorkingSet` calls `getImmutableSchemaClass` then `getCollectionForNewInstance`; must not hand `doGetAndCheckCollection` a provisional id.
- **Search performed**: PSI method-call enumeration over `AbstractStorage`.
- **Code location**: `computeCommitWorkingSet` declared @ `:2363`, body ends `:2422`. Inside: `getImmutableSchemaClass` @ `:2410`, `getCollectionForNewInstance` @ `:2412`, `doGetAndCheckCollection` @ `:2416`.
- **Actual behavior**: `if (record.isDirty() && collectionId == RID.COLLECTION_ID_INVALID && record instanceof EntityImpl entity) { var cls = entity.getImmutableSchemaClass(session); if (cls != null) { collectionId = cls.getCollectionForNewInstance(entity); collectionOverrides.put(...); } } collectionsToLock.put(collectionId, doGetAndCheckCollection(collectionId));`
- **Verdict**: CONFIRMED
- **Detail**: Today the `cls != null` branch does not fire for a tx-created class (committed snapshot → null), so `collectionId` stays the record's own id. D21's tx-aware snapshot makes `cls` non-null → `getCollectionForNewInstance` could return a provisional id → `doGetAndCheckCollection` fails. Risk mechanism is real and correctly located.

#### C6 Premise: the commit chain ordering — reconcile before computeCommitWorkingSet, forceSnapshot after
- **Track claim**: D21 Risk 1 — `computeCommitWorkingSet` "reached at line 2528, after `reconcileCollections` at 2473 and before `forceSnapshot` at 2691".
- **Search performed**: PSI method-call enumeration over `AbstractStorage`, sorted by line.
- **Code location**: `reconcileCollections(...)` call @ `:2473`; `resolveProvisionalCollectionIds(...)` @ `:2489`; `computeCommitWorkingSet(...)` call @ `:2528`; `forceSnapshot` @ `:2691` and `:2700`.
- **Actual behavior**: `applyCommitOperations` reconciles collections (`:2473`), patches provisional→real ids (`:2489`), serializes the tx-local schema, then gathers the working set (`:2528`), then applies records; promotion + `forceSnapshot` on the success path (`:2691`).
- **Verdict**: CONFIRMED
- **Detail**: The 2410/2412 reads live inside `computeCommitWorkingSet` (declared `:2363`) and execute at its call site `:2528`, i.e. after `reconcileCollections` and `resolveProvisionalCollectionIds`. The track's phrasing ("`:2410` ... reached at line 2528") is accurate. Whether the tx-local class is re-keyed to a real id by `:2528` is the open verification the track hands to implementation (Risk 1) — the reconcile+resolve steps at 2473/2489 run first, so the guard is checkable there.

#### C7 Premise: doGetAndCheckCollection fails on a provisional (negative) collection id
- **Track claim**: D21 Risk 1 — a provisional id (`<= -2`) reaching `doGetAndCheckCollection` fails.
- **Search performed**: PSI `findMethodsByName("doGetAndCheckCollection")` on `AbstractStorage`.
- **Code location**: `:2023`.
- **Actual behavior**: `checkCollectionSegmentIndexRange(collectionId); final var collection = collections.get(collectionId); if (collection == null) throw new IllegalArgumentException("Collection " + collectionId + " is null"); return collection;`
- **Verdict**: CONFIRMED
- **Detail**: A negative id fails the range check or returns null → throw. Risk is real.

#### C8 Premise: TxSchemaState carries the generated collection name for a provisional id
- **Track claim**: gap (2) fix — "Resolve provisional ids (`<= -2`) via `TxSchemaState` (it carries the generated collection name)".
- **Search performed**: PSI method enumeration on `TxSchemaState`.
- **Code location**: `getProvisionalCollectionName` @ `:185`; `getResolvedCollectionIds` @ `:242`.
- **Actual behavior**: `getProvisionalCollectionName(provisionalCollectionId)` returns "The `<class>_<counter>` name recorded for `provisionalCollectionId` when it was allocated" (Javadoc); "The commit creates the real collection under this name." `getResolvedCollectionIds` gives the provisional→real map.
- **Verdict**: CONFIRMED
- **Detail**: The carrier the fix needs exists.

#### C9 Premise: ClassIndexManager reads a cached indexes set materialized once at snapshot init
- **Track claim**: Context / D15 — "`ClassIndexManager` reads a cached `indexes` set materialized once at snapshot init."
- **Search performed**: PSI class-shape + method enumeration on `ClassIndexManager`; field + init inspection on `SchemaImmutableClass`.
- **Code location**: `ClassIndexManager` — `public` class, all-`static` methods (`checkIndexesAfterCreate` @ `:41`, `processIndexOnCreate` @ `:51`, ...), **no fields**. `SchemaImmutableClass.indexes` field @ `:91`; `init` @ `:135` (`this.indexes = new HashSet<>()`); `getRawIndexes()` @ `:690` returns the field.
- **Actual behavior**: `ClassIndexManager.processIndexOnCreate` reads `cls.getRawIndexes()` where `cls = entity.getImmutableSchemaClass(...)`. The cached set is `SchemaImmutableClass.indexes`, materialized at snapshot init and sourced from the index manager via `getRawClassIndexes`.
- **Verdict**: PARTIAL
- **Detail**: In effect correct (the set ClassIndexManager consumes is the snapshot class's cache, invalidated by a snapshot force-rebuild), but the cache is owned by `SchemaImmutableClass`, not `ClassIndexManager`. → T2.

#### C10 Premise: the snapshot sources a class's index list from the index manager (getRawClassIndexes → getClassRawIndexes)
- **Track claim**: Context — "The snapshot sources a class's index list from the index manager (`SchemaImmutableClass.getRawClassIndexes` → the index manager's `getClassRawIndexes`)".
- **Search performed**: PSI method lookup on `SchemaImmutableClass` and project-wide `getClassRawIndexes`.
- **Code location**: `SchemaImmutableClass.getRawClassIndexes` @ `:654` → `getIndexManager().getClassRawIndexes(session, name, indexes)`; `IndexManagerAbstract.getClassRawIndexes` @ `:85` (inherited by `IndexManagerEmbedded`).
- **Actual behavior**: The chain resolves as claimed; `getClassRawIndexes` is declared on the abstract superclass, not `IndexManagerEmbedded` directly, but is reachable on the embedded instance.
- **Verdict**: CONFIRMED

#### C11 Premise: tx-path dropIndex only marks the class changed; the shared registry/engine survive
- **Track claim**: gap (3) — a tx-local `dropIndex` "today only calls `markClassChanged` (`IndexManagerEmbedded.java:590-600`), so the index stays in the shared registry ... survives the commit ... also tighten the drop comment, which currently reads as if the Track 4 commit already drops the index".
- **Search performed**: PSI `findMethodsByName("dropIndex")` + body read.
- **Code location**: `dropIndex` @ `:581`; tx-path block `:583-604` (the `markClassChanged` call is within `:590-600`).
- **Actual behavior**: tx path: `var txState = session.ensureTxSchemaState(); ... txState.markClassChanged(idx.getDefinition().getClassName()); ... return;` — leaves `indexes` map and engine intact. Comment says: "The commit removes the entry and deletes the engine inside its own atomic operation (... are a later track), so a rollback leaves the index in place."
- **Verdict**: CONFIRMED
- **Detail**: The mechanism (only `markClassChanged`, registry survives) is confirmed. The comment characterization in the track is inverted — the comment attributes the drop to "a later track", not to a Track-4 commit that already drops it. → T3.

#### C12 Premise: create-time deferred path throws IndexException on a provisional collection id
- **Track claim**: gap (2) — indexing a class created in the same tx throws `IndexException("Collection with id -2 does not exist")` because the deferred path resolves ids through `getCollectionNameById`, which returns null for `< 0`.
- **Search performed**: PSI method read of `createIndex` (8-arg overload) + `findCollectionsByIds` + `DatabaseSessionEmbedded.getCollectionNameById`.
- **Code location**: `createIndex` tx-deferred path @ `:422-444`; `findCollectionsByIds(collectionIdsToIndex, session)` @ `:439` → `findCollectionsByIds` @ `:564`; `getCollectionNameById` @ `:2842`.
- **Actual behavior**: `findCollectionsByIds` calls `database.getCollectionNameById(collectionId)`; if null throws `new IndexException(db, "Collection with id " + collectionId + " does not exist.")`. `getCollectionNameById` returns null for `collectionId < 0`. The deferred path calls `findCollectionsByIds` at `:439` before `markDeferred`.
- **Verdict**: CONFIRMED
- **Detail**: Confirmed. The message text carries a trailing period the track omits (cosmetic; T3).

#### C13 Edge case: same-tx query against a tx-created (provisional-collection) class
- **Trigger**: D21 makes the snapshot tx-aware; a query/scan runs against a class created in the same tx (its collection is provisional `<= -2`, engine not built).
- **Code path trace**:
  1. Entry: `FetchFromClassExecutionStep(className, collections=null, ...)` ctor @ `FetchFromClassExecutionStep.java:72`.
  2. `loadClassFromSchema(className, ctx)` @ `:131` → `getImmutableSchemaSnapshot().getClass(className)`; today throws `CommandExecutionException("Class ... not found")` (class absent from committed snapshot); after D21 the tx-created class resolves and the throw is bypassed.
  3. `clazz.getPolymorphicCollectionIds()` @ `:106` returns the provisional id(s).
  4. loop `:109-114`: `getCollectionNameById(provisionalId)` @ `:110` returns null (`< 0` → null, `DatabaseSessionEmbedded:2842`); at `:111` `collections == null` → the provisional id is **added** to `filteredClassCollections` @ `:112`.
  5. `collectionIds` @ `:120` carries the provisional id into `internalStart` @ `:142`; `:148` sees a non-empty array and constructs `RecordIteratorCollections` over an id with no backing storage collection.
- **Outcome**: The scan set contains a provisional id; iteration would fail to resolve the collection (collection-not-found downstream), rather than falling through to a correct merged-tx scan.
- **Track coverage**: PARTIAL — the track acknowledges the scenario (D21 Risk 2, a Validation line) but its named fix is the index-planner guard (`getIndexesInternal`, `getIndexId() < 0`), which does not touch the collection-scan setup where the break occurs. → T1.

#### C14 Premise: FetchFromClassExecutionStep is the scan fallback that returns the merged tx view
- **Track claim**: Context / D13 — the scan fallback (`FetchFromClassExecutionStep`) "already returns the correct merged tx view".
- **Search performed**: PSI `findClass` + supertype.
- **Code location**: `com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromClassExecutionStep` extends `AbstractExecutionStep`.
- **Actual behavior**: Class exists; iterates class collections via `RecordIteratorCollections`. For already-built classes the merged-tx view via record iteration holds (unchanged); the tx-created-class case is the C13 edge.
- **Verdict**: CONFIRMED
- **Detail**: The scan fallback exists and is the right fallthrough for built classes; only the provisional-collection case (T1/C13) needs the extra guard.

#### C15 Premise: Index.getIndexId exists for the planner skip-unbuilt predicate
- **Track claim**: D13 — the planner skips any index whose engine is not built (`getIndexId() < 0`); Signatures name "excluding `getIndexId() < 0` engines".
- **Search performed**: PSI method lookup on `Index`; project-wide `getIndexesInternal`.
- **Code location**: `Index.getIndexId()` @ `:460`. `getIndexesInternal` overrides on `SchemaClassProxy` (`:109`,`:115`), `SchemaClassInternal` (`:39`,`:43`), `SchemaClassImpl` (`:1191`,`:1207`), `SchemaImmutableClass` (`:660`,`:685`).
- **Actual behavior**: `Index.getIndexId()` is declared; the class-side index-enumeration surface (`getIndexesInternal`) exists on the proxy/impl/immutable hierarchy the planner reads through.
- **Verdict**: CONFIRMED

#### C16 Premise: getImmutableSchemaSnapshot has 174 call sites (D21 blast radius)
- **Track claim**: D21 — "The snapshot is the single read tier the whole read/query/serialize/security stack consumes (174 call sites)"; MEMORY notes "174 callers ... semantic change, not 174 edits; core = 1 method".
- **Search performed**: PSI `ReferencesSearch.search(MetadataDefault#getImmutableSchemaSnapshot, allScope)`.
- **Code location**: single declaration `MetadataDefault.getImmutableSchemaSnapshot` @ `:105`.
- **Actual behavior**: exactly **174** references project-wide.
- **Verdict**: CONFIRMED
- **Detail**: The count is exact, not rounded. The change is a single-method behavior change (`SchemaProxy.makeSnapshot` becomes tx-aware); the 174 call sites all read through the changed tier without individual edits. Blast-radius framing is accurate. `Index.doPut` (the commit-time build target, D12) also confirmed present on `Index`/`IndexUnique`/`IndexMultiValues`.
