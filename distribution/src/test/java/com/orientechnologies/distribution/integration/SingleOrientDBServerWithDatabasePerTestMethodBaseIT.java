package com.orientechnologies.distribution.integration;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;

public abstract class SingleOrientDBServerWithDatabasePerTestMethodBaseIT
    extends SingleYouTrackDBServerBaseIT {

  @Before
  public void setupOrientDBAndPool() throws Exception {

    String dbName = name.getMethodName();

    String serverUrl =
        "remote:" + container.getContainerIpAddress() + ":" + container.getMappedPort(2424);

    youTrackDB = new YouTrackDBImpl(serverUrl, "root", "root", YouTrackDBConfig.defaultConfig());

    if (youTrackDB.exists(dbName)) {
      youTrackDB.drop(dbName);
    }
    youTrackDB.createIfNotExists(dbName, DatabaseType.DISK);

    pool = youTrackDB.cachedPool(dbName, "admin", "admin");
  }

  @After
  public void tearDown() {
    pool.close();
    youTrackDB.close();
  }
}
