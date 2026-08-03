package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
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
   * Per-scenario cardinality opt-in for the anti-vacuity guard. A seeded {@code RECOGNIZED} case
   * must return rows ({@link #NON_EMPTY}) or the translator-on / translator-off multiset equality
   * holds vacuously over two empty lists and verifies nothing. Empty-by-design cases (empty-input
   * {@code sum}/{@code min}/{@code max}/{@code mean}, which drop the null aggregate row) opt out
   * with {@link #MAY_BE_EMPTY}. This is deliberately not a blanket {@code isNotEmpty}: the suite
   * hosts empty-result {@code RECOGNIZED} cases on purpose.
   */
  private enum Cardinality {
    NON_EMPTY, MAY_BE_EMPTY
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

    // Zero matched vertices → SQL aggregate null cell → dropNullRows drops the row. Empty by design,
    // so it opts out of the non-empty guard.
    assertEquivalent(
        "g.V().has(name, nobody).values(age).sum()",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
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

  /** Bare {@code group()} keys the map by Vertex (value = singleton vertex list), matching native. */
  @Test
  public void group_bare_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().group()", Recognition.RECOGNIZED, () -> graph.traversal().V().group());
  }

  /** {@code group().by(name)} keys the map by the property value, matching native. */
  @Test
  public void group_byName_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().group().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().group().by("name"));
  }

  /** Bare {@code groupCount()} keys the map by Vertex, matching native (guards the RID→Vertex fix). */
  @Test
  public void groupCount_bare_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().groupCount()", Recognition.RECOGNIZED, () -> graph.traversal().V().groupCount());
  }

  /** Seeded {@code sum}/{@code min}/{@code max}/{@code mean} match native values (exercises all arms). */
  @Test
  public void numericAggregates_seeded_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 10);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 20);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 30);
    graph.tx().commit();

    assertEquivalent("g.V().values(age).sum()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").sum());
    assertEquivalent("g.V().values(age).min()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").min());
    assertEquivalent("g.V().values(age).max()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").max());
    assertEquivalent("g.V().values(age).mean()", Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").mean());
  }

  /** Multi-label {@code select(a,b)} emits a two-entry map (unwrapSingletonMap=false), matching native. */
  @Test
  public void selectMultiLabel_matchNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().as(a).as(b).select(a,b)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").as("b").select("a", "b"));
  }

  /**
   * {@code dedup()} after {@code values(k)} declines to native: a RETURN DISTINCT over the boundary
   * presence column deduped on (entity, value) and the unique entity defeated it. Native dedups the
   * projected names; the payloads must match.
   */
  @Test
  public void valuesDedup_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    assertEquivalent(
        "g.V().values(name).dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("name").dedup());
  }

  /** {@code valueMap(k).dedup()} likewise declines to native (map output type, not ELEMENT). */
  @Test
  public void valueMapDedup_declinesToNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().valueMap(name).dedup()",
        Recognition.DECLINED,
        () -> graph.traversal().V().valueMap("name").dedup());
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

  // ---------------------------------------------------------------------------
  // B1 — a reducing / grouping terminator after a captured limit / skip / dedup
  // now declines to native (MATCH applies SKIP / LIMIT / DISTINCT after the
  // aggregate, Gremlin before it). Each case asserts the decline (no boundary
  // step, on/off parity) and the hand-computed native answer, so the parity is
  // not vacuous.
  // ---------------------------------------------------------------------------

  /** {@code limit(5).count()} declines to native; native counts the first 5 of 8 vertices. */
  @Test
  public void limit5Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().limit(5).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(5).count());
    assertThat(graph.traversal().V().limit(5).count().next()).isEqualTo(5L);
  }

  /** {@code skip(2).count()} declines to native; native counts the 6 remaining of 8 vertices. */
  @Test
  public void skip2Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().skip(2).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().skip(2).count());
    assertThat(graph.traversal().V().skip(2).count().next()).isEqualTo(6L);
  }

  /** {@code range(1,3).count()} declines to native; native counts the 2 vertices in {@code [1,3)}. */
  @Test
  public void range1to3Count_declinesToNative() {
    seedPeople(8);
    assertEquivalent(
        "g.V().range(1,3).count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().range(1, 3).count());
    assertThat(graph.traversal().V().range(1, 3).count().next()).isEqualTo(2L);
  }

  /**
   * {@code out(knows).dedup().count()} declines to native. A parallel edge makes Bob reachable
   * twice, so {@code out} yields {Bob, Bob, Carol}; native dedups to {Bob, Carol} before counting
   * (2). A mistranslation to {@code RETURN DISTINCT count(*)} would count the duplicates (3).
   */
  @Test
  public void outDedupCount_declinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", bob); // parallel edge → Bob reached twice by out()
    alice.addEdge("knows", carol);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows).dedup().count()",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows").dedup().count());
    assertThat(graph.traversal().V().out("knows").dedup().count().next()).isEqualTo(2L);
  }

  /**
   * {@code limit(2).values(age).mean()} declines to native. All four ages are 30, so the mean is a
   * deterministic {@code 30.0} regardless of which two vertices the limit picks.
   */
  @Test
  public void limit2ValuesMean_declinesToNative() {
    for (var i = 0; i < 4; i++) {
      graph.addVertex(T.label, "Person", "name", "P" + i, "age", 30);
    }
    graph.tx().commit();

    assertEquivalent(
        "g.V().limit(2).values(age).mean()",
        Recognition.DECLINED,
        () -> graph.traversal().V().limit(2).values("age").mean());
    assertThat(graph.traversal().V().limit(2).values("age").mean().next()).isEqualTo(30.0);
  }

  // ---------------------------------------------------------------------------
  // TC1 / TC2 — empty-input aggregate and group parity.
  // ---------------------------------------------------------------------------

  /**
   * TC1: empty-input {@code min()}/{@code max()}/{@code mean()} emit no traverser on both paths
   * ({@code dropNullRows}), matching native — the companions to the already-covered empty {@code
   * sum()}. Empty by design, so they opt out of the non-empty guard.
   */
  @Test
  public void emptyInputMinMaxMean_emitNothing() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    assertEquivalent("g.V().has(name, nobody).values(age).min()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").min());
    assertEquivalent("g.V().has(name, nobody).values(age).max()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").max());
    assertEquivalent("g.V().has(name, nobody).values(age).mean()", Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().has("name", "nobody").values("age").mean());
  }

  /**
   * TC2: empty-input {@code group().by(name)} / {@code groupCount().by(name)} emit a single empty
   * map {@code [{}]} on both paths (the accumulate-map drain of zero GROUP BY rows), matching
   * native. The single empty map is a non-empty result, so these keep the default non-empty guard.
   */
  @Test
  public void emptyInputGroup_emitsSingleEmptyMap() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(name, nobody).group().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").group().by("name"));
    assertEquivalent(
        "g.V().has(name, nobody).groupCount().by(name)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "nobody").groupCount().by("name"));
  }

  /** Seeds {@code count} Person vertices with distinct names and ages for the B1 cardinality cases. */
  private void seedPeople(int count) {
    for (var i = 0; i < count; i++) {
      graph.addVertex(T.label, "Person", "name", "P" + i, "age", 20 + i);
    }
    graph.tx().commit();
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, false);
  }

  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, cardinality, traversalSupplier, false);
  }

  private void assertEquivalentOrdered(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalentInternal(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier, true);
  }

  private void assertEquivalentInternal(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
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
        // Anti-vacuity guard (opt-in): a NON_EMPTY RECOGNIZED fixture must return rows, or the
        // multiset equality below is vacuous over two empty lists (a seed regression that persisted
        // nothing would go green while verifying nothing). Empty-by-design cases pass MAY_BE_EMPTY.
        if (cardinality == Cardinality.NON_EMPTY) {
          assertThat(onPayload)
              .as(scenario + ": a NON_EMPTY RECOGNIZED fixture must return rows "
                  + "(else the multiset equality below is vacuous)")
              .isNotEmpty();
        }
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

  /**
   * Counts translated boundary steps of <em>any</em> kind across a step list (raw {@code
   * List<Step>}). The supertype is deliberate: a shape that splices a {@code MultiPlanMatchStep}
   * instead of a single-plan step is still a translation, and counting only the single-plan subtype
   * would let such a shape satisfy a decline expectation while the translator in fact accepted it.
   */
  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
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
