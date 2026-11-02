package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CountFromClassStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.DistinctExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromClassExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromRidsStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FilterStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LimitExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SkipExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.StartKind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Direct physical evidence tests. Plans are never started and need no session or records. */
public class PlainPlanClassifierTest {

  private final BasicCommandContext ctx = new BasicCommandContext();

  @Test
  public void ridAndClassStartsExposeImmutableFactsWithoutExecuting() throws Exception {
    var f = fixture("{as:a}", "a");
    assertDecision(f, Reason.PLAIN);
    assertThat(decide(f).facts().startKind()).isEqualTo(StartKind.RID);
    classStart(f);
    assertDecision(f, Reason.PLAIN);
    assertThat(decide(f).facts().scannedClasses()).containsExactly("V");
    org.junit.Assert.assertThrows(UnsupportedOperationException.class,
        () -> decide(f).facts().scannedClasses().add("Other"));
  }

  @Test
  public void startAliasPrefetchAdmitsTheSameRidOrClassSourceButNotAnotherAlias() throws Exception {
    for (boolean scan : List.of(false, true)) {
      var f = fixture("{as:a}", "a");
      if (scan) {
        classStart(f);
      }
      var source = f.source;
      f.steps.set(0, new MatchFirstStep(ctx, f.inputs.pattern().get("a"), false));
      f.steps.addFirst(new MatchPrefetchStep(ctx, source, "a", false));
      assertDecision(f, Reason.PLAIN);
      assertThat(decide(f).facts().prefetchedStart()).isTrue();
      f.steps.set(0, new MatchPrefetchStep(ctx, source, "other", false));
      assertDecision(f, Reason.ROOT_CHANGED);
    }
  }

  @Test
  public void forwardChainsAndDirectCurrentRowReadsArePlainButSingleRidHopIsProtected()
      throws Exception {
    var f = fixture("{as:a}.out('E'){as:b}", "b.name");
    assertDecision(f, Reason.RID_SINGLE_HOP_PROTECTED);
    assertThat(decide(f).ridProtected()).isTrue();
    f = fixture("{as:a}.out('E'){as:b}.out('E'){as:c}", "c.@rid");
    assertDecision(f, Reason.PLAIN);
    assertThat(decide(f).facts().hops()).isEqualTo(2);
  }

  @Test
  public void localDisjunctionAndSlicesStayPlainUnlessTheyFilterStreamingInput() throws Exception {
    var f = fixture("{as:a}", "a LIMIT 2");
    assertDecision(f, Reason.PLAIN);
    f.source.chain(new FilterStep(where("name = 'a' OR name = 'b'"), ctx, 0, false));
    assertDecision(f, Reason.PLAIN);
    classStart(f);
    f.source.chain(new FilterStep(where("name = 'a' OR name = 'b'"), ctx, 0, false));
    assertDecision(f, Reason.L3);
    assertThat(decide(f).l3Applied()).isTrue();
    f = fixture("{as:a}.out('E'){as:b,where:(age > 1)}", "b LIMIT 2");
    assertDecision(f, Reason.L3);
    f = fixture("{as:a}", "a SKIP 1 LIMIT 2");
    assertDecision(f, Reason.PLAIN);
  }

  @Test
  public void materializedRootDoesNotTransferItsScanFilterToL3() throws Exception {
    var f = fixture("{as:a}", "a LIMIT 2");
    classStart(f);
    f.source.chain(new FilterStep(where("age > 1"), ctx, 0, false));
    f.steps.set(0, new MatchFirstStep(ctx, f.inputs.pattern().get("a"), false));
    f.steps.addFirst(new MatchPrefetchStep(ctx, f.source, "a", false));
    assertDecision(f, Reason.PLAIN);
  }

