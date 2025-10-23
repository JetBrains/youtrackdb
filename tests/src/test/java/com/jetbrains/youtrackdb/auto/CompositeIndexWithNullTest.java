package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.testng.Assert;

/**
 * @since 4/11/14
 */
public class CompositeIndexWithNullTest extends BaseDBTest {

  public void testPointQuery() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("compositeIndexNullPointQueryClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullPointQueryIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(g -> g.createSchemaClass("compositeIndexNullPointQueryInTxClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullPointQueryInTxIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("compositeIndexNullPointQueryInMiddleTxClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullPointQueryInMiddleTxIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(g -> g.createSchemaClass("compositeIndexNullRangeQueryClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullRangeQueryIndex", YTDBSchemaIndex.IndexType.NOT_UNIQUE,
            "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(g -> g.createSchemaClass("compositeIndexNullRangeQueryInMiddleTxClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullRangeQueryInMiddleTxIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("compositeIndexNullPointQueryNullInTheMiddleClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullPointQueryNullInTheMiddleIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("compositeIndexNullPointQueryNullInTheMiddleInMiddleTxClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullPointQueryNullInTheMiddleInMiddleTxIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("compositeIndexNullRangeQueryNullInTheMiddleClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullRangeQueryNullInTheMiddleIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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

    graph.autoExecuteInTx(
        g -> g.createSchemaClass("compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxClass",
            __.createSchemaProperty("prop1", PropertyType.INTEGER),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.INTEGER)
        ).createClassIndex("compositeIndexNullRangeQueryNullInTheMiddleInMiddleTxIndex",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE, "prop1", "prop2", "prop3")
    );

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
