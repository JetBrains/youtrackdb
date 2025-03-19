package com.jetbrains.youtrack.db.auto;

import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 9/15/14
 */
@Test
public class DBMethodsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public DBMethodsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void testAddCluster() {
    session.addCluster("addClusterTest");

    Assert.assertTrue(session.existsCluster("addClusterTest"));
    Assert.assertTrue(session.existsCluster("addclUstertESt"));
  }
}
