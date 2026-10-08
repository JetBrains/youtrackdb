package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.GlobalConfigurationScope;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.RuntimeRidStartTestFactory;
import com.jetbrains.youtrackdb.internal.core.sql.executor.EmptyStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Planner-level checks for the direct runtime source and its refusal boundaries. */
@Category(SequentialTest.class)
public class RuntimeRidStartPlannerTest extends DbTestBase {

  private static SQLMatchStatement parse(String query) {
    try {
      return (SQLMatchStatement) new YouTrackDBSql(
          new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8))).parse();
    } catch (Exception e) {
      throw new AssertionError("Cannot parse MATCH fixture", e);
    }
  }

  private MatchPlanInputs inputs(String query, Map<String, String> classes,
      boolean runtime) {
    return inputs(query, classes, runtime, List.of());
  }

  private MatchPlanInputs inputs(String query, Map<String, String> classes,
      boolean runtime, List<SQLMatchExpression> existsChecks) {
    var statement = parse(query);
    var pattern = new Pattern();
    var filters = new HashMap<String, SQLWhereClause>();
    for (var expression : statement.getMatchExpressions()) {
      pattern.addExpression(expression);
      var origin = expression.getOrigin();
      if (origin.getFilter() != null) {
        filters.put(origin.getAlias(), origin.getFilter());
      }
      for (var item : expression.getItems()) {
        var filter = item.getFilter();
        if (filter != null && filter.getFilter() != null) {
          filters.put(filter.getAlias(), filter.getFilter());
        }
      }
    }
    return MatchPlanInputs.builder(pattern)
        .aliasClasses(classes)
        .aliasFilters(filters)
        .notMatchExpressions(statement.getNotMatchExpressions())
        .existsMatchExpressions(existsChecks)
        .returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .returnElements(statement.returnsElements())
        .groupBy(statement.getGroupBy())
        .orderBy(statement.getOrderBy())
        .limit(statement.getLimit())
        .skip(statement.getSkip())
        .runtimeRidStarts(runtime
            ? Map.of("s", RuntimeRidStartTestFactory.create("s", classes.get("s"), 0))
            : Map.of())
        .build();
  }

  private InternalExecutionPlan plan(String query, Map<String, String> classes, boolean runtime) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    context.setInputParameters(Map.of());
    return new MatchExecutionPlanner(inputs(query, classes, runtime))
        .createExecutionPlan(context, false, false);
  }

  private InternalExecutionPlan planWithExists(String positive, String check,
      Map<String, String> classes, boolean runtime) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    context.setInputParameters(Map.of());
    var checks = parse(check).getMatchExpressions();
    return new MatchExecutionPlanner(inputs(positive, classes, runtime, checks))
        .createExecutionPlan(context, false, false);
  }

  /** A remembered runtime tuple estimates one, so nested work wins for both singleton forms. */
  @Test
  public void detachedKeyEstimateUsesRuntimeSingletonIndependentlyOfOriginEstimate()
      throws Exception {
    var db = mock(DatabaseSessionEmbedded.class);
    var metadata = mock(com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault.class);
    var schema = mock(com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.class);
    var clazz =
        mock(com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal.class);
    org.mockito.Mockito.when(db.getMetadata()).thenReturn(metadata);
    org.mockito.Mockito.when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    org.mockito.Mockito.when(schema.existsClass("Large")).thenReturn(true);
    org.mockito.Mockito.when(schema.getClassInternal("Large")).thenReturn(clazz);
    org.mockito.Mockito.when(clazz.approximateCount(db)).thenReturn(10000L);
    var context = mock(com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
    org.mockito.Mockito.when(context.getDatabaseSession()).thenReturn(db);
    var positive = parse("MATCH {as:s} RETURN s");
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var check = parse("MATCH {as:s}.out('UnknownCheck'){as:n} RETURN s")
        .getMatchExpressions().getFirst();
    var reads = DetachedCheckReadSet.describe(check, pattern);
    assertThat(reads.rememberable()).isTrue();
    assertThat(reads.aliases()).containsExactly("s");
    var starts = Map.of("s", RuntimeRidStartTestFactory.create("s", "Large", 2));
    var classes = Map.of("s", "Large");
    var pins = Map.of("s", List.of(mock(SQLRid.class)));
    var keys = MatchExecutionPlanner.class.getDeclaredMethod("estimateDetachedKeys",
        DetachedCheckReadSet.class, Map.class, Map.class, Map.class, Map.class,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
    keys.setAccessible(true);
    try (var threshold = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 10000L);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 5L);
        var fanOut = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT, 10.0)) {
      assertThat(keys.invoke(null, reads, classes, Map.of(), Map.of(), starts, context))
          .isEqualTo(java.util.OptionalDouble.of(1));
      assertThat(keys.invoke(null, reads, classes, Map.of(), pins, Map.of(), context))
          .isEqualTo(java.util.OptionalDouble.of(1));
      var outer = java.util.OptionalLong.of(50);
      var slice = new MatchExecutionPlanner.DetachedSlice(java.util.OptionalLong.empty(), false);
      assertThat(MatchExecutionPlanner.canUseHashJoin(check, classes, Map.of(), Map.of(),
          starts, context, pattern, outer, slice)).isFalse();
      assertThat(MatchExecutionPlanner.canUseHashJoin(check, classes, Map.of(), pins,
          context, pattern, outer, slice)).isFalse();
      // The origin remains one in this control. Only losing the remembered-key discount wins hash.
      assertThat(MatchExecutionPlanner.detachedHashCostWins(outer, 1,
          java.util.OptionalDouble.of(10), java.util.OptionalLong.empty(),
          java.util.OptionalDouble.empty(), java.util.OptionalDouble.of(10001))).isTrue();
    }
  }

  /** A density-root control reverses, but a runtime descriptor bypasses even the density read. */
  @Test
  public void runtimeStartBypassesPresenceRootAndAllPrefetch() {
    session.createVertexClass("StartPerson");
    var target = session.createVertexClass("TargetPerson");
    target.createProperty("k",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER)
        .setMandatory(true);
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name='one'").close();
    session.execute("CREATE VERTEX StartPerson SET name='two'").close();
    session.execute("CREATE VERTEX TargetPerson SET k=1").close();
    session.execute("CREATE EDGE Knows FROM (SELECT FROM StartPerson WHERE name='one')"
        + " TO (SELECT FROM TargetPerson)").close();
    session.commit();
    var where =
        new com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder();
    var base = inputs("MATCH {as:s}.out('Knows'){as:t} RETURN t",
        Map.of("s", "StartPerson", "t", "TargetPerson"), false)
        .toBuilder().aliasFilters(Map.of("t", where.wrap(where.isDefined("k"))))
        .promotedPresenceKeys(Map.of("t", Set.of("k"))).build();
    class Observed extends MatchExecutionPlanner {
      List<String> schedule;
      Set<String> prefetch;

      Observed(MatchPlanInputs input) {
        super(input);
      }

      @Override
      void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
          List<EdgeTraversal> edges) {
        this.prefetch = prefetch;
        this.schedule = edges.stream().map(edge -> sourceAliasForTest(edge)).toList();
      }
    }
    var control = new Observed(base);
    var controlPlan = control.createExecutionPlan(new BasicCommandContext(session), false, false);
    try {
      assertThat(control.presenceDensities().get("t").applied()).isTrue();
      assertThat(control.schedule).containsExactly("t");
    } finally {
      controlPlan.close();
    }
    try (var density = org.mockito.Mockito.mockStatic(PresenceDensity.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      var runtime = new Observed(base.toBuilder().runtimeRidStarts(Map.of("s",
          RuntimeRidStartTestFactory.create("s", "StartPerson", 0))).build());
      var built = runtime.createExecutionPlan(new BasicCommandContext(session), false, false);
      try {
        assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
        assertThat(runtime.schedule).containsExactly("s");
        assertThat(runtime.prefetch).isEmpty();
        assertThat(runtime.presenceDensities()).isEmpty();
        density.verifyNoInteractions();
      } finally {
        built.close();
      }
    }
  }

  private static String sourceAliasForTest(EdgeTraversal edge) {
    return edge.out ? edge.edge.out.alias : edge.edge.in.alias;
  }

  /** Eager boolean hashes refuse before building, while nested probes follow each bound row. */
  @Test
  public void booleanHashRefusesRuntimeWhileNestedChecksKeepPerRowResults() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name='linked'").close();
    session.execute("CREATE VERTEX StartPerson SET name='unlinked'").close();
    session.execute("CREATE VERTEX TargetPerson").close();
    session.execute("CREATE EDGE Knows FROM (SELECT FROM StartPerson WHERE name='linked')"
        + " TO (SELECT FROM TargetPerson)").close();
    session.commit();
    var check = parse("MATCH {as:s}.out('Knows'){as:n} RETURN s")
        .getMatchExpressions().getFirst();
    var base = inputs("MATCH {as:s} RETURN s.name AS name", Map.of("s", "StartPerson"), false)
        .toBuilder().booleanCheck(new MatchBooleanCheck.Or(
            List.of(new MatchBooleanCheck.Probe(check))))
        .build();
    var runtime = base.toBuilder().runtimeRidStarts(Map.of("s",
        RuntimeRidStartTestFactory.create("s", "StartPerson", 0))).build();
    try (var threshold = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 10000L);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 0L)) {
      var control = new MatchExecutionPlanner(base)
          .createExecutionPlan(new BasicCommandContext(session), false, false);
      try {
        assertThat(control.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
      } finally {
        control.close();
      }
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> new MatchExecutionPlanner(runtime)
              .createExecutionPlan(new BasicCommandContext(session), false, false))
          .withMessage("BOOLEAN-pattern hash join needs another source");
    }
    try (var threshold = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 0L)) {
      var built = new MatchExecutionPlanner(runtime)
          .createExecutionPlan(new BasicCommandContext(session), false, false);
      for (var name : List.of("linked", "unlinked")) {
        var ctx = built.getContext();
        ctx.setInputParameters(Map.of(0, session.query(
            "SELECT @rid AS rid FROM StartPerson WHERE name=?", name)
            .toList().getFirst().getProperty("rid")));
        var stream = built.start();
        try {
          assertThat(stream.stream(ctx).map(row -> row.<String>getProperty("name")).toList())
              .isEqualTo(name.equals("linked") ? List.of("linked") : List.of());
        } finally {
          stream.close(ctx);
        }
      }
      built.close();
    }
  }

  /** Matching default/ci and a later compatible index select candidates, but mismatches do not. */
  @Test
  public void indexCollationCompatibilityPreservesLiteralAndRuntimeDetectionParity() {
    for (var mode : List.of("Default", "Ci", "Mismatch", "Mixed")) {
      var sourceName = "Source" + mode;
      var targetName = "Target" + mode;
      session.createVertexClass(sourceName);
      var target = session.createVertexClass(targetName);
      var property = target.createProperty("name",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
      if (mode.equals("Ci")) {
        property.setCollate("ci");
      }
      if (mode.equals("Mismatch") || mode.equals("Mixed")) {
        session.execute("CREATE INDEX " + targetName + ".incompatible ON " + targetName
            + " (name COLLATE ci) NOTUNIQUE").close();
      }
      if (!mode.equals("Mismatch")) {
        target.createIndex(targetName + ".compatible",
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE,
            "name");
      }
      session.createEdgeClass("Link" + mode);
      session.begin();
      session.execute("CREATE VERTEX " + sourceName).close();
      for (var name : List.of("B", "a", "A")) {
        session.execute("CREATE VERTEX " + targetName + " SET name=?", name).close();
      }
      session.commit();
      var classes = Map.of("s", sourceName, "t", targetName);
      var query = "MATCH {as:s}.out('Link" + mode + "'){as:t} RETURN t"
          + " ORDER BY t.name ASC LIMIT 2";
      var input = inputs(query, classes, true);
      input.orderBy().getItems().getFirst().setDeclaredCollate(property.getCollate());
      var edge = input.pattern().aliasToNode.get("s").out.iterator().next();
      var schedule = List.of(new EdgeTraversal(edge, true));
      var context = new BasicCommandContext(session);
      var estimates = Map.of("s", 1L, "t", 4L);
      var literal = indexProbe(input, Map.of("s", List.of(mock(SQLRid.class))), Set.of())
          .detect(schedule, context, estimates);
      var runtime = indexProbe(input, Map.of(), Set.of("s"))
          .detect(schedule, context, estimates);
      assertThat(literal != null).as(mode).isEqualTo(!mode.equals("Mismatch"));
      assertThat(runtime != null).as(mode).isEqualTo(literal != null);
      if (literal != null) {
        assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
            .isThrownBy(() -> plan(query, classes, true)).withMessageContaining("Index-ordered");
      }
      var unlimited = plan(query.replace(" LIMIT 2", ""), classes, true);
      try {
        assertThat(unlimited.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
        assertThat(unlimited.prettyPrint(0, 2)).contains("ORDER BY")
            .doesNotContain("INDEX ORDERED MATCH", "PREFETCH");
      } finally {
        unlimited.close();
      }
    }
  }

  private static final String WALK = "MATCH {as:s}.out('Knows'){as:t} RETURN $elements";

  /** A small target and a larger start still produce a direct first source with no prefetch. */
  @Test
  public void forcedRuntimeRootAndNoPrefetchEvenForSmallTarget() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    session.begin();
    for (var i = 0; i < 120; i++) {
      session.execute("CREATE VERTEX StartPerson SET name = 's" + i + "'").close();
    }
    session.execute("CREATE VERTEX TargetPerson SET name = 'target'").close();
    session.execute("CREATE EDGE Knows FROM (SELECT FROM StartPerson WHERE name = 's0')"
        + " TO (SELECT FROM TargetPerson WHERE name = 'target')").close();
    session.commit();

    // The target has a singleton filter estimate too. The direct scheduler test below
    // independently proves that the runtime root wins even when the target is listed first.
    var forcedWalk = "MATCH {as:s}.both('Knows'){as:a, where:(name = 'target')}"
        + " RETURN $elements";
    var built = plan(forcedWalk, Map.of("s", "StartPerson", "a", "TargetPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(built.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
        .doesNotContain("PREFETCH").doesNotContain("FETCH FROM CLASS StartPerson");
    assertThat(built.canBeCached()).isTrue();

    // The source reads the RID at execution, and the hop consumes that row rather than
    // independently scanning StartPerson or the small target class.
    var ctx = built.getContext();
    ctx.setInputParameters(Map.of(0, session.query(
        "SELECT @rid as rid FROM StartPerson WHERE name = 's0'")
        .toList().getFirst().getProperty("rid")));
    var stream = built.start();
    try {
      assertThat(stream.hasNext(ctx)).isTrue();
      assertThat(stream.next(ctx)).isNotNull();
      // RETURN $elements emits the start and the target for each matched path.
      assertThat(stream.hasNext(ctx)).isTrue();
      assertThat(stream.next(ctx)).isNotNull();
      assertThat(stream.hasNext(ctx)).isFalse();
    } finally {
      stream.close(ctx);
      built.close();
    }
    var other = plan(forcedWalk, Map.of("s", "StartPerson", "a", "TargetPerson"), true);
    var otherCtx = other.getContext();
    otherCtx.setInputParameters(Map.of(0, session.query(
        "SELECT @rid as rid FROM StartPerson WHERE name = 's1'")
        .toList().getFirst().getProperty("rid")));
    var otherStream = other.start();
    try {
      assertThat(otherStream.hasNext(otherCtx)).isFalse();
    } finally {
      otherStream.close(otherCtx);
      other.close();
    }
  }

  /** Equal estimates with the target listed first still schedule the runtime source first. */
  @Test
  public void forcedRuntimeRootOverridesCompetingAliasFirstInEstimateOrder() throws Exception {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    var input = inputs("MATCH {as:s}.both('Knows'){as:a} RETURN s",
        Map.of("s", "StartPerson", "a", "TargetPerson"), true);
    var estimates = new LinkedHashMap<String, Long>();
    estimates.put("a", 1L);
    estimates.put("s", 1L);
    // Invoke the scheduler directly so estimate-map insertion order cannot mask forcing.
    var scheduler = MatchExecutionPlanner.class.getDeclaredMethod(
        "getTopologicalSortedSchedule", Map.class, Pattern.class, Map.class, Map.class,
        DatabaseSessionEmbedded.class);
    scheduler.setAccessible(true);
    var schedule = (List<?>) scheduler.invoke(new MatchExecutionPlanner(input), estimates,
        input.pattern(), input.aliasClasses(), input.aliasFilters(), session);
    assertThat(schedule).hasSize(1);
    var first = (EdgeTraversal) schedule.getFirst();
    assertThat(first.out).isTrue();
    assertThat(first.edge.out.alias).isEqualTo("s");
    assertThat(first.edge.in.alias).isEqualTo("a");
  }

  /** An isolated runtime alias also uses direct fetch and never a SELECT root subplan. */
  @Test
  public void isolatedAliasUsesDirectSource() {
    session.createVertexClass("StartPerson");
    var built = plan("MATCH {as:s} RETURN $elements", Map.of("s", "StartPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(built.prettyPrint(0, 2)).doesNotContain("PREFETCH");
  }

  /** A disconnected component would run its own SELECT before the RID source. */
  @Test
  public void disjointComponentIsRefused() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    var query = "MATCH {as:s}, {as:t} RETURN $elements";
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan(query, Map.of("s", "StartPerson", "t", "TargetPerson"), true))
        .withMessageContaining("Disconnected");
  }

  /** A class count shortcut must not turn a single-RID count into a whole-class count. */
  @Test
  public void hardwiredCountIsRefusedButFilteredCountRunsNormally() {
    session.createVertexClass("StartPerson");
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan("MATCH {as:s} RETURN count(*)",
            Map.of("s", "StartPerson"), true))
        .withMessageContaining("Whole-class count");
    var normal = plan("MATCH {as:s, where:(name = 'one')} RETURN count(*)",
        Map.of("s", "StartPerson"), true);
    assertThat(normal.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
  }

  /** A zero estimate on another alias keeps the non-cacheable empty-plan shortcut. */
  @Test
  public void zeroEstimateOnOtherAliasKeepsNonCacheableEmptyStep() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    var built = plan("MATCH {as:s}.out('Knows'){as:t, where:(name = 'absent')}"
        + " RETURN $elements", Map.of("s", "StartPerson", "t", "TargetPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(EmptyStep.class);
    assertThat(built.canBeCached()).isFalse();
  }

  /** The final alias filter is handed to the direct step, not lost in a SELECT subplan. */
  @Test
  public void remainingStartFilterRunsOnEveryBoundRid() {
    session.createVertexClass("StartPerson");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'allowed'").close();
    session.execute("CREATE VERTEX StartPerson SET name = 'blocked'").close();
    session.commit();
    for (var name : List.of("allowed", "blocked")) {
      var built = plan("MATCH {as:s, where:(name = 'allowed')} RETURN $elements",
          Map.of("s", "StartPerson"), true);
      var ctx = built.getContext();
      ctx.setInputParameters(Map.of(0, session.query(
          "SELECT @rid as rid FROM StartPerson WHERE name = '" + name + "'")
          .toList().getFirst().getProperty("rid")));
      var stream = built.start();
      try {
        assertThat(stream.hasNext(ctx)).isEqualTo(name.equals("allowed"));
      } finally {
        stream.close(ctx);
        built.close();
      }
    }
  }

  /** An optional root reverses the actual first traversal despite its priority in the list. */
  @Test
  public void optionalSourceCannotBypassTheResultingScheduleCheck() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    var query = "MATCH {as:t}.both('Knows'){as:s,optional:true} RETURN $elements";
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan(query, Map.of("s", "StartPerson", "t", "TargetPerson"), true))
        .withMessageContaining("schedule");
  }

  /** An isolated indexed node keeps the RID source and a normal ORDER BY, not SELECT index order. */
  @Test
  public void isolatedStartSkipsSingleNodeIndexOrder() {
    var start = session.createVertexClass("StartPerson");
    start.createProperty("score",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var built = plan("MATCH {as:s} RETURN s ORDER BY s.score ASC LIMIT 1",
        Map.of("s", "StartPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(built.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
        .doesNotContain("FETCH FROM INDEX VALUES");
    for (var query : List.of(
        "MATCH {as:s} RETURN s ORDER BY s.score ASC, s.@rid ASC LIMIT 1",
        "MATCH {as:s} RETURN s.score AS ordered ORDER BY ordered ASC LIMIT 1")) {
      var ordered = plan(query, Map.of("s", "StartPerson"), true);
      assertThat(ordered.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
      assertThat(ordered.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
          .contains("ORDER BY").doesNotContain("FETCH FROM INDEX VALUES");
    }
  }

  /** The index-ordered path is refused while the ordinary sort path remains available. */
  @Test
  public void indexedOrderRequiresLiteralStartWhileUnindexedOrderKeepsRuntimeStart() {
    var target = session.createVertexClass("TargetPerson");
    target.createProperty("score",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.createVertexClass("StartPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'one'").close();
    session.execute("CREATE VERTEX TargetPerson SET score = 1").close();
    session.commit();
    var order = "MATCH {as:s}.out('Knows'){as:t} RETURN t ORDER BY t.score ASC LIMIT 1";
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan(order, Map.of("s", "StartPerson", "t", "TargetPerson"), true))
        .withMessageContaining("Index-ordered");
    var ordinary = plan("MATCH {as:s}.out('Knows'){as:t} RETURN t"
        + " ORDER BY t.other ASC LIMIT 1",
        Map.of("s", "StartPerson", "t", "TargetPerson"), true);
    assertThat(ordinary.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(ordinary.prettyPrint(0, 2)).doesNotContain("INDEX ORDERED MATCH");
  }

  /** A selected hash anti-join scans its NOT pattern before opening the runtime source. */
  @Test
  public void notPatternHashBuildIsRefused() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'one'").close();
    session.execute("CREATE VERTEX TargetPerson SET name = 'two'").close();
    session.commit();
    var query = "MATCH {as:s}.out('Knows'){as:t},"
        + " NOT {as:s}.out('Knows'){as:n} RETURN $elements";
    var classes = Map.of("s", "StartPerson", "t", "TargetPerson", "n", "TargetPerson");
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 0L)) {
      assertThat(plan(query, classes, false).prettyPrint(0, 2)).contains("HASH ANTI_JOIN");
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> plan(query, classes, true))
          .withMessageContaining("NOT-pattern hash join");
    }
  }

  /** A selected EXISTS hash build scans its origin before opening the RID source. */
  @Test
  public void existsPatternHashBuildIsRefused() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'one'").close();
    session.execute("CREATE VERTEX TargetPerson SET name = 'two'").close();
    session.commit();
    var check = "MATCH {as:s}.out('Knows'){as:child} RETURN s";
    var positive = "MATCH {as:s} RETURN s";
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 0L)) {
      var literal = planWithExists(positive, check, Map.of("s", "StartPerson"), false);
      assertThat(literal.prettyPrint(0, 2)).contains("HASH SEMI_JOIN");
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> planWithExists(positive, check, Map.of("s", "StartPerson"), true))
          .withMessageContaining("EXISTS-pattern hash join needs another source");
    }
  }

  /** Default guards select the literal singleton's anti-join and refuse its runtime equivalent. */
  @Test
  public void notPatternDefaultCostGuardsUseRuntimeSingletonEstimate() {
    var rid = detachedCostFixture();
    var query = "MATCH {as:s}.out('Expand'){as:t},"
        + " NOT {as:s}.out('Check'){as:n, where:(name IS DEFINED)} RETURN t";
    var literalQuery = query.replace("MATCH {as:s}",
        "MATCH {as:s, where:(@rid = " + rid + ")}");
    var classes = Map.of("s", "StartPerson", "t", "TargetPerson", "n", "TargetPerson");
    var input = inputs(query, classes, true);
    assertThat(DetachedCheckReadSet.describe(input.notMatchExpressions().getFirst(),
        input.pattern()).rememberable()).isFalse();
    try (var threshold = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 10000L);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 5L)) {
      assertThat(plan(literalQuery, classes, false).prettyPrint(0, 2)).contains("HASH ANTI_JOIN");
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> plan(query, classes, true))
          .withMessageContaining("NOT-pattern hash join needs another source");
    }
  }

  /** Default guards select the literal singleton's semi-join and refuse its runtime equivalent. */
  @Test
  public void existsPatternDefaultCostGuardsUseRuntimeSingletonEstimate() {
    var rid = detachedCostFixture();
    var positive = "MATCH {as:s}.out('Expand'){as:t} RETURN t";
    var literalPositive = positive.replace("MATCH {as:s}",
        "MATCH {as:s, where:(@rid = " + rid + ")}");
    var check = "MATCH {as:s}.out('Check'){as:n, where:(name IS DEFINED)} RETURN s";
    var classes = Map.of("s", "StartPerson", "t", "TargetPerson", "n", "TargetPerson");
    assertThat(DetachedCheckReadSet.describe(parse(check).getMatchExpressions().getFirst(),
        inputs(positive, classes, true).pattern()).rememberable()).isFalse();
    try (var threshold = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 10000L);
        var minimum = GlobalConfigurationScope.set(
            GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 5L)) {
      assertThat(planWithExists(literalPositive, check, classes, false).prettyPrint(0, 2))
          .contains("HASH SEMI_JOIN");
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> planWithExists(positive, check, classes, true))
          .withMessageContaining("EXISTS-pattern hash join needs another source");
    }
  }

  private Object detachedCostFixture() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Expand");
    session.createEdgeClass("Check");
    session.begin();
    for (var i = 0; i < 100; i++) {
      session.execute("CREATE VERTEX StartPerson SET name = ?", "s" + i).close();
    }
    for (var i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX TargetPerson SET name = ?", "t" + i).close();
    }
    for (var edge : List.of("Expand", "Check")) {
      session.execute("CREATE EDGE " + edge
          + " FROM (SELECT FROM StartPerson) TO (SELECT FROM TargetPerson)").close();
    }
    session.commit();
    // Every target has a name. The detached presence check preserves these rows but is not
    // rememberable, so ten upstream rows pay ten probes. Singleton hash cost 21 beats 100.
    // A whole-class origin estimate makes hash lose, so both tests detect missing threading.
    return session.query("SELECT @rid AS rid FROM StartPerson WHERE name = 's0'")
        .toList().getFirst().getProperty("rid");
  }

  /**
   * The per-row EXISTS probe keeps the runtime source. The known-endpoint alternative requires a
   * class-scan root and must never wrap this runtime plan, including for an unlinked start.
   */
  @Test
  public void existsPerRowProbeKeepsRuntimeSource() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createVertexClass("OtherPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'one'").close();
    session.execute("CREATE VERTEX TargetPerson SET name = 'two'").close();
    session.execute("CREATE VERTEX OtherPerson SET name = 'other'").close();
    session.execute("CREATE EDGE Knows FROM (SELECT FROM StartPerson WHERE name = 'one')"
        + " TO (SELECT FROM TargetPerson WHERE name = 'two')").close();
    session.commit();
    var valid = session.query("SELECT @rid AS rid FROM StartPerson WHERE name = 'one'")
        .toList().getFirst().getProperty("rid");
    var wrong = session.query("SELECT @rid AS rid FROM OtherPerson WHERE name = 'other'")
        .toList().getFirst().getProperty("rid");
    var check = "MATCH {as:s}.out('Knows'){as:child} RETURN s";
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD, 0L)) {
      for (var binding : List.of(valid, wrong)) {
        var plan = planWithExists("MATCH {as:s} RETURN s", check,
            Map.of("s", "StartPerson"), true);
        assertThat(plan.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
        assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
        assertThat(plan.prettyPrint(0, 2)).contains("+ EXISTS (")
            .doesNotContain("HASH SEMI_JOIN").doesNotContain("PREFETCH")
            .doesNotContain("FETCH FROM CLASS StartPerson");
        var ctx = plan.getContext();
        ctx.setInputParameters(Map.of(0, binding));
        var stream = plan.start();
        try {
          assertThat(stream.stream(ctx).toList()).hasSize(binding.equals(valid) ? 1 : 0);
        } finally {
          stream.close(ctx);
          plan.close();
        }
      }
    }
  }

  /** A mandatory back-reference keeps only the neighbor that links back to the bound start. */
  @Test
  public void backReferenceHashJoinKeepsRuntimeSourceAndReturnsMatchingNeighbor() {
    var rid = backReferenceFixture();
    var built = plan("MATCH {as:s}.out('Knows'){as:p}"
        + ".out('Back'){as:r, where:(@rid = $matched.s.@rid)}"
        + " RETURN p.name AS neighbor, r.name AS back",
        Map.of("s", "StartPerson", "p", "TargetPerson", "r", "StartPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(built.getSteps()).anyMatch(BackRefHashJoinStep.class::isInstance);
    assertThat(built.prettyPrint(0, 2)).contains("BACK-REF HASH JOIN")
        .doesNotContain("PREFETCH").doesNotContain("FETCH FROM CLASS StartPerson");
    var ctx = built.getContext();
    ctx.setInputParameters(Map.of(0, rid));
    var stream = built.start();
    try {
      var rows = stream.stream(ctx).toList();
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().<String>getProperty("neighbor")).isEqualTo("matched");
      assertThat(rows.getFirst().<String>getProperty("back")).isEqualTo("one");
    } finally {
      stream.close(ctx);
      built.close();
    }
  }

  /** A correlated optional join preserves both neighbors and binds null for the missing link. */
  @Test
  public void correlatedOptionalHashJoinKeepsRuntimeSourceAndPreservesUnmatchedNeighbor() {
    var rid = backReferenceFixture();
    var built = plan("MATCH {as:s}.out('Knows'){as:p}"
        + ".out('Back'){as:r, where:(@rid = $matched.s.@rid), optional:true}"
        + " RETURN p.name AS neighbor, r.name AS back",
        Map.of("s", "StartPerson", "p", "TargetPerson", "r", "StartPerson"), true);
    assertThat(built.getSteps().getFirst()).isInstanceOf(RuntimeRidStartStep.class);
    assertThat(built.getSteps()).anyMatch(CorrelatedOptionalHashJoinStep.class::isInstance);
    assertThat(built.prettyPrint(0, 2)).contains("CORRELATED OPTIONAL HASH JOIN")
        .doesNotContain("PREFETCH").doesNotContain("FETCH FROM CLASS StartPerson");
    var ctx = built.getContext();
    ctx.setInputParameters(Map.of(0, rid));
    var stream = built.start();
    try {
      var rows = stream.stream(ctx).toList();
      assertThat(rows).hasSize(2);
      assertThat(rows).extracting(row -> row.<String>getProperty("neighbor"))
          .containsExactlyInAnyOrder("matched", "unmatched");
      for (var row : rows) {
        assertThat(row.<String>getProperty("back"))
            .isEqualTo("matched".equals(row.getProperty("neighbor")) ? "one" : null);
      }
    } finally {
      stream.close(ctx);
      built.close();
    }
  }

  private Object backReferenceFixture() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    session.createEdgeClass("Back");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'one'").close();
    session.execute("CREATE VERTEX TargetPerson SET name = 'matched'").close();
    session.execute("CREATE VERTEX TargetPerson SET name = 'unmatched'").close();
    session.execute("CREATE EDGE Knows FROM (SELECT FROM StartPerson)"
        + " TO (SELECT FROM TargetPerson)").close();
    session.execute("CREATE EDGE Back FROM (SELECT FROM TargetPerson WHERE name = 'matched')"
        + " TO (SELECT FROM StartPerson)").close();
    session.commit();
    return session.query("SELECT @rid AS rid FROM StartPerson")
        .toList().getFirst().getProperty("rid");
  }

  /** A diamond pattern's hash-build side cannot run before the runtime source. */
  @Test
  public void diamondHashBuildIsRefused() {
    session.createVertexClass("StartPerson");
    session.createEdgeClass("Knows");
    var query = "MATCH {as:s}.out('Knows'){as:a}.out('Knows'){as:d},"
        + " {as:s}.out('Knows'){as:b}.out('Knows'){as:d} RETURN $elements";
    try (var ignored = GlobalConfigurationScope.set(
        GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN, 0L)) {
      assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
          .isThrownBy(() -> plan(query, Map.of("s", "StartPerson", "a", "StartPerson",
              "b", "StartPerson", "d", "StartPerson"), true))
          .withMessageContaining("MATCH hash join needs another source");
    }
  }

  /** An inverted WHILE step finds an anchor through its own SELECT before traversal. */
  @Test
  public void invertedWhileAnchorIsRefused() {
    session.createVertexClass("StartPerson");
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'anchor'").close();
    session.commit();
    var query = "MATCH {as:s}.out('Knows'){as:middle}"
        + ".out('Knows'){while: (true), as:t, where:(name = 'anchor')} RETURN $elements";
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan(query, Map.of("s", "StartPerson", "middle", "StartPerson",
            "t", "StartPerson"), true))
        .withMessageContaining("Inverted WHILE needs another source");
  }

  /** The runtime probe takes the literal pin's single-source path, not filtered cost rejection. */
  @Test
  public void orderedProbeMatchesLiteralSingletonWhenFilteredModeRejects() {
    session.createVertexClass("StartPerson");
    var target = session.createVertexClass("TargetPerson");
    target.createProperty("score",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER)
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.createEdgeClass("Knows");
    session.begin();
    session.execute("CREATE VERTEX StartPerson SET name = 'starter'").close();
    // The index dwarfs the fan-out cap of the filtered cost gate. The singleton probe
    // bypasses that gate and sees the indexed top-N candidate the literal pin would use.
    for (var i = 0; i < 10000; i++) {
      session.execute("CREATE VERTEX TargetPerson SET score = " + i).close();
    }
    session.commit();
    session.begin();
    var query = "MATCH {as:s, where:(name LIKE 'starter%')}"
        + ".out('Knows'){as:t} RETURN t ORDER BY t.score ASC LIMIT 100";
    var input = inputs(query, Map.of("s", "StartPerson", "t", "TargetPerson"), true);
    var edge = input.pattern().aliasToNode.get("s").out.iterator().next();
    var sorted = List.of(new EdgeTraversal(edge, true));
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var estimated = Map.of("s", 1L, "t", 10001L);
    var legacyFiltered = indexProbe(input, Map.of(), Set.of());
    var literalPin = indexProbe(input, Map.of("s", List.of(mock(SQLRid.class))), Set.of());
    var runtime = indexProbe(input, Map.of(), Set.of("s"));
    assertThat(legacyFiltered.detect(sorted, context, estimated)).isNull();
    assertThat(literalPin.detect(sorted, context, estimated)).isNotNull();
    assertThat(runtime.detect(sorted, context, estimated)).isNotNull();
    assertThatExceptionOfType(RuntimeRidStartPlanningException.class)
        .isThrownBy(() -> plan(query, Map.of("s", "StartPerson", "t", "TargetPerson"), true))
        .withMessageContaining("Index-ordered");
  }

  private static IndexOrderedPlanner indexProbe(MatchPlanInputs input,
      Map<String, List<SQLRid>> pins, Set<String> runtimeSingletons) {
    return new IndexOrderedPlanner(input.pattern(), input.aliasClasses(), input.aliasFilters(),
        pins, runtimeSingletons, input.orderBy(), input.skip(), input.limit(),
        input.returnItems(), input.returnAliases(), input.returnDistinct(),
        input.returnElements(), input.returnPaths(), input.returnPatterns(),
        input.returnPathElements());
  }

  /** SQL parsing has no runtime descriptor and preserves the normal small-class prefetch path. */
  @Test
  public void handwrittenSqlMatchStillUsesItsOwnSourceAndPrefetch() {
    session.createVertexClass("StartPerson");
    session.createVertexClass("TargetPerson");
    session.createEdgeClass("Knows");
    var query = "EXPLAIN MATCH {class:StartPerson,as:s}.out('Knows')"
        + "{class:TargetPerson,as:t} RETURN $elements";
    var sqlPlan = (String) session.query(query).toList().getFirst()
        .getProperty("executionPlanAsString");
    assertThat(sqlPlan).contains("PREFETCH").doesNotContain("FETCH FROM RID PARAMETER");
    var additive = plan(WALK, Map.of("s", "StartPerson", "t", "TargetPerson"), false);
    assertThat(additive.prettyPrint(0, 2)).doesNotContain("FETCH FROM RID PARAMETER");
  }
}
