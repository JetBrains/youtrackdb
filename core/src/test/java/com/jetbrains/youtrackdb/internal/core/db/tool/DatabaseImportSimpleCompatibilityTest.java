package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class DatabaseImportSimpleCompatibilityTest {

  private YouTrackDBImpl youTrackDB;

  private DatabaseSessionEmbedded importDatabase;
  private DatabaseImport importer;

  private DatabaseExport export;

  // Known compatibility issue: the deprecation of manual indexes is checked by maven, which makes
  // this test fail: `"manualIndexes":[{"name":"dictionary","content":[]}]`
  @Ignore
  @Test
  public void testImportExportOldEmpty() throws Exception {
    final var emptyDbV2 = load("/databases/databases_2_2/Empty.json");
    Assert.assertNotNull("Input must not be null!", emptyDbV2);
    final var output = new ByteArrayOutputStream();
    Assert.assertEquals(0, output.size());
    final var databaseName = "testImportExportOldEmpty";
    this.setup(databaseName, emptyDbV2, output);

    this.executeImport();
    this.executeExport(" -excludeAll -includeSchema=true -includeManualIndexes=false");

    this.tearDown(databaseName);
    Assert.assertTrue(output.size() > 0);
  }

  // The deprecation of manual indexes is checked by maven, which makes this test fail.
  @Ignore
  @Test
  public void testImportExportOldSimple() throws Exception {
    final var simpleDbV2 = load("/databases/databases_2_2/OrderCustomer-sl-0.json");
    Assert.assertNotNull("Input must not be null!", simpleDbV2);
    final var output = new ByteArrayOutputStream();
    Assert.assertEquals(0, output.size());
    final var databaseName = "testImportExportOldSimple";
    this.setup(databaseName, simpleDbV2, output);

    this.executeImport();
    this.executeExport(" -excludeAll -includeSchema=true -includeManualIndexes=false");

    Assert.assertTrue(importDatabase.getMetadata().getSchema().existsClass("OrderCustomer"));

    this.tearDown(databaseName);
    Assert.assertTrue(output.size() > 0);
  }

  // Fails on IndexManagerShared with 'manualIndexesAreUsed' == true, due to missing class name and
  // empty fields, thus throwing 'IndexAbstract.manualIndexesWarning()'.
  // Hence, it is not sufficient to just remove the manualIndexes section in the import JSON
  @Ignore
  @Test
  public void testImportExportNewerSimple() throws Exception {
    // Only required in case of manual indexes:
    System.setProperty("youtrackdb.index.allowManualIndexes", String.valueOf(true));

    final var simpleDbV3 = load("/databases/databases_3_1/OrderCustomer-sl-0.json");
    Assert.assertNotNull("Input must not be null!", simpleDbV3);
    final var output = new ByteArrayOutputStream();
    Assert.assertEquals(0, output.size());
    final var databaseName = "testImportExportNewerSimple";
    this.setup(databaseName, simpleDbV3, output);

    this.executeImport();
    this.executeExport(" -excludeAll -includeSchema=true");

    Assert.assertTrue(importDatabase.getMetadata().getSchema().existsClass("OrderCustomer"));

    this.tearDown(databaseName);
    Assert.assertTrue(output.size() > 0);
  }

  private InputStream load(final String path) throws FileNotFoundException {
    final var file = new File(getClass().getResource(path).getFile());
    return new FileInputStream(file);
  }

  private void setup(
      final String databaseName, final InputStream input, final OutputStream output) {
    final var importDbPath = "target/import_" + this.getClass().getSimpleName();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(importDbPath);
    youTrackDB.create(databaseName, DatabaseType.DISK,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    try {
      importer = new DatabaseImport(importDatabase, input, iText -> {
      });
      export = new DatabaseExport(importDatabase, output, iText -> {
      });
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private void tearDown(final String databaseName) {
    try {
      youTrackDB.drop(databaseName);
      youTrackDB.close();
    } catch (final Exception e) {
      System.out.println("Issues during teardown " + e.getMessage());
    }
  }

  private void executeImport() {
    importer.setOptions(" -includeManualIndexes=true");
    importer.importDatabase();
  }

  public void executeExport(final String options) {
    export.setOptions(options);
    export.exportDatabase();
  }
}
