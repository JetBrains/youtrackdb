/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;

/**
 * Integration tests for cost model and histogram-based query planning against
 * a real database instance (Section 10.10 of the ADR).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>EXPLAIN output uses FETCH FROM INDEX for indexed equality queries</li>
 *   <li>EXPLAIN output uses FETCH FROM INDEX for indexed range queries</li>
 *   <li>EXPLAIN selects the more selective index when two compete</li>
 *   <li>Histogram-based estimates are reflected after ANALYZE INDEX</li>
 *   <li>MATCH planner produces valid plans with histogram statistics</li>
 * </ul>
 */
public class CostModelIntegrationTest extends DbTestBase {

  // ── EXPLAIN shows FETCH FROM INDEX for indexed equality ───────

  @Test
  public void explainEqualityOnIndexedField_showsFetchFromIndex() {
    // Given: a class with an indexed integer field and 2000 entries
    createClassWithIndexAndData("EqTest", "val", PropertyType.INTEGER,
        2000, i -> i);

    // Build histogram so cost estimation is available
    session.execute("ANALYZE INDEX EqTestvalIdx").close();

    // When: EXPLAIN for an equality query on the indexed field
    try (var result = session.query(
        "EXPLAIN SELECT FROM EqTest WHERE val = 500")) {
      // Then: the plan should include FETCH FROM INDEX
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertNotNull("EXPLAIN should return executionPlanAsString", plan);
      assertTrue(
          "Plan should use FETCH FROM INDEX for equality on indexed "
              + "field, got:\n" + plan,
          plan.contains("FETCH FROM INDEX"));
    }
  }

  // ── EXPLAIN shows FETCH FROM INDEX for indexed range ──────────

  @Test
  public void explainRangeOnIndexedField_showsFetchFromIndex() {
    // Given: a class with an indexed integer field
    createClassWithIndexAndData("RangeTest", "val", PropertyType.INTEGER,
        2000, i -> i);
    session.execute("ANALYZE INDEX RangeTestvalIdx").close();

    // When: EXPLAIN for a range query
    try (var result = session.query(
        "EXPLAIN SELECT FROM RangeTest WHERE val >= 100 AND val < 200")) {
      // Then: plan should use index
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertTrue(
          "Plan should use FETCH FROM INDEX for range query, got:\n"
              + plan,
          plan.contains("FETCH FROM INDEX"));
    }
  }

  // ── EXPLAIN uses correct index name ───────────────────────────

  @Test
  public void explainShowsCorrectIndexName() {
    // Given: a class with a specifically named index
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NameTest");
    clazz.createProperty("code", PropertyType.STRING);
    clazz.createIndex("idx_code_unique",
        SchemaClass.INDEX_TYPE.UNIQUE, "code");

    session.begin();
    for (int i = 0; i < 100; i++) {
      var doc = session.newEntity("NameTest");
      doc.setProperty("code", "C" + String.format("%04d", i));
    }
    session.commit();

    session.execute("ANALYZE INDEX idx_code_unique").close();

    // When: EXPLAIN
    try (var result = session.query(
        "EXPLAIN SELECT FROM NameTest WHERE code = 'C0042'")) {
      // Then: the specific index name appears in the plan
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertTrue(
          "Plan should reference idx_code_unique, got:\n" + plan,
          plan.contains("idx_code_unique"));
    }
  }

  // ── Competing indexes: more selective index chosen ────────────

  @Test
  public void explainChoosesMoreSelectiveIndex() {
    // Given: a class with two indexes on different fields.
    // "status" has very few distinct values (low selectivity for equality).
    // "userId" has many distinct values (high selectivity for equality).
    // The planner should choose the userId index for an equality query.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompeteTest");
    clazz.createProperty("status", PropertyType.STRING);
    clazz.createProperty("userId", PropertyType.INTEGER);
    clazz.createIndex("idx_status",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "status");
    clazz.createIndex("idx_userId",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "userId");

    session.begin();
    var statuses = new String[]{"active", "inactive", "pending"};
    for (int i = 0; i < 3000; i++) {
      var doc = session.newEntity("CompeteTest");
      doc.setProperty("status", statuses[i % 3]);
      doc.setProperty("userId", i);
    }
    session.commit();

    // Build histograms for both indexes
    session.execute("ANALYZE INDEX *").close();

    // When: query filters on both fields with AND
    try (var result = session.query(
        "EXPLAIN SELECT FROM CompeteTest "
            + "WHERE status = 'active' AND userId = 1500")) {
      // Then: the plan should choose idx_userId (more selective)
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertNotNull(plan);

      // The plan should use an index.
      assertTrue(
          "Plan should use an index, got:\n" + plan,
          plan.contains("FETCH FROM INDEX"));

      // The more selective index (userId with NDV=3000) should be
      // preferred over status (NDV=3). The planner picks the index
      // that covers the most fields; if both cover one field, it
      // picks the lowest cost.
      assertTrue(
          "Plan should prefer idx_userId (NDV=3000) over idx_status "
              + "(NDV=3), got:\n" + plan,
          plan.contains("idx_userId"));
    }
  }

  // ── Histogram estimates reflected in cost after ANALYZE ───────

