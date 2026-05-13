package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import org.junit.Test;

/**
 * Standalone round-trip tests for active WAL record types — the records actually wired
 * into the {@code WALRecordsFactory} switch statement (IDs 0&ndash;18 in
 * {@link WALRecordTypes}).
 *
 * <p>For each record this exercises:
 * <ul>
 *   <li>{@code toStream(byte[], int)} / {@code fromStream(byte[], int)} round-trip
 *       returns the same {@code endOffset} as the original write,
 *   <li>at least one specific getter on the deserialized instance returns the value
 *       passed in via the constructor (falsifiability rule — equality alone could pass
 *       on an aliasing bug), and
 *   <li>{@code getId()} returns the expected {@link WALRecordTypes} constant.
 * </ul>
 *
 * <p>Records with their own dedicated test class (e.g. {@code AtomicUnitEndDBRecordTest}
 * for {@link AtomicUnitEndRecord}, {@code WALRecordsFactoryPageOperationTest} for
 * {@code TestPageOperation}) are not duplicated here.
 */
public class ActiveWALRecordsRoundTripTest {

  /**
   * Allocate a serialization buffer one byte larger than the record's serialized size
   * so we can verify that {@code toStream} writes exactly {@code serializedSize()}
   * bytes starting at offset 1 — catching off-by-one errors in {@code serializedSize}
   * that would otherwise be invisible because the buffer is exactly sized.
   */
  private static byte[] allocateBuffer(int serializedSize) {
    return new byte[serializedSize + 1];
  }

  /**
   * {@link AtomicUnitStartRecord} carries an {@code operationUnitId} and a single
   * {@code isRollbackSupported} flag. Round-trip with the flag set both ways covers
   * the encode-as-1 / encode-as-0 branches in {@code serializeToByteBuffer}.
   */
  @Test
  public void atomicUnitStartRecordRoundTripWithRollbackSupported() {
    var original = new AtomicUnitStartRecord(true, 4242L);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new AtomicUnitStartRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    // Falsifiability: pin the operationUnitId and the flag explicitly via getters.
    assertEquals(4242L, restored.getOperationUnitId());
    assertTrue(restored.isRollbackSupported());
    assertEquals(WALRecordTypes.ATOMIC_UNIT_START_RECORD, restored.getId());
  }

  /**
   * Same as above but with the rollback flag cleared — covers the {@code byte 0}
   * branch in {@code serializeToByteBuffer} and the {@code <= 0} branch in
   * {@code deserializeFromByteBuffer}.
   */
  @Test
  public void atomicUnitStartRecordRoundTripWithRollbackUnsupported() {
    var original = new AtomicUnitStartRecord(false, 1L);
    var buffer = allocateBuffer(original.serializedSize());

    original.toStream(buffer, 1);
    var restored = new AtomicUnitStartRecord();
    restored.fromStream(buffer, 1);

    assertEquals(1L, restored.getOperationUnitId());
    assertFalse(restored.isRollbackSupported());
  }

  /**
   * {@link AtomicUnitStartMetadataRecord} extends {@link AtomicUnitStartRecord} with a
   * variable-length metadata blob, length-prefixed. Use a non-empty payload so the
   * {@code buffer.put(metadata)} branch is exercised; the byte-by-byte equality check
   * pins the payload (falsifiability — a length-only round-trip would pass with empty
   * arrays).
   */
  @Test
  public void atomicUnitStartMetadataRecordRoundTripWithNonEmptyPayload() {
    var payload = new byte[] {0x01, 0x02, 0x03, 0x7F, (byte) 0xCA, (byte) 0xFE};
    var original = new AtomicUnitStartMetadataRecord(true, 99L, payload);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new AtomicUnitStartMetadataRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    assertEquals(99L, restored.getOperationUnitId());
    assertTrue(restored.isRollbackSupported());
    assertArrayEquals(payload, restored.getMetadata());
    assertNotEquals("payload reference must not be shared", payload, restored.getMetadata());
    assertEquals(WALRecordTypes.ATOMIC_UNIT_START_METADATA_RECORD, restored.getId());
  }

