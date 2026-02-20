/*
 * JUnit 4 version of SQLSelectTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLSelectTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLSelectTest extends BaseDBTest {

  private static SQLSelectTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
  }

  /**
   * Original: testQueryCount (line 105) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test01_QueryCount() {
    session.getMetadata().reload();
    final var vertexesCount = session.countClass("V");
    var result = executeQuery("select count(*) from V");
    Assert.assertEquals(result.getFirst().<Object>getProperty("count(*)"), vertexesCount);
  }

  /**
   * Original: testRecordNumbers (line 936) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test02_RecordNumbers() {
    session.begin();
    var tot = session.countClass("V");

    var count = 0;
    var entityIterator = session.browseClass("V");
    while (entityIterator.hasNext()) {
      entityIterator.next();
      count++;
    }

    Assert.assertEquals(count, tot);

    Assert.assertTrue(executeQuery("select from V", session).size() >= tot);
    session.commit();
  }

  /**
   * Original: testBetweenWithParameters (line 1060) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test03_BetweenWithParameters() {
    session.begin();

    final var result =
        executeQuery(
            "select * from company where id between ? and ? and salary is not null",
            session,
            4,
            7);

    System.out.println("testBetweenWithParameters:");
    for (var d : result) {
      System.out.println(d);
    }

    Assert.assertEquals("Found: " + result, 4, result.size());

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  /**
   * Original: testInWithParameters (line 1085) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test04_InWithParameters() {
    session.begin();

    final var result =
        executeQuery(
            "select * from Company where id in [?, ?, ?, ?] and salary is not null",
            session,
            4,
            5,
            6,
            7);

    Assert.assertEquals(result.size(), 4);

    final List<Integer> resultsList = new ArrayList<>(Arrays.asList(4, 5, 6, 7));
    for (var record : result) {
      Assert.assertTrue(resultsList.remove(record.<Integer>getProperty("id")));
    }
    session.commit();
  }

  /**
   * Original: testEqualsNamedParameter (line 1107) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test05_EqualsNamedParameter() {

    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);
    final var result =
        executeQuery("select * from Company where id = :id and salary is not null", params);

    Assert.assertEquals(result.size(), 1);
  }

  /**
   * Original: testQueryAsClass (line 1118) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test06_QueryAsClass() {
    session.begin();

    var result =
        executeQuery("select from Account where addresses.@class in [ 'Address' ]");
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      Assert.assertNotNull(d.getProperty("addresses"));
      Identifiable identifiable = ((Collection<Identifiable>) d.getProperty("addresses"))
          .iterator()
          .next();
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          Objects.requireNonNull(
                  ((EntityImpl)
                      transaction.load(identifiable))
                      .getSchemaClass())
              .getName(),
          "Address");
    }
    session.commit();
  }

  /**
   * Original: testQueryNotOperator (line 1142) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test07_QueryNotOperator() {
    final var tx = session.begin();

    var result =
        executeQuery("select from Account where not ( addresses.@class in [ 'Address' ] )");
    Assert.assertFalse(result.isEmpty());
    for (var d : result) {
      final var addresses = d.getLinkList("addresses");
      if (addresses != null && !addresses.isEmpty()) {

        for (var a : addresses) {
          Assert.assertNotEquals(tx.loadEntity(a).getSchemaClassName(), "Address");
        }
      }
    }
    session.commit();
  }

  /**
   * Original: testParams (line 1160) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test08_Params() {
    var test = session.getMetadata().getSchema().getClass("test");
    if (test == null) {
      test = session.getMetadata().getSchema().createClass("test");
      test.createProperty("f1", PropertyType.STRING);
      test.createProperty("f2", PropertyType.STRING);
    }
    session.begin();
    var document = ((EntityImpl) session.newEntity(test));
    document.setProperty("f1", "a");

    session.commit();

    session.begin();
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("p1", "a");
    executeQuery("select from test where (f1 = :p1)", parameters);
    executeQuery("select from test where f1 = :p1 and f2 = :p1", parameters);
    session.commit();
  }

  /**
   * Original: testSelectFromListParameter (line 1320) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test09_SelectFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty("id", PropertyType.STRING);
    placeClass.createProperty("descr", PropertyType.STRING);
    placeClass.createIndex("place_id_index", INDEX_TYPE.UNIQUE, "id");

    session.begin();
    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    session.commit();

    session.begin();
    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.commit();

    session.begin();
    Map<String, Object> params = new HashMap<>();
    List<String> inputValues = new ArrayList<>();
    inputValues.add("lago_di_como");
    inputValues.add("lecco");
    params.put("place", inputValues);

    var result = executeQuery("select from place where id in :place", session,
        params);
    Assert.assertEquals(result.size(), 1);
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  /**
   * Original: testSelectRidFromListParameter (line 1356) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test10_SelectRidFromListParameter() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    placeClass.createProperty("id", PropertyType.STRING);
    placeClass.createProperty("descr", PropertyType.STRING);
    placeClass.createIndex("place_id_index", INDEX_TYPE.UNIQUE, "id");

    List<RID> inputValues = new ArrayList<>();

    session.begin();
    var odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "adda");
    odoc.setProperty("descr", "Adda");

    inputValues.add(odoc.getIdentity());

    odoc = ((EntityImpl) session.newEntity("Place"));
    odoc.setProperty("id", "lago_di_como");
    odoc.setProperty("descr", "Lago di Como");

    session.commit();

    session.begin();
    inputValues.add(odoc.getIdentity());

    Map<String, Object> params = new HashMap<>();
    params.put("place", inputValues);

    var result =
        executeQuery("select from place where @rid in :place", session, params);
    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.getMetadata().getSchema().dropClass("Place");
  }

  /**
   * Original: testSelectRidInList (line 1392) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test11_SelectRidInList() {
    var placeClass = session.getMetadata().getSchema().createClass("Place", 1);
    session.getMetadata().getSchema().createClass("FamousPlace", 1, placeClass);

    session.begin();
    var firstPlace = ((EntityImpl) session.newEntity("Place"));
    session.commit();

    session.begin();
    var secondPlace = ((EntityImpl) session.newEntity("Place"));
    session.commit();

    session.begin();
    var famousPlace = ((EntityImpl) session.newEntity("FamousPlace"));
    session.commit();

    RID secondPlaceId = secondPlace.getIdentity();
    RID famousPlaceId = famousPlace.getIdentity();
    // if one of these two asserts fails, the test will be meaningless.
    Assert.assertTrue(secondPlaceId.getCollectionId() < famousPlaceId.getCollectionId());
    Assert.assertTrue(
        secondPlaceId.getCollectionPosition() > famousPlaceId.getCollectionPosition());

    session.begin();
    var result =
        executeQuery(
            "select from Place where @rid in [" + secondPlaceId + "," + famousPlaceId + "]",
            session);
    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.getMetadata().getSchema().dropClass("FamousPlace");
    session.getMetadata().getSchema().dropClass("Place");
  }

  /**
   * Original: testMapKeys (line 1428) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test12_MapKeys() {
    Map<String, Object> params = new HashMap<>();
    params.put("id", 4);

    final var result =
        executeQuery(
            "select * from company where id = :id and salary is not null", session, params);

    Assert.assertEquals(result.size(), 1);
  }

  /**
   * Original: testQueryParameterNotPersistent (line 1457) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test13_QueryParameterNotPersistent() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("test", "test");
    executeQuery("select from OUser where @rid = ?", doc);
    Assert.assertTrue(doc.isDirty());
    session.commit();
  }

  /**
   * Original: testQueryLetExecutedOnce (line 1466) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test14_QueryLetExecutedOnce() {
    session.begin();
    final var result =
        executeQuery(
            "select name, $counter as counter from OUser let $counter = eval(\"$counter +"
                + " 1\")");

    Assert.assertFalse(result.isEmpty());
    for (var r : result) {
      Assert.assertEquals(r.<Object>getProperty("counter"), 1);
    }
    session.commit();
  }

  /**
   * Original: testMultipleCollectionsWithPagination (line 1481) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test15_MultipleCollectionsWithPagination() {
    session.getMetadata().getSchema().createClass("PersonMultipleCollections");
    try {
      Set<String> names =
          new HashSet<>(Arrays.asList("Luca", "Jill", "Sara", "Tania", "Gianluca", "Marco"));
      for (var n : names) {
        session.begin();
        EntityImpl entity = ((EntityImpl) session.newEntity("PersonMultipleCollections"));
        entity.setProperty("First", n);

        session.commit();
      }

      session.begin();
      var query = "select from PersonMultipleCollections where @rid > ? limit 2";
      var resultset = executeQuery(query,
          new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));

      while (!resultset.isEmpty()) {
        final var last = resultset.getLast().getIdentity();

        for (var personDoc : resultset) {
          Assert.assertTrue(names.contains(personDoc.<String>getProperty("First")));
          Assert.assertTrue(names.remove(personDoc.<String>getProperty("First")));
        }

        resultset = executeQuery(query, last);
      }

      Assert.assertTrue(names.isEmpty());
      session.commit();

    } finally {
      session.getMetadata().getSchema().dropClass("PersonMultipleCollections");
    }
  }

  /**
   * Original: testOutFilterInclude (line 1519) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test16_OutFilterInclude() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("TestOutFilterInclude", schema.getClass("V"));
    session.execute("create class linkedToOutFilterInclude extends E").close();

    session.begin();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"one\" }").close();
    session.execute("insert into TestOutFilterInclude content { \"name\": \"two\" }").close();
    session
        .execute(
            "create edge linkedToOutFilterInclude from (select from TestOutFilterInclude where name"
                + " = 'one') to (select from TestOutFilterInclude where name = 'two')")
        .close();
    session.commit();

    final var result =
        executeQuery(
            "select"
                + " expand(out('linkedToOutFilterInclude')[@class='TestOutFilterInclude'].include('@rid'))"
                + " from TestOutFilterInclude where name = 'one'");

    Assert.assertEquals(result.size(), 1);

    for (var r : result) {
      Assert.assertNull(r.getProperty("name"));
    }
  }

  /**
   * Original: testExpandSkip (line 1565) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test17_ExpandSkip() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    final var cls = schema.createClass("TestExpandSkip", v);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("TestExpandSkip.name", INDEX_TYPE.UNIQUE, "name");

    session.begin();
    session.execute("CREATE VERTEX TestExpandSkip set name = '1'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '2'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '3'").close();
    session.execute("CREATE VERTEX TestExpandSkip set name = '4'").close();

    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestExpandSkip WHERE name = '1') to (SELECT FROM"
                + " TestExpandSkip WHERE name <> '1')")
        .close();
    session.commit();

    var result = session.query(
        "select expand(out()) from TestExpandSkip where name = '1'");

    Assert.assertEquals(result.stream().count(), 3);

    Map<Object, Object> params = new HashMap<>();
    params.put("values", Arrays.asList("2", "3", "antani"));
    result =
        session.query(
            "select expand(out()[name in :values]) from TestExpandSkip where name = '1'", params);
    Assert.assertEquals(result.stream().count(), 2);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1");

    Assert.assertEquals(result.stream().count(), 2);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 2");
    Assert.assertEquals(result.stream().count(), 1);

    result = session.query("select expand(out()) from TestExpandSkip where name = '1' skip 3");
    Assert.assertEquals(result.stream().count(), 0);

    result =
        session.query("select expand(out()) from TestExpandSkip where name = '1' skip 1 limit 1");
    Assert.assertEquals(result.stream().count(), 1);
  }

  /**
   * Original: testPolymorphicEdges (line 1613) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test18_PolymorphicEdges() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    schema.createClass("TestPolymorphicEdges_V", v);
    final var e1 = schema.createClass("TestPolymorphicEdges_E1", e);
    schema.createClass("TestPolymorphicEdges_E2", e1);

    session.begin();
    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '1'").close();
    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '2'").close();
    session.execute("CREATE VERTEX TestPolymorphicEdges_V set name = '3'").close();

    session
        .execute(
            "CREATE EDGE TestPolymorphicEdges_E1 FROM (SELECT FROM TestPolymorphicEdges_V WHERE"
                + " name = '1') to (SELECT FROM TestPolymorphicEdges_V WHERE name = '2')")
        .close();
    session
        .execute(
            "CREATE EDGE TestPolymorphicEdges_E2 FROM (SELECT FROM TestPolymorphicEdges_V WHERE"
                + " name = '1') to (SELECT FROM TestPolymorphicEdges_V WHERE name = '3')")
        .close();
    session.commit();

    var result =
        session.query(
            "select expand(out('TestPolymorphicEdges_E1')) from TestPolymorphicEdges_V where name ="
                + " '1'");
    Assert.assertEquals(result.stream().count(), 2);

    result =
        session.query(
            "select expand(out('TestPolymorphicEdges_E2')) from TestPolymorphicEdges_V where name ="
                + " '1' ");
    Assert.assertEquals(result.stream().count(), 1);
  }

  /**
   * Original: testSizeOfLink (line 1652) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test19_SizeOfLink() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("TestSizeOfLink", v);

    session.begin();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '1'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '2'").close();
    session.execute("CREATE VERTEX TestSizeOfLink set name = '3'").close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM TestSizeOfLink WHERE name = '1') to (SELECT FROM"
                + " TestSizeOfLink WHERE name <> '1')")
        .close();
    session.commit();

    var result =
        session.query(
            " select from (select from TestSizeOfLink where name = '1') where out()[name=2].size()"
                + " > 0");
    Assert.assertEquals(result.stream().count(), 1);
  }

  /**
   * Original: testEmbeddedMapAndDotNotation (line 1676) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test20_EmbeddedMapAndDotNotation() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("EmbeddedMapAndDotNotation", v);

    session.begin();
    session.execute("CREATE VERTEX EmbeddedMapAndDotNotation set name = 'foo'").close();
    session
        .execute(
            "CREATE VERTEX EmbeddedMapAndDotNotation set data = {\"bar\": \"baz\", \"quux\": 1},"
                + " name = 'bar'")
        .close();
    session
        .execute(
            "CREATE EDGE E FROM (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'foo') to"
                + " (SELECT FROM EmbeddedMapAndDotNotation WHERE name = 'bar')")
        .close();
    session.commit();

    var result =
        executeQuery(
            " select out().data as result from (select from EmbeddedMapAndDotNotation where"
                + " name = 'foo')");
    Assert.assertEquals(result.size(), 1);
    var doc = result.getFirst();
    Assert.assertNotNull(doc);
    @SuppressWarnings("rawtypes")
    List list = doc.getProperty("result");
    Assert.assertEquals(list.size(), 1);
    var first = list.getFirst();
    Assert.assertTrue(first instanceof Map);
    //noinspection rawtypes
    Assert.assertEquals(((Map) first).get("bar"), "baz");
  }

  /**
   * Original: testLetWithQuotedValue (line 1712) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test21_LetWithQuotedValue() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass("LetWithQuotedValue", v);
    session.begin();
    session.execute("CREATE VERTEX LetWithQuotedValue set name = \"\\\"foo\\\"\"").close();
    session.commit();

    var result =
        session.query(
            " select expand($a) let $a = (select from LetWithQuotedValue where name ="
                + " \"\\\"foo\\\"\")");
    Assert.assertEquals(result.stream().count(), 1);
  }

  /**
   * Original: testNamedParams (line 1728) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectTest.java
   */
  @Test
  public void test22_NamedParams() {
    // issue #7236

    session.execute("create class testNamedParams extends V").close();
    session.execute("create class testNamedParams_permission extends V").close();
    session.execute("create class testNamedParams_HasPermission extends E").close();

    session.begin();
    session.execute("insert into testNamedParams_permission set type = ['USER']").close();
    session.execute("insert into testNamedParams set login = 20").close();
    session
        .execute(
            "CREATE EDGE testNamedParams_HasPermission from (select from testNamedParams) to"
                + " (select from testNamedParams_permission)")
        .close();
    session.commit();

    Map<String, Object> params = new HashMap<>();
    params.put("key", 10);
    params.put("permissions", new String[]{"USER"});
    params.put("limit", 1);
    var results =
        executeQuery(
            "SELECT *, out('testNamedParams_HasPermission').type as permissions FROM"
                + " testNamedParams WHERE login >= :key AND"
                + " out('testNamedParams_HasPermission').type IN :permissions ORDER BY login"
                + " ASC LIMIT :limit",
            params);
    Assert.assertEquals(results.size(), 1);
  }

}
