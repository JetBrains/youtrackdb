package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DatabaseImportTest {

  @Test
  public void exportImportOnlySchemaTest() throws IOException {
    var databaseName = "export";
    final var exportDbPath = "target/export_" + DatabaseImportTest.class.getSimpleName();
    var youTrackDB = YourTracks.embedded(exportDbPath, YouTrackDBConfig.defaultConfig());
    youTrackDB.createIfNotExists(databaseName, DatabaseType.DISK, "admin", "admin", "admin");

    final var output = new ByteArrayOutputStream();
    try (final var db = youTrackDB.open(databaseName, "admin", "admin")) {
      db.getSchema().createClass("SimpleClass");

      final var export =
          new DatabaseExport((DatabaseSessionInternal) db, output, iText -> {
          });
      export.setOptions(" -excludeAll -includeSchema=true");
      export.exportDatabase();
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();

    final var importDbPath = "target/import_" + DatabaseImportTest.class.getSimpleName();
    youTrackDB = YourTracks.embedded(importDbPath, YouTrackDBConfig.defaultConfig());
    databaseName = "import";

    youTrackDB.createIfNotExists(databaseName, DatabaseType.DISK, "admin", "admin", "admin");
    try (var db = (DatabaseSessionInternal) youTrackDB.open(databaseName, "admin",
        "admin")) {
      final var importer =
          new DatabaseImport(
              db,
              new ByteArrayInputStream(output.toByteArray()),
              iText -> {
              });
      importer.importDatabase();
      Assert.assertTrue(db.getMetadata().getSchema().existsClass("SimpleClass"));
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();
  }
}
