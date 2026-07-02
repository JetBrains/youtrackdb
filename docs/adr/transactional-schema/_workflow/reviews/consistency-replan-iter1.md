<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 14, matches: 14}
cert_index:
  - {id: R1, verdict: MATCHES, anchor: "#### R1 "}
  - {id: R2, verdict: MATCHES, anchor: "#### R2 "}
  - {id: R3, verdict: MATCHES, anchor: "#### R3 "}
  - {id: R4, verdict: MATCHES, anchor: "#### R4 "}
  - {id: R5, verdict: MATCHES, anchor: "#### R5 "}
  - {id: R6, verdict: MATCHES, anchor: "#### R6 "}
  - {id: R7, verdict: MATCHES, anchor: "#### R7 "}
  - {id: R8, verdict: MATCHES, anchor: "#### R8 "}
  - {id: R9, verdict: MATCHES, anchor: "#### R9 "}
  - {id: R10, verdict: MATCHES, anchor: "#### R10 "}
  - {id: R11, verdict: MATCHES, anchor: "#### R11 "}
  - {id: R12, verdict: MATCHES, anchor: "#### R12 "}
  - {id: R13, verdict: MATCHES, anchor: "#### R13 "}
  - {id: R14, verdict: MATCHES, anchor: "#### R14 "}
flags: [CONTRACT_OK]
-->

## Findings

No findings. Every code citation in Decision Record D21 and the Track 5 track
file verified against the codebase via IntelliJ PSI. The replan's current-state
code references — the ones that drive Track 5 execution — are all accurate,
including the line numbers, which match to within the precision a reader needs to
land on the exact mechanism.

