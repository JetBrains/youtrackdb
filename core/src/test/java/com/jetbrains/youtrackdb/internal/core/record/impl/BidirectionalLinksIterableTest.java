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

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * Standalone coverage for {@link BidirectionalLinksIterable}. The iterable
 * wraps an edge {@link Iterable} and yields the opposite vertex of each
 * edge in a configured direction.
 *
 * <p>The {@link BidirectionalLinksIterable#size()} dispatch has four arms:
 * null inner iterable (zero), {@link Sizeable} (delegate), {@link
 * java.util.Collection} (delegate), and "neither" (throws). The
 * {@link BidirectionalLinksIterable#isSizeable()} predicate mirrors the
 * size dispatch arms (null + Sizeable.isSizeable + Collection).
 */
public class BidirectionalLinksIterableTest {

  /**
   * The {@link BidirectionalLinksIterable#iterator()} factory produces a
   * {@link BidirectionalLinkToEntityIterator}; iteration end-to-end yields
   * the configured-direction vertex per edge. Verifies the iterator
   * factory wires up correctly without any inner mocking layer.
   */
  @Test
  public void testIteratorYieldsOppositeVertexPerEdge() {
    var to = mock(Vertex.class);
    var edge = mock(Edge.class);
    when(edge.getTo()).thenReturn(to);

    var iterable = new BidirectionalLinksIterable(List.of(edge), Direction.OUT);
    var iterator = iterable.iterator();

    assertTrue(iterator.hasNext());
    assertSame(to, iterator.next());
    assertFalse(iterator.hasNext());
  }

  /**
   * {@code size()} dispatch arm 1 — null inner iterable.
   */
  @Test
  public void testSizeReturnsZeroForNullInnerIterable() {
    var iterable = new BidirectionalLinksIterable(null, Direction.OUT);
    assertEquals(0, iterable.size());
    assertTrue("null inner iterable still reports sizeable=true",
        iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 2 — inner iterable implements
   * {@link Sizeable}; size delegates and {@code isSizeable} reflects the
   * inner-iterable's flag.
   */
  @Test
  public void testSizeDelegatesToSizeableInnerIterable() {
    var sizeable = mock(SizeableEdgeIterable.class);
    when(sizeable.size()).thenReturn(7);
    when(sizeable.isSizeable()).thenReturn(true);

    var iterable = new BidirectionalLinksIterable(sizeable, Direction.OUT);
    assertEquals(7, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 3 — inner iterable is a plain
   * {@link java.util.Collection}; size delegates to {@code Collection.size}.
   */
  @Test
  public void testSizeDelegatesToCollectionInnerIterable() {
    var iterable = new BidirectionalLinksIterable(
        List.of(mock(Edge.class), mock(Edge.class), mock(Edge.class)),
        Direction.OUT);
    assertEquals(3, iterable.size());
    assertTrue(iterable.isSizeable());
  }

  /**
   * {@code size()} dispatch arm 4 — inner iterable is neither sizeable nor
   * a collection; size must throw {@link UnsupportedOperationException}
   * naming the unsupported type. {@code isSizeable} must return false for
   * the same case.
   */
  @Test
  public void testSizeThrowsForOpaqueInnerIterable() {
    Iterable<Edge> opaque = Collections::emptyIterator;
    var iterable = new BidirectionalLinksIterable(opaque, Direction.OUT);

    assertFalse("opaque inner iterable must report sizeable=false",
        iterable.isSizeable());

    try {
      iterable.size();
      fail("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      assertTrue("message must name the inner iterable's type",
          e.getMessage().contains("Size is not supported"));
    }
  }

  /**
   * {@code isSizeable()} returns true for a {@link Sizeable} inner iterable
   * whose own {@code isSizeable} reports true, and false when the inner
   * iterable's flag reports false. Pins the delegating arm explicitly.
   */
  @Test
  public void testIsSizeableMirrorsInnerSizeableFlag() {
    var sizeable = mock(SizeableEdgeIterable.class);
    when(sizeable.isSizeable()).thenReturn(false);
    var iterable = new BidirectionalLinksIterable(sizeable, Direction.OUT);
    assertFalse(iterable.isSizeable());
  }

  /**
   * Marker interface used in the tests above so a Mockito mock can implement
   * both {@link Iterable} and {@link Sizeable} simultaneously.
   */
  private interface SizeableEdgeIterable extends Iterable<Edge>, Sizeable {

    @Override
    Iterator<Edge> iterator();
  }
}
