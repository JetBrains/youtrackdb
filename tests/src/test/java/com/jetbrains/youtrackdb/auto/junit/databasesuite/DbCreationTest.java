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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import java.io.File;
import java.util.Locale;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for database creation, opening, and basic operations.
 *
 * <p><b>Suite Position:</b> This is the <b>first</b> test in {@link DatabaseTestSuite} and
 * initializes the YouTrackDB instance. It does not extend {@link BaseDBTest} as it manages
 * its own database lifecycle.</p>
 *
 * <p><b>Standalone Execution:</b> This test can be run independently as it creates its own
 * test database (DbCreationTest) separate from the main demo database used by other tests.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.DbCreationTest}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DbCreationTest {

  private static final String DB_NAME = "DbCreationTest";

  private static YouTrackDBImpl youTrackDB;

  @BeforeClass
  public static void beforeClass() {
    initYTDB();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.close();
  }

  private static void initYTDB() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory + "/test-db");
  }

  /**
   * Original test method: testDbCreationDefault Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:54
   */
  @Test
  public void test01_DbCreationDefault() {
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }

    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
  }

  /**
   * Original test method: testDbExists Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:63
   */
  @Test
  public void test02_DbExists() {
    Assert.assertTrue("Database " + DB_NAME + " not found", youTrackDB.exists(DB_NAME));
  }

  /**
   * Original test method: testDbOpen Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:68
   */
  @Test
  public void test03_DbOpen() {
    var database = youTrackDB.open(DB_NAME, "admin", "admin");
    Assert.assertNotNull(database.getDatabaseName());
    database.close();
  }

  /**
   * Original test method: testDbOpenWithLastAsSlash Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:75
   */
  @Test
  public void test04_DbOpenWithLastAsSlash() {
    youTrackDB.close();

    final var buildDirectory = System.getProperty("buildDirectory", ".");
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(buildDirectory + "/test-db/")) {
      var database = ytdb.open(DB_NAME, "admin", "admin");
      database.close();
    }

    initYTDB();
  }

  private static String calculateDirectory() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    return buildDirectory + "/test-db";
  }

  /**
   * Original test method: testChangeLocale Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:93
   */
  @Test
  public void test05_ChangeLocale() {
    try (var database = (DatabaseSessionInternal) youTrackDB.open(DB_NAME, "admin", "admin")) {
      database.execute(" ALTER DATABASE LOCALE_LANGUAGE  ?", Locale.GERMANY.getLanguage()).close();
      database.execute(" ALTER DATABASE LOCALE_COUNTRY  ?", Locale.GERMANY.getCountry()).close();

      Assert.assertEquals(
          Locale.GERMANY.getLanguage(),
          database.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE));
      Assert.assertEquals(
          Locale.GERMANY.getCountry(),
          database.get(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY));
      database.set(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.ENGLISH.getCountry());
      database.set(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE, Locale.ENGLISH.getLanguage());
      Assert.assertEquals(
          Locale.ENGLISH.getCountry(),
          database.get(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY));
      Assert.assertEquals(
          Locale.ENGLISH.getLanguage(),
          database.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE));
    }
  }

  /**
   * Original test method: testRoles Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:112
   */
  @Test
  public void test06_Roles() {
    try (var database = youTrackDB.open(DB_NAME, "admin", "admin")) {
      var tx = database.begin();
      tx.query("select from ORole where name = 'admin'").close();
      tx.commit();
    }
  }

  /**
   * Original test method: testSubFolderDbCreate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:121
   */
  @Test
  public void test07_SubFolderDbCreate() {
    var directory = calculateDirectory();

    var ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    if (ytdb.exists("sub")) {
      ytdb.drop("sub");
    }

    ytdb.create("sub", DatabaseType.DISK, "admin", "admin", "admin");
    var db = ytdb.open("sub", "admin", "admin");
    db.close();

    ytdb.drop("sub");
  }

  /**
   * Original test method: testSubFolderDbCreateConnPool Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:137
   */
  @Test
  public void test08_SubFolderDbCreateConnPool() {
    var directory = calculateDirectory();

    var ytdb = (YouTrackDBImpl) YourTracks.instance(directory);
    if (ytdb.exists("sub")) {
      ytdb.drop("sub");
    }

    ytdb.create("sub", DatabaseType.DISK, "admin", "admin", "admin");
    var db = ytdb.cachedPool("sub", "admin", "admin");
    db.close();

    ytdb.drop("sub");
  }

  /**
   * Original test method: testOpenCloseConnectionPool Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:153
   */
  @Test
  public void test09_OpenCloseConnectionPool() {
    for (var i = 0; i < 500; i++) {
      youTrackDB.cachedPool(DB_NAME, "admin", "admin").acquire().close();
    }
  }

  /**
   * Original test method: testSubFolderMultipleDbCreateSameName Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:160
   */
  @Test
  public void test10_SubFolderMultipleDbCreateSameName() {
    for (var i = 0; i < 3; ++i) {
      var dbName = "a" + i + "$db";
      if (youTrackDB.exists(dbName)) {
        youTrackDB.drop(dbName);
      }

      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");
      Assert.assertTrue(youTrackDB.exists(dbName));

      youTrackDB.open(dbName, "admin", "admin").close();
    }

    for (var i = 0; i < 3; ++i) {
      var dbName = "a" + i + "$db";
      Assert.assertTrue(youTrackDB.exists(dbName));
      youTrackDB.drop(dbName);
      Assert.assertFalse(youTrackDB.exists(dbName));
    }
  }

  /**
   * Original test method: testDbIsNotRemovedOnSecondTry Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCreationTest.java:182
   */
  @Test
  public void test11_DbIsNotRemovedOnSecondTry() {
    youTrackDB.create(DB_NAME + "Remove", DatabaseType.DISK, "admin", "admin", "admin");

    try {
      youTrackDB.create(DB_NAME + "Remove", DatabaseType.DISK, "admin", "admin", "admin");
      Assert.fail();
    } catch (CoreException e) {
      // ignore all is correct
    }

    final var buildDirectory = System.getProperty("buildDirectory", ".");
    var path = buildDirectory + "/test-db/" + DB_NAME + "Remove";
    Assert.assertTrue(new File(path).exists());

    youTrackDB.drop(DB_NAME + "Remove");
    try {
      youTrackDB.drop(DB_NAME + "Remove");
      Assert.fail();
    } catch (CoreException e) {
      // ignore all is correct
    }
  }
}
