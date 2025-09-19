package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Map;
import org.testng.Assert;

/**
 * @since 4/11/14
 */
public class CompositeIndexWithNullTest extends BaseDBTest {

  public void testPointQuery() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.createClass(
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

  public void testPointQueryInTx() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testPointQueryInMiddleTx() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testRangeQuery() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testRangeQueryInMiddleTx() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testPointQueryNullInTheMiddle() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testPointQueryNullInTheMiddleInMiddleTx() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testRangeQueryNullInTheMiddle() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
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

  public void testRangeQueryNullInTheMiddleInMiddleTx() {

    final Schema schema = session.getMetadata().getSlowMutableSchema();
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
