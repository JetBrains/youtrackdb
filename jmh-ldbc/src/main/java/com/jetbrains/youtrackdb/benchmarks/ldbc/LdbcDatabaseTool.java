package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI tool for LDBC database export/import and backup/restore operations.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... LdbcDatabaseTool export   &lt;exportFile&gt; [dbPath]
 * java -cp ... LdbcDatabaseTool import   &lt;exportFile&gt; [dbPath]
 * java -cp ... LdbcDatabaseTool backup   &lt;backupDir&gt;  [dbPath]
 * java -cp ... LdbcDatabaseTool restore  &lt;backupDir&gt;  [dbPath]
 * </pre>
 */
public class LdbcDatabaseTool {

  private static final String DB_NAME = "ldbc_benchmark";

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println(
          "Usage: LdbcDatabaseTool <export|import|backup|restore> <path> [dbPath]");
      System.exit(1);
    }

    String command = args[0];
    String path = args[1];
    String dbPath = args.length > 2 ? args[2] : "./target/ldbc-bench-db";

    switch (command) {
      case "export" -> doExport(dbPath, path);
      case "import" -> doImport(path, dbPath);
      case "backup" -> doBackup(dbPath, path);
      case "restore" -> doRestore(path, dbPath);
      default -> {
        System.err.println("Unknown command: " + command);
        System.err.println(
            "Usage: LdbcDatabaseTool <export|import|backup|restore> <path> [dbPath]");
        System.exit(1);
      }
    }
  }

  private static void doExport(String dbPath, String exportFile) throws Exception {
    System.out.println("Opening database at: " + dbPath);
    try (var db = (YouTrackDBImpl) YourTracks.instance(dbPath)) {
      try (var session = db.open(DB_NAME, "admin", "admin")) {
        System.out.println("Exporting database to: " + exportFile);
        long start = System.currentTimeMillis();
        var export = new DatabaseExport(session, exportFile, System.out::println);
        export.exportDatabase();
        export.close();
        long elapsed = System.currentTimeMillis() - start;

        long fileSize = Files.size(Path.of(exportFile + ".gz"));
        System.out.printf("Export completed in %dms (%.1fs), file size: %.1f MB%n",
            elapsed, elapsed / 1000.0, fileSize / 1024.0 / 1024.0);
      }
    }
  }

  private static void doImport(String importFile, String dbPath) throws Exception {
    System.out.println("Importing database from: " + importFile + " to: " + dbPath);
    try (var db = (YouTrackDBImpl) YourTracks.instance(dbPath)) {
      // Drop existing DB if present
      try {
        db.drop(DB_NAME);
        System.out.println("Dropped existing database: " + DB_NAME);
      } catch (Exception e) {
        // doesn't exist
      }

      db.create(DB_NAME, com.jetbrains.youtrackdb.api.DatabaseType.DISK,
          "admin", "admin", "admin");

      try (var session = db.open(DB_NAME, "admin", "admin")) {
        long start = System.currentTimeMillis();
        var imp = new DatabaseImport(session, importFile, System.out::println);
        imp.importDatabase();
        imp.close();
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Import completed in %dms (%.1fs)%n", elapsed, elapsed / 1000.0);
      }
    }
  }

  private static void doBackup(String dbPath, String backupPath) throws Exception {
    Files.createDirectories(Path.of(backupPath));

    System.out.println("Opening database at: " + dbPath);
    try (YouTrackDB db = YourTracks.instance(dbPath)) {
      var traversal = db.openTraversal(DB_NAME, "admin", "admin");

      System.out.println("Creating backup at: " + backupPath);
      long start = System.currentTimeMillis();
      String result = traversal.backup(Path.of(backupPath));
      long elapsed = System.currentTimeMillis() - start;

      System.out.printf("Backup created: %s in %dms (%.1fs)%n",
          result, elapsed, elapsed / 1000.0);
      traversal.close();
    }
  }

  private static void doRestore(String backupPath, String dbPath) throws Exception {
    System.out.println("Restoring database from: " + backupPath + " to: " + dbPath);
    try (YouTrackDB db = YourTracks.instance(dbPath)) {
      try {
        db.drop(DB_NAME);
        System.out.println("Dropped existing database: " + DB_NAME);
      } catch (Exception e) {
        // doesn't exist
      }

      long start = System.currentTimeMillis();
      db.restore(DB_NAME, backupPath);
      long elapsed = System.currentTimeMillis() - start;

      System.out.printf("Database restored in %dms (%.1fs)%n", elapsed, elapsed / 1000.0);

      // Quick verification
      var traversal = db.openTraversal(DB_NAME, "admin", "admin");
      @SuppressWarnings("unchecked")
      var personCount =
          ((com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource) traversal)
              .sqlCommand("SELECT count(*) as cnt FROM Person").toList();
      System.out.println("Verification - Person count: " + personCount);
      traversal.close();
    }
  }
}
