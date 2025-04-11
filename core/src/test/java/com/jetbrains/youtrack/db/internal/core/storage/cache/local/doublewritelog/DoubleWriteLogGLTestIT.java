package com.jetbrains.youtrack.db.internal.core.storage.cache.local.doublewritelog;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class DoubleWriteLogGLTestIT {

  private static String buildDirectory;

  @BeforeClass
  public static void beforeClass() throws Exception {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + DoubleWriteLogGLTestIT.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));
    Files.createDirectories(Paths.get(buildDirectory));
  }

  @Test
  public void testWriteSinglePage() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);

    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        Pointer pointer;

        final var buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
        var random = ThreadLocalRandom.current();

        final var data = new byte[pageSize];
        random.nextBytes(data);

        buffer.put(data);

        buffer.position(0);
        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));
        doubleWriteLog.truncate();

        pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        Assert.assertNull(pointer);

        doubleWriteLog.restoreModeOn();

        pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        final var loadedBuffer = pointer.getNativeByteBuffer();

        Assert.assertEquals(256, loadedBuffer.limit());
        final var loadedData = new byte[256];
        loadedBuffer.rewind();
        loadedBuffer.get(loadedData);

        Assert.assertArrayEquals(data, loadedData);
        bufferPool.release(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        Assert.assertNull(pointer);
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testWriteSinglePageTwoTimes() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);

    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        final var buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
        var random = ThreadLocalRandom.current();

        final var data = new byte[pageSize];
        random.nextBytes(data);

        buffer.put(data);

        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));

        buffer.rewind();
        random.nextBytes(data);
        buffer.put(data);

        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));
        doubleWriteLog.truncate();

        var pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        Assert.assertNull(pointer);

        doubleWriteLog.restoreModeOn();

        pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        final var loadedBuffer = pointer.getNativeByteBuffer();

        Assert.assertEquals(256, loadedBuffer.limit());
        final var loadedData = new byte[256];
        loadedBuffer.rewind();
        loadedBuffer.get(loadedData);

        Assert.assertArrayEquals(data, loadedData);
        bufferPool.release(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        Assert.assertNull(pointer);
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testWriteTwoPagesSameFile() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var random = ThreadLocalRandom.current();

        List<byte[]> datas = new ArrayList<>();
        for (var i = 0; i < 2; i++) {
          final var data = new byte[pageSize];
          random.nextBytes(data);
          datas.add(data);
        }

        final var buffer =
            ByteBuffer.allocate(pageSize * datas.size()).order(ByteOrder.nativeOrder());
        for (final var data : datas) {
          buffer.put(data);
        }

        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));

        var pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        Assert.assertNull(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        Assert.assertNull(pointer);

        doubleWriteLog.restoreModeOn();

        pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        var loadedBuffer = pointer.getNativeByteBuffer();

        Assert.assertEquals(256, loadedBuffer.limit());
        var loadedData = new byte[256];
        loadedBuffer.rewind();
        loadedBuffer.get(loadedData);

        Assert.assertArrayEquals(datas.get(0), loadedData);
        bufferPool.release(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        loadedBuffer = pointer.getNativeByteBuffer();

        Assert.assertEquals(256, loadedBuffer.limit());
        loadedData = new byte[256];
        loadedBuffer.rewind();
        loadedBuffer.get(loadedData);

        Assert.assertArrayEquals(datas.get(1), loadedData);
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testWriteTenPagesSameFile() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var random = ThreadLocalRandom.current();

        List<byte[]> datas = new ArrayList<>();
        for (var i = 0; i < 10; i++) {
          final var data = new byte[pageSize];
          random.nextBytes(data);
          datas.add(data);
        }

        var buffer =
            ByteBuffer.allocate(datas.size() * pageSize).order(ByteOrder.nativeOrder());
        for (final var data : datas) {
          buffer.put(data);
        }

        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));

        var pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        Assert.assertNull(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        Assert.assertNull(pointer);

        doubleWriteLog.restoreModeOn();

        for (var i = 0; i < 10; i++) {
          pointer = doubleWriteLog.loadPage(12, 24 + i, bufferPool);
          var loadedBuffer = pointer.getNativeByteBuffer();

          Assert.assertEquals(256, loadedBuffer.limit());
          var loadedData = new byte[256];
          loadedBuffer.rewind();
          loadedBuffer.get(loadedData);

          Assert.assertArrayEquals(datas.get(i), loadedData);
          bufferPool.release(pointer);
        }
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testWriteTenDifferentSinglePages() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var random = ThreadLocalRandom.current();

        List<byte[]> datas = new ArrayList<>();
        for (var i = 0; i < 10; i++) {
          final var data = new byte[pageSize];
          random.nextBytes(data);
          datas.add(data);
        }

        final var buffer =
            ByteBuffer.allocate(pageSize * datas.size()).order(ByteOrder.nativeOrder());
        for (var data : datas) {
          buffer.put(data);
        }
        doubleWriteLog.write(
            new ArrayList<>(Collections.singleton(buffer)),
            IntArrayList.of(12),
            IntArrayList.of(24));

        var pointer = doubleWriteLog.loadPage(12, 24, bufferPool);
        Assert.assertNull(pointer);

        pointer = doubleWriteLog.loadPage(12, 25, bufferPool);
        Assert.assertNull(pointer);

        doubleWriteLog.restoreModeOn();

        for (var i = 0; i < 10; i++) {
          pointer = doubleWriteLog.loadPage(12, 24 + i, bufferPool);
          var loadedBuffer = pointer.getNativeByteBuffer();

          Assert.assertEquals(256, loadedBuffer.limit());
          var loadedData = new byte[256];
          loadedBuffer.rewind();
          loadedBuffer.get(loadedData);

          Assert.assertArrayEquals(datas.get(i), loadedData);
          bufferPool.release(pointer);
        }
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testWriteTenDifferentPagesTenTimes() throws Exception {
    final var pageSize = 256;

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var random = ThreadLocalRandom.current();

        List<byte[]> datas = new ArrayList<>();
        for (var i = 0; i < 100; i++) {
          final var data = new byte[pageSize];
          random.nextBytes(data);
          datas.add(data);
        }

        for (var i = 0; i < 10; i++) {
          var buffer = ByteBuffer.allocate(10 * pageSize).order(ByteOrder.nativeOrder());

          for (var j = 0; j < 10; j++) {
            buffer.put(datas.get(i * 10 + j));
          }

          doubleWriteLog.write(
              new ArrayList<>(Collections.singleton(buffer)),
              IntArrayList.of(12 + i),
              IntArrayList.of(24));
        }

        doubleWriteLog.restoreModeOn();

        for (var i = 0; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var pointer = doubleWriteLog.loadPage(12 + i, 24 + j, bufferPool);

            var loadedBuffer = pointer.getNativeByteBuffer();

            Assert.assertEquals(256, loadedBuffer.limit());
            var loadedData = new byte[256];
            loadedBuffer.rewind();
            loadedBuffer.get(loadedData);

            Assert.assertArrayEquals(datas.get(i * 10 + j), loadedData);
            bufferPool.release(pointer);
          }
        }
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testRandomWriteOne() throws Exception {
    final var seed = System.nanoTime();
    System.out.println("testRandomWriteOne : seed " + seed);

    var random = new Random(seed);

    for (var n = 0; n < 10; n++) {
      System.out.println("Iteration - " + n);

      final var pageSize = 256;

      final var bufferPool = new ByteBufferPool(pageSize);
      try {
        final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);

        doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
        try {
          final var pagesToWrite = random.nextInt(20_000) + 100;

          List<byte[]> datas = new ArrayList<>();
          for (var i = 0; i < pagesToWrite; i++) {
            final var data = new byte[pageSize];
            random.nextBytes(data);
            datas.add(data);
          }

          var pageIndex = 0;
          var writtenPages = 0;

          while (writtenPages < pagesToWrite) {
            final var pagesForSinglePatch = random.nextInt(pagesToWrite - writtenPages) + 1;
            final var buffer =
                ByteBuffer.allocate(pagesForSinglePatch * pageSize).order(ByteOrder.nativeOrder());

            for (var j = 0; j < pagesForSinglePatch; j++) {
              buffer.put(datas.get(pageIndex + j));
            }

            doubleWriteLog.write(
                new ArrayList<>(Collections.singleton(buffer)),
                IntArrayList.of(12),
                IntArrayList.of(24 + pageIndex));
            pageIndex += pagesForSinglePatch;
            writtenPages += pagesForSinglePatch;
          }

          doubleWriteLog.restoreModeOn();

          for (var i = 0; i < pagesToWrite; i++) {
            final var pointer = doubleWriteLog.loadPage(12, 24 + i, bufferPool);

            var loadedBuffer = pointer.getNativeByteBuffer();

            Assert.assertEquals(256, loadedBuffer.limit());
            var loadedData = new byte[256];
            loadedBuffer.rewind();
            loadedBuffer.get(loadedData);

            Assert.assertArrayEquals(datas.get(i), loadedData);
            bufferPool.release(pointer);
          }

        } finally {
          doubleWriteLog.close();
        }
      } finally {
        bufferPool.clear();
      }
    }
  }

  @Test
  public void testRandomWriteTwo() throws Exception {
    final var seed = System.nanoTime();
    System.out.println("testRandomWriteTwo : seed " + seed);

    final var random = new Random(seed);
    final var pageSize = 256;

    for (var n = 0; n < 10; n++) {
      System.out.println("Iteration - " + n);

      final var bufferPool = new ByteBufferPool(pageSize);
      try {
        final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);
        doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
        try {
          final Map<Integer, ByteBuffer> pageMap = new HashMap<>();
          final var pages = random.nextInt(900) + 100;

          System.out.println("testRandomWriteTwo : pages " + pages);

          for (var k = 0; k < 100; k++) {
            final var pagesToWrite = random.nextInt(pages - 1) + 1;

            List<byte[]> datas = new ArrayList<>();
            for (var i = 0; i < pagesToWrite; i++) {
              final var data = new byte[pageSize];
              random.nextBytes(data);
              datas.add(data);
            }

            final var startPageIndex = random.nextInt(pages);
            var pageIndex = 0;
            var writtenPages = 0;

            while (writtenPages < pagesToWrite) {
              final var pagesForSinglePatch = random.nextInt(pagesToWrite - writtenPages) + 1;
              var buffers = new ByteBuffer[pagesForSinglePatch];

              var containerBuffer =
                  ByteBuffer.allocate(pagesForSinglePatch * pageSize)
                      .order(ByteOrder.nativeOrder());
              for (var j = 0; j < pagesForSinglePatch; j++) {
                final var buffer =
                    ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
                buffer.put(datas.get(pageIndex + j));
                buffers[j] = buffer;

                buffer.rewind();
                containerBuffer.put(buffer);
                buffer.rewind();
              }

              doubleWriteLog.write(
                  new ArrayList<>(Collections.singleton(containerBuffer)),
                  IntArrayList.of(12),
                  IntArrayList.of(startPageIndex + pageIndex));

              for (var j = 0; j < buffers.length; j++) {
                pageMap.put(startPageIndex + pageIndex + j, buffers[j]);
              }

              pageIndex += pagesForSinglePatch;
              writtenPages += pagesForSinglePatch;
            }
          }

          doubleWriteLog.restoreModeOn();

          for (final int pageIndex : pageMap.keySet()) {
            final var pointer = doubleWriteLog.loadPage(12, pageIndex, bufferPool);

            final var loadedBuffer = pointer.getNativeByteBuffer();

            Assert.assertEquals(pageSize, loadedBuffer.limit());
            final var loadedData = new byte[pageSize];

            loadedBuffer.rewind();
            loadedBuffer.get(loadedData);

            final var data = new byte[pageSize];
            final var buffer = pageMap.get(pageIndex);

            buffer.rewind();
            buffer.get(data);

            Assert.assertArrayEquals(data, loadedData);
            bufferPool.release(pointer);
          }

        } finally {
          doubleWriteLog.close();
        }
      } finally {
        bufferPool.clear();
      }
    }
  }

  @Test
  public void testRandomCrashOne() throws Exception {
    final var seed = System.nanoTime();
    System.out.println("testRandomCrashOne : seed " + seed);

    var random = new Random(seed);

    for (var n = 0; n < 10; n++) {
      System.out.println("Iteration - " + n);

      final var pageSize = 256;

      final var bufferPool = new ByteBufferPool(pageSize);
      try {
        final var doubleWriteLog = new DoubleWriteLogGL(4 * 4 * 1024, 512);

        doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
        try {
          final var pagesToWrite = random.nextInt(20_000) + 100;

          List<byte[]> datas = new ArrayList<>();
          for (var i = 0; i < pagesToWrite; i++) {
            final var data = new byte[pageSize];
            random.nextBytes(data);
            datas.add(data);
          }

          var pageIndex = 0;
          var writtenPages = 0;

          while (writtenPages < pagesToWrite) {
            final var pagesForSinglePatch = random.nextInt(pagesToWrite - writtenPages) + 1;
            var buffer =
                ByteBuffer.allocate(pagesForSinglePatch * pageSize).order(ByteOrder.nativeOrder());

            for (var j = 0; j < pagesForSinglePatch; j++) {
              buffer.put(datas.get(pageIndex + j));
            }

            doubleWriteLog.write(
                new ArrayList<>(Collections.singleton(buffer)),
                IntArrayList.of(12),
                IntArrayList.of(24 + pageIndex));
            pageIndex += pagesForSinglePatch;
            writtenPages += pagesForSinglePatch;
          }

          final var doubleWriteLogRestore = new DoubleWriteLogGL(2 * 4 * 1024, 512);
          doubleWriteLogRestore.open("test", Paths.get(buildDirectory), pageSize);

          doubleWriteLogRestore.restoreModeOn();

          for (var i = 0; i < pagesToWrite; i++) {
            final var pointer = doubleWriteLogRestore.loadPage(12, 24 + i, bufferPool);

            var loadedBuffer = pointer.getNativeByteBuffer();

            Assert.assertEquals(256, loadedBuffer.limit());
            var loadedData = new byte[256];
            loadedBuffer.rewind();
            loadedBuffer.get(loadedData);

            Assert.assertArrayEquals(datas.get(i), loadedData);
            bufferPool.release(pointer);
          }

          doubleWriteLogRestore.close();
        } finally {
          doubleWriteLog.close();
        }
      } finally {
        bufferPool.clear();
      }
    }
  }

  @Test
  public void testRandomWriteCrashTwo() throws Exception {
    final var seed = System.nanoTime();
    System.out.println("testRandomCrashTwo : seed " + seed);

    final var random = new Random(seed);
    final var pageSize = 256;

    for (var n = 0; n < 10; n++) {
      System.out.println("Iteration - " + n);

      final var bufferPool = new ByteBufferPool(pageSize);
      try {
        final var doubleWriteLog = new DoubleWriteLogGL(2 * 4 * 1024, 512);
        doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
        try {
          final Map<Integer, ByteBuffer> pageMap = new HashMap<>();
          final var pages = random.nextInt(900) + 100;

          System.out.println("testRandomCrashTwo : pages " + pages);

          for (var k = 0; k < 100; k++) {
            final var pagesToWrite = random.nextInt(pages - 1) + 1;

            List<byte[]> datas = new ArrayList<>();
            for (var i = 0; i < pagesToWrite; i++) {
              final var data = new byte[pageSize];
              random.nextBytes(data);
              datas.add(data);
            }

            final var startPageIndex = random.nextInt(pages);
            var pageIndex = 0;
            var writtenPages = 0;

            while (writtenPages < pagesToWrite) {
              final var pagesForSinglePatch = random.nextInt(pagesToWrite - writtenPages) + 1;
              var buffers = new ByteBuffer[pagesForSinglePatch];
              var containerBuffer =
                  ByteBuffer.allocate(pagesForSinglePatch * pageSize)
                      .order(ByteOrder.nativeOrder());

              for (var j = 0; j < pagesForSinglePatch; j++) {
                final var buffer =
                    ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
                buffer.put(datas.get(pageIndex + j));
                buffer.rewind();

                buffers[j] = buffer;
                containerBuffer.put(buffer);
              }

              doubleWriteLog.write(
                  new ArrayList<>(Collections.singleton(containerBuffer)),
                  IntArrayList.of(12),
                  IntArrayList.of(startPageIndex + pageIndex));
              for (var j = 0; j < buffers.length; j++) {
                pageMap.put(startPageIndex + pageIndex + j, buffers[j]);
              }

              pageIndex += pagesForSinglePatch;
              writtenPages += pagesForSinglePatch;
            }
          }

          final var doubleWriteLogRestore = new DoubleWriteLogGL(2 * 4 * 1024, 512);
          doubleWriteLogRestore.open("test", Paths.get(buildDirectory), pageSize);

          doubleWriteLogRestore.restoreModeOn();

          for (final int pageIndex : pageMap.keySet()) {
            final var pointer = doubleWriteLogRestore.loadPage(12, pageIndex, bufferPool);

            final var loadedBuffer = pointer.getNativeByteBuffer();

            Assert.assertEquals(pageSize, loadedBuffer.limit());
            final var loadedData = new byte[pageSize];

            loadedBuffer.rewind();
            loadedBuffer.get(loadedData);

            final var data = new byte[pageSize];
            final var buffer = pageMap.get(pageIndex);

            buffer.rewind();
            buffer.get(data);

            Assert.assertArrayEquals(data, loadedData);
            bufferPool.release(pointer);
          }

          doubleWriteLogRestore.close();
        } finally {
          doubleWriteLog.close();
        }
      } finally {
        bufferPool.clear();
      }
    }
  }

  @Test
  public void testTruncate() throws IOException {
    final var pageSize = 256;
    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(512, 512);
      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var paths = listFiles();
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());

        for (var i = 0; i < 4; i++) {
          final var overflow =
              doubleWriteLog.write(
                  new ArrayList<>(Collections.singleton(ByteBuffer.allocate(pageSize))),
                  IntArrayList.of(12),
                  IntArrayList.of(45));
          Assert.assertTrue(overflow && i > 0 || i == 0 && !overflow);
        }

        paths = listFiles();
        Assert.assertEquals(4, paths.size());

        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());

        doubleWriteLog.restoreModeOn();
        doubleWriteLog.truncate();

        paths = listFiles();
        Assert.assertEquals(4, paths.size());

        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());

        doubleWriteLog.restoreModeOff();

        doubleWriteLog.truncate();

        paths = listFiles();
        Assert.assertEquals(1, paths.size());

        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  private static List<Path> listFiles() throws IOException {
    try (var files = Files.list(Paths.get(buildDirectory))) {
      return files
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .collect(Collectors.toList());
    }
  }

  @Test
  public void testClose() throws IOException {
    final var pageSize = 256;
    final var maxLogSize = 512; // single block for each segment

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(maxLogSize, 512);
      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var paths = listFiles();
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());

        for (var i = 0; i < 4; i++) {
          doubleWriteLog.write(
              new ArrayList<>(Collections.singleton(ByteBuffer.allocate(pageSize))),
              IntArrayList.of(12),
              IntArrayList.of(45));
        }

        paths = listFiles();
        Assert.assertEquals(4, paths.size());

        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());

      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }

    final var paths = listFiles();
    Assert.assertTrue(paths.isEmpty());
  }

  @Test
  public void testInitAfterCrash() throws Exception {
    final var pageSize = 256;
    final var maxLogSize = 512; // single block for each segment

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(maxLogSize, 512);
      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        for (var i = 0; i < 4; i++) {
          doubleWriteLog.write(
              new ArrayList<>(Collections.singleton(ByteBuffer.allocate(pageSize))),
              IntArrayList.of(12),
              IntArrayList.of(45));
        }

        var paths = listFiles();
        Assert.assertEquals(4, paths.size());

        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());

        final var doubleWriteLogRestore = new DoubleWriteLogGL(maxLogSize, maxLogSize);
        doubleWriteLogRestore.open("test", Paths.get(buildDirectory), pageSize);

        paths = listFiles();
        Assert.assertEquals(5, paths.size());

        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());
        Assert.assertEquals(
            "test_4" + DoubleWriteLogGL.EXTENSION, paths.get(4).getFileName().toString());

        doubleWriteLogRestore.close();
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }

  @Test
  public void testCreationNewSegment() throws Exception {
    final var pageSize = 256;
    final var maxLogSize = 512; // single block for each segment

    final var bufferPool = new ByteBufferPool(pageSize);
    try {
      final var doubleWriteLog = new DoubleWriteLogGL(maxLogSize, 512);
      doubleWriteLog.open("test", Paths.get(buildDirectory), pageSize);
      try {
        var paths = listFiles();
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());

        doubleWriteLog.startCheckpoint();
        doubleWriteLog.truncate();

        for (var i = 0; i < 4; i++) {
          final var overflow =
              doubleWriteLog.write(
                  new ArrayList<>(Collections.singleton(ByteBuffer.allocate(pageSize))),
                  IntArrayList.of(12),
                  IntArrayList.of(45));
          Assert.assertFalse(overflow);
        }

        paths = listFiles();

        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());

        doubleWriteLog.endCheckpoint();

        paths = listFiles();

        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());

        for (var i = 0; i < 4; i++) {
          final var overflow =
              doubleWriteLog.write(
                  new ArrayList<>(Collections.singleton(ByteBuffer.allocate(pageSize))),
                  IntArrayList.of(12),
                  IntArrayList.of(45));
          Assert.assertTrue(overflow);
        }

        paths = listFiles();

        Assert.assertEquals(5, paths.size());
        Assert.assertEquals(
            "test_0" + DoubleWriteLogGL.EXTENSION, paths.get(0).getFileName().toString());
        Assert.assertEquals(
            "test_1" + DoubleWriteLogGL.EXTENSION, paths.get(1).getFileName().toString());
        Assert.assertEquals(
            "test_2" + DoubleWriteLogGL.EXTENSION, paths.get(2).getFileName().toString());
        Assert.assertEquals(
            "test_3" + DoubleWriteLogGL.EXTENSION, paths.get(3).getFileName().toString());
        Assert.assertEquals(
            "test_4" + DoubleWriteLogGL.EXTENSION, paths.get(4).getFileName().toString());
      } finally {
        doubleWriteLog.close();
      }
    } finally {
      bufferPool.clear();
    }
  }
}
