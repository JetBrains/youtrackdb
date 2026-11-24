package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/** Lossless comparison for every T5 population. No physical record identity enters an answer. */
public final class T5ResultComparison {
  public enum Order {
    SEQUENCE, MULTISET
  }

  /** The resolver must supply an immutable logical key unique within its element kind and label.
   * Parallel edges need separate keys, even when their endpoints and properties are equal. */
  public record ElementKey(String kind, String label, Object key) {
    public ElementKey {
      if (!List.of("vertex", "edge").contains(kind)) {
        throw new IllegalArgumentException("Unknown element kind");
      }
      Objects.requireNonNull(label);
      Objects.requireNonNull(key);
    }
  }

  private record Scalar(Class<?> type, Object value) {
  }

  private record Entry(Object key, Object value) {
  }

  public record Expected(String identity, Order order, List<?> rows) {
    public Expected {
      rows = java.util.Collections.unmodifiableList(new ArrayList<>(rows));
      Objects.requireNonNull(identity);
      Objects.requireNonNull(order);
    }
  }

  public static void requireEqual(Expected expected, List<?> actual,
      Function<Element, ElementKey> identity) {
    var left = rows(expected.rows(), identity);
    var right = rows(actual, identity);
    if (!Objects.equals(expected.order() == Order.SEQUENCE ? left : frequencies(left),
        expected.order() == Order.SEQUENCE ? right : frequencies(right))) {
      throw new AssertionError("Result mismatch: " + expected.identity());
    }
  }

  private static List<Object> rows(List<?> input, Function<Element, ElementKey> identity) {
    return input.stream().map(v -> value(v, identity)).toList();
  }

  private static Map<Object, Long> frequencies(List<Object> rows) {
    var result = new HashMap<Object, Long>();
    rows.forEach(v -> result.merge(v, 1L, Long::sum));
    return result;
  }

  private static Object value(Object v, Function<Element, ElementKey> identity) {
    if (v == null) {
      return null;
    }
    if (v instanceof Element e) {
      var key = Objects.requireNonNull(identity.apply(e));
      String kind = e instanceof Vertex ? "vertex" : e instanceof Edge ? "edge" : "unsupported";
      if (!key.kind().equals(kind) || !key.label().equals(e.label())) {
        throw new IllegalArgumentException("Logical identity kind or label mismatch");
      }
      return value(key, identity);
    }
    if (v instanceof ElementKey k) {
      return new ElementKey(k.kind(), k.label(), value(k.key(), identity));
    }
    if (v instanceof List<?> list) {
      return rows(list, identity);
    }
    if (v instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      map.forEach((k, x) -> {
        var key = value(k, identity);
        if (result.containsKey(key)) {
          throw new IllegalArgumentException("Logical map key collision");
        }
        result.put(key, value(x, identity));
      });
      return result;
    }
    if (v instanceof Map.Entry<?, ?> e) {
      return new Entry(value(e.getKey(), identity), value(e.getValue(), identity));
    }
    if (v instanceof T token) {
      return new Scalar(T.class, token);
    }
    if (v.getClass() == Date.class) {
      return new Scalar(Date.class, ((Date) v).getTime());
    }
    if (List.of(String.class, Boolean.class, Character.class, Byte.class, Short.class,
        Integer.class, Long.class, Float.class, Double.class, BigInteger.class,
        BigDecimal.class).contains(v.getClass())) {
      return new Scalar(v.getClass(), v);
    }
    throw new IllegalArgumentException("Unsupported result type: " + v.getClass().getName());
  }

  private T5ResultComparison() {
  }
}
