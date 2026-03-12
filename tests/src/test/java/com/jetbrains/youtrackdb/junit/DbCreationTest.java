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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import java.io.File;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DbCreationTest {

  private static final String DB_NAME = "DbCreationTest";

  private YouTrackDBImpl youTrackDB;

  @BeforeAll
  void beforeAll() {
    initYTDB();
  }

  @AfterAll
  void afterAll() {
    youTrackDB.close();
  }

  private void initYTDB() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        buildDirectory + "/test-db-junit5-creation");
  }

  @Test
  @Order(1)
  void testDbCreationDefault() {
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }

    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
  }

  @Test
  @Order(2)
  void testDbExists() {
    assertTrue(youTrackDB.exists(DB_NAME), "Database " + DB_NAME + " not found");
  }

  @Test
  @Order(3)
  void testDbOpen() {
    var database = youTrackDB.open(DB_NAME, "admin", "admin");
    assertNotNull(database.getDatabaseName());
    database.close();
  }

  @Test
  @Order(4)
  void testDbOpenWithLastAsSlash() {
    youTrackDB.close();

    final var buildDirectory = System.getProperty("buildDirectory", ".");
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        buildDirectory + "/test-db-junit5-creation/")) {
      var database = ytdb.open(DB_NAME, "admin", "admin");
      database.close();
    }

    initYTDB();
  }

  private static String calculateDirectory() {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    return buildDirectory + "/test-db-junit5-creation";
  }

  @Test
  @Order(5)
  void testChangeLocale() {
    try (var database = youTrackDB.open(DB_NAME, "admin", "admin")) {
      database.execute(" ALTER DATABASE LOCALE_LANGUAGE  ?",
          Locale.GERMANY.getLanguage()).close();
      database.execute(" ALTER DATABASE LOCALE_COUNTRY  ?",
          Locale.GERMANY.getCountry()).close();

      assertEquals(
          Locale.GERMANY.getLanguage(),
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE));
      assertEquals(
          Locale.GERMANY.getCountry(),
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY));
      database.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY,
          Locale.ENGLISH.getCountry());
      database.set(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE,
          Locale.ENGLISH.getLanguage());
      assertEquals(
          Locale.ENGLISH.getCountry(),
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY));
      assertEquals(
          Locale.ENGLISH.getLanguage(),
          database.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE));
    }
  }

  @Test
  @Order(6)
  void testRoles() {
    try (var database = youTrackDB.open(DB_NAME, "admin", "admin")) {
      var tx = database.begin();
      tx.query("select from ORole where name = 'admin'").close();
      tx.commit();
    }
  }

  @Test
  @Order(7)
  void testSubFolderDbCreate() {
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

  @Test
  @Order(8)
  void testSubFolderDbCreateConnPool() {
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

  @Test
  @Order(9)
  void testOpenCloseConnectionPool() {
    for (var i = 0; i < 500; i++) {
      youTrackDB.cachedPool(DB_NAME, "admin", "admin").acquire().close();
    }
  }

  @Test
  @Order(10)
  void testSubFolderMultipleDbCreateSameName() {
    for (var i = 0; i < 3; ++i) {
      var dbName = "a" + i + "$db";
      if (youTrackDB.exists(dbName)) {
        youTrackDB.drop(dbName);
      }

      youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");
      assertTrue(youTrackDB.exists(dbName));

      youTrackDB.open(dbName, "admin", "admin").close();
    }

    for (var i = 0; i < 3; ++i) {
      var dbName = "a" + i + "$db";
      assertTrue(youTrackDB.exists(dbName));
      youTrackDB.drop(dbName);
      assertFalse(youTrackDB.exists(dbName));
    }
  }

  @Test
  @Order(11)
  void testDbIsNotRemovedOnSecondTry() {
    youTrackDB.create(DB_NAME + "Remove", DatabaseType.DISK, "admin", "admin",
        "admin");

    try {
      youTrackDB.create(DB_NAME + "Remove", DatabaseType.DISK, "admin", "admin",
          "admin");
      fail();
    } catch (CoreException e) {
      // ignore all is correct
    }

    final var buildDirectory = System.getProperty("buildDirectory", ".");
    var path = buildDirectory + "/test-db-junit5-creation/" + DB_NAME + "Remove";
    assertTrue(new File(path).exists());

    youTrackDB.drop(DB_NAME + "Remove");
    try {
      youTrackDB.drop(DB_NAME + "Remove");
      fail();
    } catch (CoreException e) {
      // ignore all is correct
    }
  }
}