  /**
   * {@link UpdatePageRecord} carries pageIndex, fileId, operationUnitId, a
   * {@link WALPageChangesPortion} (must be non-null on serialize), and an initial LSN.
   * Round-trip the full struct; pin every primitive getter and the LSN equals so the
   * test fails on byte mis-ordering.
   */
  @Test
  public void updatePageRecordRoundTrip() {
    var initialLsn = new LogSequenceNumber(7L, 123);
    var original =
        new UpdatePageRecord(0xAABBCCL, 0x1122L, 0x77L, new WALPageChangesPortion(), initialLsn);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new UpdatePageRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    assertEquals(0xAABBCCL, restored.getPageIndex());
    assertEquals(0x1122L, restored.getFileId());
    assertEquals(0x77L, restored.getOperationUnitId());
    assertEquals(initialLsn, restored.getInitialLsn());
    assertEquals(7L, restored.getInitialLsn().getSegment());
    assertEquals(123, restored.getInitialLsn().getPosition());
    assertEquals(WALRecordTypes.UPDATE_PAGE_RECORD, restored.getId());
  }

  /**
   * {@link FileCreatedWALRecord} carries a fileName plus fileId. Use a non-trivial
   * Unicode string so a UTF-8 / UTF-16 mismatch would corrupt the round-trip.
   */
  @Test
  public void fileCreatedWALRecordRoundTrip() {
    var original = new FileCreatedWALRecord(101L, "users/dataü.dat", 0x4242_4242L);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new FileCreatedWALRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    assertEquals(101L, restored.getOperationUnitId());
    assertEquals("users/dataü.dat", restored.getFileName());
    assertEquals(0x4242_4242L, restored.getFileId());
    assertEquals(WALRecordTypes.FILE_CREATED_WAL_RECORD, restored.getId());
  }

  /**
   * {@link FileDeletedWALRecord} — minimal payload (operationUnitId + fileId). The test
   * pins both so a swapped-field bug fails immediately.
   */
  @Test
  public void fileDeletedWALRecordRoundTrip() {
    var original = new FileDeletedWALRecord(55L, 0xDEAD_BEEFL);
    var buffer = allocateBuffer(original.serializedSize());

    original.toStream(buffer, 1);
    var restored = new FileDeletedWALRecord();
    restored.fromStream(buffer, 1);

    assertEquals(55L, restored.getOperationUnitId());
    assertEquals(0xDEAD_BEEFL, restored.getFileId());
    assertEquals(WALRecordTypes.FILE_DELETED_WAL_RECORD, restored.getId());
  }

  /**
   * {@link FileTruncatedWALRecord} mirrors the deleted record but uses a different
   * record ID. Round-trip independently to defend against an accidental copy-paste
   * regression that aliased the two record types.
   */
  @Test
  public void fileTruncatedWALRecordRoundTrip() {
    var original = new FileTruncatedWALRecord(44L, 0xC0FFEEL);
    var buffer = allocateBuffer(original.serializedSize());

    original.toStream(buffer, 1);
    var restored = new FileTruncatedWALRecord();
    restored.fromStream(buffer, 1);

    assertEquals(44L, restored.getOperationUnitId());
    assertEquals(0xC0FFEEL, restored.getFileId());
    assertEquals(WALRecordTypes.FILE_TRUNCATED_WAL_RECORD, restored.getId());
  }

  /**
   * {@link HighLevelTransactionChangeRecord} carries an arbitrary opaque byte payload.
   * Use a payload with embedded zero bytes so a length-prefix bug that NUL-terminates
   * early would surface.
   */
  @Test
  public void highLevelTransactionChangeRecordRoundTripWithBinaryPayload() {
    var payload = new byte[] {0x00, 0x10, 0x00, 0x20, 0x7F, (byte) 0x80, 0x00};
    var original = new HighLevelTransactionChangeRecord(33L, payload);
    var buffer = allocateBuffer(original.serializedSize());

    original.toStream(buffer, 1);
    var restored = new HighLevelTransactionChangeRecord();
    restored.fromStream(buffer, 1);

    assertEquals(33L, restored.getOperationUnitId());
    assertArrayEquals(payload, restored.getData());
    assertEquals(WALRecordTypes.HIGH_LEVEL_TRANSACTION_CHANGE_RECORD, restored.getId());
  }

  /**
   * {@link MetaDataRecord} is a "TX_METADATA"-flavoured envelope holding an arbitrary
   * payload, length-prefixed. The class differs from
   * {@link HighLevelTransactionChangeRecord} in that it uses the byte-array
   * {@code toStream}/{@code fromStream} directly (no intermediate
   * {@code OperationUnitRecord} layer); test the byte-array path explicitly.
   */
  @Test
  public void metaDataRecordRoundTripPreservesPayload() {
    var payload = new byte[] {0x01, 0x02, 0x03, 0x04};
    var original = new MetaDataRecord(payload);

    // Allocate exactly serializedSize() bytes — MetaDataRecord uses the entire buffer.
    var buffer = new byte[original.serializedSize()];
    var endOffset = original.toStream(buffer, 0);
    // MetaDataRecord.toStream returns offset + content.length (a quirk vs. other
    // records); the contract for this test is that fromStream restores the payload.

    var restored = new MetaDataRecord();
    restored.fromStream(buffer, 0);
    assertArrayEquals(payload, restored.getMetadata());
    assertEquals(WALRecordTypes.TX_METADATA, restored.getId());
    // Sanity: write returned a non-zero offset.
    assertTrue("toStream must advance offset", endOffset > 0);
  }

