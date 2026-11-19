package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Planner-level assertions distinguish a density decision from the G5 row-count sorter. */
public class PresenceRootPlanningTest extends GraphBaseTest {

  /** Dense mandatory keys stay forward while sparse indexed keys reverse through a class scan. */
  @Test
  public void denseAndSparse_chooseDifferentRootsWithoutIndexAcquisition() throws Exception {
    for (var sparse : List.of(false, true)) {
      seed(sparse ? "Sparse" : "Dense", sparse);
      session.begin();
      var planner = planner(sparse ? "Sparse" : "Dense", null);
      var plan = planner.createExecutionPlan(new BasicCommandContext(session), false, false);
      try {
        var records = planner.presenceDensities();
        assertThat(records.get("t").applied()).isTrue();
        assertThat(planner.schedule).containsExactly(sparse ? "t->s" : "s->t");
        assertThat(planner.prefetch).doesNotContain("t");
        var text = plan.prettyPrint(0, 2);
        assertThat(text).doesNotContain("FETCH FROM INDEX Target");
        if (sparse) {
          assertThat(text).contains("FETCH FROM CLASS TargetSparse");
        }
      } finally {
        plan.close();
        session.rollback();
      }
      var traversal = graph.traversal().V().hasLabel("Source" + (sparse ? "Sparse" : "Dense"))
          .has("selected", true).out("Link" + (sparse ? "Sparse" : "Dense"))
          .hasLabel("Target" + (sparse ? "Sparse" : "Dense")).values("k").limit(10000);
      assertThat(traversal.toList()).hasSize(sparse ? 20 : 450);
      graph.tx().rollback();
    }
  }

  /** No useful declaration keeps G5, including the source-first schedule and unscaled estimates. */
  @Test
  public void unavailableDensity_keepsG5() throws Exception {
    seed("Fallback", true);
    session.getMetadata().getSchema().getClass("TargetFallback").getProperty("k")
        .setNotNull(false);
    session.begin();
    var planner = planner("Fallback", null);
    var plan = planner.createExecutionPlan(new BasicCommandContext(session), false, false);
    try {
      assertThat(planner.presenceDensities().get("t").applied()).isFalse();
      assertThat(planner.schedule).containsExactly("s->t");
      assertThat(planner.estimates).containsEntry("t", 501L);
    } finally {
      plan.close();
      session.rollback();
    }
  }

