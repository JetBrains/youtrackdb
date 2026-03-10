package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the GC helper methods on {@link CollectionPage}:
 * {@link CollectionPage#isFirstRecordChunk(int)},
 * {@link CollectionPage#readCollectionPositionFromRecord(int)}, and
 * {@link CollectionPage#getNextPagePointer(int)}.
 *
 * <p>These methods are used by the records GC to identify stale record versions on collection
 * pages and follow multi-chunk chains. The tests verify correct parsing of the on-page record
 * format for both entry-point (start) chunks and continuation chunks.
 */
public class CollectionPageGcHelperTest {

  private ByteBufferPool bufferPool;
  private CachePointer cachePointer;
  private CacheEntry cacheEntry;
  private CollectionPage page;

  @Before
  public void setUp() {
    bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, /* readCache= */ null);
    cacheEntry.acquireExclusiveLock();

    page = new CollectionPage(cacheEntry);
    page.init();
  }

  @After
  public void tearDown() {
    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  // ---------------------------------------------------------------------------
  // isFirstRecordChunk
  // ---------------------------------------------------------------------------

  // Verifies that a record with firstRecordFlag=1 is identified as a start chunk.
  @Test
  public void isFirstRecordChunkReturnsTrueForStartChunk() {
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, 42L, new byte[0], -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.isFirstRecordChunk(pos)).isTrue();
  }

  // Verifies that a record with firstRecordFlag=0 is identified as a continuation chunk.
  @Test
  public void isFirstRecordChunkReturnsFalseForContinuationChunk() {
    var recordBytes = buildContinuationChunkBytes(new byte[] {1, 2, 3}, -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.isFirstRecordChunk(pos)).isFalse();
  }

  // Verifies isFirstRecordChunk for a start chunk with non-empty content payload.
  @Test
  public void isFirstRecordChunkWithLargePayload() {
    var content = new byte[200];
    content[0] = (byte) 0xAB;
    content[199] = (byte) 0xCD;
    var recordBytes = buildStartChunkBytes(
        (byte) 1, content.length, 100L, content, -1L);
    int pos = page.appendRecord(5L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.isFirstRecordChunk(pos)).isTrue();
  }

  // ---------------------------------------------------------------------------
  // readCollectionPositionFromRecord
  // ---------------------------------------------------------------------------

  // Verifies that the collection position is correctly read from a start chunk.
  @Test
  public void readCollectionPositionFromStartChunk() {
    long expectedCollPos = 12345L;
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, expectedCollPos, new byte[0], -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.readCollectionPositionFromRecord(pos)).isEqualTo(expectedCollPos);
  }

  // Verifies collection position read with a large collection position value.
  @Test
  public void readCollectionPositionLargeValue() {
    long expectedCollPos = Long.MAX_VALUE - 1;
    var recordBytes = buildStartChunkBytes(
        (byte) 2, 10, expectedCollPos, new byte[10], -1L);
    int pos = page.appendRecord(3L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.readCollectionPositionFromRecord(pos)).isEqualTo(expectedCollPos);
  }

  // Verifies collection position read when the position is zero.
  @Test
  public void readCollectionPositionZero() {
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 5, 0L, new byte[5], -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.readCollectionPositionFromRecord(pos)).isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // getNextPagePointer
  // ---------------------------------------------------------------------------

  // Verifies that the next-page pointer is -1 for a last (or only) chunk.
  @Test
  public void getNextPagePointerReturnsMinusOneForLastChunk() {
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, 1L, new byte[0], -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getNextPagePointer(pos)).isEqualTo(-1L);
  }

  // Verifies that a non-negative next-page pointer is correctly read.
  @Test
  public void getNextPagePointerReadsPackedPointer() {
    long packedPointer = 0x0000_0005_0000_0003L;
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, 1L, new byte[0], packedPointer);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getNextPagePointer(pos)).isEqualTo(packedPointer);
  }

  // Verifies next-page pointer on a continuation chunk.
  @Test
  public void getNextPagePointerOnContinuationChunk() {
    long packedPointer = 0x0000_000A_0000_0002L;
    var recordBytes = buildContinuationChunkBytes(new byte[] {10, 20}, packedPointer);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getNextPagePointer(pos)).isEqualTo(packedPointer);
  }

  // Verifies next-page pointer is -1 for a continuation chunk that is the last in chain.
  @Test
  public void getNextPagePointerOnLastContinuationChunk() {
    var recordBytes = buildContinuationChunkBytes(new byte[] {5}, -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getNextPagePointer(pos)).isEqualTo(-1L);
  }

  // Verifies that a next-page pointer of 0L (page 0, position 0) is correctly read
  // and not confused with a sentinel. 0L is a valid packed pointer.
  @Test
  public void getNextPagePointerZeroIsValidPointer() {
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, 1L, new byte[0], 0L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getNextPagePointer(pos)).isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // readCollectionPositionFromRecord precondition
  // ---------------------------------------------------------------------------

  // Verifies that readCollectionPositionFromRecord's assertion rejects continuation
  // chunks. The method must only be called on entry-point chunks; calling it on a
  // continuation chunk is a programming error.
  // Requires -ea (assertions enabled); configured in core/pom.xml <argLine>.
  @Test(expected = AssertionError.class)
  public void readCollectionPositionRejectsContChunkWithAssertion() {
    var recordBytes = buildContinuationChunkBytes(new byte[] {1, 2, 3}, -1L);
    int pos = page.appendRecord(1L, recordBytes, -1, IntSets.emptySet());

    // Should throw AssertionError because the record is not a start chunk.
    page.readCollectionPositionFromRecord(pos);
  }

  // ---------------------------------------------------------------------------
  // Combined: multiple records on the same page
  // ---------------------------------------------------------------------------

  // Verifies that multiple records (start + continuation) can coexist on the same
  // page and each returns correct values from the GC helpers.
  @Test
  public void multipleRecordsOnSamePageIdentifiedCorrectly() {
    long collPos1 = 100L;
    long collPos2 = 200L;

    var startChunk1 = buildStartChunkBytes(
        (byte) 0, 0, collPos1, new byte[0], -1L);
    var contChunk = buildContinuationChunkBytes(new byte[] {1, 2, 3, 4}, -1L);
    var startChunk2 = buildStartChunkBytes(
        (byte) 1, 3, collPos2, new byte[3], -1L);

    int pos0 = page.appendRecord(10L, startChunk1, -1, IntSets.emptySet());
    int pos1 = page.appendRecord(20L, contChunk, -1, IntSets.emptySet());
    int pos2 = page.appendRecord(30L, startChunk2, -1, IntSets.emptySet());

    assertThat(page.isFirstRecordChunk(pos0)).isTrue();
    assertThat(page.isFirstRecordChunk(pos1)).isFalse();
    assertThat(page.isFirstRecordChunk(pos2)).isTrue();

    assertThat(page.readCollectionPositionFromRecord(pos0)).isEqualTo(collPos1);
    assertThat(page.readCollectionPositionFromRecord(pos2)).isEqualTo(collPos2);

    assertThat(page.getNextPagePointer(pos0)).isEqualTo(-1L);
    assertThat(page.getNextPagePointer(pos1)).isEqualTo(-1L);
    assertThat(page.getNextPagePointer(pos2)).isEqualTo(-1L);
  }

  // Verifies that after deleting a record, the remaining records are still readable.
  @Test
  public void gcHelpersWorkAfterRecordDeletion() {
    long collPos = 999L;
    var startChunk = buildStartChunkBytes(
        (byte) 0, 0, collPos, new byte[0], -1L);
    var otherRecord = buildStartChunkBytes(
        (byte) 0, 0, 1L, new byte[0], -1L);

    int pos0 = page.appendRecord(1L, otherRecord, -1, IntSets.emptySet());
    int pos1 = page.appendRecord(2L, startChunk, -1, IntSets.emptySet());

    // Delete the first record
    page.deleteRecord(pos0, true);
    assertThat(page.isDeleted(pos0)).isTrue();

    // The second record is still readable
    assertThat(page.isFirstRecordChunk(pos1)).isTrue();
    assertThat(page.readCollectionPositionFromRecord(pos1)).isEqualTo(collPos);
    assertThat(page.getNextPagePointer(pos1)).isEqualTo(-1L);
  }

  // ---------------------------------------------------------------------------
  // Record version interaction
  // ---------------------------------------------------------------------------

  // Verifies that getRecordVersion returns the version passed to appendRecord,
  // and returns -1 for deleted records. This is used by the GC to skip deleted slots.
  @Test
  public void recordVersionAndDeletion() {
    var recordBytes = buildStartChunkBytes(
        (byte) 0, 0, 1L, new byte[0], -1L);
    int pos = page.appendRecord(42L, recordBytes, -1, IntSets.emptySet());

    assertThat(page.getRecordVersion(pos)).isEqualTo(42L);
    assertThat(page.isDeleted(pos)).isFalse();

    page.deleteRecord(pos, true);
    assertThat(page.getRecordVersion(pos)).isEqualTo(-1);
    assertThat(page.isDeleted(pos)).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Test helpers: build chunk byte arrays
  // ---------------------------------------------------------------------------

  /**
   * Builds a start chunk byte array matching the on-page format:
   * <pre>{@code
   * [recordType: 1B][contentSize: 4B][collectionPosition: 8B]
   * [content ...][firstRecordFlag: 1B][nextPagePointer: 8B]
   * }</pre>
   */
  private static byte[] buildStartChunkBytes(
      byte recordType,
      int contentSize,
      long collectionPosition,
      byte[] content,
      long nextPagePointer) {
    // metadata header: recordType(1) + contentSize(4) + collectionPosition(8) = 13
    // trailer: firstRecordFlag(1) + nextPagePointer(8) = 9
    int totalSize = ByteSerializer.BYTE_SIZE
        + IntegerSerializer.INT_SIZE
        + LongSerializer.LONG_SIZE
        + content.length
        + ByteSerializer.BYTE_SIZE
        + LongSerializer.LONG_SIZE;

    var buf = ByteBuffer.allocate(totalSize).order(ByteOrder.nativeOrder());
    buf.put(recordType);
    buf.putInt(contentSize);
    buf.putLong(collectionPosition);
    buf.put(content);
    buf.put((byte) 1); // firstRecordFlag = 1 (start chunk)
    buf.putLong(nextPagePointer);
    return buf.array();
  }

  /**
   * Builds a continuation chunk byte array matching the on-page format:
   * <pre>{@code
   * [chunk data ...][firstRecordFlag: 0B][nextPagePointer: 8B]
   * }</pre>
   */
  private static byte[] buildContinuationChunkBytes(
      byte[] chunkData,
      long nextPagePointer) {
    int totalSize = chunkData.length
        + ByteSerializer.BYTE_SIZE
        + LongSerializer.LONG_SIZE;

    var buf = ByteBuffer.allocate(totalSize).order(ByteOrder.nativeOrder());
    buf.put(chunkData);
    buf.put((byte) 0); // firstRecordFlag = 0 (continuation chunk)
    buf.putLong(nextPagePointer);
    return buf.array();
  }
}
