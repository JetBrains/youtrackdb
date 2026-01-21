/*
 * JUnit 4 version of OrderByIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @since 2/11/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class OrderByIndexReuseTest extends BaseDBTest {

  private static OrderByIndexReuseTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new OrderByIndexReuseTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 17) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final var schema = session.getMetadata().getSchema();
    final var orderByIndexReuse = schema.createClass("OrderByIndexReuse", 1);

    orderByIndexReuse.createProperty("firstProp", PropertyType.INTEGER);
    orderByIndexReuse.createProperty("secondProp", PropertyType.INTEGER);
    orderByIndexReuse.createProperty("thirdProp", PropertyType.STRING);
    orderByIndexReuse.createProperty("prop4", PropertyType.STRING);

    orderByIndexReuse.createIndex(
        "OrderByIndexReuseIndexSecondThirdProp",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "secondProp", "thirdProp");
    orderByIndexReuse.createIndex(
        "OrderByIndexReuseIndexFirstPropNotUnique", SchemaClass.INDEX_TYPE.NOTUNIQUE, "firstProp");

    for (var i = 0; i < 100; i++) {
      session.begin();
      var document = ((EntityImpl) session.newEntity("OrderByIndexReuse"));
      document.setProperty("firstProp", (101 - i) / 2);
      document.setProperty("secondProp", (101 - i) / 2);

      document.setProperty("thirdProp", "prop" + (101 - i));
      document.setProperty("prop4", "prop" + (101 - i));

      session.commit();
    }
  }

  /**
   * Original: testGreaterThanOrderByAscFirstProperty (line 48) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test01_GreaterThanOrderByAscFirstProperty() {
    session.begin();
    var query = "select from OrderByIndexReuse where firstProp > 5 order by firstProp limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 6);
    }

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByAscSecondAscThirdProperty (line 62) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test02_GreaterThanOrderByAscSecondAscThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 6);
      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + (i + 12));
    }

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByDescSecondDescThirdProperty (line 79) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test03_GreaterThanOrderByDescSecondDescThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), 50 - i / 2);
      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + (101 - i));
    }

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByAscSecondDescThirdProperty (line 96) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test04_GreaterThanOrderByAscSecondDescThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp desc"
            + " limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 6);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByDescFirstProperty (line 120) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test05_GreaterThanOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 50 - i / 2);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByAscFirstProperty (line 135) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test06_GTEOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 5);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByAscSecondPropertyAscThirdProperty (line 150) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test07_GTEOrderByAscSecondPropertyAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 5);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByDescSecondPropertyDescThirdProperty (line 174) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test08_GTEOrderByDescSecondPropertyDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), 50 - i / 2);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByAscSecondPropertyDescThirdProperty (line 198) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test09_GTEOrderByAscSecondPropertyDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 5);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByDescFirstProperty (line 222) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test10_GTEOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 50 - i / 2);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByAscFirstProperty (line 237) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test11_LTOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 1);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByAscSecondAscThirdProperty (line 252) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test12_LTOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByDescSecondDescThirdProperty (line 277) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test13_LTOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), 4 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByAscSecondDescThirdProperty (line 302) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test14_LTOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp desc"
            + " limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByDescFirstProperty (line 327) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test15_LTOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 4 - i / 2);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByAscFirstProperty (line 342) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test16_LTEOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 1);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByAscSecondAscThirdProperty (line 357) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test17_LTEOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByDescSecondDescThirdProperty (line 382) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test18_LTEOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), 5 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByAscSecondDescThirdProperty (line 407) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test19_LTEOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByDescFirstProperty (line 432) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test20_LTEOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 5 - i / 2);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByAscFirstProperty (line 447) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test21_BetweenOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 5);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByAscSecondAscThirdProperty (line 462) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test22_BetweenOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp asc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 5);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByDescSecondDescThirdProperty (line 487) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test23_BetweenOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp desc,"
            + " thirdProp desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), 15 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByAscSecondDescThirdProperty (line 512) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test24_BetweenOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp desc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("secondProp"), i / 2 + 5);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByDescFirstProperty (line 537) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test25_BetweenOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc"
            + " limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 15 - i / 2);
    }

    session.commit();
  }

  /**
   * Original: testInOrderByAscFirstProperty (line 553) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test26_InOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 2);

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 2);

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 10);

    session.commit();
  }

  /**
   * Original: testInOrderByDescFirstProperty (line 574) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test27_InOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 47);

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 47);

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 45);

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByAscFirstAscFourthProperty (line 595) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test28_GreaterThanOrderByAscFirstAscFourthProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 6);
      Assert.assertEquals(document.getProperty("prop4"), "prop" + (i + 12));
    }

    session.commit();
  }

  /**
   * Original: testGreaterThanOrderByDescFirstPropertyAscFourthProperty (line 612) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test29_GreaterThanOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 50 - i / 2);
      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByAscFirstPropertyAscFourthProperty (line 636) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test30_GTEOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 5);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testGTEOrderByDescFirstPropertyAscFourthProperty (line 661) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test31_GTEOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc, prop4 asc"
            + " limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 50 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByAscFirstPropertyAscFourthProperty (line 686) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test32_LTOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 1);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testLTOrderByDescFirstPropertyAscFourthProperty (line 711) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test33_LTOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 4 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByAscFirstPropertyAscFourthProperty (line 736) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test34_LTEOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 1);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testLTEOrderByDescFirstPropertyAscFourthProperty (line 761) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test35_LTEOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc, prop4 asc"
            + " limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 5 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }

    session.commit();
  }

  /**
   * Original: testBetweenOrderByAscFirstPropertyAscFourthProperty (line 786) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test36_BetweenOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp asc,"
            + " prop4 asc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), i / 2 + 5);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }
    session.commit();
  }

  /**
   * Original: testBetweenOrderByDescFirstPropertyAscFourthProperty (line 810) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test37_BetweenOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc,"
            + " prop4 asc limit 5";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 15 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("prop4"), "prop" + property4Index);
    }
    session.commit();
  }

  /**
   * Original: testInOrderByAscFirstPropertyAscFourthProperty (line 834) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test38_InOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp asc, prop4 asc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 2);
    Assert.assertEquals(document.getProperty("prop4"), "prop4");

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 2);
    Assert.assertEquals(document.getProperty("prop4"), "prop5");

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 10);
    Assert.assertEquals(document.getProperty("prop4"), "prop20");
    session.commit();
  }

  /**
   * Original: testInOrderByDescFirstPropertyAscFourthProperty (line 857) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test39_InOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc, prop4 asc limit 3";
    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 47);
    Assert.assertEquals(document.getProperty("prop4"), "prop94");

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 47);
    Assert.assertEquals(document.getProperty("prop4"), "prop95");

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>getProperty("firstProp"), 45);
    Assert.assertEquals(document.getProperty("prop4"), "prop90");
    session.commit();
  }

  /**
   * Original: testOrderByFirstPropWithLimitAsc (line 880) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test40_OrderByFirstPropWithLimitAsc() {
    session.begin();
    final var query = "select from OrderByIndexReuse order by firstProp offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("firstProp"), 6 + i / 2);
    }
    session.commit();
  }

  /**
   * Original: testOrderByFirstPropWithLimitDesc (line 896) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test41_OrderByFirstPropWithLimitDesc() {
    session.begin();
    final var query = "select from OrderByIndexReuse order by firstProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("firstProp"), 45 - i / 2);
    }
    session.commit();
  }

  /**
   * Original: testOrderBySecondThirdPropWithLimitAsc (line 912) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test42_OrderBySecondThirdPropWithLimitAsc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp asc offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("secondProp"), 6 + i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }
    session.commit();
  }

  /**
   * Original: testOrderBySecondThirdPropWithLimitDesc (line 938) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test43_OrderBySecondThirdPropWithLimitDesc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("secondProp"), 45 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }
    session.commit();
  }

  /**
   * Original: testOrderBySecondThirdPropWithLimitAscDesc (line 964) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test44_OrderBySecondThirdPropWithLimitAscDesc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("secondProp"), 6 + i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }
    session.commit();
  }

  /**
   * Original: testOrderBySecondThirdPropWithLimitDescAsc (line 990) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/OrderByIndexReuseTest.java
   */
  @Test
  public void test45_OrderBySecondThirdPropWithLimitDescAsc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp asc offset 10 limit 4";

    var result = session.query(query).toList();

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>getProperty("secondProp"), 45 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      Assert.assertEquals(document.getProperty("thirdProp"), "prop" + thirdPropertyIndex);
    }
    session.commit();
  }

}
