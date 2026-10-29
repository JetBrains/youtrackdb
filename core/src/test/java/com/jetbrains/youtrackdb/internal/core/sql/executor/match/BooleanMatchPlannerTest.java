package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Tests construct the post-pattern tree directly, independently of Gremlin recognition. */
@Category(SequentialTest.class)
public class BooleanMatchPlannerTest extends DbTestBase {

  private Object threshold;
  private Object minimum;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    threshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    minimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10000L);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    session.execute("CREATE CLASS BoolP EXTENDS V").close();
    session.execute("CREATE CLASS BoolE EXTENDS E").close();
    session.begin();
    for (var name : List.of("filter", "hop", "both", "neither")) {
      session.execute("CREATE VERTEX BoolP SET name = ?, slug = ?", name,
          name.equals("filter") || name.equals("both") ? "yes" : "no").close();
    }
    for (var name : List.of("hop", "hop", "both")) {
      session.execute("CREATE EDGE BoolE FROM (SELECT FROM BoolP WHERE name = ?)"
          + " TO (SELECT FROM BoolP WHERE name = 'neither')", name).close();
    }
    session.commit();
    session.begin();
  }

  @After
  public void restoreSettings() {
    if (session.isTxActive()) {
      session.rollback();
    }
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(threshold);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(minimum);
  }

  /** Every detached kind agrees across hash, plain nested and remembered nested execution. */
  @Test
  public void detachedKindsRememberTrueFalseAndRestartWithFreshParametersAndData()
      throws Exception {
    session.rollback();
    session.createEdgeClass("BoolFan");
    session.begin();
    for (int i = 0; i < 16; i++) {
      session.execute("CREATE EDGE BoolFan FROM (SELECT FROM BoolP)"
          + " TO (SELECT FROM BoolP WHERE name='neither')").close();
    }
    session.commit();
    session.begin();
    for (int kind = 0; kind < 4; kind++) {
      boolean negative = kind == 1 || kind == 3;
      for (int mode = 0; mode < 3; mode++) {
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(mode == 0 ? 0L : 256L);
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(
            mode == 1 ? Long.MAX_VALUE : 0L);
        var statement = parse("MATCH {class:BoolP, as:p}.out('BoolFan'){as:q}"
            + " RETURN p.name as name");
        var pattern = new Pattern();
        statement.getMatchExpressions().forEach(pattern::addExpression);
        var check = parse("MATCH {as:p}.out('BoolE'){as:t, where:(name=:wanted)} RETURN p")
            .getMatchExpressions().getFirst();
        var builder = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP"))
            .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
            .returnNestedProjections(statement.getReturnNestedProjections());
        if (kind == 0) {
          builder.existsMatchExpressions(List.of(check));
        } else if (kind == 1) {
          builder.notMatchExpressions(List.of(check));
        } else {
          builder
              .booleanCheck(new MatchBooleanCheck.Or(List.of(new MatchBooleanCheck.Probe(check))));
        }
        var ctx = context();
        ctx.setInputParameters(Map.of("wanted", "neither"));
        var planner = kind == 3 ? new MatchExecutionPlanner(parse(
            "MATCH {class:BoolP, as:p}.out('BoolFan'){as:q},"
                + " NOT {as:p}.out('BoolE'){as:t, where:(name=:wanted)} RETURN p.name as name"))
            : new MatchExecutionPlanner(builder.build());
        var plan = (SelectExecutionPlan) planner.createExecutionPlan(ctx, false, false);
        assertThat(plan.prettyPrint(0, 2).contains("HASH")).isEqualTo(mode == 2);
        for (int start = 0; start < 3; start++) {
          if (start == 2) {
            session.execute("CREATE EDGE BoolE FROM (SELECT FROM BoolP WHERE name='filter')"
                + " TO (SELECT FROM BoolP WHERE name='neither')").close();
          }
          ctx.setInputParameters(Map.of("wanted", start == 1 ? "absent" : "neither"));
          var stream = plan.start();
          var rows = stream.stream(ctx).map(row -> row.<String>getProperty("name"))
              .sorted().toList();
          var passing = start == 1 ? List.<String>of()
              : start == 2 ? List.of("both", "filter", "hop") : List.of("both", "hop");
          var expected = List.of("both", "filter", "hop", "neither").stream()
              .filter(name -> negative != passing.contains(name))
              .flatMap(name -> java.util.stream.IntStream.range(0, 16).mapToObj(i -> name))
              .toList();
          assertThat(rows).isEqualTo(expected);
          if (mode != 2) {
            var memories = memories(plan.getSteps());
            assertThat(memories).hasSize(1);
            assertThat(memories.getFirst().hits()).isEqualTo(mode == 1 ? 60 : 0);
            assertThat(memories.getFirst().size()).isEqualTo(mode == 1 ? 4 : 0);
          }
          stream.close(ctx);
          if (start == 1) {
            plan.close();
            for (var memory : memories(plan.getSteps())) {
              assertThat(memory.size()).isZero();
            }
          } else {
            plan.reset(ctx);
          }
        }
        plan.close();
        session.execute("DELETE EDGE BoolE FROM (SELECT FROM BoolP WHERE name='filter')").close();
      }
    }
  }

  private List<DetachedCheckMemory> memories(
      List<? extends com.jetbrains.youtrackdb.internal.core.query.ExecutionStep> steps)
      throws Exception {
    var result = new java.util.ArrayList<DetachedCheckMemory>();
    for (var step : steps) {
      if (step instanceof FilterBooleanMatchStep tree) {
        var field = FilterBooleanMatchStep.class.getDeclaredField("kind");
        field.setAccessible(true);
        if (field.get(tree) == FilterBooleanMatchStep.Kind.PROBE) {
          var memory = FilterBooleanMatchStep.class.getDeclaredField("memory");
          memory.setAccessible(true);
          result.add((DetachedCheckMemory) memory.get(step));
        }
      } else if (step instanceof FilterExistsMatchPatternStep
          || step instanceof FilterNotMatchPatternStep) {
        var field = step.getClass().getDeclaredField("memory");
        field.setAccessible(true);
        result.add((DetachedCheckMemory) field.get(step));
      }
      result.addAll(memories(step.getSubSteps()));
    }
    return result;
  }

  /** OR retains filter-only, hop-only and both once. Hash and nested give identical multisets. */
  @Test
  public void orHashAndNestedKeepExactlyThePassingRows() {
    for (var hash : List.of(true, false)) {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(hash ? 10000L : 0L);
      var plan = plan("RETURN p.name as name", or());
      assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR", "BOOLEAN PROBE");
      if (hash) {
        assertThat(plan.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
      } else {
        assertThat(plan.prettyPrint(0, 2)).doesNotContain("HASH SEMI_JOIN");
      }
      assertThat(names(plan)).containsExactly("both", "filter", "hop");
    }
  }

  /** Overflow discards the build once, uses nested probes for all rows, and clears on reset. */
  @Test
  public void overflowFallsBackForTheWholeStart() {
    var plan = plan("RETURN p.name as name", or());
    assertThat(plan.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
    var stream = plan.start();
    try {
      assertThat(stream.stream(plan.getContext()).map(row -> row.<String>getProperty("name"))
          .sorted().toList()).containsExactly("both", "filter", "hop");
    } finally {
      stream.close(plan.getContext());
    }
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10000L);
    plan.reset(plan.getContext());
    assertThat(names(plan)).containsExactly("both", "filter", "hop");
  }

  /** A plan selected before zero still builds its hash set at runtime without a positive cap. */
  @Test
  public void zeroRuntimeThresholdKeepsAlreadySelectedHashBuild() throws Exception {
    var plan = plan("RETURN p.name as name", or());
    assertThat(plan.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
    var hash = findHashStep(plan.getSteps());
    assertThat(hash).isNotNull();
    assertThat(hash.buildHashSet(plan.getContext())).hasSize(2);
    assertThat(names(plan)).containsExactly("both", "filter", "hop");
  }

  private HashJoinMatchStep findHashStep(
      List<? extends com.jetbrains.youtrackdb.internal.core.query.ExecutionStep> steps) {
    for (var step : steps) {
      if (step instanceof HashJoinMatchStep hash) {
        return hash;
      }
      var nested = findHashStep(step.getSubSteps());
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  /** A positive hop repeats the origin row. Tree membership must not multiply or deduplicate it. */
  @Test
  public void repeatedPositiveRowsPreserveTheirMultiplicity() {
    var statement = parse("MATCH {class:BoolP, as:p}.out('BoolE'){as:q}"
        + " RETURN p.name as name");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP"))
        .booleanCheck(or()).returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections()).build();
    var plan = (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR");
    assertThat(names(plan)).containsExactly("both", "hop", "hop");
  }

  /** A missing outer estimate cannot force a hash leaf even with the cost guards off. */
  @Test
  public void unknownEstimatesChooseNested() {
    var statement = parse("MATCH {class:BoolP, as:p} RETURN p.name as name");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP")).build();
    var ctx = context();
    var tree = new MatchExecutionPlanner(inputs).buildBooleanCheck(or(), ctx,
        java.util.OptionalLong.empty(), false);
    var rows = session.query("SELECT @rid as p, name FROM BoolP").toList();
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep(
        ctx, false) {
      @Override
      public com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream
          internalStart(CommandContext context) {
        return com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream
            .resultIterator(rows.iterator());
      }

      @Override
      public com.jetbrains.youtrackdb.internal.core.query.ExecutionStep
          copy(CommandContext context) {
        throw new UnsupportedOperationException("source fixture is execution local");
      }
    });
    plan.chain(tree);
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR").doesNotContain("HASH SEMI_JOIN");
    assertThat(names(plan)).containsExactly("both", "filter", "hop");
  }

  /** A large minimum chooses nested probing despite a complete tree-only outer estimate. */
  @Test
  public void fewOuterRowsChooseNested() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(10000L);
    var plan = plan("RETURN p.name as name", or());
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR").doesNotContain("HASH SEMI_JOIN");
    assertThat(names(plan)).containsExactly("both", "filter", "hop");
  }

  /** Memory makes repeated origins cheaper than an eager build, even for forty outer rows. */
  @Test
  public void defaultCostGuardsDistinguishFewAndManyOuterRows() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getDefValue());
    var few = plan("RETURN p.name as name", probe());
    assertThat(few.prettyPrint(0, 2)).doesNotContain("HASH SEMI_JOIN");
    assertThat(names(few)).containsExactly("both", "hop");
    session.rollback();
    session.createEdgeClass("BoolFan");
    session.begin();
    for (int i = 0; i < 40; i++) {
      session.execute("CREATE EDGE BoolFan FROM (SELECT FROM BoolP WHERE name='hop')"
          + " TO (SELECT FROM BoolP WHERE name='neither')").close();
      session.execute("CREATE EDGE BoolE FROM (SELECT FROM BoolP WHERE name='hop')"
          + " TO (SELECT FROM BoolP WHERE name='neither')").close();
    }
    session.commit();
    session.begin();
    var statement = parse("MATCH {class:BoolP, as:p}.out('BoolFan'){as:q}"
        + " RETURN p.name as name");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP"))
        .booleanCheck(probe()).returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections()).build();
    var many = (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
    assertThat(many.prettyPrint(0, 2)).contains("BOOLEAN PROBE").doesNotContain("HASH SEMI_JOIN");
    assertThat(names(many)).hasSize(40).containsOnly("hop");
  }

  /** AND and complete-child NOT exercise every truth-table combination. */
  @Test
  public void conjunctionAndNegationPreserveBooleanScope() {
    var and = new MatchBooleanCheck.And(List.of(filter(), probe()));
    var plan = plan("RETURN p.name as name", and);
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN AND");
    assertThat(names(plan)).containsExactly("both");
    plan = plan("RETURN p.name as name", new MatchBooleanCheck.Not(or()));
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN NOT", "BOOLEAN OR");
    assertThat(names(plan)).containsExactly("neither");
  }

  /** Tree filtering runs before count and blocks the whole-class count shortcut. */
  @Test
  public void countObservesTheWholeTree() {
    var plan = plan("RETURN count(*) as total", or());
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR").doesNotContain("COUNT FROM CLASS");
    var stream = plan.start();
    try {
      assertThat(((Number) stream.next(plan.getContext()).getProperty("total")).longValue())
          .isEqualTo(3L);
      assertThat(stream.hasNext(plan.getContext())).isFalse();
    } finally {
      stream.close(plan.getContext());
      plan.close();
    }
  }

  /** Both LIMIT and SKIP force nested leaves and retain downstream slice ordering. */
  @Test
  public void slicesUseNestedLeaves() {
    for (var slice : List.of(" LIMIT 1", " SKIP 1")) {
      var plan = plan("RETURN p.name as name ORDER BY name" + slice, or());
      assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN OR").doesNotContain("HASH SEMI_JOIN");
      assertThat(names(plan)).containsExactlyElementsOf(slice.contains("LIMIT")
          ? List.of("both") : List.of("filter", "hop"));
    }
  }

  /** A copied tree owns fresh executable descendants and leaves the template unused. */
  @Test
  public void copiedTreeHasIndependentDescendants() {
    var template = plan("RETURN p.name as name", new MatchBooleanCheck.Not(
        new MatchBooleanCheck.And(List.of(new MatchBooleanCheck.Or(List.of(probe())), filter()))));
    template.close();
    var copy = (SelectExecutionPlan) template.copy(context());
    var original = template.getSteps().stream().filter(FilterBooleanMatchStep.class::isInstance)
        .map(FilterBooleanMatchStep.class::cast).findFirst().orElseThrow();
    var copied = copy.getSteps().stream().filter(FilterBooleanMatchStep.class::isInstance)
        .map(FilterBooleanMatchStep.class::cast).findFirst().orElseThrow();
    assertSeparate(original, copied);
    var templateState = mutableState(template);
    var copyState = mutableState(copy);
    for (var value : copyState.keySet()) {
      assertThat(templateState.containsKey(value)).as(value.getClass().getName()).isFalse();
    }
    var stream = copy.start();
    try {
      assertThat(stream.stream(copy.getContext()).map(row -> row.<String>getProperty("name"))
          .sorted().toList()).containsExactly("filter", "hop", "neither");
      // The copy now owns a populated set. The cached template must still own no set.
      assertThat(mutableState(copy).keySet().stream().filter(java.util.Set.class::isInstance))
          .isNotEmpty();
      assertThat(mutableState(template)).containsExactlyInAnyOrderEntriesOf(templateState);
      assertSeparate(original, copied);
    } finally {
      stream.close(copy.getContext());
      copy.close();
      template.close();
    }
  }

  /** Eager hash building cannot replace an already-published outer matched binding. */
  @Test
  public void eagerBuildRestoresOuterMatchedBinding() {
    var plan = plan("RETURN p.name as name", or());
    assertThat(plan.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
    var sentinel = new com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal(session);
    sentinel.setProperty("sentinel", true);
    plan.getContext().setSystemVariable(CommandContext.VAR_MATCHED, sentinel);
    var stream = plan.start();
    try {
      assertThat(plan.getContext().<Object>getSystemVariable(CommandContext.VAR_MATCHED))
          .isSameAs(sentinel);
      assertThat(stream.stream(plan.getContext()).toList()).hasSize(3);
    } finally {
      stream.close(plan.getContext());
      plan.close();
    }
  }

  /** Concurrent copies evaluate different parameter bindings in independent database sessions. */
  @Test
  public void concurrentCopiesHaveIndependentBindings() throws Exception {
    var condition = parse("MATCH {as:p, where:(slug = :wanted)} RETURN p")
        .getMatchExpressions().getFirst().getOrigin().getFilter();
    var template = plan("RETURN p.name as name", new MatchBooleanCheck.Or(List.of(probe(),
        new MatchBooleanCheck.Filter("p", condition))));
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try {
      var tasks = List.of("yes", "no").stream().map(value -> executor.submit(() -> {
        try (var independent = openDatabase()) {
          independent.begin();
          var context = new BasicCommandContext(independent);
          context.setInputParameters(Map.of("wanted", value));
          var copy = (SelectExecutionPlan) template.copy(context);
          barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
          var rows = names(copy);
          independent.rollback();
          return rows;
        }
      })).toList();
      assertThat(tasks.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS))
          .containsExactly("both", "filter", "hop");
      assertThat(tasks.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS))
          .containsExactly("both", "hop", "neither");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      session.activateOnCurrentThread();
      template.close();
    }
  }

  /** Input copying keeps the exact scoped tree when only the return projection changes. */
  @Test
  public void inputRebuildKeepsTree() {
    var statement = parse("MATCH {class:BoolP, as:p} RETURN p");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).booleanCheck(or()).build();
    assertThat(inputs.hasChecks()).isTrue();
    assertThat(inputs.toBuilder().returnPaths(true).build().booleanCheck())
        .isSameAs(inputs.booleanCheck());
  }

  /** Unrelated variables, functions, arithmetic and unknown predicates cannot enter a hash build. */
  @Test
  public void readSetRejectsEveryUnrecognisedCondition() {
    var positive = new Pattern();
    parse("MATCH {class:BoolP, as:p} RETURN p").getMatchExpressions()
        .forEach(positive::addExpression);
    for (var predicate : List.of("slug = :wanted", "slug = 'yes'", "name = slug", "slug = true",
        "true",
        "slug = 'yes' OR name = 'hop'")) {
      var expression = parse("MATCH {as:p}.out('BoolE'){as:c, where:(" + predicate
          + ")} RETURN p").getMatchExpressions().getFirst();
      assertThat(DetachedCheckReadSet.describe(expression, positive).independent())
          .as(predicate).isTrue();
    }
    for (var predicate : List.of("slug = $unrelated", "slug = $parent.value",
        "slug = $matched.p.slug", "random() > 0", "name.toLowerCase() = 'hop'",
        "1 + 1 = 2", "slug IS NULL", "slug = $current.slug", "slug = [random()] || [1]",
        "(slug = 'yes' OR name = 'hop') AND NOT (slug = 'no')")) {
      var expression = parse("MATCH {as:p}.out('BoolE'){as:c, where:(" + predicate
          + ")} RETURN p").getMatchExpressions().getFirst();
      assertThat(DetachedCheckReadSet.describe(expression, positive).independent())
          .as(predicate).isFalse();
    }
    var unknown = parse("MATCH {as:p}.out('BoolE'){as:c, where:(@rid = $matched.unknown)} RETURN p")
        .getMatchExpressions().getFirst();
    assertThat(DetachedCheckReadSet.describe(unknown, positive).independent()).isFalse();
  }

  /** A correlated positive origin filter keeps the tree nested and retains the start binding. */
  @Test
  public void downstreamInequalityKeepsTheOuterStartAfterHashBuild() {
    session.rollback();
    session.execute("CREATE CLASS k EXTENDS E").close();
    session.execute("CREATE CLASS t EXTENDS E").close();
    session.begin();
    for (var edge : List.of("filter,hop", "hop,both", "both,filter", "both,neither")) {
      var pair = edge.split(",");
      session.execute("CREATE EDGE k FROM (SELECT FROM BoolP WHERE name = ?)"
          + " TO (SELECT FROM BoolP WHERE name = ?)", pair[0], pair[1]).close();
    }
    session.execute("CREATE EDGE t FROM (SELECT FROM BoolP WHERE name = 'both')"
        + " TO (SELECT FROM BoolP WHERE name = 'neither')").close();
    var statement = parse("MATCH {class:BoolP, as:start}.out('k'){as:middle}"
        + ".out('k'){class:BoolP, as:p, where:(@rid <> $matched.start.@rid)}"
        + " RETURN start.name as origin, p.name as name");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var hop = new MatchBooleanCheck.Probe(parse("MATCH {as:p}.out('t'){as:target} RETURN p")
        .getMatchExpressions().getFirst());
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("start", "BoolP", "p", "BoolP"))
        .aliasFilters(Map.of("p", statement.getMatchExpressions().getFirst().getItems()
            .getLast().getFilter().getFilter()))
        .booleanCheck(new MatchBooleanCheck.Or(List.of(hop, filter())))
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections()).build();
    var plan = (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
    assertThat(plan.prettyPrint(0, 2)).contains("BOOLEAN PROBE")
        .doesNotContain("HASH SEMI_JOIN");
    var stream = plan.start();
    try {
      assertThat(stream.stream(plan.getContext()).map(row -> row.getProperty("origin") + ":"
          + row.getProperty("name")).sorted().toList())
          .containsExactly("filter:both", "hop:filter");
    } finally {
      stream.close(plan.getContext());
      plan.close();
    }
  }

  private void assertSeparate(com.jetbrains.youtrackdb.internal.core.query.ExecutionStep first,
      com.jetbrains.youtrackdb.internal.core.query.ExecutionStep second) {
    assertThat(second).isNotSameAs(first);
    assertThat(second.getSubSteps()).hasSameSizeAs(first.getSubSteps());
    for (int i = 0; i < first.getSubSteps().size(); i++) {
      assertSeparate(first.getSubSteps().get(i), second.getSubSteps().get(i));
    }
  }

  /** Walks executable fields, hidden build plans, fallback lists and mutable FILTER syntax. */
  private java.util.IdentityHashMap<Object, String> mutableState(Object root) {
    var state = new java.util.IdentityHashMap<Object, String>();
    collectMutable(root, state);
    return state;
  }

  private void collectMutable(Object value, java.util.IdentityHashMap<Object, String> state) {
    // Operators and identifiers are immutable syntax values whose copy() returns this.
    if (value == null || state.containsKey(value)
        || value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator
        || value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier) {
      return;
    }
    if (value instanceof List<?> list) {
      if (!list.isEmpty()
          && list
              .getFirst() instanceof com.jetbrains.youtrackdb.internal.core.query.ExecutionStep
          || !list.isEmpty() && list
              .getFirst() instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode) {
        state.put(value, "list");
        list.forEach(item -> collectMutable(item, state));
      }
      return;
    }
    if (value instanceof java.util.Set<?> || value instanceof java.util.HashMap<?, ?>
        || value instanceof CommandContext) {
      state.put(value, value.toString());
      return;
    }
    if (!(value instanceof com.jetbrains.youtrackdb.internal.core.query.ExecutionStep)
        && !(value instanceof SelectExecutionPlan)
        && !(value instanceof DetachedCheckMemory)
        && !(value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode)) {
      return;
    }
    state.put(value,
        value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause
            ? value.toString() : value.getClass().getName());
    for (var type = value.getClass(); type != Object.class
        && type != com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode.class;
        type = type.getSuperclass()) {
      for (var field : type.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        try {
          if (value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode) {
            var fieldValue = field.get(value);
            // Snapshot scalar values and reference identities, including lazy syntax caches.
            state.put(value, state.get(value) + "|" + field.getName() + "="
                + (fieldValue == null || fieldValue instanceof String
                    || fieldValue instanceof Number || fieldValue instanceof Boolean
                        ? fieldValue : System.identityHashCode(fieldValue)));
          }
          // Pattern descriptors shared by existing MATCH steps are outside this tree-copy check.
          // FILTER syntax and its semantic descendants must all belong to the copied leaf.
          if (value instanceof FilterBooleanMatchStep && field.getName().equals("condition")) {
            collectMutable(field.get(value), state);
          } else if (!(field
              .get(value) instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode)
              || value instanceof com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode) {
            collectMutable(field.get(value), state);
          }
        } catch (IllegalAccessException failure) {
          throw new AssertionError(failure);
        }
      }
    }
  }

  private List<String> names(SelectExecutionPlan plan) {
    var stream = plan.start();
    try {
      return stream.stream(plan.getContext()).map(row -> row.<String>getProperty("name"))
          .sorted().toList();
    } finally {
      stream.close(plan.getContext());
      plan.close();
    }
  }

  private MatchBooleanCheck or() {
    return new MatchBooleanCheck.Or(List.of(probe(), filter()));
  }

  private MatchBooleanCheck probe() {
    return new MatchBooleanCheck.Probe(parse("MATCH {as:p}.out('BoolE'){as:child} RETURN p")
        .getMatchExpressions().getFirst());
  }

  private MatchBooleanCheck filter() {
    return new MatchBooleanCheck.Filter("p", parse(
        "MATCH {as:p, where:(slug = 'yes')} RETURN p").getMatchExpressions().getFirst()
        .getOrigin().getFilter());
  }

  private SelectExecutionPlan plan(String tail, MatchBooleanCheck tree) {
    var statement = parse("MATCH {class:BoolP, as:p} " + tail);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP"))
        .booleanCheck(tree).returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .orderBy(statement.getOrderBy()).limit(statement.getLimit()).skip(statement.getSkip())
        .build();
    return (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(), false, false);
  }

  /** Flat NOT and EXISTS retain hash selection for predicates outside the tree read whitelist. */
  @Test
  public void flatChecksKeepHashForNullAndInPredicates() {
    for (var predicate : List.of("slug IS NULL", "age IN [30,40]")) {
      var check = parse("MATCH {as:p}.out('BoolE'){as:c, where:(" + predicate
          + ")} RETURN p").getMatchExpressions().getFirst();
      for (var exists : List.of(true, false)) {
        var statement = parse("MATCH {class:BoolP, as:p} RETURN p.name as name");
        var pattern = new Pattern();
        statement.getMatchExpressions().forEach(pattern::addExpression);
        var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("p", "BoolP"))
            .existsMatchExpressions(exists ? List.of(check) : List.of())
            .notMatchExpressions(exists ? List.of() : List.of(check))
            .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
            .returnNestedProjections(statement.getReturnNestedProjections()).build();
        var plan = (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
            .createExecutionPlan(context(), false, false);
        assertThat(plan.prettyPrint(0, 2)).contains(exists ? "HASH SEMI_JOIN" : "HASH ANTI_JOIN");
        assertThat(names(plan)).containsExactlyElementsOf(exists ? List.of()
            : List.of("both", "filter", "hop", "neither"));
      }
      var treePlan = plan("RETURN p.name as name", new MatchBooleanCheck.Probe(check));
      assertThat(treePlan.prettyPrint(0, 2)).contains("BOOLEAN PROBE")
          .doesNotContain("HASH SEMI_JOIN");
      assertThat(names(treePlan)).isEmpty();
      try (var sql = session.query("MATCH {class:BoolP, as:p}, NOT {as:p}.out('BoolE')"
          + "{as:c, where:(" + predicate + ")} RETURN p.name as name")) {
        assertThat(sql.getExecutionPlan().prettyPrint(0, 2))
            .contains("HASH ANTI_JOIN");
        assertThat(sql.toList()).hasSize(4);
      }
    }
  }

  /** Cartesian output publishes all aliases and the FILTER record, not stale upstream state. */
  @Test
  public void cartesianFilterReadsCurrentPositiveBindings() {
    for (var predicate : List.of("name = $matched.a.name", "name = $currentMatch.name")) {
      var statement = parse("MATCH {class:BoolP, as:a}, {class:BoolP, as:b}"
          + " RETURN a.name as origin, b.name as name");
      var pattern = new Pattern();
      statement.getMatchExpressions().forEach(pattern::addExpression);
      var condition = parse("MATCH {as:b, where:(" + predicate + ")} RETURN b")
          .getMatchExpressions().getFirst().getOrigin().getFilter();
      var inputs = MatchPlanInputs.builder(pattern)
          .aliasClasses(Map.of("a", "BoolP", "b", "BoolP"))
          .booleanCheck(new MatchBooleanCheck.Or(List.of(new MatchBooleanCheck.Filter("b",
              condition))))
          .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
          .returnNestedProjections(statement.getReturnNestedProjections()).build();
      var plan = (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
          .createExecutionPlan(context(), false, false);
      assertThat(plan.prettyPrint(0, 2)).contains("CARTESIAN", "BOOLEAN FILTER");
      var stream = plan.start();
      try {
        var rows = stream.stream(plan.getContext()).toList();
        assertThat(rows).hasSize(predicate.contains("matched") ? 4 : 16);
        if (predicate.contains("matched")) {
          rows.forEach(row -> assertThat(row.<String>getProperty("name"))
              .isEqualTo(row.<String>getProperty("origin")));
        }
      } finally {
        stream.close(plan.getContext());
        plan.close();
      }
    }
  }

  private BasicCommandContext context() {
    return new BasicCommandContext(session);
  }

  private static SQLMatchStatement parse(String query) {
    try {
      return (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
          query.getBytes(StandardCharsets.UTF_8))).parse();
    } catch (Exception failure) {
      throw new AssertionError(query, failure);
    }
  }
}
