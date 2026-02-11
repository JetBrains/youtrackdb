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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import java.io.File;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class DbCreationTest {

  private static final String DB_NAME = "DbCreationTest";

  private YouTrackDBImpl youTrackDB;


  @BeforeClass
  public void beforeClass() {
    initYTDB();
  }

  @AfterClass
  public void afterClass() {
    youTrackDB.close();
  }

  private void initYTDB() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory + "/test-db");
  }

  @Test
  public void testDbCreationDefault() {
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }

    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
  }

  @Test(dependsOnMethods = {"testDbCreationDefault"})
  public void testDbExists() {
    Assert.assertTrue(youTrackDB.exists(DB_NAME), "Database " + DB_NAME + " not found");
  }

  @Test(dependsOnMethods = {"testDbExists"})
  public void testDbOpen() {
    var database = youTrackDB.open(DB_NAME, "admin", "admin");
    Assert.assertNotNull(database.getDatabaseName());
    database.close();
  }

  @Test(dependsOnMethods = {"testDbOpen"})
  public void testDbOpenWithLastAsSlash() {
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

  @Test(dependsOnMethods = {"testDbOpenWithLastAsSlash"})
  public void testChangeLocale() {
    try (var database = youTrackDB.open(DB_NAME, "admin", "admin")) {
      database.execute(" ALTER DATABASE LOCALE_LANGUAGE  ?", Locale.GERMANY.getLanguage()).close();
      database.execute(" ALTER DATABASE LOCALE_COUNTRY  ?", Locale.GERMANY.getCountry()).close();

      Assert.assertEquals(
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE),
          Locale.GERMANY.getLanguage());
      Assert.assertEquals(
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY),
          Locale.GERMANY.getCountry());
      database.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY, Locale.ENGLISH.getCountry());
      database.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE,
          Locale.ENGLISH.getLanguage());
      Assert.assertEquals(
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY),
          Locale.ENGLISH.getCountry());
      Assert.assertEquals(
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE),
          Locale.ENGLISH.getLanguage());
    }
  }

  @Test(dependsOnMethods = {"testChangeLocale"})
  public void testRoles() {
    try (var database = youTrackDB.open(DB_NAME, "admin", "admin")) {
      var tx = database.begin();
      tx.query("select from ORole where name = 'admin'").close();
      tx.commit();
    }
  }

  @Test(dependsOnMethods = {"testChangeLocale"})
  public void testSubFolderDbCreate() {
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

  @Test(dependsOnMethods = {"testChangeLocale"})
  public void testSubFolderDbCreateConnPool() {
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

  @Test(dependsOnMethods = {"testSubFolderDbCreateConnPool"})
  public void testOpenCloseConnectionPool() {
    for (var i = 0; i < 500; i++) {
      youTrackDB.cachedPool(DB_NAME, "admin", "admin").acquire().close();
    }
  }

  @Test(dependsOnMethods = {"testChangeLocale"})
  public void testSubFolderMultipleDbCreateSameName() {
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

  public void testDbIsNotRemovedOnSecondTry() {
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
