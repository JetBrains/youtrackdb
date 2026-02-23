package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for GqlExecutionPlan: empty plan, SQL-plan mode (start, close, reset, copy),
 * SqlStreamAdapter edge cases (closed hasNext/next, idempotent close), canBeCached.
 */
public class GqlExecutionPlanTest extends GraphBaseTest {

  @Test
  public void emptyPlan_start_returnsEmptyStream() {
    var plan = GqlExecutionPlan.empty();
    var stream = plan.start();
    Assert.assertNotNull(stream);
    Assert.assertFalse(stream.hasNext());
  }

  @Test
  public void emptyPlan_close_reset_doNotThrow() {
    var plan = GqlExecutionPlan.empty();
    plan.close();
    plan.reset();
  }

  @Test
  @SuppressWarnings("resource")
  public void emptyPlan_copy_returnsEmptyPlan() {
    var plan = GqlExecutionPlan.empty();
    var copy = plan.copy();
    Assert.assertNotNull(copy);
    Assert.assertNotSame(plan, copy);
    var stream = copy.start();
    Assert.assertFalse(stream.hasNext());
  }

  @Test
  public void canBeCached_returnsTrue() {
    Assert.assertTrue(GqlExecutionPlan.canBeCached());
  }

  @Test
  public void sqlPlanMode_start_returnsAdaptedStream() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlPlanTest EXTENDS V");
      session.command("CREATE VERTEX SqlPlanTest SET name = 'A'");

      var gqlPlan = buildSqlPlan(session, "a", "SqlPlanTest");
      var stream = gqlPlan.start();
      Assert.assertNotNull(stream);
      Assert.assertTrue(stream.hasNext());
      Assert.assertNotNull(stream.next());
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.rollback();
    }
  }

  @Test
  public void sqlPlanMode_reset_allowsReExecution() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlPlanReset EXTENDS V");
      session.command("CREATE VERTEX SqlPlanReset SET name = 'R'");

      var gqlPlan = buildSqlPlan(session, "a", "SqlPlanReset");

      var stream1 = gqlPlan.start();
      Assert.assertTrue(stream1.hasNext());
      stream1.next();
      Assert.assertFalse(stream1.hasNext());

      gqlPlan.reset();
      var stream2 = gqlPlan.start();
      Assert.assertTrue(stream2.hasNext());
      stream2.next();
      Assert.assertFalse(stream2.hasNext());
      gqlPlan.close();
    } finally {
      tx.rollback();
    }
  }

  @Test
  public void sqlPlanMode_copy_producesIndependentCopy() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlPlanCopy EXTENDS V");
      session.command("CREATE VERTEX SqlPlanCopy SET name = 'B'");

      var gqlPlan = buildSqlPlan(session, "b", "SqlPlanCopy");
      var copy = gqlPlan.copy();
      Assert.assertNotSame(gqlPlan, copy);

      var stream = copy.start();
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
      copy.close();
      gqlPlan.close();
    } finally {
      tx.rollback();
    }
  }

  @Test
  public void sqlStreamAdapter_hasNext_afterClose_returnsFalse() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlAdapterClose EXTENDS V");
      session.command("CREATE VERTEX SqlAdapterClose SET name = 'X'");

      var gqlPlan = buildSqlPlan(session, "a", "SqlAdapterClose");
      var stream = gqlPlan.start();
      stream.close();
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.rollback();
    }
  }

  @Test(expected = NoSuchElementException.class)
  public void sqlStreamAdapter_next_afterClose_throwsNoSuchElement() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlAdapterNextClose EXTENDS V");
      session.command("CREATE VERTEX SqlAdapterNextClose SET name = 'Y'");

      var gqlPlan = buildSqlPlan(session, "a", "SqlAdapterNextClose");
      var stream = gqlPlan.start();
      stream.close();
      stream.next();
    } finally {
      tx.rollback();
    }
  }

  @Test
  public void sqlStreamAdapter_close_isIdempotent() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      session.command("CREATE CLASS SqlAdapterIdempotent EXTENDS V");
      session.command("CREATE VERTEX SqlAdapterIdempotent SET name = 'Z'");

      var gqlPlan = buildSqlPlan(session, "a", "SqlAdapterIdempotent");
      var stream = gqlPlan.start();
      stream.close();
      stream.close();
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.rollback();
    }
  }

  private static GqlExecutionPlan buildSqlPlan(
      DatabaseSessionEmbedded session, String alias, String className) {
    var pattern = new Pattern();
    pattern.addNode(alias);
    var planner = new MatchExecutionPlanner(
        pattern, Map.of(alias, className), null, null);
    var sqlPlan = planner.createExecutionPlan(new BasicCommandContext(session), false);
    return GqlExecutionPlan.forSqlMatchPlan(sqlPlan);
  }
}
