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

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.testng.Assert;
import org.testng.annotations.Optional;
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
  public DbImportStreamExportTest(@Optional Boolean remote, String testPath) {
    super(remote != null && remote);
    this.testPath = testPath;
    this.exportFilePath = System.getProperty("exportFilePath", EXPORT_FILE_PATH);
  }

  @Test
  public void testDbExport() throws IOException {
    if (remoteDB) {
      return;
    }

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
    if (remoteDB) {
      return;
    }

    final var importDir = new File(testPath + "/" + NEW_DB_PATH);
    if (importDir.exists()) {
      for (final var f : importDir.listFiles()) {
        f.delete();
      }
    } else {
      importDir.mkdir();
    }

    final DatabaseSessionInternal database =
        new DatabaseDocumentTx(getStorageType() + ":" + testPath + "/" + NEW_DB_URL);
    database.create();

    final var dbImport =
        new DatabaseImport(database, new FileInputStream(importDir), this);
    // UNREGISTER ALL THE HOOKS
    for (final var hook : new ArrayList<>(database.getHooks())) {
      database.unregisterHook(hook);
    }

    dbImport.setDeleteRIDMapping(false);
    dbImport.importDatabase();
    dbImport.close();

    database.close();
  }

  @Test(dependsOnMethods = "testDbImport")
  public void testCompareDatabases() throws IOException {
    if (remoteDB) {
      return;
    }
    var first = acquireSession();
    DatabaseSessionInternal second =
        new DatabaseDocumentTx(getStorageType() + ":" + testPath + "/" + NEW_DB_URL);
    second.open("admin", "admin");

    final var databaseCompare = new DatabaseCompare(first, second, this);
    databaseCompare.setCompareEntriesForAutomaticIndexes(true);
    databaseCompare.setCompareIndexMetadata(true);

    Assert.assertTrue(databaseCompare.compare());
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
