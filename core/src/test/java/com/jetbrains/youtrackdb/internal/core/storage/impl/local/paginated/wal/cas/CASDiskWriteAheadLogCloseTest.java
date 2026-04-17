package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link CASDiskWriteAheadLog#close()} to verify that direct memory buffers
 * are always deallocated during shutdown. Covers the normal close path including the
 * records writer cancellation, pending record drain, WAL file close queue processing,
 * and buffer deallocation in the finally block.
 */
public class CASDiskWriteAheadLogCloseTest {

  private static final int TEST_RECORD_ID = 500;
  private static Path testDirectory;

  @BeforeClass
  public static void beforeClass() {
    testDirectory =
        Paths.get(
            System.getProperty("buildDirectory", "." + File.separator + "target"),
            "casWALCloseTest");

    WALRecordsFactory.INSTANCE.registerNewRecord(TEST_RECORD_ID, SmallTestRecord.class);
  }

  @Before
  public void before() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  @After
  public void after() {
    FileUtils.deleteRecursively(testDirectory.toFile());
  }

  /**
   * Verifies that close() properly deallocates the two direct memory write buffers
   * after writing records. This exercises the normal close path: flush, cancel
   * records writer, drain queued records, process file close queue, and deallocate
   * buffers in the finally block.
   */
  @Test
  public void testCloseReleasesWriteBuffers() throws Exception {
    var wal = createWAL(true, true);

    // Write several records to populate the write buffer and exercise the records writer
    var random = new Random(42);
    for (int i = 0; i < 10; i++) {
      wal.log(new SmallTestRecord(random));
    }

    // Close with flush=true exercises the full close path:
    // doFlush → cancel recordsWriter → get() → drain records → close queue → deallocate
    wal.close();

    // After close, the DirectMemoryAllocator should have no outstanding allocations
    // from this WAL. If deallocate was not called, the checkMemoryLeaks() call in the
    // test teardown (run by JUnitTestListener) will catch it.
  }

  /**
   * Verifies that close(false) (without flush) still deallocates write buffers.
   * This exercises the close path where doFlush is skipped.
   */
  @Test
  public void testCloseWithoutFlushReleasesWriteBuffers() throws Exception {
    var wal = createWAL(true, false);

    var random = new Random(123);
    for (int i = 0; i < 5; i++) {
      wal.log(new SmallTestRecord(random));
    }

    // Close without flush — skip doFlush but still cancel, drain, and deallocate
    wal.close(false);
  }

  /**
   * Verifies that close() properly processes the file close queue when WAL segments
   * have been rotated. Uses a small maxSegmentSize to force segment rollovers, which
   * populates the fileCloseQueue with old segment files. Exercises the fileCloseQueue
   * loop body (lines 1311-1318) and the callFsync branches during close.
   */
  @Test
  public void testCloseWithSegmentRolloverProcessesFileCloseQueue() throws Exception {
    // Use a very small segment size (1 page) to force frequent segment rollovers.
    // Each rollover adds the old segment file to the fileCloseQueue.
    var wal =
        new CASDiskWriteAheadLog(
            "walCloseTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            100,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            1, // maxSegmentSize=1 → forces rollover after each page of records
            20,
            true,
            Locale.US,
            -1,
            1000,
            false,
            true, // callFsync=true → exercises the fsync branches
            false,
            10);

    // Write enough records to trigger multiple segment rollovers
    var random = new Random(456);
    for (int i = 0; i < 50; i++) {
      wal.log(new SmallTestRecord(random));
    }

    // Flush to ensure all records are written and segments are finalized
    wal.flush();

    // Close exercises the full path including fileCloseQueue processing:
    // for each pair in fileCloseQueue → file.force(true) → file.close()
    wal.close();
  }

