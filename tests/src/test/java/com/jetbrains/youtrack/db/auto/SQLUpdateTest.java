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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * If some of the tests start to fail then check collection number in queries, e.g #7:1. It can be
 * because the order of collections could be affected due to adding or removing collection from
 * storage.
 */
@Test
public class SQLUpdateTest extends BaseDBTest {

  private long updatedRecords;

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateProfiles();
    generateCompanyData();
  }

  @Test
  public void updateWithWhereOperator() {

    session.begin();
    var positions = getAddressValidPositions();

    var records =
        session.execute(
            "update Profile set salary = 120.30, location = "
                + positions.get(2)
                + ", salary_cloned = salary where surname = 'Obama'");

    Assert.assertEquals(((Number) records.next().getProperty("count")).intValue(), 3);
    session.commit();
  }

  @Test
  public void updateWithWhereRid() {

    session.begin();
    var result =
        session.execute("select @rid as rid from Profile where surname = 'Obama'").stream()
            .toList();

    Assert.assertEquals(result.size(), 3);
    session.commit();

    session.begin();
    var records =
        session.execute(
            "update Profile set salary = 133.00 where @rid = ?",
            result.get(0).<Object>getProperty("rid"));

    Assert.assertEquals(((Number) records.next().getProperty("count")).intValue(), 1);
    session.commit();
  }

  @Test
  public void updateUpsertOperator() {

    session.begin();
    var result =
        session.execute(
            "UPDATE Profile SET surname='Merkel' RETURN AFTER where surname = 'Merkel'");
    session.commit();
    Assert.assertEquals(result.stream().count(), 0);

    session.begin();
    result =
        session.execute(
            "UPDATE Profile SET surname='Merkel' UPSERT RETURN AFTER  where surname = 'Merkel'");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();

    session.begin();
    result = session.execute("SELECT FROM Profile  where surname = 'Merkel'");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();
  }

  @Test(dependsOnMethods = "updateWithWhereOperator")
  public void updateCollectionsAddWithWhereOperator() {
    session.begin();
    var positions = getAddressValidPositions();
    updatedRecords =
        session
            .execute("update Account set addresses = addresses || " + positions.get(0))
            .next()
            .getProperty("count");
    session.commit();
  }

  @Test(dependsOnMethods = "updateCollectionsAddWithWhereOperator")
  public void updateCollectionsRemoveWithWhereOperator() {
    session.begin();
    var positions = getAddressValidPositions();
    final long records =
        session
            .execute("update Account remove addresses = " + positions.get(0))
            .next()
            .getProperty("count");

    Assert.assertEquals(records, updatedRecords);
    session.commit();
  }

  @Test(dependsOnMethods = "updateCollectionsRemoveWithWhereOperator")
  public void updateCollectionsWithSetOperator() {

    session.begin();
    var docs = session.query("select from Account").stream().toList();

    var positions = getAddressValidPositions();
    session.commit();

    for (var doc : docs) {

      var tx = session.begin();
      doc = tx.load(doc.getIdentity());
      final long records =
          session
              .execute(
                  "update Account set addresses = ["
                      + positions.get(0)
                      + ","
                      + positions.get(1)
                      + ","
                      + positions.get(2)
                      + "] where @rid = "
                      + doc.getIdentity())
              .next()
              .getProperty("count");
      Assert.assertEquals(records, 1);

      EntityImpl loadedDoc = session.load(doc.getIdentity());
      Assert.assertEquals(((List<?>) loadedDoc.getProperty("addresses")).size(), 3);
      Assert.assertEquals(
          ((Identifiable) ((List<?>) loadedDoc.getProperty("addresses")).get(0)).getIdentity(),
          positions.get(0));
      loadedDoc.setProperty("addresses", doc.getProperty("addresses"));

      var activeTx = session.getActiveTransaction();
      activeTx.load(loadedDoc);
      session.commit();
    }
  }

  @Test(dependsOnMethods = "updateCollectionsRemoveWithWhereOperator")
  public void updateMapsWithSetOperator() {

    session.begin();
    var element =
        session
            .execute(
                "insert into O (equaledges, name, properties) values ('no',"
                    + " 'circleUpdate', {'round':'eeee', 'blaaa':'zigzag'} )")
            .next()
            .asEntityOrNull();

    Assert.assertNotNull(element);

    long records =
        session
            .execute(
                "update "
                    + element.getIdentity()
                    + " set properties = {'roundOne':'ffff',"
                    + " 'bla':'zagzig','testTestTEST':'okOkOK'}")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertEquals(records, 1);

    session.begin();
    Entity loadedElement = session.load(element.getIdentity());

    Assert.assertTrue(loadedElement.getProperty("properties") instanceof Map);

    Map<Object, Object> entries = loadedElement.getProperty("properties");
    Assert.assertEquals(entries.size(), 3);

    Assert.assertNull(entries.get("round"));
    Assert.assertNull(entries.get("blaaa"));

    Assert.assertEquals(entries.get("roundOne"), "ffff");
    Assert.assertEquals(entries.get("bla"), "zagzig");
    Assert.assertEquals(entries.get("testTestTEST"), "okOkOK");
    session.commit();
  }

  @Test(dependsOnMethods = "updateCollectionsRemoveWithWhereOperator")
  public void updateAllOperator() {

    var total = session.countClass("Profile");

    session.begin();
    Long records = session.execute("update Profile set sex = 'male'").next().getProperty("count");
    session.commit();

    Assert.assertEquals(records.intValue(), (int) total);
  }

  @Test(dependsOnMethods = "updateAllOperator")
  public void updateWithWildcards() {

    session.begin();
    long updated =
        session
            .execute("update Profile set sex = ? where sex = 'male' limit 1", "male")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertEquals(updated, 1);
  }

  @Test
  public void updateWithWildcardsOnSetAndWhere() {

    session.createClassIfNotExist("Person");
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Person"));
    doc.setProperty("name", "Raf");
    doc.setProperty("city", "Torino");
    doc.setProperty("gender", "fmale");

    session.commit();

    checkUpdatedDoc(session, "Torino", "fmale");

    /* THESE COMMANDS ARE OK */
    session.begin();
    session.execute("update Person set gender = 'female' where name = 'Raf'", "Raf");
    session.commit();

    checkUpdatedDoc(session, "Torino", "female");

    session.begin();
    session.execute("update Person set city = 'Turin' where name = ?", "Raf");
    session.commit();

    checkUpdatedDoc(session, "Turin", "female");

    session.begin();
    session.execute("update Person set gender = ? where name = 'Raf'", "F");
    session.commit();

    checkUpdatedDoc(session, "Turin", "F");

    session.begin();
    session.execute(
        "update Person set gender = ?, city = ? where name = 'Raf'", "FEMALE", "TORINO");
    session.commit();

    checkUpdatedDoc(session, "TORINO", "FEMALE");

    session.begin();
    session.execute("update Person set gender = ? where name = ?", "f", "Raf");
    session.commit();

    checkUpdatedDoc(session, "TORINO", "f");
  }

  public void updateWithReturn() {
    session.createClassIfNotExist("Data");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Data"));
    doc.setProperty("name", "Pawel");
    doc.setProperty("city", "Wroclaw");
    doc.setProperty("really_big_field", "BIIIIIIIIIIIIIIIGGGGGGG!!!");

    session.commit();

    // check AFTER
    var sqlString = "UPDATE " + doc.getIdentity() + " SET gender='male' RETURN AFTER";
    session.begin();
    var result1 = session.execute(sqlString).stream().toList();
    Assert.assertEquals(result1.size(), 1);
    Assert.assertEquals(result1.get(0).getIdentity(), doc.getIdentity());
    Assert.assertEquals(result1.get(0).getProperty("gender"), "male");
    session.commit();

    session.begin();
    sqlString =
        "UPDATE " + doc.getIdentity() + " set Age = 101 RETURN AFTER $current.Age";
    result1 = session.execute(sqlString).stream().toList();

    Assert.assertEquals(result1.size(), 1);
    Assert.assertTrue(result1.get(0).hasProperty("$current.Age"));
    Assert.assertEquals(result1.get(0).<Object>getProperty("$current.Age"), 101);
    // check exclude + WHERE + LIMIT
    session.commit();

    session.begin();
    sqlString =
        "UPDATE "
            + doc.getIdentity()
            + " set Age = Age + 100 RETURN AFTER $current.Exclude('really_big_field') as res WHERE"
            + " Age=101 LIMIT 1";
    result1 = session.execute(sqlString).stream().toList();

    Assert.assertEquals(result1.size(), 1);
    var element = result1.get(0).<Result>getProperty("res");
    Assert.assertTrue(element.hasProperty("Age"));
    Assert.assertEquals(element.<Integer>getProperty("Age"), 201);
    Assert.assertFalse(element.hasProperty("really_big_field"));
    session.commit();
  }

  @Test
  public void updateWithNamedParameters() {

    session.createClassIfNotExist("Data");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Data"));

    doc.setProperty("name", "Raf");
    doc.setProperty("city", "Torino");
    doc.setProperty("gender", "fmale");

    session.commit();

    var updatecommand = "update Data set gender = :gender , city = :city where name = :name";
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("gender", "f");
    params.put("city", "TOR");
    params.put("name", "Raf");

    session.begin();
    session.execute(updatecommand, params);
    session.commit();

    var result = session.query("select * from Data");
    var oDoc = result.next();
    Assert.assertEquals("Raf", oDoc.getProperty("name"));
    Assert.assertEquals("TOR", oDoc.getProperty("city"));
    Assert.assertEquals("f", oDoc.getProperty("gender"));
    result.close();
  }

  public void updateIncrement() {

    session.begin();
    var result1 =
        session.query("select salary from Account where salary is defined").stream().toList();
    Assert.assertFalse(result1.isEmpty());
    session.commit();

    session.begin();
    updatedRecords =
        session
            .execute("update Account set salary += 10 where salary is defined")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertTrue(updatedRecords > 0);

    session.begin();
    var result2 =
        session.query("select salary from Account where salary is defined").stream().toList();
    Assert.assertFalse(result2.isEmpty());
    Assert.assertEquals(result2.size(), result1.size());

    for (var i = 0; i < result1.size(); ++i) {
      float salary1 = result1.get(i).getProperty("salary");
      float salary2 = result2.get(i).getProperty("salary");
      Assert.assertEquals(salary2, salary1 + 10);
    }
    session.commit();

    session.begin();
    updatedRecords =
        session
            .execute("update Account set salary -= 10 where salary is defined")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertTrue(updatedRecords > 0);

    session.begin();
    var result3 =
        session.execute("select salary from Account where salary is defined").stream().toList();
    Assert.assertFalse(result3.isEmpty());
    Assert.assertEquals(result3.size(), result1.size());

    for (var i = 0; i < result1.size(); ++i) {
      float salary1 = result1.get(i).getProperty("salary");
      float salary3 = result3.get(i).getProperty("salary");
      Assert.assertEquals(salary3, salary1);
    }
    session.commit();
  }

  public void updateSetMultipleFields() {

    session.begin();
    var result1 =
        session.query("select salary from Account where salary is defined").stream().toList();
    Assert.assertFalse(result1.isEmpty());
    session.commit();

    session.begin();
    updatedRecords =
        session
            .execute(
                "update Account set salary2 = salary, checkpoint = true where salary is defined")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertTrue(updatedRecords > 0);

    session.begin();
    var result2 =
        session.query("select from Account where salary is defined").stream().toList();
    Assert.assertFalse(result2.isEmpty());
    Assert.assertEquals(result2.size(), result1.size());

    for (var i = 0; i < result1.size(); ++i) {
      float salary1 = result1.get(i).getProperty("salary");
      float salary2 = result2.get(i).getProperty("salary2");
      Assert.assertEquals(salary2, salary1);
      Assert.assertEquals(result2.get(i).<Object>getProperty("checkpoint"), true);
    }
    session.commit();
  }

  public void updateAddMultipleFields() {

    session.begin();
    updatedRecords =
        session
            .execute("update Account set myCollection = myCollection || [1,2] limit 1")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertTrue(updatedRecords > 0);

    session.begin();
    var result2 =
        session.execute("select from Account where myCollection is defined").stream().toList();
    Assert.assertEquals(result2.size(), 1);

    Collection<Object> myCollection = result2.iterator().next().getProperty("myCollection");

    Assert.assertTrue(myCollection.containsAll(Arrays.asList(1, 2)));
    session.commit();
  }

  @Test(enabled = false)
  public void testEscaping() {
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

  public void testUpdateVertexContent() {
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
      Assert.assertEquals(doc.getLong("sum"), 3);
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
      Assert.assertEquals(doc.getLong("sum"), 3);
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

  public void testUpdateEdgeContent() {
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

  private void checkUpdatedDoc(
      DatabaseSession database, String expectedCity, String expectedGender) {
    database.executeInTx(transaction -> {
      var result = transaction.query("select * from Person where name = 'Raf'");
      var oDoc = result.next();
      Assert.assertEquals(expectedCity, oDoc.getProperty("city"));
      Assert.assertEquals(expectedGender, oDoc.getProperty("gender"));
    });
  }

  private List<RID> getAddressValidPositions() {
    final List<RID> positions = new ArrayList<>();

    final var iteratorClass = session.browseClass("Address");

    for (var i = 0; i < 7; i++) {
      if (!iteratorClass.hasNext()) {
        break;
      }
      var doc = iteratorClass.next();
      positions.add(doc.getIdentity());
    }
    return positions;
  }

  public void testMultiplePut() {
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

  public void testAutoConversionOfEmbeddededListWithLinkedClass() {
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

  public void testPutListOfMaps() {
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
