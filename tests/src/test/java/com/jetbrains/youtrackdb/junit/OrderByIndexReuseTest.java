// Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for ORDER BY clause optimization via index reuse.
 *
 * @since 2/11/14
 */
public class OrderByIndexReuseTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByAscFirstProperty
  @Test
  void testGreaterThanOrderByAscFirstProperty() {
    session.begin();
    var query = "select from OrderByIndexReuse where firstProp > 5 order by firstProp limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 6, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByAscSecondAscThirdProperty
  @Test
  void testGreaterThanOrderByAscSecondAscThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 6, (int) document.<Integer>getProperty("secondProp"));
      assertEquals("prop" + (i + 12), document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByDescSecondDescThirdProperty
  @Test
  void testGreaterThanOrderByDescSecondDescThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("secondProp"));
      assertEquals("prop" + (101 - i), document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByAscSecondDescThirdProperty
  @Test
  void testGreaterThanOrderByAscSecondDescThirdProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp desc"
            + " limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 6, (int) document.<Integer>getProperty("secondProp"));
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByDescFirstProperty
  @Test
  void testGreaterThanOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByAscFirstProperty
  @Test
  void testGTEOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByAscSecondPropertyAscThirdProperty
  @Test
  void testGTEOrderByAscSecondPropertyAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("secondProp"));
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByDescSecondPropertyDescThirdProperty
  @Test
  void testGTEOrderByDescSecondPropertyDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("secondProp"));
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByAscSecondPropertyDescThirdProperty
  @Test
  void testGTEOrderByAscSecondPropertyDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp"
            + " desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("secondProp"));
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByDescFirstProperty
  @Test
  void testGTEOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByAscFirstProperty
  @Test
  void testLTOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByAscSecondAscThirdProperty
  @Test
  void testLTOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByDescSecondDescThirdProperty
  @Test
  void testLTOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(4 - i / 2, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByAscSecondDescThirdProperty
  @Test
  void testLTOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp desc"
            + " limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByDescFirstProperty
  @Test
  void testLTOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(4 - i / 2, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByAscFirstProperty
  @Test
  void testLTEOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByAscSecondAscThirdProperty
  @Test
  void testLTEOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByDescSecondDescThirdProperty
  @Test
  void testLTEOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(5 - i / 2, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByAscSecondDescThirdProperty
  @Test
  void testLTEOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp"
            + " desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByDescFirstProperty
  @Test
  void testLTEOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(5 - i / 2, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByAscFirstProperty
  @Test
  void testBetweenOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByAscSecondAscThirdProperty
  @Test
  void testBetweenOrderByAscSecondAscThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp asc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByDescSecondDescThirdProperty
  @Test
  void testBetweenOrderByDescSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp desc,"
            + " thirdProp desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(15 - i / 2, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByAscSecondDescThirdProperty
  @Test
  void testBetweenOrderByAscSecondDescThirdProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp desc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByDescFirstProperty
  @Test
  void testBetweenOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc"
            + " limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(15 - i / 2, (int) document.<Integer>getProperty("firstProp"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testInOrderByAscFirstProperty
  @Test
  void testInOrderByAscFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());

    var document = result.get(0);
    assertEquals(2, (int) document.<Integer>getProperty("firstProp"));

    document = result.get(1);
    assertEquals(2, (int) document.<Integer>getProperty("firstProp"));

    document = result.get(2);
    assertEquals(10, (int) document.<Integer>getProperty("firstProp"));

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testInOrderByDescFirstProperty
  @Test
  void testInOrderByDescFirstProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());

    var document = result.get(0);
    assertEquals(47, (int) document.<Integer>getProperty("firstProp"));

    document = result.get(1);
    assertEquals(47, (int) document.<Integer>getProperty("firstProp"));

    document = result.get(2);
    assertEquals(45, (int) document.<Integer>getProperty("firstProp"));

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByAscFirstAscFourthProperty
  @Test
  void testGreaterThanOrderByAscFirstAscFourthProperty() {
    session.begin();
    var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 6, (int) document.<Integer>getProperty("firstProp"));
      assertEquals("prop" + (i + 12), document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGreaterThanOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testGreaterThanOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("firstProp"));
      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByAscFirstPropertyAscFourthProperty
  @Test
  void testGTEOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testGTEOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testGTEOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc, prop4 asc"
            + " limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(50 - i / 2, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByAscFirstPropertyAscFourthProperty
  @Test
  void testLTOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testLTOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(4 - i / 2, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByAscFirstPropertyAscFourthProperty
  @Test
  void testLTEOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 1, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testLTEOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testLTEOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc, prop4 asc"
            + " limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      assertEquals(5 - i / 2, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }

    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByAscFirstPropertyAscFourthProperty
  @Test
  void testBetweenOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp asc,"
            + " prop4 asc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(i / 2 + 5, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testBetweenOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testBetweenOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc,"
            + " prop4 asc limit 5";
    var result = session.query(query).toList();

    assertEquals(5, result.size());
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      assertEquals(15 - i / 2, (int) document.<Integer>getProperty("firstProp"));

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>getProperty("firstProp") << 1;
      } else {
        property4Index = (document.<Integer>getProperty("firstProp") << 1) + 1;
      }

      assertEquals("prop" + property4Index, document.getProperty("prop4"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testInOrderByAscFirstPropertyAscFourthProperty
  @Test
  void testInOrderByAscFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp asc, prop4 asc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());

    var document = result.get(0);
    assertEquals(2, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop4", document.getProperty("prop4"));

    document = result.get(1);
    assertEquals(2, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop5", document.getProperty("prop4"));

    document = result.get(2);
    assertEquals(10, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop20", document.getProperty("prop4"));
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testInOrderByDescFirstPropertyAscFourthProperty
  @Test
  void testInOrderByDescFirstPropertyAscFourthProperty() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc, prop4 asc limit 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());

    var document = result.get(0);
    assertEquals(47, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop94", document.getProperty("prop4"));

    document = result.get(1);
    assertEquals(47, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop95", document.getProperty("prop4"));

    document = result.get(2);
    assertEquals(45, (int) document.<Integer>getProperty("firstProp"));
    assertEquals("prop90", document.getProperty("prop4"));
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderByFirstPropWithLimitAsc
  @Test
  void testOrderByFirstPropWithLimitAsc() {
    session.begin();
    final var query = "select from OrderByIndexReuse order by firstProp offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(6 + i / 2, document.<Object>getProperty("firstProp"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderByFirstPropWithLimitDesc
  @Test
  void testOrderByFirstPropWithLimitDesc() {
    session.begin();
    final var query = "select from OrderByIndexReuse order by firstProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(45 - i / 2, document.<Object>getProperty("firstProp"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderBySecondThirdPropWithLimitAsc
  @Test
  void testOrderBySecondThirdPropWithLimitAsc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp asc offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(6 + i / 2, document.<Object>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderBySecondThirdPropWithLimitDesc
  @Test
  void testOrderBySecondThirdPropWithLimitDesc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(45 - i / 2, document.<Object>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderBySecondThirdPropWithLimitAscDesc
  @Test
  void testOrderBySecondThirdPropWithLimitAscDesc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp desc offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(6 + i / 2, document.<Object>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      } else {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.OrderByIndexReuseTest#testOrderBySecondThirdPropWithLimitDescAsc
  @Test
  void testOrderBySecondThirdPropWithLimitDescAsc() {
    session.begin();
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp asc offset 10 limit 4";

    var result = session.query(query).toList();

    assertEquals(4, result.size());

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      assertEquals(45 - i / 2, document.<Object>getProperty("secondProp"));

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>getProperty("secondProp") << 1;
      } else {
        thirdPropertyIndex = (document.<Integer>getProperty("secondProp") << 1) + 1;
      }

      assertEquals("prop" + thirdPropertyIndex, document.getProperty("thirdProp"));
    }
    session.commit();
  }
}
