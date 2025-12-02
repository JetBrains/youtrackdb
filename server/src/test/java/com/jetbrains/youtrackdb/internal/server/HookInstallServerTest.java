package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.internal.server.config.ServerConfigurationManager;
import com.jetbrains.youtrackdb.internal.server.config.ServerHookConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Before;

public class HookInstallServerTest {

  private static final String SERVER_DIRECTORY = "./target/dbfactory";

  public static class MyHook extends EntityHookAbstract {

    public MyHook() {
      super();
    }

    @Override
    public void onBeforeEntityCreate(Entity entity) {
      count++;
    }
  }

  private static int count = 0;
  private YouTrackDBServer server;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);

    var ret =
        new ServerConfigurationManager(
            this.getClass()
                .getClassLoader()
                .getResourceAsStream(
                    "com/jetbrains/youtrackdb/internal/server/network/youtrackdb-server-config.xml"));
    var hc = new ServerHookConfiguration();
    hc.clazz = MyHook.class.getName();
    ret.getConfiguration().hooks = new ArrayList<>();
    ret.getConfiguration().hooks.add(hc);
    server.startup(ret.getConfiguration());
    server.activate();

    try (var youTrackDB = YourTracks.instance("localhost", "root", "root")) {
      youTrackDB.createIfNotExists("test", DatabaseType.MEMORY, "admin", "admin", "admin");
    }
  }

  @After
  public void after() throws IOException {
    try (var youTrackDB = YourTracks.instance("localhost", "root", "root")) {
      youTrackDB.drop("test");
    }
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
