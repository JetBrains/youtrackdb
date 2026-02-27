package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collections;
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
   * RidPair.ofSingle creates a lightweight pair where primary == secondary.
   * This is used for lightweight edges where no separate edge record exists.
   */
  @Test
  public void testOfSingleCreatesLightweightPair() {
    var rid = new RecordId(10, 1);
    var pair = RidPair.ofSingle(rid);

    assertEquals(rid, pair.primaryRid());
    assertEquals(rid, pair.secondaryRid());
    assertTrue("ofSingle should create a lightweight pair", pair.isLightweight());
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
