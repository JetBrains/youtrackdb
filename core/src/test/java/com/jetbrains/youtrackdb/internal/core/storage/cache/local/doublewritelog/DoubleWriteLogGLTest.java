package com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link DoubleWriteLogGL} and {@link DoubleWriteLogNoOP} covering the
 * surefire-reachable paths: open/write/truncate/loadPage/restoreMode/close/checkpoint.
 *
 * <p>Recovery, segment-rotation, and seal paths are shadowed by
 * {@code DoubleWriteLogGLTestIT} (failsafe-only); those branches are IT-territory and are
 * not attempted here. Branch ceiling for this package is ~70% line / ~52% branch per D4.
 *
 * <p>Every test uses a fresh temporary directory created by {@link #before()} and deleted
 * by {@link #after()}, so tests do not share on-disk state and file handles are closed
 * before the directory is removed.
 */
public class DoubleWriteLogGLTest {

  /** Small page size that still respects the block-alignment math. */
  private static final int PAGE_SIZE = 256;

  /**
   * Small segment cap: one write fills the segment so subsequent writes trigger
   * a new-segment rotation (i.e. {@code segmentPosition >= maxSegSize}).
   */
  private static final long MAX_SEG_SIZE = 512;

  /** Block size hard-coded to the smallest useful value to make block-fill math predictable. */
  private static final int BLOCK_SIZE = 512;

  private static Path testRootDirectory;
  private Path testDirectory;

  @BeforeClass
  public static void beforeClass() throws IOException {
    testRootDirectory =
        Paths.get(
            System.getProperty("buildDirectory", "." + File.separator + "target"),
            "DoubleWriteLogGLTest");
    Files.createDirectories(testRootDirectory);
  }

  @Before
  public void before() throws IOException {
    // Each test gets its own sub-directory so parallel runs (if ever enabled) do not clash.
    testDirectory = Files.createTempDirectory(testRootDirectory.toAbsolutePath(), "dwl-test-");
  }

  @After
  public void after() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  // -------------------------------------------------------------------------
  // Helper utilities
  // -------------------------------------------------------------------------

  /**
   * Lists all {@code .dwl} files in {@link #testDirectory}, sorted lexicographically by name.
   */
  private List<Path> listDwlFiles() throws IOException {
    try (var stream = Files.list(testDirectory)) {
      return stream
          .filter(p -> p.getFileName().toString().endsWith(DoubleWriteLogGL.EXTENSION))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .collect(Collectors.toList());
    }
  }

  /**
   * Fills {@code buf} with random bytes and returns the raw data (position reset to 0).
   */
  private static byte[] randomPage(ByteBuffer buf) {
    buf.clear();
    var data = new byte[PAGE_SIZE];
    ThreadLocalRandom.current().nextBytes(data);
    buf.put(data);
    buf.rewind();
    return data;
  }

  // -------------------------------------------------------------------------
  // open() — initial state
  // -------------------------------------------------------------------------

  /**
   * Verifies that {@link DoubleWriteLogGL#open} creates exactly one segment file named
   * {@code <baseName>_0.dwl} in the storage directory, and that the log can be closed cleanly.
   *
   * <p>The segment-0 file is the initial empty file created on every fresh open.
   */
  @Test
  public void testOpenCreatesInitialSegmentFile() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("testStorage", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
        testDirectory, PAGE_SIZE);
    try {
      var files = listDwlFiles();
      assertEquals("Expected exactly one segment file after open", 1, files.size());
      assertEquals(
          ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_0" + DoubleWriteLogGL.EXTENSION,
          files.getFirst().getFileName().toString());
    } finally {
      dwl.close();
    }
  }

  /**
   * Verifies that the {@code EXTENSION} constant equals {@code ".dwl"} — callers rely on
   * this literal when constructing or scanning log files.
   */
  @Test
  public void testExtensionConstant() {
    assertEquals(".dwl", DoubleWriteLogGL.EXTENSION);
  }

  // -------------------------------------------------------------------------
  // write() — return-value semantics (overflow flag)
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#write} returns {@code false} on the very first write because
   * there are no tail segments yet and the segment has not grown past the size cap.
   *
   * <p>Pinned getter: {@code write()} return value == {@code false}.
   */
  @Test
  public void testFirstWriteReturnsFalse() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      var overflow = dwl.write(
          new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1),
          IntArrayList.of(0));
      assertFalse("First write must not signal overflow (no tail segments yet)", overflow);
    } finally {
      dwl.close();
    }
  }

  /**
   * After the first write fills segment-0 past the size cap, the second write rotates into
   * segment-1 (adding segment-0 to tail segments) and returns {@code true} — signalling the
   * caller to flush and truncate.
   *
   * <p>Pinned getter: {@code write()} return value == {@code true} on the second call;
   * two segment files exist on disk.
   */
  @Test
  public void testSecondWriteReturnsOverflowWhenTailSegmentsExist() throws IOException {
    // MAX_SEG_SIZE=512 bytes; one page write exceeds this so the second write rotates.
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());

      // First write — fills segment-0.
      randomPage(buf);
      boolean first = dwl.write(
          new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));
      assertFalse(first);

      // Second write — segment is past the cap; a new segment was created; tail is non-empty.
      randomPage(buf);
      boolean second = dwl.write(
          new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(1));
      assertTrue("Second write should signal overflow once tail segments exist", second);

      var files = listDwlFiles();
      assertEquals("Two segment files expected after rotation", 2, files.size());
      assertEquals(
          ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_0" + DoubleWriteLogGL.EXTENSION,
          files.get(0).getFileName().toString());
      assertEquals(
          ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_1" + DoubleWriteLogGL.EXTENSION,
          files.get(1).getFileName().toString());
    } finally {
      dwl.close();
    }
  }

  /**
   * When the log is in restore mode, {@link DoubleWriteLogGL#write} returns {@code false}
   * even if tail segments exist — the caller must not truncate while recovery is in progress.
   *
   * <p>Pinned getter: {@code write()} return value == {@code false} after {@code restoreModeOn()}.
   */
  @Test
  public void testWriteReturnsFalseInRestoreMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());

      // Trigger rotation so tail segments are non-empty.
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(1));

      dwl.restoreModeOn();

      // Third write in restore mode — must return false regardless of tail-segment count.
      randomPage(buf);
      boolean result = dwl.write(
          new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(2));
      assertFalse("write() must return false in restore mode", result);
    } finally {
      dwl.close();
    }
  }

  /**
   * When a checkpoint is active (counter > 0) the write-overflow flag is {@code false} even if
   * the segment has grown past the cap, because the DWL must not rotate during a checkpoint.
   *
   * <p>Pinned getter: {@code write()} return value == {@code false} while checkpoint active.
   */
  @Test
  public void testWriteReturnsFalseDuringCheckpoint() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());

      // Fill segment-0 to rotation threshold.
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));

      dwl.startCheckpoint(); // checkpoint counter = 1; new segment created
      try {
        // Writes during checkpoint must not signal overflow.
        randomPage(buf);
        boolean result = dwl.write(
            new ArrayList<>(Collections.singleton(buf)),
            IntArrayList.of(1), IntArrayList.of(1));
        assertFalse("write() must return false during an active checkpoint", result);
      } finally {
        dwl.endCheckpoint();
      }
    } finally {
      dwl.close();
    }
  }

  // -------------------------------------------------------------------------
  // write() — multiple buffers in one call
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#write} accepts a list of buffers representing different files/pages;
   * all are serialised into a single block allocation in the segment file. After
   * {@code restoreModeOn()} both pages are recoverable.
   *
   * <p>Pinned getters: {@code loadPage(fileId=1, pageIndex=0)} and
   * {@code loadPage(fileId=2, pageIndex=0)} each return a non-null pointer whose buffer limit
   * equals {@code PAGE_SIZE} and whose bytes match the written data.
   */
  @Test
  public void testWriteMultipleBuffersInSingleCall() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var buf1 = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      var buf2 = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      byte[] data1 = randomPage(buf1);
      byte[] data2 = randomPage(buf2);

      dwl.write(new ArrayList<>(List.of(buf1, buf2)),
          IntArrayList.of(1, 2), IntArrayList.of(0, 0));

      dwl.restoreModeOn();

      Pointer p1 = dwl.loadPage(1, 0, bufferPool);
      assertNotNull("Page (fileId=1, pageIndex=0) must be recoverable", p1);
      assertEquals(PAGE_SIZE, p1.getNativeByteBuffer().limit());
      byte[] loaded1 = new byte[PAGE_SIZE];
      p1.getNativeByteBuffer().rewind();
      p1.getNativeByteBuffer().get(loaded1);
      assertArrayEquals(data1, loaded1);
      bufferPool.release(p1);

      Pointer p2 = dwl.loadPage(2, 0, bufferPool);
      assertNotNull("Page (fileId=2, pageIndex=0) must be recoverable", p2);
      assertEquals(PAGE_SIZE, p2.getNativeByteBuffer().limit());
      byte[] loaded2 = new byte[PAGE_SIZE];
      p2.getNativeByteBuffer().rewind();
      p2.getNativeByteBuffer().get(loaded2);
      assertArrayEquals(data2, loaded2);
      bufferPool.release(p2);
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  // -------------------------------------------------------------------------
  // loadPage() — before and after restoreModeOn()
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#loadPage} returns {@code null} when called outside restore mode,
   * regardless of what was written. The log is write-only until restore is activated.
   *
   * <p>Pinned getter: return value is {@code null} before {@code restoreModeOn()}.
   */
  @Test
  public void testLoadPageReturnsNullOutsideRestoreMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(5), IntArrayList.of(7));

      Pointer pointer = dwl.loadPage(5, 7, bufferPool);
      assertNull("loadPage() must return null when not in restore mode", pointer);
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  /**
   * {@link DoubleWriteLogGL#loadPage} returns {@code null} for a page that was never written,
   * even when restore mode is active. The page map only contains entries from actual writes.
   *
   * <p>Pinned getter: return value is {@code null} for an unknown (fileId, pageIndex) pair.
   */
  @Test
  public void testLoadPageReturnsNullForUnknownPageInRestoreMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));

      dwl.restoreModeOn();

      // Ask for a page that was never written.
      Pointer pointer = dwl.loadPage(99, 99, bufferPool);
      assertNull("loadPage() must return null for an unknown page in restore mode", pointer);
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  /**
   * After writing a single page and activating restore mode, {@link DoubleWriteLogGL#loadPage}
   * returns the correct page content.
   *
   * <p>Pinned getters: pointer is non-null; {@code limit()} equals {@code PAGE_SIZE};
   * the byte content matches the written data.
   */
  @Test
  public void testLoadPageReturnsSingleWrittenPageInRestoreMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      byte[] original = randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(3), IntArrayList.of(7));

      dwl.restoreModeOn();

      Pointer pointer = dwl.loadPage(3, 7, bufferPool);
      assertNotNull("loadPage() must return a non-null pointer for a written page", pointer);
      assertEquals(PAGE_SIZE, pointer.getNativeByteBuffer().limit());

      byte[] loaded = new byte[PAGE_SIZE];
      pointer.getNativeByteBuffer().rewind();
      pointer.getNativeByteBuffer().get(loaded);
      assertArrayEquals("Loaded page content must match the written data", original, loaded);

      bufferPool.release(pointer);
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  /**
   * When a multi-page buffer (covering consecutive page indices) is written, all individual
   * page indices are retrievable via {@code loadPage()} after restore mode is activated.
   *
   * <p>Pinned getters: each {@code loadPage(fileId, pageIndex + i)} returns a non-null pointer
   * with the correct limit and byte content for page {@code i}.
   */
  @Test
  public void testLoadPageReturnsCorrectPageFromMultiPageBuffer() throws IOException {
    final int pageCount = 3;
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 8, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var combined = ByteBuffer.allocate(PAGE_SIZE * pageCount).order(ByteOrder.nativeOrder());
      byte[][] pages = new byte[pageCount][];
      for (int i = 0; i < pageCount; i++) {
        pages[i] = new byte[PAGE_SIZE];
        ThreadLocalRandom.current().nextBytes(pages[i]);
        combined.put(pages[i]);
      }
      combined.rewind();

      dwl.write(new ArrayList<>(Collections.singleton(combined)),
          IntArrayList.of(4), IntArrayList.of(10));

      dwl.restoreModeOn();

      for (int i = 0; i < pageCount; i++) {
        Pointer ptr = dwl.loadPage(4, 10 + i, bufferPool);
        assertNotNull("Page at index " + (10 + i) + " must be recoverable", ptr);
        assertEquals(PAGE_SIZE, ptr.getNativeByteBuffer().limit());
        byte[] loaded = new byte[PAGE_SIZE];
        ptr.getNativeByteBuffer().rewind();
        ptr.getNativeByteBuffer().get(loaded);
        assertArrayEquals("Content of page index " + (10 + i) + " must match", pages[i], loaded);
        bufferPool.release(ptr);
      }
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  // -------------------------------------------------------------------------
  // truncate()
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#truncate} in normal mode deletes all tail segments, leaving only
   * the current (active) segment on disk.
   *
   * <p>Pinned getter: file count after truncate is 1; the remaining file is the highest-numbered
   * segment (the current active one).
   */
  @Test
  public void testTruncateRemovesTailSegmentsInNormalMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());

      // Two writes — first fills segment-0, second rotates to segment-1.
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(1));

      assertEquals("Two segment files expected before truncate", 2, listDwlFiles().size());

      dwl.truncate();

      var afterTruncate = listDwlFiles();
      assertEquals("Exactly one segment file must remain after truncate", 1, afterTruncate.size());
      // The current segment (segment-1) is retained; segment-0 (tail) is deleted.
      assertEquals(
          ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_1" + DoubleWriteLogGL.EXTENSION,
          afterTruncate.getFirst().getFileName().toString());
    } finally {
      dwl.close();
    }
  }

  /**
   * {@link DoubleWriteLogGL#truncate} in restore mode is a no-op — tail segments are preserved
   * so that subsequent {@code loadPage()} calls can still find pages in them.
   *
   * <p>Pinned getter: file count is unchanged after calling truncate while in restore mode.
   */
  @Test
  public void testTruncateIsNoOpInRestoreMode() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(1));

      dwl.restoreModeOn();
      int countBefore = listDwlFiles().size();

      dwl.truncate(); // must be a no-op

      assertEquals("Truncate in restore mode must not delete any files",
          countBefore, listDwlFiles().size());
    } finally {
      dwl.close();
    }
  }

  // -------------------------------------------------------------------------
  // restoreModeOff()
  // -------------------------------------------------------------------------

  /**
   * After calling {@link DoubleWriteLogGL#restoreModeOff}, previously written pages are no longer
   * accessible via {@link DoubleWriteLogGL#loadPage} (returns {@code null}).
   *
   * <p>Pinned getter: {@code loadPage()} returns {@code null} after restore mode is turned off.
   */
  @Test
  public void testRestoreModeOffClearsPageMap() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(7), IntArrayList.of(3));

      dwl.restoreModeOn();

      // Page is accessible in restore mode.
      Pointer ptr = dwl.loadPage(7, 3, bufferPool);
      assertNotNull(ptr);
      bufferPool.release(ptr);

      dwl.restoreModeOff();

      // After turning off restore mode, loadPage must return null.
      assertNull("loadPage() must return null after restoreModeOff()",
          dwl.loadPage(7, 3, bufferPool));
    } finally {
      dwl.close();
      bufferPool.clear();
    }
  }

  // -------------------------------------------------------------------------
  // close() — file cleanup
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#close} removes all {@code .dwl} files from the storage directory,
   * including both the current segment and any tail segments.
   *
   * <p>Pinned getter: file count is 0 after close.
   */
  @Test
  public void testCloseDeletesAllSegmentFiles() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);

    var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
    // Two writes to create two segment files (segment-0 and segment-1).
    randomPage(buf);
    dwl.write(new ArrayList<>(Collections.singleton(buf)),
        IntArrayList.of(1), IntArrayList.of(0));
    randomPage(buf);
    dwl.write(new ArrayList<>(Collections.singleton(buf)),
        IntArrayList.of(1), IntArrayList.of(1));

    assertEquals("Two segment files must exist before close", 2, listDwlFiles().size());

    dwl.close();

    assertEquals("All segment files must be deleted by close()", 0, listDwlFiles().size());
  }

  // -------------------------------------------------------------------------
  // startCheckpoint() / endCheckpoint()
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogGL#startCheckpoint} creates a new segment (rotating the current one if
   * it is non-empty) and increments the checkpoint counter. While the counter is positive,
   * {@code write()} does not signal overflow even if tail segments exist.
   *
   * <p>Pinned getter: after {@code startCheckpoint()}, a second segment file appears on disk;
   * the new segment is named {@code <base>_1.dwl}.
   */
  @Test
  public void testStartCheckpointCreatesNewSegment() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE * 4, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));

      assertEquals(1, listDwlFiles().size());

      dwl.startCheckpoint();
      try {
        // startCheckpoint rotates the non-empty segment → two files now.
        var files = listDwlFiles();
        assertEquals("startCheckpoint must rotate the current non-empty segment", 2, files.size());
        assertEquals(
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_0" + DoubleWriteLogGL.EXTENSION,
            files.get(0).getFileName().toString());
        assertEquals(
            ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_1" + DoubleWriteLogGL.EXTENSION,
            files.get(1).getFileName().toString());
      } finally {
        dwl.endCheckpoint();
      }
    } finally {
      dwl.close();
    }
  }

  /**
   * Nested checkpoint calls (counter > 1) are supported. The write-overflow flag remains
   * {@code false} while the counter is positive; only after all {@code endCheckpoint()} calls
   * bring the counter to 0 does normal overflow logic resume.
   *
   * <p>Pinned getter: {@code write()} returns false while checkpoint counter > 0; returns true
   * (overflow signalled) after the last {@code endCheckpoint()} call.
   */
  @Test
  public void testNestedCheckpointsControlOverflowFlag() throws IOException {
    var dwl = new DoubleWriteLogGL(MAX_SEG_SIZE, BLOCK_SIZE);
    dwl.open("s", ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, testDirectory, PAGE_SIZE);
    try {
      var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());

      // Fill segment-0 to trigger rotation on the next un-checkpointed write.
      randomPage(buf);
      dwl.write(new ArrayList<>(Collections.singleton(buf)),
          IntArrayList.of(1), IntArrayList.of(0));

      dwl.startCheckpoint(); // counter = 1, segment rotated; segment-0 goes to tail
      dwl.startCheckpoint(); // counter = 2

      randomPage(buf);
      boolean inCheckpoint = dwl.write(
          new ArrayList<>(Collections.singleton(buf)), IntArrayList.of(1), IntArrayList.of(1));
      assertFalse("write() must return false while checkpoint counter > 0", inCheckpoint);

      dwl.endCheckpoint(); // counter = 1

      randomPage(buf);
      boolean stillInCheckpoint = dwl.write(
          new ArrayList<>(Collections.singleton(buf)), IntArrayList.of(1), IntArrayList.of(2));
      assertFalse("write() must still return false with counter = 1", stillInCheckpoint);

      dwl.endCheckpoint(); // counter = 0

      // Now tail segments exist and checkpoint is done — overflow must be signalled.
      randomPage(buf);
      boolean afterCheckpoint = dwl.write(
          new ArrayList<>(Collections.singleton(buf)), IntArrayList.of(1), IntArrayList.of(3));
      assertTrue("write() must return true (overflow) after all checkpoints end", afterCheckpoint);
    } finally {
      dwl.close();
    }
  }

  // -------------------------------------------------------------------------
  // DoubleWriteLogNoOP — full method coverage
  // -------------------------------------------------------------------------

  /**
   * {@link DoubleWriteLogNoOP#write} always returns {@code false} (no overflow signalled).
   * The no-op implementation never creates any files or state.
   *
   * <p>Pinned getter: return value is {@code false}.
   */
  @Test
  public void testNoOpWriteReturnsFalse() throws IOException {
    var noOp = new DoubleWriteLogNoOP();
    var buf = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
    boolean result = noOp.write(
        new ArrayList<>(Collections.singleton(buf)),
        IntArrayList.of(1), IntArrayList.of(0));
    assertFalse("DoubleWriteLogNoOP.write() must always return false", result);
  }

  /**
   * {@link DoubleWriteLogNoOP#truncate} is a no-op and must not throw.
   */
  @Test
  public void testNoOpTruncateIsNoop() throws IOException {
    new DoubleWriteLogNoOP().truncate();
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#open} is a no-op and must not throw.
   */
  @Test
  public void testNoOpOpenIsNoop() throws IOException {
    new DoubleWriteLogNoOP().open("s", "base", testDirectory, PAGE_SIZE);
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#loadPage} always returns {@code null} — the no-op log never
   * stores any pages.
   *
   * <p>Pinned getter: return value is {@code null}.
   */
  @Test
  public void testNoOpLoadPageReturnsNull() throws IOException {
    var noOp = new DoubleWriteLogNoOP();
    var bufferPool = new ByteBufferPool(PAGE_SIZE);
    try {
      assertNull("DoubleWriteLogNoOP.loadPage() must always return null",
          noOp.loadPage(1, 0, bufferPool));
    } finally {
      bufferPool.clear();
    }
  }

  /**
   * {@link DoubleWriteLogNoOP#restoreModeOn} is a no-op and must not throw.
   */
  @Test
  public void testNoOpRestoreModeOnIsNoop() throws IOException {
    new DoubleWriteLogNoOP().restoreModeOn();
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#restoreModeOff} is a no-op and must not throw.
   */
  @Test
  public void testNoOpRestoreModeOffIsNoop() {
    new DoubleWriteLogNoOP().restoreModeOff();
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#close} is a no-op and must not throw.
   */
  @Test
  public void testNoOpCloseIsNoop() throws IOException {
    new DoubleWriteLogNoOP().close();
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#startCheckpoint} is a no-op and must not throw.
   */
  @Test
  public void testNoOpStartCheckpointIsNoop() throws IOException {
    new DoubleWriteLogNoOP().startCheckpoint();
    // No exception is the assertion.
  }

  /**
   * {@link DoubleWriteLogNoOP#endCheckpoint} is a no-op and must not throw.
   */
  @Test
  public void testNoOpEndCheckpointIsNoop() {
    new DoubleWriteLogNoOP().endCheckpoint();
    // No exception is the assertion.
  }
}
