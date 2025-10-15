package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
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
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(exportDbPath);
    youTrackDB.createIfNotExists(databaseName, DatabaseType.DISK, "admin", "admin", "admin");

    final var output = new ByteArrayOutputStream();
    try (final var db = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, "admin", "admin")) {
      try (var graph = youTrackDB.openGraph(databaseName, "admin", "admin")) {
        graph.autoExecuteInTx(
            g -> g.addSchemaClass("SimpleVertexClass").addStateFullEdgeClass("SimpleEdgeClass"));
      }

      final var export =
          new DatabaseExport(db, output, iText -> {
          });
      export.setOptions(" -excludeAll -includeSchema=true");
      export.exportDatabase();
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();

    final var importDbPath = "target/import_" + DatabaseImportTest.class.getSimpleName();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(importDbPath);
    databaseName = "import";

    youTrackDB.createIfNotExists(databaseName, DatabaseType.DISK, "admin", "admin", "admin");
    try (var db = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, "admin",
        "admin")) {
      final var importer =
          new DatabaseImport(
              db,
              new ByteArrayInputStream(output.toByteArray()),
              iText -> {
              });
      importer.importDatabase();
      final var schema = db.getMetadata().getSlowMutableSchema();
      Assert.assertTrue(schema.existsClass("SimpleVertexClass"));
      Assert.assertTrue(schema.existsClass("SimpleEdgeClass"));
      Assert.assertTrue(schema.getClass("SimpleVertexClass").isVertexType());
      Assert.assertTrue(schema.getClass("SimpleEdgeClass").isEdgeType());
    }
    youTrackDB.drop(databaseName);
    youTrackDB.close();
  }
}
