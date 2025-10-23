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
package com.jetbrains.youtrackdb.auto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLSelectProjectionsTest extends BaseDBTest {

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateGraphData();
    generateProfiles();
  }

  @Test
  public void queryProjectionOk() {
    session.begin();
    var result =
        session
            .execute(
                "select nick, followings, followers from Profile where nick is defined and"
                    + " followings is defined and followers is defined")
            .toList();

    Assert.assertFalse(result.isEmpty());
    for (var r : result) {
      var colNames = r.getPropertyNames();
      Assert.assertEquals(colNames.size(), 3, "result: " + r);
      Assert.assertTrue(colNames.contains("nick"), "result: " + r);
      Assert.assertTrue(colNames.contains("followings"), "result: " + r);
      Assert.assertTrue(colNames.contains("followers"), "result: " + r);
    }
    session.commit();
  }

  @Test
  public void queryProjectionObjectLevel() {
    var result =
        session.query("select nick, followings, followers from Profile")
            .toList();

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 3);
    }
  }

  @Test
  public void queryProjectionLinkedAndFunction() {
    var result =
        session.query(
            "select name.toUpperCase(Locale.ENGLISH), address.city.country.name from"
                + " Profile").toList();

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 2);
      if (r.getProperty("name") != null) {
        Assert.assertEquals(
            ((String) r.getProperty("name")).toUpperCase(Locale.ENGLISH), r.getProperty("name"));
      }
    }
  }

  @Test
  public void queryProjectionSameFieldTwice() {
    var result =
        session
            .query(
                "select name, name.toUpperCase(Locale.ENGLISH) as name2 from Profile where name is"
                    + " not null").toList();

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 2);
      Assert.assertNotNull(r.getProperty("name"));
      Assert.assertNotNull(r.getProperty("name2"));
    }
  }

  @Test
  public void queryProjectionStaticValues() {
    var result =
        session
            .query(
                "select location.city.country.name as location, address.city.country.name as"
                    + " address from Profile where location.city.country.name is not null")
            .toList();

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertNotNull(r.getProperty("location"));
      Assert.assertNull(r.getProperty("address"));
    }
  }

  @Test
  public void queryProjectionPrefixAndAppend() {
    var result =
        executeQuery(
            "select *, name.prefix('Mr. ').append(' ').append(surname).append('!') as test"
                + " from Profile where name is not null");

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertEquals(
          r.getProperty("test").toString(),
          "Mr. " + r.getProperty("name") + " " + r.getProperty("surname") + "!");
    }
  }

  @Test
  public void queryProjectionFunctionsAndFieldOperators() {
    var result =
        executeQuery(
            "select name.append('.').prefix('Mr. ') as name from Profile where name is not"
                + " null");

    Assert.assertFalse(result.isEmpty());
    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 1);
      Assert.assertTrue(r.getProperty("name").toString().startsWith("Mr. "));
      //noinspection SingleCharacterStartsWith
      Assert.assertTrue(r.getProperty("name").toString().endsWith("."));
    }
  }

  @Test
  public void queryProjectionSimpleValues() {
    session.begin();
    var result = executeQuery("select 10, 'ciao' from Profile LIMIT 1");

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 2);
      Assert.assertEquals(((Integer) r.getProperty("10")).intValue(), 10);
      Assert.assertEquals(r.getProperty("\"ciao\""), "ciao");
    }
    session.commit();
  }

  @Test
  public void queryProjectionJSON() throws JsonProcessingException {
    final var tx = session.begin();
    var result = executeQuery("select @rid, @this.toJson() as json from Profile");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 2);
      final var jsonStr = r.getString("json");
      Assert.assertNotNull(jsonStr);

      tx.loadEntity(r.getProperty("@rid")).updateFromJSON(jsonStr);
    }
    session.commit();
  }

  public void queryProjectionRid() {
    var result = executeQuery("select @rid as rid FROM V");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 1);
      Assert.assertNotNull(r.getProperty("rid"));

      final RecordIdInternal rid = r.getProperty("rid");
      Assert.assertTrue(rid.isValidPosition());
    }
  }

  public void queryProjectionOrigin() {
    var result = executeQuery("select @raw as raw FROM V");
    Assert.assertFalse(result.isEmpty());

    for (var d : result) {
      Assert.assertTrue(d.getPropertyNames().size() <= 1);
      Assert.assertNotNull(d.getProperty("raw"));
    }
  }

  public void queryProjectionEval() {
    var result = executeQuery("select eval('1 + 4') as result");
    Assert.assertEquals(result.size(), 1);

    for (var r : result) {
      Assert.assertEquals(r.<Object>getProperty("result"), 5);
    }
  }

  public void queryProjectionContextArray() {
    session.begin();
    var result =
        executeQuery(
            "select $a[0] as a0, $a as a, @class from GraphCar let $a = outE() where outE().size() > 0");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.hasProperty("a"));
      Assert.assertTrue(r.hasProperty("a0"));

      final var a0doc = (EntityImpl) session.loadEntity(r.getProperty("a0"));
      final var identifiable = r.<Iterable<Identifiable>>getProperty("a").iterator().next();
      final var transaction = session.getActiveTransaction();
      final EntityImpl firstADoc = transaction.load(identifiable);

      Assert.assertTrue(
          EntityHelper.hasSameContentOf(a0doc, session, firstADoc, session, null));
    }
    session.commit();
  }

  public void ifNullFunction() {
    var result = executeQuery("SELECT ifnull('a', 'b') as ifnull");
    Assert.assertFalse(result.isEmpty());
    Assert.assertEquals(result.getFirst().getProperty("ifnull"), "a");

    result = executeQuery("SELECT ifnull('a', 'b', 'c') as ifnull");
    Assert.assertFalse(result.isEmpty());
    Assert.assertEquals(result.getFirst().getProperty("ifnull"), "c");

    result = executeQuery("SELECT ifnull(null, 'b') as ifnull");
    Assert.assertFalse(result.isEmpty());
    Assert.assertEquals(result.getFirst().getProperty("ifnull"), "b");
  }

  public void setAggregation() {
    var result = executeQuery("SELECT set(name) as set from OUser");
    Assert.assertEquals(result.size(), 1);
    for (var r : result) {
      Assert.assertTrue(MultiValue.isMultiValue(r.<Object>getProperty("set")));
      Assert.assertTrue(MultiValue.getSize(r.getProperty("set")) <= 3);
    }
  }

  public void projectionWithNoTarget() {
    var result = executeQuery("select 'Ay' as a , 'bEE'");
    Assert.assertEquals(result.size(), 1);
    for (var r : result) {
      Assert.assertEquals(r.getProperty("a"), "Ay");
      Assert.assertEquals(r.getProperty("\"bEE\""), "bEE");
    }

    result = executeQuery("select 'Ay' as a , 'bEE' as b");
    Assert.assertEquals(result.size(), 1);
    for (var d : result) {
      Assert.assertEquals(d.getProperty("a"), "Ay");
      Assert.assertEquals(d.getProperty("b"), "bEE");
    }

    result = executeQuery("select 'Ay' as a , 'bEE' as b fetchplan *:1");
    Assert.assertEquals(result.size(), 1);
    for (var d : result) {
      Assert.assertEquals(d.getProperty("a"), "Ay");
      Assert.assertEquals(d.getProperty("b"), "bEE");
    }

    result = executeQuery("select 'Ay' as a , 'bEE' fetchplan *:1");
    Assert.assertEquals(result.size(), 1);
    for (var d : result) {
      Assert.assertEquals(d.getProperty("a"), "Ay");
      Assert.assertEquals(d.getProperty("\"bEE\""), "bEE");
    }
  }

  @Test
  public void testSelectExcludeFunction() {
    try {
      graph.autoExecuteInTx(g -> g.createSchemaClass("A").createSchemaClass("B"));

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

      Assert.assertNotNull(res.getFirst().getProperty("a"));
      Assert.assertNotNull(res.getFirst().getProperty("b"));

      final var child = res.getFirst().getResult("child");

      Assert.assertNotNull(child.getProperty("c"));
      Assert.assertNull(child.getProperty("d"));
      Assert.assertNotNull(child.getProperty("e"));
      session.commit();

    } finally {
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

  @Test
  public void testSimpleExpandExclude() {
    try {
      graph.autoExecuteInTx(g -> g.createSchemaClass("A").createSchemaClass("B"));

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
      Assert.assertEquals(res.size(), 1);

      var root = res.getFirst();
      Assert.assertNotNull(root.getProperty("link"));

      Assert.assertNull(root.<Result>getProperty("link").getProperty("d"));
      Assert.assertNotNull(root.<Result>getProperty("link").getProperty("c"));
      Assert.assertNotNull(root.<Result>getProperty("link").getProperty("e"));

    } finally {
      session.commit();
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }
}
