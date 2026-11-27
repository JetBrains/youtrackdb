package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DefaultStoragePageSizeTest {

  private static final String PAGE_SIZE = "youtrackdb.storage.diskCache.pageSize";
  private static final String SOURCE = "source";
  private static final String TARGET = "restored";
  private static final String ADMIN = "admin";

  @Rule
  public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void unsetProcessCreatesFourKbAndRefusesEightKbImagesAnExplicitProcessCanRead()
      throws Exception {
    var root = temporary.newFolder().toPath().toAbsolutePath();
    runChild(root, "produce", true);
    var sourceBefore = snapshot(root.resolve(SOURCE));
    var backupBefore = snapshot(root.resolve("backup"));
    runChild(root, "unset", false);
    assertEquals(sourceBefore, snapshot(root.resolve(SOURCE)));
    assertEquals(backupBefore, snapshot(root.resolve("backup")));
    runChild(root, "consume", true);
  }

  private static void runChild(Path root, String mode, boolean explicitEight) throws Exception {
    // Keep restore staging in the JUnit folder with the rest of the test data.
    var childTemp = Files.createDirectory(root.resolve(mode + "-tmp"));
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.io.tmpdir=" + childTemp);
    if (explicitEight) {
      command.add("-D" + PAGE_SIZE + "=8");
    }
    // Do not forward JVM arguments or properties from the test runner.
    command.addAll(List.of("-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        DefaultStoragePageSizeTest.class.getName(), mode, root.toString()));
    var log = root.resolve(mode + ".log");
    var builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(log.toFile());
    for (var name : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
        "MAVEN_OPTS")) {
      builder.environment().remove(name);
    }
    // Redirect directly to a file so child output cannot fill a pipe during the wait.
    var child = builder.start();
    boolean exited;
    try {
      exited = child.waitFor(90, TimeUnit.SECONDS);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(mode + " child did not terminate", child.waitFor(10, TimeUnit.SECONDS));
      }
    }
    if (!exited) {
      fail(mode + " child timed out:\n" + logTail(log));
    }
    int status = child.exitValue();
    if (status != 0) {
      assertEquals(mode + " child failed:\n" + logTail(log), 0, status);
    }
  }

  private static String logTail(Path log) throws IOException {
    int limit = 4_000;
    long skipped = Math.max(0, Files.size(log) - limit);
    try (var input = Files.newInputStream(log)) {
      input.skipNBytes(skipped);
      var tail = new String(input.readNBytes(limit), StandardCharsets.UTF_8);
      return skipped == 0 ? tail : "[earlier child output omitted]\n" + tail;
    }
  }

  public static void main(String[] args) throws Exception {
    var mode = args[0];
    if ("unset".equals(mode)) {
      assertNull("The page-size property must be absent before database initialization",
          System.getProperty(PAGE_SIZE));
    } else {
      assertEquals("8", System.getProperty(PAGE_SIZE));
    }

    var root = Path.of(args[1]);
    var backup = root.resolve("backup");
    try (var manager = (YouTrackDBImpl) YourTracks.instance(root.toString())) {
      var internal = (YouTrackDBInternalEmbedded) manager.internal;
      switch (mode) {
        case "produce" -> {
          manager.create(SOURCE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
          try (var session = manager.open(SOURCE, ADMIN, ADMIN)) {
            session.getMetadata().getSchema().createClass("Witness");
            session.begin();
            session.newEntity("Witness").setProperty("value", "page-size witness");
            session.commit();
          }
          assertRecordedSize(root.resolve(SOURCE), 8);
          var unit = internal.getStorage(SOURCE).fullBackup(Files.createDirectory(backup));
          var bytes = Files.readAllBytes(backup.resolve(unit));
          // The version field is 74 bytes from the end in the supported formats.
          assertEquals(7, ByteBuffer.wrap(bytes).getShort(bytes.length - 74));
        }
        case "unset" -> {
          manager.create("defaultSize", DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
          assertRecordedSize(root.resolve("defaultSize"), 4);
          assertEquals(1228, internal.getStorage("defaultSize").getRecordedMaxIndexKeySize());

          assertSizeRefusal(assertThrows(RuntimeException.class,
              () -> manager.open(SOURCE, ADMIN, ADMIN)), "Database");
          assertNull(internal.getStorage(SOURCE));
          assertSizeRefusal(assertThrows(IllegalArgumentException.class,
              () -> internal.restore(TARGET, backup.toString(), null, null)), "Backup");
          assertNull(internal.getStorage(TARGET));
          assertFalse(Files.exists(root.resolve(TARGET)));
        }
        case "consume" -> {
          assertWitness(manager, SOURCE);
          internal.restore(TARGET, backup.toString(), null, null);
          assertWitness(manager, TARGET);
        }
        default -> throw new AssertionError("Unknown mode: " + mode);
      }
    }
  }

  private static void assertRecordedSize(Path database, int size) throws IOException {
    var metadata = new StorageBootstrapMetadata(database, new FeatureFormatIdentity(1));
    assertEquals("Recorded page size in KB", size, metadata.readActiveRequired().pageSizeKiB());
  }

  private static void assertSizeRefusal(Throwable failure, String kind) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message != null && message.contains(kind + " page size is 8 KB")
          && message.contains("Configured page size is 4 KB") && message.contains(PAGE_SIZE)) {
        return;
      }
    }
    throw new AssertionError("Expected a size-specific " + kind + " refusal", failure);
  }

  private static void assertWitness(YouTrackDBImpl manager, String database) {
    try (var session = manager.open(database, ADMIN, ADMIN);
        var result = session.query("select value from Witness")) {
      assertEquals(2457, session.getStorage().getRecordedMaxIndexKeySize());
      assertTrue(result.hasNext());
      assertEquals("page-size witness", result.next().getProperty("value"));
      assertFalse(result.hasNext());
    }
  }

  private static Map<Path, String> snapshot(Path directory) throws IOException {
    var entries = new TreeMap<Path, String>();
    try (var paths = Files.walk(directory)) {
      for (var path : paths.toList()) {
        entries.put(directory.relativize(path),
            Files.isDirectory(path) ? "/" : HexFormat.of().formatHex(Files.readAllBytes(path)));
      }
    }
    return entries;
  }
}
