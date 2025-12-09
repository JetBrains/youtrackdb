package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @since 9/12/14
 */
@Test
public class BetweenConversionTest extends BaseDBTest {
  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("BetweenConversionTest");
    clazz.createProperty("a", PropertyType.INTEGER);
    clazz.createProperty("ai", PropertyType.INTEGER);

    clazz.createIndex("BetweenConversionTestIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "ai");

    for (var i = 0; i < 10; i++) {

      session.begin();
      var document = ((EntityImpl) session.newEntity("BetweenConversionTest"));
      document.setProperty("a", i);
      document.setProperty("ai", i);

      if (i < 5) {
        document.setProperty("vl", "v1");
      } else {
        document.setProperty("vl", "v2");
      }

      var ed = ((EntityImpl) session.newEntity());
      ed.setProperty("a", i);

      document.setProperty("d", ed);

      session.commit();
    }
  }

  public void testBetweenRightLeftIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a <= 3").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedReverseOrder() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a <= 3 and a >= 1").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a > 1 and a <= 3").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightIncludedReverse() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a <= 3 and a > 1").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenLeftIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a < 3").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenLeftIncludedReverseOrder() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where  a < 3 and a >= 1").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetween() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a > 1 and a < 3").toList();

      Assert.assertEquals(result.size(), 1);
      List<Integer> values = new ArrayList<Integer>(List.of(2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai >= 1 and ai <= 3").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai <= 3 and ai >= 1").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai > 1 and ai <= 3").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai <= 3 and ai > 1").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenLeftIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai >= 1 and ai < 3").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenLeftIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where  ai < 3 and ai >= 1").toList();

      Assert.assertEquals(result.size(), 2);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai > 1 and ai < 3").toList();

      Assert.assertEquals(result.size(), 1);
      List<Integer> values = new ArrayList<Integer>(List.of(2));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedDeepQuery() {
    session.executeInTx(transaction -> {
      final var result = session.query(
          "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and ((a"
              + " >= 1 and a <= 7) and vl = 'v1'))) and vl <> 'v4')"
      ).toList();

      Assert.assertEquals(result.size(), 4);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedDeepQueryIndex() {
    session.executeInTx(transaction -> {
      final var result = session.query(
          "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and"
              + " ((ai >= 1 and ai <= 7) and vl = 'v1'))) and vl <> 'v4')"
      ).toList();

      Assert.assertEquals(result.size(), 4);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedDifferentFields() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and ai <= 3").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenNotRangeQueryRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a = 3").toList();

      Assert.assertEquals(result.size(), 1);
      List<Integer> values = new ArrayList<Integer>(List.of(3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenNotRangeQueryLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a = 1 and a <= 3").toList();

      Assert.assertEquals(result.size(), 1);
      List<Integer> values = new ArrayList<Integer>(List.of(1));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedBothFieldsLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= ai and a <= 3").toList();

      Assert.assertEquals(result.size(), 4);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(0, 1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedBothFieldsRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a <= ai").toList();

      Assert.assertEquals(result.size(), 9);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedFieldChainLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where d.a >= 1 and a <= 3").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }

  public void testBetweenRightLeftIncludedFieldChainRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and d.a <= 3").toList();

      Assert.assertEquals(result.size(), 3);
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        Assert.assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      Assert.assertTrue(values.isEmpty());
    });
  }
}