  @Test
  public void unknownStartsAndNestedContentFailIncludingIndexAndKnownEndpointAlternatives()
      throws Exception {
    for (var type : List.of(FetchFromIndexStep.class, KnownEndpointSourceStep.class,
        KnownEndpointExistsStep.class, OrderByStep.class)) {
      for (boolean prefetch : List.of(false, true)) {
        var f = fixture("{as:a}", "a");
        var unknown = mock(type);
        f.source.getSteps().clear();
        f.source.chain(unknown);
        if (prefetch) {
          f.steps.set(0, new MatchFirstStep(ctx, f.inputs.pattern().get("a"), false));
          f.steps.addFirst(new MatchPrefetchStep(ctx, f.source, "a", false));
        }
        assertDecision(f, Reason.UNKNOWN_STEP);
        assertThat(decide(f).offendingStepClass()).isEqualTo(unknown.getClass().getName());
      }
    }
  }

  @Test
  public void frozenNonPlainOperatorFamiliesKeepUnknownReasonWithoutOpeningTheirChildren()
      throws Exception {
    for (var type : List.of(BackRefHashJoinStep.class, CorrelatedOptionalHashJoinStep.class,
        InvertedWhileHashJoinStep.class, HashJoinMatchStep.class, KnownEndpointExistsStep.class,
        KnownEndpointSourceStep.class, FilterExistsMatchPatternStep.class,
        FilterNotMatchPatternStep.class, OptionalMatchStep.class, IndexOrderedEdgeStep.class,
        CartesianProductStep.class, CountFromClassStep.class, DistinctExecutionStep.class,
        OrderByStep.class)) {
      var f = fixture("{as:a}", "a");
      var unknown = mock(type);
      f.steps.add(1, unknown);
      assertDecision(f, Reason.UNKNOWN_STEP);
      assertThat(decide(f).offendingStepClass()).isEqualTo(unknown.getClass().getName());
    }
    for (var mode : JoinMode.values()) {
      var f = fixture("{as:a}", "a");
      f.steps.add(1, new HashJoinMatchStep(ctx, new SelectExecutionPlan(ctx), List.of("a"),
          mode, false));
      assertDecision(f, Reason.UNKNOWN_STEP);
    }
  }

  @Test
  public void reverseIntersectionAndSemiJoinHopsFailTheirAccessCondition() throws Exception {
    var f = fixture("{as:a}.out('E'){as:b}", "b");
    var hop = (MatchStep) f.steps.get(1);
    hop.edge.out = false;
    assertDecision(f, Reason.HOP_ACCESS);
    hop.edge.out = true;
    hop.edge.setIntersectionDescriptor(new RidFilterDescriptor.DirectRid(null));
    assertDecision(f, Reason.HOP_ACCESS);
    hop.edge.setIntersectionDescriptor(null);
    // Defensive malformed-step check. The planner uses BackRefHashJoinStep for semi-joins.
    hop.edge.setSemiJoinDescriptor(mock(SingleEdgeSemiJoin.class));
    assertDecision(f, Reason.HOP_ACCESS);
  }

  @Test
  public void unknownSubclassIsNeverInferredPlainFromItsParent() throws Exception {
    var f = fixture("{as:a}.out('E'){as:b}", "b");
    f.steps.set(1, new MatchStep(ctx, ((MatchStep) f.steps.get(1)).edge, false) {
    });
    assertDecision(f, Reason.UNKNOWN_STEP);
    var unknown = mock(AbstractExecutionStep.class);
    f.steps.addFirst(unknown);
    assertDecision(f, Reason.UNKNOWN_STEP);
  }

  @Test
  public void crossAliasAndParentPredicatesFailEvenAtRidStarts() throws Exception {
    for (String predicate : List.of("name = $matched.b.name", "name = $parent.name")) {
      var f = fixture("{as:a}", "a");
      f.source.chain(new FilterStep(where(predicate), ctx, 0, false));
      assertDecision(f, Reason.PREDICATE);
    }
  }

  @Test
  public void computedAndPriorAliasProjectionsAreNotDirectBoundaryReads() throws Exception {
    for (String expression : List.of("a.name.toLowerCase()", "a.age + 1", "count(*)", "b")) {
      assertDecision(fixture("{as:a}", expression), Reason.PROJECTION);
    }
  }

