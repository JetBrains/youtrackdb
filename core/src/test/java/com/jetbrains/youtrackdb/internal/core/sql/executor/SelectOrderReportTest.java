package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLContainsValueOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** SELECT scan order and comparator-agreement tests. */
public class SelectOrderReportTest extends TestUtilsFixture {

  private static final String CLASS = "OrderReportData";

  private SchemaClass createClass(PropertyType a, PropertyType b) {
    var clazz = session.getMetadata().getSchema().createClass(CLASS);
    clazz.createProperty("a", a);
    clazz.createProperty("b", b);
    return clazz;
  }

  private SelectExecutionPlan plan(String sql, Map<Object, Object> params) {
    try (var results = session.query(sql, params)) {
      return (SelectExecutionPlan) results.getExecutionPlan();
    }
  }

  private void assertOrder(
      String sql, Map<Object, Object> params, boolean covered, boolean ridOrdered) {
    var result = plan(sql, params).getOrderReport();
    Assert.assertEquals(sql + " full", covered, result.fullOrderCovered());
    Assert.assertEquals(sql + " RID", ridOrdered, result.ridOrderWithinEqualKeys());
  }

  private SelectExecutionPlan request(String sql, boolean rid, Map<Object, Object> params) {
    var statement = (SQLSelectStatement) SQLEngine.parse(sql, session);
    var ctx = newContext();
    ctx.setInputParameters(params);
    return (SelectExecutionPlan) new SelectExecutionPlanner(statement)
        .createExecutionPlanForOrderRequest(ctx, false, rid, true);
  }

