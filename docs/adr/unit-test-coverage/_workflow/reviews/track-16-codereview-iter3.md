# Track 16 — Phase C Code Review (iter-3 gate check)

Three sub-agents (TB / TX / CQ) re-checked iter-2 fix commit
`9409a1a66d` against `git diff 171f37df14..HEAD` (the post-rebase
Phase A SHA — note the step file's `## Base commit` field still
records the pre-rebase SHA `7ccf28f311…`, but the cumulative track
diff is anchored at `171f37df14`). Per the iter-2 review's iter-3
recommendation, only TB / TX / CQ were re-spawned — BC, TS, and TC
PASSed at iter-2 with only suggestion-tier residue or deferred-
absorption-bound should-fixes, and a third-pass on those dimensions
would not surface new actionable findings.

## Verdict per dimension

| Dim | Verdict | Notes |
|---|---|---|
| TB | PASS | All iter-1 items VERIFIED; iter-2 fixes for TB STILL OPEN (`configIsNoOpAndAdapterHasNoStateBeyondFunction`) and TB9 (`deprecatedCreateIsIdempotent` rewrite) verified clean — falsifiability checks pass for both. **0 new findings.** |
| TX | PASS | All iter-1 items VERIFIED; iter-2 fixes for TX5 (one-sided-invariant doc-comment in `writersAreSerializedAcrossThreads`) and TX6 (daemon flag + `!isAlive()` check + `interrupt()` + `fail(...)` discipline mirrored across both `SchemaSharedLockApiTest` and `SchemaProxyBoundaryTest`) verified clean. **0 new findings.** Minor directional note in the TX5 comment ("`version++` then unlock") was flagged as documentation hygiene only — load-bearing claim about `aReleased.set(true)` preceding the actual `lock.writeLock().unlock()` remains accurate. |
| CQ | PASS | All iter-1 items VERIFIED; iter-2 fix for CQ5 (static-imported JUnit `fail` / `assertNotSame` in three files) verified clean. Ephemeral-identifier self-check on durable content (test code, excluding `_workflow/`) is clean once the established `WHEN-FIXED: Track 22` deletion-marker convention is honoured — those are intentional cross-track lockstep markers, not workflow ephemera (precedent set by `CommandScriptDeadCodeTest`, `DatabaseScriptManagerTest`, `SqlScriptExecutorTest`, `CommandExecutorUtilityTest` in earlier tracks). **0 new findings.** |

Aggregate: **0 STILL OPEN, 0 REGRESSION, 0 new should-fix, 0 new
suggestions across TB / TX / CQ on iter-3.** Review loop closed.

## Items routed to the Track 22 absorption queue at iter-2

These were accepted as deferred at iter-2 and are reproduced here as
the canonical record of what the absorption queue receives from this
track. Plan corrections in `implementation-backlog.md` add them to
Track 22's "From Track 16 (Metadata Schema & Functions)" subsection.

**Should-fix tier (real test-additions or naming improvements,
deferred because the fix shape fits the absorption track better than
expanding the current track):**

- **TC9** — `PropertyTypeInternal.LINK.convert(...)` `Result` arm has
  three uncovered sub-paths
  (`isIdentifiable()→asIdentifiable()` short-circuit;
  `isProjection()→toMap()` recurse;
  neither→post-switch throw at `PropertyTypeInternal:861`). Add
  three tests under the appropriate link-convert test class with
  `ResultInternal` setup using `setIdentifiable(rid)` /
  `setProperty(...)`.
- **TC10** — `PropertyTypeInternal.EMBEDDEDMAP.convert(...)` `Result`
  arm has two uncovered sub-paths (projection→entries-from-property-
  names; non-projection→wrap-under-`"value"`-key). Add two tests
  under the appropriate embeddedmap-convert test class.
