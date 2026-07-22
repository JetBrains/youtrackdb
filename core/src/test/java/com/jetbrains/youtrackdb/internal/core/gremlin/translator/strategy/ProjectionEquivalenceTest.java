package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for Track 6 projection and aggregate terminators
 * ({@code values} / {@code valueMap} / {@code elementMap} / {@code select} / {@code count} /
 * {@code mean} / {@code groupCount} / order / range / dedup). Multiset equality is on a
 * string-canonicalised payload so Maps / scalars / lists compare without relying on Vertex RID
 * sorting from {@link EdgeTraversalEquivalenceTest}.
 */
public class ProjectionEquivalenceTest extends GraphBaseTest {

  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  /**
   * Absent vs present-null for {@code valueMap}: native and translated both omit absent keys and
   * include present-null as {@code [null]}.
   */
  @Test
  public void valueMap_absentVsPresentNull_matchNative() {
    var withNull = graph.addVertex(T.label, "Person", "name", "HasNull");
    withNull.property("foo", null);
    graph.addVertex(T.label, "Person", "name", "Absent");
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap(foo)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().valueMap("foo"));
  }

  /**
   * {@code values("foo")} emits a null traverser for present-null and nothing for absent — the
   * {@code dropOnAbsent} path.
   */
  @Test
  public void values_absentVsPresentNull_matchNative() {
    var withNull = graph.addVertex(T.label, "Person", "name", "HasNull");
    withNull.property("foo", null);
    graph.addVertex(T.label, "Person", "name", "Absent");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(foo)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("foo"));
  }

  /** {@code elementMap("name")} matches native id/label/property maps. */
  @Test
  public void elementMap_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.tx().commit();

    assertEquivalent(
        "g.V().elementMap(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().elementMap("name"));
  }

  /** {@code select("v")} after {@code as("v")} returns the same vertex multiset as native. */
  @Test
  public void selectBoundLabel_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(v).select(v)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("v").select("v"));
  }

  /** Empty-input {@code count()} emits {@code 0L} on both paths. */
  @Test
  public void count_empty_emitsZero() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(name, nobody).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").count());
  }

  /** Empty-input {@code sum()} emits no traverser ({@code dropNullRows}). */
  @Test
  public void sum_empty_emitsNothing() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    // Zero matched vertices → SQL aggregate null cell → dropNullRows drops the row.
    assertEquivalent(
        "g.V().has(name, nobody).values(age).sum()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").values("age").sum());
  }

  /** Non-empty {@code count()} matches native. */
  @Test
  public void count_seeded_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().count());
  }

  /** {@code groupCount().by("name")} matches native map (single traverser). */
  @Test
  public void groupCount_byName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("name"));
  }

  /** {@code order().by("name")} matches native order for a deterministic seed. */
  @Test
  public void orderByName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name"));
  }

  /** {@code limit(2)} after order matches native. */
  @Test
  public void orderLimit_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalentOrdered(
        "g.V().order().by(name).limit(2).values(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").limit(2).values("name"));
  }

  /** {@code dedup()} matches native vertex multiset. */
  @Test
  public void dedup_matchNative() {
    var a = graph.addVertex(T.label, "Person", "name", "Alice");
    var b = graph.addVertex(T.label, "Person", "name", "Bob");
    a.addEdge("knows", b);
    a.addEdge("knows", b);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows).dedup()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").dedup());
  }

  /**
   * Named {@code dedup("v")} on the current boundary keeps ELEMENT emission and matches native
   * (DISTINCT without rewriting RETURN under the user alias).
   */
  @Test
  public void namedDedup_currentBoundary_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(v).dedup(v)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("v").dedup("v"));
  }

  /**
   * {@code dedup().by("name")} declines to native — MATCH cannot DISTINCT-ON a property while
   * still emitting the current element.
   */
  @Test
  public void dedupByName_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().dedup().by(name)",
        Recognition.DECLINED,
        () -> graph.traversal().V().dedup().by("name"));
  }

  /**
   * Named dedup on a prior path label declines to native (unique-by-{@code a}, emit-{@code b} is
   * not MATCH {@code DISTINCT} on RETURN).
   */
  @Test
  public void namedDedup_priorLabel_declinesToNative() {
    var a = graph.addVertex(T.label, "Person", "name", "Alice");
    var b1 = graph.addVertex(T.label, "Person", "name", "Bob");
    var b2 = graph.addVertex(T.label, "Person", "name", "Carol");
    a.addEdge("knows", b1);
    a.addEdge("knows", b2);
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(a).out(knows).as(b).dedup(a)",
        Recognition.DECLINED,
        () -> graph.traversal().V().as("a").out("knows").as("b").dedup("a"));
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, traversalSupplier, false);
  }

  private void assertEquivalentOrdered(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, traversalSupplier, true);
  }

  private void assertEquivalentInternal(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      boolean ordered) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onPayload = canonicalize(onAdmin.toList(), ordered);

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offPayload = canonicalize(offAdmin.toList(), ordered);

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline — no boundary step")
            .isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onPayload)
          .as(scenario + ": translator-on and translator-off payloads must match")
          .isEqualTo(offPayload);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void setTranslatorEnabled(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (Object step : steps) {
      if (step instanceof YTDBMatchPlanStep) {
        count++;
      }
    }
    return count;
  }

  private static List<String> canonicalize(List<?> results, boolean ordered) {
    var mapped = new ArrayList<String>(results.size());
    for (Object result : results) {
      mapped.add(canonicalizeOne(result));
    }
    if (!ordered) {
      mapped.sort(Comparator.naturalOrder());
    }
    return mapped;
  }

  private static String canonicalizeOne(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Vertex vertex) {
      return "V:" + Objects.toString(vertex.id());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .sorted(Comparator.comparing(e -> Objects.toString(e.getKey())))
          .map(e -> canonicalizeOne(e.getKey()) + "=" + canonicalizeOne(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof Collection<?> collection) {
      var parts = collection.stream().map(ProjectionEquivalenceTest::canonicalizeOne).sorted()
          .collect(Collectors.joining(",", "[", "]"));
      return parts;
    }
    if (value instanceof Number number) {
      // Align Long / Integer / Double count cells (hardwired count may box differently).
      if (number.doubleValue() == Math.rint(number.doubleValue())) {
        return "N:" + number.longValue();
      }
      return "N:" + number.doubleValue();
    }
    return value.getClass().getSimpleName() + ":" + value;
  }
}
