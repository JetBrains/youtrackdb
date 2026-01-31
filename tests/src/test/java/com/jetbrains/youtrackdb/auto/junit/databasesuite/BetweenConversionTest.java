/*
 * JUnit 4 version of BetweenConversionTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 9/12/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BetweenConversionTest extends BaseDBTest {

  private static BetweenConversionTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new BetweenConversionTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 21) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
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

  /**
   * Original: testBetweenRightLeftIncluded (line 53) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test01_BetweenRightLeftIncluded() {
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

  /**
   * Original: testBetweenRightLeftIncludedReverseOrder (line 69) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test02_BetweenRightLeftIncludedReverseOrder() {
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

  /**
   * Original: testBetweenRightIncluded (line 85) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test03_BetweenRightIncluded() {
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

  /**
   * Original: testBetweenRightIncludedReverse (line 101) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test04_BetweenRightIncludedReverse() {
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

  /**
   * Original: testBetweenLeftIncluded (line 117) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test05_BetweenLeftIncluded() {
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

  /**
   * Original: testBetweenLeftIncludedReverseOrder (line 133) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test06_BetweenLeftIncludedReverseOrder() {
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

  /**
   * Original: testBetween (line 149) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test07_Between() {
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

  /**
   * Original: testBetweenRightLeftIncludedIndex (line 165) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test08_BetweenRightLeftIncludedIndex() {
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

  /**
   * Original: testBetweenRightLeftIncludedReverseOrderIndex (line 181) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test09_BetweenRightLeftIncludedReverseOrderIndex() {
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

  /**
   * Original: testBetweenRightIncludedIndex (line 197) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test10_BetweenRightIncludedIndex() {
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

  /**
   * Original: testBetweenRightIncludedReverseOrderIndex (line 213) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test11_BetweenRightIncludedReverseOrderIndex() {
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

  /**
   * Original: testBetweenLeftIncludedIndex (line 229) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test12_BetweenLeftIncludedIndex() {
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

  /**
   * Original: testBetweenLeftIncludedReverseOrderIndex (line 245) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test13_BetweenLeftIncludedReverseOrderIndex() {
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

  /**
   * Original: testBetweenIndex (line 261) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test14_BetweenIndex() {
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

  /**
   * Original: testBetweenRightLeftIncludedDeepQuery (line 277) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test15_BetweenRightLeftIncludedDeepQuery() {
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

  /**
   * Original: testBetweenRightLeftIncludedDeepQueryIndex (line 295) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test16_BetweenRightLeftIncludedDeepQueryIndex() {
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

  /**
   * Original: testBetweenRightLeftIncludedDifferentFields (line 313) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test17_BetweenRightLeftIncludedDifferentFields() {
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

  /**
   * Original: testBetweenNotRangeQueryRight (line 329) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test18_BetweenNotRangeQueryRight() {
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

  /**
   * Original: testBetweenNotRangeQueryLeft (line 345) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test19_BetweenNotRangeQueryLeft() {
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

  /**
   * Original: testBetweenRightLeftIncludedBothFieldsLeft (line 361) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test20_BetweenRightLeftIncludedBothFieldsLeft() {
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

  /**
   * Original: testBetweenRightLeftIncludedBothFieldsRight (line 377) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test21_BetweenRightLeftIncludedBothFieldsRight() {
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

  /**
   * Original: testBetweenRightLeftIncludedFieldChainLeft (line 393) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test22_BetweenRightLeftIncludedFieldChainLeft() {
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

  /**
   * Original: testBetweenRightLeftIncludedFieldChainRight (line 409) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BetweenConversionTest.java
   */
  @Test
  public void test23_BetweenRightLeftIncludedFieldChainRight() {
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
