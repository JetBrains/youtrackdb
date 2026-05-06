# Track 16 — Phase C Code Review (iter-2 gate check)

Six sub-agents (CQ/BC/TB/TC/TX/TS) re-checked iter-1 fix commit
`075ed92aaf` against `git diff 7ccf28f311..HEAD` and surfaced
new findings on the cumulative track diff. The iter-1 review file
was not saved as a separate document; the iter-1 fix commit message
served as the authoritative summary of what the iter-1 sweep applied.

## Verdict per dimension

| Dim | Verdict | Notes |
|---|---|---|
| CQ | PASS | Both iter-1 items VERIFIED (ephemeral-identifier strip clean under broader regex; reflective signature pins concrete and falsifiable). New: **CQ5 should-fix** (3 FQ JUnit asserts), CQ6/7/8/9 suggestions (style FQ refactors). |
| BC | PASS | All 5 iter-1 items VERIFIED (`CopyOnWriteArrayList` failure collector; `readerAtLockAttempt` bounded spin; positive `bEnteredWhileAHeld` latch; `aHolding`/`bAttemptingAcquire` handoff sound; NPE-narrowed `dropFunction` catch). New: BC5/6/7 suggestions (diagnostic clarity). |
| TB | **FAIL** | TB items 1, 3, 4, 5 VERIFIED. **TB item 2 STILL OPEN** — `configIsNoOp` post-state pin structurally vacuous because `getResult()` returns hardcoded `null` and `getName(session)` reads a final field; a regression that stashed config args in a new private field would silently pass. New: **TB9 should-fix** (`deprecatedCreateIsIdempotent` exercises empty library — count==0 cannot distinguish "no-op" from "wipes everything"); TB10 suggestion. |
| TC | PASS | Both iter-1 items VERIFIED (4 STRING-arm branches pinned in `PropertyTypeInternalStringConvertTest`; reflective method/field signature pins cover the entire deletion lockstep groups). New: **TC9/10/11 should-fix** (Result-arm coverage gaps in LINK / EMBEDDEDMAP; malformed-JSON arm in EMBEDDED); TC12/13 suggestions. |
| TX | PASS | Both iter-1 items VERIFIED (`spawn()` + `@After` join discipline mirrored across both test files; race fixes sound). New: **TX5/TX6 should-fix** (one-sided invariant in `writersAreSerializedAcrossThreads` needs documenting; `joinSpawnedWorkers` lacks daemon flag + `isAlive()` check, leaks worker as non-daemon if a latch path stalls); TX7 suggestion. |
| TS | PASS | Both iter-1 items VERIFIED (tracked `spawn()` + `@After` discipline uniform across `SchemaProxyBoundaryTest` and `SchemaSharedLockApiTest`; ephemeral-identifier strip clean across the 5 rewritten files). New: **TS10 should-fix** (mixed naming conventions in pre-existing `FunctionLibraryTest` — `test*` vs `actionDescribesOutcome` style); TS5–9 suggestions (DRY hoist candidates routed to absorption queue). |

Aggregate: **1 STILL OPEN + 8 new should-fix + ~17 suggestions, no
blockers.** Iter-2 fix commit `9409a1a66d` resolves the STILL OPEN
item plus the should-fix items in scope; the remaining should-fix
items (TC9/10/11, TS10) and all suggestions are routed to the
deferred-cleanup track's absorption queue per the Track 12 / 14 / 15
precedent of forwarding test-additions and pre-existing-code
readability nits rather than expanding the current track.

## Iter-2 fixes applied (commit 9409a1a66d)

1. **TB STILL OPEN (configIsNoOp structurally vacuous)** — replaced
   `configIsNoOp` in `DatabaseFunctionRoundTripTest` with
   `configIsNoOpAndAdapterHasNoStateBeyondFunction`. The new pin asserts
   `DatabaseFunction` has exactly one non-synthetic field — the final
   `Function f` reference — so there is nowhere for `config(args)` to
   stash state. A regression that adds a `private Object[] cfg` field
   to absorb config arguments would FAIL the field-count assertion. The
   `config(...)` call exercises the line for coverage.
2. **TB9 [should-fix]** — `SequenceLibraryProxyTest.deprecatedCreateIsIdempotent`
   now populates the library with `IdemSeq` before invoking `create()`
   twice, then asserts both the populated count is preserved AND the
   sequence remains visible. The previous empty-library form was
   satisfiable by both "no-op" and "wipes everything" implementations.
3. **TX5 [should-fix]** — comment update on
   `SchemaSharedLockApiTest.writersAreSerializedAcrossThreads` documenting
   the one-sided invariant: `aReleased.set(true)` runs strictly BEFORE
   the actual `lock.writeLock().unlock()` (per
   `SchemaShared.releaseSchemaWriteLock`), so a true `bEnteredWhileAHeld`
   is conclusive proof of breakage but a false result is not proof of
   correct serialization. The test is positioned as a positive smoke
   gate, not a full invariant pin.
4. **TX6 [should-fix]** — hardened the tracked `spawn()` +
   `joinSpawnedWorkers()` helper in BOTH `SchemaSharedLockApiTest` and
   `SchemaProxyBoundaryTest`. Workers are now marked daemon
   (`t.setDaemon(true)`) so a leaked worker cannot keep the surefire
   forked JVM alive past the test method, and the `@After` hook asserts
   `!t.isAlive()` after `t.join(5_000)` with `t.interrupt()` +
   `fail(...)` so a stalled worker fails the test loudly instead of
   silently lingering.
