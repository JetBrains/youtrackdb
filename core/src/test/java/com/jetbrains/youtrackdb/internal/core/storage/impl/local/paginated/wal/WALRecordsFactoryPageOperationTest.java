package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link PageOperation} subclasses survive the full {@link WALRecordsFactory}
 * serialization/deserialization pipeline, including record ID header, LZ4 compression
 * handling, and reflection-based instantiation via {@code registerNewRecord()}.
 */
public class WALRecordsFactoryPageOperationTest {

  @Before
  public void registerTestRecord() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        TestPageOperation.TEST_RECORD_ID, TestPageOperation.class);
  }

  /**
   * Full factory roundtrip: toStream serializes the record (including the record ID header),
   * fromStream deserializes it back. Verifies all fields — operationUnitId, pageIndex, fileId,
   * initialLsn, and the subclass-specific testValue — survive intact.
   */
  @Test
  public void testFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new TestPageOperation(10, 20, 30, initialLsn, 99);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    WriteableWALRecord deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(
        "Expected TestPageOperation but got " + deserialized.getClass().getName(),
        deserialized instanceof TestPageOperation);
    var result = (TestPageOperation) deserialized;

    Assert.assertEquals(original.getOperationUnitId(), result.getOperationUnitId());
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(original.getTestValue(), result.getTestValue());
    Assert.assertEquals(TestPageOperation.TEST_RECORD_ID, result.getId());
  }

  /**
   * Verifies that the factory correctly validates the record ID matches after deserialization.
   * The deserialized record's getId() must return the same ID used for serialization.
   */
  @Test
  public void testFactoryRecordIdValidation() {
    var original = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 0);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    // Factory checks walRecord.getId() == recordId — should not throw
    WriteableWALRecord result = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(TestPageOperation.TEST_RECORD_ID, result.getId());
  }

  /**
   * Verifies that attempting to deserialize an unregistered record ID throws
   * IllegalStateException — the expected behavior for unknown record types.
   */
  @Test(expected = IllegalStateException.class)
  public void testUnregisteredRecordIdThrows() {
    var original = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 0);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    // Tamper with the record ID to an unregistered value (999)
    content[0] = (byte) (999 & 0xFF);
    content[1] = (byte) ((999 >> 8) & 0xFF);

    // Use a fresh factory without registration — the default factory has our
    // test record registered. Instead, we tamper the ID to one that's unregistered.
    WALRecordsFactory.INSTANCE.fromStream(content);
  }

  /**
   * Verifies that different field values produce distinct serialized forms and deserialize
   * correctly — ensures the factory roundtrip is not accidentally returning cached instances.
   */
  @Test
  public void testFactoryRoundtripDistinctValues() {
    var op1 = new TestPageOperation(1, 2, 3, new LogSequenceNumber(10, 20), 100);
    var op2 = new TestPageOperation(4, 5, 6, new LogSequenceNumber(30, 40), 200);

    ByteBuffer buf1 = WALRecordsFactory.toStream(op1);
    ByteBuffer buf2 = WALRecordsFactory.toStream(op2);

    var content1 = new byte[buf1.limit()];
    buf1.get(0, content1);
    var content2 = new byte[buf2.limit()];
    buf2.get(0, content2);

    var result1 = (TestPageOperation) WALRecordsFactory.INSTANCE.fromStream(content1);
    var result2 = (TestPageOperation) WALRecordsFactory.INSTANCE.fromStream(content2);

    Assert.assertEquals(100, result1.getTestValue());
    Assert.assertEquals(200, result2.getTestValue());
    Assert.assertEquals(1, result1.getPageIndex());
    Assert.assertEquals(4, result2.getPageIndex());
  }

  /**
   * Verifies that the redo() method on a deserialized record is callable (it's a no-op for
   * the test subclass, but the contract must be fulfilled).
   */
  @Test
  public void testDeserializedRedoCallable() {
    var original = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 42);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = (TestPageOperation) WALRecordsFactory.INSTANCE.fromStream(content);
    // redo(null) should not throw — it's a no-op in the test subclass
    result.redo(null);
  }

  /**
   * Verifies that tombstoned old page operation IDs (35-198) still throw
   * IllegalStateException during deserialization, ensuring backward compatibility
   * protection is not broken by the new registration mechanism.
   */
  @Test(expected = IllegalStateException.class)
  public void testTombstonedOldPageOperationIdThrows() {
    // Construct a minimal valid byte array with record ID = 42 (COLLECTION_PAGE_INIT_PO)
    // The factory should reject it via the tombstone branch
    var content = new byte[100];
    content[0] = 42;
    content[1] = 0;
    WALRecordsFactory.INSTANCE.fromStream(content);
  }
}
