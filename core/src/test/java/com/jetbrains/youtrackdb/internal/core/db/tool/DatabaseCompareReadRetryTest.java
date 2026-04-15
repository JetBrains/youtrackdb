package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link DatabaseCompare#readRecordWithRetry} — verifies that the retry loop correctly
 * handles {@link com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException}
 * from {@code toRawBuffer()} when the optimistic stamp is invalidated by a concurrent page
 * eviction, and succeeds on retry.
 */
public class DatabaseCompareReadRetryTest {

  private static final byte[] EXPECTED_DATA = {0x01, 0x02, 0x03, 0x04};
  private static final long RECORD_VERSION = 1L;
  private static final byte RECORD_TYPE = 'd';

  private DirectMemoryAllocator allocator;
  private Pointer pointer;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    pointer = allocator.allocate(4096, true, Intention.TEST);
  }

  @After
  public void tearDown() {
    allocator.deallocate(pointer);
  }

  /**
   * Verifies that readRecordWithRetry retries when the first read returns a RawPageBuffer whose
   * stamp has been invalidated (simulating concurrent page eviction between
   * executeOptimisticStorageRead and toRawBuffer). On retry the storage returns a RawBuffer
   * (simulating the pinned fallback path), which succeeds without stamp validation.
   */
  @Test
  public void testRetryOnInvalidatedOptimisticStamp() {
    // Set up a PageFrame with content data and an invalidated stamp to simulate
    // a concurrent eviction between the optimistic read and toRawBuffer().
    var frame = new PageFrame(pointer);
    var buffer = frame.getBuffer();
    buffer.position(0);
    buffer.put(EXPECTED_DATA);

    // Take an optimistic stamp, then invalidate it via exclusive lock cycle
    long staleStamp = frame.tryOptimisticRead();
    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    // First call returns a RawPageBuffer with the stale stamp — toRawBuffer() will throw
    // OptimisticReadFailedException because the stamp fails validation.
    // Second call returns a RawBuffer — toRawBuffer() succeeds immediately.
    var staleResult =
        new RawPageBuffer(frame, staleStamp, 0, EXPECTED_DATA.length, RECORD_VERSION,
            RECORD_TYPE);
    var successResult = new RawBuffer(EXPECTED_DATA, RECORD_VERSION, RECORD_TYPE);

    var storage = mock(Storage.class);
    var rid = new RecordId(1, 0);
    when(storage.readRecord(any(), any()))
        .thenReturn(staleResult)
        .thenReturn(successResult);

    RawBuffer result = DatabaseCompare.readRecordWithRetry(storage, rid, null);

    assertNotNull(result);
    assertArrayEquals(EXPECTED_DATA, result.buffer());
    assertEquals(RECORD_VERSION, result.recordVersion());
    assertEquals(RECORD_TYPE, result.recordType());
    verify(storage, times(2)).readRecord(any(), any());
  }

  /**
   * Verifies that readRecordWithRetry returns immediately when the storage returns a RawBuffer
   * (no optimistic path involved), without any retries.
   */
  @Test
  public void testNoRetryWhenRawBufferReturnedDirectly() {
    var storage = mock(Storage.class);
    var rid = new RecordId(1, 0);
    when(storage.readRecord(any(), any()))
        .thenReturn(new RawBuffer(EXPECTED_DATA, RECORD_VERSION, RECORD_TYPE));

    RawBuffer result = DatabaseCompare.readRecordWithRetry(storage, rid, null);

    assertNotNull(result);
    assertArrayEquals(EXPECTED_DATA, result.buffer());
    assertEquals(RECORD_VERSION, result.recordVersion());
    assertEquals(RECORD_TYPE, result.recordType());
    verify(storage, times(1)).readRecord(any(), any());
  }

  /**
   * Verifies that readRecordWithRetry handles multiple consecutive
   * OptimisticReadFailedExceptions before succeeding — simulating sustained page contention
   * where the optimistic stamp is invalidated repeatedly.
   */
  @Test
  public void testMultipleConsecutiveRetriesBeforeSuccess() {
    var frame = new PageFrame(pointer);
    frame.getBuffer().position(0);
    frame.getBuffer().put(EXPECTED_DATA);

    // Create 3 stale RawPageBuffers with invalidated stamps
    long staleStamp1 = frame.tryOptimisticRead();
    long ws1 = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(ws1);

    long staleStamp2 = frame.tryOptimisticRead();
    long ws2 = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(ws2);

    long staleStamp3 = frame.tryOptimisticRead();
    long ws3 = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(ws3);

    var stale1 = new RawPageBuffer(frame, staleStamp1, 0, EXPECTED_DATA.length,
        RECORD_VERSION, RECORD_TYPE);
    var stale2 = new RawPageBuffer(frame, staleStamp2, 0, EXPECTED_DATA.length,
        RECORD_VERSION, RECORD_TYPE);
    var stale3 = new RawPageBuffer(frame, staleStamp3, 0, EXPECTED_DATA.length,
        RECORD_VERSION, RECORD_TYPE);
    var successResult = new RawBuffer(EXPECTED_DATA, RECORD_VERSION, RECORD_TYPE);

    var storage = mock(Storage.class);
    var rid = new RecordId(1, 0);
    when(storage.readRecord(any(), any()))
        .thenReturn(stale1)
        .thenReturn(stale2)
        .thenReturn(stale3)
        .thenReturn(successResult);

    RawBuffer result = DatabaseCompare.readRecordWithRetry(storage, rid, null);

    assertNotNull(result);
    assertArrayEquals(EXPECTED_DATA, result.buffer());
    assertEquals(RECORD_VERSION, result.recordVersion());
    assertEquals(RECORD_TYPE, result.recordType());
    // Verify exactly 4 calls: 3 failures + 1 success
    verify(storage, times(4)).readRecord(any(), any());
  }

  /**
   * Verifies that readRecordWithRetry does NOT catch non-OptimisticReadFailedException
   * exceptions — they propagate immediately to the caller without retry. This ensures
   * the retry loop only handles the specific stamp-invalidation case, not general
   * storage errors.
   */
  @Test(expected = RecordNotFoundException.class)
  public void testNonOptimisticExceptionPropagatesWithoutRetry() {
    var storage = mock(Storage.class);
    var rid = new RecordId(1, 0);
    when(storage.readRecord(any(), any()))
        .thenThrow(new RecordNotFoundException("test-db", rid));

    DatabaseCompare.readRecordWithRetry(storage, rid, null);
  }
}
