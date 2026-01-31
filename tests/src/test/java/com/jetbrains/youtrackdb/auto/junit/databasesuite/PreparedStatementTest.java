/*
 * JUnit 4 version of PreparedStatementTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of PreparedStatementTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PreparedStatementTest extends BaseDBTest {

  private static PreparedStatementTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new PreparedStatementTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 30) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    session.execute("CREATE CLASS PreparedStatementTest1");
    session.begin();
    session.execute("insert into PreparedStatementTest1 (name, surname) values ('foo1', 'bar1')");
    session.execute(
        "insert into PreparedStatementTest1 (name, listElem) values ('foo2', ['bar2'])");
    session.commit();
  }

  /**
   * Original: testUnnamedParamTarget (line 41) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test01_UnnamedParamTarget() {
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
      Assert.assertTrue(expected.contains(doc.getProperty("name")));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testNamedParamTarget (line 59) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test02_NamedParamTarget() {
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
      Assert.assertTrue(expected.contains(doc.getProperty("name")));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testNamedParamTargetRid (line 79) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test03_NamedParamTargetRid() {
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
      Assert.assertEquals(doc.getIdentity(), record.getIdentity());
      Assert.assertEquals(doc.<Object>getProperty("name"), record.getProperty("name"));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testUnnamedParamTargetRid (line 102) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test04_UnnamedParamTargetRid() {
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
      Assert.assertEquals(doc.getIdentity(), record.getIdentity());
      Assert.assertEquals(doc.<Object>getProperty("name"), record.getProperty("name"));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testUnnamedParamFlat (line 124) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test05_UnnamedParamFlat() {
    var result = session.query("select from PreparedStatementTest1 where name = ?",
        "foo1");

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
  }

  /**
   * Original: testNamedParamFlat (line 138) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test06_NamedParamFlat() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session.query("select from PreparedStatementTest1 where name = :name", params);

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
  }

  /**
   * Original: testUnnamedParamInArray (line 154) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test07_UnnamedParamInArray() {
    session.begin();
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [?]", "foo1").toList();

    var found = false;
    for (var doc : result) {
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testNamedParamInArray (line 171) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test08_NamedParamInArray() {
    session.begin();
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo1");
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [:name]", params).toList();
    var found = false;
    for (var doc : result) {
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testUnnamedParamInArray2 (line 189) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test09_UnnamedParamInArray2() {
    session.begin();
    var result =
        session
            .query(
                "select from PreparedStatementTest1 where name in [?, 'antani']", "foo1").toList();

    var found = false;
    for (var doc : result) {
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testNamedParamInArray2 (line 206) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test10_NamedParamInArray2() {
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
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
    session.commit();
  }

  /**
   * Original: testSubqueryUnnamedParamFlat (line 226) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test11_SubqueryUnnamedParamFlat() {
    var result =
        session.query(
            "select from (select from PreparedStatementTest1 where name = ?) where name = ?",
            "foo1",
            "foo1");

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
  }

  /**
   * Original: testSubqueryNamedParamFlat (line 243) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test12_SubqueryNamedParamFlat() {
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
      Assert.assertEquals(doc.getProperty("name"), "foo1");
    }
    Assert.assertTrue(found);
  }

  /**
   * Original: testFunction (line 262) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test13_Function() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("one", 1);
    params.put("three", 3);
    var result = session.query("select max(:one, :three) as maximo", params);

    var found = false;
    while (result.hasNext()) {
      var doc = result.next();
      found = true;
      Assert.assertEquals(doc.<Object>getProperty("maximo"), 3);
    }
    Assert.assertTrue(found);
  }

  /**
   * Original: testSqlInjectionOnTarget (line 278) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/PreparedStatementTest.java
   */
  @Test
  public void test14_SqlInjectionOnTarget() {

    try {
      var result =
          session
              .query("select from ?", "PreparedStatementTest1 where name = 'foo'").toList();
      Assert.fail();
    } catch (Exception e) {

    }
  }

}