  /** An exact-width composite scan serves both keys and RID ties. */
  @Test
  public void compositeOrderCoversAllKeysAndRidTies() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    assertOrder("select from " + CLASS + " order by a, b", Map.of(), true, true);
    assertOrder("select from " + CLASS + " order by a", Map.of(), true, false);
    var requested = request("select from " + CLASS + " order by a, b", true, Map.of());
    Assert.assertTrue(requested.getOrderReport().fullOrderCovered());
    Assert.assertTrue(requested.getOrderReport().ridOrderWithinEqualKeys());
    Assert.assertEquals(0,
        requested.getSteps().stream().filter(OrderByStep.class::isInstance).count());
    Assert.assertEquals(requested.getOrderReport(),
        ((SelectExecutionPlan) requested.copy(newContext())).getOrderReport());
  }

  /** An equality prefix fixes a, leaving b ordered and RID ties intact. */
  @Test
  public void equalityPrefixAllowsRidTiesButInListDoesNotCoverOrder() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    assertOrder("select from " + CLASS + " where a = 1 order by b", Map.of(), true, true);
    assertOrder("select from " + CLASS + " where a in [1, 2] order by b", Map.of(), false,
        false);
  }

  /** A range scan serves property order, but a wider index cannot serve RID ties. */
  @Test
  public void rangeAndWiderIndexHaveSeparateRidFacts() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    session.getMetadata().getSchema().createClass("WiderReport");
    var wider = session.getMetadata().getSchema().getClass("WiderReport");
    wider.createProperty("a", PropertyType.INTEGER);
    wider.createProperty("b", PropertyType.INTEGER);
    wider.createIndex("WiderReport.ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    session.begin();
    var row = session.newInstance("WiderReport");
    row.setProperty("a", 1);
    row.setProperty("b", 2);
    session.commit();
    assertOrder("select from WiderReport order by a", Map.of(), true, false);
    assertOrder("select from " + CLASS + " where a >= 2 order by a", Map.of(), true, true);
  }

  /** A WHERE index on another field wins over an order-only index. */
  @Test
  public void chosenWhereIndexDoesNotReportAnotherIndexesOrder() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    clazz.createIndex(CLASS + ".b", SchemaClass.INDEX_TYPE.NOTUNIQUE, "b");
    assertOrder("select from " + CLASS + " where b = 1 order by a", Map.of(), false, false);
    var requested = request("select from " + CLASS + " where b = 1 order by a", false, Map.of());
    Assert.assertFalse(requested.getOrderReport().fullOrderCovered());
    Assert.assertEquals(0,
        requested.getSteps().stream().filter(OrderByStep.class::isInstance).count());
  }

  /** A filter with no index preserves the sort-only index and its RID order. */
  @Test
  public void nonIndexedFilterPreservesIndexOrder() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    assertOrder("select from " + CLASS + " where b = 1 order by a", Map.of(), true, true);
  }

  /** A request never sorts, even when the scan provides no property order. */
  @Test
  public void uncoveredOrderRequestDoesNotAppendSort() {
    createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    var requested = request("select from " + CLASS + " order by a", false, Map.of());
    Assert.assertFalse(requested.getOrderReport().fullOrderCovered());
    Assert.assertEquals(0,
        requested.getSteps().stream().filter(OrderByStep.class::isInstance).count());
    Assert.assertTrue(plan("select from " + CLASS + " order by a", Map.of())
        .getSteps().stream().anyMatch(OrderByStep.class::isInstance));
  }

  /** ASC moves nulls into its leading bucket; DESC does not promise RID ties for nulls. */
  @Test
  public void descendingNullableKeyHasNoRidTieGuarantee() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    session.begin();
    session.newInstance(CLASS).setProperty("a", 2);
    session.newInstance(CLASS);
    session.commit();
    assertOrder("select from " + CLASS + " order by a asc", Map.of(), true, true);
    assertOrder("select from " + CLASS + " order by a desc", Map.of(), true, false);
    assertOrder("select from " + CLASS + " where a >= 2 order by a desc", Map.of(), true,
        true);
    assertOrder("select from " + CLASS + " order by a desc nulls first", Map.of(), true,
        false);
    try (var rows = session.query("select from " + CLASS + " order by a asc")) {
      Assert.assertEquals(Arrays.asList(null, 2),
          rows.stream().map(row -> (Integer) row.getProperty("a")).toList());
    }
  }

  /** NOT NULL allows an absent property, so single and composite scans cannot promise DESC RID. */
  @Test
  public void optionalNotNullPropertiesDoNotExcludeMissingIndexKeys() {
    for (var unique : List.of(false, true)) {
      for (var composite : List.of(false, true)) {
        var cls = "MissingReport" + unique + composite;
        var clazz = session.getMetadata().getSchema().createClass(cls);
        clazz.createProperty("a", PropertyType.INTEGER).setMandatory(true).setNotNull(true);
        clazz.createProperty("p", PropertyType.INTEGER).setNotNull(true);
        clazz.createIndex(cls + ".p", unique ? SchemaClass.INDEX_TYPE.UNIQUE
            : SchemaClass.INDEX_TYPE.NOTUNIQUE,
            composite ? new String[] {"a", "p"}
                : new String[] {"p"});
        session.begin();
        var first = session.newInstance(cls);
        first.setProperty("a", 1);
        first.setProperty("p", 1);
        session.newInstance(cls).setProperty("a", 1);
        if (!unique) {
          session.newInstance(cls).setProperty("a", 1);
        }
        session.commit();
        for (var pending : List.of(false, true)) {
          session.begin();
          if (pending) {
            var inserted = session.newInstance(cls);
            inserted.setProperty("a", 1);
            inserted.setProperty("p", 2);
          }
          var order = composite ? "a desc, p desc" : "p desc";
          assertOrder("select from " + cls + " order by " + order, Map.of(), true, false);
          var requested = request("select from " + cls + " order by " + order, true, Map.of());
          Assert.assertTrue(requested.getOrderReport().fullOrderCovered());
          Assert.assertFalse(requested.getOrderReport().ridOrderWithinEqualKeys());
          session.rollback();
        }
      }
    }
  }

  /** Constraints added after insertion do not remove existing missing null index keys. */
  @Test
  public void constraintsAddedAfterInsertDoNotProveDescendingRidOrder() {
    assertUncheckedNullKeysDoNotProveDescendingRidOrder(false);
  }

  /** Disabled validation permits missing null index keys despite both schema constraints. */
  @Test
  public void disabledValidationDoesNotProveDescendingRidOrder() {
    assertUncheckedNullKeysDoNotProveDescendingRidOrder(true);
  }

  private void assertUncheckedNullKeysDoNotProveDescendingRidOrder(boolean disableValidation) {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    if (disableValidation) {
      clazz.getProperty("a").setMandatory(true).setNotNull(true);
    }
    var validation = session.isValidationEnabled();
    try {
      session.setValidationEnabled(!disableValidation);
      session.begin();
      session.newInstance(CLASS).setProperty("a", 1);
      session.newInstance(CLASS);
      session.newInstance(CLASS);
      session.commit();
    } finally {
      session.setValidationEnabled(validation);
    }
    if (!disableValidation) {
      clazz.getProperty("a").setMandatory(true).setNotNull(true);
    }
    for (var pending : List.of(false, true)) {
      session.begin();
      if (pending) {
        session.newInstance(CLASS).setProperty("a", 2);
      }
      var sql = "select from " + CLASS + " order by a desc";
      assertOrder(sql, Map.of(), true, false);
      var requested = request(sql, true, Map.of());
      Assert.assertTrue(requested.getOrderReport().fullOrderCovered());
      Assert.assertFalse(requested.getOrderReport().ridOrderWithinEqualKeys());
      session.rollback();
    }
  }

  /** Parameter equality stays conservative in the report, and a null binding returns no rows. */
  @Test
  public void nullEqualityParameterDoesNotProveDescendingRidOrder() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    session.begin();
    session.newInstance(CLASS).setProperty("a", 1);
    session.newInstance(CLASS);
    session.newInstance(CLASS);
    session.commit();
    var params = new HashMap<Object, Object>();
    var sql = "select from " + CLASS + " where a = :x order by a desc";
    for (var pending : List.of(false, true)) {
      session.begin();
      if (pending) {
        session.newInstance(CLASS);
      }
      params.put("x", 1);
      assertOrder(sql, params, true, false);
      params.put("x", null);
      assertOrder(sql, params, true, false);
      Assert.assertFalse(request(sql, true, params).getOrderReport().ridOrderWithinEqualKeys());
      try (var rows = session.query(sql + ", @rid desc", params)) {
        Assert.assertTrue("Equality rejects a null operand", rows.stream().toList().isEmpty());
      }
      session.rollback();
    }
  }

  /** Both planners use one conservative predicate proof, with no parameter-bound equality. */
  @Test
  public void sharedNullExclusionProofAcceptsOnlyNullRejectingConditions() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    for (var filter : List.of("a = 1", "a == 1", "a = 'x'", "a = true", "a = false",
        "a > :x", "a >= :x", "a < :x", "a <= :x", "a LIKE :x",
        "a CONTAINSKEY :x", "a IS NOT NULL",
        "b = 1 AND a IS NOT NULL", "a = 1 AND b = 1", "`a` = 1")) {
      var condition = ((SQLSelectStatement) SQLEngine.parse(
          "select from " + CLASS + " where " + filter, session)).getWhereClause()
          .getBaseExpression();
      Assert.assertTrue(filter,
          SelectExecutionPlanner.orderedFieldsExcludeNulls(clazz, List.of("a"), condition));
      // Exercise real evaluation on both explicit-null and absent values before widening MATCH.
      var context = newContext();
      context.setInputParameters(Map.of("x", 1));
      for (var present : List.of(false, true)) {
        var row = new ResultInternal(session);
        if (present) {
          row.setProperty("a", null);
        }
        Assert.assertFalse(filter, condition.evaluate(row, context));
      }
    }
    for (var filter : List.of("a = null", "a = :x", "a = ?", "a = b", "a = 1 + 2",
        "a = :x + 1", "a = 'x'.toUpperCase()", "a <> 1", "a != 1", "a IS DEFINED",
        "NOT (a IS NOT NULL)", "a = 1 OR b = 1", "b = 1", "a.foo = 1",
        "a.foo IS NOT NULL")) {
      var condition = ((SQLSelectStatement) SQLEngine.parse(
          "select from " + CLASS + " where " + filter, session)).getWhereClause()
          .getBaseExpression();
      Assert.assertFalse(filter,
          SelectExecutionPlanner.orderedFieldsExcludeNulls(clazz, List.of("a"), condition));
    }
    // IN and CONTAINSVALUE normally have dedicated condition nodes. Their binary operators
    // also reject null, so exercise those node forms directly.
    var binary = new SQLBinaryCondition(-1);
    binary.setLeft(new SQLExpression(new SQLIdentifier("a")));
    binary.setRight(binary.getLeft());
    for (var operator : List.<SQLBinaryCompareOperator>of(
        new SQLInOperator(-1), new SQLContainsValueOperator(-1))) {
      binary.setOperator(operator);
      Assert.assertFalse(operator.execute(session, null, List.of(1)));
      Assert.assertTrue(SelectExecutionPlanner.orderedFieldsExcludeNulls(clazz, List.of("a"),
          binary));
    }
    Assert.assertFalse(SelectExecutionPlanner.orderedFieldsExcludeNulls(clazz,
        List.of("undeclared"), null));
    for (var mandatory : List.of(false, true)) {
      for (var notNull : List.of(false, true)) {
        clazz.getProperty("a").setMandatory(mandatory).setNotNull(notNull);
        // Schema flags cannot certify existing or unvalidated index entries.
        Assert.assertFalse(
            SelectExecutionPlanner.orderedFieldsExcludeNulls(clazz, List.of("a"), null));
      }
    }
  }

  /** Nullable composite keys require their natural null placement in both directions. */
  @Test
  public void compositeNullPlacementChangesOrderCoverage() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    assertOrder("select from " + CLASS + " order by a asc, b asc", Map.of(), true, true);
    assertOrder("select from " + CLASS + " order by a desc, b desc", Map.of(), true, false);
    assertOrder("select from " + CLASS + " order by a asc, b asc nulls last",
        Map.of(), false, false);
  }

  /** Cache copies retain the report while different bound range values fetch different rows. */
  @Test
  public void cachedPlanKeepsOrderReportWithNewParameters() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    session.begin();
    session.newInstance(CLASS).setProperty("a", 1);
    session.newInstance(CLASS).setProperty("a", 5);
    session.commit();
    var sql = "select from " + CLASS + " where a >= :minimum order by a";
    assertOrder(sql, Map.of("minimum", 1), true, true);
    assertOrder(sql, Map.of("minimum", 5), true, true);
    try (var rows = session.query(sql, Map.of("minimum", 5))) {
      Assert.assertEquals(List.of(5),
          rows.stream().map(row -> (Integer) row.getProperty("a")).toList());
    }
    var first = request(sql, true, Map.of("minimum", 1));
    var next = request(sql, true, Map.of("minimum", 5));
    Assert.assertEquals(first.getOrderReport(), next.getOrderReport());
    Assert.assertEquals(first.getOrderReport(),
        ((SelectExecutionPlan) next.copy(newContext())).getOrderReport());
  }

  /** Synthetic SELECTs have no original SQL text, so neither plan mode caches their text. */
  @Test
  public void syntheticSelectDoesNotEnterPlanCache() {
    createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    var sql = "select from " + CLASS + " order by a";
    var cache = YqlExecutionPlanCache.instance(session);
    for (var requestMode : List.of(false, true)) {
      var statement = (SQLSelectStatement) SQLEngine.parse(sql, session);
      statement.setOriginalStatement(null);
      var planner = new SelectExecutionPlanner(statement);
      var plan = requestMode
          ? planner.createExecutionPlanForOrderRequest(newContext(), false, true, true)
          : planner.createExecutionPlan(newContext(), false, true);
      Assert.assertNotNull(plan);
      Assert.assertFalse(cache.contains(sql));
      Assert.assertFalse(cache.contains(statement.toString()));
      Assert.assertFalse(cache.contains("null\0orderRequestWithRid"));
    }
  }

  /** A single BINARY key covers property order but cannot guarantee RID order across SQL ties. */
  @Test
  public void singleBinaryIndexHasNoRidOrderAcrossSqlEqualKeys() {
    var clazz = createClass(PropertyType.BINARY, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    assertOrder("select from " + CLASS + " order by a", Map.of(), true, false);
    var requested = request("select from " + CLASS + " order by a", false, Map.of());
    Assert.assertTrue(requested.getOrderReport().fullOrderCovered());
    Assert.assertFalse(requested.getOrderReport().ridOrderWithinEqualKeys());
  }

  /** A cached unmarked request must not give a Gremlin-marked request its positive report. */
  @Test
  public void gremlinMarkedOrderDoesNotReuseUnmarkedCachedReport() {
    var clazz = createClass(PropertyType.BINARY, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    var sql = "select from " + CLASS + " order by a";
    Assert.assertTrue(request(sql, false, Map.of()).getOrderReport().fullOrderCovered());
    var marked = (SQLSelectStatement) SQLEngine.parse(sql, session);
    marked.getOrderBy().getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
    var ctx = newContext();
    var result = (SelectExecutionPlan) new SelectExecutionPlanner(marked)
        .createExecutionPlanForOrderRequest(ctx, false, false, true);
    Assert.assertFalse(result.getOrderReport().fullOrderCovered());
  }

  /** Cache keys must distinguish each marked item, not merely whether any item is marked. */
  @Test
  public void mixedGremlinMarkerVectorsHaveSeparateCachedReports() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.BINARY);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    var sql = "select from " + CLASS + " order by a, b";
    var first = (SQLSelectStatement) SQLEngine.parse(sql, session);
    first.setOriginalStatement(sql);
    first.getOrderBy().getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
    var firstReport = (SelectExecutionPlan) new SelectExecutionPlanner(first)
        .createExecutionPlanForOrderRequest(newContext(), false, false, true);
    Assert.assertTrue(firstReport.getOrderReport().fullOrderCovered());
    // Full index scans do not publish themselves, so seed the cache to exercise key lookup.
    YqlExecutionPlanCache.put(sql + "\0orderRequest\0gremlinOrder",
        firstReport, session, null);
    Assert.assertTrue(YqlExecutionPlanCache.instance(session)
        .contains(sql + "\0orderRequest\0gremlinOrder"));

    var second = (SQLSelectStatement) SQLEngine.parse(sql, session);
    second.setOriginalStatement(sql);
    second.getOrderBy().getItems().forEach(
        item -> item.setGremlinToMatchTranslatorProduced(true));
    var secondReport = (SelectExecutionPlan) new SelectExecutionPlanner(second)
        .createExecutionPlanForOrderRequest(newContext(), false, false, true);
    Assert.assertFalse(secondReport.getOrderReport().fullOrderCovered());
    Assert.assertEquals(0,
        secondReport.getSteps().stream().filter(OrderByStep.class::isInstance).count());
  }

  /** A presence filter excludes the DESC null bucket in an index-only scan's RID report. */
  @Test
  public void descendingPresenceFilterProvidesRidOrderWithoutChangingSelectScan() {
    var clazz = createClass(PropertyType.INTEGER, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    var desc = "select from " + CLASS + " where a is not null order by a desc";
    assertOrder(desc, Map.of(), true, true);
    Assert.assertEquals(0, plan(desc, Map.of()).getSteps().stream()
        .filter(OrderByStep.class::isInstance).count());
    assertOrder("select from " + CLASS + " where a is defined order by a desc",
        Map.of(), true, false);
  }

  /** Not-equal keeps null scores, so DESC index scans cannot promise RID tie order. */
  @Test
  public void descendingNotEqualFilterDoesNotProveRidOrderForNullKeys() {
    var clazz = session.getMetadata().getSchema().createClass(CLASS);
    clazz.createProperty("score", PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".score", SchemaClass.INDEX_TYPE.NOTUNIQUE, "score");
    session.begin();
    session.newInstance(CLASS);
    session.newInstance(CLASS);
    session.newInstance(CLASS).setProperty("score", 3);
    session.newInstance(CLASS).setProperty("score", 5);
    session.newInstance(CLASS).setProperty("score", 7);
    session.commit();
    for (var operator : List.of("<>", "!=")) {
      var sql = "select from " + CLASS + " where score " + operator
          + " 5 order by score desc";
      var report = request(sql, true, Map.of()).getOrderReport();
      Assert.assertTrue(sql, report.fullOrderCovered());
      Assert.assertFalse(sql, report.ridOrderWithinEqualKeys());
    }
  }

  /** SQL treats BINARY a values as ties. Index key a must not hide b order. */
  @Test
  public void binaryCompositeKeepsSqlComparatorSort() {
    var clazz = createClass(PropertyType.BINARY, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    session.begin();
    var first = session.newInstance(CLASS);
    first.setProperty("a", new byte[] {0});
    first.setProperty("b", 2);
    var second = session.newInstance(CLASS);
    second.setProperty("a", new byte[] {1});
    second.setProperty("b", 1);
    session.commit();
    var sql = "select from " + CLASS + " order by a, b";
    var result = plan(sql, Map.of());
    Assert.assertFalse(result.getOrderReport().fullOrderCovered());
    Assert.assertTrue(result.getSteps().stream().anyMatch(OrderByStep.class::isInstance));
    try (var rows = session.query(sql)) {
      Assert.assertEquals(List.of(1, 2),
          rows.stream().map(row -> (Integer) row.getProperty("b")).toList());
    }
    var single = plan("select from " + CLASS + " order by a", Map.of());
    Assert.assertTrue(single.getOrderReport().fullOrderCovered());
    Assert.assertFalse(single.getOrderReport().ridOrderWithinEqualKeys());
  }

  /** An explicit STRING index does not prove that undeclared row values are strings. */
  @Test
  public void undeclaredIntegerValuesKeepNumericSqlOrder() {
    session.getMetadata().getSchema().createClass(CLASS);
    session.execute("create index " + CLASS + ".p on " + CLASS + " (p) NOTUNIQUE STRING")
        .close();
    session.begin();
    session.newInstance(CLASS).setProperty("p", 2);
    session.newInstance(CLASS).setProperty("p", 10);
    session.commit();
    var sql = "select from " + CLASS + " order by p";
    var result = plan(sql, Map.of());
    Assert.assertFalse(result.getOrderReport().fullOrderCovered());
    Assert.assertTrue(result.getSteps().stream().anyMatch(OrderByStep.class::isInstance));
    try (var rows = session.query(sql)) {
      Assert.assertEquals(List.of(2, 10),
          rows.stream().map(row -> (Integer) row.getProperty("p")).toList());
    }
  }

  /** Request mode checks the Gremlin comparator without appending a SQL sort. */
  @Test
  public void gremlinMarkedBinaryRequestRefusesIndexOrder() {
    var clazz = createClass(PropertyType.BINARY, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    var statement = (SQLSelectStatement) SQLEngine.parse(
        "select from " + CLASS + " order by a", session);
    statement.getOrderBy().getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
    var plan = (SelectExecutionPlan) new SelectExecutionPlanner(statement)
        .createExecutionPlanForOrderRequest(newContext(), false, false, false);
    Assert.assertFalse(plan.getOrderReport().fullOrderCovered());
    Assert.assertEquals(0, plan.getSteps().stream().filter(OrderByStep.class::isInstance).count());
  }

  /** Declared agreement-set types serve both comparators; DATE serves terminal SQL only. */
  @Test
  public void declaredAgreementTypesCoverBothComparators() {
    var clazz = session.getMetadata().getSchema().createClass(CLASS);
    for (var type : List.of(PropertyType.STRING, PropertyType.BYTE, PropertyType.SHORT,
        PropertyType.INTEGER, PropertyType.LONG, PropertyType.FLOAT, PropertyType.DOUBLE,
        PropertyType.DECIMAL, PropertyType.DATETIME, PropertyType.BOOLEAN,
        PropertyType.DATE)) {
      var field = "p" + type;
      clazz.createProperty(field, type);
      clazz.createIndex(CLASS + "." + field, SchemaClass.INDEX_TYPE.NOTUNIQUE, field);
      var order = ((SQLSelectStatement) SQLEngine.parse(
          "select from " + CLASS + " order by " + field, session)).getOrderBy();
      var schemaClass = session.getMetadata().getImmutableSchemaSnapshot().getClassInternal(CLASS);
      var index = schemaClass.getIndexesInternal().stream()
          .filter(candidate -> candidate.getName().equals(CLASS + "." + field))
          .findFirst().orElseThrow();
      Assert.assertTrue(type.toString(), IndexOrderTypeAgreement.agrees(schemaClass,
          index.getDefinition(), order.getItems(), List.of(field), false));
      order.getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
      Assert.assertEquals(type.toString(), type != PropertyType.DATE,
          IndexOrderTypeAgreement.agrees(schemaClass,
              index.getDefinition(), order.getItems(), List.of(field), false));
    }
  }

  /** A Gremlin-marked terminal BINARY item fails while ordinary SQL can use that key. */
  @Test
  public void typeAgreementDistinguishesComparatorAndSchema() {
    var clazz = createClass(PropertyType.BINARY, PropertyType.INTEGER);
    clazz.createIndex(CLASS + ".ab", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a", "b");
    var index = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassInternal(CLASS).getIndexesInternal().iterator().next();
    var order = ((SQLSelectStatement) SQLEngine.parse(
        "select from " + CLASS + " order by a, b", session)).getOrderBy();
    Assert.assertFalse(IndexOrderTypeAgreement.agrees(clazz, index.getDefinition(),
        order.getItems(), List.of("a", "b"), false));
    Assert.assertTrue(IndexOrderTypeAgreement.agrees(clazz, index.getDefinition(),
        order.getItems().subList(0, 1), List.of("a"), false));
    Assert.assertFalse(IndexOrderTypeAgreement.agrees(clazz, index.getDefinition(),
        order.getItems().subList(0, 1), List.of("a"), true));
    order.getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
    Assert.assertFalse(IndexOrderTypeAgreement.agrees(clazz, index.getDefinition(),
        order.getItems().subList(0, 1), List.of("a"), false));
    var orderB = ((SQLSelectStatement) SQLEngine.parse(
        "select from " + CLASS + " order by b", session)).getOrderBy();
    orderB.getItems().getFirst().setGremlinToMatchTranslatorProduced(true);
    Assert.assertTrue(IndexOrderTypeAgreement.agrees(clazz, index.getDefinition(),
        orderB.getItems(), List.of("b"), false));
  }
}
