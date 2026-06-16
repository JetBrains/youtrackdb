<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 27, matches: 27}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

No findings. Every current-state code anchor the design and the eight track files
assert was verified against the live codebase via mcp-steroid PSI and matched. The
bulk of the design and track prose describes target state that the pending `[ ]`
tracks create (the tx-local `SchemaShared` copy, `TxSchemaState`, `IndexOverlay`,
`MetadataWriteMutex`, commit-time reconciliation, the freeze-kind taxonomy,
base-keyed engine files, export/import hardening); the intent-axis pre-screen
classifies these as target-state and they are not findings. No target was found
unreachable from the current code, so no `design-decision` escalation is raised.

## Evidence base

Certificates are grouped by consistency axis. Every Ref/Invariant search ran
through mcp-steroid PSI (`steroid_execute_code` against the PSI tree of the open
`transactional-schema` project, path-matched to the working tree); grep was used
only for the rough `< 0` occurrence count noted in C26. All 26 certificates
verified MATCHES / ENFORCED-as-claimed.

### DESIGN ↔ CODE

#### C1 Ref: SchemaShared
- **Document claim**: design Class Design + Track 2/3 — `SchemaShared` holds `classes`, `globalProperties`, `collectionCounter`, `blobCollections`, the schema record, with `toStream`/`fromStream`/`forceSnapshot`; today single monolithic EMBEDDEDSET at `CURRENT_VERSION_NUMBER` 4.
- **Search performed**: PSI `PsiShortNamesCache.getClassesByName("SchemaShared")` + field/method dump.
- **Code location**: `core/.../metadata/schema/SchemaShared.java`.
- **Actual signature/role**: fields `CURRENT_VERSION_NUMBER` (int, `=4`), `VERSION_NUMBER_V4` (`=4`), `VERSION_NUMBER_V5` (`=5`), `lock: ReentrantReadWriteLock`, `classes: Map<String,SchemaClassImpl>`, `collectionsToClasses: Int2ObjectOpenHashMap`, `collectionCounter: int`, `properties: List<GlobalPropertyImpl>`, `blobCollections: IntOpenHashSet`, `snapshot: ImmutableSchema`; methods `forceSnapshot()`, `fromStream(DatabaseSessionEmbedded, EntityImpl)`, `toStream(DatabaseSessionEmbedded): EntityImpl`, `saveInternal(...)`.
- **Verdict**: MATCHES
- **Detail**: `CURRENT_VERSION_NUMBER=4` confirms today's format-version claim. A `VERSION_NUMBER_V5=5` constant pre-exists (the bump target Track 2 names), which strengthens rather than contradicts the plan; not raised as a finding (a declared constant is not a wired format). `SchemaShared.lock` is a JDK `ReentrantReadWriteLock` — the design treats `SchemaShared.lock` as distinct from `stateLock` throughout, consistent.

#### C2 Ref: SchemaClassImpl.owner is final + no record-RID field
- **Document claim**: D8 / Class Design — `SchemaClassImpl.owner` is `final`, superclass/subclass links are object references (so a clone would leak the shared owner; a `fromStream` re-parse is required); Track 2/3 — `SchemaClassImpl` has no record-RID field today (F45), the field is net-new.
- **Search performed**: PSI field dump of `SchemaClassImpl`.
- **Code location**: `core/.../metadata/schema/SchemaClassImpl.java`.
- **Actual signature/role**: `owner: SchemaShared [protected final]`; `superClasses: List<SchemaClassImpl>`, `subclasses: List<SchemaClassImpl>` (object references); no RID/record-identity field present (fields: owner, properties, defaultCollectionId, name, description, collectionIds, polymorphicCollectionIds, superClasses, subclasses, overSize, strictMode, abstractClass, customFields, collectionSelection, hashCode).
- **Verdict**: MATCHES

#### C3 Ref: SchemaClassImpl.renameCollection
- **Document claim**: D11 / Track 6 — neuter `SchemaClassImpl.renameCollection` (the `writeCache.renameFile` path).
- **Search performed**: PSI method-name filter on `SchemaClassImpl`.
- **Code location**: `core/.../metadata/schema/SchemaClassImpl.java`.
- **Actual signature/role**: `renameCollection(DatabaseSessionEmbedded, String, String)`.
- **Verdict**: MATCHES

