package com.jetbrains.youtrack.db.internal.server.metadata;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RemoteMetadataReloadTest {

  private static final String SERVER_DIRECTORY = "./target/metadata-reload";
  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;
  private DatabaseSessionInternal database;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();

    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        RemoteMetadataReloadTest.class.getSimpleName());
    database =
        (DatabaseSessionInternal)
            youTrackDB.open(RemoteMetadataReloadTest.class.getSimpleName(), "admin", "admin");
  }

  @After
  public void after() {
    database.close();
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void testStorageUpdate() throws InterruptedException {
    database.execute("ALTER DATABASE LOCALE_LANGUAGE  ?", Locale.GERMANY.getLanguage());
    assertEquals(
        database.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE), Locale.GERMANY.getLanguage());
  }

  @Test
  public void testSchemaUpdate() throws InterruptedException {
    database.execute(" create class X");
    assertTrue(database.getMetadata().getSchema().existsClass("X"));
  }

  @Test
  public void testSequencesUpdate() throws InterruptedException {
    database.begin();
    database.execute("CREATE SEQUENCE test TYPE CACHED");
    database.commit();

    assertNotNull(database.getMetadata().getSequenceLibrary().getSequence("test"));
  }
}
