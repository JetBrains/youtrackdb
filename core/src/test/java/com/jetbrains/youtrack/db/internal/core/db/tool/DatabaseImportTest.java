package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class DatabaseImportTest {

  @Ignore // this test seems to be broken, need more deep investigation to fix
  @Test
  public void exportImportOnlySchemaTest() throws IOException {
    // delete import path to make test work without clean step before it
    final String importDbPath = "target/import_" + DatabaseImportTest.class.getSimpleName();
    deleteDirectory(Path.of(importDbPath));

    var databaseName = "export";
    final var exportDbPath = "target/export_" + DatabaseImportTest.class.getSimpleName();
    var youTrackDB = YourTracks.embedded(exportDbPath, YouTrackDBConfig.defaultConfig());
    youTrackDB.createIfNotExists(databaseName, DatabaseType.DISK, "admin", "admin", "admin");

    final var output = new ByteArrayOutputStream();
    try (final var db = youTrackDB.open(databaseName, "admin", "admin")) {
      db.getSchema().createClass("SimpleClass");
      db.getSchema().createVertexClass("SimpleVertexClass");
      db.getSchema().createEdgeClass("SimpleEdgeClass");

      final var export =
          new DatabaseExport((DatabaseSessionInternal) db, output, iText -> {
          });
      export.setOptions(" -excludeAll -includeSchema=true");
      export.exportDatabase();
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();

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
      final var schema = db.getMetadata().getSchema();
      Assert.assertTrue(schema.existsClass("SimpleClass"));
      Assert.assertTrue(schema.existsClass("SimpleVertexClass"));
      Assert.assertTrue(schema.existsClass("SimpleEdgeClass"));
      Assert.assertTrue(schema.getClass("SimpleVertexClass").isVertexType());
      Assert.assertTrue(schema.getClass("SimpleEdgeClass").isEdgeType());
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
