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

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorCluster;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Ignore;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * If some of the tests start to fail then check cluster number in queries, e.g #7:1. It can be
 * because the order of clusters could be affected due to adding or removing cluster from storage.
 */
@Test
public class SQLInsertTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLInsertTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void insertOperator() {
    if (!session.getMetadata().getSchema().existsClass("Account")) {
      session.getMetadata().getSchema().createClass("Account");
    }

    final var profileClass = session.getMetadata().getSchema().getClass("Account");

    if (!session.getMetadata().getSchema().existsClass("Address")) {
      session.getMetadata().getSchema().createClass("Address");
    }

    var addressId = session.getMetadata().getSchema().getClass("Address").getClusterIds()[0];

    for (var i = 0; i < 30; i++) {
      session.begin();
      session.newEntity("Address");
      session.commit();
    }
    var positions = getValidPositions(addressId);

    if (!session.getMetadata().getSchema().existsClass("Profile")) {
      session.getMetadata().getSchema().createClass("Profile");
    }

    session.begin();
    var doc =
        session
            .execute(
                "insert into Profile (name, surname, salary, location, dummy) values"
                    + " ('Luca','Smith', 109.9, #"
                    + addressId
                    + ":"
                    + positions.get(3)
                    + ", 'hooray')")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getProperty("name"), "Luca");
    Assert.assertEquals(doc.getProperty("surname"), "Smith");
    Assert.assertEquals(((Number) doc.getProperty("salary")).floatValue(), 109.9f);
    Assert.assertEquals(doc.getProperty("location"), new RecordId(addressId, positions.get(3)));
    Assert.assertEquals(doc.getProperty("dummy"), "hooray");

    session.begin();
    doc =
        session
            .execute(
                "insert into Profile SET name = 'Luca', surname = 'Smith', salary = 109.9,"
                    + " location = #"
                    + addressId
                    + ":"
                    + positions.get(3)
                    + ", dummy =  'hooray'")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getProperty("name"), "Luca");
    Assert.assertEquals(doc.getProperty("surname"), "Smith");
    Assert.assertEquals(((Number) doc.getProperty("salary")).floatValue(), 109.9f);
    Assert.assertEquals(
        ((Identifiable) doc.getProperty("location")).getIdentity(),
        new RecordId(addressId, positions.get(3)));
    Assert.assertEquals(doc.getProperty("dummy"), "hooray");
  }

  @Test
  public void insertWithWildcards() {
    var addressId = session.getMetadata().getSchema().getClass("Address").getClusterIds()[0];

    var positions = getValidPositions(addressId);

    session.begin();
    var doc =
        session
            .execute(
                "insert into Profile (name, surname, salary, location, dummy) values"
                    + " (?,?,?,?,?)",
                "Marc",
                "Smith",
                120.0,
                new RecordId(addressId, positions.get(3)),
                "hooray")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getProperty("name"), "Marc");
    Assert.assertEquals(doc.getProperty("surname"), "Smith");
    Assert.assertEquals(((Number) doc.getProperty("salary")).floatValue(), 120.0f);
    Assert.assertEquals(doc.getProperty("location"), new RecordId(addressId, positions.get(3)));
    Assert.assertEquals(doc.getProperty("dummy"), "hooray");

    session.begin();
    session.delete(session.bindToSession(doc));
    session.commit();

    session.begin();
    doc =
        session
            .execute(
                "insert into Profile SET name = ?, surname = ?, salary = ?, location = ?,"
                    + " dummy = ?",
                "Marc",
                "Smith",
                120.0,
                new RecordId(addressId, positions.get(3)),
                "hooray")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getProperty("name"), "Marc");
    Assert.assertEquals(doc.getProperty("surname"), "Smith");
    Assert.assertEquals(((Number) doc.getProperty("salary")).floatValue(), 120.0f);
    Assert.assertEquals(
        ((Identifiable) doc.getProperty("location")).getIdentity(),
        new RecordId(addressId, positions.get(3)));
    Assert.assertEquals(doc.getProperty("dummy"), "hooray");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void insertMap() {
    session.begin();
    var doc =
        session
            .execute(
                "insert into O (equaledges, name, properties) values ('no',"
                    + " 'circle', {'round':'eeee', 'blaaa':'zigzag'} )")
            .next()
            .asEntity();
    session.commit();

    Assert.assertNotNull(doc);

    doc = session.bindToSession(doc);
    Assert.assertEquals(doc.getProperty("equaledges"), "no");
    Assert.assertEquals(doc.getProperty("name"), "circle");
    Assert.assertTrue(doc.getProperty("properties") instanceof Map);

    Map<Object, Object> entries = doc.getProperty("properties");
    Assert.assertEquals(entries.size(), 2);

    Assert.assertEquals(entries.get("round"), "eeee");
    Assert.assertEquals(entries.get("blaaa"), "zigzag");

    session.begin();
    session.delete(session.bindToSession(doc));
    session.commit();

    session.begin();
    doc =
        session
            .execute(
                "insert into O SET equaledges = 'no', name = 'circle',"
                    + " properties = {'round':'eeee', 'blaaa':'zigzag'} ")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);

    Assert.assertEquals(doc.getProperty("equaledges"), "no");
    Assert.assertEquals(doc.getProperty("name"), "circle");
    Assert.assertTrue(doc.getProperty("properties") instanceof Map);

    entries = doc.getProperty("properties");
    Assert.assertEquals(entries.size(), 2);

    Assert.assertEquals(entries.get("round"), "eeee");
    Assert.assertEquals(entries.get("blaaa"), "zigzag");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void insertList() {
    session.begin();
    var doc =
        session
            .execute(
                "insert into O (equaledges, name, list) values ('yes',"
                    + " 'square', ['bottom', 'top','left','right'] )")
            .next()
            .asEntity();
    session.commit();

    Assert.assertNotNull(doc);

    doc = session.bindToSession(doc);
    Assert.assertEquals(doc.getProperty("equaledges"), "yes");
    Assert.assertEquals(doc.getProperty("name"), "square");
    Assert.assertTrue(doc.getProperty("list") instanceof List);

    List<Object> entries = doc.getProperty("list");
    Assert.assertEquals(entries.size(), 4);

    Assert.assertEquals(entries.get(0), "bottom");
    Assert.assertEquals(entries.get(1), "top");
    Assert.assertEquals(entries.get(2), "left");
    Assert.assertEquals(entries.get(3), "right");

    session.begin();
    session.delete(session.bindToSession(doc));
    session.commit();

    session.begin();
    doc =
        session
            .execute(
                "insert into O SET equaledges = 'yes', name = 'square', list"
                    + " = ['bottom', 'top','left','right'] ")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);

    Assert.assertEquals(doc.getProperty("equaledges"), "yes");
    Assert.assertEquals(doc.getProperty("name"), "square");
    Assert.assertTrue(doc.getProperty("list") instanceof List);

    entries = doc.getProperty("list");
    Assert.assertEquals(entries.size(), 4);

    Assert.assertEquals(entries.get(0), "bottom");
    Assert.assertEquals(entries.get(1), "top");
    Assert.assertEquals(entries.get(2), "left");
    Assert.assertEquals(entries.get(3), "right");
  }

  @Test
  public void insertWithNoSpaces() {
    session.begin();
    var res =
        session.execute("insert into O (id, title)values(10, 'NoSQL movement')");
    session.commit();

    Assert.assertTrue(res.hasNext());
  }

  @Test
  public void insertAvoidingSubQuery() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.getClass("test") == null) {
      schema.createClass("test");
    }

    session.begin();
    var doc = session.execute("INSERT INTO test(text) VALUES ('(Hello World)')").next();
    session.commit();

    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getProperty("text"), "(Hello World)");
  }

  @Test
  public void insertSubQuery() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.getClass("test") == null) {
      schema.createClass("test");
    }

    final var usersCount = session.query("select count(*) as count from OUser");
    final long uCount = usersCount.next().getProperty("count");
    usersCount.close();

    session.begin();
    var doc =
        session
            .execute("INSERT INTO test SET names = (select name from OUser)")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertNotNull(doc);
    Assert.assertNotNull(doc.getProperty("names"));
    Assert.assertTrue(doc.getProperty("names") instanceof Collection);
    Assert.assertEquals(((Collection<?>) doc.getProperty("names")).size(), uCount);
  }

  @Test(dependsOnMethods = "insertOperator")
  public void insertCluster() {
    session.begin();
    var doc =
        session
            .execute(
                "insert into Account cluster anotherdefault (id, title) values (10, 'NoSQL"
                    + " movement')").stream().findFirst().orElseThrow().asEntityOrNull();
    session.commit();

    Assert.assertNotNull(doc);
    Assert.assertEquals(
        doc.getIdentity().getClusterId(), session.getClusterIdByName("anotherdefault"));
    Assert.assertEquals(doc.getSchemaClassName(), "Account");
  }

  public void updateMultipleFields() {
    if (!session.getMetadata().getSchema().existsClass("Account")) {
      session.getMetadata().getSchema().createClass("Account");
    }

    session.begin();
    for (var i = 0; i < 30; i++) {
      session.execute("insert into O set name = 'foo" + i + "'");
    }
    session.commit();

    var positions = getValidPositions(3);

    session.begin();
    Identifiable result =
        session
            .execute(
                "  INSERT INTO Account SET id= 3232,name= 'my name',map="
                    + " {\"key\":\"value\"},dir= '',user= #3:"
                    + positions.getFirst())
            .next()
            .asEntity();
    session.commit();
    Assert.assertNotNull(result);

    EntityImpl record = result.getRecord(session);

    record = session.bindToSession(record);
    Assert.assertEquals(record.<Object>getProperty("id"), 3232);
    Assert.assertEquals(record.getProperty("name"), "my name");
    Map<String, String> map = record.getProperty("map");
    Assert.assertEquals(map.get("key"), "value");
    Assert.assertEquals(record.getProperty("dir"), "");
    Assert.assertEquals(record.getProperty("user"), new RecordId(3, positions.getFirst()));
  }

  @Test
  public void insertSelect() {
    session.execute("CREATE CLASS UserCopy").close();

    session.begin();
    var inserted =
        session
            .execute("INSERT INTO UserCopy FROM select from ouser where name <> 'admin' limit 2")
            .stream()
            .count();
    session.commit();

    Assert.assertEquals(inserted, 2);

    var result =
        session.query("select from UserCopy").toList();

    Assert.assertEquals(result.size(), 2);
    for (var r : result) {
      Assert.assertEquals(r.asEntityOrNull().getSchemaClassName(), "UserCopy");
      EntityImpl entity = ((EntityImpl) r.asEntityOrNull());
      Assert.assertNotEquals(entity.getProperty("name"), "admin");
    }
  }

  @Test(expectedExceptions = ValidationException.class)
  public void insertSelectFromProjection() {
    session.execute("CREATE CLASS ProjectedInsert").close();
    session.execute("CREATE property ProjectedInsert.a Integer (max 3)").close();

    session.begin();
    session.execute("INSERT INTO ProjectedInsert FROM select 10 as a ").close();
    session.commit();
  }

  @Test
  @Ignore
  public void insertWithReturn() {
    if (!session.getMetadata().getSchema().existsClass("actor2")) {
      session.execute("CREATE CLASS Actor2").close();
    }

    // RETURN with $current.
    EntityImpl doc =
        session
            .command(new CommandSQL("INSERT INTO Actor2 SET FirstName=\"FFFF\" RETURN $current"))
            .execute(session);
    Assert.assertNotNull(doc);
    Assert.assertEquals(doc.getSchemaClassName(), "Actor2");
    // RETURN with @rid
    try (var resultSet1 =
        session.execute("INSERT INTO Actor2 SET FirstName=\"Butch 1\" RETURN @rid")) {
      var res1 = resultSet1.next().getProperty("@rid");
      Assert.assertTrue(res1 instanceof RecordId);
      Assert.assertTrue(((RecordId) ((Identifiable) res1).getIdentity()).isValidPosition());
      // Create many records and return @rid
      try (var resultSet2 =
          session.execute(
              "INSERT INTO Actor2(FirstName,LastName) VALUES"
                  + " ('Jay','Miner'),('Frank','Hermier'),('Emily','Saut')  RETURN @rid")) {

        var res2 = resultSet2.next().getProperty("@rid");
        Assert.assertTrue(res2 instanceof RecordId);

        // Create many records by INSERT INTO ...FROM and return wrapped field
        var another = ((Identifiable) res1).getIdentity();
        final var sql =
            "INSERT INTO Actor2 RETURN $current.FirstName  FROM SELECT * FROM ["
                + doc.getIdentity()
                + ","
                + another
                + "]";
        List res3 = session.command(new CommandSQL(sql)).execute(session);
        Assert.assertEquals(res3.size(), 2);
        Assert.assertTrue(((List<?>) res3).getFirst() instanceof EntityImpl);
        final var res3doc = (EntityImpl) res3.getFirst();
        Assert.assertTrue(res3doc.hasProperty("result"));
        Assert.assertTrue(
            "FFFF".equalsIgnoreCase(res3doc.getProperty("result"))
                || "Butch 1".equalsIgnoreCase(res3doc.getProperty("result")));
        Assert.assertTrue(res3doc.hasProperty("rid"));
        Assert.assertTrue(res3doc.hasProperty("version"));
      }
    }

    // create record using content keyword and update it in sql batch passing recordID between
    // commands
    final var sql2 =
        "let var1 = (INSERT INTO Actor2 CONTENT {Name:\"content\"} RETURN $current.@rid) "
            + "; let var2 = (UPDATE $var1 SET Bingo=1 RETURN AFTER @rid) "
            + " return $var2";
    try (var resSql2ResultSet = session.execute(sql2)) {
      var res_sql2 = resSql2ResultSet.next().getProperty("$var2");
      Assert.assertTrue(res_sql2 instanceof RecordId);

      // create record using content keyword and update it in sql batch passing recordID between
      // commands
      final var sql3 =
          "let var1 = (INSERT INTO Actor2 CONTENT {Name:\"Bingo owner\"} RETURN @this) "
              + "; let var2 = (UPDATE $var1 SET Bingo=1 RETURN AFTER) "
              + "return $var2";
      try (var resSql3ResultSet = session.execute(sql3)) {
        var res_sql3 = resSql3ResultSet.next().<Identifiable>getProperty("$var2");
        final EntityImpl sql3doc = res_sql3.getRecord(session);
        Assert.assertEquals(sql3doc.<Object>getProperty("Bingo"), 1);
        Assert.assertEquals(sql3doc.getProperty("Name"), "Bingo owner");
      }
    }
  }

  @Test
  public void testAutoConversionOfEmbeddededSetNoLinkedClass() {
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedSetNoLinkedClass") instanceof Set);

    Set addr = doc.getProperty("embeddedSetNoLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof Map);
    }
  }

  @Test
  public void testAutoConversionOfEmbeddededSetWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty(
        "embeddedSetWithLinkedClass",
        PropertyType.EMBEDDEDSET,
        session.getMetadata().getSchema().getOrCreateClass("TestConvertLinkedClass"));

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedSetWithLinkedClass',"
                    + " embeddedSetWithLinkedClass = [{'line1':'123 Fake Street'}]")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedSetWithLinkedClass") instanceof Set);

    Set addr = doc.getProperty("embeddedSetWithLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
    }
  }

  @Test
  public void testAutoConversionOfEmbeddededListNoLinkedClass() {
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedListNoLinkedClass") instanceof List);

    List addr = doc.getProperty("embeddedListNoLinkedClass");
    for (var o : addr) {
      Assert.assertTrue(o instanceof Map);
    }
  }

  @Test
  public void testAutoConversionOfEmbeddededListWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    if (!c.existsProperty("embeddedListWithLinkedClass")) {
      c.createProperty(
          "embeddedListWithLinkedClass",
          PropertyType.EMBEDDEDLIST,
          session.getMetadata().getSchema().getOrCreateClass("TestConvertLinkedClass"));
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedListWithLinkedClass") instanceof List);

    List addr = doc.getProperty("embeddedListWithLinkedClass");
    for (var o : addr) {
      session.begin();
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
      session.commit();
    }
  }

  @Test
  public void testAutoConversionOfEmbeddededMapNoLinkedClass() {
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedMapNoLinkedClass") instanceof Map);

    Map addr = doc.getProperty("embeddedMapNoLinkedClass");
    for (var o : addr.values()) {
      Assert.assertTrue(o instanceof Map);
    }
  }

  @Test(enabled = false)
  public void testAutoConversionOfEmbeddededMapWithLinkedClass() {
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedMapWithLinkedClass") instanceof Map);

    Map addr = doc.getProperty("embeddedMapWithLinkedClass");
    for (var o : addr.values()) {
      Assert.assertTrue(o instanceof EntityImpl);
      Assert.assertEquals(((EntityImpl) o).getSchemaClassName(), "TestConvertLinkedClass");
    }
  }

  @Test(enabled = false)
  public void testAutoConversionOfEmbeddededNoLinkedClass() {
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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedNoLinkedClass") instanceof EntityImpl);
  }

  @Test
  public void testEmbeddedDates() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestEmbeddedDates");

    session.begin();
    session
        .execute(
            "insert into TestEmbeddedDates set events = [{\"on\": date(\"2005-09-08 04:00:00\","
                + " \"yyyy-MM-dd HH:mm:ss\", \"UTC\")}]\n")
        .close();
    session.commit();

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

    session.begin();
    session.delete(result.asRecord());
    session.commit();

    Assert.assertTrue(found);
  }

  @Test
  public void testAutoConversionOfEmbeddededWithLinkedClass() {
    var c = session.getMetadata().getSchema().getOrCreateClass("TestConvert");
    c.createProperty(
        "embeddedWithLinkedClass",
        PropertyType.EMBEDDED,
        session.getMetadata().getSchema().getOrCreateClass("TestConvertLinkedClass"));

    session.begin();
    var doc =
        session
            .execute(
                "INSERT INTO TestConvert SET name = 'embeddedWithLinkedClass',"
                    + " embeddedWithLinkedClass = {'line1':'123 Fake Street'}")
            .next()
            .asEntity();
    session.commit();

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("embeddedWithLinkedClass") instanceof EntityImpl);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("embeddedWithLinkedClass")).getSchemaClassName(),
        "TestConvertLinkedClass");
  }

  @Test
  public void testInsertEmbeddedWithRecordAttributes() {
    var c = session.getMetadata().getSchema()
        .getOrCreateClass("EmbeddedWithRecordAttributes");
    c.createProperty(
        "like",
        PropertyType.EMBEDDED,
        session.getMetadata().getSchema().getOrCreateClass("EmbeddedWithRecordAttributes_Like"));

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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("like") instanceof Identifiable);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("like")).getSchemaClassName(),
        "EmbeddedWithRecordAttributes_Like");
    Assert.assertEquals(((Entity) doc.getProperty("like")).<Object>getProperty("count"), 0);
  }

  @Test
  public void testInsertEmbeddedWithRecordAttributes2() {
    var c = session.getMetadata().getSchema()
        .getOrCreateClass("EmbeddedWithRecordAttributes2");
    c.createProperty(
        "like",
        PropertyType.EMBEDDED,
        session.getMetadata().getSchema().getOrCreateClass("EmbeddedWithRecordAttributes2_Like"));

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

    doc = session.bindToSession(doc);
    Assert.assertTrue(doc.getProperty("like") instanceof Identifiable);
    Assert.assertEquals(
        ((EntityImpl) doc.getProperty("like")).getSchemaClassName(),
        "EmbeddedWithRecordAttributes2_Like");
    Assert.assertEquals(((Entity) doc.getProperty("like")).<Object>getProperty("count"), 0);
  }

  @Test
  public void testInsertWithClusterAsFieldName() {
    var c = session.getMetadata().getSchema()
        .getOrCreateClass("InsertWithClusterAsFieldName");

    session.begin();
    session
        .execute("INSERT INTO InsertWithClusterAsFieldName ( `cluster` ) values ( 'foo' )")
        .close();
    session.commit();

    var result =
        session.query("SELECT FROM InsertWithClusterAsFieldName").stream()
            .collect(Collectors.toList());

    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.getFirst().getProperty("cluster"), "foo");
  }

  @Test
  public void testInsertEmbeddedBigDecimal() {
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

    var result =
        session.query("SELECT FROM TestInsertEmbeddedBigDecimal").stream()
            .collect(Collectors.toList());
    Assert.assertEquals(result.size(), 1);
    Iterable ed = result.getFirst().getProperty("ed");
    var o = ed.iterator().next();
    Assert.assertEquals(o.getClass(), BigDecimal.class);
    Assert.assertEquals(((BigDecimal) o).intValue(), 5);
  }

  private List<Long> getValidPositions(int clusterId) {
    final List<Long> positions = new ArrayList<Long>();

    final RecordIteratorCluster<?> iteratorCluster =
        session.browseCluster(session.getClusterNameById(clusterId));

    for (var i = 0; i < 100; i++) {
      if (!iteratorCluster.hasNext()) {
        break;
      }
      var doc = iteratorCluster.next();
      positions.add(doc.getIdentity().getClusterPosition());
    }
    return positions;
  }
}
