package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DatabaseImportTest {

  @Test
  public void exportImportOnlySchemaTest() throws IOException {
    // delete import path to make test work without clean step before it
    final String importDbPath = "target/import_" + DatabaseImportTest.class.getSimpleName();
    deleteDirectory(Path.of(importDbPath));

    String databaseName = "export";
    final String exportDbPath = "target/export_" + DatabaseImportTest.class.getSimpleName();
    YouTrackDB youTrackDB = YourTracks.embedded(exportDbPath, YouTrackDBConfig.defaultConfig());
    youTrackDB.createIfNotExists(databaseName, DatabaseType.PLOCAL, "admin", "admin", "admin");

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (final DatabaseSession db = youTrackDB.open(databaseName, "admin", "admin")) {
      db.createClass("SimpleClass");

      final DatabaseExport export =
          new DatabaseExport((DatabaseSessionInternal) db, output, iText -> {
          });
      export.setOptions(" -excludeAll -includeSchema=true");
      export.exportDatabase();
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();

    youTrackDB = YourTracks.embedded(importDbPath, YouTrackDBConfig.defaultConfig());
    databaseName = "import";

    youTrackDB.createIfNotExists(databaseName, DatabaseType.PLOCAL, "admin", "admin", "admin");
    try (var db = (DatabaseSessionInternal) youTrackDB.open(databaseName, "admin",
        "admin")) {
      final DatabaseImport importer =
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

  public static void deleteDirectory(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream.sorted((p1, p2) -> p2.compareTo(p1)) // Reverse order for deleting files first
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (IOException e) {
              System.err.println("Failed to delete: " + p + " - " + e.getMessage());
            }
          });
    }
  }
}
