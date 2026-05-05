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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone coverage for {@link EdgeIterator}. The iterator wraps an
 * {@link Identifiable} iterator and lazily resolves each entry to an
 * {@link EdgeInternal} via the active transaction. Missing records
 * ({@link RecordNotFoundException}) are skipped; legacy lightweight-edge
 * vertex entries throw; non-edge non-vertex entries throw.
 *
 * <p>The tests pin the four {@code loadEdge} arms (null, already-an-edge,
 * RecordNotFound, vertex-throws), the size dispatch (explicit size, sizeable
 * inner iterator, sizeable multiValue, collection multiValue, throws), the
 * isSizeable mirror, the reset/isResetable arms, and the
 * {@code getMultiValue} accessor.
 */
public class EdgeIteratorTest {

  private DatabaseSessionEmbedded session;
  private FrontendTransactionImpl transaction;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    transaction = mock(FrontendTransactionImpl.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
  }

  /**
   * {@code hasNext} skips a null entry and continues to the next valid one.
   * {@code List.of} forbids null elements, so we use {@link java.util.Arrays#asList}
   * to construct an iterable carrying a null in the middle of the sequence.
   */
  @Test
  public void testNullEntryIsSkipped() {
    var rid = new RecordId(20, 1);
    mockLoadReturnsEdgeEntity(rid);

    java.util.List<Identifiable> entries = java.util.Arrays.asList(null, rid);
    var iter = new EdgeIterator(entries, entries.iterator(), -1, session);

    assertTrue(iter.hasNext());
    assertEquals(rid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * When the inner iterator yields an entity that is already an edge, the
   * iterator returns it without consulting the transaction. Pins the
   * "instanceof Entity && entity.isEdge()" fast-path arm.
   */
  @Test
  public void testAlreadyAnEdgeShortCircuitsLoad() {
    var rid = new RecordId(20, 1);
    var edge = mock(Edge.class, org.mockito.Mockito.withSettings()
        .extraInterfaces(EdgeInternal.class, Entity.class));
    when(edge.getIdentity()).thenReturn(rid);
    when(((Entity) edge).isEdge()).thenReturn(true);
    when(((Entity) edge).asEdge()).thenReturn(edge);

    var iter = new EdgeIterator(
        null, List.<Identifiable>of((Identifiable) edge).iterator(), 1, session);

    assertTrue(iter.hasNext());
    assertSame(edge, iter.next());
  }

  /**
   * {@link RecordNotFoundException} from the transaction is logged and the
   * entry is skipped. Verified by mixing a missing entry with a valid one.
   */
  @Test
  public void testRecordNotFoundIsSkipped() {
    var missing = new RecordId(20, 1);
    var valid = new RecordId(20, 2);
    when(transaction.loadEntity((Identifiable) missing))
        .thenThrow(new RecordNotFoundException("test", missing));
    mockLoadReturnsEdgeEntity(valid);

    var iter = new EdgeIterator(
        null, List.<Identifiable>of(missing, valid).iterator(), -1, session);

    assertTrue(iter.hasNext());
    assertEquals(valid, iter.next().getIdentity());
    assertFalse(iter.hasNext());
  }

  /**
   * A vertex entry (legacy lightweight edge) must throw with a clearly-named
   * exception message. The iterator does not silently skip these.
   */
  @Test
  public void testVertexEntryThrows() {
    var vertexRid = new RecordId(10, 1);
    var entity = mock(Entity.class);
    when(entity.isEdge()).thenReturn(false);
    when(entity.isVertex()).thenReturn(true);
    when(transaction.loadEntity((Identifiable) vertexRid)).thenReturn(entity);

    var iter = new EdgeIterator(
        null, List.<Identifiable>of(vertexRid).iterator(), -1, session);

    try {
      iter.hasNext();
      fail("Expected IllegalStateException for legacy vertex entry");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("Legacy lightweight edge detected"));
    }
  }

  /**
   * An entity that is neither an edge nor a vertex must throw with a
   * different message, identifying the offending value as not-an-edge.
   */
  @Test
  public void testNonEdgeNonVertexThrows() {
    var rid = new RecordId(20, 1);
    var entity = mock(Entity.class);
    when(entity.isEdge()).thenReturn(false);
    when(entity.isVertex()).thenReturn(false);
    when(transaction.loadEntity((Identifiable) rid)).thenReturn(entity);

    var iter = new EdgeIterator(
        null, List.<Identifiable>of(rid).iterator(), -1, session);

    try {
      iter.hasNext();
      fail("Expected IllegalStateException for non-edge non-vertex entity");
    } catch (IllegalStateException e) {
      assertTrue("message must name the not-an-edge case",
          e.getMessage().contains("is not an edge"));
    }
  }

  /**
   * {@code next()} on an exhausted iterator must throw
   * {@link NoSuchElementException}.
   */
  @Test(expected = NoSuchElementException.class)
  public void testNextOnExhaustedIteratorThrows() {
    var iter = new EdgeIterator(
        null, Collections.<Identifiable>emptyList().iterator(), 0, session);
    iter.next();
  }

  /**
   * {@code size()} dispatch arm 1 — explicit size ≥ 0. Pinned because the
   * branch is the first checked and the cheapest.
   */
  @Test
  public void testSizeReturnsExplicitSizeWhenNonNegative() {
    var iter = new EdgeIterator(
        null, Collections.<Identifiable>emptyList().iterator(), 42, session);
    assertEquals(42, iter.size());
    assertTrue(iter.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 2 — explicit size < 0 and inner iterator is
   * {@link Sizeable}. Delegates.
   */
  @Test
  public void testSizeDelegatesToSizeableInnerIterator() {
    var sizeable = mock(SizeableIdentifiableIterator.class);
    when(sizeable.hasNext()).thenReturn(false);
    when(sizeable.size()).thenReturn(11);
    when(sizeable.isSizeable()).thenReturn(true);

    var iter = new EdgeIterator(null, sizeable, -1, session);
    assertEquals(11, iter.size());
    assertTrue(iter.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 3 — explicit size < 0, inner iterator not
   * sizeable, but {@code multiValue} is {@link Sizeable}. Delegates.
   */
  @Test
  public void testSizeDelegatesToSizeableMultiValue() {
    var multi = mock(SizeableMultiValue.class);
    when(multi.size()).thenReturn(13);
    when(multi.isSizeable()).thenReturn(true);

    var iter = new EdgeIterator(
        multi, Collections.<Identifiable>emptyList().iterator(), -1, session);
    assertEquals(13, iter.size());
    assertTrue(iter.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 4 — multiValue is a {@link java.util.Collection}.
   * Delegates to {@code Collection.size}.
   */
  @Test
  public void testSizeDelegatesToCollectionMultiValue() {
    var iter = new EdgeIterator(
        List.of("a", "b", "c"),
        Collections.<Identifiable>emptyList().iterator(), -1, session);
    assertEquals(3, iter.size());
    assertTrue(iter.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 5 — none of the above. Throws.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testSizeThrowsWhenAllArmsFail() {
    var iter = new EdgeIterator(
        new Object(),
        Collections.<Identifiable>emptyList().iterator(), -1, session);
    iter.size();
  }

  /**
   * {@code isSizeable()} returns false when explicit size is < 0 and neither
   * the inner iterator nor multiValue is sizeable / a collection.
   */
  @Test
  public void testIsSizeableFalseWhenAllArmsFail() {
    var iter = new EdgeIterator(
        new Object(),
        Collections.<Identifiable>emptyList().iterator(), -1, session);
    assertFalse(iter.isSizeable());
  }

  /**
   * {@code reset()} delegates to the inner iterator when it is
   * {@link Resettable}.
   */
  @Test
  public void testResetDelegatesToResettableInnerIterator() {
    var resettable = mock(ResettableIdentifiableIterator.class);
    when(resettable.isResetable()).thenReturn(true);
    when(resettable.hasNext()).thenReturn(false);

    var iter = new EdgeIterator(null, resettable, -1, session);
    assertTrue(iter.isResetable());
    iter.reset(); // must not throw

    org.mockito.Mockito.verify(resettable).reset();
  }

  /**
   * {@code reset()} on a non-resettable inner iterator must throw
   * {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testResetThrowsOnNonResettableInner() {
    var iter = new EdgeIterator(
        null, Collections.<Identifiable>emptyList().iterator(), -1, session);
    iter.reset();
  }

  /**
   * {@code isResetable} reports false when the inner iterator is not
   * Resettable, and true only when both Resettable AND its own
   * {@code isResetable} flag is true.
   */
  @Test
  public void testIsResetableArms() {
    // Arm 1: not Resettable.
    var iter1 = new EdgeIterator(
        null, Collections.<Identifiable>emptyList().iterator(), -1, session);
    assertFalse(iter1.isResetable());

    // Arm 2: Resettable but isResetable=false.
    var resettable = mock(ResettableIdentifiableIterator.class);
    when(resettable.isResetable()).thenReturn(false);
    when(resettable.hasNext()).thenReturn(false);
    var iter2 = new EdgeIterator(null, resettable, -1, session);
    assertFalse(iter2.isResetable());
  }

  /**
   * {@code getMultiValue()} returns the value passed at construction. Pinned
   * because the slot survives unchanged for the iterator's lifetime.
   */
  @Test
  public void testGetMultiValueReturnsConstructorArg() {
    var multi = new Object();
    var iter = new EdgeIterator(
        multi, Collections.<Identifiable>emptyList().iterator(), -1, session);
    assertSame(multi, iter.getMultiValue());
  }

  // ---------- helpers --------------------------------------------------------

  private void mockLoadReturnsEdgeEntity(RID rid) {
    var entity = mock(Entity.class);
    var edge = mock(Edge.class, org.mockito.Mockito.withSettings()
        .extraInterfaces(EdgeInternal.class));
    when(entity.isEdge()).thenReturn(true);
    when(entity.asEdge()).thenReturn(edge);
    when(edge.getIdentity()).thenReturn(rid);
    // EdgeIterator's loadEdge calls transaction.loadEntity(Identifiable) (the
    // Identifiable-typed overload, not the RID one), so we must stub the
    // Identifiable signature explicitly — the parameter type at the call
    // site, not the runtime concrete RecordId.
    when(transaction.loadEntity((Identifiable) rid)).thenReturn(entity);
  }

  /**
   * Marker mock interface combining {@link Iterator}{@code <Identifiable>}
   * and {@link Sizeable} so a single Mockito mock can fulfil both.
   */
  private interface SizeableIdentifiableIterator extends Iterator<Identifiable>, Sizeable {
  }

  /**
   * Marker mock interface combining {@link Iterator}{@code <Identifiable>}
   * and {@link Resettable}.
   */
  private interface ResettableIdentifiableIterator extends Iterator<Identifiable>, Resettable {
  }

  /**
   * Marker mock interface for the multiValue parameter when it must look
   * sizeable but not be a Collection.
   */
  private interface SizeableMultiValue extends Sizeable {
  }
}