#### C4 Ref: SchemaClassProxy / SchemaPropertyProxy / SchemaProxy captured delegate
- **Document claim**: D8 / Track 3 — each proxy holds a captured `delegate`, a direct reference to the impl it stood for at creation (tier-2 fast path; tier-3 name re-resolution during a schema tx).
- **Search performed**: PSI superclass-chain field dump for all three proxies.
- **Code location**: `core/.../metadata/schema/Schema{Class,Property}Proxy.java`, `core/.../db/record/ProxedResource.java`.
- **Actual signature/role**: all three extend `ProxedResource<T>` which declares `delegate: T [protected final]` and `session: DatabaseSessionEmbedded [protected final]`.
- **Verdict**: MATCHES
- **Detail**: `delegate` is `final` — consistent with the design's tier model (the captured-delegate fast path stays for non-schema-tx reads; name re-resolution is the in-tx tier, not a field mutation).

#### C5 Ref: ScalableRWLock as AbstractStorage.stateLock (non-reentrant)
- **Document claim**: design `### Constraints` / D3 / D19 — `stateLock` is a `ScalableRWLock`, non-reentrant; reconciliation must use lock-free inner primitives, never the public structural methods that re-take it.
- **Search performed**: PSI field dump of `AbstractStorage`; class lookup of `ScalableRWLock`.
- **Code location**: `core/.../storage/impl/local/AbstractStorage.java`; `core/.../common/concur/lock/ScalableRWLock.java`.
- **Actual signature/role**: `AbstractStorage.stateLock: ScalableRWLock`.
- **Verdict**: MATCHES

#### C6 Ref: AbstractStorage commit + collection/engine primitives + lockIndexes
- **Document claim**: D3 / Track 4 — `doAddCollection` / `dropCollectionInternal` are the existing lock-free collection primitives; engines need `doAddIndexEngine` / `doDeleteIndexEngine(atomicOperation,…)` extracted from the public `addIndexEngine` / `deleteIndexEngine`; `lockIndexes` resolves engines by id; `commit` runs the record commit.
- **Search performed**: PSI method enumeration of `AbstractStorage`.
- **Code location**: `core/.../storage/impl/local/AbstractStorage.java`.
- **Actual signature/role**: `commit(FrontendTransactionImpl)` + `commit(FrontendTransactionImpl, boolean)`; `addCollection(...)` (public, 2 overloads); `doAddCollection(AtomicOperation, String[, int])`; `dropCollection(...)` (public); `dropCollectionInternal(AtomicOperation, int)`; `addIndexEngine(IndexMetadata, Map)`; `deleteIndexEngine(int)`; `lockIndexes(SortedMap<String, FrontendTransactionIndexChanges>, AtomicOperation)`.
- **Verdict**: MATCHES
- **Detail**: No `doAddIndexEngine` / `doDeleteIndexEngine` exist today — confirms Track 4's current-state claim that those engine primitives must be extracted (target-state work, not a phantom reference). `commit(...)`'s body confirms it is the live commit path.

#### C7 Ref: OperationsFreezer freezeOperations / startOperation / endOperation
- **Document claim**: D7 freezer facet / Track 7 — the kind-aware gate is the schema-commit variant of `startOperation`'s check; the operator arm lives in `freezeOperations`; `endOperation` pairs the depth decrement.
- **Search performed**: PSI method enumeration of `OperationsFreezer`.
- **Code location**: `core/.../atomicoperations/operationsfreezer/OperationsFreezer.java`.
- **Actual signature/role**: `freezeOperations(Supplier<? extends BaseException>)`, `startOperation()`, `endOperation()`.
- **Verdict**: MATCHES
- **Detail**: The throw-vs-park supplier (`Supplier<? extends BaseException>`) confirms the "registered with a throw-exception supplier" design claim. No `freeze` method on the freezer itself; the freeze entry point is `DatabaseSessionEmbedded.freeze` (C8), as the design states.

#### C8 Ref: DatabaseSessionEmbedded.freeze / realClose / checkOpenness
- **Document claim**: design Part 3 / Track 7 — operator freeze raised through `DatabaseSessionEmbedded.freeze`; the volatile teardown-intent mark publishes at `realClose()` entry; `checkOpenness` gates commit/rollback once the session reads CLOSED.
- **Search performed**: PSI method lookup on `DatabaseSessionEmbedded`.
- **Code location**: `core/.../db/DatabaseSessionEmbedded.java`.
- **Actual signature/role**: `freeze(boolean)`, `freeze()`, `realClose()`, `checkOpenness()`.
- **Verdict**: MATCHES

