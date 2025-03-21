package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class BaseServerMemoryDatabase {

  protected DatabaseSessionInternal session;
  protected YouTrackDB context;
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

    context = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    context
        .execute(
            "create database "
                + name.getMethodName()
                + " memory users(admin identified by 'adminpwd' role admin) ")
        .close();
    session = (DatabaseSessionInternal) context.open(name.getMethodName(), "admin", "adminpwd");
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
