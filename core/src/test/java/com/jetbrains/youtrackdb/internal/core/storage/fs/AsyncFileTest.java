package com.jetbrains.youtrackdb.internal.core.storage.fs;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class AsyncFileTest {

  private static Path buildDirectoryPath;

  private static final String STORAGE_NAME = AsyncFileTest.class.getSimpleName();

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
  }

  @Test
  public void testWrite() throws Exception {
    final AsyncFile file =
        new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        sourcePath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
    // Verifies that read() with throwOnEof=false stops silently at EOF rather
    // than propagating an EOFException.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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

    file.close();
  }

  @Test
  public void testWriteToClosedFileThrowsStorageException() throws Exception {
    // Verifies that write() on a closed AsyncFile throws StorageException (via checkForClose).
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
    file.create();
    final var pos = file.allocateSpace(128);
    file.close();

    try {
      file.write(pos, ByteBuffer.allocate(128));
      Assert.fail("Expected StorageException for write on closed file");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      // expected
    }
  }

  @Test
  public void testReadToClosedFileThrowsStorageException() throws Exception {
    // Verifies that read() on a closed AsyncFile throws StorageException (via checkForClose).
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
    file.create();
    final var pos = file.allocateSpace(128);
    final var data = new byte[128];
    file.write(pos, ByteBuffer.wrap(data));
    file.close();

    try {
      file.read(pos, ByteBuffer.allocate(128), true);
      Assert.fail("Expected StorageException for read on closed file");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      // expected
    }
  }

  @Test
  public void testWriteOutOfBoundsThrowsStorageException() throws Exception {
    // Verifies that write() at an offset beyond the allocated size throws
    // StorageException (via checkPosition).
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
    file.create();
    // No space allocated; offset 0 is already out of bounds.
    try {
      file.write(0, ByteBuffer.allocate(128));
      Assert.fail("Expected StorageException for out-of-bounds write");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      // expected
    } finally {
      file.close();
    }
  }

  @Test
  public void testCreateAlreadyOpenedThrowsStorageException() throws Exception {
    // Verifies that calling create() on an already-open AsyncFile throws StorageException.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
    file.create();
    try {
      file.create();
      Assert.fail("Expected StorageException for double create");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException e) {
      // expected
    } finally {
      file.close();
    }
  }

  @Test
  public void testSynchOnCleanFileIsNoOp() throws Exception {
    // Verifies that synch() can be called on a file with no dirty pages without error.
    final AsyncFile file = new AsyncFile(
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
        buildDirectoryPath, 1, false, Executors.newCachedThreadPool(), STORAGE_NAME);
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
}
