package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
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
 * sub-walks). End-to-end multiset equivalence for the declined {@code and(__.out(...), __.out(...))}
 * shape lives in {@link EdgeTraversalEquivalenceTest}.
 */
public class AndStepRecogniserTest extends GraphBaseTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";
  private static final String SECOND_ANON_ALIAS = "$g2m_anon_1";
  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /**
   * {@code and(has(age), has(city))} over pure-filter children AND-composes both predicates on the
   * boundary alias and consumes one {@link AndStep}.
   */
  @Test
  public void pureFilterChildren_andComposesFiltersOnBoundary() {
    var admin =
        graph.traversal().V().and(__.has("age", P.eq(30)).barrier(), __.has("city", P.eq("NYC")))
            .asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(cursor.peek()).isInstanceOf(AndStep.class);
    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(renderBoundaryFilter(ctx)).contains("age").contains("city");
  }

  /**
   * {@code and(out(a), out(b))} declines: native {@code and(...)} passes each source through once,
   * while appending both hops to the positive pattern would emit one row per pair of matching
   * targets. The context must be left untouched — no hop alias, no edge, no boundary filter — so the
   * traversal runs on the native pipeline unchanged.
   */
  @Test
  public void edgeBearingChildren_declineWithoutMutatingContext() {
    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.hasAlias(SECOND_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /**
   * {@code and(out(knows), has(age))} declines on its edge-bearing arm, and the pure-filter arm must
   * not be committed on the way out. A recogniser that committed arm-by-arm would leave the
   * {@code age} predicate on the boundary of a walk that then declines, which is the partial-commit
   * shape the all-or-nothing dispatch contract forbids.
   */
  @Test
  public void mixedChildren_declineWithoutCommittingThePureFilterArm() {
    var admin =
        graph.traversal().V().and(__.out("knows"), __.has("age", P.eq(30)).barrier()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * Nested {@code and(and(out(a), out(b)), has(age))} declines, and the decline propagates outward
   * rather than being re-derived at each level: the inner combinator hits the edge-bearing gate
   * itself, {@link ConnectiveStepSupport#walkAcceptedChildren} sees the middle adapter's DECLINE
   * outcome, and the outer AND withdraws before it reads any classification. A regression that let
   * the inner AND accept would translate the shape and drop the hops.
   */
  @Test
  public void nestedAndOfOutHops_thenHas_declines() {
    var admin =
        graph
            .traversal()
            .V()
            .and(__.and(__.out("a"), __.out("b")), __.has("age", P.eq(30)).barrier())
            .asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /**
   * End-to-end {@link GremlinStepWalker#production()} walk for {@code and(out, out)} — the same
   * registry path the strategy uses. The decline has to survive the full walk, not only the
   * recogniser in isolation: a null result is what keeps the traversal on the native pipeline.
   */
  @Test
  public void productionWalk_andTwoOutHops_declines() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var targetA = graph.addVertex(T.label, "Person", "name", "TargetA");
    var targetB = graph.addVertex(T.label, "Person", "name", "TargetB");
    hub.addEdge("a", targetA);
    hub.addEdge("b", targetB);
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();

    assertThat(GremlinStepWalker.production().walk(admin)).isNull();
  }

  /**
   * Walk + eager plan build for the pure-filter AND that still translates — the same path {@link
   * GremlinToMatchStrategy} runs after {@code walk}. Pins that the surviving combinator shape
   * reaches a buildable plan, so the edge-bearing decline above did not take the whole recogniser
   * out of service.
   */
  @Test
  public void productionWalk_andTwoPureFilters_buildsExecutionPlan() {
    graph.addVertex(T.label, "Person", "name", "Hub", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Other", "age", 31);
    graph.tx().commit();

    var admin =
        graph.traversal().V().and(__.has("age", P.eq(30)).barrier(), __.has("name", P.eq("Hub")))
            .asAdmin();
    var translation = GremlinToMatchTranslator.translate(admin);
    assertThat(translation).isNotNull();
    var cmdCtx = new BasicCommandContext(session);
    assertThatCode(
        () -> new MatchExecutionPlanner(translation.inputs())
            .createExecutionPlan(cmdCtx, false, false))
        .doesNotThrowAnyException();
  }

  /**
   * Recursive optimization rewrites {@code out(L)} into the folded {@code outE(L).inV()} form that
   * {@code applyStrategies} produces. The decline must key on the child's edge contribution rather
   * than on the un-optimised step shape, or the rewritten traversal would slip past the gate and
   * over-emit again.
   */
  @Test
  public void edgeBearingChildren_afterRecursiveOptimization_stillDecline() {
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

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.hasAlias(SECOND_ANON_ALIAS)).isFalse();
  }

  /**
   * Recursive optimization (as in {@code applyStrategies}) must not break whole-traversal translation
   * of the pure-filter AND that still translates.
   *
   * <p>The barrier in the first arm is load-bearing, not decoration. {@code InlineFilterStrategy}
   * rewrites a plain {@code and(has, has)} into a {@code has}-chain and deletes the {@code AndStep}
   * outright, so the un-barriered spelling would assert AND coverage over a traversal that no longer
   * contains an {@code AndStep}. A {@code NoOpBarrierStep} blocks the inlining and is already in the
   * walker's transparent set, so the arm stays a pure filter. The step-survival assertion below pins
   * that, because it is the assertion whose absence let the hole open.
   */
  @Test
  public void recursiveOptimizationPreservesPureFilterAndTranslation() {
    graph.addVertex(T.label, "Person", "name", "Hub", "age", 30);
    graph.tx().commit();

    var admin =
        graph.traversal().V()
            .and(__.has("age", P.eq(30)).barrier(), __.has("name", P.eq("Hub")))
            .asAdmin();
    for (TraversalStrategy<?> strategy : admin.getStrategies().toList()) {
      if (strategy instanceof TraversalStrategy.OptimizationStrategy) {
        TraversalHelper.applyTraversalRecursively(strategy::apply, admin);
      }
    }
    assertThat(TraversalHelper.hasStepOfClass(AndStep.class, admin))
        .as("the optimised traversal must still carry an AndStep, or this case witnesses nothing "
            + "about AND")
        .isTrue();
    assertThat(GremlinToMatchTranslator.translate(admin)).isNotNull();
  }

  /**
   * {@code applyStrategies} must splice no boundary step for the edge-bearing AND, and exactly one
   * for the pure-filter AND. Both halves in one test because the discriminating claim is the
   * contrast: a zero-count assertion alone would also pass if the strategy had stopped engaging
   * altogether.
   *
   * <p>Both halves first check that an {@code AndStep} is still there for the translator to see.
   * {@code InlineFilterStrategy} rewrites a plain {@code and(has, has)} into a {@code has}-chain and
   * deletes the step outright, so the pure-filter half would otherwise assert AND coverage over a
   * traversal with no AND in it; the barrier in the first arm blocks that rewrite and is transparent
   * to the walker. The survival check runs on a translator-off pass because a translated traversal
   * is replaced wholesale by the boundary step, leaving nothing to inspect.
   */
  @Test
  public void applyStrategies_engagesBoundaryStepOnlyForThePureFilterAnd() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub", "age", 30);
    hub.addEdge("a", graph.addVertex(T.label, "Person", "name", "TargetA"));
    hub.addEdge("b", graph.addVertex(T.label, "Person", "name", "TargetB"));
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> edgeBearing =
        () -> graph.traversal().V().and(__.out("a"), __.out("b"));
    Supplier<GraphTraversal<?, ?>> pureFilter =
        () -> graph.traversal().V()
            .and(__.has("age", P.eq(30)).barrier(), __.has("name", P.eq("Hub")));

    // The body toggles the flag itself — the survival checks below need it off and the boundary
    // counts need it on — so the harness only owns the restore.
    support.withTranslatorRestored(
        () -> {
          assertAndStepReachesTheTranslator("edge-bearing and(out(a), out(b))", edgeBearing);
          assertAndStepReachesTheTranslator(
              "pure-filter and(has(age).barrier(), has(name))", pureFilter);

          support.setTranslatorEnabled(true);
          var edgeBearingAdmin = edgeBearing.get().asAdmin();
          edgeBearingAdmin.applyStrategies();
          assertThat(countBoundarySteps(edgeBearingAdmin))
              .as("an edge-bearing and(...) must decline to the native pipeline")
              .isZero();

          var pureFilterAdmin = pureFilter.get().asAdmin();
          pureFilterAdmin.applyStrategies();
          assertThat(countBoundarySteps(pureFilterAdmin))
              .as("a pure-filter and(...) must still engage the boundary step")
              .isEqualTo(1);
        });
  }

  /**
   * Asserts the shape still carries an {@code AndStep} once TinkerPop's optimisation strategies have
   * run, which is the step list the translator is handed. Driven with the translator off: those
   * strategies run identically either way (they precede the provider stage the translator is in),
   * and an accepted translation replaces the whole step list with the boundary step, so the on-run
   * cannot answer the question.
   */
  private void assertAndStepReachesTheTranslator(
      String scenario, Supplier<GraphTraversal<?, ?>> supplier) {
    support.setTranslatorEnabled(false);
    var admin = supplier.get().asAdmin();
    admin.applyStrategies();
    assertThat(TraversalHelper.hasStepOfClass(AndStep.class, admin))
        .as(scenario + ": the AndStep must survive optimisation, or the boundary-step count below "
            + "says nothing about AND")
        .isTrue();
  }

  /**
   * An {@code AndStep} with a child whose sub-walk declines (here {@code count()} — not registered)
   * declines the whole combinator without mutating the outer context. The other arm is a pure filter
   * so the decline can only come from the unrecognised child, not from the edge-bearing gate.
   */
  @Test
  public void declinedChild_declinesWholeAndStep() {
    var admin = graph.traversal().V().and(__.has("age", P.eq(30)).barrier(), __.count()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /** Without a pinned boundary the recogniser declines rather than inventing an origin alias. */
  @Test
  public void nullBoundary_declines() {
    var admin = graph.traversal().V().and(__.has("age", P.eq(30)).barrier()).asAdmin();
    var ctx = new WalkerContext(true, false, null, productionRegistry());
    var cursor = cursorAfterStart(admin);

    assertThat(AndStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
  }

  /**
   * Feeding the recogniser a non-{@code AndStep} head (defence-in-depth against a registry mistake)
   * declines and leaves the outer context untouched.
   */
  @Test
  public void nonAndStepHead_declines() {
    var admin = graph.traversal().V().has("age", P.eq(30)).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    assertThat(AndStepRecogniser.INSTANCE.recognize(cursor, ctx)).isEqualTo(Outcome.DECLINE);
    assertThat(ctx.aliasFilters).isEmpty();
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
