package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateClusterStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var clusterName = "testPlain";
    var result = session.execute("create cluster " + clusterName);
    Assert.assertTrue(session.getClusterIdByName(clusterName) > 0);
    result.close();
  }

  @Test
  public void testExisting() {
    var clazz = session.getMetadata().getSchema().createClass("testExisting");
    var clusterName = session.getClusterNameById(clazz.getClusterIds()[0]);
    try {
      session.execute("create cluster " + clusterName);
      Assert.fail();
    } catch (CommandExecutionException ex) {

    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testWithNumber() {
    var clusterName = "testWithNumber";
    var result = session.execute("create cluster " + clusterName + " id 1000");
    Assert.assertTrue(session.getClusterIdByName(clusterName) > 0);
    Assert.assertNotNull(session.getClusterNameById(1000));

    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    Assert.assertEquals((Object) 1000, next.getProperty("requestedId"));
    result.close();
  }

  @Test
  public void testBlob() {
    var clusterName = "testBlob";
    var result = session.execute("create blob cluster " + clusterName);
    Assert.assertTrue(session.getClusterIdByName(clusterName) > 0);
    Assert.assertTrue(session.getStorage().getClusterIdByName(clusterName) >= 0);
    // TODO test that it's a blob cluster
    result.close();
  }

  @Test
  public void testIfNotExists() {
    var clusterName = "testIfNotExists";
    var result = session.execute("create cluster " + clusterName + " IF NOT EXISTS id 2000");
    Assert.assertTrue(session.getClusterIdByName(clusterName) > 0);
    Assert.assertNotNull(session.getClusterNameById(2000));

    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    Assert.assertEquals((Object) 2000, next.getProperty("requestedId"));
    result.close();

    result = session.execute("create cluster " + clusterName + " IF NOT EXISTS id 1000");
    Assert.assertFalse(result.hasNext());
    result.close();
  }
}
