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

  // =========================================================================
  // Effectiveness metric flush
  //
  // These tests verify that the iterator reports the TRUE intersection of
  // linkBag ∩ acceptedRids to the Ratio metric, not the broken
  // (linkBagSize − ridSet.size()) approximation that PR #973 originally used.
  // The recording is lazy — it fires on iterator exhaustion or close — and
  // must happen exactly once per iterator instance.
  // =========================================================================

  /**
   * Minimal recording Ratio used to capture all {@code record(success, total)}
   * calls in order, so tests can assert on the exact flushed values without
   * pulling in the production Ratio.Impl (which depends on a Ticker).
   */
  private static final class RecordingRatio
      implements com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio {
    final java.util.List<long[]> calls = new java.util.ArrayList<>();

    @Override
    public void record(long success, long total) {
      calls.add(new long[] {success, total});
    }

    @Override
    public double getRatio() {
      return 0.0;
    }
  }

  /**
   * When the iterator is exhausted naturally and {@code acceptedRids} is a
   * strict subset of the link bag, the flushed {@code filtered} equals
   * {@code linkBagSize − |acceptedRids|} — the one case where the old
   * approximation was correct.  {@code probed} equals the link bag size.
   */
  @Test
  public void testEffectivenessFlush_subsetRidSet_recordsCorrectFilteredCount() {
    var inSet1 = new RecordId(20, 1);
    var inSet2 = new RecordId(20, 2);
    var notInSet = new RecordId(20, 3);

    var p1 = RidPair.ofPair(inSet1, new RecordId(10, 1));
    var p2 = RidPair.ofPair(inSet2, new RecordId(10, 2));
    var p3 = RidPair.ofPair(notInSet, new RecordId(10, 3));
    mockLoadReturnsEdge(inSet1);
    mockLoadReturnsEdge(inSet2);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(inSet1);
    acceptedRids.add(inSet2);
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2, p3).iterator(), session, 3,
        null, acceptedRids, metric);

    // Drain.
    while (iterator.hasNext()) {
      iterator.next();
    }

    assertEquals("Exactly one flush call expected", 1, metric.calls.size());
    assertEquals("filtered should equal LB-not-in-RS count",
        1L, metric.calls.get(0)[0]);
    assertEquals("probed should equal full link bag size",
        3L, metric.calls.get(0)[1]);
  }

  /**
   * When {@code acceptedRids} is a superset of the link bag, the old
   * {@code linkBagSize − ridSet.size()} formula would clip to 0 and silently
   * under-report.  The lazy flush correctly reports {@code filtered = 0}
   * because every probed entry is found in the set.
   */
  @Test
  public void testEffectivenessFlush_supersetRidSet_recordsZeroFiltered() {
    var inSet1 = new RecordId(20, 1);
    var inSet2 = new RecordId(20, 2);
    var p1 = RidPair.ofPair(inSet1, new RecordId(10, 1));
    var p2 = RidPair.ofPair(inSet2, new RecordId(10, 2));
    mockLoadReturnsEdge(inSet1);
    mockLoadReturnsEdge(inSet2);

    // RidSet has 4 entries, link bag has 2 — old formula would say
    // filtered = 2 - 4 = -2 → clipped to 0.
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(inSet1);
    acceptedRids.add(inSet2);
    acceptedRids.add(new RecordId(20, 99));
    acceptedRids.add(new RecordId(20, 100));
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2).iterator(), session, 2,
        null, acceptedRids, metric);
    while (iterator.hasNext()) {
      iterator.next();
    }

    assertEquals(1, metric.calls.size());
    assertEquals("0 filtered: every LB entry was in the set",
        0L, metric.calls.get(0)[0]);
    assertEquals("probed = link bag size",
        2L, metric.calls.get(0)[1]);
  }

  /**
   * The old formula gave the wrong answer whenever {@code acceptedRids} had
   * entries outside the link bag: it underestimated filtering by counting
   * those orphan RIDs as if they survived.  This test pins the correct
   * value (3 probed, 2 filtered) for the case
   * linkBag={A,B,C}, ridSet={B,X,Y} → intersection={B}, filtered={A,C}.
   */
  @Test
  public void testEffectivenessFlush_partialOverlap_recordsTrueIntersection() {
    var a = new RecordId(20, 1);
    var b = new RecordId(20, 2);
    var c = new RecordId(20, 3);
    var p1 = RidPair.ofPair(a, new RecordId(10, 1));
    var p2 = RidPair.ofPair(b, new RecordId(10, 2));
    var p3 = RidPair.ofPair(c, new RecordId(10, 3));
    mockLoadReturnsEdge(b);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(b);
    acceptedRids.add(new RecordId(20, 88));
    acceptedRids.add(new RecordId(20, 99));
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2, p3).iterator(), session, 3,
        null, acceptedRids, metric);
    while (iterator.hasNext()) {
      iterator.next();
    }

    assertEquals(1, metric.calls.size());
    assertEquals("2 LB entries (A, C) not in ridSet",
        2L, metric.calls.get(0)[0]);
    assertEquals(3L, metric.calls.get(0)[1]);
  }

  /**
   * When {@code acceptedRids} and the link bag are disjoint, every probed
   * entry is filtered; the metric records {@code filtered == probed}.
   */
  @Test
  public void testEffectivenessFlush_disjointRidSet_recordsAllFiltered() {
    var a = new RecordId(20, 1);
    var b = new RecordId(20, 2);
    var p1 = RidPair.ofPair(a, new RecordId(10, 1));
    var p2 = RidPair.ofPair(b, new RecordId(10, 2));
    // No mockLoadReturnsEdge — neither RID survives the filter.

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(new RecordId(20, 88));
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2).iterator(), session, 2,
        null, acceptedRids, metric);
    assertFalse(iterator.hasNext());

    assertEquals(1, metric.calls.size());
    assertEquals(2L, metric.calls.get(0)[0]);
    assertEquals(2L, metric.calls.get(0)[1]);
  }

  /**
   * When {@code acceptedRids} is null (the iterator wasn't built for RID
   * filtering), the effectiveness metric must never be recorded — there is
   * no pre-filter to measure.
   */
  @Test
  public void testEffectivenessFlush_noRidFilter_neverRecords() {
    var edgeRid = new RecordId(20, 1);
    var pair = RidPair.ofPair(edgeRid, new RecordId(10, 1));
    mockLoadReturnsEdge(edgeRid);

    var metric = new RecordingRatio();
    var iterator = new EdgeFromLinkBagIterator(
        List.of(pair).iterator(), session, 1,
        null, null, metric);
    while (iterator.hasNext()) {
      iterator.next();
    }

    assertTrue("No record() call expected when acceptedRids is null",
        metric.calls.isEmpty());
  }

  /**
   * The class filter must short-circuit BEFORE the RID-set probe count, so
   * entries rejected by class filter don't show up in {@code probed}.  This
   * keeps the effectiveness ratio describing the RID-set filter in isolation.
   * Setup: LB={cls20:1, cls30:2, cls20:3}, classFilter={20}, ridSet={cls20:1}
   * → probed=2 (only cls20 entries reach the probe), filtered=1 (cls20:3).
   */
  @Test
  public void testEffectivenessFlush_classFilterShortCircuit_excludesFromProbed() {
    var matchClassMatchRid = new RecordId(20, 1);
    var wrongClass = new RecordId(30, 2);
    var matchClassWrongRid = new RecordId(20, 3);
    var p1 = RidPair.ofPair(matchClassMatchRid, new RecordId(10, 1));
    var p2 = RidPair.ofPair(wrongClass, new RecordId(10, 2));
    var p3 = RidPair.ofPair(matchClassWrongRid, new RecordId(10, 3));
    mockLoadReturnsEdge(matchClassMatchRid);

    var classes = IntOpenHashSet.of(20);
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchClassMatchRid);
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2, p3).iterator(), session, 3,
        classes, acceptedRids, metric);
    while (iterator.hasNext()) {
      iterator.next();
    }

    assertEquals(1, metric.calls.size());
    assertEquals(
        "filtered counts only cls20:3 (cls30:2 rejected by class filter first)",
        1L, metric.calls.get(0)[0]);
    assertEquals(
        "probed counts only entries that reached the RID-set check (2 of 3)",
        2L, metric.calls.get(0)[1]);
  }

  /**
   * Calling {@code close()} after partial iteration flushes the counts
   * accumulated so far, so consumers that stop iterating early (LIMIT,
   * exception, manual short-circuit) still contribute to the metric.
   */
  @Test
  public void testEffectivenessFlush_earlyClose_flushesPartialCounts() {
    var a = new RecordId(20, 1);
    var b = new RecordId(20, 2);
    var c = new RecordId(20, 3);
    var p1 = RidPair.ofPair(a, new RecordId(10, 1));
    var p2 = RidPair.ofPair(b, new RecordId(10, 2));
    var p3 = RidPair.ofPair(c, new RecordId(10, 3));
    mockLoadReturnsEdge(a);
    mockLoadReturnsEdge(b);
    mockLoadReturnsEdge(c);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(a);
    acceptedRids.add(b);
    acceptedRids.add(c);
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2, p3).iterator(), session, 3,
        null, acceptedRids, metric);

    // Only consume the first element, then close.
    iterator.next();
    iterator.close();

    assertEquals(1, metric.calls.size());
    // Probed 1 element so far; 0 filtered.
    assertEquals(0L, metric.calls.get(0)[0]);
    assertEquals(1L, metric.calls.get(0)[1]);
  }

  /**
   * Repeated {@code hasNext()} calls after exhaustion, plus a redundant
   * {@code close()}, must trigger exactly one {@code record()} — the flush
   * is idempotent.
   */
  @Test
  public void testEffectivenessFlush_idempotent_acrossRepeatedCalls() {
    var a = new RecordId(20, 1);
    var p1 = RidPair.ofPair(a, new RecordId(10, 1));
    mockLoadReturnsEdge(a);
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(a);
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1).iterator(), session, 1,
        null, acceptedRids, metric);
    iterator.next();
    // Exhausted now.  Call hasNext repeatedly + close() — none should
    // double-record.
    assertFalse(iterator.hasNext());
    assertFalse(iterator.hasNext());
    iterator.close();
    iterator.close();

    assertEquals(1, metric.calls.size());
  }

  /**
   * When the iterator has no link bag entries at all, no probed count is
   * accumulated and the metric must NOT be recorded (the {@code probed > 0}
   * guard inside {@code flushEffectivenessMetric} avoids feeding zero
   * totals to {@code Ratio.Impl}, which would reject {@code total <= 0}).
   */
  @Test
  public void testEffectivenessFlush_emptyLinkBag_doesNotRecord() {
    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(new RecordId(20, 1));
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        Collections.<RidPair>emptyIterator(), session, 0,
        null, acceptedRids, metric);
    assertFalse(iterator.hasNext());

    assertTrue("Empty link bag must not produce a record(0,0) call",
        metric.calls.isEmpty());
  }

  /**
   * When {@code loadEntity} throws a runtime exception mid-iteration (e.g.
   * the legacy-lightweight-edge {@code IllegalStateException} at
   * {@code EdgeFromLinkBagIterator:194-198}), a caller using
   * try-with-resources still gets exactly one flush of the effectiveness
   * metric — driven by {@code close()} — carrying the probed/filtered
   * counts accumulated up to the exception. The metric must not be
   * skipped or double-recorded.
   */
  @Test
  public void testEffectivenessFlush_exceptionMidIteration_closeStillFlushesOnce() {
    var passing = new RecordId(20, 1);
    var poisoned = new RecordId(20, 2);
    var unreached = new RecordId(20, 3);

    var p1 = RidPair.ofPair(passing, new RecordId(10, 1));
    var p2 = RidPair.ofPair(poisoned, new RecordId(10, 2));
    var p3 = RidPair.ofPair(unreached, new RecordId(10, 3));

    mockLoadReturnsEdge(passing);
    // The poisoned RID's load throws IllegalStateException, mirroring the
    // legacy-lightweight-edge path inside loadEdge().
    when(transaction.loadEntity(poisoned))
        .thenThrow(new IllegalStateException("legacy lightweight edge"));

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(passing);
    acceptedRids.add(poisoned);
    acceptedRids.add(unreached);
    var metric = new RecordingRatio();

    var iterator = new EdgeFromLinkBagIterator(
        List.of(p1, p2, p3).iterator(), session, 3,
        null, acceptedRids, metric);

    // Drain via try-with-resources; the second hasNext() triggers the throw.
    boolean caught = false;
    try (iterator) {
      iterator.next(); // consumes 'passing' (probed=1, filtered=0)
      try {
        iterator.next(); // attempts 'poisoned' → throws
        fail("Expected IllegalStateException to propagate");
      } catch (IllegalStateException expected) {
        caught = true;
      }
    }

    assertTrue("Exception must propagate to caller", caught);
    assertEquals("close() must flush exactly once after a mid-iteration throw",
        1, metric.calls.size());
    // probed counts entries that reached the acceptedRids check: 'passing'
    // succeeded, 'poisoned' was probed then threw inside loadEntity (still
    // counted because the probe increments before storage I/O), 'unreached'
    // is never visited.
    assertEquals("filtered=0: every probed RID was in the accepted set",
        0L, metric.calls.get(0)[0]);
    assertEquals("probed=2: passing succeeded, poisoned counted before throw",
        2L, metric.calls.get(0)[1]);
  }
}