  @Test
  public void analyzeIndexProducesHistogramUsedByPlanner() {
    // Given: a class with skewed data (90% of values are 0, 10% are 1-199)
    createClassWithIndexAndData("SkewTest", "val", PropertyType.INTEGER,
        2000, i -> (i < 1800) ? 0 : (i - 1800));

    // When: ANALYZE INDEX builds the histogram
    try (var result = session.execute("ANALYZE INDEX SkewTestvalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();

      // Then: the histogram should reflect the actual data distribution
      long totalCount =
          ((Number) row.getProperty("totalCount")).longValue();
      assertEquals(2000L, totalCount);

      long distinctCount =
          ((Number) row.getProperty("distinctCount")).longValue();
      // Should have ~200 distinct values (0 through 199)
      assertTrue(
          "distinctCount should be ~200, got " + distinctCount,
          distinctCount > 100 && distinctCount < 300);

      int bucketCount =
          ((Number) row.getProperty("bucketCount")).intValue();
      assertTrue("bucketCount should be > 0", bucketCount > 0);
    }

    // The planner should use this histogram for cost estimation.
    // Verify via EXPLAIN that the index is still chosen.
    try (var explain = session.query(
        "EXPLAIN SELECT FROM SkewTest WHERE val = 0")) {
      assertTrue(explain.hasNext());
      String plan = explain.next().getProperty("executionPlanAsString");
      assertTrue(
          "Plan should use index for equality even on skewed data",
          plan.contains("FETCH FROM INDEX"));
    }
  }

  // ── Full scan fallback when no index on queried field ─────────

  @Test
  public void explainWithoutIndex_showsFetchFromClass() {
    // Given: a class with an indexed field "val" but querying "other"
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NoIdxTest");
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createProperty("other", PropertyType.STRING);
    clazz.createIndex("NoIdxTestvalIdx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    session.begin();
    for (int i = 0; i < 100; i++) {
      var doc = session.newEntity("NoIdxTest");
      doc.setProperty("val", i);
      doc.setProperty("other", "text" + i);
    }
    session.commit();

    // When: EXPLAIN for a query on the non-indexed field
    try (var result = session.query(
        "EXPLAIN SELECT FROM NoIdxTest WHERE other = 'text50'")) {
      // Then: should use full class scan (no index on "other")
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertTrue(
          "Plan should use FETCH FROM CLASS for non-indexed field, "
              + "got:\n" + plan,
          plan.contains("FETCH FROM CLASS"));
    }
  }

  // ── EXPLAIN with MATCH pattern ────────────────────────────────

  @Test
  public void explainMatchQuery_producesValidPlan() {
    // Given: Person and City vertex classes with a LivesIn edge class.
    // No edges created — this test validates plan generation only,
    // not execution.
    var schema = session.getMetadata().getSchema();
    var vClass = schema.getClass("V");
    var eClass = schema.getClass("E");
    var personClass = schema.createClass("Person", vClass);
    schema.createClass("City", vClass);
    schema.createClass("LivesIn", eClass);

    personClass.createProperty("name", PropertyType.STRING);
    personClass.createIndex("idx_person_name",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    for (int i = 0; i < 100; i++) {
      var p = session.newVertex("Person");
      p.setProperty("name", "Person" + i);
    }
    for (int i = 0; i < 10; i++) {
      var c = session.newVertex("City");
      c.setProperty("name", "City" + i);
    }
    session.commit();

    session.execute("ANALYZE INDEX *").close();

    // When: EXPLAIN a MATCH query
    try (var result = session.query(
        "EXPLAIN MATCH {class: Person, as: p, "
            + "where: (name = 'Person42')}"
            + ".out('LivesIn') {class: City, as: c} RETURN p, c")) {
      // Then: produces a valid execution plan
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertNotNull("MATCH EXPLAIN should return a plan string", plan);
      assertFalse("Plan should not be empty", plan.isEmpty());
    }
  }

  // ── Composite index: EXPLAIN picks composite over single ──────

  @Test
  public void explainCompositeIndex_preferredOverSingleField() {
    // Given: a class with both a single-field index and a composite
    // index. A query matching both fields should prefer the composite.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompositeTest");
    clazz.createProperty("city", PropertyType.STRING);
    clazz.createProperty("age", PropertyType.INTEGER);
    clazz.createIndex("idx_city",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "city");
    clazz.createIndex("idx_city_age",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "city", "age");

    session.begin();
    var cities = new String[]{"NYC", "LA", "CHI", "HOU", "PHX"};
    for (int i = 0; i < 1000; i++) {
      var doc = session.newEntity("CompositeTest");
      doc.setProperty("city", cities[i % 5]);
      doc.setProperty("age", 20 + (i % 60));
    }
    session.commit();

    session.execute("ANALYZE INDEX *").close();

    // When: query matches both fields
    try (var result = session.query(
        "EXPLAIN SELECT FROM CompositeTest "
            + "WHERE city = 'NYC' AND age = 35")) {
      // Then: composite index should be preferred (covers more fields)
      assertTrue(result.hasNext());
      var row = result.next();
      String plan = row.getProperty("executionPlanAsString");
      assertTrue(
          "Plan should use composite index idx_city_age, got:\n" + plan,
          plan.contains("idx_city_age"));
    }
  }

  // ── Helpers ───────────────────────────────────────────────────

  /**
   * Creates a class with a single indexed field and populates it with data.
   * Index name follows the pattern: className + fieldName + "Idx".
   */
  private void createClassWithIndexAndData(
      String className, String fieldName, PropertyType type,
      int rowCount, java.util.function.IntFunction<Object> valueMapper) {
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(className);
    clazz.createProperty(fieldName, type);
    clazz.createIndex(className + fieldName + "Idx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, fieldName);

    session.begin();
    for (int i = 0; i < rowCount; i++) {
      var doc = session.newEntity(className);
      doc.setProperty(fieldName, valueMapper.apply(i));
    }
    session.commit();
  }
}
