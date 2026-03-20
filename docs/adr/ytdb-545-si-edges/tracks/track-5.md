# Track 5: Enable and extend TransactionTest edge SI tests

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/4 complete)
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

- [ ] Step 3: Snapshot fallback via iteration and edge label filter tests
  Write 2 new BTree-forced SI tests:
  (a) **Snapshot fallback for deleted edges via iteration**: Reader opens
  snapshot, writer deletes an edge and commits. Reader iterates
  `getEdges(Direction.OUT)` — the deleted edge must still appear in the
  iteration (BTree spliterator falls back to snapshot index for the tombstone
  entry). This specifically tests the spliterator visibility resolution path,
  not direct `loadEdge(rid)`.
  (b) **Edge label filter under SI**: Create edges with different labels
  (schema setup: `createEdgeClass("Friend")`, `createEdgeClass("Colleague")`).
  Writer adds edges of label "Friend" and commits. Reader with old snapshot
  calls `getEdges(Direction.OUT, "Friend")` — must NOT see the new edges.
  Fresh reader must see them.
  Both tests use `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD=-1`.
  **Key files:** `TransactionTest.java` (modified)

- [ ] Step 4: Multiple readers same snapshot epoch test
  Write a new BTree-forced SI test: Two readers start at the same snapshot
  point (both open transactions before any writes). A writer modifies edges
  and commits. Both readers independently verify they see the same consistent
  pre-write edge state. Then fresh readers verify the committed state. This
  tests that the snapshot index correctly serves multiple concurrent readers
  from the same epoch. Uses `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD=-1`.
  **Key files:** `TransactionTest.java` (modified)
