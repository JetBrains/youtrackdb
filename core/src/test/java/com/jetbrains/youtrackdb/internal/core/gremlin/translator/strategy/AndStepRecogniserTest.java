package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link AndStepRecogniser}. Each test drives the recogniser through a {@link
 * StepStreamCursor} over a strategised traversal with a hand-built {@link WalkerContext} that carries
 * the production recogniser registry (so {@link RecognitionContext#walkChild} dispatches real child
 * sub-walks). End-to-end multiset equivalence for {@code and(__.out(...), __.out(...))} lives in
 * {@link EdgeTraversalEquivalenceTest}.
 */
public class AndStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";
  private static final String SECOND_ANON_ALIAS = "$g2m_anon_1";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * {@code and(has(age), has(city))} over pure-filter children AND-composes both predicates on the
   * boundary alias and consumes one {@link AndStep}.
   */
  @Test
  public void pureFilterChildren_andComposesFiltersOnBoundary() {
    var admin =
        graph.traversal().V().and(__.has("age", P.eq(30)), __.has("city", P.eq("NYC"))).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(cursor.peek()).isInstanceOf(AndStep.class);
    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("age").contains("city");
  }

  /**
   * {@code and(out(a), out(b))} accepts mixed edge-bearing children, appends two hop fragments with
   * distinct anonymous target aliases (the sub-context alias-isolation contract), and leaves the
   * boundary pinned on the source vertex.
   */
  @Test
  public void edgeBearingChildren_appendsDistinctHopAliases() {
    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isTrue();
    assertThat(ctx.patternBuilder.hasAlias(SECOND_ANON_ALIAS)).isTrue();
    assertThat(ctx.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isEqualTo(2);
  }

  /**
   * {@code and(out(a), has(age))} accepts a mixed pure-filter + edge-bearing pair: the hop lands in
   * the pattern and the property filter AND-composes on the boundary alias.
   */
  @Test
  public void mixedChildren_commitsHopAndFilter() {
    var admin =
        graph.traversal().V().and(__.out("knows"), __.has("age", P.eq(30))).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isTrue();
    assertThat(renderBoundaryFilter(ctx)).contains("age");
  }

  /**
   * End-to-end {@link GremlinStepWalker#production()} walk for {@code and(out, out)} — the same
   * registry path the strategy uses. Pins that the combinator integrates with the start-step
   * recogniser, not only in isolation.
   */
  @Test
  public void productionWalk_andTwoOutHops_translates() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var targetA = graph.addVertex(T.label, "Person", "name", "TargetA");
    var targetB = graph.addVertex(T.label, "Person", "name", "TargetB");
    hub.addEdge("a", targetA);
    hub.addEdge("b", targetB);
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).isNotNull();
    assertThat(result.boundaryAlias()).isEqualTo(BOUNDARY_ALIAS);
  }

  /** Walk + eager plan build — the same path {@link GremlinToMatchStrategy} runs after {@code walk}. */
  @Test
  public void productionWalk_andTwoOutHops_buildsExecutionPlan() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    hub.addEdge("a", graph.addVertex(T.label, "Person", "name", "TargetA"));
    hub.addEdge("b", graph.addVertex(T.label, "Person", "name", "TargetB"));
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    var translation = GremlinToMatchTranslator.translate(admin);
    assertThat(translation).isNotNull();
    var cmdCtx = new BasicCommandContext(session);
    assertThatCode(
        () -> new MatchExecutionPlanner(translation.inputs())
            .createExecutionPlan(cmdCtx, false, false))
        .doesNotThrowAnyException();
  }

  @Test
  public void edgeBearingChildren_afterRecursiveOptimization() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    hub.addEdge("a", graph.addVertex(T.label, "Person", "name", "TargetA"));
    hub.addEdge("b", graph.addVertex(T.label, "Person", "name", "TargetB"));
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    for (TraversalStrategy<?> strategy : admin.getStrategies().toList()) {
      if (strategy instanceof TraversalStrategy.OptimizationStrategy) {
        TraversalHelper.applyTraversalRecursively(strategy::apply, admin);
      }
    }
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isTrue();
    assertThat(ctx.patternBuilder.hasAlias(SECOND_ANON_ALIAS)).isTrue();
  }

  /**
   * Recursive optimization (as in {@code applyStrategies}) must not break whole-traversal translation.
   */
  @Test
  public void recursiveOptimizationPreservesAndTranslation() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    hub.addEdge("a", graph.addVertex(T.label, "Person", "name", "TargetA"));
    hub.addEdge("b", graph.addVertex(T.label, "Person", "name", "TargetB"));
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    for (TraversalStrategy<?> strategy : admin.getStrategies().toList()) {
      if (strategy instanceof TraversalStrategy.OptimizationStrategy) {
        TraversalHelper.applyTraversalRecursively(strategy::apply, admin);
      }
    }
    assertThat(GremlinToMatchTranslator.translate(admin)).isNotNull();
  }

  /** {@code applyStrategies} must splice a boundary step — the path {@link EdgeTraversalEquivalenceTest} uses. */
  @Test
  public void applyStrategies_andTwoOutHops_engagesBoundaryStep() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    hub.addEdge("a", graph.addVertex(T.label, "Person", "name", "TargetA"));
    hub.addEdge("b", graph.addVertex(T.label, "Person", "name", "TargetB"));
    graph.tx().commit();

    var config = session.getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    try {
      var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
      admin.applyStrategies();
      var boundaryCount =
          admin.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance).count();
      assertThat(boundaryCount).isEqualTo(1);
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }

  /**
   * An {@code AndStep} with a child whose sub-walk declines (here {@code out().count()} — {@code
   * count()} is not registered) declines the whole combinator without mutating the outer context.
   */
  @Test
  public void declinedChild_declinesWholeAndStep() {
    var admin = graph.traversal().V().and(__.out("a"), __.count()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  private static Map<Class<?>, StepRecogniser> productionRegistry() {
    return Map.of(
        GraphStep.class, StartStepRecogniser.INSTANCE,
        VertexStep.class, VertexStepRecogniser.INSTANCE,
        VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
        HasStep.class, HasStepRecogniser.INSTANCE,
        TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE,
        AndStep.class, AndStepRecogniser.INSTANCE,
        OrStep.class, OrStepRecogniser.INSTANCE);
  }

  private WalkerContext contextWithRegistry(boolean polymorphic, Schema schema) {
    var ctx = new WalkerContext(polymorphic, false, schema, productionRegistry());
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  private static StepStreamCursor cursorAfterStart(Traversal.Admin<?, ?> admin) {
    var cursor = new StepStreamCursor(admin.getSteps(), TRANSPARENT);
    cursor.take();
    return cursor;
  }

  private static String renderBoundaryFilter(WalkerContext ctx) {
    var clause = ctx.aliasFilters.get(BOUNDARY_ALIAS);
    assertThat(clause).isNotNull();
    var sb = new StringBuilder();
    clause.getBaseExpression().toGenericStatement(sb);
    return sb.toString();
  }
}
