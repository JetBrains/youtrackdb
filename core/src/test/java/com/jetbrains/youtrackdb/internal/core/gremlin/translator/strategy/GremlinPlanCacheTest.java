package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Before;
import org.junit.Test;

/**
 * R6 determinism and correctness tests for {@link GremlinPlanCache} and {@link
 * GremlinPlanFingerprint}: distinct shapes occupy distinct entries, same shapes fingerprint
 * identically, positional rebinding serves the second value's multiset, RID-bearing shapes bypass
 * the cache, and schema changes invalidate entries.
 */
public class GremlinPlanCacheTest extends GraphBaseTest {

  @Before
  public void enableTranslator() {
    graphSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    GremlinPlanCache.instance(graphSession()).invalidate();
  }

  /** {@code eq(null)} (bare {@code IS NULL}) and scalar {@code eq(v)} ({@code = ?}) differ in fingerprint. */
  @Test
  public void eqNull_and_eqValue_distinctFingerprints() {
    var nullWalk = walk(() -> graph.traversal().V().has("age", P.eq(null)));
    var valueWalk = walk(() -> graph.traversal().V().has("age", P.eq(30)));
    assertThat(fingerprint(nullWalk)).isNotEqualTo(fingerprint(valueWalk));
  }

  /** Distinct {@code hasLabel} class names stay discriminating in the fingerprint (R1). */
  @Test
  public void distinctHasLabel_distinctFingerprints_polymorphicAndNonPolymorphic() {
    seedPersonEmployeeHierarchy();
    withPolymorphic(true, () -> {
      var person = walk(() -> graph.traversal().V().hasLabel("Person"));
      var company = walk(() -> graph.traversal().V().hasLabel("Company"));
      assertThat(fingerprint(person)).isNotEqualTo(fingerprint(company));
    });
    withPolymorphic(false, () -> {
      var person = walk(() -> graph.traversal().V().hasLabel("Person"));
      var employee = walk(() -> graph.traversal().V().hasLabel("Employee"));
      assertThat(fingerprint(person)).isNotEqualTo(fingerprint(employee));
    });
  }

  /** NOT-differing shapes ({@code not(out(a))} vs {@code not(out(b))}, NOT vs no-NOT) differ (A1). */
  @Test
  public void notDifferingShapes_distinctFingerprints() {
    seedKnowsGraph();
    var notA = walk(() -> graph.traversal().V().not(__.out("knows")));
    var notB = walk(() -> graph.traversal().V().not(__.out("likes")));
    assertThat(fingerprint(notA)).isNotEqualTo(fingerprint(notB));

    var noNot = walk(() -> graph.traversal().V());
    assertThat(fingerprint(notA)).isNotEqualTo(fingerprint(noNot));
  }

  /** {@code hasId(...)} marks the walk RID-bearing and bypasses the plan cache. */
  @Test
  public void hasId_bypassesPlanCache() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();
    var result = walk(() -> graph.traversal().V().hasId(alice.id()));
    assertThat(result.cacheEligible()).isFalse();

    apply(() -> graph.traversal().V().hasId(alice.id()));
    var fp = fingerprint(result);
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isFalse();
  }

  /** Two independent walks of the same shape produce identical fingerprints (R2). */
  @Test
  public void sameShape_identicalFingerprint() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    var first = walk(() -> graph.traversal().V().has("age", P.eq(30)));
    var second = walk(() -> graph.traversal().V().has("age", P.eq(99)));
    assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
  }

  /**
   * A cached plan reused with a second predicate value returns the second value's multiset, not the
   * first's (R3).
   */
  @Test
  public void cachedPlan_rebindsSecondValue() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.tx().commit();

    apply(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", P.eq(30))));
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isTrue();

    var secondRun = apply(() -> graph.traversal().V().has("age", 40));
    assertThat(sortedNames(secondRun)).containsExactly("Bob");
  }

  /** {@code within} with different element counts does not collide on fingerprint. */
  @Test
  public void withinDifferentSizes_distinctFingerprints() {
    var one = walk(() -> graph.traversal().V().has("age", P.within(30)));
    var two = walk(() -> graph.traversal().V().has("age", P.within(30, 40)));
    assertThat(fingerprint(one)).isNotEqualTo(fingerprint(two));
  }

  /** Schema listener invalidates the cache; no live schema mutation required. */
  @Test
  public void schemaChange_invalidatesCache() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    apply(() -> graph.traversal().V().has("age", 30));
    var fp = fingerprint(walk(() -> graph.traversal().V().has("age", P.eq(30))));
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isTrue();

    var before = GremlinPlanCache.getLastInvalidation(graphSession());
    GremlinPlanCache.instance(graphSession()).onSchemaUpdate(null, "test", null);
    assertThat(GremlinPlanCache.getLastInvalidation(graphSession())).isGreaterThan(before);
    assertThat(GremlinPlanCache.instance(graphSession()).contains(fp)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private GremlinToMatchTranslator.TranslationResult walk(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var admin = supplier.get().asAdmin();
    var result = GremlinStepWalker.production().walk(admin);
    assertThat(result).isNotNull();
    return result;
  }

  private List<?> apply(
      java.util.function.Supplier<
          org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal<?, ?>> supplier) {
    var admin = supplier.get().asAdmin();
    GremlinToMatchStrategy.instance().apply(admin);
    assertThat(admin.getSteps()).hasSize(1);
    assertThat(admin.getSteps().getFirst()).isInstanceOf(YTDBMatchPlanStep.class);
    return admin.toList();
  }

  private static String fingerprint(GremlinToMatchTranslator.TranslationResult result) {
    return GremlinPlanFingerprint.fingerprint(result.inputs());
  }

  private static List<String> sortedNames(List<?> vertices) {
    return vertices.stream().map(v -> ((Vertex) v).value("name")).map(Object::toString).sorted()
        .toList();
  }

  private void seedPersonEmployeeHierarchy() {
    graph.addVertex(T.label, "Person", "name", "Pat");
    graph.addVertex(T.label, "Employee", "name", "Em");
    graph.addVertex(T.label, "Company", "name", "Co");
    graph.tx().commit();
  }

  private void seedKnowsGraph() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    alice.addEdge("likes", bob);
    graph.tx().commit();
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private void withPolymorphic(boolean value, Runnable body) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }
}
