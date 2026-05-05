# Track 15 — Phase C Code Review (iter-1 synthesis)

Five sub-agents reviewed `git diff 587dfae4e6..HEAD` (8,936 lines, 41 test
files in `core/db/tool*` and `core/record*`). All 6 steps tagged
`risk: medium` — entire diff is a focal point.

**Total findings: 3 blockers / 27 should-fix / 24 suggestions.**

## Blockers (must fix before merge)

### CQ1 [blocker] — Ephemeral identifier rule violations
**Location**: ~21 sites across the diff. Top offenders:
- `EntityImplTest.java:758,759,7868,7876,7881,7893,7915,7928,7934` —
  `// see Track 15 out-of-scope notes for ... Tracks 19–21`,
  `forwarded to ... When the deferred-cleanup track ...`,
  `(coverage) belong to Tracks 19–21. ... is forwarded to Tracks 19–21`
- `RecordBytesTestOnlyOverloadTest.java:8112` —
  `This pin is the implementable form of the Track 12 backlog item`
- `VertexAndEdgeTest.java:8673` — `(Track 11)`
- 12 importer test files at line ~65 + `EntityImplTest.java:354` —
  `Defensive {@code @After} (Track 5+ idiom)`
- `DatabaseExportImportRoundTripTest.java:124` —
  `verified by Step 5 in the importer-converter coverage track`

**Issue**: `.claude/workflow/ephemeral-identifier-rule.md` forbids
`Track N`, `Step N`, `Tracks N–M`, finding IDs, etc. in source code,
Javadoc, test names, and test descriptions because the `_workflow/`
directory is purged in the Phase 4 cleanup commit — every reference
becomes a dangling pointer the moment the PR squashes into `develop`.
**Fix**: Sweep all forbidden phrasings to durable equivalents (e.g.
`Track 5+ idiom` → `defensive @After idiom`; `Tracks 19–21` → `the
deferred page-frame / foreign-memory test suite`; `Track 12 backlog
item` → `the MemoryStream rewrite cleanup item in the deferred-cleanup
queue`). Verify clean with:
`grep -rE 'Track [0-9]+|Step [0-9]+' core/src/test/java/com/jetbrains/youtrackdb/internal/core/{record,db/tool}`

### TB1 [blocker] — Vacuous `assertSame(X.class, X.class)` tautologies
**Location**:
- `EntityComparatorDeadCodeTest.chainDeadVia_EntityHelperSort_andTestOnlyReachableViaCRUDDocumentValidationTest`
  (lines 187–207): `assertSame(EntityHelper.class, EntityHelper.class)`,
  `assertSame(Pair.class, Pair.class)`,
  `assertSame(CommandContext.class, CommandContext.class)`,
  `assertSame(DBRecord.class, DBRecord.class)` — all tautological.
- `EntityHelperDeadCodeTest.sortChainTargetEntityComparatorIsObservedInTheClasspath`
  (lines 372–388) — same pattern.
- `RecordVersionHelperDeadCodeTest.declaresOnlyTheSerializedSizeField:243` —
  `assertNotNull(BinaryProtocol.class)` (a class literal is never null).

**Issue**: Class-literal-to-itself comparisons always pass. The asserted
"force these symbols to remain on the test classpath" intent is already
satisfied by the import line. No production-side mutation can ever
flip the assertion.
**Fix**: Drop the vacuous `assertSame`/`assertNotNull` lines. Move any
documentation intent into class Javadoc.

### TB2 [blocker] — Round-trip link content fidelity weakened to size-only
**Location**: `DatabaseExportImportRoundTripTest.roundTripPreservesEntityContentForUnambiguousTypes`
(lines 246–259, 459–472).

**Issue**: For LINKLIST / LINKMAP / LINKBAG payloads the test compares
only `linkCollectionSize(srcLinks) == linkCollectionSize(impLinks)`.
A regression that produced the right number of links but wrong rids
would pass silently. The track's stated mandate is full
`EntityHelper.hasSameContentOf` fidelity, with `RIDMapper`.
**Fix**: Build a source→import RID map keyed by the `name` property
and pass it as a `RIDMapper` argument to `EntityHelper.hasSameContentOf`
for ALL entities including link-bearing ones. `EntityHelper.RIDMapper`
is independent of `DatabaseCompare`.

## Should-fix (in scope for iter-1)

Highest-leverage / most-overlapped items selected for iter-1:

### CQ2 / TS2 — `setupRidMapping` duplicated 7 ways
Identical 13-line helper across 7 importer test files. Extract a
package-private `ImporterTestFixtures.setupRidMapping(session, from, to)`
under `core/src/test/java/.../db/tool/importer/`.

