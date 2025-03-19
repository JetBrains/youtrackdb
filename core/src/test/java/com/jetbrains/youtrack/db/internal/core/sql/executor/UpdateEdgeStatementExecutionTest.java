package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class UpdateEdgeStatementExecutionTest extends DbTestBase {

  @Test
  public void testUpdateEdge() {

    session.execute("create class V1 extends V");

    session.execute("create class E1 extends E");

    // VERTEXES

    session.begin();
    Entity v1;
    try (var res1 = session.execute("create vertex")) {
      var r = res1.next();
      Assert.assertEquals("V", r.getProperty("@class"));
      v1 = r.asEntityOrNull();
    }
    session.commit();

    session.begin();
    Entity v2;
    try (var res2 = session.execute("create vertex V1")) {
      var r = res2.next();
      Assert.assertEquals("V1", r.getProperty("@class"));
      v2 = r.asEntityOrNull();
    }
    session.commit();

    session.begin();
    Entity v3;
    try (var res3 = session.execute("create vertex set vid = 'v3', brand = 'fiat'")) {
      var r = res3.next();
      Assert.assertEquals("V", r.getProperty("@class"));
      Assert.assertEquals("fiat", r.getProperty("brand"));
      v3 = r.asEntityOrNull();
    }
    session.commit();

    session.begin();
    Entity v4;
    try (var res4 =
        session.execute("create vertex V1 set vid = 'v4',  brand = 'fiat',name = 'wow'")) {
      var r = res4.next();
      Assert.assertEquals("V1", r.getProperty("@class"));
      Assert.assertEquals("fiat", r.getProperty("brand"));
      Assert.assertEquals("wow", r.getProperty("name"));
      v4 = r.asEntityOrNull();
    }
    session.commit();

    session.begin();
    var edges =
        session.execute("create edge E1 from " + v1.getIdentity() + " to " + v2.getIdentity());

    Assert.assertTrue(edges.hasNext());
    var edge = edges.next();
    Assert.assertFalse(edges.hasNext());
    Assert.assertEquals("E1",
        ((EntityImpl) edge.asEntityOrNull().getRecord(session)).getSchemaClassName());
    edges.close();
    session.commit();
    session.begin();
    session.execute(
        "update edge E1 set out = "
            + v3.getIdentity()
            + ", in = "
            + v4.getIdentity()
            + " where @rid = "
            + edge.asEntityOrNull().getIdentity());
    session.commit();

    session.begin();
    var result = session.query("select expand(out('E1')) from " + v3.getIdentity());
    Assert.assertTrue(result.hasNext());
    var vertex4 = result.next();
    Assert.assertEquals("v4", vertex4.getProperty("vid"));
    Assert.assertFalse(result.hasNext());
    result.close();

    result = session.query("select expand(in('E1')) from " + v4.getIdentity());
    Assert.assertTrue(result.hasNext());
    var vertex3 = result.next();
    Assert.assertEquals("v3", vertex3.getProperty("vid"));
    Assert.assertFalse(result.hasNext());
    result.close();

    result = session.query("select expand(out('E1')) from " + v1.getIdentity());
    Assert.assertFalse(result.hasNext());
    result.close();

    result = session.query("select expand(in('E1')) from " + v2.getIdentity());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUpdateEdgeOfTypeE() {
    // issue #6378

    session.begin();
    var v1 = session.newVertex();
    var v2 = session.newVertex();
    var v3 = session.newVertex();
    session.commit();

    session.begin();
    var edges =
        session.execute("create edge E from " + v1.getIdentity() + " to " + v2.getIdentity());
    session.commit();
    var edge = edges.next();

    session.begin();
    session.execute(
            "UPDATE EDGE " + edge.asEntityOrNull().getIdentity() + " SET in = " + v3.getIdentity())
        .close();
    session.commit();
    edges.close();

    session.begin();
    var result = session.query("select expand(out()) from " + v1.getIdentity());

    Assert.assertEquals(result.next().getIdentity(), v3.getIdentity());
    result.close();

    result = session.query("select expand(in()) from " + v3.getIdentity());
    Assert.assertEquals(result.next().getIdentity(), v1.getIdentity());
    result.close();

    result = session.execute("select expand(in()) from " + v2.getIdentity());
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }
}
