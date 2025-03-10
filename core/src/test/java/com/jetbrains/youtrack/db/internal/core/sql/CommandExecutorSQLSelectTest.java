/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.Ignore;
import org.junit.Test;

public class CommandExecutorSQLSelectTest extends DbTestBase {

  private static final int ORDER_SKIP_LIMIT_ITEMS = 100 * 1000;

  public void beforeTest() throws Exception {
    super.beforeTest();

    session.command("CREATE class foo").close();
    session.command("CREATE property foo.name STRING").close();
    session.command("CREATE property foo.bar INTEGER").close();
    session.command("CREATE property foo.address EMBEDDED").close();
    session.command("CREATE property foo.comp STRING").close();
    session.command("CREATE property foo.osite INTEGER").close();

    session.command("CREATE index foo_name on foo (name) NOTUNIQUE").close();
    session.command("CREATE index foo_bar on foo (bar) NOTUNIQUE").close();
    session.command("CREATE index foo_comp_osite on foo (comp, osite) NOTUNIQUE").close();

    session.begin();
    session.command(
            "insert into foo (name, bar, address) values ('a', 1, {'street':'1st street',"
                + " 'city':'NY', '@type':'d'})")
        .close();
    session.command("insert into foo (name, bar) values ('b', 2)").close();
    session.command("insert into foo (name, bar) values ('c', 3)").close();

    session.command("insert into foo (comp, osite) values ('a', 1)").close();
    session.command("insert into foo (comp, osite) values ('b', 2)").close();
    session.commit();

    session.command("CREATE class bar").close();

    session.begin();
    session.command("insert into bar (name, foo) values ('a', 1)").close();
    session.command("insert into bar (name, foo) values ('b', 2)").close();
    session.command("insert into bar (name, foo) values ('c', 3)").close();
    session.command("insert into bar (name, foo) values ('d', 4)").close();
    session.command("insert into bar (name, foo) values ('e', 5)").close();
    session.command("insert into bar (name, foo) values ('f', 1)").close();
    session.command("insert into bar (name, foo) values ('g', 2)").close();
    session.command("insert into bar (name, foo) values ('h', 3)").close();
    session.command("insert into bar (name, foo) values ('i', 4)").close();
    session.command("insert into bar (name, foo) values ('j', 5)").close();
    session.command("insert into bar (name, foo) values ('k', 1)").close();
    session.command("insert into bar (name, foo) values ('l', 2)").close();
    session.command("insert into bar (name, foo) values ('m', 3)").close();
    session.command("insert into bar (name, foo) values ('n', 4)").close();
    session.command("insert into bar (name, foo) values ('o', 5)").close();
    session.commit();

    session.command("CREATE class ridsorttest clusters 1").close();
    session.command("CREATE property ridsorttest.name INTEGER").close();
    session.command("CREATE index ridsorttest_name on ridsorttest (name) NOTUNIQUE").close();

    session.begin();
    session.command("insert into ridsorttest (name) values (1)").close();
    session.command("insert into ridsorttest (name) values (5)").close();
    session.command("insert into ridsorttest (name) values (3)").close();
    session.command("insert into ridsorttest (name) values (4)").close();
    session.command("insert into ridsorttest (name) values (1)").close();
    session.command("insert into ridsorttest (name) values (8)").close();
    session.command("insert into ridsorttest (name) values (6)").close();
    session.commit();

    session.command("CREATE class unwindtest").close();

    session.begin();
    session.command("insert into unwindtest (name, coll) values ('foo', ['foo1', 'foo2'])").close();
    session.command("insert into unwindtest (name, coll) values ('bar', ['bar1', 'bar2'])").close();
    session.commit();

    session.command("CREATE class unwindtest2").close();
    session.begin();
    session.command("insert into unwindtest2 (name, coll) values ('foo', [])").close();
    session.commit();

    session.command("CREATE class `edge`").close();

    session.command("CREATE class TestFromInSquare").close();
    session.begin();
    session.command("insert into TestFromInSquare set tags = {' from ':'foo',' to ':'bar'}")
        .close();
    session.commit();

    session.command("CREATE class TestMultipleClusters").close();
    session.command("alter class TestMultipleClusters add_cluster testmultipleclusters1 ").close();
    session.command("alter class TestMultipleClusters add_cluster testmultipleclusters2 ").close();

    session.begin();
    session.command("insert into TestMultipleClusters set name = 'aaa'").close();
    session.command("insert into TestMultipleClusters set name = 'foo'").close();
    session.command("insert into TestMultipleClusters set name = 'bar'").close();
    session.commit();

    session.command("CREATE class TestUrl").close();
    session.begin();
    session.command("insert into TestUrl content { \"url\": \"http://www.google.com\" }").close();
    session.commit();

    session.command("CREATE class TestParams").close();
    session.begin();
    session.command("insert into TestParams  set name = 'foo', surname ='foo', active = true")
        .close();
    session.command("insert into TestParams  set name = 'foo', surname ='bar', active = false")
        .close();
    session.commit();

    session.command("CREATE class TestParamsEmbedded").close();
    session.begin();
    session.command(
            "insert into TestParamsEmbedded set emb = {  \n"
                + "            \"count\":0,\n"
                + "            \"testupdate\":\"1441258203385\"\n"
                + "         }")
        .close();
    session.command(
            "insert into TestParamsEmbedded set emb = {  \n"
                + "            \"count\":1,\n"
                + "            \"testupdate\":\"1441258203385\"\n"
                + "         }")
        .close();
    session.commit();

    session.command("CREATE class TestBacktick").close();
    session.begin();
    session.command("insert into TestBacktick  set foo = 1, bar = 2, `foo-bar` = 10").close();
    session.commit();

    // /*** from issue #2743
    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("alphabet")) {
      schema.createClass("alphabet", 1, null);
    }

    var iter = session.browseClass("alphabet");
    while (iter.hasNext()) {
      iter.next().delete();
    }

    // add 26 entries: { "letter": "A", "number": 0 }, ... { "letter": "Z", "number": 25 }

    var rowModel = "{\"letter\": \"%s\", \"number\": %d}";
    for (var i = 0; i < 26; ++i) {
      session.begin();
      var l = String.valueOf((char) ('A' + i));
      var json = String.format(rowModel, l, i);
      EntityImpl doc = session.newInstance("alphabet");
      doc.updateFromJSON(json);

      session.commit();
    }