#### C9 Ref: DatabaseExport.exportSchema / EXPORTER_VERSION
- **Document claim**: D20 / Track 8 — `DatabaseExport.exportSchema` walks the logical schema (not raw bytes); `EXPORTER_VERSION` is 14, bumped to 15.
- **Search performed**: PSI method + field lookup; project-wide `EXPORTER_VERSION` field search.
- **Code location**: `core/.../db/tool/DatabaseExport.java`.
- **Actual signature/role**: `exportSchema()`, `exportRecords()`; `EXPORTER_VERSION = 14`.
- **Verdict**: MATCHES

#### C10 Ref: DatabaseImport.importSchema
- **Document claim**: D20 / Track 8 — `DatabaseImport.importSchema` rebuilds through the schema API; the importer has a silent plain-JSON fallback today.
- **Search performed**: PSI method lookup on `DatabaseImport`.
- **Code location**: `core/.../db/tool/DatabaseImport.java`.
- **Actual signature/role**: `importSchema(boolean)`; `exporterVersion` runtime field initialized `-1` (the parsed version slot).
- **Verdict**: MATCHES

#### C11 Ref: IndexDefinition.className has no setter
- **Document claim**: D17 / Track 6 — `IndexDefinition.className` has no setter today; the planner resolves an index by class name; a setter is added.
- **Search performed**: PSI on both `IndexDefinition` types for `getClassName`/`setClassName`.
- **Code location**: `core/.../index/IndexDefinition.java` (interface).
- **Actual signature/role**: `core.index.IndexDefinition` (interface): `getClassName()` present, `setClassName()` absent; `AbstractIndexDefinition`: `setClassName()` absent. (The unrelated `metadata.schema.schema.IndexDefinition` record-class has a `className` field but no accessors.)
- **Verdict**: MATCHES
- **Detail**: The "no setter today" claim is accurate for the type the planner resolves by — the `core.index.IndexDefinition` hierarchy.

#### C12 Ref: StorageComponent setName / getName / getFullName
- **Document claim**: D16 / Track 6 — store an immutable file base on `StorageComponent` keyed by the stable engine id; `setName` changes only the logical name; the data file is `getFullName()`.
- **Search performed**: PSI method lookup on `StorageComponent`.
- **Code location**: `core/.../storage/impl/local/paginated/base/StorageComponent.java`.
- **Actual signature/role**: `setName(String)`, `getName()`, `getFullName()`.
- **Verdict**: MATCHES

#### C13 Ref: YTDBGraphImplAbstract.createVertexWithClass (read-site #1)
- **Document claim**: D19 / Track 4 — one of the two remaining lock-based read sites converted to snapshot-first.
- **Search performed**: PSI method lookup.
- **Code location**: `core/.../gremlin/YTDBGraphImplAbstract.java`.
- **Actual signature/role**: `createVertexWithClass(DatabaseSessionEmbedded, String)`.
- **Verdict**: MATCHES

#### C14 Ref: SQLMatchStatement.getLowerSubclass (read-site #2)
- **Document claim**: D19 / Track 4 — the per-MATCH lock-based read site converted to snapshot-first.
- **Search performed**: PSI method lookup.
- **Code location**: `core/.../sql/parser/SQLMatchStatement.java`.
- **Actual signature/role**: `getLowerSubclass(DatabaseSessionEmbedded, String, String)`.
- **Verdict**: MATCHES

#### C15 Ref: SchemaImmutableClass.getRawClassIndexes
- **Document claim**: Track 5 Context — the snapshot sources a class's index list via `SchemaImmutableClass.getRawClassIndexes` → index manager `getClassRawIndexes`.
- **Search performed**: PSI method lookup.
- **Code location**: `core/.../metadata/schema/SchemaImmutableClass.java`.
- **Actual signature/role**: `getRawClassIndexes(DatabaseSessionEmbedded, Collection<Index>)`.
- **Verdict**: MATCHES

### PLAN ↔ CODE

