/*
 * JUnit 4 version of SQLInsertTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
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
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class SQLInsertTest extends BaseDBTest {

  private static SQLInsertTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLInsertTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testAutoConversionOfEmbeddededSetNoLinkedClass (line 519) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test01_AutoConversionOfEmbeddededSetNoLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty("embeddedSetNoLinkedClass", PropertyType.EMBEDDEDSET);

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedSetNoLinkedClass',"
                    + " embeddedSetNoLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .asEntity();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedSetNoLinkedClass") instanceof Set);

    Set addr = doc.getProperty("embeddedSetNoLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof Map);
    }
    session.commit();
  }

  /**
   * Original: testAutoConversionOfEmbeddededSetWithLinkedClass (line 546) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test02_AutoConversionOfEmbeddededSetWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    var cc = session.getMetadata().getSchema().getClass("TestConvertLinkedClass");
    if (cc == null) {
      cc = session.getMetadata().getSchema().createAbstractClass("TestConvertLinkedClass");
    }
    if (!c.existsProperty("embeddedSetWithLinkedClass")) {
      c.createProperty("embeddedSetWithLinkedClass", PropertyType.EMBEDDEDSET, cc);
    }

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedSetWithLinkedClass',"
                    + " embeddedSetWithLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedSetWithLinkedClass") instanceof Set);

    Set addr = doc.getProperty("embeddedSetWithLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
    }
    session.commit();
  }

  /**
   * Original: testAutoConversionOfEmbeddededListNoLinkedClass (line 579) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test03_AutoConversionOfEmbeddededListNoLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty("embeddedListNoLinkedClass", PropertyType.EMBEDDEDLIST);

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedListNoLinkedClass',"
                    + " embeddedListNoLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .asEntity();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedListNoLinkedClass") instanceof List);

    List addr = doc.getProperty("embeddedListNoLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof Map);
    }
    session.commit();
  }

  /**
   * Original: testAutoConversionOfEmbeddededListWithLinkedClass (line 606) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test04_AutoConversionOfEmbeddededListWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    var cc = session.getMetadata().getSchema().getClass("TestConvertLinkedClass");
    if (cc == null) {
      cc = session.getMetadata().getSchema().createAbstractClass("TestConvertLinkedClass");
    }
    if (!c.existsProperty("embeddedListWithLinkedClass")) {
      c.createProperty("embeddedListWithLinkedClass", PropertyType.EMBEDDEDLIST, cc);
    }

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedListWithLinkedClass',"
                    + " embeddedListWithLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedListWithLinkedClass") instanceof List);

    List addr = doc.getProperty("embeddedListWithLinkedClass");
    for (var o : addr) {
      session.begin();
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
      session.commit();
    }
    session.commit();
  }

  /**
   * Original: testAutoConversionOfEmbeddededMapNoLinkedClass (line 641) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test05_AutoConversionOfEmbeddededMapNoLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty("embeddedMapNoLinkedClass", PropertyType.EMBEDDEDMAP);

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedMapNoLinkedClass',"
                    + " embeddedMapNoLinkedClass = {test:{'line1':'123 Fake Street'}}")
            .next()
            .asEntity();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedMapNoLinkedClass") instanceof Map);

    Map addr = doc.getProperty("embeddedMapNoLinkedClass");
    for (var o : addr.values()) {
      Assert.assertTrue(o instanceof Map);
    }
    session.commit();
  }

  /**
   * Original: testAutoConversionOfEmbeddededMapWithLinkedClass (line 668) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  @Ignore
  public void test06_AutoConversionOfEmbeddededMapWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty(
        "embeddedMapWithLinkedClass",
        PropertyType.EMBEDDEDMAP,
        session.getMetadata().getSchema().getOrCreateClass("TestConvertLinkedClass"));

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedMapWithLinkedClass',"
                    + " embeddedMapWithLinkedClass = {test:{'line1':'123 Fake Street'}}")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedMapWithLinkedClass") instanceof Map);

    Map addr = doc.getProperty("embeddedMapWithLinkedClass");
    for (var o : addr.values()) {
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
    }
  }

  /**
   * Original: testAutoConversionOfEmbeddededNoLinkedClass (line 697) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  @Ignore
  public void test07_AutoConversionOfEmbeddededNoLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty("embeddedNoLinkedClass", PropertyType.EMBEDDED);

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedNoLinkedClass',"
                    + " embeddedNoLinkedClass = {'line1':'123 Fake Street'}")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedNoLinkedClass") instanceof EntityImpl);
  }

  /**
   * Original: testEmbeddedDates (line 717) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test08_EmbeddedDates() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestEmbeddedDates");

    session.begin();
    session
        .execute(
            "insert into TestEmbeddedDates set events = [{\"on\": date(\"2005-09-08 04:00:00\","
                + " \"yyyy-MM-dd HH:mm:ss\", \"UTC\")}]\n")
        .close();
    session.commit();

    session.begin();
    var resultList =
        session.query("select from TestEmbeddedDates").stream().collect(Collectors.toList());

    Assert.assertEquals(resultList.size(), 1);
    var found = false;
    var result = resultList.getFirst();
    Collection events = result.getProperty("events");
    for (var event : events) {
      Assert.assertTrue(event instanceof Map);
      var dateObj = ((Map) event).get("on");
      Assert.assertTrue(dateObj instanceof Date);
      Calendar cal = new GregorianCalendar();
      cal.setTime((Date) dateObj);
      Assert.assertEquals(cal.get(Calendar.YEAR), 2005);
      found = true;
    }
    session.commit();

    session.begin();
    session.delete(session.load(result.getIdentity()));
    session.commit();

    Assert.assertTrue(found);
  }

  /**
   * Original: testAutoConversionOfEmbeddededWithLinkedClass (line 755) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test09_AutoConversionOfEmbeddededWithLinkedClass() {

    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    var cc = session.getMetadata().getSchema().getClass("TestConvertLinkedClass");
    if (cc == null) {
      cc = session.getMetadata().getSchema().createAbstractClass("TestConvertLinkedClass");
    }
    c.createProperty("embeddedWithLinkedClass", PropertyType.EMBEDDED, cc);

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedWithLinkedClass',"
                    + " embeddedWithLinkedClass = {'line1':'123 Fake Street'}")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("embeddedWithLinkedClass") instanceof EntityImpl);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("embeddedWithLinkedClass")).getSchemaClassName(),
        "TestConvertLinkedClass");
    session.commit();
  }

  /**
   * Original: testInsertEmbeddedWithRecordAttributes (line 784) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test10_InsertEmbeddedWithRecordAttributes() {
    var c = session.getMetadata().getSchema().getOrCreateClass("EmbeddedWithRecordAttributes");
    var cc = session.getMetadata().getSchema().getClass("EmbeddedWithRecordAttributes_Like");
    if (cc == null) {
      cc = session.getMetadata().getSchema()
          .createAbstractClass("EmbeddedWithRecordAttributes_Like");
    }
    if (!c.existsProperty("like")) {
      c.createProperty("like", PropertyType.EMBEDDED, cc);
    }

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO EmbeddedWithRecordAttributes SET `like` = { \n"
                    + "      count: 0, \n"
                    + "      latest: [], \n"
                    + "      '@type': 'document', \n"
                    + "      '@class': 'EmbeddedWithRecordAttributes_Like'\n"
                    + "    } ")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("like") instanceof Identifiable);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("like")).getSchemaClassName(),
        "EmbeddedWithRecordAttributes_Like");
    Assert.assertEquals(((Entity) doc.getProperty("like")).<Object>getProperty("count"), 0);
    session.commit();
  }

  /**
   * Original: testInsertEmbeddedWithRecordAttributes2 (line 820) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test11_InsertEmbeddedWithRecordAttributes2() {
    var c = session.getMetadata().getSchema()
        .getOrCreateClass("EmbeddedWithRecordAttributes2");
    var cc = session.getMetadata().getSchema().getClass("EmbeddedWithRecordAttributes2_Like");
    if (cc == null) {
      cc = session.getMetadata().getSchema()
          .createAbstractClass("EmbeddedWithRecordAttributes2_Like");
    }
    if (!c.existsProperty("like")) {
      c.createProperty("like", PropertyType.EMBEDDED, cc);
    }

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO EmbeddedWithRecordAttributes2 SET `like` = { \n"
                    + "      count: 0, \n"
                    + "      latest: [], \n"
                    + "      @type: 'document', \n"
                    + "      @class: 'EmbeddedWithRecordAttributes2_Like'\n"
                    + "    } ")
            .next()
            .asEntity();
    session.commit();

    var activeTx = session.begin();
    doc = activeTx.load(doc);
    Assert.assertTrue(doc.getProperty("like") instanceof Identifiable);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("like")).getSchemaClassName(),
        "EmbeddedWithRecordAttributes2_Like");
    Assert.assertEquals(((Entity) doc.getProperty("like")).<Object>getProperty("count"), 0);
    session.commit();
  }

  /**
   * Original: testInsertWithCollectionAsFieldName (line 857) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test12_InsertWithCollectionAsFieldName() {
    var c = session.getMetadata().getSchema()
        .getOrCreateClass("InsertWithCollectionAsFieldName");

    session.begin();
    session
        .execute("INSERT INTO InsertWithCollectionAsFieldName ( `collection` ) values ( 'foo' )")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query("SELECT FROM InsertWithCollectionAsFieldName").stream()
            .collect(Collectors.toList());

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.getFirst().getProperty("collection"), "foo");
    session.commit();
  }

  /**
   * Original: testInsertEmbeddedBigDecimal (line 878) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLInsertTest.java
   */
  @Test
  public void test13_InsertEmbeddedBigDecimal() {
    // issue #6670
    session.getMetadata().getSchema().getOrCreateClass("TestInsertEmbeddedBigDecimal");
    session
        .execute("create property TestInsertEmbeddedBigDecimal.ed embeddedlist decimal")
        .close();

    session.begin();
    session
        .execute("INSERT INTO TestInsertEmbeddedBigDecimal CONTENT {\"ed\": [5,null,5]}")
        .close();
    session.commit();

    session.begin();
    var result =
        session.query("SELECT FROM TestInsertEmbeddedBigDecimal").stream()
            .collect(Collectors.toList());
    Assert.assertEquals(result.size(), 1);
    Iterable ed = result.getFirst().getProperty("ed");
    var o = ed.iterator().next();
    Assert.assertEquals(o.getClass(), BigDecimal.class);
    Assert.assertEquals(((BigDecimal) o).intValue(), 5);
    session.commit();
  }

}