    session.command("create class OCommandExecutorSQLSelectTest_aggregations").close();
    session.begin();
    session.command(
            "insert into OCommandExecutorSQLSelectTest_aggregations set data = [{\"size\": 0},"
                + " {\"size\": 0}, {\"size\": 30}, {\"size\": 50}, {\"size\": 50}]")
        .close();
    session.commit();
  }

  private static void initCollateOnLinked(DatabaseSession db) {
    db.command("CREATE CLASS CollateOnLinked").close();
    db.command("CREATE CLASS CollateOnLinked2").close();
    db.command("CREATE PROPERTY CollateOnLinked.name String").close();
    db.command("ALTER PROPERTY CollateOnLinked.name collate ci").close();

    db.begin();
    var doc = (EntityImpl) db.newEntity("CollateOnLinked");
    doc.field("name", "foo");

    var doc2 = (EntityImpl) db.newEntity("CollateOnLinked2");
    doc2.field("linked", doc.getIdentity());

    db.commit();
  }

  private static void initComplexFilterInSquareBrackets(DatabaseSession db) {
    db.command("CREATE CLASS ComplexFilterInSquareBrackets1").close();
    db.command("CREATE CLASS ComplexFilterInSquareBrackets2").close();

    db.begin();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n1', value = 1").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n2', value = 2").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n3', value = 3").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n4', value = 4").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n5', value = 5").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n6', value = -1").close();
    db.command("INSERT INTO ComplexFilterInSquareBrackets1 SET name = 'n7', value = null").close();
    db.command(
            "INSERT INTO ComplexFilterInSquareBrackets2 SET collection = (select from"
                + " ComplexFilterInSquareBrackets1)")
        .close();
    db.commit();
  }

  private static void initFilterAndOrderByTest(DatabaseSession db) {
    db.command("CREATE CLASS FilterAndOrderByTest").close();
    db.command("CREATE PROPERTY FilterAndOrderByTest.dc DATETIME").close();
    db.command("CREATE PROPERTY FilterAndOrderByTest.active BOOLEAN").close();
    db.command(
            "CREATE INDEX FilterAndOrderByTest.active ON FilterAndOrderByTest (active) NOTUNIQUE")
        .close();

    db.begin();
    db.command("insert into FilterAndOrderByTest SET dc = '2010-01-05 12:00:00:000', active = true")
        .close();
    db.command(
            "insert into FilterAndOrderByTest SET dc = '2010-05-05 14:00:00:000', active = false")
        .close();
    db.command("insert into FilterAndOrderByTest SET dc = '2009-05-05 16:00:00:000', active = true")
        .close();
    db.command(
            "insert into FilterAndOrderByTest SET dc = '2008-05-05 12:00:00:000', active = false")
        .close();
    db.command(
            "insert into FilterAndOrderByTest SET dc = '2014-05-05 14:00:00:000', active = false")
        .close();
    db.command("insert into FilterAndOrderByTest SET dc = '2016-01-05 14:00:00:000', active = true")
        .close();
    db.commit();
  }

  private static void initMaxLongNumber(DatabaseSession db) {
    db.command("CREATE class MaxLongNumberTest").close();

    db.begin();
    db.command("insert into MaxLongNumberTest set last = 1").close();
    db.command("insert into MaxLongNumberTest set last = null").close();
    db.command("insert into MaxLongNumberTest set last = 958769876987698").close();
    db.command("insert into MaxLongNumberTest set foo = 'bar'").close();
    db.commit();
  }

  private static void initLinkListSequence(DatabaseSession db) {
    db.command("CREATE class LinkListSequence").close();

    db.begin();
    db.command("insert into LinkListSequence set name = '1.1.1'").close();
    db.command("insert into LinkListSequence set name = '1.1.2'").close();
    db.command("insert into LinkListSequence set name = '1.2.1'").close();
    db.command("insert into LinkListSequence set name = '1.2.2'").close();
    db.command(
            "insert into LinkListSequence set name = '1.1', children = (select from"
                + " LinkListSequence where name like '1.1.%')")
        .close();
    db.command(
            "insert into LinkListSequence set name = '1.2', children = (select from"
                + " LinkListSequence where name like '1.2.%')")
        .close();
    db.command(
            "insert into LinkListSequence set name = '1', children = (select from LinkListSequence"
                + " where name in ['1.1', '1.2'])")
        .close();
    db.command("insert into LinkListSequence set name = '2'").close();
    db.command(
            "insert into LinkListSequence set name = 'root', children = (select from"
                + " LinkListSequence where name in ['1', '1'])")
        .close();
    db.commit();
  }

  private static void initMatchesWithRegex(DatabaseSession db) {
    db.command("CREATE class matchesstuff").close();

    db.begin();
    db.command("insert into matchesstuff (name, foo) values ('admin[name]', 1)").close();
    db.commit();
  }

  private static void initDistinctLimit(DatabaseSession db) {
    db.command("CREATE class DistinctLimit").close();

    db.begin();
    db.command("insert into DistinctLimit (name, foo) values ('one', 1)").close();
    db.command("insert into DistinctLimit (name, foo) values ('one', 1)").close();
    db.command("insert into DistinctLimit (name, foo) values ('two', 2)").close();
    db.command("insert into DistinctLimit (name, foo) values ('two', 2)").close();
    db.commit();
  }

  private static void initDatesSet(DatabaseSession db) {
    db.command("create class OCommandExecutorSQLSelectTest_datesSet").close();
    db.command("create property OCommandExecutorSQLSelectTest_datesSet.foo embeddedlist date")
        .close();
    db.begin();
    db.command("insert into OCommandExecutorSQLSelectTest_datesSet set foo = ['2015-10-21']")
        .close();
    db.commit();
  }

  private static void initMassiveOrderSkipLimit(DatabaseSessionInternal db) {
    db.getMetadata().getSchema().createClass("MassiveOrderSkipLimit", 1, null);
    var fieldValue =
        "laskdf lkajsd flaksjdf laksjd flakjsd flkasjd flkajsd flkajsd flkajsd flkajsd flkajsd"
            + " flkjas;lkj a;ldskjf laksdj asdklasdjf lskdaj fladsd";
    for (var i = 0; i < ORDER_SKIP_LIMIT_ITEMS; i++) {
      db.begin();
      var doc = (EntityImpl) db.newEntity("MassiveOrderSkipLimit");
      doc.field("nnum", i);
      doc.field("aaa", fieldValue);
      doc.field("bbb", fieldValue);
      doc.field("bbba", fieldValue);
      doc.field("daf", fieldValue);
      doc.field("dfgd", fieldValue);
      doc.field("dgd", fieldValue);

      db.commit();
    }
  }

  private static void initExpandSkipLimit(DatabaseSession db) {
    db.command("create class ExpandSkipLimit clusters 1").close();

    for (var i = 0; i < 5; i++) {
      db.begin();
      var doc = (EntityImpl) db.newEntity("ExpandSkipLimit");
      doc.field("nnum", i);

      var parent = (EntityImpl) db.newEntity("ExpandSkipLimit");
      parent.field("parent", true);
      parent.field("num", i);
      parent.field("linked", doc);

      db.commit();
    }
  }

  private static ProfilerStub getProfilerInstance() {
    return ProfilerStub.INSTANCE;
  }

  @Test
  public void testUseIndexWithOrderBy2() throws Exception {
    var qResult =
        session.query(
            "select * from foo where address.city = 'NY' order by name ASC").toList();
    assertEquals(1, qResult.size());
  }

  @Ignore("Should be rewritten using the new JFR-based monitoring")
  @Test
  public void testUseIndexWithOr() throws Exception {
    var idxUsagesBefore = indexUsages(session);

    List<EntityImpl> qResult =
        session.command(new CommandSQL("select * from foo where bar = 2 or name ='a' and bar >= 0"))
            .execute(session);

    assertEquals(2, qResult.size());
    assertEquals(indexUsages(session), idxUsagesBefore + 2);
  }

  @Test
  public void testDoNotUseIndexWithOrNotIndexed() throws Exception {

    var idxUsagesBefore = indexUsages(session);

    List<EntityImpl> qResult =
        session.command(new CommandSQL("select * from foo where bar = 2 or notIndexed = 3"))
            .execute(session);

    assertEquals(indexUsages(session), idxUsagesBefore);
  }

  @Ignore("Should be rewritten using the new JFR-based monitoring")
  @Test
  public void testCompositeIndex() {
    var idxUsagesBefore = indexUsages(session);

    List<EntityImpl> qResult =
        session.command(new CommandSQL("select * from foo where comp = 'a' and osite = 1")).execute(
            session);

    assertEquals(1, qResult.size());
    assertEquals(indexUsages(session), idxUsagesBefore + 1);
  }

  @Test
  public void testProjection() {
    try (var rs = session.query("select a from foo where name = 'a' or bar = 1")) {
      if (!session.isRemote()) {
        assertEquals(2, indexUsages(rs.getExecutionPlan()));
      }

      var qResult = rs.toList();
      assertEquals(1, qResult.size());
    }
  }

  @Test
  public void testProjection2() {
    try (var rs = session.query("select a from foo where name = 'a' or bar = 2")) {
      if (!session.isRemote()) {
        assertEquals(2, indexUsages(rs.getExecutionPlan()));
      }

      var qResult = rs.toList();
      assertEquals(2, qResult.size());
    }
  }

  @Ignore("Should be rewritten using the new JFR-based monitoring")
  @Test
  public void testCompositeIndex2() {
    var idxUsagesBefore = indexUsages(session);

    List<EntityImpl> qResult =
        session.command(
                new CommandSQL("select * from foo where (comp = 'a' and osite = 1) or name = 'a'"))
            .execute(session);

    assertEquals(2, qResult.size());
    assertEquals(indexUsages(session), idxUsagesBefore + 2);
  }

  @Test
  public void testOperatorPriority() {

    var qResult =
        session.command("select * from foo where name ='a' and bar = 1000 or name = 'b'").stream()
            .toList();

    var qResult2 =
        session.command("select * from foo where name = 'b' or name ='a' and bar = 1000").stream()
            .toList();

    var qResult3 =
        session.command("select * from foo where name = 'b' or (name ='a' and bar = 1000)").stream()
            .toList();

    var qResult4 =
        session.command("select * from foo where (name ='a' and bar = 1000) or name = 'b'").stream()
            .toList();

    var qResult5 =
        session.command("select * from foo where ((name ='a' and bar = 1000) or name = 'b')")
            .stream()
            .toList();

    var qResult6 =
        session.command("select * from foo where ((name ='a' and (bar = 1000)) or name = 'b')")
            .stream()
            .toList();

    var qResult7 =
        session.command("select * from foo where (((name ='a' and bar = 1000)) or name = 'b')")
            .stream()
            .toList();

    var qResult8 =
        session
            .command("select * from foo where (((name ='a' and bar = 1000)) or (name = 'b'))")
            .stream()
            .toList();

    assertEquals(qResult.size(), qResult2.size());
    assertEquals(qResult.size(), qResult3.size());
    assertEquals(qResult.size(), qResult4.size());
    assertEquals(qResult.size(), qResult5.size());
    assertEquals(qResult.size(), qResult6.size());
    assertEquals(qResult.size(), qResult7.size());
    assertEquals(qResult.size(), qResult8.size());
  }

  @Test
  public void testOperatorPriority2() {
    var qResult =
        session
            .command(
                "select * from bar where name ='a' and foo = 1 or name='b' or name='c' and foo = 3"
                    + " and other = 4 or name = 'e' and foo = 5 or name = 'm' and foo > 2 ")
            .stream()
            .toList();

    var qResult2 =
        session
            .command(
                "select * from bar where (name ='a' and foo = 1) or name='b' or (name='c' and foo ="
                    + " 3 and other = 4) or (name = 'e' and foo = 5) or (name = 'm' and foo > 2)")
            .stream()
            .toList();

    var qResult3 =
        session
            .command(
                "select * from bar where (name ='a' and foo = 1) or (name='b') or (name='c' and foo"
                    + " = 3 and other = 4) or (name ='e' and foo = 5) or (name = 'm' and foo > 2)")
            .stream()
            .toList();

    var qResult4 =
        session
            .command(
                "select * from bar where (name ='a' and foo = 1) or ((name='b') or (name='c' and"
                    + " foo = 3 and other = 4)) or (name = 'e' and foo = 5) or (name = 'm' and foo"
                    + " > 2)")
            .stream()
            .toList();

    var qResult5 =
        session
            .command(
                "select * from bar where (name ='a' and foo = 1) or ((name='b') or (name='c' and"
                    + " foo = 3 and other = 4) or (name = 'e' and foo = 5)) or (name = 'm' and foo"
                    + " > 2)")
            .stream()
            .toList();

    assertEquals(qResult.size(), qResult2.size());
    assertEquals(qResult.size(), qResult3.size());
    assertEquals(qResult.size(), qResult4.size());
    assertEquals(qResult.size(), qResult5.size());
  }

  @Test
  public void testOperatorPriority3() {
    var qResult =
        session
            .command(
                "select * from bar where name <> 'a' and foo = 1 or name='b' or name='c' and foo ="
                    + " 3 and other <> 4 or name = 'e' and foo = 5 or name = 'm' and foo > 2 ")
            .stream()
            .toList();

    var qResult2 =
        session
            .command(
                "select * from bar where (name <> 'a' and foo = 1) or name='b' or (name='c' and foo"
                    + " = 3 and other <>  4) or (name = 'e' and foo = 5) or (name = 'm' and foo >"
                    + " 2)")
            .stream()
            .toList();

    var qResult3 =
        session
            .command(
                "select * from bar where ( name <> 'a' and foo = 1) or (name='b') or (name='c' and"
                    + " foo = 3 and other <>  4) or (name ='e' and foo = 5) or (name = 'm' and foo"
                    + " > 2)")
            .stream()
            .toList();

    var qResult4 =
        session
            .command(
                "select * from bar where (name <> 'a' and foo = 1) or ( (name='b') or (name='c' and"
                    + " foo = 3 and other <>  4)) or  (name = 'e' and foo = 5) or (name = 'm' and"
                    + " foo > 2)")
            .stream()
            .toList();

    var qResult5 =
        session
            .command(
                "select * from bar where (name <> 'a' and foo = 1) or ((name='b') or (name='c' and"
                    + " foo = 3 and other <>  4) or (name = 'e' and foo = 5)) or (name = 'm' and"
                    + " foo > 2)")
            .stream()
            .toList();

    assertEquals(qResult.size(), qResult2.size());
    assertEquals(qResult.size(), qResult3.size());
    assertEquals(qResult.size(), qResult4.size());
    assertEquals(qResult.size(), qResult5.size());
  }

  @Test
  public void testExpandOnEmbedded() {
    try (var qResult = session.command("select expand(address) from foo where name = 'a'")) {
      assertEquals("NY", qResult.next().getProperty("city"));
      assertFalse(qResult.hasNext());
    }
  }

  @Test
  public void testLimit() {
    var qResult = session.query("select from foo limit 3");
    assertEquals(3, qResult.stream().count());
  }

  @Test
  public void testLimitWithMetadataQuery() {
    var qResult = session.query("select expand(classes) from metadata:schema limit 3");
    assertEquals(3, qResult.stream().count());
  }

  @Test
  public void testOrderByWithMetadataQuery() {
    var qResult = session.query("select expand(classes) from metadata:schema order by name");
    assertTrue(qResult.stream().count() > 0);
  }

  @Test
  public void testLimitWithUnnamedParam() {
    var qResult = session.query("select from foo limit ?", 3);
    assertEquals(3, qResult.stream().count());
  }

  @Test
  public void testLimitWithNamedParam() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("lim", 2);
    var qResult = session.query("select from foo limit :lim", params);
    assertEquals(2, qResult.stream().count());
  }

  @Test
  public void testLimitWithNamedParam2() {
    // issue #5493
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("limit", 2);
    var qResult = session.command("select from foo limit :limit", params);
    assertEquals(2, qResult.stream().count());
  }

  @Test
  public void testParamsInLetSubquery() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo");
    var qResult =
        session.command(
            "select from TestParams let $foo = (select name from TestParams where surname = :name)"
                + " where surname in $foo.name ",
            params);
    assertEquals(1, qResult.stream().count());
  }

  @Test
  public void testBooleanParams() {
    // issue #4224
    var qResult =
        session.command("select name from TestParams where name = ? and active = ?", "foo", true);
    assertEquals(1, qResult.stream().count());
  }

  @Test
  public void testFromInSquareBrackets() {
    try (var qResult = session.command("select tags[' from '] as a from TestFromInSquare")) {
      assertEquals("foo", qResult.next().getProperty("a"));
      assertFalse(qResult.hasNext());
    }
  }

  @Test
  public void testNewline() {
    var qResult = session.command("select\n1 as ACTIVE\nFROM foo");
    assertEquals(5, qResult.stream().count());
  }

  @Test
  public void testOrderByRid() {
    var qResult = session.query("select from ridsorttest order by @rid ASC");
    assertTrue(qResult.hasNext());

    var prev = qResult.next();
    while (qResult.hasNext()) {
      var next = qResult.next();
      assertTrue(prev.getIdentity().compareTo(next.getIdentity()) <= 0);
      prev = next;
    }
    qResult.close();

    qResult = session.query("select from ridsorttest order by @rid DESC");
    assertTrue(qResult.hasNext());

    prev = qResult.next();
    while (qResult.hasNext()) {
      var next = qResult.next();
      assertTrue(prev.getIdentity().compareTo(next.getIdentity()) >= 0);
      prev = next;
    }
    qResult.close();

    qResult = session.query("select from ridsorttest where name > 3 order by @rid DESC");
    assertTrue(qResult.hasNext());

    prev = qResult.next();
    while (qResult.hasNext()) {
      var next = qResult.next();
      assertTrue(prev.getIdentity().compareTo(next.getIdentity()) >= 0);
      prev = next;
    }
  }

  @Test
  public void testUnwind() {
    var qResult =
        session.command("select from unwindtest unwind coll").stream().toList();

    assertEquals(4, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
      assertNull(doc.getIdentity());
    }
  }

  @Test
  public void testUnwind2() {
    var qResult =
        session.command("select from unwindtest2 unwind coll").stream().toList();

    assertEquals(1, qResult.size());
    for (var doc : qResult) {
      var coll = doc.getProperty("coll");
      assertNull(coll);
      assertNull(doc.getIdentity());
    }
  }

  @Test
  public void testUnwindOrder() {
    var qResult =
        session.command("select from unwindtest order by coll unwind coll").stream()
            .toList();

    assertEquals(4, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
      assertNull(doc.getIdentity());
    }
  }

  @Test
  public void testUnwindSkip() {
    var qResult =
        session.command("select from unwindtest unwind coll skip 1").stream()
            .toList();

    assertEquals(3, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
    }
  }

  @Test
  public void testUnwindLimit() {
    var qResult =
        session.command("select from unwindtest unwind coll limit 1").stream()
            .toList();

    assertEquals(1, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
    }
  }

  @Test
  public void testUnwindLimit3() {
    var qResult =
        session.command("select from unwindtest unwind coll limit 3").stream()
            .toList();

    assertEquals(3, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
    }
  }

  @Test
  public void testUnwindSkipAndLimit() {
    var qResult =
        session.command("select from unwindtest unwind coll skip 1 limit 1").stream()
            .toList();

    assertEquals(1, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
    }
  }

  @Test
  public void testMultipleClusters() {
    var qResult = session.command("select from cluster:[testmultipleclusters1]");

    assertEquals(1, qResult.stream().count());

    qResult = session.command("select from cluster:[testmultipleclusters1, testmultipleclusters2]");

    assertEquals(2, qResult.stream().count());
  }

  @Test
  public void testMatches() {
    var result =
        session.query(
            new SQLSynchQuery<Object>(
                "select from foo where name matches"
                    + " '(?i)(^\\\\Qa\\\\E$)|(^\\\\Qname2\\\\E$)|(^\\\\Qname3\\\\E$)' and bar ="
                    + " 1"));
    assertEquals(1, result.size());
  }

  @Test
  public void testStarPosition() {
    var result =
        session.query("select *, name as blabla from foo where name = 'a'").stream()
            .collect(Collectors.toList());

    assertEquals(1, result.size());
    assertEquals("a", result.get(0).getProperty("blabla"));

    result =
        session.query("select name as blabla, * from foo where name = 'a'").stream()
            .collect(Collectors.toList());

    assertEquals(1, result.size());
    assertEquals("a", result.get(0).getProperty("blabla"));

    result =
        session.query("select name as blabla, *, fff as zzz from foo where name = 'a'").stream()
            .collect(Collectors.toList());

    assertEquals(1, result.size());
    assertEquals("a", result.get(0).getProperty("blabla"));
  }

  @Test
  public void testQuotedClassName() {
    var qResult = session.query("select from `edge`");

    assertEquals(0, qResult.stream().count());
  }

  public void testUrl() {

    var qResult = session.command("select from TestUrl").stream().toList();

    assertEquals(1, qResult.size());
    assertEquals("http://www.google.com", qResult.get(0).getProperty("url"));
  }

  @Test
  public void testUnwindSkipAndLimit2() {
    var qResult =
        session.command("select from unwindtest unwind coll skip 1 limit 2").stream()
            .toList();

    assertEquals(2, qResult.size());
    for (var doc : qResult) {
      String name = doc.getProperty("name");
      String coll = doc.getProperty("coll");
      assertTrue(coll.startsWith(name));
    }
  }

  @Test
  public void testMultipleParamsWithSameName() {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", "foo");
    var qResult =
        session.query("select from TestParams where name like '%' + :param1 + '%'", params);
    assertEquals(2, qResult.stream().count());

    qResult =
        session.query(
            "select from TestParams where name like '%' + :param1 + '%' and surname like '%' +"
                + " :param1 + '%'",
            params);
    assertEquals(1, qResult.stream().count());

    params = new HashMap<String, Object>();
    params.put("param1", "bar");

    qResult = session.query("select from TestParams where surname like '%' + :param1 + '%'",
        params);
    assertEquals(1, qResult.stream().count());
  }

  // /*** from issue #2743
  @Test
  public void testBasicQueryOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter");
    assertEquals(26, results.stream().count());
  }

  @Test
  public void testSkipZeroOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter SKIP 0");
    assertEquals(26, results.stream().count());
  }

  @Test
  public void testSkipOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter SKIP 7");
    assertEquals(19, results.stream().count());
  }

  @Test
  public void testLimitOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter LIMIT 9");
    assertEquals(9, results.stream().count());
  }

  @Test
  public void testLimitMinusOneOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter LIMIT -1");
    assertEquals(26, results.stream().count());
  }

  @Test
  public void testSkipAndLimitOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter SKIP 7 LIMIT 9");
    assertEquals(9, results.stream().count());
  }

  @Test
  public void testSkipAndLimitMinusOneOrdered() {
    var results = session.query("SELECT from alphabet ORDER BY letter SKIP 7 LIMIT -1");
    assertEquals(19, results.stream().count());
  }

  @Test
  public void testLetAsListAsString() {
    var sql =
        "SELECT $ll as lll from unwindtest let $ll = coll.asList().asString() where name = 'bar'";
    var results = session.query(sql).stream().toList();
    assertEquals(1, results.size());
    assertNotNull(results.get(0).getProperty("lll"));
    assertEquals("[bar1, bar2]", results.get(0).getProperty("lll"));
  }

  @Test
  public void testAggregations() {
    var results = session.query(
        "select data.size as collection_content, data.size() as collection_size, min(data.size)"
            + " as collection_min, max(data.size) as collection_max, sum(data.size) as"
            + " collection_sum, avg(data.size) as collection_avg from"
            + " OCommandExecutorSQLSelectTest_aggregations").toList();
    assertEquals(1, results.size());
    var doc = results.get(0);

    assertThat(doc.<Integer>getProperty("collection_size")).isEqualTo(5);
    assertThat(doc.<Integer>getProperty("collection_sum")).isEqualTo(130);
    assertThat(doc.<Integer>getProperty("collection_avg")).isEqualTo(26);
    assertThat(doc.<Integer>getProperty("collection_min")).isEqualTo(0);
    assertThat(doc.<Integer>getProperty("collection_max")).isEqualTo(50);
  }

  @Test
  public void testLetOrder() {
    var sql =
        new SQLSynchQuery(
            "SELECT"
                + "      source,"
                + "  $maxYear as maxYear"
                + "              FROM"
                + "      ("
                + "          SELECT expand( $union ) "
                + "  LET"
                + "      $a = (SELECT 'A' as source, 2013 as year),"
                + "  $b = (SELECT 'B' as source, 2012 as year),"
                + "  $union = unionAll($a,$b) "
                + "  ) "
                + "  LET "
                + "      $maxYear = max(year)"
                + "  GROUP BY"
                + "  source");
    try {
      List<EntityImpl> results = session.query(sql);
      fail(
          "Invalid query, usage of LET, aggregate functions and GROUP BY together is not"
              + " supported");
    } catch (CommandSQLParsingException x) {

    }
  }

  @Test
  public void testNullProjection() {
    var sql =
        "SELECT 1 AS integer, 'Test' AS string, NULL AS nothing, [] AS array, {} AS object";

    var results = session.query(sql).stream().toList();
    assertEquals(1, results.size());
    var doc = results.get(0);
    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(1);
    assertEquals("Test", doc.getProperty("string"));
    assertNull(doc.getProperty("nothing"));
    var nullFound = false;
    for (var s : doc.getPropertyNames()) {
      if (s.equals("nothing")) {
        nullFound = true;
        break;
      }
    }
    assertTrue(nullFound);
  }

  @Test
  public void testExpandSkipLimit() {
    initExpandSkipLimit(session);
    // issue #4985
    var results =
        session.query(
            "SELECT expand(linked) from ExpandSkipLimit where parent = true order by nnum skip 1"
                + " limit 1");
    var doc = results.next();
    assertThat(doc.<Integer>getProperty("nnum")).isEqualTo(1);
  }

  @Test
  public void testBacktick() {
    var results = session.query("SELECT `foo-bar` as r from TestBacktick");
    var doc = results.next();
    assertThat(doc.<Integer>getProperty("r")).isEqualTo(10);
  }

  @Test
  public void testOrderByEmbeddedParams() {
    // issue #4949
    Map<String, Object> parameters = new HashMap<String, Object>();
    parameters.put("paramvalue", "count");
    var qResult =
        session.command("select from TestParamsEmbedded order by emb[:paramvalue] DESC",
            parameters);
    Map embedded = qResult.next().getProperty("emb");
    assertEquals(1, embedded.get("count"));
    qResult.close();
  }

  @Test
  public void testOrderByEmbeddedParams2() {
    // issue #4949
    Map<String, Object> parameters = new HashMap<String, Object>();
    parameters.put("paramvalue", "count");
    var qResult =
        session.command("select from TestParamsEmbedded order by emb[:paramvalue] ASC", parameters);
    Map embedded = qResult.next().getProperty("emb");
    assertEquals(0, embedded.get("count"));
    qResult.close();
    qResult =
        session.command("select from TestParamsEmbedded order by emb[:paramvalue] ASC", parameters);
    assertEquals(2, qResult.stream().count());
  }

  @Test
  public void testMassiveOrderAscSkipLimit() {
    initMassiveOrderSkipLimit(session);
    var skip = 1000;
    var results =
        session.query(
            "SELECT from MassiveOrderSkipLimit order by nnum asc skip " + skip + " limit 5");

    var i = 0;
    while (results.hasNext()) {
      var doc = results.next();
      assertThat(doc.<Integer>getProperty("nnum")).isEqualTo(skip + i);
      i++;
    }
    assertEquals(5, i);
  }

  @Test
  public void testMassiveOrderDescSkipLimit() {
    initMassiveOrderSkipLimit(session);
    var skip = 1000;
    var results =
        session.query(
            "SELECT from MassiveOrderSkipLimit order by nnum desc skip " + skip + " limit 5");
    var i = 0;
    while (results.hasNext()) {
      var doc = results.next();
      assertThat(doc.<Integer>getProperty("nnum")).isEqualTo(ORDER_SKIP_LIMIT_ITEMS - 1 - skip - i);
      i++;
    }
    assertEquals(5, i);
  }

  @Test
  public void testIntersectExpandLet() {
    // issue #5121
    var results =
        session.query(
            "select expand(intersect($q1, $q2)) "
                + "let $q1 = (select from OUser where name ='admin'),"
                + "$q2 = (select from OUser where name ='admin')");
    assertTrue(results.hasNext());
    var doc = results.next();
    assertEquals("admin", doc.getProperty("name"));
    assertFalse(results.hasNext());
  }

  @Test
  public void testDatesListContainsString() {
    initDatesSet(session);
    // issue #3526

    var results =
        session.query(
            "select from OCommandExecutorSQLSelectTest_datesSet where foo contains '2015-10-21'");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testParamWithMatches() {
    // issue #5229
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", "adm.*");
    var results = session.query("select from OUser where name matches :param1", params);
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testParamWithMatchesQuoteRegex() {
    initMatchesWithRegex(session);
    // issue #5229
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", ".*admin[name].*"); // will not work
    var results = session.query("select from matchesstuff where name matches :param1", params);
    assertEquals(0, results.stream().count());
    params.put("param1", Pattern.quote("admin[name]") + ".*"); // should work
    results = session.query("select from matchesstuff where name matches :param1", params);
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testMatchesWithQuotes() {
    initMatchesWithRegex(session);
    // issue #5229
    var pattern = Pattern.quote("adm") + ".*";
    var results = session.query("SELECT FROM matchesstuff WHERE (name matches ?)", pattern);
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testMatchesWithQuotes2() {
    initMatchesWithRegex(session);
    // issue #5229
    var results =
        session.query(
            "SELECT FROM matchesstuff WHERE (name matches '\\\\Qadm\\\\E.*' and not ( name matches"
                + " '(.*)foo(.*)' ) )");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testMatchesWithQuotes3() {
    initMatchesWithRegex(session);
    // issue #5229
    var results =
        session.query(
            "SELECT FROM matchesstuff WHERE (name matches '\\\\Qadm\\\\E.*' and  ( name matches"
                + " '\\\\Qadmin\\\\E.*' ) )");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testParamWithMatchesAndNot() {
    // issue #5229
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("param1", "adm.*");
    params.put("param2", "foo.*");
    var results =
        session.query(
            "select from OUser where (name matches :param1 and not (name matches :param2))",
            params);
    assertEquals(1, results.stream().count());

    params.put("param1", Pattern.quote("adm") + ".*");
    results =
        session.query(
            "select from OUser where (name matches :param1 and not (name matches :param2))",
            params);
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testDistinctLimit() {
    initDistinctLimit(session);
    var results = session.query("select distinct(name) from DistinctLimit limit 1");
    assertEquals(1, results.stream().count());

    results = session.query("select distinct(name) from DistinctLimit limit 2");
    assertEquals(2, results.stream().count());

    results = session.query("select distinct(name) from DistinctLimit limit 3");
    assertEquals(2, results.stream().count());

    results = session.query("select distinct(name) from DistinctLimit limit -1");
    assertEquals(2, results.stream().count());
  }

  @Test
  public void testSelectFromClusterNumber() {
    initDistinctLimit(session);
    var clazz = session.getMetadata().getSchema().getClass("DistinctLimit");
    var clusterId = clazz.getClusterIds(session)[0];
    var results = session.query("select from cluster:" + clusterId + " limit 1");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testLinkListSequence1() {
    initLinkListSequence(session);
    var sql =
        new SQLSynchQuery(
            "select expand(children.children.children) from LinkListSequence where name = 'root'");
    List<EntityImpl> results = session.query(sql);
    assertEquals(4, results.size());
    for (var result : results) {
      String value = result.field("name");
      assertEquals(5, value.length());
    }
  }

  @Test
  public void testLinkListSequence2() {
    initLinkListSequence(session);
    var sql =
        new SQLSynchQuery(
            "select expand(children[0].children.children) from LinkListSequence where name ="
                + " 'root'");
    List<EntityImpl> results = session.query(sql);
    assertEquals(4, results.size());
    for (var result : results) {
      String value = result.field("name");
      assertEquals(5, value.length());
    }
  }

  @Test
  public void testLinkListSequence3() {
    initLinkListSequence(session);
    var sql =
        "select expand(children[0].children[0].children) from LinkListSequence where name = 'root'";
    var results = session.query(sql).stream().toList();
    assertEquals(2, results.size());
    for (var result : results) {
      String value = result.getProperty("name");
      assertTrue(value.equals("1.1.1") || value.equals("1.1.2"));
    }
  }

  @Test
  public void testMaxLongNumber() {
    initMaxLongNumber(session);
    // issue #5664
    var results = session.query("select from MaxLongNumberTest WHERE last < 10 OR last is null");
    assertEquals(3, results.stream().count());
    session.begin();
    session.command("update MaxLongNumberTest set last = max(91,ifnull(last,0))").close();
    session.commit();
    results = session.query("select from MaxLongNumberTest WHERE last < 10 OR last is null");
    assertEquals(0, results.stream().count());
  }

  @Test
  public void testFilterAndOrderBy() {
    initFilterAndOrderByTest(session);
    // issue http://www.prjhub.com/#/issues/6199

    var sql = "SELECT FROM FilterAndOrderByTest WHERE active = true ORDER BY dc DESC";
    var results = session.query(sql).stream().toList();
    assertEquals(3, results.size());

    Calendar cal = new GregorianCalendar();

    Date date = results.get(0).getProperty("dc");
    cal.setTime(date);
    assertEquals(2016, cal.get(Calendar.YEAR));

    date = results.get(1).getProperty("dc");
    cal.setTime(date);
    assertEquals(2010, cal.get(Calendar.YEAR));

    date = results.get(2).getProperty("dc");
    cal.setTime(date);
    assertEquals(2009, cal.get(Calendar.YEAR));
  }

  @Test
  public void testComplexFilterInSquareBrackets() {
    initComplexFilterInSquareBrackets(session);
    // issues #513 #5451

    var sql = "SELECT expand(collection[name = 'n1']) FROM ComplexFilterInSquareBrackets2";
    var results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(1, results.size());
    assertEquals("n1", results.iterator().next().getProperty("name"));

    sql =
        "SELECT expand(collection[name = 'n1' and value = 1]) FROM ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(1, results.size());
    assertEquals("n1", results.iterator().next().getProperty("name"));

    sql =
        "SELECT expand(collection[name = 'n1' and value > 1]) FROM ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(0, results.size());

    sql =
        "SELECT expand(collection[name = 'n1' or value = -1]) FROM ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(2, results.size());
    for (var doc : results) {
      assertTrue(doc.getProperty("name").equals("n1") || doc.getProperty("value").equals(-1));
    }

    sql =
        "SELECT expand(collection[name = 'n1' and not value = 1]) FROM"
            + " ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(0, results.size());

    sql = "SELECT expand(collection[value < 0]) FROM ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(1, results.size());
    //    assertEquals(results.iterator().next().field("value"), -1);
    assertThat(results.iterator().next().<Integer>getProperty("value")).isEqualTo(-1);

    sql = "SELECT expand(collection[2]) FROM ComplexFilterInSquareBrackets2";
    results = session.query(sql).stream().collect(Collectors.toList());
    assertEquals(1, results.size());
  }

  @Test
  public void testCollateOnCollections() {
    // issue #4851
    session.command("create class OCommandExecutorSqlSelectTest_collateOnCollections").close();
    session.command(
            "create property OCommandExecutorSqlSelectTest_collateOnCollections.categories"
                + " EMBEDDEDLIST string")
        .close();

    session.begin();
    session.command(
            "insert into OCommandExecutorSqlSelectTest_collateOnCollections set"
                + " categories=['a','b']")
        .close();
    session.commit();

    session.command(
            "alter property OCommandExecutorSqlSelectTest_collateOnCollections.categories COLLATE"
                + " ci")
        .close();

    session.begin();
    session.command(
            "insert into OCommandExecutorSqlSelectTest_collateOnCollections set"
                + " categories=['Math','English']")
        .close();
    session.command(
            "insert into OCommandExecutorSqlSelectTest_collateOnCollections set"
                + " categories=['a','b','c']")
        .close();
    session.commit();

    List<EntityImpl> results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from OCommandExecutorSqlSelectTest_collateOnCollections where 'Math' in"
                    + " categories"));
    assertEquals(1, results.size());
    results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from OCommandExecutorSqlSelectTest_collateOnCollections where 'math' in"
                    + " categories"));
    assertEquals(1, results.size());
  }

  @Test
  public void testCountUniqueIndex() {
    // issue http://www.prjhub.com/#/issues/6419
    session.command("create class OCommandExecutorSqlSelectTest_testCountUniqueIndex").close();
    session.command("create property OCommandExecutorSqlSelectTest_testCountUniqueIndex.AAA String")
        .close();
    session.command(
            "create index OCommandExecutorSqlSelectTest_testCountUniqueIndex.AAA on"
                + " OCommandExecutorSqlSelectTest_testCountUniqueIndex(AAA) unique")
        .close();

    var results =
        session
            .query(
                "select count(*) as count from OCommandExecutorSqlSelectTest_testCountUniqueIndex"
                    + " where AAA='missing'")
            .stream()
            .toList();
    assertEquals(1, results.size());
    //    assertEquals(results.iterator().next().field("count"), 0l);

    assertThat(results.iterator().next().<Long>getProperty("count")).isEqualTo(0L);
  }

  @Test
  public void testEvalLong() {
    // http://www.prjhub.com/#/issues/6472
    var results =
        session.query("SELECT EVAL(\"86400000 * 26\") AS value").stream().toList();
    assertEquals(1, results.size());

    //    assertEquals(results.get(0).field("value"), 86400000l * 26);
    assertThat(results.get(0).<Long>getProperty("value")).isEqualTo(86400000L * 26);
  }

  @Test
  public void testCollateOnLinked() {
    initCollateOnLinked(session);

    List<EntityImpl> results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CollateOnLinked2 where linked.name = 'foo' "));
    assertEquals(1, results.size());
    results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CollateOnLinked2 where linked.name = 'FOO' "));
    assertEquals(1, results.size());
  }

  @Test
  public void testParamConcat() {
    // issue #6049
    var results = session.query("select from TestParams where surname like ? + '%'", "fo");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testCompositeIndexWithoutNullValues() {
    session.command("create class CompositeIndexWithoutNullValues").close();
    session.command("create property CompositeIndexWithoutNullValues.one String").close();
    session.command("create property CompositeIndexWithoutNullValues.two String").close();
    session.command(
            "create index CompositeIndexWithoutNullValues.one_two on"
                + " CompositeIndexWithoutNullValues (one, two) NOTUNIQUE METADATA"
                + " {ignoreNullValues: true}")
        .close();

    session.begin();
    session.command("insert into CompositeIndexWithoutNullValues set one = 'foo'").close();
    session.command("insert into CompositeIndexWithoutNullValues set one = 'foo', two = 'bar'")
        .close();
    session.commit();

    List<EntityImpl> results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CompositeIndexWithoutNullValues where one = ?"),
            "foo");
    assertEquals(2, results.size());
    results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CompositeIndexWithoutNullValues where one = ? and two = ?"),
            "foo",
            "bar");
    assertEquals(1, results.size());

    session.command("create class CompositeIndexWithoutNullValues2").close();
    session.command("create property CompositeIndexWithoutNullValues2.one String").close();
    session.command("create property CompositeIndexWithoutNullValues2.two String").close();
    session.command(
            "create index CompositeIndexWithoutNullValues2.one_two on"
                + " CompositeIndexWithoutNullValues2 (one, two) NOTUNIQUE METADATA"
                + " {ignoreNullValues: false}")
        .close();

    session.begin();
    session.command("insert into CompositeIndexWithoutNullValues2 set one = 'foo'").close();
    session.command("insert into CompositeIndexWithoutNullValues2 set one = 'foo', two = 'bar'")
        .close();
    session.commit();

    results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CompositeIndexWithoutNullValues2 where one = ?"),
            "foo");
    assertEquals(2, results.size());
    results =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select from CompositeIndexWithoutNullValues where one = ? and two = ?"),
            "foo",
            "bar");
    assertEquals(1, results.size());
  }

  @Test
  public void testDateFormat() {
    var results =
        session.query("select date('2015-07-20', 'yyyy-MM-dd').format('dd.MM.yyyy') as dd").stream()
            .toList();
    assertEquals(1, results.size());
    assertEquals("20.07.2015", results.get(0).getProperty("dd"));
  }

  @Test
  public void testConcatenateNamedParams() {
    // issue #5572
    var results =
        session.query("select from TestMultipleClusters where name like :p1 + '%'", "fo");
    assertEquals(1, results.stream().count());

    results = session.query("select from TestMultipleClusters where name like :p1 ", "fo");
    assertEquals(0, results.stream().count());
  }

  @Test
  public void testMethodsOnStrings() {
    // issue #5671
    var results =
        session.query("select '1'.asLong() as long").stream().toList();
    assertEquals(1, results.size());
    //    assertEquals(results.get(0).field("long"), 1L);
    assertThat(results.get(0).<Long>getProperty("long")).isEqualTo(1L);
  }

  @Test
  public void testDifferenceOfInlineCollections() {
    // issue #5294
    var results =
        session.query("select difference([1,2,3],[1,2]) as difference").stream()
            .toList();
    assertEquals(1, results.size());
    var differenceFieldValue = results.get(0).getProperty("difference");
    assertTrue(differenceFieldValue instanceof Collection);
    assertEquals(1, ((Collection) differenceFieldValue).size());
    assertEquals(3, ((Collection) differenceFieldValue).iterator().next());
  }

  @Test
  public void testFoo() {
    // dispose it!
    session.command("create class testFoo");

    session.begin();
    session.command("insert into testFoo set val = 1, name = 'foo'");
    session.command("insert into testFoo set val = 3, name = 'foo'");
    session.command("insert into testFoo set val = 5, name = 'bar'");
    session.commit();

    var results = session.query("select sum(val), name from testFoo group by name");
    assertEquals(2, results.stream().count());
  }

  @Test
  public void testDateComparison() {
    // issue #6389

    session.command("create class TestDateComparison").close();
    session.command("create property TestDateComparison.dateProp DATE").close();

    session.begin();
    session.command("insert into TestDateComparison set dateProp = '2016-05-01'").close();
    session.commit();

    var results = session.query("SELECT from TestDateComparison WHERE dateProp >= '2016-05-01'");

    assertEquals(1, results.stream().count());
    results = session.query("SELECT from TestDateComparison WHERE dateProp <= '2016-05-01'");

    assertEquals(1, results.stream().count());
  }

  @Test
  public void testOrderByRidDescMultiCluster() {
    // issue #6694

    var clazz = session.getMetadata().getSchema().createClass("TestOrderByRidDescMultiCluster");
    if (clazz.getClusterIds(session).length < 2) {
      clazz.addCluster(session, "TestOrderByRidDescMultiCluster_11111");
    }

    for (var i = 0; i < 100; i++) {
      session.begin();
      session.command("insert into TestOrderByRidDescMultiCluster set foo = " + i).close();
      session.commit();
    }

    var results =
        session.query("SELECT from TestOrderByRidDescMultiCluster order by @rid desc").stream()
            .collect(Collectors.toList());
    assertEquals(100, results.size());
    Result lastDoc = null;
    for (var doc : results) {
      if (lastDoc != null) {
        assertTrue(doc.getIdentity().compareTo(lastDoc.getIdentity()) < 0);
      }
      lastDoc = doc;
    }

    results =
        session.query("SELECT from TestOrderByRidDescMultiCluster order by @rid asc").stream()
            .toList();
    assertEquals(100, results.size());
    lastDoc = null;
    for (var doc : results) {
      if (lastDoc != null) {
        assertTrue(doc.getIdentity().compareTo(lastDoc.getIdentity()) > 0);
      }
      lastDoc = doc;
    }
  }

  @Test
  public void testCountOnSubclassIndexes() {
    // issue #6737

    session.command("create class testCountOnSubclassIndexes_superclass").close();
    session.command("create property testCountOnSubclassIndexes_superclass.foo boolean").close();
    session.command(
            "create index testCountOnSubclassIndexes_superclass.foo on"
                + " testCountOnSubclassIndexes_superclass (foo) notunique")
        .close();

    session.command(
            "create class testCountOnSubclassIndexes_sub1 extends"
                + " testCountOnSubclassIndexes_superclass")
        .close();
    session.command(
            "create index testCountOnSubclassIndexes_sub1.foo on testCountOnSubclassIndexes_sub1"
                + " (foo) notunique")
        .close();

    session.command(
            "create class testCountOnSubclassIndexes_sub2 extends"
                + " testCountOnSubclassIndexes_superclass")
        .close();
    session.command(
            "create index testCountOnSubclassIndexes_sub2.foo on testCountOnSubclassIndexes_sub2"
                + " (foo) notunique")
        .close();

    session.begin();
    session.command("insert into testCountOnSubclassIndexes_sub1 set name = 'a', foo = true")
        .close();
    session.command("insert into testCountOnSubclassIndexes_sub1 set name = 'b', foo = false")
        .close();
    session.command("insert into testCountOnSubclassIndexes_sub2 set name = 'c', foo = true")
        .close();
    session.command("insert into testCountOnSubclassIndexes_sub2 set name = 'd', foo = true")
        .close();
    session.command("insert into testCountOnSubclassIndexes_sub2 set name = 'e', foo = false")
        .close();
    session.commit();

    var results =
        session
            .query("SELECT count(*) as count from testCountOnSubclassIndexes_sub1 where foo = true")
            .stream()
            .collect(Collectors.toList());
    assertEquals(1, results.size());
    assertEquals((Object) 1L, results.get(0).getProperty("count"));

    results =
        session
            .query("SELECT count(*) as count from testCountOnSubclassIndexes_sub2 where foo = true")
            .stream()
            .collect(Collectors.toList());
    assertEquals(1, results.size());
    assertEquals((Object) 2L, results.get(0).getProperty("count"));

    results =
        session
            .query(
                "SELECT count(*) as count from testCountOnSubclassIndexes_superclass where foo ="
                    + " true")
            .stream()
            .collect(Collectors.toList());
    assertEquals(1, results.size());
    assertEquals((Object) 3L, results.get(0).getProperty("count"));
  }

  @Test
  public void testDoubleExponentNotation() {
    // issue #7013

    var results = session.query("select 1e-2 as a").stream().toList();
    assertEquals(1, results.size());
    assertEquals((Object) 0.01f, results.get(0).getProperty("a"));
  }

  @Test
  public void testConvertDouble() {
    // issue #7234

    session.command("create class testConvertDouble").close();

    session.begin();
    session.command("insert into testConvertDouble set num = 100000").close();
    session.commit();

    var results =
        session.query("SELECT FROM testConvertDouble WHERE num >= 50000 AND num <=300000000");

    assertEquals(1, results.stream().count());
  }

  @Ignore
  public void testFilterListsOfMaps() {
    var className = "testFilterListaOfMaps";

    session.command("create class " + className).close();
    session.command("create property " + className + ".tagz embeddedmap").close();

    session.begin();
    session.command("insert into " + className + " set tagz = {}").close();
    session.command(
            "update "
                + className
                + " SET tagz.foo = [{name:'a', surname:'b'}, {name:'c', surname:'d'}]")
        .close();
    session.commit();

    var results =
        session.query(
            "select tagz.values()[0][name = 'a'] as t from " + className).toList();
    assertEquals(1, results.size());
    var result = results.get(0).<Map>getProperty("t");
    assertEquals("b", result.get("surname"));
  }

  @Test
  public void testComparisonOfShorts() {
    // issue #7578
    var className = "testComparisonOfShorts";
    session.command("create class " + className).close();
    session.command("create property " + className + ".state Short").close();

    session.begin();
    session.command("INSERT INTO " + className + " set state = 1").close();
    session.command("INSERT INTO " + className + " set state = 1").close();
    session.command("INSERT INTO " + className + " set state = 2").close();
    session.commit();

    var results = session.query("select from " + className + " where state in [1]");
    assertEquals(2, results.stream().count());

    results = session.query("select from " + className + " where [1] contains state");

    assertEquals(2, results.stream().count());
  }

  @Test
  public void testEnumAsParams() {
    // issue #7418
    var className = "testEnumAsParams";
    session.command("create class " + className).close();

    session.begin();
    session.command("INSERT INTO " + className + " set status = ?", PropertyType.STRING).close();
    session.command("INSERT INTO " + className + " set status = ?", PropertyType.BYTE).close();
    session.commit();

    Map<String, Object> params = new HashMap<String, Object>();
    List enums = new ArrayList();
    enums.add(PropertyType.STRING);
    enums.add(PropertyType.BYTE);
    params.put("status", enums);
    var results = session.query("select from " + className + " where status in :status", params);
    assertEquals(2, results.stream().count());
  }

  @Test
  public void testEmbeddedMapOfMapsContainsValue() {
    // issue #7793
    var className = "testEmbeddedMapOfMapsContainsValue";

    session.command("create class " + className).close();
    session.command("create property " + className + ".embedded_map EMBEDDEDMAP").close();
    session.command("create property " + className + ".id INTEGER").close();

    session.begin();
    session.command(
            "INSERT INTO "
                + className
                + " SET id = 0, embedded_map = {\"key_2\" : {\"name\" : \"key_2\", \"id\" :"
                + " \"0\"}}")
        .close();
    session.command(
            "INSERT INTO "
                + className
                + " SET id = 1, embedded_map = {\"key_1\" : {\"name\" : \"key_1\", \"id\" : \"1\""
                + " }}")
        .close();
    session.commit();

    var results =
        session.query(
            "select from "
                + className
                + " where embedded_map CONTAINSVALUE {\"name\":\"key_2\", \"id\":\"0\"}");
    assertEquals(1, results.stream().count());
  }

  @Test
  public void testInvertedIndexedCondition() {
    // issue #7820
    var className = "testInvertedIndexedCondition";

    session.command("create class " + className).close();
    session.command("create property " + className + ".name STRING").close();

    session.begin();
    session.command("insert into " + className + " SET name = \"1\"").close();
    session.command("insert into " + className + " SET name = \"2\"").close();
    session.commit();

    var results = session.query("SELECT * FROM " + className + " WHERE name >= \"0\"");
    assertEquals(2, results.stream().count());

    results = session.query("SELECT * FROM " + className + " WHERE \"0\" <= name");
    assertEquals(2, results.stream().count());

    session.command("CREATE INDEX " + className + ".name on " + className + " (name) UNIQUE")
        .close();

    results = session.query("SELECT * FROM " + className + " WHERE \"0\" <= name");
    assertEquals(2, results.stream().count());

    results = session.query("SELECT * FROM " + className + " WHERE \"2\" <= name");
    assertEquals(1, results.stream().count());

    results = session.query("SELECT * FROM " + className + " WHERE name >= \"0\"");
    assertEquals(2, results.stream().count());
  }

  @Test
  public void testIsDefinedOnNull() {
    // issue #7879
    var className = "testIsDefinedOnNull";

    session.command("create class " + className).close();
    session.command("create property " + className + ".name STRING").close();

    session.begin();
    session.command("insert into " + className + " SET name = null, x = 1").close();
    session.command("insert into " + className + " SET x = 2").close();
    session.commit();

    var results = session.query("SELECT * FROM " + className + " WHERE name is defined");
    assertEquals(1, (int) results.next().getProperty("x"));
    results.close();
    results = session.query("SELECT * FROM " + className + " WHERE name is not defined");

    assertEquals(2, (int) results.next().getProperty("x"));
    results.close();
  }

  private long indexUsages(DatabaseSession db) {
    final long oldIndexUsage;
    try {
      oldIndexUsage = getProfilerInstance().getCounter(
          "db." + db.getDatabaseName() + ".query.indexUsed");
      return oldIndexUsage == -1 ? 0 : oldIndexUsage;
    } catch (Exception e) {
      fail();
    }
    return -1L;
  }

  private int indexUsages(ExecutionPlan executionPlan) {
    var executionStep = executionPlan.getSteps();
    var usages = 0;

    for (var step : executionStep) {
      usages += indexUsages(step);
    }

    return usages;
  }

  private int indexUsages(ExecutionStep executionStep) {
    var usages = 0;
    if (executionStep instanceof FetchFromIndexStep) {
      usages++;
    }

    for (var step : executionStep.getSubSteps()) {
      usages += indexUsages(step);
    }

    if (executionStep instanceof ExecutionStepInternal internal) {
      for (var plan : internal.getSubExecutionPlans()) {
        usages += indexUsages(plan);
      }
    }

    return usages;
  }
}
