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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Standalone coverage for {@link BidirectionalLinkToEntityIterator}. The
 * iterator wraps an inner edge iterator and yields the opposite vertex of
 * each edge based on the configured {@link Direction}: {@code OUT} returns
 * {@code edge.getTo()}, {@code IN} returns {@code edge.getFrom()}.
 * {@code BOTH} is rejected at construction time.
 *
 * <p>The tests pin: the BOTH-throws contract, IN/OUT branch dispatch,
 * empty-iterator behaviour, multi-element iteration, and {@code hasNext()}
 * idempotency (delegation to the inner iterator).
 */
public class BidirectionalLinkToEntityIteratorTest {

  /**
   * Constructing with {@link Direction#BOTH} must throw
   * {@link IllegalArgumentException} with a message that names the
   * unsupported direction. This keeps callers from silently exhausting an
   * edge iterator twice.
   */
  @Test
  public void testBothDirectionRejectedAtConstruction() {
    var emptyEdges = Collections.<Edge>emptyList().iterator();
    try {
      new BidirectionalLinkToEntityIterator(emptyEdges, Direction.BOTH);
      fail("Expected IllegalArgumentException for Direction.BOTH");
    } catch (IllegalArgumentException e) {
      assertTrue("error message must mention BOTH",
          e.getMessage().contains("BOTH"));
    }
  }

  /**
   * With {@link Direction#OUT}, each edge yields its {@code getTo()} vertex.
   * Verifies the OUT branch of the dispatch switch.
   */
  @Test
  public void testOutDirectionYieldsEdgeGetTo() {
    var to = mock(Vertex.class);
    var edge = mock(Edge.class);
    when(edge.getTo()).thenReturn(to);

    var iterator = new BidirectionalLinkToEntityIterator(
        List.of(edge).iterator(), Direction.OUT);

    assertTrue(iterator.hasNext());
    assertSame("OUT must yield edge.getTo()", to, iterator.next());
    assertFalse(iterator.hasNext());
  }

  /**
   * With {@link Direction#IN}, each edge yields its {@code getFrom()}
   * vertex. Verifies the IN branch of the dispatch switch.
   */
  @Test
  public void testInDirectionYieldsEdgeGetFrom() {
    var from = mock(Vertex.class);
    var edge = mock(Edge.class);
    when(edge.getFrom()).thenReturn(from);

    var iterator = new BidirectionalLinkToEntityIterator(
        List.of(edge).iterator(), Direction.IN);

    assertTrue(iterator.hasNext());
    assertSame("IN must yield edge.getFrom()", from, iterator.next());
    assertFalse(iterator.hasNext());
  }

  /**
   * An empty inner iterator yields an empty outer iterator immediately.
   */
  @Test
  public void testEmptyInnerIteratorYieldsEmpty() {
    var iterator = new BidirectionalLinkToEntityIterator(
        Collections.<Edge>emptyList().iterator(), Direction.OUT);
    assertFalse(iterator.hasNext());
  }

  /**
   * Multi-element inner iterator yields a vertex per edge. {@code hasNext()}
   * tracks the inner iterator's exhaustion.
   */
  @Test
  public void testMultiElementYieldsOnePerEdge() {
    var to1 = mock(Vertex.class);
    var to2 = mock(Vertex.class);
    var to3 = mock(Vertex.class);
    var e1 = mock(Edge.class);
    var e2 = mock(Edge.class);
    var e3 = mock(Edge.class);
    when(e1.getTo()).thenReturn(to1);
    when(e2.getTo()).thenReturn(to2);
    when(e3.getTo()).thenReturn(to3);

    var iterator = new BidirectionalLinkToEntityIterator(
        List.of(e1, e2, e3).iterator(), Direction.OUT);

    assertSame(to1, iterator.next());
    assertSame(to2, iterator.next());
    assertSame(to3, iterator.next());
    assertFalse(iterator.hasNext());
  }

  /**
   * {@code hasNext()} just delegates to the inner iterator (no caching layer
   * in this class, unlike the LinkBag iterators). Calling it repeatedly must
   * not advance past elements.
   */
  @Test
  public void testHasNextDelegatesToInnerIterator() {
    var to = mock(Vertex.class);
    var edge = mock(Edge.class);
    when(edge.getTo()).thenReturn(to);

    var iterator = new BidirectionalLinkToEntityIterator(
        List.of(edge).iterator(), Direction.OUT);

    // Multiple hasNext() calls before next().
    assertTrue(iterator.hasNext());
    assertTrue(iterator.hasNext());
    assertEquals(to, iterator.next());
    assertFalse(iterator.hasNext());
    assertFalse(iterator.hasNext());
  }
}
