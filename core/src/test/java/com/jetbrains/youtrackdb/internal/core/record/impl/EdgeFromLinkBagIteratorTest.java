package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
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
 * Tests EdgeFromLinkBagIterator behavior when loading edge records from LinkBag
 * primary RIDs. Verifies:
 * <ul>
 *   <li>Normal iteration over valid edge RIDs (loads from primaryRid)</li>
 *   <li>Graceful skipping of deleted records (RecordNotFoundException)</li>
 *   <li>IllegalStateException for legacy vertex entries</li>
 *   <li>Empty iterator behavior</li>
 *   <li>NoSuchElementException on exhausted iteration</li>
 *   <li>hasNext() idempotency</li>
 *   <li>Class filter (acceptedCollectionIds) — zero-I/O skipping</li>
 *   <li>RID filter (acceptedRids) — zero-I/O skipping</li>
 *   <li>Combined class + RID filter</li>
 *   <li>validateEdgePair rejects corrupt entries</li>
 * </ul>
 */
public class EdgeFromLinkBagIteratorTest {

  private DatabaseSessionEmbedded session;
  private FrontendTransactionImpl transaction;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    transaction = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
  }

  /**
   * The iterator loads from primaryRid (the edge record), not secondaryRid (the
   * opposite vertex). Verify it yields the edge at primaryRid.
   */
  @Test
  public void testLoadsEdgeFromPrimaryRid() {
    var edgeRid = new RecordId(20, 1);
    var vertexRid = new RecordId(10, 1);
    var pair = RidPair.ofPair(edgeRid, vertexRid);
    mockLoadReturnsEdge(edgeRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    assertTrue(iterator.hasNext());
    assertEquals(
        "Should load edge from primary RID, not secondary",
        edgeRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When the primary RID points to a deleted record, the iterator should skip
   * that entry and continue to the next valid one.
   */
  @Test
  public void testSkipsDeletedRecordsGracefully() {
    var deletedRid = new RecordId(20, 1);
    var validRid = new RecordId(20, 2);

    var deletedPair = RidPair.ofPair(deletedRid, new RecordId(10, 1));
    var validPair = RidPair.ofPair(validRid, new RecordId(10, 2));

    when(transaction.loadEntity(deletedRid))
        .thenThrow(new RecordNotFoundException("test", deletedRid));
    mockLoadReturnsEdge(validRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(deletedPair, validPair).iterator(), session, 2);

    assertTrue(
        "Should have a next edge after skipping deleted record",
        iterator.hasNext());
    assertEquals(validRid, iterator.next().getIdentity());
    assertFalse("Should have no more edges", iterator.hasNext());
  }

  /**
   * When the primary RID points to a vertex entity (legacy lightweight edge),
   * the iterator should throw IllegalStateException with a message identifying
   * the legacy edge and the offending RID.
   */
  @Test
  public void testThrowsOnLegacyVertexEntry() {
    var vertexRid = new RecordId(20, 1);
    var pair = RidPair.ofPair(vertexRid, new RecordId(10, 1));

    var entity = mock(Entity.class);
    when(entity.isEdge()).thenReturn(false);
    when(entity.isVertex()).thenReturn(true);
    when(transaction.loadEntity(vertexRid)).thenReturn(entity);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    try {
      iterator.hasNext();
      fail("Expected IllegalStateException for legacy vertex entry");
    } catch (IllegalStateException e) {
      assertTrue("Message should mention legacy lightweight edge",
          e.getMessage().contains("Legacy lightweight edge detected"));
      assertTrue("Message should contain the offending RID",
          e.getMessage().contains(vertexRid.toString()));
    }
  }

  /**
   * When the primary RID points to a non-edge, non-vertex entity, the iterator
   * should skip it gracefully (log warning, return null).
   */
  @Test
  public void testSkipsNonEdgeNonVertexRecordsGracefully() {
    var unknownRid = new RecordId(20, 1);
    var validRid = new RecordId(20, 2);

    var unknownPair = RidPair.ofPair(unknownRid, new RecordId(10, 1));
    var validPair = RidPair.ofPair(validRid, new RecordId(10, 2));

    var nonEdge = mock(Entity.class);
    when(nonEdge.isEdge()).thenReturn(false);
    when(nonEdge.isVertex()).thenReturn(false);
    when(transaction.loadEntity(unknownRid)).thenReturn(nonEdge);

    mockLoadReturnsEdge(validRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(unknownPair, validPair).iterator(), session, 2);

    assertTrue("Should skip non-edge and find the edge", iterator.hasNext());
    assertEquals(validRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * An empty LinkBag should produce an iterator that immediately reports
   * no elements.
   */
  @Test
  public void testEmptyIterator() {
    var iterator = new EdgeFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 0);

    assertFalse(iterator.hasNext());
  }

  /**
   * Calling next() after the iterator is exhausted should throw
   * NoSuchElementException.
   */
  @Test(expected = NoSuchElementException.class)
  public void testThrowsNoSuchElementWhenExhausted() {
    var iterator = new EdgeFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 0);

    iterator.next();
  }

  /**
   * When all primary RIDs point to deleted records, the iterator should yield
   * no elements at all.
   */
  @Test
  public void testAllRecordsDeletedYieldsEmptyIteration() {
    var rid1 = new RecordId(20, 1);
    var rid2 = new RecordId(20, 2);
    var pair1 = RidPair.ofPair(rid1, new RecordId(10, 1));
    var pair2 = RidPair.ofPair(rid2, new RecordId(10, 2));

    when(transaction.loadEntity(rid1))
        .thenThrow(new RecordNotFoundException("test", rid1));
    when(transaction.loadEntity(rid2))
        .thenThrow(new RecordNotFoundException("test", rid2));

    var iterator = new EdgeFromLinkBagIterator(
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
    var edgeRid = new RecordId(20, 1);
    var pair = RidPair.ofPair(edgeRid, new RecordId(10, 1));
    mockLoadReturnsEdge(edgeRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    assertTrue(iterator.hasNext());
    assertTrue("Second hasNext() should still return true", iterator.hasNext());
    assertTrue("Third hasNext() should still return true", iterator.hasNext());
    assertEquals(edgeRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * size() returns the upper-bound count from the LinkBag, not the actual number
   * of edges yielded. isSizeable() returns true when size is non-negative.
   */
  @Test
  public void testSizeReturnsUpperBoundFromLinkBag() {
    var iterator = new EdgeFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, 42);

    assertEquals(42, iterator.size());
    assertTrue(iterator.isSizeable());
  }

  /**
   * isSizeable() returns false when size is negative (unknown size).
   */
  @Test
  public void testIsSizeableReturnsFalseForNegativeSize() {
    var iterator = new EdgeFromLinkBagIterator(
        Collections.<RidPair>emptyList().iterator(), session, -1);

    assertFalse(iterator.isSizeable());
  }

  /**
   * RidPair.validateEdgePair() rejects legacy lightweight pairs where
   * primaryRid == secondaryRid. After edge unification (YTDB-605), all edges
   * must have distinct edge and vertex RIDs. Verify the exception comes from
   * validateEdgePair (not from the legacy-vertex branch) by checking its message.
   */
  @Test
  public void testValidateEdgePairRejectsEqualRids() {
    var rid = new RecordId(10, 1);
    var pair = new RidPair(rid, rid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    try {
      iterator.hasNext();
      fail("Expected IllegalStateException from validateEdgePair");
    } catch (IllegalStateException e) {
      assertTrue("Message should mention primaryRid == secondaryRid",
          e.getMessage().contains("primaryRid == secondaryRid"));
    }
  }

  // =========================================================================
  // Class filter (acceptedCollectionIds)
  // =========================================================================

  /**
   * When acceptedCollectionIds is set, edges whose collection ID is NOT
   * in the set are skipped without loading from storage.
   */
  @Test
  public void classFilter_skipsNonMatchingCollectionId() {
    var matchingRid = new RecordId(20, 1); // collection 20 — accepted
    var nonMatchingRid = new RecordId(30, 1); // collection 30 — rejected
    var matchingPair = RidPair.ofPair(matchingRid, new RecordId(10, 1));
    var nonMatchingPair = RidPair.ofPair(nonMatchingRid, new RecordId(10, 2));
    mockLoadReturnsEdge(matchingRid);
    // nonMatchingRid should never be loaded — no mock needed

    var accepted = IntOpenHashSet.of(20);
    var iterator = new EdgeFromLinkBagIterator(
        List.of(nonMatchingPair, matchingPair).iterator(), session, 2, accepted);

    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
    // Verify zero-I/O: nonMatchingRid should never have been loaded from storage
    verify(transaction, never()).loadEntity(nonMatchingRid);
  }

  /**
   * When all edges match the class filter, all are yielded.
   */
  @Test
  public void classFilter_allMatch_yieldsAll() {
    var rid1 = new RecordId(20, 1);
    var rid2 = new RecordId(20, 2);
    mockLoadReturnsEdge(rid1);
    mockLoadReturnsEdge(rid2);

    var accepted = IntOpenHashSet.of(20);
    var iterator = new EdgeFromLinkBagIterator(
        List.of(
            RidPair.ofPair(rid1, new RecordId(10, 1)),
            RidPair.ofPair(rid2, new RecordId(10, 2))).iterator(),
        session, 2, accepted);

    assertTrue(iterator.hasNext());
    assertEquals(rid1, iterator.next().getIdentity());
    assertTrue(iterator.hasNext());
    assertEquals(rid2, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * When no edges match the class filter, the iterator is empty. Verify that
   * no records are loaded from storage (zero-I/O optimization).
   */
  @Test
  public void classFilter_noneMatch_yieldsEmpty_noIO() {
    var rid = new RecordId(30, 1); // collection 30 — not accepted

    var accepted = IntOpenHashSet.of(20);
    var iterator = new EdgeFromLinkBagIterator(
        List.of(RidPair.ofPair(rid, new RecordId(10, 1))).iterator(),
        session, 1, accepted);

    assertFalse(iterator.hasNext());
    // Verify zero-I/O: loadEntity should never have been called since all
    // entries were rejected by the collection ID check before reaching I/O.
    verifyNoMoreInteractions(transaction);
  }

  // =========================================================================
  // RID filter (acceptedRids)
  // =========================================================================

  /**
   * When acceptedRids is set, only edges whose RID is in the set are loaded
   * from storage.
   */
  @Test
  public void ridFilter_skipsNonMatchingRid() {
    var matchingRid = new RecordId(20, 1);
    var nonMatchingRid = new RecordId(20, 2);
    mockLoadReturnsEdge(matchingRid);
    // nonMatchingRid should never be loaded

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(
            RidPair.ofPair(nonMatchingRid, new RecordId(10, 1)),
            RidPair.ofPair(matchingRid, new RecordId(10, 2))).iterator(),
        session, 2, null, acceptedRids);

    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
    // Verify zero-I/O: nonMatchingRid should never have been loaded from storage
    verify(transaction, never()).loadEntity(nonMatchingRid);
  }

  /**
   * When acceptedRids is empty, no edges are yielded and no records are loaded
   * from storage (zero-I/O optimization).
   */
  @Test
  public void ridFilter_emptySet_yieldsEmpty_noIO() {
    var rid = new RecordId(20, 1);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(RidPair.ofPair(rid, new RecordId(10, 1))).iterator(),
        session, 1, null, new HashSet<>());

    assertFalse(iterator.hasNext());
    // Verify zero-I/O: loadEntity should never have been called
    verifyNoMoreInteractions(transaction);
  }

  // =========================================================================
  // Combined class + RID filter
  // =========================================================================

  /**
   * When both filters are set, an edge must pass both to be yielded.
   * Class filter is checked first (cheaper — no set lookup).
   */
  @Test
  public void combinedFilter_requiresBothToPass() {
    // rid1: collection 20 (accepted) AND in ridSet → yielded
    var rid1 = new RecordId(20, 1);
    // rid2: collection 20 (accepted) but NOT in ridSet → skipped
    var rid2 = new RecordId(20, 2);
    // rid3: collection 30 (rejected by class filter) → skipped
    var rid3 = new RecordId(30, 1);

    mockLoadReturnsEdge(rid1);

    var acceptedCollections = IntOpenHashSet.of(20);
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(rid1);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(
            RidPair.ofPair(rid3, new RecordId(10, 1)),
            RidPair.ofPair(rid2, new RecordId(10, 2)),
            RidPair.ofPair(rid1, new RecordId(10, 3))).iterator(),
        session, 3, acceptedCollections, acceptedRids);

    assertTrue(iterator.hasNext());
    assertEquals(rid1, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * Calling next() directly without a preceding hasNext() should still return
   * the correct edge, since next() delegates to hasNext() internally. This
   * verifies the iterator contract holds for callers that skip hasNext().
   */
  @Test
  public void testNextWithoutHasNextReturnsEdge() {
    var edgeRid = new RecordId(20, 1);
    var pair = RidPair.ofPair(edgeRid, new RecordId(10, 1));
    mockLoadReturnsEdge(edgeRid);

    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1);

    // Call next() directly without hasNext()
    assertEquals(edgeRid, iterator.next().getIdentity());
  }

  /**
   * Configures the mock transaction to return an edge entity when loadEntity
   * is called with the given RID.
   */
  private void mockLoadReturnsEdge(RID rid) {
    var entity = mock(Entity.class);
    var edge = mock(Edge.class, org.mockito.Mockito.withSettings()
        .extraInterfaces(EdgeInternal.class));
    when(entity.isEdge()).thenReturn(true);
    when(entity.asEdge()).thenReturn(edge);
    when(edge.getIdentity()).thenReturn(rid);
    when(transaction.loadEntity(rid)).thenReturn(entity);
  }
}