- **TC11** — `PropertyTypeInternal.EMBEDDED.convert(...)` String arm
  calls `JSONSerializerJackson.INSTANCE.fromString(...)` which throws
  on malformed JSON. Add an `assertThrows` test for the parse-failure
  path; tighten exception class once the first run reveals the
  surfaced type.
- **TS10** — pre-existing `testSimpleFunctionCreate`,
  `testDuplicateFunctionCreate`, `testFunctionCreateDrop` in
  `FunctionLibraryTest` use the legacy `test*` prefix while every
  Track 16-added test uses `actionDescribesExpectedOutcome` style.
  Either rename the three pre-existing methods or add per-method
  one-line comments explaining what they pin. Pre-existing code,
  acceptable to defer.

**Suggestion tier (~17 items, low-priority readability /
diagnostic-clarity nits routed to the absorption queue):**

- **BC5/6/7** — diagnostic-clarity nits in
  `SchemaSharedLockApiTest.multipleReadersAreConcurrent` (in-test
  join loop duplicates `@After` join), `bAttemptingAcquire`
  countdown ordering relative to `acquireSchemaWriteLock` in
  `writersAreSerializedAcrossThreads` (microsecond-scale window
  where A could exit the latch wait while B is still in JIT/
  scheduling), and
  `SchemaProxyBoundaryTest.inactiveSessionTriggersSessionNotActivatedException`
  could use an `assertFalse(t.isAlive())` after the in-test join for
  clearer diagnostic on a stuck worker.
- **CQ6/7/8/9** — fully-qualified-type usages in
  `FunctionLibraryTest` (`EntityImpl` cast),
  `PropertyTypeInternalLinkConvertTest` (`RID` field type),
  `ImmutableSchemaPropertyShapeTest` (`DefaultCollate`, `HashMap`
  inside lambdas), and `SchemaClassOperationsTest` (redundant
  `dbSession = session` aliases).
- **TC12/13** — `BalancedCollectionSelectionStrategyDeadCodeTest`
  REFRESH_TIMEOUT cache-hit branch (low priority — class is dead-
  code scheduled for deletion); `PropertyTypeInternalNumericConvertTest`
  boundary values (`Long.MAX_VALUE→Integer/Short`, `Double.NaN→Long`,
  `Double.POSITIVE_INFINITY→Long`).
- **TX7** — drop body-level join loop in
  `multipleReadersAreConcurrent` (5s+ latency penalty when a real
  failure occurs; the `failures` collector + `@After` join already
  handle the contract).
- **TS5/6/7/8/9** — DRY hoist candidates: extract
  `TrackedSpawnTestSupport` once N≥3 consumers exist (currently 2);
  collapse duplicated `classExposesExpectedPublicSurface` helpers
  in `BalancedCollectionSelectionStrategyDeadCodeTest` /
  `DefaultCollectionSelectionStrategyDeadCodeTest` (both scheduled
  for lockstep deletion); add `currentSnapshot()` /
  `snapshotClass(name)` helpers in `ImmutableSchemaShapeTest`;
  split `SchemaClassOperationsTest` (805 LOC, 31 tests) into
  thematic siblings; consider a shared row-schema for the
  `PropertyTypeInternal*ConvertTest` siblings.
- **TB10** — drop misleading "Void setters … pinned by absence of
  compile-time assertion" comment in
  `FunctionRecordRoundTripTest.settersAreFluentWhereTheyReturnFunctionAndOverwriteFields`,
  or add a reflective return-type pin on `setCode` / `setLanguage`.

## Review loop closure

Three iterations completed. Iter-1 surfaced 5 should-fix +
suggestions; iter-2 fixed TB STILL OPEN + 4 new should-fix items
across CQ/TB/TX, deferred test-additions and pre-existing-code
tweaks to Track 22; iter-3 gate check on the focal dimensions
(TB / TX / CQ) returned 0 STILL OPEN / 0 REGRESSION / 0 new
findings. Mark `Track-level code review` `[x]` in the step file's
Progress section and proceed to Track Completion.
