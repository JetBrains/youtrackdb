// Migrated from: com.jetbrains.youtrackdb.auto.JSONTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JSONTest extends BaseDBJUnit5Test {

  private static Map<String, Object> nullableMap(Object... keyValues) {
    var map = new HashMap<String, Object>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  public static final String FORMAT_WITHOUT_RID =
      "version,class,type,keepTypes";

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    addBarackObamaAndFollowers();

    session.createClass("Device");
    session.createClass("Track");
    session.createClass("NestedLinkCreation");
    session.createClass("NestedLinkCreationFieldTypes");
    session.createClass("InnerDocCreation");
    session.createClass("InnerDocCreationFieldTypes");
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testAlmostLink
  @Test
  void testAlmostLink() {
    session.executeInTx(tx -> {
      final var doc = session.newEntity();
      doc.updateFromJSON("{\"title\": \"#330: Dollar Coins Are Done\"}");
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNullList
  @Test
  void testNullList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [\"string\", null]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<String>getEmbeddedList("list");
      assertNotNull(list);
      assertEquals("string", list.getFirst());
      assertNull(list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testBooleanList
  @Test
  void testBooleanList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [true, false]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Boolean>getEmbeddedList("list");
      assertNotNull(list);
      assertEquals(true, list.getFirst());
      assertEquals(false, list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNumericIntegerList
  @Test
  void testNumericIntegerList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [17,42]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Integer>getEmbeddedList("list");
      assertNotNull(list);
      assertEquals(17, list.getFirst());
      assertEquals(42, list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNumericLongList
  @Test
  void testNumericLongList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [100000000000,100000000001]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Long>getEmbeddedList("list");
      assertNotNull(list);
      assertEquals(100000000000L, list.getFirst());
      assertEquals(100000000001L, list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNumericDoubleList
  @Test
  void testNumericDoubleList() {
    session.executeInTx(tx -> {
      final var documentSource = ((EntityImpl) session.newEntity());
      documentSource.updateFromJSON("{\"list\" : [17.3,42.7]}");

      final var documentTarget = ((EntityImpl) session.newEntity());
      documentTarget.unsetDirty();
      documentTarget.fromStream(documentSource.toStream());

      final var list = documentTarget.<Double>getEmbeddedList("list");
      assertNotNull(list);
      assertEquals(17.3, list.getFirst());
      assertEquals(42.7, list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNullity
  @Test
  void testNullity() {
    final var record = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON(
          "{\"gender\":{\"name\":\"Male\"},\"firstName\":\"Jack\",\"lastName\":\"Williams\","
              + "\"phone\":\"561-401-3348\",\"email\":\"0586548571@example.com\",\"address\":{\"street1\":\"Smith"
              + " Ave\","
              + "\"street2\":null,\"city\":\"GORDONSVILLE\",\"state\":\"VA\",\"code\":\"22942\"},\"dob\":\"2011-11-17"
              + " 03:17:04\"}");
      return entity;
    });

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "gender", Map.of(
            "name", "Male"),
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
        "@version", record.getVersion());
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNanNoTypes
  @Test
  void testNanNoTypes() {
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
      assertEquals(map1, entity.toMap());
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
      assertEquals(map2, entity.toMap());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedList
  @Test
  void testEmbeddedList() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedMap
  @Test
  void testEmbeddedMap() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testListToJSON
  @Test
  void testListToJSON() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmptyEmbeddedMap
  @Test
  void testEmptyEmbeddedMap() {
    final var rid = session.computeInTx(tx -> {
      final var original = session.newEntity();
      original.<Entity>getOrCreateEmbeddedMap("embeddedMap");
      return original.getIdentity();
    });
    checkJsonSerialization(rid);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testMultiLevelTypes
  @Test
  void testMultiLevelTypes() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedEmbeddedMap
  @Test
  void testNestedEmbeddedMap() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testFetchedJson
  @Test
  void testFetchedJson() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSpecialChar
  @Test
  void testSpecialChar() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        "{\"name\":{\"%Field\":[\"value1\",\"value2\"],\"%Field2\":{},\"%Field3\":\"value3\"}}"));

    checkJsonSerialization(record.getIdentity());

    final var expectedMap = Map.of(
        "name", Map.of(
            "%Field", List.of("value1", "value2"),
            "%Field2", Map.of(),
            "%Field3", "value3"),
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion());
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testArrayOfArray
  @Test
  void testArrayOfArray() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        """
            {
              "@type": "d",
              "@class": "Track",
              "type": "LineString",
              "coordinates": [ [ 100, 0 ],  [ 101, 1 ] ]
            }"""));

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "@class", "Track",
        "type", "LineString",
        "coordinates", List.of(
            List.of(100, 0),
            List.of(101, 1)),
        "@rid", record.getIdentity(),
        "@version", record.getVersion());
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testLongTypes
  @Test
  void testLongTypes() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        """
            {
              "@type": "d",
              "@class": "Track",
              "type": "LineString",
              "coordinates": [ [32874387347347, 0],  [-23736753287327, 1] ]
            }"""));

    checkJsonSerialization(record.getIdentity());
    final var expectedMap = Map.of(
        "@class", "Track",
        "@version", record.getVersion(),
        "type", "LineString",
        "coordinates", List.of(
            List.of(32874387347347L, 0),
            List.of(-23736753287327L, 1)),
        "@rid", record.getIdentity());

    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSpecialChars
  @Test
  void testSpecialChars() {
    final var record = session.computeInTx(tx -> session.createOrLoadEntityFromJson(
        "{\"Field\":{\"Key1\":[\"Value1\",\"Value2\"],\"Key2\":{\"%%dummy%%\":null},\"Key3\":\"Value3\"}}"));

    checkJsonSerialization(record.getIdentity());

    final var expectedMap = Map.of(
        "Field", Map.of(
            "Key1", List.of("Value1", "Value2"),
            "Key2", nullableMap("%%dummy%%", null),
            "Key3", "Value3"),
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion());

    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSameNameCollectionsAndMap
  @Test
  void testSameNameCollectionsAndMap() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSameNameCollectionsAndMap2
  @Test
  void testSameNameCollectionsAndMap2() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSameNameCollectionsAndMap3
  @Test
  void testSameNameCollectionsAndMap3() {
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

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedJsonCollection
  @Test
  void testNestedJsonCollection() {
    session.executeInTx(tx -> session
        .command(
            "insert into device (resource_id, domainset) VALUES (0, [ { 'domain' : 'abc' }, {"
                + " 'domain' : 'pqr' } ])"));

    session.executeInTx(tx -> {
      var result = session.query("select from device where domainset.domain contains 'abc'");
      assertTrue(result.stream().findAny().isPresent());

      result = session.query("select from device where domainset[domain = 'abc'] is not null");
      assertTrue(result.stream().findAny().isPresent());

      result = session.query("select from device where domainset.domain contains 'pqr'");
      assertTrue(result.stream().findAny().isPresent());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedEmbeddedJson
  @Test
  void testNestedEmbeddedJson() {
    session.executeInTx(tx -> session.command(
        "insert into device (resource_id, domainset) VALUES (1, { 'domain' : 'eee' })"));

    session.executeInTx(tx -> {
      final var result = session.query("select from device where domainset.domain = 'eee'");
      assertTrue(result.stream().findAny().isPresent());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedMultiLevelEmbeddedJson
  @Test
  void testNestedMultiLevelEmbeddedJson() {
    session.executeInTx(tx -> session
        .command(
            "insert into device (domainset) values ({'domain' : { 'lvlone' : { 'value' : 'five' } }"
                + " } )"));

    session.executeInTx(tx -> {
      final var result =
          session.query("select from device where domainset.domain.lvlone.value = 'five'");

      assertTrue(result.stream().findAny().isPresent());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testSpaces
  @Test
  void testSpaces() {
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
      assertTrue(entity.toJSON("fetchPlan:*:0,rid").contains("this is a test"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEscaping
  @Test
  void testEscaping() {
    final var rid = session.computeInTx(tx -> {
      final var s =
          ("{\"name\": \"test\", \"nested\": { \"key\": \"value\", \"anotherKey\": 123 }, \"deep\":"
              + " {\"deeper\": { \"k\": \"v\",\"quotes\": \"\\\"\\\",\\\"oops\\\":\\\"123\\\"\","
              + " \"likeJson\": \"[1,2,3]\",\"spaces\": \"value with spaces\"}}}");
      final var entity = session.createOrLoadEntityFromJson(s);
      assertEquals(
          "\"\",\"oops\":\"123\"",
          entity.<Map<String, Map<String, String>>>getProperty("deep").get("deeper").get("quotes"));
      return entity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var res = session.loadEntity(rid).toJSON();
      assertTrue(res.contains("\"quotes\":\"\\\"\\\",\\\"oops\\\":\\\"123\\\"\""));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEscapingDoubleQuotes
  @Test
  void testEscapingDoubleQuotes() {
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
      assertEquals("a", entity.<Map<String, ?>>getProperty("foo").get("three"));

      final var c =
          entity.<Map<String, Map<String, List<Map<String, Map<String, String>>>>>>getProperty(
              "foo").get("bar").get("P357");
      assertEquals(1, c.size());
      final var map = c.getFirst();
      assertEquals("\"\"", map.get("datavalue").get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEscapingDoubleQuotes2
  @Test
  void testEscapingDoubleQuotes2() {
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
      assertEquals("a", entity.<Map<String, ?>>getProperty("foo").get("three"));

      final var c =
          entity.<Map<String, Map<String, List<Map<String, Map<String, String>>>>>>getProperty(
              "foo").get("bar").get("P357");
      assertEquals(1, c.size());
      final var map = c.getFirst();
      assertEquals("\"", map.get("datavalue").get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEscapingDoubleQuotes3
  @Test
  void testEscapingDoubleQuotes3() {
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
              "foo").get("bar").get("P357");
      assertEquals(1, c.size());
      final var map = c.getFirst();
      assertEquals("\"", map.get("datavalue").get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes
  @Test
  void testEmbeddedQuotes() {
    session.executeInTx(tx -> {
      final var entity =
          session.createOrLoadEntityFromJson(
              "{\"mainsnak\":{\"datavalue\":{\"value\":\"Sub\\\\urban\"}}}");
      assertEquals(
          "Sub\\urban",
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes2
  @Test
  void testEmbeddedQuotes2() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(
          "{\"datavalue\":{\"value\":\"Sub\\\\urban\"}}");
      assertEquals(
          "Sub\\urban",
          entity.<Map<String, String>>getProperty("datavalue").get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes2a
  @Test
  void testEmbeddedQuotes2a() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Sub\\\\urban\"}");
      assertEquals("Sub\\urban", entity.getProperty("datavalue"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes3
  @Test
  void testEmbeddedQuotes3() {
    session.executeInTx(tx -> {
      final var entity =
          session.createOrLoadEntityFromJson(
              "{\"mainsnak\":{\"datavalue\":{\"value\":\"Suburban\\\\\\\"\"}}}");
      assertEquals(
          "Suburban\\\"",
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes4
  @Test
  void testEmbeddedQuotes4() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Suburban\\\\\\\"\"}");
      assertEquals("Suburban\\\"", entity.getProperty("datavalue"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes5
  @Test
  void testEmbeddedQuotes5() {
    session.executeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON("{\"mainsnak\":{\"datavalue\":{\"value\":\"Suburban\\\\\"}}}");
      assertEquals(
          "Suburban\\",
          entity.<Map<String, Map<String, String>>>getProperty("mainsnak").get("datavalue")
              .get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes6
  @Test
  void testEmbeddedQuotes6() {
    session.executeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON("{\"datavalue\":{\"value\":\"Suburban\\\\\"}}");
      assertEquals(
          "Suburban\\",
          entity.<Map<String, String>>getProperty("datavalue").get("value"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedQuotes7
  @Test
  void testEmbeddedQuotes7() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"datavalue\":\"Suburban\\\\\"}");
      assertEquals("Suburban\\", entity.getProperty("datavalue"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmpty
  @Test
  void testEmpty() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{}");
      assertTrue(entity.getPropertyNames().isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testInvalidJson
  @Test
  void testInvalidJson() {
    assertThrows(SerializationException.class,
        () -> session.executeInTx(tx -> session.createOrLoadEntityFromJson("{")));

    assertThrows(SerializationException.class,
        () -> session.executeInTx(tx -> session.createOrLoadEntityFromJson("{\"foo\":{}")));

    // Note: original test did not call Assert.fail() for this case
    try {
      session.executeInTx(tx -> session.createOrLoadEntityFromJson("{{}"));
    } catch (SerializationException ignored) {
    }

    assertThrows(SerializationException.class,
        () -> session.executeInTx(tx -> session.createOrLoadEntityFromJson("{}}")));

    assertThrows(SerializationException.class,
        () -> session.executeInTx(tx -> session.createOrLoadEntityFromJson("}")));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testDates
  @Test
  void testDates() {
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
      assertEquals(now, unmarshalled.getProperty("date"));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#shouldDeserializeFieldWithCurlyBraces
  @Test
  void shouldDeserializeFieldWithCurlyBraces() {
    final var json = """
        {"a":"{dd}","bl":{"b":"c","a":"d"}}
        """;
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(json);
      assertEquals("{dd}", entity.getProperty("a"));
      assertTrue(entity.getProperty("bl") instanceof Map);
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testList
  @Test
  void testList() {
    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson("{\"list\" : [\"string\", 42]}");
      final var list = entity.<List<?>>getProperty("list");

      assertEquals("string", list.get(0));
      assertEquals(42, list.get(1));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testEmbeddedRIDBagDeserialisationWhenFieldTypeIsProvided
  @Test
  void testEmbeddedRIDBagDeserialisationWhenFieldTypeIsProvided() {
    final var eid = session.computeInTx(tx -> session.newEntity().getIdentity());

    session.executeInTx(tx -> {
      final var entity = session.createOrLoadEntityFromJson(
          """
              {
                "@fieldTypes":{"in_EHasGoodStudents": "g"},
                "FirstName":"Student A0",
                "in_EHasGoodStudents":["%s"]
              }""".formatted(eid));

      final var bag = entity.<LinkBag>getProperty("in_EHasGoodStudents");
      assertEquals(1, bag.size());
      final var rid = bag.iterator().next().primaryRid();
      assertEquals(eid, rid);
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedLinkCreation
  @Test
  void testNestedLinkCreation() {
    final var jaimeRid = session.computeInTx(tx -> {
      final var jaimeEntity = session.newEntity("NestedLinkCreation");
      jaimeEntity.setProperty("name", "jaime");
      return jaimeEntity.getIdentity();
    });

    final var cerseiRecord = session.computeInTx(tx -> {
      final var jaimeEntity = session.loadEntity(jaimeRid);
      final var cerseiEntity = session.newEntity("NestedLinkCreation");

      cerseiEntity.updateFromJSON(
          "{\"name\":\"cersei\",\"valonqar\":" + jaimeEntity.toJSON() + "}");
      return cerseiEntity;
    });

    checkJsonSerialization(jaimeRid);
    checkJsonSerialization(cerseiRecord.getIdentity());

    final var jaimeMap = Map.of(
        "name", "jaime",
        "@rid", jaimeRid,
        "@class", "NestedLinkCreation",
        "@version", cerseiRecord.getVersion());
    checkJsonSerialization(jaimeRid, jaimeMap);

    final var cerseiMap = Map.of(
        "name", "cersei",
        "valonqar", jaimeRid,
        "@rid", cerseiRecord.getIdentity(),
        "@class", "NestedLinkCreation",
        "@version", cerseiRecord.getVersion());
    checkJsonSerialization(cerseiRecord.getIdentity(), cerseiMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedLinkCreationFieldTypes
  @Test
  void testNestedLinkCreationFieldTypes() {
    final var jaimeRecord = session.computeInTx(tx -> {
      final var jaimeDoc = session.newEntity("NestedLinkCreationFieldTypes");
      jaimeDoc.setProperty("name", "jaime");
      return jaimeDoc;
    });

    final var cerseiRecord = session.computeInTx(tx -> {
      final var cerseiDoc = session.newEntity("NestedLinkCreationFieldTypes");
      cerseiDoc.updateFromJSON(
          "{\"@type\":\"d\","
              + "\"@fieldTypes\":{\"valonqar\" : \"x\"},\"name\":\"cersei\",\"valonqar\":\""
              + jaimeRecord.getIdentity()
              + "\"}");
      return cerseiDoc;
    });

    checkJsonSerialization(jaimeRecord.getIdentity());
    checkJsonSerialization(cerseiRecord.getIdentity());

    final var jaimeMap = Map.of(
        "name", "jaime",
        "@rid", jaimeRecord.getIdentity(),
        "@class", "NestedLinkCreationFieldTypes",
        "@version", cerseiRecord.getVersion());
    checkJsonSerialization(jaimeRecord.getIdentity(), jaimeMap);

    final var cerseiMap = Map.of(
        "name", "cersei",
        "valonqar", jaimeRecord.getIdentity(),
        "@rid", cerseiRecord.getIdentity(),
        "@class", "NestedLinkCreationFieldTypes",
        "@version", cerseiRecord.getVersion());
    checkJsonSerialization(cerseiRecord.getIdentity(), cerseiMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testNestedLinkCreationEmbeddedProhibited
  @Test
  void testNestedLinkCreationEmbeddedProhibited() {

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
                + "}}"));
        fail("Nested entities should not be allowed to have links inside them.");
      } catch (SerializationException ex) {
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
      }
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testInnerDocCreation
  @Test
  void testInnerDocCreation() {
    final var adamRecord = session.computeInTx(tx -> {
      final var adamDoc = session.newEntity("InnerDocCreation");
      adamDoc.updateFromJSON("{\"name\":\"adam\"}");

      return adamDoc;
    });

    final var eveRecord = session.computeInTx(tx -> {
      final var adamDoc = session.loadEntity(adamRecord.getIdentity());
      final var eveDoc = session.newEntity("InnerDocCreation");
      eveDoc.updateFromJSON(
          "{\"@type\":\"d\",\"name\":\"eve\",\"friends\":[" + adamDoc.toJSON() + "]}");
      return eveDoc;
    });

    checkJsonSerialization(adamRecord.getIdentity());
    checkJsonSerialization(eveRecord.getIdentity());

    final var adamMap = Map.of(
        "name", "adam",
        "@rid", adamRecord.getIdentity(),
        "@class", "InnerDocCreation",
        "@version", eveRecord.getVersion());
    checkJsonSerialization(adamRecord.getIdentity(), adamMap);

    final var eveMap = Map.of(
        "name", "eve",
        "friends", List.of(adamRecord.getIdentity()),
        "@rid", eveRecord.getIdentity(),
        "@class", "InnerDocCreation",
        "@version", eveRecord.getVersion());
    checkJsonSerialization(eveRecord.getIdentity(), eveMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testInnerDocCreationFieldTypes
  @Test
  void testInnerDocCreationFieldTypes() {
    final var p = session.computeInTx(tx -> {
      final var adamDoc = session.newEntity("InnerDocCreationFieldTypes");
      adamDoc.updateFromJSON("{\"name\":\"adam\"}");

      final var eveDoc = session.newEntity("InnerDocCreationFieldTypes");
      eveDoc.updateFromJSON(
          ("{\"@type\":\"d\", \"@fieldTypes\" : { \"friends\":\"z\"}, \"name\":\"eve\",\"friends\":[\""
              + adamDoc.getIdentity()
              + "\"]}"));

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
        "@class", "InnerDocCreationFieldTypes");
    checkJsonSerialization(adamEntity.getIdentity(), expectedAdamMap);

    final var expectedEveMap = Map.of(
        "name", "eve",
        "friends", List.of(adamEntity.getIdentity()),
        "@version", eveEntity.getVersion(),
        "@rid", eveEntity.getIdentity(),
        "@class", "InnerDocCreationFieldTypes");
    checkJsonSerialization(eveEntity.getIdentity(), expectedEveMap);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testInvalidLink
  @Test
  void testInvalidLink() {
    assertThrows(RecordNotFoundException.class, () -> session.executeInTx(tx -> {
      final var nullRefDoc = session.newEntity();
      nullRefDoc.updateFromJSON("{\"name\":\"Luca\", \"ref\":\"#-1:-1\"}");
    }));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testOtherJson
  @Test
  void testOtherJson() {
    final var record = session.computeInTx(tx -> {
      final var entity = session.newEntity();
      entity.updateFromJSON(
          ("{\"Salary\":1500.0,\"Type\":\"Person\",\"Address\":[{\"Zip\":\"JX2"
              + " MSX\",\"Type\":\"Home\",\"Street1\":\"13 Marge"
              + " Street\",\"Country\":\"Holland\",\"Id\":\"Address-28813211\",\"City\":\"Amsterdam\",\"From\":\"1996-02-01\",\"To\":\"1998-01-01\"},{\"Zip\":\"90210\",\"Type\":\"Work\",\"Street1\":\"100"
              + " Hollywood"
              + " Drive\",\"Country\":\"USA\",\"Id\":\"Address-11595040\",\"City\":\"Los"
              + " Angeles\",\"From\":\"2009-09-01\"}],\"Id\":\"Person-7464251\",\"Name\":\"Stan\"}"));
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
                "To", "1998-01-01"),
            Map.of(
                "Zip", "90210",
                "Type", "Work",
                "Street1", "100 Hollywood Drive",
                "Country", "USA",
                "Id", "Address-11595040",
                "City", "Los Angeles",
                "From", "2009-09-01")),
        "Id", "Person-7464251",
        "Name", "Stan",
        "@rid", record.getIdentity(),
        "@class", "O",
        "@version", record.getVersion());
    checkJsonSerialization(record.getIdentity(), map);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.JSONTest#testScientificNotation
  @Test
  void testScientificNotation() {
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
        "@version", record.getVersion());
    checkJsonSerialization(record.getIdentity(), expectedMap);
  }

  private void checkJsonSerialization(RID rid) {
    session.begin();
    try {
      final var original = session.loadEntity(rid);
      final var json = original.toJSON(FORMAT_WITHOUT_RID);

      final var newEntity = session.createOrLoadEntityFromJson(json);

      assertEquals(0, newEntity.getVersion());

      final var originalMap = original.toMap();
      final var loadedMap = newEntity.toMap();

      originalMap.remove(EntityHelper.ATTRIBUTE_RID);
      loadedMap.remove(EntityHelper.ATTRIBUTE_RID);

      originalMap.remove(EntityHelper.ATTRIBUTE_VERSION);
      loadedMap.remove(EntityHelper.ATTRIBUTE_VERSION);

      assertEquals(loadedMap, originalMap);
    } finally {
      session.rollback();
    }
  }

  private void checkJsonSerialization(RID rid, Map<String, ?> expectedMap) {
    session.executeInTx(tx -> {
      final var original = session.loadEntity(rid);
      final var originalMap = original.toMap();
      assertEquals(expectedMap, originalMap);
    });
  }
}
