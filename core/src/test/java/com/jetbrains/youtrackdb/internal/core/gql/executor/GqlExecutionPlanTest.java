package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for GqlExecutionPlan covering:
 * - empty plan: start, close, reset, copy
 * - forSqlMatchPlan(null) → NPE
 * - SQL plan mode: start returns Result, multiple results, reset/re-execute, copy independent
 * - SqlStreamAdapter: hasNext auto-close, hasNext after close, next after close, idempotent close
 * - canBeCached always true
 */
public class GqlExecutionPlanTest extends GraphBaseTest {

  // ── Empty plan ──

  @Test
  public void emptyPlan_start_returnsEmptyStream() {
    var plan = GqlExecutionPlan.empty();
    var stream = plan.start();
    Assert.assertNotNull(stream);
    Assert.assertFalse(stream.hasNext());
  }

  @Test
  public void emptyPlan_close_doesNotThrow() {
    GqlExecutionPlan.empty().close();
  }

  @Test
  public void emptyPlan_reset_doesNotThrow() {
    GqlExecutionPlan.empty().reset();
  }

  @Test
  @SuppressWarnings("resource")
  public void emptyPlan_copy_returnsNewEmptyPlan() {
    var plan = GqlExecutionPlan.empty();
    var copy = plan.copy();
    Assert.assertNotSame(plan, copy);
    Assert.assertFalse(copy.start().hasNext());
  }

  // ── Factory ──

  @Test(expected = NullPointerException.class)
  public void forSqlMatchPlan_nullArg_throwsNPE() {
    GqlExecutionPlan.forSqlMatchPlan(null);
  }

  // ── canBeCached ──

  @Test
  public void canBeCached_returnsTrue() {
    Assert.assertTrue(GqlExecutionPlan.canBeCached());
  }

  // ── SQL plan mode: start returns adapted stream with Result ──

  @Test
  public void sqlPlanMode_start_returnsResultWithAlias() {
    graph.addVertex(T.label, "EPStart", "name", "A");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPStart");
      var stream = gqlPlan.start();
      Assert.assertTrue(stream.hasNext());
      var raw = stream.next();
      Assert.assertTrue(raw instanceof Result);
      Assert.assertTrue(((Result) raw).getPropertyNames().contains("a"));
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SQL plan mode: multiple results ──

  @Test
  public void sqlPlanMode_multipleResults_allReturned() {
    graph.addVertex(T.label, "EPMulti", "name", "One");
    graph.addVertex(T.label, "EPMulti", "name", "Two");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "x", "EPMulti");
      var stream = gqlPlan.start();
      var count = 0;
      while (stream.hasNext()) {
        Assert.assertNotNull(stream.next());
        count++;
      }
      Assert.assertEquals(2, count);
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SQL plan mode: reset allows re-execution ──

  @Test
  public void sqlPlanMode_reset_allowsReExecution() {
    graph.addVertex(T.label, "EPReset", "name", "R");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPReset");

      var s1 = gqlPlan.start();
      Assert.assertTrue(s1.hasNext());
      s1.next();
      Assert.assertFalse(s1.hasNext());

      gqlPlan.reset();
      var s2 = gqlPlan.start();
      Assert.assertTrue(s2.hasNext());
      s2.next();
      Assert.assertFalse(s2.hasNext());
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SQL plan mode: copy independent ──

  @Test
  public void sqlPlanMode_copy_producesIndependentPlan() {
    graph.addVertex(T.label, "EPCopy", "name", "C");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "b", "EPCopy");
      var copy = gqlPlan.copy();
      Assert.assertNotSame(gqlPlan, copy);

      var stream = copy.start();
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
      copy.close();
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SQL plan mode: close safe ──

  @Test
  public void sqlPlanMode_close_doesNotThrow() {
    graph.addVertex(T.label, "EPClose", "name", "Cl");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPClose");
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SqlStreamAdapter: hasNext auto-closes when exhausted ──

  @Test
  public void sqlStreamAdapter_hasNext_autoClosesWhenExhausted() {
    graph.addVertex(T.label, "EPExhaust", "name", "E");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPExhaust");
      var stream = gqlPlan.start();
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SqlStreamAdapter: hasNext after close ──

  @Test
  public void sqlStreamAdapter_hasNext_afterClose_returnsFalse() {
    graph.addVertex(T.label, "EPHasClose", "name", "X");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPHasClose");
      var stream = gqlPlan.start();
      stream.close();
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── SqlStreamAdapter: next after close throws ──

  @Test(expected = NoSuchElementException.class)
  public void sqlStreamAdapter_next_afterClose_throwsNoSuchElement() {
    graph.addVertex(T.label, "EPNextClose", "name", "Y");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPNextClose");
      var stream = gqlPlan.start();
      stream.close();
      stream.next();
    } finally {
      tx.commit();
    }
  }

  // ── SqlStreamAdapter: close idempotent ──

  @Test
  public void sqlStreamAdapter_close_isIdempotent() {
    graph.addVertex(T.label, "EPIdem", "name", "Z");
    graph.tx().commit();

    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var gqlPlan = buildSqlPlan(session, "a", "EPIdem");
      var stream = gqlPlan.start();
      stream.close();
      stream.close();
      Assert.assertFalse(stream.hasNext());
      gqlPlan.close();
    } finally {
      tx.commit();
    }
  }

  // ── Helper ──

  private static GqlExecutionPlan buildSqlPlan(
      DatabaseSessionEmbedded session, String alias, String className) {
    var pattern = new Pattern();
    var node = new PatternNode();
    node.alias = alias;
    pattern.aliasToNode.put(alias, node);
    var planner = new MatchExecutionPlanner(pattern, Map.of(alias, className));
    var sqlPlan = planner.createExecutionPlan(new BasicCommandContext(session), false);
    return GqlExecutionPlan.forSqlMatchPlan(sqlPlan);
  }
}
