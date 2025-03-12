package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 2/11/14
 */
@Test
public class OrderByIndexReuseTest extends BaseDBTest {

  @Parameters(value = "remote")
  public OrderByIndexReuseTest(boolean remote) {
    super(remote);
  }

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();

    final var orderByIndexReuse = schema.createClass("OrderByIndexReuse", 1, null);

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
      document.field("firstProp", (101 - i) / 2);
      document.field("secondProp", (101 - i) / 2);

      document.field("thirdProp", "prop" + (101 - i));
      document.field("prop4", "prop" + (101 - i));

      session.commit();
    }
  }

  public void testGreaterThanOrderByAscFirstProperty() {
    var query = "select from OrderByIndexReuse where firstProp > 5 order by firstProp limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 6);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGreaterThanOrderByAscSecondAscThirdProperty() {
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 6);
      Assert.assertEquals(document.field("thirdProp"), "prop" + (i + 12));
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testGreaterThanOrderByDescSecondDescThirdProperty() {
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), 50 - i / 2);
      Assert.assertEquals(document.field("thirdProp"), "prop" + (101 - i));
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testGreaterThanOrderByAscSecondDescThirdProperty() {
    var query =
        "select from OrderByIndexReuse where secondProp > 5 order by secondProp asc, thirdProp desc"
            + " limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 6);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testGreaterThanOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 50 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGTEOrderByAscFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 5);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGTEOrderByAscSecondPropertyAscThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp asc"
            + " limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 5);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testGTEOrderByDescSecondPropertyDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp desc, thirdProp"
            + " desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), 50 - i / 2);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testGTEOrderByAscSecondPropertyDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp >= 5 order by secondProp asc, thirdProp"
            + " desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 5);
      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testGTEOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 50 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTOrderByAscFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 1);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTOrderByAscSecondAscThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testLTOrderByDescSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), 4 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testLTOrderByAscSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp < 5 order by secondProp asc, thirdProp desc"
            + " limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testLTOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 4 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTEOrderByAscFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 1);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTEOrderByAscSecondAscThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp asc"
            + " limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testLTEOrderByDescSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp desc, thirdProp"
            + " desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), 5 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testLTEOrderByAscSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp <= 5 order by secondProp asc, thirdProp"
            + " desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 1);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testLTEOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 5 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testBetweenOrderByAscFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 5);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testBetweenOrderByAscSecondAscThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp asc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 5);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testBetweenOrderByDescSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp desc,"
            + " thirdProp desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), 15 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testBetweenOrderByAscSecondDescThirdProperty() {
    final var query =
        "select from OrderByIndexReuse where secondProp between 5 and 15 order by secondProp asc,"
            + " thirdProp desc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("secondProp"), i / 2 + 5);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testBetweenOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc"
            + " limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 15 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testInOrderByAscFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 2);

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 2);

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 10);

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testInOrderByDescFirstProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 47);

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 47);

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 45);

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGreaterThanOrderByAscFirstAscFourthProperty() {
    var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 6);
      Assert.assertEquals(document.field("prop4"), "prop" + (i + 12));
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGreaterThanOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp > 5 order by firstProp desc, prop4 asc limit"
            + " 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 50 - i / 2);
      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGTEOrderByAscFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp asc, prop4 asc limit"
            + " 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 5);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testGTEOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp >= 5 order by firstProp desc, prop4 asc"
            + " limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 50 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTOrderByAscFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 1);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp < 5 order by firstProp desc, prop4 asc limit"
            + " 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 4 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTEOrderByAscFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp asc, prop4 asc limit"
            + " 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 1);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testLTEOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp <= 5 order by firstProp desc, prop4 asc"
            + " limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    for (var i = 0; i < 3; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 5 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testBetweenOrderByAscFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp asc,"
            + " prop4 asc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), i / 2 + 5);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testBetweenOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp between 5 and 15 order by firstProp desc,"
            + " prop4 asc limit 5";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 5);
    for (var i = 0; i < 5; i++) {
      var document = result.get(i);
      Assert.assertEquals((int) document.<Integer>field("firstProp"), 15 - i / 2);

      int property4Index;
      if (i % 2 == 0) {
        property4Index = document.<Integer>field("firstProp") * 2;
      } else {
        property4Index = document.<Integer>field("firstProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("prop4"), "prop" + property4Index);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testInOrderByAscFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp asc, prop4 asc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 2);
    Assert.assertEquals(document.field("prop4"), "prop4");

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 2);
    Assert.assertEquals(document.field("prop4"), "prop5");

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 10);
    Assert.assertEquals(document.field("prop4"), "prop20");

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testInOrderByDescFirstPropertyAscFourthProperty() {
    final var query =
        "select from OrderByIndexReuse where firstProp in [10, 2, 43, 21, 45, 47, 11, 12] order by"
            + " firstProp desc, prop4 asc limit 3";
    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);

    var document = result.get(0);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 47);
    Assert.assertEquals(document.field("prop4"), "prop94");

    document = result.get(1);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 47);
    Assert.assertEquals(document.field("prop4"), "prop95");

    document = result.get(2);
    Assert.assertEquals((int) document.<Integer>field("firstProp"), 45);
    Assert.assertEquals(document.field("prop4"), "prop90");

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);

    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testOrderByFirstPropWithLimitAsc() {
    final var query = "select from OrderByIndexReuse order by firstProp offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("firstProp"), 6 + i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testOrderByFirstPropWithLimitDesc() {
    final var query = "select from OrderByIndexReuse order by firstProp desc offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("firstProp"), 45 - i / 2);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexFirstPropNotUnique"});
  }

  public void testOrderBySecondThirdPropWithLimitAsc() {
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp asc offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("secondProp"), 6 + i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testOrderBySecondThirdPropWithLimitDesc() {
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp desc offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("secondProp"), 45 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertTrue(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertTrue(explain.<Boolean>field("indexIsUsedInOrderBy"));
    Assert.assertEquals(
        explain.<Set>field("involvedIndexes").toArray(),
        new String[]{"OrderByIndexReuseIndexSecondThirdProp"});
  }

  public void testOrderBySecondThirdPropWithLimitAscDesc() {
    final var query =
        "select from OrderByIndexReuse order by secondProp asc, thirdProp desc offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("secondProp"), 6 + i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }

  public void testOrderBySecondThirdPropWithLimitDescAsc() {
    final var query =
        "select from OrderByIndexReuse order by secondProp desc, thirdProp asc offset 10 limit 4";

    List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);

    for (var i = 0; i < 4; i++) {
      final var document = result.get(i);

      Assert.assertEquals(document.<Object>field("secondProp"), 45 - i / 2);

      int thirdPropertyIndex;
      if (i % 2 == 0) {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2;
      } else {
        thirdPropertyIndex = document.<Integer>field("secondProp") * 2 + 1;
      }

      Assert.assertEquals(document.field("thirdProp"), "prop" + thirdPropertyIndex);
    }

    final EntityImpl explain = session.command(new CommandSQL("explain " + query))
        .execute(session);
    Assert.assertFalse(explain.<Boolean>field("fullySortedByIndex"));
    Assert.assertFalse(explain.<Boolean>field("indexIsUsedInOrderBy"));
  }
}
