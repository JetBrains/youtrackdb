# Track 5 Technical Review

## Review scope
Track 5: Enable and extend TransactionTest edge SI tests

## Findings

### Finding T1 [blocker]
**Location**: Plan "Enable existing disabled tests" + TransactionTest.java lines 792, 870, 1680
**Issue**: The 3 disabled tests operate with 1-5 edges, well below the BTree
threshold of 40 (`GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD`).
Simply removing `@Ignore` would test embedded edge storage (where SI works via
vertex-level record SI), NOT the BTree-backed LinkBag SI implemented in Tracks 1-4.
This would be a false positive — tests pass without exercising the new code.
**Proposed fix**: Each test must set
`GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1)` to
force BTree mode even for 1 edge. Save/restore the original value in try-finally.
Established pattern exists in `LinkBagAtomicUpdateTest` (lines 28-42). This is
cleaner and faster than creating 40+ edges per test.
**Decision**: ACCEPTED — all tests will force BTree mode via config threshold.

### Finding T2 [should-fix]
**Location**: Plan "Write new tests" — "Edge label filter under SI"
**Issue**: Edge labels correspond to schema classes extending `E`. The label filter
test must call `db.createEdgeClass("LabelA")` etc. before creating edges with
those labels, otherwise label-based filtering won't find edges regardless of SI.
**Proposed fix**: Add schema setup to the label filter test.
**Decision**: ACCEPTED — step decomposition will include schema setup.

### Finding T3 [should-fix]
**Location**: Plan "Constraints" section
**Issue**: Using config-based BTree forcing (`threshold=-1`) is cleaner than
creating 40+ edges per test. Overlaps with T1.
**Decision**: MERGED WITH T1.

### Finding T4 [suggestion]
**Location**: Plan "Enable existing disabled tests"
**Issue**: Consider keeping original tests for embedded edges AND adding BTree
variants for double coverage.
**Decision**: NOTED — embedded edge SI is already covered by vertex-level SI tests
(e.g., `testSIEdgePropertyIsolationMultiThread` at line 713, which passes without
@Ignore). Modifying the disabled tests to use BTree is the correct approach.

### Finding T5 [suggestion]
**Location**: Plan "Write new tests" — "Snapshot fallback for deleted edges"
**Issue**: This scenario overlaps with `testSIEdgeDeletionIsolationMultiThread`.
The new test should specifically test discovery via `getEdges(Direction.OUT)`
iteration (BTree spliterator visibility resolution) rather than direct
`loadEdge(rid)` (which goes through PaginatedCollectionV2 SI).
**Decision**: ACCEPTED — new deletion test will use iteration-based discovery.

### Finding T6 [suggestion]
**Location**: Plan "Constraints" — storage type
**Issue**: All TransactionTest tests use `DatabaseType.MEMORY`. No explicit disk
testing mentioned.
**Decision**: NOTED — CI runs tests with `-Dyoutrackdb.test.env=ci` which uses
disk storage. The test infrastructure handles this transparently.

## Summary
- 1 blocker (T1) — addressed by forcing BTree mode via config threshold
- 2 should-fix (T2, T3) — schema setup for label tests, merged with T1
- 3 suggestions (T4, T5, T6) — noted and incorporated where appropriate
- **PASS** — no unresolved blockers
