package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 9/12/14
 */
@Test
public class BetweenConversionTest extends BaseDBTest {

  @Parameters(value = "remote")
  public BetweenConversionTest(@Optional Boolean remote) {
    super(remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("BetweenConversionTest");
    clazz.createProperty("a", PropertyType.INTEGER);
    clazz.createProperty("ai", PropertyType.INTEGER);

    clazz.createIndex("BetweenConversionTestIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "ai");

    for (var i = 0; i < 10; i++) {
      var document = ((EntityImpl) session.newEntity("BetweenConversionTest"));
      document.field("a", i);
      document.field("ai", i);

      if (i < 5) {
        document.field("vl", "v1");
      } else {
        document.field("vl", "v2");
      }

      var ed = ((EntityImpl) session.newEntity());
      ed.field("a", i);

      document.field("d", ed);

      session.begin();

      session.commit();
    }
  }

  public void testBetweenRightLeftIncluded() {
    final var query = "select from BetweenConversionTest where a >= 1 and a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenRightLeftIncludedReverseOrder() {
    final var query = "select from BetweenConversionTest where a <= 3 and a >= 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenRightIncluded() {
    final var query = "select from BetweenConversionTest where a > 1 and a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenRightIncludedReverse() {
    final var query = "select from BetweenConversionTest where a <= 3 and a > 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenLeftIncluded() {
    final var query = "select from BetweenConversionTest where a >= 1 and a < 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenLeftIncludedReverseOrder() {
    final var query = "select from BetweenConversionTest where  a < 3 and a >= 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetween() {
    final var query = "select from BetweenConversionTest where a > 1 and a < 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 1);
    List<Integer> values = new ArrayList<Integer>(List.of(2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenRightLeftIncludedIndex() {
    final var query = "select from BetweenConversionTest where ai >= 1 and ai <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenRightLeftIncludedReverseOrderIndex() {
    final var query = "select from BetweenConversionTest where ai <= 3 and ai >= 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenRightIncludedIndex() {
    final var query = "select from BetweenConversionTest where ai > 1 and ai <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenRightIncludedReverseOrderIndex() {
    final var query = "select from BetweenConversionTest where ai <= 3 and ai > 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenLeftIncludedIndex() {
    final var query = "select from BetweenConversionTest where ai >= 1 and ai < 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenLeftIncludedReverseOrderIndex() {
    final var query = "select from BetweenConversionTest where  ai < 3 and ai >= 1";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 2);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenIndex() {
    final var query = "select from BetweenConversionTest where ai > 1 and ai < 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 1);
    List<Integer> values = new ArrayList<Integer>(List.of(2));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenRightLeftIncludedDeepQuery() {
    final var query =
        "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and ((a"
            + " >= 1 and a <= 7) and vl = 'v1'))) and vl <> 'v4')";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
  }

  public void testBetweenRightLeftIncludedDeepQueryIndex() {
    final var query =
        "select from BetweenConversionTest where (vl = 'v1' and (vl <> 'v3' and (vl <> 'v2' and"
            + " ((ai >= 1 and ai <= 7) and vl = 'v1'))) and vl <> 'v4')";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("ai")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertEquals(explain.<Object>field("rangeQueryConvertedInBetween"), 1);
    Assert.assertTrue(
        ((Set<String>) explain.field("involvedIndexes")).contains("BetweenConversionTestIndex"));
  }

  public void testBetweenRightLeftIncludedDifferentFields() {
    final var query = "select from BetweenConversionTest where a >= 1 and ai <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenNotRangeQueryRight() {
    final var query = "select from BetweenConversionTest where a >= 1 and a = 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 1);
    List<Integer> values = new ArrayList<Integer>(List.of(3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenNotRangeQueryLeft() {
    final var query = "select from BetweenConversionTest where a = 1 and a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 1);
    List<Integer> values = new ArrayList<Integer>(List.of(1));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenRightLeftIncludedBothFieldsLeft() {
    final var query = "select from BetweenConversionTest where a >= ai and a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 4);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(0, 1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenRightLeftIncludedBothFieldsRight() {
    final var query = "select from BetweenConversionTest where a >= 1 and a <= ai";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 9);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenRightLeftIncludedFieldChainLeft() {
    final var query = "select from BetweenConversionTest where d.a >= 1 and a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }

  public void testBetweenRightLeftIncludedFieldChainRight() {
    final var query = "select from BetweenConversionTest where a >= 1 and d.a <= 3";
    final List<EntityImpl> result = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(result.size(), 3);
    List<Integer> values = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

    for (var document : result) {
      Assert.assertTrue(values.remove((Integer) document.field("a")));
    }

    Assert.assertTrue(values.isEmpty());

    EntityImpl explain = session.command(new CommandSQL("explain " + query)).execute(session);

    Assert.assertNull(explain.field("rangeQueryConvertedInBetween"));
  }
}
