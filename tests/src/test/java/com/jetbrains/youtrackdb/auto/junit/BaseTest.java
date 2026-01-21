/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import java.util.Locale;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.junit.After;
import org.junit.Before;

/**
 * JUnit 4 migration of BaseTest. Original test class: com.jetbrains.youtrackdb.auto.BaseTest
 * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java
 * <p>
 * Note: In JUnit 4, @BeforeClass/@AfterClass methods must be static. Suite-level setup
 * (@BeforeSuite/@AfterSuite in TestNG) is handled by GlobalTestSetup class which is registered in
 * AllJUnitTests suite.
 */
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

  /**
   * Suite-level setup - creates database if not exists. Called from GlobalTestSetup or manually
   * before running tests.
   */
  public void setupSuite() {
    try {
      createDatabase();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot create database in test " + this.getClass().getSimpleName(), e);
    }
  }

  protected YouTrackDBImpl getYouTrackDB() {
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

  /**
   * Suite-level teardown - closes YouTrackDB instance. Called from GlobalTestSetup or manually
   * after running tests.
   */
  public static void teardownSuite() {
    try {
      if (youTrackDB != null) {
        youTrackDB.close();
        youTrackDB = null;
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot close database instance", e);
    }
  }

  /**
   * Original method: beforeClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java:114
   */
  public void beforeClass() throws Exception {
    createDatabase();
    newSession();
  }

  /**
   * Original method: beforeMethod Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java:120
   */
  @Before
  public void beforeMethod() throws Exception {
    newSession();
  }

  protected void newSession() {
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

  /**
   * Original method: afterClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java:141
   */
  public void afterClass() throws Exception {
    closeSession();
  }

  /**
   * Original method: afterMethod Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java:146
   */
  @After
  public void afterMethod() throws Exception {
    closeSession();
  }

  protected void closeSession() {
    try {
      if (session != null && !session.isClosed()) {
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
    final DatabaseSessionInternal db = this.session;

    return db.getSharedContext().getIndexManager().getIndex(indexName);
  }
}
