package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import java.util.List;
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
 * sub-walks). End-to-end multiset equivalence for {@code and(__.out(...), __.out(...))}
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
   * Two hop arms become two detached exists checks without adding positive pattern edges.
   */
  @Test
  public void edgeBearingChildren_appendTwoDetachedChecks() {
    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.existsMatchExpressions).hasSize(2);
    assertThat(ctx.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.hasAlias(SECOND_ANON_ALIAS)).isFalse();
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).isEmpty();
  }

  /**
   * A mixed AND commits a boundary predicate and one detached exists expression.
   */
  @Test
  public void mixedChildren_commitFilterAndExists() {
    var admin =
        graph.traversal().V().and(__.out("knows"), __.has("age", P.eq(30)).barrier()).asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.aliasFilters).containsKey(BOUNDARY_ALIAS);
    assertThat(ctx.existsMatchExpressions).hasSize(1);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
  }

  /**
   * Nested AND forwards both detached checks through the outer conjunctive child.
   */
  @Test
  public void nestedAndOfOutHops_thenHas_forwardsBothChecks() {
    var admin =
        graph
            .traversal()
            .V()
            .and(__.and(__.out("a"), __.out("b")), __.has("age", P.eq(30)).barrier())
            .asAdmin();
    var ctx = contextWithRegistry(true, null);
    var cursor = cursorAfterStart(admin);

    var outcome = AndStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    assertThat(ctx.aliasFilters).containsKey(BOUNDARY_ALIAS);
    assertThat(ctx.existsMatchExpressions).hasSize(2);
  }

  /**
   * End-to-end {@link GremlinStepWalker#production()} walk for {@code and(out, out)} — the same
   * registry path the strategy uses. Both detached checks survive the full walk.
   */
  @Test
  public void productionWalk_andTwoOutHops_carriesExistsChecks() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var targetA = graph.addVertex(T.label, "Person", "name", "TargetA");
    var targetB = graph.addVertex(T.label, "Person", "name", "TargetB");
    hub.addEdge("a", targetA);
    hub.addEdge("b", targetB);
    graph.tx().commit();

    var admin = graph.traversal().V().and(__.out("a"), __.out("b")).asAdmin();

    assertThat(GremlinStepWalker.production().walk(admin).inputs().existsMatchExpressions())
        .hasSize(2);
  }

  /**
   * Walk + eager plan build for the pure-filter AND that still translates — the same path {@link
   * GremlinToMatchStrategy} runs after {@code walk}. Pins that the surviving combinator shape
   * reaches a buildable plan alongside the edge-bearing translation.
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
   * {@code applyStrategies} produces. The detached check must handle the folded hop too.
   */
  @Test
  public void edgeBearingChildren_afterRecursiveOptimization_stillTranslate() {
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
    assertThat(ctx.existsMatchExpressions).hasSize(2);
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
   * {@code applyStrategies} splices one boundary step for both the edge-bearing and pure-filter
   * AND, so both branches of the connective recogniser remain active.
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
              .as("an edge-bearing and(...) must engage the MATCH boundary")
              .isEqualTo(1);

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

  /** A failed sibling class preflight commits neither class changes nor property filters. */
  @Test
  public void wideningSiblingPreflight_declinesWithoutCommittingAnyChild() {
    var user = session.createVertexClass("User");
    session.getSchema().createClass("Employee", user);
    var admin = graph.traversal().V().and(
        __.hasLabel("Employee").has("name", "e").barrier(),
        __.hasLabel("User").has("name", "u").barrier()).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());

    assertThat(AndStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), ctx))
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.patternBuilder.registeredAliasClasses()).containsOnlyKeys(BOUNDARY_ALIAS)
        .containsEntry(BOUNDARY_ALIAS, "V");
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.notMatchExpressions).isEmpty();
    assertThat(ctx.inputParameters).hasSize(2);
  }

  /** Hop targets keep their local class while a narrowing sibling changes only the parent node. */
  @Test
  public void hopAndNarrowingSibling_commitOnlyBoundaryClassAndDetachedCheck() {
    createLabelHierarchy();
    for (var polymorphic : List.of(true, false)) {
      var admin = graph.traversal().V().and(
          __.out("follows").hasLabel("User"), __.hasLabel("Manager").barrier()).asAdmin();
      var ctx = contextWithRegistry(polymorphic, session.getSchema());
      ctx.addNode(BOUNDARY_ALIAS, "Employee");

      assertThat(AndStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), ctx))
          .isEqualTo(Outcome.ACCEPTED);
      assertThat(ctx.patternBuilder.registeredAliasClasses())
          .containsOnlyKeys(BOUNDARY_ALIAS).containsEntry(BOUNDARY_ALIAS, "Manager");
      assertThat(ctx.existsMatchExpressions).hasSize(1);
      assertThat(ctx.existsMatchExpressions.getFirst().toString()).contains("User");
      assertThat(ctx.patternBuilder.build().pattern().getNumOfEdges()).isZero();
    }
  }

  /** A later widening sibling commits no earlier narrowing, property filter or detached check. */
  @Test
  public void hopBetweenWideningSiblings_preflightDeclinesWithoutAnyCommit() {
    createLabelHierarchy();
    for (var polymorphic : List.of(true, false)) {
      var admin = graph.traversal().V().and(
          __.hasLabel("Employee").has("name", "e").barrier(),
          __.out("follows"), __.hasLabel("User").barrier()).asAdmin();
      var ctx = contextWithRegistry(polymorphic, session.getSchema());
      ctx.addNode(BOUNDARY_ALIAS, "User");

      assertThat(AndStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), ctx))
          .isEqualTo(Outcome.DECLINE);
      assertNoChildCommitted(ctx, "User");
    }
  }

  /** A label before a hop cannot enter an exists check, even if its origin class narrows. */
  @Test
  public void boundaryLabelBeforeHop_declinesWithoutCommittingEarlierSibling() {
    createLabelHierarchy();
    for (var polymorphic : List.of(true, false)) {
      for (var label : List.of("Manager", "User")) {
        var admin = graph.traversal().V().and(
            __.hasLabel("Manager").has("name", "m").barrier(),
            __.hasLabel(label).barrier().out("follows")).asAdmin();
        var ctx = contextWithRegistry(polymorphic, session.getSchema());
        ctx.addNode(BOUNDARY_ALIAS, "Employee");

        // Narrowing is accepted by the sub-walk but rejected by detached origin validation.
        // Widening is rejected by the sub-walk's D5 guard before detached validation.
        var child = ctx.walkChild(__.hasLabel(label).barrier().out("follows").asAdmin());
        assertThat(child.outcome()).isEqualTo(
            label.equals("Manager") ? Outcome.ACCEPTED : Outcome.DECLINE);
        if (child.outcome() == Outcome.ACCEPTED) {
          assertThat(child.hasEdges()).isTrue();
          assertThat(child.capturedPattern().registeredAliasClasses())
              .containsEntry(BOUNDARY_ALIAS, "Manager");
          assertThat(ConnectiveStepSupport.detachedExists(ctx, child)).isNull();
        }
        assertThat(AndStepRecogniser.INSTANCE.recognize(cursorAfterStart(admin), ctx))
            .isEqualTo(Outcome.DECLINE);
        assertNoChildCommitted(ctx, "Employee");
      }
    }
  }

  private void createLabelHierarchy() {
    var user = session.createVertexClass("User");
    var employee = session.getSchema().createClass("Employee", user);
    session.getSchema().createClass("Manager", employee);
  }

  private static void assertNoChildCommitted(WalkerContext ctx, String originalClass) {
    assertThat(ctx.patternBuilder.registeredAliasClasses())
        .containsOnlyKeys(BOUNDARY_ALIAS).containsEntry(BOUNDARY_ALIAS, originalClass);
    assertThat(ctx.aliasFilters).isEmpty();
    assertThat(ctx.existsMatchExpressions).isEmpty();
    assertThat(ctx.notMatchExpressions).isEmpty();
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
