/*
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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Standalone unit tests for the {@code default} methods on the {@link Edge} interface
 * ({@link Edge#getVertex(Direction)} and {@link Edge#getVertexLink(Direction)}). Both methods
 * dispatch on direction to one of two abstract accessors — the tests pin that mapping in both
 * directions, the rejection of {@link Direction#BOTH}, and the null-pass-through.
 *
 * <p>The {@code @Nonnull dir} parameter contract excludes {@code null} input by static
 * convention, so we do not pin a null-direction case (Mockito will not auto-violate that).
 */
public class EdgeDefaultMethodsTest {

  // ---------- getVertex(Direction) ----------

  /** {@code IN} maps to {@code getTo()} (the destination vertex). */
  @Test
  public void getVertexInRoutesToGetTo() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var to = Mockito.mock(Vertex.class);
    when(edge.getTo()).thenReturn(to);

    assertSame(to, edge.getVertex(Direction.IN));
  }

  /** {@code OUT} maps to {@code getFrom()} (the source vertex). */
  @Test
  public void getVertexOutRoutesToGetFrom() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var from = Mockito.mock(Vertex.class);
    when(edge.getFrom()).thenReturn(from);

    assertSame(from, edge.getVertex(Direction.OUT));
  }

  /** {@code BOTH} is rejected — pin matches the production message verbatim. */
  @Test
  public void getVertexBothRejected() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var ex = assertThrows(IllegalArgumentException.class, () -> edge.getVertex(Direction.BOTH));
    assertEquals("Direction not supported: BOTH", ex.getMessage());
  }

  /** {@code null} accessor result is propagated unchanged (the @Nullable contract). */
  @Test
  public void getVertexInPropagatesNullTo() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    when(edge.getTo()).thenReturn(null);

    assertNull(edge.getVertex(Direction.IN));
  }

  @Test
  public void getVertexOutPropagatesNullFrom() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    when(edge.getFrom()).thenReturn(null);

    assertNull(edge.getVertex(Direction.OUT));
  }

  // ---------- getVertexLink(Direction) ----------

  /** {@code IN} maps to {@code getToLink()}. */
  @Test
  public void getVertexLinkInRoutesToGetToLink() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var toLink = Mockito.mock(Identifiable.class);
    when(edge.getToLink()).thenReturn(toLink);

    assertSame(toLink, edge.getVertexLink(Direction.IN));
  }

  /** {@code OUT} maps to {@code getFromLink()}. */
  @Test
  public void getVertexLinkOutRoutesToGetFromLink() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var fromLink = Mockito.mock(Identifiable.class);
    when(edge.getFromLink()).thenReturn(fromLink);

    assertSame(fromLink, edge.getVertexLink(Direction.OUT));
  }

  /** {@code BOTH} is rejected — pin matches the production message verbatim. */
  @Test
  public void getVertexLinkBothRejected() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    var ex =
        assertThrows(IllegalArgumentException.class, () -> edge.getVertexLink(Direction.BOTH));
    assertEquals("Direction not supported: BOTH", ex.getMessage());
  }

  @Test
  public void getVertexLinkInPropagatesNullToLink() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    when(edge.getToLink()).thenReturn(null);

    assertNull(edge.getVertexLink(Direction.IN));
  }

  @Test
  public void getVertexLinkOutPropagatesNullFromLink() {
    var edge = Mockito.mock(Edge.class, Mockito.CALLS_REAL_METHODS);
    when(edge.getFromLink()).thenReturn(null);

    assertNull(edge.getVertexLink(Direction.OUT));
  }

  // ---------- Edge constants ----------

  @Test
  public void edgeConstantsArePinned() {
    assertEquals("out", Edge.DIRECTION_OUT);
    assertEquals("in", Edge.DIRECTION_IN);
    assertEquals(SchemaClass.EDGE_CLASS_NAME, Edge.CLASS_NAME);
  }
}
