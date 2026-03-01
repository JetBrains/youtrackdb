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

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.util.Pair;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class JSONTest extends BaseDBTest {

  private static Map<String, Object> nullableMap(Object... keyValues) {
    var map = new HashMap<String, Object>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  public static final String FORMAT_WITHOUT_RID =
      "version,class,type,keepTypes";

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    addBarackObamaAndFollowers();

    session.createClass("Device");
    session.createClass("Track");
    session.createClass("NestedLinkCreation");
    session.createClass("NestedLinkCreationFieldTypes");
    session.createClass("InnerDocCreation");
    session.createClass("InnerDocCreationFieldTypes");
  }

  @Test
  public void testAlmostLink() {
    session.executeInTx(tx -> {
      final var doc = session.newEntity();
      doc.updateFromJSON("{\"title\": \"#330: Dollar Coins Are Done\"}");
    });
  }

  @Test
  public void testNullList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [\"string\", null]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<String>getEmbeddedList("list");
      Assert.assertNotNull(list);
      Assert.assertEquals(list.getFirst(), "string");
      Assert.assertNull(list.get(1));
    });
  }

  @Test
  public void testBooleanList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [true, false]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Boolean>getEmbeddedList("list");
      Assert.assertNotNull(list);
      Assert.assertEquals(list.getFirst(), true);
      Assert.assertEquals(list.get(1), false);
    });
  }

  @Test
  public void testNumericIntegerList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [17,42]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Integer>getEmbeddedList("list");
      Assert.assertNotNull(list);
      Assert.assertEquals(list.getFirst(), 17);
      Assert.assertEquals(list.get(1), 42);
    });
  }

  @Test
  public void testNumericLongList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [100000000000,100000000001]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Long>getEmbeddedList("list");
      Assert.assertNotNull(list);
      Assert.assertEquals(list.getFirst(), 100000000000L);
      Assert.assertEquals(list.get(1), 100000000001L);
    });
  }

  @Test
  public void testNumericDoubleList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [17.3,42.7]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Double>getEmbeddedList("list");
      Assert.assertNotNull(list);
      Assert.assertEquals(list.getFirst(), 17.3);
      Assert.assertEquals(list.get(1), 42.7);
    });
  }

  @Test
  public void testNullity() {
    final var record = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON(
          "{\"gender\":{\"name\":\"Male\"},\"firstName\":\"Jack\",\"lastName\":\"Williams\"," +
              "\"phone\":\"561-401-3348\",\"email\":\"0586548571@example.com\",\"address\":{\"street1\":\"Smith"
              +
              " Ave\"," +
              "\"street2\":null,\"city\":\"GORDONSVILLE\",\"state\":\"VA\",\"code\":\"22942\"},\"dob\":\"2011-11-17"
              +
              " 03:17:04\"}"
      );
      return entity;
    });

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "gender", Map.of(
            "name", "Male"
        ),
        "firstName", "Jack",
        "lastName", "Williams",
        "phone", "561-401-3348",
        "email", "0586548571@example.com",
        "address", nullableMap(
            "street1", "Smith Ave",
            "street2", null,
            "city", "GORDONSVILLE",
            "state", "VA",
            "code", "22942"),
        "dob", "2011-11-17 03:17:04",
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion()
    );
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  @Test
  public void testNanNoTypes() {
    final var rid1 = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("nan", Double.NaN);
      entity.setProperty("p_infinity", Double.POSITIVE_INFINITY);
      entity.setProperty("n_infinity", Double.NEGATIVE_INFINITY);
      return entity.getIdentity();
    });

    final var p1 = session.computeInTx(tx -> {
      final var entity = session.loadEntity(rid1);
      return new Pair<>(entity.toMap(), entity.toJSON());
    });
    final var map1 = p1.getFirst();
    final var json1 = p1.getSecond();

    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(json1);
      Assert.assertEquals(entity.toMap(), map1);
    });

    final var rid2 = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("nan", Float.NaN);
      entity.setProperty("p_infinity", Float.POSITIVE_INFINITY);
      entity.setProperty("n_infinity", Float.NEGATIVE_INFINITY);

      return entity.getIdentity();
    });

    final var p2 = session.computeInTx(tx -> {
      final var entity = session.loadEntity(rid2);
      return new Pair<>(entity.toMap(), entity.toJSON());
    });
    final var map2 = p2.getFirst();
    final var json2 = p2.getSecond();

    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(json2);
      Assert.assertEquals(entity.toMap(), map2);
    });
  }

  @Test
  public void testEmbeddedList() {
    final var rid = session.computeInTx(tx -> {
      final var original = session.newEntity();
      final var list = original.<Entity>getOrCreateEmbeddedList("embeddedList");

      final var entityOne = session.newEmbeddedEntity();
      entityOne.setProperty("name", "Luca");
      list.add(entityOne);

      final var entityTwo = session.newEmbeddedEntity();
      entityTwo.setProperty("name", "Marcus");
      list.add(entityTwo);
      return original.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testEmbeddedMap() {
    final var rid = session.computeInTx(tx -> {
      final var original = session.newEntity();
      final var map = original.<Entity>getOrCreateEmbeddedMap("embeddedMap");

      final var entityOne = session.newEmbeddedEntity();
      entityOne.setProperty("name", "Luca");
      map.put("Luca", entityOne);

      final var entityTwo = session.newEmbeddedEntity();
      entityTwo.setProperty("name", "Marcus");
      map.put("Marcus", entityTwo);

      final var entityThree = session.newEmbeddedEntity();
      entityThree.setProperty("name", "Cesare");
      map.put("Cesare", entityThree);

      return original.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testListToJSON() {
    final var rid = session.computeInTx(tx -> {
      final var original = session.newEntity();
      final var list = original.<Entity>getOrCreateEmbeddedList("embeddedList");

      final var entityOne = session.newEmbeddedEntity();
      entityOne.setProperty("name", "Luca");
      list.add(entityOne);

      final var entityTwo = session.newEmbeddedEntity();
      entityTwo.setProperty("name", "Marcus");
      list.add(entityTwo);
      return original.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testEmptyEmbeddedMap() {
    final var rid = session.computeInTx(tx -> {
      final var original = session.newEntity();
      original.<Entity>getOrCreateEmbeddedMap("embeddedMap");
      return original.getIdentity();
    });
    checkJsonSerialization(rid);
  }

  @Test
  public void testMultiLevelTypes() {
    final var rid = session.computeInTx(tx -> {
      final var newEntity = session.newEntity();
      newEntity.setProperty("long", 100000000000L);
      newEntity.setProperty("date", new Date());
      newEntity.setProperty("byte", (byte) 12);

      final var firstLevelEntity = session.newEntity();
      firstLevelEntity.setProperty("long", 200000000000L);
      firstLevelEntity.setProperty("date", new Date());
      firstLevelEntity.setProperty("byte", (byte) 13);

      final var secondLevelEntity = session.newEntity();
      secondLevelEntity.setProperty("long", 300000000000L);
      secondLevelEntity.setProperty("date", new Date());
      secondLevelEntity.setProperty("byte", (byte) 14);

      final var thirdLevelEntity = session.newEntity();
      thirdLevelEntity.setProperty("long", 400000000000L);
      thirdLevelEntity.setProperty("date", new Date());
      thirdLevelEntity.setProperty("byte", (byte) 15);

      newEntity.setProperty("doc", firstLevelEntity);
      firstLevelEntity.setProperty("doc", secondLevelEntity);
      secondLevelEntity.setProperty("doc", thirdLevelEntity);

      return newEntity.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testNestedEmbeddedMap() {
    final var rid = session.computeInTx(tx -> {
      final var entity = session.newEntity();

      final var map1 = session.<Map<String, ?>>newEmbeddedMap();
      entity.getOrCreateEmbeddedMap("map1").put("map1", map1);

      final var map2 = session.<Map<String, ?>>newEmbeddedMap();
      map1.put("map2", map2);

      final var map3 = session.<Map<String, ?>>newEmbeddedMap();
      map2.put("map3", map3);

      return entity.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testFetchedJson() {
    session.begin();
    var rs = session
        .query("select * from Profile where name = 'Barack' and surname = 'Obama'");
    final var resultSet = rs.toList();
    rs.close();
    session.commit();

    for (var result : resultSet) {
      final var entity = result.asEntity();
      checkJsonSerialization(entity.getIdentity());
    }
  }

  @Test
  public void testSpecialChar() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        "{\"name\":{\"%Field\":[\"value1\",\"value2\"],\"%Field2\":{},\"%Field3\":\"value3\"}}"));

    checkJsonSerialization(record.getIdentity());

    final var expectedMap = Map.of(
        "name", Map.of(
            "%Field", List.of("value1", "value2"),
            "%Field2", Map.of(),
            "%Field3", "value3"
        ),
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion()
    );
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  @Test
  public void testArrayOfArray() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        """
            {
              "@type": "d",
              "@class": "Track",
              "type": "LineString",
              "coordinates": [ [ 100, 0 ],  [ 101, 1 ] ]
            }"""
    ));

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "@class", "Track",
        "type", "LineString",
        "coordinates", List.of(
            List.of(100, 0),
            List.of(101, 1)
        ),
        "@rid", record.getIdentity(),
        "@version", record.getVersion()
    );
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  @Test
  public void testLongTypes() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        """
            {
              "@type": "d",
              "@class": "Track",
              "type": "LineString",
              "coordinates": [ [32874387347347, 0],  [-23736753287327, 1] ]
            }"""
    ));

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "@class", "Track",
        "@version", record.getVersion(),
        "type", "LineString",
        "coordinates", List.of(
            List.of(32874387347347L, 0),
            List.of(-23736753287327L, 1)
        ),
        "@rid", record.getIdentity()
    );

    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  @Test
  public void testSpecialChars() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        "{\"Field\":{\"Key1\":[\"Value1\",\"Value2\"],\"Key2\":{\"%%dummy%%\":null},\"Key3\":\"Value3\"}}"));

    checkJsonSerialization(record.getIdentity());

    final var expectedMap = Map.of(
        "Field", Map.of(
            "Key1", List.of("Value1", "Value2"),
            "Key2", nullableMap("%%dummy%%", null),
            "Key3", "Value3"
        ),
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion()
    );

    checkJsonSerialization(record.getIdentity(), expectedMap);
  }


  @Test
  public void testSameNameCollectionsAndMap() {
    final var rid1 = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("string", "STRING_VALUE");
      for (var i = 0; i < 1; i++) {
        final var entity1 = session.newEntity();
        entity.setProperty("number", i);
        entity.getOrCreateLinkSet("out").add(entity1);

        for (var j = 0; j < 1; j++) {
          final var entity2 = session.newEntity();
          entity2.setProperty("blabla", j);
          entity1.getOrCreateLinkMap("out").put("" + j, entity2);
          final var doc3 = session.newEntity();
          doc3.setProperty("blubli", "0");
          entity2.setProperty("out", doc3);
        }
      }

      return entity.getIdentity();
    });

    checkJsonSerialization(rid1);

    final var rid2 = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("string", "STRING_VALUE");
      for (var i = 0; i < 10; i++) {
        final var entity1 = session.newEntity();
        entity.setProperty("number", i);

        entity1.getOrCreateLinkList("out").add(entity);
        for (var j = 0; j < 5; j++) {
          final var entity2 = session.newEntity();
          entity2.setProperty("blabla", j);

          entity1.getOrCreateLinkList("out").add(entity2);
          final var entity3 = session.newEntity();

          entity3.setProperty("blubli", "" + (i + j));
          entity2.setProperty("out", entity3);
        }
        entity.getOrCreateLinkMap("out").put("" + i, entity1);
      }
      return entity.getIdentity();
    });

    checkJsonSerialization(rid2);
  }

  @Test
  public void testSameNameCollectionsAndMap2() {
    final var rid = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("string", "STRING_VALUE");

      for (var i = 0; i < 2; i++) {
        final var entity1 = session.newEntity();
        entity.getOrCreateLinkList("theList").add(entity1);

        for (var j = 0; j < 5; j++) {
          final var entity2 = session.newEntity();
          entity2.setProperty("blabla", j);
          entity1.getOrCreateLinkMap("theMap").put("" + j, entity2);
        }

        entity.getOrCreateLinkList("theList").add(entity1);
      }

      return entity.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testSameNameCollectionsAndMap3() {
    final var rid = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("string", "STRING_VALUE");
      for (var i = 0; i < 2; i++) {
        final var docMap = session.<Entity>newEmbeddedMap();

        for (var j = 0; j < 5; j++) {
          final var doc1 = session.newEmbeddedEntity();
          doc1.setProperty("blabla", j);
          docMap.put("" + j, doc1);
        }

        entity.<Map<String, Entity>>getOrCreateEmbeddedList("theList").add(docMap);
      }
      return entity.getIdentity();
    });

    checkJsonSerialization(rid);
  }

  @Test
  public void testNestedJsonCollection() {
    session.executeInTx(tx -> session
        .command(
            "insert into device (resource_id, domainset) VALUES (0, [ { 'domain' : 'abc' }, {"
                + " 'domain' : 'pqr' } ])"
        ));

    session.executeInTx(tx -> {
      var result = session.query("select from device where domainset.domain contains 'abc'");
      Assert.assertTrue(result.stream().findAny().isPresent());

      result = session.query("select from device where domainset[domain = 'abc'] is not null");
      Assert.assertTrue(result.stream().findAny().isPresent());

      result = session.query("select from device where domainset.domain contains 'pqr'");
      Assert.assertTrue(result.stream().findAny().isPresent());
    });
  }

  @Test
  public void testNestedEmbeddedJson() {
    session.executeInTx(tx -> session.command(
        "insert into device (resource_id, domainset) VALUES (1, { 'domain' : 'eee' })"));

    session.executeInTx(tx -> {
      final var result = session.query("select from device where domainset.domain = 'eee'");
      Assert.assertTrue(result.stream().findAny().isPresent());
    });
  }

  @Test
  public void testNestedMultiLevelEmbeddedJson() {
    session.executeInTx(tx -> session
        .command(
            "insert into device (domainset) values ({'domain' : { 'lvlone' : { 'value' : 'five' } }"
                + " } )"
        ));

    session.executeInTx(tx -> {
      final var result =
          session.query("select from device where domainset.domain.lvlone.value = 'five'");

      Assert.assertTrue(result.stream().findAny().isPresent());
    });
  }

  @Test
  public void testSpaces() {
    final var rid = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      final var test =
          "{"
              + "\"embedded\": {"
              + "\"second_embedded\":  {"
              + "\"text\":\"this is a test\""
              + "}"
              + "}"
              + "}";
      entity.updateFromJSON(test);
      return entity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = session.loadEntity(rid);
      Assert.assertTrue(entity.toJSON("fetchPlan:*:0,rid").contains("this is a test"));
    });
  }

  @Test
  public void testEscaping() {
    final var rid = session.computeInTx(tx -> {
      final var s =
          ("{\"name\": \"test\", \"nested\": { \"key\": \"value\", \"anotherKey\": 123 }, \"deep\":"
              + " {\"deeper\": { \"k\": \"v\",\"quotes\": \"\\\"\\\",\\\"oops\\\":\\\"123\\\"\","
              + " \"likeJson\": \"[1,2,3]\",\"spaces\": \"value with spaces\"}}}");
      final var entity = session.createOrLoadEntityFromJson(s);
      Assert.assertEquals(
          entity.<Map<String, Map<String, String>>>getProperty("deep").get("deeper").get("quotes"),
          "\"\",\"oops\":\"123\""
      );
      return entity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var res = session.loadEntity(rid).toJSON();
      Assert.assertTrue(res.contains("\"quotes\":\"\\\"\\\",\\\"oops\\\":\\\"123\\\"\""));
    });
  }

  @Test
  public void testEscapingDoubleQuotes() {
    final var rid = session.computeInTx(tx -> {
      final var sb =
          """
              {
                "foo":{
                  "bar":{
                    "P357":[{
                      "datavalue":{ "value":"\\"\\"" }
                    }]
                  },
                  "three": "a"
                }
              }""";
      return session.createOrLoadEntityFromJson(sb).getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = session.loadEntity(rid);
      Assert.assertEquals(entity.<Map<String, ?>>getProperty("foo").get("three"), "a");

      final var c =
          entity.<Map<String, Map<String, List<Map<String, Map<String, String>>>>>>getProperty(
              "foo"
          ).get("bar").get("P357");
      Assert.assertEquals(c.size(), 1);
      final var map = c.getFirst();
      Assert.assertEquals(map.get("datavalue").get("value"), "\"\"");
    });
  }

  @Test
  public void testEscapingDoubleQuotes2() {
    final var rid = session.computeInTx(tx -> {
      final var sb =
          """
              {
                "foo":{
                  "bar":{
                    "P357":[{
                      "datavalue":{ "value":"\\\"" }
                    }]
                  },
                  "three": "a"
                }
              }""";
      System.out.println(sb);
      return session.createOrLoadEntityFromJson(sb).getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = session.loadEntity(rid);
      Assert.assertEquals(entity.<Map<String, ?>>getProperty("foo").get("three"), "a");

      final var c =
          entity.<Map<String, Map<String, List<Map<String, Map<String, String>>>>>>getProperty(
              "foo"
          ).get("bar").get("P357");
      Assert.assertEquals(c.size(), 1);
      final var map = c.getFirst();
      Assert.assertEquals(map.get("datavalue").get("value"), "\"");
    });
  }

  @Test
  public void testEscapingDoubleQuotes3() {
    final var rid = session.computeInTx(tx -> {
      final var sb =
          """
              {
                "foo":{
                  "bar":{
                    "P357":[{
                      "datavalue":{ "value":"\\\"" }
                    }]
                  }
                }
              }""";
      return session.createOrLoadEntityFromJson(sb).getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = session.loadEntity(rid);
      final var c =
          entity.<Map<String, Map<String, List<Map<String, Map<String, String>>>>>>getProperty(
              "foo"
          ).get("bar").get("P357");
      Assert.assertEquals(c.size(), 1);
      final var map = c.getFirst();
      Assert.assertEquals(map.get("datavalue").get("value"), "\"");
    });
  }

  @Test
  public void testEmbeddedQuotes() {
    session.executeInTx(tx -> {
      final var entity =
          session.createOrLoadEntityFromJson(
              "{\"mainsnak\":{\"datavalue\":{\"value\":\"Sub\\\\urban\"}}}");
      Assert.assertEquals(
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"),
          "Sub\\urban"
      );
    });
  }

  @Test
  public void testEmbeddedQuotes2() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(
          "{\"datavalue\":{\"value\":\"Sub\\\\urban\"}}");
      Assert.assertEquals(
          entity.<Map<String, String>>getProperty("datavalue").get("value"),
          "Sub\\urban"
      );
    });
  }

  @Test
  public void testEmbeddedQuotes2a() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Sub\\\\urban\"}");
      Assert.assertEquals(entity.getProperty("datavalue"), "Sub\\urban");
    });
  }

  @Test
  public void testEmbeddedQuotes3() {
    session.executeInTx(tx -> {
      final var entity =
          session.createOrLoadEntityFromJson(
              "{\"mainsnak\":{\"datavalue\":{\"value\":\"Suburban\\\\\\\"\"}}}");
      Assert.assertEquals(
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"),
          "Suburban\\\""
      );
    });
  }

  @Test
  public void testEmbeddedQuotes4() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Suburban\\\\\\\"\"}");
      Assert.assertEquals(entity.getProperty("datavalue"), "Suburban\\\"");
    });
  }

  @Test
  public void testEmbeddedQuotes5() {
    session.executeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON("{\"mainsnak\":{\"datavalue\":{\"value\":\"Suburban\\\\\"}}}");
      Assert.assertEquals(
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"),
          "Suburban\\"
      );
    });
  }

  @Test
  public void testEmbeddedQuotes6() {
    session.executeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON("{\"datavalue\":{\"value\":\"Suburban\\\\\"}}");
      Assert.assertEquals(
          entity.<Map<String, String>>getProperty("datavalue").get("value"),
          "Suburban\\"
      );
    });
  }

  @Test
  public void testEmbeddedQuotes7() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Suburban\\\\\"}");
      Assert.assertEquals(entity.getProperty("datavalue"), "Suburban\\");
    });
  }


  @Test
  public void testEmpty() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{}");
      Assert.assertTrue(entity.getPropertyNames().isEmpty());
    });
  }

  @Test
  public void testInvalidJson() {
    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("{"));
      Assert.fail();
    } catch (SerializationException ignored) {
    }

    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("{\"foo\":{}"));
      Assert.fail();
    } catch (SerializationException ignored) {
    }

    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("{{}"));
    } catch (SerializationException ignored) {
    }

    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("{}}"));
      Assert.fail();
    } catch (SerializationException ignored) {
    }

    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("}"));
      Assert.fail();
    } catch (SerializationException ignored) {
    }
  }

  @Test
  public void testDates() {
    final var now = new Date(1350518475000L);

    final var rid = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.setProperty("date", now);
      return entity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = session.loadEntity(rid);
      final var json = entity.toJSON(FORMAT_WITHOUT_RID);

      final var unmarshalled = session.createOrLoadEntityFromJson(json);
      Assert.assertEquals(unmarshalled.getProperty("date"), now);
    });
  }

  @Test
  public void shouldDeserializeFieldWithCurlyBraces() {
    final var json = """
        {"a":"{dd}","bl":{"b":"c","a":"d"}}
        """;
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(json);
      Assert.assertEquals(entity.getProperty("a"), "{dd}");
      Assert.assertTrue(entity.getProperty("bl") instanceof Map);
    });
  }

  @Test
  public void testList() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"list\" : [\"string\", 42]}");
      final var list = entity.<List<?>>getProperty("list");

      Assert.assertEquals(list.get(0), "string");
      Assert.assertEquals(list.get(1), 42);
    });
  }

  @Test
  public void testEmbeddedRIDBagDeserialisationWhenFieldTypeIsProvided() {
    final var eid = session.computeInTx(tx -> session.newEntity().getIdentity());

    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(
          """
              {
                "@fieldTypes":{"in_EHasGoodStudents": "g"},
                "FirstName":"Student A0",
                "in_EHasGoodStudents":["%s"]
              }""".formatted(eid)
      );

      final var bag = entity.<LinkBag>getProperty("in_EHasGoodStudents");
      Assert.assertEquals(bag.size(), 1);
      final var rid = bag.iterator().next().primaryRid();
      Assert.assertEquals(rid, eid);
    });
  }

  @Test
  public void testNestedLinkCreation() {
    final var jaimeRid = session.computeInTx(tx -> {
      final var jaimeEntity = session.newEntity("NestedLinkCreation");
      jaimeEntity.setProperty("name", "jaime");
      return jaimeEntity.getIdentity();
    });

    final var cerseiRecord = session.computeInTx(tx -> {
      final var jaimeEntity = session.loadEntity(jaimeRid);
      final var cerseiEntity = session.newEntity("NestedLinkCreation");

      cerseiEntity.updateFromJSON(
          "{\"name\":\"cersei\",\"valonqar\":" + jaimeEntity.toJSON() + "}"
      );
      return cerseiEntity;
    });

    checkJsonSerialization(jaimeRid);
    checkJsonSerialization(cerseiRecord.getIdentity());

    final var jaimeMap = Map.of(
        "name", "jaime",
        "@rid", jaimeRid,
        "@class", "NestedLinkCreation",
        //both entities were update because a new link was added.
        "@version", cerseiRecord.getVersion()
    );
    checkJsonSerialization(jaimeRid, jaimeMap);

    final var cerseiMap = Map.of(
        "name", "cersei",
        "valonqar", jaimeRid,
        "@rid", cerseiRecord.getIdentity(),
        "@class", "NestedLinkCreation",
        "@version", cerseiRecord.getVersion()
    );
    checkJsonSerialization(cerseiRecord.getIdentity(), cerseiMap);
  }

  @Test
  public void testNestedLinkCreationFieldTypes() {
    final var jaimeRecord = session.computeInTx(tx -> {
      final var jaimeDoc = session.newEntity("NestedLinkCreationFieldTypes");
      jaimeDoc.setProperty("name", "jaime");
      return jaimeDoc;
    });

    final var cerseiRecord = session.computeInTx(tx -> {
      // The link between jaime and cersei is saved properly - the #2263 test case
      final var cerseiDoc = session.newEntity("NestedLinkCreationFieldTypes");
      cerseiDoc.updateFromJSON(
          "{\"@type\":\"d\"," +
              "\"@fieldTypes\":{\"valonqar\" : \"x\"},\"name\":\"cersei\",\"valonqar\":\""
              + jaimeRecord.getIdentity()
              + "\"}"
      );
      return cerseiDoc;
    });

    checkJsonSerialization(jaimeRecord.getIdentity());
    checkJsonSerialization(cerseiRecord.getIdentity());

    final var jaimeMap = Map.of(
        "name", "jaime",
        "@rid", jaimeRecord.getIdentity(),
        "@class", "NestedLinkCreationFieldTypes",
        //both entities were update because a new link was added.
        "@version", cerseiRecord.getVersion()
    );
    checkJsonSerialization(jaimeRecord.getIdentity(), jaimeMap);

    final var cerseiMap = Map.of(
        "name", "cersei",
        "valonqar", jaimeRecord.getIdentity(),
        "@rid", cerseiRecord.getIdentity(),
        "@class", "NestedLinkCreationFieldTypes",
        //both entities were update because a new link was added.
        "@version", cerseiRecord.getVersion()
    );
    checkJsonSerialization(cerseiRecord.getIdentity(), cerseiMap);
  }

  @Test
  public void testNestedLinkCreationEmbeddedProhibited() {

    final var jaimeRid = session.computeInTx(tx -> {
      final var jaimeEntity = session.newEntity("NestedLinkCreation");
      jaimeEntity.setProperty("name", "jaime");
      return jaimeEntity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var jaimeEntity = session.loadEntity(jaimeRid);
      final var tyrionEntity = session.newEntity("NestedLinkCreation");

      try {
        tyrionEntity.updateFromJSON(
            ("{\"name\":\"tyrion\",\"emergency_contact\":{\"@type\":\"d\","
                + " \"relationship\":\"brother\",\"contact\":"
                + jaimeEntity.toJSON()
                + "}}")
        );
        Assert.fail("Nested entities should not be allowed to have links inside them.");
      } catch (SerializationException ex) {
        Assert.assertTrue(ex.getCause() instanceof IllegalArgumentException);
      }
    });
  }

  @Test
  public void testInnerDocCreation() {
    final var adamRecord = session.computeInTx(tx -> {
      final var adamDoc = session.newEntity("InnerDocCreation");
      adamDoc.updateFromJSON("{\"name\":\"adam\"}");

      return adamDoc;
    });

    final var eveRecord = session.computeInTx(tx -> {
      final var adamDoc = session.loadEntity(adamRecord.getIdentity());
      final var eveDoc = session.newEntity("InnerDocCreation");
      eveDoc.updateFromJSON(
          "{\"@type\":\"d\",\"name\":\"eve\",\"friends\":[" + adamDoc.toJSON() + "]}"
      );
      return eveDoc;
    });

    checkJsonSerialization(adamRecord.getIdentity());
    checkJsonSerialization(eveRecord.getIdentity());

    final var adamMap = Map.of(
        "name", "adam",
        "@rid", adamRecord.getIdentity(),
        "@class", "InnerDocCreation",
        //we use eve record here as a double-sided link was updated in transaction increment updating
        //versions of both records
        "@version", eveRecord.getVersion()
    );
    checkJsonSerialization(adamRecord.getIdentity(), adamMap);

    final var eveMap = Map.of(
        "name", "eve",
        "friends", List.of(adamRecord.getIdentity()),
        "@rid", eveRecord.getIdentity(),
        "@class", "InnerDocCreation",
        "@version", eveRecord.getVersion()
    );
    checkJsonSerialization(eveRecord.getIdentity(), eveMap);
  }

  public void testInnerDocCreationFieldTypes() {
    final var p = session.computeInTx(tx -> {
      final var adamDoc = session.newEntity("InnerDocCreationFieldTypes");
      adamDoc.updateFromJSON("{\"name\":\"adam\"}");

      final var eveDoc = session.newEntity("InnerDocCreationFieldTypes");
      eveDoc.updateFromJSON(
          ("{\"@type\":\"d\", \"@fieldTypes\" : { \"friends\":\"z\"}, \"name\":\"eve\",\"friends\":[\""
              + adamDoc.getIdentity()
              + "\"]}")
      );

      return new Pair<>(adamDoc, eveDoc);
    });
    final var adamEntity = p.getFirst();
    final var eveEntity = p.getSecond();

    checkJsonSerialization(adamEntity.getIdentity());
    checkJsonSerialization(eveEntity.getIdentity());

    final var expectedAdamMap = Map.of(
        "name", "adam",
        "@version", adamEntity.getVersion(),
        "@rid", adamEntity.getIdentity(),
        "@class", "InnerDocCreationFieldTypes"
    );
    checkJsonSerialization(adamEntity.getIdentity(), expectedAdamMap);

    final var expectedEveMap = Map.of(
        "name", "eve",
        "friends", List.of(adamEntity.getIdentity()),
        "@version", eveEntity.getVersion(),
        "@rid", eveEntity.getIdentity(),
        "@class", "InnerDocCreationFieldTypes"
    );
    checkJsonSerialization(eveEntity.getIdentity(), expectedEveMap);
  }


  @Test
  public void testInvalidLink() {
    try {
      session.executeInTx(tx -> {
        final var nullRefDoc = session.newEntity();
        nullRefDoc.updateFromJSON("{\"name\":\"Luca\", \"ref\":\"#-1:-1\"}");
      });
      Assert.fail();
    } catch (RecordNotFoundException ignored) {
      // expected
    }
  }


  @Test
  public void testOtherJson() {
    final var record = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON(
          ("{\"Salary\":1500.0,\"Type\":\"Person\",\"Address\":[{\"Zip\":\"JX2"
              + " MSX\",\"Type\":\"Home\",\"Street1\":\"13 Marge"
              + " Street\",\"Country\":\"Holland\",\"Id\":\"Address-28813211\",\"City\":\"Amsterdam\",\"From\":\"1996-02-01\",\"To\":\"1998-01-01\"},{\"Zip\":\"90210\",\"Type\":\"Work\",\"Street1\":\"100"
              + " Hollywood"
              + " Drive\",\"Country\":\"USA\",\"Id\":\"Address-11595040\",\"City\":\"Los"
              + " Angeles\",\"From\":\"2009-09-01\"}],\"Id\":\"Person-7464251\",\"Name\":\"Stan\"}")
      );
      return entity;
    });
    checkJsonSerialization(record.getIdentity());
    final var map = Map.of(
        "Salary", 1500.0,
        "Type", "Person",
        "Address", List.of(
            Map.of(
                "Zip", "JX2 MSX",
                "Type", "Home",
                "Street1", "13 Marge Street",
                "Country", "Holland",
                "Id", "Address-28813211",
                "City", "Amsterdam",
                "From", "1996-02-01",
                "To", "1998-01-01"
            ),
            Map.of(
                "Zip", "90210",
                "Type", "Work",
                "Street1", "100 Hollywood Drive",
                "Country", "USA",
                "Id", "Address-11595040",
                "City", "Los Angeles",
                "From", "2009-09-01"
            )
        ),
        "Id", "Person-7464251",
        "Name", "Stan",
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion()
    );
    checkJsonSerialization(record.getIdentity(), map);
  }

  @Test
  public void testScientificNotation() {
    final var record = session.computeInTx(tx -> {
      final var doc = session.newEntity();
      doc.updateFromJSON("{\"number1\": -9.2741500e-31, \"number2\": 741800E+290}");
      return doc;
    });

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "number1", -9.27415E-31,
        "number2", 741800E+290,
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion()
    );
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }


  private void checkJsonSerialization(RID rid) {
    session.begin();
    try {
      final var original = session.loadEntity(rid);
      final var json = original.toJSON(FORMAT_WITHOUT_RID);

      final var newEntity = session.createOrLoadEntityFromJson(json);

      Assert.assertEquals(newEntity.getVersion(), 0);

      final var originalMap = original.toMap();
      final var loadedMap = newEntity.toMap();

      originalMap.remove(EntityHelper.ATTRIBUTE_RID);
      loadedMap.remove(EntityHelper.ATTRIBUTE_RID);

      originalMap.remove(EntityHelper.ATTRIBUTE_VERSION);
      loadedMap.remove(EntityHelper.ATTRIBUTE_VERSION);

      Assert.assertEquals(originalMap, loadedMap);
    } finally {
      session.rollback();
    }
  }

  private void checkJsonSerialization(RID rid, Map<String, ?> expectedMap) {
    session.executeInTx(tx -> {
      final var original = session.loadEntity(rid);
      final var originalMap = original.toMap();
      Assert.assertEquals(originalMap, expectedMap);
    });
  }

}
