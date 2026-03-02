package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for GqlExecutionContext record: constructors and getParameter.
 */
public class GqlExecutionContextTest extends GraphBaseTest {

  @Test
  public void contextWithTwoArgs_usesEmptyParameters() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session);
    Assert.assertSame(graphInternal, ctx.graph());
    Assert.assertSame(session, ctx.session());
    Assert.assertTrue(ctx.parameters().isEmpty());
    Assert.assertNull(ctx.getParameter("name"));
    tx.commit();
  }

  @Test
  public void contextWithParameters_getParameter_returnsValue() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    Map<String, Object> params = new HashMap<>();
    params.put("name", "Maria");
    params.put("age", 30);
    var ctx = new GqlExecutionContext(graphInternal, session, params);
    Assert.assertEquals("Maria", ctx.getParameter("name"));
    Assert.assertEquals(30, ctx.getParameter("age"));
    Assert.assertNull(ctx.getParameter("missing"));
    tx.commit();
  }

  @Test
  public void contextWithEmptyMap_usesEmptyParameters() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var ctx = new GqlExecutionContext(graphInternal, session, new HashMap<>());
    Assert.assertTrue(ctx.parameters().isEmpty());
    tx.commit();
  }
}
