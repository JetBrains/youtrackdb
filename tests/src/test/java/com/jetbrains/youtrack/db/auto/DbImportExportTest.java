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

import com.google.common.collect.ImmutableList;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.Optional;
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
  public DbImportExportTest(@Optional Boolean remote, String testPath) {
    super(remote != null && remote);
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
  public void testLinksMigration() throws Exception {
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
    try (final YouTrackDB youTrackDB = new YouTrackDBAbstract(
        "embedded:" + localTesPath.getPath(),
        config)) {
      youTrackDB.create("original", DatabaseType.DISK);

      final var childDocCount = 50;

      try (final var session = (DatabaseSessionInternal) youTrackDB.open(
          "original", "admin", "admin")) {
        final Schema schema = session.getMetadata().getSchema();

        final var rootCls = schema.createClass("RootClass");
        rootCls.createProperty("no", PropertyType.INTEGER);
        rootCls.createProperty("circular_link", PropertyType.LINK);
        rootCls.createProperty("linkList", PropertyType.LINKLIST);
        rootCls.createProperty("linkSet", PropertyType.LINKSET);
        rootCls.createProperty("linkMap", PropertyType.LINKMAP);

        final var childCls = schema.createClass("ChildClass");
        childCls.createProperty("no", PropertyType.INTEGER);

        // creating and deleting some records to shift the next available IDs.
        final List<RID> ridsToDelete = new ArrayList<>();
        for (var i = 0; i < 100; i++) {
          ridsToDelete.add(
              session.computeInTx(tx -> tx.newEntity(rootCls).getIdentity())
          );
          ridsToDelete.add(
              session.computeInTx(tx -> tx.newEntity(childCls).getIdentity())
          );
        }
        for (final var rid : ridsToDelete) {
          session.executeInTx(tx -> tx.load(rid).delete());
        }

        session.executeInTx(tx -> {
          final var rootDoc1 = tx.newEntity(rootCls);
          rootDoc1.setProperty("no", 1);
          final var rootDoc2 = tx.newEntity(rootCls);
          rootDoc2.setProperty("no", 2);

          rootDoc1.setProperty("circular_link", rootDoc2.getIdentity());
          rootDoc2.setProperty("circular_link", rootDoc1.getIdentity());

          final var docList = rootDoc1.getOrCreateLinkList("linkList");
          final var docSet = rootDoc1.getOrCreateLinkSet("linkSet");
          final var docMap = rootDoc1.getOrCreateLinkMap("linkMap");

          for (var i = 0; i < childDocCount; i++) {
            final var linkedDoc = tx.newEntity();
            final var doc = tx.newEntity(childCls);
            doc.setProperty("no", i);
            linkedDoc.setProperty("link", doc.getIdentity());
            linkedDoc.setProperty("no", i);

            docList.add(linkedDoc);

            if (i % 2 == 0) {
              docSet.add(linkedDoc);
            }

            if (i % 3 == 0) {
              docMap.put("" + i, linkedDoc);
            }
          }
        });

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

        session.executeInTx(tx -> {
          final var rootDocs =
              ImmutableList.copyOf(session.browseClass("RootClass"))
                  .stream()
                  .collect(Collectors.toMap(r -> r.getInt("no"), Function.identity()));

          Assert.assertEquals(rootDocs.size(), 2);

          final var rootDoc1 = rootDocs.get(1);
          final var rootDoc2 = rootDocs.get(2);

          Assert.assertNotNull(rootDoc1);
          Assert.assertNotNull(rootDoc2);

          Assert.assertEquals(rootDoc1.getLink("circular_link"), rootDoc2.getIdentity());
          Assert.assertEquals(rootDoc2.getLink("circular_link"), rootDoc1.getIdentity());

          final var docList = rootDoc1.getLinkList("linkList");

          final var docListEntities = docList
              .stream()
              .map(tx::loadEntity)
              .collect(Collectors.toMap(r -> r.getInt("no"), Function.identity()));

          final var docSetEntities = rootDoc1.getLinkSet("linkSet")
              .stream()
              .map(tx::loadEntity)
              .collect(Collectors.toMap(r -> r.getInt("no"), Function.identity()));

          final var docMapEntities = rootDoc1.getLinkMap("linkMap")
              .entrySet()
              .stream()
              .collect(Collectors.toMap(
                  Entry::getKey,
                  e -> tx.loadEntity(e.getValue().getIdentity())
              ));

          Assert.assertEquals(docListEntities.size(), 50);
          Assert.assertEquals(docSetEntities.size(), Math.ceilDiv(childDocCount, 2));
          Assert.assertEquals(docMapEntities.size(), Math.ceilDiv(childDocCount, 3));

          for (var i = 0; i < childDocCount; i++) {

            final var docId = docList.get(i).getIdentity();

            final var docs = new ArrayList<Entity>();
            docs.add(docListEntities.get(i));
            if (i % 2 == 0) {
              docs.add(docSetEntities.get(i));
            }
            if (i % 3 == 0) {
              docs.add(docMapEntities.get("" + i));
            }

            for (var doc : docs) {
              Assert.assertNotNull(doc);
              Assert.assertEquals(doc.getIdentity(), docId);
              Assert.assertEquals(doc.getInt("no"), i);
            }

            final var child = docs.getFirst().getEntity("link");
            Assert.assertNotNull(child);
            Assert.assertEquals(child.getInt("no"), i);
          }
        });
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
