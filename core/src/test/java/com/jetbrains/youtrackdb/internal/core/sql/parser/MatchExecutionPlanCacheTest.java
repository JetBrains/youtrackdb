package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that MATCH query execution plans are cached in the
 * {@link ExecutionPlanCache}, just like SELECT plans.
 */
public class MatchExecutionPlanCacheTest extends BaseMemoryInternalDatabase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.execute("CREATE class Person extends V").close();
    session.execute("CREATE class Friend extends E").close();
    session.begin();
    session.execute("CREATE VERTEX Person set name = 'Alice'").close();
    session.execute("CREATE VERTEX Person set name = 'Bob'").close();
    session
        .execute(
            "CREATE EDGE Friend from (SELECT FROM Person WHERE name='Alice')"
                + " to (SELECT FROM Person WHERE name='Bob')")
        .close();
    session.commit();
  }

  @Test
  public void testMatchPlanIsCached() throws InterruptedException {
    var cache = ExecutionPlanCache.instance(session);
    var matchSql =
        "MATCH {class: Person, as: p, where: (name = 'Alice')}"
            + ".out('Friend'){as: f} RETURN p, f";

    // Ensure we're past the last invalidation timestamp
    Thread.sleep(2);

    // First execution — plan should be created and cached
    session.begin();
    var rs = session.query(matchSql);
    Assert.assertTrue(rs.hasNext());
    rs.close();
    session.commit();

    Assert.assertTrue(
        "MATCH plan should be in cache after first execution",
        cache.contains(matchSql));
  }

  @Test
  public void testMatchPlanCacheHitReturnsSameResults() throws InterruptedException {
    var matchSql =
        "MATCH {class: Person, as: p, where: (name = 'Alice')}"
            + ".out('Friend'){as: f} RETURN f.name as friendName";

    // Ensure we're past the last invalidation timestamp
    Thread.sleep(2);

    // First execution — populates cache
    session.begin();
    var rs1 = session.query(matchSql);
    Assert.assertTrue(rs1.hasNext());
    var firstName = rs1.next().<String>getProperty("friendName");
    Assert.assertFalse(rs1.hasNext());
    rs1.close();
    session.commit();

    // Second execution — should hit cache and produce identical results
    session.begin();
    var rs2 = session.query(matchSql);
    Assert.assertTrue(rs2.hasNext());
    var secondName = rs2.next().<String>getProperty("friendName");
    Assert.assertFalse(rs2.hasNext());
    rs2.close();
    session.commit();

    Assert.assertEquals("Bob", firstName);
    Assert.assertEquals(
        "Cached plan should produce the same result", firstName, secondName);
  }

  @Test
  public void testMatchPlanCacheInvalidatedOnSchemaChange()
      throws InterruptedException {
    var cache = ExecutionPlanCache.instance(session);
    var matchSql =
        "MATCH {class: Person, as: p} RETURN p.name as name";

    Thread.sleep(2);

    // Populate cache
    session.begin();
    session.query(matchSql).close();
    session.commit();

    Assert.assertTrue(
        "MATCH plan should be cached", cache.contains(matchSql));

    // Schema change should invalidate the cache
    session.getMetadata().getSchema().createClass("AnotherClass");

    Assert.assertFalse(
        "MATCH plan should be evicted after schema change",
        cache.contains(matchSql));
  }

  @Test
  public void testMatchWithBuiltinReturnIsCached() throws InterruptedException {
    var cache = ExecutionPlanCache.instance(session);
    var matchSql =
        "MATCH {class: Person, as: p}.out('Friend'){as: f} RETURN $patterns";

    Thread.sleep(2);

    session.begin();
    session.query(matchSql).close();
    session.commit();

    Assert.assertTrue(
        "MATCH plan with RETURN $patterns should be cached",
        cache.contains(matchSql));
  }
}
