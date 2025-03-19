package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;

public class DatabaseSuperNodeTest {

  public static void main(String[] args) {
    final var tester = new DatabaseSuperNodeTest();

    final var numberEdges = Arrays.asList(100000, 500000, 1000000, 5000000);
    final List<Long> exportStats = new ArrayList<>(numberEdges.size());
    final List<Long> importStats = new ArrayList<>(numberEdges.size());

    for (int numberEdge : numberEdges) {
      final var databaseName = "superNode_export";
      final var exportDbUrl =
          "embedded:target/export_" + DatabaseSuperNodeTest.class.getSimpleName();
      YouTrackDB youTrackDB =
          CreateDatabaseUtil.createDatabase(
              databaseName, exportDbUrl, CreateDatabaseUtil.TYPE_MEMORY);

      final var output = new ByteArrayOutputStream();
      try {
        testExportDatabase(numberEdge, exportStats, databaseName, youTrackDB, output);
        Thread.sleep(2000);
      } catch (final InterruptedException e) {
        e.printStackTrace();
      } finally {
        youTrackDB.drop(databaseName);
        youTrackDB.close();
      }

      final var importDbUrl =
          "memory:target/import_" + DatabaseSuperNodeTest.class.getSimpleName();
      youTrackDB =
          CreateDatabaseUtil.createDatabase(
              databaseName + "_reImport", importDbUrl, CreateDatabaseUtil.TYPE_DISK);
      try {
        testImportDatabase(numberEdge, databaseName, youTrackDB, output, importStats);
      } finally {
        youTrackDB.drop(databaseName + "_reImport");
        youTrackDB.close();
      }
    }

    for (var i = 0; i < exportStats.size(); i++) {
      System.out.println("Export-" + numberEdges.get(i) + "(ms)=" + exportStats.get(i));
      System.out.println("Import-" + numberEdges.get(i) + "(ms)=" + importStats.get(i));
    }
  }

  private static void testExportDatabase(
      final int edgeNumber,
      final List<Long> stats,
      final String databaseName,
      final YouTrackDB youTrackDB,
      final OutputStream output) {

    try (final var session =
        youTrackDB.open(databaseName, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
      session.createClassIfNotExist("SuperNodeClass", "V");
      session.createClassIfNotExist("NonSuperEdgeClass", "E");

      var tx = session.begin();
      final var fromNode = tx.newVertex("SuperNodeClass");
      final var toNode = tx.newVertex("SuperNodeClass");

      for (var i = 0; i < edgeNumber; i++) {
        final var edge = tx.newStatefulEdge(fromNode, toNode, "NonSuperEdgeClass");
      }
      tx.commit();

      final var export =
          new DatabaseExport(
              (DatabaseSessionInternal) session,
              output,
              new CommandOutputListener() {
                @Override
                public void onMessage(String iText) {
                }
              });
      // export.setOptions(" -excludeAll -includeSchema=true");
      // List of export options can be found in `DatabaseImpExpAbstract`
      export.setOptions(" -includeSchema=true");
      final var start = System.nanoTime();
      export.exportDatabase();
      final var time = (System.nanoTime() - start) / 1000000;
      stats.add(time);
      System.out.println("Export-" + edgeNumber + "(ms)=" + time);
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private static void testImportDatabase(
      int numberEdge,
      final String databaseName,
      final YouTrackDB youTrackDB,
      final ByteArrayOutputStream output,
      List<Long> stats) {
    try (var db =
        (DatabaseSessionInternal) youTrackDB.open(
            databaseName + "_reImport", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
      final var importer =
          new DatabaseImport(
              db,
              new ByteArrayInputStream(output.toByteArray()),
              new CommandOutputListener() {
                @Override
                public void onMessage(String iText) {
                }
              });
      final var start = System.nanoTime();
      importer.importDatabase();
      final var time = (System.nanoTime() - start) / 1000000;
      stats.add(time);
      System.out.println("Import-" + numberEdge + "(ms)=" + time);
      Assert.assertTrue(db.getMetadata().getSchema().existsClass("SuperNodeClass"));
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }
}
