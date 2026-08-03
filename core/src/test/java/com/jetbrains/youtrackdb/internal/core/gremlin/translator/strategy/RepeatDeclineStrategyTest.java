package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the decline of {@code repeat(...)}-bearing traversals. Variable-depth repetition is out of
 * scope for the translator, so every such traversal must reach the native pipeline untouched.
 *
 * <p>The defect these tests exist for: {@code RepeatUnrollStrategy} rewrites {@code
 * repeat(__.out()).times(n)} into n chained {@code VertexStep}s separated by {@code
 * NoOpBarrierStep}s, and the walker treats {@code NoOpBarrierStep} as transparent, so what reaches
 * the walker is indistinguishable from a hand-written n-hop chain. The translator folded it into a
 * single MATCH pattern and the planner then materialized every path — on the TinkerPop grateful-dead
 * fixture {@code times(8)} has 2,505,037,961,767,380 of them, so the query never returned. Native
 * Gremlin answers it in milliseconds because the barriers merge identical traversers into bulks.
 *
 * <p>Each case therefore asserts three things:
 *
 * <ul>
 *   <li><b>Zero boundary steps</b> with the translator on. Both the {@code .count()} form and the
 *       element form are covered: the element form materializes the same path space, so a
 *       count-only test would pass while the catastrophic shape stayed translated.
 *   <li><b>The same result as the translator-off run</b>, with an explicit expected value so the
 *       comparison cannot hold vacuously over two empty results.
 *   <li><b>{@code RepeatUnrollStrategy} still applied</b> on both arms. The decline works by
 *       removing the translator from that one traversal's strategy list, never by removing the
 *       unroll — dropping the unroll would strip the barriers that make the native fallback fast
 *       and would move the non-termination from MATCH into the Gremlin pipeline.
 * </ul>
 *
 * <p>Both polymorphism modes are exercised because the class constraint a recogniser emits differs
 * between them, and a decline that only held under one mode would leave the other translating.
 */
public class RepeatDeclineStrategyTest extends GraphBaseTest {

  /**
   * Seeds a four-vertex {@code knows} chain a→b→c→d. Two hops from every start vertex reaches
   * exactly {c, d} (a→b→c and b→c→d), which is the expected value every case below pins.
   */
  private void seedKnowsChain() {
    var a = graph.addVertex(T.label, "Person", "name", "a");
    var b = graph.addVertex(T.label, "Person", "name", "b");
    var c = graph.addVertex(T.label, "Person", "name", "c");
    var d = graph.addVertex(T.label, "Person", "name", "d");
    a.addEdge("knows", b);
    b.addEdge("knows", c);
    c.addEdge("knows", d);
    graph.tx().commit();
  }

