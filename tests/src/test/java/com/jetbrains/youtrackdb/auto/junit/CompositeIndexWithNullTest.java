/*
 * JUnit 4 version of CompositeIndexWithNullTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Map;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 4/11/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CompositeIndexWithNullTest extends BaseDBTest {

  private static CompositeIndexWithNullTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new CompositeIndexWithNullTest();
    instance.beforeClass();
  }

  /**
   * Original: testPointQuery (line 16) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test01_PointQuery() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = (SchemaClassInternal) schema.createClass(
        "compositeIndexNullPointQueryClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullPointQueryIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"prop1", "prop2", "prop3"});

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("compositeIndexNullPointQueryClass"));
      document.setProperty("prop1", i / 10);
      document.setProperty("prop2", i / 5);

      if (i % 2 == 0) {
        document.setProperty("prop3", i);
      }

      session.commit();
    }

    session.begin();
    var query = "select from compositeIndexNullPointQueryClass where prop1 = 1 and prop2 = 2";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertEquals(result.<Object>getProperty("prop2"), 2);
    }
    session.commit();

    session.begin();
    query =
        "select from compositeIndexNullPointQueryClass where prop1 = 1 and prop2 = 2 and prop3 is"
            + " null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }
    session.commit();
  }

  /**
   * Original: testPointQueryInTx (line 69) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test02_PointQueryInTx() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullPointQueryInTxClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);
    clazz.createIndex(
        "compositeIndexNullPointQueryInTxIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"prop1", "prop2", "prop3"});

    session.begin();

    for (var i = 0; i < 20; i++) {
      var document = ((EntityImpl) session.newEntity("compositeIndexNullPointQueryInTxClass"));
      document.setProperty("prop1", i / 10);
      document.setProperty("prop2", i / 5);

      if (i % 2 == 0) {
        document.setProperty("prop3", i);
      }

    }

    session.commit();

    session.begin();
    var query =
        "select from compositeIndexNullPointQueryInTxClass where prop1 = 1 and prop2 = 2";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertEquals(result.<Object>getProperty("prop2"), 2);
    }
    session.commit();

    session.begin();
    query =
        "select from compositeIndexNullPointQueryInTxClass where prop1 = 1 and prop2 = 2 and prop3"
            + " is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }
    session.commit();
  }

  /**
   * Original: testPointQueryInMiddleTx (line 123) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test03_PointQueryInMiddleTx() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullPointQueryInMiddleTxClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullPointQueryInMiddleTxIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"prop1", "prop2", "prop3"});

    session.begin();

    for (var i = 0; i < 20; i++) {
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullPointQueryInMiddleTxClass"));
      document.setProperty("prop1", i / 10);
      document.setProperty("prop2", i / 5);

      if (i % 2 == 0) {
        document.setProperty("prop3", i);
      }

    }

    var query =
        "select from compositeIndexNullPointQueryInMiddleTxClass where prop1 = 1 and prop2 = 2";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 5);

    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertEquals(result.<Object>getProperty("prop2"), 2);
    }

    query =
        "select from compositeIndexNullPointQueryInMiddleTxClass where prop1 = 1 and prop2 = 2 and"
            + " prop3 is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }

    session.commit();
  }

  /**
   * Original: testRangeQuery (line 176) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test04_RangeQuery() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullRangeQueryClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullRangeQueryIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata,
        null, new String[]{"prop1", "prop2", "prop3"});

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("compositeIndexNullRangeQueryClass"));
      document.setProperty("prop1", i / 10);
      document.setProperty("prop2", i / 5);

      if (i % 2 == 0) {
        document.setProperty("prop3", i);
      }

      session.commit();
    }

    session.begin();
    var query = "select from compositeIndexNullRangeQueryClass where prop1 = 1 and prop2 > 2";
    var resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertTrue(result.<Integer>getProperty("prop2") > 2);
    }

    query = "select from compositeIndexNullRangeQueryClass where prop1 > 0";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertTrue(result.<Integer>getProperty("prop1") > 0);
    }
    session.commit();
  }

  /**
   * Original: testRangeQueryInMiddleTx (line 227) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test05_RangeQueryInMiddleTx() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullRangeQueryInMiddleTxClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullRangeQueryInMiddleTxIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata,
        null, new String[]{"prop1", "prop2", "prop3"});

    session.begin();
    for (var i = 0; i < 20; i++) {
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullRangeQueryInMiddleTxClass"));
      document.setProperty("prop1", i / 10);
      document.setProperty("prop2", i / 5);

      if (i % 2 == 0) {
        document.setProperty("prop3", i);
      }

    }

    var query =
        "select from compositeIndexNullRangeQueryInMiddleTxClass where prop1 = 1 and prop2 > 2";
    var resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertTrue(result.<Integer>getProperty("prop2") > 2);
    }

    query = "select from compositeIndexNullRangeQueryInMiddleTxClass where prop1 > 0";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertTrue(result.<Integer>getProperty("prop1") > 0);
    }

    session.commit();
  }

  /**
   * Original: testPointQueryNullInTheMiddle (line 279) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test06_PointQueryNullInTheMiddle() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullPointQueryNullInTheMiddleClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullPointQueryNullInTheMiddleIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata,
        null, new String[]{"prop1", "prop2", "prop3"});

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullPointQueryNullInTheMiddleClass"));
      document.setProperty("prop1", i / 10);

      if (i % 2 == 0) {
        document.setProperty("prop2", i);
      }

      document.setProperty("prop3", i);

      session.commit();
    }

    session.begin();
    var query = "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }
    session.commit();

    session.begin();
    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1 and prop2 is"
            + " null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var result : resultSet) {
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertNull(result.getProperty("prop2"));
    }
    session.commit();

    session.begin();
    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1 and prop2 is"
            + " null and prop3 = 13";
    resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 1);
    session.commit();
  }

  /**
   * Original: testPointQueryNullInTheMiddleInMiddleTx (line 342) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test07_PointQueryNullInTheMiddleInMiddleTx() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(
        "compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);
    clazz.createIndex(
        "compositeIndexNullPointQueryNullInTheMiddleInMiddleTxIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata,
        null, new String[]{"prop1", "prop2", "prop3"});

    session.begin();

    for (var i = 0; i < 20; i++) {
      var document =
          ((EntityImpl) session.newEntity(
              "compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass"));
      document.setProperty("prop1", i / 10);

      if (i % 2 == 0) {
        document.setProperty("prop2", i);
      }

      document.setProperty("prop3", i);

    }

    var query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1 and"
            + " prop2 is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var result : resultSet) {
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertNull(result.getProperty("prop2"));
    }

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1 and"
            + " prop2 is null and prop3 = 13";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 1);

    session.commit();
  }

  /**
   * Original: testRangeQueryNullInTheMiddle (line 404) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test08_RangeQueryNullInTheMiddle() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("compositeIndexNullRangeQueryNullInTheMiddleClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "compositeIndexNullRangeQueryNullInTheMiddleIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"prop1", "prop2", "prop3"});

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullRangeQueryNullInTheMiddleClass"));
      document.setProperty("prop1", i / 10);

      if (i % 2 == 0) {
        document.setProperty("prop2", i);
      }

      document.setProperty("prop3", i);

      session.commit();
    }

    session.begin();
    final var query =
        "select from compositeIndexNullRangeQueryNullInTheMiddleClass where prop1 > 0";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }
    session.commit();
  }

  /**
   * Original: testRangeQueryNullInTheMiddleInMiddleTx (line 446) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CompositeIndexWithNullTest.java
   */
  @Test
  public void test09_RangeQueryNullInTheMiddleInMiddleTx() {

    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass(
        "compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass");
    clazz.createProperty("prop1", PropertyType.INTEGER);
    clazz.createProperty("prop2", PropertyType.INTEGER);
    clazz.createProperty("prop3", PropertyType.INTEGER);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);
    clazz.createIndex(
        "compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"prop1", "prop2", "prop3"});

    for (var i = 0; i < 20; i++) {
      session.begin();
      var document =
          ((EntityImpl) session.newEntity(
              "compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass"));
      document.setProperty("prop1", i / 10);

      if (i % 2 == 0) {
        document.setProperty("prop2", i);
      }

      document.setProperty("prop3", i);

      session.commit();
    }

    session.begin();
    final var query =
        "select from compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass where prop1 > 0";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }
    session.commit();
  }

}
