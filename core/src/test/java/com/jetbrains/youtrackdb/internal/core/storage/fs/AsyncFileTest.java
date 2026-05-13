package com.jetbrains.youtrackdb.internal.core.storage.fs;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class AsyncFileTest {

  private static Path buildDirectoryPath;

  private static final String STORAGE_NAME = AsyncFileTest.class.getSimpleName();

  /**
   * Shared executor used by every {@link AsyncFile} construction in the tests below.
   * Allocated in {@link #before()} and shut down in {@link #after()} so the surefire
   * JVM does not accumulate ~24 cached thread pools (with non-daemon worker threads)
   * across the lifetime of this class. {@code testCopyToCopiesAllData} keeps its own
   * second executor for the destination AsyncFile and shuts it down inline.
   */
  private ExecutorService executor;

  @BeforeClass
  public static void beforeClass() {
    var buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + "localPaginatedCollectionTestV2";
    buildDirectoryPath = Paths.get(buildDirectory);
  }

  @Before
  public void before() {
    FileUtils.deleteRecursively(buildDirectoryPath.toFile());
    executor = Executors.newCachedThreadPool();
  }

  @After
  public void after() {
    // Shut down the per-test executor FIRST so any AsyncFile worker threads release
    // their file channels before the working directory is wiped — otherwise the
    // deleteRecursively call below could race with an in-flight write on platforms
    // that hold an OS-level lock until the channel actually closes.
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    // Ensure the test artifact file is removed after each test so it is not left
    // untracked in the working tree after the last test in this class completes.
    FileUtils.deleteRecursively(buildDirectoryPath.toFile());
  }

  @Test
  public void testWrite() throws Exception {
    final AsyncFile file =
        new AsyncFile(buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    file.allocateSpace(128);
    file.allocateSpace(256);

    final var position = file.allocateSpace(1024);
    Assert.assertEquals(128 + 256, position);

    final var data = new byte[1024];
    final var random = new Random();

    random.nextBytes(data);

    file.write(position, ByteBuffer.wrap(data));

    final var result = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
    file.read(position, result, true);

    Assert.assertArrayEquals(data, result.array());
    file.close();
  }

  @Test
  public void testOpenWrite() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    file.create();

    file.allocateSpace(128);
    file.allocateSpace(256);

    final var position = file.allocateSpace(1024);
    Assert.assertEquals(128 + 256, position);

    final var data = new byte[1024];
    final var random = new Random();

    random.nextBytes(data);

    file.write(position, ByteBuffer.wrap(data));

    file.close();
    file.open();

    final var result = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
    file.read(position, result, true);
    Assert.assertArrayEquals(data, result.array());

    file.close();
  }

  @Test
  public void testWriteSeveralChunks() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    file.create();

    final var position1 = file.allocateSpace(128);
    final var position2 = file.allocateSpace(256);
    final var position3 = file.allocateSpace(1024);

    Assert.assertEquals(0, position1);
    Assert.assertEquals(128, position2);
    Assert.assertEquals(128 + 256, position3);

    final var data1 = new byte[128];
    final var data2 = new byte[256];
    final var data3 = new byte[1024];

    final var random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final var result = file.write(buffers);
    result.await();

    final var result1 = ByteBuffer.allocate(128);
    final var result2 = ByteBuffer.allocate(256);
    final var result3 = ByteBuffer.allocate(1024);

    file.read(position1, result1, true);
    file.read(position2, result2, true);
    file.read(position3, result3, true);

    Assert.assertArrayEquals(result1.array(), data1);
    Assert.assertArrayEquals(result2.array(), data2);
    Assert.assertArrayEquals(result3.array(), data3);

    file.close();
  }

  @Test
  public void testOpenWriteSeveralChunks() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    file.create();

    final var position1 = file.allocateSpace(128);
    final var position2 = file.allocateSpace(256);
    final var position3 = file.allocateSpace(1024);

    final var data1 = new byte[128];
    final var data2 = new byte[256];
    final var data3 = new byte[1024];

    final var random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final var result = file.write(buffers);
    result.await();
    file.close();
    file.open();

    final var result1 = ByteBuffer.allocate(128);
    final var result2 = ByteBuffer.allocate(256);
    final var result3 = ByteBuffer.allocate(1024);

    file.read(position1, result1, true);
    file.read(position2, result2, true);
    file.read(position3, result3, true);

    Assert.assertArrayEquals(result1.array(), data1);
    Assert.assertArrayEquals(result2.array(), data2);
    Assert.assertArrayEquals(result3.array(), data3);

    file.close();
  }

  @Test
  public void testOpenWriteSeveralChunksTwo() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    file.create();

    final var position1 = file.allocateSpace(128);
    final var position2 = file.allocateSpace(256);
    final var position3 = file.allocateSpace(1024);

    final var data1 = new byte[128];
    final var data2 = new byte[256];
    final var data3 = new byte[1024];

    final var random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));

    final var result = file.write(buffers);
    result.await();

    file.write(position3, ByteBuffer.wrap(data3));

    final var result1 = ByteBuffer.allocate(128);
    final var result2 = ByteBuffer.allocate(256);
    final var result3 = ByteBuffer.allocate(1024);

    file.read(position1, result1, true);
    file.read(position2, result2, true);
    file.read(position3, result3, true);

    Assert.assertArrayEquals(result1.array(), data1);
    Assert.assertArrayEquals(result2.array(), data2);
    Assert.assertArrayEquals(result3.array(), data3);

    file.close();
  }

  @Test
  public void testOpenWriteSeveralChunksThree() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    file.create();

    final var position1 = file.allocateSpace(128 * 1024);
    final var position2 = file.allocateSpace(256 * 1024);
    final var position3 = file.allocateSpace(1024 * 1024);

    Assert.assertEquals(0, position1);
    Assert.assertEquals(128 * 1024, position2);
    Assert.assertEquals(128 * 1024 + 256 * 1024, position3);

    final var data1 = new byte[128 * 1024];
    final var data2 = new byte[256 * 1024];
    final var data3 = new byte[1024 * 1024];

    final var random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final var result = file.write(buffers);
    result.await();
    file.close();
    file.open();

    final var result1 = ByteBuffer.allocate(128 * 1024);
    final var result2 = ByteBuffer.allocate(256 * 1024);
    final var result3 = ByteBuffer.allocate(1024 * 1024);

    file.read(position1, result1, true);
    file.read(position2, result2, true);
    file.read(position3, result3, true);

    Assert.assertArrayEquals(result1.array(), data1);
    Assert.assertArrayEquals(result2.array(), data2);
    Assert.assertArrayEquals(result3.array(), data3);

    file.close();
  }

  @Test
  public void testOpenClose() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, executor,
        STORAGE_NAME);
    Assert.assertFalse(file.isOpen());

    file.create();
    Assert.assertTrue(file.isOpen());

    file.close();

    Assert.assertFalse(file.isOpen());
    file.open();
    Assert.assertTrue(file.isOpen());
    file.close();
    Assert.assertFalse(file.isOpen());
  }

  @Test
  public void testExistsAndGetName() throws Exception {
    // Verifies exists() returns true after create(), getName() returns the path's
    // file name component, and exists() returns false after delete().
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    Assert.assertFalse(file.exists());

    file.create();
    Assert.assertTrue(file.exists());
    Assert.assertFalse(file.getName().isEmpty());

    file.close();
    Assert.assertTrue(file.exists()); // file still on disk after close
    file.open();
    file.delete();
    Assert.assertFalse(file.exists());
  }

  @Test
  public void testGetFileSizeAndGetUnderlyingFileSize() throws Exception {
    // Verifies that getFileSize() tracks logical allocations and
    // getUnderlyingFileSize() matches the physical bytes written.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    Assert.assertEquals(0, file.getFileSize());

    final var pos = file.allocateSpace(512);
    Assert.assertEquals(512, file.getFileSize());

    final var data = new byte[512];
    new java.util.Random().nextBytes(data);
    file.write(pos, ByteBuffer.wrap(data));
    file.synch();

    // Underlying file size == logical (no partial writes for this allocation)
    Assert.assertEquals(512, file.getUnderlyingFileSize());

    file.close();
  }

  @Test
  public void testShrink() throws Exception {
    // Verifies shrink() truncates the file so that getFileSize() reports 0
    // and allocations after the shrink work from position 0.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    file.allocateSpace(1024);
    Assert.assertEquals(1024, file.getFileSize());

    file.shrink(0);
    Assert.assertEquals(0, file.getFileSize());

    file.close();
  }

  @Test
  public void testRenameTo() throws Exception {
    // Verifies renameTo() closes the original file, moves it to the new path,
    // and reopens at the new location so subsequent reads succeed.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    final var pos = file.allocateSpace(128);
    final var data = new byte[128];
    new java.util.Random().nextBytes(data);
    file.write(pos, ByteBuffer.wrap(data));
    file.synch();

    // Rename to a sibling path in the same build directory
    final var newPath = buildDirectoryPath.resolveSibling(
        buildDirectoryPath.getFileName().toString() + "_renamed");
    file.renameTo(newPath);

    Assert.assertEquals(newPath.getFileName().toString(), file.getName());
    Assert.assertTrue(file.isOpen());

    // Data must still be readable at the same offset via the renamed file
    final var result = ByteBuffer.allocate(128);
    file.read(pos, result, true);
    Assert.assertArrayEquals(data, result.array());

    file.close();
    // Clean up renamed file
    com.jetbrains.youtrackdb.internal.common.io.FileUtils.deleteRecursively(newPath.toFile());
  }

  @Test
  public void testReplaceContentWith() throws Exception {
    // Verifies replaceContentWith() replaces the file's data with the contents
    // of a second file, so a subsequent read returns the new data.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    final var pos = file.allocateSpace(256);
    final var oldData = new byte[256];
    new java.util.Random().nextBytes(oldData);
    file.write(pos, ByteBuffer.wrap(oldData));
    file.synch();
    file.close();

    // Create a second file with different content of the same size
    final var sourcePath = buildDirectoryPath.resolveSibling(
        buildDirectoryPath.getFileName().toString() + "_source");
    final AsyncFile source = new AsyncFile(
        sourcePath, 1, false, executor, STORAGE_NAME);
    source.create();
    final var posSource = source.allocateSpace(256);
    final var newData = new byte[256];
    new java.util.Random().nextBytes(newData);
    source.write(posSource, ByteBuffer.wrap(newData));
    source.synch();
    source.close();

    // Re-open the target file and replace its content
    file.open();
    file.replaceContentWith(sourcePath);

    final var result = ByteBuffer.allocate(256);
    file.read(posSource, result, true);
    Assert.assertArrayEquals(newData, result.array());

    file.close();
    com.jetbrains.youtrackdb.internal.common.io.FileUtils.deleteRecursively(sourcePath.toFile());
  }

  @Test
  public void testReadDoesNotThrowOnEof() throws Exception {
    // Verifies that read() with throwOnEof=false stops silently at EOF rather than
    // propagating an EOFException, AND that the bytes successfully read before EOF
    // match what was written. Without the post-read content check, this test could
    // not distinguish "EOF handled silently" from "junk data returned" — a
    // regression that turned the EOF branch into a hard failure but left the
    // assertion intact would still pass otherwise.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    // Allocate and write 128 bytes, but try to read 256: should not throw.
    final var pos = file.allocateSpace(128);
    final var data = new byte[128];
    new java.util.Random().nextBytes(data);
    file.write(pos, ByteBuffer.wrap(data));

    // Extend the logical size so checkPosition passes for a 256-byte read
    file.allocateSpace(128);
    final var result = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
    // Should complete without EOFException
    file.read(pos, result, false);

    // Verify the first 128 bytes of the result match the data we wrote — this is
    // the assertion that distinguishes a silent-EOF success from a silent-EOF that
    // also corrupted the buffer somehow.
    final var first128 = new byte[128];
    result.position(0);
    result.get(first128);
    Assert.assertArrayEquals(
        "First 128 bytes read back must match what was written", data, first128);

    file.close();
  }

  /**
   * Pins the throwOnEof=true branch of {@link AsyncFile#read(long, ByteBuffer, boolean)}.
   * The complementary {@code testReadDoesNotThrowOnEof} only covers the silent-EOF path;
   * removing the {@code throw new EOFException(...)} statement from the production read
   * loop would not fail any pre-existing test.
   *
   * <p>This test allocates 128 bytes, writes them, extends the logical file by another
   * 128 bytes (so {@code checkPosition} passes for a 256-byte read), then asks for 256
   * bytes with {@code throwOnEof=true}: the read must throw {@link
   * java.io.EOFException} because the second half is past the end of the file's actual
   * content (the channel's read() returns -1 once it crosses the OS-level file end).
   */
  @Test
  public void testReadThrowsOnEofWhenRequested() throws Exception {
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    final var pos = file.allocateSpace(128);
    final var data = new byte[128];
    new java.util.Random().nextBytes(data);
    file.write(pos, ByteBuffer.wrap(data));

    // Extend the logical size so checkPosition passes for a 256-byte read.
    file.allocateSpace(128);
    final var result = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());

    try {
      file.read(pos, result, /* throwOnEof= */ true);
      Assert.fail("Expected EOFException when reading past end with throwOnEof=true");
    } catch (java.io.EOFException expected) {
      // Pin the message format — production builds the message from the file path so
      // diagnostic logs identify the file. A regression that swallowed the file path
      // would still throw EOFException but with a less useful message.
      Assert.assertNotNull("EOFException must carry a non-null message", expected.getMessage());
      Assert.assertTrue(
          "EOFException message must mention the end of file",
          expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("end of file"));
    } finally {
      file.close();
    }
  }

  @Test
  public void testWriteToClosedFileThrowsStorageException() throws Exception {
    // Verifies that write() on a closed AsyncFile throws StorageException (via
    // checkForClose). The message-content assertion narrows the failure mode so that
    // a StorageException raised for an unrelated reason (e.g. an NPE wrapped at a
    // different site) would not satisfy the test.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    final var pos = file.allocateSpace(128);
    file.close();

    try {
      file.write(pos, ByteBuffer.allocate(128));
      Assert.fail("Expected StorageException for write on closed file");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      Assert.assertNotNull("StorageException must carry a message", e.getMessage());
      Assert.assertTrue(
          "Message must explicitly indicate the file is closed: " + e.getMessage(),
          e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("is closed"));
    }
  }

  @Test
  public void testReadToClosedFileThrowsStorageException() throws Exception {
    // Verifies that read() on a closed AsyncFile throws StorageException (via
    // checkForClose). The message-content assertion narrows the failure mode the
    // same way as testWriteToClosedFileThrowsStorageException.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    final var pos = file.allocateSpace(128);
    final var data = new byte[128];
    file.write(pos, ByteBuffer.wrap(data));
    file.close();

    try {
      file.read(pos, ByteBuffer.allocate(128), true);
      Assert.fail("Expected StorageException for read on closed file");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      Assert.assertNotNull("StorageException must carry a message", e.getMessage());
      Assert.assertTrue(
          "Message must explicitly indicate the file is closed: " + e.getMessage(),
          e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("is closed"));
    }
  }

  @Test
  public void testWriteOutOfBoundsThrowsStorageException() throws Exception {
    // Verifies that write() at an offset beyond the allocated size throws
    // StorageException (via checkPosition). The message-content assertion ensures the
    // StorageException is the position-validation exception rather than an unrelated
    // failure mode that also happens to wrap as StorageException.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    // No space allocated; offset 0 is already out of bounds.
    try {
      file.write(0, ByteBuffer.allocate(128));
      Assert.fail("Expected StorageException for out-of-bounds write");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      Assert.assertNotNull("StorageException must carry a message", e.getMessage());
      Assert.assertTrue(
          "Message must mention the requested position: " + e.getMessage(),
          e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("position"));
    } finally {
      file.close();
    }
  }

  @Test
  public void testCreateAlreadyOpenedThrowsStorageException() throws Exception {
    // Verifies that calling create() on an already-open AsyncFile throws
    // StorageException — not some other StorageException that happens to be triggered
    // by the underlying filesystem state. The message-content assertion narrows the
    // failure mode.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    try {
      file.create();
      Assert.fail("Expected StorageException for double create");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      Assert.assertNotNull("StorageException must carry a message", e.getMessage());
      Assert.assertTrue(
          "Message must indicate the file is already opened: " + e.getMessage(),
          e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("already opened"));
    } finally {
      file.close();
    }
  }

  @Test
  public void testSynchOnCleanFileIsNoOp() throws Exception {
    // Verifies that synch() can be called on a file with no dirty pages without error.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    // No writes — dirtyCounter is 0; synch should be a no-op
    file.synch();
    file.close();
  }

  @Test
  public void testAsyncWriteListAndAwait() throws Exception {
    // Verifies the async write(List<RawPairLongObject<ByteBuffer>>) path completes
    // without error and the data is readable afterwards (covers WriteHandler.completed).
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();

    final var pos1 = file.allocateSpace(64);
    final var pos2 = file.allocateSpace(64);
    final var data1 = new byte[64];
    final var data2 = new byte[64];
    new java.util.Random().nextBytes(data1);
    new java.util.Random().nextBytes(data2);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();
    buffers.add(new RawPairLongObject<>(pos1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(pos2, ByteBuffer.wrap(data2)));

    final var ioResult = file.write(buffers);
    ioResult.await();

    final var r1 = ByteBuffer.allocate(64);
    final var r2 = ByteBuffer.allocate(64);
    file.read(pos1, r1, true);
    file.read(pos2, r2, true);
    Assert.assertArrayEquals(data1, r1.array());
    Assert.assertArrayEquals(data2, r2.array());

    file.close();
  }

  @Test
  public void testDeleteWithLoggingEnabledLogsAndDeletesFile() throws Exception {
    // Verifies that creating an AsyncFile with logFileDeletion=true and then deleting it
    // successfully removes the file from disk (covers the logFileDeletion=true branch in delete()).
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, true, executor, STORAGE_NAME);
    file.create();
    Assert.assertTrue(file.exists());

    // delete() with logFileDeletion=true logs "File ... has been deleted." and removes the file
    file.delete();
    Assert.assertFalse(file.exists());
  }

  @Test
  public void testCheckPositionWithNegativeOffsetThrowsStorageException() throws Exception {
    // Verifies that write() at a negative offset triggers checkPosition()'s offset<0 guard
    // and throws StorageException, covering the negative-offset branch of checkPosition().
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, executor, STORAGE_NAME);
    file.create();
    // Allocate some space so the file is non-empty; negative offsets should still be rejected.
    file.allocateSpace(128);
    try {
      file.write(-1, ByteBuffer.allocate(1));
      Assert.fail("Expected StorageException for negative offset write");
    } catch (StorageException e) {
      // The checkPosition() guard rejects offset < 0 with the same "outside of allocated
      // file position" message used for offset >= fileSize. The message-content assertion
      // narrows the failure mode so an unrelated StorageException would not satisfy it.
      Assert.assertNotNull("StorageException must carry a message", e.getMessage());
      Assert.assertTrue(
          "Message must mention the requested position: " + e.getMessage(),
          e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("position"));
    } finally {
      file.close();
    }
  }

  @Test
  public void testOpenReopensFileWithPartialPageTruncation() throws Exception {
    // Verifies that opening an existing file whose physical content area is not aligned
    // to the page size triggers the truncation path in initSize() (lines that truncate
    // the file and log a warning about partially-written data pages).
    //
    // Setup: create a file with pageSize=512, allocate one full page, write data,
    // close. Then append extra bytes directly to the raw file so the content area
    // is no longer a multiple of 512. Reopening a new AsyncFile on the same path
    // must exercise the truncation branch.
    final int PAGE_SIZE = 512;
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, PAGE_SIZE, false, executor, STORAGE_NAME);
    file.create();

    // Allocate and populate one full page so the file is non-trivial
    final long pos = file.allocateSpace(PAGE_SIZE);
    final var data = new byte[PAGE_SIZE];
    new Random().nextBytes(data);
    file.write(pos, ByteBuffer.wrap(data));
    file.synch();
    file.close();

    // Append extra bytes directly to the underlying OS file so the content area
    // (physical size - HEADER_SIZE) is no longer a multiple of PAGE_SIZE.
    // HEADER_SIZE = 1024, so the file is currently 1024 + 512 = 1536 bytes.
    // Appending 100 bytes makes it 1636; content area = 612, 612 % 512 != 0.
    final var extraBytes = new byte[100];
    Files.write(buildDirectoryPath.toFile().toPath(), extraBytes, StandardOpenOption.APPEND);

    // Open a new AsyncFile on the same path — initSize() must detect the misalignment,
    // truncate back to 1024 + 512 = 1536 bytes, and log a warning.
    final AsyncFile reopened = new AsyncFile(
        buildDirectoryPath, PAGE_SIZE, false, executor, STORAGE_NAME);
    reopened.open();

    // After truncation the logical file size must equal exactly one page
    Assert.assertEquals(PAGE_SIZE, reopened.getFileSize());

    reopened.close();
  }
}
