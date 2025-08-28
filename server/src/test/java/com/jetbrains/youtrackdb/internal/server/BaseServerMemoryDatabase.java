package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class BaseServerMemoryDatabase {

  protected RemoteDatabaseSession session;
  protected YouTrackDBRemoteImpl context;
  @Rule
  public TestName name = new TestName();
  protected YouTrackDBServer server;

  @Before
  public void beforeTest() {
    server = new YouTrackDBServer(false);
    try {
      server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
      server.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    context = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root");
    context
        .execute(
            "create database "
                + name.getMethodName()
                + " memory users(admin identified by 'adminpwd' role admin) ")
        .close();
    session = context.open(name.getMethodName(), "admin", "adminpwd");
  }

  @After
  public void afterTest() {
    session.close();
    context.drop(name.getMethodName());
    context.close();
    var directory = server.getDatabaseDirectory();
    server.shutdown();
    FileUtils.deleteRecursively(new File(directory));
  }
}
