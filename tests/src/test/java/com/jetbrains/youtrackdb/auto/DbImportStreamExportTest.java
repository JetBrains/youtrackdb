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

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

// FIXME: let exporter version exports be 13 and check whether new stream processing is used.
@Test(groups = {"db", "import-export"})
public class DbImportStreamExportTest extends BaseDBTest implements CommandOutputListener {

  public static final String EXPORT_FILE_PATH = "target/db.export.gz";
  public static final String NEW_DB_PATH = "target/test-import";
  public static final String NEW_DB_URL = "target/test-import";

  private final String testPath;
  private final String exportFilePath;
  private boolean dumpMode = false;

  @Parameters(value = {"url", "testPath"})
  public DbImportStreamExportTest(String testPath) {
    this.testPath = testPath;
    this.exportFilePath = System.getProperty("exportFilePath", EXPORT_FILE_PATH);
  }

  @Test
  public void testDbExport() throws IOException {
    final var database = acquireSession();
    // ADD A CUSTOM TO THE CLASS
    database.execute("alter class V custom onBeforeCreate=onBeforeCreateItem").close();

    final var export =
        new DatabaseExport(database, testPath + "/" + exportFilePath, this);
    export.exportDatabase();
    export.close();
    database.close();
  }

  @Test(dependsOnMethods = "testDbExport")
  public void testDbImport() throws IOException {
    final var importDir = new File(testPath + "/" + NEW_DB_PATH);

    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(testPath)) {
      if (youTrackDb.exists(DbImportStreamExportTest.class.getSimpleName() + "Import")) {
        youTrackDb.drop(DbImportStreamExportTest.class.getSimpleName() + "Import");
      }
      youTrackDb.create(DbImportStreamExportTest.class.getSimpleName() + "Import", databaseType,
          "admin", "admin", "admin");
      try (var importSession = (DatabaseSessionEmbedded) youTrackDb.open(
          DbImportStreamExportTest.class.getSimpleName() + "Import", "admin", "admin")) {
        final var dbImport =
            new DatabaseImport(importSession, new FileInputStream(testPath + "/" + exportFilePath),
                this);
        // UNREGISTER ALL THE HOOKS
        for (final var hook : new ArrayList<>(importSession.getHooks())) {
          importSession.unregisterHook(hook);
        }

        dbImport.setDeleteRIDMapping(false);
        dbImport.importDatabase();
        dbImport.close();
      }
    }
  }

  @Test(dependsOnMethods = "testDbImport")
  public void testCompareDatabases() throws IOException {
    var exportSession = acquireSession();

    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(testPath)) {
      try (var importSession = (DatabaseSessionEmbedded) youTrackDb.open(
          DbImportStreamExportTest.class.getSimpleName() + "Import", "admin", "admin")) {
        final var databaseCompare = new DatabaseCompare(exportSession, importSession, this);
        databaseCompare.setCompareEntriesForAutomaticIndexes(true);

        Assert.assertTrue(databaseCompare.compare());
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
