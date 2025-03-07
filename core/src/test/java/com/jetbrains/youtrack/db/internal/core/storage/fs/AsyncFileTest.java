package com.jetbrains.youtrack.db.internal.core.storage.fs;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.util.RawPairLongObject;
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
    String buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + "localPaginatedClusterTestV2";
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

    final long position = file.allocateSpace(1024);
    Assert.assertEquals(128 + 256, position);

    final byte[] data = new byte[1024];
    final Random random = new Random();

    random.nextBytes(data);

    file.write(position, ByteBuffer.wrap(data));

    final ByteBuffer result = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
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

    final long position = file.allocateSpace(1024);
    Assert.assertEquals(128 + 256, position);

    final byte[] data = new byte[1024];
    final Random random = new Random();

    random.nextBytes(data);

    file.write(position, ByteBuffer.wrap(data));

    file.close();
    file.open();

    final ByteBuffer result = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
    file.read(position, result, true);
    Assert.assertArrayEquals(data, result.array());

    file.close();
  }

  @Test
  public void testWriteSeveralChunks() throws Exception {
    AsyncFile file = new AsyncFile(buildDirectoryPath, 1, false, Executors.newCachedThreadPool(),
        STORAGE_NAME);
    file.create();

    final long position1 = file.allocateSpace(128);
    final long position2 = file.allocateSpace(256);
    final long position3 = file.allocateSpace(1024);

    Assert.assertEquals(0, position1);
    Assert.assertEquals(128, position2);
    Assert.assertEquals(128 + 256, position3);

    final byte[] data1 = new byte[128];
    final byte[] data2 = new byte[256];
    final byte[] data3 = new byte[1024];

    final Random random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final IOResult result = file.write(buffers);
    result.await();

    final ByteBuffer result1 = ByteBuffer.allocate(128);
    final ByteBuffer result2 = ByteBuffer.allocate(256);
    final ByteBuffer result3 = ByteBuffer.allocate(1024);

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

    final long position1 = file.allocateSpace(128);
    final long position2 = file.allocateSpace(256);
    final long position3 = file.allocateSpace(1024);

    final byte[] data1 = new byte[128];
    final byte[] data2 = new byte[256];
    final byte[] data3 = new byte[1024];

    final Random random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final IOResult result = file.write(buffers);
    result.await();
    file.close();
    file.open();

    final ByteBuffer result1 = ByteBuffer.allocate(128);
    final ByteBuffer result2 = ByteBuffer.allocate(256);
    final ByteBuffer result3 = ByteBuffer.allocate(1024);

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

    final long position1 = file.allocateSpace(128);
    final long position2 = file.allocateSpace(256);
    final long position3 = file.allocateSpace(1024);

    final byte[] data1 = new byte[128];
    final byte[] data2 = new byte[256];
    final byte[] data3 = new byte[1024];

    final Random random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));

    final IOResult result = file.write(buffers);
    result.await();

    file.write(position3, ByteBuffer.wrap(data3));

    final ByteBuffer result1 = ByteBuffer.allocate(128);
    final ByteBuffer result2 = ByteBuffer.allocate(256);
    final ByteBuffer result3 = ByteBuffer.allocate(1024);

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

    final long position1 = file.allocateSpace(128 * 1024);
    final long position2 = file.allocateSpace(256 * 1024);
    final long position3 = file.allocateSpace(1024 * 1024);

    Assert.assertEquals(0, position1);
    Assert.assertEquals(128 * 1024, position2);
    Assert.assertEquals(128 * 1024 + 256 * 1024, position3);

    final byte[] data1 = new byte[128 * 1024];
    final byte[] data2 = new byte[256 * 1024];
    final byte[] data3 = new byte[1024 * 1024];

    final Random random = new Random();

    random.nextBytes(data1);
    random.nextBytes(data2);
    random.nextBytes(data3);

    final List<RawPairLongObject<ByteBuffer>> buffers = new ArrayList<>();

    buffers.add(new RawPairLongObject<>(position1, ByteBuffer.wrap(data1)));
    buffers.add(new RawPairLongObject<>(position2, ByteBuffer.wrap(data2)));
    buffers.add(new RawPairLongObject<>(position3, ByteBuffer.wrap(data3)));

    final IOResult result = file.write(buffers);
    result.await();
    file.close();
    file.open();

    final ByteBuffer result1 = ByteBuffer.allocate(128 * 1024);
    final ByteBuffer result2 = ByteBuffer.allocate(256 * 1024);
    final ByteBuffer result3 = ByteBuffer.allocate(1024 * 1024);

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
}