#### C16 Ref: IndexManagerEmbedded.addCollectionToIndex / removeCollectionFromIndex self-commit
- **Document claim**: D1 / I-A7 / Track 3 + design §"tx-local schema view" — the collection-membership entry points `addCollectionToIndex` / `removeCollectionFromIndex` wrap their work in `session.executeInTxInternal(...)`, a nested self-committing transaction (the silent-leak hazard).
- **Search performed**: PSI method enumeration + body read of `IndexManagerEmbedded.addCollectionToIndex`.
- **Code location**: `core/.../index/IndexManagerEmbedded.java`.
- **Actual signature/role**: `addCollectionToIndex(DatabaseSessionEmbedded, String, String, boolean)` body calls `session.executeInTxInternal(transaction -> { ... index.addCollection(...) ... })`; `removeCollectionFromIndex(DatabaseSessionEmbedded, String, String)` present.
- **Verdict**: MATCHES
- **Detail**: These live on the index manager (not `SchemaShared`/`SchemaClassImpl`), exactly as the design names them ("the collection-membership entry points") and reached transitively from `createClass`/`addSuperClass` via the polymorphic ripple. Consistent.

#### C17 Invariant: throw-on-active-tx guards (index-manager createIndex / dropIndex)
- **Document claim**: Track 3 — the index-manager `createIndex` / `dropIndex` throw when a transaction is active (throw-guards de-guarded to ride the user tx). (ENFORCED today, the guard the track removes.)
- **Code evidence**: `IndexManagerEmbedded.createIndex(...)` body: `if (session.getTransactionInternal().isActive()) throw new IllegalStateException("Cannot create a new index inside a transaction");`. `dropIndex(...)` body: `if (session.getTransactionInternal().isActive()) throw new IllegalStateException("Cannot drop an index inside a transaction");`.
- **Mechanism**: explicit active-transaction guard at method entry.
- **Verdict**: ENFORCED (matches the documented current-state guard the track de-guards)

#### C18 Ref: SchemaEmbedded.dropClass / dropClassInternal
- **Document claim**: Track 3 — `dropClass` / `dropClassInternal` are throw-guard entry points reworked to ride the user tx.
- **Search performed**: PSI method enumeration of `SchemaEmbedded`.
- **Code location**: `core/.../metadata/schema/SchemaEmbedded.java`.
- **Actual signature/role**: `dropClass(DatabaseSessionEmbedded, String)`, `dropClassInternal(DatabaseSessionEmbedded, String)`.
- **Verdict**: MATCHES

#### C19 Ref: SchemaClassImpl.addSuperClass
- **Document claim**: design / Track 3 — `addSuperClass` reaches the membership entry points indirectly through the hierarchy ripple.
- **Search performed**: PSI method presence on `SchemaClassImpl` / `SchemaClassEmbedded`.
- **Code location**: `core/.../metadata/schema/SchemaClassImpl.java`, `SchemaClassEmbedded.java`.
- **Actual signature/role**: `addSuperClass` present on both.
- **Verdict**: MATCHES

#### C20 Ref: IndexManagerAbstract.load + CONFIG_INDEXES link-set pattern
- **Document claim**: D14 / Track 2 — the per-class split mirrors the index manager: a `CONFIG_INDEXES` link set to per-index entities, with `IndexManagerAbstract.load` binding each index to its record identity.
- **Search performed**: PSI method + field enumeration of `IndexManagerAbstract`.
- **Code location**: `core/.../index/IndexManagerAbstract.java`.
- **Actual signature/role**: `load(DatabaseSessionEmbedded)` + `load(FrontendTransactionImpl, EntityImpl)`; field `CONFIG_INDEXES = "indexes"`; `indexes`/`classPropertyIndex` both `ConcurrentHashMap`.
- **Verdict**: MATCHES

#### C21 Ref: index-manager lookup maps (indexes, classPropertyIndex)
- **Document claim**: Track 5 / Track 6 — two flat lookup maps `indexes` (name→Index) and `classPropertyIndex` (class+property→indexes); a class rename re-keys `classPropertyIndex`.
- **Search performed**: PSI field enumeration of `IndexManagerAbstract`; method lookup of `getClassRawIndexes`.
- **Code location**: `core/.../index/IndexManagerAbstract.java`, `IndexManagerEmbedded.java`.
- **Actual signature/role**: `indexes: ConcurrentHashMap`, `classPropertyIndex: ConcurrentHashMap`; `getClassRawIndexes(DatabaseSessionEmbedded, String, Collection<Index>)` on both managers.
- **Verdict**: MATCHES

