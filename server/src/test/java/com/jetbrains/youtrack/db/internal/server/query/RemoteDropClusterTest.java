package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import org.junit.Test;

/**
 *
 */
public class RemoteDropClusterTest extends BaseServerMemoryDatabase {

  public void beforeTest() {
    GlobalConfiguration.CLASS_CLUSTERS_COUNT.setValue(1);
    super.beforeTest();
  }

  @Test
  public void simpleDropCluster() {
    var cl = session.addCluster("one");
    session.dropCluster(cl);
  }

  @Test
  public void simpleDropClusterTruncate() {
    var cl = session.addCluster("one");
    session.dropCluster(cl);
  }

  @Test
  public void simpleDropClusterName() {
    session.addCluster("one");
    session.dropCluster("one");
  }

  @Test
  public void simpleDropClusterNameTruncate() {
    session.addCluster("one");
    session.dropCluster("one");
  }
}
