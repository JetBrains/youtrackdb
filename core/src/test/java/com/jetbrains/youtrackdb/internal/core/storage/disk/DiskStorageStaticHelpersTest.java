package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link DiskStorage} static helpers that can be exercised without a
 * running storage engine: {@code exists(Path)}, {@code deleteFilesFromDisc}, the
 * private {@code IBUFileNamesComparator}, and the private {@code XXHashOutputStream}.
 *
 * <p>Each test creates its own temp directory under the Maven {@code buildDirectory}
 * system property (falling back to {@code "."}) and removes it in {@link #after()}.
 */
public class DiskStorageStaticHelpersTest {

  private static Path buildDirectoryPath;

  private Path testDir;

  @BeforeClass
  public static void beforeClass() {
    var buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }
    buildDirectoryPath = Paths.get(buildDirectory);
  }

  @Before
  public void before() throws IOException {
    testDir = buildDirectoryPath.resolve("DiskStorageStaticHelpersTest-" + System.nanoTime());
    Files.createDirectories(testDir);
  }

  @After
  public void after() throws IOException {
    // Walk tree in reverse order so files are deleted before their parent directories
    if (Files.exists(testDir)) {
      try (var stream = Files.walk(testDir)) {
        stream.sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
      }
    }
  }

  // -----------------------------------------------------------------------
  // DiskStorage.exists(Path) — public static
  // -----------------------------------------------------------------------

  /**
   * Verifies that exists(Path) returns false when the path does not exist.
   */
  @Test
  public void exists_returnsFalse_whenPathDoesNotExist() {
    var absent = testDir.resolve("nonexistent");
    assertFalse(DiskStorage.exists(absent));
  }

  /**
   * Verifies that exists(Path) returns false for an empty directory —
   * no recognised storage marker file is present.
   */
  @Test
  public void exists_returnsFalse_whenDirectoryIsEmpty() {
    assertFalse(DiskStorage.exists(testDir));
  }

  /**
   * Verifies that exists(Path) returns true when {@code database.ocf} is present
   * in the directory — the canonical marker for an existing storage.
   */
  @Test
  public void exists_returnsTrue_whenDatabaseOcfPresent() throws IOException {
    Files.createFile(testDir.resolve("database.ocf"));
    assertTrue(DiskStorage.exists(testDir));
  }

  /**
   * Verifies that exists(Path) returns true when a "config*.bd" file is present
   * (alternative storage marker used by the configuration B-tree).
   */
  @Test
  public void exists_returnsTrue_whenConfigBdFilePresent() throws IOException {
    Files.createFile(testDir.resolve("config0.bd"));
    assertTrue(DiskStorage.exists(testDir));
  }

  /**
   * Verifies that exists(Path) returns true when the {@code dirty.fl} startup
   * metadata file is present.
   */
  @Test
  public void exists_returnsTrue_whenDirtyFlPresent() throws IOException {
    Files.createFile(testDir.resolve("dirty.fl"));
    assertTrue(DiskStorage.exists(testDir));
  }

  /**
   * Verifies that exists(Path) returns true when the {@code dirty.flb} backup
   * startup metadata file is present.
   */
  @Test
  public void exists_returnsTrue_whenDirtyFlbPresent() throws IOException {
    Files.createFile(testDir.resolve("dirty.flb"));
    assertTrue(DiskStorage.exists(testDir));
  }

  /**
   * Verifies that exists(Path) returns false when only unrecognised files are
   * present — no storage marker is found even though the directory exists.
   */
  @Test
  public void exists_returnsFalse_whenOnlyUnrecognisedFilesPresent() throws IOException {
    Files.createFile(testDir.resolve("somefile.txt"));
    Files.createFile(testDir.resolve("data.bin"));
    assertFalse(DiskStorage.exists(testDir));
  }

  // -----------------------------------------------------------------------
  // DiskStorage.deleteFilesFromDisc — public static
  // -----------------------------------------------------------------------

  /**
   * Verifies that deleteFilesFromDisc removes files whose extensions are in the
   * ALL_FILE_EXTENSIONS array and leaves files with unrecognised extensions in place.
   */
  @Test
  public void deleteFilesFromDisc_deletesKnownExtensions_leavesUnknownExtensions()
      throws IOException {
    // Create a .cm file (known extension) and a .xyz file (unknown extension)
    var knownFile = testDir.resolve("storage.cm").toFile();
    var unknownFile = testDir.resolve("data.xyz").toFile();
    assertTrue(knownFile.createNewFile());
    assertTrue(unknownFile.createNewFile());

    DiskStorage.deleteFilesFromDisc("test-storage", 3, 0, testDir.toString());

    assertFalse("Known-extension file should have been deleted", knownFile.exists());
    assertTrue("Unknown-extension file should have been left in place", unknownFile.exists());
  }

  /**
   * Verifies that deleteFilesFromDisc is a no-op when the directory does not exist.
   * No exception should be thrown.
   */
  @Test
  public void deleteFilesFromDisc_isNoOp_whenDirectoryDoesNotExist() {
    var absent = testDir.resolve("no-such-dir").toString();
    // Must not throw
    DiskStorage.deleteFilesFromDisc("test-storage", 3, 0, absent);
  }

  /**
   * Verifies that deleteFilesFromDisc removes the directory itself when all
   * storage files are successfully deleted.
   */
  @Test
  public void deleteFilesFromDisc_removesDirectory_whenAllFilesDeleted() throws IOException {
    var knownFile = testDir.resolve("wal.otx").toFile();
    assertTrue(knownFile.createNewFile());

    DiskStorage.deleteFilesFromDisc("test-storage", 3, 0, testDir.toString());

    assertFalse("Storage directory should be removed after all files deleted",
        testDir.toFile().exists());
    // Re-create testDir so @After cleanup does not fail
    Files.createDirectories(testDir);
  }

  // -----------------------------------------------------------------------
  // IBUFileNamesComparator — private static nested class via reflection
  // -----------------------------------------------------------------------

  /**
   * Obtains an instance of the private {@code IBUFileNamesComparator} via reflection
   * and returns it as a {@code Comparator<String>}.
   */
  @SuppressWarnings("unchecked")
  private static Comparator<String> ibuFileNamesComparator() {
    try {
      var clazz = Class.forName(
          "com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage$IBUFileNamesComparator");
      var ctor = clazz.getDeclaredConstructor();
      ctor.setAccessible(true);
      return (Comparator<String>) ctor.newInstance();
    } catch (ReflectiveOperationException e) {
      fail("Could not instantiate IBUFileNamesComparator: " + e.getMessage());
      return null; // unreachable
    }
  }

  /**
   * Verifies that IBUFileNamesComparator orders names by the portion before the
   * last dash, which encodes the backup timestamp.
   */
  @Test
  public void ibuFileNamesComparator_ordersByTimestampPortion() {
    var comparator = ibuFileNamesComparator();
    // Format: <uuid>-<timestamp>-<seq>-db
    var earlier = "aaa-2024-01-01-00-00-00-1-db";
    var later = "aaa-2024-06-01-00-00-00-2-db";
    // 'earlier' < 'later' lexicographically up to the last dash
    assertTrue(comparator.compare(earlier, later) < 0);
    assertTrue(comparator.compare(later, earlier) > 0);
    assertEquals(0, comparator.compare(earlier, earlier));
  }

  /**
   * Verifies that IBUFileNamesComparator throws IllegalArgumentException when
   * passed a name that contains no dash separator.
   */
  @Test
  public void ibuFileNamesComparator_throwsForInvalidName() {
    var comparator = ibuFileNamesComparator();
    try {
      comparator.compare("noDash", "aaa-2024-01-01-00-00-00-1-db");
      fail("Expected IllegalArgumentException for name without dash");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  // -----------------------------------------------------------------------
  // XXHashOutputStream — private static nested class via reflection
  // -----------------------------------------------------------------------

  /**
   * Obtains an instance of the private {@code XXHashOutputStream} via reflection,
   * writes through all three write overloads, and verifies the wrapped
   * {@code ByteArrayOutputStream} contains the expected bytes.
   *
   * <p>Exercises the three write paths ({@code write(byte[])},
   * {@code write(byte[], int, int)}, {@code write(int)}) that contribute to incremental
   * hash computation. Renamed from {@code xxHashOutputStream_writeDelegatesAndHashUpdates}
   * to reflect what is actually asserted: byte-stream delegation. The hash-update
   * contract is pinned by the companion test
   * {@link #xxHashOutputStream_hashStateAdvancesAfterWrites}.
   */
  @Test
  public void xxHashOutputStream_writeDelegatesToWrappedStream() throws Exception {
    var clazz = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage$XXHashOutputStream");
    Constructor<?> ctor = clazz.getDeclaredConstructor(java.io.OutputStream.class);
    ctor.setAccessible(true);

    var underlying = new ByteArrayOutputStream();
    @SuppressWarnings("resource")
    java.io.OutputStream xxOut = (java.io.OutputStream) ctor.newInstance(underlying);

    // write(byte[])
    xxOut.write(new byte[] {1, 2, 3});
    // write(byte[], int, int) — production override delegates super.write(bts, st, end)
    // verbatim, so this writes 2 bytes from offset 1: {5, 6}.
    xxOut.write(new byte[] {4, 5, 6, 7}, 1, 2);
    // write(int)
    xxOut.write(0xFF);
    // close — should not throw
    xxOut.close();

    // Verify the underlying stream received all bytes in order
    var bytes = underlying.toByteArray();
    assertEquals(6, bytes.length);
    assertEquals((byte) 1, bytes[0]);
    assertEquals((byte) 2, bytes[1]);
    assertEquals((byte) 3, bytes[2]);
    assertEquals((byte) 5, bytes[3]);
    assertEquals((byte) 6, bytes[4]);
    assertEquals((byte) 0xFF, bytes[5]);
  }

  /**
   * Pins the hash-update contract of {@code XXHashOutputStream}: every {@code write}
   * overload must mutate the internal {@code xxHash64} state. Without this assertion,
   * removing every {@code xxHash64.update(...)} call from the three write overrides
   * would still pass {@link #xxHashOutputStream_writeDelegatesToWrappedStream} —
   * yet the wrapper's whole purpose is to compute an incremental hash that production
   * reads back at {@link
   * com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage}'s
   * {@code writeBackupMetadata} (around line 1025) for the backup metadata footer.
   *
   * <p>Each of the three write overloads is checked independently: the hash value
   * must (a) change away from the seed-only state after {@code write(byte[])},
   * (b) change again after {@code write(byte[], int, int)}, and (c) change a third
   * time after {@code write(int)}. Reading {@code xxHash64.getValue()} reflectively
   * via the private field keeps the test independent of any future renames of the
   * field — and avoids brittleness against the seed value (which is a private
   * constant). The test is independent of byte-level delegation and therefore
   * complements (not duplicates) the companion delegation test.
   */
  @Test
  public void xxHashOutputStream_hashStateAdvancesAfterWrites() throws Exception {
    var clazz = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage$XXHashOutputStream");
    Constructor<?> ctor = clazz.getDeclaredConstructor(java.io.OutputStream.class);
    ctor.setAccessible(true);

    var underlying = new ByteArrayOutputStream();
    @SuppressWarnings("resource")
    java.io.OutputStream xxOut = (java.io.OutputStream) ctor.newInstance(underlying);

    var hashField = clazz.getDeclaredField("xxHash64");
    hashField.setAccessible(true);
    var streamingHash = hashField.get(xxOut);
    // Look up getValue() on the public abstract class, not the JNI subclass — the JNI
    // subclass is in an unexported module, so reflective invocation against its declared
    // method throws IllegalAccessException ("cannot access a member of class
    // StreamingXXHash64JNI with modifiers public synchronized") despite setAccessible.
    var getValue = Class.forName("net.jpountz.xxhash.StreamingXXHash64").getMethod("getValue");

    // Hash state at construction time — the seed-only baseline. We capture this
    // by calling getValue() on a fresh stream; whatever the seed produces is the
    // baseline. We don't hardcode the seed — it's a private constant subject to
    // change, and pinning it here would couple this test to that constant.
    long initialHash = (long) getValue.invoke(streamingHash);

    // write(byte[]) — must advance the hash.
    xxOut.write(new byte[] {1, 2, 3});
    long afterByteArray = (long) getValue.invoke(streamingHash);
    assertTrue(
        "write(byte[]) must update xxHash64 — hash should differ from initial",
        afterByteArray != initialHash);

    // write(byte[], int, int) — must advance the hash again.
    xxOut.write(new byte[] {4, 5, 6, 7}, 1, 2);
    long afterByteArrayOffsetLen = (long) getValue.invoke(streamingHash);
    assertTrue(
        "write(byte[], int, int) must update xxHash64 — hash should differ from prior",
        afterByteArrayOffsetLen != afterByteArray);

    // write(int) — must advance the hash a third time.
    xxOut.write(0xFF);
    long afterInt = (long) getValue.invoke(streamingHash);
    assertTrue(
        "write(int) must update xxHash64 — hash should differ from prior",
        afterInt != afterByteArrayOffsetLen);

    // close — does not throw.
    xxOut.close();
  }
}
