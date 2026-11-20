package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Reference estimates across roots, prefetch, edge costs, forecasts and later LINK estimates. */
public class TranslatorPresenceEstimateTest extends GraphBaseTest {

  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  /** The estimate interval exposes a reversed root, with both dense and sparse target keys. */
  @Test
  public void denseAndSparseTargets_keepReferenceRootScheduleAndCosts() throws Exception {
    for (var dense : List.of(true, false)) {
      var suffix = dense ? "Dense" : "Sparse";
      seed(suffix, 240, 150, dense);
      assertReference(suffix, 240, 150);
    }
  }

  /** Presence would prefetch 75 targets, while the reference estimate of 151 does not. */
  @Test
  public void smallClass_doesNotGainPresencePrefetch() throws Exception {
    seed("Small", 150, 100, true);
    assertReference("Small", 150, 100);
  }

  private void assertReference(String suffix, int targets, int selected) throws Exception {
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      var statement = parse("MATCH {class:Source" + suffix + ", as:s}"
          + ".out('Link" + suffix + "'){as:t} RETURN t.k AS value LIMIT 10");
      var pattern = new Pattern();
      statement.getMatchExpressions().forEach(pattern::addExpression);
      var source = sourceFilter();
      var promoted = promoted();
      var classes = Map.of("s", "Source" + suffix, "t", "Target" + suffix);
      var referenceFilters = Map.of("s", source);
      var filters = Map.of("s", source, "t", promoted);
      var expected = MatchExecutionPlanner.estimateRootEntries(
          classes, Map.of(), referenceFilters, context);
      assertThat(expected.get("s")).isGreaterThan(targets / 2L).isLessThan(targets + 1L)
          .isEqualTo((long) selected);
      var estimateFilters = estimates(filters);
      assertThat(
          MatchExecutionPlanner.estimateRootEntries(classes, Map.of(), estimateFilters, context))
          .isEqualTo(expected);
      var ordinary = WHERE.wrap(WHERE.isDefined("k"));
      assertThat(MatchExecutionPlanner.estimateRootEntries(classes, Map.of(),
          Map.of("s", source, "t", ordinary), context).get("t"))
          .isLessThan(expected.get("s"));
      var inputs = MatchPlanInputs.builder(pattern).aliasClasses(classes).aliasFilters(filters)
          .promotedPresenceKeys(Map.of("t", Set.of("k")))
          .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
          .returnNestedProjections(statement.getReturnNestedProjections())
          .limit(statement.getLimit());
      var planner = new ObservedPlanner(inputs.build());
      var reference = new ObservedPlanner(inputs.aliasFilters(referenceFilters).build());
      var plan = planner.createExecutionPlan(context, false, false);
      var referencePlan = reference.createExecutionPlan(context, false, false);
      try {
        assertThat(planner.estimates).isEqualTo(reference.estimates);
        assertThat(planner.prefetch).isEqualTo(reference.prefetch).doesNotContain("t");
        assertThat(planner.schedule).isEqualTo(reference.schedule).containsExactly("s->t");
        assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, planner.edges,
            expected, classes, estimateFilters, context))
            .isEqualTo(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, reference.edges,
                expected, classes, referenceFilters, context));
        var edge = planner.edges.getFirst();
        assertThat(MatchExecutionPlanner.targetSelectivityFactor("t", edge.edge, true, classes,
            estimateFilters, expected, null, session))
            .isEqualTo(MatchExecutionPlanner.targetSelectivityFactor("t", edge.edge, true, classes,
                referenceFilters, expected, null, session));
        assertThat(MatchExecutionPlanner.applyTargetSelectivity(7, "t", edge.edge, true,
            classes, estimateFilters, expected, session))
            .isEqualTo(MatchExecutionPlanner.applyTargetSelectivity(7, "t", edge.edge, true,
                classes, referenceFilters, expected, session));
        // Later estimates see the LINK class written by index-order detection, not the V class
        // used by the translator's initial root estimate. Cover both class maps explicitly.
        for (var targetClass : List.of("V", "Target" + suffix)) {
          var laterClasses = Map.of("s", "Source" + suffix, "t", targetClass);
          assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(
              parse("MATCH {as:t} RETURN t").getMatchExpressions().getFirst(),
              laterClasses, estimateFilters, Map.of(), context))
              .isEqualTo(MatchExecutionPlanner.estimateNotPatternCardinality(
                  parse("MATCH {as:t} RETURN t").getMatchExpressions().getFirst(),
                  laterClasses, referenceFilters, Map.of(), context));
        }
        var detached = parse("MATCH {class:Source" + suffix + ",as:s}.out('Link" + suffix
            + "'){as:t,where:(k IS DEFINED)} RETURN s")
            .getMatchExpressions().getFirst();
        var detachedReference = parse("MATCH {class:Source" + suffix + ",as:s}.out('Link" + suffix
            + "'){as:t} RETURN s").getMatchExpressions().getFirst();
        assertThat(
            invoke("estimateDetachedWalk", null, detached, classes, estimateFilters, context))
            .isEqualTo(invoke("estimateDetachedWalk", null, detachedReference, classes,
                referenceFilters, context));
        assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(detached, classes,
            estimateFilters, Map.of(), context))
            .isEqualTo(MatchExecutionPlanner.estimateNotPatternCardinality(detachedReference,
                classes, referenceFilters, Map.of(), context));
        // Invoke the private consumers directly with the same shared view. Reflection keeps
        // these test-only observations out of the planner API.
        for (var name : List.of("estimateAliasCardinality", "estimateBranchCardinality",
            "estimateUpstreamCardinality", "estimateBranchFanOut")) {
          var branch = List.of(edge, edge);
          Object[] strippedArgs = switch (name) {
            case "estimateAliasCardinality" ->
                new Object[] {"t", classes, estimateFilters, Map.of(), context};
            case "estimateBranchCardinality" ->
                new Object[] {"s", branch, classes, estimateFilters, Map.of(), context};
            case "estimateUpstreamCardinality" ->
                new Object[] {planner.edges, 1, List.of(), classes, estimateFilters, Map.of(),
                    context};
            default -> new Object[] {branch, "s", classes, estimateFilters, context};
          };
          var referenceArgs = strippedArgs.clone();
          for (int i = 0; i < referenceArgs.length; i++) {
            if (referenceArgs[i] == estimateFilters) {
              referenceArgs[i] = referenceFilters;
            }
          }
          assertThat(invoke(name, null, strippedArgs)).as(name)
              .isEqualTo(invoke(name, null, referenceArgs));
        }
        var plannerClasses = MatchExecutionPlanner.class.getDeclaredField("aliasClasses");
        plannerClasses.setAccessible(true);
        for (var laterClass : List.of("V", "Target" + suffix)) {
          var laterClasses = Map.of("s", "Source" + suffix, "t", laterClass);
          plannerClasses.set(planner, laterClasses);
          plannerClasses.set(reference, laterClasses);
          var rows = new java.util.HashMap<>(expected);
          var referenceRows = new java.util.HashMap<>(expected);
          invoke("stampForecastFor", planner, edge, "s", "t", rows,
              new java.util.HashMap<>(expected), new java.util.HashMap<>(), session);
          invoke("stampForecastFor", reference, reference.edges.getFirst(), "s", "t", referenceRows,
              new java.util.HashMap<>(expected), new java.util.HashMap<>(), session);
          assertThat(rows).as("forecast after LINK class=" + laterClass).isEqualTo(referenceRows);
        }
        var propagated = new java.util.HashMap<>(expected);
        var referencePropagated = new java.util.HashMap<>(expected);
        invoke("stampForecastFor", planner, edge, "s", "t", propagated,
            new java.util.HashMap<>(expected), new java.util.HashMap<>(), session);
        long forecast = edge.getForecastN();
        invoke("stampForecastFor", reference, reference.edges.getFirst(), "s", "t",
            referencePropagated, new java.util.HashMap<>(expected),
            new java.util.HashMap<>(), session);
        assertThat(propagated).isEqualTo(referencePropagated);
        assertThat(forecast).isPositive().isEqualTo(reference.edges.getFirst().getForecastN());
        assertThat(filters.get("t").toString()).contains("is defined");
        System.out.println("G5 " + suffix + " estimates=" + expected
            + " prefetch=" + planner.prefetch + " schedule=" + planner.schedule
            + "\n" + plan.prettyPrint(0, 2));
      } finally {
        plan.close();
        referencePlan.close();
      }
    } finally {
      session.rollback();
    }
  }

  /** Other predicates survive nested AND stripping and still select the indexed class subset. */
  @Test
  public void mergedCopies_keepOtherFiltersAndPresenceOrigin() {
    seed("Merged", 240, 150, true);
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      var original = sourceFilter().getBaseExpression();
      var merged = WHERE.wrap(WHERE.and(WHERE.and(original,
          promoted().getBaseExpression()), promoted().getBaseExpression()));
      var classes = Map.of("s", "SourceMerged");
      var reference = MatchExecutionPlanner.estimateRootEntries(classes, Map.of(),
          Map.of("s", WHERE.wrap(original)), context);
      for (var copy : List.of(merged, merged.copy(), merged.copy().copy())) {
        assertThat(copy.toString()).contains("is defined");
        assertThat(MatchExecutionPlanner.estimateRootEntries(classes, Map.of(),
            estimates(Map.of("s", copy)), context)).isEqualTo(reference).containsEntry("s", 150L);
        assertThat(MatchExecutionPlanner.estimateFilterSelectivity(
            estimates(Map.of("s", copy)).get("s"), 400, null, null))
            .isEqualTo(MatchExecutionPlanner.estimateFilterSelectivity(
                WHERE.wrap(original), 400, null, null));
      }
      var twoRemaining = WHERE.wrap(WHERE.and(original, WHERE.isDefined("other"),
          promoted().getBaseExpression()));
      assertThat(estimates(Map.of("s", twoRemaining)).get("s").toString())
          .isEqualTo(WHERE.wrap(WHERE.and(original, WHERE.isDefined("other"))).toString());
      assertThat(estimates(Map.of("s", WHERE.wrap(WHERE.and(
          promoted().getBaseExpression(), promoted().getBaseExpression())))).get("s")).isNull();
      assertThat(estimates(Map.of("s", WHERE.wrap(original))).get("s").toString())
          .isEqualTo(WHERE.wrap(original).toString());
    } finally {
      session.rollback();
    }
  }

  /** The affected empty class loses only the zero-estimate shortcut and still returns no rows. */
  @Test
  public void emptyClass_usesReferenceBiasInsteadOfEmptyShortcut() {
    session.createVertexClass("Empty");
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      var classes = Map.of("t", "Empty");
      assertThat(MatchExecutionPlanner.estimateRootEntries(classes, Map.of(),
          estimates(Map.of("t", promoted())), context))
          .isEqualTo(
              MatchExecutionPlanner.estimateRootEntries(classes, Map.of(), Map.of(), context))
          .containsEntry("t", 1L);
      assertThat(MatchExecutionPlanner.estimateRootEntries(classes, Map.of(),
          Map.of("t", WHERE.wrap(WHERE.isDefined("k"))), context)).containsEntry("t", 0L);
    } finally {
      session.rollback();
    }
    assertThat(graph.traversal().V().hasLabel("Empty").values("k").limit(1).toList()).isEmpty();
  }

  /** OR and NOT predicates are executable units, even when a mapped key occurs inside them. */
  @Test
  public void nestedDisjunctionAndNegation_keepUserSelectivity() {
    var presence = WHERE.isDefined("k");
    for (var condition : List.of(WHERE.or(presence, WHERE.isDefined("other")),
        WHERE.not(presence), WHERE.isNotDefined("k"))) {
      var filter = WHERE.wrap(condition);
      assertThat(estimates(Map.of("s", filter)).get("s").toString())
          .isEqualTo(filter.toString());
    }
  }

  /** A runtime alternative and its copy estimate the stripped binary, not the executable AND. */
  @Test
  public void knownEndpointCopy_keepsSharedEstimateFilter() {
    seed("Runtime", 240, 150, true);
    session.begin();
    var target = graph.traversal().V().hasLabel("TargetRuntime").next().id();
    var positive = parse("MATCH {class:SourceRuntime, as:s} RETURN s");
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var source = sourceFilter();
    var executable = WHERE.wrap(WHERE.and(source.getBaseExpression(), WHERE.isDefined("k")));
    var check = parse("MATCH {as:s}.out('LinkRuntime'){as:t,where:(@rid=" + target
        + ")} RETURN s").getMatchExpressions().getFirst();
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("s", "SourceRuntime"))
        .aliasFilters(Map.of("s", executable)).promotedPresenceKeys(Map.of("s", Set.of("k")))
        .existsMatchExpressions(List.of(check)).returnItems(positive.getReturnItems())
        .returnAliases(positive.getReturnAliases())
        .returnNestedProjections(positive.getReturnNestedProjections()).build();
    var context = new BasicCommandContext(session);
    var plan = new MatchExecutionPlanner(inputs).createExecutionPlan(context, false, false);
    assertThat(plan.getSteps().getFirst()).isInstanceOf(KnownEndpointExistsStep.class);
    var step = (KnownEndpointExistsStep) plan.getSteps().getFirst();
    var copied = (KnownEndpointExistsStep) step.copy(context);
    session.rollback();
    session.getMetadata().getSchema().getClass("SourceRuntime").getProperty("group")
        .createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.begin();
    // The existing equality estimator uses 1 / totalCount without a useful histogram.
    // Presence must not turn the binary into an unsupported compound filter with share 1.
    assertThat(step.filterShare(context)).isEqualTo(1.0 / 300);
    assertThat(copied.filterShare(context)).isEqualTo(step.filterShare(context));
    copied.close();
    plan.close();
    session.rollback();
  }

  /** Root projection promotion keeps the index-values access path at the planner seam. */
  @Test
  public void promotedRootValue_keepsOrderedIndexPlan() {
    var root = session.createVertexClass("PresenceOrderedRoot");
    root.createProperty("k", PropertyType.INTEGER).createIndex(
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    for (int i = 0; i < 120; i++) {
      var vertex = graph.addVertex(T.label, "PresenceOrderedRoot");
      if (i % 3 != 0) {
        vertex.property("k", i);
      }
    }
    graph.tx().commit();
    session.begin();
    try {
      var statement = parse("MATCH {class:PresenceOrderedRoot, as:s}"
          + " RETURN s.k AS value ORDER BY s.k LIMIT 10");
      var pattern = new Pattern();
      statement.getMatchExpressions().forEach(pattern::addExpression);
      var inputs = MatchPlanInputs.builder(pattern)
          .aliasClasses(Map.of("s", "PresenceOrderedRoot"))
          .aliasFilters(Map.of("s", promoted()))
          .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
          .returnNestedProjections(statement.getReturnNestedProjections())
          .orderBy(statement.getOrderBy()).limit(statement.getLimit());
      var context = new BasicCommandContext(session);
      var promotedPlanner = new ObservedPlanner(
          inputs.promotedPresenceKeys(Map.of("s", Set.of("k"))).build());
      var ordinaryPlanner = new ObservedPlanner(inputs.promotedPresenceKeys(Map.of()).build());
      var plan = promotedPlanner.createExecutionPlan(context, false, false);
      var reference = ordinaryPlanner.createExecutionPlan(context, false, false);
      try {
        assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM INDEX VALUES")
            .contains("PresenceOrderedRoot.k").doesNotContain("+ ORDER BY")
            .isEqualTo(reference.prettyPrint(0, 2));
        System.out.println("G5 root planner seam\n" + plan.prettyPrint(0, 2));
      } finally {
        plan.close();
        reference.close();
      }
    } finally {
      session.rollback();
    }
  }

  /** The filtered ordered-scan cost gate reads stripped source filters, not promotion origin. */
  @Test
  public void indexOrderCostGate_usesUnfilteredSourceAdjustment() throws Exception {
    seed("Cost", 240, 150, true);
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      var filters = Map.of("s", promoted());
      var classes = Map.of("s", "SourceCost", "t", "TargetCost");
      var index =
          org.mockito.Mockito.mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
      var method = java.util.Arrays.stream(IndexOrderedPlanner.class.getDeclaredMethods())
          .filter(candidate -> candidate.getName().equals("isFilteredScanLikelyWorthwhile"))
          .findFirst().orElseThrow();
      method.setAccessible(true);
      boolean distinguished = false;
      for (var limit : List.of(1, 10, 100)) {
        var statement = parse("MATCH {class:SourceCost,as:s}.out('LinkCost'){as:t}"
            + " RETURN t.k AS value ORDER BY t.k LIMIT " + limit);
        var planner = new IndexOrderedPlanner(null, classes, filters, Map.of(), java.util.Set.of(),
            statement.getOrderBy(), null, statement.getLimit(), statement.getReturnItems(),
            statement.getReturnAliases(), false, false, false, false, false);
        var order = statement.getOrderBy().getItems().getFirst();
        for (var size : List.of(100L, 1000L, 10000L, 1000000L)) {
          org.mockito.Mockito.when(index.size(session)).thenReturn(size);
          Object[] args = {"s", index, order, Map.of("s", 300L), session, context,
              List.of(), null, false};
          planner.estimateFilters(Map.of());
          var expected = method.invoke(planner, args);
          planner.estimateFilters(estimates(filters));
          assertThat(method.invoke(planner, args)).isEqualTo(expected);
          planner.estimateFilters(filters);
          distinguished |= !method.invoke(planner, args).equals(expected);
        }
      }
      assertThat(distinguished).as("ordinary presence takes the filtered cost branch").isTrue();
    } finally {
      session.rollback();
    }
  }

  /**
   * An upstream hop constrains the ordered source, so both plans enter the filtered cost gate.
   * Actual index detection must narrow V to its LINK class before downstream estimates run.
   */
  @Test
  public void orderedHop_keepsStrippedCostGateAndLaterLinkEstimates() throws Exception {
    seed("Ordered", 1000, 150, true);
    session.createVertexClass("OrderedRoot");
    session.createEdgeClass("OrderedStart");
    var root = graph.addVertex(T.label, "OrderedRoot");
    graph.traversal().V().hasLabel("SourceOrdered").toList().forEach(source -> {
      source.property("k", 1);
      root.addEdge("OrderedStart", source);
    });
    graph.tx().commit();
    session.getMetadata().getSchema().getClass("TargetOrdered")
        .createProperty("k", PropertyType.INTEGER).createIndex(
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      boolean distinguished = false;
      for (var limit : List.of(1, 10, 100, 300, 500, 1000)) {
        var statement = parse("MATCH {class:OrderedRoot,as:u}.out('OrderedStart')"
            + "{class:SourceOrdered,as:s}.out('LinkOrdered'){as:t}"
            + " RETURN s.k AS source, t.k AS value ORDER BY t.k LIMIT " + limit);
        var pattern = new Pattern();
        statement.getMatchExpressions().forEach(pattern::addExpression);
        var inputs = MatchPlanInputs.builder(pattern)
            .aliasClasses(Map.of("u", "OrderedRoot", "s", "SourceOrdered", "t", "V"))
            .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
            .returnNestedProjections(statement.getReturnNestedProjections())
            .orderBy(statement.getOrderBy()).limit(statement.getLimit());
        var promotedPlanner = new ObservedPlanner(inputs.aliasFilters(
            Map.of("s", promoted(), "t", promoted()))
            .promotedPresenceKeys(Map.of("s", Set.of("k"), "t", Set.of("k"))).build());
        var reference = new ObservedPlanner(inputs.aliasFilters(Map.of())
            .promotedPresenceKeys(Map.of()).build());
        // Keep target estimates neutral in the control. Only source presence enters its cost gate.
        var ordinary = new ObservedPlanner(inputs.aliasFilters(
            Map.of("s", promoted(), "t", promoted()))
            .promotedPresenceKeys(Map.of("t", Set.of("k"))).build());
        var plan = promotedPlanner.createExecutionPlan(context, false, false);
        var referencePlan = reference.createExecutionPlan(context, false, false);
        var ordinaryPlan = ordinary.createExecutionPlan(context, false, false);
        try {
          var classesField = MatchExecutionPlanner.class.getDeclaredField("aliasClasses");
          classesField.setAccessible(true);
          @SuppressWarnings("unchecked")
          var classes = (Map<String, String>) classesField.get(promotedPlanner);
          assertThat(classes).containsEntry("t", "TargetOrdered");
          var filtersField = MatchExecutionPlanner.class.getDeclaredField("estimateFilters");
          filtersField.setAccessible(true);
          @SuppressWarnings("unchecked")
          var filters = (Map<String, SQLWhereClause>) filtersField.get(promotedPlanner);
          assertThat(filters.get("s")).isNull();
          assertThat(filters.get("t")).isNull();
          assertThat(promotedPlanner.estimates).isEqualTo(reference.estimates);
          assertThat(promotedPlanner.schedule).isEqualTo(reference.schedule)
              .containsExactly("u->s", "s->t");
          boolean ordered = plan.prettyPrint(0, 2).contains("INDEX ORDERED MATCH");
          assertThat(ordered).as("ordered candidate at limit %s", limit)
              .isEqualTo(referencePlan.prettyPrint(0, 2).contains("INDEX ORDERED MATCH"));
          distinguished |= ordered != ordinaryPlan.prettyPrint(0, 2)
              .contains("INDEX ORDERED MATCH");
          var not = parse("MATCH {as:t} RETURN t").getMatchExpressions().getFirst();
          assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(
              not, classes, filters, Map.of(), context)).isEqualTo(
                  MatchExecutionPlanner.estimateNotPatternCardinality(
                      not, classes, Map.of(), Map.of(), context));
          assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern,
              promotedPlanner.edges, promotedPlanner.estimates, classes, filters, context))
              .isEqualTo(MatchExecutionPlanner.estimateDetachedOuterRows(pattern,
                  reference.edges, reference.estimates, classes, Map.of(), context));
          var rows = new java.util.HashMap<>(promotedPlanner.estimates);
          var referenceRows = new java.util.HashMap<>(reference.estimates);
          invoke("stampForecastFor", promotedPlanner, promotedPlanner.edges.getLast(), "s", "t",
              rows, new java.util.HashMap<>(rows), new java.util.HashMap<>(), session);
          invoke("stampForecastFor", reference, reference.edges.getLast(), "s", "t",
              referenceRows, new java.util.HashMap<>(referenceRows), new java.util.HashMap<>(),
              session);
          assertThat(rows).isEqualTo(referenceRows);
          System.out.println("G5 ordered hop limit=" + limit + " promoted=" + ordered
              + " ordinary=" + ordinaryPlan.prettyPrint(0, 2).contains("INDEX ORDERED MATCH"));
        } finally {
          plan.close();
          referencePlan.close();
          ordinaryPlan.close();
        }
      }
      assertThat(distinguished).as("source presence must distinguish the cost-gate control")
          .isTrue();
    } finally {
      session.rollback();
    }
  }

  /** Statement paths retain executable presence and the same prefetch/class-scan plan. */
  @Test
  public void sqlAndGqlPresence_keepStatementPlans() {
    session.createVertexClass("StatementPresence");
    graph.addVertex(T.label, "StatementPresence", "k", 7);
    graph.addVertex(T.label, "StatementPresence");
    graph.tx().commit();
    session.begin();
    try {
      var context = new BasicCommandContext(session);
      var sql = new MatchExecutionPlanner(parse("MATCH {class:StatementPresence, as:s,"
          + "where:(k IS DEFINED)} RETURN $elements"))
          .createExecutionPlan(context, false, false);
      var filter = com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter
          .fromGqlNode("s", "StatementPresence");
      filter.setFilter(promoted());
      var gql = new com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement(
          List.of(filter)).createExecutionPlan(
              new com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext(session),
              false);
      try {
        for (var plan : List.of(sql.prettyPrint(0, 2), gql.prettyPrint(0, 2))) {
          assertThat(plan).contains("PREFETCH s", "FETCH FROM CLASS StatementPresence",
              "k is defined");
          System.out.println("G5 statement presence\n" + plan);
        }
      } finally {
        sql.close();
        gql.close();
      }
    } finally {
      session.rollback();
    }
  }

  /** Boolean probe hash costing receives the stripped view and keeps executable presence. */
  @Test
  public void booleanProbe_usesSharedEstimateFilters() throws Exception {
    seed("Boolean", 240, 150, true);
    session.begin();
    try {
      var statement = parse("MATCH {class:SourceBoolean,as:s} RETURN s");
      var detached = parse("MATCH {as:s}.out('LinkBoolean')"
          + "{as:t,where:(k IS DEFINED)} RETURN s").getMatchExpressions().getFirst();
      var pattern = new Pattern();
      statement.getMatchExpressions().forEach(pattern::addExpression);
      var inputs = MatchPlanInputs.builder(pattern)
          .aliasClasses(Map.of("s", "SourceBoolean", "t", "TargetBoolean"))
          .aliasFilters(Map.of("s", promoted(), "t", promoted()))
          .promotedPresenceKeys(Map.of("s", Set.of("k"), "t", Set.of("k")))
          .booleanCheck(new MatchBooleanCheck.Probe(
              parse("MATCH {as:s}.out('LinkBoolean'){as:t} RETURN s")
                  .getMatchExpressions().getFirst()))
          .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
          .returnNestedProjections(statement.getReturnNestedProjections()).build();
      var planner = new MatchExecutionPlanner(inputs);
      var context = new BasicCommandContext(session);
      var plan = planner.createExecutionPlan(context, false, false);
      try {
        var field = MatchExecutionPlanner.class.getDeclaredField("estimateFilters");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var view = (Map<String, SQLWhereClause>) field.get(planner);
        assertThat(view).containsEntry("s", null).containsEntry("t", null);
        assertThat(invoke("estimateDetachedWalk", null, detached, inputs.aliasClasses(),
            view, context)).isEqualTo(invoke("estimateDetachedWalk", null,
                parse("MATCH {as:s}.out('LinkBoolean'){as:t} RETURN s")
                    .getMatchExpressions().getFirst(),
                inputs.aliasClasses(), Map.of(), context));
        try (var calls = org.mockito.Mockito.mockStatic(MatchExecutionPlanner.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
          var tree = planner.buildBooleanCheck(inputs.booleanCheck(), context,
              java.util.OptionalLong.of(300), false);
          try {
            calls.verify(() -> MatchExecutionPlanner.canUseHashJoin(
                org.mockito.ArgumentMatchers.any(
                    com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression.class),
                org.mockito.ArgumentMatchers.eq(inputs.aliasClasses()),
                org.mockito.ArgumentMatchers.same(view),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                org.mockito.ArgumentMatchers.same(context),
                org.mockito.ArgumentMatchers.same(pattern),
                org.mockito.ArgumentMatchers.eq(java.util.OptionalLong.of(300)),
                org.mockito.ArgumentMatchers.eq(new MatchExecutionPlanner.DetachedSlice(
                    java.util.OptionalLong.empty(), false))));
          } finally {
            tree.close();
          }
        }
        assertThat(inputs.aliasFilters().get("t").toString()).contains("is defined");
      } finally {
        plan.close();
      }
    } finally {
      session.rollback();
    }
  }

  private void seed(String suffix, int targets, int selected, boolean dense) {
    var source = session.createVertexClass("Source" + suffix);
    // The unindexed equality estimates half the source class. This deliberately places the
    // source between the target's presence guess and its filter-absent class count.
    source.createProperty("group", PropertyType.STRING);
    var target = session.createVertexClass("Target" + suffix);
    var link = session.createEdgeClass("Link" + suffix);
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(target);
    var vertices = new java.util.ArrayList<org.apache.tinkerpop.gremlin.structure.Vertex>();
    for (int i = 0; i < targets; i++) {
      var vertex = graph.addVertex(T.label, "Target" + suffix);
      if (dense || i == targets - 1) {
        vertex.property("k", i);
      }
      vertices.add(vertex);
    }
    for (int i = 0; i < selected * 2; i++) {
      graph.addVertex(T.label, "Source" + suffix, "group", i < selected ? "first" : "second")
          .addEdge("Link" + suffix, vertices.get(i % targets));
    }
    graph.tx().commit();
  }

  private static SQLWhereClause promoted() {
    return WHERE.wrap(WHERE.isDefined("k"));
  }

  private static Map<String, SQLWhereClause> estimates(Map<String, SQLWhereClause> filters) {
    return PresenceEstimateFilters.build(filters, Map.of("s", Set.of("k"), "t", Set.of("k")));
  }

  private SQLWhereClause sourceFilter() {
    return parse("MATCH {class:V, as:s, where:(`group` = 'first')} RETURN s")
        .getMatchExpressions().getFirst().getOrigin().getFilter();
  }

  private SQLMatchStatement parse(String sql) {
    return (SQLMatchStatement) SQLEngine.parse(sql, session);
  }

  private static Object invoke(String name, Object receiver, Object... args) throws Exception {
    var method = java.util.Arrays.stream(MatchExecutionPlanner.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(name)
            && candidate.getParameterCount() == args.length)
        .findFirst().orElseThrow();
    method.setAccessible(true);
    return method.invoke(receiver, args);
  }

  private static final class ObservedPlanner extends MatchExecutionPlanner {
    private Map<String, Long> estimates;
    private Set<String> prefetch;
    private List<String> schedule;
    private List<EdgeTraversal> edges;

    private ObservedPlanner(MatchPlanInputs inputs) {
      super(inputs);
    }

    @Override
    void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
        List<EdgeTraversal> edges) {
      this.estimates = Map.copyOf(estimates);
      this.prefetch = Set.copyOf(prefetch);
      this.edges = List.copyOf(edges);
      this.schedule = edges.stream().map(e -> (e.out ? e.edge.out.alias : e.edge.in.alias)
          + "->" + (e.out ? e.edge.in.alias : e.edge.out.alias)).toList();
    }
  }
}
