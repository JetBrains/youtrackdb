package com.jetbrains.youtrack.db.internal;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class DbTestBase {
  private static final AtomicLong counter = new AtomicLong();
  private static final ConcurrentHashMap<Class<?>, Long> ids = new ConcurrentHashMap<>();

  protected DatabaseSessionInternal session;
  protected SessionPool pool;
  protected YouTrackDBImpl context;

  @Rule
  public TestName name = new TestName();
  protected String databaseName;
  protected DatabaseType dbType;

  protected String adminUser = "admin";
  protected String adminPassword = "adminpwd";

  protected String readerUser = "reader";
  protected String readerPassword = "readerpwd";

  @Before
  public void beforeTest() throws Exception {
    context = createContext();
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

    context.create(this.databaseName, dbType,
        adminUser, adminPassword, "admin", readerUser, readerPassword, "reader");
    pool = context.cachedPool(this.databaseName, adminUser, adminPassword);

    session = (DatabaseSessionInternal) context.open(this.databaseName, "admin", "adminpwd");
  }

  public static String embeddedDBUrl(Class<?> testClass) {
    return "embedded:" + getBaseDirectoryPath(testClass);
  }

  public static String getBaseDirectoryPath(Class<?> testClass) {
    final String buildDirectory = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath().toString();
    return
        buildDirectory + File.separator + "databases" + File.separator + testClass
            .getSimpleName() + "-" + getTestId(testClass);
  }

  private static long getTestId(Class<?> testClass) {
    return ids.computeIfAbsent(testClass, k -> counter.incrementAndGet());
  }

  protected YouTrackDBImpl createContext() {
    var directoryPath = getBaseDirectoryPath(getClass());
    var builder = YouTrackDBConfig.builder();
    var config = createConfig((YouTrackDBConfigBuilderImpl) builder);

    return (YouTrackDBImpl) YourTracks.embedded(directoryPath, config);
  }

  protected DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.PLOCAL;
    }

    return DatabaseType.MEMORY;
  }

  @SuppressWarnings("SameParameterValue")
  protected void reOpen(String user, String password) {
    if (!pool.isClosed()) {
      pool.close();
      this.pool = context.cachedPool(this.databaseName, user, password);
    }

    if (!session.isClosed()) {
      session.activateOnCurrentThread();
      session.close();
      this.session = (DatabaseSessionInternal) context.open(this.databaseName, user, password);
    }
  }

  public DatabaseSessionInternal openDatabase() {
    return (DatabaseSessionInternal) context.open(this.databaseName, adminUser, adminPassword);
  }

  public DatabaseSessionInternal openDatabase(String user, String password) {
    return (DatabaseSessionInternal) context.open(this.databaseName, user, password);
  }

  protected YouTrackDBConfig createConfig(YouTrackDBConfigBuilderImpl builder) {
    return builder.build();
  }

  @After
  public void afterTest() {
    dropDatabase();
    context.close();
  }

  public void dropDatabase() {
    if (!session.isClosed()) {
      session.activateOnCurrentThread();
      session.close();
    }
    if (!pool.isClosed()) {
      pool.close();
    }

    if (context.exists(this.databaseName)) {
      context.drop(databaseName);
    }
  }

  public static void assertWithTimeout(DatabaseSession session, Runnable runnable)
      throws Exception {
    for (var i = 0; i < 30 * 60 * 10; i++) {
      try {
        session.begin();
        runnable.run();
        session.commit();
        return;
      } catch (AssertionError e) {
        session.rollback();
        Thread.sleep(100);
      } catch (Exception e) {
        session.rollback();
        throw e;
      }
    }

    runnable.run();
  }
}
