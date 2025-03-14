package com.jetbrains.youtrack.db.internal.server.metadata;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
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

/**
 *
 */
public class MetadataPushTest {

  private static final String SERVER_DIRECTORY = "./target/metadata-push";
  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;
  private DatabaseSession database;

  private YouTrackDB secondYouTrackDB;
  private DatabaseSessionInternal secondDatabase;

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
        MetadataPushTest.class.getSimpleName());
    database = youTrackDB.open(MetadataPushTest.class.getSimpleName(), "admin", "admin");

    secondYouTrackDB = new YouTrackDBImpl("remote:localhost",
        YouTrackDBConfig.defaultConfig());
    secondDatabase =
        (DatabaseSessionInternal)
            youTrackDB.open(MetadataPushTest.class.getSimpleName(), "admin", "admin");
  }

  @After
  public void after() {
    database.close();
    youTrackDB.close();
    secondDatabase.close();
    secondYouTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void testStorageUpdate() throws Exception {
    database.command(" ALTER DATABASE LOCALE_LANGUAGE  ?", Locale.GERMANY.getLanguage());
    // Push done in background for now, do not guarantee update before command return.
    secondDatabase.activateOnCurrentThread();
    DbTestBase.assertWithTimeout(
        secondDatabase,
        () -> {
          secondDatabase.activateOnCurrentThread();
          assertEquals(
              secondDatabase.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE),
              Locale.GERMANY.getLanguage());
        });
  }

  @Test
  public void testSchemaUpdate() throws Exception {
    database.command(" create class X");

    // Push done in background for now, do not guarantee update before command return.
    secondDatabase.activateOnCurrentThread();
    DbTestBase.assertWithTimeout(
        secondDatabase,
        () -> {
          secondDatabase.activateOnCurrentThread();
          assertTrue(secondDatabase.getMetadata().getSchema().existsClass("X"));
        });
  }

  @Test
  public void testSequencesUpdate() throws Exception {
    database.begin();
    database.command("CREATE SEQUENCE test TYPE CACHED");
    database.commit();
    // Push done in background for now, do not guarantee update before command return.
    secondDatabase.activateOnCurrentThread();
    DbTestBase.assertWithTimeout(
        secondDatabase,
        () -> {
          secondDatabase.activateOnCurrentThread();
          assertNotNull(secondDatabase.getMetadata().getSequenceLibrary().getSequence("test"));
        });
  }
}