  /**
   * {@link NonTxOperationPerformedWALRecord} has zero serialized payload — round-trip
   * leaves the offset unchanged. Pin the offset behaviour and the record-type id.
   */
  @Test
  public void nonTxOperationPerformedWALRecordHasZeroPayloadAndConstantId() {
    var rec = new NonTxOperationPerformedWALRecord();
    assertEquals(0, rec.serializedSize());

    var buffer = new byte[8];
    var afterWrite = rec.toStream(buffer, 5);
    assertEquals("zero-payload write must leave offset unchanged", 5, afterWrite);

    var restored = new NonTxOperationPerformedWALRecord();
    var afterRead = restored.fromStream(buffer, 5);
    assertEquals(5, afterRead);
    assertEquals(WALRecordTypes.NON_TX_OPERATION_PERFORMED_WAL_RECORD, restored.getId());
  }

  /**
   * {@link EmptyWALRecord} (in {@code wal.common}) is the empty-payload counterpart to
   * {@link NonTxOperationPerformedWALRecord}. Its dedicated id distinguishes it during
   * deserialization. Pin both behaviours.
   */
  @Test
  public void emptyWALRecordHasZeroPayloadAndDedicatedId() {
    var rec = new EmptyWALRecord();
    assertEquals(0, rec.serializedSize());

    var buffer = new byte[4];
    assertEquals(2, rec.toStream(buffer, 2));

    var restored = new EmptyWALRecord();
    assertEquals(2, restored.fromStream(buffer, 2));
    assertEquals(WALRecordTypes.EMPTY_WAL_RECORD, restored.getId());
  }

  /**
   * Verify that the LSN sentinel and the binary-content / disk-size / distance plumbing
   * on {@link AbstractWALRecord} round-trip through the setter / getter chain that
   * factories rely on. Initial-state checks pin the read-side branches that throw
   * {@link IllegalStateException} when the field is unset.
   */
  @Test
  public void abstractWalRecordPlumbingReadsAndWritesEachField() {
    var rec = new FileDeletedWALRecord(0L, 0L);

    assertNull(rec.getLsn());
    assertNull(rec.getBinaryContent());
    assertEquals(-1, rec.getBinaryContentLen());
    assertFalse(rec.isWritten());

    var lsn = new LogSequenceNumber(2L, 3);
    rec.setLsn(lsn);
    assertSame(lsn, rec.getLsn());

    var direct = java.nio.ByteBuffer.allocateDirect(7);
    direct.limit(7);
    rec.setBinaryContent(direct);
    assertSame(direct, rec.getBinaryContent());
    assertEquals(7, rec.getBinaryContentLen());

    rec.freeBinaryContent();
    assertNull(rec.getBinaryContent());

    rec.setDistance(11);
    rec.setDiskSize(13);
    assertEquals(11, rec.getDistance());
    assertEquals(13, rec.getDiskSize());

    rec.written();
    assertTrue(rec.isWritten());
  }

  /**
   * {@link AbstractWALRecord#getDistance()} throws {@link IllegalStateException} when
   * the distance is not set; pin the branch.
   */
  @Test(expected = IllegalStateException.class)
  public void getDistanceThrowsWhenUnset() {
    new FileDeletedWALRecord(0L, 0L).getDistance();
  }

  /**
   * {@link AbstractWALRecord#getDiskSize()} throws {@link IllegalStateException} when
   * the disk size is not set; pin the branch.
   */
  @Test(expected = IllegalStateException.class)
  public void getDiskSizeThrowsWhenUnset() {
    new FileDeletedWALRecord(0L, 0L).getDiskSize();
  }

