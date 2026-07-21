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
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link NotStepRecogniser}. Each test drives the recogniser through a {@link
 * StepStreamCursor} over a strategised traversal with a hand-built {@link WalkerContext} that carries
 * the production recogniser registry. End-to-end multiset equivalence for {@code hasNot(key)},
 * {@code not(has(...))}, and {@code not(out(...))} lives in {@link PredicateTraversalEquivalenceTest}
 * and {@link EdgeTraversalEquivalenceTest}.
 */
public class NotStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * {@code hasNot("nickname")} maps to {@code nickname IS NOT DEFINED} on the boundary alias — entity
   * absence, distinct from {@code IS NULL}.
   */
  @Test
  public void hasNot_valuesChild_contributesIsNotDefined() {
    var admin = graph.traversal().V().hasNot("nickname").asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(cursor.peek()).isInstanceOf(NotStep.class);
    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("nickname is not defined");
  }

  /**
   * The {@code properties(key)} child form is accepted too: optimisation may rewrite {@code
   * hasNot(key)}'s {@code values(key)} child into {@code properties(key)} before g2m runs.
   */
  @Test
  public void hasNot_propertiesChild_contributesIsNotDefined() {
    var admin = graph.traversal().V().not(__.properties("nickname")).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).containsIgnoringCase("nickname is not defined");
  }

  /**
   * Pure-filter {@code not(has(age))} wraps the captured boundary predicate in WHERE NOT and leaves
   * the positive pattern edge-free.
   */
  @Test
  public void pureFilterChild_wrapsBoundaryPredicateInNot() {
    var admin = graph.traversal().V().not(__.has("age", P.eq(30))).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("age");
    assertThat(ctx.notMatchExpressions).isEmpty();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * Edge-bearing {@code not(out(knows))} appends a detached NOT expression and does not add hops to
   * the positive pattern.
   */
  @Test
  public void edgeBearingChild_appendsDetachedNotExpression() {
    var admin = graph.traversal().V().not(__.out("knows")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.notMatchExpressions).hasSize(1);
    assertThat(ctx.notMatchExpressions.getFirst().getOrigin().getAlias()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(ctx.notMatchExpressions.getFirst().getItems()).hasSize(1);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * {@code not(out(knows).has(city))} attaches the captured target filter to the NOT path item.
   */
  @Test
  public void edgeBearingChildWithTargetFilter_attachesLeafWhere() {
    var admin = graph.traversal().V().not(__.out("knows").has("city", P.eq("NYC"))).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.notMatchExpressions).hasSize(1);
    var leafFilter = ctx.notMatchExpressions.getFirst().getItems().getFirst().getFilter();
    assertThat(leafFilter).isNotNull();
    assertThat(leafFilter.getAlias()).isEqualTo(FIRST_ANON_ALIAS);
    var sb = new StringBuilder();
    leafFilter.getFilter().getBaseExpression().toGenericStatement(sb);
    assertThat(sb.toString()).contains("city");
  }

  /**
   * When the NOT origin alias is absent from the positive pattern, the recogniser declines
   * rather than emitting a planner-disqualifying detached expression.
   */
  @Test
  public void edgeBearingChild_originAbsentFromPositivePattern_declines() {
    var admin = graph.traversal().V().not(__.out("knows")).asAdmin();
    var ctx = new WalkerContext(true, false, null, productionRegistry());
    // boundary pinned but no positive-pattern node registered for it
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.notMatchExpressions).isEmpty();
  }

  /**
   * A child sub-walk that declines ({@code count()} is unregistered) declines the whole {@code
   * NotStep} without mutating the outer context.
   */
  @Test
  public void declinedChild_declinesWholeNotStep() {
    var admin = graph.traversal().V().not(__.count()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.notMatchExpressions).isEmpty();
  }

  /** End-to-end production walk for {@code has(age).not(out(knows))} builds a planner-ready plan. */
  @Test
  public void productionWalk_positiveWherePlusNotOut_buildsExecutionPlan() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    alice.addEdge("knows", bob);
    graph.tx().commit();

    var admin =
        graph.traversal().V().has("age", 30).not(__.out("knows")).asAdmin();
    var translation = GremlinToMatchTranslator.translate(admin);
    assertThat(translation).isNotNull();
    assertThat(translation.inputs().notMatchExpressions()).hasSize(1);
    var cmdCtx = new BasicCommandContext(session);
    assertThatCode(
        () -> new MatchExecutionPlanner(translation.inputs())
            .createExecutionPlan(cmdCtx, false, false))
        .doesNotThrowAnyException();
  }

  /** {@code applyStrategies} must splice a boundary step for a translated NOT shape. */
  @Test
  public void applyStrategies_hasAgeNotOut_engagesBoundaryStep() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    alice.addEdge("knows", graph.addVertex(T.label, "Person", "name", "Bob"));
    graph.tx().commit();

    var config = session.getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    try {
      var admin =
          graph.traversal().V().has("age", 30).not(__.out("knows")).asAdmin();
      admin.applyStrategies();
      var boundaryCount =
          admin.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance).count();
      assertThat(boundaryCount).isEqualTo(1);
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }

  private static Map<Class<?>, StepRecogniser> productionRegistry() {
    return Map.of(
        GraphStep.class, StartStepRecogniser.INSTANCE,
        VertexStep.class, VertexStepRecogniser.INSTANCE,
        VertexStepPlaceholder.class, VertexStepRecogniser.INSTANCE,
        HasStep.class, HasStepRecogniser.INSTANCE,
        TraversalFilterStep.class, TraversalFilterStepRecogniser.INSTANCE,
        AndStep.class, AndStepRecogniser.INSTANCE,
        OrStep.class, OrStepRecogniser.INSTANCE,
        NotStep.class, NotStepRecogniser.INSTANCE);
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
