package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @since 2/11/14
 */
@Test
public class OrderByIndexReuseTest extends BaseDBTest {
  @Override
  @BeforeClass
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

  public void testGreaterThanOrderByAscFirstProperty() {
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

  public void testGreaterThanOrderByAscSecondAscThirdProperty() {
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

  public void testGreaterThanOrderByDescSecondDescThirdProperty() {
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

  public void testGreaterThanOrderByAscSecondDescThirdProperty() {
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

  public void testGreaterThanOrderByDescFirstProperty() {
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

  public void testGTEOrderByAscFirstProperty() {
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

  public void testGTEOrderByAscSecondPropertyAscThirdProperty() {
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

  public void testGTEOrderByDescSecondPropertyDescThirdProperty() {
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

  public void testGTEOrderByAscSecondPropertyDescThirdProperty() {
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

  public void testGTEOrderByDescFirstProperty() {
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

  public void testLTOrderByAscFirstProperty() {
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

  public void testLTOrderByAscSecondAscThirdProperty() {
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

  public void testLTOrderByDescSecondDescThirdProperty() {
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

  public void testLTOrderByAscSecondDescThirdProperty() {
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

  public void testLTOrderByDescFirstProperty() {
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

  public void testLTEOrderByAscFirstProperty() {
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

  public void testLTEOrderByAscSecondAscThirdProperty() {
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

  public void testLTEOrderByDescSecondDescThirdProperty() {
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

  public void testLTEOrderByAscSecondDescThirdProperty() {
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

  public void testLTEOrderByDescFirstProperty() {
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

  public void testBetweenOrderByAscFirstProperty() {
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

  public void testBetweenOrderByAscSecondAscThirdProperty() {
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

  public void testBetweenOrderByDescSecondDescThirdProperty() {
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

  public void testBetweenOrderByAscSecondDescThirdProperty() {
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

  public void testBetweenOrderByDescFirstProperty() {
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

  public void testInOrderByAscFirstProperty() {
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

  public void testInOrderByDescFirstProperty() {
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

  public void testGreaterThanOrderByAscFirstAscFourthProperty() {
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

  public void testGreaterThanOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testGTEOrderByAscFirstPropertyAscFourthProperty() {
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

  public void testGTEOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testLTOrderByAscFirstPropertyAscFourthProperty() {
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

  public void testLTOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testLTEOrderByAscFirstPropertyAscFourthProperty() {
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

  public void testLTEOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testBetweenOrderByAscFirstPropertyAscFourthProperty() {
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

  public void testBetweenOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testInOrderByAscFirstPropertyAscFourthProperty() {
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

  public void testInOrderByDescFirstPropertyAscFourthProperty() {
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

  public void testOrderByFirstPropWithLimitAsc() {
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

  public void testOrderByFirstPropWithLimitDesc() {
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

  public void testOrderBySecondThirdPropWithLimitAsc() {
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

  public void testOrderBySecondThirdPropWithLimitDesc() {
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

  public void testOrderBySecondThirdPropWithLimitAscDesc() {
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

  public void testOrderBySecondThirdPropWithLimitDescAsc() {
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
