package com.jetbrains.youtrackdb.internal.server.network;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class BinaryProtocolAnyResultTest {

  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();
  }

  @Test
  @Ignore
  public void scriptReturnValueTest() throws IOException {
    var youTrackDB =
        (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root", "root");

    if (youTrackDB.exists("test")) {
      youTrackDB.drop("test");
    }

    youTrackDB.execute(
        "create database test memory users (admin identified by 'admin' role admin)");
    var db = youTrackDB.open("test", "admin", "admin");

    Object res = db.computeScript("SQL", " let $one = select from OUser limit 1; return [$one,1]");

    assertTrue(res instanceof List);
    assertTrue(((List) res).get(0) instanceof Collection);
    assertTrue(((List) res).get(1) instanceof Integer);
    db.close();

    youTrackDB.drop("test");
    youTrackDB.close();
  }

  @After
  public void after() {
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }
}