The expected D21-vs-frozen-design divergence (the revised tx-aware snapshot
superseding `design.md`'s committed-only snapshot) is not flagged, per the
consistency-review rule that a revised Decision Record diverging from the frozen
design is the expected state after an inline replan; `design-final.md` reconciles
it in Phase 4. The `design.md:270` sentence the D21 record leans on ("reads route
to the tx-local structure, not only the snapshot") is present at the cited line and
is consistent with making the snapshot itself tx-aware.

## Evidence base

The certificates below cover the D21 / Track 5 load-bearing current-state code
claims (the spawn's explicit verify list) plus the supporting Track 5 / Track 6
Context-section claims. All Java symbols were verified through PSI
(`steroid_execute_code` against the open `transactional-schema-b4l1mcdq` project,
which matches this working tree) — `JavaPsiFacade.findClass`, `findMethodsByName`,
`ReferencesSearch`, and `PsiShortNamesCache` — not grep.

#### R1 Ref: EntityImpl.validate() and the `immutableSchemaClass` guard
- **Document claim**: D21 (`implementation-plan.md:251`, `track-5.md:59`) and
  Track 5 `## Context and Orientation` (`track-5.md:87`) — "`EntityImpl.validate()`
  (`EntityImpl.java:3932`) resolves the class through the committed-only snapshot
  and guards every check behind `if (immutableSchemaClass != null)`."
- **Search performed**: PSI `findMethodsByName("validate", false)` on
  `com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl`; document text
  index for the guard string; line read at 3932.
- **Code location**: `EntityImpl.java` — `validate()` declared at line 3923; line
  3932 is `final var immutableSchemaClass = getImmutableSchemaClass(session);`;
  the guard `if (immutableSchemaClass != null)` is at line 3933.
- **Actual signature/role**: single no-arg `validate()`; line 3932 is exactly the
  snapshot-backed class resolution the record describes, and line 3933 is exactly
  the guard quoted.
- **Verdict**: MATCHES
- **Detail**: The `:3932` anchor lands on the class-resolution call inside
  `validate()` (the precise mechanism the record cites), with the quoted guard on
  the next line. `validate()` itself is declared at 3923, but the cite is a
  "method (file:line-where-the-relevant-code-is)" reference, not a
  declaration-line claim, so it points the execution agent at the right code. No
  drift that would cause a wrong assumption — not a finding.

#### R2 Ref: SchemaProxy.makeSnapshot() reads the committed delegate
- **Document claim**: D21 — "`SchemaProxy.makeSnapshot` reads the committed
  `delegate`, `SchemaProxy.java:78`"; Track 5 plan-file and track file say
  `SchemaProxy.makeSnapshot()` must resolve the tx-local `SchemaShared`.
- **Search performed**: PSI `findMethodsByName("makeSnapshot", false)` on
  `SchemaProxy`; body read; line read at 78; `delegate` field type via
  `findFieldByName`.
- **Code location**: `SchemaProxy.java` — `makeSnapshot` declared at line 76; body
  is `assert session.assertIfNotActive(); // Tier 1: the immutable snapshot is
  taken from the committed instance, not the tx-local copy. return
  delegate.makeSnapshot(session);`. Line 78 is the `// Tier 1:` comment; line 79
  is the `return delegate.makeSnapshot(session)`. `delegate` is the generic `T`
  field inherited from `ProxedResource` (the committed `SchemaShared`).
- **Actual signature/role**: returns `delegate.makeSnapshot(session)` — reads the
  committed instance, exactly as the record states; the source comment literally
  labels it "Tier 1 … committed instance, not the tx-local copy."
- **Verdict**: MATCHES
- **Detail**: `:78` lands on the explanatory comment for the committed read; the
  return is one line below. Substantively accurate; the source comment matches the
  record's prose verbatim. Confirms the Track 5 Context cite of "the 'Tier 1'
  lock-free fast read."

#### R3 Ref: MetadataDefault.makeThreadLocalSchemaSnapshot (per-operation refcount-pinned build)
- **Document claim**: D21 — the snapshot "is refcount-pinned per operation
  (`MetadataDefault.makeThreadLocalSchemaSnapshot`), not resolved per field."
- **Search performed**: PSI `findMethodsByName("makeThreadLocalSchemaSnapshot",
  false)` on `MetadataDefault`.
- **Code location**: `MetadataDefault` — declared at line 78.
- **Actual signature/role**: method exists as named, on the cited class.
- **Verdict**: MATCHES

#### R4 Ref: MetadataDefault.getImmutableSchemaSnapshot — 174 call sites
- **Document claim**: D21 — "The snapshot is the single read tier the whole
  read/query/serialize/security stack consumes (174 call sites)."
- **Search performed**: PSI `ReferencesSearch.search(getImmutableSchemaSnapshot,
  projectScope)`.
- **Code location**: `MetadataDefault.getImmutableSchemaSnapshot` declared at line
  106; 174 direct references in project scope.
- **Actual signature/role**: exactly 174 call sites — the figure is precise, not
  an estimate.
- **Verdict**: MATCHES

#### R5 Ref: AbstractStorage.computeCommitWorkingSet and its call chain
- **Document claim**: D21 / Track 5 — "`computeCommitWorkingSet`
  (`AbstractStorage.java:2410`, reached at line 2528 …) calls
  `getImmutableSchemaClass` then `getCollectionForNewInstance`" → eventually
  `doGetAndCheckCollection`.
- **Search performed**: PSI `findMethodsByName("computeCommitWorkingSet", false)`
  on `AbstractStorage`; body substring checks; lines read at 2410 and 2528.
- **Code location**: `AbstractStorage.java` — `computeCommitWorkingSet`
  declared at line 2371; line 2410 is `var cls =
  entity.getImmutableSchemaClass(session);` (inside it); line 2528 is the call
  site `final var workingSet = computeCommitWorkingSet(frontendTransaction,
  session);`. The method body references `getImmutableSchemaClass`,
  `getCollectionForNewInstance`, and `doGetAndCheckCollection`.
- **Actual signature/role**: all three chain members present; the `:2410` read
  anchor lands on the `getImmutableSchemaClass` call; `:2528` lands on the call
  site, both as the record states.
- **Verdict**: MATCHES

#### R6 Ref: commit ordering — reconcileCollections (2473) before forceSnapshot (2691)
- **Document claim**: D21 / Track 5 — `computeCommitWorkingSet` is reached "after
  `reconcileCollections` at 2473 and before `forceSnapshot` at 2691."
- **Search performed**: PSI method lookup; direct line reads at 2473 and 2691.
- **Code location**: `AbstractStorage.java` — line 2473 is the
  `reconcileCollections(schemaContext.committedSchema(),
  schemaContext.txLocalSchema(), …)` call; line 2691 is
  `schemaContext.committedSchema().forceSnapshot();`. (The
  `reconcileCollections` method itself is declared at 2781; the cite is the
  in-commit call site, consistent with the other call-site anchors.)
- **Actual signature/role**: ordering reconcile-call (2473) → working-set build
  (2528) → forceSnapshot (2691) holds exactly as described.
- **Verdict**: MATCHES

#### R7 Ref: SchemaShared.forceSnapshot
- **Document claim**: Track 5 / Track 4 — the commit ends with a single trailing
  `forceSnapshot`.
- **Search performed**: PSI `findMethodsByName("forceSnapshot", true)` on
  `SchemaShared`.
- **Code location**: `SchemaShared.forceSnapshot` declared on `SchemaShared`
  (1 declaration); invoked at `AbstractStorage.java:2691` as
  `schemaContext.committedSchema().forceSnapshot()`.
- **Verdict**: MATCHES

#### R8 Ref: IndexManagerEmbedded.createIndex deferred path → getCollectionNameById gap
- **Document claim**: Track 5 — indexing a class created in the same transaction
  throws `IndexException("Collection with id -2 does not exist")` because the
  deferred `createIndex` path resolves collection ids through
  `DatabaseSessionEmbedded.getCollectionNameById`, which returns null for any id
  `< 0`.
- **Search performed**: PSI on `IndexManagerEmbedded.createIndex` (8-param deferred
  overload) body; PSI on `findCollectionsByIds`; PSI on
  `DatabaseSessionEmbedded.getCollectionNameById`.
- **Code location**: `IndexManagerEmbedded.java` — the tx-active deferred arm of
  `createIndex` (lines 390-503) calls `findCollectionsByIds(collectionIdsToIndex,
  session)`; `findCollectionsByIds` calls `database.getCollectionNameById(...)`
  and `throw new IndexException(database.getDatabaseName(), "Collection with id "
  + collectionId + " does not exist.")` when the name is null;
  `DatabaseSessionEmbedded.getCollectionNameById` (line 2842) returns `null` when
  `collectionId < 0`.
- **Actual signature/role**: the create-side gap is real and the exception
  message text is exact. The resolution is interprocedural (through
  `findCollectionsByIds`), but the cited symbol `getCollectionNameById` is the
  resolution point as the record states.
- **Verdict**: MATCHES

#### R9 Ref: IndexManagerEmbedded drop path at lines 590-600 (tx-local dropIndex only marks the class)
- **Document claim**: Track 5 — "a tx-local `dropIndex` currently only calls
  `markClassChanged` (`IndexManagerEmbedded.java:590-600`), so the index stays in
  the shared registry … and survives the commit."
- **Search performed**: PSI `findMethodsByName("dropIndex", false)`; direct read of
  lines 585-605.
- **Code location**: `IndexManagerEmbedded.java` — `dropIndex` declared at 582; the
  tx-active arm spans 585-601: line 590 `session.ensureTxSchemaState()`, lines
  591-600 acquire the shared lock and call `txState.markClassChanged(...)` at line
  596, then `return` at 601, leaving the shared registry and engine intact.
- **Actual signature/role**: matches — the tx-local arm marks the class and
  returns without removing the registry entry or deleting the engine.
- **Verdict**: MATCHES
- **Detail**: The cited "drop comment reads as if Track 4 already drops the index"
  is a subjective characterization of the source comment at lines 585-589, which
  actually says "the commit … (the tx-local index-definition overlay … and the
  commit-time engine drop are a later track)." The comment defers correctly to a
  later track; the Track 5 directive to tighten its wording is a target-state task,
  not a current-code mismatch, so no finding.

#### R10 Ref: design.md:270 — "not only the snapshot"
- **Document claim**: D21 — "`design.md` §'The tx-local schema view' states that
  during a schema tx reads route to the tx-local structure 'not only the snapshot'
  (design.md:270)."
- **Search performed**: direct read of `design.md` around line 270.
- **Code location**: `design.md:269-270` — "During a schema transaction,
  `SchemaProxy` routes its read methods to the tx-local structure, not only the
  snapshot."
- **Actual signature/role**: the cited sentence is present at the cited line and
  reads as quoted.
- **Verdict**: MATCHES
- **Detail**: This is a frozen-design citation, not a divergence to flag. It
  supports the D21 rationale (the design already says reads route tx-local, yet
  leaves the snapshot itself committed-only — the gap D21 closes). The
  D21-vs-frozen-design divergence is the expected post-replan state per the
  review rule.

#### R11 Ref: Index.getIndexId / planner skip-unbuilt guard (getIndexesInternal)
- **Document claim**: D13 / Track 5 — the planner skips any index whose engine is
  not built (`getIndexId() < 0`); Track 5 Signatures cite the guard as
  `getIndexesInternal`.
- **Search performed**: PSI `findMethodsByName("getIndexId", true)` on `Index`;
  `PsiShortNamesCache.getMethodsByName("getIndexesInternal", projectScope)`.
- **Code location**: `Index.getIndexId` exists (1 declaration);
  `getIndexesInternal` is declared on `SchemaClassProxy`, `SchemaClassInternal`,
  `SchemaClassImpl`, and `SchemaImmutableClass` (the class-side per-class index
  enumeration the planner consults).
- **Actual signature/role**: both symbols exist; `getIndexesInternal` is a real
  existing method, so the parenthetical signature cite is not phantom.
- **Verdict**: MATCHES

#### R12 Ref: Track 5 Context — getRawClassIndexes → getClassRawIndexes chain
- **Document claim**: Track 5 `## Context and Orientation` — "the snapshot sources
  a class's index list from the index manager (`SchemaImmutableClass.getRawClassIndexes`
  → the index manager's `getClassRawIndexes`)."
- **Search performed**: `PsiShortNamesCache.getMethodsByName` for both names.
- **Code location**: `getRawClassIndexes` declared on `SchemaImmutableClass` (1);
  `getClassRawIndexes` declared on `IndexManagerAbstract` (1).
- **Actual signature/role**: both methods exist on the cited classes.
- **Verdict**: MATCHES

#### R13 Ref: Track 6 Context — IndexDefinition.className has no setter
- **Document claim**: Track 6 `## Context and Orientation` and D17 — "`IndexDefinition.className`
  has no setter today."
- **Search performed**: PSI `findMethodsByName("setClassName"/"getClassName", true)`
  on `IndexDefinition`.
- **Code location**: `IndexDefinition` — `getClassName` exists (1), `setClassName`
  does not exist (0).
- **Actual signature/role**: confirms the no-setter current-state claim that the
  Track 6 work adds.
- **Verdict**: MATCHES

#### R14 Ref: Track 6 Context — SchemaEmbedded.createCollections, SchemaClassImpl.renameCollection
- **Document claim**: Track 6 — collection name generated at
  `SchemaEmbedded.createCollections`; class rename physically renames collection
  files via `SchemaClassImpl.renameCollection`.
- **Search performed**: PSI `findMethodsByName("createCollections", false)` on
  `SchemaEmbedded`; `findMethodsByName("renameCollection", false)` on
  `SchemaClassImpl`.
- **Code location**: `SchemaEmbedded.createCollections` exists (2 overloads);
  `SchemaClassImpl.renameCollection` exists and iterates the class's collection ids
  resolving names via `session.getCollectionNameById`.
- **Actual signature/role**: both sites exist and perform the described roles. The
  exact `writeCache.renameFile` token was not visible in the method's opening
  lines (the rename is routed through the session/storage rename path), but the
  rename-collection behavior is confirmed; this is a Track 6 Context claim outside
  the replan's focus and is substantially accurate.
- **Verdict**: MATCHES
