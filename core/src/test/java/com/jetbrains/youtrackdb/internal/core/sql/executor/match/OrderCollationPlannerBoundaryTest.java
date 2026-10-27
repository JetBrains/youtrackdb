package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Plan and cost boundaries for declared ordering with no applicable index. */
public class OrderCollationPlannerBoundaryTest extends GraphBaseTest {

  /** A declared comparison uses the default path's LINK prefetch and NOT build class. */
  @Test
  public void declaredComparison_prefetchesLinkClassAndNarrowsNotBuild() throws Exception {
    seed();
    session.begin();
    var context = new BasicCommandContext(session);
    var positive = parse("MATCH {class:Source, as:s}.out('Link'){as:m}"
        + " RETURN m.name AS name ORDER BY m.name LIMIT 10");
    var check = parse("MATCH {as:m}.out('Block'){as:b} RETURN m");
    var pattern = new Pattern();
    positive.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("s", "Source", "m", "V"))
        .orderCollationClasses(new java.util.HashMap<>(Map.of("m", List.of("Named"))))
        .notMatchExpressions(check.getMatchExpressions())
        .returnItems(positive.getReturnItems())
        .returnAliases(positive.getReturnAliases())
        .returnNestedProjections(positive.getReturnNestedProjections())
        .orderBy(positive.getOrderBy()).limit(positive.getLimit()).build();
    var fingerprint = fingerprint(inputs);
    var planner = new ObservedPlanner(inputs);
    var plan = planner.createExecutionPlan(context, false, false);
    // Source has no name declaration, so only comparison metadata changes in the reference.
    inputs.orderCollationClasses().put("m", List.of("Source"));
    var reference = new ObservedPlanner(inputs);
    var referencePlan = reference.createExecutionPlan(context, false, false);
    assertThat(inputs.orderBy().getItems().getFirst().getDeclaredCollate()).isNull();
    assertThat(planner.estimates).isEqualTo(reference.estimates)
        .containsEntry("m", session.countClass("V") + 1);
    assertThat(planner.prefetch).isEqualTo(reference.prefetch).containsExactlyInAnyOrder("s", "m");
    assertThat(planner.schedule).isEqualTo(reference.schedule).containsExactly("s->m");
    inputs.orderCollationClasses().put("m", List.of("Named"));
    assertThat(fingerprint(inputs)).isEqualTo(fingerprint);
    referencePlan.close();
    assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM CLASS Named")
        .doesNotContain("FETCH FROM CLASS V\n");
    var classesField = MatchExecutionPlanner.class.getDeclaredField("aliasClasses");
    classesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var classes = (Map<String, String>) classesField.get(planner);
    assertThat(classes).containsEntry("m", "Named");
    assertThat(inputs.aliasClasses()).containsEntry("m", "V");
    var before = planner.estimates;
    var expression = check.getMatchExpressions().getFirst();
    var schedule = planner.edges;
    var outerRows = MatchExecutionPlanner.estimateDetachedOuterRows(
        pattern, schedule, before, classes, Map.of(), context);
    assertThat(outerRows).isPresent();
    assertThat(outerRows).isEqualTo(MatchExecutionPlanner.estimateDetachedOuterRows(
        pattern, schedule, before, Map.of("s", "Source", "m", "Named"), Map.of(), context));
    var cardinality = MatchExecutionPlanner.estimateNotPatternCardinality(
        expression, classes, Map.of(), Map.of(), context);
    assertThat(cardinality).isEqualTo(MatchExecutionPlanner.estimateNotPatternCardinality(
        expression, Map.of("s", "Source", "m", "Named"), Map.of(), Map.of(), context));
    assertThat(cardinality).isLessThan(MatchExecutionPlanner.estimateNotPatternCardinality(
        expression, inputs.aliasClasses(), Map.of(), Map.of(), context));
    var stream = plan.start();
    try {
      assertThat(stream.stream(context).map(row -> (String) row.getProperty("name")).toList())
          .containsExactly("alice");
    } finally {
      stream.close(context);
      plan.close();
      session.rollback();
    }
  }

  /** SQL NOT estimates keep bothV unknown even when both edge endpoints share an indexed class. */
  @Test
  public void sqlNotBothV_keepsUnknownEstimateClassAndHeuristicCardinality() throws Exception {
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.STRING);
    named.createIndex("Named.name",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "name");
    var peer = session.createEdgeClass("Peer");
    peer.getProperty("in").setLinkedClass(named);
    peer.getProperty("out").setLinkedClass(named);
    var alice = graph.addVertex(T.label, "Named", "name", "alice");
    for (int i = 0; i < 19; i++) {
      alice.addEdge("Peer", graph.addVertex(T.label, "Named", "name", "n" + i));
    }
    graph.tx().commit();
    session.begin();
    try {
      var statement = parse("MATCH {class:Named, as:a}, NOT {as:a}.bothE('Peer')"
          + "{}.bothV(){where:(name='alice')} RETURN a");
      var expression = statement.getNotMatchExpressions().getFirst();
      var endpoint = expression.getItems().getLast();
      var context = new BasicCommandContext(session);
      var classes = Map.of("a", "Named");
      var targetClass = MatchExecutionPlanner.class.getDeclaredMethod("estimateTargetClass",
          com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem.class,
          String.class, Map.class,
          com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
      targetClass.setAccessible(true);
      assertThat(targetClass.invoke(null, endpoint, "Peer", classes, context)).isNull();
      assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(
          endpoint.getMethod(), "Peer", context)).isNull();
      // The unknown target uses the SQL heuristic, not the Named.name index selectivity.
      var selectivityMethod = MatchExecutionPlanner.class.getDeclaredMethod(
          "estimateFilterSelectivity",
          com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause.class,
          String.class, com.jetbrains.youtrackdb.internal.core.command.CommandContext.class);
      selectivityMethod.setAccessible(true);
      assertThat(selectivityMethod.invoke(null, endpoint.getFilter().getFilter(), null, context))
          .isEqualTo(0.1);
      assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(
          expression, classes, Map.of(), Map.of(), context)).isEqualTo(42);
    } finally {
      session.rollback();
    }
  }

  /** SQL implicit declarations and explicit COLLATE preserve plain and shared-origin prefetch. */
  @Test
  public void sqlCollations_keepOriginalVPrefetchWithoutWhileGuard() {
    seed();
    for (var suffix : List.of("", ", {class:V, as:m}.out('Block')"
        + "{as:b, while:($depth<2), where:($depth=1)}")) {
      for (var collate : List.of("", " COLLATE ci")) {
        var sql = "MATCH {class:Source, as:s}.out('Link'){class:V, as:m}"
            + suffix + " RETURN m.name AS name ORDER BY m.name" + collate + " LIMIT 10";
        try (var result = session.query(sql)) {
          var plan = result.getExecutionPlan().prettyPrint(0, 2);
          if (suffix.isEmpty()) {
            assertThat(plan).contains("PREFETCH s").doesNotContain("PREFETCH m",
                "FETCH FROM CLASS Named");
          } else {
            assertThat(plan).contains("FETCH FROM CLASS V");
          }
          assertThat(result.stream().map(row -> (String) row.getProperty("name")).toList())
              .containsExactlyElementsOf(suffix.isEmpty()
                  ? List.of("alice", "Zebra") : List.of("Zebra"));
        }
      }
    }
  }

  /** Plain explicit COLLATE returns before LINK inference even on the additive planner path. */
  @Test
  public void explicitCollatePlainHop_keepsVPrefetch() {
    seed();
    session.begin();
    var statement = parse("MATCH {class:Source, as:s}.out('Link'){class:V, as:m}"
        + " RETURN m.name ORDER BY m.name COLLATE ci LIMIT 10");
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(Map.of("s", "Source", "m", "V"))
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .orderBy(statement.getOrderBy()).limit(statement.getLimit()).build();
    var plan = new MatchExecutionPlanner(inputs)
        .createExecutionPlan(new BasicCommandContext(session), false, false);
    try {
      assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM CLASS V")
          .doesNotContain("FETCH FROM CLASS Named");
    } finally {
      plan.close();
      session.rollback();
    }
  }

  /** Captures values from the real run, before index detection can narrow alias classes. */
  private static final class ObservedPlanner extends MatchExecutionPlanner {
    private Map<String, Long> estimates;
    private java.util.Set<String> prefetch;
    private List<String> schedule;
    private List<EdgeTraversal> edges;

    private ObservedPlanner(MatchPlanInputs inputs) {
      super(inputs);
    }

    @Override
    void observeRootPlanning(Map<String, Long> estimates, java.util.Set<String> prefetch,
        List<EdgeTraversal> edges) {
      this.estimates = Map.copyOf(estimates);
      this.prefetch = java.util.Set.copyOf(prefetch);
      this.edges = List.copyOf(edges);
      this.schedule = edges.stream().map(e -> (e.out ? e.edge.out.alias : e.edge.in.alias)
          + "->" + (e.out ? e.edge.in.alias : e.edge.out.alias)).toList();
    }
  }

  /** Explicit COLLATE keeps a WHILE-zone V alias and its collection-membership filter. */
  @Test
  public void explicitCollateWhile_keepsVPrefetchAndCollectionFilter() {
    seed();
    var sql = "MATCH {class:Source, as:s}.out('Link')"
        + "{class:V, as:m, while:($depth<2), where:($depth=1)}"
        + " RETURN m.name AS name ORDER BY m.name COLLATE ci LIMIT 10";
    try (var result = session.query(sql)) {
      var plan = result.getExecutionPlan().prettyPrint(0, 2);
      assertThat(plan).contains("FETCH FROM CLASS V", "FILTER");
      assertThat(result.stream().map(row -> (String) row.getProperty("name")).toList())
          .containsExactly("alice", "Zebra");
    }
  }

  /** Unknown labels and incomplete LINK declarations retain the hierarchy fallback. */
  @Test
  public void sharedClassRule_handlesUnknownClassesAndAncestorLabels() {
    var schema = session.getMetadata().getSchema();
    var parent = session.createVertexClass("Parent");
    schema.createClass("Child", parent);
    session.createEdgeClass("EdgeLabel");
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .labelClass(null, schema::getClass)).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .labelClass(List.of(), schema::getClass)).isNull();
    for (var labels : List.of(List.of("Unknown"), List.of("EdgeLabel"),
        List.of("Parent", "Unknown"), List.of("Parent", "EdgeLabel"))) {
      assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
          .labelClass(labels, schema::getClass)).isNull();
    }
    for (var labels : List.of(List.of("Parent", "Parent"), List.of("Parent", "Child"),
        List.of("Child", "Parent"))) {
      assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
          .labelClass(labels, schema::getClass)).isEqualTo("Parent");
    }
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .inferHopClass(schema, "unsupported", null, null)).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .linkedClass(null, "EdgeLabel", "in")).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .linkedClass(schema, null, "in")).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .linkedClass(schema, "Unknown", "in")).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .linkedClass(schema, "EdgeLabel", "missing")).isNull();
    assertThat(com.jetbrains.youtrackdb.internal.core.metadata.schema.OrderClassResolver
        .linkedClass(schema, "EdgeLabel", "in")).isNull();
  }

  private String fingerprint(MatchPlanInputs inputs) throws Exception {
    var shaping = com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping.NONE;
    var method = Class.forName("com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy."
        + "GremlinPlanFingerprint").getDeclaredMethod("fingerprint", MatchPlanInputs.class,
            shaping.getClass());
    method.setAccessible(true);
    return (String) method.invoke(null, inputs, shaping);
  }

  private SQLMatchStatement parse(String sql) {
    return (SQLMatchStatement) SQLEngine.parse(sql, session);
  }

  /** Extra unrelated vertices make the V and Named cardinalities observably different. */
  private void seed() {
    var source = session.createVertexClass("Source");
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.STRING).setCollate("ci");
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(named);
    var s = graph.addVertex(T.label, "Source");
    var alice = graph.addVertex(T.label, "Named", "name", "alice");
    var zebra = graph.addVertex(T.label, "Named", "name", "Zebra");
    s.addEdge("Link", alice);
    s.addEdge("Link", zebra);
    zebra.addEdge("Block", graph.addVertex(T.label, "Marker"));
    for (int i = 0; i < 12; i++) {
      graph.addVertex(T.label, "Unrelated");
    }
    graph.tx().commit();
  }
}
