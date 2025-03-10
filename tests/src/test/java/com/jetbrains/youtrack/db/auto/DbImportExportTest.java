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
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
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

@Test(enabled = false)
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

  @Test(enabled = false)
  public void testDbExport() throws IOException {
    // ADD A CUSTOM TO THE CLASS
    database.command("alter class V custom onBeforeCreate=onBeforeCreateItem").close();

    final DatabaseExport export =
        new DatabaseExport(database, testPath + "/" + exportFilePath, this);
    export.exportDatabase();
    export.close();
  }

  @Test(dependsOnMethods = "testDbExport", enabled = false)
  public void testDbImport() throws IOException {
    final File importDir = new File(testPath + "/" + IMPORT_DB_PATH);
    if (importDir.exists()) {
      for (final File f : importDir.listFiles()) {
        f.delete();
      }
    } else {
      importDir.mkdir();
    }

    try (YouTrackDB youTrackDBImport =
        YourTracks.embedded(
            testPath + File.separator + IMPORT_DB_PATH, YouTrackDBConfig.defaultConfig())) {
      youTrackDBImport.createIfNotExists(
          IMPORT_DB_NAME, DatabaseType.PLOCAL, "admin", "admin", "admin");
      try (var importDB = youTrackDBImport.open(IMPORT_DB_NAME, "admin", "admin")) {
        final DatabaseImport dbImport =
            new DatabaseImport(
                (DatabaseSessionInternal) importDB, testPath + "/" + exportFilePath, this);
        // UNREGISTER ALL THE HOOKS
        for (final RecordHook hook : new ArrayList<>(database.getHooks().keySet())) {
          database.unregisterHook(hook);
        }
        dbImport.setDeleteRIDMapping(false);
        dbImport.importDatabase();
        dbImport.close();
      }
    }
  }

  @Test(dependsOnMethods = "testDbImport", enabled = false) // todo restore
  public void testCompareDatabases() throws IOException {
    if (remoteDB) {
      final String env = getTestEnv();
      if (env == null || env.equals("dev")) {
        return;
      }
      // EXECUTES ONLY IF NOT REMOTE ON CI/RELEASE TEST ENV
    }
    try (YouTrackDB youTrackDBImport =
        YourTracks.embedded(
            testPath + File.separator + IMPORT_DB_PATH, YouTrackDBConfig.defaultConfig())) {
      try (var importDB = youTrackDBImport.open(IMPORT_DB_NAME, "admin", "admin")) {
        final DatabaseCompare databaseCompare =
            new DatabaseCompare(database, (DatabaseSessionInternal) importDB, this);
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

    final File localTesPath = new File(testPath + "/target", "embeddedListMigration");
    FileUtils.deleteRecursively(localTesPath);
    Assert.assertTrue(localTesPath.mkdirs());

    final File exportPath = new File(localTesPath, "export.json.gz");

    final YouTrackDBConfig config =
        new YouTrackDBConfigBuilderImpl()
            .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, true)
            .build();
    try (final YouTrackDB youTrackDB = new YouTrackDBImpl(
        "embedded:" + localTesPath.getPath(),
        config)) {
      youTrackDB.create("original", DatabaseType.PLOCAL);

      try (final DatabaseSessionInternal session = (DatabaseSessionInternal) youTrackDB.open(
          "original", "admin", "admin")) {
        final Schema schema = session.getMetadata().getSchema();

        final SchemaClass rootCls = schema.createClass("RootClass");
        rootCls.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST);

        final SchemaClass childCls = schema.createClass("ChildClass");

        final List<RID> ridsToDelete = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
          session.begin();
          final EntityImpl document = new EntityImpl(childCls);
          document.save();
          session.commit();

          ridsToDelete.add(document.getIdentity());
        }

        for (final RID rid : ridsToDelete) {
          session.begin();
          rid.getRecord().delete();
          session.commit();
        }

        final EntityImpl rootDocument = new EntityImpl(rootCls);
        final ArrayList<EntityImpl> documents = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
          session.begin();
          final EntityImpl embeddedDocument = new EntityImpl();

          final EntityImpl doc = new EntityImpl(childCls);
          doc.save();
          session.commit();

          embeddedDocument.field("link", doc.getIdentity());
          documents.add(embeddedDocument);
        }

        session.begin();
        rootDocument.field("embeddedList", documents);
        rootDocument.save();
        session.commit();

        final DatabaseExport databaseExport =
            new DatabaseExport(
                session, exportPath.getPath(), System.out::println);
        databaseExport.exportDatabase();
      }

      youTrackDB.create("imported", DatabaseType.PLOCAL);
      try (final DatabaseSessionInternal session =
          (DatabaseSessionInternal) youTrackDB.open("imported", "admin", "admin")) {
        final DatabaseImport databaseImport =
            new DatabaseImport(session, exportPath.getPath(), System.out::println);
        databaseImport.run();

        final Iterator<EntityImpl> classIterator = session.browseClass("RootClass");
        final EntityImpl rootDocument = classIterator.next();

        final List<EntityImpl> documents = rootDocument.field("embeddedList");
        for (int i = 0; i < 10; i++) {
          final EntityImpl embeddedDocument = documents.get(i);

          embeddedDocument.setLazyLoad(false);
          final RecordId link = embeddedDocument.getProperty("link");

          Assert.assertNotNull(link);
          Assert.assertNotNull(link.getRecord());
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
