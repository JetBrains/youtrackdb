/*
 * JUnit 4 version of SQLUpdateTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * If some of the tests start to fail then check collection number in queries, e.g #7:1. It can be
 * because the order of collections could be affected due to adding or removing collection from
 * storage.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLUpdateTest extends BaseDBTest {

  private static SQLUpdateTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLUpdateTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 49) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateProfiles();
    generateCompanyData();
  }

  /**
   * Original: testEscaping (line 492) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  @Ignore
  public void test01_Escaping() {
    final Schema schema = session.getMetadata().getSchema();
    schema.createClass("FormatEscapingTest");

    session.begin();
    var document = ((EntityImpl) session.newEntity("FormatEscapingTest"));

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = format('aaa \\' bbb') WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx6 = session.getActiveTransaction();
    document = activeTx6.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa ' bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'ccc \\' eee', test2 = format('aaa \\' bbb')"
                + " WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx5 = session.getActiveTransaction();
    document = activeTx5.load(document);
    Assert.assertEquals(document.getProperty("test"), "ccc ' eee");
    Assert.assertEquals(document.getProperty("test2"), "aaa ' bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'aaa \\n bbb' WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx4 = session.getActiveTransaction();
    document = activeTx4.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa \n bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'aaa \\r bbb' WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx3 = session.getActiveTransaction();
    document = activeTx3.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa \r bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'aaa \\b bbb' WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa \b bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'aaa \\t bbb' WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa \t bbb");

    session.begin();
    session
        .execute(
            "UPDATE FormatEscapingTest SET test = 'aaa \\f bbb' WHERE @rid = "
                + document.getIdentity())
        .close();
    session.commit();

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertEquals(document.getProperty("test"), "aaa \f bbb");
  }

  /**
   * Original: testUpdateVertexContent (line 588) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  public void test02_UpdateVertexContent() {
    final Schema schema = session.getMetadata().getSchema();
    var vertex = schema.getClass("V");
    schema.createClass("UpdateVertexContent", vertex);

    session.begin();
    final var vOneId = session.execute("create vertex UpdateVertexContent").next().getIdentity();
    final var vTwoId = session.execute("create vertex UpdateVertexContent").next().getIdentity();

    session.execute("create edge from " + vOneId + " to " + vTwoId).close();
    session.execute("create edge from " + vOneId + " to " + vTwoId).close();
    session.execute("create edge from " + vOneId + " to " + vTwoId).close();
    session.commit();

    session.begin();
    var result =
        session
            .query("select sum(outE().size(), inE().size()) as sum from UpdateVertexContent")
            .stream()
            .collect(Collectors.toList());

    Assert.assertEquals(result.size(), 2);

    for (var doc : result) {
      Assert.assertEquals((Object) doc.getLong("sum"), (Object) 3L);
    }

    session.commit();
    session.begin();
    session
        .execute("update UpdateVertexContent content {value : 'val'} where @rid = " + vOneId)
        .close();
    session
        .execute("update UpdateVertexContent content {value : 'val'} where @rid =  " + vTwoId)
        .close();
    session.commit();

    session.begin();
    result =
        session
            .query("select sum(outE().size(), inE().size()) as sum from UpdateVertexContent")
            .stream()
            .collect(Collectors.toList());

    Assert.assertEquals(result.size(), 2);

    for (var doc : result) {
      Assert.assertEquals((Object) doc.getLong("sum"), (Object) 3L);
    }
    session.commit();

    session.begin();
    result =
        session.query("select from UpdateVertexContent").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 2);
    for (var doc : result) {
      Assert.assertEquals(doc.getProperty("value"), "val");
    }
    session.commit();
  }

  /**
   * Original: testUpdateEdgeContent (line 649) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  public void test03_UpdateEdgeContent() {
    final Schema schema = session.getMetadata().getSchema();
    var vertex = schema.getClass("V");
    var edge = schema.getClass("E");

    schema.createClass("UpdateEdgeContentV", vertex);
    schema.createClass("UpdateEdgeContentE", edge);

    session.begin();
    final var vOneId = session.execute("create vertex UpdateEdgeContentV").next().getIdentity();
    final var vTwoId = session.execute("create vertex UpdateEdgeContentV").next().getIdentity();

    session.execute("create edge UpdateEdgeContentE from " + vOneId + " to " + vTwoId).close();
    session.execute("create edge UpdateEdgeContentE from " + vOneId + " to " + vTwoId).close();
    session.execute("create edge UpdateEdgeContentE from " + vOneId + " to " + vTwoId).close();
    session.commit();

    session.begin();
    var rs = session.query("select outV() as outV, inV() as inV from UpdateEdgeContentE");
    var result =
        rs.stream()
            .collect(Collectors.toList());
    rs.close();

    Assert.assertEquals(result.size(), 3);

    for (var doc : result) {
      Assert.assertEquals(doc.getProperty("outV"), vOneId);
      Assert.assertEquals(doc.getProperty("inV"), vTwoId);
    }
    session.commit();

    session.begin();
    session.execute("update UpdateEdgeContentE content {value : 'val'}").close();
    session.commit();

    session.begin();
    result =
        session.query("select outV() as outV, inV() as inV from UpdateEdgeContentE").stream()
            .collect(Collectors.toList());

    Assert.assertEquals(result.size(), 3);

    for (var doc : result) {
      Assert.assertEquals(doc.getProperty("outV"), vOneId);
      Assert.assertEquals(doc.getProperty("inV"), vTwoId);
    }
    session.commit();

    session.begin();

    result = session.query("select from UpdateEdgeContentE").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 3);
    for (var doc : result) {
      Assert.assertEquals(doc.getProperty("value"), "val");
    }
    session.commit();
  }

  /**
   * Original: testMultiplePut (line 733) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  public void test04_MultiplePut() {
    session.begin();
    var v = session.newVertex();

    session.commit();

    session.begin();
    Long records =
        session
            .execute(
                "UPDATE"
                    + v.getIdentity()
                    + " SET embmap[\"test\"] = \"Luca\" ,embmap[\"test2\"]=\"Alex\"")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertEquals(records.intValue(), 1);

    session.begin();
    var activeTx = session.getActiveTransaction();
    v = activeTx.load(v);
    Assert.assertTrue(v.getProperty("embmap") instanceof Map);
    Assert.assertEquals(((Map) v.getProperty("embmap")).size(), 2);
    session.rollback();
  }

  /**
   * Original: testAutoConversionOfEmbeddededListWithLinkedClass (line 760) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  public void test05_AutoConversionOfEmbeddededListWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    var cc = session.getMetadata().getSchema().getClass("TestConvertLinkedClass");
    if (cc == null) {
      cc = session.getMetadata().getSchema().createAbstractClass("TestConvertLinkedClass");
    }
    if (!c.existsProperty("embeddedListWithLinkedClass")) {
      c.createProperty("embeddedListWithLinkedClass", PropertyType.EMBEDDEDLIST, cc);
    }

    session.begin();
    var id =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedListWithLinkedClass',"
                    + " embeddedListWithLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .getIdentity();

    session
        .execute(
            "UPDATE "
                + id
                + " set embeddedListWithLinkedClass = embeddedListWithLinkedClass || [{'line1':'123"
                + " Fake Street'}]")
        .close();
    session.commit();

    session.begin();
    Entity doc = session.load(id);

    Assert.assertTrue(doc.getProperty("embeddedListWithLinkedClass") instanceof List);
    Assert.assertEquals(((Collection) doc.getProperty("embeddedListWithLinkedClass")).size(), 2);
    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + doc.getIdentity()
                + " set embeddedListWithLinkedClass =  embeddedListWithLinkedClass ||"
                + " [{'line1':'123 Fake Street'}]")
        .close();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedListWithLinkedClass") instanceof List);
    Assert.assertEquals(((Collection) doc.getProperty("embeddedListWithLinkedClass")).size(), 3);

    List addr = doc.getProperty("embeddedListWithLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
    }
    session.commit();
  }

  /**
   * Original: testPutListOfMaps (line 818) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLUpdateTest.java
   */
  @Test
  public void test06_PutListOfMaps() {
    var className = "testPutListOfMaps";
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.command(
        "insert into " + className + " set list = [{\"xxx\":1},{\"zzz\":3},{\"yyy\":2}]");
    session.command("UPDATE " + className + " set list = list || [{\"kkk\":4}]");
    session.commit();

    session.begin();
    var result =
        session.query("select from " + className).stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 1);
    var doc = result.get(0);
    List list = doc.getProperty("list");
    Assert.assertEquals(list.size(), 4);
    var fourth = list.get(3);

    Assert.assertTrue(fourth instanceof Map);
    Assert.assertEquals(((Map) fourth).keySet().iterator().next(), "kkk");
    Assert.assertEquals(((Map) fourth).values().iterator().next(), 4);
    session.commit();
  }

}
