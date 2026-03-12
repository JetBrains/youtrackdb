// Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for BETWEEN query conversion and optimization.
 */
public class BetweenConversionTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncluded
  @Test
  void testBetweenRightLeftIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a <= 3").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedReverseOrder
  @Test
  void testBetweenRightLeftIncludedReverseOrder() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a <= 3 and a >= 1").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightIncluded
  @Test
  void testBetweenRightIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a > 1 and a <= 3").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightIncludedReverse
  @Test
  void testBetweenRightIncludedReverse() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a <= 3 and a > 1").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenLeftIncluded
  @Test
  void testBetweenLeftIncluded() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a < 3").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenLeftIncludedReverseOrder
  @Test
  void testBetweenLeftIncludedReverseOrder() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where  a < 3 and a >= 1").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetween
  @Test
  void testBetween() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a > 1 and a < 3").toList();

      assertEquals(1, result.size());
      List<Integer> values = new ArrayList<Integer>(List.of(2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedIndex
  @Test
  void testBetweenRightLeftIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai >= 1 and ai <= 3").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedReverseOrderIndex
  @Test
  void testBetweenRightLeftIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai <= 3 and ai >= 1").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightIncludedIndex
  @Test
  void testBetweenRightIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai > 1 and ai <= 3").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightIncludedReverseOrderIndex
  @Test
  void testBetweenRightIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai <= 3 and ai > 1").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenLeftIncludedIndex
  @Test
  void testBetweenLeftIncludedIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai >= 1 and ai < 3").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenLeftIncludedReverseOrderIndex
  @Test
  void testBetweenLeftIncludedReverseOrderIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where  ai < 3 and ai >= 1").toList();

      assertEquals(2, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenIndex
  @Test
  void testBetweenIndex() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where ai > 1 and ai < 3").toList();

      assertEquals(1, result.size());
      List<Integer> values = new ArrayList<Integer>(List.of(2));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedDeepQuery
  @Test
  void testBetweenRightLeftIncludedDeepQuery() {
    session.executeInTx(transaction -> {
      final var result = session.query(
          "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and ((a"
              + " >= 1 and a <= 7) and vl = 'v1'))) and vl <> 'v4')")
          .toList();

      assertEquals(4, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedDeepQueryIndex
  @Test
  void testBetweenRightLeftIncludedDeepQueryIndex() {
    session.executeInTx(transaction -> {
      final var result = session.query(
          "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and"
              + " ((ai >= 1 and ai <= 7) and vl = 'v1'))) and vl <> 'v4')")
          .toList();

      assertEquals(4, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("ai")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedDifferentFields
  @Test
  void testBetweenRightLeftIncludedDifferentFields() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and ai <= 3").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenNotRangeQueryRight
  @Test
  void testBetweenNotRangeQueryRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a = 3").toList();

      assertEquals(1, result.size());
      List<Integer> values = new ArrayList<Integer>(List.of(3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenNotRangeQueryLeft
  @Test
  void testBetweenNotRangeQueryLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a = 1 and a <= 3").toList();

      assertEquals(1, result.size());
      List<Integer> values = new ArrayList<Integer>(List.of(1));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedBothFieldsLeft
  @Test
  void testBetweenRightLeftIncludedBothFieldsLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= ai and a <= 3").toList();

      assertEquals(4, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(0, 1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedBothFieldsRight
  @Test
  void testBetweenRightLeftIncludedBothFieldsRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and a <= ai").toList();

      assertEquals(9, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedFieldChainLeft
  @Test
  void testBetweenRightLeftIncludedFieldChainLeft() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where d.a >= 1 and a <= 3").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BetweenConversionTest#testBetweenRightLeftIncludedFieldChainRight
  @Test
  void testBetweenRightLeftIncludedFieldChainRight() {
    session.executeInTx(transaction -> {
      final var result =
          session.query("select from BetweenConversionTest where a >= 1 and d.a <= 3").toList();

      assertEquals(3, result.size());
      List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

      for (var document : result) {
        assertTrue(values.remove((Integer) document.getProperty("a")));
      }

      assertTrue(values.isEmpty());
    });
  }
}