  /**
   * {@code g.V().repeat(__.out()).times(2).count()} under the default polymorphic mode must run
   * natively and return 2 — the two vertices reachable in exactly two hops.
   */
  @Test
  public void repeatTimesCount_declinesAndCountsNatively_polymorphic() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2).count()",
        () -> graph.traversal().V().repeat(__.out()).times(2).count(),
        List.of("2"));
  }

  /**
   * The same {@code .count()} shape under non-polymorphic mode. The mode changes how a recogniser
   * would constrain the class, so the decline is pinned separately rather than assumed to carry.
   */
  @Test
  public void repeatTimesCount_declinesAndCountsNatively_nonPolymorphic() {
    seedKnowsChain();
    withNonPolymorphicDefault(
        () -> assertDeclinedAndEquals(
            "g.V().repeat(out()).times(2).count() (non-polymorphic)",
            () -> graph.traversal().V().repeat(__.out()).times(2).count(),
            List.of("2")));
  }

  /**
   * {@code g.V().repeat(__.out()).times(2)} — the element form — under the default polymorphic
   * mode. It materializes the same path space as the count form, so it is pinned in its own right:
   * a fix verified only through {@code .count()} would leave this shape translated.
   */
  @Test
  public void repeatTimesElements_declinesAndMatchesNative_polymorphic() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2)",
        () -> graph.traversal().V().repeat(__.out()).times(2).values("name"),
        List.of("c", "d"));
  }

  /** The element form under non-polymorphic mode; same reasoning as the count form's second case. */
  @Test
  public void repeatTimesElements_declinesAndMatchesNative_nonPolymorphic() {
    seedKnowsChain();
    withNonPolymorphicDefault(
        () -> assertDeclinedAndEquals(
            "g.V().repeat(out()).times(2) (non-polymorphic)",
            () -> graph.traversal().V().repeat(__.out()).times(2).values("name"),
            List.of("c", "d")));
  }

  /**
   * {@code until(...)} is the other out-of-scope loop terminator, and it reaches the walker through
   * the same {@code RepeatStep}. Pinned so a decline keyed on {@code times(n)} alone would fail
   * here. Walking {@code knows} until a vertex has no outgoing edge reaches {@code d} from every
   * start vertex that can move at all.
   */
  @Test
  public void repeatUntil_declinesAndMatchesNative() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).until(__.not(__.out()))",
        () -> graph
            .traversal()
            .V()
            .repeat(__.out("knows"))
            .until(__.not(__.out("knows")))
            .values("name"),
        List.of("d", "d", "d"));
  }

  /**
   * A {@code repeat(...)} nested inside a combinator child must veto the whole traversal, not just
   * that child. The translator is all-or-nothing per traversal, so a partial decline would leave
   * the parent translating a pattern assembled from a child it could not read. Over a→b→c→d the
   * union of "two hops" {c, d} and "one hop" {b, c, d} is the five-element multiset below.
   *
   * <p>This shape already declined before the veto existed — the union recogniser rejects it for
   * its own reasons — so the case is a guard on the recursive scan rather than a witness for the
   * fix. It is here so the decline stops depending on which recogniser happens to say no first.
   */
  @Test
  public void repeatNestedInAUnionChild_declinesTheWholeTraversal() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().union(repeat(out()).times(2), out())",
        () -> graph
            .traversal()
            .V()
            .union(__.repeat(__.out()).times(2), __.out())
            .values("name"),
        List.of("b", "c", "c", "d", "d"));
  }

  /**
   * A traversal source that has already dropped the translator needs no veto: the strategy returns
   * before resolving the session, and the traversal runs natively. This pins the cheap pre-check
   * that keeps a repeat-bearing traversal from starting a transaction it has no use for.
   */
  @Test
  public void translatorAlreadyRemovedFromTheSource_needsNoVeto() {
    seedKnowsChain();
    setTranslatorEnabled(true);
    var admin =
        graph
            .traversal()
            .withoutStrategies(GremlinToMatchStrategy.class)
            .V()
            .repeat(__.out())
            .times(2)
            .values("name")
            .asAdmin();
    admin.applyStrategies();

    assertThat(countBoundarySteps(admin))
        .as("a source without the translator must produce no boundary step")
        .isZero();
    assertThat(sortedStrings(admin.toList()))
        .as("and must still return the native two-hop result")
        .isEqualTo(List.of("c", "d"));
  }

  /**
   * The decline must stay narrow: a hand-written chain of the same length still translates. Without
   * this case a decline that accidentally keyed on the barrier steps — which
   * {@code LazyBarrierStrategy} also inserts into ordinary chains — would look correct while
   * silently switching every multi-hop traversal back to the native pipeline.
   */
  @Test
  public void handWrittenChainOfHopsStillTranslates() {
    seedKnowsChain();
    setTranslatorEnabled(true);
    var admin = graph.traversal().V().out("knows").out("knows").asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin))
        .as("a chained two-hop traversal must still engage exactly one boundary step")
        .isEqualTo(1);
  }

  /**
   * With the translator off the traversal's strategy list must be exactly what it is on the base
   * branch: the translator still registered, the unroll still registered and still applied. This is
   * the control arm every measurement on this branch compares against, so a decline mechanism that
   * touched it would invalidate the comparison as well as change a shipped path.
   */
  @Test
  public void translatorOff_leavesTheTraversalStrategyListUntouched() {
    seedKnowsChain();
    setTranslatorEnabled(false);
    var admin = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();
    admin.applyStrategies();

    assertThat(admin.getStrategies().getStrategy(GremlinToMatchStrategy.class))
        .as("translator off: the translator strategy stays in the traversal's own strategy list")
        .isPresent();
    assertThat(admin.getStrategies().getStrategy(RepeatUnrollStrategy.class))
        .as("translator off: the unroll strategy stays in the traversal's own strategy list")
        .isPresent();
    assertThat(admin.getSteps())
        .as("translator off: the unroll still rewrote the repeat into chained hops")
        .noneMatch(step -> step instanceof RepeatStep<?>);
  }

  /**
   * Runs the shape with the translator on and again off, asserting that the on-run engages no
   * boundary step, that both runs produce {@code expected}, and that the unroll ran on both arms.
   * The expected value is passed in rather than derived from the off-run alone, so a seeding
   * regression that emptied the graph would fail the case instead of making it pass vacuously.
   */
  private void assertDeclinedAndEquals(
      String scenario, Supplier<GraphTraversal<?, ?>> traversalSupplier, List<String> expected) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin);
      var unrolledOn = onAdmin.getStrategies().getStrategy(RepeatUnrollStrategy.class).isPresent();
      var onValues = sortedStrings(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var offValues = sortedStrings(offAdmin.toList());

      assertThat(boundaryOn)
          .as(scenario + " (translator on) must decline to native — no boundary step")
          .isZero();
      assertThat(unrolledOn)
          .as(scenario + " (translator on) must keep the unroll strategy, which supplies the "
              + "barriers the native fallback needs")
          .isTrue();
      assertThat(onValues).as(scenario + " (translator on) result").isEqualTo(expected);
      assertThat(offValues).as(scenario + " (translator off) result").isEqualTo(expected);
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
   * Runs {@code body} with {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT} forced to false, restoring
   * the previous value afterwards, so the non-polymorphic cases exercise the mode in which an
   * explicit-class recogniser would narrow the scan.
   */
  private void withNonPolymorphicDefault(Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    Assert.assertNotNull(config);
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** Result values as sorted strings; sorting preserves multiplicity for the multiset comparison. */
  private static List<String> sortedStrings(List<?> results) {
    return results.stream()
        .map(value -> value instanceof Vertex vertex ? vertex.id().toString()
            : String.valueOf(value))
        .sorted()
        .toList();
  }

  /** Counts boundary steps of every form, keyed on the shared base rather than one concrete step. */
  private static int countBoundarySteps(Traversal.Admin<?, ?> admin) {
    var count = 0;
    for (var step : admin.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
