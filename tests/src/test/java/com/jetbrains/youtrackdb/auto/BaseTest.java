package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import java.util.Locale;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

@Test
public abstract class BaseTest {

  public static final String SERVER_PASSWORD =
      "D2AFD02F20640EC8B7A5140F34FCA49D2289DB1F0D0598BB9DE8AAA75A0792F3";
  public static final String DEFAULT_DB_NAME = "demo";

  protected DatabaseSessionEmbedded session;
  protected String dbName;

  protected DatabaseType databaseType;
  public static YouTrackDBImpl youTrackDB;


  public BaseTest() {
    var config = System.getProperty("youtrackdb.test.env");

    if ("ci".equals(config) || "release".equals(config)) {
      databaseType = DatabaseType.DISK;
    }

    if (databaseType == null) {
      databaseType = DatabaseType.MEMORY;
    }

    this.dbName = DEFAULT_DB_NAME;
  }

  public BaseTest(String prefix) {
    this();
    this.dbName = prefix + DEFAULT_DB_NAME;
  }

  @BeforeSuite
  public void beforeSuite() {
    try {
      createDatabase();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot create database in test " + this.getClass().getSimpleName(), e);
    }
  }

  private YouTrackDBImpl getYouTrackDB() {
    if (youTrackDB == null || !youTrackDB.isOpen()) {
      final var buildDirectory = System.getProperty("buildDirectory", ".");
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory + "/test-db",
          createConfig());
    }

    return youTrackDB;
  }


  protected void createDatabase(String dbName) {
    getYouTrackDB().createIfNotExists(
        dbName,
        databaseType,
        "admin",
        "admin",
        "admin",
        "writer",
        "writer",
        "writer",
        "reader",
        "reader",
        "reader");
  }

  protected void createDatabase() {
    createDatabase(dbName);
  }

  protected void dropDatabase(String dbName) {
    var youTrackDB = getYouTrackDB();

    if (youTrackDB.exists(dbName)) {
      youTrackDB.drop(dbName);
    }
  }

  @AfterSuite
  public void afterSuite() {
    try {
      if (youTrackDB != null) {
        youTrackDB.close();
        youTrackDB = null;
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot close database instance in test " + this.getClass().getSimpleName(), e);
    }
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    createDatabase();
    newSession();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    newSession();
  }

  private void newSession() {
    try {
      if (session == null) {
        session = createSessionInstance();
      }

      session.activateOnCurrentThread();
      if (session.isClosed()) {
        session = createSessionInstance();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot open database session in test " + this.getClass().getSimpleName(), e);
    }
  }

  @AfterClass
  public void afterClass() throws Exception {
    closeSession();
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    closeSession();
  }

  private void closeSession() {
    try {
      if (!session.isClosed()) {
        session.activateOnCurrentThread();
        session.close();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot close database session in test " + this.getClass().getSimpleName(), e);
    }
  }

  protected abstract DatabaseSessionEmbedded createSessionInstance(
      YouTrackDBImpl youTrackDB, String dbName, String user, String password);

  protected final DatabaseSessionEmbedded createSessionInstance() {
    return createSessionInstance("admin", "admin");
  }

  protected final DatabaseSessionEmbedded createSessionInstance(String dbName) {
    return createSessionInstance(dbName, "admin", "admin");
  }

  protected final DatabaseSessionEmbedded createSessionInstance(String dbName, String user,
      String password) {
    return createSessionInstance(getYouTrackDB(), dbName, user, password);
  }

  protected final DatabaseSessionEmbedded createSessionInstance(String user, String password) {
    return createSessionInstance(dbName, user, password);
  }

  protected DatabaseSessionEmbedded acquireSession() {
    return acquireSession(dbName);
  }

  protected DatabaseSessionEmbedded acquireSession(String dbName) {
    return (DatabaseSessionEmbedded) getYouTrackDB().open(dbName, "admin", "admin");
  }

  @SuppressWarnings("MethodMayBeStatic")
  protected Configuration createConfig() {
    return new BaseConfiguration();
  }

  protected final String getStorageType() {
    return databaseType.toString().toLowerCase(Locale.ROOT);
  }

  protected Index getIndex(final String indexName) {
    final DatabaseSessionEmbedded db = this.session;

    return db.getSharedContext().getIndexManager().getIndex(indexName);
  }
}