5. **CQ5 [should-fix]** — static-imported `org.junit.Assert.fail` /
   `org.junit.Assert.assertNotSame` in three files (`FunctionLibraryTest`,
   `FunctionRecordRoundTripTest`, `CollectionSelectionFactoryDeadCodeTest`)
   so the FQ call sites use the same shape as the rest of the track.

Tests: 90 / 90 pass on the seven touched classes; Spotless clean.

## Findings deferred to the deferred-cleanup track absorption queue

These are real should-fix items but their fix shape is test-additions
or pre-existing-code modifications that fit naturally into the
absorption track's queue, consistent with the precedent set by
Tracks 12 / 14 / 15 (test-additions for missed switch arms,
pre-existing-test naming, and DRY hoist candidates were routed to the
absorption queue rather than expanding their owning track):

- **TC9 [should-fix]**: `PropertyTypeInternal.LINK.convert(...)`
  `Result` arm has three uncovered sub-paths
  (`isIdentifiable()→asIdentifiable()` short-circuit;
  `isProjection()→toMap()` recurse; neither→post-switch throw at
  `PropertyTypeInternal:861`). Add three tests under the appropriate
  link-convert test class with `ResultInternal` setup using
  `setIdentifiable(rid)` / `setProperty(...)`.
- **TC10 [should-fix]**: `PropertyTypeInternal.EMBEDDEDMAP.convert(...)`
  `Result` arm has two uncovered sub-paths (projection→entries-from-
  property-names; non-projection→wrap-under-`"value"`-key). Add two
  tests under the appropriate embeddedmap-convert test class.
- **TC11 [should-fix]**: `PropertyTypeInternal.EMBEDDED.convert(...)`
  String arm calls `JSONSerializerJackson.INSTANCE.fromString(...)`
  which throws on malformed JSON. Add an `assertThrows` test for the
  parse-failure path; tighten exception class once the first run
  reveals the surfaced type.
- **TS10 [should-fix]**: pre-existing `testSimpleFunctionCreate`,
  `testDuplicateFunctionCreate`, `testFunctionCreateDrop` in
  `FunctionLibraryTest` use the legacy `test*` prefix while every
  Track 16-added test uses `actionDescribesExpectedOutcome` style.
  Either rename the three pre-existing methods or add per-method
  one-line comments explaining what they pin. Pre-existing code,
  acceptable to defer.
- **BC5/6/7 [suggestion]**: diagnostic-clarity nits in
  `SchemaSharedLockApiTest.multipleReadersAreConcurrent` (in-test
  join loop duplicates `@After` join), the `bAttemptingAcquire`
  countdown ordering relative to `acquireSchemaWriteLock` in
  `writersAreSerializedAcrossThreads` (microsecond-scale window where
  A could exit the latch wait while B is still in JIT/scheduling), and
  `SchemaProxyBoundaryTest.inactiveSessionTriggersSessionNotActivatedException`
  could use an `assertFalse(t.isAlive())` after the in-test join for
  clearer diagnostic on a stuck worker.
- **CQ6/7/8/9 [suggestion]**: FQ-type usages in
  `FunctionLibraryTest` (`EntityImpl` cast),
  `PropertyTypeInternalLinkConvertTest` (`RID` field type),
  `ImmutableSchemaPropertyShapeTest` (`DefaultCollate`, `HashMap`
  inside lambdas), and `SchemaClassOperationsTest` (redundant
  `dbSession = session` aliases).
- **TC12/13 [suggestion]**: `BalancedCollectionSelectionStrategyDeadCodeTest`
  REFRESH_TIMEOUT cache-hit branch (low priority — class is dead-code
  scheduled for deletion); `PropertyTypeInternalNumericConvertTest`
  boundary values (`Long.MAX_VALUE→Integer/Short`, `Double.NaN→Long`,
  `Double.POSITIVE_INFINITY→Long`).
- **TX7 [suggestion]**: drop body-level join loop in
  `multipleReadersAreConcurrent` (5s+ latency penalty when a real
  failure occurs; the `failures` collector + `@After` join already
  handle the contract).
- **TS5/6/7/8/9 [suggestion]**: DRY hoist candidates — extract
  `TrackedSpawnTestSupport` once N≥3 consumers exist (currently 2);
  collapse duplicated `classExposesExpectedPublicSurface` helpers in
  `BalancedCollectionSelectionStrategyDeadCodeTest` /
  `DefaultCollectionSelectionStrategyDeadCodeTest` (both scheduled
  for lockstep deletion); add `currentSnapshot()` /
  `snapshotClass(name)` helpers in `ImmutableSchemaShapeTest`; split
  `SchemaClassOperationsTest` (805 LOC, 31 tests) into thematic
  siblings; consider a shared row-schema for the
  `PropertyTypeInternal*ConvertTest` siblings.
- **TB10 [suggestion]**: drop misleading "Void setters … pinned by
  absence of compile-time assertion" comment in
  `FunctionRecordRoundTripTest.settersAreFluentWhereTheyReturnFunctionAndOverwriteFields`,
  or add a reflective return-type pin on `setCode` / `setLanguage`.

## Iter-3 gate check (next session)

Iter-3 should re-spawn the dimensions that had open should-fix items
or STILL OPEN entries — TB, TX, CQ. BC and TS PASSed in iter-2 with
only suggestion-tier residue; TC PASSed with all iter-1 items
VERIFIED and only deferred-track-bound should-fix items. The minimum
iter-3 surface is therefore TB + TX + CQ; including TS and TC for
completeness is acceptable but not required by the iteration
protocol.
