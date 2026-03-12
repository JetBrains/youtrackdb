package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for SQL SELECT projection operations: field projections, functions,
 * field operators, static values, JSON, RID, eval, context arrays, ifnull,
 * set aggregation, exclude, and expand-exclude.
 *
 * <p>Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest
 */
class SQLSelectProjectionsTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    generateGraphData();
    generateProfiles();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionOk
  @Test
  @Order(1)
  void queryProjectionOk() {
    session.begin();
    var result =
        session
            .execute(
                "select nick, followings, followers from Profile where nick is defined and"
                    + " followings is defined and followers is defined")
            .toList();

    assertFalse(result.isEmpty());
    for (var r : result) {
      var colNames = r.getPropertyNames();
      assertEquals(3, colNames.size(), "result: " + r);
      assertTrue(colNames.contains("nick"), "result: " + r);
      assertTrue(colNames.contains("followings"), "result: " + r);
      assertTrue(colNames.contains("followers"), "result: " + r);
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionObjectLevel
  @Test
  @Order(2)
  void queryProjectionObjectLevel() {
    var result =
        session.query("select nick, followings, followers from Profile")
            .toList();

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 3);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionLinkedAndFunction
  @Test
  @Order(3)
  void queryProjectionLinkedAndFunction() {
    var result =
        session.query(
            "select name.toUpperCase(Locale.ENGLISH), address.city.country.name from"
                + " Profile")
            .toList();

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 2);
      if (r.getProperty("name") != null) {
        assertEquals(
            r.getProperty("name"),
            ((String) r.getProperty("name")).toUpperCase(Locale.ENGLISH));
      }
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionSameFieldTwice
  @Test
  @Order(4)
  void queryProjectionSameFieldTwice() {
    var result =
        session
            .query(
                "select name, name.toUpperCase(Locale.ENGLISH) as name2 from Profile where name is"
                    + " not null")
            .toList();

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 2);
      assertNotNull(r.getProperty("name"));
      assertNotNull(r.getProperty("name2"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionStaticValues
  @Test
  @Order(5)
  void queryProjectionStaticValues() {
    var result =
        session
            .query(
                "select location.city.country.name as location, address.city.country.name as"
                    + " address from Profile where location.city.country.name is not null")
            .toList();

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertNotNull(r.getProperty("location"));
      assertNull(r.getProperty("address"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionPrefixAndAppend
  @Test
  @Order(6)
  void queryProjectionPrefixAndAppend() {
    var result =
        executeQuery(
            "select *, name.prefix('Mr. ').append(' ').append(surname).append('!') as test"
                + " from Profile where name is not null");

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertEquals(
          "Mr. " + r.getProperty("name") + " " + r.getProperty("surname") + "!",
          r.getProperty("test").toString());
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionFunctionsAndFieldOperators
  @Test
  @Order(7)
  void queryProjectionFunctionsAndFieldOperators() {
    var result =
        executeQuery(
            "select name.append('.').prefix('Mr. ') as name from Profile where name is not"
                + " null");

    assertFalse(result.isEmpty());
    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 1);
      assertTrue(r.getProperty("name").toString().startsWith("Mr. "));
      assertTrue(r.getProperty("name").toString().endsWith("."));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionSimpleValues
  @Test
  @Order(8)
  void queryProjectionSimpleValues() {
    session.begin();
    var result = executeQuery("select 10, 'ciao' from Profile LIMIT 1");

    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 2);
      assertEquals(10, ((Integer) r.getProperty("10")).intValue());
      assertEquals("ciao", r.getProperty("\"ciao\""));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionJSON
  @Test
  @Order(9)
  void queryProjectionJSON() throws JsonProcessingException {
    final var tx = session.begin();
    var result = executeQuery("select @rid, @this.toJson() as json from Profile");
    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 2);
      final var jsonStr = r.getString("json");
      assertNotNull(jsonStr);

      tx.loadEntity(r.getProperty("@rid")).updateFromJSON(jsonStr);
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionRid
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(10)
  void queryProjectionRid() {
    var result = executeQuery("select @rid as rid FROM V");
    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.getPropertyNames().size() <= 1);
      assertNotNull(r.getProperty("rid"));

      final RecordIdInternal rid = r.getProperty("rid");
      assertTrue(rid.isValidPosition());
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionOrigin
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(11)
  void queryProjectionOrigin() {
    var result = executeQuery("select @raw as raw FROM V");
    assertFalse(result.isEmpty());

    for (var d : result) {
      assertTrue(d.getPropertyNames().size() <= 1);
      assertNotNull(d.getProperty("raw"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionEval
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(12)
  void queryProjectionEval() {
    var result = executeQuery("select eval('1 + 4') as result");
    assertEquals(1, result.size());

    for (var r : result) {
      assertEquals(5, r.<Object>getProperty("result"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#queryProjectionContextArray
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(13)
  void queryProjectionContextArray() {
    session.begin();
    var result =
        executeQuery(
            "select $a[0] as a0, $a as a, @class from GraphCar let $a = outE() where outE().size() > 0");
    assertFalse(result.isEmpty());

    for (var r : result) {
      assertTrue(r.hasProperty("a"));
      assertTrue(r.hasProperty("a0"));

      final var a0doc = (EntityImpl) session.loadEntity(r.getProperty("a0"));
      final var identifiable = r.<Iterable<Identifiable>>getProperty("a").iterator().next();
      final var transaction = session.getActiveTransaction();
      final EntityImpl firstADoc = transaction.load(identifiable);

      assertTrue(
          EntityHelper.hasSameContentOf(a0doc, session, firstADoc, session, null));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#ifNullFunction
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(14)
  void ifNullFunction() {
    var result = executeQuery("SELECT ifnull('a', 'b') as ifnull");
    assertFalse(result.isEmpty());
    assertEquals("a", result.getFirst().getProperty("ifnull"));

    result = executeQuery("SELECT ifnull('a', 'b', 'c') as ifnull");
    assertFalse(result.isEmpty());
    assertEquals("c", result.getFirst().getProperty("ifnull"));

    result = executeQuery("SELECT ifnull(null, 'b') as ifnull");
    assertFalse(result.isEmpty());
    assertEquals("b", result.getFirst().getProperty("ifnull"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#setAggregation
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(15)
  void setAggregation() {
    var result = executeQuery("SELECT set(name) as set from OUser");
    assertEquals(1, result.size());
    for (var r : result) {
      assertTrue(MultiValue.isMultiValue(r.<Object>getProperty("set")));
      assertTrue(MultiValue.getSize(r.getProperty("set")) <= 3);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#projectionWithNoTarget
  // Note: originally had no explicit @Test (relied on class-level @Test); added for JUnit 5
  @Test
  @Order(16)
  void projectionWithNoTarget() {
    var result = executeQuery("select 'Ay' as a , 'bEE'");
    assertEquals(1, result.size());
    for (var r : result) {
      assertEquals("Ay", r.getProperty("a"));
      assertEquals("bEE", r.getProperty("\"bEE\""));
    }

    result = executeQuery("select 'Ay' as a , 'bEE' as b");
    assertEquals(1, result.size());
    for (var d : result) {
      assertEquals("Ay", d.getProperty("a"));
      assertEquals("bEE", d.getProperty("b"));
    }

    result = executeQuery("select 'Ay' as a , 'bEE' as b fetchplan *:1");
    assertEquals(1, result.size());
    for (var d : result) {
      assertEquals("Ay", d.getProperty("a"));
      assertEquals("bEE", d.getProperty("b"));
    }

    result = executeQuery("select 'Ay' as a , 'bEE' fetchplan *:1");
    assertEquals(1, result.size());
    for (var d : result) {
      assertEquals("Ay", d.getProperty("a"));
      assertEquals("bEE", d.getProperty("\"bEE\""));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#testSelectExcludeFunction
  @Test
  @Order(17)
  void testSelectExcludeFunction() {
    try {
      session.createClass("A");
      session.createClass("B");

      session.begin();
      var rootElement = session.newInstance("A");
      var childElement = session.newInstance("B");

      rootElement.setProperty("a", "a");
      rootElement.setProperty("b", "b");

      childElement.setProperty("c", "c");
      childElement.setProperty("d", "d");
      childElement.setProperty("e", "e");

      rootElement.setProperty("child", childElement, PropertyType.LINK);

      session.commit();

      session.begin();
      var res =
          executeQuery("select a,b, child.exclude('d') as child from " + rootElement.getIdentity());

      assertNotNull(res.getFirst().getProperty("a"));
      assertNotNull(res.getFirst().getProperty("b"));

      final var child = res.getFirst().getResult("child");

      assertNotNull(child.getProperty("c"));
      assertNull(child.getProperty("d"));
      assertNotNull(child.getProperty("e"));
      session.commit();

    } finally {
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLSelectProjectionsTest#testSimpleExpandExclude
  @Test
  @Order(18)
  void testSimpleExpandExclude() {
    try {
      session.createClass("A");
      session.createClass("B");

      session.begin();
      var rootElement = session.newInstance("A");
      rootElement.setProperty("a", "a");
      rootElement.setProperty("b", "b");

      var childElement = session.newInstance("B");
      childElement.setProperty("c", "c");
      childElement.setProperty("d", "d");
      childElement.setProperty("e", "e");

      rootElement.setProperty("child", childElement, PropertyType.LINK);
      childElement.setProperty("root", session.newLinkList(List.of(rootElement)),
          PropertyType.LINKLIST);

      session.commit();

      session.begin();
      var res =
          executeQuery(
              "select child.exclude('d') as link from (select expand(root) from "
                  + childElement.getIdentity()
                  + " )");
      assertEquals(1, res.size());

      var root = res.getFirst();
      assertNotNull(root.getProperty("link"));

      assertNull(root.<Result>getProperty("link").getProperty("d"));
      assertNotNull(root.<Result>getProperty("link").getProperty("c"));
      assertNotNull(root.<Result>getProperty("link").getProperty("e"));

    } finally {
      session.commit();
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }
}
