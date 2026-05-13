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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone coverage for {@link EdgeIterable}. The iterable wraps an
 * {@link Iterable}{@code <? extends Identifiable>} and produces
 * {@link EdgeIterator} instances bound to the configured session and
 * size hint.
 *
 * <p>The size dispatch has four arms (explicit ≥0, sizeable inner, collection
 * inner, throws); {@link EdgeIterable#isSizeable()} mirrors three of those
 * arms (explicit ≥0, sizeable.isSizeable, instanceof Collection).
 */
public class EdgeIterableTest {

  private DatabaseSessionEmbedded session;
  private FrontendTransactionImpl transaction;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    transaction = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
  }

  /**
   * The iterable's {@link EdgeIterable#iterator()} produces an iterator that
   * yields edge records. End-to-end through the iterable's iterator factory.
   */
  @Test
  public void testIteratorYieldsEdges() {
    var rid = new RecordId(20, 1);
    mockLoadReturnsEdge(rid);

    Iterable<Identifiable> inner = List.of(rid);
    var iterable = new EdgeIterable(session, inner, 1, null);
    var iter = iterable.iterator();

    assertTrue(iter.hasNext());
    assertEquals(rid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * {@code size()} dispatch arm 1 — explicit size ≥ 0.
   */
  @Test
  public void testSizeReturnsExplicitNonNegative() {
    Iterable<Identifiable> empty = Collections.emptyList();
    var iterable = new EdgeIterable(session, empty, 42, null);
    assertEquals(42, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 2 — explicit size < 0 and inner is
   * {@link Sizeable}.
   */
  @Test
  public void testSizeDelegatesToSizeableInnerIterable() {
    var sizeable = mock(SizeableIdentifiableIterable.class);
    when(sizeable.size()).thenReturn(7);
    when(sizeable.isSizeable()).thenReturn(true);

    var iterable = new EdgeIterable(session, sizeable, -1, null);
    assertEquals(7, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 3 — explicit size < 0 and inner is a
   * {@link java.util.Collection}.
   */
  @Test
  public void testSizeDelegatesToCollectionInnerIterable() {
    Iterable<Identifiable> coll = List.of(
        new RecordId(20, 1), new RecordId(20, 2), new RecordId(20, 3));
    var iterable = new EdgeIterable(session, coll, -1, null);
    assertEquals(3, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 4 — throws when neither sizeable nor
   * collection.
   */
  @Test
  public void testSizeThrowsForOpaqueInnerIterable() {
    Iterable<Identifiable> opaque = Collections::emptyIterator;
    var iterable = new EdgeIterable(session, opaque, -1, null);

    assertFalse("opaque inner iterable must not be sizeable",
        iterable.isSizeable());

    try {
      iterable.size();
      fail("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      assertTrue(e.getMessage().contains("Size is not supported"));
    }
  }

  /**
   * {@code isSizeable} reflects the inner sizeable's flag — pins the false
   * arm where the inner iterable is sizeable but reports
   * {@code isSizeable=false}.
   */
  @Test
  public void testIsSizeableMirrorsSizeableInnerIterableFlag() {
    var sizeable = mock(SizeableIdentifiableIterable.class);
    when(sizeable.isSizeable()).thenReturn(false);
    var iterable = new EdgeIterable(session, sizeable, -1, null);
    assertFalse(iterable.isSizeable());
  }

  // ---------- helpers --------------------------------------------------------

  private void mockLoadReturnsEdge(RID rid) {
    var entity = mock(Entity.class);
    var edge = mock(Edge.class, org.mockito.Mockito.withSettings()
        .extraInterfaces(EdgeInternal.class));
    when(entity.isEdge()).thenReturn(true);
    when(entity.asEdge()).thenReturn(edge);
    when(edge.getIdentity()).thenReturn(rid);
    // EdgeIterator's loadEdge calls transaction.loadEntity(Identifiable) (the
    // Identifiable-typed overload, not the RID one); cast to disambiguate.
    when(transaction.loadEntity((Identifiable) rid)).thenReturn(entity);
  }

  /**
   * Marker mock interface combining {@link Iterable}{@code <Identifiable>}
   * and {@link Sizeable} so a single Mockito mock can fulfil both.
   */
  private interface SizeableIdentifiableIterable extends
      Iterable<Identifiable>, Sizeable {
  }
}
