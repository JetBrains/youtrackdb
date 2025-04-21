/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.lucene.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.handler.AutomaticBackup;
import com.jetbrains.youtrack.db.internal.server.handler.AutomaticBackup.AutomaticBackupListener;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPInputStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LuceneAutomaticBackupRestoreTest {

  private static final String DBNAME = "LuceneAutomaticBackupRestoreTest";
  private File tempFolder;

  @Rule
  public TestName name = new TestName();

  private YouTrackDB youTrackDB;
  private String URL = null;
  private String BACKUPDIR = null;
  private String BACKUFILE = null;

  private YouTrackDBServer server;
  private DatabaseSessionEmbedded db;

  @Before
  public void setUp() throws Exception {
    final var buildDirectory = System.getProperty("buildDirectory", "target");
    final var buildDirectoryFile = new File(buildDirectory);

    tempFolder = new File(buildDirectoryFile, name.getMethodName());
    FileUtils.deleteRecursively(tempFolder);
    Assert.assertTrue(tempFolder.mkdirs());

    System.setProperty("YOUTRACKDB_HOME", tempFolder.getCanonicalPath());

    var path = tempFolder.getCanonicalPath() + File.separator + "databases";
    server =
        new YouTrackDBServer(false) {
          @Override
          public Map<String, String> getAvailableStorageNames() {
            var result = new HashMap<String, String>();
            result.put(DBNAME, URL);
            return result;
          }
        };
    server.startup();

    youTrackDB = server.getContext();

    URL = "disk:" + path + File.separator + DBNAME;

    BACKUPDIR = tempFolder.getCanonicalPath() + File.separator + "backups";

    BACKUFILE = BACKUPDIR + File.separator + DBNAME;

    var config = new File(tempFolder, "config");
    Assert.assertTrue(config.mkdirs());

    dropIfExists();

    youTrackDB.execute(
        "create database ? disk users(admin identified by 'admin' role admin) ", DBNAME);

    db = (DatabaseSessionEmbedded) youTrackDB.open(DBNAME, "admin", "admin");

    db.execute("create class City ");
    db.execute("create property City.name string");
    db.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE");

    db.begin();
    var doc = ((EntityImpl) db.newEntity("City"));
    doc.setProperty("name", "Rome");
    db.commit();
  }

  private void dropIfExists() {

    if (youTrackDB.exists(DBNAME)) {
      youTrackDB.drop(DBNAME);
    }
  }

  @After
  public void tearDown() {
    dropIfExists();
    FileUtils.deleteRecursively(tempFolder);
  }

  @AfterClass
  public static void afterClass() {
    final var youTrack = YouTrackDBEnginesManager.instance();

    if (youTrack != null) {
      youTrack.shutdown();
      youTrack.startup();
    }
  }

  @Test
  public void shouldExportImport() throws IOException, InterruptedException {

    try (var query = db.query("select from City where name lucene 'Rome'")) {
      assertThat(query).hasSize(1);
    }

    var jsonConfig =
        IOUtils.readStreamAsString(
            getClass().getClassLoader().getResourceAsStream("automatic-backup.json"));

    var map = JSONSerializerJackson.mapFromJson(jsonConfig);

    map.put("enabled", true);
    map.put("targetFileName", "${DBNAME}.json");
    map.put("targetDirectory", BACKUPDIR);
    map.put("mode", "EXPORT");

    map.put("dbInclude", new String[]{"LuceneAutomaticBackupRestoreTest"});
    map.put(
        "firstTime",
        new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis() + 2000)));

    IOUtils.writeFile(new File(tempFolder, "config/automatic-backup.json"),
        JSONSerializerJackson.mapToJson(map));

    final var aBackup = new AutomaticBackup();
    final var latch = new CountDownLatch(1);

    aBackup.registerListener(
        new AutomaticBackupListener() {
          @Override
          public void onBackupCompleted(String database) {
            latch.countDown();
          }

          @Override
          public void onBackupError(String database, Exception e) {
            latch.countDown();
          }
        });
    final var config = new ServerParameterConfiguration[]{};

    aBackup.config(server, config);
    aBackup.sendShutdown();
    db.close();
    latch.await();

    dropIfExists();
    // RESTORE

    db = createAndOpen();

    try (final var stream =
        new GZIPInputStream(new FileInputStream(BACKUFILE + ".json.gz"))) {
      new DatabaseImport(db, stream, s -> {
      }).importDatabase();
    }

    db.close();

    // VERIFY
    db = open();

    assertThat(db.countClass("City")).isEqualTo(1);

    var index = db.getSharedContext().getIndexManager().getIndex(db, "City.name");

    assertThat(index).isNotNull();
    assertThat(index.getType()).isEqualTo(SchemaClass.INDEX_TYPE.FULLTEXT.name());

    assertThat(db.query("select from City where name lucene 'Rome'")).hasSize(1);
  }

  private DatabaseSessionEmbedded createAndOpen() {
    youTrackDB.execute(
        "create database ? disk users(admin identified by 'admin' role admin) ", DBNAME);
    return open();
  }

  private DatabaseSessionEmbedded open() {
    return (DatabaseSessionEmbedded) youTrackDB.open(DBNAME, "admin", "admin");
  }
}