#### C22 Ref: Index.getIndexId() planner signal
- **Document claim**: D13 / Track 5 — the planner skips an unbuilt index via `getIndexId() < 0`.
- **Search performed**: PSI project-wide `getMethodsByName("getIndexId")`.
- **Code location**: `core/.../index/Index.java` (interface), `IndexAbstract.java`.
- **Actual signature/role**: `getIndexId(): int` on `core.index.Index` and `IndexAbstract`.
- **Verdict**: MATCHES
- **Detail**: An earlier short-name lookup collided with `org.assertj.core.data.Index` (test lib); the FQN-qualified method search resolved the engine-handle accessor unambiguously.

#### C23 Ref: SecurityShared.create
- **Document claim**: D18 / Track 8 — split `SecurityShared.create` into a schema tx then a data tx (genesis two-phase).
- **Search performed**: PSI method lookup.
- **Code location**: `core/.../metadata/security/SecurityShared.java`.
- **Actual signature/role**: `create(DatabaseSessionEmbedded)`.
- **Verdict**: MATCHES

#### C24 Ref: session executeInTxInternal / computeInTxInternal
- **Document claim**: Track 3 — the self-commit sites use `session.executeInTxInternal(...)`; index `createIndex` uses the compute variant.
- **Search performed**: PSI project-wide method-name search.
- **Code location**: `core/.../db/DatabaseSessionEmbedded.java`.
- **Actual signature/role**: both `executeInTxInternal` and `computeInTxInternal` declared on `DatabaseSessionEmbedded`; `IndexManagerEmbedded.createIndex` body uses `session.computeInTxInternal(...)`, `addCollectionToIndex`/`dropIndex` use `executeInTxInternal(...)`.
- **Verdict**: MATCHES

#### C25 Ref: ChangeableRecordId.setCollectionAndPosition + assertIdentityChangedAfterCommit
- **Document claim**: Track 4 Context — the temp-RID resolution path (`ChangeableRecordId.setCollectionAndPosition`, guarded by `assertIdentityChangedAfterCommit`) is the template the provisional-collection-id resolution extends.
- **Search performed**: PSI method lookup + project-wide `assertIdentityChangedAfterCommit`.
- **Code location**: `core/.../id/ChangeableRecordId.java`; `core/.../tx/FrontendTransaction*.java`.
- **Actual signature/role**: `ChangeableRecordId.setCollectionAndPosition(int, long)`; `assertIdentityChangedAfterCommit` on `FrontendTransaction`/`FrontendTransactionImpl`/`FrontendTransactionNoTx`.
- **Verdict**: MATCHES

### GAPS / DESIGN ↔ PLAN

#### C26 Invariant: collectionId < 0 / abstract-class -1 convention; provisional <= -2
- **Document claim**: D2 / D9 / design §"Commit-time reconciliation" — the schema layer marks an abstract class with collection id `-1` and tests `collectionId < 0` (not `== -1`) in 11+ sites, so provisional ids must use `<= -2` to stay disjoint.
- **Code evidence**: `SchemaClassImpl.NOT_EXISTENT_COLLECTION_ID = -1` (abstract marker); `< 0` collection-id comparisons present in `SchemaClassImpl` (sampled via grep, indicative count only — the exact "11+ sites" tally was not exhaustively enumerated).
- **Mechanism**: negative-id sentinel convention in the schema layer; the abstract `-1` constant is concrete, the `< 0` predicate style is present.
- **Verdict**: ENFORCED (the convention the `<= -2` provisional range depends on exists; the `<= -2` sub-range itself is target-state introduced by Track 4)
- **Detail**: The exact site count is a grep-based estimate, not PSI-exhaustive; the load-bearing fact (abstract `-1` marker plus `< 0`-style predicates) is confirmed, which is all the design's reasoning requires.

#### C27 Invariant (gap check): D12 populated-class index-build boundary is an acknowledged-open Phase-1 decision
- **Document claim**: plan `### Constraints` + design §"Index build" Edge cases + Track 5 D12/Validation — the v1 boundary (loud-reject pointing at YTDB-1064 vs accept with a documented heap envelope) is explicitly the open Phase-1 decision Track 5 settles during execution.
- **Code evidence**: not a code claim — a deferred design decision. Design and plan agree it is open; both route it to Track 5.
- **Mechanism**: documented deferral, internally consistent across design and plan.
- **Verdict**: MATCHES (no inconsistency between artifacts — both name it open and assign it to Track 5; not a finding)
- **Detail**: Recorded here so the gap scan is auditable. An explicitly-flagged open decision that design and plan agree on is not a DESIGN↔PLAN inconsistency; it is in-scope for Track 5 execution, where the structural review and the execution agent resolve it.
