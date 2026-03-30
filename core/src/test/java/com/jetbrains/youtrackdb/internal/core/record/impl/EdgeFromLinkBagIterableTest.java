package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests EdgeFromLinkBagIterable — the thin wrapper around EdgeFromLinkBagIterator.
 * Verifies construction, filter copy semantics, iteration delegation, and size
 * delegation to the underlying LinkBag.
 */
public class EdgeFromLinkBagIterableTest {

  private DatabaseSessionEmbedded session;
  private FrontendTransactionImpl transaction;
  private LinkBag linkBag;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    transaction = mock(FrontendTransactionImpl.class);
    linkBag = mock(LinkBag.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
  }

  /**
   * iterator() should produce an EdgeFromLinkBagIterator that yields edge records
   * from the LinkBag's primary RIDs.
   */
  @Test
  public void testIteratorYieldsEdgesFromLinkBag() {
    var edgeRid = new RecordId(20, 1);
    var vertexRid = new RecordId(10, 1);
    var pair = RidPair.ofPair(edgeRid, vertexRid);

    when(linkBag.iterator()).thenReturn(List.of(pair).iterator());
    when(linkBag.size()).thenReturn(1);
    mockLoadReturnsEdge(edgeRid);

    var iterable = new EdgeFromLinkBagIterable(linkBag, session);
    var iterator = iterable.iterator();

    assertTrue(iterator.hasNext());
    assertEquals(edgeRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * size() delegates to the LinkBag's size.
   */
  @Test
  public void testSizeDelegatesToLinkBag() {
    when(linkBag.size()).thenReturn(42);
    when(linkBag.isSizeable()).thenReturn(true);

    var iterable = new EdgeFromLinkBagIterable(linkBag, session);

    assertEquals(42, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * isSizeable() returns false when LinkBag reports not sizeable.
   */
  @Test
  public void testIsSizeableDelegatesToLinkBag() {
    when(linkBag.isSizeable()).thenReturn(false);

    var iterable = new EdgeFromLinkBagIterable(linkBag, session);

    assertFalse(iterable.isSizeable());
  }

  /**
   * withClassFilter returns a new iterable (immutable copy pattern) that filters
   * edges by collection ID.
   */
  @Test
  public void testWithClassFilterReturnsFilteredCopy() {
    var matchingRid = new RecordId(20, 1);
    var nonMatchingRid = new RecordId(30, 1);
    var matchingPair = RidPair.ofPair(matchingRid, new RecordId(10, 1));
    var nonMatchingPair = RidPair.ofPair(nonMatchingRid, new RecordId(10, 2));

    when(linkBag.iterator())
        .thenReturn(List.of(nonMatchingPair, matchingPair).iterator());
    when(linkBag.size()).thenReturn(2);
    mockLoadReturnsEdge(matchingRid);

    var original = new EdgeFromLinkBagIterable(linkBag, session);
    var filtered = original.withClassFilter(IntOpenHashSet.of(20));

    assertNotSame("withClassFilter must return a new instance", original, filtered);

    var iterator = filtered.iterator();
    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * withRidFilter returns a new iterable that filters edges by RID set.
   */
  @Test
  public void testWithRidFilterReturnsFilteredCopy() {
    var matchingRid = new RecordId(20, 1);
    var nonMatchingRid = new RecordId(20, 2);
    var matchingPair = RidPair.ofPair(matchingRid, new RecordId(10, 1));
    var nonMatchingPair = RidPair.ofPair(nonMatchingRid, new RecordId(10, 2));

    when(linkBag.iterator())
        .thenReturn(List.of(nonMatchingPair, matchingPair).iterator());
    when(linkBag.size()).thenReturn(2);
    mockLoadReturnsEdge(matchingRid);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var original = new EdgeFromLinkBagIterable(linkBag, session);
    var filtered = original.withRidFilter(acceptedRids);

    assertNotSame("withRidFilter must return a new instance", original, filtered);

    var iterator = filtered.iterator();
    assertTrue(iterator.hasNext());
    assertEquals(matchingRid, iterator.next().getIdentity());
    assertFalse(iterator.hasNext());
  }

  /**
   * Chaining withClassFilter() then withRidFilter() should produce an iterable
   * that applies both filters, because each method preserves the other filter.
   * An edge must pass both the collection ID check and the RID set check.
   */
  @Test
  public void testChainingBothFiltersPreservesBoth() {
    var matchingRid = new RecordId(20, 1); // collection 20 + in RID set
    var wrongClassRid = new RecordId(30, 1); // collection 30 (rejected by class)
    var wrongRidRid = new RecordId(20, 2); // collection 20 but not in RID set

    when(linkBag.iterator()).thenReturn(List.of(
        RidPair.ofPair(wrongClassRid, new RecordId(10, 1)),
        RidPair.ofPair(wrongRidRid, new RecordId(10, 2)),
        RidPair.ofPair(matchingRid, new RecordId(10, 3))).iterator());
    when(linkBag.size()).thenReturn(3);
    mockLoadReturnsEdge(matchingRid);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var filtered = new EdgeFromLinkBagIterable(linkBag, session)
        .withClassFilter(IntOpenHashSet.of(20))
        .withRidFilter(acceptedRids);

    var iter = filtered.iterator();
    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

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
