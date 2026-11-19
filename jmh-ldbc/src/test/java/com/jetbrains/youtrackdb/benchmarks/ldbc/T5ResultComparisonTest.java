package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.junit.Test;

public class T5ResultComparisonTest {
  private static void check(List<?> expected, List<?> actual, T5ResultComparison.Order order) {
    T5ResultComparison.requireEqual(T5BenchmarkCatalogue.expected("fixture", order, expected),
        actual, e -> new T5ResultComparison.ElementKey(
            e instanceof org.apache.tinkerpop.gremlin.structure.Vertex ? "vertex" : "edge",
            e.label(), e.value("logicalKey")));
  }

  /** Only root multiset order is ignored. Nested lists and duplicate rows remain observable. */
  @Test
  public void multiplicityNestedOrderAndSequenceHaveIndependentNegativeCases() {
    var multiset = T5ResultComparison.Order.MULTISET;
    check(List.of(1L, 2L, 1L), List.of(2L, 1L, 1L), multiset);
    assertThrows(AssertionError.class, () -> check(List.of(1L, 1L), List.of(1L), multiset));
    assertThrows(AssertionError.class, () -> check(List.of(List.of(1L, 2L)),
        List.of(List.of(2L, 1L)), multiset));
    assertThrows(AssertionError.class, () -> check(List.of(1L, 2L), List.of(2L, 1L),
        T5ResultComparison.Order.SEQUENCE));
    check(Arrays.asList(null, 1L), Arrays.asList(1L, null), multiset);
  }

  /** Numeric runtime types, token keys, absent keys, nulls and empty lists never collapse. */
  @Test
  public void mapsAndScalarTypesRejectLossyEquality() {
    var order = T5ResultComparison.Order.SEQUENCE;
    for (Object wrong : List.of(1, "1", 1.0, (short) 1)) {
      assertThrows(AssertionError.class, () -> check(List.of(1L), List.of(wrong), order));
    }
    var nullable = new HashMap<Object, Object>();
    nullable.put("x", null);
    assertThrows(AssertionError.class, () -> check(List.of(Map.of()), List.of(nullable), order));
    assertThrows(AssertionError.class, () -> check(List.of(nullable),
        List.of(Map.of("x", List.of())), order));
    assertThrows(AssertionError.class, () -> check(List.of(Map.of(T.id, 1L)),
        List.of(Map.of("id", 1L)), order));
    check(List.of(Map.of(T.label, "Person", "dates", List.of(new Date(10)))),
        List.of(Map.of("dates", List.of(new Date(10)), T.label, "Person")), order);
    check(List.of(Map.entry("a", 2L)), List.of(Map.entry("a", 2L)), order);
    assertThrows(IllegalArgumentException.class,
        () -> check(List.of(new Object()), List.of(), order));
  }

  /** Physical ids may change. Kind, label and parallel-edge logical keys may not change. */
  @Test
  public void logicalIdentityDistinguishesParallelEdgesWithoutPhysicalRids() {
    var a = new DetachedVertex(1, "Person", Map.of("logicalKey", List.of(Map.of("value", "p"))));
    var b = new DetachedVertex(99, "Person", Map.of("logicalKey", List.of(Map.of("value", "p"))));
    check(List.of(a), List.of(b), T5ResultComparison.Order.SEQUENCE);
    var first = new DetachedEdge(1, "KNOWS", Map.of("logicalKey", "edge1"),
        1, "Person", 2, "Person");
    var second = new DetachedEdge(2, "KNOWS", Map.of("logicalKey", "edge2"),
        1, "Person", 2, "Person");
    assertThrows(AssertionError.class,
        () -> check(List.of(first), List.of(second), T5ResultComparison.Order.MULTISET));
    assertThrows(AssertionError.class,
        () -> check(List.of(a), List.of(first), T5ResultComparison.Order.MULTISET));
  }
}