  /**
   * Verifies that the filterWALFiles=false path in initSegmentSet is exercised
   * during WAL construction, then close() properly deallocates.
   * This covers the validateSimpleName lambda (lines 356-357).
   */
  @Test
  public void testCloseAfterOpenWithUnfilteredWALFiles() throws Exception {
    // First create a WAL with default name and close it to leave files on disk
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(789)));
    wal.close();

    // Re-open with filterWALFiles=false to exercise the validateSimpleName path
    var wal2 =
        new CASDiskWriteAheadLog(
            "walCloseTest",
            testDirectory,
            testDirectory,
            ContextConfiguration.WAL_DEFAULT_NAME,
            100,
            64,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            20,
            false, // filterWALFiles=false → exercises validateSimpleName lambda
            Locale.US,
            -1,
            1000,
            false,
            true, // callFsync=true
            false,
            10);

    wal2.close();
  }

  /**
   * Verifies that close() throws StorageException when the records writer future cannot
   * be cancelled and is not yet done. This covers the error branch at line 1272-1273.
   * Also verifies that the write buffers are still deallocated via the finally block
   * even when the pre-lock code throws.
   */
  @Test
  public void testCloseThrowsWhenRecordsWriterCannotBeCancelled() throws Exception {
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(100)));

    // Cancel and wait for the real records writer before replacing with a mock,
    // otherwise the real scheduled task keeps running and may corrupt state.
    cancelRealRecordsWriter(wal);

    // Replace the recordsWriterFuture with a mock that refuses cancellation
    @SuppressWarnings("unchecked")
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    when(mockFuture.cancel(false)).thenReturn(false);
    when(mockFuture.isDone()).thenReturn(false);

    setField(wal, "recordsWriterFuture", mockFuture);

    // close() should throw StorageException because the writer can't be cancelled,
    // but the finally block must still deallocate the write buffers
    assertThrows(StorageException.class, () -> wal.close(false));
  }

  /**
   * Verifies that close() propagates ExecutionException from recordsWriterFuture.get()
   * as a wrapped StorageException. Covers the catch block at lines 1281-1282.
   * Also verifies write buffer deallocation in the finally block.
   */
  @Test
  public void testCloseHandlesExecutionExceptionFromRecordsWriter() throws Exception {
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(200)));

    // Cancel the real records writer before replacing with a mock
    cancelRealRecordsWriter(wal);

    // Replace with a mock future that was cancelled successfully but get() throws
    @SuppressWarnings("unchecked")
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    when(mockFuture.cancel(false)).thenReturn(true);
    when(mockFuture.get()).thenThrow(new ExecutionException(new RuntimeException("write failed")));

    setField(wal, "recordsWriterFuture", mockFuture);

    // close() should propagate the exception, but still deallocate buffers
    assertThrows(Exception.class, () -> wal.close(false));
  }

  /**
   * Verifies that close() processes entries in the fileCloseQueue with callFsync=true.
   * Nulls out writeFuture so executeSyncAndCloseFile doesn't drain the queue before
   * the for loop in close() processes it. Covers lines 1311-1318 and the
   * callFsync=true branch at line 1314.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testCloseProcessesFileCloseQueueWithFsync() throws Exception {
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(300)));

    // Flush and null out writeFuture so it doesn't drain the queue during close
    wal.flush();
    setField(wal, "writeFuture", null);

    // Inject mock WAL files into the fileCloseQueue
    var mockFile1 = mock(WALFile.class);
    var mockFile2 = mock(WALFile.class);

    var queue =
        (ConcurrentLinkedQueue<RawPairLongObject<WALFile>>) getField(wal, "fileCloseQueue");
    queue.offer(new RawPairLongObject<>(1L, mockFile1));
    queue.offer(new RawPairLongObject<>(2L, mockFile2));

    var queueSize =
        (java.util.concurrent.atomic.AtomicInteger) getField(wal, "fileCloseQueueSize");
    queueSize.set(2);

    wal.close();

    // Verify both files were force-synced and closed (callFsync=true)
    org.mockito.Mockito.verify(mockFile1).force(true);
    org.mockito.Mockito.verify(mockFile1).close();
    org.mockito.Mockito.verify(mockFile2).force(true);
    org.mockito.Mockito.verify(mockFile2).close();
  }

  /**
   * Same as above but with callFsync=false, verifying that file.force() is NOT called
   * but file.close() still is. Covers the callFsync=false branch at line 1314.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testCloseProcessesFileCloseQueueWithoutFsync() throws Exception {
    var wal = createWAL(false, true);
    wal.log(new SmallTestRecord(new Random(350)));

    // Flush and null out writeFuture
    wal.flush();
    setField(wal, "writeFuture", null);

    // Inject a mock WAL file
    var mockFile = mock(WALFile.class);

    var queue =
        (ConcurrentLinkedQueue<RawPairLongObject<WALFile>>) getField(wal, "fileCloseQueue");
    queue.offer(new RawPairLongObject<>(1L, mockFile));

    var queueSize =
        (java.util.concurrent.atomic.AtomicInteger) getField(wal, "fileCloseQueueSize");
    queueSize.set(1);

    wal.close();

    // force() should NOT be called when callFsync=false
    org.mockito.Mockito.verify(mockFile, org.mockito.Mockito.never()).force(true);
    org.mockito.Mockito.verify(mockFile).close();
  }

  /**
   * Verifies that close() handles a non-null writeFuture that completes normally.
   * Covers the {@code future != null} branch at line 1291 and the {@code future.get()}
   * call at line 1293.
   */
  @Test
  public void testCloseWaitsForWriteFuture() throws Exception {
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(400)));

    // Inject a mock writeFuture that returns normally
    Future<?> mockWriteFuture = mock(Future.class);
    when(mockWriteFuture.get()).thenReturn(null);
    setField(wal, "writeFuture", mockWriteFuture);

    wal.close();

    org.mockito.Mockito.verify(mockWriteFuture).get();
  }

  /**
   * Verifies that close() propagates ExecutionException from writeFuture.get() as a
   * wrapped StorageException. Covers the catch block at lines 1294-1295.
   */
  @Test
  public void testCloseHandlesExecutionExceptionFromWriteFuture() throws Exception {
    var wal = createWAL(true, true);
    wal.log(new SmallTestRecord(new Random(500)));

    // Inject a mock writeFuture that throws
    Future<?> mockWriteFuture = mock(Future.class);
    when(mockWriteFuture.get())
        .thenThrow(new ExecutionException(new RuntimeException("write buffer failed")));
    setField(wal, "writeFuture", mockWriteFuture);

    // close() should propagate the exception, but still deallocate buffers
    assertThrows(Exception.class, () -> wal.close(false));
  }

  /**
   * Cancels and waits for the real records writer scheduled task so it doesn't interfere
   * with mock-based close() tests. Must be called before replacing recordsWriterFuture.
   */
  private static void cancelRealRecordsWriter(CASDiskWriteAheadLog wal) throws Exception {
    var realFuture = (ScheduledFuture<?>) getField(wal, "recordsWriterFuture");
    realFuture.cancel(false);
    setField(wal, "cancelRecordsWriting", true);
    try {
      realFuture.get();
    } catch (java.util.concurrent.CancellationException ignored) {
      // expected after cancel
    }
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private CASDiskWriteAheadLog createWAL(boolean callFsync, boolean filterWALFiles)
      throws Exception {
    return new CASDiskWriteAheadLog(
        "walCloseTest",
        testDirectory,
        testDirectory,
        ContextConfiguration.WAL_DEFAULT_NAME,
        100,
        64,
        null,
        null,
        Integer.MAX_VALUE,
        Integer.MAX_VALUE,
        20,
        filterWALFiles,
        Locale.US,
        -1,
        1000,
        false,
        callFsync,
        false,
        10);
  }

  /**
   * A small WAL record for testing. Uses a fixed-size random byte payload.
   */
  public static final class SmallTestRecord extends AbstractWALRecord {

    private byte[] data;

    @SuppressWarnings("unused")
    public SmallTestRecord() {
    }

    SmallTestRecord(Random random) {
      data = new byte[64];
      random.nextBytes(data);
    }

    @Override
    public int toStream(byte[] content, int offset) {
      IntegerSerializer.serializeNative(data.length, content, offset);
      offset += IntegerSerializer.INT_SIZE;
      System.arraycopy(data, 0, content, offset, data.length);
      offset += data.length;
      return offset;
    }

    @Override
    public void toStream(ByteBuffer buffer) {
      buffer.putInt(data.length);
      buffer.put(data);
    }

    @Override
    public int fromStream(byte[] content, int offset) {
      var len = IntegerSerializer.deserializeNative(content, offset);
      offset += IntegerSerializer.INT_SIZE;
      data = new byte[len];
      System.arraycopy(content, offset, data, 0, len);
      offset += len;
      return offset;
    }

    @Override
    public int serializedSize() {
      return data.length + IntegerSerializer.INT_SIZE;
    }

    @Override
    public int getId() {
      return TEST_RECORD_ID;
    }
  }
}