### BC1 — Round-trip session-activation lifecycle on exception
`DatabaseExportImportRoundTripTest` lines ~756–828: nested
`importSession.executeInTx(...) → session.executeInTx(...) →
importSession.activateOnCurrentThread()` — the trailing re-activation
is unreachable on any exception inside the inner `txSource` lambda,
leaving `session` (source) active when the outer transaction unwinds.
**Fix**: Wrap the inner activation pair in try/finally:
`try { session.executeInTx(...); } finally {
importSession.activateOnCurrentThread(); }`.

### TS1 — `EntityImplTest` leaks side-DB without drop
`testRemovingReadonlyField` (line 192), `testUndo` (line 224) call
`youTrackDB.create(dbName, DatabaseType.MEMORY, ...)` without a
matching `drop(dbName)` in finally.
**Fix**: Wrap each in `try { ... } finally { youTrackDB.drop(dbName); }`,
mirroring `testKeepSchemafullFieldTypeSerialization`.

### BC2 — `DatabaseRecordWalkerTest` internal-collection guard vacuous
`internalCollectionIsAlwaysSkippedEvenWithoutExplicitExclusion` compares
each visited rid against `session.getCollectionIdByName("internal")` but
does not assert the collection exists. If `getCollectionIdByName` returns
`-1` (collection absent for the fixture), the loop comparison is
vacuously satisfied.
**Fix**: At test entry add `var internalId =
session.getCollectionIdByName(MetadataDefault.COLLECTION_INTERNAL_NAME);
assertNotEquals(-1, internalId);` before the loop, then explicitly
exclude every collection name with that id from the exclude-set
removal.

### BC3 — `testCyclicEmbeddedReferenceDoesNotInfiniteLoop` is acyclic
The body builds a strict tree (a → b → c → deep) — no cycle. The pin
does not exercise the recursion-guard `inspected` set logic.
**Fix**: Rename to `testDeeplyNestedEmbeddedReferenceDoesNotStackOverflow`
to match the body. (Genuine cycle injection would require reflective
parent-map manipulation — out of scope for iter-1 cleanup; the rename
preserves honesty.)

### TC1 — `LinksRewriter.visitField(null)` not exercised
The most-frequent input shape (cleared link / null link payload) is
not pinned. **Fix**: Add `testVisitFieldOnNullValueReturnsNull`.

### TC2 — Null-element converter tests missing for List/Set/Map/Bag
`AbstractCollectionConverter.convertSingleValue` has an explicit
`item == null` arm preserved per per-collection converter dispatch.
**Fix**: Add a `*WithNullElementReturnedByReferenceWhenNoChange` test
to each of `LinkListConverterTest`, `LinkSetConverterTest`,
`LinkMapConverterTest`, `LinkBagConverterTest`.

## Deferred to iter-2 (gate check)

Selected representative iter-2 items if iter-1 fixes pass: CQ4
(license banners), CQ8 (split mega-test), TB3/TB6 (bundled
assertions), TC3 (round-trip empty/edges/blobs/sequences), TC4
(boundary values), TC5 (multi-page walker), TS3/CQ6 (regex sentinel
in RecordBytesTestOnlyOverloadTest).

## Deferred to Track 22 / suggestion-only

Most of the remaining ~24 suggestions and several should-fix items
that exceed track scope:
- CQ4 (license banner alignment) — defer to consistency sweep.
- CQ5 (inline FQN imports) — Spotless cosmetic.
- CQ8 (mega-test split) — restructure-tier, not regression-tier.
- CQ9 (test method naming) — style nit.
- BC4 (EntityEntryTest mock-EntityImpl brittleness) — implicit pin, low-risk.
- BC5/CQ6/TS3 (RecordBytesTestOnlyOverloadTest source walker) — sentinel
  brittleness; the WHEN-FIXED Javadoc is sufficient.
- BC6 (importDb name UUID suffix) — acceptable under MEMORY storage.
- BC7 (EdgeIterator null-skip-before-load) — implicit pin.
- TB3/TB6 (bundled type assertions in DocumentValidationTest /
  DocumentFieldConversionTest) — split if iter-2 still fails.
- TB4 (substring shape pin in EntityComparatorDeadCodeTest) — strengthen
  in iter-2 if blockers remain.
- TB5 (DatabaseImpExpAbstractTest weak shape pins) — consider iter-2.
- TB7 (testToJsonContainsPropertyNames substring) — defer to iter-2.
- TB8 (embedded converter Identifiable arm) — defer to Track 22 with
  embedded-Identifiable shape work.
- TB9 (`assertNull` redundant) — minor cleanup.
- TB10 (HashSet.toArray order) — only 1-element sets affected, low-risk.
- TB11–12 (vacuous `assertNotNull`) — minor cleanup.
- TC5 (multi-page walker) — production scale gap; consider iter-2.
- TC6 (EdgeIterator null multiValue arm) — defer.
- TC7–12 — boundary/symmetry items deferred to Track 22.
- TS4–9 — class-split, naming, banner, custom assertArrayEquals,
  abstract-test-name suffix — cosmetic / deferable.