  /**
   * Equals/hashCode on {@link OperationUnitRecord}: two records with the same
   * operationUnitId compare equal regardless of subclass-specific state at the
   * {@code OperationUnitRecord} level. Pin the contract — {@code AtomicUnitStartRecord}
   * leaves equals at the operationUnitId level, so two start records with the same id
   * but different rollback flags are still equal.
   */
  @Test
  public void operationUnitRecordEqualsTracksOperationUnitIdOnly() {
    var a = new AtomicUnitStartRecord(true, 42L);
    var b = new AtomicUnitStartRecord(false, 42L);
    var c = new AtomicUnitStartRecord(true, 43L);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  /**
   * {@link AtomicUnitStartMetadataRecord} with an empty payload — the canonical edge of the
   * length-prefixed serialization. A regression that uses {@code length > 0} instead of
   * {@code length >= 0} as the put-content guard, or drops the length prefix when the
   * array is empty, would silently pass the non-empty round-trip but fail this test.
   */
  @Test
  public void atomicUnitStartMetadataRecordRoundTripWithEmptyPayload() {
    var payload = new byte[0];
    var original = new AtomicUnitStartMetadataRecord(true, 17L, payload);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new AtomicUnitStartMetadataRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    assertEquals(17L, restored.getOperationUnitId());
    assertTrue(restored.isRollbackSupported());
    assertNotNull("restored metadata must not be null on empty-payload round-trip",
        restored.getMetadata());
    assertArrayEquals(new byte[0], restored.getMetadata());
    assertEquals(0, restored.getMetadata().length);
    // Falsifiability: the restored array must be a fresh allocation, not the input
    // reference. A regression that aliased the input would silently leak caller state.
    assertNotSame("restored metadata must not be the same array reference as the input",
        payload, restored.getMetadata());
  }

  /**
   * {@link MetaDataRecord} with an empty payload — same rationale as the
   * AtomicUnitStartMetadataRecord variant, but exercising the byte-array {@code toStream}/
   * {@code fromStream} path that {@code MetaDataRecord} uses directly without the
   * intermediate {@code OperationUnitRecord} layer.
   */
  @Test
  public void metaDataRecordRoundTripWithEmptyPayload() {
    var payload = new byte[0];
    var original = new MetaDataRecord(payload);

    var buffer = new byte[original.serializedSize()];
    var endOffset = original.toStream(buffer, 0);

    var restored = new MetaDataRecord();
    restored.fromStream(buffer, 0);

    assertNotNull("restored metadata must not be null on empty-payload round-trip",
        restored.getMetadata());
    assertArrayEquals(new byte[0], restored.getMetadata());
    assertEquals(0, restored.getMetadata().length);
    assertNotSame("restored metadata must not be the same array reference as the input",
        payload, restored.getMetadata());
    // Sanity: write returned a non-zero offset (the length prefix was still written).
    assertTrue("toStream must advance offset for the length prefix even on empty payload",
        endOffset > 0);
  }

  /**
   * {@link HighLevelTransactionChangeRecord} with an empty payload — the canonical
   * edge of its length-prefixed encoding. A regression that drops the length prefix when
   * the byte array is empty, or uses a strict {@code > 0} guard, would silently pass.
   */
  @Test
  public void highLevelTransactionChangeRecordRoundTripWithEmptyPayload() {
    var payload = new byte[0];
    var original = new HighLevelTransactionChangeRecord(11L, payload);
    var buffer = allocateBuffer(original.serializedSize());

    var endOffset = original.toStream(buffer, 1);
    assertEquals(buffer.length, endOffset);

    var restored = new HighLevelTransactionChangeRecord();
    var dEnd = restored.fromStream(buffer, 1);
    assertEquals(buffer.length, dEnd);

    assertEquals(11L, restored.getOperationUnitId());
    assertNotNull("restored data must not be null on empty-payload round-trip",
        restored.getData());
    assertArrayEquals(new byte[0], restored.getData());
    assertEquals(0, restored.getData().length);
    assertNotSame("restored data must not be the same array reference as the input",
        payload, restored.getData());
  }

  /**
   * {@link AbstractPageWALRecord#equals(Object)} layers pageIndex / fileId on top of
   * the {@code OperationUnitRecord} equality. Two records with the same operationUnitId
   * but different pageIndex must NOT compare equal — pin the discriminator.
   */
  @Test
  public void abstractPageWalRecordEqualsDiscriminatesOnPageIndexAndFileId() {
    var a =
        new UpdatePageRecord(1L, 2L, 3L, new WALPageChangesPortion(), new LogSequenceNumber(0, 0));
    var samePageDifferentFileId =
        new UpdatePageRecord(1L, 99L, 3L, new WALPageChangesPortion(), new LogSequenceNumber(0, 0));
    var differentPageSameFileId =
        new UpdatePageRecord(99L, 2L, 3L, new WALPageChangesPortion(), new LogSequenceNumber(0, 0));

    assertNotEquals(a, samePageDifferentFileId);
    assertNotEquals(a, differentPageSameFileId);
  }
}
