package com.jetbrains.youtrackdb.internal;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class DbTestBase {

  public static final String ADMIN_PASSWORD = "adminpwd";
  public static final String READER_PASSWORD = "readerpwd";

  private static final AtomicLong counter = new AtomicLong(System.nanoTime());
  private static final ConcurrentHashMap<Class<?>, Long> ids = new ConcurrentHashMap<>();

  protected DatabaseSessionEmbedded session;
  protected SessionPool pool;
  protected YouTrackDBImpl youTrackDB;

  @Rule
  public TestName name = new TestName();
  protected String databaseName;
  protected DatabaseType dbType;

  protected String adminUser = "admin";
  protected String adminPassword = ADMIN_PASSWORD;

  protected String readerUser = "reader";
  protected String readerPassword = READER_PASSWORD;
  protected String dbPath;

  @Before
  public void beforeTest() throws Exception {
    youTrackDB = createContext();
    var dbName = name.getMethodName();

    dbName = dbName.replace('[', '_');
    dbName = dbName.replace(']', '_');
    this.databaseName = dbName;

    dbType = calculateDbType();
    createDatabase(dbType);
  }

  public void createDatabase() {
    createDatabase(dbType);
  }

  protected void createDatabase(DatabaseType dbType) {
    if (session != null && !session.isClosed()) {
      session.close();
    }
    if (pool != null && !pool.isClosed()) {
      pool.close();
    }

    var config = createConfig();
    youTrackDB.create(this.databaseName, dbType, config,
        adminUser, adminPassword, "admin", readerUser, readerPassword, "reader");
    var builder = YouTrackDBConfig.builder().fromApacheConfiguration(config);
    pool = youTrackDB.cachedPool(this.databaseName, adminUser, adminPassword, builder.build());

    session = openDatabase(config);
  }

  private DatabaseSessionEmbedded openDatabase(Configuration config) {
    var build = YouTrackDBConfig.builder().fromApacheConfiguration(config);
    return youTrackDB.open(this.databaseName, "admin", "adminpwd", build.build());
  }


  public static String embeddedDBUrl(Class<?> testClass) {
    return "embedded:" + getBaseDirectoryPathStr(testClass);
  }

  public static String getBaseDirectoryPathStr(Class<?> testClass) {
    final var buildDirectory = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath().toString();
    return
        buildDirectory + File.separator + "databases" + File.separator + testClass
            .getSimpleName() + "-" + getTestId(testClass);
  }

  public static YouTrackDBImpl createYTDBManagerAndDb(String dbName, DatabaseType dbType,
      Class<?> testClass) {
    var path = getBaseDirectoryPath(testClass);
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(path);
    youTrackDB.create(dbName, dbType,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    return youTrackDB;
  }

  public static Path getBaseDirectoryPath(Class<?> testClass) {
    final var buildDirectory = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath();
    return
        buildDirectory.resolve("databases").resolve(testClass.getSimpleName() +
            "-" + getTestId(testClass));
  }

  private static long getTestId(Class<?> testClass) {
    return ids.computeIfAbsent(testClass, k -> counter.incrementAndGet());
  }

  protected YouTrackDBImpl createContext() {
    dbPath = getBaseDirectoryPathStr(getClass());

    var config = createConfig();
    return (YouTrackDBImpl) YourTracks.instance(dbPath, config);
  }

  protected DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }

  @SuppressWarnings("SameParameterValue")
  protected void reOpen(String user, String password) {
    if (!pool.isClosed()) {
      pool.close();
      this.pool = youTrackDB.cachedPool(this.databaseName, user, password);
    }

    if (!session.isClosed()) {
      session.activateOnCurrentThread();
      session.close();
      this.session = youTrackDB.open(this.databaseName, user, password);
    }
  }

  public DatabaseSessionEmbedded openDatabase() {
    return youTrackDB.open(this.databaseName, adminUser, adminPassword);
  }

  public DatabaseSessionEmbedded openDatabase(String user, String password) {
    return youTrackDB.open(this.databaseName, user, password);
  }

  protected Configuration createConfig() {
    return new BaseConfiguration();
  }

  @After
  public void afterTest() {
    dropDatabase();
    youTrackDB.close();
  }

  public void dropDatabase() {
    if (!session.isClosed()) {
      session.activateOnCurrentThread();
      session.close();
    }
    if (!pool.isClosed()) {
      pool.close();
    }

    if (youTrackDB.exists(this.databaseName)) {
      youTrackDB.drop(databaseName);
    }
  }

  public static void assertWithTimeout(DatabaseSessionEmbedded session, Runnable runnable)
      throws Exception {
    for (var i = 0; i < 30 * 60 * 10; i++) {
      var tx = session.begin();
      try {
        runnable.run();
        tx.commit();
        return;
      } catch (AssertionError e) {
        tx.rollback();
        Thread.sleep(100);
      } catch (Exception e) {
        tx.rollback();
        throw e;
      }
    }

    runnable.run();
  }

  public void withOverriddenConfig(
      GlobalConfiguration parameter,
      Object value,
      Consumer<DatabaseSessionEmbedded> action) {

    var oldValue = parameter.getValue();
    DatabaseSessionEmbedded session = null;
    try {
      parameter.setValue(value);
      session = openDatabase(createConfig());
      action.accept(session);
    } finally {
      parameter.setValue(oldValue);
      if (session != null) {
        session.close();
      }
    }
  }
}
