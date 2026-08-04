package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
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
 * the production recogniser registry. General end-to-end multiset equivalence for {@code
 * hasNot(key)} and {@code not(out(...))} lives in {@link PredicateTraversalEquivalenceTest} and
 * {@link EdgeTraversalEquivalenceTest}.
 *
 * <p>The cases at the end of this class are the exception: the boundary cases for the
 * range-comparison decline — which predicate families still translate under {@code not(...)} and
 * which withdraw the whole traversal to the native pipeline. They are translator-on /
 * translator-off equivalence tests, kept here beside the recogniser branch that owns the decline,
 * and they are the tree's only end-to-end coverage of {@code not(has(...))}.
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
   * {@code not(out(knows).hasLabel(Software))} binds the target class onto the NOT path item. Under
   * the default polymorphic mode {@code hasLabel} contributes no {@code @class} term to the leaf
   * {@code WHERE}, so the path item's class slot is the only place the constraint can live — a null
   * there silently degrades the anti-join to {@code not(out(knows))} and excludes rows that have any
   * out-edge at all. The other cases here assert aliases, item counts and {@code WHERE} text; this is
   * the one that reads a class off a NOT item. End-to-end multiset equivalence for the same shape is
   * in {@link PredicateTraversalEquivalenceTest}.
   */
  @Test
  public void edgeBearingChildWithTargetLabel_bindsTargetClassOnNotItem() {
    session.createVertexClass("Software");
    var admin = graph.traversal().V().not(__.out("knows").hasLabel("Software")).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.notMatchExpressions).hasSize(1);
    var leafFilter = ctx.notMatchExpressions.getFirst().getItems().getFirst().getFilter();
    assertThat(leafFilter).isNotNull();
    assertThat(leafFilter.getAlias()).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(leafFilter.getClassName(null))
        .as("the NOT path item carries the target class; polymorphic mode puts it nowhere else")
        .isEqualTo("Software");
  }

  /**
   * {@code not(out(knows))} with no {@code hasLabel} leaves the NOT path item's class slot empty.
   * The hop recognisers register the generic vertex root on every target, labelled or not, so the
   * unguarded reading of that registration would put {@code class: V} on an item the user wrote no
   * label for. The generic root excludes nothing a vertex hop can reach, so the constraint would be
   * pure cost — and on a link whose collection resolves to no schema class the per-candidate check
   * answers "no match", which keeps a row the anti-join should have dropped. This is the counterpart
   * of the labelled case above; the two together pin that only a real label reaches the item.
   */
  @Test
  public void edgeBearingChildWithoutTargetLabel_leavesNotItemClassUnbound() {
    var admin = graph.traversal().V().not(__.out("knows")).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
    var cursor = cursorAfterStart(admin);

    var outcome = NotStepRecogniser.INSTANCE.recognize(cursor, ctx);

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ctx.notMatchExpressions).hasSize(1);
    var leafFilter = ctx.notMatchExpressions.getFirst().getItems().getFirst().getFilter();
    assertThat(leafFilter).isNotNull();
    assertThat(leafFilter.getClassName(null))
        .as("no hasLabel was written, so the generic vertex root must not reach the item")
        .isNull();
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
   * {@code not(has(city).out(knows))} captures a boundary-alias filter the bare-origin NOT contract
   * cannot express — decline rather than emit a filterless anti-join.
   */
  @Test
  public void edgeBearingChild_withOriginAliasFilter_declines() {
    var admin =
        graph.traversal().V().not(__.has("city", P.eq("NYC")).out("knows")).asAdmin();
    var ctx = contextWithRegistry(true, session.getSchema());
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

    withTranslator(true, () -> {
      var admin = graph.traversal().V().has("age", 30).not(__.out("knows")).asAdmin();
      admin.applyStrategies();
      assertThat(countBoundarySteps(admin.getSteps()))
          .as("a translated NOT shape must engage exactly one boundary step")
          .isEqualTo(1);
    });
  }

  // ---------------------------------------------------------------------------
  // Negated range comparisons — translator-on / translator-off equivalence.
  //
  // A range comparison under not(...) used to decline outright. Measured on the modern graph:
  // g.V().has("name", P.gt(27)) answers all six on both arms, because YouTrackDB folds a root has()
  // into its own graph step, whose comparator orders a String above an Integer rather than calling
  // the comparison undefined. Inside not(...) the child is not folded, so the native arm runs
  // TinkerPop's rule that a cross-type comparison is unknown: the child yields nothing and not(...)
  // keeps all six, while a plain SQL NOT(name > 27) keeps none. The two native behaviours disagree
  // with each other, so no unguarded translation of the child matched both.
  //
  // The per-record type guard reproduces the unfolded rule directly — an unfolded range comparison
  // emits key.type() IN [<the literal's block>] beside it — so these shapes translate now and agree.
  // The decline is gone; what these cases pin is that the translated answer is still native's.
  // RangeTypeGuardEquivalenceTest carries the mechanism and its scoping.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().not(__.has("name", P.gt(27)))} on the modern graph compares a String property with
   * an Integer comparand. Native keeps all six vertices — the comparison is unknown, so the child
   * yields nothing and the NOT passes every row — while a plain SQL {@code NOT(name > 27)} would
   * keep none. The guarded translation keeps all six too: the type conjunct is false for a String
   * {@code name} against a numeric literal, so the inner expression is false and the NOT passes the
   * row, which is what TinkerPop's own {@code NotP} does with an incomparable pair.
   */
  @Test
  public void notWithCrossTypeRangeComparison_translatesAndAgreesWithNative() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().not(has(name, gt(27)))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.has("name", P.gt(27))));

    // Pin the native answer: multiset equality between two empty results would pass while the shape
    // returned nothing at all on either arm.
    withTranslator(
        false,
        () -> assertThat(graph.traversal().V().not(__.has("name", P.gt(27))).toList())
            .as("native not(has(name, gt(27))) keeps every vertex — the cross-type comparison is "
                + "unknown, so the child yields nothing")
            .hasSize(6));
  }

  /**
   * Pins the engine assumption the cases above rest on: YouTrackDB's SQL comparator ranks every
   * String above the Integer 27, so {@code name <= 27} selects nothing and an unguarded {@code
   * NOT(name > 27)} would select nothing too, against native's six. That gap is what the per-record
   * type guard exists to close. The String-comparand pair is the control: the same operator over a
   * comparand of the property's own type selects every vertex, so the empty result is the cross-type
   * ordering and not a broken clause.
   *
   * <p>The count assertions are an engine-assumption pin rather than a code pin: no change to
   * {@link NotStepRecogniser} can redden them. If one reddens, the SQL comparator's cross-type rule
   * has changed, and with it the reason a folded position and an unfolded one need different
   * translations — re-evaluate the guard's scoping rather than adjusting the expected counts here.
   *
   * <p>The boundary-step assertions are the code-side half, and they are load-bearing. The folded
   * native graph step answers both shapes identically, so a silent decline would leave the counts
   * green while they pinned the native comparator instead of the SQL one this test is named for.
   */
  @Test
  public void crossTypeRangeComparison_sqlRanksStringAboveInteger() {
    ModernGraphFixture.seed(graph, session);

    withTranslator(true, () -> {
      var integerComparand = graph.traversal().V().has("name", P.lte(27)).asAdmin();
      integerComparand.applyStrategies();
      assertThat(countBoundarySteps(integerComparand.getSteps()))
          .as("the Integer-comparand shape must translate, else the count below pins the native "
              + "comparator rather than the SQL one")
          .isEqualTo(1);
      assertThat(integerComparand.toList())
          .as("SQL ranks every String above the Integer 27, so the complement of the withdrawn NOT "
              + "clause is empty")
          .isEmpty();

      var stringComparand = graph.traversal().V().has("name", P.lte("z")).asAdmin();
      stringComparand.applyStrategies();
      assertThat(countBoundarySteps(stringComparand.getSteps()))
          .as("the String-comparand control must translate too, for the same reason")
          .isEqualTo(1);
      assertThat(stringComparand.toList())
          .as("the same operator over a String comparand selects every vertex")
          .hasSize(6);
    });
  }

  /**
   * The root-level {@code g.V().has("name", P.gt(27))} is the neighbour the guard must not disturb.
   * It keeps translating and keeps answering all six on both arms: the native arm folds it into the
   * graph step, whose comparator ranks the String above the Integer — the same answer SQL {@code
   * name > 27} gives. This is the half of the native engine the translator agrees with, and the
   * reason the guard is scoped to unfolded positions rather than applied everywhere.
   */
  @Test
  public void bareCrossTypeRangeComparison_keepsTranslating_andAnswersSixOnBothArms() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().has(name, gt(27))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", P.gt(27)));

    withTranslator(
        false,
        () -> assertThat(graph.traversal().V().has("name", P.gt(27)).toList())
            .as("the folded native has() ranks the String above the Integer and keeps all six")
            .hasSize(6));
  }

  /**
   * {@code not(has(name, eq("marko")))} keeps translating. Equality is well defined across runtime
   * types on both sides — a String is simply unequal to a comparand of another type — so the decline
   * is scoped to the four range comparisons and must not swallow the equality form, which is the
   * commonest predicate under {@code not(...)}.
   */
  @Test
  public void notWithEqualityPredicate_keepsTranslating() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().not(has(name, eq(marko)))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.has("name", P.eq("marko"))));
  }

  /**
   * A range comparison nested behind a hop inside the NOT: {@code not(out(knows).has(age, gt(30)))}
   * is an edge-bearing NOT whose child {@code has} sits on the hop target, where the anti-join
   * negates it exactly as the pure-filter form does. It translates now, and the discriminating
   * assertion is the boundary step — {@code age} is an Integer on every vertex that has it, so the
   * multiset equality would hold either way.
   */
  @Test
  public void notWithRangeComparisonBehindHop_translatesToTheSameRows() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().not(out(knows).has(age, gt(30)))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.out("knows").has("age", P.gt(30))));
  }

  /**
   * {@code between(lo, hi)} never arrives as a predicate of its own — TinkerPop decomposes it into
   * {@code AndP[gte lo, lt hi]} before the translator sees it — so the guard has to reach it through
   * the connective recursion, once per arm. Same for {@code inside} / {@code outside}, which
   * decompose the same way. This case pins that a negated {@code between} translates and returns
   * native's rows; the boundary-step assertion is the discriminating half.
   *
   * <p>It does <em>not</em> witness the recursion, and neither does any other case in this class.
   * {@code age} is an Integer on all four {@code Person} vertices and absent on both
   * {@code Software} vertices, so the type conjunct is true wherever the comparison can be true,
   * and dropping it moves no row. Measured, by rebuilding the adapter's connective recursion to
   * hand every connective child an off guard: all eighteen cases here stay green under that build,
   * while two cases in {@code RangeTypeGuardEquivalenceTest} redden.
   *
   * <p>Those two are the row-level witnesses — its {@code outside(…)} case for the {@code OrP}
   * half, on a String that falls inside an unbounded arm under SQL ordering, and its
   * {@code between} case for the {@code AndP} half, on a stored Date and Boolean that a numeric
   * bound converts into and so admits inside a bounded window. Both need stored types the modern
   * graph does not carry: a String and an Integer under one key for the first, a Date or a Boolean
   * for the second. {@code GremlinPredicateAdapterTest.guardedRange_reachesUnderTheConnectives}
   * pins the same recursion at render level.
   */
  @Test
  public void notWithBetweenPredicate_translatesToTheSameRows() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().not(has(age, between(28, 33)))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.has("age", P.between(28, 33))));
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

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

  /** Whether a shape is expected to translate or to fall back to the native pipeline. */
  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  /**
   * Runs the same traversal shape twice — translator on, then off — and asserts boundary-step
   * engagement matches {@code expected} and the two result multisets are equal. Multiset equality is
   * on sorted RID strings, so multiplicity is preserved.
   */
  private void assertEquivalent(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedIds(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var offIds = sortedIds(offAdmin.toList());

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        assertThat(onIds)
            .as(scenario + ": a translated shape must return a non-empty result, else the multiset "
                + "equality below is vacuous")
            .isNotEmpty();
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline to native — no boundary step")
            .isZero();
        assertThat(offIds)
            .as(scenario + ": a declined shape must still return a non-empty native result, else "
                + "the multiset equality below is vacuous")
            .isNotEmpty();
      }
      assertThat(countBoundarySteps(offAdmin.getSteps()))
          .as(scenario + " (translator off) must never engage a boundary step")
          .isZero();
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off result multisets must match")
          .isEqualTo(offIds);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Runs {@code body} with the translator forced on or off, restoring the previous setting. */
  private void withTranslator(boolean enabled, Runnable body) {
    var original = translatorEnabled();
    setTranslatorEnabled(enabled);
    try {
      body.run();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private boolean translatorEnabled() {
    return graphSession()
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
  }

  private void setTranslatorEnabled(boolean enabled) {
    graphSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  /** The session backing the graph traversals — its configuration carries the translator flag. */
  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  /**
   * Counts translated boundary steps of <em>any</em> kind. The supertype is deliberate: a shape that
   * splices a multi-plan step instead of a single-plan one is still a translation, and counting only
   * the single-plan subtype would let such a shape satisfy a decline expectation.
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
}
