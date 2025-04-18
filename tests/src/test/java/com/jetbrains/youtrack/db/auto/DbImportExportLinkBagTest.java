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

import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = {"db", "import-export"})
public class DbImportExportLinkBagTest extends BaseDBTest implements CommandOutputListener {

  public static final String EXPORT_FILE_PATH = "target/db.export-ridbag.gz";
  public static final String NEW_DB_PATH = "target/test-import-ridbag";
  public static final String NEW_DB_URL = "target/test-import-ridbag";

  private final String testPath;
  private final String exportFilePath;
  private boolean dumpMode = false;

  @Parameters(value = {"remote", "testPath"})
  public DbImportExportLinkBagTest(@Optional Boolean remote, String testPath) {
    super(remote != null && remote);
    this.testPath = testPath;

    exportFilePath = System.getProperty("exportFilePath", EXPORT_FILE_PATH);
  }

  @Test
  public void testDbExport() throws IOException {
    if (remoteDB) {
      return;
    }

    var session = acquireSession();
    session.begin();
    session.execute("insert into V set name ='a'");
    for (var i = 0; i < 100; i++) {
      session.execute("insert into V set name ='b" + i + "'");
    }

    session.execute(
        "create edge E from (select from V where name ='a') to (select from V where name != 'a')");
    session.commit();

    // ADD A CUSTOM TO THE CLASS
    session.execute("alter class V custom onBeforeCreate=onBeforeCreateItem").close();

    var export = new DatabaseExport(session, testPath + "/" + exportFilePath, this);
    export.exportDatabase();
    export.close();

    session.close();
  }

  @Test(dependsOnMethods = "testDbExport")
  public void testDbImport() throws IOException {
    if (remoteDB) {
      return;
    }

    final var importDir = new File(testPath + "/" + NEW_DB_PATH);
    if (importDir.exists()) {
      for (var f : importDir.listFiles()) {
        f.delete();
      }
    } else {
      importDir.mkdir();
    }

    DatabaseSessionInternal database =
        new DatabaseDocumentTx(getStorageType() + ":" + testPath + "/" + NEW_DB_URL);
    database.create();

    var dbImport = new DatabaseImport(database, testPath + "/" + exportFilePath, this);
    dbImport.setMaxRidbagStringSizeBeforeLazyImport(50);

    // UNREGISTER ALL THE HOOKS
    for (var hook : new ArrayList<RecordHook>(database.getHooks())) {
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
    if (iText != null && iText.contains("ERR"))
    // ACTIVATE DUMP MODE
    {
      dumpMode = true;
    }

    if (dumpMode) {
      LogManager.instance().error(this, iText, null);
    }
  }
}
