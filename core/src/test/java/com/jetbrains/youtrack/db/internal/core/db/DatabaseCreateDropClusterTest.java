package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class DatabaseCreateDropClusterTest extends DbTestBase {

  @Test
  public void createDropCluster() {
    session.addCluster("test");
    Assert.assertNotEquals(-1, session.getClusterIdByName("test"));
    session.dropCluster("test");
    Assert.assertEquals(-1, session.getClusterIdByName("test"));
  }
}
