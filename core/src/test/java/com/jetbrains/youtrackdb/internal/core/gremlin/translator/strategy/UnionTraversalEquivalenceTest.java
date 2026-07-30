package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for mid-traversal {@code union(c1, …, cN)}. Happy paths
 * must engage a {@link MultiPlanMatchStep} and return the concatenated multiset (not a cartesian
 * product). Decline paths leave the native pipeline in place.
 */
public class UnionTraversalEquivalenceTest extends GraphBaseTest {

  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  /**
   * {@code g.V().union(out("knows"), in("knows"))} translates to a multi-plan boundary and returns
   * the same vertex multiset as native. Seed is Alice→Bob→Carol: out yields {Bob, Carol}, in yields
   * {Alice, Bob}, concatenation is the four-element multiset.
   */
  @Test
  public void unionOutAndIn_returnsConcatenatedMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")),
        true);
  }

  /**
   * Anti-cartesian pin: children whose sizes' product differs from their sum must return the sum.
   * From Alice, {@code out()} yields {Bob, Carol} (size 2) and {@code out().out()} yields {Dave}
   * (size 1); sum is 3 and product is 2. A mistaken cartesian join of the child patterns would
   * return 2 rows, not the concatenated 3.
   */
  @Test
  public void unionAntiCartesian_returnsSumNotProduct() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    bob.addEdge("knows", dave);
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).union(out(), out().out()) — |c1|+|c2| ≠ |c1|·|c2|",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()),
        true);

    // Explicit size pin against a silent cartesian regression that happened to match the native
    // multiset somehow: the concatenated result must have size 3 (2+1), never the product 2.
    setTranslatorEnabled(true);
    var ids =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());
    assertThat(ids).hasSize(3);
  }

  /**
   * Children with different hop counts mint different boundary aliases ({@code $g2m_anon_0} vs
   * {@code $g2m_anon_1}). The agreement gate rewrites every child's RETURN alias to the first
   * child's canonical alias so the multi-plan boundary projects every row.
   */
  @Test
  public void unionDifferentHopCounts_canonicalAliasParity() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), out(knows).out(knows)) — differing hop aliases",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().union(__.out("knows"), __.out("knows").out("knows")),
        true);
  }

  /**
   * A RID-bearing start inside a union child still translates (cache forced off for multi-plan) and
   * returns the same multiset as native.
   */
  @Test
  public void unionWithRidBearingPrefix_returnsSameMultiset() {
    seedKnowsChain();
    var aliceId =
        graph.traversal().V().has("name", "Alice").id().next();
    assertEquivalent(
        "g.V(alice).union(out(knows), in(knows)) — RID-bearing start",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).union(__.out("knows"), __.in("knows")),
        true);
  }

  /**
   * Projection-contract mismatch: {@code values("name")} (SINGLE_VALUE) and {@code out()} (ELEMENT)
   * disagree, so the whole union declines to native.
   */
  @Test
  public void unionProjectionContractMismatch_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(values(name), out(knows)) — output-type mismatch",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.values("name"), __.out("knows")),
        false);
  }

  /**
   * Nested union inside a child declines the whole union rather than flattening.
   */
  @Test
  public void nestedUnionInsideChild_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), union(in(knows), out(knows))) — nested union",
        Recognition.DECLINED,
        () -> graph
            .traversal()
            .V()
            .union(__.out("knows"), __.union(__.in("knows"), __.out("knows"))),
        false);
  }

  /**
   * A significant step after union ({@code count()}) declines — union must be the last recognised
   * step.
   */
  @Test
  public void suffixAfterUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).count() — suffix after union",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).count(),
        false);
  }

  /**
   * Start-position {@code g.union(...)} has no vertex GraphStep prefix; the strategy and recogniser
   * both decline.
   */
  @Test
  public void startPositionUnion_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.union(V(), V().out(knows)) — start-position union",
        Recognition.DECLINED,
        () -> graph.traversal().union(__.V(), __.V().out("knows")),
        false);
  }

  /**
   * A child that the walker cannot translate (unsupported {@code flatMap}) declines the whole union.
   */
  @Test
  public void decliningChild_declinesWholeUnion() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), flatMap(out(knows))) — declining child",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.out("knows"), __.flatMap(__.out("knows"))),
        false);
  }

  /** Seeds Alice -knows-> Bob -knows-> Carol. */
  private void seedKnowsChain() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();
  }

  /**
   * Runs the shape with translator on and off; asserts boundary engagement and multiset equality.
   * When {@code expectMultiPlan} is true, the on-side boundary must be a {@link MultiPlanMatchStep}.
   */
  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      boolean expectMultiPlan) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var multiPlanOn = countMultiPlanSteps(onAdmin.getSteps());
      var onIds = drainSortedIds(onAdmin);

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = drainSortedIds(offAdmin);

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        if (expectMultiPlan) {
          assertThat(multiPlanOn)
              .as(scenario + " (translator on) must splice MultiPlanMatchStep")
              .isEqualTo(1);
        }
        assertThat(onIds)
            .as(scenario + ": RECOGNIZED fixture must return a non-empty result")
            .isNotEmpty();
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline — no boundary step")
            .isEqualTo(0);
        assertThat(multiPlanOn).isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off result multisets must match")
          .isEqualTo(offIds);
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
   * Drains a strategy-applied traversal to a sorted RID multiset. Count terminators emit a Long, so
   * those are stringified directly; element paths use vertex ids.
   */
  private static List<String> drainSortedIds(GraphTraversal.Admin<?, ?> admin) {
    var results = admin.toList();
    return results.stream()
        .map(
            v -> {
              if (v instanceof Vertex vertex) {
                return vertex.id().toString();
              }
              return String.valueOf(v);
            })
        .sorted()
        .toList();
  }

  private static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  private static int countMultiPlanSteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof MultiPlanMatchStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
