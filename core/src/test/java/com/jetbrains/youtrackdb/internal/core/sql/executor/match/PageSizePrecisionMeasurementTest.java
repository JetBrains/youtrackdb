package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.ProcessPageSize;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Opt-in, threshold-free precision and plan measurement against a real database.
 * Run in separate JVMs with pageSize=4 and pageSize=8 and
 * -Dyoutrackdb.measurePagePrecision=true -Dtest=PageSizePrecisionMeasurementTest.
 */
public class PageSizePrecisionMeasurementTest extends DbTestBase {

  private static final int ROWS = 20_000;

  @BeforeClass
  public static void optIn() {
    assumeTrue(Boolean.getBoolean("youtrackdb.measurePagePrecision"));
  }

  /**
   * Each shape has 20K unique uniform keys and a non-unique skewed case with
   * 4K copies of zero plus 16K distinct tail keys. ANALYZE requests up to 128
   * buckets. Java enumeration supplies ground truth, independently of statistics.
   * SELECT competes against a 4K-row integer index. MATCH uses the same range
   * as its source filter and a sparse target presence filter. No accuracy or
   * plan-choice threshold is imposed. Assertions only validate the experiment.
   */
  @Test
  public void measureFittedHistogramsAndObservedPlans() {
    for (String shape : List.of("integer", "short", "string36", "boundary", "decimal")) {
      for (boolean skew : List.of(false, true)) {
        measure(shape, skew);
      }
    }
  }

