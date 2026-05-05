/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
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
 * Standalone coverage for {@link VertexFromLinkBagIterable} — sister of the
 * existing {@code EdgeFromLinkBagIterableTest}. The iterable wraps a
 * {@link LinkBag} and yields the vertex at each {@link RidPair#secondaryRid()}
 * (skipping the intermediate edge load).
 *
 * <p>Pinned: iteration delegation, size/isSizeable delegation to the LinkBag,
 * the immutable-copy semantics of {@code withClassFilter} and
 * {@code withRidFilter}, and that chaining the two filters preserves both.
 */
public class VertexFromLinkBagIterableTest {

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
   * iterator() should produce a {@link VertexFromLinkBagIterator} that yields
   * vertices from the LinkBag's secondary RIDs.
   */
  @Test
  public void testIteratorYieldsVerticesFromLinkBag() {
    var edgeRid = new RecordId(20, 1);
    var vertexRid = new RecordId(10, 1);
    var pair = RidPair.ofPair(edgeRid, vertexRid);

    when(linkBag.iterator()).thenReturn(List.of(pair).iterator());
    when(linkBag.size()).thenReturn(1);
    mockLoadReturnsVertex(vertexRid);

    var iterable = new VertexFromLinkBagIterable(linkBag, session);
    var iter = iterable.iterator();

    assertTrue(iter.hasNext());
    assertEquals(vertexRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * {@code size()} delegates to the LinkBag's size.
   */
  @Test
  public void testSizeDelegatesToLinkBag() {
    when(linkBag.size()).thenReturn(42);
    when(linkBag.isSizeable()).thenReturn(true);

    var iterable = new VertexFromLinkBagIterable(linkBag, session);

    assertEquals(42, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code isSizeable()} returns false when LinkBag reports not sizeable.
   */
  @Test
  public void testIsSizeableDelegatesToLinkBag() {
    when(linkBag.isSizeable()).thenReturn(false);
    var iterable = new VertexFromLinkBagIterable(linkBag, session);
    assertFalse(iterable.isSizeable());
  }

  /**
   * {@code withClassFilter} returns a new iterable instance (immutable copy
   * pattern) that filters vertices by collection ID before loading from
   * storage.
   */
  @Test
  public void testWithClassFilterReturnsFilteredCopy() {
    var matchingRid = new RecordId(10, 1);
    var nonMatchingRid = new RecordId(20, 1);
    var matchingPair = RidPair.ofPair(new RecordId(30, 1), matchingRid);
    var nonMatchingPair = RidPair.ofPair(new RecordId(30, 2), nonMatchingRid);

    when(linkBag.iterator())
        .thenReturn(List.of(nonMatchingPair, matchingPair).iterator());
    when(linkBag.size()).thenReturn(2);
    mockLoadReturnsVertex(matchingRid);

    var original = new VertexFromLinkBagIterable(linkBag, session);
    var filtered = original.withClassFilter(IntOpenHashSet.of(10));

    assertNotSame("withClassFilter must return a new instance", original, filtered);

    var iter = filtered.iterator();
    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * {@code withRidFilter} returns a new iterable instance that filters
   * vertices by RID set.
   */
  @Test
  public void testWithRidFilterReturnsFilteredCopy() {
    var matchingRid = new RecordId(10, 1);
    var nonMatchingRid = new RecordId(10, 2);
    var matchingPair = RidPair.ofPair(new RecordId(30, 1), matchingRid);
    var nonMatchingPair = RidPair.ofPair(new RecordId(30, 2), nonMatchingRid);

    when(linkBag.iterator())
        .thenReturn(List.of(nonMatchingPair, matchingPair).iterator());
    when(linkBag.size()).thenReturn(2);
    mockLoadReturnsVertex(matchingRid);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var original = new VertexFromLinkBagIterable(linkBag, session);
    var filtered = original.withRidFilter(acceptedRids);

    assertNotSame("withRidFilter must return a new instance", original, filtered);

    var iter = filtered.iterator();
    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * Chaining {@code withClassFilter} then {@code withRidFilter} must produce
   * an iterable that applies both filters: a vertex must pass both the
   * collection-ID check and the RID set check to be yielded.
   */
  @Test
  public void testChainingBothFiltersPreservesBoth() {
    var matchingRid = new RecordId(10, 1); // collection 10 + in RID set
    var wrongClassRid = new RecordId(20, 1); // collection 20 (rejected by class)
    var wrongRidRid = new RecordId(10, 2); // collection 10 but not in RID set

    when(linkBag.iterator()).thenReturn(List.of(
        RidPair.ofPair(new RecordId(30, 1), wrongClassRid),
        RidPair.ofPair(new RecordId(30, 2), wrongRidRid),
        RidPair.ofPair(new RecordId(30, 3), matchingRid)).iterator());
    when(linkBag.size()).thenReturn(3);
    mockLoadReturnsVertex(matchingRid);

    var acceptedRids = new HashSet<RID>();
    acceptedRids.add(matchingRid);

    var filtered = new VertexFromLinkBagIterable(linkBag, session)
        .withClassFilter(IntOpenHashSet.of(10))
        .withRidFilter(acceptedRids);

    var iter = filtered.iterator();
    assertTrue(iter.hasNext());
    assertEquals(matchingRid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  private void mockLoadReturnsVertex(RID rid) {
    var entity = mock(Entity.class);
    var vertex = mock(Vertex.class);
    when(entity.isVertex()).thenReturn(true);
    when(entity.asVertex()).thenReturn(vertex);
    when(vertex.getIdentity()).thenReturn(rid);
    when(transaction.loadEntity(rid)).thenReturn(entity);
  }
}
