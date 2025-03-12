package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

/**
 * @since 4/11/14
 */
public class CompositeIndexWithNullTest extends BaseDBTest {

  @Parameters(value = "remote")
  public CompositeIndexWithNullTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void testPointQuery() {
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
      var document = ((EntityImpl) session.newEntity("compositeIndexNullPointQueryClass"));
      document.field("prop1", i / 10);
      document.field("prop2", i / 5);

      if (i % 2 == 0) {
        document.field("prop3", i);
      }

      session.begin();

      session.commit();
    }

    var query = "select from compositeIndexNullPointQueryClass where prop1 = 1 and prop2 = 2";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertEquals(result.<Object>getProperty("prop2"), 2);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryIndex"));

    query =
        "select from compositeIndexNullPointQueryClass where prop1 = 1 and prop2 = 2 and prop3 is"
            + " null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryIndex"));
  }

  public void testPointQueryInTx() {
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
      document.field("prop1", i / 10);
      document.field("prop2", i / 5);

      if (i % 2 == 0) {
        document.field("prop3", i);
      }

    }

    session.commit();

    var query =
        "select from compositeIndexNullPointQueryInTxClass where prop1 = 1 and prop2 = 2";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertEquals(result.<Object>getProperty("prop2"), 2);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryInTxIndex"));

    query =
        "select from compositeIndexNullPointQueryInTxClass where prop1 = 1 and prop2 = 2 and prop3"
            + " is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryInTxIndex"));
  }

  public void testPointQueryInMiddleTx() {
    if (remoteDB) {
      return;
    }

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
      document.field("prop1", i / 10);
      document.field("prop2", i / 5);

      if (i % 2 == 0) {
        document.field("prop3", i);
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

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryInMiddleTxIndex"));

    query =
        "select from compositeIndexNullPointQueryInMiddleTxClass where prop1 = 1 and prop2 = 2 and"
            + " prop3 is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 2);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("prop3"));
    }

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryInMiddleTxIndex"));

    session.commit();
  }

  public void testRangeQuery() {
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
      var document = ((EntityImpl) session.newEntity("compositeIndexNullRangeQueryClass"));
      document.field("prop1", i / 10);
      document.field("prop2", i / 5);

      if (i % 2 == 0) {
        document.field("prop3", i);
      }

      session.begin();

      session.commit();
    }

    var query = "select from compositeIndexNullRangeQueryClass where prop1 = 1 and prop2 > 2";
    var resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var k = 0; k < 5; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertTrue(result.<Integer>getProperty("prop2") > 2);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullRangeQueryIndex"));

    query = "select from compositeIndexNullRangeQueryClass where prop1 > 0";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertTrue(result.<Integer>getProperty("prop1") > 0);
    }
  }

  public void testRangeQueryInMiddleTx() {
    if (remoteDB) {
      return;
    }

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
      document.field("prop1", i / 10);
      document.field("prop2", i / 5);

      if (i % 2 == 0) {
        document.field("prop3", i);
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

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullRangeQueryInMiddleTxIndex"));

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
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullPointQueryNullInTheMiddleClass"));
      document.field("prop1", i / 10);

      if (i % 2 == 0) {
        document.field("prop2", i);
      }

      document.field("prop3", i);

      session.begin();

      session.commit();
    }

    var query = "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleIndex"));

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1 and prop2 is"
            + " null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var result : resultSet) {
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertNull(result.getProperty("prop2"));
    }

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleIndex"));

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleClass where prop1 = 1 and prop2 is"
            + " null and prop3 = 13";
    resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 1);

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleIndex"));
  }

  public void testPointQueryNullInTheMiddleInMiddleTx() {
    if (remoteDB) {
      return;
    }

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
      document.field("prop1", i / 10);

      if (i % 2 == 0) {
        document.field("prop2", i);
      }

      document.field("prop3", i);

    }

    var query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleInMiddleTxIndex"));

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1 and"
            + " prop2 is null";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 5);
    for (var result : resultSet) {
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
      Assert.assertNull(result.getProperty("prop2"));
    }

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleInMiddleTxIndex"));

    query =
        "select from compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass where prop1 = 1 and"
            + " prop2 is null and prop3 = 13";
    resultSet = session.query(query).toList();

    Assert.assertEquals(resultSet.size(), 1);

    explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullPointQueryNullInTheMiddleInMiddleTxIndex"));

    session.commit();
  }

  public void testRangeQueryNullInTheMiddle() {
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
      var document = ((EntityImpl) session.newEntity(
          "compositeIndexNullRangeQueryNullInTheMiddleClass"));
      document.field("prop1", i / 10);

      if (i % 2 == 0) {
        document.field("prop2", i);
      }

      document.field("prop3", i);

      session.begin();

      session.commit();
    }

    final var query =
        "select from compositeIndexNullRangeQueryNullInTheMiddleClass where prop1 > 0";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullRangeQueryNullInTheMiddleIndex"));
  }

  public void testRangeQueryNullInTheMiddleInMiddleTx() {
    if (remoteDB) {
      return;
    }

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
      var document =
          ((EntityImpl) session.newEntity(
              "compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass"));
      document.field("prop1", i / 10);

      if (i % 2 == 0) {
        document.field("prop2", i);
      }

      document.field("prop3", i);

      session.begin();

      session.commit();
    }

    final var query =
        "select from compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass where prop1 > 0";
    var resultSet = session.query(query).toList();
    Assert.assertEquals(resultSet.size(), 10);
    for (var k = 0; k < 10; k++) {
      var result = resultSet.get(k);
      Assert.assertEquals(result.<Object>getProperty("prop1"), 1);
    }

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxIndex"));
  }
}
