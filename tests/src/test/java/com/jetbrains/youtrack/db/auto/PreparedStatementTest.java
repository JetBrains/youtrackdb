/*
 *
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
package com.jetbrains.youtrack.db.auto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class PreparedStatementTest extends BaseDBTest {

  @Parameters(value = "remote")
  public PreparedStatementTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
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

  @Test
  public void testUnnamedParamTarget() {
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

  @Test
  public void testNamedParamTarget() {
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

  @Test
  public void testNamedParamTargetRid() {
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

  @Test
  public void testUnnamedParamTargetRid() {
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

  @Test
  public void testNamedParamTargetDocument() {
    session.begin();

    var result =
        session
            .query("select from PreparedStatementTest1 limit 1").toList();
    var record = result.iterator().next();

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("inputRid", record);
    result = session.query("select from :inputRid", params).toList();
    var found = false;
    for (var doc : result) {
      found = true;
      Assert.assertEquals(doc.getIdentity(), record.getIdentity());
      Assert.assertEquals(doc.<Object>getProperty("name"), record.getProperty("name"));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  @Test
  public void testUnnamedParamTargetDocument() {
    session.begin();

    var result =
        session
            .query("select from PreparedStatementTest1 limit 1").toList();

    var record = result.iterator().next();
    result = session.query("select from ?", record).toList();
    var found = false;
    for (var doc : result) {
      found = true;
      Assert.assertEquals(doc.getIdentity(), record.getIdentity());
      Assert.assertEquals(doc.<Object>getProperty("name"), record.getProperty("name"));
    }
    Assert.assertTrue(found);
    session.commit();
  }

  @Test
  public void testUnnamedParamFlat() {
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

  @Test
  public void testNamedParamFlat() {
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

  @Test
  public void testUnnamedParamInArray() {
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

  @Test
  public void testNamedParamInArray() {
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

  @Test
  public void testUnnamedParamInArray2() {
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

  @Test
  public void testNamedParamInArray2() {
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

  @Test
  public void testSubqueryUnnamedParamFlat() {
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

  @Test
  public void testSubqueryNamedParamFlat() {
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

  @Test
  public void testFunction() {
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

  @Test
  public void testSqlInjectionOnTarget() {

    try {
      var result =
          session
              .query("select from ?", "PreparedStatementTest1 where name = 'foo'").toList();
      Assert.fail();
    } catch (Exception e) {

    }
  }
}