  @Test
  public void groupingOrderingDistinctAndDetachedInputsFailBeforePhysicalAnalysis()
      throws Exception {
    var f = fixture("{as:a}", "a");
    var original = f.inputs;
    var builders = List.of(original.toBuilder().groupBy(new SQLGroupBy(-1)),
        original.toBuilder().orderBy(new SQLOrderBy(-1)),
        original.toBuilder().unwind(new SQLUnwind(-1)), original.toBuilder().returnDistinct(true),
        original.toBuilder().returnElements(true), original.toBuilder().returnPaths(true),
        original.toBuilder().returnPatterns(true), original.toBuilder().returnPathElements(true));
    for (var builder : builders) {
      f.inputs = builder.build();
      assertDecision(f, Reason.INPUT_SHAPE);
    }
    assertDecision(fixture("{as:a}, {as:b}", "b"), Reason.INPUT_SHAPE);
    assertDecision(fixture("{as:a}.out(){as:b,optional:true}", "b"), Reason.INPUT_SHAPE);
  }

  @Test
  public void recursiveBooleanFilterTreesArePlainAndEveryProbeRoleIsNonPlain() throws Exception {
    var leaf = bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "a", where("age > 1"));
    var not = bool(FilterBooleanMatchStep.Kind.NOT, List.of(leaf), null, null);
    var or = bool(FilterBooleanMatchStep.Kind.OR, List.of(leaf, not), null, null);
    var and = bool(FilterBooleanMatchStep.Kind.AND, List.of(or, leaf), null, null);
    var f = fixture("{as:a}", "a");
    f.steps.add(1, and);
    assertDecision(f, Reason.PLAIN);
    for (var mode : List.of(JoinMode.SEMI_JOIN, JoinMode.ANTI_JOIN)) {
      var hash = new HashJoinMatchStep(ctx, new SelectExecutionPlan(ctx), List.of("a"), mode,
          List.of(), false);
      var probe = new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.PROBE,
          List.of(), null, null, List.of(), hash);
      f.steps.set(1, bool(FilterBooleanMatchStep.Kind.NOT, List.of(probe), null, null));
      assertDecision(f, Reason.BOOLEAN_PROBE);
    }
    f.steps.set(1, bool(FilterBooleanMatchStep.Kind.PROBE, List.of(), null, null));
    assertDecision(f, Reason.BOOLEAN_PROBE);
    f.steps.set(1, bool(FilterBooleanMatchStep.Kind.NOT, List.of(), null, null));
    assertDecision(f, Reason.BOOLEAN_SHAPE);
    f.steps.set(1, bool(FilterBooleanMatchStep.Kind.AND, List.of(), null, null));
    assertDecision(f, Reason.BOOLEAN_SHAPE);
    f.steps.set(1, bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "other", where("age > 1")));
    assertDecision(f, Reason.PREDICATE);
  }

  @Test
  public void changedRootAndReorderedHopsKeepMatch() throws Exception {
    var f = fixture("{as:a}.out(){as:b}.out(){as:c}", "c");
    f.steps.set(0, new MatchFirstStep(ctx, f.inputs.pattern().get("b"), f.source, false));
    assertDecision(f, Reason.ROOT_CHANGED);
    f = fixture("{as:a}.out(){as:b}.out(){as:c}", "c");
    java.util.Collections.swap(f.steps, 1, 2);
    assertDecision(f, Reason.HOP_ACCESS);
  }

  @Test
  public void recursiveWhileDepthAndCrossAliasHopFiltersAreNotOrdinaryHops() throws Exception {
    for (String filter : List.of("while:($depth < 2)", "maxDepth:2", "depthAlias:d",
        "pathAlias:p")) {
      assertDecision(fixture("{as:a}.out(){as:b," + filter + "}", "b"), Reason.HOP_ACCESS);
    }
    assertDecision(fixture("{as:a}.out(){as:b,where:(name = $matched.a.name)}", "b"),
        Reason.PREDICATE);
    assertDecision(fixture("{as:a}.out(){as:b,class:V}", "b LIMIT 1"),
        Reason.RID_SINGLE_HOP_PROTECTED);
    assertDecision(fixture("{as:a}.custom(){as:b}", "b"), Reason.HOP_ACCESS);
  }

  @Test
  public void subqueryPredicatesFailWithoutMutatingTheInputAst() throws Exception {
    var f = fixture("{as:a}", "a");
    var condition = where("name IN (SELECT name FROM V)");
    var before = condition.toString();
    f.source.chain(new FilterStep(condition, ctx, 0, false));
    assertDecision(f, Reason.PREDICATE);
    assertThat(condition.toString()).isEqualTo(before);
  }

  @Test
  public void unknownTrailingNestedWorkAndMissingOrConflictingSourcesFail() throws Exception {
    var f = fixture("{as:a}", "a");
    f.source.chain(new FilterStep(where("age > 1"), ctx, 0, false));
    f.source.chain(mock(OrderByStep.class));
    assertDecision(f, Reason.UNKNOWN_STEP);
    f = fixture("{as:a}", "a");
    f.source.getSteps().clear();
    assertDecision(f, Reason.START_ACCESS);
    f = fixture("{as:a}", "a");
    f.steps.set(0, new MatchFirstStep(ctx, f.inputs.pattern().get("a"), false));
    assertDecision(f, Reason.START_ACCESS);
    f = fixture("{as:a}", "a");
    f.steps.addFirst(new MatchPrefetchStep(ctx, f.source, "a", false));
    assertDecision(f, Reason.START_ACCESS);
    f = fixture("{as:a}", "a");
    f.steps.removeLast();
    assertDecision(f, Reason.STEP_ORDER);
    f = fixture("{as:a}", "a");
    f.steps.add(mock(OrderByStep.class));
    assertDecision(f, Reason.UNKNOWN_STEP);
  }

  @Test
  public void hopArgumentsRequireLiteralLabelsWithoutHiddenQueriesOrAliasReads() throws Exception {
    for (String argument : List.of("(SELECT name FROM V)", "$matched.a.name", "1")) {
      assertDecision(fixture("{as:a}.out(" + argument + "){as:b}.out(){as:c}", "c"),
          Reason.HOP_ACCESS);
    }
  }

  @Test
  public void hopTargetRidConstraintsFailClosedIncludingLiteralRidWithLimit() throws Exception {
    // RID expressions can execute nested work per candidate. No target RID form is audited.
    for (String rid : List.of("{\"@rid\": (SELECT FROM V LIMIT 1)}",
        "{\"@rid\": $matched.a.@rid}", "#1:1")) {
      assertDecision(fixture("{as:a}.out(){as:b,rid:" + rid + "}.out(){as:c}", "c LIMIT 1"),
          Reason.HOP_ACCESS);
    }
  }

  @Test
  public void unauditedPredicateFormsFailAtEveryPredicateOwner() throws Exception {
    for (String predicate : List.of("name = ifnull($matched.a.name,'x')",
        "name = $matched['a'].name", "name IN [$matched.a.name]", "out('E').size() > 0",
        "eval('1') = 1", "name = ifnull($parent.name,'x')", "unknown(name) = 1",
        "friend.name = 'x'", "name.toLowerCase() = 'x'", "name[0] = 'x'",
        "name = $parent", "name = $current", "name = $depth", "name = $matched")) {
      var f = fixture("{as:a}", "a");
      f.source.chain(new FilterStep(where(predicate), ctx, 0, false));
      assertDecision(f, Reason.PREDICATE);
      f = fixture("{as:a}.out(){as:b,where:(" + predicate + ")}.out(){as:c}", "c");
      assertDecision(f, Reason.PREDICATE);
      f = fixture("{as:a}", "a");
      f.inputs = f.inputs.toBuilder().aliasFilters(Map.of("a", where(predicate))).build();
      assertDecision(f, Reason.PREDICATE);
      f = fixture("{as:a}", "a");
      f.steps.add(1, bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "a", where(predicate)));
      assertDecision(f, Reason.PREDICATE);
    }
  }

  @Test
  public void l3RequiresANarrowingFilterAndReportsRidProtectionIndependently() throws Exception {
    for (String slice : List.of("LIMIT 1", "SKIP 1", "SKIP 1 LIMIT 2")) {
      var f = fixture("{as:a}.out(){as:b}.out(){as:c}", "c " + slice);
      f.inputs = f.inputs.toBuilder().aliasClasses(Map.of("b", "V", "c", "V")).build();
      assertDecision(f, Reason.PLAIN);
      assertThat(decide(f).l3Applied()).isFalse();
      assertDecision(fixture("{as:a}.out(){as:b,where:(age > 1)}", "b " + slice), Reason.L3);
      assertDecision(fixture("{as:a}.out(){as:b,class:Person}", "b " + slice), Reason.L3);
    }
    var f = fixture("{as:a}.out(){as:b,where:(age > 1)}", "b LIMIT 1");
    assertThat(decide(f).l3Applied()).isTrue();
    assertThat(decide(f).ridProtected()).isTrue();
    f = fixture("{as:a}.outE('Person'){as:e,class:Person}.inV(){as:b}", "b LIMIT 1");
    assertDecision(f, Reason.RID_SINGLE_HOP_PROTECTED);
    assertThat(decide(f).facts().hops()).isEqualTo(1);
    f = fixture("{as:a}.outE('E'){as:e,where:(age > 1)}.inV(){as:b}", "b");
    assertDecision(f, Reason.RID_SINGLE_HOP_PROTECTED);
    assertThat(decide(f).ridProtected()).isTrue();
  }

  @Test
  public void everyAuditedHopMethodAcceptsLiteralLabelsAndNoArguments() throws Exception {
    for (String method : List.of("out", "in", "both", "outE", "inE", "bothE", "outV",
        "inV", "bothV")) {
      for (String args : List.of("", "'E'", "'E','Other'")) {
        var f = fixture("{as:a}." + method + "(" + args + "){as:b}", "b");
        classStart(f);
        assertDecision(f, Reason.PLAIN);
      }
    }
  }

  @Test
  public void physicalProjectionRejectsAllExcludeAndNonBoundaryReadsIndependently()
      throws Exception {
    for (String expression : List.of("all", "exclude", "a.age + 1", "b")) {
      var f = fixture("{as:a}", "a");
      var parsed = parse("MATCH {as:a} RETURN "
          + (List.of("all", "exclude").contains(expression) ? "a" : expression));
      var item = new SQLProjectionItem(-1);
      item.setExpression(parsed.getReturnItems().getFirst());
      if (expression.equals("all")) {
        item.setAll(true);
      }
      if (expression.equals("exclude")) {
        var serialized =
            new com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal(null);
        serialized.setProperty("exclude", true);
        serialized.setProperty("all", false);
        item.deserialize(serialized);
      }
      var projection = new SQLProjection(-1);
      projection.setItems(List.of(item));
      f.steps.set(1, new ProjectionCalculationStep(projection, ctx, false));
      assertDecision(f, Reason.PROJECTION);
    }
  }

  @Test
  public void physicalStartClassMustMatchTheInputClass() throws Exception {
    var f = fixture("{as:a}", "a");
    classStart(f);
    f.inputs = f.inputs.toBuilder().aliasClasses(Map.of("a", "Other")).build();
    assertDecision(f, Reason.START_ACCESS);
    assertThat(decide(f).offendingStepClass())
        .isEqualTo(FetchFromClassExecutionStep.class.getName());
  }

  @Test
  public void detachedInputListsAndMalformedBooleanNodesFailTheirOwnConditions() throws Exception {
    var f = fixture("{as:a}", "a");
    var expression = parse("MATCH {as:a} RETURN a").getMatchExpressions().getFirst();
    f.inputs = f.inputs.toBuilder().existsMatchExpressions(List.of(expression)).build();
    assertDecision(f, Reason.INPUT_SHAPE);
    f = fixture("{as:a}", "a");
    f.inputs = f.inputs.toBuilder().notMatchExpressions(List.of(expression)).build();
    assertDecision(f, Reason.INPUT_SHAPE);
    var leaf = bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "a", where("age > 1"));
    for (var invalid : List.of(
        bool(FilterBooleanMatchStep.Kind.FILTER, List.of(leaf), "a", where("age > 1")),
        bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "a", null),
        bool(FilterBooleanMatchStep.Kind.FILTER, List.of(), "a", where("name = $parent.name")),
        bool(FilterBooleanMatchStep.Kind.NOT, List.of(leaf, leaf), null, null),
        bool(FilterBooleanMatchStep.Kind.OR, List.of(), null, null))) {
      f = fixture("{as:a}", "a");
      f.steps.add(1, invalid);
      assertDecision(f, invalid.getKind() == FilterBooleanMatchStep.Kind.FILTER
          ? Reason.PREDICATE : Reason.BOOLEAN_SHAPE);
    }
    f = fixture("{as:a}", "a");
    var hash = new HashJoinMatchStep(ctx, new SelectExecutionPlan(ctx), List.of("a"),
        JoinMode.SEMI_JOIN, List.of(), false);
    f.steps.add(1, new FilterBooleanMatchStep(ctx, false, FilterBooleanMatchStep.Kind.AND,
        List.of(leaf), null, null, List.of(), hash));
    assertDecision(f, Reason.BOOLEAN_PROBE);
    f = fixture("{as:a}", "a LIMIT 1");
    classStart(f);
    f.steps.add(1, leaf);
    assertDecision(f, Reason.L3);
  }

  private FilterBooleanMatchStep bool(FilterBooleanMatchStep.Kind kind,
      List<FilterBooleanMatchStep> children, String alias, SQLWhereClause where) {
    return new FilterBooleanMatchStep(ctx, false, kind, children, alias, where, List.of(), null);
  }

  private void classStart(Fixture f) {
    var fetch = mock(FetchFromClassExecutionStep.class);
    when(fetch.getClassName()).thenReturn("V");
    f.source.getSteps().clear();
    f.source.chain(fetch);
    f.inputs = f.inputs.toBuilder().aliasClasses(Map.of("a", "V")).build();
  }

  private PlainPlanDecision decide(Fixture f) {
    var plan = new SelectExecutionPlan(ctx);
    f.steps.forEach(plan::chain);
    return PlainPlanClassifier.classify(plan, f.inputs);
  }

  private void assertDecision(Fixture f, Reason reason) {
    var decision = decide(f);
    assertThat(decision.reason()).isEqualTo(reason);
    assertThat(decision.plain()).isEqualTo(reason == Reason.PLAIN);
  }

  private SQLWhereClause where(String predicate) throws Exception {
    return parse("MATCH {as:a,where:(" + predicate + ")} RETURN a")
        .getMatchExpressions().getFirst().getOrigin().getFilter();
  }

  private SQLMatchStatement parse(String sql) throws Exception {
    return (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
        sql.getBytes(StandardCharsets.UTF_8))).parse();
  }

  private Fixture fixture(String patternText, String returns) throws Exception {
    var statement = parse("MATCH " + patternText + " RETURN " + returns);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections()).build();
    var source = new SelectExecutionPlan(ctx);
    source.chain(new FetchFromRidsStep(List.of(), ctx, false));
    var steps = new ArrayList<ExecutionStepInternal>();
    var node = pattern.aliasToNode.values().iterator().next();
    steps.add(new MatchFirstStep(ctx, node, source, false));
    var visited = new java.util.HashSet<String>();
    while (visited.add(node.alias) && !node.out.isEmpty()) {
      var edge = node.out.iterator().next();
      steps.add(new MatchStep(ctx, new EdgeTraversal(edge, true), false));
      node = edge.in;
    }
    var projection = new SQLProjection(-1);
    var items = new ArrayList<SQLProjectionItem>();
    for (var expression : statement.getReturnItems()) {
      var item = new SQLProjectionItem(-1);
      item.setExpression(expression);
      items.add(item);
    }
    projection.setItems(items);
    steps.add(new ProjectionCalculationStep(projection, ctx, false));
    if (statement.getSkip() != null) {
      steps.add(new SkipExecutionStep(statement.getSkip(), ctx, false));
    }
    if (statement.getLimit() != null) {
      steps.add(new LimitExecutionStep(statement.getLimit(), ctx, false));
    }
    return new Fixture(inputs, source, steps);
  }

  private static final class Fixture {
    MatchPlanInputs inputs;
    final SelectExecutionPlan source;
    final List<ExecutionStepInternal> steps;

    Fixture(MatchPlanInputs inputs, SelectExecutionPlan source, List<ExecutionStepInternal> steps) {
      this.inputs = inputs;
      this.source = source;
      this.steps = steps;
    }
  }
}
