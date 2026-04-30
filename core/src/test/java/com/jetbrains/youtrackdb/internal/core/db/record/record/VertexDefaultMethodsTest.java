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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Standalone unit tests for the {@code default}/{@code static} surface on the {@link Vertex}
 * interface that is reachable without a database session:
 *
 * <ul>
 *   <li>{@link Vertex#getEdgeLinkFieldName(Direction, String)} — pins the field-name format used
 *       by every persistence-layer caller that walks vertex edges. A regression here silently
 *       swaps {@code in_*}/{@code out_*} fields and corrupts traversal.
 *   <li>{@link Vertex#removeEdges(Direction, SchemaClass...)} and
 *       {@link Vertex#removeEdges(Direction, String...)} — pins that the default routes through
 *       {@code getEdges(direction, labels)} and then invokes {@code delete()} on every edge in
 *       order, with no early termination.
 * </ul>
 */
public class VertexDefaultMethodsTest {

  // ---------- getEdgeLinkFieldName: error paths ----------

  /** {@code null} direction is rejected — pin matches the production message verbatim. */
  @Test
  public void getEdgeLinkFieldNameRejectsNullDirection() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> Vertex.getEdgeLinkFieldName(null, "Friend"));
    assertEquals("Direction not valid", ex.getMessage());
  }

  /** {@link Direction#BOTH} is rejected — bidirectional storage has no single field name. */
  @Test
  public void getEdgeLinkFieldNameRejectsBoth() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Vertex.getEdgeLinkFieldName(Direction.BOTH, "Friend"));
    assertEquals("Direction not valid", ex.getMessage());
  }

  // ---------- getEdgeLinkFieldName: prefix-only branches ----------

  /** {@code null} className → bare {@code out_} prefix (the generic-edge field). */
  @Test
  public void outDirectionWithNullClassNameYieldsBarePrefix() {
    assertEquals(Vertex.DIRECTION_OUT_PREFIX, Vertex.getEdgeLinkFieldName(Direction.OUT, null));
  }

  /** Empty className → bare {@code in_} prefix. */
  @Test
  public void inDirectionWithEmptyClassNameYieldsBarePrefix() {
    assertEquals(Vertex.DIRECTION_IN_PREFIX, Vertex.getEdgeLinkFieldName(Direction.IN, ""));
  }

  /**
   * className equal to {@link Edge#CLASS_NAME} is the generic-edge sentinel — also collapses to
   * the bare prefix (both branches must agree to keep traversal consistent for un-typed edges).
   */
  @Test
  public void outDirectionWithGenericEdgeClassYieldsBarePrefix() {
    assertEquals(
        Vertex.DIRECTION_OUT_PREFIX,
        Vertex.getEdgeLinkFieldName(Direction.OUT, Edge.CLASS_NAME));
  }

  @Test
  public void inDirectionWithGenericEdgeClassYieldsBarePrefix() {
    assertEquals(
        Vertex.DIRECTION_IN_PREFIX, Vertex.getEdgeLinkFieldName(Direction.IN, Edge.CLASS_NAME));
  }

  // ---------- getEdgeLinkFieldName: prefix + class name ----------

  /** A concrete className is appended verbatim after the OUT prefix. */
  @Test
  public void outDirectionWithConcreteClassYieldsPrefixPlusName() {
    assertEquals("out_Friend", Vertex.getEdgeLinkFieldName(Direction.OUT, "Friend"));
  }

  /** A concrete className is appended verbatim after the IN prefix. */
  @Test
  public void inDirectionWithConcreteClassYieldsPrefixPlusName() {
    assertEquals("in_Friend", Vertex.getEdgeLinkFieldName(Direction.IN, "Friend"));
  }

  /** Pins the case-sensitivity of the generic-edge fast path: {@code "edge"} (lowercase) is NOT a sentinel. */
  @Test
  public void getEdgeLinkFieldNameIsCaseSensitiveAroundGenericEdgeName() {
    assertEquals("out_edge", Vertex.getEdgeLinkFieldName(Direction.OUT, "edge"));
  }

  // ---------- DIRECTION_*_PREFIX constants ----------

  @Test
  public void directionPrefixConstantsArePinned() {
    assertEquals("out_", Vertex.DIRECTION_OUT_PREFIX);
    assertEquals("in_", Vertex.DIRECTION_IN_PREFIX);
  }

  /** {@link Vertex#CLASS_NAME} matches {@link SchemaClass#VERTEX_CLASS_NAME}. */
  @Test
  public void classNameConstantMatchesSchemaConstant() {
    assertEquals(SchemaClass.VERTEX_CLASS_NAME, Vertex.CLASS_NAME);
  }

  // ---------- removeEdges(Direction, SchemaClass...) default ----------

  /**
   * The {@code removeEdges} default iterates the edges returned by
   * {@code getEdges(direction, labels)} and calls {@code delete()} on each one. With no edges in
   * the iterable the default must complete without throwing.
   */
  @Test
  public void removeEdgesSchemaClassNoEdgesIsNoOp() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    when(vertex.getEdges(any(Direction.class), any(SchemaClass[].class))).thenReturn(List.of());

    vertex.removeEdges(Direction.OUT, new SchemaClass[0]);

    verify(vertex).getEdges(Direction.OUT, new SchemaClass[0]);
  }

  /**
   * Every edge in the iterable receives a single {@code delete()} call. The default does not
   * deduplicate or short-circuit — pin the visit count.
   */
  @Test
  public void removeEdgesSchemaClassDeletesEveryEdgeOnce() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    var e1 = Mockito.mock(Edge.class);
    var e2 = Mockito.mock(Edge.class);
    var e3 = Mockito.mock(Edge.class);
    var label = Mockito.mock(SchemaClass.class);
    when(vertex.getEdges(any(Direction.class), any(SchemaClass[].class)))
        .thenReturn(List.of(e1, e2, e3));

    vertex.removeEdges(Direction.IN, new SchemaClass[] {label});

    verify(e1, times(1)).delete();
    verify(e2, times(1)).delete();
    verify(e3, times(1)).delete();
  }

  // ---------- removeEdges(Direction, String...) default ----------

  @Test
  public void removeEdgesStringNoEdgesIsNoOp() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    when(vertex.getEdges(any(Direction.class), any(String[].class))).thenReturn(List.of());

    vertex.removeEdges(Direction.BOTH, new String[0]);

    verify(vertex).getEdges(Direction.BOTH, new String[0]);
  }

  @Test
  public void removeEdgesStringDeletesEveryEdgeOnce() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    var e1 = Mockito.mock(Edge.class);
    var e2 = Mockito.mock(Edge.class);
    when(vertex.getEdges(any(Direction.class), any(String[].class))).thenReturn(List.of(e1, e2));

    vertex.removeEdges(Direction.OUT, new String[] {"Friend", "Knows"});

    verify(e1, times(1)).delete();
    verify(e2, times(1)).delete();
  }

  /**
   * Identity check: the labels passed to {@code removeEdges} are forwarded to {@code getEdges}
   * unchanged. A regression that drops the labels would silently delete every edge.
   */
  @Test
  public void removeEdgesStringForwardsLabelsToGetEdges() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    var labels = new String[] {"Friend", "Knows"};
    when(vertex.getEdges(Direction.OUT, labels)).thenReturn(List.of());

    vertex.removeEdges(Direction.OUT, labels);

    verify(vertex).getEdges(Direction.OUT, labels);
  }

  @Test
  public void removeEdgesSchemaClassForwardsLabelsToGetEdges() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    var labels = new SchemaClass[] {Mockito.mock(SchemaClass.class)};
    when(vertex.getEdges(Direction.IN, labels)).thenReturn(List.of());

    vertex.removeEdges(Direction.IN, labels);

    verify(vertex).getEdges(Direction.IN, labels);
  }

  /**
   * Pins that {@code BOTH} also reaches {@code getEdges}, falsifying any defensive-rewrite that
   * would translate {@code BOTH} into {@code OUT|IN} — the {@code removeEdges} default delegates
   * direction handling to {@code getEdges} unchanged.
   */
  @Test
  public void removeEdgesStringPropagatesBothDirection() {
    var vertex = Mockito.mock(Vertex.class, Mockito.CALLS_REAL_METHODS);
    when(vertex.getEdges(any(Direction.class), any(String[].class))).thenReturn(List.of());

    vertex.removeEdges(Direction.BOTH, new String[] {"Friend"});
    verify(vertex).getEdges(Direction.BOTH, new String[] {"Friend"});
  }
}