  /** A full sort cannot apply the streaming demand discount, but keeps correct survivor values. */
  @Test
  public void explicitSort_doesNotUseStreamingDemand() throws Exception {
    seed("Sorted", true);
    session.begin();
    var planner = planner("Sorted", "ORDER BY t.k COLLATE ci LIMIT 10");
    try (var costs = org.mockito.Mockito.mockStatic(PresenceRootCost.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      var context = new BasicCommandContext(session);
      var plan = planner.createExecutionPlan(context, false, false);
      try {
        costs.verify(() -> PresenceRootCost.compare(1000, 500, 500, 1000, 0.02, false, -1));
        assertThat(planner.schedule).containsExactly("t->s");
        assertThat(plan.prettyPrint(0, 2)).contains("ORDER BY");
        var values = new java.util.ArrayList<Integer>();
        var stream = plan.start();
        try {
          while (stream.hasNext(context)) {
            values.add(stream.next(context).getProperty("value"));
          }
        } finally {
          stream.close(context);
        }
        assertThat(values).containsExactly(0, 0, 1, 1, 2, 2, 3, 3, 4, 4);
      } finally {
        plan.close();
        session.rollback();
      }
    }
  }

  /** A streaming range requests the skipped prefix plus the returned rows, not just its limit. */
  @Test
  public void streamingRange_includesSkippedPrefixInDemand() {
    seed("Skipped", true);
    session.begin();
    try (var costs = org.mockito.Mockito.mockStatic(PresenceRootCost.class,
        org.mockito.Mockito.CALLS_REAL_METHODS)) {
      var plan = planner("Skipped", "SKIP 7 LIMIT 3")
          .createExecutionPlan(new BasicCommandContext(session), false, false);
      try {
        costs.verify(() -> PresenceRootCost.compare(1000, 500, 500, 1000, 0.02, false, 10));
      } finally {
        plan.close();
      }
    } finally {
      session.rollback();
    }
  }

  /** Existing ordered-index detection on the forward schedule takes priority over G7. */
  @Test
  public void forwardIndexOrderCandidate_keepsG5() {
    seed("IndexOrder", true);
    session.begin();
    boolean candidate = false;
    try {
      for (int limit : new int[] {1, 10, 100, 1000}) {
        var planner = planner("IndexOrder", "ORDER BY t.k LIMIT " + limit);
        var plan = planner.createExecutionPlan(new BasicCommandContext(session), false, false);
        try {
          if (plan.prettyPrint(0, 2).contains("INDEX ORDERED MATCH")) {
            candidate = true;
            assertThat(planner.presenceDensities().get("t").applied()).isFalse();
            assertThat(planner.estimates).containsEntry("t", 501L);
            assertThat(planner.schedule).containsExactly("s->t");
          }
        } finally {
          plan.close();
        }
      }
      assertThat(candidate).as("fixture must exercise the index-order guard").isTrue();
    } finally {
      session.rollback();
    }
  }

  /** Unsupported shape gates keep the same stripped view even with useful target statistics. */
  @Test
  public void unsupportedPatterns_neverApplyDensity() throws Exception {
    seed("Unsupported", true);
    session.begin();
    try {
      var base = planner("Unsupported", null);
      var patternField = MatchExecutionPlanner.class.getDeclaredField("pattern");
      patternField.setAccessible(true);
      var pattern = (Pattern) patternField.get(base);
      var edge = pattern.aliasToNode.get("s").out.iterator().next();
      var choose = MatchExecutionPlanner.class.getDeclaredMethod("choosePresenceRoot",
          com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
      choose.setAccessible(true);
      for (var field : List.of("returnDistinct", "groupBy", "unwind", "notMatchExpressions",
          "existsMatchExpressions", "booleanCheck", "aliasPinnedRids")) {
        var planner = planner("Unsupported", null);
        var member = MatchExecutionPlanner.class.getDeclaredField(field);
        member.setAccessible(true);
        Object value = switch (field) {
          case "returnDistinct" -> true;
          case "booleanCheck" -> new MatchBooleanCheck.And(List.of());
          case "groupBy" -> new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy(-1);
          case "unwind" -> new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind(-1);
          case "aliasPinnedRids" -> Map.of("s", List.of());
          default -> List.of(((SQLMatchStatement) SQLEngine.parse("MATCH {as:s} RETURN s",
              session)).getMatchExpressions().getFirst());
        };
        member.set(planner, value);
        choose.invoke(planner, new BasicCommandContext(session));
        assertThat(planner.presenceDensities().get("t").useful()).as(field).isTrue();
        assertThat(planner.presenceDensities().get("t").applied()).as(field).isFalse();
      }
      // Edge count is checked before any narrow comparison or index-order detection.
      pattern.numOfEdges = 2;
      choose.invoke(base, new BasicCommandContext(session));
      assertThat(base.presenceDensities().get("t").applied()).isFalse();
      assertThat(edge).isNotNull();
      for (var hop : List.of(".both('LinkUnsupported')", ".out()",
          ".out('LinkUnsupported','E')", ".out('LinkUnsupported'){as:t,optional:true}",
          ".out('LinkUnsupported'){as:t,while:($depth < 2)}")) {
        var statement = (SQLMatchStatement) SQLEngine.parse("MATCH {as:s}"
            + hop + (hop.contains("{as:t") ? "" : "{as:t}") + " RETURN t", session);
        var altered = new Pattern();
        statement.getMatchExpressions().forEach(altered::addExpression);
        var planner = planner("Unsupported", null);
        patternField.set(planner, altered);
        var estimatesField = MatchExecutionPlanner.class.getDeclaredField("estimateFilters");
        estimatesField.setAccessible(true);
        estimatesField.set(planner, new HashMap<>());
        choose.invoke(planner, new BasicCommandContext(session));
        assertThat(planner.presenceDensities().get("t").useful()).as(hop).isTrue();
        assertThat(planner.presenceDensities().get("t").applied()).as(hop).isFalse();
      }
    } finally {
      session.rollback();
    }
  }

  /** The real translator retains its original V decision after ordered LINK inference. */
  @Test
  public void translatedOrderedHop_keepsPreRewriteDensityDecision() throws Exception {
    seed("Rewrite", true);
    session.begin();
    var traversal = graph.traversal().V().hasLabel("SourceRewrite").out("LinkRewrite")
        .values("k").limit(10);
    try (var reads = org.mockito.Mockito.mockStatic(PresenceDensity.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
        var planner = PresencePlanTestSupport.plan(traversal.asAdmin(), session, true)) {
      var target = planner.inputs.promotedPresenceKeys().keySet().iterator().next();
      assertThat(planner.inputs.aliasClasses()).containsEntry(target, "V");
      reads.verify(() -> PresenceDensity.read(target, "V", Set.of("k"), session));
      reads.verify(() -> PresenceDensity.read(target, "TargetRewrite", Set.of("k"), session),
          org.mockito.Mockito.never());
      var recorded = planner.presenceDensities().get(target);
      assertThat(recorded.candidateClass()).isEqualTo("V");
      assertThat(recorded.source()).isEqualTo(PresenceDensity.Source.NO_DECLARATION);
      assertThat(recorded.applied()).isFalse();
      var classesField = MatchExecutionPlanner.class.getDeclaredField("aliasClasses");
      classesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var classes = (Map<String, String>) classesField.get(planner);
      assertThat(classes).containsEntry(target, "TargetRewrite");
      var filtersField = MatchExecutionPlanner.class.getDeclaredField("estimateFilters");
      filtersField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var filters = (Map<String,
          com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause>) filtersField
              .get(planner);
      assertThat(filters.get(target)).isNull();
      var context = new BasicCommandContext(session);
      var edge = planner.edges.getFirst();
      assertThat(MatchExecutionPlanner.targetSelectivityFactor(target, edge.edge, true, classes,
          filters, planner.estimates, null, session)).isEqualTo(
              MatchExecutionPlanner.targetSelectivityFactor(target, edge.edge, true, classes,
                  Map.of(), planner.estimates, null, session));
      assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(planner.inputs.pattern(),
          planner.edges, planner.estimates, classes, filters, context)).isEqualTo(
              MatchExecutionPlanner.estimateDetachedOuterRows(planner.inputs.pattern(),
                  planner.edges, planner.estimates, classes, Map.of(), context));
      assertThat(MatchExecutionPlanner.applyTargetSelectivity(7, target, edge.edge, true,
          classes, filters, planner.estimates, session)).isEqualTo(
              MatchExecutionPlanner.applyTargetSelectivity(7, target, edge.edge, true,
                  classes, Map.of(), planner.estimates, session));
      var source = edge.edge.out.alias;
      for (var name : List.of("estimateAliasCardinality", "estimateBranchCardinality",
          "estimateUpstreamCardinality", "estimateBranchFanOut")) {
        Object[] args = switch (name) {
          case "estimateAliasCardinality" ->
              new Object[] {target, classes, filters, Map.of(), context};
          case "estimateBranchCardinality" ->
              new Object[] {source, planner.edges, classes, filters, Map.of(), context};
          case "estimateUpstreamCardinality" ->
              new Object[] {planner.edges, 1, List.of(), classes, filters, Map.of(), context};
          default -> new Object[] {planner.edges, source, classes, filters, context};
        };
        var reference = args.clone();
        for (int i = 0; i < reference.length; i++) {
          if (reference[i] == filters) {
            reference[i] = Map.of();
          }
        }
        assertThat(invoke(name, null, args)).as(name).isEqualTo(invoke(name, null, reference));
      }
      var rows = new HashMap<>(planner.estimates);
      var referenceRows = new HashMap<>(planner.estimates);
      invoke("stampForecastFor", planner, edge, source, target, rows,
          new HashMap<>(planner.estimates), new HashMap<>(), session);
      filtersField.set(planner, Map.of());
      try {
        invoke("stampForecastFor", planner, edge, source, target, referenceRows,
            new HashMap<>(planner.estimates), new HashMap<>(), session);
        assertThat(rows).isEqualTo(referenceRows);
      } finally {
        filtersField.set(planner, filters);
      }
      assertThat(planner.presenceDensities().get(target)).isSameAs(recorded);
      reads.verify(() -> PresenceDensity.read(target, "V", Set.of("k"), session));
      reads.verify(() -> PresenceDensity.read(target, "TargetRewrite", Set.of("k"), session),
          org.mockito.Mockito.never());
    } finally {
      session.rollback();
    }
  }

  private static Object invoke(String name, Object receiver, Object... args) throws Exception {
    var method = java.util.Arrays.stream(MatchExecutionPlanner.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(name)
            && candidate.getParameterCount() == args.length)
        .findFirst().orElseThrow();
    method.setAccessible(true);
    return method.invoke(receiver, args);
  }

  private Observed planner(String suffix, String tail) {
    var statement = (SQLMatchStatement) SQLEngine.parse("MATCH {class:Source" + suffix
        + ",as:s}.out('Link" + suffix + "'){class:Target" + suffix
        + ",as:t} RETURN t.k AS value " + (tail == null ? "" : tail), session);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var where = new MatchWhereBuilder();
    return new Observed(MatchPlanInputs.builder(pattern)
        .aliasClasses(new HashMap<>(Map.of("s", "Source" + suffix, "t", "Target" + suffix)))
        .aliasFilters(Map.of("t", where.wrap(where.isDefined("k")), "s",
            ((SQLMatchStatement) SQLEngine.parse("MATCH {as:s,where:(selected = true)} RETURN s",
                session)).getMatchExpressions().getFirst().getOrigin().getFilter()))
        .promotedPresenceKeys(Map.of("t", Set.of("k")))
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .orderBy(statement.getOrderBy()).limit(statement.getLimit()).skip(statement.getSkip())
        .build());
  }

  private void seed(String suffix, boolean sparse) {
    var source = session.createVertexClass("Source" + suffix);
    source.createProperty("selected", PropertyType.BOOLEAN);
    var target = session.createVertexClass("Target" + suffix);
    var property = target.createProperty("k", PropertyType.INTEGER);
    if (sparse) {
      property.setNotNull(true);
      property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    } else {
      property.setMandatory(true);
    }
    var link = session.createEdgeClass("Link" + suffix);
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(target);
    for (int i = 0; i < 500; i++) {
      var vertex = graph.addVertex(T.label, "Target" + suffix);
      if (!sparse || i < 10) {
        vertex.property("k", i);
      }
      graph.addVertex(T.label, "Source" + suffix, "selected", i < 225)
          .addEdge("Link" + suffix, vertex);
      graph.addVertex(T.label, "Source" + suffix, "selected", i < 225)
          .addEdge("Link" + suffix, vertex);
    }
    graph.tx().commit();
  }

  private static final class Observed extends MatchExecutionPlanner {
    List<String> schedule;
    Set<String> prefetch;
    Map<String, Long> estimates;

    Observed(MatchPlanInputs inputs) {
      super(inputs);
    }

    @Override
    void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
        List<EdgeTraversal> edges) {
      this.estimates = Map.copyOf(estimates);
      this.prefetch = Set.copyOf(prefetch);
      this.schedule = edges.stream().map(edge -> edge.out
          ? edge.edge.out.alias + "->" + edge.edge.in.alias
          : edge.edge.in.alias + "->" + edge.edge.out.alias).toList();
    }
  }
}
