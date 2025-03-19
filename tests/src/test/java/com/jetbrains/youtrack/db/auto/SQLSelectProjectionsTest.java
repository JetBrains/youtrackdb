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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SQLSelectProjectionsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLSelectProjectionsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateGraphData();
    generateProfiles();
  }

  @Test
  public void queryProjectionOk() {
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
    var result = executeQuery("select 10, 'ciao' from Profile LIMIT 1");

    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 2);
      Assert.assertEquals(((Integer) r.getProperty("10")).intValue(), 10);
      Assert.assertEquals(r.getProperty("'ciao'"), "ciao");
    }
  }

  @Test
  public void queryProjectionJSON() {
    var result = executeQuery("select @this.toJson() as json from Profile");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 1);
      Assert.assertNotNull(r.getProperty("json"));

      new EntityImpl(session).updateFromJSON((String) r.getProperty("json"));
    }
  }

  public void queryProjectionRid() {
    var result = executeQuery("select @rid as rid FROM V");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.getPropertyNames().size() <= 1);
      Assert.assertNotNull(r.getProperty("rid"));

      final RecordId rid = r.getProperty("rid");
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
    var result =
        executeQuery("select $a[0] as a0, $a as a from V let $a = outE() where outE().size() > 0");
    Assert.assertFalse(result.isEmpty());

    for (var r : result) {
      Assert.assertTrue(r.hasProperty("a"));
      Assert.assertTrue(r.hasProperty("a0"));

      final EntityImpl a0doc = r.getProperty("a0");
      final EntityImpl firstADoc =
          r.<Iterable<Identifiable>>getProperty("a").iterator().next().getRecord(session);

      Assert.assertTrue(
          EntityHelper.hasSameContentOf(a0doc, session, firstADoc, session, null));
    }
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
      Assert.assertEquals(r.getProperty("'bEE'"), "bEE");
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
      Assert.assertEquals(d.getProperty("'bEE'"), "bEE");
    }
  }

  @Test
  public void testSelectExcludeFunction() {
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

      var res =
          executeQuery("select a,b, child.exclude('d') as child from " + rootElement.getIdentity());

      Assert.assertNotNull(res.getFirst().getProperty("a"));
      Assert.assertNotNull(res.getFirst().getProperty("b"));
      Assert.assertNotNull(res.getFirst().<EntityImpl>getProperty("child").getProperty("c"));
      Assert.assertNull(res.getFirst().<EntityImpl>getProperty("child").getProperty("d"));
      Assert.assertNotNull(res.getFirst().<EntityImpl>getProperty("child").getProperty("e"));
    } finally {
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

  @Test
  public void testSimpleExpandExclude() {
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
      childElement.setProperty("root", List.of(rootElement), PropertyType.LINKLIST);

      session.commit();

      var res =
          executeQuery(
              "select child.exclude('d') as link from (select expand(root) from "
                  + childElement.getIdentity()
                  + " )");
      Assert.assertEquals(res.size(), 1);

      var root = res.getFirst();
      Assert.assertNotNull(root.getProperty("link"));

      Assert.assertNull(root.<EntityImpl>getProperty("link").getProperty("d"));
      Assert.assertNotNull(root.<EntityImpl>getProperty("link").getProperty("c"));
      Assert.assertNotNull(root.<EntityImpl>getProperty("link").getProperty("e"));

    } finally {
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

  @Test
  public void testTempRIDsAreNotRecycledInResultSet() {
    var resultset =
        executeQuery("select name, $l as l from OUser let $l = (select name from OuSer)");

    Assert.assertNotNull(resultset);

    Set<RID> rids = new HashSet<>();
    for (var d : resultset) {
      final var rid = d.getIdentity();
      Assert.assertFalse(rids.contains(rid));

      rids.add(rid);

      final List<Identifiable> embeddedList = d.getProperty("l");
      Assert.assertNotNull(embeddedList);
      Assert.assertFalse(embeddedList.isEmpty());

      for (var embedded : embeddedList) {
        if (embedded != null) {
          final var embeddedRid = embedded.getIdentity();

          Assert.assertFalse(rids.contains(embeddedRid));
          rids.add(rid);
        }
      }
    }
  }
}
