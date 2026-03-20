# Track 5: Enable and extend TransactionTest edge SI tests

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
(to be recorded at Phase B start)

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Enable and fix the 3 disabled edge SI tests with BTree-forced mode
  Enable `testSIEdgeCreationIsolationMultiThread` (line ~792),
  `testSIEdgeDeletionIsolationMultiThread` (line ~870), and
  `testSIEdgeCountStabilityMultiThread` (line ~1680) by removing `@Ignore`.
  Each test must force BTree storage by setting
  `GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1)`
  at the start and restoring the original value in a try-finally. This ensures
  the tests exercise the BTree-backed LinkBag SI code from Tracks 1-4, not the
  embedded edge path. The edge creation test uses `getEdges(Direction.OUT)` to
  count edges. The deletion test uses `loadEdge(rid)` to verify the edge still
  exists for the old snapshot. The count stability test creates 3+2 edges and
  verifies the reader's count is stable. Validate all 3 pass. Adjust assertions
  or setup if the SI semantics require changes (e.g., cache clearing behavior).
  **Key files:** `TransactionTest.java` (modified)

- [ ] Step 2: Edge iteration consistency and concurrent creation/deletion tests
  Write 2 new BTree-forced SI tests:
  (a) **Edge iteration consistency**: While iterating `vertex.getEdges(OUT)`,
  a concurrent writer adds/modifies edges on the same vertex — the iterator
  must return a consistent snapshot. Hub vertex starts with N edges (e.g., 3),
  reader opens snapshot and begins iterating, writer adds more edges and commits,
  reader's iteration must still return exactly N edges.
  (b) **Concurrent edge creation and deletion**: One thread creates edges,
  another deletes edges on the same vertex — both transactions are isolated.
  Reader holds snapshot with N edges, writer deletes some and creates others,
  reader must still see original N edges.
  Both tests use `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD=-1` and follow
  the established CountDownLatch synchronization pattern.
  **Key files:** `TransactionTest.java` (modified)

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
