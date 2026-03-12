package com.jetbrains.youtrackdb.junit;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base test class for the ordered test suite.
 *
 * <p>Uses {@link TestInstance.Lifecycle#PER_CLASS} so that {@code @BeforeAll} /
 * {@code @AfterAll} can be non-static and access instance fields.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(SuiteLifecycleExtension.class)
public abstract class BaseJUnit5Test {

  public static final String SERVER_PASSWORD =
      "D2AFD02F20640EC8B7A5140F34FCA49D2289DB1F0D0598BB9DE8AAA75A0792F3";
  public static final String DEFAULT_DB_NAME = "demo";

  protected DatabaseSessionEmbedded session;
  protected String dbName;
  protected DatabaseType databaseType;

  protected BaseJUnit5Test() {
    var config = System.getProperty("youtrackdb.test.env");

    if ("ci".equals(config) || "release".equals(config)) {
      databaseType = DatabaseType.DISK;
    }

    if (databaseType == null) {
      databaseType = DatabaseType.MEMORY;
    }

    this.dbName = DEFAULT_DB_NAME;
  }

  protected BaseJUnit5Test(String prefix) {
    this();
    this.dbName = prefix + DEFAULT_DB_NAME;
  }

  @BeforeAll
  void beforeAll() throws Exception {
    createDatabase();
    newSession();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    newSession();
  }

  @AfterAll
  void afterAll() throws Exception {
    closeSession();
  }

  @AfterEach
  void afterEach() throws Exception {
    closeSession();
  }

  protected final YouTrackDBImpl getYouTrackDB() {
    return SuiteLifecycleExtension.getYouTrackDB();
  }

  protected void createDatabase(String dbName) {
    getYouTrackDB().createIfNotExists(
        dbName,
        databaseType,
        "admin", "admin", "admin",
        "writer", "writer", "writer",
        "reader", "reader", "reader");
  }

  protected void createDatabase() {
    createDatabase(dbName);
  }

  protected void dropDatabase(String dbName) {
    var ytdb = getYouTrackDB();
    if (ytdb.exists(dbName)) {
      ytdb.drop(dbName);
    }
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
          "Cannot open database session in test " + this.getClass().getSimpleName(),
          e);
    }
  }

  private void closeSession() {
    try {
      if (session != null && !session.isClosed()) {
        session.activateOnCurrentThread();
        session.close();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot close database session in test " + this.getClass().getSimpleName(),
          e);
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

  protected final DatabaseSessionEmbedded createSessionInstance(
      String dbName, String user, String password) {
    return createSessionInstance(getYouTrackDB(), dbName, user, password);
  }

  protected final DatabaseSessionEmbedded createSessionInstance(
      String user, String password) {
    return createSessionInstance(dbName, user, password);
  }

  protected DatabaseSessionEmbedded acquireSession() {
    return acquireSession(dbName);
  }

  protected DatabaseSessionEmbedded acquireSession(String dbName) {
    return (DatabaseSessionEmbedded) getYouTrackDB().open(dbName, "admin", "admin");
  }

  @SuppressWarnings("MethodMayBeStatic")
  protected org.apache.commons.configuration2.Configuration createConfig() {
    return new org.apache.commons.configuration2.BaseConfiguration();
  }

  protected final String getStorageType() {
    return databaseType.toString().toLowerCase(Locale.ROOT);
  }

  protected Index getIndex(String indexName) {
    return session.getSharedContext().getIndexManager().getIndex(indexName);
  }
}
