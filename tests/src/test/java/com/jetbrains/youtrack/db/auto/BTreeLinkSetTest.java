package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

public class BTreeLinkSetTest extends BaseDBTest {

  private int topThreshold;
  private int bottomThreshold;

  @Parameters(value = "remote")
  public BTreeLinkSetTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    if (session.isRemote()) {
      var server =
          new ServerAdmin(session.getURL())
              .connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, -1);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);
    super.beforeMethod();
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    super.afterMethod();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);

    if (session.isRemote()) {
      var server =
          new ServerAdmin(session.getURL())
              .connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, topThreshold);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, bottomThreshold);
      server.close();
    }
  }
}