  private void measure(String shape, boolean skew) {
    String name = shape + (skew ? "Skew" : "Uniform");
    String source = "Source" + name;
    String target = "Target" + name;
    String edge = "Link" + name;
    String keyIndex = source + "Key";
    String rivalIndex = source + "Rival";
    var schema = session.getMetadata().getSchema();
    var type = switch (shape) {
      case "integer" -> PropertyType.INTEGER;
      case "decimal" -> PropertyType.DECIMAL;
      default -> PropertyType.STRING;
    };
    IntFunction<Object> key = rank -> switch (shape) {
      case "integer" -> rank;
      case "decimal" -> new BigDecimal("1" + "0".repeat(95) + String.format(
          Locale.ROOT, "%05d", rank));
      default -> String.format(Locale.ROOT, "%05d", rank)
          + "x".repeat(switch (shape) {
            case "short" -> 3;
            case "string36" -> 31;
            default -> 249;
          });
    };
    var s = session.createVertexClass(source);
    s.createProperty("k", type);
    s.createProperty("rival", PropertyType.INTEGER);
    s.createIndex(keyIndex, skew ? SchemaClass.INDEX_TYPE.NOTUNIQUE
        : SchemaClass.INDEX_TYPE.UNIQUE, "k");
    s.createIndex(rivalIndex, SchemaClass.INDEX_TYPE.NOTUNIQUE, "rival");
    var t = session.createVertexClass(target);
    t.createProperty("present", PropertyType.INTEGER).setNotNull(true);
    t.createIndex(target + "Presence", SchemaClass.INDEX_TYPE.NOTUNIQUE, "present");
    var e = session.createEdgeClass(edge);
    e.getProperty("out").setLinkedClass(s);
    e.getProperty("in").setLinkedClass(t);

    var keys = new ArrayList<Object>(ROWS);
    session.begin();
    var targets = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex>();
    for (int i = 0; i < 500; i++) {
      var vertex = session.newVertex(target);
      // 146/500 presence density keeps root acquisition and traversal both visible.
      if (i < 146) {
        vertex.setProperty("present", i);
      }
      targets.add(vertex);
    }
    for (int i = 0; i < ROWS; i++) {
      Object value = key.apply(skew ? (i < 4000 ? 0 : i - 3999) : i);
      keys.add(value);
      var vertex = session.newVertex(source);
      vertex.setProperty("k", value);
      vertex.setProperty("rival", i % 5);
      vertex.addEdge(targets.get(i % 500), edge);
    }
    session.commit();
    session.execute("ANALYZE INDEX " + keyIndex).close();
    session.execute("ANALYZE INDEX " + rivalIndex).close();
    session.execute("ANALYZE INDEX " + target + "Presence").close();
    session.begin();
    assertEquals(ROWS, session.countClass(source));

    var index = session.getSharedContext().getIndexManager().getIndex(keyIndex);
    var histogram = index.getHistogram(session);
    var stats = index.getStatistics(session);
    assertNotNull("ANALYZE must publish a fitted histogram", histogram);
    assertNotNull(stats);
    Object probe = key.apply(5432);
    Object low = key.apply(4001);
    Object high = key.apply(7899);
    long exactEq = keys.stream().filter(probe::equals).count();
    long exactRange = keys.stream()
        .filter(value -> compare(value, low) >= 0 && compare(value, high) <= 0).count();
    double estimatedEq = ROWS * SelectivityEstimator.estimateEquality(stats, histogram, probe);
    double estimatedRange = ROWS * SelectivityEstimator.estimateRange(
        stats, histogram, low, high, true, true);
    // Verify parameter typing and the independently enumerated counts against execution.
    assertEquals(exactEq, count(source, "k = ?", probe));
    assertEquals(exactRange, count(source, "k BETWEEN ? AND ?", low, high));
    String eqChoice = choice(source, "k = ? AND rival = 1", keyIndex, rivalIndex, probe);
    String rangeChoice = choice(source, "k BETWEEN ? AND ? AND rival = 1",
        keyIndex, rivalIndex, low, high);
    System.out.printf(Locale.ROOT,
        "PRECISION|%d|%s|%d|%d|%.6f|%.6f|%d|%.6f|%.6f|%s|%s%n",
        ProcessPageSize.kibibytes(), name, histogram.bucketCount(),
        exactEq, estimatedEq, Math.abs(estimatedEq - exactEq),
        exactRange, estimatedRange, Math.abs(estimatedRange - exactRange),
        eqChoice, rangeChoice);
    session.rollback();
    measureMatch(name, source, target, edge, low, high);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int compare(Object left, Object right) {
    return ((Comparable) left).compareTo(right);
  }

  private long count(String source, String predicate, Object... parameters) {
    try (var rows = session.query("SELECT count(*) AS n FROM " + source
        + " WHERE " + predicate, parameters)) {
      return ((Number) rows.next().getProperty("n")).longValue();
    }
  }

  private String choice(String source, String predicate, String keyIndex,
      String rivalIndex, Object... parameters) {
    try (var rows = session.query("EXPLAIN SELECT FROM " + source
        + " WHERE " + predicate, parameters)) {
      String plan = rows.next().getProperty("executionPlanAsString");
      assertNotNull(plan);
      return plan.contains(keyIndex) ? "key"
          : plan.contains(rivalIndex) ? "rival" : "class";
    }
  }

  private void measureMatch(String name, String source, String target, String edge,
      Object low, Object high) {
    // Bind the same typed bounds used by SELECT, including arbitrary-precision decimals.
    String predicate = "k BETWEEN :low AND :high";
    var statement = (SQLMatchStatement) SQLEngine.parse("MATCH {class:" + source
        + ",as:s,where:(" + predicate + ")}.out('" + edge + "'){class:" + target
        + ",as:t} RETURN s,t", session);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var where = new MatchWhereBuilder();
    var planner = new Observed(MatchPlanInputs.builder(pattern)
        .aliasClasses(new HashMap<>(Map.of("s", source, "t", target)))
        .aliasFilters(Map.of("s",
            statement.getMatchExpressions().getFirst().getOrigin().getFilter(),
            "t", where.wrap(where.isDefined("present"))))
        .promotedPresenceKeys(Map.of("t", Set.of("present")))
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections()).build());
    var comparisons = new ArrayList<String>();
    session.begin();
    try (var ignored = Mockito.mockStatic(PresenceRootCost.class, invocation -> {
      Object result = invocation.callRealMethod();
      if (invocation.getMethod().getName().equals("compare")) {
        var costs = (PresenceRootCost.Costs) result;
        comparisons.add(String.format(Locale.ROOT, "%.6f|%.6f|%s",
            costs.forward(), costs.reverse(),
            java.util.Arrays.toString(invocation.getArguments())));
      }
      return result;
    })) {
      var context = new BasicCommandContext(session);
      context.setInputParameters(new HashMap<>(Map.of("low", low, "high", high)));
      var plan = planner.createExecutionPlan(context, false, false);
      try {
        assertTrue("MATCH must apply presence costing",
            planner.presenceDensities().get("t").applied());
        assertFalse("The real PresenceRootCost must run", comparisons.isEmpty());
        System.out.printf("ROOT|%d|%s|%s|%s%n", ProcessPageSize.kibibytes(), name,
            planner.schedule, String.join("/", comparisons));
      } finally {
        plan.close();
      }
    } finally {
      session.rollback();
    }
  }

  private static final class Observed extends MatchExecutionPlanner {
    private List<String> schedule;

    private Observed(MatchPlanInputs inputs) {
      super(inputs);
    }

    @Override
    void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
        List<EdgeTraversal> edges) {
      schedule = edges.stream().map(edge -> edge.out
          ? edge.edge.out.alias + "->" + edge.edge.in.alias
          : edge.edge.in.alias + "->" + edge.edge.out.alias).toList();
    }
  }
}
