package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for the in-place comparison fast path in
 * {@link SQLBinaryCondition#evaluate(Result, com.jetbrains.youtrackdb.internal.core.command.CommandContext)}.
 *
 * <p>Each test inserts records, commits (so they have serialized {@code source} bytes),
 * then queries via SQL SELECT with WHERE clauses to exercise the in-place comparison path
 * for the Result-based evaluate method. Results are compared against expected values to
 * verify correctness.
 */
@Category(SequentialTest.class)
public class SQLBinaryConditionInPlaceTest extends DbTestBase {

  // ----- Equality (=) -----

  @Test
  public void testEqualsInteger() {
    // Verifies that integer equality comparison works via the in-place path.
    // Records with serialized source bytes are queried with WHERE age = 25.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("EqInt");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("age", PropertyType.INTEGER);

    session.begin();
    var e1 = session.newEntity("EqInt");
    e1.setProperty("name", "Alice");
    e1.setProperty("age", 25);

    var e2 = session.newEntity("EqInt");
    e2.setProperty("name", "Bob");
    e2.setProperty("age", 30);

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM EqInt WHERE age = 25")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Alice");
    }
    session.commit();
  }

  @Test
  public void testEqualsString() {
    // Verifies that string equality comparison works via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("EqStr");
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("EqStr");
    e1.setProperty("name", "hello");

    var e2 = session.newEntity("EqStr");
    e2.setProperty("name", "world");

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM EqStr WHERE name = 'hello'")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("hello");
    }
    session.commit();
  }

  @Test
  public void testEqualsDouble() {
    // Verifies that double equality comparison works via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("EqDbl");
    clazz.createProperty("val", PropertyType.DOUBLE);
    clazz.createProperty("label", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("EqDbl");
    e1.setProperty("val", 2.5);
    e1.setProperty("label", "twoAndHalf");

    var e2 = session.newEntity("EqDbl");
    e2.setProperty("val", 1.25);
    e2.setProperty("label", "oneAndQuarter");

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM EqDbl WHERE val = 2.5")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("label")).isEqualTo("twoAndHalf");
    }
    session.commit();
  }

  @Test
  public void testEqualsBoolean() {
    // Verifies that boolean equality comparison works via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("EqBool");
    clazz.createProperty("active", PropertyType.BOOLEAN);
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("EqBool");
    e1.setProperty("active", true);
    e1.setProperty("name", "on");

    var e2 = session.newEntity("EqBool");
    e2.setProperty("active", false);
    e2.setProperty("name", "off");

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM EqBool WHERE active = true")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("on");
    }
    session.commit();
  }

  // ----- Not-equal (<>, !=) -----

  @Test
  public void testNotEqualNeq() {
    // Verifies the <> operator works correctly via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NeqTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 3; i++) {
      var e = session.newEntity("NeqTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NeqTest WHERE val <> 2")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(1, 3);
    }
    session.commit();
  }

  @Test
  public void testNotEqualNe() {
    // Verifies the != operator works correctly via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NeTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 3; i++) {
      var e = session.newEntity("NeTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NeTest WHERE val != 2")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(1, 3);
    }
    session.commit();
  }

  // ----- Range operators (<, >, <=, >=) -----

  @Test
  public void testLessThan() {
    // Verifies < operator via the in-place path on integer property.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("LtTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 5; i++) {
      var e = session.newEntity("LtTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM LtTest WHERE val < 3")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(1, 2);
    }
    session.commit();
  }

  @Test
  public void testGreaterThan() {
    // Verifies > operator via the in-place path on integer property.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("GtTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 5; i++) {
      var e = session.newEntity("GtTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM GtTest WHERE val > 3")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(4, 5);
    }
    session.commit();
  }

  @Test
  public void testLessThanOrEqual() {
    // Verifies <= operator via the in-place path on integer property.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("LeTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 5; i++) {
      var e = session.newEntity("LeTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM LeTest WHERE val <= 3")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(1, 2, 3);
    }
    session.commit();
  }

  @Test
  public void testGreaterThanOrEqual() {
    // Verifies >= operator via the in-place path on integer property.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("GeTest");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int i = 1; i <= 5; i++) {
      var e = session.newEntity("GeTest");
      e.setProperty("val", i);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM GeTest WHERE val >= 3")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(3, 4, 5);
    }
    session.commit();
  }

  // ----- String range comparison -----

  @Test
  public void testStringRangeComparison() {
    // Verifies that string range comparison (lexicographic) works via
    // the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("StrRange");
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    for (var name : List.of("apple", "banana", "cherry", "date")) {
      var e = session.newEntity("StrRange");
      e.setProperty("name", name);

    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM StrRange WHERE name < 'cherry'")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var names = results.stream()
          .map(r -> r.<String>getProperty("name"))
          .collect(Collectors.toList());
      assertThat(names).containsExactlyInAnyOrder("apple", "banana");
    }
    session.commit();
  }

  // ----- Null handling -----

  @Test
  public void testNullPropertyFallsBackCorrectly() {
    // Verifies that null property values fall back to the standard SQL NULL
    // handling path (NULL = X yields false per SQL semantics).
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NullProp");
    clazz.createProperty("val", PropertyType.INTEGER);
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("NullProp");
    e1.setProperty("val", 10);
    e1.setProperty("name", "hasVal");

    var e2 = session.newEntity("NullProp");
    // val is null
    e2.setProperty("name", "noVal");

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NullProp WHERE val = 10")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("hasVal");
    }
    session.commit();
  }

  @Test
  public void testNullRightValueFallsBackCorrectly() {
    // Verifies that comparing against a null right-hand value works correctly.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NullRight");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("NullRight");
    e.setProperty("val", 10);

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NullRight WHERE val = null")) {
      var results = rs.stream().collect(Collectors.toList());
      // SQL semantics: val = NULL is false
      assertThat(results).isEmpty();
    }
    session.commit();
  }

  // ----- Cross-type numeric comparison -----

  @Test
  public void testLongPropertyVsIntegerLiteral() {
    // Verifies cross-type numeric comparison: LONG property vs integer-range
    // literal. The SQL parser produces an Integer for small numeric literals,
    // but the property is stored as LONG — this exercises the type conversion
    // path in InPlaceComparator.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CrossLongInt");
    clazz.createProperty("val", PropertyType.LONG);
    clazz.createProperty("name", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("CrossLongInt");
    e1.setProperty("val", 42L);
    e1.setProperty("name", "match");

    var e2 = session.newEntity("CrossLongInt");
    e2.setProperty("val", 99L);
    e2.setProperty("name", "noMatch");

    session.commit();

    session.begin();
    // Literal 42 is parsed as Integer; property type is LONG
    try (var rs = session.query("SELECT FROM CrossLongInt WHERE val = 42")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("match");
    }
    // Also verify range comparison cross-type
    try (var rs = session.query("SELECT FROM CrossLongInt WHERE val > 50")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("noMatch");
    }
    session.commit();
  }

  @Test
  public void testIntegerPropertyVsOverflowLongLiteral() {
    // Verifies that comparing an INTEGER property against a literal exceeding
    // Integer range correctly returns no match (the in-place path should
    // detect the overflow and fall back).
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CrossIntOverflow");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("CrossIntOverflow");
    e.setProperty("val", 42);
    session.commit();

    session.begin();
    // 4294967296 exceeds Integer.MAX_VALUE — no INTEGER can match
    try (var rs = session.query(
        "SELECT FROM CrossIntOverflow WHERE val = 4294967296")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).isEmpty();
    }
    session.commit();
  }

  // ----- Multiple WHERE conditions on same record -----

  @Test
  public void testMultipleWhereConditions() {
    // Verifies that multiple WHERE conditions on the same record each get
    // independently optimized via the in-place path.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("MultiWhere");
    clazz.createProperty("x", PropertyType.INTEGER);
    clazz.createProperty("y", PropertyType.STRING);

    session.begin();
    var e1 = session.newEntity("MultiWhere");
    e1.setProperty("x", 10);
    e1.setProperty("y", "alpha");

    var e2 = session.newEntity("MultiWhere");
    e2.setProperty("x", 10);
    e2.setProperty("y", "beta");

    var e3 = session.newEntity("MultiWhere");
    e3.setProperty("x", 20);
    e3.setProperty("y", "alpha");

    session.commit();

    session.begin();
    try (var rs = session.query(
        "SELECT FROM MultiWhere WHERE x = 10 AND y = 'alpha'")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("x")).isEqualTo(10);
      assertThat(results.get(0).<String>getProperty("y")).isEqualTo("alpha");
    }
    session.commit();
  }

  // ----- Negative values (TC1: zigzag encoding edge cases) -----

  @Test
  public void testRangeWithNegativeValues() {
    // Verifies range comparison works correctly with negative integers, which
    // use different byte representations in VarInt zigzag encoding.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NegRange");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    for (int v : new int[] {-10, -1, 0, 1, 10}) {
      var e = session.newEntity("NegRange");
      e.setProperty("val", v);
    }
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NegRange WHERE val > -1")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(0, 1, 10);
    }

    try (var rs = session.query("SELECT FROM NegRange WHERE val <= 0")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(-10, -1, 0);
    }

    try (var rs = session.query("SELECT FROM NegRange WHERE val = -1")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("val")).isEqualTo(-1);
    }
    session.commit();
  }

  // ----- Identifiable overload (via CONTAINS with linked entities) -----

  @Test
  public void testContainsWithLinkedEntityCondition() {
    // Verifies that the Identifiable-based evaluate() overload is exercised
    // when using CONTAINS with a condition on linked entities.
    // The CONTAINS (condition) syntax iterates collection items and calls
    // evaluate(Identifiable, ctx) on the SQLBinaryCondition inside the
    // condition.
    var schema = session.getMetadata().getSchema();
    schema.createClass("ContTag");
    var clazz = schema.createClass("ContParent");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("tags", PropertyType.LINKLIST);

    session.begin();
    // Create tag entities
    session.execute(
        "INSERT INTO ContTag SET label = 'important', priority = 1");
    session.execute(
        "INSERT INTO ContTag SET label = 'minor', priority = 5");
    session.execute(
        "INSERT INTO ContTag SET label = 'minor', priority = 3");
    session.commit();

    session.begin();
    // Link tags to parents
    session.execute(
        "INSERT INTO ContParent SET name = 'first', tags = "
            + "(SELECT FROM ContTag WHERE priority IN [1, 5])");
    session.execute(
        "INSERT INTO ContParent SET name = 'second', tags = "
            + "(SELECT FROM ContTag WHERE priority = 3)");
    session.commit();

    session.begin();
    try (var rs = session.query(
        "SELECT FROM ContParent WHERE tags CONTAINS (label = 'important')")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("first");
    }

    // Range comparison inside CONTAINS — exercises comparePropertyTo
    // through the Identifiable overload
    try (var rs = session.query(
        "SELECT FROM ContParent WHERE tags CONTAINS (priority < 3)")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("first");
    }
    session.commit();
  }

  // ----- No match -----

  @Test
  public void testNoMatchReturnsEmpty() {
    // Verifies that when no records match, the result set is empty.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("NoMatch");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("NoMatch");
    e.setProperty("val", 42);

    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM NoMatch WHERE val = 999")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).isEmpty();
    }
    session.commit();
  }

  // ===== MATCH query integration (Step 3) =====

  @Test
  public void testMatchWhereEquality() {
    // Verifies that MATCH queries with WHERE clauses exercise the in-place
    // comparison path via the Result-based evaluate().
    session.execute("CREATE class MatchPerson extends V");

    session.begin();
    session.execute("CREATE VERTEX MatchPerson set name = 'Alice', age = 30");
    session.execute("CREATE VERTEX MatchPerson set name = 'Bob', age = 25");
    session.execute("CREATE VERTEX MatchPerson set name = 'Carol', age = 35");
    session.commit();

    session.begin();
    try (var rs = session.query(
        "MATCH {class: MatchPerson, where: (name = 'Bob'), as: p} "
            + "RETURN p.name as name, p.age as age")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Bob");
      assertThat(results.get(0).<Integer>getProperty("age")).isEqualTo(25);
    }
    session.commit();
  }

  @Test
  public void testMatchWhereRange() {
    // Verifies MATCH with range operators in WHERE clause.
    session.execute("CREATE class MatchItem extends V");

    session.begin();
    for (int i = 1; i <= 5; i++) {
      session.execute("CREATE VERTEX MatchItem set val = " + i);
    }
    session.commit();

    session.begin();
    try (var rs = session.query(
        "MATCH {class: MatchItem, where: (val >= 3), as: i} "
            + "RETURN i.val as val")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);
      var vals = results.stream()
          .map(r -> r.<Integer>getProperty("val"))
          .collect(Collectors.toList());
      assertThat(vals).containsExactlyInAnyOrder(3, 4, 5);
    }
    session.commit();
  }

  @Test
  public void testMatchWhereNotEqual() {
    // Verifies MATCH with <> operator in WHERE clause.
    session.execute("CREATE class MatchColor extends V");

    session.begin();
    session.execute("CREATE VERTEX MatchColor set color = 'red'");
    session.execute("CREATE VERTEX MatchColor set color = 'blue'");
    session.execute("CREATE VERTEX MatchColor set color = 'green'");
    session.commit();

    session.begin();
    try (var rs = session.query(
        "MATCH {class: MatchColor, where: (color <> 'blue'), as: c} "
            + "RETURN c.color as color")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var colors = results.stream()
          .map(r -> r.<String>getProperty("color"))
          .collect(Collectors.toList());
      assertThat(colors).containsExactlyInAnyOrder("red", "green");
    }
    session.commit();
  }

  // ===== Edge cases (Step 3) =====

  @Test
  public void testNonEntityProjectionPassesThrough() {
    // Verifies that WHERE on a projected (non-entity) result falls back
    // gracefully — the in-place path requires an EntityImpl, so subquery
    // projections that produce plain ResultInternal should use the standard
    // evaluation path.
    session.execute("CREATE class ProjTest");

    session.begin();
    session.execute("INSERT INTO ProjTest SET x = 10");
    session.execute("INSERT INTO ProjTest SET x = 20");
    session.commit();

    session.begin();
    try (var rs = session.query(
        "SELECT x FROM (SELECT x FROM ProjTest) WHERE x = 10")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("x")).isEqualTo(10);
    }
    session.commit();
  }

  @Test
  public void testSchemalessPropertyWorksViaInPlacePath() {
    // Verifies that schema-less properties (no explicit property definition)
    // produce correct results — deserializeField handles dynamic fields, so
    // the in-place path works for schema-less mode too.
    session.execute("CREATE class Schemaless");

    session.begin();
    // Insert without schema — properties are stored dynamically
    session.execute("INSERT INTO Schemaless SET x = 42, y = 'hello'");
    session.execute("INSERT INTO Schemaless SET x = 99, y = 'world'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM Schemaless WHERE x = 42")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("y")).isEqualTo("hello");
    }
    session.commit();
  }

  @Test
  public void testMultipleMatchWhereConditions() {
    // Verifies that multiple WHERE conditions in a MATCH pattern each get
    // independently optimized.
    session.execute("CREATE class MatchMulti extends V");

    session.begin();
    session.execute("CREATE VERTEX MatchMulti set x = 10, y = 'a'");
    session.execute("CREATE VERTEX MatchMulti set x = 10, y = 'b'");
    session.execute("CREATE VERTEX MatchMulti set x = 20, y = 'a'");
    session.commit();

    session.begin();
    try (var rs = session.query(
        "MATCH {class: MatchMulti, where: (x = 10 AND y = 'a'), as: m} "
            + "RETURN m.x as x, m.y as y")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("x")).isEqualTo(10);
      assertThat(results.get(0).<String>getProperty("y")).isEqualTo("a");
    }
    session.commit();
  }

  @Test
  public void testMatchWithTraversalAndWhere() {
    // Verifies in-place comparison works correctly in MATCH with graph
    // traversal — the WHERE clause on a traversed vertex should use the
    // Result-based evaluate with the in-place path.
    session.execute("CREATE class MNode extends V");
    session.execute("CREATE class MEdge extends E");

    session.begin();
    session.execute("CREATE VERTEX MNode set name = 'root', level = 0");
    session.execute("CREATE VERTEX MNode set name = 'child1', level = 1");
    session.execute("CREATE VERTEX MNode set name = 'child2', level = 1");
    session.execute("CREATE VERTEX MNode set name = 'grandchild', level = 2");
    // phantom has level = 0, same as root — should be filtered OUT by level > 0
    session.execute("CREATE VERTEX MNode set name = 'phantom', level = 0");

    session.execute(
        "CREATE EDGE MEdge from (SELECT FROM MNode WHERE name = 'root') "
            + "to (SELECT FROM MNode WHERE name = 'child1')");
    session.execute(
        "CREATE EDGE MEdge from (SELECT FROM MNode WHERE name = 'root') "
            + "to (SELECT FROM MNode WHERE name = 'child2')");
    session.execute(
        "CREATE EDGE MEdge from (SELECT FROM MNode WHERE name = 'root') "
            + "to (SELECT FROM MNode WHERE name = 'phantom')");
    session.execute(
        "CREATE EDGE MEdge from (SELECT FROM MNode WHERE name = 'child1') "
            + "to (SELECT FROM MNode WHERE name = 'grandchild')");
    session.commit();

    session.begin();
    // Find direct children of root at level > 0 — phantom (level=0) should
    // be excluded by the WHERE filter, proving the range comparison works.
    try (var rs = session.query(
        "MATCH {class: MNode, where: (name = 'root'), as: r}"
            + ".out('MEdge'){where: (level > 0), as: c} "
            + "RETURN c.name as name, c.level as level")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2);
      var names = results.stream()
          .map(r -> r.<String>getProperty("name"))
          .collect(Collectors.toList());
      assertThat(names).containsExactlyInAnyOrder("child1", "child2");
    }
    session.commit();
  }

  // ===== Collation bypass (TC2) =====

  @Test
  public void testCollationPropertyBypassesInPlacePath() {
    // Verifies that a property with COLLATE ci skips the in-place path
    // (which does raw byte comparison ignoring collation) and still produces
    // correct case-insensitive matching via the standard evaluation path.
    session.execute("CREATE class CollateTest");
    session.execute("CREATE PROPERTY CollateTest.name STRING (COLLATE ci)");

    session.begin();
    session.execute("INSERT INTO CollateTest SET name = 'Alice'");
    session.execute("INSERT INTO CollateTest SET name = 'bob'");
    session.commit();

    session.begin();
    // Case-insensitive collation: 'alice' should match 'Alice'
    try (var rs = session.query(
        "SELECT FROM CollateTest WHERE name = 'alice'")) {
      var results = rs.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).<String>getProperty("name")).isEqualTo("Alice");
    }
    session.commit();
  }

  // ===== Le/Ge exact boundary (TC3) =====

  @Test
  public void testLeGeExactBoundary() {
    // Verifies that <= and >= return true when the property value exactly
    // equals the comparison value (comparePropertyTo returns 0), and that
    // strict < and > exclude the exact value.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("LeGeExact");
    clazz.createProperty("val", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("LeGeExact");
    e.setProperty("val", 42);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM LeGeExact WHERE val <= 42")) {
      assertThat(rs.stream().collect(Collectors.toList())).hasSize(1);
    }
    try (var rs = session.query("SELECT FROM LeGeExact WHERE val >= 42")) {
      assertThat(rs.stream().collect(Collectors.toList())).hasSize(1);
    }
    // Strict operators should exclude the exact boundary value
    try (var rs = session.query("SELECT FROM LeGeExact WHERE val < 42")) {
      assertThat(rs.stream().collect(Collectors.toList())).isEmpty();
    }
    try (var rs = session.query("SELECT FROM LeGeExact WHERE val > 42")) {
      assertThat(rs.stream().collect(Collectors.toList())).isEmpty();
    }
    session.commit();
  }

  // ----- Regression tests for lazy-MATCH path (YTDB-604) -----

  @Test
  public void testEvaluateResult_bareRidDoesNotForceLoadInGuard() {
    // Regression test for the YTDB-604 IC1/IC4 regression: when evaluate(Result, ctx)
    // is called with a bare-RID ResultInternal (the shape produced by the lazy-MATCH
    // ridIterator path), the fast-path guard must NOT call asEntityOrNull(), which
    // would trigger loadEntity for every WHERE predicate on an intermediate MATCH hop.
    // That force-load cost -16%/-17% throughput on ic1/ic4 in PR #863 profiling.
    //
    // The guard must use a non-loading check (asIdentifiableOrNull/getIdentifiable)
    // so bare-RID Results fail the instanceof EntityImpl test and fall through to
    // the standard path, which loads lazily via getProperty only when needed.
    //
    // We use a CountingResultInternal that records asEntityOrNull() calls: a correct
    // guard must never invoke it, regardless of the comparison outcome.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("LazyRidNoForceLoad");
    clazz.createProperty("age", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("LazyRidNoForceLoad");
    e.setProperty("age", 25);
    session.commit();
    var rid = e.getIdentity();

    var bc = parseBinaryCondition("age = 25");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var ri = new CountingResultInternal(session, (RID) rid);
    var outcome = bc.evaluate(ri, ctx);

    // Behavioral correctness: the comparison still produces the right boolean.
    assertThat(outcome).isTrue();

    // Perf invariant: the guard did not force-load the entity. Note the fallback
    // path (left.execute → getProperty) may have materialised the entity via the
    // non-force-loading lazy path — that is acceptable. The invariant here is
    // specifically that the GUARD did not invoke asEntityOrNull().
    assertThat(ri.asEntityOrNullCalls).isZero();
    session.rollback();
  }

  @Test
  public void testEvaluateResult_bareRidNoForceLoadEvenWhenComparisonFalse() {
    // Same invariant as testEvaluateResult_bareRidDoesNotForceLoadInGuard, but for
    // the comparison-is-false case: a force-load in the guard is wasteful whether
    // the row is accepted or rejected. The regression was per-predicate, so the
    // rejecting branch matters equally.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("LazyRidNoForceLoadFalse");
    clazz.createProperty("age", PropertyType.INTEGER);

    session.begin();
    var e = session.newEntity("LazyRidNoForceLoadFalse");
    e.setProperty("age", 25);
    session.commit();
    var rid = e.getIdentity();

    var bc = parseBinaryCondition("age = 99");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    session.begin();
    var ri = new CountingResultInternal(session, (RID) rid);
    var outcome = bc.evaluate(ri, ctx);

    assertThat(outcome).isFalse();
    assertThat(ri.asEntityOrNullCalls).isZero();
    session.rollback();
  }

  /**
   * Test double that records every {@link ResultInternal#asEntityOrNull()} call so
   * tests can assert whether the fast-path guard triggered a force-load.
   */
  private static final class CountingResultInternal extends ResultInternal {
    int asEntityOrNullCalls;

    CountingResultInternal(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
        RID rid) {
      super(session, rid);
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.db.record.record.Entity asEntityOrNull() {
      asEntityOrNullCalls++;
      return super.asEntityOrNull();
    }
  }

  private SQLBinaryCondition parseBinaryCondition(String booleanExpr) {
    try {
      var parser = new YouTrackDBSql(
          new java.io.ByteArrayInputStream(booleanExpr.getBytes()));
      var orBlock = parser.OrBlock();
      // "age = 25" parses to: OrBlock -> [AndBlock] -> [NotBlock(negate=false)]
      //                                             -> [SQLBinaryCondition]
      var andBlock = (SQLAndBlock) orBlock.subBlocks.get(0);
      var notBlock = (SQLNotBlock) andBlock.subBlocks.get(0);
      return (SQLBinaryCondition) notBlock.sub;
    } catch (ParseException e) {
      throw new AssertionError("Failed to parse expression: " + booleanExpr, e);
    }
  }
}
