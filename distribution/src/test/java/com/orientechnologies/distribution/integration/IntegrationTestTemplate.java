package com.orientechnologies.distribution.integration;

import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;

/**
 * This abstract class is a template to be extended to implements integration tests.
 *
 * <p>
 *
 * <p>
 */
public abstract class IntegrationTestTemplate extends SingleYouTrackDBServerBaseIT {

  protected DatabaseSessionInternal session;

  @Before
  public void setUp() throws Exception {

    final var serverUrl =
        "remote:" + container.getContainerIpAddress() + ":" + container.getMappedPort(2424);

    youTrackDB = new YouTrackDBImpl(serverUrl, "root", "root", YouTrackDBConfig.defaultConfig());

    pool = youTrackDB.cachedPool("demodb", "admin", "admin");

    session = (DatabaseSessionInternal) pool.acquire();
  }


  @After
  public void tearDown() {
    session.activateOnCurrentThread();
    session.close();
    pool.close();
    youTrackDB.close();
  }
}
