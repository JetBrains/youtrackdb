package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests VertexFromLinkBagIterator behavior when loading vertices from LinkBag
 * secondary RIDs. Verifies:
 * <ul>
 *   <li>Normal iteration over valid vertex RIDs</li>
 *   <li>Graceful skipping of deleted records (RecordNotFoundException)</li>
 *   <li>Graceful skipping of non-vertex records</li>
 *   <li>Empty iterator behavior</li>
 *   <li>NoSuchElementException on exhausted iteration</li>
 *   <li>hasNext() idempotency</li>
 * </ul>
 */
public class VertexFromLinkBagIteratorTest {

  private DatabaseSessionEmbedded session;
  private FrontendTransactionImpl transaction;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    transaction = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
    // ResultInternal.checkSession asserts session.assertIfNotActive() — mock it so
    // ridIterator tests (which wrap bare RIDs in ResultInternal) pass the session guard.
    when(session.assertIfNotActive()).thenReturn(true);
  }

  /**
   * The iterator always loads from secondaryRid, not primaryRid. When the two differ
   * (heavyweight edge), verify it yields the vertex at secondaryRid.
   */
  @Test
  public void testLoadsVertexFromSecondaryRidNotPrimary() {
    var edgeRid = new RecordId(20, 1);
    var vertexRid = new RecordId(10, 1);
    var pair = RidPair.ofPair(edgeRid, vertexRid);
    mockLoadReturnsVertex(vertexRid);

    var iterator = new VertexFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    assertTrue(iterator.hasNext());
    assertEquals(
        "Should load vertex from secondary RID, not primary",
        vertexRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When the secondary RID points to a deleted record, the iterator should skip
   * that entry and continue to the next valid one.
   */
  @Test
  public void testSkipsDeletedRecordsGracefully() {
    var deletedRid = new RecordId(10, 1);
    var validRid = new RecordId(10, 2);

    var deletedPair = RidPair.ofPair(new RecordId(20, 1), deletedRid);
    var validPair = RidPair.ofPair(new RecordId(20, 2), validRid);

    when(transaction.loadEntity(deletedRid))
        .thenThrow(new RecordNotFoundException("test", deletedRid));
    mockLoadReturnsVertex(validRid);

    var iterator = new VertexFromLinkBagIterator(
        List.of(deletedPair, validPair).iterator(), session, 2);

    assertTrue(
        "Should have a next vertex after skipping deleted record",
        iterator.hasNext());
    assertEquals(validRid, iterator.next().getIdentity());
    assertFalse("Should have no more vertices", iterator.hasNext());
  }

  /**
   * When the secondary RID points to a non-vertex entity (e.g. an edge record),
   * the iterator should skip that entry gracefully.
   */
  @Test
  public void testSkipsNonVertexRecordsGracefully() {
    var nonVertexRid = new RecordId(10, 1);
    var vertexRid = new RecordId(10, 2);

    var nonVertexPair = RidPair.ofPair(new RecordId(20, 1), nonVertexRid);
    var vertexPair = RidPair.ofPair(new RecordId(20, 2), vertexRid);

    var nonVertex = mock(Entity.class);
    when(nonVertex.isVertex()).thenReturn(false);
    when(transaction.loadEntity(nonVertexRid)).thenReturn(nonVertex);

    mockLoadReturnsVertex(vertexRid);

    var iterator = new VertexFromLinkBagIterator(
        List.of(nonVertexPair, vertexPair).iterator(), session, 2);

    assertTrue("Should skip non-vertex and find the vertex", iterator.hasNext());
    assertEquals(vertexRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * An empty LinkBag should produce an iterator that immediately reports
   * no elements.
   */
  @Test
  public void testEmptyIterator() {
    var iterator = new VertexFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 0);

    assertFalse(iterator.hasNext());
  }

  /**
   * Calling next() after the iterator is exhausted should throw
   * NoSuchElementException.
   */
  @Test(expected = NoSuchElementException.class)
  public void testThrowsNoSuchElementWhenExhausted() {
    var iterator = new VertexFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 0);

    iterator.next();
  }

  /**
   * When all secondary RIDs point to deleted records, the iterator should yield
   * no elements at all.
   */
  @Test
  public void testAllRecordsDeletedYieldsEmptyIteration() {
    var rid1 = new RecordId(10, 1);
    var rid2 = new RecordId(10, 2);
    var pair1 = RidPair.ofPair(new RecordId(20, 1), rid1);
    var pair2 = RidPair.ofPair(new RecordId(20, 2), rid2);

    when(transaction.loadEntity(rid1))
        .thenThrow(new RecordNotFoundException("test", rid1));
    when(transaction.loadEntity(rid2))
        .thenThrow(new RecordNotFoundException("test", rid2));

    var iterator = new VertexFromLinkBagIterator(
        List.of(pair1, pair2).iterator(), session, 2);

    assertFalse(
        "All records deleted, should have no elements",
        iterator.hasNext());
  }

  /**
   * Calling hasNext() multiple times before next() should not advance past
   * elements. The lazy-load pattern must be idempotent.
   */
  @Test
  public void testHasNextIsIdempotent() {
    var vertexRid = new RecordId(10, 1);
    var pair = RidPair.ofPair(new RecordId(20, 1), vertexRid);
    mockLoadReturnsVertex(vertexRid);

    var iterator = new VertexFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    assertTrue(iterator.hasNext());
    assertTrue("Second hasNext() should still return true", iterator.hasNext());
    assertTrue("Third hasNext() should still return true", iterator.hasNext());
    assertEquals(vertexRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * size() returns the upper-bound count from the LinkBag, not the actual number
   * of vertices yielded. isSizeable() returns true when size is non-negative.
   */
  @Test
  public void testSizeReturnsUpperBoundFromLinkBag() {
    var iterator = new VertexFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 42);

    assertEquals(42, iterator.size());
    assertTrue(iterator.isSizeable());
  }

  /**
   * isSizeable() returns false when size is negative (unknown size).
   */
  @Test
  public void testIsSizeableReturnsFalseForNegativeSize() {
    var iterator = new VertexFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, -1);

    assertFalse(iterator.isSizeable());
  }

  /**
   * RidPair.validateEdgePair() rejects legacy lightweight pairs where primaryRid == secondaryRid.
   * After edge unification (YTDB-605), all edges must have distinct edge and vertex RIDs.
   */
  @Test(expected = IllegalStateException.class)
  public void testValidateEdgePairRejectsEqualRids() {
    var rid = new RecordId(10, 1);
    var pair = new RidPair(rid, rid);
    pair.validateEdgePair();
  }

  /**
   * RidPair.compareTo orders by primary RID. This is used when iterating
   * sorted collections of RidPairs (e.g., BTree-backed LinkBag entries).
   */
  @Test
  public void testCompareToPairOrdersByPrimaryRid() {
    var pair1 = RidPair.ofPair(new RecordId(10, 1), new RecordId(20, 1));
    var pair2 = RidPair.ofPair(new RecordId(10, 2), new RecordId(20, 2));
    var pair3 = RidPair.ofPair(new RecordId(11, 1), new RecordId(20, 3));

    assertTrue("Lower cluster position should come first",
        pair1.compareTo(pair2) < 0);
    assertTrue("Higher cluster position should come after",
        pair2.compareTo(pair1) > 0);
    assertTrue("Lower cluster id should come first",
        pair2.compareTo(pair3) < 0);
    assertEquals("Same pair should compare as equal",
        0, pair1.compareTo(pair1));
  }

  // =========================================================================
  // Class filter (acceptedCollectionIds)
  // =========================================================================

  /**
   * When acceptedCollectionIds is set, vertices whose collection ID is NOT
   * in the set are skipped without loading from storage.
   */
  @Test
  public void classFilter_skipsNonMatchingCollectionId() {
    var matchingRid = new RecordId(10, 1); // collection 10 — accepted
    var nonMatchingRid = new RecordId(20, 1); // collection 20 — rejected
    var matchingPair = RidPair.ofPair(new RecordId(30, 1), matchingRid);
    var nonMatchingPair = RidPair.ofPair(new RecordId(30, 2), nonMatchingRid);
    mockLoadReturnsVertex(matchingRid);
    // nonMatchingRid should never be loaded — no mock needed

    var accepted = IntOpenHashSet.of(10);
    var iterator = new VertexFromLinkBagIterator(
        List.of(nonMatchingPair, matchingPair).iterator(), session, 2, accepted);

    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When all vertices match the class filter, all are yielded.
   */
  @Test
  public void classFilter_allMatch_yieldsAll() {
    var rid1 = new RecordId(10, 1);
    var rid2 = new RecordId(10, 2);
    mockLoadReturnsVertex(rid1);
    mockLoadReturnsVertex(rid2);

    var accepted = IntOpenHashSet.of(10);
    var iterator = new VertexFromLinkBagIterator(
        List.of(
            RidPair.ofPair(new RecordId(30, 1), rid1),
            RidPair.ofPair(new RecordId(30, 2), rid2)).iterator(),
        session, 2, accepted);

    assertTrue(iterator.hasNext());
    assertEquals(rid1, iterator.next().getIdentity());
    assertTrue(iterator.hasNext());
    assertEquals(rid2, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When no vertices match the class filter, the iterator is empty.
   */
  @Test
  public void classFilter_noneMatch_yieldsEmpty() {
    var rid = new RecordId(20, 1); // collection 20 — not accepted

    var accepted = IntOpenHashSet.of(10);
    var iterator = new VertexFromLinkBagIterator(
        List.of(RidPair.ofPair(new RecordId(30, 1), rid)).iterator(),
        session, 1, accepted);

    assertFalse(iterator.hasNext());
  }

  // =========================================================================
  // RID filter (acceptedRids)
  // =========================================================================

  /**
   * When acceptedRids is set, only vertices whose RID is in the set are
   * loaded from storage.
   */
  @Test
  public void ridFilter_skipsNonMatchingRid() {
    var matchingRid = new RecordId(10, 1);
    var nonMatchingRid = new RecordId(10, 2);
    mockLoadReturnsVertex(matchingRid);
    // nonMatchingRid should never be loaded

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var iterator = new VertexFromLinkBagIterator(
        List.of(
            RidPair.ofPair(new RecordId(30, 1), nonMatchingRid),
            RidPair.ofPair(new RecordId(30, 2), matchingRid)).iterator(),
        session, 2, null, acceptedRids);

    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When acceptedRids is empty, no vertices are yielded.
   */
  @Test
  public void ridFilter_emptySet_yieldsEmpty() {
    var rid = new RecordId(10, 1);

    var iterator = new VertexFromLinkBagIterator(
        List.of(RidPair.ofPair(new RecordId(30, 1), rid)).iterator(),
        session, 1, null, new HashSet<>());

    assertFalse(iterator.hasNext());
  }

  // =========================================================================
  // Combined class + RID filter
  // =========================================================================

  /**
   * When both filters are set, a vertex must pass both to be yielded.
   * Class filter is checked first (cheaper — no set lookup).
   */
  @Test
  public void combinedFilter_requiresBothToPass() {
    // rid1: collection 10 (accepted) AND in ridSet → yielded
    var rid1 = new RecordId(10, 1);
    // rid2: collection 10 (accepted) but NOT in ridSet → skipped
    var rid2 = new RecordId(10, 2);
    // rid3: collection 20 (rejected by class filter) → skipped
    var rid3 = new RecordId(20, 1);

    mockLoadReturnsVertex(rid1);

    var acceptedCollections = IntOpenHashSet.of(10);
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(rid1);

    var iterator = new VertexFromLinkBagIterator(
        List.of(
            RidPair.ofPair(new RecordId(30, 1), rid3),
            RidPair.ofPair(new RecordId(30, 2), rid2),
            RidPair.ofPair(new RecordId(30, 3), rid1)).iterator(),
        session, 3, acceptedCollections, acceptedRids);

    assertTrue(iterator.hasNext());
    assertEquals(rid1, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  // =========================================================================
  // ridIterator() tests — RID-only iteration without entity loading
  // =========================================================================

  /**
   * ridIterator() yields RecordId objects from the LinkBag without calling
   * loadEntity(). Verifies that the returned identifiable has the correct
   * RID and that no entity loading occurs.
   */
  @Test
  public void ridIterator_yieldsRecordIdsWithoutLoading() {
    var rid1 = new RecordId(10, 1);
    var rid2 = new RecordId(10, 2);
    var linkBag = mockLinkBag(
        RidPair.ofPair(new RecordId(30, 1), rid1),
        RidPair.ofPair(new RecordId(30, 2), rid2));

    var iterable = new VertexFromLinkBagIterable(linkBag, session);
    var iter = iterable.ridIterator();

    assertTrue(iter.hasNext());
    assertEquals(rid1, iter.next().getIdentity());
    assertTrue(iter.hasNext());
    assertEquals(rid2, iter.next().getIdentity());
    assertFalse(iter.hasNext());

    // Verify loadEntity was never called — entities were not loaded
    verifyNoInteractions(transaction);
  }

  /**
   * ridIterator() applies class filter by collection ID without loading.
   */
  @Test
  public void ridIterator_classFilter_skipsNonMatchingCollectionId() {
    var matchingRid = new RecordId(10, 1); // collection 10 — accepted
    var nonMatchingRid = new RecordId(20, 1); // collection 20 — rejected
    var linkBag = mockLinkBag(
        RidPair.ofPair(new RecordId(30, 1), nonMatchingRid),
        RidPair.ofPair(new RecordId(30, 2), matchingRid));

    var iterable = new VertexFromLinkBagIterable(linkBag, session)
        .withClassFilter(IntOpenHashSet.of(10));
    var iter = iterable.ridIterator();

    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * ridIterator() applies RID filter without loading.
   */
  @Test
  public void ridIterator_ridFilter_skipsNonMatchingRid() {
    var matchingRid = new RecordId(10, 1);
    var nonMatchingRid = new RecordId(10, 2);
    var linkBag = mockLinkBag(
        RidPair.ofPair(new RecordId(30, 1), nonMatchingRid),
        RidPair.ofPair(new RecordId(30, 2), matchingRid));

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var iterable = new VertexFromLinkBagIterable(linkBag, session)
        .withRidFilter(acceptedRids);
    var iter = iterable.ridIterator();

    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * ridIterator() on an empty LinkBag returns an empty iterator.
   */
  @Test
  public void ridIterator_emptyLinkBag_yieldsNothing() {
    var linkBag = mockLinkBag();
    var iterable = new VertexFromLinkBagIterable(linkBag, session);
    assertFalse(iterable.ridIterator().hasNext());
  }

  /**
   * ridIterator() throws NoSuchElementException when exhausted.
   */
  @Test(expected = NoSuchElementException.class)
  public void ridIterator_throwsWhenExhausted() {
    var linkBag = mockLinkBag();
    var iterable = new VertexFromLinkBagIterable(linkBag, session);
    iterable.ridIterator().next();
  }

  /**
   * ridIterator() hasNext() is idempotent — calling it multiple times
   * before next() does not advance past elements.
   */
  @Test
  public void ridIterator_hasNextIsIdempotent() {
    var rid = new RecordId(10, 1);
    var linkBag = mockLinkBag(RidPair.ofPair(new RecordId(30, 1), rid));

    var iter = new VertexFromLinkBagIterable(linkBag, session).ridIterator();

    assertTrue(iter.hasNext());
    assertTrue("Second hasNext() should still return true", iter.hasNext());
    assertTrue("Third hasNext() should still return true", iter.hasNext());
    assertEquals(rid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * ridIterator() applies both class and RID filters simultaneously.
   * A vertex must pass both to be yielded.
   */
  @Test
  public void ridIterator_combinedFilter_requiresBothToPass() {
    // rid1: collection 10 (accepted) AND in ridSet → yielded
    var rid1 = new RecordId(10, 1);
    // rid2: collection 10 (accepted) but NOT in ridSet → skipped
    var rid2 = new RecordId(10, 2);
    // rid3: collection 20 (rejected by class filter) → skipped
    var rid3 = new RecordId(20, 1);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(rid1);

    var linkBag = mockLinkBag(
        RidPair.ofPair(new RecordId(30, 1), rid3),
        RidPair.ofPair(new RecordId(30, 2), rid2),
        RidPair.ofPair(new RecordId(30, 3), rid1));

    var iter = new VertexFromLinkBagIterable(linkBag, session)
        .withClassFilter(IntOpenHashSet.of(10))
        .withRidFilter(acceptedRids)
        .ridIterator();

    assertTrue(iter.hasNext());
    assertEquals(rid1, iter.next().getIdentity());
    assertFalse(iter.hasNext());

    // No entity loading should have occurred
    verifyNoInteractions(transaction);
  }

  /**
   * ridIterator() yields all matching RIDs when multiple pass the filters,
   * preserving LinkBag iteration order.
   */
  @Test
  public void ridIterator_multipleMatches_preservesOrder() {
    var rid1 = new RecordId(10, 1);
    var rid2 = new RecordId(10, 2);
    var rid3 = new RecordId(10, 3);
    var linkBag = mockLinkBag(
        RidPair.ofPair(new RecordId(30, 1), rid1),
        RidPair.ofPair(new RecordId(30, 2), rid2),
        RidPair.ofPair(new RecordId(30, 3), rid3));

    var iter = new VertexFromLinkBagIterable(linkBag, session).ridIterator();

    assertEquals(rid1, iter.next().getIdentity());
    assertEquals(rid2, iter.next().getIdentity());
    assertEquals(rid3, iter.next().getIdentity());
    assertFalse(iter.hasNext());

    verifyNoInteractions(transaction);
  }

  /**
   * ridIterator() returns RID objects (not loaded entities). Verifies the
   * returned ResultInternal wraps a bare RecordId (no loaded entity).
   */
  @Test
  public void ridIterator_returnsRecordIdNotEntity() {
    var rid = new RecordId(10, 1);
    var linkBag = mockLinkBag(RidPair.ofPair(new RecordId(30, 1), rid));

    var result = new VertexFromLinkBagIterable(linkBag, session)
        .ridIterator().next();

    assertTrue(
        "ridIterator ResultInternal should wrap a bare RecordId, not a loaded entity",
        result.asIdentifiableOrNull() instanceof RecordId);
    assertEquals(rid, result.getIdentity());
    verifyNoInteractions(transaction);
  }

  private com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag mockLinkBag(
      RidPair... pairs) {
    var linkBag = mock(com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag.class);
    when(linkBag.iterator()).thenReturn(List.of(pairs).iterator());
    when(linkBag.size()).thenReturn(pairs.length);
    return linkBag;
  }

  /**
   * Configures the mock transaction to return a vertex entity when loadEntity
   * is called with the given RID.
   */
  private void mockLoadReturnsVertex(RID rid) {
    var entity = mock(Entity.class);
    var vertex = mock(Vertex.class);
    when(entity.isVertex()).thenReturn(true);
    when(entity.asVertex()).thenReturn(vertex);
    when(vertex.getIdentity()).thenReturn(rid);
    when(transaction.loadEntity(rid)).thenReturn(entity);
  }
}
