# Track 5: Enable and extend TransactionTest edge SI tests

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review

## Base commit
`c8e14c8c62`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Enable and fix the 3 disabled edge SI tests with BTree-forced mode
  > **What was done:** Removed `@Ignore` from the 3 disabled edge SI tests and
  > wrapped each in a try-finally that forces BTree-backed LinkBag storage via
  > `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD=-1`. All 3 tests pass without
  > any assertion or logic changes — the SI implementation from Tracks 1-4 is
  > fully functional at the graph API level.
  >
  > **What was discovered:** The `BTREE_TO_EMBEDDED_THRESHOLD` default is already
  > `-1` (disabled), so no reverse-downgrade can happen with small edge counts.
  > Only the `EMBEDDED_TO_BTREE_THRESHOLD` needs forcing.
  >
  > **Key files:** `TransactionTest.java` (modified)

- [x] Step 2: Edge iteration consistency and concurrent creation/deletion tests
  > **What was done:** Added 2 new BTree-forced SI tests:
  > (a) `testSIEdgeIterationConsistencyMultiThread` — reader opens iterator,
  > consumes 1 edge, writer commits 3 new edges, reader finishes iterating
  > the same iterator and sees only the original 5 edges. Tests true
  > mid-iteration SI stability.
  > (b) `testSIConcurrentEdgeCreationAndDeletionMultiThread` — writer deletes
  > 2 of 4 edges and adds 2 replacements. Reader's snapshot sees the exact
  > original edge set (asserted via target vertex names, not just count).
  >
  > **What was discovered:** Code review caught that the original iteration
  > test wasn't actually doing mid-iteration interleaving — fixed to consume
  > 1 edge before signaling the writer. Also strengthened the create/delete
  > test to assert on edge identity rather than count alone.
  >
  > **Key files:** `TransactionTest.java` (modified)

- [x] Step 3: Snapshot fallback via iteration and edge label filter tests
  > **What was done:** Added 2 new BTree-forced SI tests:
  > (a) `testSISnapshotFallbackForDeletedEdgeViaIteration` — writer deletes
  > an edge, reader's iteration still sees it via snapshot index fallback.
  > Asserts exact target vertex name set, not just count.
  > (b) `testSIEdgeLabelFilterMultiThread` — uses distinct "Colleague" and
  > "Friend" edge subclasses via `createEdgeClass()`. Writer adds "Friend"
  > edges; reader's label-filtered query sees 0 Friend, 2 Colleague.
  >
  > **What was discovered:** Auto-creation of edge classes with
  > `newStatefulEdge(v, v, "Friend")` fails with "not a regular edge class".
  > Must explicitly call `createEdgeClass("Friend")` before use. Also,
  > `getEdges(OUT, "E")` includes subclass edges due to class hierarchy,
  > so the test needed distinct classes ("Colleague", "Friend") rather than
  > mixing with the base "E" class.
  >
  > **Key files:** `TransactionTest.java` (modified)

- [x] Step 4: Multiple readers same snapshot epoch test
  > **What was done:** Added `testSIMultipleReadersSameSnapshotEpochMultiThread`
  > — two reader threads open transactions before writes, writer adds 2 edges
  > and deletes 1, both readers verify they still see 3 original edges. Fresh
  > tx verifies 4 edges (3 - 1 + 2). Uses per-thread `AtomicReference` for
  > error propagation after code review fix.
  >
  > **Key files:** `TransactionTest.java` (modified)
