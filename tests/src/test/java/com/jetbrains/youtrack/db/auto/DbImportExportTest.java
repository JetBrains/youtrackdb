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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class DbImportExportTest extends BaseDBTest implements CommandOutputListener {

  public static final String EXPORT_FILE_PATH = "target/export/db.export.gz";
  public static final String IMPORT_DB_NAME = "test-import";
  public static final String IMPORT_DB_PATH = "target/import";

  private final String testPath;
  private final String exportFilePath;
  private boolean dumpMode = false;

  @Parameters(value = {"remote", "testPath"})
  public DbImportExportTest(boolean remote, String testPath) {
    super(remote);
    this.testPath = testPath;
    this.exportFilePath = System.getProperty("exportFilePath", EXPORT_FILE_PATH);
  }

  @Test
  public void testDbExport() throws IOException {
    if (remoteDB) {
      return;
    }

    // ADD A CUSTOM TO THE CLASS
    session.execute("alter class V custom onBeforeCreate=onBeforeCreateItem").close();

    final var export =
        new DatabaseExport(session, testPath + "/" + exportFilePath, this);
    export.exportDatabase();
    export.close();
  }

  @Test(dependsOnMethods = "testDbExport")
  public void testDbImport() throws IOException {
    if (remoteDB) {
      return;
    }

    final var importDir = new File(testPath + "/" + IMPORT_DB_PATH);
    if (importDir.exists()) {
      for (final var f : importDir.listFiles()) {
        f.delete();
      }
    } else {
      importDir.mkdir();
    }

    try (var youTrackDBImport =
        YourTracks.embedded(
            testPath + File.separator + IMPORT_DB_PATH, YouTrackDBConfig.defaultConfig())) {
      youTrackDBImport.createIfNotExists(
          IMPORT_DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
      try (var importDB = youTrackDBImport.open(IMPORT_DB_NAME, "admin", "admin")) {
        final var dbImport =
            new DatabaseImport(
                (DatabaseSessionInternal) importDB, testPath + "/" + exportFilePath, this);
        // UNREGISTER ALL THE HOOKS
        for (final var hook : new ArrayList<>(
            ((DatabaseSessionInternal) importDB).getHooks())) {
          session.unregisterHook(hook);
        }
        dbImport.setDeleteRIDMapping(false);
        dbImport.importDatabase();
        dbImport.close();
      }
    }
  }

  @Test(dependsOnMethods = "testDbImport")
  public void testCompareDatabases() throws IOException {
    if (remoteDB) {
      return;
    }
    try (var youTrackDBImport =
        YourTracks.embedded(
            testPath + File.separator + IMPORT_DB_PATH, YouTrackDBConfig.defaultConfig())) {
      try (var importDB = youTrackDBImport.open(IMPORT_DB_NAME, "admin", "admin")) {
        final var databaseCompare =
            new DatabaseCompare(session, (DatabaseSessionInternal) importDB, this);
        databaseCompare.setCompareEntriesForAutomaticIndexes(true);
        databaseCompare.setCompareIndexMetadata(true);
        Assert.assertTrue(databaseCompare.compare());
      }
    }
  }

  @Test
  public void embeddedListMigration() throws Exception {
    if (remoteDB) {
      return;
    }

    final var localTesPath = new File(testPath + "/target", "embeddedListMigration");
    FileUtils.deleteRecursively(localTesPath);
    Assert.assertTrue(localTesPath.mkdirs());

    final var exportPath = new File(localTesPath, "export.json.gz");

    final YouTrackDBConfig config =
        new YouTrackDBConfigBuilderImpl()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, true)
            .build();
    try (final YouTrackDB youTrackDB = new YouTrackDBImpl(
        "embedded:" + localTesPath.getPath(),
        config)) {
      youTrackDB.create("original", DatabaseType.DISK);

      try (final var session = (DatabaseSessionInternal) youTrackDB.open(
          "original", "admin", "admin")) {
        final Schema schema = session.getMetadata().getSchema();

        final var rootCls = schema.createClass("RootClass");
        rootCls.createProperty("embeddedList", PropertyType.EMBEDDEDLIST);

        final var childCls = schema.createClass("ChildClass");

        final List<RID> ridsToDelete = new ArrayList<>();
        for (var i = 0; i < 100; i++) {
          session.begin();
          final var document = ((EntityImpl) session.newEntity(childCls));

          session.commit();

          ridsToDelete.add(document.getIdentity());
        }

        for (final var rid : ridsToDelete) {
          session.begin();
          var transaction = session.getActiveTransaction();
          transaction.load(rid).delete();
          session.commit();
        }

        final var rootDocument = ((EntityImpl) session.newEntity(rootCls));
        final var documents = new ArrayList<EntityImpl>();

        for (var i = 0; i < 10; i++) {
          session.begin();
          final var embeddedDocument = ((EntityImpl) session.newEntity());

          final var doc = ((EntityImpl) session.newEntity(childCls));

          session.commit();

          embeddedDocument.setProperty("link", doc.getIdentity());
          documents.add(embeddedDocument);
        }

        session.begin();
        rootDocument.setProperty("embeddedList", documents);

        session.commit();

        final var databaseExport =
            new DatabaseExport(
                session, exportPath.getPath(), System.out::println);
        databaseExport.exportDatabase();
      }

      youTrackDB.create("imported", DatabaseType.DISK);
      try (final var session =
          (DatabaseSessionInternal) youTrackDB.open("imported", "admin", "admin")) {
        final var databaseImport =
            new DatabaseImport(session, exportPath.getPath(), System.out::println);
        databaseImport.run();

        final Iterator<EntityImpl> classIterator = session.browseClass("RootClass");
        final var rootDocument = classIterator.next();

        final List<EntityImpl> documents = rootDocument.getProperty("embeddedList");
        for (var i = 0; i < 10; i++) {
          final var embeddedDocument = documents.get(i);

          embeddedDocument.setLazyLoad(false);
          final RecordId link = embeddedDocument.getProperty("link");

          Assert.assertNotNull(link);
          var transaction = session.getActiveTransaction();
          Assert.assertNotNull(transaction.load(link));
        }
      }
    }
  }

  @Override
  @Test(enabled = false)
  public void onMessage(final String iText) {
    if (iText != null && iText.contains("ERR")) {
      // ACTIVATE DUMP MODE
      dumpMode = true;
    }
    if (dumpMode) {
      LogManager.instance().error(this, iText, null);
    }
  }
}
