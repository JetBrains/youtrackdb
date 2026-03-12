package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class PreparedStatementTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    session.execute("CREATE CLASS PreparedStatementTest1");
    session.begin();
    session.execute("insert into PreparedStatementTest1 (name, surname) values ('foo1', 'bar1')");
    session.execute(
        "insert into PreparedStatementTest1 (name, listElem) values ('foo2', ['bar2'])");
    session.commit();
  }

  @Test
  void testUnnamedParamTarget() {
    session.begin();
    var result =
        session
            .query("select from ?", "PreparedStatementTest1").toList();
    Set<String> expected = new HashSet<String>();
    expected.add("foo1");
    expected.add("foo2");
    var found = false;
    for (var doc : result) {
      found = true;
      assertTrue(expected.contains(doc.getProperty("name")));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testNamedParamTarget() {
    session.begin();
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("className", "PreparedStatementTest1");
    var result =
        session.query("select from :className", params).toList();

    Set<String> expected = new HashSet<String>();
    expected.add("foo1");
    expected.add("foo2");
    var found = false;
    for (var doc : result) {
      found = true;
      assertTrue(expected.contains(doc.getProperty("name")));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testNamedParamTargetRid() {
    session.begin();

    var result =
        session
            .query("select from PreparedStatementTest1 limit 1").toList();
    var record = result.iterator().next();

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("inputRid", record.getIdentity());
    result =
        session.query("select from :inputRid", params).toList();
    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals(record.getIdentity(), doc.getIdentity());
      assertEquals(record.getProperty("name"), doc.<Object>getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testUnnamedParamTargetRid() {
    session.begin();

    var result =
        session
            .query("select from PreparedStatementTest1 limit 1").toList();

    var record = result.iterator().next();
    result =
        session
            .query("select from ?", record.getIdentity()).toList();
    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals(record.getIdentity(), doc.getIdentity());
      assertEquals(record.getProperty("name"), doc.<Object>getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testUnnamedParamFlat() {
    var result = session.query("select from PreparedStatementTest1 where name = ?",
        "foo1");

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
  }

  @Test
  void testNamedParamFlat() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session.query("select from PreparedStatementTest1 where name = :name", params);

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
  }

  @Test
  void testUnnamedParamInArray() {
    session.begin();
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [?]", "foo1")
            .toList();

    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testNamedParamInArray() {
    session.begin();
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [:name]", params)
            .toList();
    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testUnnamedParamInArray2() {
    session.begin();
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [?, 'antani']", "foo1")
            .toList();

    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testNamedParamInArray2() {
    session.begin();
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [:name, 'antani']", params)
            .toList();

    var found = false;
    for (var doc : result) {
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
    session.commit();
  }

  @Test
  void testSubqueryUnnamedParamFlat() {
    var result =
        session.query(
            "select from (select from PreparedStatementTest1 where name = ?) where name = ?",
            "foo1",
            "foo1");

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
  }

  @Test
  void testSubqueryNamedParamFlat() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session.query(
            "select from (select from PreparedStatementTest1 where name = :name) where name ="
                + " :name",
            params);

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      assertEquals("foo1", doc.getProperty("name"));
    }
    assertTrue(found);
  }

  @Test
  void testFunction() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("one", 1);
    params.put("three", 3);
    var result = session.query("select max(:one, :three) as maximo", params);

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      assertEquals(3, doc.<Object>getProperty("maximo"));
    }
    assertTrue(found);
  }

  @Test
  void testSqlInjectionOnTarget() {
    assertThrows(Exception.class, () -> session
        .query("select from ?", "PreparedStatementTest1 where name = 'foo'").close());
  }
}
